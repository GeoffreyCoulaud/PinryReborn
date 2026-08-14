package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
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
    lateinit var boardCreator: BoardCreator

    // --- Create ---

    @Test
    fun `Given valid board data, Then creation returns 201 with Location and zero pin count`() {
        // Given
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
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

    @Test
    fun `Given a name already held up to case, Then creating another board returns 409`() {
        // Given: the unique index folds A to Z, so these two names are one name
        val auth = createAuthenticatedUser()
        boardCreator.create(author = auth.user, name = "voyage", description = "")

        // When / Then
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"name": "Voyage", "description": ""}""")
            .`when`()
            .post("/api/v1/boards")
            .then()
            .statusCode(409)
            .body("code", equalTo("BOARD_NAME_ALREADY_EXISTS"))
    }

    @Test
    fun `Given two boards, Then renaming one onto the other's name returns 409`() {
        // Given
        val auth = createAuthenticatedUser()
        boardCreator.create(author = auth.user, name = "voyage", description = "")
        val renamed = boardCreator.create(author = auth.user, name = "cuisine", description = "")

        // When / Then
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"name": "Voyage", "description": ""}""")
            .`when`()
            .put("/api/v1/boards/${renamed.id}")
            .then()
            .statusCode(409)
            .body("code", equalTo("BOARD_NAME_ALREADY_EXISTS"))
    }

    @Test
    fun `Given another user holding the name, Then creating a board with it succeeds`() {
        // Given: the index is scoped to the author, so a name is an identity per account
        val other = createAuthenticatedUser()
        val auth = createAuthenticatedUser()
        boardCreator.create(author = other.user, name = "voyage", description = "")

        // When / Then
        given()
            .authenticatedAs(auth)
            .contentType(ContentType.JSON)
            .body("""{"name": "voyage", "description": ""}""")
            .`when`()
            .post("/api/v1/boards")
            .then()
            .statusCode(201)
    }

    // --- Get ---

    @Test
    fun `Given a created board, Then GET by id returns its data`() {
        // Given
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Recipes", description = "Cooking ideas")

        // When / Then
        given()
            .authenticatedAs(auth)
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
        val auth = createAuthenticatedUser()
        boardCreator.create(author = auth.user, name = "Banana", description = "")
        boardCreator.create(author = auth.user, name = "apple", description = "")
        boardCreator.create(author = auth.user, name = "cherry", description = "")

        // When / Then
        // Naive case-sensitive ASCII order would be "Banana", "apple", "cherry" (B=66 < a=97 < c=99).
        // The correct case-insensitive order is "apple", "Banana", "cherry", so this data discriminates
        // a correct implementation from a regression to case-sensitive sorting.
        given()
            .authenticatedAs(auth)
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
        val auth = createAuthenticatedUser()
        val board = boardCreator.create(author = auth.user, name = "Old name", description = "Old description")

        // When
        given()
            .authenticatedAs(auth)
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
            .authenticatedAs(auth)
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
        val owner = createAuthenticatedUser()
        val attacker = createAuthenticatedUser()
        val board = boardCreator.create(author = owner.user, name = "Private", description = "")

        // When / Then
        given()
            .authenticatedAs(attacker)
            .`when`()
            .get("/api/v1/boards/${board.id}")
            .then()
            .statusCode(403)
    }

    @Test
    fun `Given an unknown board id, Then getting it returns 404`() {
        // Given
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .get("/api/v1/boards/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }

    @Test
    fun `Given another user's board, Then updating it returns 403`() {
        // Given
        val owner = createAuthenticatedUser()
        val attacker = createAuthenticatedUser()
        val board = boardCreator.create(author = owner.user, name = "Private", description = "")

        // When / Then
        given()
            .authenticatedAs(attacker)
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
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
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
        val owner = createAuthenticatedUser()
        val attacker = createAuthenticatedUser()
        val board = boardCreator.create(author = owner.user, name = "Private", description = "")

        // When / Then
        given()
            .authenticatedAs(attacker)
            .`when`()
            .delete("/api/v1/boards/${board.id}")
            .then()
            .statusCode(403)
    }

    @Test
    fun `Given an unknown board id, Then deleting it returns 404`() {
        // Given
        val auth = createAuthenticatedUser()

        // When / Then
        given()
            .authenticatedAs(auth)
            .`when`()
            .delete("/api/v1/boards/${UUID.randomUUID()}")
            .then()
            .statusCode(404)
    }
}
