package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportAlreadyInProgressException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserDataImportModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserDataImportModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataImportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserDataImportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelPaginationHelper
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.UserDataImportModelSortStrategy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import java.time.Instant
import java.util.UUID

// Implements every port method plus its private helpers, which trips detekt's per-class threshold.
// Suppressed rather than split, as UserDataExportRepository and EbeanTaskQueue are for the same rule.
@Suppress("TooManyFunctions")
@ApplicationScoped
class UserDataImportRepository(
    private val persistor: Persistor,
) : UserDataImportRepositoryInterface {
    private val sqlRepository = ModelRepository<UserDataImportModel>(persistor = persistor)

    private fun persist(model: UserDataImportModel): UserDataImport = sqlRepository.saveAndReturn(model).toDomain()

    /**
     * An active state resolves the active account, refusing a tombstoned one more work, and is the only
     * save the index can refuse. A terminal one references the owner without loading a row long gone.
     */
    override fun save(userDataImport: UserDataImport): UserDataImport {
        if (!userDataImport.state.isActive) {
            val userReference = persistor.reference(UserModel::class.java, userDataImport.userId)
            return persist(userDataImport.toModel(userReference))
        }
        val model = userDataImport.toModel(ActiveUserModels.resolve(userDataImport.userId))
        return try {
            persist(model)
        } catch (error: PersistenceException) {
            SqliteConstraintViolations.translateUniqueConstraint(error) {
                ImportAlreadyInProgressException(cause = it)
            }
        }
    }

    override fun findById(id: UUID): UserDataImport? = QUserDataImportModel().id.equalTo(id).findOne()?.toDomain()

    override fun findAllForUser(
        userId: UUID,
        cursor: Cursor?,
        pageSize: Int,
    ): Page<UserDataImport> {
        val modelCursor =
            cursor
                ?.let { QUserDataImportModel().id.equalTo(it.pivotId).findOne() }
                ?.let { ModelCursor(pivot = it, direction = cursor.direction) }
        val modelPage =
            ModelPaginationHelper.getPage(
                cursor = modelCursor,
                pageSize = pageSize,
                baseQuery = QUserDataImportModel().user.id.equalTo(userId),
                sortStrategy = UserDataImportModelSortStrategy(),
            )
        return Page(
            items = modelPage.items.map { it.toDomain() },
            nextCursor = modelPage.nextCursor?.toDomain(),
            previousCursor = modelPage.previousCursor?.toDomain(),
        )
    }

    // The grace counts inactivity, so a row that never received a chunk falls back on its request time.
    override fun findAbandonableBefore(instant: Instant): List<UserDataImport> =
        QUserDataImportModel()
            .state.equalTo(UserDataImportState.AWAITING_ARCHIVE.name)
            .raw("coalesce(last_upload_activity_at, requested_at) < ?", instant)
            .findList()
            .map { it.toDomain() }

    override fun findReclaimableTerminal(): List<UserDataImport> =
        QUserDataImportModel()
            .state.isIn(TerminalImportStates.all)
            .storageKey.isNotNull()
            .findList()
            .map { it.toDomain() }

    override fun findRunning(): List<UserDataImport> =
        QUserDataImportModel()
            .state.equalTo(UserDataImportState.RUNNING.name)
            .findList()
            .map { it.toDomain() }

    override fun findAllImportIdsForUser(userId: UUID): List<UUID> =
        QUserDataImportModel().user.id.equalTo(userId).findList().map { it.id }

    override fun findMissingImportIds(candidates: Collection<UUID>): Set<UUID> {
        if (candidates.isEmpty()) return emptySet()
        val existing = QUserDataImportModel().id.isIn(candidates).findIds<UUID>()
        return candidates.toSet() - existing.toSet()
    }

    override fun deleteAllForUser(userId: UUID) {
        QUserDataImportModel().user.id.equalTo(userId).delete()
    }

    /** The states nothing more will happen to, spelled as the column stores them. */
    private object TerminalImportStates {
        val all: Set<String> = UserDataImportState.entries.filter { it.isTerminal }.map { it.name }.toSet()
    }
}
