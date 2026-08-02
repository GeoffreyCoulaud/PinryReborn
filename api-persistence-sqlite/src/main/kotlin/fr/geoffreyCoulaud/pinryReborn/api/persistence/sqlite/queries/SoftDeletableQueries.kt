package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.SoftDeletableModel
import io.ebean.typequery.PInstant
import io.ebean.typequery.QueryBean

/**
 * The three questions that can be asked of a recyclable model, asked the same way for every one.
 *
 * Outside this package a query bean cannot be constructed, and the recycling instant cannot be
 * turned into a predicate: those two shapes are what the structural rules hold, which is what makes
 * these constructors the place the state is stated. They are not a proof that every row read went
 * through one, since a read that names no query bean and writes no such predicate is invisible to
 * them. The state predicate then exists once, and a per-type declaration carries no logic beyond the
 * two things it cannot share: the bean to build and the accessor that reaches its own recycling
 * instant.
 *
 * The [M] bound is what ties the two halves together: a model that has not declared itself
 * recyclable cannot be given these constructors, and one that has gets all three in a single line.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a query base shared by the recyclable models.
abstract class SoftDeletableQueries<M : SoftDeletableModel, Q : QueryBean<M, Q>>(
    private val newQuery: () -> Q,
    private val softDeletedAt: (Q) -> PInstant<Q>,
) {
    /** Rows that are not in the recycle bin. */
    fun active(): Q = softDeletedAt(newQuery()).isNull

    /** Rows in the recycle bin. */
    fun recycled(): Q = softDeletedAt(newQuery()).isNotNull

    /** Every row, whatever its state. The caller states that it means it. */
    fun any(): Q = newQuery()
}
