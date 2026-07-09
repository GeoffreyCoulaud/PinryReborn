package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Same isolated, writable data dir as [ImageHostingDataDirTestProfile], plus a tiny
 * `images.max_file_bytes` so a small real image fixture trips the use case's 413 without an
 * impractical 30 MiB+ upload fixture. `quarkus.http.limits.max-body-size` (the framework
 * backstop) is left at its `application.properties` default (32M) so the 413 genuinely comes
 * from [fr.geoffreyCoulaud.pinryReborn.api.usecases.SetPinImage] (the app-level limit), not
 * RESTEasy Reactive rejecting the body outright.
 */
class ImageHostingTinyLimitTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "images.data_dir" to "build/test-image-data/${UUID.randomUUID()}",
        "images.max_file_bytes" to "100",
    )
}

@QuarkusTest
@TestProfile(ImageHostingTinyLimitTestProfile::class)
class ImageHostingOversizeIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var userCreator: UserCreator

    @Inject
    lateinit var pinCreator: PinCreator

    @Test
    fun `Given a tiny images_max_file_bytes limit, Then uploading a bigger image returns 413`() {
        // Given
        val username = "imgoversize"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val pin = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Oversize test pin",
            tags = emptyList(),
        )

        // When / Then: sample.png (a few hundred bytes) exceeds the 100-byte test limit
        given()
            .auth().preemptive().basic(username, password)
            .multiPart("file", File("src/test/resources/fixtures/sample.png"), "image/png")
            .`when`().put("/api/v1/pins/${pin.id}/image")
            .then()
            .statusCode(413)
    }
}
