package fr.geoffreyCoulaud.pinryReborn.api.fetch.http

import com.sun.net.httpserver.HttpServer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchAccessDeniedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchFailedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchNotFoundException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchUnreachableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.TooManyRedirectsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UrlNotAllowedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration

class HttpImageFetcherTest {
    private lateinit var server: HttpServer

    private val fetcher =
        HttpImageFetcher(
            connectTimeout = Duration.ofSeconds(2),
            requestTimeout = Duration.ofSeconds(2),
            maxRedirects = 3,
            addressPolicy = AddressPolicy.AllowAll,
        )

    private val guardedFetcher =
        HttpImageFetcher(
            connectTimeout = Duration.ofSeconds(2),
            requestTimeout = Duration.ofSeconds(2),
            maxRedirects = 3,
            addressPolicy = AddressPolicy.Standard,
        )

    @BeforeEach fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterEach fun stop() = server.stop(0)

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun handle(
        path: String,
        status: Int,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
    ) {
        server.createContext(path) { exchange ->
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
    }

    /** A local port that is not being listened on, giving a deterministic connection refusal. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `Given a 200 response, Then openStream returns the body`() {
        // Given
        val bytes = byteArrayOf(1, 2, 3)
        handle("/i.png", 200, bytes)

        // When / Then
        fetcher.openStream("${base()}/i.png").use { assertArrayEquals(bytes, it.readAllBytes()) }
    }

    @Test
    fun `Given a 401 response, Then it throws FetchAccessDenied`() {
        // Given
        handle("/i.png", 401)

        // When / Then
        assertThrows(FetchAccessDeniedException::class.java) { fetcher.openStream("${base()}/i.png") }
    }

    @Test
    fun `Given a 403 response, Then it throws FetchAccessDenied`() {
        // Given
        handle("/i.png", 403)

        // When / Then
        assertThrows(FetchAccessDeniedException::class.java) { fetcher.openStream("${base()}/i.png") }
    }

    @Test
    fun `Given a 404 response, Then it throws FetchNotFound`() {
        // Given
        handle("/i.png", 404)

        // When / Then
        assertThrows(FetchNotFoundException::class.java) { fetcher.openStream("${base()}/i.png") }
    }

    @Test
    fun `Given a 410 response, Then it throws FetchNotFound`() {
        // Given
        handle("/i.png", 410)

        // When / Then
        assertThrows(FetchNotFoundException::class.java) { fetcher.openStream("${base()}/i.png") }
    }

    @Test
    fun `Given a 429 response, Then it throws FetchUnreachable`() {
        // Given
        handle("/i.png", 429)

        // When / Then
        assertThrows(FetchUnreachableException::class.java) { fetcher.openStream("${base()}/i.png") }
    }

    @Test
    fun `Given a 500 response, Then it throws FetchUnreachable`() {
        // Given
        handle("/i.png", 500)

        // When / Then
        assertThrows(FetchUnreachableException::class.java) { fetcher.openStream("${base()}/i.png") }
    }

    @Test
    fun `Given an unmapped 418 response, Then it throws FetchFailed`() {
        // Given
        handle("/i.png", 418)

        // When / Then
        assertThrows(FetchFailedException::class.java) { fetcher.openStream("${base()}/i.png") }
    }

    @Test
    fun `Given a single redirect to a 200, Then openStream returns the final body`() {
        // Given
        val bytes = byteArrayOf(9, 8, 7)
        handle("/final.png", 200, bytes)
        handle("/redirect", 302, headers = mapOf("Location" to "/final.png"))

        // When / Then
        fetcher.openStream("${base()}/redirect").use { assertArrayEquals(bytes, it.readAllBytes()) }
    }

    @Test
    fun `Given a redirect chain over the cap, Then it throws TooManyRedirects`() {
        // Given
        handle("/loop", 302, headers = mapOf("Location" to "/loop"))

        // When / Then
        assertThrows(TooManyRedirectsException::class.java) { fetcher.openStream("${base()}/loop") }
    }

    @Test
    fun `Given a redirect without a Location header, Then it throws FetchFailed`() {
        // Given
        handle("/redirect", 302)

        // When / Then
        assertThrows(FetchFailedException::class.java) { fetcher.openStream("${base()}/redirect") }
    }

    @Test
    fun `Given a file scheme, Then it throws UrlNotAllowed`() {
        // When / Then
        assertThrows(UrlNotAllowedException::class.java) { fetcher.openStream("file:///etc/passwd") }
    }

    @Test
    fun `Given a schemeless url, Then it throws UrlNotAllowed`() {
        // When / Then
        assertThrows(UrlNotAllowedException::class.java) { fetcher.openStream("//example.com/i.png") }
    }

    @Test
    fun `Given a malformed url, Then it throws UrlNotAllowed`() {
        // When / Then
        assertThrows(UrlNotAllowedException::class.java) { fetcher.openStream("http://exa mple/i.png") }
    }

    @Test
    fun `Given a url without a host, Then it throws UrlNotAllowed`() {
        // When / Then
        assertThrows(UrlNotAllowedException::class.java) { fetcher.openStream("http:///i.png") }
    }

    @Test
    fun `Given an unresolvable host, Then it throws FetchUnreachable`() {
        // When / Then
        assertThrows(FetchUnreachableException::class.java) {
            fetcher.openStream("http://does-not-exist.invalid/i.png")
        }
    }

    @Test
    fun `Given an https origin that cannot be reached, Then it throws FetchUnreachable`() {
        // Given
        val port = closedPort()

        // When / Then
        assertThrows(FetchUnreachableException::class.java) {
            fetcher.openStream("https://127.0.0.1:$port/i.png")
        }
    }

    @Test
    fun `Given the Standard policy against a loopback origin, Then it throws UrlNotAllowed`() {
        // Given
        handle("/i.png", 200, byteArrayOf(1))

        // When / Then
        assertThrows(UrlNotAllowedException::class.java) { guardedFetcher.openStream("${base()}/i.png") }
    }
}
