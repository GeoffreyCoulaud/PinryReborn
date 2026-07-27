package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class UserRepository(
    private val database: Database,
) : UserRepositoryInterface {
    /**
     * When possible, avoid using the SQL repository directly.
     *
     * Favor usage of ebean's Query Beans.
     * https://ebean.io/docs/query/query-beans
     */
    private val sqlRepository = ModelRepository(entityClass = UserModel::class, database = database)

    override fun findUserById(id: UUID): User? =
        QUserModel()
            .id
            .equalTo(id)
            .findOne()
            ?.toDomain()

    override fun findUserByName(name: String): User? =
        QUserModel()
            .name
            .ieq(name)
            .findOne()
            ?.toDomain()

    override fun findUserByNameIncludingDeleted(name: String): User? =
        QUserModel()
            .name
            .ieq(name)
            .setIncludeSoftDeletes()
            .findOne()
            ?.toDomain()

    override fun findUserByIdIncludingDeleted(id: UUID): User? =
        QUserModel()
            .id
            .equalTo(id)
            .setIncludeSoftDeletes()
            .findOne()
            ?.toDomain()

    override fun saveUser(user: User): User = sqlRepository.saveAndReturn(user.toModel()).toDomain()

    override fun markPendingDeletion(user: User) {
        val model =
            QUserModel()
                .id
                .equalTo(user.id)
                .findOne() ?: return
        database.delete(model)
    }

    override fun permanentlyDeleteUser(user: User) {
        val model =
            QUserModel()
                .id
                .equalTo(user.id)
                .setIncludeSoftDeletes()
                .findOne() ?: return
        database.deletePermanent(model)
    }

    override fun findTombstonedUsersModifiedBefore(cutoff: Instant): List<User> =
        QUserModel()
            .deleted
            .isTrue
            .whenModified
            .lessThan(cutoff)
            .setIncludeSoftDeletes()
            .findList()
            .map { it.toDomain() }
}
