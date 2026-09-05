# Engineering

Norms, the gate perimeter, Kotlin and backend rules, and the design decisions already settled. Process is in
`agents/workflow.md`.

## Norms

- **Clean architecture.** The domain is pure (no I/O, framework, clock, environment); I/O lives in adapters; the
  dependency graph is a DAG pointing inward.
- **Strict TDD: red, green, refactor.** Write the failing test first, run it and show it fail, write the minimal
  implementation, then refactor with tests green.
- **100% branch coverage, verified after the fact.** Uncovered code is a missing test or code nobody asked for; never
  lower the threshold: add the test or delete the code.

## Gate perimeter

- **Inside (100% branch coverage)**: the modules other than `api-application`,
  `detekt-rules` included. Kover is applied per module with no aggregation; the bound is verified **per package**
  (`groupBy = PACKAGE`), so a module averaging 100% still fails when one package does not reach it.
- **Outside (not measured)**:
    - `api-application`: composition root, end-to-end tests only, Kover not applied.
    - `...persistence.sqlite.models` and `models.bases`: Ebean's bytecode enhancement rewrites entity classes in place
      and its injected bookkeeping is mis-attributed to source lines.
    - `...models.query.Q*` and every class annotated `io.ebean.typequery.Generated`

The perimeter is transcribed from `build.gradle.kts`, where it is enforced. Change it there first. **Inside never
shrinks, and widening Outside requires the user's explicit agreement.**

## Kotlin

### Null safety and modelling

