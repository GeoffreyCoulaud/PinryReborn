package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.BoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QBoardModel

/** Queries rooted on boards. */
object BoardQueries : SoftDeletableQueries<BoardModel, QBoardModel>(::QBoardModel, { it.softDeletedAt })
