# Handoff: the P2 band stops growing

Date: 2026-08-12. Branch: `chore/p2-backlog-triage` (off `main` at `672ad21`), 24 commits, 30 files.
Tier: Spec, so Discuss, Spec, Act, Verify, Wrap, Improve. Specification
`docs/specs/2026-08-12-p2-debt-triage.md`, decision record
`docs/adr/0010-review-finding-dispositions.md`. No plan document: the nine tasks are listed in the
spec's section 4.

## Current state

Done and verified. `./gradlew gate --rerun-tasks` green, 167 tasks executed from scratch, run twice:
once after the nine tasks and once after the holistic review's fixes. Nine tasks, each reviewed on
completion by a fresh subagent, then a holistic review over the whole branch diff whose three MAJOR
and five MINOR are closed. Not yet integrated: no PR is open.

## What was built

**Two halves. The first resolves sixteen backlog items, the second changes what becomes a backlog
item at all.**

The sixteen P2 items are disposed of: eight fixed, two closed by a decision the items themselves
already carried, two moved to a Before beta band, one returned to the ADR that already recorded it,
three left as open work. The band ends at **five**, not three, because this lot's own reviews filed
two new items. That arithmetic is written in all three documents rather than rounded down.

**A review finding now has four exits** (`docs/adr/0010-review-finding-dispositions.md`): fixed
inside the lot, a backlog item meaning work someone will do, an accepted limit written where the
decision lives, or refused with the reason here. Before it, the only exit was the backlog, so the lot
that preceded this one closed one item and added seven. **The backlog is banded by nature** before
priority, so a documented limit stops being counted as debt. **And a case joins an existing
integration suite** rather than getting a class of its own, which is what decided two of the sixteen
in opposite directions.

**Where each finding of this branch's own reviews went**, since the rule's observable is that list:

| Exit | Count | Examples |
|---|---|---|
| Fixed inside the lot | 14 | the sweep guard blind to a drop; a guard defeated by deleting an attribute; a KDoc describing its own reach falsely |
| Backlog item | 2 | the partial indexes serving no query; the integration suite writing to a file |
| Accepted limit | 5 | the annotation literals no compile-time constant can source; the guard's quoted-literal reach |
| Refused | 1 | a module-wide sweep of `createAndSaveUser` duplication: the four signatures diverge, so a shared base would stop them compiling, and the benefit is small |

**Four migration guards now read one directory through one reader**, `MigrationDirectory`, which
names its two readings: `currentIndexes` replays creations and removals in version order and says
what the schema holds today, `allIndexCreations` says what the history ever carried. Three of the
four gained assertions: a model's `<createIndex>` is paired with the DDL its migration applied, with
no branch that skips one; every partial unique index has its state set named once in Kotlin and
compared to the DDL; and no `definition` attribute sits outside a `<createIndex>`.

**The three collision exceptions and two of the three collision errors now require their `cause`**, so
dropping the chain is a compile error rather than a test someone forgot to write.
`UserCreator.createUser(name)` is gone. `PinRepositoryTest` is 337 lines with no suppression, its
soft-delete slice split off, its cursor tests returned to the pagination slice that existed, and the
fixtures the four slices copied now in one base.

## Pitfalls learned

- **A guard was asserting a false statement about the schema, and had been since 1.15.**
  `SweepIndexesMigrationTest` demanded an index on `tasks (state, when_modified)` by concatenating
  every migration and matching anywhere. `1.15.sql:6` had dropped that index and replaced it with
  `ix_tasks_state_terminal_state_at`. The holistic review raised drop-blindness as a future risk; it
  had already happened, silently, and no per-task review saw it because each looked at one task.
- **A commit body of mine asserted the opposite of what the code said.** T4 justified its scope by
  citing `PasswordChangeError` as an error whose cause is sometimes absent. It has one construction
  site and always passes one. The review measured the sites instead of reading the claim.
- **A structural assertion shipped without its mutation, in a lot whose subject is guards.** The
  project's convention exists precisely for this and I broke it on my own first commit; the review
  caught it, and reconstructing the red found the mutation the assertion was really closing.
- **Two agents on one working tree is still a mistake, and a dead agent leaves nothing behind.** One
  subagent died on an API 529 immediately after being handed a review's findings. It had written
  nothing, and the tree was clean, but nothing it had been told survived either: the findings had to
  be re-applied from the review report.
- **A backtick in a `git commit -m` message is a shell substitution.** Several words vanished from a
  commit body before it was amended from a file. Every long message here is written with the edit
  tool and passed with `-F`.

## What is not validated against real conditions

- **The rules are process, and the gate cannot run them.** Nothing fails when a finding takes the
  wrong exit. The compensating control is that Wrap enumerates the exits, as the table above does,
  and the next holistic review reads that list.
- **`currentIndexes` has no drop to replay in the committed history except `1.15`'s.** Its
  drop-and-recreate path was exercised by a temporary migration and then deleted, so nothing in the
  repository pins it.
- **The `Known limits` band points at documents nobody has re-read since.** A pointer that goes stale
  fails differently from a copy that drifts, and neither is checked.
- **Two of the four exits were used once or twice each.** Whether "accepted limit" stays honest under
  pressure, rather than becoming where inconvenient findings go, is a question the next two lots
  answer, not this one.
- **The integration suite writes to `api-application/data.db`** while its properties declare
  `:memory:`, so every api-application test in this branch ran against state that survives between
  runs. It is in the backlog, and it is a candidate cause for the `SQLITE_BUSY` this same lot closed
  as unreproduced.

## Suggested next step

**Integration, then Improve.** The PR is not open yet.

Improve has two candidates already visible. The first is the one this branch keeps re-learning: a
guard that reads an append-only history as if it were the current state passes forever, and three
separate guards in this repository have now made that mistake. It may be worth a Konsist or a plain
test over the test sources themselves. The second is that the arithmetic in three documents
disagreed with the file they described, which a reader can check in seconds and no tool checks at
all.

Of the five P2 items left, the two new ones are the cheapest and the most concrete: the partial
indexes are measured on both sides and need only a decision, and the test database is a
configuration question with a short answer.
