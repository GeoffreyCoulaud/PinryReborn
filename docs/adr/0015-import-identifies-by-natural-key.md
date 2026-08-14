# 0015. The importer identifies by natural key, never overwrites, and restores the archive's timestamps

Status: Proposed
Date: 2026-08-14
Specification: `docs/specs/2026-08-14-user-data-import.md`
Amends: `docs/adr/0006-domain-owned-timestamps.md`, whose rule that a domain timestamp is stamped by
the use case from the `Clock` port gains one narrow exception, stated in decision 3.
Related: `docs/adr/0009-unique-index-named-outcomes.md` (the database is the authority on uniqueness;
this ADR adds two constraints and one of them changes a public contract).

## Context

The export shipped a versioned archive designed as the input contract of a future importer
(`docs/specs/2026-07-22-user-data-export.md`). Writing that importer forces four questions the export
could leave alone, because reading data back into a live account is not the mirror image of writing
it out.

The archive carries UUIDs. Reusing them would make an import a fast path to choosing one's own
primary keys, and would make the same archive un-importable by two users of one instance, which is a
case worth supporting: an archive is a file people hand to each other.

The archive carries `createdAt` and `updatedAt`, promoted into the domain by the export for exactly
this purpose: its section 3 states that without them "the archive loses the chronology and a future
import would re-date everything". Reading them back means a client-supplied instant reaches a domain
entity, which ADR 0006 forbids.

An early design gave the import an override mode: the server would compute the set of rows an
archive would overwrite, present it, and apply only the pre-approved set, ignoring conflicts that
appeared after approval so the account need not be frozen during a restore. The protocol was sound.
It was also answering a question nobody asked: none of the four scenarios the feature exists for
(moving instance, merging two accounts, restoring lost data, receiving an archive from someone) wants
an existing row replaced by an older copy of itself.

## Decisions

**1. The import never modifies or deletes anything that already exists.** It creates what is missing
and skips the rest, reporting every skip. Override mode is dropped, along with the plan-and-approve
protocol built for it.

**2. Identity is a natural key, never an archive identifier.** Tags and boards are identified by
name, case insensitively; a pin is identified by the SHA-256 of its medium. Archive identifiers are
read and discarded, and every created row gets a fresh one. Two consequences follow and are taken:
`(user, tag.name)` and `(user, board.name)` become unique indexes, which turns `POST /api/v1/boards`
with a taken name from a success into a `409`; and a pin carrying no medium has no identity, so it
is skipped and reported rather than imported without one.

**3. The archive's timestamps are restored, clamped to the import instant when they are in the
future.** The exception to ADR 0006 is bounded by a rule that can be checked by reading the code: the
import restores timestamps it reads and stamps from `Clock` everything it invents. No other input
path gains this permission.

**4. When a natural key matches more than one existing row, the import does nothing and says so.**
Nothing forbids two pins on the same medium today, so the pin key can return several rows. A visible
refusal is preferred to an invented winner.

## Consequences

**Idempotence becomes a property rather than an aspiration.** Because a conflict means skip, replaying
an archive from the beginning converges: it does against a non-empty account, which is the same
operation. Crash recovery therefore needs no distributed bookkeeping, and the line cursor the spec
keeps is an optimisation and a progress source, not a correctness mechanism. This is the single
largest simplification the first decision buys, and it is why the decision is worth its cost.

**Step-up re-authentication is not required, and the reason is the first decision, not the feature's
importance.** The export demands it because it turns a pin-by-pin grind into one file holding
everything; an import exfiltrates nothing and, without override, destroys nothing. If override ever
returns, step-up returns with it. Anyone adding a destructive mode later must read this paragraph as
a condition, not as a precedent.

**A public contract breaks.** Creating a second board with an existing name stops working. Alpha
status allows it and no instance is deployed, but the change is a deliberate cost of decision 2, not
a tidy-up: a name cannot serve as an identity while the system lets it be ambiguous.

**Some data does not survive a round trip.** A pin whose image download was pending or had failed,
and a pin whose bytes the exporter could not write, both arrive without a medium and are dropped.
The report makes the loss visible; making them travel requires the export to carry the download
state, which is a backlog item and not a defect of this decision.

**The metadata in the archive becomes decoration.** Since the importer probes every image rather
than believing the manifest, the recorded `mimeType`, dimensions and digest serve the human reading
their own export and nothing else. That is deliberate: `ImageController` serves the stored media type,
so trusting an archive's word on it would be stored cross-site scripting by the shortest path.
