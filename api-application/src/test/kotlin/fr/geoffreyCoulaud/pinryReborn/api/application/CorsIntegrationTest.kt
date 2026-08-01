package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.containsStringIgnoringCase
import org.junit.jupiter.api.Test

@QuarkusTest
class CorsIntegrationTest : IntegrationTest() {

    @Test
    fun `Given an allowed origin, Then the preflight advertises the CORS policy`() {
        // Given / When
        given()
            .header("Origin", ALLOWED_ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type")
            .`when`()
            .options("/api/v1/pins")
            // Then
            .then()
            .header("Access-Control-Allow-Origin", equalTo(ALLOWED_ORIGIN))
            .header("Access-Control-Allow-Methods", containsStringIgnoringCase("POST"))
            .header("Access-Control-Allow-Headers", containsStringIgnoringCase("authorization"))
    }

    @Test
    fun `Given a disallowed origin, Then the preflight omits the allow-origin header`() {
        // Given / When
        given()
            .header("Origin", DISALLOWED_ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .`when`()
            .options("/api/v1/pins")
            // Then
            .then()
            .header("Access-Control-Allow-Origin", nullValue())
    }

    @Test
    fun `Given an allowed origin, Then an actual request echoes the origin and exposes Location`() {
        // Given
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .header("Origin", ALLOWED_ORIGIN)
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo(ALLOWED_ORIGIN))
            .header("Access-Control-Expose-Headers", containsStringIgnoringCase("Location"))
    }

    @Test
    fun `Given an allowed origin, Then the actual response exposes Retry-After to cross-origin clients`() {
        // Given
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .header("Origin", ALLOWED_ORIGIN)
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)
            .header("Access-Control-Expose-Headers", containsStringIgnoringCase("Retry-After"))
    }

    companion object {
        /** Pinned in the test `application.properties` as the single allowed origin. */
        private const val ALLOWED_ORIGIN = "https://app.test"
        private const val DISALLOWED_ORIGIN = "https://evil.test"
    }
}
