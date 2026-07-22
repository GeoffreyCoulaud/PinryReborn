package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveEntryDigest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ExportReadmeTest {
    private fun sampleManifest(excluded: List<ExportExclusion>) =
        ExportManifest(
            formatVersion = 1,
            generator = ExportGenerator(name = "pinry-reborn", version = "1.2.3"),
            exportId = UUID.fromString("77777777-7777-7777-7777-777777777777"),
            createdAt = Instant.parse("2026-07-22T10:15:30Z"),
            expiresAt = Instant.parse("2026-07-29T10:15:30Z"),
            user = ExportedRef(id = UUID.fromString("22222222-2222-2222-2222-222222222222"), name = "alice"),
            counts = ExportCounts(pins = 1, boards = 1, tags = 1, images = 1),
            entries = listOf(ArchiveEntryDigest(path = "pins.jsonl", byteSize = 918273, sha256 = "cafef00d")),
            excluded = excluded,
        )

    @Test
    fun `Given a manifest, Then the README names every archive file and every exclusion reason`() {
        // Given
        val excluded =
            listOf(
                ExportExclusion(what = "password hashes", why = "secrets; useless to you, dangerous if leaked"),
                ExportExclusion(what = "session tokens", why = "secrets; expired and meaningless elsewhere"),
                ExportExclusion(what = "image renditions", why = "derived from the original bytes, regenerable"),
            )
        val manifest = sampleManifest(excluded)

        // When
        val readme = ExportReadme.render(manifest)

        // Then
        assertTrue(readme.contains("README.md"))
        assertTrue(readme.contains("manifest.json"))
        assertTrue(readme.contains("user.json"))
        assertTrue(readme.contains("pins.jsonl"))
        assertTrue(readme.contains("boards.jsonl"))
        assertTrue(readme.contains("tags.jsonl"))
        assertTrue(readme.contains("images/"))
        for (exclusion in excluded) {
            assertTrue(readme.contains(exclusion.what), "expected README to mention '${exclusion.what}'")
            assertTrue(readme.contains(exclusion.why), "expected README to mention '${exclusion.why}'")
        }
    }
}
