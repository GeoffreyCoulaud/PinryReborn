package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.Task
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageSourceUrlInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class RequestPinImageDownloadTest {
    private val pins: PinRepositoryInterface = mockk()
    private val downloads: ImageDownloadRepositoryInterface = mockk(relaxed = true)
    private val enqueue: EnqueueTask = mockk()
    private val runner: TransactionRunner = mockk()
    private val clock: Clock = mockk()
    private val now = Instant.parse("2026-07-10T00:00:00Z")
    private val owner = User(randomUUID(), "o", createdAt = Instant.now())
    private val pinId = randomUUID()

    private val subject = RequestPinImageDownload(pins, downloads, enqueue, runner, clock)

    init {
        every { clock.now() } returns now
        every { runner.inTransaction<ImageDownload>(any()) } answers { firstArg<() -> ImageDownload>().invoke() }
    }

    private fun pin(author: User = owner) = Pin(pinId, author, "https://ctx", null, "d", emptyList(), emptyList(),
        createdAt = Instant.now(), updatedAt = Instant.now())
    private fun aTask(id: java.util.UUID) = Task(
        id, PinDownloadTask.KIND, pinId.toString(), TaskState.PENDING, 0, now, 0, 5, null, null, false,
        "${PinDownloadTask.KIND}:$pinId", null,
    )

    @Test
    fun `Given a missing pin, Then it throws ImagePinDoesNotExistError`() {
        every { pins.findPinById(pinId) } returns null
        assertThrows(ImagePinDoesNotExistError::class.java) { subject.request(pinId, owner, "https://x/i.png") }
    }

    @Test
    fun `Given a non-owner, Then it throws ImagePermissionError`() {
        every { pins.findPinById(pinId) } returns pin(author = User(randomUUID(), "other", createdAt = Instant.now()))
        assertThrows(ImagePermissionError::class.java) { subject.request(pinId, owner, "https://x/i.png") }
    }

    @Test
    fun `Given a non-http url, Then it throws ImageSourceUrlInvalidError`() {
        every { pins.findPinById(pinId) } returns pin()
        assertThrows(ImageSourceUrlInvalidError::class.java) { subject.request(pinId, owner, "ftp://x/i.png") }
    }

    @Test
    fun `Given a schemeless url, Then it throws ImageSourceUrlInvalidError`() {
        every { pins.findPinById(pinId) } returns pin()
        assertThrows(ImageSourceUrlInvalidError::class.java) { subject.request(pinId, owner, "not-a-url") }
    }

    @Test
    fun `Given a malformed url, Then it throws ImageSourceUrlInvalidError`() {
        every { pins.findPinById(pinId) } returns pin()
        assertThrows(ImageSourceUrlInvalidError::class.java) { subject.request(pinId, owner, "http://exa mple/i.png") }
    }

    @Test
    fun `Given a plain http url, Then it enqueues pin download and upserts a PENDING row atomically`() {
        val taskId = randomUUID()
        every { pins.findPinById(pinId) } returns pin()
        every { enqueue.enqueue(any(), any(), any(), any(), any(), any()) } returns aTask(taskId)
        every { downloads.upsertPending(pinId, "http://x/i.png", taskId, now) } returns
            ImageDownload(pinId, "http://x/i.png", DownloadStatus.PENDING, null, null, taskId, now, now)

        val result = subject.request(pinId, owner, "http://x/i.png")

        assertEquals(DownloadStatus.PENDING, result.status)
        verify { downloads.upsertPending(pinId, "http://x/i.png", taskId, now) }
    }

    @Test
    fun `Given a valid request, Then it enqueues pin download and upserts a PENDING row atomically`() {
        val taskId = randomUUID()
        every { pins.findPinById(pinId) } returns pin()
        every { enqueue.enqueue(any(), any(), any(), any(), any(), any()) } returns aTask(taskId)
        every { downloads.upsertPending(pinId, "https://x/i.png", taskId, now) } returns
            ImageDownload(pinId, "https://x/i.png", DownloadStatus.PENDING, null, null, taskId, now, now)
        val kindSlot = slot<String>(); val payloadSlot = slot<String>(); val dedupSlot = slot<String?>()
        every {
            enqueue.enqueue(capture(kindSlot), capture(payloadSlot), any(), any(), any(), captureNullable(dedupSlot))
        } returns aTask(taskId)

        val result = subject.request(pinId, owner, "https://x/i.png")

        assertEquals(DownloadStatus.PENDING, result.status)
        assertEquals(PinDownloadTask.KIND, kindSlot.captured)
        assertEquals(pinId.toString(), payloadSlot.captured)
        assertEquals("${PinDownloadTask.KIND}:$pinId", dedupSlot.captured)
        verify { downloads.upsertPending(pinId, "https://x/i.png", taskId, now) }
    }
}
