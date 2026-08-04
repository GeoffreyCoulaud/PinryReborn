package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordChangeCollisionException
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserPasswordHashModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserPasswordHashModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserPasswordHashModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserPasswordHashModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException

@ApplicationScoped
class UserPasswordHashRepository(
    persistor: Persistor,
) : UserPasswordHashRepositoryInterface {
    private val sqlRepository = ModelRepository<UserPasswordHashModel>(persistor = persistor)

    override fun saveUserPasswordHash(
        user: User,
        hashedPassword: HashedPassword,
    ): HashedPassword {
        // resolve() throws UserModelDoesNotExistError (the project's own PersistenceException, not the
        // jakarta one caught below) for a tombstoned or absent user; it stays outside the try so the two
        // error paths stay distinct.
        val hashedPasswordModel = hashedPassword.toModel(ActiveUserModels.resolve(user.id))
        return try {
            sqlRepository.saveAndReturn(hashedPasswordModel).toDomain()
        } catch (error: PersistenceException) {
            // Only a unique-constraint violation on (user_id, when_created) is a 409 collision.
            SqliteConstraintViolations.translateUniqueConstraint(error) {
                PasswordChangeCollisionException(cause = it)
            }
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
