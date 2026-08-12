# Handoff: every unique constraint names its outcome

Date: 2026-08-06. Branch: `fix/unique-index-named-outcomes` (off `main` at `f511b15`), 28 commits,
25 files, +1887/-111. Plan tier: Discuss, Spec, Plan, Act, Verify, Wrap, Improve.
Specification `docs/specs/2026-08-04-unique-index-named-outcomes.md`, decision record
`docs/adr/0009-unique-index-named-outcomes.md`, plan
`docs/plans/2026-08-04-unique-index-named-outcomes.md`.

## Current state

Done and verified. `./gradlew gate --rerun-tasks` green, 167 tasks executed from scratch. Eight
planned tasks, each reviewed on completion by a fresh subagent, then a holistic review over the whole
branch diff whose one MAJOR and five MINOR are closed. Not yet integrated: the PR is open and awaits
the human review `AGENTS.md` requires.

## What was built

**Two rules, both now enforced or written where a reader will meet them.**

1. Every unique constraint states what a client sees when it fires. `UniqueConstraintOutcomeTest`
   extracts them from the committed migrations in both spellings and compares the set to a declared
   table whose values are the outcomes. A new constraint fails the build until someone answers for it;
   a blank answer fails too.
2. No read before a write exists solely to answer a uniqueness question the index already answers.
   `agents/project.md` carries it under Design invariants, because no tool the project has can tell a
   uniqueness read from any other read.

**The inventory was wrong before the work started.** The backlog item said four unique constraints;
there are six. It had counted `create unique index` statements and missed two inline table constraints
from `@Column(unique = true)`, which SQLite enforces because they sit inside a `create table`.

**The six outcomes.** Two were already translated and did not move. Two received code:
`ix_users_name_nocase` now raises the domain `UsernameAlreadyTakenException`, which `UserCreator`
rethrows as `UsernameAlreadyTakenError` (409); `ux_tasks_dedup` converges, returning the live task the
dedup key names, which is what `TaskQueueInterface` already documented. Two received a written reason
for translating nothing: `uq_images_pin_id` cannot fire because its write path deletes then inserts in
one transaction, and `uq_session_tokens_token_hash` firing means the token generator repeated itself,
which is a broken invariant and honestly a 500.

**`UserCreator` lost its read-before-insert**, and `findUserByNameIncludingDeleted` left the port with
it. The tombstoned-name and case-insensitivity rules moved from a lookup to the index, and are pinned
by repository tests against the real store rather than against a mock. The end-to-end contract did not
move: `UserCreationIntegrationTest` is untouched and green, which is the witness.

**Four indexes entered Ebean's migration model.** `1.3.sql` created three that `1.3.model.xml` never
recorded, and `1.2` had no model file at all, so the generator could not see any of them. Both gaps are
closed by declaring each index on its entity and recording it in its migration's model file, without
touching a single `.sql`.

**The shared helper gained a recovery shape.** `SqliteConstraintViolations.onUniqueConstraint(error,
recover)` returns a value; `translateUniqueConstraint` is now its throwing special case. One
discriminator and one rethrow branch for four callers.

## Pitfalls learned

- **A measurement narrowed an invariant recorded two days earlier.** The single connection serialises
  each statement, not a pair: in autocommit each statement takes and releases it separately. The same
  check-then-insert reproduced 335 to 341 times in 400 attempts with the transaction removed, and zero
  with it. `agents/project.md` credited the connection; it now credits the transaction, and says a
  check-then-insert written outside a transaction is racy today.
- **"The generator reports no change" was a check that could not fail.** It reports no change on an
  untouched tree too. The task was rebuilt in two steps with a visible change between them: annotations
  alone make the generator emit DDL for indexes that already exist, and only then does its silence mean
  something. Caught by the plan review, before any code.
- **Ebean's own generated model file is the answer to the question the task was about to guess.** The
  two existing precedents disagree on the `<createIndex>` shape because they came from different
  annotation forms. Harvesting the generated elements rather than hand-writing them was load-bearing.
- **A `<dropIndex>` satisfied an assertion meant to require a create.** Ebean combines the model files
  to build the prior model, so a name known only through its removal is exactly the state the assertion
  refused. The wording came from the specification, and the implementer followed it correctly.
- **Reading raw `.sql` text makes a commented-out statement look like schema.** Both migration test
  classes now blank SQL line comments before matching.
- **Two agents on one working tree is a mistake, once.** A subagent still reachable by message amended
  its commit onto another agent's, folding two commits into one. Nothing was lost, verified by diffing
  the trees, but the fix is to stop an agent before dispatching the next.

## What is not validated against real conditions

- **Neither translated violation was ever produced by a real race.** Fifty rounds of eight threads at
  both sites produced zero failures, which is the finding rather than a gap: the transaction serialises
  the pair. Both catches are driven by `Persistor` decorators that create the situation a race would
  leave behind.
- **The premise holds in one process.** SQLite in WAL mode admits several writers across processes, so
  splitting the API and the worker reopens every race named here. Nothing enforces that they stay
  together.
- **The outcome table's values are prose and nothing reads them.** The assertion enforces that an
  outcome is named, not that it is true. A wrong entry passes; the compensating cover is that each
  translation has its own test.
- **Two entries assert an absence**, and no test fails when the reason for the silence stops holding.
  Make `EbeanImageRepository.saveWithin` insert without deleting first and `uq_images_pin_id`'s entry
  becomes false in silence.
- **A test JVM died on an `OutOfMemoryError` during the work**, leaving a 515 MB heap dump at the
  repository root, and one forced gate run failed with `SQLITE_BUSY` minutes earlier. Three consecutive
  forced runs then passed clean. Neither is reproduced and neither is tied to a change; both are in the
  backlog.

## Suggested next step

**Improve, which has not run.** Two lessons are worth the discussion. First, three separate times a
task falsified a living document an earlier task had written, and every time it was a review that
caught it rather than the gate: the corrections went to the documents whose decision changed and
missed the ones that merely described it. Second, an allowlist entry states a justification, and this
branch removed an exemption by checking its reason rather than inheriting it, which is a habit worth
naming.

After that, the backlog carries seven new items from this lot's own reviews. The two closest to this
work are the assertion pairing a `<createIndex definition>` with the DDL its migration applied, and the
one tying a partial index's `where` clause to the Kotlin query that mirrors it: both are cases where
two things agree by hand today.
