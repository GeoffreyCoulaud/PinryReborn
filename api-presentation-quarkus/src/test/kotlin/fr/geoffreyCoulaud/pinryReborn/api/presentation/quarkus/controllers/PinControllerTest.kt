package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ApiConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDirectionDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinBoardsInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinSortStrategyInputEnum
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinBoardSetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinGetter
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class PinControllerTest {
    private val pinGetter = mockk<PinGetter>()
    private val pinBoardSetter = mockk<PinBoardSetter>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val apiConfig = mockk<ApiConfig>()
    private val controller = PinController(
        pinCreator = mockk(),
        pinGetter = pinGetter,
        pinTagger = mockk(),
        pinRecycleBin = mockk(),
        pinBoardSetter = pinBoardSetter,
        securityIdentity = securityIdentity,
        apiConfig = apiConfig,
    )

    @Test
    fun `Given no cursor, no page size and no sort, Then listPins uses defaults`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val page = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every {
            pinGetter.listPinsPaginatedForUser(
                reader = user,
                cursor = null,
                pageSize = PinController.DEFAULT_PAGE_SIZE,
                sort = PinSortStrategy.CREATED_AT_ASC,
            )
        } returns page

        // When
        val response = controller.listPins(cursorInput = null, pageSizeInput = null, sortInput = null)

        // Then
        assertEquals(200, response.status)
    }

    @Test
    fun `Given cursor, page size and sort provided, Then listPins uses the provided values`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val pivotId = randomUUID()
        val cursorInput = CursorDto(pivotId = pivotId, direction = CursorDirectionDto.FORWARD)
        val pageSizeInput = 5
        val sortInput = PinSortStrategyInputEnum.CREATED_AT_DESC
        val page = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every {
            pinGetter.listPinsPaginatedForUser(
                reader = user,
                cursor = match { it.pivotId == pivotId },
                pageSize = pageSizeInput,
                sort = PinSortStrategy.CREATED_AT_DESC,
            )
        } returns page

        // When
        val response = controller.listPins(
            cursorInput = cursorInput,
            pageSizeInput = pageSizeInput,
            sortInput = sortInput,
        )

        // Then
        assertEquals(200, response.status)
    }

    @Test
    fun `Given board ids, Then setBoards sets them and returns the updated pin`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)
        val pinId = randomUUID()
        val boardIds = listOf(randomUUID(), randomUUID())
        val dto = PinBoardsInputDto(boardIds = boardIds)
        val pin = Pin(
            id = pinId,
            author = user,
            sourceContextUrl = createRandomString(),
            sourceMediaUrl = null,
            description = createRandomString(),
            tags = emptyList(),
            boards = emptyList(),
            createdAt = TestTime.now,
            updatedAt = TestTime.now,
        )
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { pinBoardSetter.setBoards(pinId = pinId, boardIds = boardIds, user = user) } returns pin

        // When
        val response = controller.setBoards(pinId, dto)

        // Then
        assertEquals(200, response.status)
        val body = response.entity as PinOutputDto
        assertEquals(pin.id, body.id)
    }
}
