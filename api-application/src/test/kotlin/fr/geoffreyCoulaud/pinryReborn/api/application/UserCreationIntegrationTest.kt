package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test

@QuarkusTest
class UserCreationIntegrationTest : IntegrationTest() {

    // ==================== Simple Scenarios ====================

    @Test
    fun `creating a user returns the created user`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "testuser", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("name", equalTo("testuser"))
    }

    @Test
    fun `creating a user with different name returns the created user`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "another_user", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("name", equalTo("another_user"))
    }

    // ==================== Complex Scenarios ====================

    @Test
    fun `creating two different users succeeds`() {
        // Create first user
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "user1", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)
            .body("name", equalTo("user1"))

        // Create second user with different name
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "user2", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)
            .body("name", equalTo("user2"))
    }

    @Test
    fun `creating a user with duplicate name fails`() {
        // Create user first time
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "duplicate_user", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)
            .body("name", equalTo("duplicate_user"))

        // Try to create user with same name
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "duplicate_user", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("USERNAME_ALREADY_EXISTS"))
            .body("status", equalTo(409))
    }

    @Test
    fun `creating a user with special characters in name succeeds`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "user_with-special.chars123", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)
            .body("id", notNullValue())
            .body("name", equalTo("user_with-special.chars123"))
    }

    @Test
    fun `creating a user with unicode name fails validation`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "用户名", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `creating a user with blank name fails validation`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "  ", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `creating a user with too short password fails validation`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "shortpass", "password": "short"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `creating a user with too long password fails validation`() {
        val longPassword = "a".repeat(73)
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "longpass", "password": "$longPassword"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `Given a created user with password, Then authentication with that password succeeds`() {
        // Given
        val username = "authuser"
        val password = "mysecretpassword"
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "$username", "password": "$password"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)

        // When / Then - authentication with correct password succeeds
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)

        // When / Then - authentication with wrong password fails
        given()
            .auth().preemptive().basic(username, "wrongpassword")
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(401)
    }
}
