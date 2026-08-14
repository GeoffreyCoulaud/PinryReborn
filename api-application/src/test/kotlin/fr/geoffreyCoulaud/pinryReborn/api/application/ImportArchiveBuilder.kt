package fr.geoffreyCoulaud.pinryReborn.api.application

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A `formatVersion` 1 archive built entry by entry, so a case can hand the real importer what the real
 * exporter never writes: a lying manifest, a truncated line, a traversal path, a text file named `.jpg`.
 */
internal class ImportArchiveBuilder(private val mapper: ObjectMapper) {
    private val entries = linkedMapOf<String, ByteArray>()

    fun manifest(announcedPins: Int, formatVersion: Int = FORMAT_VERSION) = apply {
        json("manifest.json", mapOf("formatVersion" to formatVersion, "counts" to mapOf("pins" to announcedPins)))
    }

    fun tags(vararg names: String) = apply {
        jsonLines("tags.jsonl", names.map { mapOf("name" to it, "createdAt" to PAST.toString()) })
    }

    fun boards(vararg lines: Map<String, Any?>) = apply { jsonLines("boards.jsonl", lines.toList()) }

    fun pins(vararg lines: Map<String, Any?>) = apply { jsonLines("pins.jsonl", lines.toList()) }

    /** Appends a raw line to an entry already written: a line cut in half is not JSON a writer emits. */
    fun appendLine(name: String, line: String) = apply {
        entries[name] = (String(entries.getValue(name)) + "\n" + line).toByteArray()
    }

    fun entry(name: String, bytes: ByteArray) = apply { entries[name] = bytes }

    fun bytes(): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun json(name: String, value: Map<String, Any?>) {
        entries[name] = mapper.writeValueAsBytes(value)
    }

    private fun jsonLines(name: String, values: List<Map<String, Any?>>) {
        entries[name] = values.joinToString("\n") { mapper.writeValueAsString(it) }.toByteArray()
    }

    companion object {
        const val FORMAT_VERSION = 1
        val PAST: Instant = Instant.parse("2026-01-02T03:04:05Z")

        fun sha256(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun boardLine(
            name: String,
            description: String = "",
            deletedAt: Instant? = null,
        ): Map<String, Any?> = mapOf(
            "id" to "11111111-1111-1111-1111-111111111111",
            "name" to name,
            "description" to description,
            "createdAt" to PAST.toString(),
            "updatedAt" to PAST.toString(),
            "deletedAt" to deletedAt?.toString(),
        )

        /** One `pins.jsonl` line. [imagePath] null is a pin with no medium, which has no identity. */
        @Suppress("LongParameterList") // The published line's shape; grouping it would invent a type.
        fun pinLine(
            sourceContextUrl: String,
            description: String = "a pin",
            tags: List<String> = emptyList(),
            boards: List<String> = emptyList(),
            imagePath: String? = null,
            imageSha256: String = "",
            imageMimeType: String = "image/png",
            deletedAt: Instant? = null,
        ): Map<String, Any?> = mapOf(
            "id" to "22222222-2222-2222-2222-222222222222",
            "description" to description,
            "sourceContextUrl" to sourceContextUrl,
            "sourceMediaUrl" to null,
            "createdAt" to PAST.toString(),
            "updatedAt" to PAST.toString(),
            "deletedAt" to deletedAt?.toString(),
            "tags" to tags.map { mapOf("name" to it) },
            "boards" to boards.map { mapOf("name" to it) },
            "image" to imagePath?.let {
                mapOf("path" to it, "sha256" to imageSha256, "mimeType" to imageMimeType)
            },
        )
    }
}
