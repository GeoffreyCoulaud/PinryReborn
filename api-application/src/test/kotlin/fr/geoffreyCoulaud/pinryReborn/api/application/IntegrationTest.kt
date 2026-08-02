package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.ebean.DB
import io.ebean.Database
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach

@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a shared test base for concrete subclasses.
abstract class IntegrationTest {
    @Inject
    lateinit var userCreator: UserCreator

    private val database: Database get() = DB.getDefault()

    /** A created user together with a live bearer token for it. */
    data class AuthenticatedUser(val user: User, val token: String)

    /**
     * Truncate all non-internal tables in the database.
     *
     * - Tables prefixed by "sqlite_" are ignored.
     * - The "db_migration" table is ignored, as it's necessary for ebean.
     */
    @BeforeEach
    fun truncateAllTables() {
        database
            .sqlQuery("SELECT name FROM sqlite_master WHERE type='table'")
            .findList()
            .map { it.getString("name") }
            .filterNot { it.startsWith("sqlite_") or it.equals("db_migration") }
            .forEach { database.truncate(it) }
    }

    /** Create a user and log it in, returning the user and a bearer token. */
    protected fun createAuthenticatedUser(
        name: String = createRandomString(),
        password: String = DEFAULT_PASSWORD,
        rememberMe: Boolean = false,
    ): AuthenticatedUser {
        val user = userCreator.createUserWithPassword(name = name, password = password)
        val token = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to name, "password" to password, "rememberMe" to rememberMe))
            .post("/api/v1/sessions")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("token")
        return AuthenticatedUser(user, token)
    }

    /** Attach `Authorization: Bearer <token>` to a REST-Assured request. */
    protected fun RequestSpecification.authenticatedAs(auth: AuthenticatedUser): RequestSpecification =
        header("Authorization", "Bearer ${auth.token}")

    /**
     * Wait long enough for the next stamped instant to differ from the previous one.
     *
     * Stamped instants are truncated to the millisecond, so two writes inside the same millisecond
     * carry the same value and an "instant moved" assertion cannot tell a fresh stamp from a stale
     * one.
     */
    protected fun waitForTheClockToTick() = Thread.sleep(CLOCK_RESOLUTION_MILLIS)

    companion object {
        const val DEFAULT_PASSWORD = "password123"
        private const val HTTP_CREATED = 201

        /** Long enough to cross a millisecond boundary, the resolution stamped instants keep. */
        private const val CLOCK_RESOLUTION_MILLIS = 2L
    }
}
