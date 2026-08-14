package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class UserDataImportError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)

/** Rethrown from the domain exception the partial unique index raises; the index is the only authority. */
class ImportAlreadyInProgressError(cause: Throwable) :
    UserDataImportError("An import is already in progress", ErrorCode.IMPORT_ALREADY_IN_PROGRESS, cause)

class ImportDoesNotExistError : UserDataImportError("Import does not exist", ErrorCode.IMPORT_DOES_NOT_EXIST)

class ImportPermissionError :
    UserDataImportError("Import belongs to another user", ErrorCode.IMPORT_INSUFFICIENT_PERMISSIONS)

class ImportNotAwaitingArchiveError :
    UserDataImportError("Import is past its upload phase", ErrorCode.IMPORT_NOT_AWAITING_ARCHIVE)

/** Carries [currentLength] so a client resumes from the reported offset rather than restarting. */
class ImportChunkOffsetMismatchError(val currentLength: Long, cause: Throwable) :
    UserDataImportError(
        "Chunk offset does not match the current length of $currentLength",
        ErrorCode.IMPORT_CHUNK_OFFSET_MISMATCH,
        cause,
    )

/** No chunk ever arrived, so there is no file to close: the store would open a path that is not there. */
class ImportArchiveEmptyError :
    UserDataImportError("Import received no archive bytes", ErrorCode.IMPORT_ARCHIVE_EMPTY)

class ImportArchiveTooLargeError(cause: Throwable) :
    UserDataImportError("Archive is larger than this instance accepts", ErrorCode.IMPORT_ARCHIVE_TOO_LARGE, cause)

class ImportInsufficientStorageError :
    UserDataImportError("Not enough free space to receive this archive", ErrorCode.IMPORT_INSUFFICIENT_STORAGE)
