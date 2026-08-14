package fr.geoffreyCoulaud.pinryReborn.api.usecases.imports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImportAlreadyInProgressError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class UserDataImportCreatorTest : BaseTest() {
    private val clock = mockk<Clock>()
    private val now = Instant.parse("2026-08-14T10:00:00Z")
    private val user = User(id = randomUUID(), name = "alice", createdAt = TestTime.now)

    @Test
    fun `Given no active import, Then one is inserted without a single read`() {
        // Given: a repository whose every read fails the test, since the index answers uniqueness
        val repository = WriteOnlyImportRepository { it }
        every { clock.now() } returns now

        // When
        val created = UserDataImportCreator(repository, clock).create(user)

        // Then
        assertEquals(UserDataImportState.AWAITING_ARCHIVE, created.state)
        assertEquals(user.id, created.userId)
        assertEquals(now, created.requestedAt)
        assertEquals(0, created.uploadedBytes)
        assertEquals(1, repository.saved.size)
    }

    @Test
    fun `Given the index refuses a second active import, Then it surfaces as the use-case error`() {
        // Given
        val violation = ImportAlreadyInProgressException(Exception("unique constraint violated"))
        val repository = WriteOnlyImportRepository { throw violation }
        every { clock.now() } returns now

        // When
        val error =
            assertThrows(ImportAlreadyInProgressError::class.java) {
                UserDataImportCreator(repository, clock).create(user)
            }

        // Then
        assertEquals(violation, error.cause)
    }

    /**
     * Fails the test on any read: ADR 0009 decision 2 bars a read that only answers a uniqueness
     * question, and this import has no second refusal to order ahead of the first.
     */
    private class WriteOnlyImportRepository(
        private val outcome: (UserDataImport) -> UserDataImport,
    ) : UserDataImportRepositoryInterface {
        val saved = mutableListOf<UserDataImport>()

        override fun save(userDataImport: UserDataImport): UserDataImport {
            saved += userDataImport
            return outcome(userDataImport)
        }

        override fun findById(id: UUID) = refuse("findById")

        override fun findAllForUser(userId: UUID, cursor: Cursor?, pageSize: Int): Page<UserDataImport> =
            refuse("findAllForUser")

        override fun findAbandonableBefore(instant: Instant) = refuse("findAbandonableBefore")

        override fun findReclaimableTerminal() = refuse("findReclaimableTerminal")

        override fun findAllImportIdsForUser(userId: UUID) = refuse("findAllImportIdsForUser")

        override fun findMissingImportIds(candidates: Collection<UUID>) = refuse("findMissingImportIds")

        override fun deleteAllForUser(userId: UUID) = refuse("deleteAllForUser")

        private fun refuse(name: String): Nothing =
            throw AssertionError("$name: creating an import reads nothing, the index is the authority")
    }
}
