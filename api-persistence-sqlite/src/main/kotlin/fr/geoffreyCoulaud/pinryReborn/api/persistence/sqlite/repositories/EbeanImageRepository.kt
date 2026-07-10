package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QImageModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class EbeanImageRepository(
    private val database: Database,
) : ImageRepositoryInterface {
    // Ambient-transaction-aware: when a TransactionRunner already opened a transaction on this thread,
    // join it (so the delete+save commits atomically with the caller's other writes, e.g. deleting the
    // download row) instead of opening (and committing) our own. Only when there is no ambient
    // transaction do we open our own, so delete-then-save still serializes as one unit.
    override fun save(image: Image): Image =
        if (database.currentTransaction() != null) {
            saveWithin(image)
        } else {
            database.beginTransaction().use { transaction ->
                val result = saveWithin(image)
                transaction.commit()
                result
            }
        }

    private fun saveWithin(image: Image): Image {
        QImageModel(database).pinId.equalTo(image.pinId).delete()
        val model = image.toModel()
        database.save(model)
        return model.toDomain()
    }

    override fun findByPinId(pinId: UUID): Image? =
        QImageModel(database).pinId.equalTo(pinId).findOne()?.toDomain()

    override fun deleteByPinId(pinId: UUID) {
        QImageModel(database).pinId.equalTo(pinId).delete()
    }
}
