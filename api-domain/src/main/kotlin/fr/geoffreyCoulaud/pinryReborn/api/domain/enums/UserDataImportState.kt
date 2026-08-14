package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

enum class UserDataImportState {
    AWAITING_ARCHIVE, PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, ABANDONED,
    ;

    /** True while the import still holds the account's single slot, which the partial index tests. */
    val isActive: Boolean get() = this == AWAITING_ARCHIVE || this == PENDING || this == RUNNING

    // Enumerated rather than `!isActive`, so a state added later is neither and the partition test
    // fails instead of silently declaring it terminal.
    /** True once nothing more will happen to the row, so consumers read one accessor, not four arms. */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == ABANDONED
}
