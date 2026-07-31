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
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

@ApplicationScoped
class UserPasswordHashRepository(
    database: Database,
) : UserPasswordHashRepositoryInterface {
    private val sqlRepository = ModelRepository(entityClass = UserPasswordHashModel::class, database = database)

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
            translateIfCollision(error)
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

    companion object {
        // Package-visible for the focused unit tests of the collision decision; not part of the
        // repository port. The cause structure (PersistenceException wrapping SQLiteException) was
        // observed empirically against Ebean-on-SQLite and is pinned by the duplicate-insert test.
        internal fun isUniqueConstraint(error: PersistenceException): Boolean {
            val sqliteException = error.cause as? SQLiteException ?: return false
            return sqliteException.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
        }

        // Always throws (returns Nothing); the catch site has no branch of its own. Only a
        // unique-constraint violation on (user_id, when_created) becomes a 409 collision; any other
        // persistence failure (NOT NULL, connection, ...) must surface as a genuine 500. SQLite
        // wraps both as PersistenceException(SQLiteException) and both share vendor errorCode 19,
        // so the typed resultCode is the one reliable discriminator (verified empirically:
        // SQLITE_CONSTRAINT_UNIQUE vs SQLITE_CONSTRAINT_NOTNULL). Extracted so the rethrow branch
        // is unit-testable: a non-unique PersistenceException cannot be produced through the public
        // save against a real store, so the branch could not otherwise be covered.
        internal fun translateIfCollision(error: PersistenceException): Nothing {
            if (isUniqueConstraint(error)) throw PasswordChangeCollisionException(cause = error)
            throw error
        }
    }
}
