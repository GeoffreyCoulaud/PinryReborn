# Move the review budget upstream

Date: 2026-08-13
Status: Draft
ADR: `docs/adr/0014-review-budget-upstream.md`

## Goal

Stop paying for a review at every completed task, and spend that budget where the measurements say
it returns most: on the spec and the plan, before any code exists.

The workflow reviews every task on completion, in every tier, by a fresh subagent. That rule was
written when a review cost about 250k tokens and four minutes. It now costs about 1 Mtok and sits on
the critical path, because no task starts while a review runs. This lot changes when reviews happen
and how many there are. It does not change what a review looks for: the mandates keep their
criteria, and no engineering norm moves.

## Context: what was measured

All figures come from the session transcripts under
`~/.claude/projects/-home-geoffrey-Repositories-pinry-reborn-api`, over 338 subagents and 17 lots
between 2026-07-15 and 2026-08-13. Token counts are normalised to input-token equivalents (cache
write 1.25x, cache read 0.1x, output 5x). The scripts that produced them were throwaway and are not
kept; the numbers are recorded here because the decisions rest on them.

**Cost.** Task reviews are the second largest subagent line after implementers: 100 reviews plus 14
follow-up fixup agents, 67.5 Mtok, against 27.1 Mtok for the 19 holistic reviews and their 5 fixups.
That is 10 % of all measured spend, and 17 % to 29 % of a session that uses them.

**Latency.** The parallelism factor measured on every session is 1.0: no two reviews ever overlap,
and no review overlaps an implementer. Between two consecutive implementers, the session spends
33.2 hours building nothing; 16.1 of those hours are windows containing a task review, while the
reviews themselves account for 7.1 hours. The remaining 9 hours are the main loop writing briefs and
arbitrating reports. Against 20.9 hours of actual implementation, the task-review cycle is 44 % of
the time the work moves forward. The median window is 5.2 minutes and the p90 is 37.9 minutes, with
three windows above two hours.

**Return.** Counting severity-headed findings in each reviewer's final report:

| Review | n | CRITICAL | MAJOR | MINOR | serious per review | Mtok per serious finding |
|---|---:|---:|---:|---:|---:|---:|
| Upstream (plan, spec) | 12 | 5 | 20 | 28 | 2.08 | 0.58 |
| Holistic | 24 | 0 | 11 | 37 | 0.46 | 2.46 |
| Task | 114 | 6 | 26 | 72 | 0.28 | 2.11 |

Per finding, a task review costs what a holistic review costs. It is not less efficient; there are
simply 4.75 times more of them. Upstream review returns four times better than either, which is what
this lot acts on.

The counts are floors: the detector reads severity headings, and reports written in free prose are
undercounted. The bias applies equally to the three populations, so the ratios hold.

**What each review catches is not interchangeable.** Task reviews caught defects no holistic review
could have caught the same day: the plan's T2 scope note that was false about the rollback paths
(`docs/handoffs/2026-07-27 - handoff - periodic-gc.md`), the half-covered `reapExpired` path
(`2026-07-30 - handoff - end-of-audited-base-model.md`), and the guard fix that was measured to open
a hole and was reverted (`2026-08-13 - handoff - consolidate-agents-instructions.md`). Holistic
reviews caught what task reviews structurally could not: the claim-then-drop orphaning bug that
"every per-task review had waved through" (`2026-07-08 - handoff - task-queue.md`), T6's incomplete
logging after it had passed its task review (`2026-08-02`), the boards membership Critical
(`2026-07-20`), and the migration drop-blindness (`2026-08-12`). Removing task review entirely would
lose a class of defect rather than move it, so it is not removed.

## What changes

### 1. Task review becomes block review, in tier Plan only

The review runs at block boundaries, not at every task. A block is the plan's own grouping; where a
plan states none, a block ends where a later task first depends on an earlier task's result.

