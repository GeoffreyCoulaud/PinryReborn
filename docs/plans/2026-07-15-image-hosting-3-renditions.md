# Image Hosting 3 — Disposable Renditions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve fast, small WebP thumbnails ("renditions") of a pin's canonical image, generated lazily on first request and cached on disk under `<data_dir>/cache/`, selected by a named size the client requests.

**Architecture:** A new use case `GetPinImageRendition` orchestrates existing ports plus two new domain ports: `ImageTransformer` (libvips WebP resize, adapter in `api-imaging-vips`) and `RenditionCache` (disposable filesystem cache, adapter in `api-storage-filesystem`). The controller resolves the size name to a pixel value from config and routes every `GET .../image` through the new use case. The canonical `Image` gains an `animated` flag (needed to decide first-frame flattening). Renditions have no DB row; the cache subtree is evicted best-effort wherever a canonical image is dropped.

**Tech Stack:** Kotlin, Quarkus 3 (Jakarta REST), Ebean 19 + SQLite, libvips via vips-ffm (`app.photofox.vipsffm`), JUnit 5, MockK, REST Assured, Kover (branch coverage).

**Spec:** `docs/specs/2026-07-15-image-hosting-3-renditions.md`

## Global Constraints

_Every task's requirements implicitly include this section._

- **100% branch coverage per package** (Kover, gated in CI + pre-push). In-gate here: `api-imaging-vips`, `api-storage-filesystem`, `api-usecases`, `api-presentation-quarkus`. Exercise BOTH sides of every conditional.
- **Strict TDD**: write the failing test first, run it, watch it fail, then the minimal implementation. Tests are the spec.
- **Clean / Hexagonal purity**: `api-domain` is pure (no I/O) — `Image`, `ProbeResult`, `RenditionSpec`, and the `ImageTransformer` / `RenditionCache` interfaces live there. `api-usecases` depends on `api-domain` only. `api-imaging-vips` and `api-storage-filesystem` depend on `api-domain` only. `api-presentation-quarkus` depends on `api-usecases` + `api-domain`. `api-application` is the only composition root that wires adapters.
- **No top-level functions** (Kotlin): keep helpers in a class/companion/object; extension functions are the only free-function exception.
- **Language: English everywhere** — identifiers and prose (comments, messages, commit messages).
- **No em-dashes (`—`/`–`) in any user-facing string** (error messages, etc.). A hyphen `-` is fine.
- **Conventional commits** (`feat(domain):`, `feat(usecase):`, `feat(persistence):`, `feat(presentation):`, `test(images):`, `chore:`, `docs:`).
- **Rendition invariants** (from the spec):
  - Output format is always WebP for a generated rendition; the canonical original is never re-encoded.
  - `animated` defaults to `true`; `animated=false` flattens an animated source to its first frame; the flag is a no-op on a non-animated source.
  - Never upscale: an original whose shortest side is `<=` the requested size is served AS-IS (original bytes, original format), no generation.
  - `effectivePx = min(requestedPx, min(image.width, image.height))`.
  - Cache key: `"<effectivePx>-<a|s>.webp"`; cache layout `<data_dir>/cache/<imageId>/<key>`.
  - Rendition ETag: `"<encoderVersion>-<imageId>-<effectivePx>-<a|s>"` (synthetic, deterministic). The original keeps its `contentHash` ETag.
  - Eviction is best-effort (errors swallowed) at the four canonical-image-drop sites.
  - Config defaults: `tiny=112`, `small=240`, `medium=480`, `large=960`, `webp_quality=80`.
  - Errors: unknown `?size=` -> 400; missing pin / image-less pin -> 404; non-owner -> 403; libvips render failure on a probe-valid image -> 500.

## File Structure

**New files:**

| Path | Responsibility |
|------|----------------|
| `api-domain/.../images/RenditionSpec.kt` | Value type: `RenditionSpec(shortestSide: Int, animated: Boolean)`. |
| `api-domain/.../images/ImageTransformer.kt` | Port: `render(source, spec): StagedFile`. |
| `api-domain/.../images/RenditionCache.kt` | Port: `openStream / store / evictImage`. |
| `api-imaging-vips/.../VipsImageTransformer.kt` | libvips WebP resize/flatten adapter. |
| `api-storage-filesystem/.../FilesystemRenditionCache.kt` | Filesystem cache adapter. |
| `api-storage-filesystem/.../DataDirPaths.kt` | Shared internal helper: `resolveWithinRoot` + atomic move, factored from `FilesystemImageStore`. |
| `api-usecases/.../GetPinImageRendition.kt` | Serve-flow use case + `ServedImage` sealed type. |
| `api-usecases/.../exceptions/ImageRenditionSizeInvalidError.kt` | 400 for an unknown size name. |
| `api-presentation-quarkus/.../config/RenditionsConfig.kt` | `@ConfigMapping(prefix = "images.renditions")`. |
| `api-presentation-quarkus/.../controllers/RenditionSize.kt` | Enum of size names + name->px resolution against config. |
| `api-application/.../wiring/RenditionAdapterProducers.kt` (or extend `ImageAdapterProducers`) | CDI producer for `RenditionCache`. |
| `api-application/src/test/resources/fixtures/animated.gif` | Copied from the vips test fixtures (3-frame, 10x10). |

**Modified files:**

| Path | Change |
|------|--------|
| `api-domain/.../entities/Image.kt` | Add `animated: Boolean`. |
| `api-domain/.../images/ImageProbe.kt` | Add `animated: Boolean` to `ProbeResult`. |
| `api-imaging-vips/.../VipsImageProbe.kt` | Derive `animated` from the page count. |
| `api-persistence-sqlite/.../models/ImageModel.kt` | Add `animated: Boolean` column. |
| `api-persistence-sqlite/.../mappers/ImageModelMapper.kt` | Map `animated` both ways. |
| `api-persistence-sqlite/src/main/resources/dbmigration/1.6.sql` + `model/1.6.model.xml` | Generated migration adding `images.animated`. |
| `api-usecases/.../SetPinImage.kt` | Pass `probe.animated`; evict rendition cache of the replaced image. |
| `api-usecases/.../DownloadPinImage.kt` | Pass `probe.animated`; evict rendition cache on a real mode-B swap. |
| `api-usecases/.../DeletePinImage.kt` | Evict rendition cache on delete. |
| `api-usecases/.../PinRecycleBin.kt` | Evict rendition cache on permanent delete / empty. |
| `api-usecases/.../exceptions/ErrorCode.kt` | Add `IMAGE_RENDITION_SIZE_INVALID`. |
| `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` | Map the new code to 400. |
| `api-presentation-quarkus/.../controllers/ImageController.kt` | `getImage` gains `size` / `animated`; route through `GetPinImageRendition`. |

**Task order** (bottom-up; each task is independently testable, TDD internally):

1. `ProbeResult.animated` + `VipsImageProbe` derives it (adapter).
2. `Image.animated` propagated through persistence + use cases (canonical slice).
3. `RenditionSpec` + `ImageTransformer` port + `VipsImageTransformer` adapter.
4. `RenditionCache` port + shared `DataDirPaths` helper + `FilesystemRenditionCache` adapter.
5. `GetPinImageRendition` use case + `ServedImage`.
6. Evict wiring: replace paths (`SetPinImage`, `DownloadPinImage`).
7. Evict wiring: delete paths (`DeletePinImage`, `PinRecycleBin`).
8. `RenditionsConfig` + `RenditionSize` + `RenditionCache` producer (config + CDI).
9. `ImageController` `size`/`animated` + `IMAGE_RENDITION_SIZE_INVALID` (400) + routing.
10. End-to-end integration test.

---

### Task 1: `ProbeResult.animated` + `VipsImageProbe` derives it

Adds an `animated` flag to the probe result, derived from the libvips page count. No default value (a defaulted data-class param generates a synthetic branch Kover would flag), so every `ProbeResult(...)` call site is updated in this task.

