package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanPersistor
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import io.ebean.DB
import io.ebean.Database
import org.junit.jupiter.api.BeforeEach
import java.time.Instant

@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a shared test base for concrete subclasses.
abstract class RepositoryTest {
    protected val database: Database get() = DB.getDefault()

    // The CRUD repositories inject Persistor in production; tests wire the same adapter here so a
    // repo under test sees the write capability through the port, not the Database type it is being
    // weaned off. Each access wraps the shared database; EbeanPersistor is stateless.
    protected val persistor: Persistor get() = EbeanPersistor(database)

    /**
     * An instant an entity can carry across a save-then-read unchanged.
     *
     * TestTime.now is millisecond-coarse, so it round-trips through the store (SystemClock truncates
     * for the same reason; a nanosecond-precision instant would come back different and break equality
     * on a re-read entity).
     */
    protected fun storableNow(): Instant = TestTime.now

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
