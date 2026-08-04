# Every unique constraint has a named outcome, and the database owns uniqueness

Date: 2026-08-04
Status: Approved 2026-08-04
Branch: `fix/unique-index-named-outcomes`
ADR: `docs/adr/0009-unique-index-named-outcomes.md`
Closes the backlog item "Two unique indexes have no named outcome, and an implicit invariant is what
closes them", surfaced by the holistic review of
`docs/handoffs/2026-08-04 - handoff - shared-sqlite-constraint-violations.md`.

## 1. Goal

Two rules, one about the schema and one about the code that writes to it.

1. Every unique constraint states what a client sees when it fires, and a test refuses the next one
   that arrives without an answer.
2. The database is the authority on uniqueness: no read-before-write exists solely to answer a
   uniqueness question the index already answers.

Two things are wrong today. Two write paths let `jakarta.persistence.PersistenceException` cross a
layer boundary, which `agents/modules/kotlin.md` forbids ("an adapter translates its framework or
driver exception into a domain error"). And nothing ties a unique index to an outcome, so the
decision was taken four times by four different authors, twice by writing a translation and twice by
writing nothing, with no record of which was deliberate.

## 2. Inventory, corrected

The backlog item counted four unique constraints. There are six: it counted `create unique index`
statements and missed two inline table constraints.

```
$ rg -n 'create unique index|constraint \w+ unique' api-persistence-sqlite/src/main/resources/dbmigration/*.sql
1.2.sql:2:   create unique index ix_users_name_nocase on users (name collate nocase);
1.3.sql:27:  create unique index ux_tasks_dedup on tasks (dedup_key) where dedup_key is not null and state in ('PENDING','RUNNING');
1.4.sql:13:    constraint uq_images_pin_id unique (pin_id),
1.8.sql:10:    constraint uq_session_tokens_token_hash unique (token_hash),
1.11.sql:2:  create unique index uq_user_data_exports_pending on user_data_exports (user_id) where state = 'PENDING';
1.18.sql:2:  create unique index ix_user_password_hashes_user_created on user_password_hashes (user_id, when_created);
```

`uq_images_pin_id` and `uq_session_tokens_token_hash` come from `@Column(unique = true)`
(`ImageModel.kt:16`, `SessionTokenModel.kt:20`) and are genuinely enforced: they are inline in a
`create table`, which SQLite accepts. The gotcha recorded in `agents/project.md` concerns the
`ALTER TABLE ADD CONSTRAINT` path Ebean takes when a unique constraint arrives after its table, which
SQLite refuses and Ebean renders as a `-- not supported:` no-op. That is a different situation from
these two.

## 3. Outcome per constraint

| Constraint | Write path | Today | Decided outcome |
|---|---|---|---|
| `ix_users_name_nocase` | `UserRepository.kt:54` | `PersistenceException` escapes | Translate to `UsernameAlreadyTakenException`, rethrown by `UserCreator` as `UsernameAlreadyTakenError` (409) |
| `ux_tasks_dedup` | `EbeanTaskQueue.kt:78` | no catch | Converge: return the live task the dedup key already names |
| `uq_images_pin_id` | `EbeanImageRepository.kt:33` | no catch | None needed: the path deletes by `pinId` then inserts, in one transaction |
| `uq_session_tokens_token_hash` | `SessionTokenRepository.kt:20` | no catch | None wanted: a token-hash collision is a broken generator, and a 500 is the correct answer |
| `uq_user_data_exports_pending` | `UserDataExportRepository` | translated | Unchanged: `ExportAlreadyInProgressException` |
| `ix_user_password_hashes_user_created` | `UserPasswordHashRepository` | translated | Unchanged: `PasswordChangeCollisionException` |

The last four rows are declarations, not code. Two of them already hold; two say in writing that
nothing should be written, which is the part that does not exist today.

## 4. The database owns uniqueness, and where that lands

The rule is deliberately narrow: a read before a write is forbidden when its **only** job is to answer
a uniqueness question the index already answers. A read that also does something else survives, and
what that something else is gets written down. The three sites that touch a uniqueness index land in
three different places, which is why the rule is phrased this way rather than as "delete the
pre-check".

**Username: the read goes.** `UserCreator.createUserInternal` (`UserCreator.kt:38-47`) reads the name
back before inserting and throws `UsernameAlreadyTakenError` when it finds one. That is its only job.
It leaves. Detailed in section 5.

**Password hashes: already conformant.** No read answers the uniqueness question of
`ix_user_password_hashes_user_created`, which is `(user_id, when_created)`. `PasswordChanger`'s three
reads serve three other rules: `findCurrentPasswordHash` for re-authentication (`PasswordChanger.kt:28`),
`current.createdAt` for the minimum interval (`:31`), `findAllPasswordHashesForUser` for the history
(`:35`). None asks whether a hash already exists at that instant. The database is already the sole
authority here, and this index is the one that collides by value rather than by interleaving
(`docs/adr/0006-domain-owned-timestamps.md:111`). Nothing to change.

**Exports: the read stays, and its second job is recorded.**
`UserDataExportRequester.kt:58` does ask the index's question, and `savePending` (`:82-92`) already
catches the violation and raises the same error. But the line also orders two refusals, and that
ordering is the behaviour a client depends on.

`findLastRequestedAtForUser` returns the last `requestedAt` in **any** state, so a live `PENDING` row
counts towards the minimum interval. Production sets `exports.minimum_interval=PT1H`
(`application.properties:63`) and a `PENDING` export lives seconds. Delete line 58 and the too-soon
check wins: a second request while an export is running answers `429 EXPORT_TOO_SOON` instead of
`409 EXPORT_ALREADY_IN_PROGRESS`, and the 409 survives only for a `PENDING` row older than an hour,
which means a stuck worker. That is the wrong answer to give: the problem in that moment is that an
export is in progress, not that the request came too soon.

So the pre-check stays, and the reason becomes explicit rather than accidental. Today nothing pins
that precedence: `MeExportIntegrationTest.kt:124-130` deliberately sets the interval to zero to isolate
the `PENDING` guard, so the case where both refusals apply is tested nowhere. A test is added that
pins 409 ahead of 429 when both apply, which turns the surviving read from an unexplained line into a
stated rule.

Insert-then-validate was considered and rejected: after the insert, `findLastRequestedAtForUser`
returns the row just written, so the too-soon check fires on the request's own insertion.

## 5. The username constraint in detail

**The pre-check is deleted and the index becomes the single authority.** The use case catches the
adapter's exception and rethrows its own error, the shape `PasswordChanger.kt:39-43` already uses for
`PasswordChangeCollisionException`. Consequences, each an acceptance criterion below:

- `findUserByNameIncludingDeleted` loses its only production caller. It is deleted from
  `UserRepositoryInterface`, from `UserRepository`, and from its tests. Verified sole caller:
  `rg -n 'findUserByNameIncludingDeleted' --type kotlin` returns `UserCreator.kt:42` plus
  `UserCreatorTest` and the two declarations.
- The tombstoned-name rule is preserved by the index, not by a lookup: `ix_users_name_nocase` covers
  every row of `users`, including soft-deleted ones, so a name held by an account pending deletion
  still collides. What was a use-case unit test becomes a repository test.
- Case-insensitivity likewise moves from Ebean's `ieq` to the index's `collate nocase`. Both fold
  ASCII only, so no behaviour changes, and a repository test pins it.
- `UserRepositoryTest.kt:197-205`, which asserts a raw `PersistenceException`, is rewritten to assert
  the domain exception. That test is the current contract; replacing it is the point of the change.
- The end-to-end contract does not move: `UserCreationIntegrationTest.kt:85-88` already pins
  `409 / USERNAME_ALREADY_EXISTS` and must stay green untouched.

The new exception is `UsernameAlreadyTakenException`, in a new `api-domain/.../domain/users/` package.
The two precedents place such an exception in the package of its concept rather than beside the port
that throws it (`domain/security/PasswordChangeCollisionException`,
`domain/exports/ExportAlreadyInProgressException`), and there is no `users` package yet.

## 6. The dedup constraint: convergence

`TaskQueueInterface:13-16` documents that a live dedup key returns the existing task without
inserting. `EbeanTaskQueue.enqueueWithin` implements that as a check-then-insert; the index is what
holds when the check loses. Catching it and re-reading the live task makes the documented behaviour
true on both paths rather than only on the fast one. The check-then-insert read is not a uniqueness
pre-check in the sense of section 4: it is the fast path of the documented convergence, and it returns
a value rather than refusing.

Two facts have to be established before this is written, which is why section 9 opens with an
experiment:

- **Where the violation surfaces.** `Persistor.save` and `merge` may or may not flush immediately
  under Ebean's batching. If the failure only appears at commit, the catch does not belong where this
  spec puts it.
- **Whether the transaction survives the caught violation.** The convergence needs a read after the
  failed insert, inside the same transaction. SQLite rolls back the statement, not the transaction, on
  a unique-index conflict, but whether Ebean leaves the transaction usable is not something to assume.

If the second fact comes back negative, the fallback is stated here so the plan does not have to
reopen the design: the catch moves out to `enqueue`, which retries the read in a fresh transaction,
and `enqueueWithin` stays as it is.

## 7. The migration model gaps in `1.2` and `1.3`

`1.3.sql` creates three indexes on `tasks`; `1.3.model.xml` records none of them, so Ebean's migration
model does not know they exist. Ebean builds the prior model by combining the migration XML and diffs
it against the entity beans (ebean.io/docs/db-migrations/detail), so declaring any of the three on
`TaskModel` today would make the generator emit a second `create index` for an index that is already
there.

```
$ for idx in $(rg -oI 'create (unique )?index (\w+)' -r '$2' *.sql | sort -u); do
    rg -q "indexName=\"$idx\"" model/*.xml && echo "OK      $idx" || echo "MISSING $idx"; done
OK      ix_session_tokens_expires_at
MISSING ix_tasks_claim
MISSING ix_tasks_lease
OK      ix_tasks_state_terminal_state_at
OK      ix_tasks_state_when_modified
OK      ix_user_password_hashes_user_created
MISSING ix_users_name_nocase
OK      uq_user_data_exports_pending
MISSING ux_tasks_dedup
```

**Decision: close both gaps, leaving no exemption.** Each index is declared on its entity with
`@Index(definition = ...)`, mirroring `UserPasswordHashModel.kt:16-19`, **and** recorded in its
migration's model file as a `<createIndex ... definition="...">` element, mirroring `1.11.model.xml`
and `1.18.model.xml`. The two halves go together: the model alone would make the generator want to
drop an index that exists, the annotation alone would make it emit a duplicate `create index`. The
observable in both cases is that `generateDbMigration` then reports no change.

- **`1.3`.** `ix_tasks_claim`, `ix_tasks_lease` and `ux_tasks_dedup` on `TaskModel`; three elements
  added to the existing `1.3.model.xml`.
- **`1.2`.** `ix_users_name_nocase` on `UserModel`; `1.2.model.xml` created, since `1.2` has no model
  file at all.

The `.model.xml` is generator state, not applied DDL: no checksum covers it and the runner reads only
`.sql`, so recording history there is not the applied-migration edit the project forbids. That
distinction is what makes `1.2` reachable. Its exemption in `DbMigrationModelCoverageTest.kt:26-29`
rests on a reason that does not apply: the comment says rewriting an applied migration would change
its checksum, but nothing here rewrites `1.2.sql`, and what is missing is a model file that never
existed. Two obstacles were checked and are absent: `users` was never rebuilt by a temporary-table
migration, so the index cannot have been silently dropped along the way (only `add column` and
`drop column`, on columns the index does not cover), and `UserModel` declares no `@Index` today.

Closing `1.2` empties the `handWritten` allowlist, so its assertion strengthens from "every `.sql` has
a model file or is listed here" to "every `.sql` has a model file".

This does **not** clear the beta-flattening backlog item. It clears one of that item's two debts, the
hand-written `1.2`. The `when_created` / `when_modified` column names remain, and those do require
rewriting applied migrations. The item narrows; the matching line under Design invariants in
`agents/project.md` is corrected in the same commit.

Residual risk, the same at both migrations: `MIndex.compare` diffs `indexName`, `tableName`, `columns`
and `definition`, so the XML has to match what the annotation produces exactly. That is reached by
running the generator and iterating, not by guessing. If a form resists, the fallback is the exemption
the lot is removing, documented then by an attempt rather than by a comment with the wrong reason.

## 8. The guard rail

Two assertions, both in `api-persistence-sqlite/src/test/.../migration/`, next to
`DbMigrationModelCoverageTest`, which is the project's declared home for an invariant about repository
content (`agents/project.md`, Conventions).

**A. `UniqueConstraintOutcomeTest`.** Extracts every unique constraint name from the committed `.sql`
files, in both spellings, and compares the set to a table declared in the test, each entry carrying its
outcome in a comment. A new unique constraint fails the test until someone writes down what a client
sees when it fires; a removed one fails it until the stale entry goes.

Its limit, stated rather than discovered later: it enforces that an outcome is **named**, not that the
name is **true**. A wrong outcome passes. What it stops is the silent one.

**B. An index-model assertion on `DbMigrationModelCoverageTest`.** Every index a `.sql` creates appears
as an `indexName` in some `model/*.model.xml`, with no exemption. This is the assertion that would have
caught the `1.3` gap: the existing test checks that a model file exists, not that it records the
migration's content.

Both arrive with the mutation that makes them fail, pasted in the commit message
(`agents/project.md`, Conventions).

The rule of section 4 gets no automated guard. A read that answers a uniqueness question is not
distinguishable by any tool the project has: Konsist sees declarations, not what a query means, and a
detekt rule keying on method names would catch the export read it is meant to allow. It lands in
`agents/project.md` as a design invariant, which is where a judgement call belongs.

## 9. Acceptance criteria

1. **The experiment ran and is recorded.** A concurrency attempt at both sites, on the model of
   `EbeanTaskQueueConcurrencyTest`, with its outcome written into the ADR whether or not it reproduces
   anything. The two facts of section 6 are answered by observation, not by reasoning.
2. `TaskModel` declares `ix_tasks_claim`, `ix_tasks_lease` and `ux_tasks_dedup` and `1.3.model.xml`
   records all three; `UserModel` declares `ix_users_name_nocase` and `1.2.model.xml` records it.
   `./gradlew :api-persistence-sqlite:generateDbMigration` produces no new migration, output shown.
   No `.sql` under `dbmigration/` is modified, shown by `git status --porcelain`.
3. `DbMigrationModelCoverageTest` carries the index-model assertion with no exemption, and its
   `handWritten` allowlist is empty. Its red is a mutation.
4. `UserRepository.saveUser` throws `UsernameAlreadyTakenException` (new, in `api-domain`) on a
   collision under `ix_users_name_nocase`, routed through `SqliteConstraintViolations`; any other
   `PersistenceException` still propagates untouched.
5. `UserCreator` no longer reads the name before inserting, catches that exception, and rethrows
   `UsernameAlreadyTakenError`. `findUserByNameIncludingDeleted` is gone from the port, the adapter and
   the tests.
6. Repository tests pin, against the real store, that a case-variant name collides and that a name
   held by a tombstoned account collides.
7. `UserCreationIntegrationTest` is unchanged and green: the 409 contract did not move.
8. `EbeanTaskQueue.enqueue` returns the live task when the insert loses the dedup race, under whichever
   of the two shapes section 6 leaves standing, with a test that drives the losing path.
9. A test pins the export refusal precedence: with a `PENDING` export **and** a non-zero minimum
   interval both applying, the answer is `409 EXPORT_ALREADY_IN_PROGRESS`, not `429 EXPORT_TOO_SOON`.
   It fails if `UserDataExportRequester.kt:58` is deleted, which is its mutation.
10. `UniqueConstraintOutcomeTest` exists, lists the six constraints with their outcomes, and its red is
    a mutation.
11. `./gradlew gate` green, output in the same message as the completion claim.
12. `agents/project.md` carries both rules under Design invariants, in one line each; its
    migration-history line no longer claims `1.2` as an accepted cost; the backlog item this lot closes
    is removed in the branch and the beta-flattening item is narrowed to the column names.

## 10. Out of scope

- **Making any of these violations reachable.** The single-connection datasource is not revisited. The
  work makes the outcomes explicit and cheap on the day that decision changes; it does not change it.
- **Multi-process deployment.** The in-process serialisation invariant holds because the API and the
  worker share one Quarkus process. Splitting them would reopen every race here. Named in the ADR,
  not addressed.
- **The `when_created` / `when_modified` column names.** Still owed to the beta flattening item, which
  this lot narrows rather than closes: correcting those names does require rewriting applied
  migrations, which `1.2`'s model file did not.
- **Unifying the ambient-transaction logic** in `EbeanTaskQueue` and `EbeanImageRepository`: its own
  backlog item, untouched even though this work reads both.
- **Whether the minimum-interval rule should count a live `PENDING` export.** Section 4 takes the
  current semantics as given and only fixes the precedence between the two refusals. Changing what
  `findLastRequestedAtForUser` counts is a product decision with its own specification.
- **Non-unique constraints.** Foreign keys, NOT NULL and check constraints keep whatever they do today.
  The rule this lot introduces is about uniqueness only, because that is the class whose violation is a
  plausible applicative answer rather than a bug.
