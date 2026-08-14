package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import java.time.Instant

/**
 * ADR 0015 decision 3: the archive's instants are restored, clamped at both ends. Clamping only the
 * future, as the first draft did, let a valid `Instant` of year -999999999 reach a pagination sort key.
 */
class ImportInstantClamp(
    private val accountCreatedAt: Instant,
    private val importInstant: Instant,
) {
    fun clamp(instant: Instant): Instant = instant.coerceAtLeast(accountCreatedAt).coerceAtMost(importInstant)

    /** Floored at the row's own clamped creation: nothing was modified before it existed. */
    fun clampUpdate(updatedAt: Instant, createdAt: Instant): Instant = clamp(updatedAt).coerceAtLeast(createdAt)
}
