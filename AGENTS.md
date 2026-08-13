# AGENTS.md

The instructions this repository runs on: what the project is, where its code lives, the
engineering baseline it holds itself to, the workflow, and the rules situated to Pinry Reborn's API
server. The repository owns this file and edits it here.

**A rule or a review mandate changes only in a lot whose subject it is**, never in passing to make
the work at hand easier. _Observable: the change is the lot's declared subject, or it arrives in its
own `docs(agents):` commit._ The opposite case is not this one and is required by Simultaneity
below: a rule this repository discovers while writing the code that establishes it ships in that
code's commit. What this forbids is loosening a criterion that judges the work in flight, not
recording what the work just proved.

Every rule is written so breaking it is **visible in the output**: when a rule names an observable,
produce it; a rule you cannot satisfy is reported, never silently skipped. Rules govern work from
adoption onward: pre-existing violations are inventoried once at adoption and reported, never swept
or fixed as a side effect, and a frozen dated document (see Documentation) is never rewritten to
satisfy a later rule.

The three review mandates stay outside this file, under `agents/reviews/`, and are read by the
reviewing subagent alone. Why they stay out is in Review mandates below.

## The project

Pinry Reborn's API server: the whole business logic of a self-hosted pin board (users, pins,
boards, tags, images and their renditions, exports), exposed as an HTTP API for the web UI and a
browser extension to call. It is a Kotlin and Quarkus service following Clean Architecture, with a
SQLite store, filesystem-backed image storage, and a task worker for the long operations
(downloading a remote image, deleting an account, building an export archive).

### Orientation

- `docs/handoffs` : one continuation guide per milestone. **The newest is the entry point**:
  current state, what was just built, pitfalls learned, next step, what is not yet validated.
- `docs/specs` : the authoritative design per subsystem, dated.
- `docs/adr` : the architectural decisions, with their context and consequences.
- `docs/plans` : the execution plans derived from specs.
- `docs/backlog.md` : what is queued, out of session.

### Where the code lives

Twelve Gradle modules, declared in `settings.gradle.kts`. The layering is enforced by the build
graph and by `ArchitectureKonsistTest`, not by this table.

| Subsystem | Location | Role |
|---|---|---|
| Domain | `api-domain` | Pure: entities, enums, and the ports (`images`, `storage`, `tasks`, `time`, `security`, `exports`, `repositories`). Declares no project dependency. |
| Use cases | `api-usecases` | Business logic: use cases and their exceptions, search, exports, task contracts. Depends on the domain and on `api-utilities`. |
| Persistence | `api-persistence-sqlite` | Ebean and SQLite adapter: models (and `models/bases`), mappers, repositories, query constructors (`queries`), cursor pagination, and the migration history in `src/main/resources/dbmigration/`. |
| Presentation | `api-presentation-quarkus` | Jakarta REST adapter: controllers, DTOs (`input`, `output`, `common`), mappers, security, serialization, OpenAPI, HTTP config. |
| File storage | `api-storage-filesystem` | Image store, rendition cache, ZIP export archive store, data directory layout. |
| Imaging | `api-imaging-vips` | libvips adapter through vips-ffm: probing and transforming images. |
| Fetching | `api-fetch-http` | Fetches remote images over HTTP, behind an address policy. |
| System | `api-system` | System adapters: `SystemClock`, bcrypt password hashing, secure token generation. |
| Worker | `api-worker-quarkus` | Task worker runtime: dispatcher, bounded executor, task handlers (pin download, account deletion), export retention lifecycle. |
| Utilities | `api-utilities` | Shared helpers (`createRandomString`) and the `BaseTest` fixture, published as a `testFixtures` source set. |
| Application | `api-application` | Composition root: entry point, CDI wiring, and the end-to-end integration tests. Depends on every module. |
| Static analysis | `detekt-rules` | The project's own detekt rules and their `RuleSetProvider`, consumed by every other module through `detektPlugins`. Depends on no project module and none depends on it, so it sits outside the layering. |

## Commands

- **Runner**: `./gradlew` (the committed wrapper; the JDK 25 Adoptium toolchain is provisioned by
  the foojay resolver, so no JDK has to be installed by hand).
- **Install**: nothing for the JVM side, three things once per clone. `git config core.hooksPath
  .githooks` enables the hooks, native libvips must be present (`brew install vips` on macOS, which
  the build wires vips-ffm to automatically; `libvips42t64` on Ubuntu 24.04, which is what CI
  installs) or the `api-imaging-vips` tests and the `api-application` image-touching integration
  tests cannot load the library, and `python3`
  must be on the PATH because `.claude/settings.json` runs `.claude/hooks/evidence-guard.py` on
  every Bash, Edit and Write. What it inspects is narrower than what fires it: since the
  generic-file rule was dropped it returns at once on the three edit tools, so what it still
  enforces is over Bash commands. Without `python3` it cannot run and enforces nothing, silently.
- **THE GATE**: `./gradlew gate` (detekt with type resolution, all tests, the 100% branch coverage
  bound, `checkNoLongDashes` over every tracked file, and `checkEvidenceGuard`, which runs the hook's
  own tests because Kover cannot see a Python file under `.claude/`). The `gate` task in the root `build.gradle.kts`
  aggregates `check` and `koverVerify` across every module, so it is the single knob **locally**: grow
  the gate by adding `dependsOn` there. **CI is the second place**, and it is not a caller of `gate`:
  `validate.yml` enumerates the gate's parts, so a check added to `gate` alone runs on no pull
  request and the protected `validate / gate` never sees it. Measured green on 2026-07-23. **It
  is not everything CI runs either**: `validate.yml` also builds the multi-arch container image and checks
  that `docs/openapi.json` is in sync, both behind the same `validate / gate` check, and no local
  command covers either. `.githooks/pre-push` runs `./gradlew gate`, so a push runs the gate locally
  once `core.hooksPath` is set.
- **One test**: `./gradlew :api-usecases:test --tests "UserCreatorTest"`. The coverage bound lives
  in its own task, so running `test` alone never trips it: there is nothing to bypass.
- **New migration**: `./gradlew :api-persistence-sqlite:generateDbMigration`, after changing an
  entity model. It writes the next `dbmigration/<version>.sql` and its `model/<version>.model.xml`.
  An applied migration is never edited, only followed by a new one (see Gotchas).
