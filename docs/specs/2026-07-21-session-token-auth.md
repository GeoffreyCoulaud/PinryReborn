# Session-token authentication

Date: 2026-07-21
Status: approved design, pending implementation plan
Depends on: users (registration + `UserAuthenticator` password check), the `Clock` port, and the
existing Quarkus Security wiring. No dependency on pins/boards/images.

## 1. Goal

Give browser clients (the web SPA and the browser extension) a way to authenticate **without
storing the raw password**. Today auth is HTTP Basic only, so every client would have to keep the
password and send it on every request. Replace that with a **server-side session token**: the
client logs in once with credentials, receives an opaque bearer token, and uses it thereafter.

This is the first P1 "client ergonomics" item in `docs/backlog.md` and a prerequisite for serious
UI/extension work. It also unblocks the CORS item (mechanical once the auth model is fixed).

Deliverables named by the backlog: a token mechanism, a `GET /me` (current identity) endpoint, and
a credential-check endpoint (satisfied by the login endpoint: invalid credentials return 401).

## 2. Scope

**In scope:**

- Opaque, server-stored, revocable **session tokens**, presented as `Authorization: Bearer <token>`.
- A login endpoint that takes credentials in a JSON body and issues a token.
- A **renewal** endpoint (present the current valid token, receive a fresh one, old rotated out) so
  an active client stays logged in indefinitely without ever re-sending the password.
- **Logout** of the current token, and **logout of all** the user's tokens.
- A **"keep me logged in"** flag at login selecting a long-lived (persistent) vs short-lived
  (ephemeral) session; renewal works for both.
- Expiry metadata returned to the client (`expiresAt` + a recommended `renewAfter`), so a polite
  client renews before it hits a 401.
- `GET /me` returning the current identity.
- Retiring HTTP Basic entirely (the token becomes the only request credential).
- One reusable test-authentication helper replacing the Basic pattern across the integration tests.

**Out of scope (deferred):**

- **JWT / stateless tokens.** Opaque + server-stored is chosen for cheap revocation on a
  single-instance self-hosted app; statelessness buys nothing here. See §3.
- **Access + refresh token split.** A single renewable token already delivers the indefinite
  silent session; the split only shortens the hot-credential exposure window, at a real cost under
  the 100%-branch-coverage rule. See §3.
- **API keys / personal access tokens** (user-managed, PAT-style). YAGNI for self-hosted.
- **Scopes / roles.** The model stays binary authenticated/anonymous, as today.
- **GC of expired token rows.** Expired rows are inert (rejected at verification). A sweep is P2
  operational debt, noted in the handoff, not built here.
- **CORS.** The next backlog item; unblocked by this but not part of it.
- **Device/session listing UI** (naming sessions, seeing active devices). Logout-all covers the
  security need; per-session management is a later profile-management concern.

## 3. Key decisions (rationale captured for the plan)

- **Opaque, server-stored token, not JWT.** The token is ~256 bits of `SecureRandom`; only its
  SHA-256 hash is stored. A DB lookup per request is sub-millisecond on local SQLite, so the only
  JWT advantage (stateless verification, no lookup) is worthless here, while it would cost cheap
  revocation. This matches the "server-side session token" variant the `Login` KDoc already
  anticipates.
- **Single renewable token, not access/refresh.** With a renewal endpoint + rotation, an active
  client renews before expiry and stays logged in forever without the password. That already
  achieves UX parity with (and beats, being revocable + expirable) storing credentials. The
  access/refresh split only shortens the exposure of the per-request secret, a modest gain that
  doubles the auth surface to test.
- **Absolute expiry, no sliding refresh tokens.** A token carries a hard `expiresAt`. Past it, the
  client re-logs in. Before it, the client renews.
- **Credentials in a JSON body; HTTP Basic fully retired.** `POST /sessions {name, password}` is
  the only endpoint touching credentials, and it is `@PermitAll`, authenticating via the existing
  `UserAuthenticator`. `quarkus.http.auth.basic` and `BasicAuthIdentityProvider` are removed; the
  `BasicAuthLogin` domain type stays (the login use case still constructs one). This closes the
  "password on every request" door completely rather than leaving Basic half-alive.
