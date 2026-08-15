# 0015. The importer identifies by natural key, never overwrites, and restores the archive's timestamps

Status: Proposed
Date: 2026-08-14
Specification: `docs/specs/2026-08-14-user-data-import.md`
Amends: `agents/engineering.md`, Design invariants, "Domain data is stamped by use cases, never
invented by adapters: instants, ids, state transitions come from ports (`Clock`)". Decision 3 below
carves the one exception, and the norms document is amended in the commit that establishes it in code,
so the invariant is never left reading as unqualified.
Related: `docs/adr/0006-domain-owned-timestamps.md`, whose decision is narrower than decision 3
touches (it forbids the persistence adapter from reading a clock, which the import does not do);
`docs/adr/0009-unique-index-named-outcomes.md` (the database is the authority on uniqueness; this
lot adds three constraints and one changes a public contract at two sites);
`docs/adr/0008-structural-soft-delete-read-isolation.md` (every new read names its soft-delete state).

## Context

The export shipped a versioned archive designed as the input contract of a future importer
(`docs/specs/2026-07-22-user-data-export.md`). Writing that importer forces four questions the export
could leave alone, because reading data back into a live account is not the mirror image of writing
it out.

The archive carries UUIDs. Reusing them would make an import a fast path to choosing one's own
primary keys, and would stop two users of one instance importing the same file, which is a case worth
supporting: an archive is a file people hand to each other.

The archive carries `createdAt` and `updatedAt`, promoted into the domain by the export for exactly
this purpose: its section 3 states that without them "the archive loses the chronology and a future
import would re-date everything". Reading them back means a client-supplied instant reaches a domain
entity.

An early design gave the import an override mode: the server would compute the set of rows an archive
would overwrite, present it, and apply only the pre-approved set, ignoring conflicts that appeared
after approval so the account need not be frozen during a restore. The protocol was sound. It was
also answering a question nobody asked: none of the scenarios the feature exists for (moving
instance, merging two accounts, restoring lost data, receiving an archive from someone) wants an
existing row replaced by an older copy of itself.

## Decisions

**1. The import never modifies or deletes anything that already exists.** It creates what is missing
and skips the rest, counting every skip. Override mode is dropped, along with the plan-and-approve
protocol built for it.

**2. Identity is a natural key, never an archive identifier.** Tags and boards are identified by
name, folded for ASCII case; a pin is identified by the SHA-256 of its medium. Archive identifiers
are read and discarded. Three consequences follow and are taken: `(author_id, name collate nocase)`
becomes unique on tags and on boards, covering recycled rows, which turns a taken board name into a
`409` at two call sites; a pin carrying no medium has no identity, so it is skipped and reported;
and when a digest matches several existing pins the import does nothing and says so, rather than
inventing a winner.

**3. The archive's timestamps are restored, clamped at both ends.** Instants earlier than the account
are raised to its creation, instants later than now are lowered to the import instant. The exception
to the norms document is bounded by a rule that a test can express: the import restores the instants
it reads and stamps from `Clock` everything it invents.

**4. The archive is untrusted input at every level, and the import is a validating write path.**
Nothing in the archive that describes bytes is believed: media type, dimensions and animation come
from probing the file. Field bounds that today exist only on REST input DTOs are restated by the
import, because it is a second write path into the same tables and no entity carries an invariant.
Every read of the archive is bounded in size, count and line length.

## Consequences

**Idempotence becomes a property rather than an aspiration, provided the runner is fenced.** Because
a conflict means skip, replaying an archive from the beginning converges: it does against a non-empty
account, which is the same operation. But convergence assumes one writer. The task queue reclaims a
task whose lease expires while its handler still runs, so two runners can read "no pin holds this
digest" simultaneously and both create one, after which that medium is permanently ambiguous and
never importable again. The design therefore re-reads a run token inside every per-pin transaction.
An alternative was available, a unique index on `(author_id, content_hash)`, and it was rejected here
because it would settle a product question (may one medium exist under two pins?) that this lot has
no mandate to answer and that the backlog holds.

**Step-up re-authentication is not required, and the reopening condition is a property, not a
feature.** The export demands step-up because it turns a pin-by-pin grind into one file holding
everything. The import exfiltrates nothing and, without override, destroys nothing. The operator took
this decision with the counter-argument on the table: what this codebase actually gates is unbounded
effect (`AccountDeleter` requires step-up), undoing an import costs one request per pin, and decision
2 lets an archive take a name its owner cannot then reuse. The accepted worst case is recorded in the
specification's section 14. **The condition that reopens this is any change making the import
destructive, irreversible in bulk, or capable of acting on rows it did not create**, of which
override's return is one instance rather than the definition.

**A public contract breaks, at two sites.** Creating a board with an existing name stops working, and
so does renaming onto one. Restoring from the recycle bin is not a third, though two earlier
revisions of this decision said it was: the index covers recycled rows, so no homonym can exist while
a board sits in the bin, and `restoreBoard` writes no indexed column, which makes the collision
unreachable rather than translated. Alpha status allows the break and no instance is deployed, but it
is a deliberate cost of decision 2: a name cannot serve as an identity while the system lets it be
ambiguous. The translation lives in the repository, at `saveBoard`, so no persistence exception can
reach a controller from either path.

**Some data does not survive a round trip, and restoring recovers less than the word suggests.** A
pin whose image download was pending or had failed arrives without a medium and is dropped; two pins
sharing one medium arrive as one; a recycled pin is skipped rather than restored, because its digest
still matches. The report makes each loss visible, and the specification's section 14 states them so
a user is not left to infer them.

**The archive's own metadata is informational.** Since every image is probed, the recorded media
type, dimensions and byte size serve the human reading their own export. The declared digest is the
one exception: it is compared against the digest the import computes anyway and a mismatch is
reported, because it is the only signal that an archive was altered or truncated in transit. It still
changes no outcome, since the bytes remain the authority.
