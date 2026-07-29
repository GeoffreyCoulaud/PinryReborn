package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import java.time.Instant

/**
 * A model whose rows are recycled rather than removed.
 *
 * Implementing this is the declaration that queries on the type go through its `Queries` object:
 * the structural rules read this interface to know what they guard, so a new recyclable model is
 * covered by declaring itself, with no rule to update.
 *
 * A plain Kotlin interface, not a `@MappedSuperclass`: it carries no column and no mapping, the
 * implementing entity already declaring the property it names.
 */
interface SoftDeletableModel {
    var softDeletedAt: Instant?
}
