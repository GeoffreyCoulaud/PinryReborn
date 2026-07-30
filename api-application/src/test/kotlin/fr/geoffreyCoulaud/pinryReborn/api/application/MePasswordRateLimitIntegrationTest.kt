package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

class MePasswordRateLimitTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("auth.password_change_minimum_interval" to "PT1H")
}

@QuarkusTest
@TestProfile(MePasswordRateLimitTestProfile::class)
class MePasswordRateLimitIntegrationTest : IntegrationTest() {
    private fun changeBody(current: String, next: String) =
        """{"currentPassword":"$current","newPassword":"$next"}"""

    @Test
    fun `Given a change inside the minimum interval, Then 429 with Retry-After and PASSWORD_CHANGED_TOO_SOON`() {
        // Given: the seed hash from signup is fresh, and the profile pins a 1 h interval
        val auth = createAuthenticatedUser(password = "password123")
        // When / Then
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("password123", "newpassword1"))
            .put("/api/v1/me/password")
            .then()
            .statusCode(429)
            .header("Retry-After", notNullValue())
            .body("code", equalTo("PASSWORD_CHANGED_TOO_SOON"))
    }
}
