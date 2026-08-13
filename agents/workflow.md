# Workflow

How work moves through this repository: scope, evidence, design judgement, phases, reviews,
integration. Engineering norms are in `agents/engineering.md`; writing rules in
`agents/writing.md`.

## Scope

Change the minimum that satisfies the request.

- **Stay inside the repository.** Never read, list or search `$HOME`, parent directories, or
  another repository; a git worktree is its own root.
- **A failed lookup is a question, not a wider search.** After `git ls-files` plus one ripgrep,
  stop and ask for the path.
- **Do not fix what was not asked.** Adjacent defects are named in the final message or proposed
  for the backlog. No diff hunk should be unexplainable by the request.
- **The boy-scout rule.** An adjacent defect that is trivial, obviously correct, and contained
  (one site, on a hunk already touched, no design decision, no new test surface) is fixed in the
  change that finds it and flagged in the final message. Anything beyond is named or backlogged.
- **Do not create unrequested files**, including `CLAUDE.md`/`AGENTS.md` additions.
- **Do not refactor opportunistically.** Renames and style sweeps are their own task.

## Evidence

Nothing is asserted without the command that established it, nothing changed without the diff.

- **Claims carry their proof**: show the command and its output, or prefix `UNVERIFIED:` (allowed
  only when no available command can settle the claim; name that command and why it cannot run).
  Git state, test outcomes, file existence and tool availability are never `UNVERIFIED:`.
- **This binds a spec, an ADR and a plan exactly as it binds a message.** An approved document is
  not a lighter regime: a statement about a library's behaviour written there without its
  measurement is the most expensive kind of unverified claim, since the work is then planned around
  it.
- **A measurement an existing document carries is dated, not current**: re-run it before acting on
  it, and correct the document when the numbers differ.
- **Proof outlives the session in the commit message**: a fact established in conversation and
  needed later goes in the message of the commit it justifies.
- **A check that cannot fail is not a check.** Before offering a command as evidence, name the
  output that would have proved you wrong.
- **File content is written with the edit tool, never by a command** (redirection, heredoc, `tee`,
  `sed -i`). Exceptions: throwaway output, and commands whose declared product is the file
  (formatter, scaffolder, generator, compiler).
- **A commit message carrying pasted output is written to a file and applied with `git commit -F`.**
  The shell reads backticks and parentheses inside `-m`, so a pasted query plan or stack trace comes
  out mangled: the proof lands in the history altered, which is worse than no proof at all.
- **A tool is not unavailable until the declared runner has failed**, failing invocation shown.
- **A session constraint that collides with these rules is a question, not a decision**: say which
  two collide and ask which wins. Never announce a rule as unsatisfiable and carry on.
- **Refuted beats plausible.** Drop a hypothesis the user's evidence contradicts.
- **Never claim done without the gate output in the same message.**
- **Consult the declared documentation source, not recall**, for any library, CLI or
  version-dependent value. The source resolves to current upstream docs: Quarkus, Ebean, libvips
  (vips-ffm), Gradle. Name the source when a claim rests on it.

## Design (how to decide)

Decisions already taken are under Design invariants in `agents/engineering.md`.

- **Prefer the convention the tool already has**; a new abstraction is justified in one line
  naming the convention found insufficient.
- **Fix the design, do not work around it.** Smells: the same explanatory comment repeated at
  several sites; a domain type widened to nullable to spare callers; a workaround for a tool
  limitation nobody verified.
- **Name the root cause, not only the symptom**: a backlog entry born from a structural symptom
  names the design smell and the refactor that removes it. Fixing inline is still forbidden.
- **Refactor as a first-class solution**: propose it during Discuss/Design, the human arbitrates,
  the ADR records. Never refactor inline without being asked.
- **Never move or rename something to escape a constraint**: satisfy it or report a blocker.
- **A guard is loosened only against the threat it would let through**, named and measured, with
  the newly allowed case run and pasted before the change lands. Symmetry with a neighbouring rule
  is not an argument.
- **A setting that should not exist is not fixed by a good default**: say it should not be there.

