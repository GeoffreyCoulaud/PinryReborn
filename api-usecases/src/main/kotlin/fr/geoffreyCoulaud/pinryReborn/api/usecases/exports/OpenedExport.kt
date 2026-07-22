package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import java.io.InputStream
import java.time.Instant
import java.util.UUID

/**
 * Non-nullable projection of a `READY`
 * [fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport] row, built by
 * [UserDataExportDownloader.open] at a single validation site (spec
 * `docs/specs/2026-07-22-user-data-export.md` §5). Every nullable field a `READY` row carries is
 * guaranteed present here, so a controller consuming this type never dereferences a nullable and
 * never grows an "impossible" branch that no test could reach.
 */
data class OpenedExport(
    val exportId: UUID,
    val mediaType: String,
    val fileExtension: String,
    val totalByteSize: Long,
    val sha256: String,
    val completedAt: Instant,
    val stream: InputStream,
)
