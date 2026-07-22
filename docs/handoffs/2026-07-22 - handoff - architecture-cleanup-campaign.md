# Handoff — Architecture cleanup campaign (A → B → C → D)

**Date:** 2026-07-22
**Scope:** The four architecture items tracked in `docs/backlog.md` (P2), executed as one
sequenced campaign of independent sub-projects, each with its own branch + PR.

This handoff is updated as each item lands. It is the running record for the campaign.

## The four items and their order

Ordered by hard dependencies, not preference:

- **A. `Session*` → `TransactionRunner`.** Route `SessionCreator` / `SessionRenewer` through the
  `TransactionRunner` port, dropping `@Transactional`. **Prerequisite for D** (its Konsist rule bans
  `jakarta.transaction` from `api-usecases`; these two were the last violators).
- **B. Consolidate misplaced adapters into a dedicated module.** `SecureTokenGenerator`
  (presentation), `SystemClock` (presentation/tasks), `BcryptPasswordHasher` (`api-application`) →
  a new adapter module depending on `api-domain` only. **Provides the home `SystemClock` needs for C.**
- **C. Extract the task-worker runtime into `api-worker-quarkus`.** Move the whole `tasks/` driving
  subsystem out of `api-presentation-quarkus`. **Depends on B** (SystemClock stays out of the worker
  module). The big one: warrants a structured spec + plan.
- **D. Konsist boundary enforcement.** Capstone: encode the module DAG + import bans in CI.
  **Depends on A** (and ideally B + C, to encode the final DAG).

Integration cadence: **Option 1** — merge each item before starting the next (branch the next off a
fresh `main`).

## Status

- **A — DONE (merged to `main`, PR #25, 2026-07-22).**
- B — not started.
- C — not started.
- D — not started.

## A — what was built

`SessionCreator.create` and `SessionRenewer.renew` now wrap their bodies in
`transactionRunner.inTransaction { ... }` and return the `IssuedSession` from inside, exactly as
`UserCreator` does. `@Transactional` and its import are gone. `api-usecases` no longer imports
`jakarta.transaction` anywhere.

Behaviour is unchanged: authentication stays inside the transaction for the creator; the atomic
save-new-then-delete-old rotation is preserved for the renewer. No wiring change was needed — the
`TransactionRunner` bean already existed and CDI injects it (confirmed by the green `api-application`
integration tests exercising real login/renew).

### Learned pitfalls

- **Local JDK default matters for the Quarkus packaging step.** The Gradle toolchain compiles to
  Java 25, but `quarkusAppPartsBuild` forks a JVM from the ambient `JAVA_HOME`. With a Java 21
  default, `./gradlew build` fails at packaging with `UnsupportedClassVersionError` (class file
  version 69.0 vs 65.0) even though all tests pass. Fix: make Java 25 the default (the operator set
  `25-tem` as the sdkman default on 2026-07-22), or set `org.gradle.java.home`. Unit/use-case tests
  alone do not surface this — only the full `build` (packaging) does.
- The "writes live inside the transaction" test is cheap and worth doing: stub `inTransaction` as a
  no-op (never invoking the block) and assert zero repository writes. It subsumes a plain
  `verify { inTransaction(...) }` and proves the boundary, not just the call.

### Not validated against real hardware

Nothing hardware-specific in A. Full gate (all module tests + `api-application` integration +
`koverVerify` 100% branch coverage + detekt) is green in CI (`validate / gate`) and locally under
JDK 25.

## Suggested next step

Start **B** off a fresh `main`. Brainstorm/spec it (simple/inline is likely enough): pick the module
name (`api-security` vs `api-system` vs a neutral `api-adapters-system`), create the Gradle module
mirroring `api-fetch-http` (depends on `api-domain`, `compileOnly` CDI), move the three adapters with
their tests, and watch Quarkus CDI bean discovery (the new module needs the `jandex` plugin so its
beans are indexed). Confirm the `api-application` composition still resolves `Clock`, `TokenGenerator`,
and `PasswordHasher` beans exactly once.
