# Image hosting — sub-project 3: disposable renditions (thumbnails)

Date: 2026-07-15
Status: approved design, pending implementation plan
Depends on: the canonical image (2a, merged) and server-side ingestion (2b, merged).
This is **sub-project 3** of the image-hosting effort; it fills the "renditions cache" seam
left open in 2a §16.

## 1. Goal

Let the web UI render a pin grid that **loads fast without badly compromising quality**, by
serving small, cheap **renditions** (thumbnails) of a pin's canonical image instead of the
full-resolution original. The client asks for a named size; the server returns a scaled
image. Renditions are a disposable, regenerable **optimisation cache**, never a domain
concept (2a §3).

## 2. Scope

**In scope (3):**

- Named sizes **tiny / small / medium / large**, whose pixel value (the length of the image's
  **shortest side**) is admin-configurable. The client requests a size **by name**; it never
  sends a pixel value.
- Lazy generation with an on-disk cache under `<data_dir>/cache/`: a rendition is produced on
  the first `GET` that needs it (libvips), written to the cache, and served from the cache
  thereafter.
- Serving via the existing endpoint, content-negotiated by a query parameter:
  `GET /api/v1/pins/{pinId}/image?size=<name>[&animated=<bool>]`. No `size` = the original
  (unchanged from 2a; a first-class use case, e.g. a full-resolution gallery).
- WebP output for every generated rendition.
- Animated sources (GIF / animated WebP): the rendition **preserves animation by default**
  (`animated=true`), or is **flattened to the first frame** on `animated=false`.
- "Never upscale": an original whose shortest side is already ≤ the requested size is served
  **as-is** (its original bytes and format), no generation.
- A new `animated: Boolean` field on the canonical `Image` (needed for the flatten decision;
  the only touch to the 2a canonical).
- Best-effort cache eviction wired into the three points where a canonical image is dropped
  (replace, delete, hard-delete/recycle-bin).

**Out of scope (deferred; §13):**

- **Format negotiation** via `Accept` (WebP is the sole output format; the client does not
  choose a format).
- **`srcset`-style multi-size responses**: the client picks one size per request.
- **A periodic GC** sweeping orphaned cache subtrees. Eviction is best-effort synchronous;
  a residual orphan after a crash is benign (regenerable disk), and a GC sweep is a future
  safety net.
- **Single-flight** de-duplication of concurrent misses (atomic writes make a double-run
  benign).
- **`ImageHash`** (perceptual) — still deferred to the pin-merge feature.
- **A capabilities endpoint** exposing the configured pixel values (the client requests by
  name; YAGNI).

## 3. Key decisions (rationale captured for the plan)

- **"Size" = the shortest side, aspect ratio preserved, no crop.** A rendition whose shortest
  side is N guarantees the image covers at least an N×N cell, which is what a CSS `cover`
  grid wants. This is a downscale-to-shortest-side, never a crop.
- **Lazy, not eager.** Nothing is generated at ingestion. The first `GET` of a size generates
  and caches it. This produces only what is actually requested (zero waste for sizes never
  viewed), adds no step to ingestion (notably the 2b async flow), and matches the "disposable
  cache" framing. The first-hit latency (one synchronous libvips render, tens of ms) is a
  non-issue: every later hit is served from the cache with an `ETag`.
- **WebP for every rendition.** Best weight/quality ratio for a grid, handles transparency
  and animation, and a single output format simplifies the cache. The canonical original is
  **never** re-encoded; it keeps its source format.
- **Never upscale; serve the original as-is when it is already small enough.** No generation,
  no cache entry, no re-encode: the original bytes (original format, original `Content-Type`)
  are streamed. A rendition is produced **only** when a real transformation is required
  (downscale and/or flatten).
- **Animation preserved by default.** `animated=true` is the default for an animated source;
  `animated=false` flattens to the first frame (a lighter static thumbnail). The parameter is
  a no-op on a non-animated source.
- **`animated` is added to the canonical `Image`.** The serve flow must know whether the
  source is animated to decide flattening; deriving it from the MIME type would be imprecise
  (a static GIF/WebP would be needlessly re-encoded on `animated=false`). The probe already
  reads the header, so it can read the page count in the same pass. This is legitimate domain
  data (also reusable for a UI "GIF" badge).
- **Renditions have no database row.** They are pure filesystem, keyed by convention under
  `cache/<imageId>/…`, regenerable at any time. A dedicated `RenditionCache` port keeps the
  cache semantics (lookup-or-miss / store / evict-by-image) distinct from the `ImageStore`
  that manages originals.
