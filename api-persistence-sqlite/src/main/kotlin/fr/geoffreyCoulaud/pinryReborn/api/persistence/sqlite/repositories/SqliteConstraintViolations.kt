package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import jakarta.persistence.PersistenceException
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * Tells a unique-index violation apart from every other persistence failure, for the repositories
 * that answer one with a domain error or the row the insert collided with.
 *
 * The cause structure (`PersistenceException` wrapping `org.sqlite.SQLiteException`) was observed
 * empirically against Ebean-on-SQLite and is pinned by the duplicate-insert repository tests. The
 * unique and the NOT NULL violation were both observed carrying vendor `errorCode` 19, so the typed
 * `resultCode` is the discriminator between them: a NOT NULL violation or a dropped connection must
 * stay a genuine 500, not become the caller's 409.
 */
internal object SqliteConstraintViolations {
    /**
     * Answers a unique-constraint violation with [recover], and rethrows [error] itself otherwise.
     *
     * The rethrow branch lives here rather than in each `catch` because no real store can be made to
     * produce a non-unique failure through a repository's public save, so a test can only reach it
     * through this object.
     */
    fun <T> onUniqueConstraint(
        error: PersistenceException,
        recover: (PersistenceException) -> T,
    ): T {
        if (isUniqueConstraint(error)) return recover(error)
        throw error
    }

    /** Always throws: [toDomainError] for a unique-constraint violation, [error] itself otherwise. */
    fun translateUniqueConstraint(
        error: PersistenceException,
        toDomainError: (PersistenceException) -> Throwable,
    ): Nothing = onUniqueConstraint(error) { throw toDomainError(it) }

    /** True when [error] is SQLite refusing a row that already exists under a unique index. */
    private fun isUniqueConstraint(error: PersistenceException): Boolean {
        val sqliteException = error.cause as? SQLiteException ?: return false
        return sqliteException.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
    }
}
