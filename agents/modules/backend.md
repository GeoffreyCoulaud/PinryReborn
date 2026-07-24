<!-- agents-baseline v2.2.0 | generic module | do not edit in place -->

# Module: Backend

Conventions for a service that exposes an API and owns persistent state. Language-specific rules
are in the language module; the workflow and engineering norms are in `../../AGENTS.md`. The concrete
error format, auth scheme and storage engine are declared in `../project.md`.

## The API is a contract, and contracts are uniform

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

## Boundaries

- **Validate at the edge, then trust inward.** Incoming payloads are parsed into validated types at
  the adapter. The domain never receives a raw request body, a raw query string, or a nullable
  field it has to re-check.
- **The wire format is not the domain model.** DTOs are separate types. Serialising a domain entity
  directly couples the public contract to an internal refactor.
- **Identifiers, casing and normalisation are decided once.** Whether usernames are
  case-insensitive, whether trailing slashes matter, whether IDs are opaque: decided in the spec,
  applied at one place, tested.

## Configuration and secrets

- **All configuration is read in one place** and exposed as a typed object. No component reads the
  environment directly.
- **Configuration keys keep their namespace.** This is the constraint-escape rule of `../../AGENTS.md`
  Design in its most common backend form: a key is not moved to another prefix to dodge a
  framework strictness check.
- **Before adding an option, ask whether the deployment model makes it meaningless.** An option
  nobody should ever change is a design smell, not a feature.
- **Secrets never reach a log, an error payload, a trace or a test fixture.** Redaction is applied
  at the sink, not at each call site.

## Network binding

- **Bind addresses are deliberate and stated.** A service reachable only from its own loopback is
  unreachable from another container; a service bound to every interface is exposed further than
  intended. Whichever is chosen, the reason is in the spec, and the running configuration is
  verified with a real check (`ss`, `curl` from the intended caller), not assumed from the code.

## Persistence

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

## Operations

- **Every response the client can act on is testable end to end.** Contract tests exercise the real
  wire format, not the handler function.
- **Idempotency is a property, not a hope.** Retried writes, replayed webhooks and re-delivered
  jobs either converge or are explicitly documented as unsafe to retry.
- **Health and readiness are distinct.** Readiness reflects the dependencies the service actually
  needs to serve traffic, and a dependency check that always returns true is worse than none.