- **The cache key uses the effective pixel value, not the size name.** `effectivePx =
  min(config(size), shortestSideOfOriginal)`. Changing an admin config value produces new keys
  (old ones orphaned, benign); two sizes that clamp to the same native dimension share one
  rendition (automatic dedup).
- **403 vs 404** semantics unchanged from 2a: non-owner → 403, missing pin / image-less pin →
  404.

## 4. Modules (dependency DAG preserved)

| Module                     | Change                                                                 |
|----------------------------|------------------------------------------------------------------------|
| `api-domain`               | `Image.animated`; `ProbeResult.animated`; new ports `ImageTransformer`, `RenditionCache`; a `RenditionSpec` value type. |
| `api-usecases`             | new `GetPinImageRendition`; `SetPinImage` / `DownloadPinImage` / `DeletePinImage` / `PinRecycleBin` call `RenditionCache.evictImage`. |
| `api-imaging-vips`         | `VipsImageProbe` reads the page count (animated); new `VipsImageTransformer`. |
| `api-storage-filesystem`   | new `FilesystemRenditionCache`.                                        |
| `api-persistence-sqlite`   | `ImageModel.animated`; mapper; migration `1.6`.                        |
| `api-presentation-quarkus` | `ImageController.getImage` gains `size` / `animated`; `RenditionSizesConfig`; a `Size` enum + 400 mapping. |
| `api-application`          | producer for `ImageTransformer`; producer/wiring for `RenditionCache`; integration tests. |

No layer is punched through: presentation depends on usecases + domain; the vips and
filesystem adapters depend on `api-domain` only.

`FilesystemRenditionCache` lives in `api-storage-filesystem` alongside `FilesystemImageStore`:
adapters are grouped by infrastructure mechanism (filesystem), not by port, matching the repo
convention (`api-persistence-sqlite` holds every Ebean repository; `api-imaging-vips` holds
probe + transformer). The two filesystem adapters share the path-safety (`resolveWithinRoot`)
and atomic temp→move primitives, which are factored out (e.g. into a small internal helper)
rather than duplicated. The durable-vs-disposable boundary is carried by the two distinct
domain ports (`ImageStore` vs `RenditionCache`), which is the right place for it.

## 5. Domain model changes

### 5.1 `Image` gains `animated`

```kotlin
data class Image(
    override val id: UUID,
    val pinId: UUID,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val animated: Boolean,   // NEW
    val byteSize: Long,
    val contentHash: String,
    val storageKey: String,
    val createdAt: Instant,
) : Identifiable
```

`ProbeResult` gains `animated: Boolean`; `VipsImageProbe` derives it from the loaded image's
page count (`n-pages` > 1). `SetPinImage` and `DownloadPinImage` thread it through when they
build the `Image`.

### 5.2 New ports (pure, in `api-domain`)

```kotlin
// The transform request: shortest side to fit (px), and whether to keep animation.
data class RenditionSpec(val shortestSide: Int, val animated: Boolean)

interface ImageTransformer {
    // Render [source] to a fresh temp WebP file per [spec], returning it as a StagedFile
    // (path + byteSize + contentHash). Never upscales beyond the source's native size.
    fun render(source: InputStream, spec: RenditionSpec): StagedFile
}

interface RenditionCache {
    // Open a read stream for a cached rendition, or null on a miss.
    fun openStream(imageId: UUID, key: String): InputStream?
    // Atomically move a staged temp file into the cache at (imageId, key).
    fun store(imageId: UUID, key: String, staged: StagedFile)
    // Best-effort: delete the whole cache subtree for an image (idempotent).
    fun evictImage(imageId: UUID)
}
```

`StagedFile` is reused from `api-domain/images` (already `path + byteSize + contentHash`).

## 6. Rendition decision matrix

Let `srcShort = min(image.width, image.height)` and `px = config(size)`. The serve flow serves
the **original as-is** iff no transformation is required — i.e. neither a downscale
(`srcShort > px`) nor a flatten (`image.animated && !animated`) is needed. Otherwise it
produces a WebP rendition.

| Source   | Params                            | Result                                        |
| -------- | --------------------------------- | --------------------------------------------- |
| static   | `px >= srcShort`                  | original as-is                                |
| static   | `px < srcShort`                   | downscale to WebP                             |
| animated | `animated=true`, `px >= srcShort` | original as-is (animated)                     |
| animated | `animated=true`, `px < srcShort`  | downscale to animated WebP                    |
| animated | `animated=false`, `px >= srcShort`| flatten first frame to static WebP (no resize)|
| animated | `animated=false`, `px < srcShort` | downscale + flatten to static WebP            |

