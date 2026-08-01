package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.BoardModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.BoardModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.PinModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.PinModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TagModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TagModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.PinBoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.PinModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.PinTagModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinBoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinTagModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelPaginationHelper
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.PinModelSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries.PinQueries
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries.withActiveBoard
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
// PinRepositoryInterface's surface (11 methods) plus this adapter's four private query helpers
// (getTagsForPin, savePinTags, getBoardsForPin, savePinBoards) trips detekt's default per-class
// threshold. Suppressed rather than split, since splitting would fragment one cohesive adapter
// across artificial classes for no readability gain (mirrors EbeanTaskQueue's precedent for the
// same rule).
@Suppress("TooManyFunctions")
class PinRepository(
    private val database: Database,
) : PinRepositoryInterface {
    private val sqlRepository =
        ModelRepository(
            entityClass = PinModel::class,
            database = database,
        )

    private fun getTagsForPin(pinId: UUID): List<Tag> =
        QPinTagModel()
            .pin.id
            .equalTo(pinId)
            .fetch("tag")
            .findList()
            .map { it.tag.toDomain() }

    // Only active (non soft-deleted) boards are exposed on a pin: a recycled board must never
    // appear in a pin's boards even though the join row is kept (mirrors softDeleteBoard's contract).
    private fun getBoardsForPin(pinId: UUID): List<Board> =
        QPinBoardModel()
            .pin.id
            .equalTo(pinId)
            .withActiveBoard()
            .fetch("board")
            .findList()
            .map { it.board.toDomain() }

    override fun findBoardsForPinIncludingRecycled(pinId: UUID): List<Board> =
        QPinBoardModel()
            .pin.id
            .equalTo(pinId)
            .fetch("board")
            .findList()
            .map { it.board.toDomain() }

    override fun savePin(pin: Pin): Pin {
        val pinModel = sqlRepository.saveAndReturn(pin.toModel())
        savePinTags(pinModel, pin.tags)
        savePinBoards(pinModel, pin.boards)
        return pinModel.toDomain(getTagsForPin(pinModel.id), getBoardsForPin(pinModel.id))
    }

    private fun savePinTags(
        pinModel: PinModel,
        tags: List<Tag>,
    ) {
        // Get the new tag IDs
        val updatedTagIds = tags.map { it.id }.toSet()
        val existingTagIds =
            QPinTagModel()
                .pin.id
                .equalTo(pinModel.id)
                .findList()
                .map { it.tag.id }
                .toSet()

        // Remove the appropriate ones
        val removedTagIds = existingTagIds.minus(updatedTagIds)
        QPinTagModel()
            .pin.id
            .equalTo(pinModel.id)
            .tag.id
            .isIn(removedTagIds)
            .delete()

        // Persist the new tags
        val newTagIds = updatedTagIds.minus(existingTagIds)
        tags
            .filter { newTagIds.contains(it.id) }
            .map { tag -> PinTagModel(pin = pinModel, tag = tag.toModel()) }
            .forEach { database.save(it) }
    }

    private fun savePinBoards(
        pinModel: PinModel,
        boards: List<Board>,
    ) {
        // Get the new board IDs
        val updatedBoardIds = boards.map { it.id }.toSet()
        // Only diff against ACTIVE memberships: a recycled board's join row is kept (getBoardsForPin
        // never exposes it, so `boards` can't contain it), and re-saving the pin must not remove it.
        val existingBoardIds =
            QPinBoardModel()
                .pin.id
                .equalTo(pinModel.id)
                .withActiveBoard()
                .findList()
                .map { it.board.id }
                .toSet()

        // Remove the appropriate ones
        val removedBoardIds = existingBoardIds.minus(updatedBoardIds)
        QPinBoardModel()
            .pin.id
            .equalTo(pinModel.id)
            .board.id
            .isIn(removedBoardIds)
            .delete()

        // Persist the new boards
        val newBoardIds = updatedBoardIds.minus(existingBoardIds)
        boards
            .filter { newBoardIds.contains(it.id) }
            .map { board -> PinBoardModel(pin = pinModel, board = board.toModel()) }
            .forEach { database.save(it) }
    }

    // A page of recycled pins is walked from a recycled pivot and a page of active ones from an
    // active pivot, so the pivot is read whatever its state. Narrowing this lookup would strand
    // every caller whose page is not the one the narrowing picked.
    private fun findCursorPivot(cursor: Cursor?): ModelCursor<PinModel>? =
        cursor
            ?.let { PinQueries.any().id.equalTo(it.pivotId).findOne() }
            ?.let { ModelCursor(pivot = it, direction = cursor.direction) }

    override fun findPinById(id: UUID): Pin? {
        val pin =
            PinQueries
                .any()
                .id
                .equalTo(id)
                .findOne() ?: return null
        return pin.toDomain(getTagsForPin(pin.id), getBoardsForPin(pin.id))
    }

    override fun findPinsForUser(
        reader: User,
        cursor: Cursor?,
        pageSize: Int,
        sortStrategy: PinSortStrategy,
    ): Page<Pin> {
        val modelPage =
            ModelPaginationHelper.getPage(
                cursor = findCursorPivot(cursor),
                pageSize = pageSize,
                baseQuery = PinQueries.active().author.id.equalTo(reader.id),
                sortStrategy = PinModelSortStrategy.fromDomain(sortStrategy),
            )
        return Page(
            items = modelPage.items.map { it.toDomain(getTagsForPin(it.id), getBoardsForPin(it.id)) },
            nextCursor = modelPage.nextCursor?.toDomain(),
            previousCursor = modelPage.previousCursor?.toDomain(),
        )
    }

    override fun findAllPinsForUser(user: User): List<Pin> =
        PinQueries
            .active()
            .author.id
            .equalTo(user.id)
            .findList()
            .map { it.toDomain(getTagsForPin(it.id), getBoardsForPin(it.id)) }

    override fun findAllPinIdsForUser(user: User): List<UUID> =
        PinQueries.any().author.id.equalTo(user.id).findList().map { it.id }

    override fun softDeletePin(pin: Pin, at: Instant): Pin {
        val model = checkNotNull(PinQueries.any().id.equalTo(pin.id).findOne()) {
            "pin ${pin.id} vanished between read and soft-delete transition"
        }
        model.softDeletedAt = at
        model.updatedAt = at
        database.save(model)
        return model.toDomain(getTagsForPin(model.id), getBoardsForPin(model.id))
    }

    override fun restorePin(pin: Pin, at: Instant): Pin {
        val model = checkNotNull(PinQueries.any().id.equalTo(pin.id).findOne()) {
            "pin ${pin.id} vanished between read and restore transition"
        }
        model.softDeletedAt = null
        model.updatedAt = at
        database.save(model)
        return model.toDomain(getTagsForPin(model.id), getBoardsForPin(model.id))
    }

    override fun permanentlyDeletePin(pin: Pin) {
        QPinTagModel().pin.id.equalTo(pin.id).delete()
        QPinBoardModel().pin.id.equalTo(pin.id).delete()
        PinQueries.any().id.equalTo(pin.id).delete()
    }

    override fun permanentlyDeleteAllSoftDeletedPinsForUser(user: User) {
        val softDeletedPinIds = PinQueries
            .recycled()
            .author.id.equalTo(user.id)
            .findList()
            .map { it.id }
        if (softDeletedPinIds.isEmpty()) return
        QPinTagModel().pin.id.isIn(softDeletedPinIds).delete()
        QPinBoardModel().pin.id.isIn(softDeletedPinIds).delete()
        PinQueries.any().id.isIn(softDeletedPinIds).delete()
    }

    override fun permanentlyDeleteAllPinsForUser(user: User) {
        val pinIds = PinQueries.any().author.id.equalTo(user.id).findList().map { it.id }
        if (pinIds.isEmpty()) return
        QPinTagModel().pin.id.isIn(pinIds).delete()
        QPinBoardModel().pin.id.isIn(pinIds).delete()
        PinQueries.any().id.isIn(pinIds).delete()
    }

    override fun findAllSoftDeletedPinsForUser(user: User): List<Pin> =
        PinQueries
            .recycled()
            .author.id
            .equalTo(user.id)
            .findList()
            .map { it.toDomain(getTagsForPin(it.id), getBoardsForPin(it.id)) }

    override fun findSoftDeletedPinsForUser(
        reader: User,
        cursor: Cursor?,
        pageSize: Int,
        sortStrategy: PinSortStrategy,
    ): Page<Pin> {
        val modelPage =
            ModelPaginationHelper.getPage(
                cursor = findCursorPivot(cursor),
                pageSize = pageSize,
                baseQuery = PinQueries.recycled().author.id.equalTo(reader.id),
                sortStrategy = PinModelSortStrategy.fromDomain(sortStrategy),
            )
        return Page(
            items = modelPage.items.map { it.toDomain(getTagsForPin(it.id), getBoardsForPin(it.id)) },
            nextCursor = modelPage.nextCursor?.toDomain(),
            previousCursor = modelPage.previousCursor?.toDomain(),
        )
    }

    override fun findActivePinsForBoard(
        reader: User,
        boardId: UUID,
        cursor: Cursor?,
        pageSize: Int,
        sortStrategy: PinSortStrategy,
    ): Page<Pin> {
        // Loads the board's pin ids up front; acceptable for v1, called out in spec §11 as a
        // scaling risk (large boards mean a large IN clause).
        val pinIdsInBoard =
            QPinBoardModel().board.id.equalTo(boardId).findList().map { it.pin.id }
        val modelPage =
            ModelPaginationHelper.getPage(
                cursor = findCursorPivot(cursor),
                pageSize = pageSize,
                baseQuery = PinQueries
                    .active()
                    .author.id.equalTo(reader.id)
                    .id.isIn(pinIdsInBoard),
                sortStrategy = PinModelSortStrategy.fromDomain(sortStrategy),
            )
        return Page(
            items = modelPage.items.map { it.toDomain(getTagsForPin(it.id), getBoardsForPin(it.id)) },
            nextCursor = modelPage.nextCursor?.toDomain(),
            previousCursor = modelPage.previousCursor?.toDomain(),
        )
    }
}
