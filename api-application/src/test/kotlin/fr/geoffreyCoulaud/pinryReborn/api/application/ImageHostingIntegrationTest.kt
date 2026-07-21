package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.endsWith
import org.hamcrest.CoreMatchers.not
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Isolated, writable `images.data_dir` for the whole class run: a fresh UUID-suffixed directory
 * under the module's `build/`, so these tests never touch the production default
 * (`/var/lib/pinry/images`, not writable in CI) and successive local runs never collide.
 */
class ImageHostingDataDirTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("images.data_dir" to "build/test-image-data/${UUID.randomUUID()}")
}

/**
 * End-to-end coverage of the canonical-image-hosting flow through the fully wired app: multipart
 * PUT upload/replace, conditional GET (`ETag`/`If-None-Match`), DELETE, and the interaction with
 * pin permanent deletion. This is what validates the spec's flagged risk -- a real multipart PUT,
 * routed by RESTEasy Reactive through `@RestForm`/`FileUpload`, exercised for real against a
 * running app (not a controller unit test).
 */
@QuarkusTest
@TestProfile(ImageHostingDataDirTestProfile::class)
class ImageHostingIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    @Inject
    lateinit var imageRepository: ImageRepositoryInterface

    @Inject
    lateinit var imagesConfig: ImagesConfig

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    private fun createPinForNewUser(): Pair<AuthenticatedUser, UUID> {
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Image hosting test pin",
            tags = emptyList(),
        )
        return auth to pin.id
    }

    @Test
    fun `Given own pin, Then upload returns 201, GET returns 200 with ETag, and If-None-Match returns 304`() {
        // Given
        val (auth, pinId) = createPinForNewUser()

        // When: multipart PUT upload
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("sample.png"), "image/png")
            .`when`().put("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(201)
            .body("url", endsWith("/api/v1/pins/$pinId/image"))

        // Then: GET returns the image with an ETag
        val etag = given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(200)
            .contentType("image/png")
            .header("ETag", notNullValue())
            .extract()
            .header("ETag")

        // Then: re-GET with a matching If-None-Match returns 304
        given()
            .authenticatedAs(auth)
            .header("If-None-Match", etag)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(304)
    }

    @Test
    fun `Given an existing image, Then replacing it returns 200 and GET reflects the new bytes and ETag`() {
        // Given
        val (auth, pinId) = createPinForNewUser()
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("sample.png"), "image/png")
            .`when`().put("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(201)
        val originalEtag = given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(200)
            .extract()
            .header("ETag")

        // When: replace with a different image
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("sample.jpg"), "image/jpeg")
            .`when`().put("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(200)
            .body("url", endsWith("/api/v1/pins/$pinId/image"))

        // Then: GET reflects the replaced image
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(200)
            .contentType("image/jpeg")
            .header("ETag", notNullValue())
            .header("ETag", not(originalEtag))
    }

    @Test
    fun `Given a pin owned by someone else, Then uploading an image returns 403`() {
        // Given
        val (_, pinId) = createPinForNewUser()
        val intruder = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(intruder)
            .multiPart("file", fixture("sample.png"), "image/png")
            .`when`().put("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(403)
    }

    @Test
    fun `Given a non-image file, Then uploading it returns 422`() {
        // Given
        val (auth, pinId) = createPinForNewUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("not-an-image.txt"), "text/plain")
            .`when`().put("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(422)
    }

    @Test
    fun `Given a pin with no image, Then GET returns 404`() {
        // Given
        val (auth, pinId) = createPinForNewUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(404)
    }

    @Test
    fun `Given an uploaded image, Then DELETE returns 204 and the subsequent GET returns 404`() {
        // Given
        val (auth, pinId) = createPinForNewUser()
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("sample.png"), "image/png")
            .`when`().put("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(201)

        // When
        given()
            .authenticatedAs(auth)
            .`when`().delete("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(204)

        // Then
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(404)
    }

    @Test
    fun `Given a pin with an image, Then permanently deleting the pin removes the stored file from disk`() {
        // Given
        val (auth, pinId) = createPinForNewUser()
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("sample.png"), "image/png")
            .`when`().put("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(201)
        val image = requireNotNull(imageRepository.findByPinId(pinId)) { "image should exist right after upload" }
        val storedPath: Path = Path.of(imagesConfig.dataDir()).resolve(image.storageKey)
        assertTrue(Files.exists(storedPath), "uploaded image file should exist on disk at $storedPath")

        // When: soft-delete then empty the recycle bin (permanent delete)
        given().authenticatedAs(auth).delete("/api/v1/pins/$pinId").then().statusCode(204)
        given()
            .authenticatedAs(auth)
            .`when`().delete("/api/v1/pins/recycled")
            .then()
            .statusCode(204)

        // Then: the stored file is gone
        assertFalse(Files.exists(storedPath), "image file should be removed from disk after permanent delete")
    }
}
