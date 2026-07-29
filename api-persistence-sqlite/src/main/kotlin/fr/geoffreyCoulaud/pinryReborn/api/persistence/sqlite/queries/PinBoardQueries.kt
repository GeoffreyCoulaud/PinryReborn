package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinBoardModel

// A query constructor cannot cover a filter rooted on the pin-to-board join table: that root is not
// itself recyclable, so there is no state to ask about until an association is navigated. An
// extension can, and it keeps the predicate in the package that owns every other one. Two functions
// rather than one generic function taking the association as a parameter, which would read worse
// than the two lines it replaced.

/** Memberships whose board is not in the recycle bin. */
fun QPinBoardModel.withActiveBoard(): QPinBoardModel = board.softDeletedAt.isNull

/** Memberships whose pin is not in the recycle bin. */
fun QPinBoardModel.withActivePin(): QPinBoardModel = pin.softDeletedAt.isNull
