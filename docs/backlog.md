# Backlog

**Living document.** A snapshot of what the API already ships and what is still open, ordered by priority.
Keep it alive: update it at the end of every sub-project (Wrap phase), and whenever a scope decision is made.
It is written in English (project language), like the specs, plans, and handoffs.

Last reviewed: 2026-07-21.

## How to use this file

- **Shipped** records what exists so the backlog reads against a known baseline. Move an item there when it merges.
- **Open items** are grouped by priority band, not by module. Each item states the context and what must be
  decided or done, with a pointer to the relevant spec/handoff when one exists.
- `P0` = product decisions that shape the data model and the UI; resolve these before building on top of them.
  `P1` = client ergonomics needed for the web UI and browser extension. `P2` = operational debt already flagged
  in handoffs (not UI blockers).
- When an item is picked up, note the branch/sub-project next to it; when it merges, move it to **Shipped**.

---

## Shipped (baseline)

Six sub-projects merged (`v0.1.0-task-queue` → `v0.5.0-boards`, plus **session-token authentication**,
now on `main`). CI green, 100% branch coverage, hexagonal layering, generated OpenAPI.

- **Users**: registration (`POST /api/v1/users`, public). Authentication is session-token / Bearer
  (see the Auth bullet); HTTP Basic has been removed.
- **Auth (session tokens)**: `POST /api/v1/sessions` login issues an opaque revocable bearer token
  (SHA-256 hash stored, `rememberMe` persistent/ephemeral TTL); `GET /me`, `GET /sessions/current`,
  `POST /sessions/current/renew` (atomic rotation), `DELETE /sessions/current` (logout),
  `DELETE /sessions` (logout-all); OpenAPI declares a bearer security scheme. Replaces HTTP Basic
  entirely. See `docs/handoffs/2026-07-21 - handoff - session-token-auth.md`.
- **Pins**: list (cursor pagination + sort strategy), get, create, update, soft-delete, tag.
- **Recycle bin**: list recycled, restore, permanent delete, empty.
- **Search**: pins (trigram similarity) + tags.
- **Images**: direct upload (multipart, mode-A), download-from-URL (mode-B, async via the task queue), serve
  original, delete, status, and disposable **renditions/thumbnails** (lazy WebP, on-disk cache, `?size` + `?animated`).
  See `docs/handoffs/2026-07-16 - handoff - image-hosting-3-renditions.md`.
- **Boards**: named owner-scoped collections; a pin belongs to 0..N boards (set-based membership via
  `PUT /pins/{id}/boards`, mirroring tags); board CRUD, cursor-paginated board pins, `PinOutputDto.boards`
  + board `pinCount`, and a recycle bin mirroring `PinRecycleBin`. On `main`.
  See `docs/handoffs/2026-07-20 - handoff - boards.md`.
- **CORS**: Quarkus CORS filter enabled with a whitelist policy for browser clients. Allowed origins
  driven by `api.cors.origins` (typed on `ApiConfig`; dev default `http://localhost:5173`, prod via
  `API_CORS_ORIGINS`); methods `GET,POST,PUT,DELETE`, request headers `Authorization,Content-Type`,
  `Location` exposed, credentials off (Bearer only), 24 h preflight cache. On `main`.
  See `docs/handoffs/2026-07-21 - handoff - cors.md`.
- **Infrastructure**: generic task queue (enqueue/cancel/reap), Ebean migrations, git hooks, CI gate.

---

## Open items

### P0 — (none open)

**Client auth story shipped 2026-07-21** (session tokens; merged to `main`). **CORS shipped 2026-07-21**
(merged to `main`, in Shipped above). The next priority is **profile management**.

### P1 — Client ergonomics (needed for the web UI and browser extension)

- **Profile management.** Change password, delete account, and (if visibility lands) public profiles.
- **Browser-extension CORS origin.** Deferred from the CORS sub-project (decision B1): the extension
  does not exist yet and has no stable ID, so no origin is wired for it. When it ships, add its
  `chrome-extension://<id>` / `moz-extension://<id>` origin to `api.cors.origins`. See
  `docs/handoffs/2026-07-21 - handoff - cors.md`.

### P2 — Operational debt (flagged in handoffs; not UI blockers)

- **Expired session-token GC sweep.** `session_tokens` rows are inert once expired (verification rejects
  them), but they accumulate. No sweep in v1. See `docs/handoffs/2026-07-21 - handoff - session-token-auth.md`.

- **`animated` backfill migration** for pre-existing image rows. The only item with a correctness consequence:
  rows predating migration 1.6 are labelled `animated = false`, so `?animated=false` on such a row can serve the
  original animated bytes instead of flattening. See the renditions handoff, "NOT validated" section.
- **Cache GC sweep** for orphaned rendition subtrees. Eviction is best-effort; a failed eviction or a crash
  mid-write leaves a subtree forever. Spec §14 of the renditions sub-project.
- **Perceptual `ImageHash`** for pin deduplication (deliberately YAGNI'd in sub-project 2b).

---

## Parked (explicitly out of scope for now)

- **Visibility / sharing (public / private).** *(Parked 2026-07-20.)* Everything stays `@Authenticated` and
  owner-scoped (non-owner → 403); no anonymous browsing, no public gallery, no shareable links. Revisit when a
  sharing model is actually wanted; it will interact with boards (public boards, shared boards) and with the P1
  profile/auth items.
