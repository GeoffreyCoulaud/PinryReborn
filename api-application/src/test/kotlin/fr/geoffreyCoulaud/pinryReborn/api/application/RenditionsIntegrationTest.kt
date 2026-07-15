package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.imaging.vips.VipsImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Below-the-fixtures rendition sizes so a real downscale happens against the 10x10 fixtures
 * (`sample.png`, `animated.gif`), and an isolated, writable `images.data_dir` per run so these
 * tests never touch the production default and successive local runs never collide.
 */
class RenditionsTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "images.data_dir" to "build/test-image-data/${UUID.randomUUID()}",
        "images.renditions.tiny" to "4",
        "images.renditions.small" to "6",
    )
}

/**
 * End-to-end coverage of rendition serving through the fully wired app with real libvips + real
 * filesystem cache: WebP renditions at the correct shortest side, animated vs flattened output,
 * original-as-is when the requested size is not smaller than the source, a 400 on an unknown
 * size, and cache eviction on delete. This is where the animated `page-height` correctness (spec
 * section 13) is validated end-to-end against a real 3-frame GIF, by re-probing the response
 * bytes with the real `VipsImageProbe`.
 */
@QuarkusTest
@TestProfile(RenditionsTestProfile::class)
class RenditionsIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var userCreator: UserCreator

    @Inject
    lateinit var pinCreator: PinCreator

    @Inject
    lateinit var imageRepository: ImageRepositoryInterface

    @Inject
    lateinit var imagesConfig: ImagesConfig

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    private fun createUserAndPin(username: String, password: String): UUID {
        val user = userCreator.createUserWithPassword(username, password)
        val pin = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "rendition test",
            tags = emptyList(),
        )
        return pin.id
    }

    private fun upload(username: String, password: String, pinId: UUID, fixtureName: String, contentType: String) {
        given()
            .auth().preemptive().basic(username, password)
            .multiPart("file", fixture(fixtureName), contentType)
            .`when`().put("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(201)
    }

    private fun probeBytes(bytes: ByteArray): ProbeResult {
        val tmp = Files.createTempFile("resp-", ".bin")
        Files.write(tmp, bytes)
        return try {
            VipsImageProbe().probe(StagedFile(tmp.toString(), 0, ""), maxPixels = 1_000_000)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `Given a 10px image and size=tiny (4), Then GET returns a 4px WebP`() {
        // Given
        val pinId = createUserAndPin("rtiny", "password123")
        upload("rtiny", "password123", pinId, "sample.png", "image/png")

        // When
        val bytes = given()
            .auth().preemptive().basic("rtiny", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=tiny")
            .then()
            .statusCode(200)
            .contentType("image/webp")
            .extract()
            .asByteArray()

        // Then
        val probe = probeBytes(bytes)
        assertEquals(ImageFormat.WEBP, probe.format)
        assertEquals(4, minOf(probe.width, probe.height))
    }

    @Test
    fun `Given no size, Then GET returns the original bytes`() {
        // Given
        val pinId = createUserAndPin("rorig", "password123")
        upload("rorig", "password123", pinId, "sample.png", "image/png")

        // When / Then
        given()
            .auth().preemptive().basic("rorig", "password123")
            .`when`().get("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(200)
            .contentType("image/png")
    }

    @Test
    fun `Given size=large (960) larger than the image, Then GET serves the original as-is`() {
        // Given
        val pinId = createUserAndPin("rlarge", "password123")
        upload("rlarge", "password123", pinId, "sample.png", "image/png")

        // When / Then: never upscaled, original format
        given()
            .auth().preemptive().basic("rlarge", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=large")
            .then()
            .statusCode(200)
            .contentType("image/png")
    }

    @Test
    fun `Given an unknown size, Then GET returns 400`() {
        // Given
        val pinId = createUserAndPin("rbad", "password123")
        upload("rbad", "password123", pinId, "sample.png", "image/png")

        // When / Then
        given()
            .auth().preemptive().basic("rbad", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=huge")
            .then()
            .statusCode(400)
    }

    @Test
    fun `Given an animated GIF and animated=false, Then the rendition is a static WebP`() {
        // Given
        val pinId = createUserAndPin("rflat", "password123")
        upload("rflat", "password123", pinId, "animated.gif", "image/gif")

        // When
        val bytes = given()
            .auth().preemptive().basic("rflat", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=tiny&animated=false")
            .then()
            .statusCode(200)
            .contentType("image/webp")
            .extract()
            .asByteArray()

        // Then
        assertFalse(probeBytes(bytes).animated)
    }

    @Test
    fun `Given an animated GIF and the default (animated), Then the rendition keeps the animation`() {
        // Given
        val pinId = createUserAndPin("ranim", "password123")
        upload("ranim", "password123", pinId, "animated.gif", "image/gif")

        // When
        val bytes = given()
            .auth().preemptive().basic("ranim", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=tiny")
            .then()
            .statusCode(200)
            .contentType("image/webp")
            .extract()
            .asByteArray()

        // Then
        assertTrue(probeBytes(bytes).animated)
    }

    @Test
    fun `Given a cached rendition, Then deleting the image evicts the cache subtree`() {
        // Given
        val pinId = createUserAndPin("revict", "password123")
        upload("revict", "password123", pinId, "sample.png", "image/png")
        val imageId = requireNotNull(imageRepository.findByPinId(pinId)).id

        // When: generate + cache a rendition
        given()
            .auth().preemptive().basic("revict", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=tiny")
            .then()
            .statusCode(200)
        val cacheDir: Path = Path.of(imagesConfig.dataDir()).resolve("cache/$imageId")
        assertTrue(Files.exists(cacheDir), "rendition cache subtree should exist after first GET")

        // When: delete the image
        given()
            .auth().preemptive().basic("revict", "password123")
            .`when`().delete("/api/v1/pins/$pinId/image")
            .then()
            .statusCode(204)

        // Then: the cache subtree is gone
        assertFalse(Files.exists(cacheDir), "rendition cache subtree should be evicted on delete")
    }
}
