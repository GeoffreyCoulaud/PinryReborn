package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.emptyIterable
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

@QuarkusTest
class BoardRecycleBinIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var userCreator: UserCreator

    @Inject
    lateinit var pinCreator: PinCreator

    @Inject
    lateinit var boardCreator: BoardCreator

    // --- Soft delete ---

    @Test
    fun `Given an owned board with a pin, Then soft delete hides it from the board list but keeps the pin active`() {
        // Given
        val username = "recyclesoftdel"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board = boardCreator.create(author = user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")

        // When
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .delete("/api/v1/boards/${board.id}")
            .then()
            .statusCode(204)

        // Then - hidden from the board list and no longer directly reachable
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards")
            .then()
            .statusCode(200)
            .body("boards", emptyIterable<Any>())

        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${board.id}")
            .then()
            .statusCode(404)

        // Then - its pins stay active in the feed
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(1))
            .body("pins[0].id", equalTo(pin.id.toString()))

        // Then - listed in the recycle bin
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/recycled")
            .then()
            .statusCode(200)
            .body("boards", hasSize<Any>(1))
            .body("boards[0].id", equalTo(board.id.toString()))
    }

    // --- Restore ---

    @Test
    fun `Given a soft-deleted board, Then restoring it brings back its membership`() {
        // Given
        val username = "recyclerestore"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board = boardCreator.create(author = user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")
        given().auth().preemptive().basic(username, password).delete("/api/v1/boards/${board.id}")

        // When
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .post("/api/v1/boards/recycled/${board.id}/restore")
            .then()
            .statusCode(200)
            .body("id", equalTo(board.id.toString()))
            .body("pinCount", equalTo(1))

        // Then - back in the board list, membership intact
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards")
            .then()
            .statusCode(200)
            .body("boards", hasSize<Any>(1))

        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${board.id}/pins")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(1))
            .body("pins[0].id", equalTo(pin.id.toString()))
    }

    // --- Permanent delete ---

    @Test
    fun `Given a soft-deleted board, Then permanent delete drops it from its pins' boards but the pins survive`() {
        // Given
        val username = "recyclepermdel"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board = boardCreator.create(author = user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")
        given().auth().preemptive().basic(username, password).delete("/api/v1/boards/${board.id}")

        // When
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .delete("/api/v1/boards/recycled/${board.id}")
            .then()
            .statusCode(204)

        // Then - the pin survives, without the deleted board
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(200)
            .body("id", equalTo(pin.id.toString()))
            .body("boards", emptyIterable<Any>())

        // Then - gone from the recycle bin
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/recycled")
            .then()
            .statusCode(200)
            .body("boards", emptyIterable<Any>())
    }

    // --- Empty recycle bin ---

    @Test
    fun `Given multiple soft-deleted boards, Then emptying the recycle bin removes them all`() {
        // Given
        val username = "recycleemptybin"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board1 = boardCreator.create(author = user, name = "Board 1", description = "")
        val board2 = boardCreator.create(author = user, name = "Board 2", description = "")
        given().auth().preemptive().basic(username, password).delete("/api/v1/boards/${board1.id}")
        given().auth().preemptive().basic(username, password).delete("/api/v1/boards/${board2.id}")

        // When
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .delete("/api/v1/boards/recycled")
            .then()
            .statusCode(204)

        // Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/recycled")
            .then()
            .statusCode(200)
            .body("boards", emptyIterable<Any>())
    }

    // --- Pin recycle bin interactions ---

    @Test
    fun `Given a pin in a board, Then permanently deleting the pin removes it from the board's pin listing`() {
        // Given
        val username = "recyclepindel"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board = boardCreator.create(author = user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")
        given().auth().preemptive().basic(username, password).delete("/api/v1/pins/${pin.id}")

        // When
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .delete("/api/v1/pins/recycled/${pin.id}")
            .then()
            .statusCode(204)

        // Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${board.id}/pins")
            .then()
            .statusCode(200)
            .body("pins", emptyIterable<Any>())
    }

    @Test
    fun `Given a pin in two boards, Then soft-deleting one board drops it from the pin's boards but keeps the other`() {
        // Given
        val username = "recycleonebrd"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board1 = boardCreator.create(author = user, name = "Board 1", description = "")
        val board2 = boardCreator.create(author = user, name = "Board 2", description = "")
        val pin = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board1.id}", "${board2.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")

        // When
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .delete("/api/v1/boards/${board1.id}")
            .then()
            .statusCode(204)

        // Then - the recycled board never appears in the pin's boards
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(200)
            .body("boards", hasSize<Any>(1))
            .body("boards[0].id", equalTo(board2.id.toString()))
    }
}
