package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class ImageError(message: String, code: ErrorCode, cause: Throwable? = null) : BaseError(message, code, cause)

class ImagePinDoesNotExistError : ImageError("Pin does not exist", ErrorCode.IMAGE_DOES_NOT_EXIST)

class ImagePermissionError : ImageError("Insufficient permissions", ErrorCode.IMAGE_INSUFFICIENT_PERMISSIONS)

class ImageDoesNotExistError : ImageError("Pin has no image", ErrorCode.IMAGE_DOES_NOT_EXIST)

class ImageTooLargeError(cause: Throwable? = null) :
    ImageError("Image exceeds the maximum size", ErrorCode.IMAGE_TOO_LARGE, cause)

class ImageInvalidError(message: String, cause: Throwable? = null) : ImageError(message, ErrorCode.IMAGE_INVALID, cause)

class ImageSourceUrlInvalidError(cause: Throwable? = null) :
    ImageError("Invalid source URL", ErrorCode.IMAGE_SOURCE_URL_INVALID, cause)
