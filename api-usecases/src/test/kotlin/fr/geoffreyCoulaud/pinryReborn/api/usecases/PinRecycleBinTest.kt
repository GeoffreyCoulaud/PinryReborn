package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinDeletionPermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinDeletionPinAlreadySoftDeletedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinDeletionPinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PinDeletionPinNotSoftDeletedError
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class PinRecycleBinTest {
    private val pinRepository = mockk<PinRepositoryInterface>()
    private val imageRepository = mockk<ImageRepositoryInterface>(relaxed = true)
    private val imageStore = mockk<ImageStore>(relaxed = true)
    private val clearPinDownload = mockk<ClearPinDownload>(relaxed = true)
    private val renditionCache = mockk<RenditionCache>()
    private val useCase = PinRecycleBin(
        pinRepository = pinRepository,
        imageRepository = imageRepository,
        imageStore = imageStore,
        clearPinDownload = clearPinDownload,
        renditionCache = renditionCache,
    )

    init { every { renditionCache.evictImage(any()) } returns Unit }

    private fun createPin(author: User, softDeletedAt: Instant? = null) = Pin(
        id = randomUUID(),
        author = author,
        sourceContextUrl = "https://example.com",
        sourceMediaUrl = "https://example.com/img.jpg",
        description = "A pin",
        tags = emptyList(),
        boards = emptyList(),
        softDeletedAt = softDeletedAt,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun createImage(pinId: UUID) = Image(
        id = randomUUID(),
        pinId = pinId,
        mimeType = "image/png",
        width = 1,
        height = 1,
        animated = false,
        byteSize = 1,
        contentHash = "hash",
        storageKey = "originals/x/$pinId/i.png",
        createdAt = Instant.parse("2026-07-08T00:00:00Z"),
    )

    // --- Soft delete ---

    @Test
    fun `Given valid pin owned by user, Then soft delete succeeds and does not touch the image`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = createPin(author = user)
        every { pinRepository.findPinById(pin.id) } returns pin
        every { pinRepository.softDeletePin(pin) } returns pin.copy(softDeletedAt = Instant.now())

        // When
        useCase.softDelete(pinId = pin.id, user = user)

        // Then
        verify { pinRepository.softDeletePin(pin) }
        verify(exactly = 0) { imageRepository.deleteByPinId(any()) }
        verify(exactly = 0) { imageStore.delete(any()) }
    }

    @Test
    fun `Given already soft-deleted pin, Then soft delete throws PinDeletionPinAlreadySoftDeletedError`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = createPin(author = user, softDeletedAt = Instant.now())
        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinDeletionPinAlreadySoftDeletedError> {
            useCase.softDelete(pinId = pin.id, user = user)
        }
    }

    @Test
    fun `Given pin not owned by user, Then soft delete throws PinDeletionPermissionError`() {
        // Given
        val owner = User(id = randomUUID(), name = "Owner", createdAt = Instant.now())
        val otherUser = User(id = randomUUID(), name = "Other", createdAt = Instant.now())
        val pin = createPin(author = owner)
        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinDeletionPermissionError> {
            useCase.softDelete(pinId = pin.id, user = otherUser)
        }
    }

    @Test
    fun `Given pin does not exist, Then soft delete throws PinDeletionPinDoesNotExistError`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pinId = randomUUID()
        every { pinRepository.findPinById(pinId) } returns null

        // When, Then
        assertThrows<PinDeletionPinDoesNotExistError> {
            useCase.softDelete(pinId = pinId, user = user)
        }
    }

    // --- Restore ---

    @Test
    fun `Given soft-deleted pin owned by user, Then restore succeeds and does not touch the image`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = createPin(author = user, softDeletedAt = Instant.now())
        every { pinRepository.findPinById(pin.id) } returns pin
        every { pinRepository.restorePin(pin) } returns pin.copy(softDeletedAt = null)

        // When
        val result = useCase.restore(pinId = pin.id, user = user)

        // Then
        verify { pinRepository.restorePin(pin) }
        assert(result.softDeletedAt == null)
        verify(exactly = 0) { imageRepository.deleteByPinId(any()) }
        verify(exactly = 0) { imageStore.delete(any()) }
    }

    @Test
    fun `Given active pin, Then restore throws PinDeletionPinNotSoftDeletedError`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = createPin(author = user)
        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinDeletionPinNotSoftDeletedError> {
            useCase.restore(pinId = pin.id, user = user)
        }
    }

    // --- Permanent delete ---

    @Test
    fun `Given soft-deleted pin with an image, Then permanent delete removes the image row and file`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = createPin(author = user, softDeletedAt = Instant.now())
        val image = createImage(pin.id)
        every { pinRepository.findPinById(pin.id) } returns pin
        every { imageRepository.findByPinId(pin.id) } returns image
        justRun { pinRepository.permanentlyDeletePin(pin) }

        // When
        useCase.permanentlyDelete(pinId = pin.id, user = user)

        // Then
        verifyOrder {
            imageRepository.deleteByPinId(pin.id)
            pinRepository.permanentlyDeletePin(pin)
            imageStore.delete(image.storageKey)
        }
        verify { renditionCache.evictImage(image.id) }
    }

    @Test
    fun `Given soft-deleted pin without an image, Then permanent delete succeeds without touching the image store`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = createPin(author = user, softDeletedAt = Instant.now())
        every { pinRepository.findPinById(pin.id) } returns pin
        every { imageRepository.findByPinId(pin.id) } returns null
        justRun { pinRepository.permanentlyDeletePin(pin) }

        // When
        useCase.permanentlyDelete(pinId = pin.id, user = user)

        // Then
        verify { imageRepository.deleteByPinId(pin.id) }
        verify { pinRepository.permanentlyDeletePin(pin) }
        verify(exactly = 0) { imageStore.delete(any()) }
        verify(exactly = 0) { renditionCache.evictImage(any()) }
    }

    @Test
    fun `Given active pin, Then permanent delete throws PinDeletionPinNotSoftDeletedError`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = createPin(author = user)
        every { pinRepository.findPinById(pin.id) } returns pin

        // When, Then
        assertThrows<PinDeletionPinNotSoftDeletedError> {
            useCase.permanentlyDelete(pinId = pin.id, user = user)
        }
    }

    @Test
    fun `Given a permanently deleted pin, Then its download is cleared`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pin = createPin(author = user, softDeletedAt = Instant.now())
        val pinId = pin.id
        every { pinRepository.findPinById(pinId) } returns pin
        every { imageRepository.findByPinId(pinId) } returns null
        justRun { pinRepository.permanentlyDeletePin(pin) }

        // When
        useCase.permanentlyDelete(pinId = pinId, user = user)

        // Then
        verify { clearPinDownload.clear(pinId) }
    }

    // --- Empty recycle bin ---

    @Test
    fun `Given user with no soft-deleted pins, Then empty recycle bin does not touch any image`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        every { pinRepository.findAllSoftDeletedPinsForUser(user) } returns emptyList()
        justRun { pinRepository.permanentlyDeleteAllSoftDeletedPinsForUser(user) }

        // When
        useCase.emptyRecycleBin(user = user)

        // Then
        verify { pinRepository.permanentlyDeleteAllSoftDeletedPinsForUser(user) }
        verify(exactly = 0) { imageRepository.deleteByPinId(any()) }
        verify(exactly = 0) { imageStore.delete(any()) }
        verify(exactly = 0) { renditionCache.evictImage(any()) }
    }

    @Test
    fun `Given soft-deleted pins some with images, Then empty recycle bin deletes rows and files for those`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val pinWithImage = createPin(author = user, softDeletedAt = Instant.now())
        val pinWithoutImage = createPin(author = user, softDeletedAt = Instant.now())
        val image = createImage(pinWithImage.id)
        every { pinRepository.findAllSoftDeletedPinsForUser(user) } returns listOf(pinWithImage, pinWithoutImage)
        every { imageRepository.findByPinId(pinWithImage.id) } returns image
        every { imageRepository.findByPinId(pinWithoutImage.id) } returns null
        justRun { pinRepository.permanentlyDeleteAllSoftDeletedPinsForUser(user) }

        // When
        useCase.emptyRecycleBin(user = user)

        // Then
        // Lock the cascade ordering: the enumerate + image-row deletes MUST happen before the
        // bulk pin delete (else the collect-before-bulk-delete step would silently leak every
        // file), and the file delete MUST happen after the bulk pin delete.
        verifyOrder {
            pinRepository.findAllSoftDeletedPinsForUser(user)
            imageRepository.deleteByPinId(pinWithImage.id)
            imageRepository.deleteByPinId(pinWithoutImage.id)
            pinRepository.permanentlyDeleteAllSoftDeletedPinsForUser(user)
            imageStore.delete(image.storageKey)
        }
        verify(exactly = 1) { imageStore.delete(any()) }
        verify(exactly = 1) { renditionCache.evictImage(any()) }
        verify { renditionCache.evictImage(image.id) }
    }

    @Test
    fun `Given soft-deleted pins, Then empty recycle bin clears each pin's download`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = Instant.now())
        val firstPin = createPin(author = user, softDeletedAt = Instant.now())
        val secondPin = createPin(author = user, softDeletedAt = Instant.now())
        every { pinRepository.findAllSoftDeletedPinsForUser(user) } returns listOf(firstPin, secondPin)
        every { imageRepository.findByPinId(any()) } returns null
        justRun { pinRepository.permanentlyDeleteAllSoftDeletedPinsForUser(user) }

        // When
        useCase.emptyRecycleBin(user = user)

        // Then
        verify { clearPinDownload.clear(firstPin.id) }
        verify { clearPinDownload.clear(secondPin.id) }
    }
}
