package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataExportListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataExportOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http.ByteRange
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http.ContentDispositionFileName
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http.RangeHeader
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDataExportDtoMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.ReauthenticationHeader
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization.Base64Json
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.OpenedExport
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportDeleter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportDownloader
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportGetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportRequester
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/**
 * `/api/v1/me/exports`: request, track, download and destroy the caller's own user data export
 * archives (spec `docs/specs/2026-07-22-user-data-export.md` §7). All endpoints are owner-scoped
 * through the use cases they delegate to (`403` for a non-owner, `404` for an unknown id).
 */
@Path("/api/v1/me/exports")
@Authenticated
class MeExportController(
    private val requester: UserDataExportRequester,
    private val getter: UserDataExportGetter,
    private val downloader: UserDataExportDownloader,
    private val deleter: UserDataExportDeleter,
    private val securityIdentity: SecurityIdentity,
) {
    @POST
    fun requestExport(
        @HeaderParam(ReauthenticationHeader.HEADER) reauthHeader: String?,
    ): RestResponse<UserDataExportOutputDto> {
        val user = securityIdentity.getUser()
        val factor = ReauthenticationHeader.parsePasswordFactor(reauthHeader)
        val export = requester.request(user, factor)
        return ResponseBuilder.create(RestResponse.Status.ACCEPTED, export.toDto()).build()
    }

    @GET
    fun listExports(
        @QueryParam("cursor") @Base64Json cursorInput: CursorDto? = null,
        @QueryParam("pageSize") pageSizeInput: Int? = null,
    ): RestResponse<UserDataExportListOutputDto> {
        val user = securityIdentity.getUser()
        val pageSize = pageSizeInput ?: DEFAULT_PAGE_SIZE
        val cursor = cursorInput?.toDomain()
        return RestResponse.ok(getter.list(user, cursor, pageSize).toDto())
    }

    @GET
    @Path("/{id}")
    fun getExport(id: UUID): RestResponse<UserDataExportOutputDto> {
        val user = securityIdentity.getUser()
        return RestResponse.ok(getter.get(user, id).toDto())
    }

    @GET
    @Path("/{id}/download")
    fun downloadExport(id: UUID, @HeaderParam("Range") rangeHeader: String?): RestResponse<StreamingOutput> {
        val user = securityIdentity.getUser()
        // Opened at 0 always: the size needed to parse the Range header is only known once the
        // export row is read, and this use case is the single validated source for it (spec §5).
        val opened = downloader.open(user, id, 0)
        val range = RangeHeader.parse(rangeHeader, opened.totalByteSize)
        if (range != null) opened.stream.skipNBytes(range.start)
        val sliceLength = range?.let { it.endInclusive - it.start + 1 } ?: opened.totalByteSize
        val contentDisposition = contentDispositionHeader(opened, user)
        val streamingOutput = StreamingOutput { output -> opened.stream.use { copyBounded(it, output, sliceLength) } }
        return downloadResponse(opened, range, sliceLength, contentDisposition, streamingOutput)
    }

    @DELETE
    @Path("/{id}")
    fun deleteExport(id: UUID): RestResponse<Void> {
        val user = securityIdentity.getUser()
        deleter.delete(user, id)
        return RestResponse.noContent()
    }

    @Suppress("LongParameterList")
    private fun downloadResponse(
        opened: OpenedExport,
        range: ByteRange?,
        sliceLength: Long,
        contentDisposition: String,
        streamingOutput: StreamingOutput,
    ): RestResponse<StreamingOutput> {
        val status = if (range != null) RestResponse.Status.PARTIAL_CONTENT else RestResponse.Status.OK
        val builder = ResponseBuilder.create(status, streamingOutput)
            .header("Content-Type", opened.mediaType)
            .header("Content-Length", sliceLength)
            .header("ETag", "\"${opened.sha256}\"")
            .header("Accept-Ranges", "bytes")
            .header("Content-Disposition", contentDisposition)
        if (range != null) {
            builder.header("Content-Range", "bytes ${range.start}-${range.endInclusive}/${opened.totalByteSize}")
        }
        return builder.build()
    }

    private fun contentDispositionHeader(opened: OpenedExport, user: User): String {
        val extension = opened.fileExtension
        val rawName = "${isoDate(opened.completedAt)}-pinry-export-${user.name}.$extension"
        val fallback = "$FALLBACK_FILE_STEM.$extension"
        return ContentDispositionFileName.headerValue(rawName, fallback)
    }

    private fun isoDate(instant: Instant): String = instant.toString().take(ISO_DATE_LENGTH)

    /**
     * Copies exactly [byteCount] bytes from [input] to [output]. Deliberately NOT `copyTo`, which
     * streams to end-of-file: that would contradict an announced `Content-Length` on a range slice.
     * Stops early on end-of-stream instead of looping forever, even though that should not happen
     * in practice (the announced size always comes from the same row as the bytes on disk).
     */
    private fun copyBounded(input: InputStream, output: OutputStream, byteCount: Long) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = byteCount
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        private const val ISO_DATE_LENGTH = 10
        private const val COPY_BUFFER_SIZE = 8192
        private const val FALLBACK_FILE_STEM = "export"
    }
}
