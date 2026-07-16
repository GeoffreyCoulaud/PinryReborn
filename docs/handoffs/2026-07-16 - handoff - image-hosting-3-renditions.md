# Handoff — Image Hosting 3 (disposable renditions / thumbnails)

**Date:** 2026-07-16
**Branch:** `feat/image-hosting-3-renditions` (18 commits on top of `main` @ `a36f3d6`)
**Spec:** `docs/specs/2026-07-15-image-hosting-3-renditions.md` · **Plan:** `docs/plans/2026-07-15-image-hosting-3-renditions.md`
**Status:** Feature complete. Full local gate green (`detekt test koverVerify`, JDK 25). App boots end-to-end;
`RenditionsIntegrationTest` 9/9 against the real wired app + real libvips + the real filesystem cache.
Merge-ready pending CI (`validate / gate`).

## Why this exists

A Pinry-style grid loads dozens of images at once. Serving canonical originals for every tile is slow and
wasteful. This sub-project serves small WebP thumbnails ("renditions"), generated lazily on first request and
cached on disk, while keeping the original available as a first-class choice (the client may still want full
quality in the grid).

This was deferred out of 2b and fills the "renditions cache" seam that spec 2a §16 left open.

## What this delivers

`GET /api/v1/pins/{pinId}/image` gains two query parameters:

- `?size=<tiny|small|medium|large>` — the named size resolves (server-side, admin-configurable) to a pixel
  value that becomes the rendition's **shortest side**. Unknown name → **400**. No `size` → the original bytes.
- `?animated=<bool>` — defaults to **true** (animation preserved). `animated=false` flattens an animated source
  to its first frame. It is a **no-op on a static source** (same bytes, same cache entry, same ETag).

Invariants:

- Output is always **WebP** for a generated rendition. The canonical original is **never re-encoded**.
- **Never upscale**: if the original's shortest side `<=` the requested size, the ORIGINAL bytes and format are
  served as-is (no generation, no WebP conversion).
- `effectivePx = min(requestedPx, min(width, height))`.
- Renditions have **no DB row**. They are disposable and regenerable.
- Guards are unchanged from 2a: missing pin / image-less pin → 404, non-owner → 403 (delegated verbatim to the
  existing `GetPinImage.get`).

Cache: `<data_dir>/cache/<imageId>/<encoderVersion>-<effectivePx>-<a|s>.webp`. The per-image subtree is the
eviction unit. Rendition ETag: `"<encoderVersion>-<imageId>-<effectivePx>-<a|s>"` (synthetic, deterministic);
the original keeps its `contentHash` ETag. Eviction is best-effort (`runCatching`) at **all five** canonical-image
drop sites: `SetPinImage` replace, `DownloadPinImage` mode-B swap, `DeletePinImage`, `PinRecycleBin.permanentlyDelete`,
`PinRecycleBin.emptyRecycleBin`.

Config (`images.renditions.*`, admin-modifiable, materialised in `application.properties`):
`tiny=112`, `small=240`, `medium=480`, `large=960`, `webp_quality=80`.

## Structure (what is new vs 2b)

- **api-domain**: `Image` gains `animated: Boolean` (after `height`); `ProbeResult` gains `animated`; new
  `RenditionSpec(shortestSide, animated)`, `ImageTransformer` port (`render(source, spec): StagedFile`), and
  `RenditionCache` port (`openStream / store / evictImage`).
- **api-imaging-vips**: `VipsImageProbe` derives `animated` from `n-pages`; new `VipsImageTransformer(quality)`
  (WebP by shortest side, animation-preserving or first-frame).
- **api-storage-filesystem**: new `FilesystemRenditionCache(dataDir)`; new `internal DataDirPaths(dataDir)` holding
  the shared `resolveWithinRoot` + `atomicMove` primitives factored out of `FilesystemImageStore` (both adapters now
  delegate to it).
- **api-usecases**: new `GetPinImageRendition` + sealed `ServedImage { Original, Rendition }`; `ErrorCode.IMAGE_RENDITION_SIZE_INVALID`
  + `ImageRenditionSizeInvalidError`; `SetPinImage` / `DownloadPinImage` / `DeletePinImage` / `PinRecycleBin` each gain a
  `renditionCache` ctor param and evict best-effort.
