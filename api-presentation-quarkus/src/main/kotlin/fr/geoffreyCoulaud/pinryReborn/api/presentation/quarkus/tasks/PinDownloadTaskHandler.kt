package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PinDownloadTaskHandler(
    private val downloadPinImage: DownloadPinImage,
    private val imagesConfig: ImagesConfig,
) : TaskHandler {
    override val kind = PinDownloadTask.KIND

    override fun handle(payload: String, context: TaskContext) {
        downloadPinImage.download(
            pinId = UUID.fromString(payload),
            context = context,
            maxBytes = imagesConfig.maxFileBytes(),
            maxPixels = imagesConfig.maxPixels(),
        )
    }
}
