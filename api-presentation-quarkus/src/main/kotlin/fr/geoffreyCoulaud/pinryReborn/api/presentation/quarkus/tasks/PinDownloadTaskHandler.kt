package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import java.util.UUID

class PinDownloadTaskHandler(
    private val downloadPinImage: DownloadPinImage,
    private val maxBytes: Long,
    private val maxPixels: Long,
) : TaskHandler {
    override val kind = PinDownloadTask.KIND

    override fun handle(payload: String, context: TaskContext) {
        downloadPinImage.download(
            pinId = UUID.fromString(payload),
            context = context,
            maxBytes = maxBytes,
            maxPixels = maxPixels,
        )
    }
}
