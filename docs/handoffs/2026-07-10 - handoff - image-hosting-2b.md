# Handoff — Image Hosting 2b (server-side ingestion, "mode B")

**Date:** 2026-07-10
**Branch:** `feat/image-hosting-2b` (28 commits on top of `main` @ `e8fe6e4`)
**Spec:** `docs/specs/2026-07-10-image-hosting-2b.md` · **Plan:** `docs/plans/2026-07-10-image-hosting-2b.md`
**Status:** Feature complete. Full local gate green (`./gradlew detekt test koverVerify`, JDK 25). App boots end-to-end; `ModeBImageHostingIntegrationTest` 12/12 against a real local origin + real libvips + the real async worker. Merge-ready pending CI (`validate / gate`).

## Why this exists

The web UI must be able to create a pin from a URL. A browser generally cannot read cross-origin image bytes (CORS: opaque `no-cors` fetch, tainted canvas), so client-side upload (mode A, 2a) cannot cover URL-only pinning. The SERVER fetches the image instead (not bound by CORS). Mode A (web extension, direct file upload) stays; mode B serves web-UI-only users pinning from a URL.

## What this delivers

Same endpoint, content-negotiated:

- `PUT /api/v1/pins/{pinId}/image`
  - `multipart/form-data` → **mode A** (synchronous, unchanged from 2a): 201/200. Additionally now **cancels any in-flight download and clears the pin's download row** (a direct upload wins over a slow/failing fetch).
  - `application/json {"sourceUrl":"<http(s) url>"}` → **mode B** (async): validates the URL (http/https only; malformed/other scheme → 400), atomically upserts a PENDING `ImageDownload` row + enqueues a `pin.download` task, returns **202** with a `Location` header at the status sub-resource. A second request for a pin already PENDING is coalesced (dedup by pinId).
- `GET /api/v1/pins/{pinId}/image/status` → **new** owner-only poll target. Returns `PinImageStateDto` derived from the (image, download) rows: `status` ∈ NONE/PENDING/READY/FAILED; when READY the image metadata; when FAILED a `reasonCode` + human `message`; when an image coexists with a download, a `replacement` sub-object. `200` always (even NONE), `403` non-owner, `404` missing pin.
- `GET /api/v1/pins/{pinId}/image` → unchanged (bytes; the image row wins, so old bytes serve with zero downtime during a replacement fetch).
- `DELETE /api/v1/pins/{pinId}/image` → **extended**: cancels an in-flight download and drops the download row (204 even when no image exists yet); 404 only when there is neither an image nor a download.

State machine (spec §6), 6 rows keyed on (image row, download row): absent/absent → NONE; absent/PENDING → PENDING; absent/FAILED → FAILED(+reason); present/absent → READY; present/PENDING → READY + replacement{PENDING}; present/FAILED → READY + replacement{FAILED,reason}.

Failure taxonomy (`DownloadReason`, 9 codes): permanent (fail on first attempt) = URL_NOT_ALLOWED, ACCESS_DENIED (401/403), NOT_FOUND (404/410), TOO_LARGE, INVALID_IMAGE, TOO_MANY_PIXELS; transient (retry with backoff up to `MAX_ATTEMPTS=5`, then terminal FAILED) = UNREACHABLE (429/5xx/IO), INTERNAL_ERROR, FETCH_FAILED.

## Structure (what is new vs 2a)

