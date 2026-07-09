# Handoff — Image Hosting 2a (canonical image)

**Date:** 2026-07-09
**Branch:** `feat/image-hosting-2a` (20 commits on top of `main` @ `b9dd000`)
**Spec:** `docs/specs/2026-07-08-image-hosting-2a.md` · **Plan:** `docs/plans/2026-07-08-image-hosting-2a.md`
**Status:** Feature complete. Full local gate green (`./gradlew detekt test koverVerify`). App boots end-to-end (`:api-application:test` 90/90). Merge-ready pending CI (`validate / gate`).

## What this delivers

A pin has ONE canonical image, owned by the pin's author:

- `PUT /api/v1/pins/{pinId}/image` — multipart upload (`file` part). 201 created / 200 replaced.
- `GET /api/v1/pins/{pinId}/image` — streams the bytes; `ETag` = content hash, `Cache-Control: private, must-revalidate`; `304` on matching `If-None-Match`.
- `DELETE /api/v1/pins/{pinId}/image` — 204.
- Owner-only: `403` non-owner, `404` missing pin/image, `413` oversize, `422` invalid/over-pixel.
- Bytes live on the filesystem (`<data_dir>/originals/<user_id>/<pin_id>/<image_id>.<ext>`), never in the DB. The DB holds only metadata + the storage key. Server-generated file names.
- Validation/measurement via native libvips (vips-ffm). Formats: PNG, JPEG, WebP (incl. animated), GIF. Limits `images.max_file_bytes` (30 MiB) / `images.max_pixels` (50 MP), configurable.
- Pin permanent-delete (single + empty-recycle-bin) cascades the image row + best-effort file removal. Soft-delete/restore do not touch images.

## Structure

- **api-domain**: `Image` entity (immutable), `ImageFormat` enum, ports `ImageStore`/`ImageProbe`/`ImageRepositoryInterface`, `StagedFile`/`ProbeResult`, sealed `ImageProbeException` family + `ImageTooLargeException`. `Pin.image: Image?` + `Pin.sourceMediaUrl: String?` (widened nullable).
- **api-storage-filesystem** (new module): `FilesystemImageStore` — stage (streaming SHA-256 + size guard + fsync), promote (atomic-move with fallback), openStream, delete, discard. Path-traversal + absolute-path guard.
- **api-imaging-vips** (new module): `VipsImageProbe` — format via the `vips-loader` header, pixel guard, unsupported/undecodable mapping.
- **api-persistence-sqlite**: `ImageModel` + mapper + `EbeanImageRepository` (create-or-replace by pin in one tx); migration `1.4` (images table + FK + `pins.source_media_url` widened via SQLite table-rebuild).
- **api-usecases**: `SetPinImage` (returns `SetPinImageResult(image, replaced)`), `GetPinImage`, `DeletePinImage`; image `ErrorCode`s + `ImageError` subclasses.
- **api-presentation-quarkus**: `ImageController`, `ImageOutputDto`/`ImageMapper`, `ImagesConfig`, `BaseErrorMapper` image-code arms.
- **api-application**: `wiring/ImageAdapterProducers` produces `ImageStore` from `ImagesConfig` (composition root); end-to-end integration tests.

## Learned pitfalls (read before touching this again)

1. **Bytecode floor was raised 21 → 25** (`c422f68`). vips-ffm's Gradle metadata declares `org.gradle.jvm.version: 22`, which a Java-21 consumer variant rejects. detekt 2.0 already runs on JDK 25, so the old floor (a detekt-1.23.8 relic) was removed. Toolchain and target are now both 25.
2. **Multipart over PUT WORKS** with RESTEasy Reactive `@RestForm`/`FileUpload` — the spec's flagged risk did NOT materialise. Never fall back to POST.
3. **`eclipse-temurin:25-jre` is Ubuntu 26.04** (not Debian), and `ubuntu-latest` is 24.04 — both past the 64-bit `time_t` transition, so the libvips runtime package is `libvips42t64` (not `libvips42`) in BOTH the Dockerfile and CI.
4. **FK `images → pins` is NOT enforced at runtime** — the SQLite `foreign_keys` pragma is OFF project-wide. Every FK in the schema is decorative. Use cases delete image rows explicitly (never rely on DB cascade/restrict).
5. **`FilesystemImageStore.promote` throws checked `IOException`** (disk full, perms). Any use case cleaning up around it must `catch (Exception)`, not `RuntimeException` — the original code caught `RuntimeException` and a `RuntimeException`-mock test hid the leak (fixed in `690a2ee`).
6. **The request-logging filter** (`LoggingRequestResponseFilter`) buffered the whole request body into memory; now skipped for `multipart/form-data` so 30 MiB uploads are not held in memory.
7. **Adapter producers belong in the composition root** (`api-application`), not presentation — presentation must not depend on a storage/imaging adapter module (layer purity).
8. **`ImageInvalidError`** carries a fixed client-facing message; probe detail is preserved via `cause` (not echoed to the caller).

## NOT validated against real environment / hardware

- **CI has not run yet.** The `libvips42t64` package name on `ubuntu-latest` is only fully confirmed when this PR's `test` job runs. The Docker runtime image + libvips was validated by a LOCAL `docker build` (health endpoint UP), not in CI.
- **Large uploads / real limits**: integration tests use tiny fixtures and a tiny-`max_file_bytes` `@TestProfile` for the 413 path. No real 30 MiB / near-50 MP image was pushed through.
- **Animated WebP/GIF**: validated with 3-frame synthesised fixtures (`n-pages: 3`), not a broad corpus of real animated images.
- **Filesystem only** — no S3/object-store (deliberate; a future port can add it).
- Local `quarkusBuild` fails on a JDK-21 Gradle daemon (host quirk); CI runs everything on JDK 25.

## Deferred (non-blocking) — see `.superpowers/sdd/progress.md` "Deferred" section

ETag not quoted (RFC 7232) + 304 omits validators; `images` table omits Ebean `@Version` (benign — rows are insert/delete only); `docs/openapi.json` GET is generated as `application/json`/`StreamingOutput` with `If-None-Match` required (annotate later); detekt `ThrowsCount:max=2` accreting baselines (bump to 3 / `excludeGuardClauses`); the two "does not exist" errors share one `ErrorCode`; test bodies miss `// Given/When/Then` comments; `ImagesConfigTest` uses an anonymous impl.

## Suggested next step

**Sub-project 2b** — mode B (server-fetch download via the task queue), disposable renditions/thumbnails (the future optimisation cache under `<data_dir>/cache/`, reserved but not created here), and perceptual `ImageHash`. The task queue (sub-project 1) and this canonical-image layer are the foundations it builds on.
