package fr.geoffreyCoulaud.pinryReborn.api.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The production `application.properties` must declare every datasource key the runtime depends on, not only
 * the ones the CDI producer also sets in code.
 *
 * The default `Database` has two creation paths, `EbeanDatabaseProducer` and avaje-config reading that file,
 * and whichever runs first wins. The worker's startup observer reaches a query bean, and so `DB.getDefault()`,
 * before the producer is asked for anything, so avaje-config is the path that wins and it reads only the file.
 * A key present in the producer and absent from the file therefore fails silently in production: migrations
 * that never run against a stale schema, or a pool above one connection on a single-writer database.
 *
 * No integration test can catch this, since the test profile has its own properties file. Deleting
 * `ebean.properties` is what surfaced it: that file used to supply these keys to the avaje-config path, from
 * `main` resources, for every downstream module at once
 * (`docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`, decision 1).
 */
class ProductionDatasourceDeclarationTest {
    private val requiredKeys =
        mapOf(
            "datasource.db.minConnections" to "1",
            "datasource.db.maxConnections" to "1",
            "ebean.migration.run" to "true",
            "ebean.migration.path" to "dbmigration",
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
