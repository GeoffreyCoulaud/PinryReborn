# The P2 band empties: twenty-three items, ten blocks, and the regime they change

Date: 2026-09-05
Status: Draft, awaiting the operator's review; one adversarial review closed (section 11)
Branch of block 1: `docs/p2-debt-regime`
ADRs: `docs/adr/0020-two-reviews-and-an-inline-act.md` (block 1),
`docs/adr/0021-framework-refusals-share-the-problem-format.md` (block 6),
`docs/adr/0022-a-lost-lease-is-an-exception.md` (block 9)
Amends: `docs/adr/0018-a-block-is-a-pull-request.md` (decisions 4 and 8) and
`docs/adr/0019-review-before-the-pull-request.md` (the block review and its mandate)

## 1. Goal

Close every item in the `P2: Operational debt` band of `docs/backlog.md` as it stands on
2026-09-05, by three of the four exits `docs/adr/0010-review-finding-dispositions.md` names: fixed
in this lot, accepted limit written where the decision lives, or refused with the reason in the
handoff. The fourth exit, the backlog, is closed to them: none becomes another lot's. Twenty-three
items, read from the file:

```
$ awk '/^### P2/,/^## Known limits/' docs/backlog.md | grep -c '^- \*\*'
23
```

The questions put to the operator in Discuss, one at a time, and their answers are section 3. Every
design choice below derives from one of them.

## 2. What the band holds

| # | Item, as the backlog names it | Exit | Block |
|---|---|---|---|
| 1 | `PinModel` and `BoardModel` unfenced, `exports` package without static guard | Fixed | 7, 8 |
| 2 | A task handler is never told it lost its lease | Fixed, ADR 0022 | 9 |
| 3 | `claimNext` kills a task whose handler may still hold a live lease | Fixed by 2, see 4.7 | 9 |
| 4 | Two defects in the import half (KDoc, sweep outside `safe`) | Fixed | 2 |
| 5 | The export reclaim pass has no order | Fixed | 3 |
| 6 | `ReapExpiredUserDataExports` runs three passes under a name that says one; `DIRECTORY` rivals | Fixed | 2 |
| 7 | The export test fixtures close only one direction | Fixed | 4 |
| 8 | `TaskQueueBootIntegrationTest` counts every row in `tasks` | Refused, handoff | 9 |
| 9 | `MeExportController` declares no `@APIResponse` | Fixed | 5 |
| 10 | A body that fails to deserialize gets Quarkus's own `400` | Fixed, ADR 0021 | 6 |
| 11 | `EbeanTaskQueue.reapExpired` selects every expired lease at once | Fixed | 3 |
| 12 | `ImportStateMergedOutsideTransaction` reads a construction as an insert | Accepted limit, rule KDoc | 8 |
| 13 | The tag respelling is a contract change nobody published | Fixed | 5 |
| 14 | An absent `offset` defaults to 0, undocumented | Fixed | 5 |
| 15 | The backlog compression lost an argument and mis-resolved three pointers | Closed by this document, see 4.1 | 1 |
| 16 | Decisions 4 and 5 of ADR 0018 cannot both hold for an over-budget block | Fixed, ADR 0020 | 1 |
| 17 | A specification freezes at delivery and the regime requires it to keep changing | Fixed, ADR 0020 | 1 |
| 18 | A holistic finding against an already merged block has no legitimate exit | Fixed, ADR 0020 | 1 |
| 19 | The ADR-existence check was deleted with `agents/reviews/plan.md` | Fixed, `agents/reviews/spec.md` | 1 |
| 20 | `AGENTS.md` says no local command covers the image build or the OpenAPI sync | Fixed | 1 |
| 21 | Both files describe a `P0` band the backlog does not have | Fixed | 1 |
| 22 | ADR 0018 has no back-link from the ADRs it amends; ADR 0014 is still `Proposed` | Fixed | 1 |
| 23 | Measure what review costs and what it returns | Refused, handoff | 9 |

Items 8 and 23 are deleted in block 9, whose diff carries the handoff that records the refusal
(D12): the item and its reason leave in the same pull request.

**The `P1` band is not adjacent.** Its two items, the browser-extension CORS origin and the import
follow-ons, share no file, no use case and no decision with anything above. Stated here because ADR
0018 decision 6 requires a lot to say so.

## 3. Decisions

Each is the operator's answer, with the alternative it rejected. This section is the record of
those answers; nothing else in the repository holds them.

- **D1. Two adversarial reviews and no block review.** One review of the specification, one holistic
  review of the whole lot. Each pull request is read by the human alone. The commit `b3a8671a` of
  2026-09-05 deleted `agents/reviews/` entirely while `agents/workflow.md:89` still says "re-run the
  block review"; the operator's working tree already drops the `agents/reviews/*` cell from
  `agents/writing.md`'s table (`git diff agents/writing.md` on the branch). This decision is what
  the deletion meant, and it is the claim the whole lot rests on: it lives only here and in the
  operator's reading of this section. Rejected: no automatic review at all; the ADR 0018/0019 regime
  restored.
- **D2. The review-cost measurement is abandoned.** The decision it would inform is taken. Exit:
  refused, reason in the handoff. Rejected: a measuring block in this lot; leaving the item open.
- **D3. A lost lease is an exception.** `renewLease()` throws `TaskLeaseLostException` when the queue
  answers `false`; `claimNext` and the `PT6H` grace do not change. Rejected: `() -> Boolean`, which
  breaks both handlers at compile time and invites the swallowing lambda the export spec warned of.
- **D4. The fence rule reaches the whole module.** One generic fence helper, the three pin and board
  sites on it, and the detekt rule renamed, matching `save*`, over all of `api-usecases`. Rejected:
  widening to the `exports` package only, which would leave the sites this lot fences unguarded.
- **D5. Sweep selections page by key.** Ordered by `id`, looped page after page until an empty one,
  so a row whose action is refused is passed by the cursor. The import twin takes the same shape.
  Rejected: an order alone (deterministic stall); a "last refusal" column (a migration).
- **D6. The error format is completed**, not only the malformed body: unknown route, method, media
  type, oversize body and the fallback all answer `application/problem+json`. Rejected: the
  `JsonProcessingException` mapper alone.
- **D7. `TaskQueueBootIntegrationTest` stays refused.** Exit: refused, with the reason in the
  handoff. Rejected: the two-line fix; leaving the item open.
- **D8. The construction-versus-rebuild blindness is an accepted limit**, recorded in the rule's
  KDoc and pointed at from the backlog's Known limits band. Rejected: detekt type resolution.
