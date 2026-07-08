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
    override fun save(image: Image): Image =
        database.beginTransaction().use { transaction ->
            QImageModel(database).pinId.equalTo(image.pinId).delete()
            val model = image.toModel()
            database.save(model)
            transaction.commit()
            model.toDomain()
        }

    override fun findByPinId(pinId: UUID): Image? =
        QImageModel(database).pinId.equalTo(pinId).findOne()?.toDomain()

    override fun deleteByPinId(pinId: UUID) {
        QImageModel(database).pinId.equalTo(pinId).delete()
    }
}
