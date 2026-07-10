package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus

enum class PinImageStatus { NONE, PENDING, READY, FAILED }

data class PinImageReplacement(val status: DownloadStatus, val reasonCode: DownloadReason?)

data class PinImageState(
    val status: PinImageStatus,
    val image: Image?,
    val reasonCode: DownloadReason?,
    val replacement: PinImageReplacement?,
) {
    companion object {
        fun derive(image: Image?, download: ImageDownload?): PinImageState =
            if (image != null) {
                val replacement = download?.let { PinImageReplacement(it.status, it.reasonCode) }
                PinImageState(PinImageStatus.READY, image, null, replacement)
            } else if (download == null) {
                PinImageState(PinImageStatus.NONE, null, null, null)
            } else if (download.status == DownloadStatus.PENDING) {
                PinImageState(PinImageStatus.PENDING, null, null, null)
            } else {
                PinImageState(PinImageStatus.FAILED, null, download.reasonCode, null)
            }
    }
}
