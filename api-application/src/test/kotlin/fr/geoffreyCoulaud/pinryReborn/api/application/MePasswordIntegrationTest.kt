package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
class MePasswordIntegrationTest : IntegrationTest() {
    private fun changeBody(current: String, next: String) =
        """{"currentPassword":"$current","newPassword":"$next"}"""

    @Test
    fun `Given valid change, Then 204 and old sessions die and new password works`() {
        // Given
        val auth = createAuthenticatedUser(password = "password123")
        // When
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("password123", "newpassword1")).put("/api/v1/me/password")
            .then().statusCode(204)
        // Then — old token rejected
        given().authenticatedAs(auth).get("/api/v1/me").then().statusCode(401)
        // Then — new password logs in, old does not
        login(auth.user.name, "newpassword1").then().statusCode(201)
        login(auth.user.name, "password123").then().statusCode(401)
    }

    @Test
    fun `Given wrong current password, Then 403 and password unchanged`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("wrongpass", "newpassword1")).put("/api/v1/me/password")
            .then().statusCode(403).body("code", equalTo("REAUTHENTICATION_FAILED"))
        login(auth.user.name, "password123").then().statusCode(201)
    }

    @Test
    fun `Given reusing the current password, Then 422`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("password123", "password123")).put("/api/v1/me/password")
            .then().statusCode(422).body("code", equalTo("PASSWORD_PREVIOUSLY_USED"))
    }

    @Test
    fun `Given a too-short new password, Then 400`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("password123", "short")).put("/api/v1/me/password")
            .then().statusCode(400)
    }

    @Test
    fun `Given no token, Then 401`() {
        given().contentType("application/json").body(changeBody("a", "newpassword1"))
            .put("/api/v1/me/password").then().statusCode(401)
    }

    private fun login(name: String, password: String) =
        given().contentType("application/json").body("""{"name":"$name","password":"$password"}""")
            .post("/api/v1/sessions")
}