- **Login is also the credential-check endpoint.** Invalid credentials return 401; no separate
  `/verify` that checks without issuing.
- **"Keep me logged in" is a per-session boolean, not a session type.** A session does not change
  kind. At login, `rememberMe` selects a longer or shorter TTL; the session stores a `persistent`
  boolean so **renewal preserves the same lifespan** (a renewed ephemeral session stays ephemeral).
  Changing it means re-logging in.
- **`renewAfter` is a proportional threshold, not an absolute window.** With two TTLs, an absolute
  "renew N days before expiry" breaks for the short one (`renewAfter` in the past). Instead
  `renewAfter = expiresAt - ttl × (1 - renewThreshold)`, i.e. renew once `renewThreshold` of the
  lifetime has elapsed. It scales to any TTL with one knob.
- **Rotation on renew.** Renewal issues a brand-new token and deletes the old row. A leaked old
  token is dead after the legitimate client renews. Edge: if the renew response is lost in transit,
  the client is left holding the now-dead old token and must re-login. Accepted for v1 (bounded, and
  the client can retry renew while the old token is still valid up to that point). No grace window.
- **`SecureRandom` behind a port.** Token generation is the codebase's first `SecureRandom` use. It
  goes behind a pure `TokenGenerator` port (impl in presentation), so use cases stay deterministic
  and fully branch-testable, mirroring how `BackoffPolicy` injects its randomness.

## 4. Domain model

Pure domain (no I/O):

```
data class SessionToken(
    override val id: UUID,
    val user: User,
    val expiresAt: Instant,
    val persistent: Boolean,
) : Identifiable
```

The plaintext token is **never** a field on the entity and is never stored; it exists only in the
issue/renew response. The row is addressed by the SHA-256 hash of the plaintext (see §9).

> **Amendment (2026-07-21, agreed before planning):** token verification is a **dedicated
> `SessionTokenAuthenticator` use case** returning the full `SessionToken` (its id/expiry/persistent
> are needed by renew/revoke/`GET /sessions/current`), **not** a `SessionTokenLogin` subtype of
> `Login`. Routing tokens through `UserAuthenticator.authenticate(login): User` would be lossy (it
> returns only the user) and the extra `Login` branch would be dead in production. So the `Login`
> sealed interface and `UserAuthenticator` stay **password-only and unchanged**; no dead code.

Value returned by the issue/renew use cases (carries the transient plaintext):

```
data class IssuedSession(val token: String, val expiresAt: Instant, val renewAfter: Instant)
```

Ports (pure interfaces in api-domain):

- `TokenGenerator { fun generateToken(): String }` — a fresh, high-entropy, URL-safe token string.
- `SessionTokenRepositoryInterface`: `save(sessionToken, tokenHash)`, `findByTokenHash(hash): SessionToken?`,
  `deleteById(id)`, `deleteAllForUser(userId)`.

Plus a **concrete pure class** `SessionExpiryPolicy(persistentTtl, ephemeralTtl, renewThreshold)`
with `expiryFrom(now, persistent): Instant` (= `now + ttl(persistent)`) and
`renewAfterFor(expiresAt, persistent): Instant` (= `expiresAt - ttl(persistent) × (1 - renewThreshold)`).
Defining `renewAfter` from `expiresAt` (domain state on `SessionToken`) rather than from the issue
instant lets it be recomputed for an already-stored token (see `GET /sessions/current`) without
persisting an `issuedAt`. It has no injected dependency (just config values), so it needs no
interface; it is constructed by a presentation producer from `AuthConfig` and injected into the
issue/renew use cases. Deterministic, so unit tests construct it directly with fixed durations.

## 5. REST surface

All routes are under `/api/v1`. Every route except `POST /sessions` requires a valid bearer token.

