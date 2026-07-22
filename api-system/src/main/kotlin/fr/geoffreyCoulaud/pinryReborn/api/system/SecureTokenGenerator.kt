package fr.geoffreyCoulaud.pinryReborn.api.system

import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.Base64

@ApplicationScoped
class SecureTokenGenerator : TokenGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32 // 256 bits
    }
}