**Files:**
- Modify: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/images/ImageProbe.kt`
- Modify: `api-imaging-vips/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/imaging/vips/VipsImageProbe.kt`
- Test: `api-imaging-vips/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/imaging/vips/VipsImageProbeTest.kt`
- Fix call sites (add `animated = false`, position last): `api-domain/.../images/ImagePortsTest.kt:31`, `api-usecases/.../DownloadPinImageTest.kt:241,255,271,282`, `api-usecases/.../SetPinImageTest.kt:56,77,119,135,150,167`

**Interfaces:**
- Produces: `ProbeResult(format: ImageFormat, width: Int, height: Int, animated: Boolean)`. `VipsImageProbe.probe` returns `animated = true` when the source has more than one page.

- [ ] **Step 1: Write the failing tests** — append to `VipsImageProbeTest.kt` (fixtures already present: `animated.gif`/`animated.webp` have `n-pages: 3`; `sample.png`/`sample.webp` are single-page):

```kotlin
@Test
fun `Given a static PNG, Then probe reports animated = false`() {
    // Given / When
    val result = probe.probe(staged("sample.png"), maxPixels = 1_000_000)
    // Then
    assertFalse(result.animated)
}

@Test
fun `Given an animated GIF, Then probe reports animated = true`() {
    // Given / When
    val result = probe.probe(staged("animated.gif"), maxPixels = 1_000_000)
    // Then
    assertTrue(result.animated)
}

@Test
fun `Given an animated WebP, Then probe reports animated = true`() {
    // Given / When
    val result = probe.probe(staged("animated.webp"), maxPixels = 1_000_000)
    // Then
    assertTrue(result.animated)
}

@Test
fun `Given a static WebP, Then probe reports animated = false`() {
    // Given / When
    val result = probe.probe(staged("sample.webp"), maxPixels = 1_000_000)
    // Then
    assertFalse(result.animated)
}
```

Add imports `org.junit.jupiter.api.Assertions.assertFalse` / `assertTrue`.

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-imaging-vips:test --tests "VipsImageProbeTest"` → FAILS to compile (`ProbeResult` has no `animated`).

- [ ] **Step 3: Add `animated` to `ProbeResult`** (`ImageProbe.kt`):

```kotlin
data class ProbeResult(val format: ImageFormat, val width: Int, val height: Int, val animated: Boolean)
```

- [ ] **Step 4: Derive it in `VipsImageProbe.readHeader`** — replace the final `ProbeResult(format, width, height)` (line 59) with:

```kotlin
// n-pages is the libvips header field set from the container; absent (null) for single-frame
// formats, > 1 for an animated GIF / animated WebP. getInt returns null when the field is absent.
val animated = (image.getInt("n-pages") ?: 1) > 1
ProbeResult(format, width, height, animated)
```

- [ ] **Step 5: Fix the broken `ProbeResult(...)` call sites** so the other modules compile. In each listed test, add `, animated = false` as the last argument. Example (`SetPinImageTest.kt:56`):

```kotlin
every { probe.probe(staged, 50) } returns ProbeResult(ImageFormat.PNG, 4, 5, animated = false)
```

Apply the identical edit at `ImagePortsTest.kt:31`, `DownloadPinImageTest.kt:241/255/271/282`, and `SetPinImageTest.kt:77/119/135/150/167`.

- [ ] **Step 6: Run to verify pass** — `./gradlew :api-domain:test :api-imaging-vips:test :api-usecases:test` → PASS. Then `./gradlew :api-imaging-vips:koverVerify` → PASS (both `animated` branches hit).

- [ ] **Step 7: Commit**

```bash
git add api-domain api-imaging-vips api-usecases
git commit -m "feat(images): probe reports whether the source is animated"
```

---

### Task 2: `Image.animated` propagated through persistence + use cases

Adds `animated` to the canonical `Image` (after `height`, per spec §5.1), the DB model + migration, and the two producers that build an `Image` from a probe. No default value; every `Image(...)` and `ImageModel(...)` call site is updated here.

**Files:**
- Modify: `api-domain/.../entities/Image.kt`, `api-persistence-sqlite/.../models/ImageModel.kt`, `api-persistence-sqlite/.../mappers/ImageModelMapper.kt`
- Modify: `api-usecases/.../SetPinImage.kt:58`, `api-usecases/.../DownloadPinImage.kt:128`
- Create (generated): `api-persistence-sqlite/src/main/resources/dbmigration/1.6.sql` + `model/1.6.model.xml`
- Test: `api-persistence-sqlite/.../mappers/ImageModelMapperTest.kt`, `api-persistence-sqlite/.../EbeanImageRepositoryTest.kt`
- Fix `Image(...)` call sites (add `animated`): `api-domain/.../ImageTest.kt:16`, `api-usecases/.../DeletePinImageTest.kt:33`, `GetPinImageTest.kt:29`, `SetPinImageTest.kt:74,164`, `PinRecycleBinTest.kt:46`, `PinImageStateTest.kt:16`, `api-persistence-sqlite/.../EbeanImageRepositoryTest.kt:29`, `EbeanTransactionRunnerTest.kt:44`, `api-presentation-quarkus/.../ImageControllerTest.kt:60`, `PinImageStateMapperTest.kt:22,37,64`, `ImageMapperTest.kt:14`. (Run `./gradlew build -x test` after Step 3 to let the compiler list any site this plan missed.)

**Interfaces:**
- Consumes: `ProbeResult.animated` (Task 1).
- Produces: `Image(id, pinId, mimeType, width, height, animated: Boolean, byteSize, contentHash, storageKey, createdAt)`. `ImageModel` gains `var animated: Boolean`.

- [ ] **Step 1: Write the failing persistence tests.** In `ImageModelMapperTest.kt`, assert `animated` round-trips both ways (add a case with `animated = true`). In `EbeanImageRepositoryTest.kt`, extend the save/find test to persist an image with `animated = true` and assert it reads back true. Example mapper assertion:

```kotlin
@Test
fun `Given an animated image, Then the flag round-trips through the model`() {
    // Given
    val image = Image(
        randomUUID(), randomUUID(), "image/gif", 10, 10, animated = true,
        byteSize = 1, contentHash = "h", storageKey = "originals/x/y/z.gif", createdAt = Instant.EPOCH,
    )
    // When
    val back = image.toModel().toDomain()
    // Then
    assertTrue(back.animated)
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-persistence-sqlite:test` → FAILS to compile (`Image`/`ImageModel` have no `animated`).

- [ ] **Step 3: Add the field to the domain and model.**

`Image.kt`:
```kotlin
data class Image(
    override val id: UUID,
    val pinId: UUID,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val byteSize: Long,
    val contentHash: String,
    val storageKey: String,
    val createdAt: Instant,
) : Identifiable
```

`ImageModel.kt` — add after `height`, with a DB default so the migration can backfill existing rows:
```kotlin
import io.ebean.annotation.DbDefault
// ...
    var height: Int,
    @DbDefault("false") var animated: Boolean,
    var byteSize: Long,
```

`ImageModelMapper.kt` — add `animated = animated,` to both `toModel()` and `toDomain()`.

- [ ] **Step 4: Generate the migration.** With `JAVA_HOME` on the JDK 25 toolchain (the JDK-21-daemon quirk from prior handoffs), run:

```bash
./gradlew :api-persistence-sqlite:generateDbMigration
```

This diffs `ImageModel` against `model/1.5.model.xml` and writes `1.6.sql` + `model/1.6.model.xml`. Expected `1.6.sql` shape (commit whatever is actually generated; it must add the column with a `false` default and `not null`):

```sql
-- apply changes
alter table images add column animated boolean default false not null;
```

`model/1.6.model.xml` should contain an `<addColumn>` changeSet for `images.animated`. If generation is unavailable, hand-write both files following the `1.5` format exactly and verify the app boots (the migration applies) in Step 6.

- [ ] **Step 5: Propagate `animated` from the probe in both producers.**

`SetPinImage.kt` (line 58 `Image(`), add `animated = probeResult.animated,` after `height = probeResult.height,`.
`DownloadPinImage.kt` (line 128 `Image(`), add `animated = probe.animated,` after `height = probe.height,`.

Then fix every remaining `Image(...)` test call site (list above) by adding `animated = false` (or `true` where the test is about an animated image) in the `height`-then-`byteSize` position. For positional constructors like `Image(randomUUID(), p.id, "image/png", 1, 1, 1, "old", ...)`, insert the boolean after the two dimension ints: `Image(randomUUID(), p.id, "image/png", 1, 1, false, 1, "old", ...)`.

- [ ] **Step 6: Run to verify pass** — per-module then boot:

