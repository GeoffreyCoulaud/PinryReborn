package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

enum class UserDataExportState {
    PENDING, READY, FAILED, EXPIRED, DELETED, SUPERSEDED,
    ;

    /** True for the states where the archive bytes no longer exist. */
    val isGone: Boolean get() = this == EXPIRED || this == DELETED || this == SUPERSEDED
}
