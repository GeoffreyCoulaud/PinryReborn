# Backlog

**Living document.** A snapshot of what the API already ships and what is still open, ordered by priority.
Keep it alive: update it at the end of every sub-project (Wrap phase), and whenever a scope decision is made.
It is written in English (project language), like the specs, plans, and handoffs.

Last reviewed: 2026-07-20.

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

Four sub-projects merged (`v0.1.0-task-queue` → `v0.4.0-image-hosting-3`). CI green, 100% branch coverage,
hexagonal layering, generated OpenAPI.

- **Users**: registration (`POST /api/v1/users`, public) + HTTP Basic auth (Quarkus Security).
- **Pins**: list (cursor pagination + sort strategy), get, create, update, soft-delete, tag.
- **Recycle bin**: list recycled, restore, permanent delete, empty.
- **Search**: pins (trigram similarity) + tags.
- **Images**: direct upload (multipart, mode-A), download-from-URL (mode-B, async via the task queue), serve
  original, delete, status, and disposable **renditions/thumbnails** (lazy WebP, on-disk cache, `?size` + `?animated`).
  See `docs/handoffs/2026-07-16 - handoff - image-hosting-3-renditions.md`.
- **Infrastructure**: generic task queue (enqueue/cancel/reap), Ebean migrations, git hooks, CI gate.

---

## Open items

### P0 — Boards / collections (current top priority)

**Decided 2026-07-20:** yes, Reborn has boards, and a pin belongs to **0..N boards** (optional, many-to-many).

A full sub-project: board entity + CRUD, the pin↔board membership relation, board-scoped pin listing, and the
DTOs/endpoints. Owner-scoped like the rest (no public/shared boards while visibility is parked). Drives the whole
UI navigation. Next: brainstorm → spec → plan.

### P1 — Client ergonomics (needed for the web UI and browser extension)

- **Client auth story.** Auth is HTTP Basic only, which is awkward for an SPA and a browser extension (they would
  store the raw password). Decide on a token / API-key mechanism, and add at least `GET /me` (current identity)
  and a credential-check endpoint. Touches both the extension and the UI, so decide early.
- **CORS configuration.** Not configured (no entry in the Quarkus properties). Blocks any browser client calling
  from another origin. Mechanical once the client auth model is fixed.
- **Profile management.** Change password, delete account, and (if visibility lands) public profiles.

### P2 — Operational debt (flagged in handoffs; not UI blockers)

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
