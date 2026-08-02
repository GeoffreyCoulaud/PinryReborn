package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.DB
import io.ebean.Database
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.temporal.ChronoUnit

@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a shared test base for concrete subclasses.
abstract class RepositoryTest {
    protected val database: Database get() = DB.getDefault()

    /**
     * An instant an entity can carry across a save-then-read unchanged.
     *
     * The store round-trips instants at millisecond resolution, so a nanosecond-precision
     * `Instant.now()` comes back as a *different* value and every equality assertion on a re-read
     * entity fails. [fr.geoffreyCoulaud.pinryReborn.api.system.SystemClock] truncates for the same
     * reason; tests that build entities by hand must match it.
     */
    protected fun storableNow(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

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
