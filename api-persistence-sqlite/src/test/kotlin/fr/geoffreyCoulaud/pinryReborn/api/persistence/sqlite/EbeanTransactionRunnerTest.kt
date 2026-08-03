package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.NewTask
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanImageDownloadRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanImageRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTaskQueue
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.EbeanTransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PinRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class EbeanTransactionRunnerTest : RepositoryTest() {
    private val runner: TransactionRunner = EbeanTransactionRunner(database)
    private val queue = EbeanTaskQueue(database)
    private val downloads = EbeanImageDownloadRepository(database)
    private val images = EbeanImageRepository(database)
    private val userRepository = UserRepository(persistor)
    private val pinRepository = PinRepository(persistor)
    private val now = Instant.parse("2026-07-10T00:00:00Z")

    private fun newDownloadTask(pinId: UUID) =
        NewTask("pin.download", pinId.toString(), now, maxAttempts = 5)

    private fun savedPin(): Pin {
        val user = userRepository.saveUser(User(randomUUID(), createRandomString(), createdAt = storableNow()))
        return pinRepository.savePin(
            Pin(
                randomUUID(), user, "https://ctx", null, "desc", emptyList(), emptyList(),
                createdAt = storableNow(), updatedAt = storableNow(),
            ),
        )
    }

    private fun imageFor(pinId: UUID) = Image(
        id = randomUUID(), pinId = pinId, mimeType = "image/png", width = 1, height = 1, animated = false,
        byteSize = 1, contentHash = "h", storageKey = "originals/x/$pinId/i.png", createdAt = now,
    )

    @Test
    fun `Given a committed transaction, Then the enqueued task and the download row both exist`() {
        val pinId = randomUUID()
        val taskId = runner.inTransaction {
            val task = queue.enqueue(newDownloadTask(pinId))
            downloads.upsertPending(pinId, "https://x/i.png", task.id, now)
            task.id
        }
        assertNotNull(queue.findById(taskId))
        assertNotNull(downloads.findByPinId(pinId))
    }

    @Test
    fun `Given a rolled-back transaction, Then neither the task nor the download row exists`() {
        val pinId = randomUUID()
        assertThrows(IllegalStateException::class.java) {
            runner.inTransaction {
                val task = queue.enqueue(newDownloadTask(pinId))
                downloads.upsertPending(pinId, "https://x/i.png", task.id, now)
                error("boom")
            }
        }
        assertNull(downloads.findByPinId(pinId))
        assertEquals(0, queue.countByState(TaskState.PENDING))
    }

    @Test
    fun `Given a committed transaction, Then an image saved within it joins and is persisted`() {
        val pin = savedPin()
        val saved = runner.inTransaction { images.save(imageFor(pin.id)) }
        assertEquals(saved, images.findByPinId(pin.id))
    }
}
