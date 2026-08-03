package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.SessionTokenModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.SessionTokenModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QSessionTokenModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.queries.withActiveUser
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SessionTokenRepository(
    persistor: Persistor,
) : SessionTokenRepositoryInterface {
    private val sqlRepository = ModelRepository<SessionTokenModel>(persistor = persistor)

    override fun saveSessionToken(sessionToken: SessionToken, tokenHash: String): SessionToken {
        val userModel = ActiveUserModels.resolve(sessionToken.user.id)
        val model = SessionTokenModel(
            id = sessionToken.id,
            user = userModel,
            tokenHash = tokenHash,
            expiresAt = sessionToken.expiresAt,
            persistent = sessionToken.persistent,
            createdAt = sessionToken.createdAt,
        )
        return sqlRepository.saveAndReturn(model).toDomain()
    }

    override fun findByTokenHash(tokenHash: String): SessionToken? =
        QSessionTokenModel().tokenHash.equalTo(tokenHash).withActiveUser().findOne()?.toDomain()

    override fun deleteById(id: UUID) {
        QSessionTokenModel().id.equalTo(id).delete()
    }

    override fun deleteAllForUser(userId: UUID) {
        QSessionTokenModel().user.id.equalTo(userId).delete()
    }

    override fun deleteExpiredBefore(now: Instant): Int =
        QSessionTokenModel().expiresAt.lessThan(now).delete()
}
