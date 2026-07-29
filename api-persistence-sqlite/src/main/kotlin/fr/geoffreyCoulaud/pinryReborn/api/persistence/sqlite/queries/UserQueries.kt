package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserModel
import java.time.Instant

/** Queries rooted on users. */
object UserQueries : SoftDeletableQueries<UserModel, QUserModel>(::QUserModel, { it.softDeletedAt }) {
    /**
     * Accounts tombstoned strictly before [cutoff], for the retention sweep.
     *
     * Retention asks a question of its own, and it belongs here for the same reason as the three
     * constructors: this package owns every predicate on the recycling instant.
     */
    fun tombstonedBefore(cutoff: Instant): QUserModel = recycled().softDeletedAt.lessThan(cutoff)
}
