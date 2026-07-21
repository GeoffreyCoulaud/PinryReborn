# Handoff — Session-token authentication

**Date:** 2026-07-21
**Branch:** `feat/session-token-auth` (in-place; 20 commits on top of `main` @ `681ae9d`)
**Spec:** `docs/specs/2026-07-21-session-token-auth.md` · **Plan:** `docs/plans/2026-07-21-session-token-auth.md`
**Status:** Feature complete. Full local gate green (`build`, all modules, `detekt` + `koverVerify`,
JDK 25, libvips-backed image tests ran for real), 100% branch coverage held. Whole-branch holistic
review (opus) = **READY TO MERGE (Yes)**: no Critical, no Important. Merge-ready pending CI
(`validate / gate`).

## Why this exists

The first P1 "client ergonomics" item in `docs/backlog.md`: HTTP Basic forced every client (SPA,
browser extension) to store and resend the raw password. This replaces it with an opaque,
server-stored, revocable **session token** presented as `Authorization: Bearer <token>`. It also
unblocks the next P1 item (CORS), which is mechanical now that the client-auth model is fixed.

## What this delivers

- **Login (= credential check):** `POST /api/v1/sessions {name, password, rememberMe?}` → 201
  `{token, expiresAt, renewAfter}`. Bad/unknown credentials → 401 `AUTHENTICATION_FAILED` (collapsed,
  no enumeration). `rememberMe` (default false) picks a persistent (30d) vs ephemeral (12h) TTL.
- **Bearer everywhere else:** `GET /api/v1/me` (`{id,name}`); `GET /api/v1/sessions/current`
  (`{expiresAt, renewAfter, persistent}`, **no token** — lets a token-only client recover when to
  renew); `POST /api/v1/sessions/current/renew` (atomic rotation, preserves `persistent`);
  `DELETE /api/v1/sessions/current` (logout, 204); `DELETE /api/v1/sessions` (logout-all, 204).
- **Expiry metadata:** `expiresAt` (hard) + `renewAfter` (soft = `expiresAt - ttl × (1 - 0.75)`),
  ISO-8601 UTC. A polite client renews after `renewAfter` and stays logged in indefinitely without
  the password.
- **Security:** 256-bit `SecureRandom` token, only its **SHA-256 hash** stored; token never logged
  and absent from `GET /sessions/current`; `Cache-Control: no-store` on the two token-bearing
  responses; case-insensitive Bearer scheme (RFC 7235).
- **HTTP Basic fully removed** (`quarkus.http.auth.basic`, `BasicAuthIdentityProvider`,
  `WWW-Authenticate: Basic`). All 15 prior integration tests migrated to Bearer through one seam.
- **OpenAPI** declares an `http`/`bearer` `@SecurityScheme`; `docs/openapi.json` regenerated, coherent.

## Structure (per module)

- **api-domain:** `SessionToken(id, user, expiresAt, persistent)`, `IssuedSession`, `TokenGenerator`
  port, pure `SessionExpiryPolicy`, `SessionTokenRepositoryInterface`. (No `SessionTokenLogin` — see
  pitfall 4.)
- **api-persistence-sqlite:** `SessionTokenModel` (`@Table("session_tokens")`, FK `user_id`, unique
  `token_hash`), mapper, `SessionTokenRepository`, migration **1.8** (additive).
- **api-usecases:** `TokenHasher` (object, SHA-256), `SessionTokenAuthenticator` (verify),
  `SessionCreator`, `SessionRenewer` (`@Transactional` atomic rotation), `SessionRevoker`;
  `SessionAuthenticationError` (`SessionTokenInvalidError` / `SessionTokenExpiredError`).
  `UserAuthenticator` **unchanged** (password-only).
- **api-presentation-quarkus:** `BearerAuthenticationMechanism`, `BearerTokenIdentityProvider`,
  `SecureTokenGenerator`, `AuthConfig` + `AuthRuntimeProducers`, `SessionController`, `MeController`,
  DTOs + `SessionDtoMapper`, `getSessionToken()` extension, Bearer challenge, cause-inspecting
  `AuthenticationFailedExceptionMapper`, `@SecurityScheme` holder.
