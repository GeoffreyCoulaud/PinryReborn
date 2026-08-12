package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.users.UsernameAlreadyTakenException
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries.UserQueries
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class UserRepository(
    private val persistor: Persistor,
) : UserRepositoryInterface {
    /**
     * When possible, avoid using the SQL repository directly.
     *
     * Favor usage of ebean's Query Beans.
     * https://ebean.io/docs/query/query-beans
     */
    private val sqlRepository = ModelRepository<UserModel>(persistor = persistor)

    override fun findUserById(id: UUID): User? =
        UserQueries.active()
            .id
            .equalTo(id)
            .findOne()
            ?.toDomain()

    override fun findUserByName(name: String): User? =
        UserQueries.active()
            .name
            .ieq(name)
            .findOne()
            ?.toDomain()

    override fun findUserByIdIncludingDeleted(id: UUID): User? =
        UserQueries.any()
            .id
            .equalTo(id)
            .findOne()
            ?.toDomain()

    override fun saveUser(user: User): User =
        try {
            sqlRepository.saveAndReturn(user.toModel()).toDomain()
        } catch (error: PersistenceException) {
            // ix_users_name_nocase is the only unique index on users, so a violation is a taken name.
            SqliteConstraintViolations.translateUniqueConstraint(error) {
                UsernameAlreadyTakenException(cause = it)
            }
        }

    // Rooted on the active accounts: a repeated deletion request then finds nothing and returns,
    // instead of re-stamping the instant and pushing the retention deadline further away every time.
    override fun markPendingDeletion(user: User, at: Instant) {
        val model =
            UserQueries.active()
                .id
                .equalTo(user.id)
                .findOne() ?: return
        model.softDeletedAt = at
        persistor.save(model)
    }

    override fun permanentlyDeleteUser(user: User) {
        val model =
            UserQueries.any()
                .id
                .equalTo(user.id)
                .findOne() ?: return
        persistor.delete(model)
    }

    override fun findTombstonedUsersSoftDeletedBefore(cutoff: Instant): List<User> =
        UserQueries.tombstonedBefore(cutoff)
            .findList()
            .map { it.toDomain() }
}
