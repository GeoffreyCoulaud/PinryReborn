package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

/**
 * Failures of session-token verification. These are caught by the Bearer identity provider and
 * translated into Quarkus auth failures; they deliberately do NOT extend [BaseError] (they never
 * reach [BaseErrorMapper] / need an [ErrorCode]).
 */
sealed class SessionAuthenticationError(message: String) : Exception(message)

class SessionTokenInvalidError : SessionAuthenticationError("Invalid session token")

class SessionTokenExpiredError : SessionAuthenticationError("Session token expired")
