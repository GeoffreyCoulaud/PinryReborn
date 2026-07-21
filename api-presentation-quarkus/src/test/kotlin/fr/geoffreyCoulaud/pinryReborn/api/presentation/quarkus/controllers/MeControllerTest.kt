package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import io.quarkus.security.identity.SecurityIdentity
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class MeControllerTest {
    @Test
    fun `Given an authenticated caller, Then getCurrentUser returns their id and name`() {
        val user = User(randomUUID(), "alice")
        val identity = mockk<SecurityIdentity> { every { getAttribute<User>("user") } returns user }
        val dto = MeController(identity).getCurrentUser()
        assertEquals(user.id, dto.id)
        assertEquals("alice", dto.name)
    }
}
