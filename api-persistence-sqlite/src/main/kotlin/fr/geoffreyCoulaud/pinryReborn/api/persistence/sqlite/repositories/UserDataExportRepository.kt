package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserDataExportModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserDataExportModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataExportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserDataExportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelPaginationHelper
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.UserDataExportModelSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.pageByIdAfter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import java.time.Instant
import java.util.UUID

// Implements every port method of UserDataExportRepositoryInterface plus its private helpers, which
// trips detekt's default per-class threshold. Suppressed rather than split, since splitting would
// fragment one cohesive adapter across artificial classes for no readability gain (mirrors
// BoardRepository and EbeanTaskQueue for the same rule).
@Suppress("TooManyFunctions")
@ApplicationScoped
class UserDataExportRepository(
    private val persistor: Persistor,
) : UserDataExportRepositoryInterface {
    private val sqlRepository = ModelRepository<UserDataExportModel>(persistor = persistor)

    private fun persist(model: UserDataExportModel): UserDataExport = sqlRepository.saveAndReturn(model).toDomain()

    /**
     * A state-change re-save (any state but PENDING) only updates columns on a row that already
     * exists, so it does not re-resolve the active user: the owner may be tombstoned by now (the
     * account cleaner reaps exports after the user), and re-resolving would throw
     * `UserModelDoesNotExistError` and abort the retention sweep. The user id is enough to keep the
     * foreign key, supplied as an Ebean reference that never loads the row.
     *
     * A new PENDING export still resolves the active user: that lookup is the guard that keeps a
     * tombstoned account from queueing more work against its own data. It stays outside the try so
     * `UserModelDoesNotExistError` is never mistaken for the unique-index violation translated below.
     */
    override fun save(export: UserDataExport): UserDataExport {
        if (export.state != UserDataExportState.PENDING) {
            val userReference = persistor.reference(UserModel::class.java, export.userId)
            return persist(export.toModel(userReference))
        }
        val model = export.toModel(ActiveUserModels.resolve(export.userId))
        return try {
            persist(model)
        } catch (error: PersistenceException) {
            // Only a unique-constraint violation is an export already in progress (409).
            SqliteConstraintViolations.translateUniqueConstraint(error) {
                ExportAlreadyInProgressException(cause = it)
            }
        }
    }

    override fun findById(id: UUID): UserDataExport? =
        QUserDataExportModel().id.equalTo(id).findOne()?.toDomain()

    override fun findAllForUser(
        userId: UUID,
        cursor: Cursor?,
        pageSize: Int,
    ): Page<UserDataExport> {
        val modelCursor =
            cursor
                ?.let { QUserDataExportModel().id.equalTo(it.pivotId).findOne() }
                ?.let { ModelCursor(pivot = it, direction = cursor.direction) }
        val modelPage =
            ModelPaginationHelper.getPage(
                cursor = modelCursor,
                pageSize = pageSize,
                baseQuery = QUserDataExportModel().user.id.equalTo(userId),
                sortStrategy = UserDataExportModelSortStrategy(),
            )
        return Page(
            items = modelPage.items.map { it.toDomain() },
            nextCursor = modelPage.nextCursor?.toDomain(),
            previousCursor = modelPage.previousCursor?.toDomain(),
        )
    }

    override fun findPendingForUser(userId: UUID): UserDataExport? =
        QUserDataExportModel()
            .user.id.equalTo(userId)
            .state.isIn(PartialUniqueIndexStates.pendingExportStates)
            .findOne()
            ?.toDomain()

    override fun findReadyForUser(userId: UUID): UserDataExport? =
        QUserDataExportModel()
            .user.id.equalTo(userId)
            .state.equalTo(UserDataExportState.READY.name)
            .findOne()
            ?.toDomain()

    override fun findLastRequestedAtForUser(userId: UUID): Instant? =
        QUserDataExportModel()
            .user.id.equalTo(userId)
            .orderBy()
            .requestedAt.desc()
            .setMaxRows(1)
            .findOne()
            ?.requestedAt

    override fun findExpiredReadyExports(now: Instant, afterId: UUID?, limit: Int): List<UserDataExport> =
        QUserDataExportModel()
            .state.equalTo(UserDataExportState.READY.name)
            .expiresAt.lessThan(now)
            .pageByIdAfter(afterId, limit)
            .findList()
            .map { it.toDomain() }

    override fun findPending(limit: Int): List<UserDataExport> =
        QUserDataExportModel()
            .state.equalTo(UserDataExportState.PENDING.name)
            .orderBy()
            .requestedAt.asc()
            .setMaxRows(limit)
            .findList()
            .map { it.toDomain() }

    override fun findReclaimableTerminal(afterId: UUID?, limit: Int): List<UserDataExport> =
        QUserDataExportModel()
            .state.isIn(TerminalExportStates.all)
            .storageKey.isNotNull()
            .pageByIdAfter(afterId, limit)
            .findList()
            .map { it.toDomain() }

    override fun findAllExportIdsForUser(userId: UUID): List<UUID> =
        QUserDataExportModel().user.id.equalTo(userId).findList().map { it.id }

    override fun findMissingExportIds(candidates: Collection<UUID>): Set<UUID> {
        if (candidates.isEmpty()) return emptySet()
        val existing = QUserDataExportModel().id.isIn(candidates).findIds<UUID>()
        return candidates.toSet() - existing.toSet()
    }

    override fun deleteAllForUser(userId: UUID) {
        QUserDataExportModel().user.id.equalTo(userId).delete()
    }

    /** The states nothing more will happen to, spelled as the column stores them. */
    private object TerminalExportStates {
        val all: Set<String> = UserDataExportState.entries.filter { it.isTerminal }.map { it.name }.toSet()
    }
}
