# Handoff: user data import

Date: 2026-08-15
Branch: `feat/user-data-import`
Specification: `docs/specs/2026-08-14-user-data-import.md`
ADR: `docs/adr/0015-import-identifies-by-natural-key.md`
Plan: `docs/plans/2026-08-14-user-data-import.md`

## Current state

150 commits, 164 files, about 14 200 insertions. `./gradlew gate --rerun-tasks` green with all 168
tasks executed. Not integrated: the pull request awaits the human review `agents/workflow.md`
requires before a rebase merge.

Two generated migrations, `1.20` and `1.21`. Both halves of portability now exist: a user can pour an
export archive into an account, on this instance or another.

**This is the first lot to run under the review regime of `docs/adr/0014`**, so the numbers below are
its first evidence and the reason several of them are recorded at all.

## What was built, and the four decisions that shaped it

**The import is additive and never destructive.** Override mode was designed, argued and dropped: no
user scenario wants an existing row replaced by an older copy of itself. Everything downstream
follows, including the absence of step-up re-authentication, which the ADR now conditions on a
property (any change making the import destructive or irreversible in bulk) rather than on override's
return.

**Identity is a natural key, never an archive identifier.** Tags and boards by name, folded for ASCII
case; a pin by the SHA-256 of its medium. That decision is what forces the two unique indexes, and
with them a public contract break on board names at two write sites.

**The archive's timestamps are restored, clamped at both ends**, which is the one exception to
`agents/engineering.md`'s rule that instants come from `Clock`. The rule that bounds it is testable:
restore what you read, stamp what you invent.

**The archive is untrusted input at every level.** Nothing describing bytes is believed: every image
is probed. Field bounds that live only on REST DTOs are restated by the import, because it is a second
write path into the same tables and no entity carries an invariant.

## Pitfalls, in the order they will bite again

- **`Persistor.merge` writes every column, so saving an entity read earlier restores its whole state,
  including what another actor just changed.** Nine sites of this defect appeared in one lot, found by
  four different readers, each fixed as a particular case before anyone named the cause. It cost the
  most rework of anything here. `ImportStateMergedOutsideTransaction` now fails the build on a save
  handed a copy outside a transaction, but its reach is one package: `docs/backlog.md` carries the
  general case, whose answer is optimistic locking. `TaskModel` is the only entity with `@Version`
  today and is the precedent that item would build on.
- **A guard written as a list of forbidden syntaxes is not a guard.** The same detekt rule first fired
  only on a `copy` that set `state`, and missed a copy bound to a local, which is the shape the next
  site would naturally take. Inverting it (report any save whose argument is not a construction)
  is what made it hold. Expect the same trap in any rule phrased as a blocklist.
- **The pre-commit hook will not stage `docs/openapi.json` if a local build already refreshed it.**
  A `@QuarkusTest` boot writes the document straight into `docs/`, so a `gate` run before committing
  makes the hook see no change of its own. `AGENTS.md`'s gotcha holds only when no build ran first,
  which is the opposite of the discipline the repository asks for. Check `git status` before
  committing, not after.
- **One commit in six does not compile, and that is the rule working.** 25 of this lot's 151 commits
  touch test sources only and create a test file, so they name a class that does not exist yet and
  fail `compileTestKotlin`. An earlier draft of this handoff read that as a discipline failure and
  proposed requiring a green gate per commit, which would have forbidden the repository's own TDD.
  `agents/engineering.md` now says so where the red-commit rule lives.
- **`generateDbMigration` numbers by diffing the model directory**, so two schema tasks built from the
  same base both emit the same version, each omitting the other's index. Keep schema work serial and
  let the generator own the number: no document should name one.
- **`CommentCarriesDocumentation` counts a KDoc's delimiters and merges it with adjacent line
  comments.** A four-line KDoc plus a two-line suppression reason is reported as one seven-line
  comment at the KDoc's position. It cost several rewrites; the way out is folding the reason into the
  KDoc's last sentence.
- **`api-application` boots are flaky under back-to-back runs in this environment**: `Port already
  bound: 8081`, or `Failed to start quarkus` with no cause in the output, followed by a clean re-run.
  Nothing in this branch touches boot. Worth watching in CI.
- **Ebean gives no SQL after `findIds()`**, so a query whose plan you want to assert must run
  `select("id")` plus `findSingleAttributeList()`. `Query.getGeneratedSql()` returns null otherwise.

## What is not validated

