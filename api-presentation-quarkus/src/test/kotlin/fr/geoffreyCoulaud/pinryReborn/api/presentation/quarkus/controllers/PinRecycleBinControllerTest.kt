package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDirectionDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinRecycleBinSortStrategyInputEnum
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinRecycleBinGetter
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class PinRecycleBinControllerTest {
    private val pinRecycleBinGetter = mockk<PinRecycleBinGetter>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val controller = PinRecycleBinController(
        pinRecycleBin = mockk(),
        pinRecycleBinGetter = pinRecycleBinGetter,
        securityIdentity = securityIdentity,
    )

    @Test
    fun `Given no cursor, no page size and no sort, Then listRecycledPins uses defaults`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = Instant.now())
        val page = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every {
            pinRecycleBinGetter.listSoftDeletedPinsPaginatedForUser(
                reader = user,
                cursor = null,
                pageSize = PinRecycleBinController.DEFAULT_PAGE_SIZE,
                sort = PinSortStrategy.DELETED_AT_DESC,
            )
        } returns page

        // When
        val response = controller.listRecycledPins(cursorInput = null, pageSizeInput = null, sortInput = null)

        // Then
        assertEquals(200, response.status)
    }

    @Test
    fun `Given cursor, page size and sort provided, Then listRecycledPins uses the provided values`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString(), createdAt = Instant.now())
        val pivotId = randomUUID()
        val cursorInput = CursorDto(pivotId = pivotId, direction = CursorDirectionDto.BACKWARD)
        val pageSizeInput = 7
        val sortInput = PinRecycleBinSortStrategyInputEnum.CREATED_AT_ASC
        val page = Page<Pin>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every {
            pinRecycleBinGetter.listSoftDeletedPinsPaginatedForUser(
                reader = user,
                cursor = match { it.pivotId == pivotId },
                pageSize = pageSizeInput,
                sort = PinSortStrategy.CREATED_AT_ASC,
            )
        } returns page

        // When
        val response = controller.listRecycledPins(
            cursorInput = cursorInput,
            pageSizeInput = pageSizeInput,
            sortInput = sortInput,
        )

        // Then
        assertEquals(200, response.status)
    }
}