| Method | Path | Auth | Body | Result |
|---|---|---|---|---|
| POST | `/sessions` | `@PermitAll` | `{name, password, rememberMe?}` | `CreatedSessionOutputDto`, **201** |
| GET | `/sessions/current` | Bearer | — | `ExistingSessionOutputDto`, 200 |
| POST | `/sessions/current/renew` | Bearer | — | `CreatedSessionOutputDto`, 200 |
| DELETE | `/sessions/current` | Bearer | — | 204 (revoke the current token) |
| DELETE | `/sessions` | Bearer | — | 204 (revoke **all** the user's tokens) |
| GET | `/me` | Bearer | — | `UserOutputDto` (`{id, name}`), 200 |

- `POST /sessions` is login **and** the credential check: bad credentials → 401
  (`AUTHENTICATION_FAILED`). `rememberMe` defaults to `false` (the conservative short session) when
  absent.
- `POST /sessions/current/renew` issues a fresh token with the **same** `persistent` value, and deletes the
  presented token (rotation). Delete-old + create-new is **atomic** (see §6).
- `GET /sessions/current` returns the presented session's metadata (`expiresAt`, `renewAfter`,
  `persistent`) **without** the token, so a client that persisted only the token string can recover
  when to renew. Deliberately on the session resource, not `GET /me`: `/me` is identity, this is
  session state.
- `DELETE /sessions/current` revokes only the presented token; `DELETE /sessions` revokes every
  token of the caller (including the presented one). Deleting one vs the collection keeps the two
  unambiguous.

### DTOs

- `SessionCreationInputDto = {name, password, rememberMe: Boolean = false}` — validates only
  `@NotBlank` on `name` and `password`; it deliberately does **not** re-apply registration's size /
  pattern constraints, so a badly-shaped credential returns 401 (auth failure), never 400
  (validation), avoiding a login/registration information gap.
- `CreatedSessionOutputDto = {token, expiresAt, renewAfter}` — `token` is the plaintext bearer string;
  `expiresAt` and `renewAfter` are ISO-8601 instants in UTC (`2026-08-19T12:34:56Z`).
- `ExistingSessionOutputDto = {expiresAt, renewAfter, persistent}` — the current session's state, no
  token (the client already holds it; re-emitting it would risk leaking it into logs).
- `GET /me` reuses `UserOutputDto = {id, name}`.

`renewAfter` is computed at response time (`expiresAt - ttl × (1 - renewThreshold)`), not stored.

## 6. Token lifecycle & rules

- **Issue (login).** Authenticate the credentials via `UserAuthenticator.authenticate(BasicAuthLogin)`.
  On success: `token = tokenGenerator.generateToken()`; `hash = TokenHasher.sha256(token)`;
  `expiresAt = expiryPolicy.expiryFrom(clock.now(), persistent)`; persist
  `SessionToken(randomUUID(), user, expiresAt, persistent)` under `hash`; return
  `IssuedSession(token, expiresAt, expiryPolicy.renewAfterFor(expiresAt, persistent))`.
- **Verify (every Bearer request).** `SessionTokenAuthenticator.authenticate(token)`:
  `hash = TokenHasher.sha256(token)`; `findByTokenHash(hash)`; if absent → invalid; if
  `expiresAt <= clock.now()` → expired; otherwise return the `SessionToken`. The Quarkus identity
  carries `user`, `userId`, and the full `SessionToken` (so renew/revoke can act on it).
- **Renew.** From the presented token's `SessionToken`, within a **single transaction**
  (`@Transactional` on `SessionRenewer.renew`): persist a new token with the same `persistent`
  **and** delete the old row by id, then return the new `IssuedSession`. The delete-old +
  create-new pair is **atomic** — the request must never commit a half-rotation (old deleted but
  new unsaved, leaving the caller with no valid token; or both persisted). This atomicity is a
  server-side all-or-nothing guarantee; it is distinct from the network "lost renew response" edge
  in §3, which it does not address.
- **Read current session.** Return the presented token's `expiresAt`, its `persistent`, and
  `renewAfter` recomputed via `expiryPolicy.renewAfterFor(expiresAt, persistent)`. No new token, no
  mutation.
- **Revoke current / all.** Delete the presented token's row, or all rows for `user.id`.
- **Expiry.** Purely a `expiresAt` vs `clock.now()` check at verification; expired rows are inert
  until (optionally, later) swept.
- **Remember-me.** `persistent = rememberMe`. Persistent → `persistentTtl`; ephemeral →
  `ephemeralTtl`. The ephemeral guarantee is the server-side short TTL; a client further scopes it
  by storing the token in non-persistent storage (its concern, not ours).

## 7. Security wiring (hexagonal placement)

