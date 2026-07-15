package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTransformer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.imaging.vips.VipsImageTransformer
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.RenditionsConfig
import fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem.FilesystemImageStore
import fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem.FilesystemRenditionCache
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * CDI wiring for [ImageStore], [RenditionCache] and [ImageTransformer], hosted in the composition
 * root (`api-application`): this is the only module that may depend on the
 * `api-storage-filesystem` and `api-imaging-vips` infrastructure adapters, so the producers live
 * here rather than in the presentation layer (which must depend on `api-usecases`/`api-domain`
 * only).
 *
 * `FilesystemImageStore`, `FilesystemRenditionCache` and `VipsImageTransformer` are deliberately
 * not `@ApplicationScoped` (see their kdoc) since ARC cannot resolve their plain constructor
 * parameters on its own. These producers are the single place that construct them, sourcing
 * `dataDir` from [ImagesConfig] and `webpQuality` from [RenditionsConfig].
 */
@ApplicationScoped
class ImageAdapterProducers {
    @Produces
    @ApplicationScoped
    fun imageStore(config: ImagesConfig): ImageStore = FilesystemImageStore(config.dataDir())

    @Produces
    @ApplicationScoped
    fun renditionCache(config: ImagesConfig): RenditionCache = FilesystemRenditionCache(config.dataDir())

    @Produces
    @ApplicationScoped
    fun imageTransformer(config: RenditionsConfig): ImageTransformer = VipsImageTransformer(config.webpQuality())
}
