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
reachable in this process today, and the experiment below measured what closes them. Both sites hold
their read and their write inside one transaction (`UserCreator.kt:21,27`, `EbeanTaskQueue.kt:50`),
and at the username site that transaction is measurably what does the work: with it, zero failures in
400 concurrent attempts; without it, the same pair run as two autocommit statements on the same single
connection reproduced the violation 335 times in 400. The single connection is therefore not by itself
the reason. The dedup site was driven only in its production shape, which also produced zero failures,
so its answer rests on having the same shape rather than on a second measurement. This is not a live
500. It is a 500 that arrives the day either site drops its transaction, or the day the connection
decision changes. Neither dependency was stated anywhere in the repository when this lot opened; the
transaction one is now recorded in `agents/project.md` under Design invariants, corrected there in
the same commit as the measurement that established it.

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

Two questions it had to answer, because the shape of decisions 2 and 3 depends on them:

- whether a constraint violation surfaces at the `Persistor` call or only at commit;
- whether an Ebean transaction stays usable for a read after a caught unique-constraint violation.

### What was run

A throwaway probe, `ConstraintProbeSpikeTest` in `api-persistence-sqlite`, deleted before this commit.
It is not merged for two reasons: `AGENTS.md` exempts a spike from the TDD order but not from the
safety net, and a concurrency attempt that reproduces nothing would pass forever whatever the code,
which `AGENTS.md` Evidence calls a check that cannot fail. It extended `RepositoryTest`, so it ran on
the module's test datasource: in-memory SQLite pinned to one connection
(`application-test.properties:16-21`), Ebean 19.2.0 and sqlite-jdbc 3.53.2.0
(`gradle/libs.versions.toml:4,8`).

**Why that datasource answers for production.** Production is a file database with three pragmas the
probe's `jdbc:sqlite::memory:` does not carry: `journal_mode=WAL`, `synchronous=NORMAL` and
`busy_timeout=5000` (`api-application/src/main/resources/application.properties:12`). None of the
three sits in the path of anything measured here. The first two govern how a change is journalled and
how often it is flushed, neither of which decides which call raises a unique violation nor what a
failed statement leaves behind; `busy_timeout` governs how long a connection waits for a lock another
connection holds, and both configurations pin the pool to exactly one connection
(`EbeanDatabaseProducer.kt:53-54`, `application-test.properties:20-21`), so no second connection
exists to hold one and the setting never fires. The remaining difference, memory against a file, is
durability and speed. The one place it would be real is across processes, where WAL on a file admits
several writers and `:memory:` cannot express the case at all; that is out of scope and named in the
consequences below.

```
$ ./gradlew :api-persistence-sqlite:test --tests "ConstraintProbeSpikeTest"
BUILD SUCCESSFUL in 3s
21 actionable tasks: 4 executed, 17 up-to-date
```

**The discriminator every transactional row carries.** A probe that never opened a transaction, or
opened one the `Persistor` call did not join, prints the same "at the call, then NONE at the commit,
then one row" as a probe that did. The first version of this probe printed exactly that and proved
nothing about the transactional half, which is the half the two stop clauses hang on: the autocommit
half is already pinned by committed tests (`UserRepositoryTest.kt:197-206`,
`UserPasswordHashRepositoryTest.kt:81-88`). Every transactional shape below is therefore run twice,
once committing and once rolling back. The rollback run writes a row through the same `Persistor` call
under test, a **witness row** in fact 1 and in fact 2 the rows the transaction already wrote, then
rolls the transaction back instead of committing it. An autocommitted write survives a rollback, so
the count afterwards separates the two cases: **0 means the write joined the transaction, 1 would have
meant autocommit** and would have voided every transactional line in the transcript. Each such line
carries its own "autocommit would print 1" so the discriminating value is visible where it is read.

The probe wrote its observations to a scratch file outside the repository, because Gradle prints test
standard output only at debug level and this suite configures no `showStandardStreams`:

