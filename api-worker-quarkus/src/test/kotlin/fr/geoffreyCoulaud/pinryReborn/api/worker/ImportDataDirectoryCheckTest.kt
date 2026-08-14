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

class ImportDataDirectoryCheckTest {
    @TempDir lateinit var tempDir: Path

    private val config = mockk<ImportsConfig>()

    private fun check() = ImportDataDirectoryCheck(config)

    @Test
    fun `Given a data directory that is not there yet, Then it is created and the boot goes on`() {
        // Given: the volume is mounted empty, which is the ordinary first start
        val dataDir = tempDir.resolve("imports")

        // When
        check().verifyUsable(dataDir)

        // Then: created at boot rather than on first write, and nothing is left behind by the probe
        assertTrue(Files.isDirectory(dataDir))
        assertTrue(Files.list(dataDir).use { it.toList() }.isEmpty())
    }

    @Test
    fun `Given a data directory that cannot take a byte, Then the boot is refused naming the path`() {
        // Given: a regular file where the parent directory should be, which refuses the write whatever
        // the effective user is. A mode bit would not: root walks straight through one.
        val blocker = Files.createFile(tempDir.resolve("blocker"))
        val dataDir = blocker.resolve("imports")

        // When / Then: the operator gets the path, not a stack trace about a temp file
        val error = assertThrows(IllegalStateException::class.java) { check().verifyUsable(dataDir) }
        assertTrue(
            error.message.orEmpty().contains(dataDir.toString()),
            "the refusal should name the directory it could not use, was: ${error.message}",
        )
    }

    @Test
    fun `Given the startup event, Then the configured directory is the one checked`() {
        // Given
        val dataDir = tempDir.resolve("configured")
        every { config.dataDir() } returns dataDir.toString()

        // When
        check().onStart(mockk<StartupEvent>())

        // Then
        assertTrue(Files.isDirectory(dataDir))
    }
}
