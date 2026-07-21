package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.emptyIterable
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class PinSoftDeleteIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    // --- Soft delete ---

    @Test
    fun `Given own pin, Then soft delete returns 204 and pin no longer in listing`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "To be deleted",
            tags = emptyList(),
        )

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(204)

        // Then - pin no longer in listing
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)
            .body("pins", emptyIterable<Any>())
    }

    @Test
    fun `Given soft-deleted pin, Then GET by id still returns it with softDeletedAt set`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Still accessible",
            tags = emptyList(),
        )

        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(204)

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(200)
            .body("id", equalTo(pin.id.toString()))
            .body("softDeletedAt", notNullValue())
    }

    @Test
    fun `Given soft-deleted pin, Then pin excluded from search`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Beautiful landscape painting",
            tags = emptyList(),
        )

        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(204)

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/search?q=landscape")
            .then()
            .statusCode(200)
            .body("results", emptyIterable<Any>())
    }

    @Test
    fun `Given already soft-deleted pin, Then soft delete returns 409`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Double delete",
            tags = emptyList(),
        )

        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(204)

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(409)
    }

    @Test
    fun `Given pin not owned by user, Then soft delete returns 403`() {
        // Given
        val owner = createAuthenticatedUser()
        val other = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = owner.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Not yours",
            tags = emptyList(),
        )

        // When / Then
        given()
            .authenticatedAs(other)
            .`when`()
            .delete("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(403)
    }

    @Test
    fun `Given unauthenticated request, Then soft delete returns 401`() {
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Unauth",
            tags = emptyList(),
        )

        given()
            .`when`()
            .delete("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(401)
    }

    // --- Recycle bin listing ---

    @Test
    fun `Given soft-deleted pins, Then recycle bin lists them paginated`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin1 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/1",
            sourceMediaUrl = "https://example.com/img1.jpg",
            description = "Deleted 1",
            tags = emptyList(),
        )
        val pin2 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/2",
            sourceMediaUrl = "https://example.com/img2.jpg",
            description = "Deleted 2",
            tags = emptyList(),
        )

        given().authenticatedAs(auth).delete("/api/v1/pins/${pin1.id}")
        given().authenticatedAs(auth).delete("/api/v1/pins/${pin2.id}")

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/recycled")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(2))
    }

    @Test
    fun `Given soft-deleted pins, Then default sort is most recently deleted first`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin1 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/1",
            sourceMediaUrl = "https://example.com/img1.jpg",
            description = "Deleted first",
            tags = emptyList(),
        )
        val pin2 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/2",
            sourceMediaUrl = "https://example.com/img2.jpg",
            description = "Deleted second",
            tags = emptyList(),
        )

        given().authenticatedAs(auth).delete("/api/v1/pins/${pin1.id}")
        Thread.sleep(2)
        given().authenticatedAs(auth).delete("/api/v1/pins/${pin2.id}")

        // When / Then - default sort should be DELETED_AT_DESC (most recently deleted first)
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/recycled")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(2))
            .body("pins[0].id", equalTo(pin2.id.toString()))
            .body("pins[1].id", equalTo(pin1.id.toString()))
    }

    @Test
    fun `Given soft-deleted pins, Then explicit DELETED_AT_DESC sort works`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin1 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/1",
            sourceMediaUrl = "https://example.com/img1.jpg",
            description = "Deleted first",
            tags = emptyList(),
        )
        val pin2 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/2",
            sourceMediaUrl = "https://example.com/img2.jpg",
            description = "Deleted second",
            tags = emptyList(),
        )

        given().authenticatedAs(auth).delete("/api/v1/pins/${pin1.id}")
        Thread.sleep(2)
        given().authenticatedAs(auth).delete("/api/v1/pins/${pin2.id}")

        // When / Then
        given()
            .authenticatedAs(auth)
            .queryParam("sort", "DELETED_AT_DESC")
            .`when`()
            .get("/api/v1/pins/recycled")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(2))
            .body("pins[0].id", equalTo(pin2.id.toString()))
            .body("pins[1].id", equalTo(pin1.id.toString()))
    }

    @Test
    fun `Given soft-deleted pins, Then CREATED_AT_ASC sort still works on recycle bin`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin1 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/1",
            sourceMediaUrl = "https://example.com/img1.jpg",
            description = "Created first",
            tags = emptyList(),
        )
        Thread.sleep(2)
        val pin2 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/2",
            sourceMediaUrl = "https://example.com/img2.jpg",
            description = "Created second",
            tags = emptyList(),
        )

        given().authenticatedAs(auth).delete("/api/v1/pins/${pin2.id}")
        given().authenticatedAs(auth).delete("/api/v1/pins/${pin1.id}")

        // When / Then - CREATED_AT_ASC should sort by creation date regardless of deletion order
        given()
            .authenticatedAs(auth)
            .queryParam("sort", "CREATED_AT_ASC")
            .`when`()
            .get("/api/v1/pins/recycled")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(2))
            .body("pins[0].id", equalTo(pin1.id.toString()))
            .body("pins[1].id", equalTo(pin2.id.toString()))
    }

    // --- Restore ---

    @Test
    fun `Given soft-deleted pin, Then restore returns 200 and pin back in normal listing`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "To restore",
            tags = emptyList(),
        )

        given().authenticatedAs(auth).delete("/api/v1/pins/${pin.id}")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .post("/api/v1/pins/recycled/${pin.id}/restore")
            .then()
            .statusCode(200)
            .body("id", equalTo(pin.id.toString()))
            .body("softDeletedAt", nullValue())

        // Then - back in normal listing
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(1))
    }

    @Test
    fun `Given active pin, Then restore returns 409`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Active",
            tags = emptyList(),
        )

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .post("/api/v1/pins/recycled/${pin.id}/restore")
            .then()
            .statusCode(409)
    }

    // --- Permanent delete ---

    @Test
    fun `Given soft-deleted pin, Then permanent delete returns 204 and pin gone entirely`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Permanent delete",
            tags = emptyList(),
        )

        given().authenticatedAs(auth).delete("/api/v1/pins/${pin.id}")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/recycled/${pin.id}")
            .then()
            .statusCode(204)

        // Then - gone entirely
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(404)
    }

    @Test
    fun `Given active pin, Then permanent delete returns 409`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Still active",
            tags = emptyList(),
        )

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/recycled/${pin.id}")
            .then()
            .statusCode(409)
    }

    // --- Empty recycle bin ---

    @Test
    fun `Given user with soft-deleted pins, Then empty recycle bin returns 204 and all gone`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin1 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/1",
            sourceMediaUrl = "https://example.com/img1.jpg",
            description = "Bin 1",
            tags = emptyList(),
        )
        val pin2 = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com/2",
            sourceMediaUrl = "https://example.com/img2.jpg",
            description = "Bin 2",
            tags = emptyList(),
        )

        given().authenticatedAs(auth).delete("/api/v1/pins/${pin1.id}")
        given().authenticatedAs(auth).delete("/api/v1/pins/${pin2.id}")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/recycled")
            .then()
            .statusCode(204)

        // Then - recycle bin empty
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/recycled")
            .then()
            .statusCode(200)
            .body("pins", emptyIterable<Any>())
    }

    // --- Tag soft-deleted pin ---

    @Test
    fun `Given soft-deleted pin, Then tagging returns 409`() {
        // Given
        val auth = createAuthenticatedUser()
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Tag deleted",
            tags = emptyList(),
        )

        given().authenticatedAs(auth).delete("/api/v1/pins/${pin.id}")

        // When / Then
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"tags": ["newtag"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/tags")
            .then()
            .statusCode(409)
    }
}
