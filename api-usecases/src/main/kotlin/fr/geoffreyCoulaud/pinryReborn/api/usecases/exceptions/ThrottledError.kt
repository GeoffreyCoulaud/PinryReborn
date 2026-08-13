package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

import java.time.Duration
import java.time.Instant

/**
 * A [BaseError] that tells the client how long to wait before retrying. Rendered as a `Retry-After`
 * header by [fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.BaseErrorMapper], so
 * every 429 response carries the header without a dedicated mapper per code.
 */
interface ThrottledError {
    val retryAfterSeconds: Long

    companion object {
        /**
         * Whole seconds from [from] to [to], rounded up, for every site that emits the header: a
         * truncation would send the client back a second early, into a second refusal.
         */
        fun wholeSecondsBetween(from: Instant, to: Instant): Long {
            val remaining = Duration.between(from, to)
            return if (remaining.nano == 0) remaining.seconds else remaining.seconds + 1
        }
    }
}
