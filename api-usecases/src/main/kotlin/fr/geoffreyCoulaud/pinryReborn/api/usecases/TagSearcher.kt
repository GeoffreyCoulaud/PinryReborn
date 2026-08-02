package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SearchResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SearchEmptyQueryError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.search.TextSimilarity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TagSearcher(
    private val tagRepository: TagRepositoryInterface,
) {
    /**
     * Search for tags matching the query using Jaro-Winkler similarity.
     * @param user The user whose tags to search
     * @param query The search query
     * @param limit Maximum number of results to return
     * @return List of search results sorted by score descending
     */
    fun searchTags(
        user: User,
        query: String,
        limit: Int,
    ): List<SearchResult<Tag>> {
        if (query.isBlank()) {
            throw SearchEmptyQueryError()
        }

        val tags = tagRepository.findAllTagsForUser(user)

        return tags
            .map { tag ->
                val score = TextSimilarity.jaroWinklerSimilarity(query, tag.name)
                SearchResult(item = tag, score = score)
            }
            .filter { it.score >= DEFAULT_MIN_SCORE }
            .sortedByDescending { it.score }
            .take(limit)
    }

    companion object {
        const val DEFAULT_MIN_SCORE = 0.3
    }
}