```
=== FACT 1: where a unique-constraint violation surfaces ===
merge / autocommit   : at the call                       -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: users.name)
merge / autocommit   : rows for name                     -> 1
merge / tx commit    : witness row inside the tx          -> 1
merge / tx commit    : at the call                       -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: users.name)
merge / tx commit    : at the commit                     -> NONE
merge / tx commit    : rows for name after commit        -> 1
merge / tx commit    : witness rows after commit         -> 1
merge / tx rollback  : witness row inside the tx          -> 1
merge / tx rollback  : at the call                       -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: users.name)
merge / tx rollback  : at the rollback                   -> NONE
merge / tx rollback  : witness rows after rollback       -> 0 (autocommit would print 1)
merge / tx rollback  : rows for name after rollback      -> 1
save  / autocommit   : at the call                       -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: tasks.dedup_key)
save  / autocommit   : rows for key                      -> 1
save  / tx commit    : witness row inside the tx          -> 1
save  / tx commit    : at the call                       -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: tasks.dedup_key)
save  / tx commit    : at the commit                     -> NONE
save  / tx commit    : rows for key after commit         -> 1
save  / tx commit    : witness rows after commit         -> 1
save  / tx rollback  : witness row inside the tx          -> 1
save  / tx rollback  : at the call                       -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: tasks.dedup_key)
save  / tx rollback  : at the rollback                   -> NONE
save  / tx rollback  : witness rows after rollback       -> 0 (autocommit would print 1)
save  / tx rollback  : rows for key after rollback       -> 1

=== FACT 2: is the transaction usable for a read after a caught violation ===
save  / tx commit    : violation                         -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: tasks.dedup_key)
save  / tx commit    : read of own uncommitted write     -> 1 (the row written in this tx)
save  / tx commit    : read of committed data            -> 1 (a row committed before this tx)
save  / tx commit    : a further write in the same tx    -> NONE
save  / tx commit    : commit after the caught violation -> NONE
save  / tx commit    : rows for key after the tx closed  -> 1
save  / tx commit    : the further write survived commit -> 1
save  / tx rollback  : violation                         -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: tasks.dedup_key)
save  / tx rollback  : a further write in the same tx    -> NONE
save  / tx rollback  : rollback after the violation      -> NONE
save  / tx rollback  : rows for key after rollback       -> 0 (autocommit would print 1)
save  / tx rollback  : further-write rows after rollback -> 0 (autocommit would print 1)
merge / tx commit    : violation                         -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: users.name)
merge / tx commit    : read of own uncommitted write     -> 1 (the row written in this tx)
merge / tx commit    : read of committed data            -> 1 (a row committed before this tx)
merge / tx commit    : a further write in the same tx    -> NONE
merge / tx commit    : commit after the caught violation -> NONE
merge / tx commit    : rows for name after the tx closed -> 1
merge / tx commit    : the further write survived commit -> 1
merge / tx rollback  : violation                         -> PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: users.name)
merge / tx rollback  : a further write in the same tx    -> NONE
merge / tx rollback  : rollback after the violation      -> NONE
merge / tx rollback  : rows for name after rollback      -> 0 (autocommit would print 1)
merge / tx rollback  : further-write rows after rollback -> 0 (autocommit would print 1)

=== CONCURRENCY: UserRepository.saveUser ===
50 rounds of 8 threads each, one fresh username per round.
A. check+save inside ONE transaction (production shape, UserCreator.kt:21,27):
   failures=0 rows written=50 (one per round is 50)
   distinct failure kinds -> []
B. check+save WITHOUT a transaction (two autocommit statements):
   failures=335 rows written=50
   distinct failure kinds -> [PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: users.name)]
C. bare concurrent saveUser of the same name, no check at all:
   failures=350 rows written=50
   distinct failure kinds -> [PersistenceException <- SQLiteException(resultCode=SQLITE_CONSTRAINT_UNIQUE, errorCode=19) (UNIQUE constraint failed: users.name)]

=== CONCURRENCY: EbeanTaskQueue.enqueue ===
50 rounds of 8 threads each, one fresh dedup key per round.
   failures=0 rows written=50 (one per round is 50)
   returned tasks=400 summed distinct ids per round=50 (converged is 50)
   distinct failure kinds -> []
```