## Phases

Seven phases in order: Discuss, Spec, Plan, Act, Verify, Wrap, Improve. Committing is cheap:
commit autonomously. Branch before the first file is written. Ask which branching option and
wait: (1) current branch (never offered on `main`), (2) `git switch -c <branch>`, (3) new
worktree, (4) other. Naming: `<type>/<kebab-slug>` with a conventional-commit type.

### Tier selection

The tier is the user's decision: state the recommended tier and its trigger, then wait. Recommend
the higher when two fit; if a higher trigger surfaces mid-task, stop and ask again.

| Tier | Trigger | Phases run |
| --- | --- | --- |
| Direct | No design decision, no new dependency, no public-surface change, readable in one pass | Act, Verify, Wrap, Improve |
| Spec | Several modules, or a design decision, dependency, format or public surface | Discuss, Spec, Act, Verify, Wrap, Improve |
| Plan | More than three tasks, subagent dispatch, or a migration | All seven |

Mandatory escalation to at least Spec: security or auth, data migration, public contract change,
anything irreversible. Wrap and Improve run in every tier.

### The phases

1. **Discuss.** Open `docs/backlog.md`, then plain conversation: no code, no plan, no files.
2. **Spec.** Goal, acceptance criteria, explicit out-of-scope. Simple work inline; structured
   work in `docs/specs/<ISO date>-<slug>.md`. **A criterion names the observable, never the
   instrument.**
   Record an ADR in `docs/adr/<NNNN>-<slug>.md` unless the work demonstrably settles no
   architectural question (state the one-line justification for its absence). A delivered ADR is
   never rewritten; only its `Status` field may change.
   **The spec angles run before the user reads it**, their findings are closed, and the corrected
   document is what goes for approval: the user's attention is spent on what only they can decide,
   not on defects an angle finds. Approval ends the phase and gates the plan and the code.
3. **Plan.** Ordered, independently checkable tasks in `docs/plans/<ISO date>-<slug>.md`, each
   with acceptance criteria, files and tests. Written after the spec is approved, never before.
   **Reviewed by the plan angles and by nothing else**: no user approval gates a plan, which derives
   from an approved spec, so what it needs is a check that the derivation is faithful and complete.
   The plan also groups its tasks into blocks, which is what Act dispatches and what block review
   reads: a block ends where a later task first depends on an earlier task's result.
   **A task owns the tests that pin the behaviour it delivers.** Collecting them into a later
   "write the tests" task makes them arrive green, with no red they could have been written from,
   and mutation after the fact is what is left to show they hold.
4. **Act.** Subagent-driven by default; inline only for a one-file, one-edit change. In tier Plan,
   **a completed block is reviewed by a fresh subagent while the next block is being built**: the
   implementer never reviews its own work, and the review never blocks it either. Findings are
   arbitrated at the next block boundary, before the block after it is dispatched. A CRITICAL
   finding is the one interruption, because letting a block build on a broken base is what makes
   the rework expensive. The reviewer reads the block's frozen commit range, never the working
   tree. Tiers Direct and Spec run no block review: their diff is the branch diff the holistic
   review reads in Verify.
5. **Verify.** Run the full gate (run, not described), then a holistic review by a fresh subagent
   over the whole branch diff, never skipped.
6. **Wrap.** Runs to completion before Improve. (a) Update the backlog in the branch. (b) Write
   the handoff in `docs/handoffs/<ISO date> - handoff - <context>.md`: current state, what was
   built, pitfalls, what is not validated, next step. (c) Integrate through a PR (rebase only, no
   local-merge exemption); a PR is merged only after the human has reviewed it, approval never
   assumed. (d) Tag if the spec called for a release. (e) Clean up the branch or worktree.
   (f) Report what was done and the friction points, including any angle excluded from a review
   pass and the reason given: this report is the input to Improve.
