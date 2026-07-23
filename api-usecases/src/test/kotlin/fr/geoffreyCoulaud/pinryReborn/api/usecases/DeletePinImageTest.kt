package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class DeletePinImageTest : BaseTest() {
    private val pins = mockk<PinRepositoryInterface>()
    private val images = mockk<ImageRepositoryInterface>(relaxed = true)
    private val store = mockk<ImageStore>(relaxed = true)
    private val clearPinDownload = mockk<ClearPinDownload>(relaxed = true)
    private val renditionCache = mockk<RenditionCache>()
    private val useCase = DeletePinImage(pins, images, store, clearPinDownload, renditionCache)

    init { every { renditionCache.evictImage(any()) } returns Unit }

    private val owner = User(randomUUID(), createRandomString(), createdAt = Instant.now())
    private fun pin(author: User = owner) = Pin(randomUUID(), author, "https://c", null, "d", emptyList(), emptyList(),
        createdAt = Instant.now(), updatedAt = Instant.now())
    private fun imageFor(pinId: UUID, hash: String = "h") = Image(
        id = randomUUID(), pinId = pinId, mimeType = "image/png", width = 1, height = 1, animated = false,
        byteSize = 1, contentHash = hash, storageKey = "originals/x/$pinId/i.png",
        createdAt = Instant.parse("2026-07-08T00:00:00Z"),
    )

    @Test fun `Given the owner and an image, Then delete removes the row and the file`() {
        val p = pin(); val img = imageFor(p.id)
        every { pins.findPinById(p.id) } returns p
        every { images.findByPinId(p.id) } returns img

        useCase.delete(p.id, owner)

        verifyOrder {
            images.deleteByPinId(p.id)
            store.delete(img.storageKey)
            clearPinDownload.clear(p.id)
        }
        verify { renditionCache.evictImage(img.id) }
    }

    @Test fun `Given a missing pin, Then delete throws ImagePinDoesNotExistError`() {
        every { pins.findPinById(any()) } returns null
        assertThrows(ImagePinDoesNotExistError::class.java) { useCase.delete(randomUUID(), owner) }
    }

    @Test fun `Given a non-owner, Then delete throws ImagePermissionError`() {
        val p = pin(author = User(randomUUID(), createRandomString(), createdAt = Instant.now()))
        every { pins.findPinById(p.id) } returns p
        assertThrows(ImagePermissionError::class.java) { useCase.delete(p.id, owner) }
    }

    @Test fun `Given a pin without an image, Then delete throws ImageDoesNotExistError`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { images.findByPinId(p.id) } returns null
        every { clearPinDownload.clear(p.id) } returns false
        assertThrows(ImageDoesNotExistError::class.java) { useCase.delete(p.id, owner) }
    }

    @Test fun `Given no image but a pending download, Then delete cancels it and does not throw`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { images.findByPinId(p.id) } returns null
        every { clearPinDownload.clear(p.id) } returns true

        useCase.delete(p.id, owner)

        verify { clearPinDownload.clear(p.id) }
        verify(exactly = 0) { images.deleteByPinId(any()) }
        verify(exactly = 0) { store.delete(any()) }
        verify(exactly = 0) { renditionCache.evictImage(any()) }
    }
}
