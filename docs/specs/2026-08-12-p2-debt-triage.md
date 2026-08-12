# The P2 band stops growing: sixteen items triaged, and a rule that keeps it triaged

Date: 2026-08-12
Status: Proposed
Branch: `chore/p2-backlog-triage`
ADR: `docs/adr/0010-review-finding-dispositions.md`

Closes or reclassifies sixteen of the seventeen P2 items open on 2026-08-12. Opened because the
backlog grows faster than the work that feeds it, which no single item describes.

## 1. Goal

Two halves, and the second is what makes the first last.

1. **Resolve the P2 band down to open work only.** Six corrections, four closures by decision, two
   reclassifications, one item moved out of the backlog to the document that already carries it.
2. **Give a review finding somewhere to land other than the backlog.** Today it has one exit, so
   everything not fixed inside the lot becomes debt. Three more exits, written down, plus a backlog
   split by nature so a recorded limit stops being counted as work.

## 2. The measurement

Item count in `docs/backlog.md`, read from the file at each commit that touched it:

```
$ for c in $(git log --format='%h' -- docs/backlog.md | head -20 | tac); do \
    d=$(git show -s --format=%ad --date=short $c); \
    n=$(git show $c:docs/backlog.md | grep -cE '^- \*\*'); echo "$d $c $n items"; done
2026-08-02 17574d9 19 items     <- the operational-debt wave closes
2026-08-03 25b3b7b 20 items
2026-08-04 56f17a6 20 items
2026-08-05 abe8a3c 22 items
2026-08-05 f6dab96 23 items
2026-08-05 bfe7946 25 items
2026-08-05 68c58ff 27 items
2026-08-05 d0075fa 28 items
2026-08-05 28c25c0 29 items
2026-08-12 d37520e 28 items     <- the unique-constraint lot closes
```

The last lot closed one item and added seven. Sixteen of the twenty-eight sit in P2:

```
$ awk '/^### P2/,/^### Features/' docs/backlog.md | grep -cE '^- \*\*'
16
```

Three causes, and only the first is about volume.

- **A review finding has one exit.** Fixed inside the lot, or backlog item. There is no exit for
  "accepted limit, recorded where the decision lives" and none for "refused, and here is why".
- **The band mixes four natures.** Open work (inverse associations), limits already documented
  elsewhere (soft-delete residuals), dated events (flatten migrations at beta), and arbitrations
  nobody arbitrates (the `PinRepositoryTest` split). Only the first is debt.
- **The chain of guards has no stopping rule.** Three of the most recent items are about the tests
  that guard the migrations, not about the product. Reviewing a guard produces a finding about the
  guard.

## 3. Disposition of the sixteen

| # | Item | Disposition |
|---|---|---|
| 1 | Inverse associations on the persistence models | Stays: open work, needs Ebean behaviour proofs |
| 2 | Unify the ambient-transaction logic | Stays: open work, changes Ebean transaction nesting |
| 3 | Soft-delete read-isolation residuals | Out of the backlog: a limit, recorded in ADR 0008 |
| 4 | Authentication attempt limiting | Reclassified: before beta |
| 5 | Flatten the migration history | Reclassified: before beta |
| 6 | No test pins the cause chain | Fixed by the type: `cause` becomes required (T4) |
| 7 | Tombstoned-name refusal composed over two levels | Fixed: one case in an existing suite (T6) |
| 8 | `UserCreator.createUser(name)` has no caller | Deleted with its test (T5) |
| 9 | Partial index predicate mirrored by hand in Kotlin | Fixed (T3) |
| 10 | Heap dump and one `SQLITE_BUSY` | Closed: not reproduced, not tied to a change |
| 11 | Nothing checks `<createIndex definition>` against the DDL | Fixed (T2) |
| 12 | Two migration tests, two readers | Fixed (T1) |
| 13 | `UserDataExportModel.kt:16` states a false rule | Fixed (T7) |
| 14 | Periodic maintenance via the task queue | Stays: its own session, operator's call |
| 15 | Export refusal precedence pinned only at unit level | Closed: each link already pinned |
| 16 | `PinRepositoryTest` split decision | Fixed, widest scope (T8) |

P2 holds three items after this lot: 1, 2 and 14.

### Why 10 closes

A test JVM died on an `OutOfMemoryError` on 2026-08-05, leaving a 515 MB heap dump, and a forced gate
run had failed with `SQLITE_BUSY` minutes earlier. Three consecutive forced runs then passed clean and
the dump was deleted. Nothing reproduced it, nothing ties it to a change, and no measurement says what
a correct heap bound would be. Setting one now is an option nobody can size, which
`agents/modules/backend.md` names as a smell rather than a fix. It reopens if CI meets it.

### Why 15 closes

Every link is already pinned, each by its own test:

| Link | Test |
|---|---|
| Both refusals apply, the use case raises `ExportAlreadyInProgressError` | `UserDataExportRequesterTest.kt:158-172` |
| That error is `CONFLICT` | `BaseErrorMapperTest.kt:170` |
| The client reads `409 EXPORT_ALREADY_IN_PROGRESS` | `MeExportIntegrationTest.kt:120` |