Tier Direct loses task review outright: its task diff is the branch diff, so the holistic review in
Verify reads the same code with a mandate that covers it. Tier Spec loses it for the same reason,
since it has no plan and therefore no block boundary. Both keep the holistic review, which is never
skipped.

`agents/reviews/task.md` becomes `agents/reviews/block.md`, and its framing changes from one task to
one block. Its twelve criteria are unchanged. The `Read the ranges your brief names` instruction now
scopes to the block's commits.

### 2. The block review runs asynchronously, one block behind

Implementation of block N+1 starts as soon as block N is complete. The review of block N runs
alongside it, and its findings are arbitrated at the next block boundary, before block N+2 is
dispatched. A CRITICAL finding is the one interruption: it stops the block in flight, because
letting a block build on a broken base is what makes the rework expensive.

This trades a certain block-long stall for a measured risk of rework. Over 114 reviews the rate of
serious findings was 0.28 per review, so about one block in four carries a finding worth acting on
and about one in sixteen a CRITICAL. The operator accepted that trade explicitly.

The reviewer reads a frozen commit range, never the working tree: `git show <sha>` or a detached
worktree on the block's last commit. Two agents writing in one tree is the incident recorded in
`docs/handoffs/2026-08-06 - handoff - unique-index-named-outcomes.md`, and asynchronous review is
exactly the condition that would reproduce it.

### 3. An angle is a mandate, and the upstream pass runs all of them by default

The upstream pass adds one subagent per angle, dispatched in parallel at the end of Spec and at the
end of Plan. The main agent selects angles from the closed list below. **The default is all angles
that declare the artefact under review**; excluding one requires a stated reason in the brief, and
that reason is reported at wrap.

Each angle is one file in `agents/reviews/`, beside the mandates already there. The name carries the
angle; there is no subdirectory. The seven are derived from the defects the handoffs record, and
each one below names the handoff that motivates it.

| Angle | Artefact | Motivating defect |
|---|---|---|
| `evidence.md` | spec, plan | The spec, the ADR and the plan all asserted that nesting `inTransaction` opened two transactions; the plan review asked for the measurement and refuted it (`2026-08-13 - persistence-p2-debt`). ADR 0006 credited an automatic predicate with 26 navigations it never touched, which cost three plan revisions (`2026-07-29`). A spike "confirmed" a CDI behaviour without removing the other resolution path (`2026-07-28`). |
| `falsifiability.md` | spec | "The generator reports no change" reported no change on an untouched tree too (`2026-08-06`). A `<dropIndex>` satisfied an assertion meant to require a create, and the wording came from the specification. A bound on tracked keys was specified, implemented and reviewed twice as a memory defence, and bounded no memory (`2026-08-13 - auth-attempt-limiting`). |
| `precedent.md` | spec | `BoardRecycleBin` ordered ownership against state differently from `PinRecycleBin`, returning 404 where the spec required 409 and 409 where it required 403 (`2026-07-20`). `compileOnly` took the implementation JAR where the convention is the API JAR (`2026-07-26`). `application.properties` restated defaults the annotations already carried (`2026-08-13`). |
| `security.md` | spec | The SSRF guard checks only the first resolved IP and re-resolves at connect (`2026-07-10`). The attempt-limiting key was the submitted name in full, so sixty megabyte names retained tens of megabytes (`2026-08-13`). |
| `operations.md` | spec | A failing rendition store orphaned one temp file per request into a tmpfs (`2026-07-16`). The cross-filesystem fallback is the common path, not the rare one, and needed `REPLACE_EXISTING`. Fetch timeouts must stay below the task lease or a second worker double-runs (`2026-07-10`). `@ApplicationScoped` without `@Startup` turned an invalid policy into a clean boot followed by 500 on every authentication (`2026-08-13`). |
| `testability.md` | spec, plan | The spec named reap as a path to cover and only retry was covered (`2026-07-30`). An import-list check created a null branch `lint()` fixtures cannot reach, against a 100 % branch bound (`2026-08-03`). T6's WARN was invisible to the gate because the logging convention asserts outcomes, not logs (`2026-08-02`). |
| `plan.md` | plan | The existing mandate, unchanged, kept as the generalist angle: it caught the missed test deletion (`2026-07-28`), the files a signature change forces (`2026-07-30`) and the check that could not fail (`2026-08-06`). |