- **A drop takes a second pair, asked for separately.** Ebean never puts a destructive change in the
  apply output: a dropped column or table goes into a `pendingDrops` change set in the model file the
  run above produces, and pending drops are selected explicitly
  (ebean.io/docs/setup/dbmigration, "Pending Drops"). The drop is generated by re-running the same
  task with `ddl.migration.pendingDropsFor` naming the version that recorded it, which writes
  `<next>__dropsFor_<version>.sql` and its model. **The property has to reach the generator's own
  JVM**: the task is a `JavaExec`, so `-D` on the Gradle command line sets it on the daemon and the
  run reports "no changes detected" whatever version is named. Passing it through the environment
  works:
  `JAVA_TOOL_OPTIONS="-Dddl.migration.pendingDropsFor=<version>" ./gradlew :api-persistence-sqlite:generateDbMigration`.
  Both pairs are committed together, so the history stays fully modelled. Precedent: `1.13` and
  `1.14__dropsFor_1.13`.

## Scope

Change the minimum that satisfies the request.

- **Stay inside the repository.** Never read, list or search `$HOME`, parent directories, or another
  repository; a git worktree is its own root. _Observable: every path touched is under the working tree._
- **A failed lookup is a question, not a wider search.** After `git ls-files` plus one ripgrep, stop
  and ask for the path.
- **Do not fix what was not asked.** Adjacent defects are named in the final message or proposed for
  the backlog. _Observable: no diff hunk is unexplainable by the request._
- **The boy-scout rule.** An adjacent defect that is **trivial, obviously correct, and contained**
  (a stale comment, a typo, a one-line fix on a hunk already in scope) is fixed in the change that
  finds it, not backlogged. All three must hold: trivial (no design decision, no new test surface),
  obviously correct (a reader sees it is right at a glance), and contained (one site, on a hunk
  already touched). Anything beyond is still named or backlogged. A boy-scout fix is flagged in the
  final message ("also fixed a stale comment at X").
- **Do not create unrequested files**, including `CLAUDE.md`/`AGENTS.md` additions.
- **Do not refactor opportunistically.** Renames and style sweeps are their own task, proposed separately.

## Evidence

Nothing is asserted without the command that established it, nothing changed without the diff.

- **Claims carry their proof.** Any statement about system state shows the command and its output, or
  the prefix `UNVERIFIED:`, allowed only when no available command can settle the claim (name that
  command and why it cannot run). Git state, test outcomes, file existence and tool availability are
  never `UNVERIFIED:`.
- **Proof outlives the session in the commit message.** A fact established in conversation and needed
  later goes in the message of the commit it justifies; comments say why the code is shaped as it is,
  never assert unproven facts.
- **A check that cannot fail is not a check.** Before offering a command as evidence, name the output
  that would have proved you wrong.
- **File content is written with the edit tool, never by a command** (redirection, heredoc, `tee`,
  `sed -i`, applied patch). Exceptions: throwaway output (`/dev/null`, `$TMPDIR`) and commands whose
  declared product is the file (formatter, scaffolder, generator, compiler). _Observable: no diff hunk
  was produced by a command in the trace._
- **A tool is not unavailable until the declared runner has failed**, failing invocation shown.
- **A session constraint that collides with this file is a question, not a decision.** When the
  harness, a permission, or an instruction of the day appears to forbid what these rules require, say
  which two collide and ask which wins. Never announce a rule as unsatisfiable and carry on.
  _Observable: the collision and the user's answer appear before the work that depends on it._
- **Refuted beats plausible.** Drop a hypothesis the user's evidence contradicts; do not restate it weaker.
- **Never claim done without the gate output in the same message.**
- **Consult the declared documentation source, not recall**, for any library, CLI or cloud service and
  any version-dependent value (flags, ports, roles, defaults, formats); the source is named under
  Conventions. _Observable: the source consulted is named._ "The tool cannot do that" is a claim like
  any other: consult before designing around a limitation.

## Design

How to decide. The decisions already taken for this project are under Design invariants.

- **Prefer the convention the tool already has.** _Observable: any new abstraction is justified in one
  line naming the convention found insufficient._
- **Fix the design, do not work around it.** Three smells that the design is the defect: the same
  explanatory comment repeated at several sites; a domain type widened to nullable to spare existing
  callers; a workaround for a tool limitation nobody verified.
- **Name the root cause, not only the symptom.** When an adjacent defect is a symptom of a design
  smell (repeated explanatory comment, type widened to nullable to spare callers, workaround for
  a limitation nobody verified) name the root cause and propose the refactor that removes it, as
  a backlog item or a separate task. "Do not fix what was not asked" forbids doing it inline; it does
  not forbid naming the root. _Observable: any backlog entry born from a structural symptom names the
  design smell and the refactor that removes it._
- **Refactor as a first-class solution.** During Discuss and Design, consider refactor when you see
  several related fixes or a workaround. Propose it to the human; the human arbitrates, the ADR
  records the decision. Never refactor inline without being asked.
- **Never move or rename something to escape a constraint**: satisfy it or report a blocker.
- **A guard is loosened only against the threat it would let through**, named and measured. Symmetry
  with a neighbouring rule is not an argument: the sibling may be judging something this guard cannot
  see. _Observable: the case the loosened guard now allows, run and pasted, before the change lands._
- **A setting that should not exist is not fixed by a good default**: say it should not be there.

## Workflow

Seven phases in order: Discuss, Spec, Plan, Act, Verify, Wrap, Improve. **Committing is cheap: commit
autonomously. Branch before the first file is written, in any phase.** The integration branch,
declared under Conventions, receives work only through integration. Ask which option and wait:
(1) stay on the current branch (never offered on the integration branch), (2) `git switch -c <branch>`,
(3) new worktree, (4) other. Naming: `<type>/<kebab-slug>` with a conventional-commit type.

### Tier selection

**The tier is the user's decision.** State the recommended tier and its trigger, then wait; recommend
the higher when two fit, and if a higher trigger surfaces mid-task, stop and ask again. _Observable:
tier, trigger, and the user's answer appear before the first edit._

| Tier | Trigger | Phases run |
| --- | --- | --- |
| **Direct** | No design decision, no new dependency, no public-surface change, readable in one pass | Act, Verify, Wrap, Improve |
| **Spec** | Several modules, or a design decision, dependency, format or public surface | Discuss, Spec, Act, Verify, Wrap, Improve |
| **Plan** | More than three tasks, subagent dispatch, or a migration | All seven |

Mandatory escalation to at least Spec, regardless of size: security or auth, data migration, public
contract change (status codes, error shapes, config keys, CLI flags, file formats), anything
irreversible. The Direct trigger is the absence of a decision, not a file count. **Wrap and Improve
run in every tier** and scale down on their own.

### Phases

1. **Discuss.** Open `docs/backlog.md` (records what is already arbitrated; receives what this phase
   surfaces out of scope), then plain conversation: no code, no plan, no files. Use a
   structured-elicitation affordance if this file declares one.