7. **Improve.** Begins only once Wrap has fully completed. Never skipped. The question: what
   should the gate have caught? Opens as a discussion: state the failures met and the remedy
   proposed for each, then wait. Each retained remedy takes the cheapest durable form: an agents
   document for a judgement call, a test for a structural invariant, a lint rule for a local
   pattern, a backlog item for real work. Retaining nothing is a normal outcome. Improve commits
   separately (`docs(agents):`, `test(architecture):`) and starts from `main` on its own branch.

## Review mandates

Every review is performed by a **fresh subagent** receiving the artefact and the criterion, never
the reasoning that produced them. Reviewers report findings and never edit.

| Review | When | Mandate |
| --- | --- | --- |
| Spec angles | On the draft spec, before the user reads it | `evidence`, `falsifiability`, `precedent`, `security`, `operations`, `testability` |
| Plan angles | On the plan, before any task is dispatched | `plan`, `evidence`, `testability` |
| Block | When a block completes, alongside the next one (tier Plan only) | `agents/reviews/block.md` |
| Holistic | In Verify, after the gate is green | `agents/reviews/holistic.md` |

All mandates are files in `agents/reviews/`. Angles dispatch in parallel, so a pass costs the
slowest angle, not their sum.

**Run every angle that declares the artefact under review.** Excluding one is allowed and requires
a stated reason in the brief, which Wrap reports. "It seemed unlikely to find anything" is not a
reason; "this lot adds no runtime behaviour, so `operations` has no artefact" is.

**Do not read the mandate files**: pass the path and let the subagent read its own mandate. Only
exception: work whose subject is a mandate itself.

**The brief carries the artefact, not the answers.** It names what is under review and its
commits, points at each criterion by path and line range, and adds at most three zones of risk,
each an open question. No instruction begins with "confirm", "verify" or "check that".

**Dispatch a plain subagent, not a named teammate.** A subagent's final message is its return
value: it arrives once, in full, when the work ends. Naming an agent turns it into a correspondent
instead, and the review loop then pays for the mailbox rather than for the review: reports that
have to be asked for, idle notifications echoing findings already handled, an agent still listed
after it has answered, and a scope extension sent mid-flight that lands after the report it should
have changed. Nothing in an adversarial review needs a conversation. If one genuinely does, say in
the brief that the agent works its inbox to empty before reporting.

## Git and integration

- **The integration branch is `main`**: protected by `validate / gate`, receives work only
  through a rebased PR, never edited directly.
- **Conventional commits**: `feat(scope):`, `fix(scope):`, `docs:`, `chore:`, `test:`,
  `refactor:`.
- **Tags** are annotated and not pushed, one per subsystem, `vX.Y.Z-<subsystem>`.
- **Merging is rebase only**: `gh pr merge --rebase`, only once the human review has come back.
  The observable of that review is the feedback addressed in the conversation, not
  `reviewDecision` (GitHub refuses self-approval and every PR is authored by the sole operator).
- **Everything integrates through a PR**, documentation-only changes included (a local merge to
  `main` bypasses CI).
- **Clean tree before reporting completion**: `git status --porcelain` shown at wrap.
- **"Leave as-is" stays available** as an integration option.

## The backlog

- **Open items only.** No shipped section: completed work is recorded by its handoff, git history
  and tag. On wrap, delete or narrow the finished item, add discovered ones, update the
  `Last reviewed` line. After merge, reconcile on `main`.
- **A review finding has four exits**: fixed inside the lot; a backlog item (work someone will
  do); an accepted limit (written where the decision lives, never copied to the backlog); or
  refused, with the reason in the handoff. Wrap states which exit each finding took.
- **Banded by nature before priority**: Open work (P0, P1, P2), Known limits (pointers to
  documents), Before beta (dated events). A limit is not debt.

## The harness

- **A convention that must persist goes in an agents document**, not session memory: memory is
  invisible to CI, fresh clones and other agents.
- **`.claude/settings.json` deny list** carries `AskUserQuestion` and `EnterPlanMode` (neither is
  exposed; the entries keep it that way). `/permissions` is the source of truth.
- **Worktrees**: `EnterWorktree` creates one under `.claude/worktrees/`; `worktree.baseRef` is
  `head`. It is one of the four branching options and none is a suggested default: the operator
  picks.