- **Nothing on the retry path runs end to end.** The integration suite injects no transient failure,
  so neither a second attempt nor the retry floor is exercised against a real worker.
- **Cursor resumption after an interruption, and cancellation mid-walk, are pinned by fakes only.**
  A small archive finishes before a test can act; the spec records the demotion.
- **No bound is exercised over the wire** (`max_entries`, `max_metadata_bytes`, `max_line_bytes`, the
  413 and the 507), because the integration profile is shared with the sweep suite and lowering them
  would move that suite.
- **The report cap and `issueDetailTruncated` are unit-tested only**, as are lease renewal on a long
  walk, the `-1` priority under contention, and two concurrent imports meeting the partial unique
  index.
- **Nothing observes that a multi-gigabyte body actually streams.** The controller test drives an
  in-memory stream; `@Blocking` plus a Konsist assertion guard the annotation, not the wire.
- **CI has not run.** The container build and the OpenAPI sync check live only in `validate.yml`. The
  boot defect the holistic review found is exactly what that container step would have caught, and it
  reached the end of Verify unnoticed by ten block reviews.

## Where each review finding went

Nineteen review passes ran: six spec angles, three plan angles, ten block reviews, one holistic.
Their findings took three of the four exits.

**Fixed inside the lot**: the overwhelming majority, including every CRITICAL and every MAJOR. The
four that mattered most: an over-long JSONL line silently truncating the rest of an import and
reporting success; a cancellation erased by a stale row write; `FAIL_ON_UNKNOWN_PROPERTIES` refusing
every real archive line; and a startup check that stopped the container booting on default
configuration.

**Backlog items**, seven new: the optimistic-locking cause behind the fenced writes; the export
endpoints publishing a status they do not answer; a malformed body not answering in this project's
error format; `reapExpired` selecting unbounded; the detekt rule reading a construction as an insert;
the tag respelling being unpublished; and an undocumented `offset` default.

**Accepted limits**, written in the spec's section 14 rather than the backlog: a pin with no medium
not surviving the trip; two pins sharing a medium arriving as one; what restoring does not recover;
the worst case of having no step-up; a cursor-less metadata walk; and the ASCII-only fold.

**Refused, with the reason recorded**: correcting the two sentences that have since become false in
the export's dated specification, which `agents/writing.md` freezes; an index on
`(author_id, content_hash)`, which would settle a product question this lot had no mandate to answer;
and step-up itself, which the operator maintained with the counter-argument on the table.

**No block finding had to be arbitrated as work in a later block**, which is the number this scheme
exists to keep low. Three findings were closed one block later than the block that raised them,
deliberately, because their fix touched a file an implementer was holding.

## What the regime cost and found

Ten block reviews found the interaction defects as they appeared, on narrow ranges. The holistic
review found one code defect none of them could see, because its three pieces were laid by three
different blocks and each was correct alone.

The implementers corrected four errors that were in the documents and that neither the six spec angles
nor the three plan angles caught: a query plan that `ieq` does not serve, a mapper refusing every
archive line, a cancellation boolean whose sense I had inverted, and an idempotence test that could
not be written as specified. Each was only visible to someone trying to build the thing.

Three of my own claims in the plan were falsified the same way: twice a pair of tasks I called
mutually independent shared a file, and once a criterion named an instrument the library does not
expose.

## Suggested next step

**Improve, opened 2026-08-15.** The candidates, in the order the operator retained:

1. **The size of the lot itself.** 151 commits and 14 200 insertions is past what its owner can read,
   and past what GitHub can rebase: the pull request answered `rebaseable: false` while git replayed
   all 151 patches locally without one conflict, on an unmoved base. The remedy on the table is that
   a change of behaviour on existing code ships in its own pull request, before the lot that needs
   it. The four this lot carried were four ten-minute reviews, and what would have been left is a lot
   of new code only, where "what does this break" answers itself.
2. The stale-merge family, which cost more rework than anything else and whose durable form is a test
   or a version column rather than a rule with a one-package reach.
3. The `docs/openapi.json` hook hole, which lets an API change land unpublished whenever the author
   followed the repository's own build discipline.
4. Whether a spec angle's finding that is not applied should be recorded as refused. The
   `FAIL_ON_UNKNOWN_PROPERTIES` defect was predicted on the draft spec, left unapplied without a
   reason being written, and cost a block its first hour.
5. Block independence: the plan asserts it and nothing checks it, and it was wrong three times out of
   ten in a plan whose angles had already corrected it once.

Already applied: the red commit that does not compile, in the pitfalls above.