A lot in tier Spec runs the five spec angles; a lot in tier Plan runs those five, then the three
plan angles at the end of Plan.

### 4. The angles run before the human reads, and the plan never reaches them

The spec angles dispatch on the draft spec, their findings are arbitrated, and only the corrected
spec goes to the operator. Approval is the last step of the phase, not the first: the operator reads
a document five angles could not fault, and spends their attention on what only they can decide.

The plan is written after that approval, never before. It is reviewed by the three plan angles and
by nothing else: no operator approval gates it. A plan is a derivation from an approved spec, so
what it needs is a check that the derivation is faithful and complete, which is what the angles do.

The full order in tier Plan:

1. Spec drafted.
2. Five spec angles dispatched in parallel; findings arbitrated; spec corrected.
3. Spec submitted to the operator. Approval ends the phase.
4. Plan written.
5. Three plan angles dispatched in parallel; findings arbitrated; plan corrected.
6. Act begins.

In tier Spec, steps 4 and 5 do not exist.

### 5. What does not change

The models are unchanged: reviewers keep running at the session's model and effort. The holistic
review, its mandate and its place in Verify are untouched. The four exits of a review finding
(ADR 0010) apply to angle findings exactly as they apply to the others.

## Acceptance criteria

1. `agents/workflow.md` states the review regime: the tier table shows which tiers run block review,
   the Act phase describes the one-block-behind dispatch and the CRITICAL interruption, and the
   review mandate table lists the angles with their artefacts.
2. `agents/reviews/block.md` exists, `agents/reviews/task.md` does not, and the new file frames its
   subject as a block of tasks and its reading scope as the block's commit range.
3. Seven angle mandates sit directly in `agents/reviews/`, one file per row of the table above, each
   stating the artefact it reviews and reporting in the `SEVERITY | file:line | issue | suggested
   fix` format the other mandates use.
4. Every angle mandate states at least one criterion whose motivating defect is traceable to a
   handoff, and every criterion is phrased as a question about the document under review.
   `agents/reviews/holistic.md` asks its questions of a diff; an angle asking the same question of a
   spec is the point of this lot, so overlap of subject is expected and copied wording is not.
5. `agents/workflow.md` states that excluding an angle requires a reason in the brief and that wrap
   reports the exclusions.
6. `agents/workflow.md`'s phase 2 places operator approval after the spec angles have reported and
   their findings are closed, and its phase 3 states that the plan is written after that approval
   and reviewed by the plan angles alone, with no operator approval gating it.
6. `docs/adr/0014-review-budget-upstream.md` records the decision, the measurements it rests on and
   the accepted rework risk.
7. `docs/backlog.md` carries an item to re-measure the three quantities this lot moves (share of
   spend, hours between implementers, findings per review) after three lots have run under the new
   regime.
8. `./gradlew gate` is green, and `checkNoLongDashes` passes over the new documents.

## Out of scope

- **The reviewer's model and effort.** Measured as the largest single lever on cost (about 4x
  between sonnet-5 and opus-5 at equal mandate), and explicitly declined by the operator.
- **Any engineering norm.** No rule in `agents/engineering.md` changes.
- **The holistic mandate.** Unchanged, including its overlap with the new angles: an angle that
  looks upstream at what holistic looks at downstream is the point, not a duplication to resolve.
- **A tool that measures the review regime.** The scripts behind the figures above were throwaway.
  Whether the repository should own a transcript-measuring tool is a backlog question, not this lot.
- **The plan review's own timing.** It stays synchronous: nothing is dispatched from a plan that has
  not been reviewed.