- **D9. Both sweeps are renamed** to `ReapUserDataExports` and `ReapUserDataImports`, and the three
  path segments get one home, `StorageLayout` in `api-domain`.
- **D10. Act is always inline.** ADR 0018 decision 4's sentence "a block that exceeds the budget
  anyway may still be dispatched" is removed. Rejected: the question as the subagent's return value;
  degrading tier 2 to tier 3 inside a dispatched block.
- **D11. A lot's dated documents freeze when its last block merges.** Earlier pull requests may
  correct them in the `(Corrected: ...)` form. Rejected: keeping the spec off every pull request but
  the last; a new dated document per correction.
- **D12. Holistic findings become a closing block**, with its own pull request, and the handoff
  counts them. Rejected: fixing them inside the last code block before its pull request; the backlog.
- **D13. Ten blocks in series**, one pull request each (section 6).

## 4. Design

### 4.1 The regime: ADR 0020, two mandates, and the living documents

**ADR 0020** records D1, D10, D11 and D12 and amends ADR 0018 decisions 4 and 8, and ADR 0019's
placement of a block review. It carries one correction the regime needs to state: with the holistic
review running after the last code block, that block writes the handoff so the review reads it, and
the closing block corrects the handoff, which D11 allows.

**Two mandates return under `agents/reviews/`.** `spec.md` merges the deleted `evidence.md` and
`falsifiability.md` (recoverable at `b3a8671a~1`) and takes the ADR-existence check that
`agents/reviews/plan.md` lost at `7e89996e`: a specification that picks a library, a storage format,
a protocol, a boundary or an error contract settles an architectural question, and the one-line
justification `agents/workflow.md` accepts for an absent ADR is tested against that list.
`holistic.md` is the deleted one without its references to a block review and to a pull request.
`agents/writing.md`'s table gets its `agents/reviews/*` cell back, the directory returning with
these two files; the operator's uncommitted removal of that cell is carried by block 1's branch and
superseded there.

**`agents/workflow.md`** loses "may still be dispatched" from phase 3, says in phase 4 that the
holistic review runs on the lot after the last code block and that its findings are a closing block,
drops "the block review" from phase 5, and states four bands in the backlog section: Open work
(`P0`, `P1`, `P2`; a priority may hold nothing), Known limits, Before beta, Features. **`agents/writing.md`**
takes D11. **`AGENTS.md`** line 9 names the two reviews, and its CI sentence becomes: the gate covers
neither the image build nor the OpenAPI sync; the `pre-commit` hook regenerates `docs/openapi.json`,
and `ImportDataDirectoryImageTest` and `ExportDataDirectoryImageTest` read the Dockerfile's ownership
lines from inside the gate.

**Status lines.** They are the one edit a frozen ADR admits, and the precedent is ADR 0001's line
naming ADR 0014. ADR 0014 becomes `Accepted`, amended by 0018, 0019 and 0020; ADRs 0016 and 0017,
both `Proposed` and both in force (this lot builds D3 and D4 on them), become `Accepted`; ADR 0010
and ADR 0001 name 0018 and 0020; ADR 0018 and 0019 name 0020.

**Item 15 closes here**, not by editing the frozen documents it faults. The block review of
`d644257f` found that the `renewLease` item lost the two sentences saying why the work matters. They
are section 4.7's first paragraph now, in a dated document. The pointers it found wrong vanish with
the items they belong to. The "six of fourteen" figure at line 47 of
`docs/handoffs/2026-09-04 - handoff - blocks-as-pull-requests.md` is four, as ADR 0019 line 113
already says, and ADR 0020's context repeats it. The two-line rule's exception is in
`agents/workflow.md:108-110` since `b3a8671a`.

### 4.2 The sweeps

**Names.** `ReapExpiredUserDataExports` becomes `ReapUserDataExports`; `ReapAbandonedUserDataImports`,
which runs four passes under a name that says one, becomes `ReapUserDataImports`. The `Reap` prefix
stays, being the one `ReapExpiredSessionTokens`, `ReapOrphanedStorage` and `ReapTombstonedAccounts`
carry. Test classes, producers, lifecycles and the KDocs that cite either follow.

**`StorageLayout`.** An `object` in `api-domain`, next to `StagedFile`, holding the three segments:
`STAGING_DIRECTORY = "tmp"`, `EXPORTS_DIRECTORY = "exports"`, `IMPORTS_DIRECTORY = "imports"`. The
segments are spelled ten times under `*/src/main` today, and every one becomes a read of the object:

| Spelling | Where |
|---|---|
| `"tmp"` | `FilesystemImageStore.kt:38`, `FilesystemZipExportArchiveStore.kt:42`, `FilesystemZipImportArchiveStore.kt:37`, `ExportDataDirectoryCheck.kt:23` |
| `"exports"` | `ExportArchiveKey.kt:11`, `FilesystemZipExportArchiveStore.kt:111` |
| `"exports/"`, `"imports/"` | `ReapOrphanedStorage.kt:95-96` |
| `"imports"` | `FilesystemZipImportArchiveStore.kt:169` |
| `"imports/$importId.zip"` | `ImportArchiveKey.kt:10` |

The domain stays pure: a constant is not I/O, and `api-storage-filesystem` and `api-worker-quarkus`
both depend on `api-domain`, so every reader reaches it.

**Import hygiene.** `ReapAbandonedUserDataImports.reap()`'s KDoc says abandonment runs first because
"it is what makes a row holding a promoted archive terminal"; `abandonStaleUploads` selects rows with
no `storageKey` yet, and it is `failInterruptedRuns` that makes a key-holding row terminal. The
sentence is corrected. `ImportLifecycle.start()` calls `reap()` bare where `ExportRetentionLifecycle`
calls `safeReap()`: a sweep that throws at boot ends the boot. It takes `safeReap()`, with the test
the export twin has and the import lacks (`ExportRetentionLifecycleTest` has eight cases,
`ImportLifecycleTest` seven; the missing one is "a reap that throws at startup").

**Paged selections (D5).** Every sweep selection that a refused action leaves in place takes the
shape `find...(afterId: UUID?, limit: Int)`, ordered by `id`, and the pass loops until a page comes
back empty. The rows a page acts on drop out of the predicate; the rows it fails on are behind the
cursor. One sweep therefore drains the table with the page as its memory bound. The convention found
insufficient is `exports.sweep_batch_size` as the export completion lot applied it
(`docs/specs/2026-08-27-export-build-completion.md` line 206): a bound with no order, which caps a
sweep's memory and lets a refused row hold the head for good. The selections concerned, with their
state today:

| Selection | Today |
|---|---|
| `UserDataExportRepository.findReclaimableTerminal(limit)` | bounded, no order |
| `UserDataExportRepository.findExpiredReadyExports(now)` | no bound, no order |
| `UserDataImportRepository.findReclaimableTerminal()` | no bound, no order |
| `UserDataImportRepository.findRunning()` | no bound, no order |
| `UserDataImportRepository.findAbandonableBefore(instant)` | no bound, no order |

Verified by `grep -n "setMaxRows\|orderBy" api-persistence-sqlite/.../UserDataImportRepository.kt`,
which returns nothing, and by the export repository's source. `findPending(limit)` is left as it
is: it is ordered by `requestedAt` for a reason its KDoc gives, and the grace filter applied after
it is what keeps a row in place, not a refusal. `ImportsConfig` gains `imports.sweep_batch_size`
(default `500`, the export's figure) so both sweeps are bounded by the same kind of key; a constant
would be a bound nobody can raise when a large instance needs it, and the precedent is the export.

**`reapExpired` bounded.** `TaskQueueInterface.reapExpired` takes a `limit`, `EbeanTaskQueue` applies
`setMaxRows`, and `ReapExpiredTasks` passes a constant `REAP_BATCH_SIZE = 500`. A constant rather
than a key: the reaper runs every `lease_duration / 2` (`TaskWorkerLifecycle.start`, thirty seconds
at the default), and a bound is only ever reached after a crash left many rows `RUNNING`. No paging:
the selection acts on every row it reads, and the single transaction makes the batch all or nothing.

### 4.3 The export fixtures

`UserDataExportBuilderFixtures` extends `UserDataExportFakeStoreFixtures`, so a case driven by the
mock store also sees `fakeArchiveStore`, and an assertion on it passes vacuously. The shape that
closes both directions is one base and two siblings:

- `UserDataExportFixtures`: the repositories, the clock, the rows, `stubRow`, `stubRowWrites`,
  `deleteWhen`, `eraseWhen`, the pin, image and board factories, `builderOver(store)`.
- `UserDataExportFakeStoreFixtures : UserDataExportFixtures`: `fakeArchiveStore`,
  `fakeStoreBuilder`, `rivalPublishes`, `stubFakeStoreBuild`.
- `UserDataExportMockStoreFixtures : UserDataExportFixtures`: `archiveStore` as a mock, `builder`,
  `sink`, `stageCalls`, `stubArchiveStore`, `stubFailingStage`, `stubBuildEntry`,
  `stubBuildToStaging`, `stubHappyPathBuild`.

Each test class extends exactly one sibling. No case changes: `UserDataExportBuilderTest` uses no
fake-side member and `UserDataExportCompletionTest` no mock-side member, and the move is proved by
the test count before and after, as `docs/specs/2026-08-12-p2-debt-triage.md` T8 did.

### 4.4 The HTTP contract

**`MeExportController`.** SmallRye reads the status off the return type, and a runtime
`ResponseBuilder` carries none, which `MeImportController`'s comment already records. Today:

```
$ jq -r '.paths["/api/v1/me/exports"].post.responses | keys' docs/openapi.json
["200","401","403"]
```

The endpoint answers `202`. Every status the class answers is declared, in the import controller's
shape, one `@APIResponse` per status with the codes it carries in the description:

| Endpoint | Statuses and codes |
|---|---|
| `POST /api/v1/me/exports` | `202`; `400 UNSUPPORTED_REAUTHENTICATION_FACTOR`; `403 REAUTHENTICATION_FAILED`; `409 EXPORT_ALREADY_IN_PROGRESS`; `429 EXPORT_TOO_SOON` with `Retry-After` |
| `GET /api/v1/me/exports` | `200` |
| `GET /api/v1/me/exports/{id}` | `200`; `403 EXPORT_INSUFFICIENT_PERMISSIONS`; `404 EXPORT_DOES_NOT_EXIST` |
| `GET /api/v1/me/exports/{id}/download` | `200`; `206`; `403`; `404`; `409 EXPORT_NOT_READY`; `410 EXPORT_GONE`; `416` |
| `DELETE /api/v1/me/exports/{id}` | `204`; `403`; `404` |

The codes come from the use cases (`grep -n "throw " api-usecases/.../exports/*.kt`) and from
`ReauthenticationHeader`. The `401` every `@Authenticated` endpoint carries is SmallRye's own and
stays.

**The tag fold.** `PUT /api/v1/pins/{pinId}/tags` answers the stored spelling: with `landscape`
stored, `Landscape` creates no second tag and comes back as `landscape`
(`docs/specs/2026-08-14-user-data-import.md` section 12). `docs/openapi.json` publishes the endpoint
with SmallRye's method-name summary, `Set Tags`, and no description. It gains an `@Operation` whose
description states the ASCII fold, its limit (`collate nocase` folds A to Z and nothing else, so
`ÉTÉ` and `été` stay two names), and that the response carries the stored spelling.

**The chunk offset.** `PUT /api/v1/me/imports/{id}/archive` reads `offset` as `Long?` and substitutes
`0`. The parameter takes `@DefaultValue("0")` and an `@Parameter` description saying an absent offset
means the start of the upload. Nothing observable changes; the default becomes published, and the
`@DefaultValue` makes the substitution the framework's rather than a line in the method.

### 4.5 The error format

`agents/engineering.md` promises one error format "including framework-generated responses". Today
five mappers exist (`UnauthorizedException`, `AuthenticationFailedException`,
`ConstraintViolationException`, `RangeNotSatisfiableException`, `BaseError`) and nothing else is
mapped. The family becomes:

| Exception | Status | `code` |
|---|---|---|
| `JsonProcessingException` | `400` | `MALFORMED_BODY` |
| `jakarta.ws.rs.NotFoundException` | `404` | `UNKNOWN_ROUTE` |
| `jakarta.ws.rs.NotAllowedException` | `405` | `METHOD_NOT_ALLOWED` |
| `jakarta.ws.rs.NotSupportedException` | `415` | `UNSUPPORTED_MEDIA_TYPE` |
| `jakarta.ws.rs.NotAcceptableException` | `406` | `NOT_ACCEPTABLE` |
| Any other `WebApplicationException` | its own status | `HTTP_ERROR` |
| `IOException` | `500` | `INTERNAL_ERROR`, `detail` null, logged at DEBUG |
| Any other `Throwable` | `500` | `INTERNAL_ERROR`, `detail` null, logged at ERROR |

**Two tables, not one.** `agents/engineering.md` says "Status codes come from one table,
`BaseErrorMapper.statusFor`, a `when` over `ErrorCode` with no `else`". That table maps domain
errors, and the codes above are not domain errors: the status is the framework's, decided before any
use case ran. They live in a second table, `enum class FrameworkErrorCode` in the `mappers` package,
one member per mapper, and `ConstraintViolationExceptionMapper`'s `VALIDATION_ERROR` string joins it
so the presentation layer holds every framework code in one place. `agents/engineering.md`'s
sentence is amended in block 6 to name both tables, in the same commit (`agents/writing.md`,
living regime). **ADR 0021** records the contract, the two tables, and the library setting below.

Three facts from the Quarkus source and documentation shape the rows (`docs/src/main/asciidoc/rest.adoc`,
"Exception handling" under Jackson; `RuntimeExceptionMapper.java`, `mapException` and
`doGetExceptionMapper`):

- Quarkus REST ships `BuiltinMismatchedInputExceptionMapper`, a `400` on `MismatchedInputException`,
  a subclass of `JsonProcessingException`. Resolution takes the exact class first and walks up the
  hierarchy second, so a mapper on the parent never sees a `MismatchedInputException` while the
  built-in one is registered. The property
  `quarkus.rest.exception-mapping.disable-mapper-for=io.quarkus.resteasy.reactive.jackson.runtime.mappers.BuiltinMismatchedInputExceptionMapper`
  removes it, and is set in `application.properties` with that reason.
- A `WebApplicationException` whose response already carries an entity short-circuits every mapper.
  One without an entity goes through `doGetExceptionMapper`, which walks the hierarchy for every
  class whatever a comment above it says, so the umbrella row is reachable by an entity-less subclass
  and stays. The four named exceptions get their own mapper because each carries its own code, not
  because the umbrella could not see them.
- An unmapped `IOException` is logged at DEBUG by Quarkus itself, "the client likely terminated the
  connection". A `Throwable` mapper would intercept it; the family keeps that log level for it and
  reserves ERROR for the rest.

The `500` carries no `detail`: "secrets never reach an error payload", and an unmapped throwable's
message is nobody's to publish.

**The oversize body is measured before it is mapped.** `quarkus.http.limits.max-body-size` is `32M`
in `application.properties`. Whether the `413` it produces reaches a JAX-RS mapper or is answered
below it is not established by the documentation consulted. The block sends a body one byte over and
reads the response: a mapper if it can see it, an accepted limit written here otherwise, and
`MeImportController`'s `413` on `IMPORT_ARCHIVE_TOO_LARGE` stays the client's documented refusal
either way, since `imports.max_chunk_bytes` sits under the HTTP bound on purpose.

**`ProblemResponses.kt`** holds two top-level functions and a top-level constant, and
`MediaTypes.kt` a second constant, against `agents/engineering.md`'s "no top-level functions"
(`grep -rn "^fun \|^const " api-presentation-quarkus/src/main`; the `SecurityIdentity` extensions it
also lists are the rule's stated exception). They move into an `object ProblemResponses`. The
callers are the mappers, which this block touches anyway.

### 4.6 The fences and the rule

**Three unfenced sites**, all reached from one owner's two concurrent requests:

| Site | Read | Written | What a lost update restores |
|---|---|---|---|
| `PinTagger.setTags` | `findPinById` | `savePin(pin.copy(tags, updatedAt))` | `softDeletedAt = null` over a soft delete, `boards` over a concurrent `setBoards` |
| `PinBoardSetter.setBoards` | `findPinById` | `savePin(pin.copy(boards, updatedAt))` | the same, `tags` over a concurrent `setTags` |
| `BoardUpdater.update` | `getActiveBoardForUser` | `saveBoard(board.copy(name, description, updatedAt))` | `softDeletedAt = null` over a soft delete |

`PinRepository.savePin` writes the model from the domain copy, `softDeletedAt` included
(`PinModelMapper.kt:22`), then rewrites both link tables from the copy's lists. `softDeletePin` and
`restorePin` re-read the model themselves before writing one column, and are not sites. The seven
entities with no dangerous pair are listed in `docs/specs/2026-08-15-export-row-fencing.md` section
8 and are not re-derived here.

**One helper.** `ExportAccess.saveFenced` and `ImportAccess.saveFenced` are the same nine lines at
two types, and their `Over` variants likewise. They become delegations to two generic functions in
`api-usecases`, extensions on `TransactionRunner`:

```kotlin
internal fun <T : Any> TransactionRunner.fenced(
    read: () -> T?, held: (T) -> Boolean, update: (T) -> T, write: (T) -> T,
): T? = inTransaction { read()?.takeIf(held)?.let { write(update(it)) } }

internal fun <T : Any> TransactionRunner.fencedOver(/* same */): T? =
    inTransaction { read()?.takeIf(held)?.also { write(update(it)) } }
```

The four existing extensions keep their names and call sites and become one line each, their
`write` spelled as a lambda, `{ save(it) }`, for the reason the rule paragraph gives.
`PinRepositoryInterface` and `BoardRepositoryInterface` get the same one-line extensions,
`saveFenced(transactionRunner, id, held, update)`, and the three sites take them with the predicate
`softDeletedAt == null`. A refused fence at those sites is the refusal the site already has for a
soft-deleted row (`PinTaggingSoftDeletedPinError`, `PinBoardSettingSoftDeletedPinError`,
`BoardRetrievalBoardDoesNotExistError` through `getActiveBoardForUser`, whose `findActiveBoardById`
already answers null for a recycled board), thrown from inside the fence rather than before it; a
row gone entirely is the site's "does not exist" error. No client-visible status changes, and the
window in which the refusal is decided closes.

**The rule.** `ImportStateMergedOutsideTransaction` becomes `RowMergedOutsideTransaction`: the file,
its test, `PinryRuleSetProvider`, the `detekt.yml` entry, whose `includes` becomes
`['**/api-usecases/**']`, and the one inline suppression that names it
(`UserDataImportRunner.kt:502`). Its callee test `endsOnName() == "save"` becomes a match on `^save`,
so `savePin`, `saveBoard`, `saveTag`, `saveUser` and `saveSessionToken` are seen. Its
`singleOrNull()` argument rule stays: `saveUserPasswordHash(user, hash)` and
`saveSessionToken(token, hash)` take two, name no row that was read, and are not the shape.

**The rule and the helper.** The rule's transaction test is lexical: it walks the PSI parents of the
`save*` call for a call named `inTransaction`. A write handed to `fenced` as `{ save(it) }` has no
such parent and would be reported six times, once per delegation; handed as `::save` it is a
callable reference the rule never visits, and the fenced writes would leave its sight. The rule
therefore gains `fenced` and `fencedOver` as transaction-boundary names beside `inTransaction`, and
the delegations spell their write as a lambda so the rule keeps seeing a call. A `save*` callable
reference passed anywhere is outside the rule's sight, today and after; the KDoc says so beside the
construction blindness. The boundary names are spellings, as `inTransaction` already is: a function
named `fenced` elsewhere would be taken for the helper, which is the price of a rule without types.

**What the widened rule reports on the module as it is**, read site by site against the rule's own
tests (`^save` callee, one argument, argument not a construction, no boundary call among the lexical
parents), from `grep -rn "\.save[A-Za-z]*(" api-usecases/src/main`:

| Site | Why reported | Disposition |
|---|---|---|
| `PinTagger.kt:30` `savePin(updatedPin)` | named local, no transaction | fenced (above) |
| `PinBoardSetter.kt:28` `savePin(pin.copy(...))` | copy, no transaction | fenced (above) |
| `BoardUpdater.kt:21` `saveBoard(board.copy(...))` | copy, no transaction | fenced (above) |
| `PinCreator.kt:37` `savePin(pin)` | named local of a fresh `Pin(...)` | the construction is inlined into the call |
| `SetPinImage.kt:78` `imageRepository.save(image)` | named local of a fresh `Image(...)` | inlined, the later reads taking `saved` |
| `UserCreator.kt:39` `saveUser(user)` | named local of a fresh `User(...)`, in a helper lexically outside the caller's `inTransaction` | inlined |
| `imports/UserDataImportRunner.kt:402` `saveBoard(created)` | named local of a fresh `Board(...)` | inlined |
| `exports/UserDataExportRequester.kt:68` and `:76` | `save(x.copy(...))` in `createPending`, which runs inside the `inTransaction` its caller opens at `:48` | `createPending` opens the transaction itself; the caller stops opening it. REQUIRED semantics join a caller's transaction, so nothing else moves |
| `exports/UserDataExportBuilder.kt:160` | `save(current.copy(...))` in `promoteIfStillPending`, called from `inTransaction` at `:151` | the same: `promoteIfStillPending` opens the transaction, `publish` calls it bare |

The last two dispositions are the rule the import completer already follows
(`UserDataImportArchiveCompleter.kt:73-74`, "the fence is lexical, and so is the rule that holds
it"): the function that saves is the function that opens the transaction. `TagCreator`,
`BoardCreator`, `SessionCreator`, `SessionRenewer`, `DownloadPinImage`, the `saveFenced` delegations
and every other site under `exports/` and `imports/` pass: a construction as the argument, or a save
lexically inside a boundary call. `UserDataImportRunner.kt:506` stays suppressed with its reason,
under the new name. This table is what the first detekt run of block 8 is checked against, and a
difference is corrected here. The gotcha in `AGENTS.md` applies: `./gradlew --stop` before trusting a
gate after the rule changes.

**The accepted limit (D8).** The rule tells a construction from a call by the callee's initial
capital, without types. A row rebuilt field by field from an earlier read, `Pin(id = old.id, ...)`,
is a construction to it and a merge to the database. No lexical criterion separates the two; type
resolution would, at a cost on every gate for a shape nobody writes here. The KDoc says so, and the
backlog's Known limits band points at it.

### 4.7 The lost lease

**Why it matters.** `TaskQueueInterface.renewLease` answers a `Boolean` whose KDoc says the caller
must stop; `TaskProcessor.kt:45` coerces it into a `() -> Unit` and no handler can read it. A long
handler therefore keeps spending disk and CPU on work it can no longer publish, since every settle
is fenced on the lease it lost. The import half is the one that matters: its runner writes into
account data as it walks, and an evicted attempt keeps writing beside the attempt that replaced it
until its own fenced `advance` refuses, which is one pin later in the pin walk and one whole entry
later in the tag and board walks, each of which writes the row once. Correctness does not depend on
the fix, `docs/adr/0017` having put the promote inside the publishing fence; the cost does.

**The mechanism (D3).** `TaskLeaseLostException(taskId)` joins `PermanentTaskException` under
`usecases/tasks/exceptions`. `TaskProcessor` builds the heartbeat as

```kotlin
renewLease = {
    if (!taskQueue.renewLease(claimed.id, claimed.leaseId, clock.now().plus(leaseDuration))) {
        throw TaskLeaseLostException(claimed.id)
    }
}
```

and `runHandler` catches it ahead of `Exception` into a fourth outcome, `Abandoned`, on which
`execute` logs at WARN and settles nothing: the lease is another attempt's or nobody's, and every
`mark*` would be refused by its own guard. `markCancelledIfRequested` is skipped for the same reason.
`TaskContext.renewLease` keeps its type and its KDoc says it throws. **ADR 0022** records the
protocol: the exception, the outcome that settles nothing, and the rule that every handler net
rethrows it.

**Where it must pass through.** Both handlers hold a net that catches `Throwable` and marks the row
`FAILED` on the last attempt: `UserDataExportBuilder.stageOrFail` and `UserDataImportRunner.replay`.
Each rethrows a `TaskLeaseLostException` before its own arm, so an evicted attempt never writes
`FAILED` over a row whose winner is still building. The export's `completeOrFail` wraps no heartbeat
and does not change. The import's per-line nets (`rejecting`, `importPin`, `promoteAndWrite`) sit
inside the heartbeat calls, not around them: `walkLines` renews before `importLine`, `walkPins`
before `importPin`, so the exception reaches `replay` without meeting them. `replay`'s `finally`
still runs `releaseArchive`, which re-reads the row and deletes nothing while another attempt holds
it `RUNNING`.

**What it changes for `claimNext` and the grace.** Nothing in code. A handler that lost its lease now
stops at its next heartbeat, so a task `claimNext` kills is one whose handler stops within the longest
gap between two heartbeats: one image stream for the export, `imports.lease_renewal_lines` lines for
the import. `failInterruptedBuilds`'s KDoc (`ReapExpiredUserDataExports.kt:67-68`) loses "without
regard for a handler still running", and `claimNext`'s comment gains the window. The `PT6H` grace
stays (D3): it is anchored on the staged file's age, not on the lease.

## 5. Acceptance criteria

Each names the output that fails it.

**Block 1**

- A1. `ls agents/reviews/` lists `spec.md` and `holistic.md` and nothing else; `spec.md` contains the
  ADR-existence check as its own section; `holistic.md` contains neither "block review" nor a
  pull request as the thing it reads. *(Corrected in block 1: the first draft said "nor 'pull
  request'", and the mandate has to name the closing block's pull request, D12, and the human's
  reading of each, D1. The grep that fails it is `grep -n "block review" agents/reviews/holistic.md`
  returning a line, or its first paragraph naming a pull request as the artefact.)*
- A2. `grep -n "may still be dispatched" agents/workflow.md` returns nothing;
  `grep -n "block review" agents/*.md AGENTS.md` returns nothing.
- A3. `head -12` of ADRs 0001, 0010, 0014, 0018 and 0019 names `0020`; the status lines of ADRs
  0014, 0016 and 0017 read `Accepted`.
- A4. `grep -c "no local command covers" AGENTS.md` is `0`, and the replacing sentence names the
  hook and the two Dockerfile tests.
- A5. `docs/backlog.md` "How to use this file" names four bands and says a priority may be empty;
  items 15 to 22 of section 2 are gone from it; each remaining P2 item carries the branch of the
  block section 6 assigns it.

**Block 2**

- A6. `grep -rn "ReapExpiredUserDataExports\|ReapAbandonedUserDataImports" --include="*.kt" .`
  returns nothing; the two new names have a test class each, and the `--tests` case count before
  and after the rename is pasted in the commit.
- A7. `grep -rn '"tmp"\|"exports\|"imports' --include="*.kt" */src/main` returns lines in
  `StorageLayout.kt` only. Run today it returns the ten sites of the table in 4.2.
- A8. `ImportLifecycleTest` has a case in which `reap()` throws at startup and the scheduler is still
  given its fixed-delay task; it fails against `start()` calling `reap()` bare (mutation pasted in
  the commit).
- A9. `ReapUserDataImports.reap()`'s KDoc names `failInterruptedRuns` as the pass that makes a
  key-holding row terminal.

**Block 3**

- A10. A use-case test over a fake repository that pages by `id`: `limit + 1` reclaimable rows, the
  delete of the row with the smallest `id` refused, one sweep. Then: every other row reads
  `storageKey = null`, and the fake was asked for pages until it answered an empty one. Against the
  current code the sweep reads one page of `limit` rows, the refused row among them, and the row
  beyond the page keeps its key.
- A11. Every selection in the table of 4.2 carries `setMaxRows` and `orderBy().id.asc()` in its
  query, and a repository test on each pins that a second page starts after the first page's last
  `id`. Today neither repository's sweep selections carry an `orderBy`.
- A12. `EbeanTaskQueueTest` seeds `limit + 1` expired leases and reads `reapExpired` returning
  `limit`; against the current code it returns `limit + 1`.

**Block 4**

- A13. `grep -rn "class UserDataExport.*Fixtures" api-usecases/src/test` shows the two siblings
  extending `UserDataExportFixtures` and neither extending the other; `--tests "*UserDataExport*"`
  reports the same test count before and after, pasted in the commit.

**Block 5**

- A14. `jq -r '.paths["/api/v1/me/exports"].post.responses | keys' docs/openapi.json` lists `202`,
  `400`, `401`, `403`, `409`, `429` and not `200`; the four other export operations list the statuses
  of the table in 4.4.
- A15. `jq -r '.paths["/api/v1/pins/{pinId}/tags"].put.description' docs/openapi.json` contains
  "nocase" and "stored spelling"; today it prints `null`.
- A16. `jq -r '.paths["/api/v1/me/imports/{id}/archive"].put.parameters[] | select(.name=="offset")
  | .schema.default' docs/openapi.json` prints `0`; today it prints `null`.

**Block 6**

- A17. One integration case per row of the table in 4.5, in `MeImportIntegrationTest` or
  `SessionAuthIntegrationTest` as fits, each reading `Content-Type: application/problem+json`, the
  status, and the `code` of the row. Against the current code each reads a body that is not JSON or
  a `code` that is absent; no framework-generated `problem+json` case exists today.
- A18. The two `500` rows are driven by a test-only resource under `api-application/src/test`,
  `@Path("/test/failures")`, that throws an `IllegalStateException` carrying a marker string on one
  route and an `IOException` on another. Each case's body has `detail` null and `code`
  `INTERNAL_ERROR`, and the test asserts the marker appears nowhere in the body. The resource runs
  nothing on its own, so joining the default profile changes nothing that runs beside it.
- A19. The oversize case's outcome is written in 4.5 in `(Corrected: ...)` form if it differs from a
  mapped `413`. This one is a reader's check, not a test.
- A20. `grep -rn "^fun \|^const " api-presentation-quarkus/src/main` lists only the three
  `SecurityIdentity` extensions; every mapper calls `ProblemResponses.problemResponse(...)`; and
  `grep -rn "VALIDATION_ERROR\|MALFORMED_BODY" --include="*.kt" api-presentation-quarkus/src/main`
  finds each in `FrameworkErrorCode.kt` only.
- A21. `agents/engineering.md`'s "one table" sentence names `BaseErrorMapper.statusFor` for domain
  errors and `FrameworkErrorCode` for the framework's, in block 6's diff.

**Block 7**

- A22. `UserDataExportRepositoryInterface.saveFenced`'s body and its import twin's are one call each
  into the generic function; `grep -c "inTransaction" ExportAccess.kt ImportAccess.kt` is `0`; and a
  test with `PassthroughTransactionRunner` reads the read and the write of a fenced call in the same
  transaction number, which a fence that opened no transaction fails.
- A23. For each of the three sites, a use-case test in which the re-read inside the fence answers a
  soft-deleted row: the site throws its soft-deleted refusal and `savePin`/`saveBoard` is never
  called over that one use-case call. Against the current code the save is called with
  `softDeletedAt = null`.
- A24. For `PinTagger` and `PinBoardSetter`, a test in which the fence's re-read carries boards
  (respectively tags) the first read did not: the saved pin carries them.

**Block 8**

- A25. `grep -rn "ImportStateMergedOutsideTransaction" . --include="*.kt" --include="*.yml"` returns
  nothing (today: three files and the suppression at `UserDataImportRunner.kt:502`); `detekt.yml`
  scopes `RowMergedOutsideTransaction` to `**/api-usecases/**`.
- A26. The rule's test has a case where `savePin(local)` is reported, one where
  `saveSessionToken(token, hash)` is not, one where `{ save(it) }` under a call named `fenced` is
  not, and one where the same lambda under any other call is.
- A27. `./gradlew --stop && ./gradlew gate` is green; the diff carries the four inlinings and the
  two transaction moves of the table in 4.6 and no inline suppression of the rule beyond the renamed
  one at `UserDataImportRunner.kt:502`.
- A28. `docs/backlog.md` Known limits holds one line for the construction blindness, pointing at the
  rule's KDoc, and the P2 item is gone.

**Block 9**

- A29. `TaskProcessorTest`: a handler whose heartbeat is answered `false` by the queue throws
  `TaskLeaseLostException`, and over that one `execute` call `markSucceeded`, `markPendingRetry`,
  `markDead` and `markCancelledIfRequested` are each called zero times. Against the current code the
  heartbeat returns normally and `markSucceeded` is called.
- A30. `UserDataExportBuilderTest`: a lost lease during the pin walk on the last attempt leaves the
  row `PENDING`, rethrows the exception, and stages nothing further. Against the current code no
  exception exists; against the net without its exclusion the row reads `FAILED`.
- A31. `UserDataImportRunnerTest`: the same on the last attempt, the row still `RUNNING` under its
  `runToken`, no `FAILED`, the archive not deleted.
- A32. The handoff is in this block's diff (D12), names the refusal of items 8 and 23 with its
  reason, and `docs/backlog.md` no longer holds them.

**Block 10**

- A33. Every finding of the holistic review is named in the handoff with its exit; the count of
  findings that touched an already merged block is stated. `docs/backlog.md`'s P2 band is empty, or
  holds only what this lot's own reviews filed through the backlog exit, and the handoff says which.

## 6. Block table

| # | Block | Delivers | Files | Criteria |
|---|---|---|---|---|
| 1 | `docs/p2-debt-regime` | ADR 0020, the two mandates, `agents/*.md`, `AGENTS.md`, ADR status lines, backlog bands, this spec | `docs/adr/0020-*.md`, `agents/reviews/spec.md`, `agents/reviews/holistic.md`, `agents/workflow.md`, `agents/writing.md`, `AGENTS.md`, ADRs 0001/0010/0014/0016/0017/0018/0019 status lines, `docs/backlog.md`, `docs/specs/2026-09-05-p2-debt-elimination.md` | A1 to A5 |
| 2 | `fix/p2-debt-sweep-names` | The two renames, `StorageLayout`, import hygiene | `api-domain/.../storage/StorageLayout.kt`, `ExportArchiveKey.kt`, `ImportArchiveKey.kt`, `ReapOrphanedStorage.kt`, the three filesystem stores (both `"tmp"` and their directory constants), `ExportDataDirectoryCheck.kt`, `ReapUserDataExports.kt`, `ReapUserDataImports.kt`, `ImportLifecycle.kt`, producers, their tests | A6 to A9 |
| 3 | `fix/p2-debt-paged-sweeps` | Paged selections, `reapExpired` bound, `imports.sweep_batch_size` | the two repository interfaces and implementations, `ReapUserDataExports.kt`, `ReapUserDataImports.kt`, `ImportsConfig.kt`, `ImportProducers`, `TaskQueueInterface.kt`, `EbeanTaskQueue.kt`, `ReapExpiredTasks.kt`, their tests | A10 to A12 |
| 4 | `test/p2-debt-export-fixtures` | One base, two siblings | `UserDataExportBuilderFixtures.kt` split in three, the test classes' `extends` | A13 |
| 5 | `fix/p2-debt-openapi` | Export `@APIResponse`, tag `@Operation`, offset default | `MeExportController.kt`, `PinController.kt`, `MeImportController.kt`, `docs/openapi.json` (hook) | A14 to A16 |
| 6 | `fix/p2-debt-error-format` | ADR 0021, the mapper family, `FrameworkErrorCode`, `ProblemResponses` object, the property, the engineering sentence | `docs/adr/0021-*.md`, `mappers/*.kt`, `application.properties`, `agents/engineering.md`, a test resource and integration cases in `api-application/src/test` | A17 to A21 |
| 7 | `fix/p2-debt-fences` | Generic fence, four delegations, two new extensions, three sites | `usecases/Fences.kt`, `ExportAccess.kt`, `ImportAccess.kt`, `PinAccess.kt`, `BoardAccess.kt`, `PinTagger.kt`, `PinBoardSetter.kt`, `BoardUpdater.kt`, their tests | A22 to A24 |
| 8 | `fix/p2-debt-fence-rule` | The rule renamed, widened, `save*`, boundary names; four inlinings; two transaction moves; the limit | `detekt-rules/.../RowMergedOutsideTransaction.kt` and test, `PinryRuleSetProvider.kt`, `detekt.yml`, `PinCreator.kt`, `SetPinImage.kt`, `UserCreator.kt`, `UserDataImportRunner.kt`, `UserDataExportRequester.kt`, `UserDataExportBuilder.kt`, `docs/backlog.md` | A25 to A28 |
| 9 | `fix/p2-debt-lost-lease` | ADR 0022, `TaskLeaseLostException`, processor, two nets, comments, the handoff, two refusals | `docs/adr/0022-*.md`, `tasks/exceptions/TaskLeaseLostException.kt`, `TaskProcessor.kt`, `TaskContext.kt`, `UserDataExportBuilder.kt`, `UserDataImportRunner.kt`, `EbeanTaskQueue.kt` comment, `ReapUserDataExports.kt` KDoc, tests, `docs/handoffs/2026-09-05 - handoff - p2-debt-elimination.md`, `docs/backlog.md` | A29 to A32 |
| 10 | `fix/p2-debt-closing` | Holistic findings, handoff corrected, backlog reconciled | whatever the findings name, the handoff, `docs/backlog.md` | A33 |

Every block sits under 600 lines excluding dated documents by construction; block 2 is the one to
watch, its renames touching many files, and `git mv` keeps a rename at zero lines where the content
does not move. Block 1 counts the two mandates and the living documents, around 250 lines, the ADR
and the spec being dated. Block 8 grew by four files in review and stays under budget: each move is
a few lines.

Blocks 2 to 9 are each coherent alone: every new port method has its caller in the same block
(`reapExpired`'s `limit` and `ReapExpiredTasks`, the paged selections and their passes, the generic
fence and its delegations, `FrameworkErrorCode` and its mappers), and `imports.sweep_batch_size` is
read by the producer that lands with it.

## 7. Adjacent items

All twenty-three P2 items are the subject; section 2 gives each its block. The P1 band holds no
adjacent item (section 2). Nothing is left open on purpose.

Found in Discuss and in the review of this document, taken into the blocks that touch the same
files under tier 1 of `agents/workflow.md` Scope, and listed here so no diff hunk is unexplained:

- The export's `findExpiredReadyExports` and the import's three unbounded selections, block 3 (4.2).
- `ImportLifecycleTest`'s missing startup case, block 2 (4.2).
- `ProblemResponses.kt` and `MediaTypes.kt`'s top-level declarations, and `VALIDATION_ERROR`
  joining `FrameworkErrorCode`, block 6 (4.5).
- The four inlinings and the two transaction moves, block 8 (4.6): the widened rule demands them.
- ADRs 0016 and 0017 still `Proposed`, block 1 (4.1).

## 8. Out of scope, accepted limits

Each names how a reader would notice if it changed anyway.

- **The review-cost measurement (D2).** Refused, not deferred: the item is deleted in block 9 with
  the reason in the handoff. A later lot wanting it starts from the transcripts, not from this file.
  Noticed by: the handoff carrying the refusal, and the backlog no longer holding the item.
- **`TaskQueueBootIntegrationTest` (D7).** Refused. The reason the handoff will carry: the truncation
  `IntegrationTest` runs before each case rules out leftover rows from another class, which is the
  contamination the export completion handoff supposed; a writer inside the same boot is not ruled
  out and was never observed, so the one failure seen is unexplained, and repairing an unexplained
  symptom hides the next one. A reproduction reopens it. Noticed by: `TaskQueueBootIntegrationTest.kt`
  absent from every block's diff.
- **The construction blindness of the rule (D8).** Accepted limit, 4.6. Noticed by: the Known
  limits line and the KDoc paragraph.
- **`claimNext` keeps killing inline.** D3 makes the handler stop rather than the queue wait; the
  residual window is stated in 4.7. A claim token on the export row, which
  `docs/specs/2026-08-15-export-row-fencing.md` section 8 named for the two-attempts race, is not
  taken: the promote inside the publishing fence made the race harmless, and this lot adds no column.
  Noticed by: `git diff main -- EbeanTaskQueue.kt` touching only the comment at lines 108-111, and
  `UserDataExportModel` gaining no column.
- **The `PT6H` grace.** Unchanged (D3). Noticed by: `grep -c PT6H ExportsConfig.kt` still `2`.
- **`findPending(limit)`'s head.** A `PENDING` row past the grace whose task is live stays at the
  head of pass 1 forever. The partial index allows one such row per user, so the head is bounded by
  the number of users with a live build, and the page is 500. Not paged, and said here so the
  asymmetry with 4.2's table is a decision rather than an oversight. Noticed by: the signature
  `findPending(limit: Int)` unchanged.
- **The oversize `413`.** Measured in block 6, mapped if reachable, recorded here otherwise (4.5).
- **`imports.lease_renewal_lines` and the export's per-image heartbeat** define the residual window
  of 4.7 and are not retuned. Noticed by: the default `200` in `ImportsConfig.kt` and the heartbeat
  at `UserDataExportBuilder.kt:253` unchanged.
- **The Ebean `@Version` on `TaskModel`** stays, as `docs/adr/0016` decided. Noticed by:
  `TaskModel.kt` absent from every diff.
- **No migration.** Every change here is code and documents; `git diff main...HEAD -- '*dbmigration*'`
  is empty at every block.

## 9. Tests

Red before green in every block, the failing run pasted in the commit that adds the test, the
mutation pasted in the commit for every structural assertion (`agents/engineering.md`, Test
conventions).

- **Unit, `api-usecases`**: the three fence sites (A23, A24), the generic fence's two shapes with
  `PassthroughTransactionRunner` counting the transaction (A22), the paged passes over a fake
  repository that pages by `id` and refuses one row (A10), the two nets letting
  `TaskLeaseLostException` through (A30, A31), `TaskProcessor`'s `Abandoned` outcome (A29),
  `ReapExpiredTasks` passing its bound.
- **Unit, `api-worker-quarkus`**: `ImportLifecycleTest`'s startup case (A8).
- **Repository, `api-persistence-sqlite`**: each paged selection's order and bound (A11),
  `reapExpired`'s bound (A12).
- **Rule, `detekt-rules`**: the renamed rule's cases plus A26's four.
- **Integration, `api-application`**: one case per mapper row (A17, A18), the oversize probe (A19),
  and the OpenAPI assertions run against `docs/openapi.json` as the hook regenerates it (A14 to A16),
  which needs no boot.
- **Structural**: A7's grep is the assertion that `StorageLayout` is the only spelling; it is run in
  the commit, not turned into a Konsist test, since a string literal is not a declaration Konsist
  sees.

No new `@QuarkusTest` class: every integration case joins a suite that boots already
(`docs/specs/2026-08-12-p2-debt-triage.md` rule 3); the test resource of A18 is a resource, not a
suite.

## 10. Risks

- **The table in 4.6 was read by hand; the rule reads syntax.** The review of this document found
  five sites the first reading missed, all of one kind, a save lexically outside the transaction it
  runs in. Block 8's first detekt run is the check, and a site the table still misses is
  dispositioned there and the table corrected.
- **Two boundary names in the rule are spellings.** A function named `fenced` that opens no
  transaction would pass the rule. The name is the helper's, the helper is `internal` to
  `api-usecases`, and the KDoc says it.
- **The oversize body may be answered below JAX-RS** (4.5). Accepted limit if so.
- **Block 2's rename diff.** If `git diff --stat` excluding dated documents passes 600 lines, the
  `StorageLayout` half splits into its own block, and this table gains a row.
- **The two mandates are written from the deleted ones.** They are recoverable from history
  (`git show b3a8671a~1:agents/reviews/evidence.md`) and are trimmed, not rewritten; the adversarial
  review of this document ran on the merged mandate as a prompt, which is the first test it got, and
  `spec.md` is that prompt written down.
- **D11 lets nine pull requests edit a dated document.** Each edit is a `(Corrected: ...)` at the
  sentence it corrects, never a rewrite, so the document keeps recording what was believed when.
- **The claim the lot rests on is not in the repository** (D1). If the deletion of `agents/reviews/`
  was a trim that overshot rather than a regime change, ADR 0020 codifies an accident and nine pull
  requests ship with no automated review of their code. Only the operator's reading of section 3
  can refute it, which is why this document is reviewed by them before block 1 opens.

## 11. Review of this specification

One adversarial review ran, by a fresh subagent, on the merged mandate of 4.1 given as its prompt,
before block 1's pull request opens. It reported four MAJOR and ten MINOR findings on evidence, three
MAJOR and five MINOR on falsifiability, and two decisions without a record. Every one is closed in
this revision: the table of 4.6 recounted from five to ten sites and the helper-versus-rule
interplay written down; ADRs 0021 and 0022 added; `agents/engineering.md` named in block 6; the
claims resting on the Discuss transcript reduced to section 3; the ten spellings of the segments
listed; the Quarkus resolution cited from its code rather than its comment and the `IOException`
row added; A7, A10, A18, A21 (now A22) and A26 (now A27) made able to fail; items 8 and 23 moved to
block 9; ADRs 0016 and 0017 added to the status pass. The three-angle rotation of ADR 0018 decision
8 is gone with the block review (D1), so there is no third angle to name. The holistic review runs
once, after block 9, over `git diff <lot base>..<block 9 head>` with every merged block included, and
its findings are block 10.