- **api-persistence-sqlite**: `ImageModel.animated` (`@DbDefault("false")`), mapper both ways, migration **1.6**.
- **api-presentation-quarkus**: `RenditionsConfig` (`@ConfigMapping(prefix = "images.renditions")`), `RenditionSize`
  enum (name → px), `ImageController.getImage` routed through `GetPinImageRendition`, `BaseErrorMapper` 400 arm.
- **api-application**: `ImageAdapterProducers` gains `RenditionCache` + `ImageTransformer` producers; new
  `RenditionsIntegrationTest` + a copied `animated.gif` fixture.

## Learned pitfalls (read before touching this again)

1. **libvips `n` load option + a loader that has no `n` property = a GLib CRITICAL on every call.** Passing
   `n` to `pngload`/`jpegload` logs `object class 'VipsForeignLoadPngFile' has no property named 'n'`. Two things
   are needed and BOTH matter: (a) `VipsImageTransformer` passes `n = -1` only when `spec.animated`; (b)
   `GetPinImageRendition` computes `effectiveAnimated = animated && image.animated`, so a static source never
   reaches the transformer with `animated = true`. Fixing only (a) leaves the CRITICAL firing on the **dominant**
   production path (a static image at the default `animated=true`) — that exact half-fix shipped here and was
   caught only by the final holistic review, which verified it empirically.
2. **`VImage.resize()` does NOT update `page-height`.** Resizing a multi-page image scales the tall strip but
   leaves the frame height stale, corrupting animated frame boundaries. `VipsImageTransformer` re-sets
   `page-height` for animated output. The guard is the re-probe assertion in `VipsImageTransformerTest` and,
   end-to-end, `RenditionsIntegrationTest` against a real 3-frame GIF. Do not weaken those.
3. **`flatten()` is alpha-blending, NOT frame reduction.** It is a trap for "flatten an animation to one frame".
   Load with `n = 1` (the loader default) instead.
