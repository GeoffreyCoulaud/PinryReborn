package fr.geoffreyCoulaud.pinryReborn.api.domain.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword

interface PasswordHasher {
    /** Hash [raw] with a fresh random salt. */
    fun hash(raw: String): HashedPassword

    /** True if [raw] matches [stored] under [stored]'s algorithm. */
    fun matches(raw: String, stored: HashedPassword): Boolean
}
