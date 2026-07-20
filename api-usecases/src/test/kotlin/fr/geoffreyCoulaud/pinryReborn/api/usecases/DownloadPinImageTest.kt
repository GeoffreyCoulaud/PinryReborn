package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchAccessDeniedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchFailedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchNotFoundException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchUnreachableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooManyPixelsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.TooManyRedirectsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UndecodableImageException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UnsupportedImageFormatException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UrlNotAllowedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID.randomUUID

class DownloadPinImageTest {
    private val pins: PinRepositoryInterface = mockk()
    private val images: ImageRepositoryInterface = mockk(relaxed = true)
    private val downloads: ImageDownloadRepositoryInterface = mockk(relaxed = true)
    private val store: ImageStore = mockk(relaxed = true)
    private val probe: ImageProbe = mockk()
    private val fetcher: ImageFetcher = mockk()
    private val runner: TransactionRunner = mockk()
    private val clock: Clock = mockk()
    private val renditionCache: RenditionCache = mockk()
    private val now = Instant.parse("2026-07-10T00:00:00Z")
    private val pinId = randomUUID()
    private val user = User(randomUUID(), "u")

    private val subject =
        DownloadPinImage(pins, images, downloads, store, probe, fetcher, runner, clock, renditionCache)

    init {
        every { clock.now() } returns now
        every { renditionCache.evictImage(any()) } returns Unit
    }

    private fun pendingRow() = ImageDownload(
        pinId, "https://x/i.png", DownloadStatus.PENDING, null, null, randomUUID(), now, now,
    )
    private fun failedRow() = ImageDownload(
        pinId, "https://x/i.png", DownloadStatus.FAILED, DownloadReason.NOT_FOUND, null, randomUUID(), now, now,
    )
    private fun pin() = Pin(pinId, user, "https://ctx", "https://x/i.png", "d", emptyList(), emptyList())
    private fun ctx(attempt: Int = 1, max: Int = 3) = TaskContext(attempt, max)
    private fun staged() = StagedFile("tmp/x", 3, "hash")

    private fun stubUntilFetch() {
        every { downloads.findByPinId(pinId) } returns pendingRow()
        every { pins.findPinById(pinId) } returns pin()
    }

