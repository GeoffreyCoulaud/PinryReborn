package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class UserRepository(
    database: Database,
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

    override fun saveUser(user: User): User = sqlRepository.saveAndReturn(user.toModel()).toDomain()

    override fun deleteUser(user: User) {
        QUserModel()
            .id
            .equalTo(user.id)
            .delete()
    }
}
