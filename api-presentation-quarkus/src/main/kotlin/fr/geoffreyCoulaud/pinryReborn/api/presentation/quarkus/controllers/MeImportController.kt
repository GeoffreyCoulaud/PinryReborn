package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataImportIssueListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataImportListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataImportOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDataImportDtoMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDataImportIssueDtoMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization.Base64Json
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportArchiveCompleter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportCanceller
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportChunkReceiver
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportGetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportIssueLister
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.common.annotation.Blocking
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.io.InputStream
import java.util.UUID

/**
 * `/api/v1/me/imports`: open an import, upload its archive in chunks, track it and cancel it (spec
 * `docs/specs/2026-08-14-user-data-import.md` §7). Owner scoped by the use cases it delegates to.
 */
@Path("/api/v1/me/imports")
@Authenticated
@Suppress("LongParameterList") // CDI-injected: every parameter is a collaborator provided by the container.
class MeImportController(
    private val creator: UserDataImportCreator,
    private val chunkReceiver: UserDataImportChunkReceiver,
    private val archiveCompleter: UserDataImportArchiveCompleter,
    private val getter: UserDataImportGetter,
    private val issueLister: UserDataImportIssueLister,
    private val canceller: UserDataImportCanceller,
    private val securityIdentity: SecurityIdentity,
) {
    @POST
    @Operation(
        summary = "Open an import and wait for its archive",
        description = IMPORT_IS_NOT_ATOMIC,
    )
    fun createImport(): RestResponse<UserDataImportOutputDto> {
        val user = securityIdentity.getUser()
        val userDataImport = creator.create(user)
        return ResponseBuilder.create(RestResponse.Status.ACCEPTED, userDataImport.toDto()).build()
    }

    /**
     * Blocking deliberately: Quarkus REST reads this body lazily only on a worker thread, and an
     * extension installing a global Vert.x body handler would buffer it whatever this says (spec §7).
     */
    @PUT
    @Path("/{id}/archive")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Blocking
    @Operation(
        summary = "Append one chunk of the archive at the given offset",
        description = "Answers the upload's new length. An offset that is not the current length is " +
            "refused with that length, so a client resumes rather than restarts.",
    )
    fun uploadChunk(
        id: UUID,
        @QueryParam("offset") offsetInput: Long? = null,
        body: InputStream,
    ): RestResponse<UserDataImportOutputDto> {
        val user = securityIdentity.getUser()
        // An absent offset means the start of the upload, and a client that got that wrong reads the
        // current length off the refusal rather than a second error vocabulary.
        val offset = offsetInput ?: 0
        val userDataImport = chunkReceiver.receive(user, id, offset, body)
        return RestResponse.ok(userDataImport.toDto())
    }

    @POST
    @Path("/{id}/archive/complete")
    @Operation(
        summary = "Close the upload and queue the import",
        description = IMPORT_IS_NOT_ATOMIC,
    )
    fun completeArchive(id: UUID): RestResponse<UserDataImportOutputDto> {
        val user = securityIdentity.getUser()
        val userDataImport = archiveCompleter.complete(user, id)
        return ResponseBuilder.create(RestResponse.Status.ACCEPTED, userDataImport.toDto()).build()
    }

    @GET
    fun listImports(
        @QueryParam("cursor") @Base64Json cursorInput: CursorDto? = null,
        @QueryParam("pageSize") pageSizeInput: Int? = null,
    ): RestResponse<UserDataImportListOutputDto> {
        val user = securityIdentity.getUser()
        val pageSize = pageSizeInput ?: DEFAULT_PAGE_SIZE
        val cursor = cursorInput?.toDomain()
        return RestResponse.ok(getter.list(user, cursor, pageSize).toDto())
    }

    @GET
    @Path("/{id}")
    fun getImport(id: UUID): RestResponse<UserDataImportOutputDto> {
        val user = securityIdentity.getUser()
        return RestResponse.ok(getter.get(user, id).toDto())
    }

    @GET
    @Path("/{id}/issues")
    @Operation(
        summary = "Read the import's report",
        description = "Anomalies the walk recorded. Past `imports.report_detail_limit` rows only the " +
            "count keeps growing, and the import says so through `issueDetailTruncated`.",
    )
    fun listIssues(
        id: UUID,
        @QueryParam("cursor") @Base64Json cursorInput: CursorDto? = null,
        @QueryParam("pageSize") pageSizeInput: Int? = null,
    ): RestResponse<UserDataImportIssueListOutputDto> {
        val user = securityIdentity.getUser()
        val pageSize = pageSizeInput ?: DEFAULT_PAGE_SIZE
        val cursor = cursorInput?.toDomain()
        return RestResponse.ok(issueLister.list(user, id, cursor, pageSize).toDto())
    }

    @DELETE
    @Path("/{id}")
    @Operation(
        summary = "Cancel an import",
        description = "Cancelling leaves partial state: the pins, boards and tags the import has " +
            "already created stay, and only the archive and the work still to do are dropped.",
    )
    fun cancelImport(id: UUID): RestResponse<Void> {
        val user = securityIdentity.getUser()
        canceller.cancel(user, id)
        return RestResponse.noContent()
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20

        // Spec section 14 promises this sentence to the API documentation, not only to itself.
        private const val IMPORT_IS_NOT_ATOMIC =
            "An import is not atomic: it creates rows as it walks the archive, and what it has " +
                "already created stays if it fails, is cancelled or is abandoned."
    }
}
