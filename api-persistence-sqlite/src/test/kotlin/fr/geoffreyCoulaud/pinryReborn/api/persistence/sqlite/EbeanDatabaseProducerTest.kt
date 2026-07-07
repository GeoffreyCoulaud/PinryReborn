package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers the `DB_PATH ?: "data.db"` fallback branch via the pure [sqliteJdbcUrl] helper, so no
 * process-environment mocking (e.g. `mockkStatic(System::class)`, which deadlocks the test JVM)
 * and no real database build are needed.
 */
class EbeanDatabaseProducerTest {
    @Test
    fun `Given a DB path, Then the JDBC URL uses it`() {
        // Given
        val dbPath = "/tmp/custom-pinry-reborn.db"

        // When
        val url = sqliteJdbcUrl(dbPath)

        // Then
        assertEquals("jdbc:sqlite:/tmp/custom-pinry-reborn.db", url)
    }

    @Test
    fun `Given no DB path, Then the JDBC URL falls back to data dot db`() {
        // When
        val url = sqliteJdbcUrl(null)

        // Then
        assertEquals("jdbc:sqlite:data.db", url)
    }
}
