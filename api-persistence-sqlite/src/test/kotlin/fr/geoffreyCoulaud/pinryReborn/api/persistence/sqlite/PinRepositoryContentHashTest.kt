package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanImageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.UUID.randomUUID

/**
 * The import's "do I already hold these bytes?" lookup. A content-addressed key is an oracle for
 * what other accounts hold unless the author is part of the question, which is why the cross-user
 * case is here and why the query is rooted on pins: `ImageModel` reaches neither the author nor the
 * recycled state.
 */
class PinRepositoryContentHashTest : PinRepositoryFixtures() {
    private val imageRepository = EbeanImageRepository(persistor, transactionRunner)
    private val contentHash = "a".repeat(64)
    private val otherContentHash = "b".repeat(64)

    private fun giveImage(
        pin: Pin,
        hash: String = contentHash,
    ): Image =
        imageRepository.save(
            Image(
                id = randomUUID(),
                pinId = pin.id,
                mimeType = "image/png",
                width = 1,
                height = 1,
                animated = false,
                byteSize = 1,
                contentHash = hash,
                storageKey = "originals/${pin.id}.png",
                createdAt = storableNow(),
            ),
        )

    @Test
    fun `Given two pins of one author sharing a digest, Then both ids come back`() {
        // Given
        val author = createAndSaveUser()
        val first = createAndSavePin(author)
        val second = createAndSavePin(author)
        giveImage(first)
        giveImage(second)

        // When
        val found = repository.findPinIdsByContentHashForUser(author, contentHash)

        // Then: more than one hit is what the import reports as ambiguous, so the list carries both
        assertEquals(setOf(first.id, second.id), found.toSet())
    }

    @Test
    fun `Given a recycled pin carrying the digest, Then it is still found`() {
        // Given: recycling a pin must not make the import create a second copy of it
        val author = createAndSaveUser()
        val pin = createAndSavePin(author)
        giveImage(pin)
        repository.softDeletePin(pin, storableNow())

        // When
        val found = repository.findPinIdsByContentHashForUser(author, contentHash)

        // Then
        assertEquals(listOf(pin.id), found)
    }

    @Test
    fun `Given the same bytes under another account, Then the lookup returns empty`() {
        // Given
        val author = createAndSaveUser()
        val stranger = createAndSaveUser()
        giveImage(createAndSavePin(stranger))

        // When
        val found = repository.findPinIdsByContentHashForUser(author, contentHash)

        // Then: otherwise the lookup answers what other people hold
        assertTrue(found.isEmpty(), "$found")
    }

    @Test
    fun `Given only another digest, Then the lookup returns empty`() {
        // Given
        val author = createAndSaveUser()
        giveImage(createAndSavePin(author), hash = otherContentHash)

        // When
        val found = repository.findPinIdsByContentHashForUser(author, contentHash)

        // Then
        assertTrue(found.isEmpty(), "$found")
    }

    @Test
    fun `Given the lookup as Ebean builds it, Then its plan searches the content hash index`() {
        // Given: the query the repository runs, executed so Ebean records the SQL it generated
        val author = createAndSaveUser()
        val query = repository.pinIdsByContentHashQuery(author, contentHash)
        query.findSingleAttributeList<UUID>()

        // When
        val plan =
            database
                .sqlQuery("explain query plan ${query.query().generatedSql}")
                .setParameter(1, author.id.toString())
                .setParameter(2, contentHash)
                .findList()
                .joinToString("\n") { "${it["detail"]}" }

        // Then: read on a table with no statistics, so this pins the plan the planner picks unaided
        assertTrue(plan.contains("ix_images_content_hash"), plan)
        assertFalse(plan.contains("SCAN"), plan)
    }
}
