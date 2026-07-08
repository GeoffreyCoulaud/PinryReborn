package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.Database
import io.ebean.datasource.DataSourceConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

@ApplicationScoped
class EbeanDatabaseProducer {
    @Produces
    @Singleton
    fun produceDatabase(): Database {
        val dataSourceConfig = sqliteDataSourceConfig(System.getenv("DB_PATH"))
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

    companion object {
        /**
         * Builds the SQLite JDBC URL from the (nullable) `DB_PATH` value, falling back to `data.db`
         * when it is absent, and appends the queue's connection-level pragmas (WAL journal mode,
         * `synchronous=NORMAL` and a busy timeout) as query parameters that the xerial sqlite-jdbc
         * driver reads into its `SQLiteConfig`. Kept as a pure function so the fallback branch is
         * unit-testable without touching the process environment or building a real database.
         *
         * Deliberately does NOT set `transaction_mode=IMMEDIATE`: the datasource above is
         * constrained to a single connection (option A), so there is no pool contention and thus no
         * need for IMMEDIATE's eager write-lock -- adding it back would only reintroduce the
         * multi-connection deadlock it was originally meant to avoid. A single connection already
         * serializes writes, which is all SQLite (a single-writer database) requires.
         */
        internal fun sqliteJdbcUrl(dbPath: String?): String {
            val path = dbPath ?: "data.db"
            val params = listOf(
                "journal_mode=WAL",
                "synchronous=NORMAL",
                "busy_timeout=5000",
            ).joinToString("&")
            return "jdbc:sqlite:$path?$params"
        }

        /**
         * Builds the single-connection SQLite [DataSourceConfig] (option A): SQLite is
         * single-writer, so constraining the pool to exactly one connection makes the whole
         * concurrency story trivially correct and removes the multi-connection pool deadlock that
         * IMMEDIATE transactions caused against the default pool. Kept as a pure function so
         * `minConnections`/`maxConnections` staying at 1 is unit-testable without building a real
         * database.
         */
        internal fun sqliteDataSourceConfig(dbPath: String?): DataSourceConfig {
            val dataSourceConfig = DataSourceConfig()
            dataSourceConfig.url = sqliteJdbcUrl(dbPath)
            dataSourceConfig.driver = "org.sqlite.JDBC"
            dataSourceConfig.username = "sa"
            dataSourceConfig.password = ""
            dataSourceConfig.minConnections = 1
            dataSourceConfig.maxConnections = 1
            return dataSourceConfig
        }
    }
}
