package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Isolated, writable `images.data_dir` for the class run, as in [ModeBImageHostingTestProfile], so
 * the mode-A upload used to seed a real image can write and the deletion cleaner can evict it.
 */
class MeDeleteCompletionTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("images.data_dir" to "build/test-image-data/${UUID.randomUUID()}")
}

/**
 * End-to-end coverage of the async account-deletion completeness path (spec §13): a real
 * `DELETE /api/v1/me` on an account that owns a pin with an uploaded image enqueues the deletion,
 * and the real async worker then erases everything (including the image row and on-disk bytes)
 * before hard-deleting the user as the last step of one transaction. Because the hard delete frees
 * the username only once that whole transaction has committed, a bounded poll that observes the
 * same username become registerable again is proof the full erasure ran end-to-end.
 */
@QuarkusTest
@TestProfile(MeDeleteCompletionTestProfile::class)
class MeDeleteCompletionIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    private fun stepUp(password: String) =
        "password " + Base64.getUrlEncoder().encodeToString(password.toByteArray())

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    /** Bounded poll of `POST /api/v1/users` until re-registering [name] succeeds (username freed). */
    private fun pollUntilRegistrationSucceeds(name: String, password: String): Boolean {
        repeat(POLL_ATTEMPTS) {
            val status =
                given()
                    .contentType("application/json")
                    .body("""{"name":"$name","password":"$password"}""")
                    .post("/api/v1/users")
                    .then().extract().statusCode()
            if (status == 200) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    @Test
    fun `Given an account with a pin and an uploaded image, Then deletion erases it and frees the username`() {
        // Given: an authenticated user with a real pin carrying an uploaded image
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        val name = auth.user.name
        val pin =
            pinCreator.createPin(
                author = auth.user,
                sourceContextUrl = "https://example.com",
                sourceMediaUrl = "https://example.com/img.png",
                description = "Account deletion completeness test pin",
                tags = emptyList(),
            )
        given()
            .authenticatedAs(auth)
            .multiPart("file", fixture("sample.png"), "image/png")
            .`when`().put("/api/v1/pins/${pin.id}/image")
            .then().statusCode(201)
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/pins/${pin.id}/image")
            .then().statusCode(200)

        // When: the account is deleted with a valid step-up
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .delete("/api/v1/me")
            .then().statusCode(202)

        // Then: the worker fully erases the account, so the same username becomes registerable
        // again only once the hard delete (the last step of the cleaner's transaction) has run.
        val freedWithinBound = pollUntilRegistrationSucceeds(name, password)
        assertTrue(freedWithinBound, "the deletion worker should erase the account and free the username")
    }

    companion object {
        private const val POLL_ATTEMPTS = 50
        private const val POLL_INTERVAL_MS = 200L
    }
}