The item's premise was that `verify(exactly = 0) { repository.findLastRequestedAtForUser(any()) }` is
an internal call count rather than an outcome. The assertion that carries the test is
`assertThrows(ExportAlreadyInProgressError::class.java)` on line 168; the `verify` is reinforcement.
Precedence does hold: `findLastRequestedAtForUser` is unstubbed, so reversing the two refusals fails
the test on MockK's "no answer found". What remains is an unclear failure message, and the fix the
item proposed costs a new `@QuarkusTest` class with its own profile, so a whole Quarkus boot added to
the gate to recompose three links already held. The rule that comes out of it is in section 5.

## 4. In scope

Nine tasks. The tier is Spec, so they are listed here rather than in a plan document.

### T1. One reader for the migration directory

`UniqueConstraintOutcomeTest` and `DbMigrationModelCoverageTest` sit in the same package and repeat
`migrationDirectory`, the `sqlScripts` listing, `locationsMatching`, `schemaOnly` and the rationale
for the loose probe. `AGENTS.md` Design names the repeated explanatory comment as the signal that the
design is the defect: the comment has no shared object to hang on.

Extract a test-source helper owning the listing, the locators, the loose-probe idiom and the comment
stripping. It exposes both the stripped and the raw text, because the `-- not supported` assertion
reads the comment itself and a stripped text would make it pass on anything.

- Acceptance: neither test class declares `migrationDirectory`, its own listing, or its own
  `schemaOnly`; every existing assertion still passes unchanged; the loose-probe rationale is written
  once.

### T2. A `<createIndex definition>` is checked against the DDL its migration applied

`DbMigrationModelCoverageTest` compares index *names* between the `.sql` files and the model files,
and `generateDbMigration` compares the model against the annotations it was harvested from, so the
two agree by construction. A `<createIndex>` whose `definition` differs from the `create index` line
its migration ran passes both. Today's nine are byte-exact, checked by hand during the T3 review of
2026-08-05.

Pair each `<createIndex definition>` with the create-index statement of the `.sql` of the same
version, on T1's helper.

- Acceptance: the assertion fails when a `definition` is edited away from the DDL it mirrors, shown by
  the mutation in the commit body; a `<createIndex>` without a `definition` (the plain column-list
  form) is not reported as a mismatch.

### T3. A partial index predicate has one Kotlin site, and the test reads it

`ux_tasks_dedup` is partial on `dedup_key is not null and state in ('PENDING','RUNNING')`
(`1.3.sql:27`) and `EbeanTaskQueue.findLiveTaskWithDedupKey` (`EbeanTaskQueue.kt:77-81`) repeats that
state set in Kotlin. `uq_user_data_exports_pending` is partial on `state = 'PENDING'` (`1.11.sql:2`)
and `UserDataExportRepository.findPendingForUser` (`:89-93`) repeats it. The two agreeing is what
makes the dedup fast path correct and the recovery's empty re-read unreachable, and nothing ties them.

Name each state set once in Kotlin, have the query read that name, and assert the DDL's `where`
clause names exactly that set.

- Acceptance: the state set appears once per index in the production sources and the query reads it;
  the assertion fails when the set is widened or narrowed away from the DDL, shown by the mutation in
  the commit body; the migrations are untouched.

### T4. A dropped cause becomes a compile error

`UsernameAlreadyTakenException`, `PasswordChangeCollisionException` and
`ExportAlreadyInProgressException` all declare `cause: Throwable? = null`, so dropping the chain at a
new call site compiles and passes. All three are raised only by an adapter translating a caught
`PersistenceException`, so there is no honest construction without a cause.

Make `cause` required on the three. This replaces the item's request for three tests: a test asserting
`assertSame(violation, thrown.cause)` catches the drop after it is written, the type refuses to
compile it.

- Acceptance: the three constructors take a non-nullable `cause` with no default; the gate is green.
  No test is added, and the reason is in the commit body: the compiler is the guard, and a test of a
  compile error cannot be written in the same source set.

### T5. `UserCreator.createUser(name)` goes

`UserController` calls `createUserWithPassword`. The password-less entry point has no production
caller (`grep -rn "createUser(" --include="*.kt"` returns the controller's own method and test
helpers only) and is exercised by its unit test alone. Alpha status, so it goes rather than waits for
an intention.

- Acceptance: the method and the tests that only exercise it are deleted; `createUserInternal` folds
  into `createUserWithPassword` if nothing else calls it; the gate is green, coverage included.

### T6. The tombstoned-name refusal is pinned end to end

Since the pre-check went, "a name held by an account pending deletion is still taken" is pinned by a
repository test and by a use-case test, with nothing joining them. Its sibling, the case variant, is
pinned end to end at `UserCreationIntegrationTest.kt:191-208`.

Add the tombstoned case to that class. It boots already, so this is a case added to a suite rather
than a suite added for a case (section 5).

