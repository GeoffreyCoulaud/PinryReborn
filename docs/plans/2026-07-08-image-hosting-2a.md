# Image Hosting (2a — Canonical Image) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a pin one canonical image, uploaded directly by its owner (multipart), stored on the filesystem, validated/measured with libvips, served back with HTTP caching, and cascaded with the pin's lifecycle.

**Architecture:** Clean/Hexagonal. `api-domain` gains an `Image` entity and three ports (`ImageStore`, `ImageProbe`, `ImageRepositoryInterface`). Two new adapter modules implement the infrastructure ports: `api-storage-filesystem` (filesystem bytes) and `api-imaging-vips` (libvips decoding via vips-ffm). Use cases orchestrate the ports; the Quarkus REST layer exposes `PUT`/`GET`/`DELETE /api/v1/pins/{pinId}/image`.

**Tech Stack:** Kotlin 2.4, Quarkus 3.37 (RESTEasy Reactive + `@RestForm` multipart), Ebean 19 + SQLite, libvips via `app.photofox.vips-ffm:vips-ffm-core`, JUnit 5 + MockK + REST Assured. JDK 25 toolchain, Java 21 bytecode floor.

## Global Constraints

- **100% branch coverage per package** (Kover gate), for every module except `api-application`. Both new adapter modules are in-gate. Exercise both sides of every conditional.
- **Strict TDD**: write the failing test first, watch it fail, then the minimal implementation.
- **Clean/Hexagonal purity**: `api-domain` is pure (no I/O, CDI, Ebean, logging, `java.nio` file I/O — type-only references like a `String` path are fine, actual filesystem calls are not). `api-usecases` depends on `api-domain` only, no I/O incl. no logging. Adapters do I/O.
- **English everywhere** (identifiers, comments, commit messages, docs).
- **Conventional commits** (`feat(domain):`, `feat(persistence):`, `test:`, `chore:`, `feat(imaging):`, etc.).
- **No top-level functions** outside a class/object; extension functions are the only exception.
- **Never store binary blobs in the database**; bytes live on the filesystem, the DB holds metadata + a storage key.
- **A pin has one canonical image** (`Pin.image: Image?`, singular). `Image` is **immutable** (new id per upload; replacement is a swap).
- **Storage layout**: `<data_dir>/originals/<user_id>/<pin_id>/<image_id>.<ext>`; temp under `<data_dir>/tmp/` (same filesystem as originals); `<data_dir>/cache/` reserved for future renditions (not created here).
- **Server-controlled file names** (never the uploaded filename).
- **Formats**: PNG, JPEG, WebP (incl. animated), GIF. **Limits**: `images.max_file_bytes` default 30 MiB (`31457280`), `images.max_pixels` default `50000000`.
- **HTTP semantics**: owner-only endpoints; `403` for a non-owner, `404` for a missing pin/image, `413` oversize, `422` invalid/over-pixel. `PUT` is the verb for set-or-replace and is **not negotiable**. Serve with `ETag = content_hash`, `Cache-Control: private` (not `immutable`), `304` on `If-None-Match`.
- **Crash-safety**: stream to temp → probe → fsync → move to a fresh path → commit DB row → best-effort delete of any superseded file. Atomicity of the move is opportunistic; `fsync`-before-commit is the crash-critical guarantee.
- Spec: `docs/specs/2026-07-08-image-hosting-2a.md`.

---

## File Structure

**New files:**

- `api-domain/.../domain/entities/Image.kt` — canonical image entity.
- `api-domain/.../domain/enums/ImageFormat.kt` — supported formats (mime + extension).
- `api-domain/.../domain/repositories/ImageRepositoryInterface.kt` — persistence port.
- `api-domain/.../domain/images/ImageStore.kt` — byte-storage port + `StagedFile`.
- `api-domain/.../domain/images/ImageProbe.kt` — validation/measurement port + `ProbeResult`.
- `api-domain/.../domain/images/ImageProbeException.kt` — domain probe failures (unsupported / undecodable / too many pixels).
- `api-persistence-sqlite/.../models/ImageModel.kt` — Ebean entity (`images` table).
- `api-persistence-sqlite/.../mappers/ImageModelMapper.kt` — `toDomain()`/`toModel()`.
- `api-persistence-sqlite/.../repositories/EbeanImageRepository.kt` — port impl.
- `api-persistence-sqlite/src/main/resources/dbmigration/1.4.sql` (+ `model/1.4.model.xml`) — `images` table, `source_media_url` nullable, FK + unique.
- `api-storage-filesystem/` (new module) — `FilesystemImageStore.kt`, `build.gradle.kts`.
- `api-imaging-vips/` (new module) — `VipsImageProbe.kt`, `build.gradle.kts`.
- `api-usecases/.../usecases/SetPinImage.kt`, `DeletePinImage.kt`, `GetPinImage.kt`.
- `api-usecases/.../usecases/exceptions/ImageError.kt` — `BaseError` subclasses.
- `api-presentation-quarkus/.../config/ImagesConfig.kt` — `@ConfigMapping`.
- `api-presentation-quarkus/.../controllers/ImageController.kt`.
- `api-presentation-quarkus/.../dtos/output/ImageOutputDto.kt` + `.../mappers/ImageMapper.kt`.

**Modified files:**

- `settings.gradle.kts` — include the two new modules.
- `gradle/libs.versions.toml` — vips-ffm version + library.
- `api-application/build.gradle.kts` — depend on the two new adapter modules.
- `api-application/src/main/resources/application.properties` — `images.*` defaults + `quarkus.http.limits.max-body-size`.
- `api-domain/.../domain/entities/Pin.kt` — `sourceMediaUrl: String?`, `image: Image? = null`.
- `api-persistence-sqlite/.../models/PinModel.kt` + `mappers/PinModelMapper.kt` — nullable source media url.
- `api-usecases/.../usecases/PinCreator.kt` + presentation DTO/mapper — nullable source media url ripple.
- `api-usecases/.../usecases/exceptions/ErrorCode.kt` — new image error codes.
- `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` — map new codes.
- The pin permanent-delete path (`PinRepository` / recycle-bin use case) — delete image files.
- `Dockerfile`, `.github/workflows/validate.yml` — native libvips.

---

## Phase 0 — Scaffolding

### Task 1: Version catalog + two adapter modules + settings + composition-root wiring

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Create: `api-storage-filesystem/build.gradle.kts`
- Create: `api-imaging-vips/build.gradle.kts`
- Modify: `api-application/build.gradle.kts`
- Create (placeholder to make each module compile): `api-storage-filesystem/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/storage/filesystem/.gitkeep` is not enough for Kotlin; instead the module compiles empty. Add a trivial test in Task 8 / Task 9.

**Interfaces:**
- Produces: two buildable Gradle modules `:api-storage-filesystem` and `:api-imaging-vips`, each depending on `:api-domain`; a `libs.vips.ffm.core` catalog accessor.

