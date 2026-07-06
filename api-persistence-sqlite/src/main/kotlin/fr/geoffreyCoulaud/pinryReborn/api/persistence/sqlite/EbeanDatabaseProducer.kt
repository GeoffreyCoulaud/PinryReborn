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
        val dbPath = System.getenv("DB_PATH") ?: "data.db"

        val dataSourceConfig = DataSourceConfig()
        dataSourceConfig.url = "jdbc:sqlite:$dbPath"
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
            .addPackage("fr.geoffreyCoulaud.pinryReborn.adapters.persistence.models")
            .build()
    }
}