- **api-fetch-http** (new adapter module, depends on `api-domain` only): `HttpImageFetcher` (JDK `HttpClient`, `followRedirects(NEVER)` + manual redirect loop capped at `maxRedirects`), `AddressPolicy` (SSRF guard: `Standard` blocks loopback/site-local/link-local/any-local/multicast + IPv6 ULA fc00::/7; `AllowAll` for tests). Every hop (initial + each redirect target) is re-guarded.
- **api-domain**: `ImageDownload` entity (`@Id pinId`, sourceUrl, status, reasonCode?, lastError?, taskId:UUID, timestamps), `DownloadStatus{PENDING,FAILED}`, `DownloadReason` (9), `ImageDownloadRepositoryInterface` (upsertPending/findByPinId/markFailed/recordLastError/deleteIfPending/deleteByPinId), `TransactionRunner` port, `ImageFetcher` port + sealed `FetchException` (7 subclasses).
- **api-persistence-sqlite**: `ImageDownloadModel` (@Id pinId, NO @Version), mapper, `EbeanImageDownloadRepository` (CAS-on-PENDING; ambient-transaction-aware, no explicit beginTransaction), `EbeanTransactionRunner`; migration `1.5` (image_download table, pin_id PK, NO FK). `EbeanTaskQueue.enqueue` / `EbeanImageRepository.save` made ambient-tx-aware (join `database.currentTransaction()`).
- **api-usecases**: `RequestPinImageDownload` (atomic upsert+enqueue via `TransactionRunner`), `DownloadPinImage` (fetch → stage → probe → promote → CAS swap; failure policy), `ResolvePinImageState` + `PinImageState.derive` (pure state machine), `ClearPinDownload` (best-effort cancel + drop, returns Boolean), `TaskContext(attempt,maxAttempts)` + `PinDownloadTask(KIND="pin.download",MAX_ATTEMPTS=5)`. `TaskHandler.handle(payload, context)` (contract extended). `SetPinImage`/`DeletePinImage`/`PinRecycleBin` wired to clear the download.
- **api-presentation-quarkus**: `ImageController` mode-B PUT + status GET (`@Operation`/`@APIResponse` to keep the two same-path PUTs distinct in openapi), `PinImageStateDto`/`PinImageStateMapper` (9 `messageFor` arms, no em-dash), `PinImageDownloadInputDto`, `ImageDownloadConfig` (`@ConfigMapping prefix="images.download"`), `PinDownloadTaskHandler` (thin delegate).
- **api-application**: `wiring/FetchAdapterProducers` produces `ImageFetcher` from config (AllowAll when `images.download.allow_private_addresses=true`, else Standard); `ModeBImageHostingIntegrationTest`.

## Learned pitfalls (read before touching this again)

1. **Atomicity is via a `TransactionRunner` port + ambient-transaction awareness.** `EbeanImageDownloadRepository`, `EbeanTaskQueue.enqueue`, `EbeanImageRepository.save` deliberately do NOT open their own transaction; they join `database.currentTransaction()` so the use case can compose them in one tx. Ebean nests without savepoints, so a naive nested `beginTransaction` would silently not isolate. Do not add explicit `beginTransaction` to these.
2. **Every download-row write is CAS-on-PENDING** (`markFailed`/`recordLastError`/`deleteIfPending`). The success swap is `deleteIfPending(pinId) > 0` guarding the image insert in one tx: a zombie or a second runner finds no PENDING row and discards its promoted file. Preserve this — it is what makes replace/cancel races safe under the single SQLite writer.
3. **`DownloadPinImage` must never let an exception escape the failure policy** or the row stays stuck PENDING forever (client polls PENDING with no image). All three stages have generic `catch (Exception)` nets that route to `failPermanent`/`failRetryable` (stage-body IO → UNREACHABLE; probe non-contract error → INTERNAL_ERROR; promote/swap → INTERNAL_ERROR). Added during review; do not remove them.
4. **Mode-B replace must best-effort delete the superseded FILE**, not only the row (spec §8 step 7). `promoteAndSwap` loads the existing image first and deletes its file only on a real swap (`swapped==true`); a no-op swap kept the old image and must not touch it. Missing this = unbounded disk growth on the re-fetch-to-replace flow (caught in the holistic review, fixed in `bcdc463`).
5. **SSRF guard is per-hop but checks only the FIRST resolved IP**, then `HttpClient` re-resolves at connect: DNS-rebinding + multi-A-record is a **consciously-accepted gap** (spec §10 — trusted infra, DNS not attacker-controlled). Hardening = pin the resolved IP(s) and connect-by-IP preserving Host/SNI.
6. **Timeouts must stay < the task lease** (`connect PT5S + request PT30S = 35s < lease PT1M`) or a slow fetch outlives its lease and a second worker double-runs (CAS makes that safe, but wasteful). Not validated at boot yet (see Deferred).
7. **SmallRye merges same-path+verb operations into ONE openapi operation.** The two PUTs (multipart mode A, JSON mode B) needed explicit `@Operation`/`@APIResponse` so mode B's 202/`PinImageStateDto` did not overwrite mode A's 200/201/`ImageOutputDto` in `docs/openapi.json`.
8. **`ClearPinDownload.clear` returns `Boolean`** (was Unit) so `DeletePinImage` can decide 204-cancel vs 404-nothing. The other callers (`SetPinImage`, `PinRecycleBin`) ignore the return by design.