- [ ] **Step 1: Resolve the current vips-ffm version.** Look up the latest `app.photofox.vips-ffm:vips-ffm-core` on Maven Central (https://central.sonatype.com/artifact/app.photofox.vips-ffm/vips-ffm-core). Record the exact version string; use it below in place of `<VIPS_FFM_VERSION>`.

- [ ] **Step 2: Add vips-ffm to the version catalog.** In `gradle/libs.versions.toml`, under `[versions]` add `vips-ffm = "<VIPS_FFM_VERSION>"`; under `[libraries]` add:
```toml
vips-ffm-core = { module = "app.photofox.vips-ffm:vips-ffm-core", version.ref = "vips-ffm" }
```

- [ ] **Step 3: Include the two modules in settings.** In `settings.gradle.kts`, after `include(":api-utilities")` add:
```kotlin
include(":api-storage-filesystem")
include(":api-imaging-vips")
```

- [ ] **Step 4: Write `api-storage-filesystem/build.gradle.kts`** (mirrors `api-usecases` — depends on `api-domain` only, CDI compileOnly, standard test wiring):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
}
dependencies {
    implementation(project(":api-domain"))
    compileOnly(libs.jakarta.cdi.api)
    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
```

- [ ] **Step 5: Write `api-imaging-vips/build.gradle.kts`** (same, plus vips-ffm):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
}
dependencies {
    implementation(project(":api-domain"))
    implementation(libs.vips.ffm.core)
    compileOnly(libs.jakarta.cdi.api)
    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
```

- [ ] **Step 6: Wire the adapters into the composition root.** In `api-application/build.gradle.kts`, add to `dependencies`:
```kotlin
    implementation(project(":api-storage-filesystem"))
    implementation(project(":api-imaging-vips"))
```

- [ ] **Step 7: Verify the project graph builds.** Run: `./gradlew :api-storage-filesystem:compileKotlin :api-imaging-vips:compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL (empty modules compile; vips-ffm resolves from Maven Central). If vips-ffm fails to resolve, re-check the version from Step 1.

- [ ] **Step 8: Commit.**
```bash
git add gradle/libs.versions.toml settings.gradle.kts api-storage-filesystem api-imaging-vips api-application/build.gradle.kts
git commit -m "chore(images): scaffold api-storage-filesystem and api-imaging-vips modules"
```

### Task 2: `ImagesConfig` + defaults

**Files:**
- Create: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/config/ImagesConfig.kt`
- Modify: `api-application/src/main/resources/application.properties`
- Test: `api-presentation-quarkus/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/config/ImagesConfigTest.kt`

**Interfaces:**
- Produces: `ImagesConfig` with `dataDir(): String`, `maxFileBytes(): Long`, `maxPixels(): Long`.

- [ ] **Step 1: Write the failing test** (`ImagesConfig` is an interface; test a concrete anonymous implementation to lock the contract shape and defaults documentation):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImagesConfigTest {
    @Test
    fun `Given a config implementation, Then its accessors are readable`() {
        // Given
        val config = object : ImagesConfig {
            override fun dataDir() = "/var/lib/pinry"
            override fun maxFileBytes() = 31_457_280L
            override fun maxPixels() = 50_000_000L
        }
        // Then
        assertEquals("/var/lib/pinry", config.dataDir())
        assertEquals(31_457_280L, config.maxFileBytes())
        assertEquals(50_000_000L, config.maxPixels())
    }
}
```

- [ ] **Step 2: Run it to see it fail** (unresolved `ImagesConfig`). Run: `./gradlew :api-presentation-quarkus:test --tests "*ImagesConfigTest" --console=plain` → FAIL to compile.

- [ ] **Step 3: Implement `ImagesConfig`** (mirror `TaskQueueConfig`):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "images", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ImagesConfig {
    @WithDefault("/var/lib/pinry/images")
    fun dataDir(): String

    @WithDefault("31457280") // 30 MiB
    fun maxFileBytes(): Long

    @WithDefault("50000000") // 50 megapixels
    fun maxPixels(): Long
}
```

- [ ] **Step 4: Run the test.** Expected: PASS.

- [ ] **Step 5: Add explicit defaults + the multipart backstop to `application.properties`:**
```properties
# Image hosting (ImagesConfig uses the SNAKE_CASE naming strategy: underscores, not dashes)
images.data_dir=/var/lib/pinry/images
images.max_file_bytes=31457280
images.max_pixels=50000000
# Multipart backstop: allow bodies slightly above images.max_file_bytes so the
# precise 30 MiB limit is enforced by the use case (413), not the framework.
quarkus.http.limits.max-body-size=32M
```

- [ ] **Step 6: Commit.**
```bash
git add api-presentation-quarkus/src/main/kotlin/.../config/ImagesConfig.kt api-presentation-quarkus/src/test/.../config/ImagesConfigTest.kt api-application/src/main/resources/application.properties
git commit -m "feat(images): add ImagesConfig (data dir, size and pixel limits)"
```

---

## Phase 1 — Domain

### Task 3: `ImageFormat`

**Files:**
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/enums/ImageFormat.kt`
- Test: `api-domain/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/enums/ImageFormatTest.kt`

**Interfaces:**
- Produces: `enum class ImageFormat(val mimeType: String, val extension: String)` with `PNG`, `JPEG`, `WEBP`, `GIF`.

- [ ] **Step 1: Write the failing test:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImageFormatTest {
    @Test
    fun `Given each format, Then it exposes its mime type and extension`() {
        assertEquals("image/png" to "png", ImageFormat.PNG.mimeType to ImageFormat.PNG.extension)
        assertEquals("image/jpeg" to "jpg", ImageFormat.JPEG.mimeType to ImageFormat.JPEG.extension)
        assertEquals("image/webp" to "webp", ImageFormat.WEBP.mimeType to ImageFormat.WEBP.extension)
        assertEquals("image/gif" to "gif", ImageFormat.GIF.mimeType to ImageFormat.GIF.extension)
    }
}
```

- [ ] **Step 2: Run to fail.** Run: `./gradlew :api-domain:test --tests "*ImageFormatTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.enums

enum class ImageFormat(
    val mimeType: String,
    val extension: String,
) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif"),
}
```

- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(domain): add ImageFormat enum"`

### Task 4: `Image` entity + `Pin` changes

**Files:**
- Create: `api-domain/.../domain/entities/Image.kt`
- Modify: `api-domain/.../domain/entities/Pin.kt`
- Test: `api-domain/.../domain/entities/ImageTest.kt`

**Interfaces:**
- Produces: `data class Image(id, pinId, mimeType, width, height, byteSize, contentHash, storageKey, createdAt) : Identifiable`.
- Produces: `Pin.sourceMediaUrl: String?` (nullable), `Pin.image: Image? = null`.

- [ ] **Step 1: Write the failing test:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ImageTest {
    @Test
    fun `Given image data, Then the entity exposes it and is Identifiable`() {
        // Given
        val id = randomUUID()
        val pinId = randomUUID()
        val now = Instant.parse("2026-07-08T00:00:00Z")
        // When
        val image = Image(
            id = id, pinId = pinId, mimeType = "image/webp",
            width = 800, height = 600, byteSize = 12_345L,
            contentHash = "abc123", storageKey = "originals/u/p/$id.webp", createdAt = now,
        )
        // Then
        assertEquals(id, image.id)
        assertEquals(pinId, image.pinId)
        assertEquals(800, image.width)
        assertEquals("abc123", image.contentHash)
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-domain:test --tests "*ImageTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `Image.kt`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class Image(
    override val id: UUID,
    val pinId: UUID,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val contentHash: String,
    val storageKey: String,
    val createdAt: Instant,
) : Identifiable
```

- [ ] **Step 4: Modify `Pin.kt`** — make `sourceMediaUrl` nullable and add `image`:
```kotlin
data class Pin(
    override val id: UUID,
    val author: User,
    val sourceContextUrl: String,
    val sourceMediaUrl: String?,
    val description: String,
    val tags: List<Tag>,
    val softDeletedAt: Instant? = null,
    val image: Image? = null,
) : Identifiable
```

- [ ] **Step 5: Fix the ripple.** `sourceMediaUrl` is now `String?`. Compile the whole project to find call sites: `./gradlew compileKotlin compileTestKotlin --console=plain`. Update `PinCreator.createPin` to accept `sourceMediaUrl: String?`; update `PinModel`/`PinModelMapper` (make the column/mapping nullable — see Task 6 note); update `PinOutputDto.sourceMediaUrl` to `String?` and `PinMapper.toDto`; update `PinCreationInputDto` — keep `sourceMediaUrl` required for the JSON create endpoint (a bookmarked pin still supplies it), so `PinController.createPin` passes a non-null value; the nullability only matters for image-only pins created later. Keep changes minimal: only widen types, do not change behavior.

- [ ] **Step 6: Run the full domain + affected tests.** `./gradlew :api-domain:test :api-usecases:test --console=plain` → PASS (existing tests still green with the widened type).

- [ ] **Step 7: Commit.**
```bash
git add -A
git commit -m "feat(domain): add Image entity; make Pin.sourceMediaUrl nullable and add Pin.image"
```

### Task 5: Domain ports — `ImageStore`, `ImageProbe`, `ImageRepositoryInterface`, probe exceptions

**Files:**
- Create: `api-domain/.../domain/images/ImageStore.kt`
- Create: `api-domain/.../domain/images/ImageProbe.kt`
- Create: `api-domain/.../domain/images/ImageProbeException.kt`
- Create: `api-domain/.../domain/repositories/ImageRepositoryInterface.kt`
- Test: `api-domain/.../domain/images/ImagePortsTest.kt`

**Interfaces (Produces — used by later tasks verbatim):**
```kotlin
// StagedFile: opaque local staging reference + measured size + content hash.
data class StagedFile(val path: String, val byteSize: Long, val contentHash: String)

interface ImageStore {
    /** Stream [source] into a fresh temp file under the data dir, aborting past [maxBytes]
     *  (throws ImageTooLargeException), computing byteSize + SHA-256 in one pass. */
    fun stage(source: InputStream, maxBytes: Long): StagedFile
    /** Move a staged temp file to [storageKey] (a fresh path). */
    fun promote(staged: StagedFile, storageKey: String)
    /** Open a read stream for a stored key. */
    fun openStream(storageKey: String): InputStream
    /** Delete [storageKey] if present (idempotent). */
    fun delete(storageKey: String)
    /** Delete a staged temp file (cleanup on failure; idempotent). */
    fun discard(staged: StagedFile)
}

data class ProbeResult(val format: ImageFormat, val width: Int, val height: Int)

interface ImageProbe {
    /** Validate + measure the staged file. Reject over [maxPixels]. Throws on unsupported/undecodable. */
    fun probe(staged: StagedFile, maxPixels: Long): ProbeResult
}

interface ImageRepositoryInterface {
    fun save(image: Image): Image            // create-or-replace by pinId, single transaction
    fun findByPinId(pinId: UUID): Image?
    fun deleteByPinId(pinId: UUID)
}
```
Exceptions (domain):
```kotlin
sealed class ImageProbeException(message: String) : Exception(message)
class UnsupportedImageFormatException(message: String) : ImageProbeException(message)
class UndecodableImageException(message: String) : ImageProbeException(message)
class ImageTooManyPixelsException(message: String) : ImageProbeException(message)
// Size guard is raised by ImageStore.stage:
class ImageTooLargeException(message: String) : Exception(message)
```

- [ ] **Step 1: Write the failing test** (ports are interfaces; test that a hand-written fake satisfies each contract and that the exception hierarchy is as expected — this locks signatures):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ImagePortsTest {
    @Test
    fun `Given a StagedFile, Then it carries path, size and hash`() {
        val staged = StagedFile(path = "/tmp/x", byteSize = 3, contentHash = "h")
        assertEquals("/tmp/x", staged.path)
        assertEquals(3, staged.byteSize)
    }

    @Test
    fun `Given a fake store and probe, Then the port contracts compile and return`() {
        val store = object : ImageStore {
            override fun stage(source: InputStream, maxBytes: Long) =
                StagedFile("/tmp/staged", source.readBytes().size.toLong(), "hash")
            override fun promote(staged: StagedFile, storageKey: String) {}
            override fun openStream(storageKey: String): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun delete(storageKey: String) {}
            override fun discard(staged: StagedFile) {}
        }
        val probe = object : ImageProbe {
            override fun probe(staged: StagedFile, maxPixels: Long) =
                ProbeResult(ImageFormat.PNG, 10, 20)
        }
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 100)
        val result = probe.probe(staged, 1_000)
        assertEquals(3, staged.byteSize)
        assertEquals(ImageFormat.PNG, result.format)
        assertEquals(20, result.height)
    }

    @Test
    fun `Given probe exceptions, Then they share the sealed base`() {
        assertTrue(UnsupportedImageFormatException("x") is ImageProbeException)
        assertTrue(UndecodableImageException("x") is ImageProbeException)
        assertTrue(ImageTooManyPixelsException("x") is ImageProbeException)
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-domain:test --tests "*ImagePortsTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement the ports and exceptions** exactly as in the Interfaces block above, in the listed files. `ImageStore.kt` and `ImageProbe.kt` import `java.io.InputStream`, `ImageFormat`, `Image`, `java.util.UUID` as needed. `ImageProbeException.kt` holds the sealed hierarchy plus `ImageTooLargeException`.

- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Run `:api-domain` detekt + coverage** (ports/exceptions are simple; a fake in the test exercises them). Run: `./gradlew :api-domain:detekt :api-domain:koverVerify --console=plain` → PASS. If Kover flags an uncovered branch (e.g. a `when`), add an assertion.
- [ ] **Step 6: Commit.** `git commit -am "feat(domain): add ImageStore, ImageProbe and ImageRepository ports"`

---

## Phase 2 — Persistence

### Task 6: `ImageModel` + mapper + migration 1.4

**Files:**
- Create: `api-persistence-sqlite/.../models/ImageModel.kt`
- Create: `api-persistence-sqlite/.../mappers/ImageModelMapper.kt`
- Modify: `api-persistence-sqlite/.../models/PinModel.kt` (nullable `sourceMediaUrl`)
- Modify: `api-persistence-sqlite/.../mappers/PinModelMapper.kt` (nullable ripple)
- Create: `api-persistence-sqlite/src/main/resources/dbmigration/1.4.sql` + `model/1.4.model.xml`
- Test: `api-persistence-sqlite/.../mappers/ImageModelMapperTest.kt`

**Interfaces:**
- Produces: `ImageModel` (`images` table, plain `pinId: UUID` unique column, explicit `createdAt`), and `ImageModelMapper.toDomain()/toModel()`.

- [ ] **Step 1: Write the failing mapper test:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ImageModelMapperTest {
    @Test
    fun `Given an image, Then toModel and toDomain round-trip its fields`() {
        // Given
        val image = Image(
            id = randomUUID(), pinId = randomUUID(), mimeType = "image/png",
            width = 4, height = 5, byteSize = 6, contentHash = "h",
            storageKey = "originals/a/b/c.png", createdAt = Instant.parse("2026-07-08T00:00:00Z"),
        )
        // When
        val roundTripped = image.toModel().toDomain()
        // Then
        assertEquals(image, roundTripped)
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-persistence-sqlite:test --tests "*ImageModelMapperTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `ImageModel`** (standalone entity with explicit `createdAt`, plain unique `pinId` column):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "images")
class ImageModel(
    @Id var id: UUID,
    @Column(unique = true) var pinId: UUID,
    var mimeType: String,
    var width: Int,
    var height: Int,
    var byteSize: Long,
    var contentHash: String,
    var storageKey: String,
    var createdAt: Instant,
)
```

- [ ] **Step 4: Implement `ImageModelMapper`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.ImageModel

object ImageModelMapper {
    fun Image.toModel() = ImageModel(
        id = id, pinId = pinId, mimeType = mimeType, width = width, height = height,
        byteSize = byteSize, contentHash = contentHash, storageKey = storageKey, createdAt = createdAt,
    )

    fun ImageModel.toDomain() = Image(
        id = id, pinId = pinId, mimeType = mimeType, width = width, height = height,
        byteSize = byteSize, contentHash = contentHash, storageKey = storageKey, createdAt = createdAt,
    )
}
```

- [ ] **Step 5: Make `PinModel.sourceMediaUrl` nullable** (`var sourceMediaUrl: String?`) and update `PinModelMapper` accordingly (`sourceMediaUrl = sourceMediaUrl`, both directions already pass through — just the type widens).

- [ ] **Step 6: Run the mapper test.** Expected: PASS.

- [ ] **Step 7: Generate the migration.** Run: `./gradlew :api-persistence-sqlite:generateDbMigration --console=plain`. This emits `dbmigration/1.4.sql` + `model/1.4.model.xml` for the new `images` table and the `pins.source_media_url` nullability change.

- [ ] **Step 8: Hand-add the FK + confirm the unique constraint** in `dbmigration/1.4.sql` (established practice — cf. the hand-authored `1.2.sql` and the partial indexes in `1.3.sql`). The generated create-table for `images` must end up equivalent to:
```sql
create table images (
  id            uuid not null,
  pin_id        uuid not null,
  mime_type     text not null,
  width         integer not null,
  height        integer not null,
  byte_size     bigint not null,
  content_hash  text not null,
  storage_key   text not null,
  created_at    timestamp not null,
  constraint pk_images primary key (id),
  constraint uq_images_pin_id unique (pin_id),
  foreign key (pin_id) references pins (id) on delete restrict on update restrict
);
```
And the `source_media_url` change (SQLite cannot drop NOT NULL in place; Ebean's sqlite platform typically emits a table rebuild — verify the generated SQL widens `source_media_url` to nullable; if the generator leaves it, hand-write the rebuild). Keep `model/1.4.model.xml` in sync where the generator produced it.

- [ ] **Step 9: Verify migrations still bootstrap** (the in-memory test DB runs all migrations at startup). Run: `./gradlew :api-persistence-sqlite:test --tests "*ImageModelMapperTest" --console=plain` again — the in-memory DB now applies `1.4`. Expected: PASS (no migration error at bootstrap).

- [ ] **Step 10: Commit.**
```bash
git add -A
git commit -m "feat(persistence): add images table (migration 1.4), ImageModel and mapper"
```

### Task 7: `EbeanImageRepository`

**Files:**
- Create: `api-persistence-sqlite/.../repositories/EbeanImageRepository.kt`
- Test: `api-persistence-sqlite/src/test/kotlin/.../repositories/EbeanImageRepositoryTest.kt`

**Interfaces:**
- Consumes: `ImageRepositoryInterface`, `Image`, `QImageModel(database)` (kapt-generated once `ImageModel` exists).
- Produces: `@ApplicationScoped class EbeanImageRepository(database: Database) : ImageRepositoryInterface`.

- [ ] **Step 1: Write the failing test** (extends `RepositoryTest`; seed a user + pin FK first):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class EbeanImageRepositoryTest : RepositoryTest() {
    private val repository = EbeanImageRepository(database)
    private val userRepository = UserRepository(database)
    private val pinRepository = PinRepository(database)

    private fun savedPin(): Pin {
        val user = userRepository.saveUser(User(randomUUID(), createRandomString()))
        return pinRepository.savePin(
            Pin(randomUUID(), user, "https://ctx", null, "desc", emptyList()),
        )
    }

    private fun imageFor(pinId: java.util.UUID, hash: String = "h") = Image(
        id = randomUUID(), pinId = pinId, mimeType = "image/png", width = 1, height = 1,
        byteSize = 1, contentHash = hash, storageKey = "originals/x/$pinId/i.png",
        createdAt = Instant.parse("2026-07-08T00:00:00Z"),
    )

    @Test
    fun `Given a new image, Then save persists it and findByPinId returns it`() {
        val pin = savedPin()
        val saved = repository.save(imageFor(pin.id))
        assertEquals(saved, repository.findByPinId(pin.id))
    }

    @Test
    fun `Given a pin already imaged, Then save replaces the row (unique pin_id)`() {
        val pin = savedPin()
        repository.save(imageFor(pin.id, hash = "old"))
        val replacement = repository.save(imageFor(pin.id, hash = "new"))
        assertEquals("new", repository.findByPinId(pin.id)?.contentHash)
        assertEquals(replacement, repository.findByPinId(pin.id))
    }

    @Test
    fun `Given no image for a pin, Then findByPinId returns null`() {
        assertNull(repository.findByPinId(randomUUID()))
    }

    @Test
    fun `Given an image, Then deleteByPinId removes it`() {
        val pin = savedPin()
        repository.save(imageFor(pin.id))
        repository.deleteByPinId(pin.id)
        assertNull(repository.findByPinId(pin.id))
    }

    @Test
    fun `Given no image, Then deleteByPinId is a no-op`() {
        repository.deleteByPinId(randomUUID()) // must not throw
        assertNull(repository.findByPinId(randomUUID()))
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-persistence-sqlite:test --tests "*EbeanImageRepositoryTest" --console=plain` → FAIL (unresolved `EbeanImageRepository`; `QImageModel` is generated by kapt on first compile of `ImageModel`).

- [ ] **Step 3: Implement `EbeanImageRepository`** (database-arg query beans + explicit transaction for replace, like `EbeanTaskQueue`):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.ImageModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QImageModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class EbeanImageRepository(
    private val database: Database,
) : ImageRepositoryInterface {
    override fun save(image: Image): Image =
        database.beginTransaction().use { transaction ->
            QImageModel(database).pinId.equalTo(image.pinId).delete()
            val model = image.toModel()
            database.save(model)
            transaction.commit()
            model.toDomain()
        }

    override fun findByPinId(pinId: UUID): Image? =
        QImageModel(database).pinId.equalTo(pinId).findOne()?.toDomain()

    override fun deleteByPinId(pinId: UUID) {
        QImageModel(database).pinId.equalTo(pinId).delete()
    }
}
```

- [ ] **Step 4: Run the tests.** Expected: PASS (all five).
- [ ] **Step 5: Coverage + detekt.** `./gradlew :api-persistence-sqlite:detekt :api-persistence-sqlite:koverVerify --console=plain` → PASS (the `models` + `models.query` packages are already Kover-excluded; the repository package must hit 100% branch — the five tests cover save/replace/find-hit/find-miss/delete-noop).
- [ ] **Step 6: Commit.** `git commit -am "feat(persistence): add EbeanImageRepository (save/replace/find/delete by pin)"`

---

## Phase 3 — Storage adapter (`api-storage-filesystem`)

### Task 8: `FilesystemImageStore`

**Files:**
- Create: `api-storage-filesystem/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/storage/filesystem/FilesystemImageStore.kt`
- Test: `api-storage-filesystem/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/storage/filesystem/FilesystemImageStoreTest.kt`

**Interfaces:**
- Consumes: `ImageStore`, `StagedFile`, `ImageTooLargeException`.
- Produces: `@ApplicationScoped class FilesystemImageStore(dataDir: String) : ImageStore`. The `dataDir` is injected in presentation via a producer (Task 16). Constructor takes a plain `String` so the module stays framework-light and unit-testable with a temp dir.

Design notes for the implementer:
- `<dataDir>/tmp/` and `<dataDir>/originals/` are created on demand.
- `stage`: create a temp file under `tmp/`, stream `source` into it through a `DigestInputStream`/manual SHA-256, counting bytes; if the running count exceeds `maxBytes`, close, delete the temp, and throw `ImageTooLargeException`. `fsync` the temp file (`FileChannel.force(true)`), return `StagedFile(tempPath, byteSize, sha256Hex)`.
- `promote`: resolve `<dataDir>/<storageKey>`, create parent dirs, `Files.move(tempPath, dest, StandardCopyOption.ATOMIC_MOVE)`, falling back to a plain `Files.move` if `AtomicMoveNotSupportedException` is thrown (correctness does not depend on atomicity; temp is under the same data dir so it is normally atomic).
- `openStream`: `Files.newInputStream(<dataDir>/<storageKey>)`.
- `delete` / `discard`: `Files.deleteIfExists(...)` (idempotent).
- Guard `storageKey` against traversal: reject a key containing `..` (defence in depth; keys are server-generated).

- [ ] **Step 1: Write the failing tests:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class FilesystemImageStoreTest {
    @TempDir lateinit var dataDir: Path

    private fun store() = FilesystemImageStore(dataDir.toString())

    @Test
    fun `Given bytes within the limit, Then stage measures size and hash`() {
        val staged = store().stage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 100)
        assertEquals(4, staged.byteSize)
        // SHA-256 of {1,2,3,4}
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", staged.contentHash)
        assertTrue(Files.exists(Path.of(staged.path)))
    }

    @Test
    fun `Given bytes over the limit, Then stage throws and leaves no temp file`() {
        val store = store()
        val before = Files.list(dataDir.resolve("tmp")).use { it.count() }.takeIf { Files.exists(dataDir.resolve("tmp")) } ?: 0
        assertThrows(ImageTooLargeException::class.java) {
            store.stage(ByteArrayInputStream(ByteArray(50)), maxBytes = 10)
        }
        val after = if (Files.exists(dataDir.resolve("tmp"))) Files.list(dataDir.resolve("tmp")).use { it.count() } else 0
        assertEquals(before, after)
    }

    @Test
    fun `Given a staged file, Then promote moves it to the storage key and openStream reads it`() {
        val store = store()
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(9, 9)), maxBytes = 100)
        store.promote(staged, "originals/u/p/img.png")
        assertFalse(Files.exists(Path.of(staged.path)))
        val read = store.openStream("originals/u/p/img.png").use { it.readBytes() }
        assertTrue(read.contentEquals(byteArrayOf(9, 9)))
    }

    @Test
    fun `Given a stored key, Then delete removes it and is idempotent`() {
        val store = store()
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(1)), maxBytes = 100)
        store.promote(staged, "originals/u/p/img.png")
        store.delete("originals/u/p/img.png")
        store.delete("originals/u/p/img.png") // idempotent, must not throw
        assertFalse(Files.exists(dataDir.resolve("originals/u/p/img.png")))
    }

    @Test
    fun `Given a staged file, Then discard removes the temp`() {
        val store = store()
        val staged = store.stage(ByteArrayInputStream(byteArrayOf(1)), maxBytes = 100)
        store.discard(staged)
        assertFalse(Files.exists(Path.of(staged.path)))
    }

    @Test
    fun `Given a storage key with traversal, Then it is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            store().openStream("originals/../../etc/passwd")
        }
    }
}
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-storage-filesystem:test --console=plain` → FAIL (unresolved class).

- [ ] **Step 3: Implement `FilesystemImageStore`** following the design notes above. Key details: use `java.security.MessageDigest.getInstance("SHA-256")`, stream in a buffer while updating the digest and counting bytes, abort past `maxBytes`. Hex-encode the digest. Reject any `storageKey` containing `..` with `IllegalArgumentException`. Keep helpers as **private members** (no top-level functions).

- [ ] **Step 4: Run the tests.** Expected: PASS (all six). (Verify the SHA-256 constant in Step 1 with `printf '\x01\x02\x03\x04' | sha256sum` if it fails.)

- [ ] **Step 5: Coverage + detekt.** `./gradlew :api-storage-filesystem:detekt :api-storage-filesystem:koverVerify --console=plain` → PASS. The over-limit, idempotent-delete, atomic-move-fallback, and traversal branches all need a test — add one if Kover reports a gap (e.g. the `AtomicMoveNotSupportedException` fallback is hard to trigger on one filesystem; if unreachable in tests, extract it behind a small `private fun move(...)` and cover the primary path, then `@Suppress` with a comment, or accept the branch is defensively unreachable and document it).

- [ ] **Step 6: Commit.** `git commit -am "feat(storage): FilesystemImageStore (stage with size+hash, promote, serve, delete)"`

---

## Phase 4 — Imaging adapter (`api-imaging-vips`)

### Task 9: `VipsImageProbe`

**Files:**
- Create: `api-imaging-vips/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/imaging/vips/VipsImageProbe.kt`
- Test: `api-imaging-vips/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/imaging/vips/VipsImageProbeTest.kt`
- Test fixtures: `api-imaging-vips/src/test/resources/fixtures/{sample.png,sample.jpg,sample.webp,animated.webp,animated.gif,not-an-image.txt,bomb.png}`

**Interfaces:**
- Consumes: `ImageProbe`, `StagedFile`, `ProbeResult`, `ImageFormat`, the probe exceptions.
- Produces: `@ApplicationScoped class VipsImageProbe : ImageProbe`.

**Prerequisite:** native `libvips` must be installed to run these tests (`sudo pacman -S libvips` / `apt-get install libvips42`). vips-ffm needs `--enable-native-access=ALL-UNNAMED` (already granted to `Test` tasks in the root `build.gradle.kts`).

Design notes:
- Use `Vips.run { ... }` to scope an arena; load with `VImage.newFromFile(arena, staged.path, VipsOption.Boolean("access", ...))` (sequential access to avoid full-raster load). Confirm the exact vips-ffm API for: (a) loading a file, (b) reading `width`/`height`, (c) determining the source loader/format, via **context7** (`/lopcode/vips-ffm`) at implementation time.
- Format detection: read the image's loader (e.g. the `vips-loader` header field via `image.getString("vips-loader")`, which yields values like `pngload`, `jpegload`, `webpload`, `gifload`) and map to `ImageFormat`. An unrecognised loader → `UnsupportedImageFormatException`.
- Pixel guard: `width.toLong() * height.toLong() > maxPixels` → `ImageTooManyPixelsException` (checked from the header before any heavy processing; libvips is lazy so this is cheap).
- Any libvips error while opening/reading → catch and rethrow as `UndecodableImageException`.
- Keep the loader→format mapping as a `private fun` member or a `when`.

- [ ] **Step 1: Create fixtures.** Add small valid images (a few px) for each format, one animated WebP, one animated GIF, a `not-an-image.txt`, and a `bomb.png` whose header declares huge dimensions (or reuse a modestly large real image and set `maxPixels` low in the test to trigger the guard without a real bomb).

- [ ] **Step 2: Write the failing tests:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.imaging.vips

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooManyPixelsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UndecodableImageException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UnsupportedImageFormatException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

class VipsImageProbeTest {
    private val probe = VipsImageProbe()
    private fun staged(name: String) =
        StagedFile(path = Path.of("src/test/resources/fixtures", name).toString(), byteSize = 0, contentHash = "")

    @Test fun `Given a PNG, Then probe returns PNG with its dimensions`() {
        val r = probe.probe(staged("sample.png"), maxPixels = 1_000_000)
        assertEquals(ImageFormat.PNG, r.format)
    }
    @Test fun `Given a JPEG, Then probe returns JPEG`() {
        assertEquals(ImageFormat.JPEG, probe.probe(staged("sample.jpg"), 1_000_000).format)
    }
    @Test fun `Given a WebP, Then probe returns WEBP`() {
        assertEquals(ImageFormat.WEBP, probe.probe(staged("sample.webp"), 1_000_000).format)
    }
    @Test fun `Given an animated WebP, Then probe accepts it as WEBP`() {
        assertEquals(ImageFormat.WEBP, probe.probe(staged("animated.webp"), 1_000_000).format)
    }
    @Test fun `Given an animated GIF, Then probe accepts it as GIF`() {
        assertEquals(ImageFormat.GIF, probe.probe(staged("animated.gif"), 1_000_000).format)
    }
    @Test fun `Given a non-image, Then probe throws UndecodableImageException`() {
        assertThrows(UndecodableImageException::class.java) { probe.probe(staged("not-an-image.txt"), 1_000_000) }
    }
    @Test fun `Given an image over the pixel limit, Then probe throws ImageTooManyPixelsException`() {
        assertThrows(ImageTooManyPixelsException::class.java) { probe.probe(staged("sample.png"), maxPixels = 1) }
    }
}
```
(An `UnsupportedImageFormatException` test needs a decodable-but-unsupported format, e.g. a TIFF fixture; add `sample.tiff` and assert it throws `UnsupportedImageFormatException` to cover that branch.)

