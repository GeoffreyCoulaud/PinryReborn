package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Refuses the boot when the staging and archive halves of `exports.data_dir` sit on different file
 * stores: the promote then runs as a byte copy inside the publishing transaction (ADR 0017).
 */
@ApplicationScoped
class ExportDataDirectoryCheck(
    private val config: ExportsConfig,
) {
    fun onStart(
        @Observes ignored: StartupEvent,
    ) {
        // The two names FilesystemZipExportArchiveStore resolves under the data dir.
        val dataDir = Path.of(config.dataDir())
        verifySameFileStore(dataDir.resolve("tmp"), dataDir.resolve("exports"))
    }

    /**
     * [storeOf] is a seam: `@TempDir` gives one filesystem, and `DataDirPaths` records that static
     * mocking of `java.nio.file.Files` deadlocks this project's test JVM.
     */
    fun verifySameFileStore(
        stagingDir: Path,
        archiveDir: Path,
        storeOf: (Path) -> Any = { Files.getFileStore(it) },
    ) {
        Files.createDirectories(stagingDir)
        Files.createDirectories(archiveDir)
        check(storeOf(stagingDir) == storeOf(archiveDir)) {
            "exports staging and archives must share a filesystem, otherwise the promote copies the " +
                "whole archive while holding the write connection: $stagingDir and $archiveDir"
        }
    }
}
