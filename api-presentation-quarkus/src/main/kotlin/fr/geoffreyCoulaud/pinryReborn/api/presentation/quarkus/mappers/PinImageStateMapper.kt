package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinImageStateDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinImageStateDto.ReplacementDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageReplacement
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinImageState
import java.util.UUID

object PinImageStateMapper {
    fun PinImageState.toDto(baseUrl: String, pinId: UUID): PinImageStateDto {
        val img = image
        return PinImageStateDto(
            status = status.name,
            url = img?.let { "$baseUrl/api/v1/pins/$pinId/image" },
            mimeType = img?.mimeType,
            width = img?.width,
            height = img?.height,
            byteSize = img?.byteSize,
            reasonCode = reasonCode?.name,
            message = reasonCode?.let { messageFor(it) },
            replacement = replacement?.toDto(),
        )
    }

    private fun PinImageReplacement.toDto() =
        ReplacementDto(
            status = status.name,
            reasonCode = reasonCode?.name,
            message = reasonCode?.let { messageFor(it) },
        )

    private fun messageFor(reason: DownloadReason): String =
        when (reason) {
            DownloadReason.URL_NOT_ALLOWED -> "This URL is not allowed."
            DownloadReason.UNREACHABLE -> "The server could not reach this URL."
            DownloadReason.ACCESS_DENIED -> "The site refused the server access. Upload the image directly."
            DownloadReason.NOT_FOUND -> "No image at this URL."
            DownloadReason.TOO_LARGE -> "Image too large."
            DownloadReason.INVALID_IMAGE -> "The content is not a supported image."
            DownloadReason.TOO_MANY_PIXELS -> "Dimensions too large."
            DownloadReason.INTERNAL_ERROR -> "Temporary error, try again later."
            DownloadReason.FETCH_FAILED -> "The download failed."
        }
}
