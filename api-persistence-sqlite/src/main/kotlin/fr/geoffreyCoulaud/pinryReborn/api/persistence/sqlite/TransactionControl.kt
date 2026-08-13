package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import io.ebean.Transaction

/**
 * Transaction-lifecycle capability: opens and inspects transactions. Carries no read and no write, so a
 * holder can scope work atomically without rooting a query. Lower-level than the domain
 * `TransactionRunner.inTransaction { }`, which stays the use-case-facing abstraction.
 */
interface TransactionControl {
    /** REQUIRED semantics: joins the thread's transaction when there is one (`io.ebean.Database:475`). */
    fun beginTransaction(): Transaction

    /** No production caller: it is the observation point for the test pinning `enqueue`'s envelope. */
    fun currentTransaction(): Transaction?
}
