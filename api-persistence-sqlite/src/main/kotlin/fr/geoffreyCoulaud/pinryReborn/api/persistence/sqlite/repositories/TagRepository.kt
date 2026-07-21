package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TagModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.TagModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.TagModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QTagModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TagRepository(
    database: Database,
) : TagRepositoryInterface {
    private val sqlRepository = ModelRepository(entityClass = TagModel::class, database = database)

    override fun saveTag(tag: Tag): Tag = sqlRepository.saveAndReturn(tag.toModel()).toDomain()

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
        QTagModel().author.id.equalTo(user.id).delete()
    }
}
