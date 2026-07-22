package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveEntryDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Pins the exact JSON shape of every `Exported*` type against a Jackson upgrade or an accidental
 * property rename (spec §4: "the field names are a published contract").
 *
 * `api-usecases` carries no Jackson dependency on its main classpath by design (Jackson is
 * adapter-only); this mapper is built **test-only**, configured identically to the real one in
 * `FilesystemZipExportArchiveStore` (`JavaTimeModule` registered, `WRITE_DATES_AS_TIMESTAMPS`
 * disabled, nothing else). That equality is what makes this test meaningful: if the adapter's mapper
 * config ever drifts from this one, this test stops proving anything about the real archive. The
 * end-to-end proof that a real archive on disk matches this shape is the integration tests (later
 * tasks), not this test.
 *
 * No `jackson-module-kotlin` is on the classpath anywhere in this codebase, so every type here is
 * serialized through plain JavaBean getter introspection, not constructor/property metadata. The one
 * place that matters is `Boolean`: a property named `isX` compiles to a getter `isX()`, which Jackson
 * reads as a property named `x` (the `is` prefix is stripped). [ExportedImage.animated] is named
 * `animated`, not `isAnimated`, specifically so its getter is `getAnimated()` and its published field
 * name stays `animated`, matching spec §4 exactly.
 */
class ExportContentGoldenJsonTest {
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `Given a fully populated ExportGenerator, Then it serializes to the published JSON shape`() {
        // Given
        val generator = ExportGenerator(name = "pinry-reborn", version = "1.2.3")

        // When
        val json = mapper.writeValueAsString(generator)

        // Then
        assertEquals("""{"name":"pinry-reborn","version":"1.2.3"}""", json)
    }

    @Test
    fun `Given a fully populated ExportedRef, Then it serializes to the published JSON shape`() {
        // Given
        val ref = ExportedRef(id = UUID.fromString("11111111-1111-1111-1111-111111111111"), name = "alice")

        // When
        val json = mapper.writeValueAsString(ref)

        // Then
        assertEquals("""{"id":"11111111-1111-1111-1111-111111111111","name":"alice"}""", json)
    }

    @Test
    fun `Given a fully populated ExportExclusion, Then it serializes to the published JSON shape`() {
        // Given
        val exclusion = ExportExclusion(what = "password hashes", why = "secrets; useless to you")

        // When
        val json = mapper.writeValueAsString(exclusion)

        // Then
        assertEquals("""{"what":"password hashes","why":"secrets; useless to you"}""", json)
    }

    @Test
    fun `Given a fully populated ExportedUser, Then it serializes to the published JSON shape`() {
        // Given
        val user =
            ExportedUser(
                id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                name = "alice",
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            )

        // When
        val json = mapper.writeValueAsString(user)

        // Then
        assertEquals(
            """{"id":"22222222-2222-2222-2222-222222222222","name":"alice","createdAt":"2026-01-01T00:00:00Z"}""",
            json,
        )
    }

    @Test
    fun `Given a fully populated ExportedTag, Then it serializes to the published JSON shape`() {
        // Given
        val tag =
            ExportedTag(
                id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                name = "travel",
                createdAt = Instant.parse("2026-01-02T00:00:00Z"),
            )

        // When
        val json = mapper.writeValueAsString(tag)

        // Then
        assertEquals(
            """{"id":"33333333-3333-3333-3333-333333333333","name":"travel","createdAt":"2026-01-02T00:00:00Z"}""",
            json,
        )
    }

    @Test
    fun `Given a fully populated ExportedBoard, Then it serializes to the published JSON shape`() {
        // Given
        val board =
            ExportedBoard(
                id = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                name = "Summer",
                description = "Summer trip",
                createdAt = Instant.parse("2026-01-03T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-04T00:00:00Z"),
                deletedAt = null,
            )

        // When
        val json = mapper.writeValueAsString(board)

        // Then
        assertEquals(
            """{"id":"44444444-4444-4444-4444-444444444444","name":"Summer","description":"Summer trip",""" +
                """"createdAt":"2026-01-03T00:00:00Z","updatedAt":"2026-01-04T00:00:00Z","deletedAt":null}""",
            json,
        )
    }

    @Test
    fun `Given a fully populated ExportedImage, Then it serializes to the published JSON shape`() {
        // Given
        val image =
            ExportedImage(
                id = UUID.fromString("55555555-5555-5555-5555-555555555555"),
                path = "images/55555555-5555-5555-5555-555555555555.jpg",
                mimeType = "image/jpeg",
                width = 1920,
                height = 1080,
                animated = false,
                byteSize = 482913,
                sha256 = "deadbeef",
                createdAt = Instant.parse("2026-01-05T00:00:00Z"),
            )

        // When
        val json = mapper.writeValueAsString(image)

        // Then
        assertEquals(
            """{"id":"55555555-5555-5555-5555-555555555555",""" +
                """"path":"images/55555555-5555-5555-5555-555555555555.jpg","mimeType":"image/jpeg",""" +
                """"width":1920,"height":1080,"animated":false,"byteSize":482913,"sha256":"deadbeef",""" +
                """"createdAt":"2026-01-05T00:00:00Z"}""",
            json,
        )
    }

