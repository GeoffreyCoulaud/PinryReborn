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

    /**
     * No production caller: the adapters state their transaction boundary with `inTransaction { }`
     * rather than testing for one. Kept as the observation point for the test that pins
     * `EbeanTaskQueue.enqueue`'s envelope, which no other assertion can see
     * (`docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`).
     */
    fun currentTransaction(): Transaction?
}