- **api-domain**: `SessionToken`, `IssuedSession`, `TokenGenerator`,
  `SessionExpiryPolicy` (concrete pure class), `SessionTokenRepositoryInterface`. (No `SessionTokenLogin`;
  see the §4 amendment.)
- **api-usecases** (domain only): `SessionTokenAuthenticator` (verify a token → `SessionToken`, via
  `Clock` + `SessionTokenRepositoryInterface`; throws `SessionTokenInvalidError` /
  `SessionTokenExpiredError`); `SessionCreator` (authenticate via the existing password-only
  `UserAuthenticator`, then issue); `SessionRenewer` (`@Transactional`: atomic save-new + delete-old,
  §6); `SessionRevoker` (current + all); `TokenHasher` (an object, not a top-level function — SHA-256);
  new exceptions (see §8). `UserAuthenticator` is **unchanged**. `GET /sessions/current` needs no use
  case: it is a pure read of the identity's `SessionToken` plus a `SessionExpiryPolicy.renewAfterFor`
  call, done in presentation.
- **api-persistence-sqlite**: `SessionTokenModel` (`@Table("session_tokens")`) + mapper;
  `SessionTokenRepository`; migration **1.8** (additive).
- **api-presentation-quarkus**:
  - `SessionController` (`POST /sessions`, `GET /sessions/current`, `POST /sessions/current/renew`,
    `DELETE /sessions/current`, `DELETE /sessions`) and `MeController` (`GET /me`); input/output
    DTOs (incl. `ExistingSessionOutputDto`) + mapper. `GET /sessions/current` reads the identity's
    `SessionToken` and calls the injected `SessionExpiryPolicy` to fill `renewAfter`.
  - `BearerAuthenticationMechanism`: a custom `HttpAuthenticationMechanism` parsing
    `Authorization: Bearer <token>` into a Quarkus `TokenAuthenticationRequest` (Quarkus has no
    built-in opaque-bearer mechanism), plus an `IdentityProvider<TokenAuthenticationRequest>`
    delegating to `SessionTokenAuthenticator.authenticate(token)` and building the identity with
    `user`/`userId`/`sessionToken` attributes.
  - `SecureTokenGenerator` (`SecureRandom`) implementing `TokenGenerator`, `@ApplicationScoped`.
  - `AuthConfig` (`@ConfigMapping(prefix = "auth")`) + a producer building `SessionExpiryPolicy`
    (the `BackoffPolicy` producer precedent).
  - Remove `BasicAuthIdentityProvider`; change the `WWW-Authenticate` challenge from `Basic` to
    `Bearer` in `ProblemResponses`; a `SecurityIdentity.getSessionToken()` extension alongside the
    existing `getUser()`/`getUserId()`.
  - A JAX-RS exception mapper for the domain `UserAuthenticationError` escaping the `POST /sessions`
    call → 401 `AUTHENTICATION_FAILED` (today that error is swallowed inside
    `BasicAuthIdentityProvider`; body-login lets it reach JAX-RS).
- **api-application**: remove `quarkus.http.auth.basic=true`; add the `auth.*` properties; wire the
  new repository; integration tests; the reusable test-auth helper; regenerate `docs/openapi.json`.

## 8. Errors

Mirror the existing exception/`ErrorCode` conventions (per-use-case hierarchy on `BaseError`,
shared `ErrorCode`s, mapped by `BaseErrorMapper`), plus the auth-layer 401s:

- `AUTHENTICATION_FAILED` → 401. Invalid login credentials (`POST /sessions`) **and** an unknown /
  invalid bearer token.
- `SESSION_EXPIRED` → 401. A structurally valid but expired bearer token. A distinct code so the
  client can tell "renew is too late, re-login" from "bad token". (An expired token cannot be
  renewed; renew requires a still-valid token.)
- `AUTHENTICATION_REQUIRED` → 401. No credentials on a protected route (existing
  `UnauthorizedException` behaviour), now challenging `WWW-Authenticate: Bearer`.

All emit RFC-7807 problem+json, consistent with the existing mappers.

## 9. Persistence & migration

- Table `session_tokens`: `id` (uuid PK), `user_id` (uuid, FK → users, indexed), `token_hash`
  (text, **unique**, indexed — the lookup key), `expires_at` (timestamp), `persistent` (boolean),
  plus the `BaseModel` `when_created` / `when_modified`. The `user_id` index serves
  `deleteAllForUser`; the unique `token_hash` index serves per-request verification.
