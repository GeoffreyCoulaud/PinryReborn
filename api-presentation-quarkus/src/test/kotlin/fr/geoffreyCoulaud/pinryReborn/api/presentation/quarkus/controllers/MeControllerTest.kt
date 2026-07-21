package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PasswordChanger
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
        val passwordChanger = mockk<PasswordChanger>()
        val dto = MeController(identity, passwordChanger).getCurrentUser()
        assertEquals(user.id, dto.id)
        assertEquals("alice", dto.name)
    }
}
