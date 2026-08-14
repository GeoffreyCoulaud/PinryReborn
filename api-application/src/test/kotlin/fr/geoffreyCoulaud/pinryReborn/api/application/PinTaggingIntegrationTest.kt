package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.emptyIterable
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class PinTaggingIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    @Test
    fun `setting tags returns 200 with updated tags`() {
        val auth = createAuthenticatedUser()

        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/page",
            sourceMediaUrl = "https://example.com/image.jpg",
            description = "My pin",
            tags = emptyList()
        )

        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"tags": ["nature", "landscape"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/tags")
            .then()
            .statusCode(200)
            .body("id", equalTo(pin.id.toString()))
            .body("tags", hasSize<Any>(2))
            .body("tags.name", containsInAnyOrder("nature", "landscape"))
    }

    @Test
    fun `Given a stored tag, Then tagging with a different case reuses it and returns its spelling`() {
        // Given: a pin already carrying `landscape`, stored in that spelling.
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/page",
            sourceMediaUrl = "https://example.com/image.jpg",
            description = "My pin",
            tags = listOf("landscape"),
        )

        // When: the client tags a second pin with the same name in another case.
        val other = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/other",
            sourceMediaUrl = "https://example.com/other.jpg",
            description = "Another pin",
            tags = emptyList(),
        )
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"tags": ["Landscape"]}""")
            .`when`()
            .put("/api/v1/pins/${other.id}/tags")
            .then()
            .statusCode(200)
            // Then: the stored spelling comes back, not the one that was sent. Before the fold
            // reached the read, this created a second tag named `Landscape`.
            .body("tags", hasSize<Any>(1))
            .body("tags.name", containsInAnyOrder("landscape"))

        // And: the first pin still carries the one tag, which is the same row.
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(200)
            .body("tags.name", containsInAnyOrder("landscape"))
    }

    @Test
    fun `setting tags replaces existing tags`() {
        val auth = createAuthenticatedUser()

        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/page",
            sourceMediaUrl = "https://example.com/image.jpg",
            description = "My pin",
            tags = listOf("oldtag1", "oldtag2")
        )

        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"tags": ["newtag"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/tags")
            .then()
            .statusCode(200)
            .body("tags", hasSize<Any>(1))
            .body("tags[0].name", equalTo("newtag"))
    }

    @Test
    fun `setting empty tags clears all tags`() {
        val auth = createAuthenticatedUser()

        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/page",
            sourceMediaUrl = "https://example.com/image.jpg",
            description = "My pin",
            tags = listOf("tag1", "tag2")
        )

        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"tags": []}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/tags")
            .then()
            .statusCode(200)
            .body("tags", emptyIterable<Any>())
    }

    @Test
    fun `setting tags on another user's pin returns 403`() {
        val owner = createAuthenticatedUser()
        val attacker = createAuthenticatedUser()

        val pin = pinCreator.createPin(
            author = owner.user,
            sourceContextUrl = "https://example.com/page",
            sourceMediaUrl = "https://example.com/image.jpg",
            description = "Owner's pin",
            tags = emptyList()
        )

        given()
            .authenticatedAs(attacker)
            .contentType(ContentType.JSON)
            .body("""{"tags": ["hacked"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/tags")
            .then()
            .statusCode(403)
    }

    @Test
    fun `setting tags on non-existent pin returns 404`() {
        val auth = createAuthenticatedUser()

        val nonExistentPinId = UUID.randomUUID()

        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"tags": ["tag"]}""")
            .`when`()
            .put("/api/v1/pins/$nonExistentPinId/tags")
            .then()
            .statusCode(404)
    }

    @Test
    fun `unauthenticated request returns 401`() {
        val auth = createAuthenticatedUser()

        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/page",
            sourceMediaUrl = "https://example.com/image.jpg",
            description = "My pin",
            tags = emptyList()
        )

        given()
            .contentType(ContentType.JSON)
            .body("""{"tags": ["tag"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/tags")
            .then()
            .statusCode(401)
    }
}
