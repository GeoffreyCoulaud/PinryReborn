package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordPreviouslyUsedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID.randomUUID

class PasswordChangerTest : BaseTest() {
    private val passwords = mockk<UserPasswordHashRepositoryInterface>()
    private val hasher = mockk<PasswordHasher>()
    private val sessionRevoker = mockk<SessionRevoker>(relaxed = true)
    private val tx = mockk<TransactionRunner>()
    private val changer = PasswordChanger(passwords, hasher, sessionRevoker, tx)

    private val user = User(id = randomUUID(), name = "u")
    private val current = HashedPassword("current-hash", PasswordHashAlgorithm.BCRYPT)

    @Test
    fun `Given valid inputs, Then the new hash is appended and all sessions revoked`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current)
        every { hasher.matches("new", current) } returns false
        every { hasher.hash("new") } returns HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT)
        every { passwords.saveUserPasswordHash(user, any()) } answers { secondArg() }
        // When
        changer.changePassword(user, "old", "new")
        // Then
        verify { passwords.saveUserPasswordHash(user, HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT)) }
        verify { sessionRevoker.revokeAll(user) }
    }

    @Test
    fun `Given a wrong current password, Then it throws and nothing is written`() {
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        assertThrows<ReauthenticationError> { changer.changePassword(user, "bad", "new") }
        verify(exactly = 0) { passwords.saveUserPasswordHash(any(), any()) }
    }

    @Test
    fun `Given no stored hash, Then re-authentication fails`() {
        every { passwords.findCurrentPasswordHash(user) } returns null
        assertThrows<ReauthenticationError> { changer.changePassword(user, "x", "new") }
    }

    @Test
    fun `Given a previously-used new password, Then it throws PasswordPreviouslyUsedError`() {
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        val older = HashedPassword("older", PasswordHashAlgorithm.BCRYPT)
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current, older)
        every { hasher.matches("reused", current) } returns false
        every { hasher.matches("reused", older) } returns true
        assertThrows<PasswordPreviouslyUsedError> { changer.changePassword(user, "old", "reused") }
    }
}
