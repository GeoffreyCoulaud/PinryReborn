package fr.geoffreyCoulaud.pinryReborn.api.application

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
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
    lateinit var userCreator: UserCreator

    @Inject
    lateinit var pinCreator: PinCreator

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    private fun createUserAndPin(username: String, password: String): UUID {
        val user = userCreator.createUserWithPassword(username, password)
        val pin =
            pinCreator.createPin(
                author = user,
                sourceContextUrl = "https://example.com",
                sourceMediaUrl = "https://example.com/img.jpg",
                description = "Mode-B image hosting test pin",
                tags = emptyList(),
            )
        return pin.id
    }

    private fun originUrl(path: String) = "http://127.0.0.1:$port$path"

    /** Fetch the current image status DTO for [pinId] as a JsonPath (expects `200`). */
    private fun statusOf(pinId: UUID, username: String, password: String): JsonPath =
        given()
            .auth().preemptive().basic(username, password)
            .`when`().get("/api/v1/pins/$pinId/image/status")
            .then().statusCode(200)
            .extract().jsonPath()

    /** Bounded poll of the status sub-resource until the primary `status` reaches [target]. */
    private fun pollStatus(pinId: UUID, username: String, password: String, target: String): JsonPath {
        repeat(POLL_ATTEMPTS) {
            val body = statusOf(pinId, username, password)
            if (body.getString("status") == target) return body
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("Status for pin $pinId never reached $target within the poll budget")
    }

    /** Bounded poll until a READY image has no in-flight `replacement` left (the swap settled). */
    private fun pollReplacementCleared(pinId: UUID, username: String, password: String): JsonPath {
        repeat(POLL_ATTEMPTS) {
            val body = statusOf(pinId, username, password)
            if (body.getString("status") == "READY" && body.getString("replacement.status") == null) return body
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("Replacement for pin $pinId never cleared within the poll budget")
    }

    private fun requestDownload(pinId: UUID, username: String, password: String, sourceUrl: String) =
        given()
            .auth().preemptive().basic(username, password)
            .contentType("application/json")
            .body(mapOf("sourceUrl" to sourceUrl))
            .`when`().put("/api/v1/pins/$pinId/image")

    private fun uploadImage(pinId: UUID, username: String, password: String, fixtureName: String, mimeType: String) =
        given()
            .auth().preemptive().basic(username, password)
            .multiPart("file", fixture(fixtureName), mimeType)
            .`when`().put("/api/v1/pins/$pinId/image")

    @Test
    fun `Given a mode-B request for a real image, Then it settles READY and the bytes are served`() {
        // Given
        val username = "modebhappy"
        val password = "password123"
        val pinId = createUserAndPin(username, password)

        // When: request a server-side download of the stub origin's PNG
        requestDownload(pinId, username, password, originUrl("/img.png"))
            .then()
            .statusCode(202)
            .header("Location", endsWith("/api/v1/pins/$pinId/image/status"))
            .body("status", equalTo("PENDING"))

        // Then: the worker settles the download to READY
        val ready = pollStatus(pinId, username, password, "READY")
        assertTrue(ready.getString("mimeType") == "image/png", "READY status should report the fetched mime type")
        assertTrue(ready.getLong("byteSize") > 0, "READY status should report a positive byte size")

        // Then: the canonical bytes are served with an ETag
        given()
            .auth().preemptive().basic(username, password)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(200)
            .contentType("image/png")
            .header("ETag", notNullValue())
    }

    @Test
    fun `Given an origin that returns 403, Then the download fails with ACCESS_DENIED`() {
        // Given
        val username = "modebforbidden"
        val password = "password123"
        val pinId = createUserAndPin(username, password)

        // When
        requestDownload(pinId, username, password, originUrl("/private")).then().statusCode(202)

        // Then
        val failed = pollStatus(pinId, username, password, "FAILED")
        assertTrue(failed.getString("reasonCode") == "ACCESS_DENIED", "403 should map to ACCESS_DENIED")
        assertTrue(failed.getString("message").isNotBlank(), "a failed download should carry a human message")
    }

    @Test
    fun `Given an origin that returns 404, Then the download fails with NOT_FOUND`() {
        // Given
        val username = "modebmissing"
        val password = "password123"
        val pinId = createUserAndPin(username, password)

        // When
        requestDownload(pinId, username, password, originUrl("/missing")).then().statusCode(202)

        // Then
        val failed = pollStatus(pinId, username, password, "FAILED")
        assertTrue(failed.getString("reasonCode") == "NOT_FOUND", "404 should map to NOT_FOUND")
        assertTrue(failed.getString("message").isNotBlank(), "a failed download should carry a human message")
    }

    @Test
    fun `Given an origin body that is not an image, Then the download fails with INVALID_IMAGE`() {
        // Given
        val username = "modebnotimage"
        val password = "password123"
        val pinId = createUserAndPin(username, password)

        // When
        requestDownload(pinId, username, password, originUrl("/not-image")).then().statusCode(202)

        // Then
        val failed = pollStatus(pinId, username, password, "FAILED")
        assertTrue(failed.getString("reasonCode") == "INVALID_IMAGE", "a non-image body should map to INVALID_IMAGE")
        assertTrue(failed.getString("message").isNotBlank(), "a failed download should carry a human message")
    }

    @Test
    fun `Given a FAILED mode-B download, Then a mode-A upload clears the status to READY`() {
        // Given: a download that has settled FAILED
        val username = "modebnego"
        val password = "password123"
        val pinId = createUserAndPin(username, password)
        requestDownload(pinId, username, password, originUrl("/private")).then().statusCode(202)
        pollStatus(pinId, username, password, "FAILED")

        // When: a direct multipart upload on the same path
        uploadImage(pinId, username, password, "sample.png", "image/png").then().statusCode(201)

        // Then: the failed download row is cleared and the pin is READY
        val state = statusOf(pinId, username, password)
        assertTrue(state.getString("status") == "READY", "a direct upload should supersede a failed download")
        assertNull(state.getString("reasonCode"), "READY status should carry no failure reason")
    }

    @Test
    fun `Given a READY image, Then a mode-B replacement serves old bytes until it swaps atomically`() {
        // Given: a READY JPEG uploaded directly
        val username = "modebreplace"
        val password = "password123"
        val pinId = createUserAndPin(username, password)
        uploadImage(pinId, username, password, "sample.jpg", "image/jpeg").then().statusCode(201)
        val oldEtag =
            given()
                .auth().preemptive().basic(username, password)
                .`when`().get("/api/v1/pins/$pinId/image")
                .then().statusCode(200).contentType("image/jpeg")
                .extract().header("ETag")

        // When: request a mode-B replacement gated so it cannot settle before we observe it
        gateLatch = CountDownLatch(1)
        try {
            requestDownload(pinId, username, password, originUrl("/gated")).then().statusCode(202)

            // Then: while the fetch is gated, the old bytes still serve and the replacement is PENDING
            val duringReplace = statusOf(pinId, username, password)
            assertTrue(duringReplace.getString("status") == "READY", "the existing image must stay READY")
            assertTrue(
                duringReplace.getString("replacement.status") == "PENDING",
                "the in-flight mode-B fetch must surface as a PENDING replacement",
            )
            given()
                .auth().preemptive().basic(username, password)
                .`when`().get("/api/v1/pins/$pinId/image")
                .then().statusCode(200).contentType("image/jpeg").header("ETag", equalTo(oldEtag))
        } finally {
            // Release the gate so the worker can complete the swap
            gateLatch.countDown()
        }

        // Then: the replacement settles, the primary status stays READY, and new PNG bytes serve
        val settled = pollReplacementCleared(pinId, username, password)
        assertTrue(settled.getString("mimeType") == "image/png", "the swapped-in image should be the new PNG")
        given()
            .auth().preemptive().basic(username, password)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then().statusCode(200).contentType("image/png").header("ETag", notNullValue())
    }

    @Test
    fun `Given a pin owned by someone else, Then requesting a download or reading status returns 403`() {
        // Given
        val ownerName = "modebowner"
        val ownerPass = "password123"
        val pinId = createUserAndPin(ownerName, ownerPass)
        val intruderName = "modebintruder"
        val intruderPass = "password456"
        userCreator.createUserWithPassword(intruderName, intruderPass)

        // When / Then: a non-owner may neither request a download nor read the status
        requestDownload(pinId, intruderName, intruderPass, originUrl("/img.png")).then().statusCode(403)
        given()
            .auth().preemptive().basic(intruderName, intruderPass)
            .`when`().get("/api/v1/pins/$pinId/image/status")
            .then().statusCode(403)
    }

    @Test
    fun `Given a nonexistent pin, Then requesting a download or reading status returns 404`() {
        // Given
        val username = "modebghost"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)
        val missingPinId = UUID.randomUUID()

        // When / Then
        requestDownload(missingPinId, username, password, originUrl("/img.png")).then().statusCode(404)
        given()
            .auth().preemptive().basic(username, password)
            .`when`().get("/api/v1/pins/$missingPinId/image/status")
            .then().statusCode(404)
    }

    @Test
    fun `Given a non-http source URL, Then the request is rejected 400 before any async work`() {
        // Given
        val username = "modebftp"
        val password = "password123"
        val pinId = createUserAndPin(username, password)

        // When / Then: an ftp scheme is rejected synchronously as an invalid source URL
        requestDownload(pinId, username, password, "ftp://example.com/image.png").then().statusCode(400)

        // Then: no download was created, so the pin stays NONE
        assertTrue(statusOf(pinId, username, password).getString("status") == "NONE", "no download should exist")
    }

    /**
     * SPEC GAP (reported as a concern, production intentionally left untouched: this is a test-only
     * task). The 2b spec (section 16, "DELETE during PENDING cancels") and the task brief expect a
     * DELETE while a first-time download is PENDING to cancel it and return the pin to NONE (204).
     * The current `DeletePinImage` rejects the request with 404 first, because it requires an
     * existing image row before it reaches the download-clearing step; a first-time (image-less)
     * download therefore cannot be cancelled via DELETE. This test characterises the actual
     * behaviour: DELETE returns 404 and the download proceeds to completion uncancelled.
     */
    @Test
    fun `Given a PENDING download and no image yet, Then DELETE returns 404 and does not cancel it`() {
        // Given: a gated download held PENDING with no image yet
        val username = "modebdelete"
        val password = "password123"
        val pinId = createUserAndPin(username, password)
        gateLatch = CountDownLatch(1)
        try {
            requestDownload(pinId, username, password, originUrl("/gated")).then().statusCode(202)
            assertTrue(statusOf(pinId, username, password).getString("status") == "PENDING", "download must be PENDING")

            // When: delete the image while the download is still in flight
            given()
                .auth().preemptive().basic(username, password)
                .`when`().delete("/api/v1/pins/$pinId/image")
                .then().statusCode(404)

            // Then: the download was not cancelled; it is still PENDING
            assertTrue(statusOf(pinId, username, password).getString("status") == "PENDING", "still PENDING")
        } finally {
            gateLatch.countDown()
        }

        // Then: because it was never cancelled, the released fetch runs to completion (READY)
        pollStatus(pinId, username, password, "READY")
    }

    @Test
    fun `Given a READY image with a PENDING replacement, Then deleting the image cancels it to NONE`() {
        // Given: a READY image with a gated mode-B replacement in flight over it
        val username = "modebdelrepl"
        val password = "password123"
        val pinId = createUserAndPin(username, password)
        uploadImage(pinId, username, password, "sample.jpg", "image/jpeg").then().statusCode(201)
        gateLatch = CountDownLatch(1)
        try {
            requestDownload(pinId, username, password, originUrl("/gated")).then().statusCode(202)
            val during = statusOf(pinId, username, password)
            assertTrue(during.getString("status") == "READY", "the existing image stays READY")
            assertTrue(during.getString("replacement.status") == "PENDING", "the replacement is PENDING")

            // When: delete the image while the replacement download is in flight
            given()
                .auth().preemptive().basic(username, password)
                .`when`().delete("/api/v1/pins/$pinId/image")
                .then().statusCode(204)

            // Then: the image and the in-flight download are both cleared
            assertTrue(statusOf(pinId, username, password).getString("status") == "NONE", "everything is cleared")
        } finally {
            gateLatch.countDown()
        }

        // Then: the released fetch finds no PENDING row and discards, so the pin stays NONE (no bytes)
        repeat(POLL_SETTLE_CONFIRMATIONS) {
            assertTrue(statusOf(pinId, username, password).getString("status") == "NONE", "stays NONE after release")
            Thread.sleep(POLL_INTERVAL_MS)
        }
        given()
            .auth().preemptive().basic(username, password)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then().statusCode(404)
    }

    @Test
    fun `Given a PENDING download, Then permanently deleting the pin cancels it and drops the row`() {
        // Given: a gated download held PENDING
        val username = "modebharddelete"
        val password = "password123"
        val pinId = createUserAndPin(username, password)
        gateLatch = CountDownLatch(1)
        try {
            requestDownload(pinId, username, password, originUrl("/gated")).then().statusCode(202)
            assertTrue(statusOf(pinId, username, password).getString("status") == "PENDING", "download must be PENDING")

            // When: soft-delete then permanently delete the pin while the download is in flight
            given().auth().preemptive().basic(username, password).delete("/api/v1/pins/$pinId").then().statusCode(204)
            given()
                .auth().preemptive().basic(username, password)
                .`when`().delete("/api/v1/pins/recycled")
                .then().statusCode(204)
        } finally {
            gateLatch.countDown()
        }

        // Then: the pin (and its download row) are gone, so the status endpoint reports the pin missing
        repeat(POLL_ATTEMPTS) {
            val code =
                given()
                    .auth().preemptive().basic(username, password)
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
            // paths the 4 real workers may hit concurrently.
            server.executor = Executors.newCachedThreadPool()
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
