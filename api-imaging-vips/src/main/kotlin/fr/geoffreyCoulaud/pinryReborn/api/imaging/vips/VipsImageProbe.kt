package fr.geoffreyCoulaud.pinryReborn.api.imaging.vips

import app.photofox.vipsffm.VImage
import app.photofox.vipsffm.Vips
import app.photofox.vipsffm.VipsError
import app.photofox.vipsffm.VipsOption
import app.photofox.vipsffm.enums.VipsAccess
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooManyPixelsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UndecodableImageException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UnsupportedImageFormatException
import jakarta.enterprise.context.ApplicationScoped
import java.lang.foreign.Arena

/**
 * [ImageProbe] adapter backed by native libvips (via the vips-ffm FFM binding).
 *
 * Loads the staged file with sequential access (cheap, header-only for the checks below;
 * libvips is lazy and does not decode pixel data until asked to), reads its `vips-loader`
 * header field to determine the source format, and reads width/height to enforce the pixel
 * guard. Any libvips failure while opening/reading is reported as [UndecodableImageException].
 *
 * The arena is managed directly (rather than via `Vips.run`) so the probed [ProbeResult] can be
 * returned in one expression; `Vips.init()` still guarantees the native library is initialised
 * exactly once, same as `Vips.run` does internally.
 */
@ApplicationScoped
class VipsImageProbe : ImageProbe {
    override fun probe(staged: StagedFile, maxPixels: Long): ProbeResult =
        try {
            readHeader(staged, maxPixels)
        } catch (exception: ImageProbeException) {
            throw exception
        } catch (exception: VipsError) {
            throw UndecodableImageException("Could not decode image at ${staged.path}: ${exception.message}", exception)
        }

    private fun readHeader(staged: StagedFile, maxPixels: Long): ProbeResult {
        Vips.init()
        return Arena.ofConfined().use { arena ->
            val image =
                VImage.newFromFile(
                    arena,
                    staged.path,
                    VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL),
                )
            val format = formatOf(image.getString("vips-loader"))
            val width = image.width
            val height = image.height
            if (width.toLong() * height.toLong() > maxPixels) {
                throw ImageTooManyPixelsException(
                    "Image at ${staged.path} has $width x $height pixels, exceeding the $maxPixels limit",
                )
            }
            ProbeResult(format, width, height)
        }
    }

    private fun formatOf(loader: String?): ImageFormat =
        when (loader) {
            "pngload" -> ImageFormat.PNG
            "jpegload" -> ImageFormat.JPEG
            "webpload" -> ImageFormat.WEBP
            "gifload" -> ImageFormat.GIF
            else -> throw UnsupportedImageFormatException("Unsupported image loader: $loader")
        }
}
