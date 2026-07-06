package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
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
    lateinit var userCreator: UserCreator

    @Inject
    lateinit var pinCreator: PinCreator

    private fun createUserWithPins(username: String, password: String, vararg descriptions: String) {
        val user = userCreator.createUserWithPassword(username, password)
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
        val username = "pinsearchuser1"
        val password = "password123"
        createUserWithPins(username, password, "Beautiful mountain landscape", "City skyline at night")

        // When, Then
        given()
            .auth().preemptive().basic(username, password)
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
        val username = "pinsearchuser2"
        val password = "password123"
        createUserWithPins(username, password, "Mountain peak at sunset")

        // When, Then - "mountan" should match "mountain" with high score
        given()
            .auth().preemptive().basic(username, password)
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
        val username = "pinsearchuser3"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        // When, Then
        given()
            .auth().preemptive().basic(username, password)
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
        val username = "pinsearchuser4"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        // When, Then
        given()
            .auth().preemptive().basic(username, password)
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
        val username = "pinsearchuser5"
        val password = "password123"
        createUserWithPins(
            username, password,
            "Test pin number 1",
            "Test pin number 2",
            "Test pin number 3",
            "Test pin number 4",
            "Test pin number 5"
        )

        // When, Then
        given()
            .auth().preemptive().basic(username, password)
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
        val username = "pinsearchuser6"
        val password = "password123"
        val descriptions = (1..25).map { "Pin description $it" }.toTypedArray()
        createUserWithPins(username, password, *descriptions)

        // When, Then - requesting 100 should be capped to max (20)
        given()
            .auth().preemptive().basic(username, password)
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
        val user1 = "pinsearchuser7"
        val user2 = "pinsearchuser8"
        val password = "password123"
        createUserWithPins(user1, password, "User one pin description")
        createUserWithPins(user2, password, "User two pin description")

        // When, Then - user1 should only see their own pins
        given()
            .auth().preemptive().basic(user1, password)
            .queryParam("q", "user")
            .`when`()
            .get("/api/v1/pins/search")
            .then()
            .statusCode(200)
            .body("results", hasSize<Any>(1))
            .body("results[0].pin.description", equalTo("User one pin description"))
    }
}
