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
- **B — DONE (merged to `main`, PR #26, 2026-07-22).**
- **C — DONE (merged to `main`, PR #27, 2026-07-22).**
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

## B — what was built

New `api-system` module (name chosen over `api-security`: it holds two security adapters plus the
clock, so the neutral "system primitives" umbrella fits all three). It depends on `api-domain` only
and carries the `jandex` plugin. The three adapters moved in with `git mv` (history preserved), each
with a one-line package change to `fr.geoffreyCoulaud.pinryReborn.api.system`; their tests moved too.
`api-application` gained `implementation(project(":api-system"))` and dropped its direct
`libs.jbcrypt` dependency. `settings.gradle.kts` includes the module.

Presentation keeps its genuine HTTP-security classes (`BearerAuthenticationMechanism`,
`BearerTokenIdentityProvider`, `AuthRuntimeProducers`, ...) in its `security` package: only
`SecureTokenGenerator` left. Presentation's `tasks` package keeps the worker runtime (that is item C);
only `SystemClock` left it.

### Learned pitfalls (B)

- The settings file is `settings.gradle.kts` (Kotlin DSL), not `settings.gradle`.
- No module needed a compile dependency on `api-system`: every consumer references the domain ports
  (`Clock`, `TokenGenerator`, `PasswordHasher`), never the concrete adapters. Only `api-application`
  (composition root) depends on it, purely so Quarkus discovers the beans at runtime. This is the
  invariant to preserve when item C adds the worker module.
- `SystemClock` has no test and no branches, so per-package branch coverage is unaffected by the move.

## C — what was built

New `api-worker-quarkus` module (package `...api.worker`), `api-usecases` + `api-domain` only, holding
the eight task-worker files + six tests (`git mv`, history preserved). Two commits: first decouple
`PinDownloadTaskHandler` from `ImagesConfig` (Option 2b: plain class taking `maxBytes`/`maxPixels`,
produced by a new `api-application` `TaskHandlerProducers`), then the pure module move. See
`docs/specs/2026-07-22-worker-runtime-extraction.md` and the matching plan.

### Learned pitfalls (C)

- **Hidden transitive classpath coupling in presentation tests.** The plan trimmed presentation's
  worker-only Quarkus deps. Removing `testImplementation(quarkus-micrometer)` broke the *pre-existing*
  Bearer auth mechanism tests: `quarkus-micrometer` was the only thing transitively putting `io.vertx.*`
  and `io.quarkus.vertx.http.*` on the **test** classpath (on main those arrive via
  `quarkus-smallrye-openapi`, which the test source set does not depend on). Lesson: a build-dep trim
  is only safe once the full gate is green, not once the changed module compiles;
  `dependencyInsight --configuration testCompileClasspath` pinpoints the real provider. `quarkus-core`,
  by contrast, was genuinely worker-only and stayed removed.
- **Producer vs discovered bean:** dropping `@ApplicationScoped` from `PinDownloadTaskHandler` is
  mandatory under Option 2b — a discovered bean with `Long` constructor params is unresolvable, and a
  class must not be both discovered and produced.
- The worker module reproduced the `api-system` invariant: only `api-application` (composition root)
  depends on it; every other consumer references the use-case/domain ports.

## Suggested next step

Start **D** (the campaign capstone) off a fresh `main`: add Konsist checks that fail the build on layer
violations — `api-usecases` importing `jakarta.transaction` / `io.ebean` / `jakarta.ws.rs` / a concrete
crypto-random library; `api-domain` importing any I/O; and the module dependency DAG (now including
`api-system` and `api-worker-quarkus`). Items A–C were done partly to make these rules pass cleanly, so
D should go green with little churn. Use the context7 MCP for Konsist's current API. Decide where the
Konsist test source set lives (a dedicated `api-architecture-tests` module is the usual pattern, so the
rules can see every module on the classpath).