2. **Spec.** Goal, acceptance criteria, explicit out-of-scope. Simple work inline; structured work in
   `docs/specs/<ISO date>-<slug>.md`. **Approved by the user before any plan or code**: the human gate
   at entry (the PR review in Wrap is the gate at exit); the human's word is never assumed. **Record
   an ADR** in `docs/adr/<NNNN>-<slug>.md` unless the work demonstrably settles no architectural
   question. _Observable: the ADR path, or the one-line justification for its absence._ A delivered
   ADR is never rewritten; only its `Status` field may change (`Superseded by <NNNN>`).
3. **Plan.** Ordered, independently checkable tasks in `docs/plans/<ISO date>-<slug>.md`, each with
   acceptance criteria, files and tests. **Reviewed by a fresh subagent before any dispatch**: plan
   defects are the most expensive to discover late.
4. **Act.** The branch already exists; where a tier skipped earlier phases, ask the branching question
   here. **Subagent-driven by default** to keep the main context clean; inline only for a one-file,
   one-edit change. **Each task is reviewed by a fresh subagent on completion**; the implementer never
   reviews its own task.
5. **Verify.** Run the full project gate as declared under Commands (run, not described), then a
   **holistic review by a fresh subagent** over the whole branch diff, never skipped: it catches what
   per-task reviews cannot see.
6. **Wrap.** Runs to completion before Improve opens: the two phases never interleave. (a) Update the
   backlog in the branch. (b) Write the handoff in `docs/handoffs/<ISO date> - handoff - <context>.md`:
   current state, what was built, pitfalls, what is **not** validated against real conditions,
   suggested next step. (c) Integrate: everything goes through a PR so CI runs, with a linear history
   and never a merge commit (rebase only here, and no local-merge exemption: see Conventions).
   **A PR is merged only after the human has reviewed it**: address each round of feedback and wait
   for the next; approval is never assumed. (d) Tag if the spec called for a release. (e) Clean up the
   branch or worktree. (f) Report what was done and the friction points (wrong turns, corrections,
   review rounds, rules that did not hold), written after integration so the review loop feeds it:
   this report is the input to Improve.
7. **Improve.** Begins only once Wrap has fully completed: work integrated, branch or worktree
   cleaned. _Observable: the integration precedes the first message of the phase._ **Never skipped,
   even when the work went fine.** The question: what should the gate
   have caught? A self-correction counts the same as one the user made. Opens as a discussion: state
   the **failures met** and the **remedy proposed** for each, then wait. _Observable: both lists and
   the user's answer appear before the first edit of the phase._ Each retained remedy takes the
   cheapest durable form: **this file** for a judgement call, **a test** for a structural
   invariant, **a lint rule** for a local pattern, **a backlog item** when the fix is real work.
   **Retaining nothing is a normal outcome**: record nothing the gate already enforces.

### Review mandates

Every review is performed by a **fresh subagent** receiving the artefact and the criterion, never the
reasoning that produced them. Reviewers report findings and never edit.

| Review | When | Mandate |
| --- | --- | --- |
| Plan | Before any task is dispatched | `agents/reviews/plan.md` |
| Task | When each task completes | `agents/reviews/task.md` |
| Holistic | In Verify, after the gate is green | `agents/reviews/holistic.md` |

**Do not read these files**: pass the path and let the subagent read its own mandate; an implementer
who knows the criteria writes to them. _Observable: no read of `agents/reviews/` in the trace of a
session that produced work._ Only exception: work whose subject is a mandate itself. This is why the
mandates are three files of their own rather than a section here: a section would be loaded into
every session.

**The brief carries the artefact, not the answers.** It names what is under review and its commits,
points at each criterion by path **and line range** rather than by document, and adds at most three
zones of risk, each an open question ("what bounds memory here?"): the mandate is already the list,
and a second list doubles what the reviewer walks. "Confirm X is not premature" has performed the
review itself; a whole plan named has ten tasks read to review one. _Observable: no instruction
begins with "confirm", "verify" or "check that", and every document named carries a line range._

**The brief says how the report comes back.** A subagent working in the background signals
availability without carrying anything, so the brief asks for `SendMessage` to `main` with the
findings in the message body. _Observable: the report arrives without being asked for a second time._

## Engineering norms

- **Clean architecture.** The domain is pure (no I/O, framework, clock, environment); I/O lives in
  adapters; the dependency graph is a DAG pointing inward.
- **Strict TDD: red, green, refactor.** Write the failing test first, **run it and show it fail**,
  write the minimal implementation, then refactor with tests green (part of the cycle, not an afterthought).
- **The failing test is committed alone, before its implementation**, as `test(scope): <behaviour>`,
  **its message body carrying the red**: the command run and the failure it produced, pasted from
  that run and never retyped. The red comes from the run that produced the committed file, never
  from an intermediate state: `git show` on the test commit reproduces it. _Observable:
  `git log -1 --format=%b` on the test commit shows the command and its failure._
- **Review judges the tests before the code.** A test that passes against a wrong implementation is a
  defect of the same rank as the bug it missed.
- **100% branch coverage, verified after the fact**: the audit of the TDD cycle. Uncovered code is a
  missing test or code nobody asked for; never lower the threshold: add the test or delete the code.
- **The gate perimeter is decided by location**, declared once below: inside is 100%, outside is not
  measured, no per-category exemption ("just tooling").
- **Generated artefacts are declared, not assumed.** Code no human wrote and no test can reach is not
  source, so it was never inside; the perimeter below names each generator and its exclusion, and a
  generator absent from the list is inside. Hand-written code calling the artefact stays inside.
- **TDD exemptions apply to the order only, never the safety net**: behaviour-preserving refactors
  (existing tests are the net), throwaway spikes (never merged as-is), configuration, documentation.
  Everything inside the perimeter still ends up covered.

### Gate perimeter

- **Inside (100% branch coverage)**: the eleven modules other than `api-application`, `detekt-rules`
  among them. Kover is applied per module and measures each module from its own tests, with no
  aggregation, so `api-application`'s end-to-end tests never inflate another module's figure. **The
  bound is verified per package**, not per module (`groupBy = PACKAGE` in `build.gradle.kts`): a
  module averaging 100% still fails the gate when one of its packages does not reach it. A module
  whose product is static analysis rather than runtime behaviour is measured like the rest: there is
  no "just tooling" exemption, and a detekt rule is cheap to cover, since `detekt-test`'s `lint()`
  turns a code snippet into the findings it produces.
