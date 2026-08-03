# Handoff: structural soft-delete read isolation (ADR 0008)

Date: 2026-08-03
Branch: `refactor/soft-delete-read-isolation`
Spec: `docs/specs/2026-08-03-structural-soft-delete-read-isolation.md`
ADR: `docs/adr/0008-structural-soft-delete-read-isolation.md`
Extends: ADR 0007 (single-representation soft delete).

## Current state

Merged on the branch, gate green (`./gradlew gate --no-daemon` BUILD SUCCESSFUL: detekt with type
resolution, all tests, 100% branch coverage per package, `checkNoLongDashes`). The branch is final-review
clean (two whole-branch reviews: the first found the `io.ebean.DB`/`Ebean` static-facade hole, the second
confirmed it closed). Awaiting PR review before merge to `main`.

## What was built

An unfiltered read of a recyclable model is no longer expressible outside the `queries` package in any
ordinary form. The read capability is confined:

- `io.ebean.Database` is held in exactly three files (`EbeanDatabaseProducer`, `EbeanPersistor`,
  `EbeanTransactionControl`); every other site writes through `Persistor` or scopes transactions through
  `TransactionControl`. D3 (Konsist) holds the import confinement.
- `ModelRepository` no longer extends `BeanRepository`; D4 (Konsist, `withParent` bare-name) bars
  `BeanRepository`/`BeanFinder` as production supertypes.
- `io.ebean.DB`/`Ebean` static facades are banned: D5 (Konsist) bans their imports, the
  `DatabaseStaticFacadeCall` detekt rule catches the fully-qualified call written with no import.
- 0007's recyclable-query-bean ban still holds the query-bean route.

Twelve consumer sites switched to the ports; 17 `QXModel(database)` constructions became no-arg. No
behaviour changes (reads and writes do the same work through narrower types); no migration.

## Pitfalls learned (read before touching the detekt-rules module)

- **Gradle daemon caches the detekt plugin classloader.** A newly added or changed custom detekt rule is
  not picked up by a long-lived daemon: detekt passes whether or not the rule fires. Run `./gradlew
  --stop` or pass `--no-daemon` after a rule change. CI is unaffected (fresh daemon per job). This cost a
  long debugging session: the rule's `lint()` unit tests passed but the task did not fire it, until the
  daemon was restarted.
- **`withParentClassOf` is a silent no-op in Konsist 0.17.3 for external-library types.** It filters
  parents through `sourceDeclaration?.isClass`, and external types resolve to `KoExternalDeclarationCore`,
  not `KoClassDeclaration`, so `isClass` is false and the parent is dropped: the assertion passes with a
  real violation present. Use `withParent { parent.name... }` (bare name). Caught by the mutation-red.
- **Ebean `Database.reference` is non-null-bounded in Kotlin.** `Persistor.reference` must be
  `<T : Any>`, not the unbounded `<T>`: `Class<T>` vs `Class<T & Any>` compile error otherwise.
- **detekt rule coverage.** An import-list check creates a null branch `lint()` fixtures cannot reach
  (the parsed expression always has a real `KtFile`), failing the 100% bound. The rule was simplified to
  FQN-only (the imported form is D5's job), which removed the unreachable branch. Lesson: prefer guards
  whose branches are all reachable from `lint()` snippets, or split the concern across tools.
- **Qualified method calls vs constructor calls in detekt.** `visitDotQualifiedExpression` sees
  `pkg.Type()` (constructor) and `recv.method()` differently; verify a new rule's visitor against a real
  production-shaped mutation, not only `lint()` snippets.

## Not validated against real conditions

- The guarantee is build-time, not compile-time proof of the result (ADR 0008 decision 5, consistent with
  0007). The residual routes (raw SQL on `any()`, in-memory read after a sanctioned query, FQN typed
  reference without import) remain open and are tracked in `docs/backlog.md`.
- The ambient-transaction logic in `EbeanTaskQueue` and `EbeanImageRepository` is unchanged in behaviour
  (now over `TransactionControl`); unifying it behind `TransactionRunner` is deferred (backlog), because
  it changes Ebean nesting semantics.

## Suggested next step

Review and merge the PR (rebase-only; `gh pr merge --rebase` once the human review is in). After merge,
reconcile the backlog on `main` if anything stale survived. The next substantive soft-delete work, if any,
is the ambient-transaction unification (its own spec) or closing a residual route if one becomes exercised.
