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
- **An adjacent defect has three tiers** (`docs/adr/0018-a-block-is-a-pull-request.md`, decision 5).
  No diff hunk should be unexplainable by the request or by the answer the operator gave.
  1. **Trivial, obviously correct and contained** (one site, on a hunk already touched, no design
     decision, no new test surface): fixed in the change that finds it, flagged in the final
     message. This is the boy-scout rule.
  2. **Larger, and reachable inside this lot**: stop and ask the operator **at the moment of
     discovery**, stating the defect, the size of the fix and the block it would join. Not at the
     next boundary: the operator prefers being interrupted while the context is live over
     rebuilding it afterwards. This tier is where the backlog drains.
  3. **Refused by the operator, or genuinely another lot's**: the backlog.
- **Asking is not optional and neither is stopping.** Tier 2 is a question, so the work waits for
  the answer. Fixing it unasked and backlogging it silently are both wrong.
- **Do not create unrequested files**, including `CLAUDE.md`/`AGENTS.md` additions.
- **Do not refactor opportunistically.** Renames and style sweeps are their own task.

## Evidence

Nothing is asserted without the command that established it, nothing changed without the diff.

- **Claims carry their proof**: show the command and its output, or prefix `UNVERIFIED:` (allowed
  only when no available command can settle the claim; name that command and why it cannot run).
  Git state, test outcomes, file existence and tool availability are never `UNVERIFIED:`.
- **This binds a spec and an ADR exactly as it binds a message.** An approved document is
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

Discuss and Spec run once for the lot. Then **Act, Verify and Integrate run once per block, in
series**: a block's pull request is merged before the next block starts. Wrap and Improve close the
lot. Committing is cheap: commit autonomously.

**Each block is its own branch off `main`**, named `<type>/<kebab-slug>` after the block's subject,
with a conventional-commit type. Branch before the first file is written. Ask which branching
option and wait, once for the lot: (1) current branch (never offered on `main`), (2)
`git switch -c <branch>`, (3) new worktree, (4) other. **The answer sets the shape every later
block reuses**, not the branch itself: after a block merges the tree is back on `main`, where (1)
is not offered, so (1) can only ever apply to the first block and the rest follow with (2).

### Tier selection

The tier is the user's decision: state the recommended tier and its trigger, then wait. Recommend
the higher when both fit; if the higher trigger surfaces mid-task, stop and ask again.

| Tier | Trigger | What runs | Reviews |
| --- | --- | --- | --- |
| Direct | One block: no design decision, no new dependency, no public-surface change, readable in one pass | Act, Verify, Integrate, Wrap, Improve | Block review |
| Spec | Anything else | Discuss, Spec, then Act, Verify and Integrate per block, Wrap, Improve | Three spec angles; one block review per block; holistic on the last block, all before any pull request opens |

Mandatory escalation to Spec: security or auth, data migration, public contract change, anything
irreversible. Wrap and Improve run in both tiers.

**Tier Spec always runs the holistic review, whatever its block count.** Only tier Direct skips it,
and only because its own trigger excludes everything that mandate is about: no design decision, no
dependency, no public surface. The block mandate is deliberately narrower and disclaims reading
across blocks, so it is not a substitute. A one-block lot that escalated to Spec (a migration, an
authorization change) gets both reviews over the same diff, by two mandates that do not overlap.

### What a block is

**A block is the smallest change that can be merged to `main` on its own**
(`docs/adr/0018-a-block-is-a-pull-request.md`, decision 1). Three conditions:

- **Green alone.** `./gradlew gate` passes at the block's tip. A block therefore never ends between
  a red test commit and the implementation that answers it.
- **Coherent alone.** Nothing it adds is unreachable: every new port method has a caller, every
  configuration key is read, every new state is produced somewhere. Where a surface's real consumer
  arrives in a later block, the spec says so and the pull request repeats it.
- **Readable alone.** The diff excluding dated documents stays under 600 lines, of which under 200
  are production code. Past that the block splits, or the spec states in one line why it cannot.

**A block also ends where a later task first depends on an earlier task's result.** That rule says
where the cut is forced; the three conditions say where it happens anyway.

**A block owns the tests that pin the behaviour it delivers.** Collecting them into a later "write
the tests" block makes them arrive green, with no red they could have been written from, and
mutation after the fact is what is left to show they hold.

### The phases

1. **Discuss.** Open `docs/backlog.md`, then plain conversation: no code, no plan, no files.
2. **Spec.** One document, `docs/specs/<ISO date>-<slug>.md`: goal, acceptance criteria, explicit
   out-of-scope, and the block table. **Written to a file whatever its size**: an inline spec gives
   the angles no artefact to point at, and the pass silently does not run. **A criterion names the
   observable, never the instrument.**
   **The block table is the plan, and it is a table**: one row per block, naming what the block
   delivers, the files it touches, and the acceptance criteria it satisfies. Criteria live in the
   spec and are not restated per row. Nothing more is written, because the implementer is the agent
   that read the spec, not a subagent needing a self-contained brief.
   **The spec names the backlog items adjacent to its subject and closes them**, or states which it
   leaves and why (The backlog, below).
   Record an ADR in `docs/adr/<NNNN>-<slug>.md` unless the work demonstrably settles no
   architectural question (state the one-line justification for its absence). A delivered ADR is
   never rewritten; only its `Status` field may change.
   **The three spec angles run before the user reads it**, their findings are closed, and the
   corrected document is what goes for approval: the user's attention is spent on what only they
   can decide, not on defects an angle finds. Approval ends the phase and gates the code.