- **Outside (not measured)**:
  - `api-application`, because it is the composition root and its tests are end to end. It has no
    unit tests by design, so Kover is not applied to it at all.
  - `fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models` and its `models.bases`
    subpackage, because Ebean's bytecode enhancement rewrites entity classes in place and its
    injected bookkeeping (`_ebean_intercept`, `_ebean_get_id`, the static initialiser building
    `_ebean_props`) carries no marker at class or method level and is frequently mis-attributed to
    the wrong source line. Operator decision B1, coverage calibration.
  - `...persistence.sqlite.models.query.Q*` and every class annotated
    `io.ebean.typequery.Generated`: kapt output. This is a **generated artefact declared out of the
    perimeter**, which the norms above require rather than forbid: code no human wrote and no test
    can reach was never inside. The generator is Ebean's typequery kapt processor, named by the
    `io.ebean.typequery.Generated` annotation and the `Q*` query package. See
    `docs/adr/0002-generated-artefacts-in-gate-perimeter.md`.

The models-package exclusion above (decision B1) is the one place where this file holds itself to
less than its own norms ask: they want 100% inside the perimeter with no per-category exemption, and
those entity classes are hand-written, so excluding them is the operator's call rather than a
generated-artefact declaration (`docs/adr/0001-adopt-agents-baseline.md`). It is counted here rather
than left to accumulate quietly.

The perimeter is transcribed from `build.gradle.kts`, which is where it is enforced. Change it
there first.

**Inside never shrinks, and widening Outside requires the user's explicit agreement.** This
section is the one place where editing a document lowers the bar for the code, so it is the one
place where an agent must not decide alone.

## Kotlin

### Build

- **`./gradlew` is the runner**, never a globally installed Gradle. The wrapper is committed and
  its version is part of the project.
- **Kotlin DSL** (`build.gradle.kts`) with a version catalog (`gradle/libs.versions.toml`) as the
  single source of dependency versions. No hard-coded versions in module build files.
- **Module boundaries are enforced by the build graph, not by convention.** If a module must not
  see another, it must not declare it as a dependency. A layering rule that only exists in prose
  is already broken.

### Null safety and modelling

- **`!!` is forbidden.** If a value cannot be null, model it as non-nullable. If it can, handle it.
  `!!` converts a type error into a runtime crash and hides the design question.
- **`lateinit` only for framework-injected fields**, never as a shortcut around initialisation
  order.
- **Closed unions are `sealed`**, and `when` over them is exhaustive without an `else`. An `else`
  arm on a sealed hierarchy silently swallows the case you add next year.
- **Value objects are `data class` or `@JvmInline value class`**, never a bare `String` or `Int`.
  An identifier typed as `String` will eventually be passed in the wrong position, and the compiler
  will not object.
- **Verify every inline value class against the libraries that reflect over it.** Inlining is
  erased at runtime, so code generation and reflection-based tooling can mishandle an IVC
  **silently**, with no error and no warning. Observed with Ebean, whose migration generation
  produces a wrong column for an IVC-typed property. Before adopting an IVC on a type any library
  inspects, generate the artefact (migration, schema, serialised payload), read it, and pin the
  result in a test. Where a library cannot handle it, keep the IVC in the domain and convert at the
  adapter rather than giving up the type.
- **Immutability by default**: `val` over `var`, read-only collection types in signatures, `copy()`
  over mutation.
- **Nullability at the boundary is resolved at the boundary.** A DTO field that is nullable in the
  wire format is converted to a validated domain type in the adapter, never carried inward as a
  nullable.

### Errors and coroutines

- **Exceptions cross layers only as domain types.** An adapter translates its framework or driver
  exception into a domain error; a persistence exception must never reach a controller.
- **Never catch `Exception` broadly to keep going.** Absorb failures from external I/O
  deliberately and log the cause; let in-process, fully-tested code fail loudly.
- **Structured concurrency**: no `GlobalScope`, every coroutine has an owning scope with a defined
  lifetime. Cancellation is cooperative, so never swallow `CancellationException`.
- **Dispatchers are injected, not hard-coded**, so tests can control them.

### Tests and coverage

What the language and its tools give. How this project names, orders and maintains its tests is
under Conventions, Tests.

- **JUnit is the test framework**, at whatever major version the version catalog pins. This section
  states no number: an instruction naming one only ages, and the catalog is where the answer
  already lives. Test names describe the behaviour and the condition, not the method under test.
- **Prefer fakes over mocks** for ports you own. When mocking is genuinely needed (MockK), assert
  on outcomes rather than on the interactions you just configured: a test that verifies its own
  stubbing passes against a broken implementation.
- **Coverage is measured with Kover**, branch counting enabled, verification bound in the build so
  it fails the gate rather than printing a report nobody reads.
- **Static analysis is part of the gate**, not an optional task. If detekt or ktlint is configured,
  the gate runs it; a rule is suppressed inline only with a reason on the suppression.

### Structural invariants are tests, not prose (Konsist)

An invariant written in a document is a wish. The same invariant expressed as a Konsist test is
enforced on every run, and it names its violations. **Every structural rule this project relies on
gets a Konsist test**: the invariants declared under Design invariants, the structural decisions
recorded in `docs/adr/`, and every pitfall learned the hard way. A rule that was worth writing down
was worth failing the build over.

**Express the rule as what must not exist, then assert the list is empty.** Filter down to the
violations and finish on `assertEmpty()`, rather than filtering to the candidates and asserting a
predicate over them with `assertTrue { }`. Two reasons: the failure message enumerates exactly the
offending declarations instead of reporting that a predicate was false somewhere, and the test
reads as the prohibition it actually is.

```kotlin
// Preferred: name the violation, assert there is none.
Konsist.scopeFromProduction()
    .classes()
    .withPackage("..domain..")
    .withAllAnnotationsOf(Entity::class)
    .assertEmpty()
```

**Use the chaining DSL rather than one monolithic predicate.** `withX` and `withoutX` filters
compose, and each link narrows the set in a way that stays readable and reusable. A long boolean
expression inside `assertTrue { }` hides which condition failed; a chain of filters does not.

**Layering is asserted as layering**, with the dedicated architecture DSL rather than by hand:

```kotlin
Konsist.scopeFromProduction().assertArchitecture {
    val domain = Layer("Domain", "com.example.domain..")
    val usecases = Layer("UseCases", "com.example.usecases..")
    val adapters = Layer("Adapters", "com.example.adapters..")

    domain.dependsOnNothing()
    usecases.dependsOn(domain)
    adapters.dependsOn(domain, usecases)
}
```

Konsist tests cover the whole project by scope, so they are written once and keep holding as the
codebase grows. That is precisely what makes them worth more than a review comment: a reviewer
catches the violation that exists today, the test catches the one written next month.

## Backend

### The API is a contract, and contracts are uniform

