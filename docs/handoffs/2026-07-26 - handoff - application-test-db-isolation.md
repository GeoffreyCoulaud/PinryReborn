# Handoff: api-application test DB isolation (honour datasource.db.url)

Branch: `fix/application-test-db-isolation` (cut from `main` on 2026-07-26).
Tier: Direct (a test-infrastructure fix; no design decision, no public-surface change).

## Current state

The `api-application` `@QuarkusTest` suite no longer shares an on-disk `data.db` at the repository
root. `EbeanDatabaseProducer` now reads `datasource.db.url` via `@ConfigProperty` (injected as a
producer-method parameter, matching `ExportProducers`) instead of `System.getenv("DB_PATH")`, so
the test profile's `datasource.db.url=jdbc:sqlite::memory:` is honoured. Each test JVM run gets a
fresh in-memory database (single-connection, option A), with no file left behind and no cross-run
leakage. Production is unchanged: the `Dockerfile` sets `DB_PATH=/data/pinry.db`, which
`application.properties` wires into `datasource.db.url` via `${DB_PATH:data.db}` plus the
WAL / synchronous=NORMAL / busy_timeout pragmas.

## What was built

- `EbeanDatabaseProducer` takes the JDBC URL from `datasource.db.url` and builds the
  single-connection `DataSourceConfig` from it. The URL (path, pragmas, `:memory:`) is owned by
  configuration; the helper only pins the SQLite driver, the default credentials and the
  `minConnections=maxConnections=1` option-A constraint.
- `api-application/src/main/resources/application.properties` declares
  `datasource.db.url=jdbc:sqlite:${DB_PATH:data.db}?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000`,
  mirroring the value `api-persistence-sqlite/src/main/resources/ebean.properties` already held for
  the avaje-config `DB.getDefault()` path. Two config systems, one value.
- `api-persistence-sqlite` gains `compileOnly(libs.microprofile.config.api)` (catalog alias
  `microprofile-config = "3.1.1"`, the version Quarkus resolves), mirroring the existing
  `compileOnly(libs.jakarta.cdi.api)` line. Only `@ConfigProperty` is consumed.
- Living docs moved with the code: the resolved gotcha was removed from `agents/project.md`, the
  `Dockerfile` comment was corrected (DB_PATH now feeds `datasource.db.url`), and the
  `ebean.properties` comment was re-pointed at `application.properties` (it had referenced the
  deleted `sqliteJdbcUrl` helper).
- Backlog: the DB-isolation item is closed, and the `animated` backfill migration item is dropped
  (operator decision: alpha, nobody deployed, nobody in the broken state).

## Pitfalls / friction

- **`api-application` tests do not truncate between tests**, unlike `api-persistence-sqlite`'s
  `RepositoryTest`. They rely on unique data (`createRandomString`) to avoid collisions within one
  run. The in-memory DB is shared across `@QuarkusTest` classes within a run (same as the old
  `data.db`), only now it is per-run instead of persistent. No behavioural change for the suite.
- **`datasource.db.url` is mirrored in two config files** (`application.properties` for the Quarkus
  path, `ebean.properties` for the avaje-config `DB.getDefault()` path used by
  `RepositoryTest`-derived unit tests). The two config systems cannot share a source. The pragma
  values are therefore not unit-pinned: the old `sqliteJdbcUrl` helper appended them in code and was
  testable, but it was removed in favour of URL-as-config, so a future edit that drops the pragmas
  from one file drifts silently. Flagged by the holistic review as MINOR; left as a trade-off of
  the "URL is the single knob" design. If it matters later, pin them with a content test.
- **`compileOnly` should be the API JAR, not the impl.** The first attempt used `smallrye-config`
  (the impl); the holistic review pointed out the project convention is API JARs
  (`jakarta.cdi-api`), so it is now `microprofile-config-api`.
- **The Quarkus test worker prints a stack trace on shutdown** (`task worker pool did not drain
  within the shutdown timeout`). It is a benign teardown warning, not a failure; `BUILD SUCCESSFUL`
  is the signal.

## Not validated against real conditions

- The full gate is green on this host: `./gradlew check koverVerify`, and no `data.db` is created
  at the repo root after the `api-application` suite (checked before and after).
- Production start is not exercised here (no container build). The URL is byte-identical to what
  the old `sqliteJdbcUrl` produced for `DB_PATH=/data/pinry.db`, and the `Dockerfile` still sets
  that env, so behaviour is preserved by construction. The multi-arch container image build itself
  is not covered by any local command (see `agents/project.md`).

## Suggested next step

- Integrate: push and open a PR, merge with `gh pr merge --rebase` (squash is disabled on this
  repo).
- Then the priority work is the **GC trio** (session-token GC, rendition-cache GC, deleted-account
  residue GC), periodic at roughly once per day, reusing the worker's existing retention
  scheduling if it is already periodic. A grounding investigation was run in parallel; its report
  drives the spec.