- **api-application:** `auth.*` config; unified `IntegrationTest.createAuthenticatedUser` /
  `authenticatedAs`; 15 migrated + `SessionAuthIntegrationTest` (11 E2E); regenerated OpenAPI.

## Learned pitfalls (read before touching this again)

1. **SESSION_EXPIRED plumbing (the hard part).** `AuthenticationFailedException` is **`final`** in the
   resolved Quarkus (`quarkus-security` 2.3.2), so the distinct code cannot be a subtype. A subtype of
   `AuthenticationCompletionException` was tried and, per the Task 9 integration test, **does not route**
   to the JAX-RS mapper chain at runtime (`IllegalStateException: no content-type defined` → bodyless
   401). Shipped design: `BearerTokenIdentityProvider` throws
   `AuthenticationFailedException(msg, cause = SessionTokenExpiredError|SessionTokenInvalidError)` and the
   **shared** `AuthenticationFailedExceptionMapper` inspects the cause (`is SessionTokenExpiredError` →
   `SESSION_EXPIRED`, else `AUTHENTICATION_FAILED`). This coupling is **by-convention** (provider sets
   cause, mapper reads it), not compiler-enforced; it is guarded by three tests (mapper unit both
   branches, provider unit both branches, aged-token E2E). **Do not break the cause link** without
   updating all three.
2. **The custom `HttpAuthenticationMechanism` is Quarkus-version-sensitive** (esp. `getCredentialTransport`).
   Matched the plan as-is against Quarkus 3.37.1. If you bump Quarkus and the module fails to compile
   here, adjust the override signatures to the compiled interface (context7 for the exact API).
3. **Kover data-class trap:** `SessionToken.persistent` and `ExistingSessionOutputDto.persistent` have
   **no default**; `SessionCreationInputDto.rememberMe` is **nullable, no default** (coalesced to false
   in the controller). A non-null default (`= false`) generates a synthetic constructor branch the 100%
   gate flags.
4. **Token auth is a dedicated `SessionTokenAuthenticator` (option b), not a `Login` subtype.** Routing
   tokens through `UserAuthenticator.authenticate(login): User` is lossy (Bearer needs the full
   `SessionToken` for renew/revoke/current), and the extra `Login` branch would be dead in prod. So
   `Login`/`UserAuthenticator` stayed password-only. No dead code.
5. **OpenAPI:** `quarkus.smallrye-openapi.security-scheme=jwt` would mislabel these **opaque** DB tokens
   as JWT (`bearerFormat: JWT`). The MicroProfile `@SecurityScheme(type=HTTP, scheme="bearer")` on an
   inert anchor class was used instead. Keep it if you touch OpenAPI.

## NOT validated against real environment / hardware

- **CI has not run yet.** Green only on the local JDK-25 gate (with real libvips).
- **Concurrency:** two concurrent renews/logouts are last-writer-wins (as with tags/boards); unlocked.
- **No real-world token volumes** exercised; per-request verification is one indexed lookup on
  `token_hash`.
- **The cause-inspection contract** is convention-based (test-guarded), not type-enforced (pitfall 1).
- **Lost renew response edge:** atomic rotation means a dropped renew response strands the client on a
  dead token → re-login. No grace window in v1 (accepted, spec §3).

## Suggested next step

**CORS** (the next P1 item) is now unblocked and mechanical: add `quarkus.http.cors*` for the SPA /
extension origins with credentialed Bearer requests. Then **profile management** (change password,
delete account). Operational debt (P2): the **expired-row GC sweep** (rows are inert but accumulate;
no GC in v1) and a small DRY refactor (`SessionCreator`/`SessionRenewer` share the
`generateToken → expiryFrom → save → IssuedSession` assembly; extract a `SessionIssuer` if a third
caller appears). The parked visibility/sharing model remains parked.
