package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the `DB_PATH ?: "data.db"` fallback branch and the queue pragma suffix via the pure
 * [sqliteJdbcUrl] helper, so no process-environment mocking (e.g. `mockkStatic(System::class)`,
 * which deadlocks the test JVM) and no real database build are needed.
 */
class EbeanDatabaseProducerTest {
    private val pragmas = "journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000"

    @Test
    fun `Given a DB path, Then the JDBC URL uses it`() {
        // Given
        val dbPath = "/tmp/custom-pinry-reborn.db"

        // When
        val url = sqliteJdbcUrl(dbPath)

        // Then
        assertEquals("jdbc:sqlite:/tmp/custom-pinry-reborn.db?$pragmas", url)
    }

    @Test
    fun `Given no DB path, Then the JDBC URL falls back to data dot db`() {
        // When
        val url = sqliteJdbcUrl(null)

        // Then
        assertEquals("jdbc:sqlite:data.db?$pragmas", url)
    }

    @Test
    fun `Given a db path, Then the JDBC URL carries the queue pragmas`() {
        // When
        val url = sqliteJdbcUrl("data.db")
        // Then
        assertTrue(url.startsWith("jdbc:sqlite:data.db"))
        assertTrue(url.contains("journal_mode=WAL"))
        assertTrue(url.contains("busy_timeout=5000"))
        assertTrue(url.contains("synchronous=NORMAL"))
    }

    @Test
    fun `Given a db path, Then the data source config is constrained to a single connection`() {
        // When
        val dataSourceConfig = sqliteDataSourceConfig("data.db")

        // Then
        assertEquals(1, dataSourceConfig.minConnections)
        assertEquals(1, dataSourceConfig.maxConnections)
    }

    @Test
    fun `Given a db path, Then the data source config URL carries the queue pragmas but not transaction mode`() {
        // When
        val dataSourceConfig = sqliteDataSourceConfig("data.db")

        // Then
        assertTrue(dataSourceConfig.url.contains("journal_mode=WAL"))
        assertTrue(dataSourceConfig.url.contains("synchronous=NORMAL"))
        assertTrue(dataSourceConfig.url.contains("busy_timeout=5000"))
        assertFalse(dataSourceConfig.url.contains("transaction_mode"))
    }
}