- Only the SHA-256 **hash** of the token is stored; a DB leak never yields usable tokens.
- Ebean migration **1.8** (last shipped is 1.7, boards). Purely additive (new table); no backfill.
  Generated via `./gradlew :api-persistence-sqlite:generateDbMigration`.

## 10. Configuration

New `auth.*` block in `api-application/src/main/resources/application.properties`, snake_case with
ISO-8601 durations (matching `tasks.*`):

```
auth.persistent_ttl=P30D      # "keep me logged in"
auth.ephemeral_ttl=PT12H      # not remembered
auth.renew_threshold=0.75     # renew once 75% of the lifetime has elapsed
```

Surfaced by `AuthConfig` (`persistentTtl(): Duration`, `ephemeralTtl(): Duration`,
`renewThreshold(): Double`, with `@WithDefault`s) and consumed only through `SessionExpiryPolicy`.

## 11. Testing strategy

Strict TDD, 100% branch coverage per package, failing test first. Order per AGENTS.md: integration
(REST Assured) → use-case unit (MockK) → repository (Ebean).

**One reusable test-auth helper** (the operator's explicit constraint), on `IntegrationTest`,
generalising the currently-private `RequestSpecification.authenticatedAs` (today duplicated/inline
across 15 files):

```
protected fun createAuthenticatedUser(password: String = DEFAULT_PASSWORD): AuthenticatedUser
    // UserCreator.createUserWithPassword(...), then POST /sessions, capturing the token
protected fun RequestSpecification.authenticatedAs(user: AuthenticatedUser): RequestSpecification
    // sets Authorization: Bearer <token>
```

Every integration test migrates Basic → Bearer through this single seam
(`.auth().preemptive().basic(u, p)` → `.authenticatedAs(user)`).

Both sides of every conditional, in particular:

- Login: valid → 201 + token; bad password → 401; unknown user → 401; `rememberMe` true vs
  false/absent → the respective TTL.
- Verify: valid token → 200; missing → 401 `AUTHENTICATION_REQUIRED`; unknown/garbled → 401
  `AUTHENTICATION_FAILED`; expired → 401 `SESSION_EXPIRED` (drive the clock past `expiresAt`).
- Renew: valid → new token, old rejected afterwards (rotation); `persistent` preserved; expired
  token cannot renew. Atomicity: a use-case unit test asserting the new token is saved **and** the
  old deleted within the one `@Transactional` `renew`; if the save fails, the old row is not
  deleted (no half-rotation). Full rollback under real failure is the framework's `@Transactional`
  guarantee, not separately asserted.
- Read current: `GET /sessions/current` returns `{expiresAt, renewAfter, persistent}` for the
  presented token and **no** `token` field; `renewAfter` matches `renewAfterFor(expiresAt, persistent)`.
- Revoke: current only (other tokens survive) vs all (every token dead).
- `SessionExpiryPolicy`: `expiryFrom` and `renewAfterFor` for both `persistent` values
  (deterministic via a fixed clock).
- `GET /me` returns the caller's `{id, name}`.

## 12. Risks / open points

- **Distinct `SESSION_EXPIRED` from the auth layer.** Surfacing a specific 401 code from inside a
  Quarkus authentication failure (vs the generic challenge) needs the identity provider to
  distinguish expired from invalid and the mapper to honour it. The plan must nail the exact
  exception plumbing; if it proves disproportionate, falling back to a single `AUTHENTICATION_FAILED`
  is acceptable and should be flagged, not done silently.
- **Lost renew response.** As in §3: rotation means a dropped renew response can strand the client
  on a dead token → re-login. No grace window in v1.
- **Client without stored expiry.** Resolved: a client that persisted only the token string can
  call `GET /sessions/current` to recover `expiresAt` / `renewAfter` and know when to renew. It need
  not persist the timestamps from the login/renew response.
- **Expired-row growth.** No GC in v1 (rows are inert). Flag as P2 operational debt in the handoff.
- **Concurrency.** Two concurrent renews/logouts are last-writer-wins, consistent with the rest of
  the system; no locking in scope.