### Fact 1: the violation surfaces at the `Persistor` call, in all four combinations

| Operation | Mode | Where it surfaces | Discriminator |
|---|---|---|---|
| `merge` | autocommit | at the `Persistor.merge` call | not needed: pinned by `UserRepositoryTest.kt:197-206` |
| `merge` | explicit transaction | at the `Persistor.merge` call; the later commit then succeeds | witness row gone after rollback (0) |
| `save` | autocommit | at the `Persistor.save` call | not needed: `UserPasswordHashRepositoryTest.kt:81-88` reaches the same violation at the call, on another index |
| `save` | explicit transaction | at the `Persistor.save` call; the later commit then succeeds | witness row gone after rollback (0) |

**Neither stop clause fires.** The catch T4 puts in `saveUser` and the one T6 puts in `enqueueWithin`
both fire in production, and both are reachable from a test. The weaker mode matters too: T4's tests
run through `RepositoryTest`, which gives the repository no ambient transaction, and the autocommit
row of the table says the violation is visible there as well.

The exception shape is the one `SqliteConstraintViolations` already discriminates on:
`jakarta.persistence.PersistenceException` wrapping `org.sqlite.SQLiteException` with
`resultCode = SQLITE_CONSTRAINT_UNIQUE` and vendor `errorCode` 19. The object needs no widening to
recognise either of these two constraints.

The answer holds because JDBC batching is off, and that is the one setting that would overturn it.
Ebean buffers a `save` until the batch fills or the transaction commits only in batch mode, which
ebean.io/docs/transactions/batch and ebean.io/docs/persist name three ways in: on a transaction
(`transaction.setBatchMode(true)`, `setBatch(PersistBatch.ALL)`, `setCascadeBatch`, `setBatchSize`),
on a method (`@Transactional(batchSize = 50)`), and on the database
(`databaseBuilder.persistBatch(PersistBatch.ALL)`, or the `persistBatch` property that backs it).
This project takes none of them. One search covers all three, over every tracked file rather than
only the source:

```
$ git grep -nE 'setBatchMode|setBatch\(|setCascadeBatch|setBatchSize|persistBatch|PersistBatch|@Transactional|batchSize' -- '*.kt' '*.kts' '*.java' '*.properties' '*.yaml' '*.yml' '*.xml' 'Dockerfile*'; echo "exit=$?"
api-application/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/wiring/GarbageCollectionProducers.kt:43:            batchSize = config.orphanBatchSize(),
api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorage.kt:13: * bounded by [batchSize] regardless of how many files or rows an active instance accumulates. The
api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorage.kt:17: * Not `@ApplicationScoped`: [batchSize] is a primitive ARC cannot resolve, so the bean is produced
api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorage.kt:28:    private val batchSize: Int,
api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorage.kt:31:     * Reclaim every orphaned rendition subtree and export archive on disk, batched by [batchSize].
api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorage.kt:39:            ids.chunked(batchSize).forEach { chunk ->
api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorage.kt:47:            parsed.chunked(batchSize).forEach { chunk ->
api-usecases/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorageTest.kt:23:    private val batchSize = 2
api-usecases/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorageTest.kt:30:        batchSize = batchSize,
api-usecases/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/ReapOrphanedStorageTest.kt:101:    fun `Given more disk ids than batchSize, Then reap processes them across multiple chunks`() {
exit=0
```

Every hit is `ReapOrphanedStorage`'s own chunk size for a filesystem sweep, unrelated to Ebean. The
six Ebean tokens and `@Transactional` match nothing, in code or in configuration. Enabling batching
by any of the three routes would move both violations to the commit or to the next flush and
invalidate both catches.

### Fact 2: the transaction stays usable, for reads and for further writes