    private fun stubUntilStage() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        every { store.stage(any(), any()) } returns staged()
    }

    @Test
    fun `Given no PENDING download row, Then it is a no-op`() {
        every { downloads.findByPinId(pinId) } returns null
        subject.download(pinId, ctx(), 100, 100)
        verify(exactly = 0) { fetcher.openStream(any()) }
    }

    @Test
    fun `Given a FAILED download row, Then it is a no-op`() {
        every { downloads.findByPinId(pinId) } returns failedRow()
        subject.download(pinId, ctx(), 100, 100)
        verify(exactly = 0) { fetcher.openStream(any()) }
    }

    @Test
    fun `Given the pin is gone, Then it is a no-op`() {
        every { downloads.findByPinId(pinId) } returns pendingRow()
        every { pins.findPinById(pinId) } returns null
        subject.download(pinId, ctx(), 100, 100)
        verify(exactly = 0) { fetcher.openStream(any()) }
    }

    @Test
    fun `Given a disallowed URL, Then it marks FAILED URL_NOT_ALLOWED and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } throws UrlNotAllowedException("blocked")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.URL_NOT_ALLOWED, now) }
    }

    @Test
    fun `Given a 403 bounce, Then it marks FAILED ACCESS_DENIED and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } throws FetchAccessDeniedException("403")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.ACCESS_DENIED, now) }
    }

    @Test
    fun `Given a 404 origin, Then it marks FAILED NOT_FOUND and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } throws FetchNotFoundException("404")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.NOT_FOUND, now) }
    }

    @Test
    fun `Given the fetch body is too large, Then it marks FAILED TOO_LARGE and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } throws FetchTooLargeException("body too big")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.TOO_LARGE, now) }
    }

    @Test
    fun `Given too many redirects, Then it marks FAILED FETCH_FAILED and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } throws TooManyRedirectsException("loop")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.FETCH_FAILED, now) }
    }

    @Test
    fun `Given a generic fetch failure, Then it marks FAILED FETCH_FAILED and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } throws FetchFailedException("unexpected 418")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.FETCH_FAILED, now) }
    }

    @Test
    fun `Given an unreachable origin below the attempt limit, Then it records the error and rethrows`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } throws FetchUnreachableException("timeout")
        assertThrows(FetchUnreachableException::class.java) {
            subject.download(pinId, ctx(attempt = 1, max = 3), 100, 100)
        }
        verify { downloads.recordLastError(pinId, "timeout", now) }
    }

    @Test
    fun `Given an unreachable origin at the attempt limit, Then it marks FAILED and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } throws FetchUnreachableException("timeout")
        assertThrows(PermanentTaskException::class.java) {
            subject.download(pinId, ctx(attempt = 3, max = 3), 100, 100)
        }
        verify { downloads.markFailed(pinId, DownloadReason.UNREACHABLE, now) }
    }

    @Test
    fun `Given the store rejects an oversize stream, Then it marks FAILED TOO_LARGE and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        every { store.stage(any(), any()) } throws ImageTooLargeException("too big")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { downloads.markFailed(pinId, DownloadReason.TOO_LARGE, now) }
    }

    @Test
    fun `Given a mid-stream stage failure below the attempt limit, Then it records the error and rethrows`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        every { store.stage(any(), any()) } throws IOException("connection reset")
        assertThrows(IOException::class.java) {
            subject.download(pinId, ctx(attempt = 1, max = 3), 100, 100)
        }
        verify { downloads.recordLastError(pinId, "connection reset", now) }
    }

    @Test
    fun `Given a mid-stream stage failure at the attempt limit, Then it marks FAILED and throws Permanent`() {
        stubUntilFetch()
        every { fetcher.openStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        every { store.stage(any(), any()) } throws IOException("connection reset")
        assertThrows(PermanentTaskException::class.java) {
            subject.download(pinId, ctx(attempt = 3, max = 3), 100, 100)
        }
        verify { downloads.markFailed(pinId, DownloadReason.UNREACHABLE, now) }
    }

    @Test
    fun `Given an undecodable image, Then it discards and marks FAILED INVALID_IMAGE and throws Permanent`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } throws UndecodableImageException("garbage")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { store.discard(staged()) }
        verify { downloads.markFailed(pinId, DownloadReason.INVALID_IMAGE, now) }
    }

    @Test
    fun `Given an unsupported image format, Then it discards and marks FAILED INVALID_IMAGE and throws Permanent`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } throws UnsupportedImageFormatException("tiff")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { store.discard(staged()) }
        verify { downloads.markFailed(pinId, DownloadReason.INVALID_IMAGE, now) }
    }

    @Test
    fun `Given too many pixels, Then it discards and marks FAILED TOO_MANY_PIXELS and throws Permanent`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } throws ImageTooManyPixelsException("decompression bomb")
        assertThrows(PermanentTaskException::class.java) { subject.download(pinId, ctx(), 100, 100) }
        verify { store.discard(staged()) }
        verify { downloads.markFailed(pinId, DownloadReason.TOO_MANY_PIXELS, now) }
    }

    @Test
    fun `Given a generic probe failure below the attempt limit, Then it discards and records a retryable error`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } throws RuntimeException("boom")
        assertThrows(RuntimeException::class.java) { subject.download(pinId, ctx(attempt = 1, max = 3), 100, 100) }
        verify { store.discard(staged()) }
        verify { downloads.recordLastError(pinId, "boom", now) }
    }

    @Test
    fun `Given a successful fetch and a still-PENDING row, Then it promotes and swaps`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } returns ProbeResult(ImageFormat.PNG, 1, 1, animated = false)
        every { images.findByPinId(pinId) } returns null
        every { downloads.deleteIfPending(pinId) } returns 1
        every { runner.inTransaction<Boolean>(any()) } answers { firstArg<() -> Boolean>().invoke() }
        subject.download(pinId, ctx(), 100, 100)
        verify { store.promote(staged(), any()) }
        verify { images.save(any()) }
        // First-time download: no superseded image, so nothing is deleted.
        verify(exactly = 0) { store.delete(any()) }
        verify(exactly = 0) { renditionCache.evictImage(any()) }
    }

    @Test
    fun `Given a still-PENDING row over an existing image, Then it swaps and deletes the superseded file`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } returns ProbeResult(ImageFormat.PNG, 1, 1, animated = false)
        val supersededKey = "originals/x/$pinId/old.png"
        every { images.findByPinId(pinId) } returns
            Image(randomUUID(), pinId, "image/png", 1, 1, false, 3, "oldhash", supersededKey, now)
        every { downloads.deleteIfPending(pinId) } returns 1
        every { runner.inTransaction<Boolean>(any()) } answers { firstArg<() -> Boolean>().invoke() }
        subject.download(pinId, ctx(), 100, 100)
        verify { images.save(any()) }
        // Only the superseded file is deleted; the freshly promoted new file is kept.
        verify(exactly = 1) { store.delete(supersededKey) }
        verify(exactly = 1) { store.delete(any()) }
    }

    @Test
    fun `Given a still-PENDING row over an existing image, Then it evicts the superseded image's rendition cache`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } returns ProbeResult(ImageFormat.PNG, 1, 1, animated = false)
        val supersededKey = "originals/x/$pinId/old.png"
        val superseded = Image(randomUUID(), pinId, "image/png", 1, 1, false, 3, "oldhash", supersededKey, now)
        every { images.findByPinId(pinId) } returns superseded
        every { downloads.deleteIfPending(pinId) } returns 1
        every { runner.inTransaction<Boolean>(any()) } answers { firstArg<() -> Boolean>().invoke() }
        subject.download(pinId, ctx(), 100, 100)
        verify { renditionCache.evictImage(superseded.id) }
    }

    @Test
    fun `Given the rendition cache eviction fails during a real swap, Then the download still succeeds`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } returns ProbeResult(ImageFormat.PNG, 1, 1, animated = false)
        val supersededKey = "originals/x/$pinId/old.png"
        val superseded = Image(randomUUID(), pinId, "image/png", 1, 1, false, 3, "oldhash", supersededKey, now)
        every { images.findByPinId(pinId) } returns superseded
        every { downloads.deleteIfPending(pinId) } returns 1
        every { runner.inTransaction<Boolean>(any()) } answers { firstArg<() -> Boolean>().invoke() }
        every { renditionCache.evictImage(any()) } throws RuntimeException("io")
        subject.download(pinId, ctx(), 100, 100)
        verify { images.save(any()) }
    }

    @Test
    fun `Given the row was superseded before the swap, Then it deletes the promoted file and does not save`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } returns ProbeResult(ImageFormat.PNG, 1, 1, animated = false)
        every { downloads.deleteIfPending(pinId) } returns 0
        every { runner.inTransaction<Boolean>(any()) } answers { firstArg<() -> Boolean>().invoke() }
        subject.download(pinId, ctx(), 100, 100)
        verify { store.delete(any()) }
        verify(exactly = 0) { images.save(any()) }
        // A no-op swap keeps the old image; its rendition cache must not be touched.
        verify(exactly = 0) { renditionCache.evictImage(any()) }
    }

    @Test
    fun `Given promote fails below the attempt limit, Then it cleans up and records a retryable INTERNAL_ERROR`() {
        stubUntilStage()
        every { probe.probe(any(), any()) } returns ProbeResult(ImageFormat.PNG, 1, 1, animated = false)
        every { store.promote(any(), any()) } throws RuntimeException()
        assertThrows(RuntimeException::class.java) { subject.download(pinId, ctx(attempt = 1, max = 3), 100, 100) }
        verify { store.discard(staged()) }
        verify { store.delete(any()) }
        verify { downloads.recordLastError(pinId, "INTERNAL_ERROR", now) }
        verify(exactly = 0) { images.save(any()) }
        verify(exactly = 0) { renditionCache.evictImage(any()) }
    }
}
