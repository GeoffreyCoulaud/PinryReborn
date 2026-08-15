# 0016. A row two actors write is fenced by compare-and-set, not by a version column

Status: Proposed
Date: 2026-08-15
Specification: `docs/specs/2026-08-15-export-row-fencing.md`
Amends: `docs/backlog.md`, P2 item "Only `TaskModel` carries a version, so every other entity two
actors can write is exposed", whose stated general answer is optimistic locking. This record reverses
that answer and the item is rewritten in the commit that establishes this in code, so the backlog is
never left prescribing a remedy the repository has decided against.
Related: `docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`, whose single
connection is the premise decision 1 rests on; `docs/adr/0007` on `Persistor.merge` writing every
column, which is the defect being fenced.

## Context

`Persistor.merge` writes every column of a row. No model except `TaskModel` carries an Ebean
`@Version`. Saving an entity read earlier therefore restores that entity's whole state, including
whatever another actor committed in between.

The user data import found nine sites of that one defect in a single lot, each reported and fixed as
a particular case before anyone named the cause, and closed them with a fence written by hand plus a
detekt rule whose reach is one package. The backlog item that came out of it proposed the general
remedy: a version column on the models that lack one, and a domain exception for the lost update.

Reading the export half, which is the same feature's mirror, showed five unfenced writes and named
the moment to decide the general question rather than repeat the particular answer a sixth time.

**A correction belongs in the context, because the first draft of the specification argued from it.**
That draft called `TaskModel`'s `@Version` decorative, on three true observations: no mapper reads
it, the domain `Task` has no such field, and no module catches `OptimisticLockException`. The
conclusion did not follow. `EbeanTaskQueue` maintains the column deliberately across seven bulk
updates, and Ebean arms its optimistic lock on the two paths that save a bean loaded by query,
`claimNext` and `reapExpired`. The task queue's own specification records it as a back-stop retained
on purpose, and a concurrency test asserts that no `OptimisticLockException` is ever thrown, an
assertion that is only meaningful because the lock is live. The honest statement is: a live back-stop
with no domain surface.

## Decision

**1. A row that two actors can write is fenced by re-reading it and testing a predicate inside the
transaction that writes it.** Not by a version column. The datasource holds one connection
(`docs/adr/0012`), so a read followed by a write inside one transaction already serialises against
every other writer; a version column adds a second mechanism over a guarantee the transaction
already gives, at the cost of a column on every table it touches in an append-only migration history.

**2. The predicate is the state the caller's decision rested on**, and a missing row refuses.
`merge` is an upsert, so a fence that evaluates its predicate against the copy read earlier writes a
deleted row back into existence. The shape is `findById(id)?.takeIf(held)?.let { save(update(it)) }`:
absence refuses before the predicate is reached, and the update is applied to the row just read, not
to the stale copy.

**3. A caller whose release depends on the state reads the state its write replaced**, not the one it
read before the fence, which may be one state old. Two writes on one entity that differ only in which
arm they release are one fenced write with a phase-agnostic predicate, not two fences with narrow
ones. Narrow predicates split one user intent across mutually exclusive fences, and the transition
between them falls through the gap: the specification records the concrete regression this rule
exists to forbid, a `DELETE` answering success on an export that stays downloadable.

**4. The state moves before the bytes.** A destructive release is driven by the state the fence
wrote, so a failure between the two leaves a row that promises less than it holds rather than more.
The reverse order leaves a row promising bytes that are gone, which a client meets as a `500` on a
download instead of a `410`.

## Consequences

Every new writer of a shared row must take a fence by hand, and nothing structural forces it outside
the import package until `ImportStateMergedOutsideTransaction`'s path filter widens. That is the cost
of this decision and the reason the rule's reach is now a lot of its own rather than a backlog item.

`TaskModel`'s version column stays. It is a live back-stop, its removal would touch seven update
statements and need a destructive migration, and nothing in this decision asks for it.

The backlog's P2 item is rewritten from "add a version column everywhere" to "fence the writers that
have a dangerous pair, and widen the rule that catches the ones that do not". The relevé behind that
rewrite found three entities exposed out of fourteen, one of them gravely, which is a smaller and
differently shaped problem than the item described.

This decision does not close a race between two attempts of the same long-running task, which no
predicate on state can see because both attempts read the same legitimate state. That needs a claim
token, as the import runner has. The specification names it, and it is filed.
