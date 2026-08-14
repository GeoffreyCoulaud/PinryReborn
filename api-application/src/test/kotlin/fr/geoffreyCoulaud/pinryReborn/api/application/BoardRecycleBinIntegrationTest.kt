package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.emptyIterable
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class BoardRecycleBinIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var pinCreator: PinCreator

    @Inject
    lateinit var boardCreator: BoardCreator

    @Inject
    lateinit var boardRepository: BoardRepositoryInterface

    /** The stored board, whatever its state. `updatedAt` is on no output DTO, so it is read here. */
    private fun reloadBoard(boardId: UUID): Board =
        requireNotNull(boardRepository.findBoardById(boardId)) { "the board should still be stored" }

    // --- Soft delete ---

    @Test
    fun `Given an owned board with a pin, Then soft delete hides it from the board list but keeps the pin active`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/boards/${board.id}")
            .then()
            .statusCode(204)

        // Then - hidden from the board list and no longer directly reachable
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards")
            .then()
            .statusCode(200)
            .body("boards", emptyIterable<Any>())

        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards/${board.id}")
            .then()
            .statusCode(404)

        // Then - its pins stay active in the feed
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(1))
            .body("pins[0].id", equalTo(pin.id.toString()))

        // Then - listed in the recycle bin
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards/recycled")
            .then()
            .statusCode(200)
            .body("boards", hasSize<Any>(1))
            .body("boards[0].id", equalTo(board.id.toString()))
    }

    @Test
    fun `Given an active board, Then soft delete moves its updatedAt to the deletion instant`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Trip", description = "")
        val updatedAtBeforeDeletion = reloadBoard(board.id).updatedAt
        waitForTheClockToTick()

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/boards/${board.id}")
            .then()
            .statusCode(204)

        // Then - recycling is a modification, and both instants come from the same stamp
        val recycled = reloadBoard(board.id)
        assertTrue(
            recycled.updatedAt.isAfter(updatedAtBeforeDeletion),
            "updatedAt should move when the board is recycled",
        )
        assertEquals(recycled.softDeletedAt, recycled.updatedAt)
    }

    // --- Restore ---

    @Test
    fun `Given a recycled board, Then restoring it moves its updatedAt again`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Trip", description = "")
        given().authenticatedAs(auth).delete("/api/v1/boards/${board.id}")
        val updatedAtWhileRecycled = reloadBoard(board.id).updatedAt
        waitForTheClockToTick()

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .post("/api/v1/boards/recycled/${board.id}/restore")
            .then()
            .statusCode(200)

        // Then
        assertTrue(
            reloadBoard(board.id).updatedAt.isAfter(updatedAtWhileRecycled),
            "updatedAt should move when the board is restored",
        )
    }

    @Test
    fun `Given a soft-deleted board, Then restoring it brings back its membership`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")
        given().authenticatedAs(auth).delete("/api/v1/boards/${board.id}")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .post("/api/v1/boards/recycled/${board.id}/restore")
            .then()
            .statusCode(200)
            .body("id", equalTo(board.id.toString()))
            .body("pinCount", equalTo(1))

        // Then - back in the board list, membership intact
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards")
            .then()
            .statusCode(200)
            .body("boards", hasSize<Any>(1))

        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards/${board.id}/pins")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(1))
            .body("pins[0].id", equalTo(pin.id.toString()))
    }

    // --- Names ---

    @Test
    fun `Given a recycled board, Then it holds its name until the bin is emptied`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "voyage", description = "")
        given().authenticatedAs(auth).delete("/api/v1/boards/${board.id}")

        // When / Then - the recycled homonym blocks the new board, and the detail says so
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"name": "Voyage", "description": ""}""")
            .`when`()
            .post("/api/v1/boards")
            .then()
            .statusCode(409)
            .body("code", equalTo("BOARD_NAME_ALREADY_EXISTS"))
            .body("detail", containsString("recycle bin"))

        // Then - emptying the bin releases the name
        given().authenticatedAs(auth).delete("/api/v1/boards/recycled").then().statusCode(204)
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"name": "Voyage", "description": ""}""")
            .`when`()
            .post("/api/v1/boards")
            .then()
            .statusCode(201)
    }

    // --- Permanent delete ---

    @Test
    fun `Given a soft-deleted board, Then permanent delete drops it from its pins' boards but the pins survive`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")
        given().authenticatedAs(auth).delete("/api/v1/boards/${board.id}")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/boards/recycled/${board.id}")
            .then()
            .statusCode(204)

        // Then - the pin survives, without the deleted board
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(200)
            .body("id", equalTo(pin.id.toString()))
            .body("boards", emptyIterable<Any>())

        // Then - gone from the recycle bin
        given()
            .authenticatedAs(auth)
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
        val auth = createAuthenticatedUser()
        val board1 = boardCreator.create(author = auth.user, name = "Board 1", description = "")
        val board2 = boardCreator.create(author = auth.user, name = "Board 2", description = "")
        given().authenticatedAs(auth).delete("/api/v1/boards/${board1.id}")
        given().authenticatedAs(auth).delete("/api/v1/boards/${board2.id}")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/boards/recycled")
            .then()
            .statusCode(204)

        // Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards/recycled")
            .then()
            .statusCode(200)
            .body("boards", emptyIterable<Any>())
    }

    // --- Error semantics ---

    @Test
    fun `Given an already soft-deleted board, Then soft-deleting it again returns 409`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Trip", description = "")
        given()
            .authenticatedAs(auth)
            .delete("/api/v1/boards/${board.id}")
            .then()
            .statusCode(204)

        // When / Then - a second soft-delete conflicts instead of 404-ing
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/boards/${board.id}")
            .then()
            .statusCode(409)
    }

    @Test
    fun `Given another user's recycled board, Then permanently deleting it returns 403`() {
        // Given
        val owner = createAuthenticatedUser()
        val attacker = createAuthenticatedUser()
        val board = boardCreator.create(author = owner.user, name = "Private", description = "")
        given().authenticatedAs(owner).delete("/api/v1/boards/${board.id}")

        // When / Then - ownership is checked before state, so a non-owner gets 403 not 404
        given()
            .authenticatedAs(attacker)
            .`when`()
            .delete("/api/v1/boards/recycled/${board.id}")
            .then()
            .statusCode(403)
    }

    // --- Pin recycle bin interactions ---

    @Test
    fun `Given a pin in a board, Then permanently deleting the pin removes it from the board's pin listing`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")
        given().authenticatedAs(auth).delete("/api/v1/pins/${pin.id}")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/pins/recycled/${pin.id}")
            .then()
            .statusCode(204)

        // Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards/${board.id}/pins")
            .then()
            .statusCode(200)
            .body("pins", emptyIterable<Any>())
    }

    @Test
    fun `Given a pin in a soft-deleted board, Then re-saving the pin and restoring the board keeps the membership`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Trip", description = "")
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")
        given().authenticatedAs(auth).delete("/api/v1/boards/${board.id}")

        // When - re-saving the pin (setting tags) must not drop the recycled board's join row
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"tags": ["nature"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/tags")
            .then()
            .statusCode(200)
        given()
            .authenticatedAs(auth)
            .post("/api/v1/boards/recycled/${board.id}/restore")

        // Then - the pin is still listed under the restored board
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards/${board.id}/pins")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(1))
            .body("pins[0].id", equalTo(pin.id.toString()))
    }

    @Test
    fun `Given a pin in two boards, Then soft-deleting one board drops it from the pin's boards but keeps the other`() {
        // Given
        val auth = createAuthenticatedUser()
        val board1 = boardCreator.create(author = auth.user, name = "Board 1", description = "")
        val board2 = boardCreator.create(author = auth.user, name = "Board 2", description = "")
        val pin = pinCreator.createPin(
            author = auth.user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board1.id}", "${board2.id}"]}""")
            .put("/api/v1/pins/${pin.id}/boards")

        // When
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/boards/${board1.id}")
            .then()
            .statusCode(204)

        // Then - the recycled board never appears in the pin's boards
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(200)
            .body("boards", hasSize<Any>(1))
            .body("boards[0].id", equalTo(board2.id.toString()))
    }
}
