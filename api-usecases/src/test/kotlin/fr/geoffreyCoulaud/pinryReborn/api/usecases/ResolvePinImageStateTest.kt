package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class ResolvePinImageStateTest {
    private val pins: PinRepositoryInterface = mockk()
    private val images: ImageRepositoryInterface = mockk()
    private val downloads: ImageDownloadRepositoryInterface = mockk()
    private val owner = User(randomUUID(), "o")
    private val pinId = randomUUID()
    private val subject = ResolvePinImageState(pins, images, downloads)

    @Test fun `Given a missing pin, Then it throws ImagePinDoesNotExistError`() {
        every { pins.findPinById(pinId) } returns null
        assertThrows(ImagePinDoesNotExistError::class.java) { subject.resolve(pinId, owner) }
    }

    @Test fun `Given a non-owner, Then it throws ImagePermissionError`() {
        every { pins.findPinById(pinId) } returns Pin(pinId, User(randomUUID(), "x"), "c", null, "d", emptyList())
        assertThrows(ImagePermissionError::class.java) { subject.resolve(pinId, owner) }
    }

    @Test fun `Given an owner with no image and no download, Then NONE`() {
        every { pins.findPinById(pinId) } returns Pin(pinId, owner, "c", null, "d", emptyList())
        every { images.findByPinId(pinId) } returns null
        every { downloads.findByPinId(pinId) } returns null
        assertEquals(PinImageStatus.NONE, subject.resolve(pinId, owner).status)
    }
}