The failure mode here is never one endpoint being wrong: it is one endpoint being **different**.
Nineteen endpoints returning a consistent error shape and one returning the framework default is a
broken contract, and it is invisible in a per-endpoint review.

- **One error format, declared once, applied everywhere**, including the responses the framework
  generates for you: unauthenticated requests, unhandled media types, malformed payloads, method
  not allowed, and the fallback error handler. These are the ones that silently keep their default
  shape.
- **Status codes follow their meaning, not convenience.** A successful delete is 204 and not 404
  because the resource is now gone; a failed authentication is 401 and an authorised-but-forbidden
  request is 403; a validation failure is 422 or 400 per the project's declared convention, never
  a 500. Whenever a status code is chosen, the choice is stated in the spec.
- **A partial failure is a specified behaviour, not an implementation detail.** For any batch or
  multi-step operation, the spec states what the client receives and what state persists when step
  N of M fails. "Returns 207" is not a specification until each element's outcome is defined.
- **Anything a client depends on is versioned or additive.** Removing a field, narrowing a type,
  making an optional parameter required, or changing a default are breaking changes even when no
  test fails.

### Boundaries

- **Validate at the edge, then trust inward.** Incoming payloads are parsed into validated types at
  the adapter. The domain never receives a raw request body, a raw query string, or a nullable
  field it has to re-check.
- **The wire format is not the domain model.** DTOs are separate types. Serialising a domain entity
  directly couples the public contract to an internal refactor.
- **Identifiers, casing and normalisation are decided once.** Whether usernames are
  case-insensitive, whether trailing slashes matter, whether IDs are opaque: decided in the spec,
  applied at one place, tested.

### Configuration and secrets

- **All configuration is read in one place** and exposed as a typed object. No component reads the
  environment directly.
- **Configuration keys keep their namespace.** This is the constraint-escape rule under Design in
  its most common backend form: a key is not moved to another prefix to dodge a framework
  strictness check.
- **Before adding an option, ask whether the deployment model makes it meaningless.** An option
  nobody should ever change is a design smell, not a feature.
- **Secrets never reach a log, an error payload, a trace or a test fixture.** Redaction is applied
  at the sink, not at each call site.

### Network binding

- **Bind addresses are deliberate and stated.** A service reachable only from its own loopback is
  unreachable from another container; a service bound to every interface is exposed further than
  intended. Whichever is chosen, the reason is in the spec, and the running configuration is
  verified with a real check (`ss`, `curl` from the intended caller), not assumed from the code.

### Persistence

- **Migrations are append-only.** A migration that has been applied anywhere is never edited; a
  correction is a new migration. Editing an applied migration produces schemas that differ per
  environment with nothing to detect it.
- **Every migration is reversible or explicitly declared irreversible**, and destructive ones say
  what is lost.
- **Transactions have an explicit boundary**, owned by the use case, not scattered across
  repositories. A multi-step write that is not in one transaction is a partial-failure bug waiting
  for load.
- **Queries that grow with the data are measured, not assumed.** Before optimising, produce the
  timing; after optimising, produce it again. Window functions, correlated subqueries and N+1
  access patterns are the usual causes, and only a query plan tells you which.

### Operations

- **Every response the client can act on is testable end to end.** Contract tests exercise the real
  wire format, not the handler function.
- **Idempotency is a property, not a hope.** Retried writes, replayed webhooks and re-delivered
  jobs either converge or are explicitly documented as unsafe to retry.
- **Health and readiness are distinct.** Readiness reflects the dependencies the service actually
  needs to serve traffic, and a dependency check that always returns true is worse than none.

### This project's API contract

The three values the rules above expect a project to declare.

- **Error format**: RFC 7807 Problem Details, served as `application/problem+json`
  (`mappers/MediaTypes.kt`). One shape everywhere, `dtos/output/ProblemDetail.kt`: `type`, `title`,
  `status`, `detail`, `instance`, plus a `code` extension member carrying the applicative error
  code. Every payload is built through `mappers/ProblemResponses.kt`, which is what keeps a single
  endpoint from drifting back to the framework default.
- **Status codes** come from one table, `BaseErrorMapper.statusFor`, a `when` over `ErrorCode` with
  no `else`, so an unmapped new code fails to compile rather than falling through. The convention it
  encodes: 400 for a request that is malformed or ill-formed (`SEARCH_EMPTY_QUERY`,
  `IMAGE_SOURCE_URL_INVALID`, and every Bean Validation failure through
  `ConstraintViolationExceptionMapper`), 422 for a well-formed request the domain refuses on its
  merits (`IMAGE_INVALID`, `PASSWORD_PREVIOUSLY_USED`), 401 unauthenticated, 403 authenticated but
  not allowed, 409 for a state conflict, 404 for an absent resource, 410 for one that expired
  (`EXPORT_GONE`), 413 for an oversize upload (`IMAGE_TOO_LARGE`), 429 for a rate limit
  (`EXPORT_TOO_SOON`).
- **Authentication**: opaque session tokens sent as `Authorization: Bearer <token>`, issued by
  `POST /api/v1/sessions` and validated by `SessionTokenAuthenticator` behind
  `security/BearerTokenIdentityProvider`. They are not JWTs, which is why the OpenAPI security
  scheme is declared by hand in `openapi/OpenApiApplication.kt` instead of through
  `quarkus.smallrye-openapi.security-scheme`, whose shortcut would stamp them `bearerFormat: JWT`.
  HTTP Basic is gone.

## Design invariants

What is already settled here, and why. How to decide the next one is under Design.

- **Alpha status**: breaking changes and data loss are acceptable, and nobody should be running
  Pinry Reborn yet. This is a decision input, not a disclaimer: when the only thing standing in the
  way of the clean fix is that a database somewhere already applied the old one, take the clean fix
  and record the consequence in the backlog.
- **`api-domain` is pure**: no I/O, no config, DB, network, clock or logging imports. All I/O lives
  in the adapters and the dependency graph is a DAG. `ArchitectureKonsistTest` enforces both and is
  the authority on the layering, ahead of any table in a document.
- **Never poke holes through layers**: presentation must not call persistence directly, and use
  cases must not depend on persistence implementations.
- **Domain data is stamped by use cases, never invented by adapters** (decided 2026-07-23):
  creation and update instants, ids and state transitions are business facts, so the use case sets
  them from a port such as `Clock` and the adapter stores what it is given.
- **All code is English** (decided 2026-07-07). Documents written before that decision keep their
  original language: no retro-translation.
