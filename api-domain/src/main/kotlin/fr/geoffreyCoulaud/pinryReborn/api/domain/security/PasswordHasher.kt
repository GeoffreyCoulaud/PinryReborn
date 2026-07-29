package fr.geoffreyCoulaud.pinryReborn.api.domain.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import java.time.Instant

interface PasswordHasher {
    /**
     * Hash [raw] with a fresh random salt, stamped with [createdAt]. The adapter does not own a
     * business instant, so the creation instant arrives here and passes straight through.
     */
    fun hash(raw: String, createdAt: Instant): HashedPassword

    /** True if [raw] matches [stored] under [stored]'s algorithm. */
    fun matches(raw: String, stored: HashedPassword): Boolean
}
