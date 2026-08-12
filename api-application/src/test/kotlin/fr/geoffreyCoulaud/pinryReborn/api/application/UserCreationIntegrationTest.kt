package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@QuarkusTest
class UserCreationIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var userRepository: UserRepositoryInterface

    @Inject
    lateinit var clock: Clock

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

        // When / Then - logging in with the correct password succeeds and issues a token
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "$username", "password": "$password"}""")
            .`when`()
            .post("/api/v1/sessions")
            .then()
            .statusCode(201)
            .body("token", notNullValue())

        // When / Then - logging in with the wrong password fails
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "$username", "password": "wrongpassword"}""")
            .`when`()
            .post("/api/v1/sessions")
            .then()
            .statusCode(401)
    }

    @Test
    fun `creating a user with a name differing only by case is rejected`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "CaseUser", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)

        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "caseuser", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(409)
            .body("code", equalTo("USERNAME_ALREADY_EXISTS"))
    }

    @Test
    fun `Given a name held by an account pending deletion, Then creating it again is rejected`() {
        // Given: an account tombstoned in place. The deletion task the endpoint would enqueue is
        // left out on purpose: the worker runs here and its hard delete would free the name.
        val name = createRandomString()
        val password = DEFAULT_PASSWORD
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "$name", "password": "$password"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)
        val user = requireNotNull(userRepository.findUserByName(name))
        userRepository.markPendingDeletion(user, clock.now())
        // The arrangement, asserted on the state itself: a 401 on a session attempt is also what a
        // merely credential-less account answers, and would leave this test pinning a plain duplicate.
        assertNull(userRepository.findUserByName(name))
        assertNotNull(userRepository.findUserByIdIncludingDeleted(user.id))

        // When
        val response = given()
            .contentType(ContentType.JSON)
            .body("""{"name": "$name", "password": "$password"}""")
            .`when`()
            .post("/api/v1/users")

        // Then
        response
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("USERNAME_ALREADY_EXISTS"))
    }

    @Test
    fun `authentication is case-insensitive on username`() {
        val password = "password123"
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "CaseLogin", "password": "$password"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)

        val token = given()
            .contentType(ContentType.JSON)
            .body("""{"name": "caselogin", "password": "$password"}""")
            .`when`()
            .post("/api/v1/sessions")
            .then()
            .statusCode(201)
            .extract()
            .path<String>("token")

        given()
            .header("Authorization", "Bearer $token")
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)
    }
}