Probed inside one transaction: write the conflicting row, catch the violation raised by the duplicate,
then keep using the transaction. Every follow-up worked, for `save` and for `merge` alike. The read of
the row written earlier in the same uncommitted transaction returned it, the read of committed data
returned it, a further unrelated write succeeded, and the commit succeeded with both written rows
surviving. SQLite rolled back the failing statement, not the transaction, and Ebean did not mark the
transaction rollback-only. The rollback run is what makes those lines mean anything: it discards the
transaction instead of committing it, and both the pre-violation row and the post-violation further
write are gone afterwards (0 and 0). Autocommit would have left both at 1, and would have shown that
the probe was never testing a transaction at all.

**The re-read is an inference here, not an observation.** The reads the probe ran after the violation
are a count by `dedup_key` and a count by `name`, not the `dedupKey` plus `state` query T6's catch
will run (`EbeanTaskQueue.kt:59-63`). What the probe establishes is the general property, that the
transaction accepts reads and writes after a caught violation, from which the specific query follows.
Nothing here pins that specific query, and nothing here should: T6's own test drives the catch and
asserts the task it returns, which covers the exact read directly.

So decision 3 takes the shape the specification's first branch describes: the catch sits in
`enqueueWithin` and re-reads the live task there, which needs no transaction of its own and therefore
works on both of `enqueue`'s branches. The asymmetric fallback the specification held in reserve is
not needed. The further-write result settles the ambient branch specifically: `UserDataExportRequester`
writes again after its enqueue (`UserDataExportRequester.kt:73`), and that write is unaffected by a
violation caught earlier in the same transaction.

### The two concurrency attempts: neither reproduces the production race

**`UserRepository.saveUser`.** Fifty rounds of eight threads, one fresh username per round, in three
shapes. The production shape (A), which wraps the check and the save in one transaction the way
`UserCreator.kt:21,27` does, produced zero failures in 400 attempts and exactly one row per round. The
premise the lot rests on is confirmed: the username violation does not reproduce in this process.

**`EbeanTaskQueue.enqueue`.** Fifty rounds of eight threads on one dedup key produced zero failures,
one row per round, and one distinct task id per round: all eight callers received the same task. The
dedup violation does not reproduce either, and the documented convergence is already delivered by the
fast path.

Neither attempt becomes a merged test, which is the finding rather than a gap.

### What the control cases add, and the one claim they narrow

Shapes B and C existed to keep A from being a check that cannot fail, and they turned out to say
something about the invariant recorded in `agents/project.md` under Design invariants.

C is the arithmetic of a value collision: eight concurrent bare `saveUser` calls on one name, no check
at all, gave 350 failures, exactly seven per round. Every unique index still refuses a duplicate value
however well writes are serialised, which is what T4's and T6's tests will lean on.

B is the interesting one. It is A minus the transaction: the same check-then-save pair, run as two
separate autocommit statements. It reproduced 335 times in 400 attempts, and three runs of the same
probe gave 335, 337 and 341, so it is a broad target and not a knife edge. The number of connections
is therefore not by itself what makes a check-then-insert non-racy here. A and B differ in exactly one
thing, whether the pair sits inside one transaction, and only B reproduces. The conclusion is
untouched, because both production sites do hold the pair inside one transaction
(`UserCreator.kt:21,27` for the username, `EbeanTaskQueue.kt:50` for the dedup key), but the stated
mechanism is one step wider than the observation supports: what serialises a read-write pair is the
transaction that holds the single connection across both statements, not the single connection alone.

That wording existed in three places. **This document's own Context** carried it and is corrected
above, because a dated document absorbs changes until it is delivered and it cannot contradict the
findings it now carries. **`agents/project.md`, under Design invariants** carried it as the invariant
itself, and is corrected in this same commit by the operator's decision of 2026-08-05: a living
document ships with the measurement that justifies the change, not after it. The heading now credits
the transaction rather than the connection count, and the corollary the old wording lacked is written
down, that a check-then-insert outside a transaction is racy today. The dated history stays and the
correction is appended to it.

**`docs/backlog.md:112-122`** is the third copy, in the entry this lot is working. It still reads
"the datasource is pinned to a single connection, which serialises them" and "a 500 the day the
single-connection decision changes". It is not corrected here: that entry is narrowed or deleted when
the lot wraps, which is where the project puts the change, and a rewrite now would be undone in the
same branch.

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
