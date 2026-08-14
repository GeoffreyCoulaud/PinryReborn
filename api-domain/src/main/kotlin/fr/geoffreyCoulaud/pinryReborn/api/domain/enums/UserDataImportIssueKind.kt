package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

/**
 * What the importer reported about one archive line. "Already present" is not one of these: it is a
 * counter, and [LINE_REJECTED] is the catch-all that keeps one bad entry from failing an import.
 */
enum class UserDataImportIssueKind {
    PIN_HAS_NO_MEDIA,
    MEDIA_ENTRY_MISSING,
    MEDIA_UNREADABLE,
    MEDIA_TOO_LARGE,
    MEDIA_TOO_MANY_PIXELS,
    MEDIA_AMBIGUOUS,
    MEDIA_DIGEST_MISMATCH,
    LINE_MALFORMED,
    FIELD_INVALID,
    ENTRY_PATH_INVALID,
    NAME_TAKEN_BY_RECYCLED,
    LINE_REJECTED,
}
