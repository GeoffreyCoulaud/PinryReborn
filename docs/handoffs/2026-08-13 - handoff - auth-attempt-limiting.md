# Handoff: authentication attempt limiting

Date: 2026-08-13
Branch: `feat/auth-attempt-limiting`
Spec: `docs/specs/2026-08-13-auth-attempt-limiting.md`
ADR: `docs/adr/0013-in-memory-authentication-attempt-limiting.md`
No plan document: tier Spec, three tasks.

Closes the backlog's one deliberately open security gap. Twenty-three commits, 26 files.

## Current state

Repeated password failures against one identity now buy an escalating refusal instead of another
guess, at the three sites that verify a password:

| Site | Entry point | Key |
|---|---|---|
| `UserAuthenticator` | `POST /api/v1/sessions` | the submitted name, digested |
| `Reauthenticator` | `DELETE /api/v1/me`, `POST /api/v1/me/exports` | the user id |
| `PasswordChanger` | `PUT /api/v1/me/password` | the user id, the same counter as above |

Defaults: five failures, then 30 seconds, 2 minutes, 10 minutes, saturating; a success clears the
counter; an idle counter is forgotten after 15 minutes. The refusal is a 429
(`TOO_MANY_AUTHENTICATION_ATTEMPTS`) with `Retry-After`, through the `ThrottledError` marker that
already existed. `./gradlew gate` green.

## What was built, and the three things that were not what they looked like

**The state question answered itself.** The backlog framed the counter's "behaviour across
instances" as one of three things to design. It is not a question while the store is a SQLite file:
more than one instance is already excluded, so the counters live in one `ConcurrentHashMap` in
`api-usecases`, behind no port, with no migration and no sweep. ADR 0013 decision 1.

**A bound that counts entries is not a bound on memory.** The tracked-key bound was specified,
implemented and reviewed twice as a defence against an attacker-supplied unbounded key space. The
holistic review measured what it actually bought: since the login DTO carries only `@NotBlank` and
the body limit is 32M, the key was the submitted name in full. Sixty distinct megabyte names, one
failed attempt each, retained tens of megabytes; the bound never noticed, because 60 is far below
10000. `AuthenticationAttemptKey.forLogin` now digests the normalised name to a fixed 64 characters.
Lower-case first, then digest: the other order makes the case folding a no-op.

**`Retry-After` rounded down at both sites that already emitted it.** The spec cited
`PasswordChanger` as the precedent for rounding up; that citation was false. Rounding down sends a
client back a second early into a second 429. The operator decided during the lot to align all three
sites upward, and the arithmetic now lives once, in `ThrottledError.wholeSecondsBetween`.

## Pitfalls, in the order someone will meet them

- **An invalid policy fails at boot, and that is a one-annotation property.** The limiter's
  constructor refuses every value at which it would stop limiting (spec D10), but
  `@ApplicationScoped` alone made those guards fire on first use: the application started clean and
  then returned 500 on every authentication forever, since a constructor that throws caches nothing.
  `@Startup` on the producing method is what turns that into a boot failure. Removing it restores
  the silent-until-first-login behaviour, and no test would notice (see "not validated").
- **`forget_after` and the last backoff step interact, and nothing validates the pair.** Each is
  checked in isolation. Because the forget-after is longer than the last step, a counter never walks
  back down under a sustained attack, which is what makes holding an account's login closed cost
  about 150 requests a day. Measured both ways, ADR 0013 decision 3.
- **The counters survive `truncateAllTables`.** They live in the CDI container, not the database, so
  they cross test cases within a class. They do not cross a `QuarkusTestProfile` boundary, since
  Quarkus rebuilds the container per profile. Any integration case that submits a wrong credential
  takes an identity of its own; `IntegrationTest`'s KDoc now says so.
- **`application.properties` no longer restates any `@WithDefault`.** The holistic review asked for
  the four new properties to be added on the file's own convention; restating a value the annotation
  already carries makes two sources for one default, and it silently turned
  `AuthConfigDefaultsIntegrationTest` into a test of the file rather than of the defaults. On
  operator review the four came out, then the thirty other restatements with them: every one was
  byte-identical to its annotation, and `garbage-collection.*` was already living outside the file
  with no ill effect. What is left is what an operator must set (`api.*`, the datasource, the CORS
  origin) plus a comment naming each prefix and the two traps an interface cannot state on its own.
- **A blocked key costs no bcrypt, but only for requests arriving after the block is recorded.**
  `check` and `recordFailure` are two operations; requests already past their check still pay.

## What is not validated

- **The boot failure has no test.** `QuarkusUnitTest` is not on the test classpath, and pinning it
  would cost a catalogue entry on an artifact Quarkus calls internal plus a Quarkus boot per pull
  request. The property is declared in spec D10 and in a comment on the annotation, and it is
  measured in `bfb8c60`'s commit body, not by anything that runs again.
- **The concurrency window is reasoned, not measured.** No test drives parallel failures against one
  key; `ConcurrentHashMap.compute` is what serialises the increment.

## Next step

Nothing in this subsystem is left open. If the deployment ever stops being a single process, the
limiter is the seam that breaks, and it breaks silently: ADR 0013 decision 1 is where that is
written, and no test enforces it.
