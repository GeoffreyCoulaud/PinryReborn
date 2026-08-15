package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.smallrye.config.WithDefault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [ImportDataDirectoryCheck] creates and probes `imports.data_dir` at boot, so a default the image
 * cannot write refuses every boot. Nothing here boots a container: the two are pinned to each other.
 */
class ImportDataDirectoryImageTest {
    private val dockerfile = File("../Dockerfile").readText()

    /** The uid the container runs as, which is what decides whether a chowned directory helps. */
    private val runtimeUsers = USER_INSTRUCTION.findAll(dockerfile).map { it.groupValues[1] }.toList()

    /** The directories the image creates for that uid. `COPY --chown=` carries no space and is not one. */
    private val ownedDirectories =
        CHOWN_CALL
            .findAll(dockerfile)
            .filter { it.groupValues[1] in runtimeUsers }
            .map { it.groupValues[2] }
            .toList()

    @Test
    fun `Given the Dockerfile, Then one instruction names the user the runtime runs as`() {
        // Without it the ownership below is read against nothing and every directory looks provided.
        assertEquals(listOf("1001"), runtimeUsers)
    }

    @Test
    fun `Given the import data directory's default, Then the image provides it to the user it runs as`() {
        // Given
        val declaredDefault =
            ImportsConfig::class.java.getMethod("dataDir").getAnnotation(WithDefault::class.java).value

        // Then: the probe writes a file, so an ancestor the uid owns is enough; a root-owned one is not
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
