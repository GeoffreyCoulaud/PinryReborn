package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UndecodableImageException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageTooLargeError
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID.randomUUID

class SetPinImageTest : BaseTest() {
    private val pins = mockk<PinRepositoryInterface>()
    private val images = mockk<ImageRepositoryInterface>(relaxed = true)
    private val store = mockk<ImageStore>(relaxed = true)
    private val probe = mockk<ImageProbe>()
    private val clock = mockk<Clock>()
    private val clearPinDownload = mockk<ClearPinDownload>(relaxed = true)
    private val renditionCache = mockk<RenditionCache>()
    private val useCase = SetPinImage(pins, images, store, probe, clock, clearPinDownload, renditionCache)

    private val owner = User(randomUUID(), createRandomString(), createdAt = Instant.now())
    private fun pin(author: User = owner) = Pin(randomUUID(), author, "https://c", null, "d", emptyList(), emptyList(),
        createdAt = Instant.now(), updatedAt = Instant.now())
    private fun upload() = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    private val staged = StagedFile("/tmp/s", 3, "hash")

    init { every { renditionCache.evictImage(any()) } returns Unit }

    @Test fun `Given a valid upload by the owner, Then it stores and persists a new image`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.PNG, 4, 5, animated = false)
        every { images.findByPinId(p.id) } returns null
        every { clock.now() } returns Instant.parse("2026-07-08T00:00:00Z")
        every { images.save(any()) } answers { firstArg() }

        val result = useCase.set(p.id, owner, upload(), maxBytes = 30, maxPixels = 50)

        assertEquals(p.id, result.image.pinId)
        assertEquals("image/png", result.image.mimeType)
        assertTrue(result.image.storageKey.startsWith("originals/${owner.id}/${p.id}/"))
        assertFalse(result.replaced)
        verify { store.promote(staged, result.image.storageKey) }
        verify { images.save(result.image) }
        verify { clearPinDownload.clear(p.id) }
        // A first-time upload has no superseded image, so nothing is evicted.
        verify(exactly = 0) { renditionCache.evictImage(any()) }
    }

    @Test fun `Given a replacement, Then the old file is deleted after commit`() {
        val p = pin()
        val old = Image(randomUUID(), p.id, "image/png", 1, 1, false, 1, "old", "originals/o/old.png", Instant.EPOCH)
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.WEBP, 2, 2, animated = false)
        every { images.findByPinId(p.id) } returns old
        every { clock.now() } returns Instant.EPOCH
        every { images.save(any()) } answers { firstArg() }

        val result = useCase.set(p.id, owner, upload(), 30, 50)

        assertTrue(result.replaced)
        verify { store.delete("originals/o/old.png") }
    }

    @Test fun `Given a replaced image, Then the old image's rendition cache is evicted`() {
        val p = pin()
        val old = Image(randomUUID(), p.id, "image/png", 1, 1, false, 1, "old", "originals/o/old.png", Instant.EPOCH)
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.WEBP, 2, 2, animated = false)
        every { images.findByPinId(p.id) } returns old
        every { clock.now() } returns Instant.EPOCH
        every { images.save(any()) } answers { firstArg() }

        useCase.set(p.id, owner, upload(), 30, 50)

        verify { renditionCache.evictImage(old.id) }
    }

    @Test fun `Given the rendition cache eviction fails during replace, Then the upload still succeeds`() {
        val p = pin()
        val old = Image(randomUUID(), p.id, "image/png", 1, 1, false, 1, "old", "originals/o/old.png", Instant.EPOCH)
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.WEBP, 2, 2, animated = false)
        every { images.findByPinId(p.id) } returns old
        every { clock.now() } returns Instant.EPOCH
        every { images.save(any()) } answers { firstArg() }
        every { renditionCache.evictImage(any()) } throws RuntimeException("io")

        val result = useCase.set(p.id, owner, upload(), 30, 50)

        assertTrue(result.replaced)
        verify { images.save(result.image) }
    }

    @Test fun `Given a missing pin, Then it throws ImagePinDoesNotExistError`() {
        every { pins.findPinById(any()) } returns null
        assertThrows(ImagePinDoesNotExistError::class.java) { useCase.set(randomUUID(), owner, upload(), 30, 50) }
    }

    @Test fun `Given a non-owner, Then it throws ImagePermissionError`() {
        val p = pin(author = User(randomUUID(), createRandomString(), createdAt = Instant.now()))
        every { pins.findPinById(p.id) } returns p
        assertThrows(ImagePermissionError::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }
    }

    @Test fun `Given an oversize upload, Then it throws ImageTooLargeError`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } throws ImageTooLargeException("too big")
        assertThrows(ImageTooLargeError::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }
    }

    @Test fun `Given an undecodable upload, Then it discards the temp and throws ImageInvalidError`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } throws UndecodableImageException("nope")
        assertThrows(ImageInvalidError::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }
        verify { store.discard(staged) }
    }

    @Test fun `Given a promote failure, Then it discards the temp and rethrows`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.PNG, 4, 5, animated = false)
        every { images.findByPinId(p.id) } returns null
        every { clock.now() } returns Instant.EPOCH
        every { store.promote(any(), any()) } throws RuntimeException("disk full")

        assertThrows(RuntimeException::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }
        verify { store.discard(staged) }
        verify(exactly = 0) { images.save(any()) }
    }

    @Test fun `Given an IO failure during promote, Then it discards the temp and rethrows`() {
        // FilesystemImageStore.promote throws java.io.IOException (Files.createDirectories /
        // Files.move), not a RuntimeException; the cleanup catch must cover it too.
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.PNG, 4, 5, animated = false)
        every { images.findByPinId(p.id) } returns null
        every { clock.now() } returns Instant.EPOCH
        every { store.promote(any(), any()) } throws IOException("disk full")

        assertThrows(IOException::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }
        verify { store.discard(staged) }
        verify(exactly = 0) { images.save(any()) }
    }

    @Test fun `Given save fails after a successful promote, Then it discards the temp and deletes the promoted file`() {
        val p = pin()
        val storageKeySlot = slot<String>()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.PNG, 4, 5, animated = false)
        every { images.findByPinId(p.id) } returns null
        every { clock.now() } returns Instant.EPOCH
        every { store.promote(staged, capture(storageKeySlot)) } just runs
        every { images.save(any()) } throws RuntimeException("db down")

        assertThrows(RuntimeException::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }

        verify { store.discard(staged) }
        verify { store.delete(storageKeySlot.captured) }
    }

    @Test fun `Given the old file delete fails during replace, Then the request still succeeds`() {
        val p = pin()
        val old = Image(randomUUID(), p.id, "image/png", 1, 1, false, 1, "old", "originals/o/old.png", Instant.EPOCH)
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.WEBP, 2, 2, animated = false)
        every { images.findByPinId(p.id) } returns old
        every { clock.now() } returns Instant.EPOCH
        every { images.save(any()) } answers { firstArg() }
        every { store.delete("originals/o/old.png") } throws RuntimeException("locked")

        val result = useCase.set(p.id, owner, upload(), 30, 50)

        assertTrue(result.replaced)
    }
}
