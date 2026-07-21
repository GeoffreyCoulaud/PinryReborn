package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

@QuarkusTest
class PinSearchIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    private fun createPinsFor(user: User, vararg descriptions: String) {
        descriptions.forEachIndexed { index, description ->
            pinCreator.createPin(
                author = user,
                sourceContextUrl = "https://example.com/page$index",
                sourceMediaUrl = "https://example.com/image$index.jpg",
                description = description,
                tags = emptyList()
            )
        }
    }

    @Test
    fun `Given pins exist, Then search returns ranked results`() {
        // Given
        val auth = createAuthenticatedUser()
        createPinsFor(auth.user, "Beautiful mountain landscape", "City skyline at night")

        // When, Then
        given()
            .authenticatedAs(auth)
            .queryParam("q", "mountain")
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(200)
            .body("results", hasSize<Any>(greaterThan(0)))
            .body("results[0].pin.description", equalTo("Beautiful mountain landscape"))
    }

    @Test
    fun `Given typo in query, Then search returns fuzzy matches`() {
        // Given
        val auth = createAuthenticatedUser()
        createPinsFor(auth.user, "Mountain peak at sunset")

        // When, Then - "mountan" should match "mountain" with high score
        given()
            .authenticatedAs(auth)
            .queryParam("q", "mountan")
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(200)
            .body("results", hasSize<Any>(greaterThan(0)))
    }

    @Test
    fun `Given empty query, Then returns 400`() {
        // Given
        val auth = createAuthenticatedUser()

        // When, Then
        given()
            .authenticatedAs(auth)
            .queryParam("q", "")
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `Given no query parameter, Then returns 400`() {
        // Given
        val auth = createAuthenticatedUser()

        // When, Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `Given limit parameter, Then returns at most limit results`() {
        // Given
        val auth = createAuthenticatedUser()
        createPinsFor(
            auth.user,
            "Test pin number 1",
            "Test pin number 2",
            "Test pin number 3",
            "Test pin number 4",
            "Test pin number 5"
        )

        // When, Then
        given()
            .authenticatedAs(auth)
            .queryParam("q", "test")
            .queryParam("limit", 2)
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(200)
            .body("results", hasSize<Any>(2))
    }

    @Test
    fun `Given limit exceeds max, Then returns at most max results`() {
        // Given
        val auth = createAuthenticatedUser()
        val descriptions = (1..25).map { "Pin description $it" }.toTypedArray()
        createPinsFor(auth.user, *descriptions)

        // When, Then - requesting 100 should be capped to max (20)
        given()
            .authenticatedAs(auth)
            .queryParam("q", "pin")
            .queryParam("limit", 100)
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(200)
            .body("results", hasSize<Any>(20))
    }

    @Test
    fun `Given unauthenticated request, Then returns 401`() {
        // When, Then
        given()
            .queryParam("q", "test")
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(401)
    }

    @Test
    fun `Given search for another user's pins, Then returns only own pins`() {
        // Given
        val auth1 = createAuthenticatedUser()
        val auth2 = createAuthenticatedUser()
        createPinsFor(auth1.user, "User one pin description")
        createPinsFor(auth2.user, "User two pin description")

        // When, Then - user1 should only see their own pins
        given()
            .authenticatedAs(auth1)
            .queryParam("q", "user")
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(200)
            .body("results", hasSize<Any>(1))
            .body("results[0].pin.description", equalTo("User one pin description"))
    }
}
