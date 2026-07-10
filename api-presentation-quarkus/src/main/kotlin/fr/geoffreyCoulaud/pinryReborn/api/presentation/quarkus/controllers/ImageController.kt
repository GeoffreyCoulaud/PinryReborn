package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ApiConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinImageDownloadInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ImageOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinImageStateDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.ImageMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.PinImageStateMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DeletePinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.GetPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageStatus
import fr.geoffreyCoulaud.pinryReborn.api.usecases.RequestPinImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ResolvePinImageState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SetPinImage
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.nio.file.Files
import java.util.UUID

@Path("/api/v1/pins")
@Authenticated
class ImageController(
    private val setPinImage: SetPinImage,
    private val getPinImage: GetPinImage,
    private val deletePinImage: DeletePinImage,
    private val requestPinImageDownload: RequestPinImageDownload,
    private val resolvePinImageState: ResolvePinImageState,
    private val imageStore: ImageStore,
    private val imagesConfig: ImagesConfig,
    private val securityIdentity: SecurityIdentity,
    private val apiConfig: ApiConfig,
) {
    @PUT
    @Path("/{pinId}/image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun setImage(pinId: UUID, @RestForm("file") file: FileUpload): RestResponse<ImageOutputDto> {
        val requester = securityIdentity.getUser()
        val result = Files.newInputStream(file.uploadedFile()).use { upload ->
            setPinImage.set(
                pinId = pinId,
                requester = requester,
                upload = upload,
                maxBytes = imagesConfig.maxFileBytes(),
                maxPixels = imagesConfig.maxPixels(),
            )
        }
        val dto = result.image.toDto(baseUrl())
        val status = if (result.replaced) RestResponse.Status.OK else RestResponse.Status.CREATED
        return ResponseBuilder.create(status, dto).build()
    }

    @GET
    @Path("/{pinId}/image")
    fun getImage(pinId: UUID, @HeaderParam("If-None-Match") ifNoneMatch: String?): RestResponse<StreamingOutput> {
        val requester = securityIdentity.getUser()
        val image = getPinImage.get(pinId = pinId, requester = requester)
        // Kotlin's `==` is null-safe (delegates to `equals`), so a null `ifNoneMatch` simply
        // compares unequal to `image.contentHash` without a separate null check/branch.
        if (ifNoneMatch == image.contentHash) {
            return RestResponse.notModified()
        }
        val streamingOutput = StreamingOutput { output ->
            imageStore.openStream(image.storageKey).use { it.copyTo(output) }
        }
        return ResponseBuilder.ok(streamingOutput)
            .header("Content-Type", image.mimeType)
            .header("ETag", image.contentHash)
            .header("Cache-Control", "private, must-revalidate")
            .header("Content-Length", image.byteSize)
            .build()
    }

    @DELETE
    @Path("/{pinId}/image")
    fun deleteImage(pinId: UUID): RestResponse<Void> {
        val requester = securityIdentity.getUser()
        deletePinImage.delete(pinId = pinId, requester = requester)
        return RestResponse.noContent()
    }

    @PUT
    @Path("/{pinId}/image")
    @Consumes(MediaType.APPLICATION_JSON)
    fun requestImageDownload(pinId: UUID, @Valid body: PinImageDownloadInputDto): RestResponse<PinImageStateDto> {
        val requester = securityIdentity.getUser()
        val download = requestPinImageDownload.request(pinId, requester, body.sourceUrl)
        val dto = PinImageState(PinImageStatus.PENDING, null, null, null).toDto(baseUrl(), pinId)
        return ResponseBuilder.create<PinImageStateDto>(RestResponse.Status.ACCEPTED, dto)
            .header(HttpHeaders.LOCATION, "${baseUrl()}/api/v1/pins/$pinId/image/status")
            .build()
    }

    @GET
    @Path("/{pinId}/image/status")
    fun getImageStatus(pinId: UUID): RestResponse<PinImageStateDto> {
        val requester = securityIdentity.getUser()
        val state = resolvePinImageState.resolve(pinId = pinId, requester = requester)
        return RestResponse.ok(state.toDto(baseUrl(), pinId))
    }

    private fun baseUrl(): String = apiConfig.baseUrl()
}
