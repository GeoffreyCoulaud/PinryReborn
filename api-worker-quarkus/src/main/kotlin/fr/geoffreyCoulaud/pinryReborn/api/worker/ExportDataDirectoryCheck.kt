package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StorageLayout
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
        verifySameFileStore(
            dataDir.resolve(StorageLayout.STAGING_DIRECTORY),
            dataDir.resolve(StorageLayout.EXPORTS_DIRECTORY),
        )
    }

    /**
     * [storeOf] is a seam: static mocking of `java.nio.file.Files` deadlocks this test JVM, which
     * `DataDirPaths` records. Creating first bounds this to mounts that exist, argued in ADR 0017.
     */
    fun verifySameFileStore(
        stagingDir: Path,
        archiveDir: Path,
        storeOf: (Path) -> Any = { Files.getFileStore(it) },
    ) {
        val (stagingStore, archiveStore) = runCatching {
            Files.createDirectories(stagingDir)
            Files.createDirectories(archiveDir)
            storeOf(stagingDir) to storeOf(archiveDir)
        }.getOrElse { throw IllegalStateException("exports.data_dir is not usable: $stagingDir, $archiveDir", it) }
        check(stagingStore == archiveStore) {
            "exports staging and archives must share a filesystem, otherwise the promote copies the " +
                "whole archive while holding the write connection: $stagingDir and $archiveDir"
        }
    }
}
