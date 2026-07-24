<!-- agents-baseline v3.0.0 | generic file, identical in every project | do not edit in place -->

# AGENTS.md

Engineering baseline, byte-identical across projects: **never edit it here**. Everything situated
(context, layout, commands, gate, invariants) lives in `agents/project.md`, imported at the end; if it
is missing or declares no gate, stop and ask.

Every rule is written so breaking it is **visible in the output**: when a rule names an observable,
produce it; a rule you cannot satisfy is reported, never silently skipped. Rules govern work from
adoption onward: pre-existing violations are inventoried once at adoption and reported, never swept
or fixed as a side effect, and a frozen dated document (see Documentation) is never rewritten to
satisfy a later rule.

## Scope

Change the minimum that satisfies the request.

- **Stay inside the repository.** Never read, list or search `$HOME`, parent directories, or another
  repository; a git worktree is its own root. _Observable: every path touched is under the working tree._
- **A failed lookup is a question, not a wider search.** After `git ls-files` plus one ripgrep, stop
  and ask for the path.
- **Do not fix what was not asked.** Adjacent defects are named in the final message or proposed for
  the backlog. _Observable: no diff hunk is unexplainable by the request._
- **Do not create unrequested files**, including `CLAUDE.md`/`AGENTS.md` additions.
- **Do not refactor opportunistically.** Renames and style sweeps are their own task, proposed separately.

## Evidence

Nothing is asserted without the command that established it, nothing changed without the diff.

- **Claims carry their proof.** Any statement about system state shows the command and its output, or
  the prefix `UNVERIFIED:`, allowed only when no available command can settle the claim (name that
  command and why it cannot run). Git state, test outcomes, file existence and tool availability are
  never `UNVERIFIED:`.
- **Proof outlives the session in the commit message.** A fact established in conversation and needed
  later goes in the message of the commit it justifies; comments say why the code is shaped as it is,
  never assert unproven facts.
- **A check that cannot fail is not a check.** Before offering a command as evidence, name the output
  that would have proved you wrong.
- **File content is written with the edit tool, never by a command** (redirection, heredoc, `tee`,
  `sed -i`, applied patch). Exceptions: throwaway output (`/dev/null`, `$TMPDIR`) and commands whose
  declared product is the file (formatter, scaffolder, generator, compiler). _Observable: no diff hunk
  was produced by a command in the trace._
- **A tool is not unavailable until the declared runner has failed**, failing invocation shown.
- **Refuted beats plausible.** Drop a hypothesis the user's evidence contradicts; do not restate it weaker.
- **Never claim done without the gate output in the same message.**
- **Consult the declared documentation source, not recall**, for any library, CLI or cloud service and
  any version-dependent value (flags, ports, roles, defaults, formats); `agents/project.md` names the
  source. _Observable: the source consulted is named._ "The tool cannot do that" is a claim like any
  other: consult before designing around a limitation.

## Design

- **Prefer the convention the tool already has.** _Observable: any new abstraction is justified in one
  line naming the convention found insufficient._
- **Fix the design, do not work around it.** Three smells that the design is the defect: the same
  explanatory comment repeated at several sites; a domain type widened to nullable to spare existing
  callers; a workaround for a tool limitation nobody verified.
- **Never move or rename something to escape a constraint**: satisfy it or report a blocker.
- **A setting that should not exist is not fixed by a good default**: say it should not be there.

## Workflow

Seven phases in order: Discuss, Spec, Plan, Act, Verify, Wrap, Improve. **Committing is cheap: commit
autonomously. Branch before the first file is written, in any phase.** The integration branch is
declared in `agents/project.md` and receives work only through integration. Ask which option and wait:
(1) stay on the current branch (never offered on the integration branch), (2) `git switch -c <branch>`,
(3) new worktree, (4) other. Naming: `<type>/<kebab-slug>` with a conventional-commit type.

### Tier selection

