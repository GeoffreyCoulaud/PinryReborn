package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class EbeanTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T =
        database.beginTransaction().use { transaction ->
            val result = block()
            transaction.commit()
            result
        }
}