- **The migration history is append-only until beta**, when it will be flattened into a single
  generated baseline. One known cost is accepted meanwhile: `users`/`pins`/`boards`/`tags` keep
  `when_created` (and `pins`/`boards` also `when_modified`) column names that no longer match the
  domain's `createdAt` and `updatedAt`. The hand-written `1.2` index was the second and is closed:
  it is declared on `UserModel` and recorded in `1.2.model.xml`, and a `.model.xml` is generator
  state rather than applied DDL, so writing one rewrites no migration
  (`docs/adr/0009-unique-index-named-outcomes.md`, decision 5).
- **A query rooted on a recyclable model is built by its `Queries` object** (decided 2026-07-29): a
  model whose rows are recycled implements the `SoftDeletableModel` marker, and every query rooted
  on it says which state it means through `active()`, `recycled()` or `any()` rather than
  constructing the query bean itself. A query rooted elsewhere that filters on a recyclable
  association uses an extension declared beside those constructors (`withActiveBoard()`,
  `withActivePin()`, `withActiveUser()`), which is the extension-function exception to the no-top-level-functions
  convention below. Two Konsist assertions and the `SoftDeleteStateFilteredOutsideQueries` detekt
  rule hold it: the assertions derive their reach from the marker, and the rule keys on the
  `softDeletedAt` property name, which is the marker's single member, with its reach set by the path
  filters in `config/detekt/detekt.yml`. Either way a newly recyclable model is covered by declaring
  itself. That filtering is backed by the capability confinement of
  `docs/adr/0008-structural-soft-delete-read-isolation.md`: the `io.ebean.Database` instance is
  confined behind `Persistor` and `TransactionControl`, and the `BeanRepository` / `BeanFinder`
  supertypes and the `io.ebean.DB` / `io.ebean.Ebean` static facades are barred, so no read can be
  rooted without the query bean the assertions above guard. The `pagination` package is exempt from
  the import assertion: it names the query bean in a supertype and in every signature without ever
  constructing one.
- **Dependencies are injected by type, not by a string qualifier** (decided 2026-07-27): a new
  dependency is a dedicated type that carries its role (e.g. `PeriodicScheduler`, `WorkerExecutor`),
  and the container provides the instance. `@Identifier("...")` string qualifiers are not used for new
  code, because they couple the consumer to a producer's name rather than its type.
- **The database is one connection, and a transaction is what serialises a pair of statements.**
  `EbeanDatabaseProducer.sqliteDataSourceConfig` pins `minConnections` and `maxConnections` to 1
  (`EbeanDatabaseProducer.kt:53-54`); the URL adds `journal_mode=WAL`, `synchronous=NORMAL` and
  `busy_timeout=5000`, and deliberately omits `transaction_mode=IMMEDIATE`, whose eager write-lock
  grab is pointless without pool contention and once reintroduced a deadlock
  (`ebean.properties:16-23`). SQLite is a single-writer database and one connection is what it
  requires. **A claim about concurrent behaviour is checked against this before it is written down**,
  and the check turns on where the transaction boundary is, not on the connection count. The single
  connection serialises each statement; it does **not** serialise a pair, because in autocommit each
  statement takes and releases the connection separately. So a check-then-insert **inside** one
  transaction cannot be interleaved and is not racy, and the same pair written as two autocommit
  statements is racy today. One such pair is left, `EbeanTaskQueue.enqueue` (`EbeanTaskQueue.kt:50`),
  and it holds its transaction; a new one that does not is a defect, whatever the pool is set to.
  `UserCreator` was the second until 2026-08-05, when its pair stopped existing rather than being made
  safe: the read went and the index became the authority (the invariant below). An index
  that collides by value is a different matter again and stays reachable however well writes are
  serialised: `ix_user_password_hashes_user_created` (two hashes at the same instant) is the one the
  code translates, and `docs/adr/0006-domain-owned-timestamps.md:111` is where that reasoning lives.
  Recorded 2026-08-04, after a backlog entry asserted a race that the pool configuration had already
  closed; corrected 2026-08-05 against a measurement, which found the pair racy without its
  transaction on that same single connection, roughly 335 to 341 interleavings in 400 attempts across
  three runs, and zero with it (`docs/adr/0009-unique-index-named-outcomes.md`, findings).
- **The database is the authority on uniqueness** (decided 2026-08-05): no read before a write exists
  **solely** to answer a uniqueness question an index already answers. The write goes ahead, the
  adapter translates the violation into a domain exception and the use case rethrows its own error
  (`UserRepository.saveUser` under `ix_users_name_nocase`, caught by `UserCreator`). A read that also
  does something else stays, and what that something else is gets written down: the one written
  exception is `UserDataExportRequester.kt:58`, which orders two refusals and answers 409 ahead of
  429 while an export is running. No tool can tell a uniqueness read from any other read, so this
  line is the whole guard (`docs/adr/0009-unique-index-named-outcomes.md`, decision 2).
- **A unique constraint is not complete until its outcome is named** (decided 2026-08-05): every one
  the migrations declare, in either spelling, appears in `UniqueConstraintOutcomeTest`'s table with
  the answer a client gets when it fires, "no translation, deliberately" included, and the test
  refuses the next one that arrives silent. It holds that an outcome is named, not that it is true
  (`docs/adr/0009-unique-index-named-outcomes.md`, decision 1).

## Documentation

Two regimes; the table under "Which document is in which regime" says which document belongs to
which.

| Regime | Property | Rule |
| --- | --- | --- |
| **Dated** | Append-only once frozen: records what was believed on that date. | Never edit a frozen document; write a superseding one. |
| **Living** | Describes the present; drifts silently. | Updated in the same commit as the change. |

- **A dated document freezes at delivery** (branch integrated), not at writing; until then it absorbs
  changes like any work in progress. After delivery, changes go in a **new** dated document,
  cross-linked both ways, the old one marked `Status: Superseded by <file>`.
- **Simultaneity.** A living doc is updated **in the same commit** as the behaviour it describes, never
  a follow-up `docs:` commit. _Observable: doc hunk and code hunk in one commit._
- **The backlog is the pressure valve**: updated in the branch before wrap completes, writable at any
  time with the user's agreement; out-of-scope findings are proposed for it rather than done or lost.
- **Renumbering breaks anchors**: after reordering sections, re-check every cross-reference.
  _Observable: the check is run and reported._
- **An edit that does not apply is reported**, never assumed landed.
- **Comments explain why, not what.** Density matches the surrounding file.

### Which document is in which regime

| Document | Regime |
|---|---|
| `README.md` | living |
| `SECURITY.md` | living |
| `docs/backlog.md` | living |
| `AGENTS.md`, `agents/reviews/*` | living: the instructions this repository owns, updated in the same commit as the change they describe |
| `docs/openapi.json` | generated: rewritten by the `pre-commit` hook and checked in CI, never edited by hand |
| `docs/specs`, `docs/plans`, `docs/adr`, `docs/handoffs` | dated, append-only |

