package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.TransactionControl
import io.ebean.Database
import io.ebean.Transaction
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class EbeanTransactionControl(
    private val database: Database,
) : TransactionControl {
    override fun beginTransaction(): Transaction = database.beginTransaction()
    override fun currentTransaction(): Transaction? = database.currentTransaction()
}
