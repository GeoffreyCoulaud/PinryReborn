package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

enum class UserDataExportState {
    PENDING, READY, FAILED, EXPIRED, DELETED, SUPERSEDED,
    ;

    // Enumerated rather than negated, so a state added later is neither terminal nor live and the
    // partition test fails instead of admitting it to the sweep that deletes bytes.
    /** True once nothing more will happen to the row, which is what the archive sweep selects on. */
    val isTerminal: Boolean
        get() = this == FAILED || this == EXPIRED || this == DELETED || this == SUPERSEDED

    /** True once the archive is unreachable to its owner, whether or not its bytes still exist. */
    val isGone: Boolean get() = isTerminal && this != FAILED
}
