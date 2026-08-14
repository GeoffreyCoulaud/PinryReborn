package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TagModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TagModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TagModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QPinTagModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QTagModel
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TagRepository(
    persistor: Persistor,
) : TagRepositoryInterface {
    private val sqlRepository = ModelRepository<TagModel>(persistor = persistor)

    override fun saveTag(tag: Tag): Tag = sqlRepository.saveAndReturn(tag.toModel()).toDomain()

    // The comparison goes through the column's own collation rather than Ebean's `ieq`, which
    // renders lower(column) = ? with the bind lowercased in Java: that fold is Unicode aware while
    // `collate nocase` is ASCII only, so the read and ix_tags_author_name_nocase would disagree in
    // one direction and find-or-create would depend on which case was stored first.
    override fun findUserTagByName(
        user: User,
        name: String,
    ): Tag? =
        QTagModel()
            .author.id
            .equalTo(user.id)
            .raw("name collate nocase = ?", name)
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
