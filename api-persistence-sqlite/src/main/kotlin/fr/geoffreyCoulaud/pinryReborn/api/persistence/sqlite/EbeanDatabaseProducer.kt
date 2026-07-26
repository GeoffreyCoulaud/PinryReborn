package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.Database
import io.ebean.datasource.DataSourceConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class EbeanDatabaseProducer {

    @Produces
    @Singleton
    fun produceDatabase(
        @ConfigProperty(name = "datasource.db.url") dbUrl: String,
    ): Database {
        val dataSourceConfig = sqliteDataSourceConfig(dbUrl)
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
         * Builds the single-connection SQLite [DataSourceConfig] (option A) for a JDBC URL resolved
         * from `datasource.db.url`. SQLite is single-writer, so constraining the pool to exactly
         * one connection makes the concurrency story trivially correct and removes the
         * multi-connection pool deadlock that IMMEDIATE transactions caused against the default
         * pool. The URL itself (file path, pragmas, or `:memory:`) is owned by configuration, not
         * built here, so deployment and test wiring differ only in that one property. Kept as a
         * pure function so the single-connection constraint is unit-testable without building a
         * real database.
         *
         * Deliberately does NOT set `transaction_mode=IMMEDIATE`: the datasource above is
         * constrained to a single connection, so there is no pool contention and thus no need for
         * IMMEDIATE's eager write-lock. Adding it back would only reintroduce the multi-connection
         * deadlock it was originally meant to avoid. A single connection already serializes writes,
         * which is all SQLite (a single-writer database) requires.
         */
        internal fun sqliteDataSourceConfig(url: String): DataSourceConfig {
            val dataSourceConfig = DataSourceConfig()
            dataSourceConfig.url = url
            dataSourceConfig.driver = "org.sqlite.JDBC"
            dataSourceConfig.username = "sa"
            dataSourceConfig.password = ""
            dataSourceConfig.minConnections = 1
            dataSourceConfig.maxConnections = 1
            return dataSourceConfig
        }
    }
}
