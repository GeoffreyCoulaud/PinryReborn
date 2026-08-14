package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Refuses the boot when `imports.data_dir` cannot take bytes (spec §9), rather than letting an
 * operator find out through a user who has just streamed twenty gigabytes into an unwritable volume.
 */
@ApplicationScoped
class ImportDataDirectoryCheck(
    private val config: ImportsConfig,
) {
    fun onStart(
        @Observes ignored: StartupEvent,
    ) = verifyUsable(Path.of(config.dataDir()))

    /**
     * A real write rather than a permission read: the effective user is what decides, and a container
     * running as root passes a mode check that would refuse everybody else.
     */
    fun verifyUsable(dataDir: Path) {
        runCatching {
            Files.createDirectories(dataDir)
            Files.delete(Files.createTempFile(dataDir, "startup-", ".probe"))
        }.getOrElse { throw IllegalStateException("imports.data_dir is not usable: $dataDir", it) }
    }
}