```bash
./gradlew :api-domain:test :api-persistence-sqlite:test :api-usecases:test :api-presentation-quarkus:test
./gradlew :api-persistence-sqlite:koverVerify :api-usecases:koverVerify
```
Expected: PASS. The repository test proves the migration applies and `animated` persists.

- [ ] **Step 7: Commit**

```bash
git add api-domain api-persistence-sqlite api-usecases api-presentation-quarkus
git commit -m "feat(images): persist whether the canonical image is animated"
```

---

### Task 3: `RenditionSpec` + `ImageTransformer` port + `VipsImageTransformer` adapter

The libvips WebP resize/flatten adapter. API confirmed against vips-ffm 1.9.8 sources: load with `n=-1` to keep all frames or `n=1` for the first frame; `getInt("n-pages")`/`getInt("page-height")` are nullable; there is no shortest-side thumbnail mode, so resize by `scale = N / min(width, frameHeight)`; `resize()` does not adjust `page-height`, so it must be re-set for animated output; `writeToFile(path.webp, Q)` encodes WebP (animated automatically when the image is multi-page). Do NOT use `flatten()` (that blends alpha, not frames).

**Files:**
- Create: `api-domain/.../images/RenditionSpec.kt`, `api-domain/.../images/ImageTransformer.kt`
- Create: `api-imaging-vips/.../VipsImageTransformer.kt`
- Test: `api-imaging-vips/.../VipsImageTransformerTest.kt`

**Interfaces:**
- Consumes: `StagedFile` (existing, `api-domain/images`), `ProbeResult` (Task 1, for the re-probe assertions).
- Produces:
  - `RenditionSpec(shortestSide: Int, animated: Boolean)`
  - `ImageTransformer.render(source: InputStream, spec: RenditionSpec): StagedFile`
  - `VipsImageTransformer(quality: Int)` (NOT `@ApplicationScoped` — a `String`/`Int` ctor param cannot be resolved by ARC; a producer builds it, Task 8).

