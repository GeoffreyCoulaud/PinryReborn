# Handoff: shared SqliteConstraintViolations helper

Date: 2026-08-04. Branch: `refactor/shared-sqlite-constraint-violations` (off `main` at `f9cebdc`),
2 commits. Direct tier, so no spec and no plan: the extraction was already arbitrated by the backlog
item it closes, itself deferred from T3 of the operational-debt wave
(`docs/handoffs/2026-08-02 - handoff - operational-debt-wave-delivered.md`).

## Current state

Done. `./gradlew gate` green, a fresh-subagent holistic review passed with no CRITICAL and no MAJOR,
and its three actionable MINOR findings are fixed in the second commit. Behaviour is unchanged
everywhere: the two collision-translating repositories answer exactly what they answered before.

## What was built

`SqliteConstraintViolations`, an `internal object` in
`api-persistence-sqlite/.../repositories/`, next to `ActiveUserModels` and following its shape. It
exposes one function:

```kotlin
translateUniqueConstraint(error: PersistenceException, toDomainError: (PersistenceException) -> Throwable): Nothing
```

It always throws: the caller's domain error for a unique-index violation, the original failure
untouched otherwise. The discriminator (`error.cause as? SQLiteException`, then
`resultCode == SQLITE_CONSTRAINT_UNIQUE`) is private beneath it.

`UserPasswordHashRepository` and `UserDataExportRepository` lost their companion objects and now
call it with a lambda naming their own error (`PasswordChangeCollisionException`,
`ExportAlreadyInProgressException`). The two near-identical `*CollisionDecisionTest` classes, six
tests each, collapse into `SqliteConstraintViolationsTest`, three tests, which also asserts the
factory is never invoked on the rethrow path: the old pair asserted only that the original
propagated.

## Pitfalls learned

- **The rethrow branch decides where the code can live.** No real store can be made to raise a
  non-unique `PersistenceException` through a repository's public save, so that branch is reachable
  only from a unit test calling the function directly. Leaving the `if` inline in each `catch` would
  have made it uncoverable, and the gate's 100% branch bound is per package. That is why the helper
  takes the exception factory instead of merely answering a predicate: it is the branch, not the
  discriminator, that had to move.
- **`rg -rn` is not "recursive, numbered".** `-r` is `--replace`, so `rg -rn 'foo'` prints matches
  with the text replaced by `n`. It cost one round of reading a grep output that appeared to show a
  function renamed to `n` in a file nobody had touched. Ripgrep recurses by default: `rg -n`.

## What is not validated against real conditions

- **Nothing is exercised against a concurrent writer.** Both translations are pinned by
  duplicate-insert tests that insert twice in sequence (`UserPasswordHashRepositoryTest.kt:81`,
  `UserDataExportRepositoryTest.kt:263`). The race these constraints exist to catch has never been
  reproduced under real concurrency, before this change or after it.
- **Those two tests are now load-bearing rather than illustrative.** Each call-site lambda contains
  no branch, so the coverage bound no longer forces either site's translation to be executed: before
  this change, each repository owned a branching `translateIfCollision` that the package bound could
  not leave unexercised. Delete either duplicate-insert test and the gate stays green while that
  endpoint's 409 silently degrades to a 500. Raised by the holistic review; recorded rather than
  fixed, since a test guarding a test is worse than the exposure.
- **The vendor `errorCode` 19 claim rests on an observation, not on a test.** The KDoc says the
  unique and NOT NULL violations were both observed carrying it, which is why the typed `resultCode`
  is the discriminator. No test asserts on `errorCode`, and the wording was corrected during review
  after it had been widened to "every constraint kind".

## Suggested next step

The backlog item this review created: **two unique indexes have no named domain outcome**. Four
unique indexes exist in the schema and only the two touched here translate their violation.
`UserRepository.saveUser` lets the raw framework exception escape on a username-case collision, and
`UserRepositoryTest.kt:197` pins that leak as expected, so a sign-up race answers 500 where the
password path answers 409, against `docs/adr/0006-domain-owned-timestamps.md:111`.
`EbeanTaskQueue.enqueueWithin` has the same shape behind `ux_tasks_dedup`. The helper this branch
built is what those two sites would call; the lot's real content is the missing tie between a unique
index and a named applicative outcome, so that the next index does not decide again in silence.
