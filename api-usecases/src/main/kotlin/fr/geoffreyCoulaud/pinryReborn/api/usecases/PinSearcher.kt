package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SearchResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SearchEmptyQueryError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.search.TextSimilarity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PinSearcher(
    private val pinRepository: PinRepositoryInterface,
) {
    /**
     * Search for pins matching the query using combined trigram similarity.
     * @param user The user whose pins to search
     * @param query The search query
     * @param limit Maximum number of results to return
     * @return List of search results sorted by score descending
     */
    fun searchPins(
        user: User,
        query: String,
        limit: Int,
    ): List<SearchResult<Pin>> {
        if (query.isBlank()) {
            throw SearchEmptyQueryError()
        }

        val pins = pinRepository.findAllPinsForUser(user)

        return pins
            .map { pin ->
                val score = TextSimilarity.combinedSimilarity(query, pin.description)
                SearchResult(item = pin, score = score)
            }
            .filter { it.score >= DEFAULT_MIN_SCORE }
            .sortedByDescending { it.score }
            .take(limit)
    }

    companion object {
        const val DEFAULT_MIN_SCORE = 0.3
    }
}
