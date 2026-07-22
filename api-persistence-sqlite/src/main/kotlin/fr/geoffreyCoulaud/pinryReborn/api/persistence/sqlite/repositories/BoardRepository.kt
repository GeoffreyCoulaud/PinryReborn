package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.BoardModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.BoardModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.BoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QBoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinBoardModel
import io.ebean.Database
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
    private val database: Database,
) : BoardRepositoryInterface {
    private val sqlRepository = ModelRepository(entityClass = BoardModel::class, database = database)

    // Case-insensitive name sort with id tie-breaker, applied in-memory after the DB fetch
    // (SQLite collation for `lower()` ordering is simplest handled here; sets are small).
    private fun List<BoardModel>.sortedForListing(): List<BoardModel> =
        sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    override fun saveBoard(board: Board): Board {
        val model = sqlRepository.saveAndReturn(board.toModel())
        // Re-read by id rather than mapping `model` directly: its `author` is still the bare
        // placeholder built by `Board.toModel()` (id + name only, no Ebean-managed timestamps), so
        // mapping it straight would throw UninitializedPropertyAccessException on
        // `UserModel.whenCreated`. A fresh query loads a genuine Ebean reference for the author.
        return findBoardById(model.id)!!
    }

    override fun findBoardById(id: UUID): Board? =
        QBoardModel().id.equalTo(id).findOne()?.toDomain()

    override fun findActiveBoardById(id: UUID): Board? =
        QBoardModel().id.equalTo(id).softDeletedAt.isNull.findOne()?.toDomain()

    override fun findActiveBoardsForUser(user: User): List<Board> =
        QBoardModel().author.id.equalTo(user.id).softDeletedAt.isNull
            .findList().sortedForListing().map { it.toDomain() }

    override fun findRecycledBoardsForUser(user: User): List<Board> =
        QBoardModel().author.id.equalTo(user.id).softDeletedAt.isNotNull
            .findList().sortedForListing().map { it.toDomain() }

    override fun softDeleteBoard(board: Board): Board {
        val model = QBoardModel().id.equalTo(board.id).findOne()!!
        model.softDeletedAt = Instant.now()
        database.save(model)
        return model.toDomain()
    }

    override fun restoreBoard(board: Board): Board {
        val model = QBoardModel().id.equalTo(board.id).findOne()!!
        model.softDeletedAt = null
        database.save(model)
        return model.toDomain()
    }

    override fun permanentlyDeleteBoard(board: Board) {
        QPinBoardModel().board.id.equalTo(board.id).delete()
        QBoardModel().id.equalTo(board.id).delete()
    }

    override fun permanentlyDeleteAllRecycledBoardsForUser(user: User) {
        val recycledIds = QBoardModel().author.id.equalTo(user.id).softDeletedAt.isNotNull
            .findList().map { it.id }
        if (recycledIds.isEmpty()) return
        QPinBoardModel().board.id.isIn(recycledIds).delete()
        QBoardModel().id.isIn(recycledIds).delete()
    }

    override fun permanentlyDeleteAllBoardsForUser(user: User) {
        val boardIds = QBoardModel().author.id.equalTo(user.id).findList().map { it.id }
        if (boardIds.isEmpty()) return
        QPinBoardModel().board.id.isIn(boardIds).delete()
        QBoardModel().id.isIn(boardIds).delete()
    }

    override fun countActivePinsInBoard(boardId: UUID): Int =
        QPinBoardModel().board.id.equalTo(boardId).pin.softDeletedAt.isNull.findCount()
}