    @Test
    fun `Given a fully populated ExportedPin, Then it serializes to the published JSON shape`() {
        // Given
        val pin =
            ExportedPin(
                id = UUID.fromString("66666666-6666-6666-6666-666666666666"),
                description = "A pin",
                sourceContextUrl = "https://example.org/article",
                sourceMediaUrl = "https://example.org/image.jpg",
                createdAt = Instant.parse("2026-01-06T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-07T00:00:00Z"),
                deletedAt = null,
                tags =
                    listOf(ExportedRef(id = UUID.fromString("11111111-1111-1111-1111-111111111111"), name = "travel")),
                boards =
                    listOf(ExportedRef(id = UUID.fromString("44444444-4444-4444-4444-444444444444"), name = "Summer")),
                image =
                    ExportedImage(
                        id = UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        path = "images/55555555-5555-5555-5555-555555555555.jpg",
                        mimeType = "image/jpeg",
                        width = 1920,
                        height = 1080,
                        animated = false,
                        byteSize = 482913,
                        sha256 = "deadbeef",
                        createdAt = Instant.parse("2026-01-05T00:00:00Z"),
                    ),
            )

        // When
        val json = mapper.writeValueAsString(pin)

        // Then
        assertEquals(
            """{"id":"66666666-6666-6666-6666-666666666666","description":"A pin",""" +
                """"sourceContextUrl":"https://example.org/article",""" +
                """"sourceMediaUrl":"https://example.org/image.jpg",""" +
                """"createdAt":"2026-01-06T00:00:00Z","updatedAt":"2026-01-07T00:00:00Z","deletedAt":null,""" +
                """"tags":[{"id":"11111111-1111-1111-1111-111111111111","name":"travel"}],""" +
                """"boards":[{"id":"44444444-4444-4444-4444-444444444444","name":"Summer"}],""" +
                """"image":{"id":"55555555-5555-5555-5555-555555555555",""" +
                """"path":"images/55555555-5555-5555-5555-555555555555.jpg","mimeType":"image/jpeg",""" +
                """"width":1920,"height":1080,"animated":false,"byteSize":482913,"sha256":"deadbeef",""" +
                """"createdAt":"2026-01-05T00:00:00Z"}}""",
            json,
        )
    }

    @Test
    fun `Given a pin with no image, Then the image field serializes as null rather than being omitted`() {
        // Given
        val pin =
            ExportedPin(
                id = UUID.fromString("66666666-6666-6666-6666-666666666666"),
                description = "A pin",
                sourceContextUrl = "https://example.org/article",
                sourceMediaUrl = null,
                createdAt = null,
                updatedAt = null,
                deletedAt = Instant.parse("2026-01-08T00:00:00Z"),
                tags = emptyList(),
                boards = emptyList(),
                image = null,
            )

        // When
        val json = mapper.writeValueAsString(pin)

        // Then
        assertEquals(
            """{"id":"66666666-6666-6666-6666-666666666666","description":"A pin",""" +
                """"sourceContextUrl":"https://example.org/article","sourceMediaUrl":null,""" +
                """"createdAt":null,"updatedAt":null,"deletedAt":"2026-01-08T00:00:00Z",""" +
                """"tags":[],"boards":[],"image":null}""",
            json,
        )
    }

    @Test
    fun `Given a fully populated ExportManifest, Then it serializes to the published JSON shape`() {
        // Given
        val manifest =
            ExportManifest(
                formatVersion = 1,
                generator = ExportGenerator(name = "pinry-reborn", version = "1.2.3"),
                exportId = UUID.fromString("77777777-7777-7777-7777-777777777777"),
                createdAt = Instant.parse("2026-07-22T10:15:30Z"),
                expiresAt = Instant.parse("2026-07-29T10:15:30Z"),
                user = ExportedRef(id = UUID.fromString("22222222-2222-2222-2222-222222222222"), name = "alice"),
                counts = ExportCounts(pins = 1234, boards = 12, tags = 90, images = 1180),
                entries = listOf(ArchiveEntryDigest(path = "pins.jsonl", byteSize = 918273, sha256 = "cafef00d")),
                excluded =
                    listOf(
                        ExportExclusion(
                            what = "password hashes",
                            why = "secrets; useless to you, dangerous if this archive leaks",
                        ),
                    ),
            )

        // When
        val json = mapper.writeValueAsString(manifest)

        // Then
        assertEquals(
            """{"formatVersion":1,"generator":{"name":"pinry-reborn","version":"1.2.3"},""" +
                """"exportId":"77777777-7777-7777-7777-777777777777",""" +
                """"createdAt":"2026-07-22T10:15:30Z","expiresAt":"2026-07-29T10:15:30Z",""" +
                """"user":{"id":"22222222-2222-2222-2222-222222222222","name":"alice"},""" +
                """"counts":{"pins":1234,"boards":12,"tags":90,"images":1180},""" +
                """"entries":[{"path":"pins.jsonl","byteSize":918273,"sha256":"cafef00d"}],""" +
                """"excluded":[{"what":"password hashes",""" +
                """"why":"secrets; useless to you, dangerous if this archive leaks"}]}""",
            json,
        )
    }
}
