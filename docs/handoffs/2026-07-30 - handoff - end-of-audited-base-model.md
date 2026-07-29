# Handoff: end of `AuditedBaseModel` (block 2 of domain-owned timestamps)

Branch: `refactor/end-audited-base-model` (cut from `main` on 2026-07-29).
Tier: Plan (three tasks). Spec: `docs/specs/2026-07-29-end-of-audited-base-model.md`,
plan: `docs/plans/2026-07-29-end-of-audited-base-model.md`. No new ADR: the parent
`docs/adr/0006-domain-owned-timestamps.md` already carries decision D6 ("delete
`AuditedBaseModel` and ban `@When*`"); this block executes it.

## Current state

`AuditedBaseModel` is gone. Its four subclasses (`TaskModel`, `SessionTokenModel`,
`UserDataExportModel`, `UserPasswordHashModel`) extend `BaseModel` and declare only what they need;
`UserModel` and `TagModel` dropped their direct `@WhenModified`. Eight dead audit columns are dropped;
the two creation instants the business reads (`session_tokens.when_created`,
`user_password_hashes.when_created`) are reused as the domain-mapped `createdAt`, written by the use
case from `Clock`, never auto-stamped. The task retention sweep reads `terminalStateAt`, written
explicitly on every one of the five terminal paths, and a Konsist assertion bars `@WhenCreated` /
`@WhenModified` from production. `./gradlew gate` is green, 100% branch coverage per package holds, and
the branch is ready to integrate through a rebased pull request.

The block also pins seven corrections to the frozen parent spec
(`docs/specs/2026-07-29-domain-owned-timestamps.md` section 6): the drop count (eight not seven), the
column reuse (no `created_at` added), no table rebuild, the tasks index move, the fifth terminal path,
the explicit-write finding, and the migration being two events not one pair. The parent's status line
is not yet cross-linked; do that on `main` after the merge if the frozen text still reads as one pair.

## What was built

**Domain.** `Task.terminalStateAt: Instant?`, `SessionToken.createdAt: Instant`,
`HashedPassword.createdAt: Instant`. `TaskQueueInterface.cancelPending(id, now: Instant)` and
`deleteTerminalBefore` whose KDoc names `terminalStateAt`. `PasswordHasher.hash(raw, createdAt)` (the
adapter receives the instant, the `softDeletePin(pin, at)` idiom).

**Task queue (`EbeanTaskQueue`).** The five terminal paths each stamp `terminalStateAt`:
`markSucceeded`, `markDead`, `markCancelledIfRequested` and `cancelPending` add `.set("terminalStateAt",
now)` to their `asUpdate()`; the exhausted-task kill inside `claimNext` sets it on the bean before the
save. `deleteTerminalBefore` filters `.terminalStateAt.lessThan(cutoff)`. `TaskModel` keeps its
`@Index(state, terminal_state_at)` (moved off `when_modified`). `CancelTask` injects `Clock` and passes
`clock.now()`; its `cancel(id)` signature is unchanged.

**Models.** `AuditedBaseModel.kt` deleted. `SessionTokenModel` and `UserPasswordHashModel` declare
`@Column(name = "when_created") var createdAt`; `UserDataExportModel` drops both audit columns
(`requestedAt` is its creation instant); `TaskModel` drops both (never read). `SessionTokenModelMapper`
and `UserPasswordHashModelMapper` round-trip `createdAt`; `UserPasswordHashRepository.findCurrentPasswordHash`
orders on `.createdAt`.

**Use cases.** `SessionCreator`, `SessionRenewer`, `UserCreator`, `PasswordChanger` stamp `createdAt`
from `Clock` (`PasswordChanger` gains `Clock`). The constant-time `dummyHash` in `UserAuthenticator`
takes `Instant.EPOCH`: never persisted, never read, the one deliberate blemish, with a comment.

**Migrations.** `1.15.sql` adds `tasks.terminal_state_at` and moves the tasks index in place. The eight
drops are a `pendingDrops` pair: `1.16.model.xml` records them (there is no `1.16.sql`, the pure-drops
signature), `1.17__dropsFor_1.16.sql` emits them as eight in-place `alter table ... drop column`. No
table rebuild anywhere; `ix_users_name_nocase` survives (guarded by `UserRepositoryTest`).

**Konsist.** `ArchitectureKonsistTest` gains "none imports the Ebean generated-timestamp annotations",
`assertEmpty()` over both annotation classes across all production.

## Pitfalls learned

- **Bulk `asUpdate()` updates bypass `@WhenModified`.** The current `when_modified`-based task retention
  was more than misowned: it was broken on the four `asUpdate()` terminal paths, which never moved the
  column, so the sweep measured claim time, not terminal time. Verified against the Ebean documentation
  (ebean.io/docs/query/update and /mapping/extensions/when-modified), not recalled. Block 2 fixes the
  semantics by writing `terminalStateAt` explicitly, and the five-path test pins it.
- **There are five terminal paths, not four.** The exhausted-task kill inside `claimNext`
  (`EbeanTaskQueue.kt:104-112`) is a bean save to `DEAD`, the fifth path the frozen spec's section 6.2
  omitted. Easy to miss because it is not a `mark*` method.
- **Pure drops produce no apply SQL.** When the only change in a generator run is column drops, Ebean
  records them as `pendingDrops` in the version's `model.xml` and emits no `1.16.sql`; the drops land in
  the `__dropsFor_1.16` file from the second run. So a "version with no `.sql`" is legitimate, and
  `DbMigrationModelCoverageTest` (which pairs each `.sql` with a model) is unaffected.
- **A port signature change forces its callers and their mocks.** `cancelPending(id)` -> `cancelPending(id,
  now)` forced `CancelTask` (the one main caller, which then needs `Clock`) and `CancelTaskTest`'s mock,
  neither of which the first plan draft listed. The plan review caught it; the lesson is to trace callers
  of a changed signature, not just the changed file.
- **A required domain field breaks every construction site at compile time.** Adding `createdAt` to
  `SessionToken` and `HashedPassword`, and the `hash(raw, createdAt)` signature, broke ~13 test fixtures
  across three modules. They are mechanical `createdAt = <instant>` additions and `hash(any(), any())`
  widenings, found through compile errors and proven complete by the gate.
- **`core.hooksPath` may be unset in a clone**, so the pre-commit hook enforces nothing: the OpenAPI
  sync and the no-em-dash check were done by the gate (`checkNoLongDashes`) and this branch changed no
  wire contract, so nothing was missed. Run `git config core.hooksPath .githooks` to get the early
  warning.

## Not validated against real conditions

- **Neither migration has been applied to a database holding a row stamped under the old columns**, and
  none exists. Both were generated, read, and run against an in-memory store built from the full history,
  but the interesting cases (a terminal task with null `terminal_state_at`; a row whose `when_modified`
  was the retention signal) have no instance anywhere. The consequence and the recovery `update` are
  written into the migration files; neither has been run.
- **The five-path terminal semantics are proven on the in-memory test store, not against a long-running
  queue under load.** The dispatcher hands `clock.now()` on every path, and the tests assert each writes
  it, but no real retention cycle has been observed.
- **The `dummyHash` sentinel (`Instant.EPOCH`) is a deliberate blemish.** It is never persisted and never
  read (`matches` discards the dummy's result), but it is a placeholder instant where a real one would be
  more honest; the alternative (a `Clock` on `UserAuthenticator` used only for the dummy) was judged the
  larger smell.
- **The local gate does not cover the multi-architecture container image build** or the `docs/openapi.json`
  sync, both behind CI's `validate / gate`. This branch changed no wire contract, but integration still
  goes through a pull request for that reason.

## Suggested next step

- Integrate: push and open a pull request, merge with `gh pr merge --rebase` once the human review has
  come back. Squash is disabled on this repository.
- Then run Improve, from the input below.
- Then **block 3, current-password determinism**, the last block of the P0 entry. Block 2 already added
  `HashedPassword.createdAt` (domain-stamped) and ordered `findCurrentPasswordHash` on it, so block 3
  adds only the `(user_id, created_at)` unique constraint, the configurable minimum interval between
  password changes (default 30 s), and the two error codes (`PASSWORD_CHANGE_COLLISION` 409,
  `PASSWORD_CHANGED_TOO_SOON` 429). It must be specced: two new public error codes are a contract change.

## Improve input (failures the gate did not catch)

- **The first plan draft did not list files a signature change forces.** `cancelPending`'s new parameter
  forced `CancelTask` and `CancelTaskTest`, and the required `createdAt` broke ~13 test fixtures; both
  were caught by the plan review, not by anything that runs. The compile error would have caught them at
  Act time regardless, so this is a planning-completeness lesson rather than a gate gap. Candidate
  remedy: a judgement call that a changed port signature traces its callers and their mocks in the plan,
  but the plan review already does this well enough that retaining nothing is defensible.
- **`reapExpired` did not assert `terminalStateAt` stayed null.** The spec named reap as a path to cover,
  the implementer covered only retry, and the task review caught it. The remedy (the assertion, with its
  falsification) is already in. The general shape, "a path the spec names is half-covered," is what the
  task review is for, and it held.
- **Retaining nothing is the likely outcome.** The block shipped clean: the two defects found (the
  unlisted files, the half-covered path) were both caught before merge by the reviews the workflow
  mandates, and the gate enforced everything it was asked to. The only durable note worth carrying is
  the Ebean bulk-update fact above, which belongs in a handoff (here) rather than a rule.
