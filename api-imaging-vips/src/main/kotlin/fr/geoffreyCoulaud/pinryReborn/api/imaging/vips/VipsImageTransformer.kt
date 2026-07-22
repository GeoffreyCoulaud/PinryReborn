package fr.geoffreyCoulaud.pinryReborn.api.imaging.vips

import app.photofox.vipsffm.VImage
import app.photofox.vipsffm.Vips
import app.photofox.vipsffm.VipsOption
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTransformer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionSpec
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import java.io.InputStream
import java.lang.foreign.Arena
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

/**
 * [ImageTransformer] adapter backed by native libvips (vips-ffm). Output is always WebP.
 *
 * Not `@ApplicationScoped`: ARC cannot resolve the `Int quality` ctor param, so a producer in
 * the composition root builds it (mirrors `FilesystemImageStore`).
 */
class VipsImageTransformer(private val quality: Int) : ImageTransformer {

    private companion object {
        private val HEX = HexFormat.of()
    }

    // A render failure (a probe-valid image libvips still refuses to encode, an I/O fault) must
    // leave no output temp behind; the input temp is always removed. The broad catch dispatches
    // via the JVM exception table, not a conditional jump, so it adds no uncovered Kover branch.
    @Suppress("TooGenericExceptionCaught")
    override fun render(source: InputStream, spec: RenditionSpec): StagedFile {
        Vips.init()
        val input = Files.createTempFile("rendition-in-", ".tmp")
        val output = Files.createTempFile("rendition-out-", ".webp")
        try {
            Files.newOutputStream(input).use { source.copyTo(it) }
            Arena.ofConfined().use { arena ->
                // n = -1 loads every frame (animation preserved). For the static case we omit the
                // option entirely: the loader default is already the first frame only, and loaders
                // without an `n` property (PNG, JPEG) log a GObject CRITICAL when handed one.
                val image = if (spec.animated) {
                    VImage.newFromFile(arena, input.toString(), VipsOption.Int("n", -1))
                } else {
                    VImage.newFromFile(arena, input.toString())
                }
                // For a multi-page load, per-frame height is `page-height`; absent (null) on a
                // single frame, where the frame height IS the image height.
                val frameHeight = image.getInt("page-height") ?: image.height
                val scale = spec.shortestSide.toDouble() / minOf(image.width, frameHeight)
                val rendered = if (scale < 1.0) resize(image, scale, frameHeight, spec.animated) else image
                rendered.writeToFile(output.toString(), VipsOption.Int("Q", quality))
            }
            val bytes = Files.readAllBytes(output)
            val hash = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            return StagedFile(output.toString(), bytes.size.toLong(), hash)
        } catch (error: Throwable) {
            Files.deleteIfExists(output)
            throw error
        } finally {
            Files.deleteIfExists(input)
        }
    }

    // resize() scales the whole tall multi-page strip but does NOT update `page-height`, which
    // would corrupt frame boundaries; re-set it for animated output (vips-ffm 1.9.8 behaviour).
    private fun resize(image: VImage, scale: Double, frameHeight: Int, animated: Boolean): VImage {
        val resized = image.resize(scale)
        if (animated) resized.set("page-height", Math.round(frameHeight * scale).toInt())
        return resized
    }
}
