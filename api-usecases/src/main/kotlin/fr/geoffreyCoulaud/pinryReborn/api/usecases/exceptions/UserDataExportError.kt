package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class UserDataExportError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)

// Thrown by the requester when it catches the domain ExportAlreadyInProgressException raised by the
// repository's partial-unique-index guard (the ordinary case is caught earlier by
// findPendingForUser, but this is what surfaces a lost race between two concurrent requests).
/** The one collision error whose `cause` stays optional: `UserDataExportRequester.kt:58` refuses on a read, with
 * no failure behind it to carry. Its sibling errors require it, so a translation site cannot drop the chain. */
class ExportAlreadyInProgressError(cause: Throwable? = null) :
    UserDataExportError("An export is already in progress", ErrorCode.EXPORT_ALREADY_IN_PROGRESS, cause)

class ExportTooSoonError(override val retryAfterSeconds: Long) :
    UserDataExportError("Another export was requested too recently", ErrorCode.EXPORT_TOO_SOON),
    ThrottledError

class ExportDoesNotExistError : UserDataExportError("Export does not exist", ErrorCode.EXPORT_DOES_NOT_EXIST)

class ExportPermissionError :
    UserDataExportError("Export belongs to another user", ErrorCode.EXPORT_INSUFFICIENT_PERMISSIONS)

class ExportNotReadyError : UserDataExportError("Export is not ready", ErrorCode.EXPORT_NOT_READY)

class ExportGoneError : UserDataExportError("Export is no longer available", ErrorCode.EXPORT_GONE)
