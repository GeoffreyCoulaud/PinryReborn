package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The tight policy the acceptance criteria are checked against. */
class AuthAttemptLimitTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            "auth.attempt_limit_threshold" to THRESHOLD.toString(),
            "auth.attempt_limit_backoff" to BACKOFF_STEP,
        )

    companion object {
        /** Failures before the first block. Two is the smallest value that still shows a refusal turning into a 429. */
        const val THRESHOLD = 2

        /** The only block step, short enough for a case to wait it out and long enough to refuse the next request. */
        const val BACKOFF_STEP = "PT1S"
    }
}

/**
 * Acceptance criteria 1, 2, 3, 5 and 7 of `docs/specs/2026-08-13-auth-attempt-limiting.md`. The
 * counters live in process memory and no fixture clears them, so each case takes an identity of its own.
 */
@QuarkusTest
@TestProfile(AuthAttemptLimitTestProfile::class)
class AuthAttemptLimitIntegrationTest : IntegrationTest() {
    private val threshold = AuthAttemptLimitTestProfile.THRESHOLD

    @Test
    fun `Given the threshold reached on one name, Then the next login is 429 with Retry-After`() {
        // Given: a user of this case's own, failed up to the threshold
        val name = createRandomString()
        userCreator.createUserWithPassword(name, DEFAULT_PASSWORD)
        repeat(threshold) { login(name).then().statusCode(UNAUTHORIZED).body(CODE, equalTo(AUTHENTICATION_FAILED)) }

        // When / Then: the attempt past the threshold buys a refusal instead of another guess
        login(name)
            .then()
            .statusCode(TOO_MANY_REQUESTS)
            .body(CODE, equalTo(TOO_MANY_ATTEMPTS))
            .header(RETRY_AFTER, matchesPattern(WHOLE_SECONDS))
    }

    @Test
    fun `Given the threshold reached with the name's case alternated, Then the next login is 429`() {
        // Given: a user whose name holds letters, so the two spellings below really differ
        val name = "user" + createRandomString()
        assertNotEquals(name, spelling(name, 1), "The alternated spelling must differ, or this case pins nothing")
        userCreator.createUserWithPassword(name, DEFAULT_PASSWORD)

        // When: every attempt is spelled in the other case
        repeat(threshold) { attempt -> login(spelling(name, attempt)).then().statusCode(UNAUTHORIZED) }

        // Then: the counter folded the spellings together, so the next one is refused
        login(spelling(name, threshold))
            .then()
            .statusCode(TOO_MANY_REQUESTS)
            .body(CODE, equalTo(TOO_MANY_ATTEMPTS))
    }

    @Test
    fun `Given the threshold reached on a name that belongs to nobody, Then the next login is 429`() {
        // Given: a name no user carries, failed up to the threshold
        val name = createRandomString()
        repeat(threshold) { login(name).then().statusCode(UNAUTHORIZED).body(CODE, equalTo(AUTHENTICATION_FAILED)) }

        // When / Then: refused like an existing name, so the 429 tells no one which names exist
        login(name)
            .then()
            .statusCode(TOO_MANY_REQUESTS)
            .body(CODE, equalTo(TOO_MANY_ATTEMPTS))
            .header(RETRY_AFTER, matchesPattern(WHOLE_SECONDS))
    }

    @Test
    fun `Given the threshold reached across both step-up endpoints, Then each answers 429 rather than 403`() {
        // Given: failures spread over the two endpoints, which is what makes the counter's sharing visible
        assertTrue(
            threshold >= 2,
            "This case spends its failures on two endpoints, so it needs a threshold of 2 or more",
        )
        val auth = createAuthenticatedUser()
        repeat(threshold) { attempt ->
            val refusal = if (attempt % 2 == 0) deleteAccount(auth) else changePassword(auth)
            refusal.then().statusCode(FORBIDDEN).body(CODE, equalTo(REAUTHENTICATION_FAILED))
        }

        // When / Then: both endpoints read the one counter the failures went into
        changePassword(auth)
            .then()
            .statusCode(TOO_MANY_REQUESTS)
            .body(CODE, equalTo(TOO_MANY_ATTEMPTS))
            .header(RETRY_AFTER, matchesPattern(WHOLE_SECONDS))
        deleteAccount(auth).then().statusCode(TOO_MANY_REQUESTS).body(CODE, equalTo(TOO_MANY_ATTEMPTS))
    }

    @Test
    fun `Given a blocked name once the step has passed, Then the login is the ordinary 401 again`() {
        // Given: a name blocked by the profile's one-second step
        val name = createRandomString()
        repeat(threshold) { login(name).then().statusCode(UNAUTHORIZED) }
        login(name).then().statusCode(TOO_MANY_REQUESTS)

        // When: attempts are retried until the block lifts on its own
        val status = statusOnceTheBlockLifts(name)

        // Then: nothing unblocked the key, and the attempt runs as it did before
        assertEquals(UNAUTHORIZED, status, "Expected the block to lift within $BLOCK_LIFT_TIMEOUT_SECONDS s")
    }

    /**
     * The status of the first attempt the limiter lets through, or the last one refused at the timeout.
     * Polling costs nothing to the block: a refused attempt is not a failure and records none.
     */
    private fun statusOnceTheBlockLifts(name: String): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BLOCK_LIFT_TIMEOUT_SECONDS)
        var status = login(name).statusCode
        while (status == TOO_MANY_REQUESTS && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            status = login(name).statusCode
        }
        return status
    }

    /** [name] as submitted on an even attempt, shouted on an odd one. */
    private fun spelling(name: String, attempt: Int): String =
        if (attempt % 2 == 0) name else name.uppercase(Locale.ROOT)

    /** Every request this class sends carries the wrong secret: being refused is the point. */
    private fun login(name: String): Response =
        given().contentType(JSON)
            .body("""{"name":"$name","password":"$WRONG_PASSWORD"}""")
            .post("/api/v1/sessions")

    private fun changePassword(auth: AuthenticatedUser): Response =
        given().authenticatedAs(auth).contentType(JSON)
            .body("""{"currentPassword":"$WRONG_PASSWORD","newPassword":"$NEW_PASSWORD"}""")
            .put("/api/v1/me/password")

    private fun deleteAccount(auth: AuthenticatedUser): Response =
        given().authenticatedAs(auth)
            .header(
                "X-Reauthentication",
                "password " + Base64.getUrlEncoder().encodeToString(WRONG_PASSWORD.toByteArray()),
            )
            .delete("/api/v1/me")

    private companion object {
        const val JSON = "application/json"
        const val WRONG_PASSWORD = "wrong-password"
        const val NEW_PASSWORD = "newpassword1"

        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val TOO_MANY_REQUESTS = 429

        const val CODE = "code"
        const val RETRY_AFTER = "Retry-After"
        const val AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"
        const val REAUTHENTICATION_FAILED = "REAUTHENTICATION_FAILED"
        const val TOO_MANY_ATTEMPTS = "TOO_MANY_AUTHENTICATION_ATTEMPTS"

        /** Whole seconds, never below 1 while the block is in the future: `\d+` would accept a 0. */
        const val WHOLE_SECONDS = "[1-9]\\d*"

        const val BLOCK_LIFT_TIMEOUT_SECONDS = 10L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
