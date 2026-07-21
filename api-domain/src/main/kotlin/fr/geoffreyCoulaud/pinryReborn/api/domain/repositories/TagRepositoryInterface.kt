package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User

interface TagRepositoryInterface {
    /**
     * Create or update a tag from the given domain data.
     */
    fun saveTag(tag: Tag): Tag

    /**
     * Find a tag with the given name for the specific user
     */
    fun findUserTagByName(
        user: User,
        name: String,
    ): Tag?

    /**
     * Find all tags for a user
     */
    fun findAllTagsForUser(user: User): List<Tag>

    /**
     * Delete all tags for a user
     */
    fun deleteAllTagsForUser(user: User)
}
