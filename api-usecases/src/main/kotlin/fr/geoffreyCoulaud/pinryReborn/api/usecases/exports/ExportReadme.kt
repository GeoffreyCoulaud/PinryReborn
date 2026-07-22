package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

/**
 * Renders the archive's `README.md`: a plain-text explanation of what the export contains, aimed at
 * the end user opening the ZIP rather than at a developer (spec §4).
 *
 * The exclusion list is built **from [ExportManifest.excluded]**, never hand-duplicated, so the text
 * a user reads and `manifest.json`'s own `excluded` array can never disagree.
 *
 * Deliberately branch-free: every section is a fixed template, and the only per-manifest content (the
 * exclusion list) is produced with `joinToString`, not a hand-written loop or conditional. `render`
 * itself contains no `if`/`when`, so there is no second branch for the 100%-branch-coverage gate to
 * demand a test for.
 */
internal object ExportReadme {

    fun render(manifest: ExportManifest): String {
        val exclusions =
            manifest.excluded.joinToString(separator = "\n") { exclusion -> "- ${exclusion.what}: ${exclusion.why}" }
        return """
            |# Pinry data export
            |
            |This archive is a self-contained copy of ${manifest.user.name}'s Pinry data, produced by
            |${manifest.generator.name} ${manifest.generator.version} on ${manifest.createdAt}.
            |
            |## Contents
            |
            |- `README.md` - this file.
            |- `manifest.json` - format version, generator, record counts, and the byte size and
            |  SHA-256 digest of every other file in this archive.
            |- `user.json` - your account: id, name, creation date.
            |- `pins.jsonl` - every pin you created, one JSON object per line.
            |- `boards.jsonl` - every board you created, active and recycled, one JSON object per
            |  line.
            |- `tags.jsonl` - every tag you have used, one JSON object per line.
            |- `images/` - the original image bytes referenced from `pins.jsonl`, one file per image,
            |  named `<imageId>.<ext>`.
            |
            |## The `.jsonl` convention
            |
            |`pins.jsonl`, `boards.jsonl` and `tags.jsonl` are "JSON Lines" files: one complete JSON
            |object per line, not one big JSON array. Read them line by line rather than parsing the
            |whole file as a single JSON document.
            |
            |## Verifying integrity
            |
            |`manifest.json`'s `entries` array lists the byte size and SHA-256 digest of every other
            |file in this archive. Recompute a digest locally (for example with `sha256sum <file>`)
            |and compare it against the matching entry to confirm the file was not altered or
            |truncated in transit.
            |
            |## What is not included, and why
            |
            |$exclusions
            |
            """.trimMargin()
    }
}
