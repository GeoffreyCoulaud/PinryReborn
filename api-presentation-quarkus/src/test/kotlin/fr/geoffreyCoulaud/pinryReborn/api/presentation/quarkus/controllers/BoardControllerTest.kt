package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ApiConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDirectionDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.BoardInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinSortStrategyInputEnum
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardGetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardPinLister
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardRecycleBin
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardUpdater
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class BoardControllerTest {
    private val boardCreator = mockk<BoardCreator>()
    private val boardGetter = mockk<BoardGetter>()
    private val boardUpdater = mockk<BoardUpdater>()
    private val boardPinLister = mockk<BoardPinLister>()
    private val boardRecycleBin = mockk<BoardRecycleBin>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val apiConfig = mockk<ApiConfig>()
    private val controller = BoardController(
        boardCreator = boardCreator,
        boardGetter = boardGetter,
        boardUpdater = boardUpdater,
        boardPinLister = boardPinLister,
        boardRecycleBin = boardRecycleBin,
        securityIdentity = securityIdentity,
        apiConfig = apiConfig,
    )

    private fun aUser() = User(id = randomUUID(), name = createRandomString())

    private fun aBoard(author: User) =
        Board(id = randomUUID(), author = author, name = createRandomString(), description = createRandomString())

    @Test
    fun `Given valid input, Then createBoard returns 201 with Location and a zero pin count`() {
        // Given
        val user = aUser()
        val dto = BoardInputDto(name = createRandomString(), description = createRandomString())
        val board = Board(id = randomUUID(), author = user, name = dto.name, description = dto.description)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { apiConfig.baseUrl() } returns "https://example.test"
        every { boardCreator.create(author = user, name = dto.name, description = dto.description) } returns board

        // When
        val response = controller.createBoard(dto)

        // Then
        assertEquals(201, response.status)
        assertEquals("https://example.test/api/v1/boards/${board.id}", response.getHeaderString("Location"))
        val body = response.entity as BoardOutputDto
        assertEquals(board.id, body.id)
        assertEquals(board.name, body.name)
        assertEquals(board.description, body.description)
        assertEquals(0, body.pinCount)
    }

    @Test
    fun `Given active boards for the user, Then listBoards returns each with its own pin count`() {
        // Given
        val user = aUser()
        val boardA = aBoard(user)
        val boardB = aBoard(user)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { boardGetter.listActiveBoardsForUser(user) } returns listOf(boardA, boardB)
        every { boardGetter.countActivePinsForUserBoard(boardA.id, user) } returns 3
        every { boardGetter.countActivePinsForUserBoard(boardB.id, user) } returns 0

        // When
        val response = controller.listBoards()

        // Then
        assertEquals(200, response.status)
        val body = response.entity as BoardListOutputDto
        assertEquals(
            listOf(
                BoardOutputDto(id = boardA.id, name = boardA.name, description = boardA.description, pinCount = 3),
                BoardOutputDto(id = boardB.id, name = boardB.name, description = boardB.description, pinCount = 0),
            ),
            body.boards,
        )
    }

    @Test
    fun `Given an existing board, Then getBoard returns it with its pin count`() {
        // Given
        val user = aUser()
        val board = aBoard(user)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { boardGetter.getActiveBoardForUser(boardId = board.id, reader = user) } returns board
        every { boardGetter.countActivePinsForUserBoard(board.id, user) } returns 5

        // When
        val response = controller.getBoard(board.id)

        // Then
        assertEquals(200, response.status)
        val body = response.entity as BoardOutputDto
        assertEquals(board.id, body.id)
        assertEquals(5, body.pinCount)
    }

    @Test
    fun `Given valid input, Then updateBoard returns the updated board with its pin count`() {
        // Given
        val user = aUser()
        val boardId = randomUUID()
        val dto = BoardInputDto(name = createRandomString(), description = createRandomString())
        val updated = Board(id = boardId, author = user, name = dto.name, description = dto.description)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every {
            boardUpdater.update(boardId = boardId, name = dto.name, description = dto.description, user = user)
        } returns updated
        every { boardGetter.countActivePinsForUserBoard(boardId, user) } returns 2

        // When
        val response = controller.updateBoard(boardId, dto)

        // Then
        assertEquals(200, response.status)
        val body = response.entity as BoardOutputDto
        assertEquals(dto.name, body.name)
        assertEquals(dto.description, body.description)
        assertEquals(2, body.pinCount)
    }

    @Test
    fun `Given an existing board, Then softDeleteBoard returns 204`() {
        // Given
        val user = aUser()
        val board = aBoard(user)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { boardRecycleBin.softDelete(boardId = board.id, user = user) } returns board

        // When
        val response = controller.softDeleteBoard(board.id)

        // Then
        assertEquals(204, response.status)
        verify { boardRecycleBin.softDelete(boardId = board.id, user = user) }
    }

    @Test
    fun `Given no cursor, no page size and no sort, Then listBoardPins uses defaults`() {
        // Given
        val user = aUser()
        val boardId = randomUUID()
        val page = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every {
            boardPinLister.listActivePinsForBoard(
                reader = user,
                boardId = boardId,
                cursor = null,
                pageSize = BoardController.DEFAULT_PAGE_SIZE,
                sort = PinSortStrategy.CREATED_AT_ASC,
            )
        } returns page

        // When
        val response = controller.listBoardPins(
            boardId = boardId,
            cursorInput = null,
            pageSizeInput = null,
            sortInput = null,
        )

        // Then
        assertEquals(200, response.status)
        val body = response.entity as PinListOutputDto
        assertEquals(emptyList<Any>(), body.pins)
    }

    @Test
    fun `Given cursor, page size and sort provided, Then listBoardPins uses the provided values`() {
        // Given
        val user = aUser()
        val boardId = randomUUID()
        val pivotId = randomUUID()
        val cursorInput = CursorDto(pivotId = pivotId, direction = CursorDirectionDto.FORWARD)
        val pageSizeInput = 5
        val sortInput = PinSortStrategyInputEnum.CREATED_AT_DESC
        val page = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every {
            boardPinLister.listActivePinsForBoard(
                reader = user,
                boardId = boardId,
                cursor = match { it.pivotId == pivotId },
                pageSize = pageSizeInput,
                sort = PinSortStrategy.CREATED_AT_DESC,
            )
        } returns page

        // When
        val response = controller.listBoardPins(
            boardId = boardId,
            cursorInput = cursorInput,
            pageSizeInput = pageSizeInput,
            sortInput = sortInput,
        )

        // Then
        assertEquals(200, response.status)
    }
}
