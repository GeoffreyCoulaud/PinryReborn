package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * Focused unit tests for the collision decision in [UserPasswordHashRepository].
 *
 * They do not extend [fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest]: the
 * decision under test is a pure function of the exception's cause structure, observed empirically as
 * `PersistenceException` wrapping `org.sqlite.SQLiteException` whose `resultCode` discriminates
 * `SQLITE_CONSTRAINT_UNIQUE` from the other constraint codes (NOT NULL, FOREIGN KEY, ...). The
 * duplicate-insert repository test in
 * [fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.UserPasswordHashRepositoryTest] pins the
 * end-to-end translation for the unique case.
 */
class UserPasswordHashRepositoryCollisionDecisionTest {
    @Test
    fun `Given a unique-constraint violation, Then isUniqueConstraint returns true`() {
        // Given: the exact structure observed from Ebean/SQLite, a PersistenceException wrapping a
        // SQLiteException whose resultCode is SQLITE_CONSTRAINT_UNIQUE
        val error =
            PersistenceException(
                "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed",
                SQLiteException(
                    "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed",
                    SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE,
                ),
            )
        // When
        val result = UserPasswordHashRepository.isUniqueConstraint(error)
        // Then
        assertTrue(result)
    }

    @Test
    fun `Given a not-null constraint violation, Then isUniqueConstraint returns false`() {
        // Given: same wrapper shape, but a NOT NULL constraint (vendor errorCode 19 is shared with
        // the unique case, so only the typed resultCode distinguishes them)
        val error =
            PersistenceException(
                "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed",
                SQLiteException(
                    "[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed",
                    SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL,
                ),
            )
        // When
        val result = UserPasswordHashRepository.isUniqueConstraint(error)
        // Then
        assertFalse(result)
    }

    @Test
    fun `Given a persistence failure with no SQLite cause, Then isUniqueConstraint returns false`() {
        // Given: a connection or IO failure surfaced as a bare PersistenceException with no cause
        val error = PersistenceException("connection refused")
        // When
        val result = UserPasswordHashRepository.isUniqueConstraint(error)
        // Then
        assertFalse(result)
    }
}
