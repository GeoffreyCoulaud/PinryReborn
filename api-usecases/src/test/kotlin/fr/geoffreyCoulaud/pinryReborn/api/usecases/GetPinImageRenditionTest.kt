package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTransformer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionSpec
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageDoesNotExistError
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.UUID.randomUUID

class GetPinImageRenditionTest {
    private val getPinImage = mockk<GetPinImage>()
    private val imageStore = mockk<ImageStore>()
    private val imageTransformer = mockk<ImageTransformer>()
    private val renditionCache = mockk<RenditionCache>()
    private val useCase = GetPinImageRendition(getPinImage, imageStore, imageTransformer, renditionCache)

    private val requester = mockk<User>()

    private fun image(pinId: UUID, width: Int, height: Int, animated: Boolean) = Image(
        id = randomUUID(), pinId = pinId, mimeType = "image/png", width = width, height = height,
        animated = animated, byteSize = 1, contentHash = "h",
        storageKey = "originals/u/$pinId/i.png", createdAt = java.time.Instant.EPOCH,
    )

    private fun stubMiss(img: Image, key: String) {
        every { renditionCache.openStream(img.id, key) } returns null
        every { imageStore.openStream(img.storageKey) } returns ByteArrayInputStream(byteArrayOf(1))
        every { imageTransformer.render(any(), any()) } returns StagedFile("/tmp/out.webp", 3, "hh")
        every { renditionCache.store(img.id, key, any()) } returns Unit
    }

    @Test
    fun `Given no size, Then it serves the original`() {
        val pinId = randomUUID()
        val img = image(pinId, 100, 80, animated = false)
        every { getPinImage.get(pinId, requester) } returns img

        val served = useCase.get(pinId, requester, requestedPx = null, animated = true)

        assertEquals(ServedImage.Original(img), served)
    }

    @Test
    fun `Given a static image at least as small as the size, Then it serves the original`() {
        val pinId = randomUUID()
        val img = image(pinId, 10, 20, animated = false)
        every { getPinImage.get(pinId, requester) } returns img

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        assertEquals(ServedImage.Original(img), served)
    }

    @Test
    fun `Given a static image larger than the size and a cache miss, Then it renders, stores, and serves it`() {
        val pinId = randomUUID()
        val img = image(pinId, 100, 80, animated = false)
        every { getPinImage.get(pinId, requester) } returns img
        // A static source renders statically whatever was requested: the flag is intersected away.
        stubMiss(img, "v1-40-s.webp")

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        val rendition = assertInstanceOf(ServedImage.Rendition::class.java, served)
        assertEquals("v1-40-s.webp", rendition.key)
        assertEquals(40, rendition.effectivePx)
        assertFalse(rendition.animated)
        verify { imageTransformer.render(any(), RenditionSpec(40, false)) }
        verify { renditionCache.store(img.id, "v1-40-s.webp", any()) }
    }

    @Test
    fun `Given a static image, Then requesting it static hits the same key as requesting it animated`() {
        // Given: the same static source, this time with animated = false explicitly
        val pinId = randomUUID()
        val img = image(pinId, 100, 80, animated = false)
        every { getPinImage.get(pinId, requester) } returns img
        stubMiss(img, "v1-40-s.webp")

        // When
        val served = useCase.get(pinId, requester, requestedPx = 40, animated = false)

        // Then: identical bytes dedup onto one cache entry and one ETag, whatever the client asked
        val rendition = assertInstanceOf(ServedImage.Rendition::class.java, served)
        assertEquals("v1-40-s.webp", rendition.key)
        assertFalse(rendition.animated)
        verify { imageTransformer.render(any(), RenditionSpec(40, false)) }
    }

    @Test
    fun `Given an animated image larger than the size, Then it renders an animated rendition`() {
        // Given
        val pinId = randomUUID()
        val img = image(pinId, 100, 80, animated = true)
        every { getPinImage.get(pinId, requester) } returns img
        stubMiss(img, "v1-40-a.webp")

        // When
        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        // Then: an animated source is the only case that reaches the transformer with animated=true
        val rendition = assertInstanceOf(ServedImage.Rendition::class.java, served)
        assertEquals("v1-40-a.webp", rendition.key)
        assertTrue(rendition.animated)
        verify { imageTransformer.render(any(), RenditionSpec(40, true)) }
    }

    @Test
    fun `Given a cache hit, Then it serves the rendition without rendering`() {
        val pinId = randomUUID()
        val img = image(pinId, 100, 80, animated = false)
        every { getPinImage.get(pinId, requester) } returns img
        every { renditionCache.openStream(img.id, "v1-40-s.webp") } returns ByteArrayInputStream(byteArrayOf(9))

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        assertInstanceOf(ServedImage.Rendition::class.java, served)
        verify(exactly = 0) { imageTransformer.render(any(), any()) }
        verify(exactly = 0) { renditionCache.store(any(), any(), any()) }
    }

    @Test
    fun `Given an animated image and animated = false at a large size, Then it flattens to a rendition`() {
        val pinId = randomUUID()
        val img = image(pinId, 10, 20, animated = true)
        every { getPinImage.get(pinId, requester) } returns img
        stubMiss(img, "v1-10-s.webp")

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = false)

        val rendition = assertInstanceOf(ServedImage.Rendition::class.java, served)
        assertEquals("v1-10-s.webp", rendition.key)
        assertEquals(10, rendition.effectivePx)
        verify { imageTransformer.render(any(), RenditionSpec(10, false)) }
    }

    @Test
    fun `Given an animated image and animated = true at a large size, Then it serves the original`() {
        val pinId = randomUUID()
        val img = image(pinId, 10, 20, animated = true)
        every { getPinImage.get(pinId, requester) } returns img

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        assertEquals(ServedImage.Original(img), served)
    }

    @Test
    fun `Given the pin has no image, Then the guard from GetPinImage propagates`() {
        val pinId = randomUUID()
        every { getPinImage.get(pinId, requester) } throws ImageDoesNotExistError()

        assertThrows(ImageDoesNotExistError::class.java) {
            useCase.get(pinId, requester, requestedPx = 40, animated = true)
        }
    }
}
