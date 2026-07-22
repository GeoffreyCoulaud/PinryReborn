package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.worker.PinDownloadTaskHandler
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * CDI wiring for [PinDownloadTaskHandler], hosted in the composition root because it needs the
 * `images.*` limits from [ImagesConfig] (owned by the presentation layer) which the worker module
 * must not depend on. The produced bean is collected by the worker's `TaskHandlerRegistry` via
 * `Instance<TaskHandler>`. Companion to [ImageAdapterProducers].
 */
@ApplicationScoped
class TaskHandlerProducers {
    @Produces
    @ApplicationScoped
    fun pinDownloadTaskHandler(
        downloadPinImage: DownloadPinImage,
        imagesConfig: ImagesConfig,
    ): PinDownloadTaskHandler =
        PinDownloadTaskHandler(downloadPinImage, imagesConfig.maxFileBytes(), imagesConfig.maxPixels())
}
