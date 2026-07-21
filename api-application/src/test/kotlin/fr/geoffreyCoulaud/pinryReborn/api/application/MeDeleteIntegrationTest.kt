package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.Base64

@QuarkusTest
class MeDeleteIntegrationTest : IntegrationTest() {
    private fun stepUp(password: String) =
        "password " + Base64.getUrlEncoder().encodeToString(password.toByteArray())

    @Test
    fun `Given a valid step-up, Then 202 and the account becomes unusable`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).header("X-Reauthentication", stepUp("password123"))
            .delete("/api/v1/me").then().statusCode(202)
        // token rejected, login refused
        given().authenticatedAs(auth).get("/api/v1/me").then().statusCode(401)
        given().contentType("application/json")
            .body("""{"name":"${auth.user.name}","password":"password123"}""")
            .post("/api/v1/sessions").then().statusCode(401)
    }

    @Test
    fun `Given a wrong step-up, Then 403 and the account survives`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).header("X-Reauthentication", stepUp("wrongpass"))
            .delete("/api/v1/me").then().statusCode(403).body("code", equalTo("REAUTHENTICATION_FAILED"))
        given().authenticatedAs(auth).get("/api/v1/me").then().statusCode(200)
    }

    @Test
    fun `Given an unsupported factor kind, Then 400`() {
        val auth = createAuthenticatedUser()
        given().authenticatedAs(auth).header("X-Reauthentication", "totp 123456")
            .delete("/api/v1/me").then().statusCode(400)
            .body("code", equalTo("UNSUPPORTED_REAUTHENTICATION_FACTOR"))
    }

    @Test
    fun `Given no step-up header, Then 403`() {
        val auth = createAuthenticatedUser()
        given().authenticatedAs(auth).delete("/api/v1/me").then().statusCode(403)
    }

    @Test
    fun `Given no token, Then 401`() {
        given().header("X-Reauthentication", stepUp("x")).delete("/api/v1/me").then().statusCode(401)
    }
}