`effectivePx = min(px, srcShort)` is the shortest side of the produced rendition (and part of
its cache key). No `size` at all short-circuits to the original before this matrix.

## 7. REST API

### `GET /api/v1/pins/{pinId}/image` — serve (2a, extended)

Query parameters (both optional):

- `size` ∈ `{tiny, small, medium, large}`. Absent → the canonical original (unchanged 2a
  behaviour: original bytes, `ETag = contentHash`, original `Content-Type`).
- `animated` ∈ `{true, false}`, default `true`. Only meaningful for an animated source.

Responses:

- **200** with the served bytes. For a rendition: `Content-Type: image/webp`,
  `ETag: "<encoderVersion>-<imageId>-<effectivePx>-<a|s>"`, `Cache-Control: private,
  must-revalidate`. For an as-is original: exactly the 2a headers.
- **304** on a matching `If-None-Match` (same conditional-GET handling as 2a).
- **400** if `size` is present but not a known name.
- **403** non-owner; **404** missing pin or image-less pin.
- **500** if libvips fails to render an image that passed probe (rare); the staged temp is
  cleaned up best-effort.

The other operations (`PUT` mode A/B, `GET …/status`, `DELETE`) are unchanged from 2a/2b.

## 8. The serve flow (`GetPinImageRendition`)

The use case orchestrates ports and returns a **descriptor of what to serve**; the controller
streams (as `GetPinImage` does today), keeping I/O at the edge. Following the existing pattern
(`SetPinImage` receives `maxBytes` / `maxPixels` as primitives resolved by the controller from
`ImagesConfig`), the **controller resolves the size name to a px value** and passes it in; the
use case never touches presentation config. Signature:

```kotlin
fun get(pinId: UUID, requester: User, requestedPx: Int?, animated: Boolean): ServedImage
```

`requestedPx == null` means "no `size`" (the enum→px resolution and the 400-on-unknown-name
happen at the edge, §11).

```
1. image = getPinImage.get(pinId, requester)     // REUSE 2a's load + owner/404 guards verbatim
2. if requestedPx == null → return Original(image)             // stream via ImageStore
3. needsDownscale = min(image.width, image.height) > requestedPx
   needsFlatten   = image.animated && !animated
   if !needsDownscale && !needsFlatten → return Original(image)  // never upscale, no re-encode
4. effectivePx = min(requestedPx, min(image.width, image.height))
   key = "$effectivePx-${if (animated) "a" else "s"}.webp"
   cached = renditionCache.openStream(image.id, key)
   if cached != null → return Rendition(image.id, key, effectivePx, animated)   // hit
5. staged = imageTransformer.render(imageStore.openStream(image.storageKey),
                                    RenditionSpec(effectivePx, animated))
   renditionCache.store(image.id, key, staged)                                   // atomic move
   return Rendition(image.id, key, effectivePx, animated)                        // miss → generated
```

`Original(image)` and `Rendition(imageId, key, effectivePx, animated)` are the two arms of the
returned descriptor (a sealed `ServedImage` type). The controller maps `Original` to the 2a
streaming path, and `Rendition` to `renditionCache.openStream(imageId, key)` + the WebP/ETag
headers of §7.

Note: step 1 **delegates to the existing `GetPinImage.get`** for the load + owner/not-found
guards, so the no-size path is byte-identical to 2a and the guards are not duplicated. The
controller routes every `GET …/image` (with or without `size`) through `GetPinImageRendition`.

## 9. Cache layout, key, ETag

- **Layout**: `<data_dir>/cache/<imageId>/<effectivePx>-<a|s>.webp`. The per-image subtree is
  the eviction unit.
- **Key** (`<effectivePx>-<a|s>.webp`): derived from the effective pixel value (not the size
  name) and the animation flag. Config changes orphan old keys (benign); sizes clamping to the
  same native dimension dedup to one file.
- **ETag**: `"<encoderVersion>-<imageId>-<effectivePx>-<a|s>"`, a stable synthetic validator.
  A rendition's bytes are fully determined by these inputs (libvips encoding is
  deterministic), so the ETag survives regeneration. `encoderVersion` is a bumpable constant
  to invalidate cleanly if encoding parameters ever change. The original keeps its
  `contentHash` ETag.
- **Concurrency**: `RenditionCache.store` writes via temp + atomic move. Two concurrent misses
  are benign (identical bytes; last move wins). No single-flight lock.

## 10. Lifecycle cascade (eviction)

