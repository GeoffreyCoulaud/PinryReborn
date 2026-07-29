package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SessionDtoMapper.toCreatedDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SessionDtoMapper.toExistingDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class SessionDtoMapperTest {
    private val expiresAt = Instant.parse("2026-08-19T12:34:56Z")
    private val renewAfter = Instant.parse("2026-08-12T12:34:56Z")

    @Test
    fun `Given an IssuedSession, Then toCreatedDto copies token and timestamps`() {
        val dto = IssuedSession("tok", expiresAt, renewAfter).toCreatedDto()
        assertEquals("tok", dto.token)
        assertEquals(expiresAt, dto.expiresAt)
        assertEquals(renewAfter, dto.renewAfter)
    }

    @Test
    fun `Given a SessionToken, Then toExistingDto exposes expiry, renewAfter and persistent but no token`() {
        val token = SessionToken(randomUUID(), User(randomUUID(), "alice",
            createdAt = Instant.now()), expiresAt, persistent = true, createdAt = Instant.now())
        val dto = token.toExistingDto(renewAfter)
        assertEquals(expiresAt, dto.expiresAt)
        assertEquals(renewAfter, dto.renewAfter)
        assertEquals(true, dto.persistent)
    }
}
