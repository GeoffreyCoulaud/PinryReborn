package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.BoardModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.BoardModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.BoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinBoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries.BoardQueries
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries.withActivePin
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
// BoardRepositoryInterface's surface (10 methods) plus the private sortedForListing helper trips
// detekt's default per-class threshold. Suppressed rather than split, since splitting would
// fragment one cohesive adapter across artificial classes for no readability gain (mirrors
// PinRepository's precedent for the same rule).
@Suppress("TooManyFunctions")
class BoardRepository(
    private val persistor: Persistor,
) : BoardRepositoryInterface {
    private val sqlRepository = ModelRepository<BoardModel>(persistor = persistor)

    // Case-insensitive name sort with id tie-breaker, applied in-memory after the DB fetch
    // (SQLite collation for `lower()` ordering is simplest handled here; sets are small).
    private fun List<BoardModel>.sortedForListing(): List<BoardModel> =
        sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    override fun saveBoard(board: Board): Board = sqlRepository.saveAndReturn(board.toModel()).toDomain()

    override fun findBoardById(id: UUID): Board? =
        BoardQueries.any().id.equalTo(id).findOne()?.toDomain()

    override fun findActiveBoardById(id: UUID): Board? =
        BoardQueries.active().id.equalTo(id).findOne()?.toDomain()

    override fun findActiveBoardsForUser(user: User): List<Board> =
        BoardQueries.active().author.id.equalTo(user.id)
            .findList().sortedForListing().map { it.toDomain() }

    override fun findRecycledBoardsForUser(user: User): List<Board> =
        BoardQueries.recycled().author.id.equalTo(user.id)
            .findList().sortedForListing().map { it.toDomain() }

    override fun softDeleteBoard(board: Board, at: Instant): Board {
        val model = checkNotNull(BoardQueries.any().id.equalTo(board.id).findOne()) {
            "board ${board.id} vanished between read and soft-delete transition"
        }
        model.softDeletedAt = at
        model.updatedAt = at
        persistor.save(model)
        return model.toDomain()
    }

    override fun restoreBoard(board: Board, at: Instant): Board {
        val model = checkNotNull(BoardQueries.any().id.equalTo(board.id).findOne()) {
            "board ${board.id} vanished between read and restore transition"
        }
        model.softDeletedAt = null
        model.updatedAt = at
        persistor.save(model)
        return model.toDomain()
    }

    override fun permanentlyDeleteBoard(board: Board) {
        QPinBoardModel().board.id.equalTo(board.id).delete()
        BoardQueries.any().id.equalTo(board.id).delete()
    }

    override fun permanentlyDeleteAllRecycledBoardsForUser(user: User) {
        val recycledIds = BoardQueries.recycled().author.id.equalTo(user.id)
            .findList().map { it.id }
        if (recycledIds.isEmpty()) return
        QPinBoardModel().board.id.isIn(recycledIds).delete()
        BoardQueries.any().id.isIn(recycledIds).delete()
    }

    override fun permanentlyDeleteAllBoardsForUser(user: User) {
        val boardIds = BoardQueries.any().author.id.equalTo(user.id).findList().map { it.id }
        if (boardIds.isEmpty()) return
        QPinBoardModel().board.id.isIn(boardIds).delete()
        BoardQueries.any().id.isIn(boardIds).delete()
    }

    override fun countActivePinsInBoard(boardId: UUID): Int =
        QPinBoardModel().board.id.equalTo(boardId).withActivePin().findCount()
}
