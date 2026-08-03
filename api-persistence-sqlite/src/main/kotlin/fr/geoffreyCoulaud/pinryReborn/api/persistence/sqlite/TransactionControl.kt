package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.Transaction

/**
 * Transaction-lifecycle capability: opens and inspects transactions. Carries no read and no write, so a
 * holder can scope work atomically without rooting a query. Lower-level than the domain
 * `TransactionRunner.inTransaction { }`, which stays the use-case-facing abstraction.
 */
interface TransactionControl {
    fun beginTransaction(): Transaction
    fun currentTransaction(): Transaction?
}
