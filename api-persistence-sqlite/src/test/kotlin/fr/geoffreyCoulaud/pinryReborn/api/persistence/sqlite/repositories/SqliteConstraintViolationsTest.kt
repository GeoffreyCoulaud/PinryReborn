package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * Focused unit tests for the collision decision shared by the repositories that answer a unique-index
 * violation with a domain error or the row the insert collided with.
 *
 * They do not extend [fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest]: the
 * decision under test is a pure function of the exception's cause structure, observed empirically as
 * `PersistenceException` wrapping `org.sqlite.SQLiteException` whose `resultCode` discriminates
 * `SQLITE_CONSTRAINT_UNIQUE` from the other constraint codes (NOT NULL, FOREIGN KEY, ...). The
 * repository tests pin the end-to-end answer each caller asks for: a domain error in
 * [fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.UserPasswordHashRepositoryTest] and
 * [fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.UserDataExportRepositoryTest], the
 * collided-with row in [fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.EbeanTaskQueueTest].
 * These tests cover the decision itself and the rethrow of unrelated failures, which cannot be
 * produced through a public save against a real store.
 */
class SqliteConstraintViolationsTest {
    private class DomainError(
        cause: Throwable,
    ) : RuntimeException(cause)

    private fun uniqueConstraintFailure() =
        PersistenceException(
            "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed",
            SQLiteException(
                "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed",
                SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE,
            ),
        )

    private fun notNullConstraintFailure() =
        PersistenceException(
            "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed",
            SQLiteException(
                "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed",
                SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL,
            ),
        )

    @Test
    fun `Given a unique-constraint violation, Then translateUniqueConstraint throws the caller's domain error`() {
        // Given: the exact structure observed from Ebean/SQLite, a PersistenceException wrapping a
        // SQLiteException whose resultCode is SQLITE_CONSTRAINT_UNIQUE
        val error = uniqueConstraintFailure()
        // When / Then: the caller's factory decides the type, and receives the failure as its cause
        val thrown =
            assertThrows(DomainError::class.java) {
                SqliteConstraintViolations.translateUniqueConstraint(error) { DomainError(it) }
            }
        assertSame(error, thrown.cause)
    }

    @Test
    fun `Given a non-unique persistence failure, Then translateUniqueConstraint rethrows it unchanged`() {
        // Given: a NOT NULL violation must NOT be reported as a 409, and it was observed carrying
        // the same vendor errorCode 19 as the unique case, so only the typed resultCode parts them
        val error = notNullConstraintFailure()
        var factoryCalled = false
        // When / Then: the exact same instance propagates, no translation
        val thrown =
            assertThrows(PersistenceException::class.java) {
                SqliteConstraintViolations.translateUniqueConstraint(error) {
                    factoryCalled = true
                    DomainError(it)
                }
            }
        assertSame(error, thrown)
        assertFalse(factoryCalled)
    }

    @Test
    fun `Given a persistence failure with no SQLite cause, Then translateUniqueConstraint rethrows it unchanged`() {
        // Given
        val error = PersistenceException("connection refused")
        var factoryCalled = false
        // When / Then
        val thrown =
            assertThrows(PersistenceException::class.java) {
                SqliteConstraintViolations.translateUniqueConstraint(error) {
                    factoryCalled = true
                    DomainError(it)
                }
            }
        assertSame(error, thrown)
        assertFalse(factoryCalled)
    }
}
