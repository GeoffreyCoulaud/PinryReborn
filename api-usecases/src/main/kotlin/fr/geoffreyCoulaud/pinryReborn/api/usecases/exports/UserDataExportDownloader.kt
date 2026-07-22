package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportGoneError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ExportNotReadyError
import java.util.UUID

/**
 * Opens a user data export archive for download (spec `docs/specs/2026-07-22-user-data-export.md`
 * §5, §6): owner- and state-checks [exportId] through [UserDataExportGetter], then opens the archive
 * stream EAGERLY at [skipBytes] -- so a purge racing the download fails right here, before any status
 * line is sent, instead of truncating an already-committed `200`.
 *
 * The six nullable fields a `READY` row carries (`storageKey`, `mediaType`, `fileExtension`,
 * `byteSize`, `sha256`, `completedAt`) collapse into a SINGLE reachable branch (`presentFieldCount`
 * below), instead of one null check per field: dereferencing each of them separately would create
 * branches whose "impossible" side no test could ever reach, which breaks the 100% branch gate. See
 * spec §5.
 *
 * Deliberately not `@ApplicationScoped` yet: [ExportArchiveStore] has no CDI producer until the
 * wiring task (`ExportProducers`). Same precedent as `UserDataExportRequester`/`UserDataExportBuilder`.
 */
@Suppress("UnsafeCallOnNullableType")
class UserDataExportDownloader(
    private val getter: UserDataExportGetter,
    private val archiveStore: ExportArchiveStore,
) {
    fun open(user: User, exportId: UUID, skipBytes: Long): OpenedExport {
        val export = getter.get(user, exportId)
        requireLive(export)
        requireReady(export)
        requireValidOffset(skipBytes, export.byteSize!!)
        return toOpenedExport(export, skipBytes)
    }

    private fun requireLive(export: UserDataExport) {
        if (export.state.isGone) throw ExportGoneError()
    }

    /**
     * The single validation site for the six nullable fields a `READY` row carries: their presence
     * collapses into ONE reachable branch (`presentFieldCount`), instead of one null check per field.
     */
    private fun requireReady(export: UserDataExport) {
        if (export.state != UserDataExportState.READY) throw ExportNotReadyError()
        val presentFieldCount = listOfNotNull(
            export.storageKey, export.mediaType, export.fileExtension,
            export.byteSize, export.sha256, export.completedAt,
        ).size
        if (presentFieldCount != REQUIRED_FIELD_COUNT) throw ExportNotReadyError()
    }

    private fun requireValidOffset(skipBytes: Long, byteSize: Long) {
        if (skipBytes < 0) throw ExportNotReadyError()
        if (skipBytes >= byteSize) throw ExportNotReadyError()
    }

    private fun toOpenedExport(export: UserDataExport, skipBytes: Long): OpenedExport = OpenedExport(
        exportId = export.id,
        mediaType = export.mediaType!!,
        fileExtension = export.fileExtension!!,
        totalByteSize = export.byteSize!!,
        sha256 = export.sha256!!,
        completedAt = export.completedAt!!,
        stream = archiveStore.openStream(export.storageKey!!, skipBytes),
    )

    private companion object {
        const val REQUIRED_FIELD_COUNT = 6
    }
}