`.claude/` (settings, hooks, the `CLAUDE.md` import pointer) is harness configuration and code,
not documentation, so it stands outside this regime.

## Conventions

### Git and integration

- **The integration branch is `main`.** It is protected by the `validate / gate` check and receives
  work only through integration (a rebased PR); it is never edited directly.
- **Conventional commits**: `feat(scope):`, `fix(scope):`, `docs:`, `chore:`, `test:`, `refactor:`.
- **Tags** are annotated and not pushed, one per subsystem, named `vX.Y.Z-` followed by the
  subsystem's name (the latest is `v0.9.0-user-data-export`).
- **Merging is rebase only.**
  `gh repo view --json squashMergeAllowed,rebaseMergeAllowed,mergeCommitAllowed` returns
  `false`, `true`, `false`, so the PR is merged with `gh pr merge --rebase`, and only once the
  human review Wrap requires has come back. **The observable of that review is not
  `reviewDecision`**: GitHub refuses an approval from a pull request's own author, and every PR
  here is authored by the sole operator, so the trace is the feedback addressed in the
  conversation. Settled 2026-07-28, `docs/adr/0005-adopt-agents-baseline-v3.2.md`.
- **No local-merge exemption: everything integrates through a PR**, documentation-only changes
  included, because a local merge to `main` bypasses the `validate / gate` check (see Gotchas).
- **Clean tree before reporting completion.** Tool artefacts are cleaned or gitignored, never
  committed. _Observable: `git status --porcelain` shown at wrap._
- **"Leave as-is" stays available** as an integration option when the operator wants to handle the
  branch later.
