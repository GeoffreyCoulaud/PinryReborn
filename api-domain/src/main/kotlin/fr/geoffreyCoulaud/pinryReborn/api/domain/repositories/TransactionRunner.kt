package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

/**
 * Runs [block] inside a single persistence transaction. Repository calls and task enqueues issued
 * from within [block] join that ambient transaction, so multi-write operations commit atomically.
 * The domain declares the port; the persistence adapter implements it (no Ebean leaks into callers).
 */
interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}