- [ ] **Step 3: Run to fail.** `./gradlew :api-imaging-vips:test --console=plain` → FAIL (unresolved class; if libvips is missing, install it first).

- [ ] **Step 4: Implement `VipsImageProbe`** per the design notes, confirming the vips-ffm API via context7. Map loaders → `ImageFormat`; unsupported → `UnsupportedImageFormatException`; libvips errors → `UndecodableImageException`; over-pixel → `ImageTooManyPixelsException`.

- [ ] **Step 5: Run the tests.** Expected: PASS. If animated WebP fails to load, re-check the libvips build has WebP+demux support and the vips-ffm version; this is the spec's flagged risk — surface it if unresolved.

- [ ] **Step 6: Coverage + detekt.** `./gradlew :api-imaging-vips:detekt :api-imaging-vips:koverVerify --console=plain` → PASS (each format branch, the unsupported branch, the undecodable branch, and the pixel-guard branch are all exercised).

- [ ] **Step 7: Commit.** `git commit -am "feat(imaging): VipsImageProbe (validate + measure via libvips)"`

---

## Phase 5 — Use cases

### Task 10: Image error codes + `SetPinImage`

**Files:**
- Modify: `api-usecases/.../usecases/exceptions/ErrorCode.kt`
- Create: `api-usecases/.../usecases/exceptions/ImageError.kt`
- Create: `api-usecases/.../usecases/SetPinImage.kt`
- Test: `api-usecases/src/test/kotlin/.../usecases/SetPinImageTest.kt`

