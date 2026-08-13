package fr.geoffreyCoulaud.pinryReborn.api.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Nothing else holds these keys: the producer loads them from this file, and no integration test can read
 * production's copy of it (`docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`).
 * A missing one fails silently (migrations that never run, a pool above one connection) or, for the
 * credentials, loudly at a boot no test performed until the image smoke check.
 */
class ProductionDatasourceDeclarationTest {
    private val requiredKeys =
        mapOf(
            "datasource.db.username" to "pinry",
            "datasource.db.password" to "",
            "datasource.db.minConnections" to "1",
            "datasource.db.maxConnections" to "1",
            "ebean.migration.run" to "true",
            "ebean.migration.path" to "dbmigration",
            "ebean.packages" to "fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models",
        )

    @Test
    fun `Given the production properties, Then every datasource key the avaje-config path needs is declared`() {
        // Given
        val declared = readProductionProperties()

        // Then
        val wrong = requiredKeys.filterNot { (key, value) -> declared[key] == value }
        assertEquals(
            emptyMap<String, String>(),
            wrong,
            "Expected these keys in src/main/resources/application.properties with these values; " +
                "declared instead: ${wrong.keys.associateWith { declared[it] }}",
        )
    }

    private fun readProductionProperties(): Map<String, String> =
        File("src/main/resources/application.properties")
            .readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("#") || it.isEmpty() }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.take(separator).trim() to line.drop(separator + 1).trim()
            }
            .toMap()
}
