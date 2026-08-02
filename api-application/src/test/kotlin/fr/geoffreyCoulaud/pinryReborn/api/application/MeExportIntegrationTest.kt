package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.emptyIterable
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Endpoint behaviour of `/api/v1/me/exports` (spec `docs/specs/2026-07-22-user-data-export.md` §7):
 * step-up enforcement, ownership, state-dependent errors and the plain CRUD-ish shape of the
 * resource. The archive an export actually contains, built by the real async worker, is
 * [MeExportCompletionIntegrationTest]'s job, not this class's.
 *
 * Most tests here seed a [UserDataExport] row directly through the injected repository rather than
 * driving the real `POST` + real worker to a specific state: a fresh `PENDING` (or `READY`) row is
 * needed to exercise a given branch (already-in-progress, not-ready-for-download, ownership,
 * not-found), and racing the real worker (`tasks.poll_interval` is `PT0.05S` in tests) to observe
 * one of those states is exactly the kind of test the task brief warns against writing flaky. The
 * one test that legitimately fires two real `POST`s back-to-back documents that race explicitly and
 * asserts the invariant it actually guarantees, not a specific status code.
 */
@QuarkusTest
@TestProfile(MeExportTestProfile::class)
// The app under test runs the real SystemClock; these read the wall clock to keep fixture instants consistent with it.
@Suppress("WallClockRead")
class MeExportIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var userDataExportRepository: UserDataExportRepositoryInterface

    private fun stepUp(password: String) =
        "password " + Base64.getUrlEncoder().encodeToString(password.toByteArray())

    private fun pendingExportFor(userId: UUID) = UserDataExport(
        id = UUID.randomUUID(),
        userId = userId,
        state = UserDataExportState.PENDING,
        formatVersion = 1,
        requestedAt = Instant.now(),
    )

    // --- POST: step-up enforcement ---

    @Test
    fun `Given no reauthentication header, Then requesting an export returns 403`() {
        // Given
        val auth = createAuthenticatedUser(password = "password123")

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`().post("/api/v1/me/exports")
            .then().statusCode(403)
    }

    @Test
    fun `Given a wrong step-up password, Then requesting an export returns 403 and no export is created`() {
        // Given
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)

        // When
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp("wrongpass"))
            .`when`().post("/api/v1/me/exports")
            .then().statusCode(403).body("code", equalTo("REAUTHENTICATION_FAILED"))

        // Then: no task was enqueued, so no export row exists for this user either
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports")
            .then().statusCode(200).body("exports", emptyIterable<Any>())
    }

    @Test
    fun `Given a valid step-up, Then requesting an export returns 202 with a pending export`() {
        // Given
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)

        // When / Then
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .`when`().post("/api/v1/me/exports")
            .then().statusCode(202)
            .body("id", notNullValue())
            .body("state", equalTo("PENDING"))
            .body("formatVersion", equalTo(1))
    }

    // --- POST: at most one PENDING export per user ---

    @Test
    fun `Given a PENDING export already exists, Then requesting another one returns 409`() {
        // Given: a PENDING row seeded directly, deterministically, instead of racing the real
        // async worker with two back-to-back POSTs (see the race-tolerant test below).
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)
        userDataExportRepository.save(pendingExportFor(auth.user.id))

        // When / Then
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .`when`().post("/api/v1/me/exports")
            .then().statusCode(409).body("code", equalTo("EXPORT_ALREADY_IN_PROGRESS"))
    }

    @Test
    fun `Given two POSTs fired back-to-back, Then the second is refused or the PENDING invariant holds`() {
        // Given: with exports.minimum_interval pinned to zero, only the PENDING guard can refuse
        // the second request. Whether it does is a genuine race against the real async worker
        // (tasks.poll_interval is PT0.05S in tests): if the worker claims and completes the first
        // export before the second POST's transaction runs, the first export is no longer PENDING
        // (it is READY), so the second legitimately supersedes it with a fresh PENDING row and
        // returns 202 instead of 409. Asserting one fixed status code here would be flaky.
        //
        // What must ALWAYS hold, regardless of who wins that race, is the invariant the partial
        // unique index enforces at the DB level: at most one PENDING export per user at any time.
        // That is what this test actually proves, rather than a specific HTTP status.
        val password = "password123"
        val auth = createAuthenticatedUser(password = password)

        // When
        given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .`when`().post("/api/v1/me/exports")
            .then().statusCode(202)
        val second = given()
            .authenticatedAs(auth)
            .header("X-Reauthentication", stepUp(password))
            .`when`().post("/api/v1/me/exports")
        val secondStatus = second.then().extract().statusCode()

        // Then
        assertTrue(secondStatus == 202 || secondStatus == 409, "expected 202 or 409, got $secondStatus")
        if (secondStatus == 409) second.then().body("code", equalTo("EXPORT_ALREADY_IN_PROGRESS"))
        val states = given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports")
            .then().extract().jsonPath().getList("exports.state", String::class.java)
        assertTrue(states.count { it == "PENDING" } <= 1, "at most one PENDING export must ever exist for a user")
    }

    // --- GET: list and by id ---

    @Test
    fun `Given a seeded export, Then listing exports includes it`() {
        // Given
        val auth = createAuthenticatedUser()
        val export = userDataExportRepository.save(pendingExportFor(auth.user.id))

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports")
            .then().statusCode(200).body("exports.id", hasItem(export.id.toString()))
    }

    @Test
    fun `Given a seeded export, Then getting it by id returns it`() {
        // Given
        val auth = createAuthenticatedUser()
        val export = userDataExportRepository.save(pendingExportFor(auth.user.id))

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports/${export.id}")
            .then().statusCode(200).body("id", equalTo(export.id.toString()))
    }

    @Test
    fun `Given another user's export id, Then getting it by id returns 403`() {
        // Given
        val owner = createAuthenticatedUser()
        val attacker = createAuthenticatedUser()
        val export = userDataExportRepository.save(pendingExportFor(owner.user.id))

        // When / Then
        given()
            .authenticatedAs(attacker)
            .`when`().get("/api/v1/me/exports/${export.id}")
            .then().statusCode(403).body("code", equalTo("EXPORT_INSUFFICIENT_PERMISSIONS"))
    }

    @Test
    fun `Given an unknown export id, Then getting it by id returns 404`() {
        // Given
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports/${UUID.randomUUID()}")
            .then().statusCode(404).body("code", equalTo("EXPORT_DOES_NOT_EXIST"))
    }

    // --- GET download: not ready ---

    @Test
    fun `Given a PENDING export, Then downloading it returns 409`() {
        // Given
        val auth = createAuthenticatedUser()
        val export = userDataExportRepository.save(pendingExportFor(auth.user.id))

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`().get("/api/v1/me/exports/${export.id}/download")
            .then().statusCode(409).body("code", equalTo("EXPORT_NOT_READY"))
    }

    // --- DELETE ---

    @Test
    fun `Given a seeded export, Then deleting it returns 204`() {
        // Given
        val auth = createAuthenticatedUser()
        val export = userDataExportRepository.save(pendingExportFor(auth.user.id))

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`().delete("/api/v1/me/exports/${export.id}")
            .then().statusCode(204)
    }
}
