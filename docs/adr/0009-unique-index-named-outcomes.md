# 0009. The database owns uniqueness, and every unique constraint names its outcome

Status: Proposed
Date: 2026-08-04
Specification: `docs/specs/2026-08-04-unique-index-named-outcomes.md`
Related: `docs/adr/0006-domain-owned-timestamps.md` (the password-hash index, the one constraint that
collides by value rather than by interleaving).

## Context

The schema carries six unique constraints. Two translate their violation into a domain error, four do
not. Nothing recorded which of the four were deliberate, so the question was answered four times by
four authors, twice by writing a translation and twice by writing nothing, and the two silences read
identically to the next reader.

Two of the four silences leak `jakarta.persistence.PersistenceException` out of the persistence
adapter, which `agents/modules/kotlin.md` forbids: `UserRepository.saveUser` under
`ix_users_name_nocase`, and `EbeanTaskQueue.enqueueWithin` under `ux_tasks_dedup`. Neither leak is
reachable in this process today, because the datasource is pinned to a single connection
(`EbeanDatabaseProducer.kt:53-54`) and both violations need two callers to interleave a read and a
write. So this is not a live 500. It is a 500 that arrives the day the connection decision changes,
with nothing in the repository stating that dependency.

Behind the four silences sits a second, larger question. Where a uniqueness rule is enforced twice,
once by a read before the write and once by the index, the two can disagree and only one of them is
always right. `UserCreator` carries such a pair. Naming the outcome of the four constraints without
settling who owns uniqueness would leave the duplication in place and the next author free to add
another.

## Decision

1. **A unique constraint is not complete until its outcome is named.** Every unique constraint in the
   committed migrations appears in one table, in `UniqueConstraintOutcomeTest`, with the outcome a
   client observes when it fires. "No translation, deliberately" is a valid outcome and must be
   written; it is silence that is refused. A constraint absent from the table fails the build.
   The assertion enforces that an outcome is named, not that it is correct: a wrong entry passes. That
   is accepted, because the failure this addresses is the unwritten decision, not the wrong one.

2. **The database is the authority on uniqueness.** No read before a write exists solely to answer a
   uniqueness question the index already answers. The rule is scoped to *solely*, because a read that
   also does something else is not a duplicate authority, and what that something else is then has to
   be written down. Three sites, three landings:

   - **Username.** `UserCreator`'s read-before-insert has no second job, so it goes.
     `UserRepository.saveUser` translates the violation into the new domain
     `UsernameAlreadyTakenException`, and the use case rethrows `UsernameAlreadyTakenError`, the shape
     `PasswordChanger` already uses. The tombstoned-name and case-insensitivity rules move with it,
     from `findUserByNameIncludingDeleted` and Ebean's `ieq` to the index's own `collate nocase` over
     every row. The port method, left without a caller, is deleted rather than kept for symmetry.
   - **Password hashes.** Already conformant. None of `PasswordChanger`'s three reads asks whether a
     hash exists at that instant; they serve re-authentication, the minimum interval and the history.
     The index has been the sole authority since it was added.
   - **Exports.** `UserDataExportRequester.kt:58` asks the index's question, but it also orders two
     refusals, and the order is the behaviour clients depend on. A live `PENDING` row counts towards
     the minimum interval, and production sets that interval to one hour, so deleting the read makes
     a second request during a running export answer `429 EXPORT_TOO_SOON` and leaves
     `409 EXPORT_ALREADY_IN_PROGRESS` only for a `PENDING` row older than an hour, which is a stuck
     worker. The problem in that moment is that an export is in progress, not that the request came
     too soon, so the read stays and a test pins the precedence it encodes.

   The rule gets no automated guard. No tool the project has can tell a uniqueness read from any other
   read: Konsist sees declarations, and a detekt rule keying on method names would catch the export
   read the rule deliberately allows. It lives in `agents/project.md`, which is where a judgement call
   belongs.

