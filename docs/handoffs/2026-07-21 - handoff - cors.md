# Handoff — CORS configuration

**Date:** 2026-07-21
**Branch:** `feat/cors` (in-place, off `main`)
**Spec:** `docs/specs/2026-07-21-cors.md` (no separate plan: config + one typed member + one test)
**Status:** Feature complete. Full local gate green (`./gradlew check koverVerify`, all modules,
`detekt` + `koverVerify` 100% branch coverage, JDK 25, libvips-backed image tests ran for real):
715 tests, 0 failures. Merge-ready pending CI (`validate / gate`).

## Why this exists

The next P1 "client ergonomics" item in `docs/backlog.md`, unblocked once the client-auth model was
fixed (Bearer, no cookies). No `quarkus.http.cors*` entry existed, so Quarkus allowed only
same-origin requests and every cross-origin call from a browser (the web SPA, later the extension)
was blocked.

## What this delivers

Quarkus CORS filter enabled with an explicit whitelist policy
(`api-application/src/main/resources/application.properties`):

- **Origins:** `api.cors.origins` (dev default `http://localhost:5173`, the typical Vite/SPA origin),
  forwarded to `quarkus.http.cors.origins` via `${api.cors.origins}`. Prod overrides `API_CORS_ORIGINS`.
- **Methods:** `GET,POST,PUT,DELETE` (the only verbs the controllers expose; no PATCH). The filter
  answers the `OPTIONS` preflight itself.
- **Request headers:** `Authorization,Content-Type` — neither is CORS-safelisted for our use (JSON
  writes send `application/json`; the Bearer token rides in `Authorization`), so without them every
  authenticated write would fail preflight. Listing them turns Quarkus's default header-mirroring
  into a whitelist.
- **Exposed headers:** `Location` (set on the 201 create responses and the 202 image-status
  response). `ETag` deliberately not exposed (images load via `<img>`, whose revalidation the browser
  handles natively; `exposed-headers` only governs the Fetch API).
- **Credentials:** off (defaults `false`) — auth is Bearer in a header, no cookies.
- **Preflight cache:** `access-control-max-age=PT24H`.

## Key decision — the public knob stays under `api.`

`api.cors.origins` is declared as a typed member on **`ApiConfig`** (`@ConfigMapping(prefix = "api")`),
a nested `Cors.origins()`:

- **Why under `api.`:** project convention is that everything publicly configurable lives under the
  `api.` prefix, so operators never touch framework-namespaced vars (`QUARKUS_HTTP_CORS_ORIGINS`).
- **Why declared in `ApiConfig` (not just a bare property):** `ApiConfig` claims the `api.` prefix as
  a strict `@ConfigMapping`. An unmapped `api.cors.*` property risks SmallRye's unknown-property
  validation at startup. Mapping it keeps the invariant "every `api.*` property is typed in
  `ApiConfig`" and passes validation.
- **The member is populated/validated at startup but not read by app code.** Quarkus's built-in CORS
  filter reads the framework property `quarkus.http.cors.origins` directly and cannot be fed from
  `ApiConfig`, so the `${...}` interpolation line is still required. The typed member's role is to
  keep the public config surface complete and validated. Abstract interface methods carry no
  branches, so this costs nothing in coverage.

## Pitfalls learned

- **Test config merges, not shadows.** `api-application/src/test/resources/application.properties`
  does not replace the main one; Quarkus merges them per-key. So the CORS block lives in main and
  applies to tests, and the test file only overrides `api.cors.origins=https://app.test` to pin a
  deterministic allowed origin. The `${api.cors.origins}` interpolation picks up the override.
- **Disallowed-origin assertion is header-absence, not status.** Quarkus may answer a disallowed
  preflight with 200-without-headers or 403 depending on version; `CorsIntegrationTest` asserts only
  that `Access-Control-Allow-Origin` is absent (the browser-relevant contract), so it is robust.
- **Header casing.** The tests use `containsStringIgnoringCase` for `Access-Control-Allow-Methods` /
  `-Headers` / `-Expose-Headers` because Quarkus echoes the configured (capitalised) header names.

## NOT validated

- **No real-browser end-to-end test.** Only the server-side header contract is asserted (via REST
  Assured preflight + actual-request tests). A real SPA doing a credentialed-in-header cross-origin
  `fetch` has not been exercised.
- **No prod origin exercised.** The dev default (`http://localhost:5173`) and the test origin
  (`https://app.test`) are the only origins tested; the `API_CORS_ORIGINS` prod override path is
  config-only, not integration-tested.
- **Regex origins untested.** Quarkus supports `/regex/` origin entries; none is used or tested here.

## Suggested next step

**Profile management** (the remaining P1 item): change password, delete account, and public profiles
if visibility lands. The **browser-extension CORS origin** is parked until the extension has a stable
ID (decision B1; recorded in the backlog).
