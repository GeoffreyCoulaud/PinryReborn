package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanImageRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PinRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class EbeanImageRepositoryTest : RepositoryTest() {
    private val repository = EbeanImageRepository(persistor, transactionRunner)
    private val userRepository = UserRepository(persistor)
    private val pinRepository = PinRepository(persistor)

    private fun savedPin(): Pin {
        val user = userRepository.saveUser(User(randomUUID(), createRandomString(), createdAt = storableNow()))
        return pinRepository.savePin(
            Pin(
                randomUUID(), user, "https://ctx", null, "desc", emptyList(), emptyList(),
                createdAt = storableNow(), updatedAt = storableNow(),
            ),
        )
    }

    private fun imageFor(pinId: UUID, hash: String = "h", animated: Boolean = false) = Image(
        id = randomUUID(), pinId = pinId, mimeType = "image/png", width = 1, height = 1, animated = animated,
        byteSize = 1, contentHash = hash, storageKey = "originals/x/$pinId/i.png",
        createdAt = Instant.parse("2026-07-08T00:00:00Z"),
    )

    @Test
    fun `Given a new image, Then save persists it and findByPinId returns it`() {
        val pin = savedPin()
        val saved = repository.save(imageFor(pin.id))
        assertEquals(saved, repository.findByPinId(pin.id))
    }

    @Test
    fun `Given an animated image, Then save persists it and findByPinId reads back animated = true`() {
        val pin = savedPin()
        val saved = repository.save(imageFor(pin.id, animated = true))
        assertTrue(saved.animated)
        assertEquals(true, repository.findByPinId(pin.id)?.animated)
    }

    @Test
    fun `Given a pin already imaged, Then save replaces the row (unique pin_id)`() {
        val pin = savedPin()
        repository.save(imageFor(pin.id, hash = "old"))
        val replacement = repository.save(imageFor(pin.id, hash = "new"))
        assertEquals("new", repository.findByPinId(pin.id)?.contentHash)
        assertEquals(replacement, repository.findByPinId(pin.id))
    }

    @Test
    fun `Given no image for a pin, Then findByPinId returns null`() {
        assertNull(repository.findByPinId(randomUUID()))
    }

    @Test
    fun `Given an image, Then deleteByPinId removes it`() {
        val pin = savedPin()
        repository.save(imageFor(pin.id))
        repository.deleteByPinId(pin.id)
        assertNull(repository.findByPinId(pin.id))
    }

    @Test
    fun `Given no image, Then deleteByPinId is a no-op`() {
        repository.deleteByPinId(randomUUID()) // must not throw
        assertNull(repository.findByPinId(randomUUID()))
    }

    // --- findMissingImageIds (orphan sweep) ---

    @Test
    fun `Given a mix of present and absent candidate ids, Then findMissingImageIds returns only the absent`() {
        // Given
        val pin = savedPin()
        val saved = repository.save(imageFor(pin.id))
        val missingId = randomUUID()

        // When
        val missing = repository.findMissingImageIds(listOf(saved.id, missingId))

        // Then
        assertEquals(setOf(missingId), missing)
    }

    @Test
    fun `Given an empty candidate set, Then findMissingImageIds returns empty`() {
        // Given
        val pin = savedPin()
        repository.save(imageFor(pin.id))

        // When
        val missing = repository.findMissingImageIds(emptyList())

        // Then
        assertTrue(missing.isEmpty())
    }
}
