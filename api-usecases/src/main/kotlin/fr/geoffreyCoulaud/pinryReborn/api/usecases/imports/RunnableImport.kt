package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import java.util.UUID

/**
 * Non-nullable projection of a claimed import (spec section 5), built at one validation site so the
 * walk carries no nullable. [runToken] is the token just written, not a column still null at step 1.
 */
data class RunnableImport(
    val importId: UUID,
    val userId: UUID,
    val storageKey: String,
    val runToken: UUID,
)
