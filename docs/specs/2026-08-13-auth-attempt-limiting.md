# Authentication attempt limiting

Date: 2026-08-13
Status: Draft 2026-08-13 (design approved in discussion; written spec pending review)
Branch: `feat/auth-attempt-limiting`
ADR: `docs/adr/0013-in-memory-authentication-attempt-limiting.md`
Backlog item: "Authentication attempt limiting (brute force)", `docs/backlog.md`, Before beta.

Closes the one security gap the project left deliberately open. The predecessor spec
`docs/specs/2026-07-31-current-password-determinism.md` (D10) states why the existing minimum
interval is not a brute-force defence: it counts successful changes only, so a failed attempt writes
nothing and costs the caller nothing. This spec adds the state that a failed attempt costs
something.

## 1. Goal

Three sites verify a password, and each one answers "was that the right password" as many times as a
caller asks:

| Site | HTTP entry point | Identity available |
|---|---|---|
| `UserAuthenticator.checkLogin` (`api-usecases/.../UserAuthenticator.kt:35`) | `POST /api/v1/sessions` | the submitted name, unauthenticated |
| `Reauthenticator.reauthenticate` (`Reauthenticator.kt:14`) | `DELETE /api/v1/me`, `POST /api/v1/me/exports` | the authenticated user |
| `PasswordChanger.changePassword` (`PasswordChanger.kt:29`) | `PUT /api/v1/me/password` | the authenticated user |

After this lot, repeated failures against one identity buy an escalating refusal instead of another
password guess, and the refusal is served before the bcrypt call rather than after it.

## 2. Scope

**In scope:**

- One `AuthenticationAttemptLimiter` in `api-usecases`, holding the failure counters in process
  memory, with the policy (threshold, backoff steps, forget-after) supplied as constructor
  parameters by the composition root.
- The three call sites above check the limiter before verifying the password, record a failure when
  the verification fails, and clear the counter when it succeeds.
- One error code, `TOO_MANY_AUTHENTICATION_ATTEMPTS`, mapped to 429 and carrying `Retry-After`
  through the existing `ThrottledError` marker.
- Four configuration properties on `AuthConfig`, each with a default that is on, not off.

**Out of scope:**

- **Persisting the counters.** The state is in memory and is lost on restart (ADR 0013, decision 1).
- **Limiting by IP address.** It needs a trusted `X-Forwarded-For` chain, which is a deployment
  contract this project does not have (ADR 0013, decision 2).
- **Routing `PasswordChanger` through `Reauthenticator`.** The duplication predates this lot and
  removing it is a refactor, which is its own task (`agents/workflow.md`, Scope).
- **Limiting anything that is not a password oracle**: user creation, session token presentation,
  export downloads. A session token is 256 bits of entropy from `SecureTokenGenerator`, not a guess
  target.
- **CAPTCHA, administrative lockout, notification on lockout, two-factor.** Two-factor is already a
  separate backlog feature.
- **Declaring the 429 in `docs/openapi.json`.** No controller declares its error responses today
  (`grep -c 429 docs/openapi.json` returns 0); adding the first one is a documentation convention to
  settle on its own.

## 3. Decisions (invariants)

- **D1. The state lives in process memory**, in one `ConcurrentHashMap` owned by the limiter. No
  table, no migration, no repository, no port. Rationale and accepted limits: ADR 0013.
- **D2. The login key is the submitted name, counted whether or not the user exists.** Counting only
  existing users would turn the 429 into the account-enumeration oracle that
  `UserAuthenticator.kt:26` pays a dummy bcrypt hash to avoid.
- **D3. The login key is lower-cased with `Locale.ROOT`**, and lower-cased before it is digested (D9),
  so the folding never depends on the name's length. `UserRepository.findUserByName` matches with
  `ieq` and the unique index is `collate nocase` (`UserModel.kt:16`), so a case-sensitive counter
  would be bypassed by alternating case. Kotlin's `lowercase` folds more than SQLite's ASCII-only
  `nocase`, so two distinct accounts can share one counter; grouping wider is safe for brute force
  and is recorded as a limit in ADR 0013.
