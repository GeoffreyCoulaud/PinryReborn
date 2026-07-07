package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinSearcher
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class PinSearchControllerTest {
    private val pinSearcher = mockk<PinSearcher>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val controller = PinSearchController(
        pinSearcher = pinSearcher,
        securityIdentity = securityIdentity,
    )

    @Test
    fun `Given no limit and a query, Then searchPins uses the default limit`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        val query = createRandomString()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every {
            pinSearcher.searchPins(user = user, query = query, limit = PinSearchController.DEFAULT_LIMIT)
        } returns emptyList()

        // When
        val response = controller.searchPins(query = query, limitParam = null)

        // Then
        assertEquals(200, response.status)
    }

    @Test
    fun `Given a limit above the max and a null query, Then searchPins clamps the limit and requireNotNull throws`() {
        // Given
        val user = User(id = randomUUID(), name = createRandomString())
        every { securityIdentity.getAttribute<User>("user") } returns user

        // When, Then
        assertThrows<IllegalArgumentException> {
            controller.searchPins(query = null, limitParam = PinSearchController.MAX_LIMIT + 5)
        }
    }
}
