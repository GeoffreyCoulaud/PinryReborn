package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem.FilesystemImageStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * CDI wiring for [ImageStore], hosted in the composition root (`api-application`): this is the
 * only module that may depend on the `api-storage-filesystem` infrastructure adapter, so the
 * producer lives here rather than in the presentation layer (which must depend on
 * `api-usecases`/`api-domain` only).
 *
 * `FilesystemImageStore` is deliberately not `@ApplicationScoped` (see its kdoc) since ARC
 * cannot resolve its plain `String dataDir` constructor parameter on its own. This producer is
 * the single place that constructs it, sourcing `dataDir` from [ImagesConfig].
 */
@ApplicationScoped
class ImageAdapterProducers {
    @Produces
    @ApplicationScoped
    fun imageStore(config: ImagesConfig): ImageStore = FilesystemImageStore(config.dataDir())
}
