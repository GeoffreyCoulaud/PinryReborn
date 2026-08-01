package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QSessionTokenModel

// A query constructor cannot cover a filter rooted on session_tokens: that root is not itself
// recyclable, so there is no state to ask about until the user association is navigated. An
// extension can, and it keeps the predicate in the package that owns every other one. See
// PinBoardQueries for the same shape on the pin-to-board join.

/** Session tokens whose owner is not in the recycle bin. */
fun QSessionTokenModel.withActiveUser(): QSessionTokenModel = user.softDeletedAt.isNull
