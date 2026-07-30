package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

/**
 * A [BaseError] that tells the client how long to wait before retrying. Rendered as a `Retry-After`
 * header by [fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.BaseErrorMapper], so
 * every 429 response carries the header without a dedicated mapper per code.
 */
interface ThrottledError {
    val retryAfterSeconds: Long
}
