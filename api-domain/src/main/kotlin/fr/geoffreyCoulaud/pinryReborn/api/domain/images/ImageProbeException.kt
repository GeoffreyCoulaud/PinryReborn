package fr.geoffreyCoulaud.pinryReborn.api.domain.images

/**
 * Base for failures raised while probing a staged image (format detection, decoding, dimensions).
 */
sealed class ImageProbeException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UnsupportedImageFormatException(message: String, cause: Throwable? = null) : ImageProbeException(message, cause)

class UndecodableImageException(message: String, cause: Throwable? = null) : ImageProbeException(message, cause)

class ImageTooManyPixelsException(message: String, cause: Throwable? = null) : ImageProbeException(message, cause)

/**
 * Raised by [ImageStore.stage] when the source stream exceeds the configured byte-size guard.
 * Deliberately NOT part of the [ImageProbeException] sealed family: it is a store-side guard,
 * not a probe-side failure.
 */
class ImageTooLargeException(message: String) : Exception(message)
