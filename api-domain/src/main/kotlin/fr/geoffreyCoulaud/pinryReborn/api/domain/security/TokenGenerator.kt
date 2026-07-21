package fr.geoffreyCoulaud.pinryReborn.api.domain.security

interface TokenGenerator {
    /** Generate a fresh, high-entropy, URL-safe opaque token string. */
    fun generateToken(): String
}
