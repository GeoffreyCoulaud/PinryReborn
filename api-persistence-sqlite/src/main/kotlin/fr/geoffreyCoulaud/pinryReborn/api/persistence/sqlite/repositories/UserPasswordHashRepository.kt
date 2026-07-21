package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserPasswordHashModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserPasswordHashModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserPasswordHashModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserPasswordHashModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class UserPasswordHashRepository(
    database: Database,
) : UserPasswordHashRepositoryInterface {
    private val sqlRepository = ModelRepository(entityClass = UserPasswordHashModel::class, database = database)

    override fun saveUserPasswordHash(
        user: User,
        hashedPassword: HashedPassword,
    ): HashedPassword {
        val userModel = QUserModel().id.equalTo(user.id).findOne() ?: throw UserModelDoesNotExistError()
        val hashedPasswordModel = hashedPassword.toModel(userModel)
        return sqlRepository.saveAndReturn(hashedPasswordModel).toDomain()
    }

    override fun findCurrentPasswordHash(user: User): HashedPassword? =
        QUserPasswordHashModel()
            .user.id
            .equalTo(user.id)
            .orderBy()
            .whenCreated
            .desc()
            .findList()
            .firstOrNull()
            ?.toDomain()

    override fun findAllPasswordHashesForUser(user: User): List<HashedPassword> =
        QUserPasswordHashModel()
            .user.id
            .equalTo(user.id)
            .findList()
            .map { it.toDomain() }

    override fun deleteForUser(user: User) {
        QUserPasswordHashModel()
            .user.id
            .equalTo(user.id)
            .delete()
    }
}
