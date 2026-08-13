# 0013. In-memory authentication attempt limiting

Status: Proposed
Date: 2026-08-13

## Context

Three use cases verify a password and answer as often as asked: `UserAuthenticator` behind
`POST /api/v1/sessions`, `Reauthenticator` behind the step-up header, and `PasswordChanger` behind
`PUT /api/v1/me/password`. The backlog recorded this as the one security gap deliberately left open,
and named what it would take to close it: "a per-user failure counter, its expiry, its behaviour
across instances".

Two of those three are design questions rather than code. Where the counter lives decides whether
this lot carries a migration, a repository and a garbage collection sweep, or none of them. What the
counter is keyed by decides who can be denied service by whom.

The existing minimum interval on password change is not a partial answer to any of this: it reads
the current hash's `createdAt`, so it counts successful changes and a failed attempt writes nothing
(`docs/specs/2026-07-31-current-password-determinism.md`, D10).

## Decision 1: the counters live in process memory, behind no port

One `ConcurrentHashMap` inside `AuthenticationAttemptLimiter`, in `api-usecases`, with the policy
supplied as constructor parameters and `Clock` injected. No table, no migration, no repository
interface, no adapter.

The alternative was a SQLite table with a periodic sweep, on the ADR 0003 pattern. It buys one
property: counters that survive a restart. It costs a migration, a repository, a garbage collection
job, and a disk write on every failed attempt, which hands an unauthenticated caller a write
primitive on the database. Trading a database write per failure for the ability to survive a process
restart is the wrong side of the trade, because an attacker who can restart the process has already
won.

**The store is what settles the multi-instance question, not this decision.** The database is a
SQLite file; more than one instance is already excluded. When that changes, the counters are one of
several things that change with it, and extracting a port then is a smaller job than carrying one
now for a deployment that does not exist.

**Why no port at all.** The project puts its ports in `api-domain` and its adapters in `api-system`:
`Clock`, `PasswordHasher`, `TokenGenerator`. Those three name a resource outside the process: the
wall clock, a crypto library, a random source. Process memory is not one. A port here would have one
implementation, no seam worth testing at the boundary, and would spread a policy across two modules
to no end. The limiter is a use case that happens to remember something.

Accepted limits, all of them consequences of this decision:

- Counters reset on restart, and a deployment that restarts often weakens the limit accordingly.
- The limiter is per process. It is correct only while the deployment is one instance, which the
  SQLite store enforces today and nothing checks.

## Decision 2: keyed by identity, not by network origin

The login key is the submitted name, lower-cased, counted whether or not that user exists. The
authenticated key is the user id, in a separate key space.

Keying by IP address was considered and rejected. It does not stop a distributed attack, and it
requires trusting a forwarded-for chain, which means a deployment contract (which proxy, which
header, how many hops) that this project does not have and would then have to document, validate and
keep true. A wrong answer there is worse than no answer: a spoofable key lets an attacker pick a
victim's bucket.

Counting names that do not exist is not incidental. `UserAuthenticator` pays a dummy bcrypt hash so
that a missing user and a wrong password take the same time (`UserAuthenticator.kt:26`). A limiter
that only counted real accounts would answer 429 for existing names and 401 for the rest, which is
the enumeration oracle that dummy hash exists to deny.

Accepted limit: **a third party can degrade an account's login**, by failing on its behalf. Decision
3 is what bounds the damage.

## Decision 3: escalating backoff, never a lockout

Five consecutive failures, then a block whose length walks up a configured list of steps and
saturates on the last one. A success clears the counter, and an idle counter is forgotten after a
configured interval.

The usual shape, N failures then a fixed lockout window, hands anyone a free denial of service of
that window's length against any account whose name they know. The backoff makes the same attack
cost the attacker a sustained stream of requests to hold, and lets the legitimate owner back in as
soon as they stop, at a cost that starts at 30 seconds. Against brute force the two shapes are
equivalent: both cap the guess rate far below what a password search needs.

The check runs before the password verification. A limiter placed after it would still let a flood
of guesses pay for a bcrypt hash each, which is the CPU exhaustion half of the same problem.

Accepted limit: **the check and the record are two operations**, so a request already past its check
when the blocking failure lands still pays for its bcrypt hash. The ceiling on hashes per key is the
threshold plus whatever is in flight, and the "no bcrypt for a blocked key" rule holds for requests
arriving after the block is recorded, not for those already inside.

Accepted limit: **grouping is wider than the database's.** Kotlin's `lowercase` folds Unicode, while
the `collate nocase` index folds ASCII only, so two accounts SQLite considers distinct can share one
counter. Wider grouping is the safe direction for brute force and marginally widens decision 2's
limit.

## Decision 4: the tracked-key count is bounded, with eviction

The login key is attacker-supplied and unbounded in cardinality, so the map is bounded and evicts:
expired entries first, then the entry closest to expiry.

Accepted limit: **eviction is a bypass, and a cheap one to describe but not to run.** An attacker who
floods the map with distinct names can push a victim's entry out, or their own. Every entry they
create costs the server one bcrypt hash and them one request, so filling a ten thousand entry bound
means ten thousand hashed attempts. The unbounded alternative is a memory exhaustion path reachable
without credentials, which is worse, and the CPU cost of the flood is a pre-existing property of the
login endpoint that this lot does not change.

## Consequences

- The limiter is the first stateful component in `api-usecases`. That is the seam a future
  multi-instance deployment breaks, and it breaks loudly rather than silently: correctness is stated
  here, not enforced anywhere.
- No migration, so the beta-time migration flattening (`docs/backlog.md`) is not affected.
- 429 responses gain a third source, all three rendered by one marker interface: the arrangement
  D23 of `docs/specs/2026-07-31-current-password-determinism.md` set up pays off again.
- `Retry-After` now rounds up at all three sources, through `ThrottledError.wholeSecondsBetween`:
  the two older ones truncated, which sent a client back a second early into a second 429. Operator
  decision, taken during this lot; the earlier documents describe the floor they shipped with.
- `PasswordChanger` keeps verifying the current password itself rather than calling
  `Reauthenticator`. Both now call the limiter with the same key, so the duplication costs one more
  call site than the refactor would; removing it stays available and stays out of this lot.
- The backlog item moves out of "Before beta". What remains open in it is nothing: the gap it
  described is closed, and the limits above are recorded here rather than copied there.
