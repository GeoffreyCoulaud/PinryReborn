package fr.geoffreyCoulaud.pinryReborn.api.application

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.endsWith
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Isolated, writable `images.data_dir` for the class run (fresh UUID-suffixed directory under
 * `build/`, as in 2a) and `allow_private_addresses=true` so the real SSRF-guarded [ImageFetcher]
 * accepts the loopback stub origin these tests fetch from (without it the fetch fails
 * `URL_NOT_ALLOWED`). Config keys are snake_case, matching the SmallRye `@ConfigMapping`s.
 */
class ModeBImageHostingTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            "images.data_dir" to "build/test-image-data/${UUID.randomUUID()}",
            "images.download.allow_private_addresses" to "true",
        )
}

/**
 * End-to-end coverage of mode-B (server-side) image ingestion through the fully wired app: a real
 * REST request enqueues a download, the real async worker fetches from a local `HttpServer` origin,
 * probes with native libvips, and swaps the bytes in atomically. The worker is real and async, so
 * settled-state cases poll the status sub-resource in a bounded loop, and cases that must observe a
 * transient `PENDING` gate the origin on a latch so the download cannot settle before the assertion.
 */
@QuarkusTest
@TestProfile(ModeBImageHostingTestProfile::class)
class ModeBImageHostingIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    private fun createUserAndPin(): Pair<AuthenticatedUser, UUID> {
        val auth = createAuthenticatedUser()
        val pin =
            pinCreator.createPin(
                author = auth.user,
                sourceContextUrl = "https://example.com",
                sourceMediaUrl = "https://example.com/img.jpg",
                description = "Mode-B image hosting test pin",
                tags = emptyList(),
            )
        return auth to pin.id
    }

    private fun originUrl(path: String) = "http://127.0.0.1:$port$path"

    /** Fetch the current image status DTO for [pinId] as a JsonPath (expects `200`). */
    private fun statusOf(pinId: UUID, auth: AuthenticatedUser): JsonPath =
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image/status")
            .then().statusCode(200)
            .extract().jsonPath()

    /** Bounded poll of the status sub-resource until the primary `status` reaches [target]. */
    private fun pollStatus(pinId: UUID, auth: AuthenticatedUser, target: String): JsonPath {
        repeat(POLL_ATTEMPTS) {
            val body = statusOf(pinId, auth)
            if (body.getString("status") == target) return body
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("Status for pin $pinId never reached $target within the poll budget")
    }

    /** Bounded poll until a READY image has no in-flight `replacement` left (the swap settled). */
    private fun pollReplacementCleared(pinId: UUID, auth: AuthenticatedUser): JsonPath {
        repeat(POLL_ATTEMPTS) {
            val body = statusOf(pinId, auth)
            if (body.getString("status") == "READY" && body.getString("replacement.status") == null) return body
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("Replacement for pin $pinId never cleared within the poll budget")
    }

    private fun requestDownload(pinId: UUID, auth: AuthenticatedUser, sourceUrl: String) =
        given()
            .authenticatedAs(auth)
            .contentType("application/json")
            .body(mapOf("sourceUrl" to sourceUrl))
            .`when`().put("/api/v1/pins/$pinId/image")

    private fun uploadImage(pinId: UUID, auth: AuthenticatedUser, fixtureName: String, mimeType: String) =
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture(fixtureName), mimeType)
            .`when`().put("/api/v1/pins/$pinId/image")

    @Test
    fun `Given a mode-B request for a real image, Then it settles READY and the bytes are served`() {
        // Given
        val (auth, pinId) = createUserAndPin()

        // When: request a server-side download of the stub origin's PNG
        requestDownload(pinId, auth, originUrl("/img.png"))
            .then()
            .statusCode(202)
            .header("Location", endsWith("/api/v1/pins/$pinId/image/status"))
            .body("status", equalTo("PENDING"))

        // Then: the worker settles the download to READY
        val ready = pollStatus(pinId, auth, "READY")
        assertTrue(ready.getString("mimeType") == "image/png", "READY status should report the fetched mime type")
        assertTrue(ready.getLong("byteSize") > 0, "READY status should report a positive byte size")

        // Then: the canonical bytes are served with an ETag
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(200)
            .contentType("image/png")
            .header("ETag", notNullValue())
    }

    @Test
    fun `Given an origin that returns 403, Then the download fails with ACCESS_DENIED`() {
        // Given
        val (auth, pinId) = createUserAndPin()

        // When
        requestDownload(pinId, auth, originUrl("/private")).then().statusCode(202)

        // Then
        val failed = pollStatus(pinId, auth, "FAILED")
        assertTrue(failed.getString("reasonCode") == "ACCESS_DENIED", "403 should map to ACCESS_DENIED")
        assertTrue(failed.getString("message").isNotBlank(), "a failed download should carry a human message")
    }

    @Test
    fun `Given an origin that returns 404, Then the download fails with NOT_FOUND`() {
        // Given
        val (auth, pinId) = createUserAndPin()

        // When
        requestDownload(pinId, auth, originUrl("/missing")).then().statusCode(202)

        // Then
        val failed = pollStatus(pinId, auth, "FAILED")
        assertTrue(failed.getString("reasonCode") == "NOT_FOUND", "404 should map to NOT_FOUND")
        assertTrue(failed.getString("message").isNotBlank(), "a failed download should carry a human message")
    }

    @Test
    fun `Given an origin body that is not an image, Then the download fails with INVALID_IMAGE`() {
        // Given
        val (auth, pinId) = createUserAndPin()

        // When
        requestDownload(pinId, auth, originUrl("/not-image")).then().statusCode(202)

        // Then
        val failed = pollStatus(pinId, auth, "FAILED")
        assertTrue(failed.getString("reasonCode") == "INVALID_IMAGE", "a non-image body should map to INVALID_IMAGE")
        assertTrue(failed.getString("message").isNotBlank(), "a failed download should carry a human message")
    }

    @Test
    fun `Given a FAILED mode-B download, Then a mode-A upload clears the status to READY`() {
        // Given: a download that has settled FAILED
        val (auth, pinId) = createUserAndPin()
        requestDownload(pinId, auth, originUrl("/private")).then().statusCode(202)
        pollStatus(pinId, auth, "FAILED")

        // When: a direct multipart upload on the same path
        uploadImage(pinId, auth, "sample.png", "image/png").then().statusCode(201)

        // Then: the failed download row is cleared and the pin is READY
        val state = statusOf(pinId, auth)
        assertTrue(state.getString("status") == "READY", "a direct upload should supersede a failed download")
        assertNull(state.getString("reasonCode"), "READY status should carry no failure reason")
    }

    @Test
    fun `Given a READY image, Then a mode-B replacement serves old bytes until it swaps atomically`() {
        // Given: a READY JPEG uploaded directly
        val (auth, pinId) = createUserAndPin()
        uploadImage(pinId, auth, "sample.jpg", "image/jpeg").then().statusCode(201)
        val oldEtag =
            given()
                .authenticatedAs(auth)
                .`when`().get("/api/v1/pins/$pinId/image")
                .then().statusCode(200).contentType("image/jpeg")
                .extract().header("ETag")

        // When: request a mode-B replacement gated so it cannot settle before we observe it
        gateLatch = CountDownLatch(1)
        try {
            requestDownload(pinId, auth, originUrl("/gated")).then().statusCode(202)

            // Then: while the fetch is gated, the old bytes still serve and the replacement is PENDING
            val duringReplace = statusOf(pinId, auth)
            assertTrue(duringReplace.getString("status") == "READY", "the existing image must stay READY")
            assertTrue(
                duringReplace.getString("replacement.status") == "PENDING",
                "the in-flight mode-B fetch must surface as a PENDING replacement",
            )
            given()
                .authenticatedAs(auth)
                .`when`().get("/api/v1/pins/$pinId/image")
                .then().statusCode(200).contentType("image/jpeg").header("ETag", equalTo(oldEtag))
        } finally {
            // Release the gate so the worker can complete the swap
            gateLatch.countDown()
        }

        // Then: the replacement settles, the primary status stays READY, and new PNG bytes serve
        val settled = pollReplacementCleared(pinId, auth)
        assertTrue(settled.getString("mimeType") == "image/png", "the swapped-in image should be the new PNG")
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then().statusCode(200).contentType("image/png").header("ETag", notNullValue())
    }

    @Test
    fun `Given a pin owned by someone else, Then requesting a download or reading status returns 403`() {
        // Given
        val (_, pinId) = createUserAndPin()
        val intruder = createAuthenticatedUser()

        // When / Then: a non-owner may neither request a download nor read the status
        requestDownload(pinId, intruder, originUrl("/img.png")).then().statusCode(403)
        given()
            .authenticatedAs(intruder)
            .`when`().get("/api/v1/pins/$pinId/image/status")
            .then().statusCode(403)
    }

    @Test
    fun `Given a nonexistent pin, Then requesting a download or reading status returns 404`() {
        // Given
        val auth = createAuthenticatedUser()
        val missingPinId = UUID.randomUUID()

        // When / Then
        requestDownload(missingPinId, auth, originUrl("/img.png")).then().statusCode(404)
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$missingPinId/image/status")
            .then().statusCode(404)
    }

    @Test
    fun `Given a non-http source URL, Then the request is rejected 400 before any async work`() {
        // Given
        val (auth, pinId) = createUserAndPin()

        // When / Then: an ftp scheme is rejected synchronously as an invalid source URL
        requestDownload(pinId, auth, "ftp://example.com/image.png").then().statusCode(400)

        // Then: no download was created, so the pin stays NONE
        assertTrue(statusOf(pinId, auth).getString("status") == "NONE", "no download should exist")
    }

    @Test
    fun `Given a PENDING download and no image yet, Then DELETE cancels it and returns the pin to NONE`() {
        // Given: a gated first-time download held PENDING with no image yet
        val (auth, pinId) = createUserAndPin()
        gateLatch = CountDownLatch(1)
        try {
            requestDownload(pinId, auth, originUrl("/gated")).then().statusCode(202)
            assertTrue(statusOf(pinId, auth).getString("status") == "PENDING", "download must be PENDING")

            // When: delete the image while the first-time download is still in flight
            given()
                .authenticatedAs(auth)
                .`when`().delete("/api/v1/pins/$pinId/image")
                .then().statusCode(204)

            // Then: the in-flight download is cancelled and the pin is back to NONE
            assertTrue(statusOf(pinId, auth).getString("status") == "NONE", "the download is cancelled")
        } finally {
            gateLatch.countDown()
        }

        // Then: the released fetch finds no PENDING row and discards, so the pin stays NONE (no bytes)
        repeat(POLL_SETTLE_CONFIRMATIONS) {
            assertTrue(statusOf(pinId, auth).getString("status") == "NONE", "stays NONE after release")
            Thread.sleep(POLL_INTERVAL_MS)
        }
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then().statusCode(404)
    }

    @Test
    fun `Given a READY image with a PENDING replacement, Then deleting the image cancels it to NONE`() {
        // Given: a READY image with a gated mode-B replacement in flight over it
        val (auth, pinId) = createUserAndPin()
        uploadImage(pinId, auth, "sample.jpg", "image/jpeg").then().statusCode(201)
        gateLatch = CountDownLatch(1)
        try {
            requestDownload(pinId, auth, originUrl("/gated")).then().statusCode(202)
            val during = statusOf(pinId, auth)
            assertTrue(during.getString("status") == "READY", "the existing image stays READY")
            assertTrue(during.getString("replacement.status") == "PENDING", "the replacement is PENDING")

            // When: delete the image while the replacement download is in flight
            given()
                .authenticatedAs(auth)
                .`when`().delete("/api/v1/pins/$pinId/image")
                .then().statusCode(204)

            // Then: the image and the in-flight download are both cleared
            assertTrue(statusOf(pinId, auth).getString("status") == "NONE", "everything is cleared")
        } finally {
            gateLatch.countDown()
        }

        // Then: the released fetch finds no PENDING row and discards, so the pin stays NONE (no bytes)
        repeat(POLL_SETTLE_CONFIRMATIONS) {
            assertTrue(statusOf(pinId, auth).getString("status") == "NONE", "stays NONE after release")
            Thread.sleep(POLL_INTERVAL_MS)
        }
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then().statusCode(404)
    }

    @Test
    fun `Given a PENDING download, Then permanently deleting the pin cancels it and drops the row`() {
        // Given: a gated download held PENDING
        val (auth, pinId) = createUserAndPin()
        gateLatch = CountDownLatch(1)
        try {
            requestDownload(pinId, auth, originUrl("/gated")).then().statusCode(202)
            assertTrue(statusOf(pinId, auth).getString("status") == "PENDING", "download must be PENDING")

            // When: soft-delete then permanently delete the pin while the download is in flight
            given().authenticatedAs(auth).delete("/api/v1/pins/$pinId").then().statusCode(204)
            given()
                .authenticatedAs(auth)
                .`when`().delete("/api/v1/pins/recycled")
                .then().statusCode(204)
        } finally {
            gateLatch.countDown()
        }

        // Then: the pin (and its download row) are gone, so the status endpoint reports the pin missing
        repeat(POLL_ATTEMPTS) {
            val code =
                given()
                    .authenticatedAs(auth)
                    .`when`().get("/api/v1/pins/$pinId/image/status")
                    .then().extract().statusCode()
            if (code == 404) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("Status for the hard-deleted pin $pinId never reported 404 within the poll budget")
    }

    companion object {
        private const val POLL_ATTEMPTS = 50
        private const val POLL_INTERVAL_MS = 200L
        private const val POLL_SETTLE_CONFIRMATIONS = 5
        private const val GATE_RELEASE_TIMEOUT_SECONDS = 25L
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val NO_RESPONSE_BODY = -1L

        private lateinit var server: HttpServer
        private lateinit var originExecutor: ExecutorService

        @Volatile
        private var port: Int = 0

        // A fresh latch is assigned per gated test; a completed latch lets `/gated` serve immediately,
        // so the non-gated tests (which never touch it) are unaffected.
        @Volatile
        private var gateLatch = CountDownLatch(0)

        private lateinit var pngBytes: ByteArray
        private lateinit var textBytes: ByteArray

        @JvmStatic
        @BeforeAll
        fun startOrigin() {
            pngBytes = Files.readAllBytes(File("src/test/resources/fixtures/sample.png").toPath())
            textBytes = Files.readAllBytes(File("src/test/resources/fixtures/not-an-image.txt").toPath())
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            // A cached thread pool so a deliberately-blocked `/gated` handler cannot stall the other
            // paths the 4 real workers may hit concurrently. HttpServer.stop() does not touch a
            // caller-supplied executor, so we keep a handle and shut it down in @AfterAll (otherwise
            // its non-daemon threads linger past the test-worker drain timeout).
            originExecutor = Executors.newCachedThreadPool()
            server.executor = originExecutor
            server.createContext("/img.png") { exchange -> respondBytes(exchange, HTTP_OK, "image/png", pngBytes) }
            server.createContext("/private") { exchange -> respondStatus(exchange, HTTP_FORBIDDEN) }
            server.createContext("/missing") { exchange -> respondStatus(exchange, HTTP_NOT_FOUND) }
            server.createContext("/not-image") { exchange -> respondBytes(exchange, HTTP_OK, "text/plain", textBytes) }
            server.createContext("/gated") { exchange ->
                // Block before writing so the download stays PENDING until the test releases the gate.
                gateLatch.await(GATE_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                respondBytes(exchange, HTTP_OK, "image/png", pngBytes)
            }
            server.start()
            port = server.address.port
        }

        @JvmStatic
        @AfterAll
        fun stopOrigin() {
            gateLatch.countDown()
            server.stop(0)
            originExecutor.shutdownNow()
        }

        private fun respondBytes(exchange: HttpExchange, status: Int, contentType: String, body: ByteArray) {
            try {
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.write(body)
            } finally {
                exchange.close()
            }
        }

        private fun respondStatus(exchange: HttpExchange, status: Int) {
            try {
                exchange.sendResponseHeaders(status, NO_RESPONSE_BODY)
            } finally {
                exchange.close()
            }
        }
    }
}
