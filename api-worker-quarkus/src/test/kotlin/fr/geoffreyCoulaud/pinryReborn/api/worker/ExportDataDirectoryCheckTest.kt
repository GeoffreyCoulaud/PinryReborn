package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExportDataDirectoryCheckTest {
    @TempDir lateinit var tempDir: Path

    private val config = mockk<ExportsConfig>()

    private fun check() = ExportDataDirectoryCheck(config)

    @Test
    fun `Given staging and archives on two file stores, Then the boot is refused naming both`() {
        // Given: the store lookup is a seam, since @TempDir cannot produce two filesystems
        val stagingDir = tempDir.resolve("tmp")
        val archiveDir = tempDir.resolve("exports")

        // When
        val error = assertThrows(IllegalStateException::class.java) {
            check().verifySameFileStore(stagingDir, archiveDir) { path -> path.fileName.toString() }
        }

        // Then: an operator gets the two directories to reconcile, not a stalled promote under load
        val message = error.message.orEmpty()
        assertTrue(message.contains(stagingDir.toString()), "the refusal should name the staging dir: $message")
        assertTrue(message.contains(archiveDir.toString()), "the refusal should name the archive dir: $message")
    }

    @Test
    fun `Given staging and archives on one file store, Then the boot goes on`() {
        // Given
        val stagingDir = tempDir.resolve("tmp")
        val archiveDir = tempDir.resolve("exports")

        // When
        check().verifySameFileStore(stagingDir, archiveDir) { "one store" }

        // Then: both exist, so the lookup answered for the directories the store will really use
        assertTrue(Files.isDirectory(stagingDir))
        assertTrue(Files.isDirectory(archiveDir))
    }

    @Test
    fun `Given the startup event, Then the configured data directory is the one probed`() {
        // Given: no seam here, so the real file store lookup is the one exercised
        val dataDir = tempDir.resolve("configured")
        every { config.dataDir() } returns dataDir.toString()

        // When
        check().onStart(mockk<StartupEvent>())

        // Then
        assertTrue(Files.isDirectory(dataDir.resolve("tmp")))
        assertTrue(Files.isDirectory(dataDir.resolve("exports")))
    }
}
