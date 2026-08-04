package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import jakarta.persistence.PersistenceException
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * Tells a unique-index violation apart from every other persistence failure, once for every
 * repository whose insert races another.
 *
 * The cause structure (`PersistenceException` wrapping `org.sqlite.SQLiteException`) was observed
 * empirically against Ebean-on-SQLite and is pinned by the duplicate-insert repository tests. Every
 * constraint kind shares vendor `errorCode` 19, so the typed `resultCode` is the one reliable
 * discriminator: a NOT NULL violation or a dropped connection must stay a genuine 500, not become
 * the caller's 409.
 */
internal object SqliteConstraintViolations {
    /** True when [error] is SQLite refusing a row that already exists under a unique index. */
    fun isUniqueConstraint(error: PersistenceException): Boolean {
        val sqliteException = error.cause as? SQLiteException ?: return false
        return sqliteException.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
    }

    /**
     * Always throws: [toDomainError] for a unique-constraint violation, [error] itself otherwise.
     *
     * The rethrow branch lives here rather than in each `catch` because no real store can be made to
     * produce a non-unique failure through a repository's public save, so a test can only reach it
     * through this function.
     */
    fun translateUniqueConstraint(
        error: PersistenceException,
        toDomainError: (PersistenceException) -> Throwable,
    ): Nothing {
        if (isUniqueConstraint(error)) throw toDomainError(error)
        throw error
    }
}
