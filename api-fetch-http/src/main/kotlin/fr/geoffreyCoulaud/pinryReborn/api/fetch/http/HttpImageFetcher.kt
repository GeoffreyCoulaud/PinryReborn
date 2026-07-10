package fr.geoffreyCoulaud.pinryReborn.api.fetch.http

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchAccessDeniedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchFailedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchNotFoundException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchUnreachableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.TooManyRedirectsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UrlNotAllowedException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpImageFetcher(
    connectTimeout: Duration,
    private val requestTimeout: Duration,
    private val maxRedirects: Int,
    private val addressPolicy: AddressPolicy,
) : ImageFetcher {
    private val client: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    // Each throw maps a distinct HTTP outcome to its typed FetchException; that mapping is the
    // adapter's purpose, so the count is intentional.
    @Suppress("ThrowsCount")
    override fun openStream(sourceUrl: String): InputStream {
        var url = guarded(sourceUrl)
        var redirects = 0
        while (true) {
            val response = send(url)
            val status = response.statusCode()
            // Classified by ascending status boundary rather than closed ranges: the JDK HttpClient
            // consumes 1xx interim responses internally, so the final status is always >= 200 and a
            // "status < 200" arm would be dead code. 2xx -> body; 3xx -> follow (capped); 401/403 ->
            // access denied; 404/410 -> not found; 429 and 5xx -> unreachable (retryable); any other
            // 4xx -> failed. Non-standard 6xx codes fall into the retryable bucket.
            when {
                status < REDIRECT_MIN -> return response.body()
                status < CLIENT_ERROR_MIN -> {
                    if (redirects >= maxRedirects) throw TooManyRedirectsException("too many redirects")
                    val location =
                        response.headers().firstValue("location").orElse(null)
                            ?: throw FetchFailedException("redirect without a location header")
                    response.body().close()
                    url = guarded(url.resolve(location).toString())
                    redirects += 1
                }
                status == UNAUTHORIZED || status == FORBIDDEN ->
                    throw FetchAccessDeniedException("origin refused access ($status)")
                status == NOT_FOUND || status == GONE ->
                    throw FetchNotFoundException("no image at this url ($status)")
                status == TOO_MANY_REQUESTS ->
                    throw FetchUnreachableException("origin error ($status)")
                status < SERVER_ERROR_MIN ->
                    throw FetchFailedException("unexpected response status $status")
                else -> throw FetchUnreachableException("origin error ($status)")
            }
        }
    }

    private fun send(url: URI): HttpResponse<InputStream> {
        // Note (spec section 17 risk): HttpRequest.timeout() bounds the time to obtain the
        // response headers, not the streaming read of the body. For v1 this is acceptable: the
        // connect timeout plus this response timeout bound the worst case before the body arrives.
        // If a slow-body origin becomes a problem, wrap the returned stream with a read deadline.
        val request = HttpRequest.newBuilder(url).timeout(requestTimeout).GET().build()
        return try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw FetchUnreachableException("could not reach the origin", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FetchUnreachableException("fetch interrupted", e)
        }
    }

    // Each throw rejects a distinct unsafe-URL condition (malformed, bad scheme, no host,
    // unresolvable, blocked address); the count is intentional for this SSRF guard.
    @Suppress("ThrowsCount")
    private fun guarded(raw: String): URI {
        val uri =
            try {
                URI(raw)
            } catch (e: URISyntaxException) {
                throw UrlNotAllowedException("malformed url", e)
            }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw UrlNotAllowedException("scheme not allowed")
        val host = uri.host ?: throw UrlNotAllowedException("missing host")
        val address =
            try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                throw FetchUnreachableException("could not resolve host", e)
            }
        if (!addressPolicy.isAllowed(address)) throw UrlNotAllowedException("address not allowed")
        return uri
    }

    private companion object {
        const val REDIRECT_MIN = 300
        const val CLIENT_ERROR_MIN = 400
        const val SERVER_ERROR_MIN = 500
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val GONE = 410
        const val TOO_MANY_REQUESTS = 429
    }
}
