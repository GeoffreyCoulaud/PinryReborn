package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.Database
import io.ebean.datasource.DataSourceConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Builds the SQLite JDBC URL from the (nullable) `DB_PATH` value, falling back to `data.db`
 * when it is absent. Extracted as a pure function so the fallback branch is unit-testable
 * without touching the process environment or building a real database.
 */
internal fun sqliteJdbcUrl(dbPath: String?): String = "jdbc:sqlite:${dbPath ?: "data.db"}"

@ApplicationScoped
class EbeanDatabaseProducer {
    @Produces
    @Singleton
    fun produceDatabase(): Database {
        val dataSourceConfig = DataSourceConfig()
        dataSourceConfig.url = sqliteJdbcUrl(System.getenv("DB_PATH"))
        dataSourceConfig.driver = "org.sqlite.JDBC"
        dataSourceConfig.username = "sa"
        dataSourceConfig.password = ""

        return Database
            .builder()
            .defaultDatabase(true)
            .dataSourceBuilder(dataSourceConfig)
            .ddlGenerate(false)
            .ddlRun(false)
            .runMigration(true)
            .addPackage("fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models")
            .build()
    }
}
