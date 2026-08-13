package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QImageModel
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class EbeanImageRepository(
    private val persistor: Persistor,
    private val transactionRunner: TransactionRunner,
) : ImageRepositoryInterface {
    // The delete and the save are one unit; a caller's transaction is joined, not nested.
    override fun save(image: Image): Image = transactionRunner.inTransaction { saveWithin(image) }

    private fun saveWithin(image: Image): Image {
        QImageModel().pinId.equalTo(image.pinId).delete()
        val model = image.toModel()
        persistor.save(model)
        return model.toDomain()
    }

    override fun findByPinId(pinId: UUID): Image? =
        QImageModel().pinId.equalTo(pinId).findOne()?.toDomain()

    override fun deleteByPinId(pinId: UUID) {
        QImageModel().pinId.equalTo(pinId).delete()
    }

    override fun findMissingImageIds(candidates: Collection<UUID>): Set<UUID> {
        if (candidates.isEmpty()) return emptySet()
        val existing = QImageModel().id.isIn(candidates).findIds<UUID>()
        return candidates.toSet() - existing.toSet()
    }
}