- Acceptance: an integration test creates a user, marks it pending deletion, and asserts the next
  creation of the same name answers `409 USERNAME_ALREADY_EXISTS`.

### T7. The false Ebean rule goes

`UserDataExportModel.kt:14-17` says `columnNames` "keeps the index in the migration model so a later
diff drops and recreates it correctly". Ebean keys an index by name and compares `tableName`,
`unique`, `definition` and the column lists between the two model sides, so a `definition`-only
declaration diffs correctly on its own. It is the only precedent a reader finds for a partial index,
and `agents/project.md` Gotchas already states the right form.

Correct the comment. The annotation itself is not touched: its `columnNames` is recorded in the
applied migration model, and changing the declaration is a migration question, not a comment fix.

- Acceptance: the comment states why the index is partial and nothing false about `columnNames`; two
  lines at most (`agents/project.md`, comment length).

### T8. `PinRepositoryTest` splits, and the fixtures stop being copied

918 lines against detekt's `allowedLines: 600` (`config/detekt/detekt.yml:116`), held by a reasoned
`@Suppress("LargeClass")`. Both sibling slices carry the opposite reason in their own KDoc: "split
from `PinRepositoryTest` to keep it under detekt's `LargeClass` threshold". The project already
decided, twice, and the suppression contradicts its own siblings.

Three moves:

1. The `// --- Soft delete tests ---` section (lines 271-616, 21 tests) becomes
   `PinRepositorySoftDeleteTest`.
2. The `// --- Pagination cursor resolution ---` section (617-767) returns to
   `PinRepositoryPaginationTest`, which exists.
3. `createAndSaveUser`, `createAndSaveTag`, `createAndSaveBoard`, `createPin` and the board
   soft-delete helpers are copied across the slices today. They move to one place the slices share.

- Acceptance: no `@Suppress("LargeClass")` on `PinRepositoryTest`; every test that existed still
  exists and still passes, with `--tests` output showing the same count before and after; no fixture
  helper is declared in two classes.

### T9. The backlog is restructured and the rule is written

The dispositions of section 3 are applied to `docs/backlog.md`, which gains the bands of section 5,
and the rules of section 5 land in `agents/project.md`.

- Acceptance: P2 holds items 1, 2 and 14; the "Known limits" and "Before beta" bands hold what
  section 3 assigns them; `agents/project.md` carries the four exits, the band definitions and the
  integration-suite rule; the `Last reviewed` line names this spec.

## 5. The durable remedy

Three rules, into `agents/project.md`.

1. **A review finding has four exits, and only one is the backlog.** Fixed inside the lot; a backlog
   item, which means work someone will do; an accepted limit, written where the decision lives (the
   ADR, the spec, the handoff) and not in the backlog; or refused, with the reason in the handoff.
   Wrap states which exit each finding took.
2. **The backlog is banded by nature, not only by priority.** Open work (P0, P1, P2) is what someone
   will do. Known limits are recorded elsewhere and pointed at from nowhere else. Before beta holds
   dated events that no session can start early. A limit is not debt and is not counted as debt.
3. **A case joins an existing integration suite; a suite is not created for a case.** A new
   `@QuarkusTest` class costs a boot in the gate, so it is justified by a scenario an existing suite
   cannot host (its profile, its wiring), never by a case that could be a method in one. Where no
   suite fits and the links are pinned separately, the composition is the coverage and the finding is
   an accepted limit.

## 6. Out of scope

- **Items 1, 2 and 14**, which stay open work: each needs a design decision this lot does not take.
- **A heap bound on the test JVMs.** No measurement sizes it (section 3).
- **A cap on the P2 band.** Considered and not retained: rules 1 and 2 address the cause, a cap
  addresses the symptom. It returns if the band grows again.
- **Making `cause` required beyond the three collision exceptions.** The sibling use-case errors
  (`PasswordChangeError.kt:16`) keep the optional shape; T4 is scoped to the three exceptions whose
  every construction site translates a caught failure.
- **Any change to an applied migration.** T2 and T3 read the `.sql` files and never write them.

## 7. Assumptions and risks

- **T8 is the churn.** Around 500 lines move between test classes. The risk is a test silently lost
  in the move, which the acceptance criterion addresses by comparing the executed test count before
  and after rather than by reading the diff.
- **T4 may reach further than the three exceptions.** If a call site outside the adapters constructs
  one without a cause, it stops compiling and the fix is that site, not the default. The gate says so.
- **T5 removes a public use-case method.** `AGENTS.md` escalation lists a public contract change; the
  HTTP surface does not move (no controller calls it), so this stays inside the Spec tier. Recorded
  here rather than assumed.
- **Rule 3 is a judgement rule and no tool enforces it.** It lives in `agents/project.md` beside the
  other judgement calls, which is where `AGENTS.md` Improve sends this kind of remedy.
- **The rules are process, and process is not measured by the gate.** The observable is the next
  lot's wrap: it names the exit each finding took, and the P2 band's size is read again then.
