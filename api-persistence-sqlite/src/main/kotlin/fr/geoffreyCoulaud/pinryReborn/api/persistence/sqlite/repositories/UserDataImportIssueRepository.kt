package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserDataImportIssueModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserDataImportIssueModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataImportIssueModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserDataImportModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserDataImportIssueModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelCursor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.ModelPaginationHelper
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination.UserDataImportIssueModelSortStrategy
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class UserDataImportIssueRepository(
    private val persistor: Persistor,
) : UserDataImportIssueRepositoryInterface {
    private val sqlRepository = ModelRepository<UserDataImportIssueModel>(persistor = persistor)

    // A reference, not a lookup: the walk writes one row per anomaly and never reads the import back.
    override fun save(issue: UserDataImportIssue): UserDataImportIssue {
        val importReference = persistor.reference(UserDataImportModel::class.java, issue.importId)
        return sqlRepository.saveAndReturn(issue.toModel(importReference)).toDomain()
    }

    override fun findAllForImport(
        importId: UUID,
        cursor: Cursor?,
        pageSize: Int,
    ): Page<UserDataImportIssue> {
        val modelCursor =
            cursor
                ?.let { QUserDataImportIssueModel().id.equalTo(it.pivotId).findOne() }
                ?.let { ModelCursor(pivot = it, direction = cursor.direction) }
        val modelPage =
            ModelPaginationHelper.getPage(
                cursor = modelCursor,
                pageSize = pageSize,
                baseQuery = QUserDataImportIssueModel().userDataImport.id.equalTo(importId),
                sortStrategy = UserDataImportIssueModelSortStrategy(),
            )
        return Page(
            items = modelPage.items.map { it.toDomain() },
            nextCursor = modelPage.nextCursor?.toDomain(),
            previousCursor = modelPage.previousCursor?.toDomain(),
        )
    }

    override fun countForImport(importId: UUID): Int =
        QUserDataImportIssueModel().userDataImport.id.equalTo(importId).findCount()

    override fun deleteAllForUser(userId: UUID) {
        QUserDataImportIssueModel().userDataImport.user.id.equalTo(userId).delete()
    }
}
