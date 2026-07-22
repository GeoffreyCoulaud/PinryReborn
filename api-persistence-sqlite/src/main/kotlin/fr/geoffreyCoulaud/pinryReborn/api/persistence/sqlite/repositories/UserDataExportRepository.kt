package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserDataExportModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserDataExportModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataExportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserDataExportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelPaginationHelper
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.UserDataExportModelSortStrategy
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class UserDataExportRepository(
    private val database: Database,
) : UserDataExportRepositoryInterface {
    private val sqlRepository = ModelRepository(entityClass = UserDataExportModel::class, database = database)
    private val paginationHelper = ModelPaginationHelper<UserDataExportModel, QUserDataExportModel>()

    private fun resolveUser(userId: UUID): UserModel =
        QUserModel().id.equalTo(userId).findOne() ?: throw UserModelDoesNotExistError()

    // The unique-index violation on `user_data_exports (user_id) WHERE state = 'PENDING'` surfaces
    // through this exact call as a plain `jakarta.persistence.PersistenceException` wrapping
    // `org.sqlite.SQLiteException: [SQLITE_CONSTRAINT_UNIQUE] ...` (verified against a live SQLite
    // database: Ebean/SQLite does NOT translate it to `io.ebean.DuplicateKeyException` here). The
    // user lookup is deliberately outside the try block so UserModelDoesNotExistError is never
    // mistaken for the unique-index violation.
    override fun save(export: UserDataExport): UserDataExport {
        val user = resolveUser(export.userId)
        return try {
            sqlRepository.saveAndReturn(export.toModel(user)).toDomain()
        } catch (error: PersistenceException) {
            throw ExportAlreadyInProgressException(cause = error)
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
            paginationHelper.getPage(
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
            .state.equalTo(UserDataExportState.PENDING.name)
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

    override fun findExpiredReadyExports(now: Instant): List<UserDataExport> =
        QUserDataExportModel()
            .state.equalTo(UserDataExportState.READY.name)
            .expiresAt.lessThan(now)
            .findList()
            .map { it.toDomain() }

    override fun findAllExportIdsForUser(userId: UUID): List<UUID> =
        QUserDataExportModel().user.id.equalTo(userId).findList().map { it.id }

    override fun deleteAllForUser(userId: UUID) {
        QUserDataExportModel().user.id.equalTo(userId).delete()
    }
}
