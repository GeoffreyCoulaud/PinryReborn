package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.smallrye.config.WithDefault
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The twin of [ImportDataDirectoryImageTest], and it exists because this lot made the export half
 * behave like the import one: [ExportDataDirectoryCheck] now creates and probes at boot.
 */
class ExportDataDirectoryImageTest {
    private val dockerfile = File("../Dockerfile").readText()

    private val runtimeUsers = USER_INSTRUCTION.findAll(dockerfile).map { it.groupValues[1] }.toList()

    private val ownedDirectories =
        CHOWN_CALL
            .findAll(dockerfile)
            .filter { it.groupValues[1] in runtimeUsers }
            .map { it.groupValues[2] }
            .toList()

    @Test
    fun `Given the export data directory's default, Then the image provides it to the user it runs as`() {
        // Given
        val declaredDefault =
            ExportsConfig::class.java.getMethod("dataDir").getAnnotation(WithDefault::class.java).value

        // Then: the check creates two children under it, so a root-owned ancestor refuses every boot
        assertTrue(
            ownedDirectories.any { declaredDefault == it || declaredDefault.startsWith("$it/") },
            "the image should create and own $declaredDefault, or an ancestor of it, since the startup " +
                "check refuses the boot otherwise; it owns $ownedDirectories",
        )
    }

    private companion object {
        val USER_INSTRUCTION = Regex("""(?m)^USER\s+(\d+)\s*$""")
        val CHOWN_CALL = Regex("""chown\s+(\d+):\d+\s+(\S+)""")
    }
}
