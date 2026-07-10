package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import java.io.InputStream

interface ImageFetcher {
    /**
     * Apply the scheme allowlist + per-hop SSRF checks, follow redirects (capped), require a 2xx
     * response, and return the body stream for staging. Throws a typed [FetchException] on any
     * failure. Does not read/validate image content (that is [ImageProbe]'s job). The caller owns
     * closing the returned stream.
     */
    fun openStream(sourceUrl: String): InputStream
}
