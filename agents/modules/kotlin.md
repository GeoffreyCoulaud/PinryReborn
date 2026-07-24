<!-- agents-baseline v3.0.0 | generic module | do not edit in place -->

# Module: Kotlin

Language conventions. The workflow, the review mandates and the engineering norms are in
`AGENTS.md` and are not repeated here. Project-specific values (module graph, JDK version,
frameworks) are declared in `agents/project.md`.

## Build

- **`./gradlew` is the runner**, never a globally installed Gradle. The wrapper is committed and
  its version is part of the project.
- **Kotlin DSL** (`build.gradle.kts`) with a version catalog (`gradle/libs.versions.toml`) as the
  single source of dependency versions. No hard-coded versions in module build files.
- **Module boundaries are enforced by the build graph, not by convention.** If a module must not
  see another, it must not declare it as a dependency. A layering rule that only exists in prose
  is already broken.

## Null safety and modelling

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

## Errors and coroutines

- **Exceptions cross layers only as domain types.** An adapter translates its framework or driver
  exception into a domain error; a persistence exception must never reach a controller.
- **Never catch `Exception` broadly to keep going.** Absorb failures from external I/O
  deliberately and log the cause; let in-process, fully-tested code fail loudly.
- **Structured concurrency**: no `GlobalScope`, every coroutine has an owning scope with a defined
  lifetime. Cancellation is cooperative, so never swallow `CancellationException`.
- **Dispatchers are injected, not hard-coded**, so tests can control them.

## Tests and coverage

- **JUnit is the test framework**, at whatever major version the version catalog pins. This module
  states no number: a generic file naming one only ages, and the catalog is where the answer
  already lives. Test names describe the behaviour and the condition, not the method under test.
- **Prefer fakes over mocks** for ports you own. When mocking is genuinely needed (MockK), assert
  on outcomes rather than on the interactions you just configured: a test that verifies its own
  stubbing passes against a broken implementation.
- **Coverage is measured with Kover**, branch counting enabled, verification bound in the build so
  it fails the gate rather than printing a report nobody reads.
- **Static analysis is part of the gate**, not an optional task. If detekt or ktlint is configured,
  the gate runs it; a rule is suppressed inline only with a reason on the suppression.

## Structural invariants are tests, not prose (Konsist)

An invariant written in a document is a wish. The same invariant expressed as a Konsist test is
enforced on every run, and it names its violations. **Every structural rule this project relies on
gets a Konsist test**: the invariants declared in `agents/project.md`, the structural decisions
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