3. **The dedup constraint converges rather than erring.** `TaskQueueInterface` documents that a live
   dedup key returns the existing task without inserting. A lost race is the same situation reached by
   a different route, so it produces the same answer, not an exception. Its check-then-insert read is
   not a second authority under decision 2: it returns a value rather than refusing, and it is the
   fast path of the documented convergence.

4. **Two constraints are declared untranslated, with their reason.** `uq_images_pin_id` cannot fire:
   `EbeanImageRepository.saveWithin` deletes by `pinId` then inserts, in one transaction.
   `uq_session_tokens_token_hash` firing means the secure token generator produced a repeat, which is
   a broken invariant and not an applicative case: a 500 is the honest answer.

5. **A migration's indexes belong in its migration model.** Four indexes exist in the database and not
   in Ebean's prior model: the three `1.3.sql` creates on `tasks`, and `ix_users_name_nocase` from the
   hand-written `1.2.sql`. Each is declared on its entity and recorded in its migration's model file,
   both halves together, and a second assertion holds that every index a `.sql` creates is recorded
   somewhere in the model, with no exemption.

   `.model.xml` is generator state, not applied DDL: no checksum covers it and the migration runner
   reads only `.sql`, so correcting it is not the applied-migration edit the project forbids. That
   distinction is what makes `1.2` reachable, and it is what its existing exemption missed: the
   exemption's stated reason is the checksum of an applied migration, but the missing artefact is a
   model file that never existed and no `.sql` is touched. The exemption is therefore removed rather
   than carried forward, which empties the `handWritten` allowlist and strengthens the assertion it
   guards.

   This clears one of the beta-flattening item's two debts. The `when_created` / `when_modified` column
   names remain and do require rewriting applied migrations, so that item narrows rather than closes.

## Findings from the experiment

The lot opens by trying to produce both violations under concurrency rather than by designing against
them. This section records what that produced, and is written during the work.

_To be filled in Act, before any translation is written._

Two questions it must answer, because the shape of decisions 2 and 3 depends on them:

- whether a constraint violation surfaces at the `Persistor` call or only at commit;
- whether an Ebean transaction stays usable for a read after a caught unique-constraint violation.

## Consequences

- A new unique index costs one line in a test table. A new author cannot ship one without meeting the
  question.
- Registration loses a database read on the happy path and gains one code path instead of two.
- The 409 on a duplicate username is now produced by the index rather than by a lookup. Its end-to-end
  contract does not move, and `UserCreationIntegrationTest` is the witness.
- `UserRepositoryTest`'s assertion that a raw `PersistenceException` escapes is deleted. It pinned the
  defect as the contract.
- The export refusal precedence stops being an accident of statement order. It was untested: the
  integration suite pins the minimum interval to zero, so the case where both refusals apply was
  reachable in production and nowhere in the tests.
- The in-process serialisation invariant becomes load-bearing in writing as well as in fact. Splitting
  the API and the worker into separate processes reopens every race named here, since SQLite in WAL
  mode admits several writers across processes. That is out of scope and stated so the next reader
  meets it before the incident.
- The two untranslated constraints now carry a written reason, so removing the reason (making
  `saveWithin` insert without deleting, say) has a place that contradicts it.
- Ebean's prior model finally matches the database, so `generateDbMigration` can be trusted on `tasks`
  and `users`. Before this, declaring any of the four indexes on its entity would have emitted a
  duplicate `create index`, and the failure would have appeared at migration time on a real database
  rather than at generation time.
- An exemption was removed by checking its reason rather than by inheriting it. The lesson generalises
  past this file: an allowlist entry states a justification, and the justification is a claim like any
  other.
- Decision 2 is a rule with one written exception. That is a maintenance cost: the next reader must
  find the export justification to understand why the rule did not apply there. The alternative was a
  rule with no exceptions and a degraded error message, which costs a user rather than a reader.