**Interfaces:**
- Consumes: `PinRepositoryInterface`, `ImageRepositoryInterface`, `ImageStore`, `ImageProbe`, `Clock`, `Image`, probe exceptions, `ImageTooLargeException`.
- Produces: `class SetPinImage(...) { fun set(pinId: UUID, requester: User, upload: InputStream, maxBytes: Long, maxPixels: Long): Image }`; new `ErrorCode`s `IMAGE_DOES_NOT_EXIST`, `IMAGE_INSUFFICIENT_PERMISSIONS`, `IMAGE_TOO_LARGE`, `IMAGE_INVALID`; `ImageError` `BaseError` subclasses.

Design notes:
- Authz: load the pin (`pinRepository.findPinById`), 404 if absent (`ImagePinDoesNotExistError`), 403 if `pin.author.id != requester.id` (`ImagePermissionError`).
- Orchestration with cleanup: `stage` (may throw `ImageTooLargeException` → `ImageTooLargeError`); then `try { probe } catch (ImageProbeException) { store.discard(staged); throw mapped }`. On probe success build the `Image`, capture `existing = imageRepository.findByPinId(pinId)`, `store.promote`, `imageRepository.save`, then best-effort `existing?.let { store.delete(it.storageKey) }`. If `promote`/`save` throws, `store.discard(staged)` and rethrow.
- `storageKey = "originals/${requester.id}/$pinId/$imageId.${format.extension}"`.
- `createdAt = clock.now()`.
- The `maxBytes`/`maxPixels` are passed in by the controller (from `ImagesConfig`), keeping the use case free of config types.