**The tier is the user's decision.** State the recommended tier and its trigger, then wait; recommend
the higher when two fit, and if a higher trigger surfaces mid-task, stop and ask again. _Observable:
tier, trigger, and the user's answer appear before the first edit._

| Tier | Trigger | Phases run |
| --- | --- | --- |
| **Direct** | No design decision, no new dependency, no public-surface change, readable in one pass | Act, Verify, Wrap, Improve |
| **Spec** | Several modules, or a design decision, dependency, format or public surface | Discuss, Spec, Act, Verify, Wrap, Improve |
| **Plan** | More than three tasks, subagent dispatch, or a migration | All seven |

Mandatory escalation to at least Spec, regardless of size: security or auth, data migration, public
contract change (status codes, error shapes, config keys, CLI flags, file formats), anything
irreversible. The Direct trigger is the absence of a decision, not a file count. **Wrap and Improve
run in every tier** and scale down on their own.

### Phases

1. **Discuss.** Open `docs/backlog.md` (records what is already arbitrated; receives what this phase
   surfaces out of scope), then plain conversation: no code, no plan, no files. Use the
   structured-elicitation affordance named in `agents/project.md`, if any.
2. **Spec.** Goal, acceptance criteria, explicit out-of-scope. Simple work inline; structured work in
   `docs/specs/<ISO date>-<slug>.md`. **Approved by the user before any plan or code**: the only human
   review gate. **Record an ADR** in `docs/adr/<NNNN>-<slug>.md` unless the work demonstrably settles
   no architectural question. _Observable: the ADR path, or the one-line justification for its absence._
   A delivered ADR is never rewritten; only its `Status` field may change (`Superseded by <NNNN>`).
3. **Plan.** Ordered, independently checkable tasks in `docs/plans/<ISO date>-<slug>.md`, each with
   acceptance criteria, files and tests. **Reviewed by a fresh subagent before any dispatch**: plan
   defects are the most expensive to discover late.
4. **Act.** The branch already exists; where a tier skipped earlier phases, ask the branching question
   here. **Subagent-driven by default** to keep the main context clean; inline only for a one-file,
   one-edit change. **Each task is reviewed by a fresh subagent on completion**; the implementer never
   reviews its own task.
5. **Verify.** Run the full project gate as declared in `agents/project.md` (run, not described), then a
   **holistic review by a fresh subagent** over the whole branch diff, never skipped: it catches what
   per-task reviews cannot see.
6. **Wrap.** (a) Update the backlog in the branch. (b) Write the handoff in
   `docs/handoffs/<ISO date> - handoff - <context>.md`: current state, what was built, pitfalls, what
   is **not** validated against real conditions, suggested next step. (c) Integrate: anything not
   documentation-only goes through a PR so CI runs, linear history (squash or rebase, never a merge
   commit); documentation-only changes on paths declared in `agents/project.md` may merge locally,
   **except `agents/project.md` itself**, which goes through a PR like code. (d) Tag if the spec called
   for a release. (e) Clean up the branch or worktree. (f) Report what was done and the friction points
   (wrong turns, corrections, rules that did not hold): the friction report is the input to Improve.
7. **Improve.** **Never skipped, even when the work went fine.** The question: what should the gate
   have caught? A self-correction counts the same as one the user made. Opens as a discussion: state
   the **failures met** and the **remedy proposed** for each, then wait. _Observable: both lists and
   the user's answer appear before the first edit of the phase._ Each retained remedy takes the
   cheapest durable form: **`agents/project.md`** for a judgement call, **a test** for a structural
   invariant, **a lint rule** for a local pattern, **a backlog item** when the fix is real work. A
   lesson true of every project is proposed to the shared baseline (`agents-baseline`), never edited
   here. **Retaining nothing is a normal outcome**: record nothing the gate already enforces.

## Review mandates

Every review is performed by a **fresh subagent** receiving the artefact and the criterion, never the
reasoning that produced them. Reviewers report findings and never edit.