- **D4. Authenticated re-verification is keyed by user id**, in a key space distinct from the login
  one. Re-authentication and password change share that counter: it is the same secret.
- **D5. No hard lockout, an escalating backoff.** A blocked key becomes usable again on its own,
  after the current step. This is what keeps a third party from locking someone out of their
  account by failing on their behalf.
- **D6. The check runs before the password verification**, so a blocked key costs no bcrypt. Without
  this, the limiter would still leave the CPU exhaustion path open.
- **D7. The refusal is a 429 with `Retry-After`**, through the existing `ThrottledError` marker
  rendered by `BaseErrorMapper.kt:34`. Nothing new is needed in the presentation layer.
- **D8. The new error is not a `UserAuthenticationError`.** `SessionController.kt:39` catches that
  type and rewrites it as a 401, which would swallow the 429.
- **D9. The tracked keys are bounded in number and in width.** The login key is attacker-supplied and
  unbounded in cardinality, so the map holds at most `maxTrackedKeys` entries. It is unbounded in
  length too, and a count bounds no memory while an entry can hold a megabyte of submitted name, so
  the key is a SHA-256 digest of the normalised name rather than the name (ADR 0013, decision 4). A
  digest and not a prefix: truncation would let an attacker land in a victim's counter by choosing a
  name that starts like theirs.
- **D10. There is no off switch.** A property that disables brute-force limiting is a setting that
  should not exist (`agents/workflow.md`, Design). The limiter refuses to be built on any value at
  which it stops limiting: a threshold below 1, an empty backoff list, a backoff step of zero or
  less, a forget-after of zero or less, and a `maxTrackedKeys` below 1. The bound is the one that
  hides best: at 0, every insertion exceeds it and evicts the entry just written, so nothing is ever
  counted and nothing says so. **The refusal lands at boot**: the producer is `@Startup`, because a
  lazily built limiter throws on first use, caches nothing, and replays that 500 on every
  authentication, password change, account deletion and export for as long as the process runs.

## 4. The policy

State per key: `failures` (Int), `blockedUntil` (Instant, nullable), `expiresAt` (Instant). An entry
whose `expiresAt` has passed counts as absent: the next failure starts the count over, and the entry
is dropped when the bound is crossed. `check` needs no expiry test of its own, since `expiresAt`
never falls before `blockedUntil` and an expired entry is therefore never still blocked.

| Operation | Effect |
|---|---|
| `check(key)` | If the entry has `blockedUntil` in the future, throw `TooManyAuthenticationAttemptsError` with the remaining seconds. Otherwise return. |
| `recordFailure(key)` | `failures` becomes the live entry's `failures` plus 1 (1 when absent). When `failures` is at least `threshold`, `blockedUntil` becomes `now + steps[min(failures - threshold, steps.lastIndex)]`. `expiresAt` becomes the later of `now + forgetAfter` and `blockedUntil`. |
| `recordSuccess(key)` | Remove the entry. |

With the defaults (threshold 5, steps `PT30S,PT2M,PT10M`):

| Consecutive failures | Outcome |
|---|---|
| 1 to 4 | 401 or 403 as today, nothing blocked |
| 5 | blocked 30 seconds |
| 6 | blocked 2 minutes |
| 7 and beyond | blocked 10 minutes |

`Retry-After` is whole seconds, rounded up: a fraction of a second still costs the caller a whole
one, so the value is never below 1 while the block is in the future.

**Bounding the map.** An entry is fixed width: `AuthenticationAttemptKey.forLogin` digests the
normalised name, so the caller's length choice buys no memory (D9). After an insertion takes the size
past `maxTrackedKeys`, expired entries are purged; if the size is still past the bound, the entry
with the earliest `expiresAt` is evicted. Both passes are linear and run only at the bound, so
nothing purges the map once the failures stop; the fixed width is what makes that acceptable.

## 5. Configuration

Added to `AuthConfig` (`api-presentation-quarkus/.../config/AuthConfig.kt`, prefix `auth`,
`SNAKE_CASE`):

