package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

@ApplicationScoped
class EbeanDatabaseProducer {

    /**
     * Builds the default database from the properties alone, so the application has one source of
     * datasource configuration rather than two that must agree.
     *
     * A query bean constructed with no argument runs on the default database, which avaje-config
     * builds from the same properties if it is reached before this producer. Configuring anything
     * here in code would make that a race with a silent loser
     * (`docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`).
     */
    @Produces
    @Singleton
    fun produceDatabase(): Database =
        Database
            .builder()
            .defaultDatabase(true)
            .loadFromProperties()
            .build()
}