| Review | When | Mandate |
| --- | --- | --- |
| Plan | Before any task is dispatched | `agents/reviews/plan.md` |
| Task | When each task completes | `agents/reviews/task.md` |
| Holistic | In Verify, after the gate is green | `agents/reviews/holistic.md` |

**Do not read these files**: pass the path and let the subagent read its own mandate; an implementer
who knows the criteria writes to them. _Observable: no read of `agents/reviews/` in the trace of a
session that produced work._ Only exception: work whose subject is a mandate itself.

## Engineering norms

- **Clean architecture.** The domain is pure (no I/O, framework, clock, environment); I/O lives in
  adapters; the dependency graph is a DAG pointing inward.
- **Strict TDD: red, green, refactor.** Write the failing test first, **run it and show it fail**,
  write the minimal implementation, then refactor with tests green (part of the cycle, not an afterthought).
- **The failing test is committed alone, before its implementation**, as `test(scope): <behaviour>`.
  _Observable: `git log --oneline` shows the test-only commit preceding the implementation commit._ The
  commit is the only red evidence the reviewer receives.
- **Review judges the tests before the code.** A test that passes against a wrong implementation is a
  defect of the same rank as the bug it missed.
- **100% branch coverage, verified after the fact**: the audit of the TDD cycle. Uncovered code is a
  missing test or code nobody asked for; never lower the threshold: add the test or delete the code.
- **The gate perimeter is decided by location**, declared once in `agents/project.md`: inside is 100%,
  outside is not measured, no per-category exemption ("just tooling").
- **Generated artefacts are declared, not assumed.** Code no human wrote and no test can reach is not
  source, so it was never inside; `agents/project.md` names each generator and its exclusion, and a
  generator absent from the list is inside. Hand-written code calling the artefact stays inside.
- **TDD exemptions apply to the order only, never the safety net**: behaviour-preserving refactors
  (existing tests are the net), throwaway spikes (never merged as-is), configuration, documentation.
  Everything inside the perimeter still ends up covered.

## Documentation

Two regimes; `agents/project.md` declares which document belongs to which.

| Regime | Property | Rule |
| --- | --- | --- |
| **Dated** | Append-only once frozen: records what was believed on that date. | Never edit a frozen document; write a superseding one. |
| **Living** | Describes the present; drifts silently. | Updated in the same commit as the change. |

- **A dated document freezes at delivery** (branch integrated), not at writing; until then it absorbs
  changes like any work in progress. After delivery, changes go in a **new** dated document,
  cross-linked both ways, the old one marked `Status: Superseded by <file>`.
- **Simultaneity.** A living doc is updated **in the same commit** as the behaviour it describes, never
  a follow-up `docs:` commit. _Observable: doc hunk and code hunk in one commit._
- **The backlog is the pressure valve**: updated in the branch before wrap completes, writable at any
  time with the user's agreement; out-of-scope findings are proposed for it rather than done or lost.
- **Renumbering breaks anchors**: after reordering sections, re-check every cross-reference.
  _Observable: the check is run and reported._
- **An edit that does not apply is reported**, never assumed landed.
- **Comments explain why, not what.** Density matches the surrounding file.

## Conventions

- **Everything in the repository is in English** (identifiers, comments, logs, commits, PRs, docs);
  conversation stays in the user's language, and genuine domain data keeps its own language.
- **Conventional commits**: `feat(scope):`, `fix(scope):`, `docs:`, `chore:`, `test:`, `refactor:`.
- **Clean tree before reporting completion.** Tool artefacts are cleaned or gitignored, never
  committed. _Observable: `git status --porcelain` shown at wrap._
- **Never use an em dash or en dash anywhere** humans read (code, docs, commits, UI, logs). Use a
  colon, period, parentheses or hyphen. Exception: agent-only scratch artefacts.
- **A convention that must persist goes in `agents/project.md`**, not session memory: memory is invisible
  to CI, fresh clones, and other agents.

## Project

Everything below is situated: this project only.

@agents/project.md
