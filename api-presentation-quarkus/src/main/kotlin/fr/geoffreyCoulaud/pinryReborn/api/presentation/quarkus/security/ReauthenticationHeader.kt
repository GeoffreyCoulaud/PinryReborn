package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.MalformedReauthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import java.util.Base64

/** Parses the `X-Reauthentication` step-up header carried by sensitive `/api/v1/me` operations. */
object ReauthenticationHeader {
    const val HEADER = "X-Reauthentication"
    private const val PASSWORD_KIND = "password"

    /**
     * Parse `<kind> <base64url(value)>`. Missing header -> [ReauthenticationError] (403);
     * unparseable, unsupported kind, or bad base64url -> [MalformedReauthenticationError] (400).
     */
    fun parsePasswordFactor(headerValue: String?): String {
        if (headerValue == null) throw ReauthenticationError()
        val parts = headerValue.trim().split(" ", limit = 2)
        if (parts.size != 2 || parts[0] != PASSWORD_KIND) throw MalformedReauthenticationError()
        return decodeBase64Url(parts[1])
    }

    private fun decodeBase64Url(value: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }
            .getOrElse { throw MalformedReauthenticationError() }
}