| Property | Type | Default | Meaning |
|---|---|---|---|
| `auth.attempt_limit_threshold` | Int | 5 | Consecutive failures before the first block |
| `auth.attempt_limit_backoff` | List of Duration | `PT30S,PT2M,PT10M` | Block duration per step, saturating on the last |
| `auth.attempt_limit_forget_after` | Duration | `PT15M` | Idle time after which a counter is forgotten |
| `auth.attempt_limit_max_tracked_keys` | Int | 10000 | Bound on the map (D9) |

The limiter takes these as raw constructor parameters, supplied by a producer in the composition
root, so `api-usecases` stays free of configuration: the `PasswordChangerProducer` precedent
(`docs/specs/2026-07-31-current-password-determinism.md`, D22).

**One unverified assumption, to be settled by a test before the code depends on it.** Quarkus's
config-mappings guide states that collections of simple types are supplied as comma-separated values
and that `@WithDefault` goes through the same conversion as a configured value, which should make
`@WithDefault("PT30S,PT2M,PT10M") fun attemptLimitBackoff(): List<Duration>` work. That is read from
the guide, not measured here. If the mapping does not resolve, the fallback is a single `String`
property parsed in the producer, and the defaults and semantics above are unchanged.

## 6. Acceptance criteria

1. Five failed logins for one name, with the sixth refused: `POST /api/v1/sessions` returns 429,
   `code` is `TOO_MANY_AUTHENTICATION_ATTEMPTS`, and `Retry-After` is present and numeric.
2. The same, with the name's case alternated on every attempt: still refused on the sixth (D3).
3. The same for a name that belongs to no user: still refused on the sixth (D2).
4. A successful login before the threshold clears the counter: the next failure starts over.
5. `PUT /api/v1/me/password` and `DELETE /api/v1/me` share the counter for one user, and the refusal
   is 429 rather than 403 once the threshold is passed.
6. A blocked key does not reach the password hasher: pinned in the unit tests by counting the
   hasher's calls, `verify(exactly = threshold) { matches(any(), any()) }`, at each of the three
   sites and on both branches an attempt can take, the wrong password and the name that belongs to
   no user. A hasher that fails the test on any call, the shape this criterion first asked for, does
   not fit: driving a key to the block takes `threshold` real attempts, each of which the hasher has
   to answer, so such a stub would fail on the setup and never reach the case. It is available, not
   forbidden: a bare `mockk<PasswordHasher>()` throws `MockKException` when reached, and
   `BaseTest.afterEachCheckUnnecessaryStub` has nothing to report against a mock with no stubs. The
   count pins the same property and pins it against the code rather than against the fixture.
7. The block expires on its own: with a short step configured, an attempt after the step passes
   through to the ordinary 401.
8. `./gradlew gate` is green, including the 100 percent branch-coverage bound.

## 7. Tasks

Three, each independently checkable:

1. **The limiter and its error.** `AuthenticationAttemptLimiter`,
   `TooManyAuthenticationAttemptsError`, `ErrorCode.TOO_MANY_AUTHENTICATION_ATTEMPTS`, the 429 arm in
   `BaseErrorMapper`. Unit tests cover the policy table, the forget-after, and the bound.
2. **The three call sites and the wiring.** `UserAuthenticator`, `Reauthenticator`, `PasswordChanger`,
   the four `AuthConfig` properties, the producer. Existing unit tests updated.
3. **The integration tests.** Criteria 1, 2, 3, 5 and 7 against a `QuarkusTestProfile` with a tight
   policy, following `MePasswordRateLimitIntegrationTest`.

## 8. Risks

- **The default threshold changes existing integration tests.** No current test fails a login more
  than twice against one name, so the default of 5 should leave them green; if one trips, the test
  is what changes, not the default.
- **Kover's branch bound and generated members.** A data class for the key state can add synthetic
  constructor branches, a trap `SessionCreationInputDto.kt:9` already records. Prefer explicit
  defaults over generated ones.
- **The limiter mutates outside the transaction.** `SessionCreator.create` wraps the authentication
  in `transactionRunner.inTransaction`, so a failure recorded by the limiter survives the rollback
  of that transaction. This is intended: a failed attempt must cost something even when nothing is
  written.