4. **The encoder version must live in the cache KEY, not only the ETag.** Version-in-ETag-only is *worse than no
   version*: the client's validator changes, the server keeps hitting the old key, and it serves stale bytes stamped
   with the new validator, permanently. `ENCODER_VERSION` now has ONE definition (`GetPinImageRendition`'s companion),
   used by both the key and the controller's ETag.
5. **`ImageTransformer.render` returns a temp the caller owns; `RenditionCache.store` now takes that ownership**
   (moved on success, discarded on failure). Without it, a failing store (full/read-only data dir) orphans one temp
   *per request* into `java.io.tmpdir` — frequently tmpfs, so it converts into RAM exhaustion.
6. **Renditions stage in `java.io.tmpdir` while the cache destination is under `<data_dir>`** — frequently different
   filesystems, which makes the `AtomicMoveNotSupportedException` fallback in `DataDirPaths.atomicMove` the **common**
   path, not the rare one. That fallback needs `REPLACE_EXISTING` or two concurrent misses on one key make the loser
   500. (`FilesystemImageStore` does not have this exposure: it stages under `<data_dir>/tmp/`.) A cleaner future fix
   is to stage renditions under `<data_dir>/tmp/` too.
7. **A defaulted Kotlin data-class param generates a synthetic constructor branch that Kover's 100%-branch gate
   flags.** Hence `Image.animated` and `ProbeResult.animated` have NO default, and every call site was updated
   explicitly. Same family: an **expression-form `when` over a sealed type** compiles an unreachable
   `else -> throw NoWhenBranchMatchedException()` that Kover flags — use the statement form (precedent:
   `TaskProcessor.settle`).
8. **The path guard is ROOT-containment, not subtree-containment.** `resolveWithinRoot("cache/<id>/../../etc/passwd")`
   normalizes to `<data_dir>/etc/passwd`, which is INSIDE the root and therefore **not** rejected. A traversal test
   needs enough `../` to climb past the (unknown-depth) temp root to the filesystem root. The plan's original
   2-dot test would have silently passed nothing.
9. **detekt `ReturnCount` is max=2 with `excludeGuardClauses: false`, and this codebase has zero
   `@Suppress("ReturnCount")`** — restructure into helpers, do not suppress.
10. **Gate the touched module's `detekt` per task, not just `test` + `koverVerify`.** Task 5 shipped two detekt
    violations that only surfaced during Task 6 because its own gate omitted detekt.
11. **SQLite has no native boolean**: Ebean renders `Boolean` as `int default 0 not null` (migration 1.6), matching
    the prior `1.3` `cancel_requested` convention. Expected, not a generator quirk.
12. **`./gradlew detekt` (the CI gate) does not cover test sources.** `detektTest` reports 3 pre-existing findings
    that CI never sees. Don't panic-fix them thinking the gate is red.

## NOT validated against real environment / hardware

- **CI has not run yet.** Green only on the local JDK-25 gate.
- **Pre-existing DB rows get `animated = false` from migration 1.6.** Any animated GIF already stored in a live
  database is mislabelled until re-uploaded, and there is **no backfill**. Narrow but real consequence: for such a
  row, `?size=large&animated=false` where `px >= srcShort` serves the original **animated** bytes instead of
  flattening. The downscale paths still produce correct output (the transformer loads with `n = 1` and gets the
  first frame regardless). A backfill would re-probe every `images` row.
- **Only tiny fixtures were exercised**: 10x10 PNG/GIF (3 frames). No large or real-world images, no long animation,
  no near-50MP source through the transformer. Render time, memory, and WebP quality at real sizes are unmeasured.
- **Concurrency is unvalidated under real load.** Single-flight de-duplication is deliberately out of scope; two
  concurrent misses double-render and the last move wins (now genuinely benign on both move paths, but only proven
  by a deterministic same-key-twice test, not by real concurrent traffic).
- **libvips 8.18.4 / vips-ffm 1.9.8 only.** Other libvips versions untested; `page-height` behaviour is
  version-sensitive.
- **Renditions are served without `Content-Length`** (chunked). The spec allowed either; no real client has
  exercised it.
- **Rendition ETags are emitted unquoted**, matching the pre-existing 2a `contentHash` convention but diverging
  from RFC 9110 and from spec §7's quoted form. Round-trips fine with clients that echo verbatim; untested through
  a real proxy or CDN.
- **Disk growth is bounded only by eviction.** No GC sweep exists, so an eviction that fails (best-effort, errors
  swallowed) or a crash mid-write leaves an orphaned subtree forever.

## Deferred (non-blocking) — full list in `.superpowers/sdd/progress.md`

Agreed with the holistic reviewer as non-blocking:

- **`serveRendition` (and pre-existing `serveOriginal`) throw from inside the `StreamingOutput` lambda**, after a
  200 + headers are already committed; depending on container buffering the client may not see the mapped 404.
  Inherited 2a design; requires a rare concurrent-evict race.
- **OpenAPI `size`/`animated` show `required: true` while nullable** — SmallRye+Kotlin behaviour, consistent with
  the existing `limit` / `q` / `If-None-Match` entries in the same tracked snapshot. Confusing for API consumers;
  fix repo-wide or not at all.
- **3 pre-existing `detektTest` findings** (`IntegrationTest.kt` abstract-without-abstract-member; `user2` unused in
  `PinRetrieval`/`PinTaggingIntegrationTest`). Not in the CI-gated `detekt` task; predate this sub-project.
- **Single-flight de-duplication** of concurrent misses, if render cost ever justifies it.
- **A GC sweep** for orphaned cache subtrees (spec §14).
- **An `animated` backfill migration** for pre-existing rows (see NOT-validated above).
- **Staging renditions under `<data_dir>/tmp/`** to make the atomic move path the common one again (pitfall 6).

## Suggested next step

The perceptual `ImageHash` for pin deduplication (deliberately YAGNI'd in 2b and still unbuilt), or, if renditions
go to production first, the two operational gaps above: the `animated` backfill and the cache GC sweep. The backfill
is the only one with a correctness consequence.
