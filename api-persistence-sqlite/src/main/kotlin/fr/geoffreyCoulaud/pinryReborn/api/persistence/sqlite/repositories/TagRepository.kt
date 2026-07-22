package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TagModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TagModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TagModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinTagModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QTagModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TagRepository(
    database: Database,
) : TagRepositoryInterface {
    private val sqlRepository = ModelRepository(entityClass = TagModel::class, database = database)

    override fun saveTag(tag: Tag): Tag {
        val model = sqlRepository.saveAndReturn(tag.toModel())
        // Re-read by id rather than mapping `model` directly: its `author` is still the bare
        // placeholder built by `Tag.toModel()` (id + name only, no Ebean-managed timestamps), so
        // mapping it straight would throw UninitializedPropertyAccessException on
        // `UserModel.whenCreated`. A fresh query loads a genuine Ebean reference for the author.
        return QTagModel().id.equalTo(model.id).findOne()!!.toDomain()
    }

    override fun findUserTagByName(
        user: User,
        name: String,
    ): Tag? =
        QTagModel()
            .name
            .equalTo(name)
            .author.id
            .equalTo(user.id)
            .findOne()
            ?.toDomain()

    override fun findAllTagsForUser(user: User): List<Tag> =
        QTagModel()
            .author.id
            .equalTo(user.id)
            .findList()
            .map { it.toDomain() }

    override fun deleteAllTagsForUser(user: User) {
        val tagIds = QTagModel().author.id.equalTo(user.id).findList().map { it.id }
        if (tagIds.isEmpty()) return
        // Remove pin_tag junction rows first (FK order), mirroring PinRepository's
        // permanentlyDeleteAllPinsForUser / permanentlyDeleteAllBoardsForUser defensive style,
        // so this method is self-sufficient regardless of call order.
        QPinTagModel().tag.id.isIn(tagIds).delete()
        QTagModel().id.isIn(tagIds).delete()
    }
}
