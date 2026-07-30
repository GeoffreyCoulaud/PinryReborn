package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordChangeCollisionException
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserPasswordHashModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserPasswordHashModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserPasswordHashModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserPasswordHashModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException

@ApplicationScoped
class UserPasswordHashRepository(
    database: Database,
) : UserPasswordHashRepositoryInterface {
    private val sqlRepository = ModelRepository(entityClass = UserPasswordHashModel::class, database = database)

    override fun saveUserPasswordHash(
        user: User,
        hashedPassword: HashedPassword,
    ): HashedPassword {
        // resolve() throws UserModelDoesNotExistError (a PersistenceException) for a tombstoned or
        // absent user; it stays outside the try so that is never reported as a collision.
        val hashedPasswordModel = hashedPassword.toModel(ActiveUserModels.resolve(user.id))
        return try {
            sqlRepository.saveAndReturn(hashedPasswordModel).toDomain()
        } catch (error: PersistenceException) {
            throw PasswordChangeCollisionException(cause = error)
        }
    }

    override fun findCurrentPasswordHash(user: User): HashedPassword? =
        QUserPasswordHashModel()
            .user.id
            .equalTo(user.id)
            .orderBy()
            .createdAt
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
