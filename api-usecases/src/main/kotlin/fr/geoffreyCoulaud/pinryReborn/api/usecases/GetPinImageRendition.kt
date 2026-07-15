package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTransformer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionSpec
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Descriptor of what to serve for a `GET .../image[?size=...]`: the original bytes, or a rendition. */
sealed interface ServedImage {
    data class Original(val image: Image) : ServedImage
    data class Rendition(val imageId: UUID, val key: String, val effectivePx: Int, val animated: Boolean) : ServedImage
}

@ApplicationScoped
class GetPinImageRendition(
    private val getPinImage: GetPinImage,
    private val imageStore: ImageStore,
    private val imageTransformer: ImageTransformer,
    private val renditionCache: RenditionCache,
) {
    fun get(pinId: UUID, requester: User, requestedPx: Int?, animated: Boolean): ServedImage {
        // Reuse 2a's load + owner/not-found guards verbatim (403/404 behaviour unchanged).
        val image = getPinImage.get(pinId, requester)
        val effectivePx = effectiveRenditionPx(image, requestedPx, animated)
        return if (effectivePx == null) {
            ServedImage.Original(image)
        } else {
            serveRendition(image, effectivePx, animated)
        }
    }

    // The clamped shortest-side px for a rendition, or null when the original must be served as-is
    // (no size requested, or the source needs neither downscaling nor flattening).
    private fun effectiveRenditionPx(image: Image, requestedPx: Int?, animated: Boolean): Int? {
        if (requestedPx == null) return null
        val srcShort = minOf(image.width, image.height)
        val needsDownscale = srcShort > requestedPx
        val needsFlatten = image.animated && !animated
        return if (needsDownscale || needsFlatten) minOf(requestedPx, srcShort) else null
    }

    private fun serveRendition(image: Image, effectivePx: Int, animated: Boolean): ServedImage.Rendition {
        val key = keyFor(effectivePx, animated)
        val cached = renditionCache.openStream(image.id, key)
        if (cached != null) {
            cached.close()
            return ServedImage.Rendition(image.id, key, effectivePx, animated)
        }
        val staged = imageStore.openStream(image.storageKey).use { source ->
            imageTransformer.render(source, RenditionSpec(effectivePx, animated))
        }
        renditionCache.store(image.id, key, staged)
        return ServedImage.Rendition(image.id, key, effectivePx, animated)
    }

    private fun keyFor(effectivePx: Int, animated: Boolean): String =
        "$effectivePx-${if (animated) "a" else "s"}.webp"
}
