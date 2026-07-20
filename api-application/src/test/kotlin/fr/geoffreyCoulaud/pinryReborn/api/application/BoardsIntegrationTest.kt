package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
class BoardsIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var userCreator: UserCreator

    @Inject
    lateinit var boardCreator: BoardCreator

    // --- Create ---

    @Test
    fun `Given valid board data, Then creation returns 201 with Location and zero pin count`() {
        // Given
        val username = "boardcreator"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        // When / Then
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"name": "Travel", "description": "Places to visit"}""")
            .`when`()
            .post("/api/v1/boards")
            .then()
            .statusCode(201)
            .header("Location", notNullValue())
            .body("id", notNullValue())
            .body("name", equalTo("Travel"))
            .body("description", equalTo("Places to visit"))
            .body("pinCount", equalTo(0))
    }

    // --- Get ---

    @Test
    fun `Given a created board, Then GET by id returns its data`() {
        // Given
        val username = "boardgetter"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board = boardCreator.create(author = user, name = "Recipes", description = "Cooking ideas")

        // When / Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${board.id}")
            .then()
            .statusCode(200)
            .body("id", equalTo(board.id.toString()))
            .body("name", equalTo("Recipes"))
            .body("description", equalTo("Cooking ideas"))
            .body("pinCount", equalTo(0))
    }

    // --- List ---

    @Test
    fun `Given boards with mixed-case names, Then listing sorts them case-insensitively`() {
        // Given
        val username = "boardlister"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        boardCreator.create(author = user, name = "Banana", description = "")
        boardCreator.create(author = user, name = "apple", description = "")
        boardCreator.create(author = user, name = "cherry", description = "")

        // When / Then
        // Naive case-sensitive ASCII order would be "Banana", "apple", "cherry" (B=66 < a=97 < c=99).
        // The correct case-insensitive order is "apple", "Banana", "cherry", so this data discriminates
        // a correct implementation from a regression to case-sensitive sorting.
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards")
            .then()
            .statusCode(200)
            .body("boards.name", contains("apple", "Banana", "cherry"))
    }

    // --- Update ---

    @Test
    fun `Given an existing board, Then updating its name and description is reflected on get`() {
        // Given
        val username = "boardupdater"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)
        val board = boardCreator.create(author = user, name = "Old name", description = "Old description")

        // When
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"name": "New name", "description": "New description"}""")
            .`when`()
            .put("/api/v1/boards/${board.id}")
            .then()
            .statusCode(200)
            .body("name", equalTo("New name"))
            .body("description", equalTo("New description"))

        // Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${board.id}")
            .then()
            .statusCode(200)
            .body("name", equalTo("New name"))
            .body("description", equalTo("New description"))
    }

    // --- Owner scoping ---

    @Test
    fun `Given another user's board, Then getting it returns 403`() {
        // Given
        val owner = userCreator.createUserWithPassword("boardowner1", "password123")
        userCreator.createUserWithPassword("boardattacker1", "password456")
        val board = boardCreator.create(author = owner, name = "Private", description = "")

        // When / Then
        given()
            .auth().preemptive().basic("boardattacker1", "password456")
            .`when`()
            .get("/api/v1/boards/${board.id}")
            .then()
            .statusCode(403)
    }

    @Test
    fun `Given an unknown board id, Then getting it returns 404`() {
        // Given
        val username = "boardgetter404"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        // When / Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .get("/api/v1/boards/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }

    @Test
    fun `Given another user's board, Then updating it returns 403`() {
        // Given
        val owner = userCreator.createUserWithPassword("boardowner2", "password123")
        userCreator.createUserWithPassword("boardattacker2", "password456")
        val board = boardCreator.create(author = owner, name = "Private", description = "")

        // When / Then
        given()
            .auth().preemptive().basic("boardattacker2", "password456")
            .contentType(ContentType.JSON)
            .body("""{"name": "Hacked", "description": ""}""")
            .`when`()
            .put("/api/v1/boards/${board.id}")
            .then()
            .statusCode(403)
    }

    @Test
    fun `Given an unknown board id, Then updating it returns 404`() {
        // Given
        val username = "boardupdater404"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        // When / Then
        given()
            .auth().preemptive().basic(username, password)
            .contentType(ContentType.JSON)
            .body("""{"name": "Ghost", "description": ""}""")
            .`when`()
            .put("/api/v1/boards/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }

    @Test
    fun `Given another user's board, Then deleting it returns 403`() {
        // Given
        val owner = userCreator.createUserWithPassword("boardowner3", "password123")
        userCreator.createUserWithPassword("boardattacker3", "password456")
        val board = boardCreator.create(author = owner, name = "Private", description = "")

        // When / Then
        given()
            .auth().preemptive().basic("boardattacker3", "password456")
            .`when`()
            .delete("/api/v1/boards/${board.id}")
            .then()
            .statusCode(403)
    }

    @Test
    fun `Given an unknown board id, Then deleting it returns 404`() {
        // Given
        val username = "boarddeleter404"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        // When / Then
        given()
            .auth().preemptive().basic(username, password)
            .`when`()
            .delete("/api/v1/boards/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }
}