3. **Act.** One block, **inline**: the implementation is written by the main loop, not dispatched
   to a subagent. A block that exceeds the budget anyway may still be dispatched, and the brief
   says why. Strict TDD as `agents/engineering.md` states it. An adjacent defect found here takes
   one of the three tiers under Scope, and tier 2 stops the work to ask.
4. **Verify, entirely on the local branch. No pull request exists yet.** Run the full gate (run, not
   described). **On the last block of the lot, write Wrap's documents first** (phase 6a and 6b):
   they belong in this block's diff, and a review that runs after them reads them.
   Then a **block review** by a fresh subagent over the block's commit range: the implementer never
   reviews its own work. In tier Spec, on the last block, the **holistic review** runs too, over
   `git diff <lot base>..HEAD`, so it reads the whole lot. Findings are closed here, on the branch.
   **Opening a pull request is handing it over, so it belongs to the next phase and not this one.**
   There is no such thing as an open pull request the user has not been offered: it is in their
   list, it notifies them, and they can merge it. A review still running while one is open is a
   review whose findings can arrive after the merge.
5. **Integrate.** Push, open the pull request, then **wait for its continuous integration run to
   settle and hand the user the link with the result named**, green or not. Handing over a link
   while the run is pending only moves "the merge precedes the evidence" from the reviews onto
   continuous integration: `enforce_admins` is false, so nothing but this rule holds the merge.
   Measured on PR #74, whose `validate / gate` reported success 4 minutes 45 after the merge.
   The body says what the diff does not say for itself, in particular any surface whose consumer
   arrives in a later block. **It is merged only after the human has reviewed it** (rebase only,
   no local-merge exemption), approval never assumed. Then
   clean up the branch or worktree, and the next block starts from `main`.
6. **Wrap.** Once per lot. Two halves, and the first runs inside the last block, before its Verify:
   (a) the backlog reconciled, an item closed by a block having been deleted in that block's own
   pull request; (b) the handoff in `docs/handoffs/<ISO date> - handoff - <context>.md`: current
   state, what was built, pitfalls, what is not validated, next step. The second half runs after
   that pull request merges: (c) tag if the spec called for a release; (d) report what was done and
   the friction points, including the third spec angle chosen with the reason given for it, and
   every tier-2 question asked with the answer it got. That report is the input to Improve.
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
| Spec angles | On the draft spec, before the user reads it | `evidence` and `falsifiability` always, plus one of `precedent`, `security`, `operations`, `testability` (three) |
| Block | In Verify, after the gate is green, on the branch, before any pull request opens | `block` |
| Holistic | In Verify on the last block of a tier-Spec lot, whatever its block count, on the branch, before any pull request opens | `holistic` |

Every mandate is `agents/reviews/<name>.md`; the table names them without the path. Angles dispatch
in parallel, so a pass costs the slowest angle, not their sum.

**Two spec angles are fixed and the third is chosen** (`docs/adr/0018-a-block-is-a-pull-request.md`,
decision 8). `evidence` and `falsifiability` always run: a false premise and a criterion nobody can
fail are the two defects cheapest to fix in a document and most expensive anywhere else. **The spec
names its third angle and the reason it was chosen**, and Wrap reports it. "It seemed the most
likely to find something" is not a reason; "this lot changes an authorization order, so `security`
owns it" is. Where no subject stands out, say that and pick `testability`.

**Do not read the mandate files**: pass the path and let the subagent read its own mandate. Only
exception: work whose subject is a mandate itself.

**The brief carries the artefact, not the answers.** It names what is under review and where it
lives (a commit range for code, a path for a document), points at each criterion by path and line
range, and adds at most three zones of risk, each an open question. No instruction begins with
"confirm", "verify" or "check that".

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
- **One block, one PR, in series**: the next block does not start until the current one is merged.
  Each PR branches from `main`, so there is no stack and no cascading rebase.
- **A PR run costs about 7 minutes on the median and up to 13**, container image included (twelve
  most recent `pr.yml` runs). A lot pays that per block, serially now that Integrate waits for it,
  and that is the accepted price of the operator reading about 300 lines at a time.
- **Clean tree before reporting completion**: `git status --porcelain` shown at wrap.
- **"Leave as-is" stays available** as an integration option.

## The backlog

- **Open items only.** No shipped section: completed work is recorded by its handoff, git history
  and tag. An item a block closes is deleted in that block's PR; Wrap reconciles on `main`.
- **A lot closes the backlog items adjacent to its subject.** Binding, not advisory
  (`docs/adr/0018-a-block-is-a-pull-request.md`, decision 6): the spec names every adjacent item,
  and for each one it leaves open it states why, which is the operator's to accept. A lot with no
  adjacent item says so. An adopted item that does not fit the block's budget becomes its own
  block; it is not thereby dropped.
- **An item holds in two lines**, plus a pointer to the handoff section carrying its reasoning.
  The reasoning is already written there, and an entry long enough to need scrolling is an entry
  nobody rereads. **There is no cap on how many items the backlog holds**: a cap discards findings
  to satisfy a number, and what grew here was the entries, not their count.
- **A review finding has four exits**: fixed inside the lot; a backlog item (work someone will
  do); an accepted limit (written where the decision lives, never copied to the backlog); or
  refused, with the reason in the handoff. Wrap states which exit each finding took. The default
  is the first: the backlog receives what the operator refused or what genuinely belongs to
  another lot, not what was merely out of the original scope.
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
