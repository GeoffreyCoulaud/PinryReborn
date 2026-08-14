package fr.geoffreyCoulaud.pinryReborn.api.application

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The CDI [ObjectMapper] binds every REST request body and every base64 cursor parameter.
 * `quarkus-kotlin` registers `KotlinModule` on it as soon as `jackson-module-kotlin` sits on the
 * runtime classpath, with no opt-in, and the import reader's dependency put it there. It is kept
 * deliberately, stricter null handling on Kotlin DTOs being what the boundary rule wants, and pinned
 * here because nothing else in this repository would notice it arriving or leaving.
 */
@QuarkusTest
class JsonBindingIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var mapper: ObjectMapper

    @Test
    fun `Given the CDI mapper, Then it carries the Kotlin module and nothing beyond what Quarkus adds`() {
        // Given: an unnamed SimpleModule takes a JVM-global counter for a name, so it is matched by
        // prefix and counted; every other id is stable.
        val ids = mapper.registeredModuleIds.map { it.toString() }

        // When / Then
        assertEquals(
            setOf(
                "VertxTypes",
                "com.fasterxml.jackson.module.kotlin.KotlinModule",
                "jackson-datatype-jsr310",
                "com.fasterxml.jackson.datatype.jdk8.Jdk8Module",
                "jackson-module-parameter-names",
            ),
            ids.filterNot { it.startsWith("SimpleModule") }.toSet(),
        )
        assertEquals(1, ids.count { it.startsWith("SimpleModule") })
    }

    @Test
    fun `Given a null where a non-nullable property is expected, Then the request is refused naming it`() {
        // Given: `description` is a non-nullable String carrying no @NotBlank, so it is where the
        // Kotlin module changes what a client sees. The status was already 400 without the module,
        // from Kotlin's constructor null check, but the body was empty.
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"name": "Travel", "description": null}""")
            .`when`()
            .post("/api/v1/boards")
            .then()
            .statusCode(400)
            .body("attributeName", equalTo("description"))
    }
}