- **`!!` is forbidden**: model non-nullable, or handle the null.
- **`lateinit` only for framework-injected fields.**
- **Closed unions are `sealed`**, `when` over them exhaustive without an `else`.
- **Value objects are `data class` or `@JvmInline value class`**, never a bare `String` or `Int`.
- **Verify every inline value class against the libraries that reflect over it**: inlining is erased at runtime and
  tooling can mishandle an IVC silently (observed with Ebean's migration generation). Generate the artefact, read it,
  pin the result in a test; where a library cannot handle it, keep the IVC in the domain and convert at the adapter.
- **Immutability by default**: `val` over `var`, read-only collection types in signatures,
  `copy()` over mutation.
- **Nullability at the boundary is resolved at the boundary**: a nullable wire field is converted to a validated domain
  type in the adapter, never carried inward.

### Errors and coroutines

- **Exceptions cross layers only as domain types**: an adapter translates its framework exception; a persistence
  exception never reaches a controller.
- **Never catch `Exception` broadly to keep going**: absorb external-I/O failures deliberately and log the cause; let
  in-process, fully-tested code fail loudly.
- **Structured concurrency**: no `GlobalScope`; never swallow `CancellationException`.
- **Dispatchers are injected, not hard-coded.**

### Tests and coverage

- **JUnit** at the version the catalog pins. **Kover** for coverage, branch counting enabled, verification bound in the
  build.
- **Prefer fakes over mocks for ports you own**; with MockK, assert on outcomes, not on the interactions you just
  configured.
- **Static analysis is part of the gate**; a rule is suppressed inline only with a reason.

### Structural invariants are tests, not prose (Konsist)

Every structural rule the project relies on gets a Konsist test: the Design invariants below, structural ADRs, and every
pitfall learned the hard way.

- **Express the rule as what must not exist**: filter down to the violations and finish on
  `assertEmpty()`, so the failure enumerates the offenders and the test reads as the prohibition it is.
- **Use the chaining DSL** (`withX`/`withoutX`) rather than one monolithic predicate inside
  `assertTrue { }`.
- **Layering is asserted with the architecture DSL** (`assertArchitecture` with `Layer`
  declarations), not by hand.

## Backend

### The API is a contract, and contracts are uniform

The failure mode is never one endpoint being wrong: it is one endpoint being **different**.

- **One error format, declared once, applied everywhere**, including framework-generated responses (unauthenticated,
  unhandled media types, malformed payloads, method not allowed, the fallback handler).
- **A partial failure is a specified behaviour**: for any batch operation, the spec states what the client receives and
  what state persists when step N of M fails.
- **Anything a client depends on is versioned or additive**: removing a field, narrowing a type, making an optional
  parameter required, or changing a default are breaking changes.

### Boundaries

- **Validate at the edge, then trust inward**: the domain never receives a raw body, query string, or a nullable it has
  to re-check.
- **The wire format is not the domain model**: DTOs are separate types.
- **Identifiers, casing and normalisation are decided once**, in the spec, applied at one place, tested.

### Configuration and secrets

- **All configuration is read in one place** and exposed as a typed object.
- **Configuration keys keep their namespace**: never moved to another prefix to dodge a framework check.
- **Before adding an option, ask whether the deployment model makes it meaningless.**
- **Secrets never reach a log, error payload, trace or test fixture**; redaction at the sink.

### Network binding

- **Bind addresses are deliberate and stated** in the spec, and verified with a real check, not assumed from the code.

### Persistence

- **Migrations are append-only**: an applied migration is never edited; a correction is a new migration.
- **Transactions have an explicit boundary, owned by the use case.**
- **Queries that grow with the data are measured, not assumed**: produce the timing before and after optimising.

### Operations

- **Every response the client can act on is testable end to end**: contract tests exercise the real wire format.
- **Idempotency is a property**: retried writes either converge or are documented as unsafe to retry.
- **Health and readiness are distinct**; a dependency check that always returns true is worse than none.

### This project's API contract

- **Error format**: RFC 7807 Problem Details as `application/problem+json`
  (`dtos/output/ProblemDetail.kt`: `type`, `title`, `status`, `detail`, `instance`, plus a `code`
  extension). Every payload built through `mappers/ProblemResponses.kt`.
- **Status codes** come from one table, `BaseErrorMapper.statusFor`, a `when` over `ErrorCode`
  with no `else`. Convention: 400 malformed request, 422 well-formed but refused on its merits, 401 unauthenticated, 403
  forbidden, 409 state conflict, 404 absent, 410 expired, 413 oversize upload, 429 rate limit.
- **Authentication**: opaque session tokens as `Authorization: Bearer <token>`, issued by
  `POST /api/v1/sessions`, validated by `SessionTokenAuthenticator`. Not JWTs: the OpenAPI security scheme is declared
  by hand in `openapi/OpenApiApplication.kt` (the Quarkus shortcut would stamp `bearerFormat: JWT`).

## Design invariants (settled decisions)

- **Alpha status**: breaking changes and data loss are acceptable. When only an already-applied database stands in the
  way of the clean fix, take the clean fix and record the consequence.
- **`api-domain` is pure**; `ArchitectureKonsistTest` enforces the layering and is the authority over any table in a
  document.
- **Never poke holes through layers**: presentation never calls persistence; use cases never depend on persistence
  implementations.
- **Domain data is stamped by use cases, never invented by adapters**: instants, ids, state transitions come from ports;
  the adapter stores what it is given.
- **All code is English**; documents predating the decision keep their language.
- **The migration history is append-only until beta**, then flattened into a generated baseline. Accepted cost
  meanwhile: legacy `when_created`/`when_modified` column names.
- **A query rooted on a recyclable model is built by its `Queries` object**: models implementing
  `SoftDeletableModel` are queried through `active()`, `recycled()` or `any()`; queries rooted elsewhere filter through
  extensions (`withActiveBoard()` etc.). Held by two Konsist assertions and the `SoftDeleteStateFilteredOutsideQueries`
  detekt rule; the `io.ebean.Database` instance is confined behind `Persistor`/`TransactionControl`
  (`docs/adr/0008-structural-soft-delete-read-isolation.md`).
- **Dependencies are injected by type, not by string qualifier**: a new dependency is a dedicated type
  (`PeriodicScheduler`); no `@Identifier("...")` for new code.
- **One connection; a transaction is what serializes a pair of statements.** SQLite is single-writer: `minConnections`/
  `maxConnections` pinned to 1, WAL, `synchronous=NORMAL`,
  `busy_timeout=5000`, no `transaction_mode=IMMEDIATE` (once reintroduced a deadlock). The single connection serialises
  each statement, **not** a pair: a check-then-insert inside one transaction is safe; the same pair as two autocommit
  statements is racy (measured: ~340/400 interleavings without the transaction, zero with it). `EbeanTaskQueue.enqueue`
  holds its transaction; a new pair that does not is a defect.
- **The database is the authority on uniqueness**: no read-before-write exists solely to answer a uniqueness question an
  index already answers; the adapter translates the violation into a domain exception. One written exception:
  `UserDataExportRequester.kt:58`, which orders two refusals (409 ahead of 429).
- **A unique constraint is not complete until its outcome is named**: every one appears in
  `UniqueConstraintOutcomeTest`'s table with the answer a client gets, "no translation, deliberately" included.

## Test conventions

- **A structural assertion arrives with the mutation that makes it fail**, pasted in its commit message: an
  `assertEmpty()` chain passes just as well when the filter matches nothing.
- **A case joins an existing integration suite**; a new `@QuarkusTest` class costs a full boot and is justified only by
  a scenario no existing suite can host.
- **Test names**: backticks, `Given..., Then...` form, no "when" in the name. Bodies follow Given-When-Then with
  explicit comments.
- **Maintainability**: helper methods for repeated setup, named variables over inline literals,
  `createRandomString()` for unique data, extend the fitting base class (`IntegrationTest`,
  `RepositoryTest`, `BaseTest`).

## Code conventions

- **Module conventions**: entities in `api-domain/entities/` have matching interfaces in
  `repositories/`; persistence converts through `mappers/`; use cases throw domain-specific exceptions; controllers use
  the DTOs in `dtos/`.
- **No top-level functions**: a helper belongs to a class, companion or object; extension functions are the only
  exception (`queries/PinBoardQueries.kt`).
- **Structural remedies have three homes**: `ArchitectureKonsistTest` for a project-wide declaration invariant, a detekt
  rule for a prohibition inside one file's statements, a plain test (`DbMigrationModelCoverageTest`) for repository
  content.