- [ ] **Step 1: Add error codes.** In `ErrorCode.kt` add `IMAGE_DOES_NOT_EXIST, IMAGE_INSUFFICIENT_PERMISSIONS, IMAGE_TOO_LARGE, IMAGE_INVALID` to the enum. (This will break `BaseErrorMapper`'s exhaustive `when` — fixed in Task 13.)

- [ ] **Step 2: Add `ImageError.kt`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class ImageError(message: String, code: ErrorCode, cause: Throwable? = null) : BaseError(message, code, cause)
class ImagePinDoesNotExistError : ImageError("Pin does not exist", ErrorCode.IMAGE_DOES_NOT_EXIST)
class ImagePermissionError : ImageError("Insufficient permissions", ErrorCode.IMAGE_INSUFFICIENT_PERMISSIONS)
class ImageDoesNotExistError : ImageError("Pin has no image", ErrorCode.IMAGE_DOES_NOT_EXIST)
class ImageTooLargeError(cause: Throwable? = null) : ImageError("Image exceeds the maximum size", ErrorCode.IMAGE_TOO_LARGE, cause)
class ImageInvalidError(message: String, cause: Throwable? = null) : ImageError(message, ErrorCode.IMAGE_INVALID, cause)
```

- [ ] **Step 3: Write the failing test** (MockK; extends `BaseTest`):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.*
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.*
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.*
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID.randomUUID

class SetPinImageTest : BaseTest() {
    private val pins = mockk<PinRepositoryInterface>()
    private val images = mockk<ImageRepositoryInterface>(relaxed = true)
    private val store = mockk<ImageStore>(relaxed = true)
    private val probe = mockk<ImageProbe>()
    private val clock = mockk<Clock>()
    private val useCase = SetPinImage(pins, images, store, probe, clock)

    private val owner = User(randomUUID(), createRandomString())
    private fun pin(author: User = owner) = Pin(randomUUID(), author, "https://c", null, "d", emptyList())
    private fun upload() = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    private val staged = StagedFile("/tmp/s", 3, "hash")

    @Test fun `Given a valid upload by the owner, Then it stores and persists a new image`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.PNG, 4, 5)
        every { images.findByPinId(p.id) } returns null
        every { clock.now() } returns Instant.parse("2026-07-08T00:00:00Z")
        every { images.save(any()) } answers { firstArg() }

        val result = useCase.set(p.id, owner, upload(), maxBytes = 30, maxPixels = 50)

        assertEquals(p.id, result.pinId)
        assertEquals("image/png", result.mimeType)
        assertTrue(result.storageKey.startsWith("originals/${owner.id}/${p.id}/"))
        verify { store.promote(staged, result.storageKey) }
        verify { images.save(result) }
    }

    @Test fun `Given a replacement, Then the old file is deleted after commit`() {
        val p = pin()
        val old = Image(randomUUID(), p.id, "image/png", 1, 1, 1, "old", "originals/o/old.png", Instant.EPOCH)
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.WEBP, 2, 2)
        every { images.findByPinId(p.id) } returns old
        every { clock.now() } returns Instant.EPOCH
        every { images.save(any()) } answers { firstArg() }

        useCase.set(p.id, owner, upload(), 30, 50)

        verify { store.delete("originals/o/old.png") }
    }

    @Test fun `Given a missing pin, Then it throws ImagePinDoesNotExistError`() {
        every { pins.findPinById(any()) } returns null
        assertThrows(ImagePinDoesNotExistError::class.java) { useCase.set(randomUUID(), owner, upload(), 30, 50) }
    }

    @Test fun `Given a non-owner, Then it throws ImagePermissionError`() {
        val p = pin(author = User(randomUUID(), createRandomString()))
        every { pins.findPinById(p.id) } returns p
        assertThrows(ImagePermissionError::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }
    }

    @Test fun `Given an oversize upload, Then it throws ImageTooLargeError`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } throws ImageTooLargeException("too big")
        assertThrows(ImageTooLargeError::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }
    }

    @Test fun `Given an undecodable upload, Then it discards the temp and throws ImageInvalidError`() {
        val p = pin()
        every { pins.findPinById(p.id) } returns p
        every { store.stage(any(), 30) } returns staged
        every { probe.probe(staged, 50) } throws UndecodableImageException("nope")
        assertThrows(ImageInvalidError::class.java) { useCase.set(p.id, owner, upload(), 30, 50) }
        verify { store.discard(staged) }
    }
}
```

- [ ] **Step 4: Run to fail.** `./gradlew :api-usecases:test --tests "*SetPinImageTest" --console=plain` → FAIL.

- [ ] **Step 5: Implement `SetPinImage`:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageTooLargeError
import jakarta.enterprise.context.ApplicationScoped
import java.io.InputStream
import java.util.UUID
import java.util.UUID.randomUUID

@ApplicationScoped
class SetPinImage(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageStore: ImageStore,
    private val imageProbe: ImageProbe,
    private val clock: Clock,
) {
    fun set(pinId: UUID, requester: User, upload: InputStream, maxBytes: Long, maxPixels: Long): Image {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()

        val staged = try {
            imageStore.stage(upload, maxBytes)
        } catch (e: ImageTooLargeException) {
            throw ImageTooLargeError(e)
        }

        val probeResult = try {
            imageProbe.probe(staged, maxPixels)
        } catch (e: ImageProbeException) {
            imageStore.discard(staged)
            throw ImageInvalidError(e.message ?: "Invalid image", e)
        }

        val imageId = randomUUID()
        val storageKey = "originals/${requester.id}/$pinId/$imageId.${probeResult.format.extension}"
        val image = Image(
            id = imageId, pinId = pinId, mimeType = probeResult.format.mimeType,
            width = probeResult.width, height = probeResult.height, byteSize = staged.byteSize,
            contentHash = staged.contentHash, storageKey = storageKey, createdAt = clock.now(),
        )
        val existing = imageRepository.findByPinId(pinId)
        try {
            imageStore.promote(staged, storageKey)
            val saved = imageRepository.save(image)
            existing?.let { imageStore.delete(it.storageKey) }
            return saved
        } catch (e: RuntimeException) {
            imageStore.discard(staged)
            throw e
        }
    }
}
```

- [ ] **Step 6: Run.** Expected: PASS.
- [ ] **Step 7: Coverage.** `./gradlew :api-usecases:koverVerify --console=plain` → the six tests cover 404/403/too-large/invalid/create/replace. Add a test for the `promote`/`save` failure→discard branch if Kover flags it (`every { store.promote(...) } throws RuntimeException(...)`, assert `store.discard` is called).
- [ ] **Step 8: Commit.** `git commit -am "feat(usecases): SetPinImage (validate, store and persist a canonical image)"`

### Task 11: `GetPinImage` + `DeletePinImage`

**Files:**
- Create: `api-usecases/.../usecases/GetPinImage.kt`
- Create: `api-usecases/.../usecases/DeletePinImage.kt`
- Test: `api-usecases/.../usecases/GetPinImageTest.kt`, `.../usecases/DeletePinImageTest.kt`

**Interfaces:**
- Produces: `GetPinImage.get(pinId, requester): Image` (403/404); `DeletePinImage.delete(pinId, requester)` (403/404, removes row + best-effort file).

- [ ] **Step 1: Write the failing `GetPinImageTest`** — cases: owner + image → returns it; missing pin → `ImagePinDoesNotExistError`; non-owner → `ImagePermissionError`; pin without image → `ImageDoesNotExistError`.
```kotlin
// structure mirrors SetPinImageTest; collaborators: pins, images (both mockk)
// useCase = GetPinImage(pins, images)
@Test fun `Given the owner and an image, Then get returns it`() {
    val p = pin(); val img = imageFor(p.id)
    every { pins.findPinById(p.id) } returns p
    every { images.findByPinId(p.id) } returns img
    assertEquals(img, useCase.get(p.id, owner))
}
@Test fun `Given a pin without an image, Then get throws ImageDoesNotExistError`() {
    val p = pin()
    every { pins.findPinById(p.id) } returns p
    every { images.findByPinId(p.id) } returns null
    assertThrows(ImageDoesNotExistError::class.java) { useCase.get(p.id, owner) }
}
// + missing-pin (404) and non-owner (403) cases
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-usecases:test --tests "*GetPinImageTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `GetPinImage`:**
```kotlin
@ApplicationScoped
class GetPinImage(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
) {
    fun get(pinId: UUID, requester: User): Image {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()
        return imageRepository.findByPinId(pinId) ?: throw ImageDoesNotExistError()
    }
}
```

- [ ] **Step 4: Write the failing `DeletePinImageTest`** — cases: owner + image → deletes row then best-effort file; missing pin → 404; non-owner → 403; pin without image → `ImageDoesNotExistError`.
```kotlin
@Test fun `Given the owner and an image, Then delete removes the row and the file`() {
    val p = pin(); val img = imageFor(p.id)
    every { pins.findPinById(p.id) } returns p
    every { images.findByPinId(p.id) } returns img
    useCase.delete(p.id, owner)
    verifyOrder { images.deleteByPinId(p.id); store.delete(img.storageKey) }
}
```

- [ ] **Step 5: Run to fail.** → FAIL.

- [ ] **Step 6: Implement `DeletePinImage`:**
```kotlin
@ApplicationScoped
class DeletePinImage(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageStore: ImageStore,
) {
    fun delete(pinId: UUID, requester: User) {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()
        val image = imageRepository.findByPinId(pinId) ?: throw ImageDoesNotExistError()
        imageRepository.deleteByPinId(pinId)
        imageStore.delete(image.storageKey)
    }
}
```

- [ ] **Step 7: Run both test classes + coverage.** `./gradlew :api-usecases:test :api-usecases:koverVerify --console=plain` → PASS.
- [ ] **Step 8: Commit.** `git commit -am "feat(usecases): GetPinImage and DeletePinImage"`

### Task 12: Pin permanent-delete cascade → image files

**Files:**
- Modify: the pin permanent-delete path. Inspect `api-usecases/.../usecases/PinRecycleBin.kt` and `PinRepositoryInterface.permanentlyDeletePin` / `permanentlyDeleteAllSoftDeletedPinsForUser`.
- Test: the corresponding use-case test.

**Interfaces:**
- Consumes: `ImageRepositoryInterface`, `ImageStore`.
- Produces: image row + file removed when a pin is permanently deleted or the recycle bin is emptied.

Design note: the DB row is removed by the FK (`on delete restrict` prevents an orphan-blocking delete, so the image row must be deleted **before** the pin). The safest sequence in the use case handling permanent deletion: for each pin being permanently deleted, look up its image, `imageRepository.deleteByPinId(pinId)`, then let the existing pin deletion proceed, then best-effort `imageStore.delete(storageKey)` after the pin-delete commit. If the current `permanentlyDelete*` logic lives purely in the repository, add an image-aware step in the use case that owns the flow (do not put `ImageStore` I/O into a repository).

- [ ] **Step 1: Read the current permanent-delete flow** to decide the exact seam. Run: open `PinRecycleBin.kt` and `PinRepository.kt` (`permanentlyDeletePin`, `permanentlyDeleteAllSoftDeletedPinsForUser`). Note whether emptying the bin returns the deleted pins/ids (needed to know which image files to delete).

- [ ] **Step 2: Write the failing test** for the use case that empties the bin / permanently deletes a pin, asserting `imageRepository.deleteByPinId` and `imageStore.delete(storageKey)` are invoked for a pin that has an image, and NOT invoked for the soft-delete path.

- [ ] **Step 3: Run to fail.** → FAIL.

- [ ] **Step 4: Implement** the cascade in the use case (inject `ImageRepositoryInterface` + `ImageStore`; before/after the pin delete, remove the image row then best-effort the file). If `permanentlyDeleteAllSoftDeletedPinsForUser` currently deletes in bulk without returning ids, add a `findSoftDeletedPinsForUser`-style lookup (or a repository method returning the storage keys) so the files can be removed — keep the file I/O in the use case, not the repository.

- [ ] **Step 5: Run the test + full usecases gate.** `./gradlew :api-usecases:test :api-usecases:koverVerify --console=plain` → PASS.

- [ ] **Step 6: Commit.** `git commit -am "feat(usecases): delete image files when a pin is permanently deleted"`

---

## Phase 6 — Presentation

### Task 13: Map image error codes to HTTP status

**Files:**
- Modify: `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt`
- Test: `api-presentation-quarkus/.../mappers/BaseErrorMapperTest.kt` (if present; else add cases)

**Interfaces:**
- Produces: `IMAGE_DOES_NOT_EXIST → 404`, `IMAGE_INSUFFICIENT_PERMISSIONS → 403`, `IMAGE_INVALID → 422`, `IMAGE_TOO_LARGE → 413`.

Design note: `statusFor` returns `Response.Status`, which has no `413` constant. Change `statusFor` to return an `Int` (status code) and build the response with `Response.status(code)`, OR add a small branch that returns `Response.Status.UNPROCESSABLE_ENTITY` (422) for `IMAGE_INVALID` and handle `IMAGE_TOO_LARGE` via a raw `413`. Recommended: refactor `problemResponse` to accept an `Int` status alongside the existing `Response.Status` overload (both build the same `ProblemDetail`), and have `BaseErrorMapper` compute an `Int`.

- [ ] **Step 1: Write/extend the failing test** asserting each new code maps to its status (413, 422, 403, 404). If there is no existing `BaseErrorMapperTest`, create one that constructs a `BaseErrorMapper` (stub `uriInfo` with MockK), calls `toResponse(error)`, and checks `response.status`.

- [ ] **Step 2: Run to fail.** → FAIL (the exhaustive `when` currently does not compile once the enum has the new codes; and no status is asserted).

- [ ] **Step 3: Implement.** Extend `statusFor` (as an `Int`-returning function or add the `Response.Status` branches; use `422 = Response.Status.UNPROCESSABLE_ENTITY`, `413` as a raw int). Add the four `when` branches. Keep the `when` exhaustive.

- [ ] **Step 4: Run + coverage.** `./gradlew :api-presentation-quarkus:test :api-presentation-quarkus:koverVerify --console=plain` → PASS (every `ErrorCode` branch, including the new ones, must be hit — add a case per code).

- [ ] **Step 5: Commit.** `git commit -am "feat(presentation): map image error codes to 403/404/413/422"`

### Task 14: `ImageOutputDto` + `ImageMapper`

**Files:**
- Create: `api-presentation-quarkus/.../dtos/output/ImageOutputDto.kt`
- Create: `api-presentation-quarkus/.../mappers/ImageMapper.kt`
- Test: `api-presentation-quarkus/.../mappers/ImageMapperTest.kt`

**Interfaces:**
- Produces: `data class ImageOutputDto(id, pinId, mimeType, width, height, byteSize, url)` and `Image.toDto(baseUrl): ImageOutputDto`.

- [ ] **Step 1: Write the failing test:**
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.ImageMapper.toDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class ImageMapperTest {
    @Test fun `Given an image and base url, Then toDto builds the serve url`() {
        val pinId = randomUUID()
        val image = Image(randomUUID(), pinId, "image/webp", 8, 6, 99, "h", "originals/x/y/z.webp", Instant.EPOCH)
        val dto = image.toDto("https://host")
        assertEquals("https://host/api/v1/pins/$pinId/image", dto.url)
        assertEquals("image/webp", dto.mimeType)
        assertEquals(8, dto.width)
    }
}
```

- [ ] **Step 2: Run to fail.** → FAIL.

- [ ] **Step 3: Implement:**
```kotlin
// ImageOutputDto.kt
data class ImageOutputDto(
    val id: UUID, val pinId: UUID, val mimeType: String,
    val width: Int, val height: Int, val byteSize: Long, val url: String,
)
// ImageMapper.kt
object ImageMapper {
    fun Image.toDto(baseUrl: String) = ImageOutputDto(
        id = id, pinId = pinId, mimeType = mimeType, width = width, height = height,
        byteSize = byteSize, url = "$baseUrl/api/v1/pins/$pinId/image",
    )
}
```

- [ ] **Step 4: Run + coverage.** → PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(presentation): ImageOutputDto and ImageMapper"`

### Task 15: `ImageController` (PUT / GET / DELETE) + CDI wiring for the store dataDir

**Files:**
- Create: `api-presentation-quarkus/.../controllers/ImageController.kt`
- Create: `api-presentation-quarkus/.../images/ImageAdapterProducers.kt` (produces `ImageStore` from `ImagesConfig.dataDir()`)
- Test: `api-presentation-quarkus/.../controllers/ImageControllerTest.kt`

**Interfaces:**
- Consumes: `SetPinImage`, `GetPinImage`, `DeletePinImage`, `ImageStore`, `ImagesConfig`, `ApiConfig`, `SecurityIdentity.getUser()`, `ImageMapper.toDto`.

Design notes:
- `FilesystemImageStore` needs a `dataDir: String`. Since its module is CDI-light (no config access), wire it with a `@Produces` in presentation:
```kotlin
@ApplicationScoped
class ImageAdapterProducers {
    @Produces @ApplicationScoped
    fun imageStore(config: ImagesConfig): ImageStore = FilesystemImageStore(config.dataDir())
}
```
(`VipsImageProbe` has a no-arg constructor and is `@ApplicationScoped`, so ARC discovers it directly — no producer needed.)
- Multipart PUT: `@PUT @Path("/{pinId}/image") @Consumes(MediaType.MULTIPART_FORM_DATA)` with `@RestForm("file") file: FileUpload` (`org.jboss.resteasy.reactive.multipart.FileUpload`). Open `Files.newInputStream(file.uploadedFile())` and pass to `setPinImage.set(...)`. The framework has already streamed the body to `file.uploadedFile()`; the use case re-stages it under the data dir (same-FS temp) — the double copy is acceptable for correctness/layering. Return `201` (created) or `200` (replaced) — decide by whether an image existed: call `getPinImage`-style existence check first, or have `SetPinImage` return whether it replaced. Simplest: the controller checks existence via `GetPinImage` semantics is heavy; instead have `SetPinImage.set` return the `Image` and a `replaced: Boolean` (widen the return to a small result, or check `imageRepository` in the controller). **Chosen:** widen `SetPinImage.set` to return `SetPinImageResult(image, replaced)` — update Task 10 accordingly if implementing PUT status precisely; otherwise return `200` always. (Pick one and keep the plan/spec consistent: return `201` when new, `200` when replaced.)
- Serve: `@GET @Path("/{pinId}/image")`. `getPinImage.get(...)`; honour `If-None-Match` (`@HeaderParam("If-None-Match")`), `304` if equal to `content_hash`; else stream `imageStore.openStream(storageKey)` with `Content-Type`, `ETag`, `Cache-Control: private, must-revalidate`, `Content-Length`. Use `RestResponse.ResponseBuilder.ok(streamingOutput).header(...)` or return a `StreamingOutput`.
- Delete: `@DELETE @Path("/{pinId}/image")` → `deletePinImage.delete(...)` → `RestResponse.noContent()`.
- Owner/404/etc. are enforced by the use cases (they throw `ImageError`s mapped in Task 13).

- [ ] **Step 1: Write the failing controller unit tests** (MockK, no Quarkus — mirror `PinControllerTest`: stub `securityIdentity.getAttribute<User>("user")`, mock the three use cases + `ImageStore` + configs). Cover: PUT returns 201/200 with the DTO; GET returns 304 on matching `If-None-Match`; GET returns 200 with the right headers; DELETE returns 204. Assert header values and statuses.

- [ ] **Step 2: Run to fail.** `./gradlew :api-presentation-quarkus:test --tests "*ImageControllerTest" --console=plain` → FAIL.

- [ ] **Step 3: Implement `ImageAdapterProducers` and `ImageController`** per the design notes. Keep any URL-building helper as a private member. Confirm the multipart-`PUT` reception works with `@RestForm` + `FileUpload` at build time; if RESTEasy Reactive rejects it, this is the spec's flagged `PUT` risk — solve it (do NOT switch to POST) and raise it.

- [ ] **Step 4: Run the unit tests + coverage.** `./gradlew :api-presentation-quarkus:test :api-presentation-quarkus:koverVerify --console=plain` → PASS (branches: replaced vs new, 304 vs 200).

- [ ] **Step 5: Commit.** `git commit -am "feat(presentation): image controller (PUT/GET/DELETE) and store wiring"`

---

## Phase 7 — Integration, ops & the full gate

### Task 16: End-to-end integration tests

**Files:**
- Create: `api-application/src/test/kotlin/.../ImageHostingIntegrationTest.kt`
- Test resources: a couple of tiny real images under `api-application/src/test/resources/fixtures/`.

**Interfaces:**
- Consumes: the whole wired app (`@QuarkusTest`), `UserCreator`, `PinCreator` (or the pins REST endpoint) to seed a pin.
- Prerequisite: native `libvips` present in the environment running the tests.

- [ ] **Step 1: Write the failing integration tests** (extend `IntegrationTest`, `@QuarkusTest`, REST Assured with preemptive basic auth). Configure `images.data_dir` to a per-test temp directory via `@TestProfile` or `quarkus.test` config override so tests do not write to `/var/lib/pinry`. Cases:
  - upload (multipart PUT) an image to your own pin → `201`, body has the serve URL; then `GET` → `200` with `Content-Type` and an `ETag`; re-`GET` with `If-None-Match` → `304`.
  - replace → `200`; `GET` returns the new bytes/ETag.
  - upload to someone else's pin → `403`.
  - upload a `not-an-image.txt` → `422`.
  - upload an oversize body (> `images.max_file_bytes`, with the test profile setting a tiny limit) → `413`.
  - `GET` a pin with no image → `404`.
  - `DELETE` the image → `204`; subsequent `GET` → `404`.
  - permanently delete the pin (empty recycle bin) → the stored file is gone from the data dir.
```kotlin
// sketch of the multipart upload with REST Assured
given()
    .auth().preemptive().basic(username, password)
    .multiPart("file", File("src/test/resources/fixtures/sample.png"), "image/png")
    .`when`().put("/api/v1/pins/$pinId/image")
    .then().statusCode(201).body("url", endsWith("/api/v1/pins/$pinId/image"))
```

- [ ] **Step 2: Run to fail.** `./gradlew :api-application:test --tests "*ImageHostingIntegrationTest" --console=plain` → FAIL (endpoints not yet wired end-to-end / profile missing).

- [ ] **Step 3: Fix wiring** until green: ensure `ImageAdapterProducers`, `VipsImageProbe`, `EbeanImageRepository`, use cases, and controller are all discovered by ARC in the packaged app; ensure the test profile points `images.data_dir` at a temp dir.

- [ ] **Step 4: Run the tests.** Expected: PASS (all cases).

- [ ] **Step 5: Commit.** `git commit -am "test(images): end-to-end integration tests for canonical image hosting"`

### Task 17: Native libvips in CI and the deploy image

**Files:**
- Modify: `.github/workflows/validate.yml` (install libvips in the `test` and `build-image` jobs — the `lint` job does not need it)
- Modify: `Dockerfile` (install libvips in the runtime image)
- Modify: `AGENTS.md` or a short `docs/` note (local-dev requirement: `libvips` must be installed)

**Interfaces:**
- Produces: CI and the runtime image can load `libvips` for `api-imaging-vips`.

- [ ] **Step 1: Add libvips to the CI `test` job** (before the Gradle step): a step running `sudo apt-get update && sudo apt-get install -y libvips42` (or `libvips`). Add the same to the `build-image` job only if `quarkusBuild`/tests there need it (the artifact build itself does not run the imaging tests — `test` does; keep libvips in `test`, and in `build-image` only if a smoke test loads vips).

- [ ] **Step 2: Add libvips to the `Dockerfile` runtime** — extend the existing `apt-get install` layer:
```dockerfile
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libvips42 \
    && rm -rf /var/lib/apt/lists/*
```
(The `--enable-native-access=ALL-UNNAMED` flag is already on the `ENTRYPOINT`.)

- [ ] **Step 3: Document the local-dev requirement** — one line in `AGENTS.md` Key Technologies (e.g. "libvips (native) required for the imaging tests").

- [ ] **Step 4: Verify locally** the full gate is green with libvips installed: `./gradlew detekt test koverVerify --console=plain` (on the JDK 25 daemon if reproducing CI). Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.** `git commit -am "chore(images): install native libvips in CI and the runtime image"`

### Task 18: Final wiring pass, gate, handoff

- [ ] **Step 1: Run the whole gate.** `./gradlew detekt test koverVerify --console=plain` → BUILD SUCCESSFUL (100% branch per in-gate package, including the two new modules).
- [ ] **Step 2: Holistic review** of the branch diff (`git diff main...HEAD`) for cross-cutting issues (leaked file handles — every `openStream`/`newInputStream` in a `use {}`; temp cleanup on every error path; the `source_media_url` nullability ripple is complete; no `ImageStore`/`ImageProbe` construction outside the composition root; no top-level functions).
- [ ] **Step 3: Write the handoff** in `docs/handoffs/<ISO date> - handoff - image-hosting-2a.md` (current state, what was built, learned pitfalls — esp. the animated-WebP/libvips verification and the multipart-PUT resolution — what is NOT validated against real hardware, suggested next step = sub-project 2b).
- [ ] **Step 4: Open a PR** for the branch; wait for the `validate / gate` check; merge (rebase) once green. Tag `v0.2.0-image-hosting-2a` (annotated, not pushed) after merge.

---

## Self-Review

**Spec coverage** (spec §→task):
- §2 scope (set/replace/serve/delete): Tasks 10, 11, 15, 16. Out-of-scope items untouched: ✓.
- §3 decisions (one immutable image; bytes on FS; server-controlled names; ports; libvips; 403/404): Tasks 4, 5, 8, 9, 10, 13. ✓
- §4 modules: Task 1 (both new modules + DAG). ✓
- §5 model (`Image`, `Pin.image`, nullable `sourceMediaUrl`): Task 4. ✓
- §6 ports: Task 5. ✓
- §7 storage layout & crash-safety (temp→probe→fsync→move→commit→delete): Tasks 8, 10. ✓
- §8 API (PUT/GET/DELETE, statuses, caching): Tasks 13, 15, 16. ✓
- §9 serve/replace/delete flows: Tasks 10, 11, 15. ✓
- §10 lifecycle cascade: Task 12. ✓
- §11 validation & limits: Tasks 2, 9, 10. ✓
- §12 config: Task 2. ✓
- §13 persistence (images table, unique pin_id, FK, source_media_url nullable): Tasks 6, 7. ✓
- §14 imaging backend: Tasks 1, 9, 17. ✓
- §15 testing (TDD order, 100% branch): every task is test-first; §15's integration/use-case/adapter layers = Tasks 16 / 10-12 / 7-9. ✓
- §16 future seams: intentionally not built. ✓
- §17 risks: animated WebP (Task 9), multipart PUT (Task 15), streaming (Tasks 8/15), native libvips (Task 17). ✓

**Open decision surfaced for the implementer:** PUT status `201` vs `200` requires `SetPinImage` to report whether it replaced (Task 10/15 note). Resolve at Task 10 by returning a small `SetPinImageResult(image, replaced)` if precise `201`/`200` is wanted; otherwise return `200` for both. Flag to the operator during execution — it is a spec-level detail (spec §8 says 201 new / 200 replace), so implement the `replaced` flag to honour the spec.

**Type consistency:** `StagedFile(path, byteSize, contentHash)`, `ProbeResult(format, width, height)`, `Image(id, pinId, mimeType, width, height, byteSize, contentHash, storageKey, createdAt)`, `ImageFormat(mimeType, extension)`, and `storageKey = "originals/<userId>/<pinId>/<imageId>.<ext>"` are used identically across Tasks 5–16. Ports' method names (`stage`, `promote`, `openStream`, `delete`, `discard`; `probe`; `save`, `findByPinId`, `deleteByPinId`) match between definition (Task 5) and use (Tasks 7–16).
