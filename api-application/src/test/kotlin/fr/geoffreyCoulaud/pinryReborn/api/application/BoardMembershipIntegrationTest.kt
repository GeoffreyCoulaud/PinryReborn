package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
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
class BoardMembershipIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var userCreator: UserCreator

    @Inject
    lateinit var pinCreator: PinCreator

    @Inject
    lateinit var boardCreator: BoardCreator

    // --- Set boards on a pin ---

    @Test
    fun `Given two owned boards, Then setting them on a pin returns both in the pin's boards`() {
        // Given
        val username = "membershipsetter"
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

        // When
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board1.id}", "${board2.id}"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/boards")
            .then()
            .statusCode(200)
            .body("boards", hasSize<Any>(2))
            .body("boards.id", containsInAnyOrder(board1.id.toString(), board2.id.toString()))

        // Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/pins/${pin.id}")
            .then()
            .statusCode(200)
            .body("boards", hasSize<Any>(2))
            .body("boards.id", containsInAnyOrder(board1.id.toString(), board2.id.toString()))
    }

    @Test
    fun `Given two pins added to the same board, Then it lists both pins with pinCount 2`() {
        // Given
        val username = "membershipcounter"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board = boardCreator.create(author = user, name = "Shared board", description = "")
        val pin1 = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com/1",
            sourceMediaUrl = "https://example.com/img1.jpg",
            description = "Pin 1",
            tags = emptyList(),
        )
        val pin2 = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com/2",
            sourceMediaUrl = "https://example.com/img2.jpg",
            description = "Pin 2",
            tags = emptyList(),
        )
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin1.id}/boards")
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${board.id}"]}""")
            .put("/api/v1/pins/${pin2.id}/boards")

        // When / Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${board.id}/pins")
            .then()
            .statusCode(200)
            .body("pins", hasSize<Any>(2))
            .body("pins.id", containsInAnyOrder(pin1.id.toString(), pin2.id.toString()))

        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${board.id}")
            .then()
            .statusCode(200)
            .body("pinCount", equalTo(2))
    }

    // --- Empty board ---

    @Test
    fun `Given a board with no pins, Then listing its pins returns an empty page`() {
        // Given
        val username = "emptyboarduser"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board = boardCreator.create(author = user, name = "Empty", description = "")

        // When / Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${board.id}/pins")
            .then()
            .statusCode(200)
            .body("pins", emptyIterable<Any>())
    }

    // --- Invalid membership ---

    @Test
    fun `Given an unknown board id, Then setting it on a pin returns 400`() {
        // Given
        val username = "unknownboarduser"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val pin = pinCreator.createPin(
            author = user,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )

        // When / Then
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${UUID.randomUUID()}"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/boards")
            .then()
            .statusCode(400)
    }

    @Test
    fun `Given another user's board id, Then setting it on a pin returns 400`() {
        // Given
        val owner = userCreator.createUserWithPassword("membershipowner", "password123")
        val attacker = userCreator.createUserWithPassword("membershipattacker", "password456")
        val otherBoard = boardCreator.create(author = attacker, name = "Not yours", description = "")
        val pin = pinCreator.createPin(
            author = owner,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg",
            description = "Pin",
            tags = emptyList(),
        )

        // When / Then
        given()
            .auth().preemptive().basic("membershipowner", "password123")
            .contentType(ContentType.JSON)
            .body("""{"boardIds": ["${otherBoard.id}"]}""")
            .`when`()
            .put("/api/v1/pins/${pin.id}/boards")
            .then()
            .statusCode(400)
    }
}