- [ ] **Step 1: Write the failing test** (`VipsImageTransformerTest.kt`). It renders real bytes and re-probes the output with `VipsImageProbe` (same module) to assert format, shortest side, and animation. Fixtures are 10x10 (`sample.png` static; `animated.gif` 3-frame):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.imaging.vips

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionSpec
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class VipsImageTransformerTest {
    private val transformer = VipsImageTransformer(quality = 80)

    private fun renderAndProbe(fixture: String, spec: RenditionSpec): ProbeResult {
        val staged = Files.newInputStream(Path.of("src/test/resources/fixtures", fixture)).use {
            transformer.render(it, spec)
        }
        return try {
            VipsImageProbe().probe(StagedFile(staged.path, 0, ""), maxPixels = 1_000_000)
        } finally {
            Files.deleteIfExists(Path.of(staged.path))
        }
    }

    @Test
    fun `Given a static image and a smaller size, Then it downscales to WebP with that shortest side`() {
        val result = renderAndProbe("sample.png", RenditionSpec(shortestSide = 4, animated = false))
        assertEquals(ImageFormat.WEBP, result.format)
        assertEquals(4, minOf(result.width, result.height))
        assertFalse(result.animated)
    }

    @Test
    fun `Given a size equal to the native shortest side, Then it re-encodes WebP without upscaling`() {
        val result = renderAndProbe("sample.png", RenditionSpec(shortestSide = 10, animated = false))
        assertEquals(ImageFormat.WEBP, result.format)
        assertEquals(10, minOf(result.width, result.height))
    }

    @Test
    fun `Given an animated source with animated = true, Then it downscales and keeps the animation`() {
        val result = renderAndProbe("animated.gif", RenditionSpec(shortestSide = 4, animated = true))
        assertEquals(ImageFormat.WEBP, result.format)
        assertEquals(4, minOf(result.width, result.height))
        assertTrue(result.animated)
    }

    @Test
    fun `Given an animated source with animated = false, Then it flattens to a static WebP`() {
        val result = renderAndProbe("animated.gif", RenditionSpec(shortestSide = 4, animated = false))
        assertEquals(ImageFormat.WEBP, result.format)
        assertEquals(4, minOf(result.width, result.height))
        assertFalse(result.animated)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-imaging-vips:test --tests "VipsImageTransformerTest"` → FAILS to compile (`RenditionSpec`/`ImageTransformer`/`VipsImageTransformer` missing).

- [ ] **Step 3: Create the domain port + value type.**

`RenditionSpec.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.images

/** A transform request: fit the shortest side to [shortestSide] px, keeping animation iff [animated]. */
data class RenditionSpec(val shortestSide: Int, val animated: Boolean)
```

`ImageTransformer.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import java.io.InputStream

interface ImageTransformer {
    /**
     * Render [source] to a fresh temp WebP file per [spec], returning it as a [StagedFile].
     * Never upscales beyond the source's native size (the caller passes an already-clamped
     * shortest side). The caller owns the returned temp file (promote or discard it).
     */
    fun render(source: InputStream, spec: RenditionSpec): StagedFile
}
```

- [ ] **Step 4: Implement `VipsImageTransformer`** (mirrors `VipsImageProbe`'s Vips/Arena setup):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.imaging.vips

import app.photofox.vipsffm.VImage
import app.photofox.vipsffm.Vips
import app.photofox.vipsffm.VipsOption
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTransformer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionSpec
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import java.io.InputStream
import java.lang.foreign.Arena
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

/**
 * [ImageTransformer] adapter backed by native libvips (vips-ffm). Output is always WebP.
 *
 * Not `@ApplicationScoped`: ARC cannot resolve the `Int quality` ctor param, so a producer in
 * the composition root builds it (mirrors `FilesystemImageStore`).
 */
class VipsImageTransformer(private val quality: Int) : ImageTransformer {

    private companion object {
        private val HEX = HexFormat.of()
    }

    // A render failure (a probe-valid image libvips still refuses to encode, an I/O fault) must
    // leave no output temp behind; the input temp is always removed. The broad catch dispatches
    // via the JVM exception table, not a conditional jump, so it adds no uncovered Kover branch.
    @Suppress("TooGenericExceptionCaught")
    override fun render(source: InputStream, spec: RenditionSpec): StagedFile {
        Vips.init()
        val input = Files.createTempFile("rendition-in-", ".tmp")
        val output = Files.createTempFile("rendition-out-", ".webp")
        try {
            Files.newOutputStream(input).use { source.copyTo(it) }
            Arena.ofConfined().use { arena ->
                // n = -1 loads every frame (animation preserved); n = 1 loads only the first frame.
                val pages = if (spec.animated) -1 else 1
                val image = VImage.newFromFile(arena, input.toString(), VipsOption.Int("n", pages))
                // For a multi-page load, per-frame height is `page-height`; absent (null) on a
                // single frame, where the frame height IS the image height.
                val frameHeight = image.getInt("page-height") ?: image.height
                val scale = spec.shortestSide.toDouble() / minOf(image.width, frameHeight)
                val rendered = if (scale < 1.0) resize(image, scale, frameHeight, spec.animated) else image
                rendered.writeToFile(output.toString(), VipsOption.Int("Q", quality))
            }
            val bytes = Files.readAllBytes(output)
            val hash = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            return StagedFile(output.toString(), bytes.size.toLong(), hash)
        } catch (error: Throwable) {
            Files.deleteIfExists(output)
            throw error
        } finally {
            Files.deleteIfExists(input)
        }
    }

    // resize() scales the whole tall multi-page strip but does NOT update `page-height`, which
    // would corrupt frame boundaries; re-set it for animated output (vips-ffm 1.9.8 behaviour).
    private fun resize(image: VImage, scale: Double, frameHeight: Int, animated: Boolean): VImage {
        val resized = image.resize(scale)
        if (animated) resized.set("page-height", Math.round(frameHeight * scale).toInt())
        return resized
    }
}
```

- [ ] **Step 5: Run to verify pass** — `./gradlew :api-domain:test :api-imaging-vips:test --tests "VipsImageTransformerTest"` → PASS, then `./gradlew :api-imaging-vips:koverVerify` → PASS. If the animated case fails frame integrity (n-pages != 3 on output), confirm the page-height re-set (the one implementation-time risk flagged in spec §13); adjust per the vips-ffm note (thumbnail-on-loaded-image alternative) until the re-probe reads 3 pages.

- [ ] **Step 6: Commit**

```bash
git add api-domain api-imaging-vips
git commit -m "feat(images): VipsImageTransformer renders WebP renditions by shortest side"
```

---

### Task 4: `RenditionCache` port + shared `DataDirPaths` helper + `FilesystemRenditionCache`

The disposable filesystem cache. Factors the path-safety + atomic-move primitives out of `FilesystemImageStore` into a shared internal helper (spec §4), used by both adapters.

**Files:**
- Create: `api-domain/.../images/RenditionCache.kt`
- Create: `api-storage-filesystem/.../DataDirPaths.kt`, `api-storage-filesystem/.../FilesystemRenditionCache.kt`
- Modify: `api-storage-filesystem/.../FilesystemImageStore.kt` (delegate `resolveWithinRoot` + `move` to `DataDirPaths`)
- Test: `api-storage-filesystem/.../FilesystemRenditionCacheTest.kt` (existing `FilesystemImageStoreTest` must stay green)

**Interfaces:**
- Consumes: `StagedFile`.
- Produces:
  - `RenditionCache.openStream(imageId: UUID, key: String): InputStream?` (null = miss); `store(imageId: UUID, key: String, staged: StagedFile)`; `evictImage(imageId: UUID)` (idempotent; may throw on a real I/O fault, callers wrap best-effort).
  - `FilesystemRenditionCache(dataDir: String)`.
  - `internal class DataDirPaths(dataDir: String)` with `resolveWithinRoot(key: String): Path` and `atomicMove(source: Path, dest: Path)`.

- [ ] **Step 1: Write the failing test** (`FilesystemRenditionCacheTest.kt`):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FilesystemRenditionCacheTest {
    @TempDir lateinit var dataDir: Path

    private fun cache() = FilesystemRenditionCache(dataDir.toString())

    private fun staged(bytes: ByteArray): StagedFile {
        val tmp = Files.createTempFile("staged-", ".webp")
        Files.write(tmp, bytes)
        return StagedFile(tmp.toString(), bytes.size.toLong(), "h")
    }

    @Test
    fun `Given a stored rendition, Then openStream reads it back`() {
        val id = UUID.randomUUID()
        cache().store(id, "4-a.webp", staged(byteArrayOf(1, 2, 3)))
        val read = cache().openStream(id, "4-a.webp")!!.use { it.readBytes() }
        assertArrayEquals(byteArrayOf(1, 2, 3), read)
    }

    @Test
    fun `Given no rendition, Then openStream returns null`() {
        assertNull(cache().openStream(UUID.randomUUID(), "4-a.webp"))
    }

    @Test
    fun `Given cached renditions for an image, Then evictImage removes the whole subtree`() {
        val id = UUID.randomUUID()
        cache().store(id, "4-a.webp", staged(byteArrayOf(1)))
        cache().store(id, "8-s.webp", staged(byteArrayOf(2)))
        cache().evictImage(id)
        assertFalse(Files.exists(dataDir.resolve("cache/$id")))
        assertNull(cache().openStream(id, "4-a.webp"))
    }

    @Test
    fun `Given no cache subtree, Then evictImage is a no-op`() {
        cache().evictImage(UUID.randomUUID()) // must not throw
    }

    @Test
    fun `Given a traversal key, Then it is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            cache().openStream(UUID.randomUUID(), "../../etc/passwd")
        }
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-storage-filesystem:test --tests "FilesystemRenditionCacheTest"` → FAILS to compile.

- [ ] **Step 3: Create the domain port** (`RenditionCache.kt`):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.images

import java.io.InputStream
import java.util.UUID

/** Disposable, regenerable cache of image renditions, keyed by (canonical image id, key). */
interface RenditionCache {
    /** Open a read stream for a cached rendition, or null on a miss. */
    fun openStream(imageId: UUID, key: String): InputStream?

    /** Atomically move a staged temp file into the cache at (imageId, key). */
    fun store(imageId: UUID, key: String, staged: StagedFile)

    /** Delete the whole cache subtree for an image (idempotent; a no-op when absent). */
    fun evictImage(imageId: UUID)
}
```

- [ ] **Step 4: Extract `DataDirPaths`** (`DataDirPaths.kt`) from `FilesystemImageStore`'s private `resolveWithinRoot` + `move`:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Shared filesystem primitives for the data-dir-backed adapters: path containment + atomic move. */
internal class DataDirPaths(dataDir: String) {
    private val root: Path = Path.of(dataDir).normalize()

    /**
     * Resolves [key] under the data dir, rejecting anything that escapes it (defence in depth;
     * keys are server-generated). Normalising then checking containment covers both `..`
     * traversal and an absolute [key].
     */
    fun resolveWithinRoot(key: String): Path {
        val resolved = root.resolve(key).normalize()
        require(resolved.startsWith(root)) { "Illegal storage key: $key" }
        return resolved
    }

    /**
     * Moves [source] to [dest], preferring an atomic move and falling back to a plain move when
     * the filesystem cannot provide atomicity. The fallback is a try/catch (JVM exception table,
     * not a conditional jump), so Kover's branch metric does not count it.
     */
    fun atomicMove(source: Path, dest: Path) {
        try {
            Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, dest)
        }
    }
}
```

Then refactor `FilesystemImageStore` to hold `private val paths = DataDirPaths(dataDir)`, replace its private `resolveWithinRoot(...)` calls with `paths.resolveWithinRoot(...)`, and its `move(...)` with `paths.atomicMove(...)`. Delete the now-duplicated private methods. Behaviour is unchanged; `FilesystemImageStoreTest` stays green.

- [ ] **Step 5: Implement `FilesystemRenditionCache`** (`FilesystemRenditionCache.kt`):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * [RenditionCache] adapter backed by the local filesystem, under `<dataDir>/cache/<imageId>/`.
 *
 * Not `@ApplicationScoped` (a `String` ctor param is unresolvable by ARC); a producer in the
 * composition root builds it, mirroring `FilesystemImageStore`.
 */
class FilesystemRenditionCache(dataDir: String) : RenditionCache {
    private val paths = DataDirPaths(dataDir)

    private fun keyPath(imageId: UUID, key: String): Path = paths.resolveWithinRoot("cache/$imageId/$key")

    override fun openStream(imageId: UUID, key: String): InputStream? {
        val path = keyPath(imageId, key)
        return if (Files.exists(path)) Files.newInputStream(path) else null
    }

    override fun store(imageId: UUID, key: String, staged: StagedFile) {
        val dest = keyPath(imageId, key)
        Files.createDirectories(dest.parent)
        paths.atomicMove(Path.of(staged.path), dest)
    }

    override fun evictImage(imageId: UUID) {
        val dir = paths.resolveWithinRoot("cache/$imageId")
        if (!Files.exists(dir)) return
        // Delete depth-first (children before parents) so the directory tree can be removed.
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
```

- [ ] **Step 6: Run to verify pass** — `./gradlew :api-domain:test :api-storage-filesystem:test` → PASS (both new cache tests and the unchanged store tests), then `./gradlew :api-storage-filesystem:koverVerify` → PASS (openStream hit/miss, evict present/absent branches all hit).

- [ ] **Step 7: Commit**

```bash
git add api-domain api-storage-filesystem
git commit -m "feat(images): FilesystemRenditionCache + shared DataDirPaths helper"
```

> **WIRING REMINDER (expected intermediate state):** from this task until Task 8, the fully-wired
> app (`./gradlew build` / `quarkusDev`) will NOT boot, because `GetPinImageRendition` (and, from
> Tasks 6-7, the existing use cases) depend on `ImageTransformer` / `RenditionCache` beans whose
> producers do not exist until Task 8. Per-module `test` / `koverVerify` stay green throughout.
> After Task 8, verify the app boots again.

---

### Task 5: `GetPinImageRendition` use case + `ServedImage`

Orchestrates the serve flow (spec §8). Reuses `GetPinImage.get` verbatim for the load + owner/404 guards, decides transform-or-original, and on a cache miss renders and stores. Returns a descriptor; the controller streams (Task 9).

**Files:**
- Create: `api-usecases/.../GetPinImageRendition.kt`
- Test: `api-usecases/.../GetPinImageRenditionTest.kt`

**Interfaces:**
- Consumes: `GetPinImage.get(pinId, requester): Image`; `ImageStore.openStream(storageKey): InputStream`; `ImageTransformer.render(source, RenditionSpec): StagedFile`; `RenditionCache.openStream/store`.
- Produces:
  - `GetPinImageRendition.get(pinId: UUID, requester: User, requestedPx: Int?, animated: Boolean): ServedImage`
  - `sealed interface ServedImage { data class Original(val image: Image); data class Rendition(val imageId: UUID, val key: String, val effectivePx: Int, val animated: Boolean) }`

- [ ] **Step 1: Write the failing tests** (`GetPinImageRenditionTest.kt`). Cover every branch of the decision (no-size, downscale, flatten, none) and cache hit/miss, plus guard delegation:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTransformer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionSpec
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageDoesNotExistError
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.UUID.randomUUID

class GetPinImageRenditionTest {
    private val getPinImage = mockk<GetPinImage>()
    private val imageStore = mockk<ImageStore>()
    private val imageTransformer = mockk<ImageTransformer>()
    private val renditionCache = mockk<RenditionCache>()
    private val useCase = GetPinImageRendition(getPinImage, imageStore, imageTransformer, renditionCache)

    private val requester = mockk<User>()

    private fun image(pinId: UUID, width: Int, height: Int, animated: Boolean) = Image(
        id = randomUUID(), pinId = pinId, mimeType = "image/png", width = width, height = height,
        animated = animated, byteSize = 1, contentHash = "h",
        storageKey = "originals/u/$pinId/i.png", createdAt = java.time.Instant.EPOCH,
    )

    private fun stubMiss(img: Image, key: String) {
        every { renditionCache.openStream(img.id, key) } returns null
        every { imageStore.openStream(img.storageKey) } returns ByteArrayInputStream(byteArrayOf(1))
        every { imageTransformer.render(any(), any()) } returns StagedFile("/tmp/out.webp", 3, "hh")
        every { renditionCache.store(img.id, key, any()) } returns Unit
    }

    @Test
    fun `Given no size, Then it serves the original`() {
        val pinId = randomUUID()
        val img = image(pinId, 100, 80, animated = false)
        every { getPinImage.get(pinId, requester) } returns img

        val served = useCase.get(pinId, requester, requestedPx = null, animated = true)

        assertEquals(ServedImage.Original(img), served)
    }

    @Test
    fun `Given a static image at least as small as the size, Then it serves the original`() {
        val pinId = randomUUID()
        val img = image(pinId, 10, 20, animated = false)
        every { getPinImage.get(pinId, requester) } returns img

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        assertEquals(ServedImage.Original(img), served)
    }

    @Test
    fun `Given a static image larger than the size and a cache miss, Then it renders, stores, and serves a rendition`() {
        val pinId = randomUUID()
        val img = image(pinId, 100, 80, animated = false)
        every { getPinImage.get(pinId, requester) } returns img
        stubMiss(img, "40-a.webp")

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        val rendition = assertInstanceOf(ServedImage.Rendition::class.java, served)
        assertEquals("40-a.webp", rendition.key)
        assertEquals(40, rendition.effectivePx)
        verify { imageTransformer.render(any(), RenditionSpec(40, true)) }
        verify { renditionCache.store(img.id, "40-a.webp", any()) }
    }

    @Test
    fun `Given a cache hit, Then it serves the rendition without rendering`() {
        val pinId = randomUUID()
        val img = image(pinId, 100, 80, animated = false)
        every { getPinImage.get(pinId, requester) } returns img
        every { renditionCache.openStream(img.id, "40-a.webp") } returns ByteArrayInputStream(byteArrayOf(9))

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        assertInstanceOf(ServedImage.Rendition::class.java, served)
        verify(exactly = 0) { imageTransformer.render(any(), any()) }
        verify(exactly = 0) { renditionCache.store(any(), any(), any()) }
    }

    @Test
    fun `Given an animated image and animated = false at a large size, Then it flattens to a rendition`() {
        val pinId = randomUUID()
        val img = image(pinId, 10, 20, animated = true)
        every { getPinImage.get(pinId, requester) } returns img
        stubMiss(img, "10-s.webp")

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = false)

        val rendition = assertInstanceOf(ServedImage.Rendition::class.java, served)
        assertEquals("10-s.webp", rendition.key)
        assertEquals(10, rendition.effectivePx)
        verify { imageTransformer.render(any(), RenditionSpec(10, false)) }
    }

    @Test
    fun `Given an animated image and animated = true at a large size, Then it serves the original`() {
        val pinId = randomUUID()
        val img = image(pinId, 10, 20, animated = true)
        every { getPinImage.get(pinId, requester) } returns img

        val served = useCase.get(pinId, requester, requestedPx = 40, animated = true)

        assertEquals(ServedImage.Original(img), served)
    }

    @Test
    fun `Given the pin has no image, Then the guard from GetPinImage propagates`() {
        val pinId = randomUUID()
        every { getPinImage.get(pinId, requester) } throws ImageDoesNotExistError()

        assertThrows(ImageDoesNotExistError::class.java) {
            useCase.get(pinId, requester, requestedPx = 40, animated = true)
        }
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-usecases:test --tests "GetPinImageRenditionTest"` → FAILS to compile.

- [ ] **Step 3: Implement** (`GetPinImageRendition.kt`):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTransformer
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionSpec
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Descriptor of what to serve for a `GET .../image[?size=...]`: the original bytes, or a rendition. */
sealed interface ServedImage {
    data class Original(val image: Image) : ServedImage
    data class Rendition(val imageId: UUID, val key: String, val effectivePx: Int, val animated: Boolean) : ServedImage
}

@ApplicationScoped
class GetPinImageRendition(
    private val getPinImage: GetPinImage,
    private val imageStore: ImageStore,
    private val imageTransformer: ImageTransformer,
    private val renditionCache: RenditionCache,
) {
    fun get(pinId: UUID, requester: User, requestedPx: Int?, animated: Boolean): ServedImage {
        // Reuse 2a's load + owner/not-found guards verbatim (403/404 behaviour unchanged).
        val image = getPinImage.get(pinId, requester)
        if (requestedPx == null) return ServedImage.Original(image)

        val srcShort = minOf(image.width, image.height)
        val needsDownscale = srcShort > requestedPx
        val needsFlatten = image.animated && !animated
        // Never upscale, never re-encode: with no transformation required, serve the original as-is.
        if (!needsDownscale && !needsFlatten) return ServedImage.Original(image)

        val effectivePx = minOf(requestedPx, srcShort)
        val key = keyFor(effectivePx, animated)
        val cached = renditionCache.openStream(image.id, key)
        if (cached != null) {
            cached.close()
            return ServedImage.Rendition(image.id, key, effectivePx, animated)
        }
        val staged = imageStore.openStream(image.storageKey).use { source ->
            imageTransformer.render(source, RenditionSpec(effectivePx, animated))
        }
        renditionCache.store(image.id, key, staged)
        return ServedImage.Rendition(image.id, key, effectivePx, animated)
    }

    private fun keyFor(effectivePx: Int, animated: Boolean): String =
        "$effectivePx-${if (animated) "a" else "s"}.webp"
}
```

- [ ] **Step 4: Run to verify pass** — `./gradlew :api-usecases:test --tests "GetPinImageRenditionTest"` → PASS, then `./gradlew :api-usecases:koverVerify` → PASS (no-size/downscale/flatten/none, hit/miss, and both `&&` operands of `needsFlatten` are all exercised).

- [ ] **Step 5: Commit**

```bash
git add api-usecases
git commit -m "feat(usecase): GetPinImageRendition lazy serve flow"
```

---

### Task 6: Evict the rendition cache on replace (`SetPinImage`, `DownloadPinImage`)

When a canonical image is replaced, its rendition subtree is orphaned. Evict it best-effort (`runCatching`, mirroring the 2b superseded-file delete) right where the superseded file is deleted.

**Files:**
- Modify: `api-usecases/.../SetPinImage.kt`, `api-usecases/.../DownloadPinImage.kt`
- Test: `api-usecases/.../SetPinImageTest.kt`, `api-usecases/.../DownloadPinImageTest.kt`

**Interfaces:**
- Consumes: `RenditionCache.evictImage(imageId)` (Task 4).
- Produces: both use cases gain a `renditionCache: RenditionCache` constructor param (appended last).

- [ ] **Step 1: Write the failing tests.** In `SetPinImageTest`, add `renditionCache` and a replace test asserting eviction of the OLD image id, plus a best-effort test:

```kotlin
// field:
private val renditionCache = mockk<RenditionCache>()
// in @BeforeEach / setup: every { renditionCache.evictImage(any()) } returns Unit
// pass renditionCache as the last ctor arg of SetPinImage(...)

@Test
fun `Given a replaced image, Then the old image's rendition cache is evicted`() {
    // Given a pin that already has image `old`, replaced by a new upload (reuse the existing
    // replace-setup helper); capture old.id
    // When set(...) replaces it
    // Then
    verify { renditionCache.evictImage(old.id) }
}

@Test
fun `Given eviction fails, Then the upload still succeeds`() {
    // Given every { renditionCache.evictImage(any()) } throws RuntimeException("io")
    // When/Then set(...) returns normally (best-effort), asserted by no exception + the new row saved
}
```

In `DownloadPinImageTest`, add `renditionCache` to the ctor + stub, and a mode-B replace test asserting `evictImage(superseded.id)` on a real swap.

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-usecases:test --tests "SetPinImageTest" --tests "DownloadPinImageTest"` → FAILS to compile (ctor arity).

- [ ] **Step 3: Wire `SetPinImage`.** Add `import ...domain.images.RenditionCache`, append `private val renditionCache: RenditionCache,` to the ctor, and replace the superseded-delete block (line ~83) with:

```kotlin
existing?.let { old ->
    runCatching { imageStore.delete(old.storageKey) }
    runCatching { renditionCache.evictImage(old.id) }
}
```

- [ ] **Step 4: Wire `DownloadPinImage`.** Add the import, append `private val renditionCache: RenditionCache,` to the ctor, and in `promoteAndSwap`'s `swapped` branch replace the superseded-delete with:

```kotlin
superseded?.let { old ->
    runCatching { imageStore.delete(old.storageKey) }
    runCatching { renditionCache.evictImage(old.id) }
}
```

- [ ] **Step 5: Run to verify pass** — `./gradlew :api-usecases:test :api-usecases:koverVerify` → PASS.

- [ ] **Step 6: Commit**

```bash
git add api-usecases
git commit -m "feat(usecase): evict the rendition cache when the canonical image is replaced"
```

---

### Task 7: Evict the rendition cache on delete (`DeletePinImage`, `PinRecycleBin`)

Same best-effort eviction on the delete / hard-delete paths.

**Files:**
- Modify: `api-usecases/.../DeletePinImage.kt`, `api-usecases/.../PinRecycleBin.kt`
- Test: `api-usecases/.../DeletePinImageTest.kt`, `api-usecases/.../PinRecycleBinTest.kt`

**Interfaces:**
- Consumes: `RenditionCache.evictImage`.
- Produces: both use cases gain a `renditionCache: RenditionCache` ctor param (appended last).

- [ ] **Step 1: Write the failing tests.** `DeletePinImageTest`: add `renditionCache` mock + stub; assert `evictImage(image.id)` on the has-image delete path, and `verify(exactly = 0)` on the no-image (download-cancel) path. `PinRecycleBinTest`: add the mock; assert `evictImage(image.id)` from `permanentlyDelete` and from `emptyRecycleBin` (one soft-deleted pin with an image).

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-usecases:test --tests "DeletePinImageTest" --tests "PinRecycleBinTest"` → FAILS to compile.

- [ ] **Step 3: Wire `DeletePinImage`.** Add the import + `private val renditionCache: RenditionCache,` ctor param; in the `image != null` branch, evict after the file delete:

```kotlin
if (image != null) {
    imageRepository.deleteByPinId(pinId)
    imageStore.delete(image.storageKey)
    runCatching { renditionCache.evictImage(image.id) }
    clearPinDownload.clear(pinId)
    return
}
```

- [ ] **Step 4: Wire `PinRecycleBin`.** Add the import + ctor param. In `permanentlyDelete`, replace the trailing `image?.let { imageStore.delete(it.storageKey) }` with:

```kotlin
image?.let {
    imageStore.delete(it.storageKey)
    runCatching { renditionCache.evictImage(it.id) }
}
```

In `emptyRecycleBin`, collect the images (not just their storage keys) so each can be evicted:

```kotlin
fun emptyRecycleBin(user: User) {
    val pins = pinRepository.findAllSoftDeletedPinsForUser(user)
    val images = pins.mapNotNull { pin ->
        clearPinDownload.clear(pin.id)
        val image = imageRepository.findByPinId(pin.id)
        imageRepository.deleteByPinId(pin.id)
        image
    }
    pinRepository.permanentlyDeleteAllSoftDeletedPinsForUser(user)
    images.forEach {
        imageStore.delete(it.storageKey)
        runCatching { renditionCache.evictImage(it.id) }
    }
}
```

- [ ] **Step 5: Run to verify pass** — `./gradlew :api-usecases:test :api-usecases:koverVerify` → PASS.

- [ ] **Step 6: Commit**

```bash
git add api-usecases
git commit -m "feat(usecase): evict the rendition cache when the canonical image is deleted"
```

---

### Task 8: `RenditionsConfig` + `RenditionSize` + CDI producers (restores boot)

Adds the admin config, the size-name enum with its config-backed px resolution, and the CDI producers for `RenditionCache` + `ImageTransformer`. After this task the full app boots again.

**Files:**
- Create: `api-presentation-quarkus/.../config/RenditionsConfig.kt`, `api-presentation-quarkus/.../controllers/RenditionSize.kt`
- Modify: `api-application/.../wiring/ImageAdapterProducers.kt`
- Test: `api-presentation-quarkus/.../controllers/RenditionSizeTest.kt`

**Interfaces:**
- Produces:
  - `RenditionsConfig.tiny()/small()/medium()/large(): Int`, `webpQuality(): Int` (prefix `images.renditions`).
  - `enum RenditionSize { TINY, SMALL, MEDIUM, LARGE }` with `pxFrom(config: RenditionsConfig): Int` and `companion fun fromName(name: String): RenditionSize?`.
  - CDI beans `RenditionCache` (`FilesystemRenditionCache(dataDir)`) and `ImageTransformer` (`VipsImageTransformer(webpQuality)`).

- [ ] **Step 1: Write the failing test** (`RenditionSizeTest.kt`):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.RenditionsConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RenditionSizeTest {
    @Test
    fun `Given a known name, Then fromName resolves it case-insensitively`() {
        assertEquals(RenditionSize.SMALL, RenditionSize.fromName("small"))
        assertEquals(RenditionSize.LARGE, RenditionSize.fromName("LARGE"))
    }

    @Test
    fun `Given an unknown name, Then fromName returns null`() {
        assertNull(RenditionSize.fromName("huge"))
    }

    @Test
    fun `Given the config, Then pxFrom returns the configured value for each size`() {
        val config = mockk<RenditionsConfig>()
        every { config.tiny() } returns 112
        every { config.small() } returns 240
        every { config.medium() } returns 480
        every { config.large() } returns 960
        assertEquals(112, RenditionSize.TINY.pxFrom(config))
        assertEquals(240, RenditionSize.SMALL.pxFrom(config))
        assertEquals(480, RenditionSize.MEDIUM.pxFrom(config))
        assertEquals(960, RenditionSize.LARGE.pxFrom(config))
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-presentation-quarkus:test --tests "RenditionSizeTest"` → FAILS to compile.

- [ ] **Step 3: Create `RenditionsConfig`** (mirrors `ImageDownloadConfig`):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "images.renditions", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface RenditionsConfig {
    @WithDefault("112")
    fun tiny(): Int

    @WithDefault("240")
    fun small(): Int

    @WithDefault("480")
    fun medium(): Int

    @WithDefault("960")
    fun large(): Int

    @WithDefault("80")
    fun webpQuality(): Int
}
```

- [ ] **Step 4: Create `RenditionSize`:**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.RenditionsConfig

enum class RenditionSize {
    TINY, SMALL, MEDIUM, LARGE;

    fun pxFrom(config: RenditionsConfig): Int = when (this) {
        TINY -> config.tiny()
        SMALL -> config.small()
        MEDIUM -> config.medium()
        LARGE -> config.large()
    }

    companion object {
        fun fromName(name: String): RenditionSize? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
```

- [ ] **Step 5: Add the producers** to `ImageAdapterProducers` (import `RenditionCache`, `FilesystemRenditionCache`, `ImageTransformer`, `VipsImageTransformer`, `RenditionsConfig`):

```kotlin
@Produces
@ApplicationScoped
fun renditionCache(config: ImagesConfig): RenditionCache = FilesystemRenditionCache(config.dataDir())

@Produces
@ApplicationScoped
fun imageTransformer(config: RenditionsConfig): ImageTransformer = VipsImageTransformer(config.webpQuality())
```

- [ ] **Step 6: Run to verify pass + boot** —

```bash
./gradlew :api-presentation-quarkus:test :api-presentation-quarkus:koverVerify
./gradlew :api-application:test --tests "ImageHostingIntegrationTest"
```
The second command boots the fully-wired app; it must start (all `ImageTransformer`/`RenditionCache` injection points now satisfied), proving the WIRING REMINDER window is closed.

- [ ] **Step 7: Commit**

```bash
git add api-presentation-quarkus api-application
git commit -m "feat(presentation): rendition sizes config + CDI producers for the cache and transformer"
```

---

### Task 9: `ImageController` `size`/`animated` + `IMAGE_RENDITION_SIZE_INVALID` (400)

Routes every `GET .../image` through `GetPinImageRendition`, serving either the original (2a headers) or a WebP rendition (synthetic ETag). An unknown `?size=` maps to 400.

**Files:**
- Modify: `api-usecases/.../exceptions/ErrorCode.kt` (add `IMAGE_RENDITION_SIZE_INVALID`), create `api-usecases/.../exceptions/ImageRenditionSizeInvalidError.kt`
- Modify: `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` (map to 400), `api-presentation-quarkus/.../mappers/BaseErrorMapperTest.kt`
- Modify: `api-presentation-quarkus/.../controllers/ImageController.kt`, `api-presentation-quarkus/.../controllers/ImageControllerTest.kt`

**Interfaces:**
- Consumes: `GetPinImageRendition.get`, `ServedImage`, `RenditionCache.openStream`, `RenditionSize`, `RenditionsConfig`.
- Produces: `ErrorCode.IMAGE_RENDITION_SIZE_INVALID`; `ImageRenditionSizeInvalidError`; `ImageController.getImage(pinId, size: String?, animated: Boolean?, ifNoneMatch: String?)`.

- [ ] **Step 1: Write the failing tests.** In `BaseErrorMapperTest`, add:

```kotlin
@Test
fun `Given IMAGE_RENDITION_SIZE_INVALID, Then status is BAD_REQUEST`() {
    assertEquals(Response.Status.BAD_REQUEST, statusFor(ErrorCode.IMAGE_RENDITION_SIZE_INVALID))
}
```

In `ImageControllerTest`, replace the `getPinImage` mock with `getPinImageRendition`, add `renditionCache` + `renditionsConfig` mocks, drop `getPinImage` from the ctor, and cover: original served (200 + `contentHash` ETag), original 304, rendition served (200 + `image/webp` + `v1-<id>-<px>-a` ETag), rendition 304, unknown size throws `ImageRenditionSizeInvalidError`, and the streamed rendition body. Example rendition test:

```kotlin
@Test
fun `Given size small and a rendition, Then it serves image webp with a synthetic ETag`() {
    val pinId = randomUUID()
    val imageId = randomUUID()
    every { securityIdentity.getUser() } returns aUser()
    every { apiConfig.baseUrl() } returns "http://localhost"
    every { renditionsConfig.small() } returns 240
    every { getPinImageRendition.get(pinId, any(), 240, true) } returns
        ServedImage.Rendition(imageId, "240-a.webp", 240, animated = true)
    every { renditionCache.openStream(imageId, "240-a.webp") } returns ByteArrayInputStream(byteArrayOf(7, 7))

    val response = controller.getImage(pinId, size = "small", animated = null, ifNoneMatch = null)

    assertEquals(200, response.status)
    assertEquals("image/webp", response.headers.getFirst("Content-Type").toString())
    assertEquals("v1-$imageId-240-a", response.headers.getFirst("ETag").toString())
    val out = ByteArrayOutputStream()
    response.entity.write(out)
    assertArrayEquals(byteArrayOf(7, 7), out.toByteArray())
}

@Test
fun `Given an unknown size, Then it throws ImageRenditionSizeInvalidError`() {
    every { securityIdentity.getUser() } returns aUser()
    assertThrows(ImageRenditionSizeInvalidError::class.java) {
        controller.getImage(randomUUID(), size = "huge", animated = null, ifNoneMatch = null)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :api-usecases:test :api-presentation-quarkus:test` → FAILS to compile.

- [ ] **Step 3: Add the error code + exception.** `ErrorCode.kt`: add `IMAGE_RENDITION_SIZE_INVALID,` (last entry). `ImageRenditionSizeInvalidError.kt`:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

class ImageRenditionSizeInvalidError :
    ImageError("Unknown rendition size", ErrorCode.IMAGE_RENDITION_SIZE_INVALID)
```

(No em-dash in the message.) In `BaseErrorMapper.statusFor`, add the arm:

```kotlin
ErrorCode.IMAGE_RENDITION_SIZE_INVALID -> Response.Status.BAD_REQUEST.statusCode
```

- [ ] **Step 4: Rewrite `ImageController.getImage`.** Add imports (`GetPinImageRendition`, `ServedImage`, `RenditionCache`, `RenditionSize`, `RenditionsConfig`, `ImageRenditionSizeInvalidError`, `QueryParam`, `Image`). Change the ctor: remove `getPinImage`, add `private val getPinImageRendition: GetPinImageRendition`, `private val renditionCache: RenditionCache`, `private val renditionsConfig: RenditionsConfig`. Replace `getImage` and add helpers:

```kotlin
@GET
@Path("/{pinId}/image")
fun getImage(
    pinId: UUID,
    @QueryParam("size") size: String?,
    @QueryParam("animated") animated: Boolean?,
    @HeaderParam("If-None-Match") ifNoneMatch: String?,
): RestResponse<StreamingOutput> {
    val requester = securityIdentity.getUser()
    val requestedPx = size?.let { resolveSizePx(it) }
    return when (val served = getPinImageRendition.get(pinId, requester, requestedPx, animated ?: true)) {
        is ServedImage.Original -> serveOriginal(served.image, ifNoneMatch)
        is ServedImage.Rendition -> serveRendition(served, ifNoneMatch)
    }
}

private fun resolveSizePx(size: String): Int =
    (RenditionSize.fromName(size) ?: throw ImageRenditionSizeInvalidError()).pxFrom(renditionsConfig)

private fun serveOriginal(image: Image, ifNoneMatch: String?): RestResponse<StreamingOutput> {
    if (ifNoneMatch == image.contentHash) return RestResponse.notModified()
    val body = StreamingOutput { output -> imageStore.openStream(image.storageKey).use { it.copyTo(output) } }
    return ResponseBuilder.ok(body)
        .header("Content-Type", image.mimeType)
        .header("ETag", image.contentHash)
        .header("Cache-Control", "private, must-revalidate")
        .header("Content-Length", image.byteSize)
        .build()
}

private fun serveRendition(rendition: ServedImage.Rendition, ifNoneMatch: String?): RestResponse<StreamingOutput> {
    val etag = renditionEtag(rendition)
    if (ifNoneMatch == etag) return RestResponse.notModified()
    val body = StreamingOutput { output ->
        // The use case just confirmed/stored this entry; a null here means a concurrent evict
        // removed it (rare race) -> treat as gone.
        (renditionCache.openStream(rendition.imageId, rendition.key)
            ?: throw ImageDoesNotExistError()).use { it.copyTo(output) }
    }
    return ResponseBuilder.ok(body)
        .header("Content-Type", "image/webp")
        .header("ETag", etag)
        .header("Cache-Control", "private, must-revalidate")
        .build()
}

private fun renditionEtag(rendition: ServedImage.Rendition): String =
    "$ENCODER_VERSION-${rendition.imageId}-${rendition.effectivePx}-${if (rendition.animated) "a" else "s"}"
```

Add to the companion: `private const val ENCODER_VERSION = "v1"`. Import `ImageDoesNotExistError` from `api-usecases.exceptions`. Add `@Parameter` docs for `size`/`animated` on `getImage` for the generated openapi if the codebase annotates params (optional; the params appear regardless).

- [ ] **Step 5: Run to verify pass** — `./gradlew :api-usecases:test :api-presentation-quarkus:test :api-presentation-quarkus:koverVerify` → PASS (both `?:` arms, both `when` arms, both `if (ifNoneMatch...)` arms, and the null-cache race arm exercised).

- [ ] **Step 6: Regenerate the OpenAPI snapshot.** `docs/openapi.json` is a tracked snapshot (maintained since 2b). Rebuild it the same way 2b did so the GET `.../image` operation gains the `size` and `animated` query parameters, and commit the refreshed file with the code. Verify the diff only adds these two parameters (no unrelated churn).

- [ ] **Step 7: Commit**

```bash
git add api-usecases api-presentation-quarkus docs/openapi.json
git commit -m "feat(presentation): serve renditions via ?size and ?animated on GET image"
```

---

### Task 10: End-to-end integration test

Validates the whole flow through the fully-wired app with real libvips + real filesystem cache: WebP renditions with the correct shortest side, animated vs flattened output, original-as-is when the size is not smaller, 400 on an unknown size, and cache eviction on delete.

**Files:**
- Create: `api-application/src/test/resources/fixtures/animated.gif` (copied from the vips fixtures)
- Create: `api-application/.../RenditionsIntegrationTest.kt`

**Interfaces:**
- Consumes: the full HTTP API; `VipsImageProbe` + `StagedFile` (re-probe response bytes); `ImageRepositoryInterface`, `ImagesConfig` (disk assertions).

- [ ] **Step 1: Copy the animated fixture.**

```bash
cp api-imaging-vips/src/test/resources/fixtures/animated.gif api-application/src/test/resources/fixtures/animated.gif
```

- [ ] **Step 2: Write the failing test** (`RenditionsIntegrationTest.kt`). The profile shrinks `tiny`/`small` below the 10x10 fixtures so a real downscale happens:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.ImageFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.imaging.vips.VipsImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class RenditionsTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "images.data_dir" to "build/test-image-data/${UUID.randomUUID()}",
        "images.renditions.tiny" to "4",
        "images.renditions.small" to "6",
    )
}

@QuarkusTest
@TestProfile(RenditionsTestProfile::class)
class RenditionsIntegrationTest : IntegrationTest() {

    @Inject lateinit var userCreator: UserCreator
    @Inject lateinit var pinCreator: PinCreator
    @Inject lateinit var imageRepository: ImageRepositoryInterface
    @Inject lateinit var imagesConfig: ImagesConfig

    private fun fixture(name: String) = File("src/test/resources/fixtures/$name")

    private fun createUserAndPin(username: String, password: String): UUID {
        val user = userCreator.createUserWithPassword(username, password)
        val pin = pinCreator.createPin(
            author = user, sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/img.jpg", description = "rendition test", tags = emptyList(),
        )
        return pin.id
    }

    private fun upload(username: String, password: String, pinId: UUID, fixtureName: String, contentType: String) {
        given().auth().preemptive().basic(username, password)
            .multiPart("file", fixture(fixtureName), contentType)
            .`when`().put("/api/v1/pins/$pinId/image").then().statusCode(201)
    }

    private fun probeBytes(bytes: ByteArray): ProbeResult {
        val tmp = Files.createTempFile("resp-", ".bin")
        Files.write(tmp, bytes)
        return try {
            VipsImageProbe().probe(StagedFile(tmp.toString(), 0, ""), maxPixels = 1_000_000)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `Given a 10px image and size=tiny (4), Then GET returns a 4px WebP`() {
        val pinId = createUserAndPin("rtiny", "password123")
        upload("rtiny", "password123", pinId, "sample.png", "image/png")

        val bytes = given().auth().preemptive().basic("rtiny", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=tiny")
            .then().statusCode(200).contentType("image/webp").extract().asByteArray()

        val probe = probeBytes(bytes)
        assertEquals(ImageFormat.WEBP, probe.format)
        assertEquals(4, minOf(probe.width, probe.height))
    }

    @Test
    fun `Given no size, Then GET returns the original bytes`() {
        val pinId = createUserAndPin("rorig", "password123")
        upload("rorig", "password123", pinId, "sample.png", "image/png")

        given().auth().preemptive().basic("rorig", "password123")
            .`when`().get("/api/v1/pins/$pinId/image")
            .then().statusCode(200).contentType("image/png")
    }

    @Test
    fun `Given size=large (960) larger than the image, Then GET serves the original as-is`() {
        val pinId = createUserAndPin("rlarge", "password123")
        upload("rlarge", "password123", pinId, "sample.png", "image/png")

        given().auth().preemptive().basic("rlarge", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=large")
            .then().statusCode(200).contentType("image/png") // never upscaled, original format
    }

    @Test
    fun `Given an unknown size, Then GET returns 400`() {
        val pinId = createUserAndPin("rbad", "password123")
        upload("rbad", "password123", pinId, "sample.png", "image/png")

        given().auth().preemptive().basic("rbad", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=huge")
            .then().statusCode(400)
    }

    @Test
    fun `Given an animated GIF and animated=false, Then the rendition is a static WebP`() {
        val pinId = createUserAndPin("rflat", "password123")
        upload("rflat", "password123", pinId, "animated.gif", "image/gif")

        val bytes = given().auth().preemptive().basic("rflat", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=tiny&animated=false")
            .then().statusCode(200).contentType("image/webp").extract().asByteArray()

        assertFalse(probeBytes(bytes).animated)
    }

    @Test
    fun `Given an animated GIF and the default (animated), Then the rendition keeps the animation`() {
        val pinId = createUserAndPin("ranim", "password123")
        upload("ranim", "password123", pinId, "animated.gif", "image/gif")

        val bytes = given().auth().preemptive().basic("ranim", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=tiny")
            .then().statusCode(200).contentType("image/webp").extract().asByteArray()

        assertTrue(probeBytes(bytes).animated)
    }

    @Test
    fun `Given a cached rendition, Then deleting the image evicts the cache subtree`() {
        val pinId = createUserAndPin("revict", "password123")
        upload("revict", "password123", pinId, "sample.png", "image/png")
        val imageId = requireNotNull(imageRepository.findByPinId(pinId)).id
        // Generate + cache a rendition
        given().auth().preemptive().basic("revict", "password123")
            .`when`().get("/api/v1/pins/$pinId/image?size=tiny").then().statusCode(200)
        val cacheDir: Path = Path.of(imagesConfig.dataDir()).resolve("cache/$imageId")
        assertTrue(Files.exists(cacheDir), "rendition cache subtree should exist after first GET")

        // When: delete the image
        given().auth().preemptive().basic("revict", "password123")
            .`when`().delete("/api/v1/pins/$pinId/image").then().statusCode(204)

        // Then: the cache subtree is gone
        assertFalse(Files.exists(cacheDir), "rendition cache subtree should be evicted on delete")
    }
}
```

- [ ] **Step 3: Run to verify** — `./gradlew :api-application:test --tests "RenditionsIntegrationTest"`. It fails first if any wiring is off; iterate until all seven pass. This is where the animated `page-height` correctness (spec §13) is validated against a real 3-frame GIF end-to-end.

- [ ] **Step 4: Full gate** —

```bash
./gradlew detekt test koverVerify   # JDK 25 (JAVA_HOME on the JDK 25 toolchain)
```
Expected: BUILD SUCCESSFUL. (Benign shutdown-race log lines from `TaskWorkerLifecycle` may appear, as noted in the 2b handoff; they are not failures.)

- [ ] **Step 5: Commit**

```bash
git add api-application
git commit -m "test(images): end-to-end rendition serving, animation, and cache eviction"
```

---

## Notes for the implementer

- **JDK 25 toolchain**: `generateDbMigration` (Task 2) and `quarkusBuild`/full builds need `JAVA_HOME` on the JDK 25 toolchain locally (JDK-21-daemon quirk from prior handoffs); CI is fine.
- **libvips native** must be installed for `api-imaging-vips` and `api-application` tests.
- **The one real implementation-time risk** is animated `page-height` after `resize()` (Tasks 3 and 10). The re-probe assertions (`animated == true`, shortest side == N) are the guard. If they fail, apply the vips-ffm note's fallback (thumbnail the `n=-1`-loaded image, or embed `[n=-1]` in the load path) until the output re-probes as 3 pages at the right size.
- **Coverage**: never lower a threshold. If a new conditional is hard to cover, the fix is a test, not a `@Suppress` on the gate.

