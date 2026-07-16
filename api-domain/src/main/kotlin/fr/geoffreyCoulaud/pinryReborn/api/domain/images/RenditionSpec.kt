package fr.geoffreyCoulaud.pinryReborn.api.domain.images

/**
 * A transform request: fit the shortest side to [shortestSide] px, keeping animation iff [animated].
 *
 * [animated] = true asserts the caller has already established that the source IS multi-frame: a
 * transformer asks the decoder for every frame on that basis, and a decoder without frame support
 * (PNG, JPEG) may reject or complain about the request. Callers must therefore intersect what was
 * requested with the source's own animated flag before building a spec, never pass the raw request.
 */
data class RenditionSpec(val shortestSide: Int, val animated: Boolean)
