package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Covers the single-connection SQLite `DataSourceConfig` built from the JDBC URL that
 * `datasource.db.url` provides (option A): SQLite is single-writer, so the pool is pinned to one
 * connection to keep the concurrency story trivially correct. Kept as a pure helper so no
 * Quarkus/CDI bootstrap, process-environment mocking or real database build is needed.
 */
class EbeanDatabaseProducerTest {
    @Test
    fun `Given an in-memory JDBC URL, Then the data source config uses it verbatim`() {
        // Given
        val url = "jdbc:sqlite::memory:"

        // When
        val dataSourceConfig = EbeanDatabaseProducer.sqliteDataSourceConfig(url)

        // Then
        assertEquals(url, dataSourceConfig.url)
    }

    @Test
    fun `Given a file JDBC URL with pragmas, Then the data source config uses it verbatim`() {
        // Given
        val url =
            "jdbc:sqlite:/var/lib/pinry/data.db?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000"

        // When
        val dataSourceConfig = EbeanDatabaseProducer.sqliteDataSourceConfig(url)

        // Then
        assertEquals(url, dataSourceConfig.url)
    }

    @Test
    fun `Given a JDBC URL, Then the data source config is constrained to a single connection`() {
        // When
        val dataSourceConfig = EbeanDatabaseProducer.sqliteDataSourceConfig("jdbc:sqlite::memory:")

        // Then
        assertEquals(1, dataSourceConfig.minConnections)
        assertEquals(1, dataSourceConfig.maxConnections)
    }

    @Test
    fun `Given a JDBC URL, Then the data source config uses the SQLite driver and default credentials`() {
        // When
        val dataSourceConfig = EbeanDatabaseProducer.sqliteDataSourceConfig("jdbc:sqlite::memory:")

        // Then
        assertEquals("org.sqlite.JDBC", dataSourceConfig.driver)
        assertEquals("sa", dataSourceConfig.username)
        assertEquals("", dataSourceConfig.password)
    }

    @Test
    fun `Given a JDBC URL, Then the data source config sets no transaction mode`() {
        // When
        val dataSourceConfig = EbeanDatabaseProducer.sqliteDataSourceConfig("jdbc:sqlite::memory:")

        // Then
        assertFalse(dataSourceConfig.url.contains("transaction_mode"))
    }
}