The whole `cache/<imageId>/` subtree is orphaned the moment its canonical image is dropped.
The three existing sites that already delete the old **canonical file** additionally call
`renditionCache.evictImage(oldImageId)`, **best-effort** (errors swallowed, mirroring the 2b
superseded-file delete):

- `SetPinImage` (mode-A replace) — after the new row commits, evicting the replaced image.
- `DownloadPinImage.promoteAndSwap` (mode-B replace) — on a real swap only.
- `DeletePinImage` and `PinRecycleBin.permanentlyDelete` / `emptyRecycleBin` — on delete /
  hard-delete.

Best-effort is safe because the cache is disposable and regenerable: a residual orphan after a
crash is disk only, never a correctness bug, and is reclaimable by the deferred GC sweep.

## 11. Configuration

Extends `images` (`@ConfigMapping(prefix = "images", SNAKE_CASE)`):

| Key                              | Default | Meaning                     |
| -------------------------------- | ------- | --------------------------- |
| `images.renditions.tiny`         | `112`   | shortest-side px for `tiny`  |
| `images.renditions.small`        | `240`   | shortest-side px for `small` |
| `images.renditions.medium`       | `480`   | shortest-side px for `medium`|
| `images.renditions.large`        | `960`   | shortest-side px for `large` |
| `images.renditions.webp_quality` | `80`    | WebP encode quality (0-100)  |

Defaults are a starting point (to confirm during the plan). A `Size` enum maps a name to its
configured px via this config; an unknown `?size=` value is rejected as **400** before the use
case runs.

## 12. Testing strategy (TDD, project order)

100% branch coverage per package (`api-imaging-vips`, `api-storage-filesystem`,
`api-usecases`, presentation all in-gate). Write each test failing first.

1. **Integration** (`api-application`, REST Assured + real libvips): `?size` returns
   `image/webp` with the correct shortest side; `animated=true` keeps > 1 page, `animated=false`
   yields 1 page; an original ≤ the requested size is served as-is (original `Content-Type`,
   no WebP); unknown `?size` → 400; a cached rendition is evicted on replace and on delete
   (a second `GET` re-generates). A `@TestProfile` sets a temp `data_dir` and small rendition
   sizes to keep fixtures cheap.
2. **Use-case** (`api-usecases`, MockK): the full decision matrix (six rows + no-size +
   404/403), cache hit vs miss (miss calls `render` then `store`, hit calls neither), and
   `evictImage` wired at the three eviction sites. Both branches of every conditional.
3. **Adapters**:
   - `VipsImageProbe`: `animated` true for a multi-page GIF/WebP, false for a single-page
     source (extends the existing probe tests).
   - `VipsImageTransformer`: renders real bytes; re-probe asserts format=WebP, correct shortest
     side, page count per the `animated` flag, and never-upscale (a small source stays native).
   - `FilesystemRenditionCache`: `store` then `openStream` round-trips; `openStream` returns
     null on a miss; `evictImage` removes the subtree and is idempotent; atomic-move path.

## 13. Risks to verify during implementation

- **libvips shortest-side fit**: `VImage.thumbnail` fits the *longest* side by default. Fitting
  the *shortest* side without cropping needs the right option set (e.g. a manual scale from
  `min(w,h)`, or `thumbnail` with an explicit height and `size=down`, `crop=none`). Verify the
  produced shortest side equals `effectivePx` and no crop occurs, for both portrait and
  landscape.
- **Animation through thumbnail**: loading with all pages (`n=-1`) and preserving animation on
  resize vs flattening (`page=0` / `n=1`). Verify page counts on the output.
- **Animated WebP encode**: confirm the vips-ffm binding encodes a multi-page WebP (quality,
  loop) as expected.
- **`Content-Length` for a rendition**: unlike the original (byteSize known from the row), a
  rendition's size is known only after generation (`staged.byteSize`) — set it from the staged
  file, or omit and stream chunked.
- **Never-upscale on the flatten-only path**: `animated=false, px ≥ srcShort` must flatten
  without resizing (output shortest side = native).

## 14. Future seams (left open, not built in sub-project 3)

- **Periodic GC** sweeping `cache/<imageId>/` subtrees whose image no longer exists (the safety
  net behind best-effort eviction).
- **Format negotiation** via `Accept` (e.g. AVIF for clients that advertise it).
- **Pre-generation** (eager) of the hottest grid size, if first-hit latency ever matters.
- **Single-flight** de-duplication of concurrent misses, if render cost ever justifies it.
- **A capabilities endpoint** exposing the configured pixel values for each size name.
- **`ImageHash`** (perceptual) for the pin-merge feature.
