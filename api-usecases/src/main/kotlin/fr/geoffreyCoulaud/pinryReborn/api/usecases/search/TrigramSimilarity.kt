package fr.geoffreyCoulaud.pinryReborn.api.usecases.search

import org.apache.commons.text.similarity.JaroWinklerSimilarity

object TrigramSimilarity {
    private val jaroWinkler = JaroWinklerSimilarity()

    /**
     * Generate trigrams from a string with padding for edge characters.
     * @param text The input string
     * @return Set of trigrams
     */
    fun generateTrigrams(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()

        val normalized = text.lowercase()
        val padded = "  $normalized  "
        return (0 until padded.length - 2)
            .map { padded.substring(it, it + 3) }
            .toSet()
    }

    /**
     * Calculate trigram similarity using Jaccard coefficient.
     * @param query The search query
     * @param target The target string to compare against
     * @return Similarity score between 0.0 and 1.0
     */
    fun trigramSimilarity(query: String, target: String): Double {
        if (query.isEmpty() || target.isEmpty()) return 0.0

        val queryTrigrams = generateTrigrams(query)
        val targetTrigrams = generateTrigrams(target)

        val intersection = queryTrigrams.intersect(targetTrigrams).size
        val union = queryTrigrams.union(targetTrigrams).size

        return intersection.toDouble() / union
    }

    /**
     * Calculate Jaro-Winkler similarity (good for short strings).
     * @param query The search query
     * @param target The target string to compare against
     * @return Similarity score between 0.0 and 1.0
     */
    fun jaroWinklerSimilarity(query: String, target: String): Double {
        if (query.isEmpty() || target.isEmpty()) return 0.0
        return jaroWinkler.apply(query.lowercase(), target.lowercase())
    }

    /**
     * Calculate combined similarity using multiple algorithms.
     * Best for longer text like descriptions - uses trigram on the whole text,
     * but also checks word-level Jaro-Winkler for partial matches.
     * @param query The search query
     * @param target The target string to compare against
     * @return Similarity score between 0.0 and 1.0
     */
    fun combinedSimilarity(query: String, target: String): Double {
        if (query.isEmpty() || target.isEmpty()) return 0.0

        val normalizedQuery = query.lowercase()
        val normalizedTarget = target.lowercase()

        // Direct trigram similarity on the whole strings
        val trigramScore = trigramSimilarity(normalizedQuery, normalizedTarget)

        // Word-level matching: find the best Jaro-Winkler match against any word in target
        val targetWords = normalizedTarget.split(Regex("\\s+")).filter { it.isNotBlank() }
        val bestWordScore = targetWords.maxOfOrNull { word -> jaroWinklerSimilarity(normalizedQuery, word) } ?: 0.0

        // Weighted combination: favor word-level matching for partial matches
        return maxOf(trigramScore, bestWordScore * 0.9)
    }
}