## Testing the async worker deterministically

`ModeBImageHostingIntegrationTest` runs a `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0` (a `@TestProfile` sets `allow_private_addresses=true` + a temp `data_dir`). The worker is real and fast, so observing a transient PENDING/replacement is made race-free with a `/gated` handler that blocks on a per-test `CountDownLatch` before writing bytes — never `Thread.sleep`. The server executor is an explicit `newCachedThreadPool` shut down in `@AfterAll` (`HttpServer.stop()` does not close a caller-supplied executor; otherwise its non-daemon threads linger past the test-worker drain timeout). All waits are bounded.

## NOT validated against real environment / hardware

- **CI has not run yet.** Green only on the local JDK-25 gate.
- **Real remote origins**: only a local loopback stub was exercised. No real internet host, no real redirect chain, no real slow-body/slow-loris origin, no real 30 MiB / near-50 MP fetch.
- **The private-site "bounce" case** is handled as `ACCESS_DENIED` (403 → terminal FAILED with a "site refused the server access, upload directly" message). Diagnosable, not accommodated (mode A is the answer). Validated only via the stub 403, not a real authenticated site.
- **DNS-rebinding / multi-A-record SSRF** is out of scope by design (see pitfall 5).
- Local `quarkusBuild` needs `JAVA_HOME` on the JDK-25 toolchain (host JDK-21-daemon quirk from the 2a handoff); CI is fine.

## Deferred (non-blocking) — full list in `.superpowers/sdd/progress.md` "Deferred" section

Agreed with the holistic reviewer as non-blocking:

- **202 body literally reports `status: PENDING`** even when replacing a READY image (spec-faithful wording; the client re-polls `/status` which reports the true READY+replacement). Could derive the real state for the 202 body.
- **Timeout invariant not validated at boot** (`connect+request < lease`); defaults are safe and the CAS makes a double-run harmless, so a misconfig gets no signal. Add a boot-time check in `FetchAdapterProducers`.
- **Malformed redirect `Location`** (`url.resolve` throws `IllegalArgumentException`) is absorbed as UNREACHABLE (retried 5×) instead of a permanent FETCH_FAILED. Terminates safely, wrong taxonomy + wasted retries.
- **Mode-A upload clears the download in a separate transaction** from the image save (spec says the upload should win); narrow race under the single writer, CAS prevents corruption (symptom: the upload occasionally loses and its file leaks).
- **SSRF hardening**: pin resolved IP + connect-by-IP (DNS gap); close error-status response bodies before throwing (a burst can hold a connection until GC); block exotic reserved ranges (255.255.255.255, 100.64/10 CGNAT, 240/4, ::a.b.c.d).
- **Cosmetic**: some plan-snippet unit tests omit `// Given/When/Then` comments (repo-wide pre-existing debt); `dbmigration/model/1.5.model.xml` lacks a trailing newline.
- **Benign shutdown-race logs** during the api-application full-gate run (`task poll failed` / `task reap failed` / `worker pool did not drain`) come from `TaskWorkerLifecycle` (sub-project 1's queue, pre-existing on main), surfaced more often because the ModeB test drives the real queue. BUILD SUCCESSFUL; not a failure. A future improvement could quiesce the queue before app shutdown or lower the log level for shutdown-phase poll failures.

## Suggested next step

Thumbnailing / disposable renditions under `<data_dir>/cache/` (deferred from 2b as a follow-up sub-project), and/or the perceptual `ImageHash` when the pin-deduplication feature lands (deliberately YAGNI'd here). Both were explicitly split out during the 2b discussion.
