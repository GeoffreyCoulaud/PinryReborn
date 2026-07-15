package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class GetPinImageTest : BaseTest() {
    private val pins = mockk<PinRepositoryInterface>()
    private val images = mockk<ImageRepositoryInterface>()
    private val useCase = GetPinImage(pins, images)

    private val owner = User(randomUUID(), createRandomString())
    private fun pin(author: User = owner) = Pin(randomUUID(), author, "https://c", null, "d", emptyList())
    private fun imageFor(pinId: UUID, hash: String = "h") = Image(
        id = randomUUID(), pinId = pinId, mimeType = "image/png", width = 1, height = 1, animated = false,
        byteSize = 1, contentHash = hash, storageKey = "originals/x/$pinId/i.png",
        createdAt = Instant.parse("2026-07-08T00:00:00Z"),
    )

    @Test fun `Given the owner and an image, Then get returns it`() {
        val p = pin(); val img = imageFor(p.id)
        every { pins.findPinById(p.id) } returns p
        every { images.findByPinId(p.id) } returns img
        assertEquals(img, useCase.get(p.id, owner))
    }

    @Test fun `Given a missing pin, Then get throws ImagePinDoesNotExistError`() {
        every { pins.findPinById(any()) } returns null
        assertThrows(ImagePinDoesNotExistError::class.java) { useCase.get(randomUUID(), owner) }
    }

    @Test fun `Given a non-owner, Then get throws ImagePermissionError`() {
        val p = pin(author = User(randomUUID(), createRandomString()))
        every { pins.findPinById(p.id) } returns p
        assertThrows(ImagePermissionError::class.java) { useCase.get(p.id, owner) }
    }

    @Test fun `Given a pin without an image, Then get throws ImageDoesNotExistError`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { images.findByPinId(p.id) } returns null
        assertThrows(ImageDoesNotExistError::class.java) { useCase.get(p.id, owner) }
    }
}