- **Improve commits separately**: `docs(agents):` for a rule, `test(architecture):` for the test
  that enforces one. Improve opens only once Wrap has fully completed, so a retained remedy starts
  from `main` on its own branch and integrates through its own PR (PR #30 is the precedent).

### Writing

- **Everything in the repository is in English** (identifiers, comments, logs, commits, PRs, docs);
  conversation stays in the user's language, and genuine domain data keeps its own language.
- **Write in plain language.** Lead with the point; use the active voice and the present
  tense; keep sentences short; prefer common words; avoid hidden verbs and filler; reach
  for lists, tables and headings where they carry structure faster than prose. This covers
  everything you write for the repository (documents, commit messages, code comments, the
  docs tree) and every message to the user. Use another register, language or level of
  detail only when the user explicitly asks for one.
- **Never use an em dash or en dash anywhere** humans read (code, docs, commits, UI, logs). Use a
  colon, period, parentheses or hyphen. Exception: agent-only scratch artefacts.
- **A comment holds in two lines.** Past that it is documentation, and it goes where documentation
  lives: a spec for a design, an ADR for a decision, the backlog for a defect, a handoff for what was
  learned. The comment keeps the one sentence that says why, and the reader who wants the rest follows
  the pointer (settled 2026-07-29, after a review flagged eight comments on a single pull request as
  walls of text, each of which explained the why correctly).
- **A dated document does not put a number on a living file** (settled 2026-08-12): it records what it
  did, and the count is read where it lives. A spec, an ADR and the backlog all announced three P2
  items on the day the band held five, because two arrived after the sentence was written and a frozen
  document cannot follow. Say "the items this lot leaves open are 1, 2 and 14", never "the band holds
  three".
- **No abbreviations in code, comments, KDocs or logs** (decided 2026-07-27): domain terms are
  spelled out (e.g. "garbage collection", not "GC"). Narrative documents (specs, ADRs, handoffs) may
  abbreviate after the first definition, but the source must not.

### Tests

How this project writes tests. The framework, the coverage tool and the stance on mocking are
under Kotlin, Tests and coverage.

- **Testing order**, each level failing before implementation: integration tests in
  `api-application` (REST Assured, end to end), then use-case unit tests in `api-usecases` (MockK),
  then repository tests in `api-persistence-sqlite` (Ebean).
- **The red pasted into a test commit body** is the output of `./gradlew :<module>:test`, narrowed
  with `--tests` when one class is enough. Here it is usually a **compilation failure rather than a
  failing assertion**: a test naming a type its implementation has not introduced yet breaks
  `compileTestKotlin`, and that unresolved-reference output is the red. Paste it from the run; a
  prose description of what failed is what this rule replaced (settled 2026-07-28,
  `docs/adr/0005-adopt-agents-baseline-v3.2.md`).
- **A structural assertion arrives with the mutation that makes it fail**, pasted in the message of
  the commit that introduces it, the way a failing test carries its red. An assertion that filters a
  set down and ends on `assertEmpty()` passes just as well when the filter matches nothing at all, so
  a green run is not evidence that it holds anything (settled 2026-07-29: four such checks shipped in
  one branch, three of them caught by mutating the code and watching them fail).
- **A guard over `dbmigration` says which of the two readings it takes** (settled 2026-08-12). The
  history is append-only, so "some migration once created this" and "the schema holds this today" are
  different questions, and `MigrationDirectory` answers each by name: `currentIndexes` replays
  creations and removals in version order, `allIndexCreations` is everything the history carried. A
  guard reading the whole history as if it were the current schema passes forever: three did, and
  `SweepIndexesMigrationTest` had been demanding an index `1.15.sql:6` dropped, green the whole time.
  `UniqueConstraintOutcomeTest` deliberately reads the history, because an outcome named for a
  constraint that once existed is not wrong; it says so in its KDoc, which is the observable.
- **A case joins an existing integration suite; a suite is not created for a case.** A new
  `@QuarkusTest` class costs a full boot in the gate, so it is justified by a scenario an existing
  suite cannot host (its profile, its wiring), never by a case that could be a method in one. Where no
  suite fits and each link is pinned separately, the composition is the coverage and the finding is an
  accepted limit.
- **Test names** use backticks and the `Given..., Then...` form, with no "when" in the name:
  `` fun `Given duplicate username, Then throws UserCreationError`() ``.
- **Test bodies** follow Given-When-Then with explicit `// Given`, `// When` and `// Then` comments.
- **Test maintainability**: helper methods for repeated setup (`createAndSaveUser()`), named test
  variables rather than inline literals, `createRandomString()` from `api-utilities` for unique
  data, and extend the base class that fits: `IntegrationTest` (`api-application`),
  `RepositoryTest` (`api-persistence-sqlite`), or `BaseTest` (`api-utilities` test fixtures).

### Code

- **Module conventions**: entities in `api-domain/entities/` have matching interfaces in
  `api-domain/repositories/`; persistence repositories convert through `mappers/`; use cases throw
  domain-specific exceptions (`UserCreationError`, `BoardCreationError`); controllers use the DTOs in
  `dtos/`.
- **No top-level functions**: a Kotlin helper belongs to a class, a companion or an object, and
  extension functions are the only free-function exception (`queries/PinBoardQueries.kt` is where the
  exception is taken). Carried by seven execution plans between 2026-07-08 and 2026-07-22
  (`docs/plans/2026-07-08-image-hosting-2a.md:18` is the first), which are frozen documents: recorded
  here on 2026-07-29 because a convention that must persist lives in this file, where a reader is
  told to look for it.
- **Structural remedies have three homes**: `ArchitectureKonsistTest` for an invariant over the
  declarations of the whole project, a detekt rule in `detekt-rules` for a prohibition about the
  statements inside one file, and a plain test such as `DbMigrationModelCoverageTest` for an
  invariant about repository content. The first two split by what each tool can see: Konsist reads
  every declaration at once and so can derive a set of types from a marker interface, while a detekt
  rule reads one syntax tree and so can tell a call apart from a sentence in a comment that spells
  it. Neither does the other's half.

### The backlog

- **The backlog holds open items only.** It has no shipped section: completed work is recorded by
  its handoff, git history and tag. On wrap, delete or narrow the item just finished, add the newly
  discovered ones, and update the `Last reviewed` line. After the merge, reconcile it on `main`: if
  a stale entry survived the pre-merge refresh, delete it with a doc-only commit.
- **A review finding has four exits, and only one is the backlog** (decided 2026-08-12): fixed inside
  the lot; a **backlog item**, which means work someone will do; an **accepted limit**, written where
  the decision lives (its ADR, its spec, the KDoc a reader meets) and never copied into the backlog;
  or **refused**, with the reason in the handoff. Wrap states which exit each finding took, and that
  list is the observable. Before this rule the only exit was the backlog, so the P2 band grew by six
  net in the lot that preceded it (`docs/adr/0010-review-finding-dispositions.md`).
- **The backlog is banded by nature before it is banded by priority**: *Open work* (P0, P1, P2) is
  what someone will do, *Known limits* points at the document recording each one, *Before beta* holds
  dated events. A limit is not debt and is not counted as debt.

### The harness

- **A convention that must persist goes in this file**, not session memory: memory is invisible
  to CI, fresh clones, and other agents.
- **Documentation source**: the project configures no in-repo documentation server (no `.mcp.json`,
  no vendored copy under `docs`). "Consult the declared documentation source, not recall" resolves
  to the current upstream documentation of the stack: Quarkus, Ebean, libvips (vips-ffm) and Gradle.
  Name the source when a claim rests on it.
- **`.claude/settings.json` deny list.** Carries `AskUserQuestion` and `EnterPlanMode`. `EnterPlanMode`
  is a no-op in this harness: the plan-mode tool that exists is `ExitPlanMode` (used to leave plan mode
  and request approval), so the entry denies a tool that is not exposed. `AskUserQuestion` is likewise
  not exposed to the agent in this project; the entry keeps it that way. `/permissions` is the source of
  truth on the effective set.
- **Worktrees**: `EnterWorktree` creates one under `.claude/worktrees/` and moves the agent session
  there while the operator's editor stays on `main`; `worktree.baseRef` is `head`. It is one of the
  four branching options and **none of them is a suggested default**: the operator picks.

## Gotchas

- **A local merge to `main` silently bypasses CI.** Branch protection requires the
  `validate / gate` check, but `enforce_admins` is false, so an admin merging locally gets no
  refusal and no CI run. Push and open a PR.
- **Editing an applied migration breaks startup.** The checksum changes and Ebean refuses the
  history. A correction is a new migration, never an edit.
- **A unique constraint on SQLite is `@Index(definition = "create unique index ...")`, never
  `unique = true`.** `unique = true` makes Ebean's dialect try `ALTER TABLE ADD CONSTRAINT UNIQUE`,
  which SQLite does not support, so the generated migration is a `-- not supported:` comment that
  applies silently and enforces nothing. The `definition` attribute makes Ebean emit the
  `create unique index` itself, and it is the form `UserModel` now uses for `ix_users_name_nocase`.
  `DbMigrationModelCoverageTest` now fails if any committed migration carries that no-op marker.
- **A partial or expression index is declared by `definition` alone**, with no `columnNames` and no
  `unique = true`. Ebean keys an index by its name and compares `tableName`, `unique`, `definition`
  and the column list between the two model sides, so `columnNames` buys nothing when a `definition`
  is present and only creates a second attribute that has to agree. `UserDataExportModel` is the one
  declaration carrying all three: its `columnNames` and `unique = true` are inert beside the
  `definition`, and they stay because `1.11.model.xml:4` records them, so removing them would diff.
  The comment that called `columnNames` what keeps the index diffable is gone (2026-08-12). The four
  indexes recorded on 2026-08-05 (`TaskModel`, `UserModel`) use the `definition`-only form, which is
  also what `1.18` uses.
- **The `pre-commit` hook rewrites `docs/openapi.json`**, stages it, and exits non-zero when it
  changed, so the commit has to be re-run. That is the hook working, not a failure. It also rejects
  em-dashes and en-dashes in staged text additions (no-em-dash rule, under Writing): use a
  colon or a hyphen instead.
- **The hook is a shortcut, not a barrier**, and an uninstalled one is no barrier at all: it ran on
  none of the 70 commits of the branch delivered on 2026-07-29, because `core.hooksPath` was unset in
  that clone. Both of its checks now have a home nothing can skip: the dash rule in the gate
  (`checkNoLongDashes`, over every tracked file, excluding the dated document directories because a
  frozen document is never rewritten), and the OpenAPI document in CI, right after the build that
  regenerates it.
- **There is no auto-fix task.** detekt runs without formatting rules and ktlint is configured only
  as an IDE plugin (`.idea/ktlint-plugin.xml`), so a finding is fixed by hand.
- **detekt baselines are per module** (`config/detekt/baseline-api-usecases.xml` and its two
  siblings, one file per module name) because each module's
  `detektBaseline` task rewrites rather than merges the target file. The path degrades gracefully
  when the file is absent.
- **A changed detekt rule is not picked up by a live Gradle daemon.** The daemon caches the
  `detektPlugins` classpath, so after editing a rule in `detekt-rules` the gate run by that same
  daemon loads the old rules and passes whether or not the new one fires: a false green. Run
  `./gradlew --stop` (or pass `--no-daemon`) after a detekt-rule change before trusting a local
  gate. CI is unaffected: each job starts a fresh daemon.
