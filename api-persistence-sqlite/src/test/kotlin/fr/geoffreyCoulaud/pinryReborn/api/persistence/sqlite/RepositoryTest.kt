package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.DB
import io.ebean.Database
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.util.UUID

abstract class RepositoryTest {
    protected val database: Database get() = DB.getDefault()

    /**
     * Rows saved in the same clock tick share a creation instant, and cursor pagination breaks such
     * ties on the id, which is random. Tests that need a *deterministic* order therefore stamp the
     * rows themselves, one second apart, in the order given. `@WhenCreated` is Ebean-managed, hence
     * the raw update; the parameters still go through Ebean's binder, so the stored representation
     * matches what the mapped entity would write.
     */
    protected fun forceCreationInstants(
        table: String,
        ids: List<UUID>,
    ) {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        ids.forEachIndexed { index, id ->
            database
                .sqlUpdate("update $table set when_created = ? where id = ?")
                .setParameter(1, base.plusSeconds(index.toLong()))
                .setParameter(2, id)
                .execute()
        }
    }

    /**
     * Truncate all non-internal tables in the database.
     *
     * - Tables prefixed by "sqlite_" are ignored.
     * - The "db_migration" table is ignored, as it's necessary for ebean.
     */
    @BeforeEach
    fun truncateAllTables() {
        database
            .sqlQuery("SELECT name FROM sqlite_master WHERE type='table'")
            .findList()
            .map { it.getString("name") }
            .filterNot { it.startsWith("sqlite_") or it.equals("db_migration") }
            .forEach { database.truncate(it) }
    }
}
