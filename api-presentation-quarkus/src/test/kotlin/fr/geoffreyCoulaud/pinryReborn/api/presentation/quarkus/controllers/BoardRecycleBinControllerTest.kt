package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.RecycledBoardDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.RecycledBoardListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardGetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardRecycleBin
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class BoardRecycleBinControllerTest {
    private val boardRecycleBin = mockk<BoardRecycleBin>()
    private val boardGetter = mockk<BoardGetter>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val controller = BoardRecycleBinController(
        boardRecycleBin = boardRecycleBin,
        boardGetter = boardGetter,
        securityIdentity = securityIdentity,
    )

    private fun aUser() = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)

    private fun aBoard(author: User) =
        Board(id = randomUUID(), author = author, name = createRandomString(), description = createRandomString(),
            createdAt = TestTime.now, updatedAt = TestTime.now)

    @Test
    fun `Given recycled boards for the user, Then listRecycledBoards returns them without pin count`() {
        // Given
        val user = aUser()
        val boardA = aBoard(user)
        val boardB = aBoard(user)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { boardRecycleBin.listRecycledBoardsForUser(user) } returns listOf(boardA, boardB)

        // When
        val response = controller.listRecycledBoards()

        // Then
        assertEquals(200, response.status)
        val body = response.entity as RecycledBoardListOutputDto
        assertEquals(
            listOf(
                RecycledBoardDto(id = boardA.id, name = boardA.name, description = boardA.description),
                RecycledBoardDto(id = boardB.id, name = boardB.name, description = boardB.description),
            ),
            body.boards,
        )
    }

    @Test
    fun `Given a recycled board, Then restoreBoard returns the restored board with its pin count`() {
        // Given
        val user = aUser()
        val board = aBoard(user)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { boardRecycleBin.restore(boardId = board.id, user = user) } returns board
        every { boardGetter.countActivePinsForUserBoard(board.id, user) } returns 4

        // When
        val response = controller.restoreBoard(board.id)

        // Then
        assertEquals(200, response.status)
        val body = response.entity as BoardOutputDto
        assertEquals(board.id, body.id)
        assertEquals(board.name, body.name)
        assertEquals(board.description, body.description)
        assertEquals(4, body.pinCount)
    }

    @Test
    fun `Given a recycled board, Then permanentlyDeleteBoard returns 204`() {
        // Given
        val user = aUser()
        val board = aBoard(user)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { boardRecycleBin.permanentlyDelete(boardId = board.id, user = user) } returns Unit

        // When
        val response = controller.permanentlyDeleteBoard(board.id)

        // Then
        assertEquals(204, response.status)
        verify { boardRecycleBin.permanentlyDelete(boardId = board.id, user = user) }
    }

    @Test
    fun `Given a user, Then emptyRecycleBin returns 204`() {
        // Given
        val user = aUser()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { boardRecycleBin.emptyRecycleBin(user = user) } returns Unit

        // When
        val response = controller.emptyRecycleBin()

        // Then
        assertEquals(204, response.status)
        verify { boardRecycleBin.emptyRecycleBin(user = user) }
    }
}
