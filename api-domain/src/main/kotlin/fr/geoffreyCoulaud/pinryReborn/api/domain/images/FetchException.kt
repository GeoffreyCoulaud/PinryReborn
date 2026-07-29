package fr.geoffreyCoulaud.pinryReborn.api.domain.images

/** Base for failures raised while fetching image bytes from a source URL (mode B). */
sealed class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** URL blocked by policy: disallowed scheme, malformed, or a private/loopback/reserved address. */
class UrlNotAllowedException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** The origin refused server access (HTTP 401/403): the bounce case. */
class FetchAccessDeniedException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** The origin returned 404/410. */
class FetchNotFoundException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** The body exceeded the configured size (declared Content-Length or streamed). */
class FetchTooLargeException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** Too many redirect hops. */
class TooManyRedirectsException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** Any other permanent HTTP failure (e.g. an unexpected 4xx). */
class FetchFailedException(message: String, cause: Throwable? = null) : FetchException(message, cause)

/** Transient reachability failure: DNS, connect refused, timeout, TLS, 5xx, 429. Retryable. */
class FetchUnreachableException(message: String, cause: Throwable? = null) : FetchException(message, cause)
