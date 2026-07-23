<!-- agents-baseline v2.1.0 | generic file, identical in every project | do not edit in place -->

# AGENTS.md

Engineering baseline for this project. **This file is generic and byte-identical across every
project. Never edit it here.** Everything situated (what this is, where code lives, commands,
gate, invariants) belongs to `docs/project.md`, imported at the end of this file, which also
pulls the optional modules this project needs.

If `docs/project.md` is missing or does not declare a gate, stop and ask before assuming
anything about how this project is built, tested or released.

Every rule below is written so that breaking it is **visible in the output**. When a rule states
an observable, produce it. A rule you cannot satisfy is reported, never silently skipped.

**These rules govern the work done from the moment this file enters the repository.** Content that
predates it is not swept to conform, and a frozen dated document (see Documentation) is never
rewritten to satisfy a rule adopted after it was written: the two rules would contradict each
other, and the older document is the one that records what was believed on its date. Violations
that predate the file are inventoried once, at adoption, and reported. They are not a backlog,
and correcting them is its own task, never a side effect of another one. A rule that no available
action can satisfy does not raise the standard, it teaches that the standard is negotiable.

## Scope

The default is to change the minimum that satisfies the request, and nothing else.

- **Stay inside the repository.** Never read, list or search `$HOME`, parent directories, or
  another repository. *Observable: every path you touch is under the root of the working tree you
  were given*, a git worktree being that root even when it sits beside the main checkout.
- **A failed lookup is a question, not a wider search.** If a referenced file is not found after
  `git ls-files` plus one ripgrep over the repo, stop and ask for its path. Do not broaden.
- **Do not fix what was not asked.** An adjacent defect is named in the final message and left
  alone, or proposed for the backlog (see Documentation). *Observable: no hunk in the diff is
  unexplainable by the request.*
- **Do not create unrequested files.** No new config, override, helper, doc or rule file unless
  the task requires it or the user asked. This includes `CLAUDE.md`/`AGENTS.md` additions.
- **Do not refactor opportunistically.** Renames, reorganisations and style sweeps are their own
  task, proposed separately.

## Evidence

Nothing is asserted about the state of the world without the command that established it, and
nothing is changed without the diff that shows it.

- **Claims carry their proof.** Any statement about system state (a test passes, a tool is
  missing, a port is bound, a role is sufficient, a file exists, a version is current) is either
  accompanied by the command and its output, or prefixed `UNVERIFIED:`. *Observable: the prefix
  or the command is literally present.*
- **`UNVERIFIED:` is bounded, or it becomes the way out of the rule above.** It is for a statement
  you cannot settle with a command available to you, and it names the command that would settle it
  and why you cannot run it. A claim you could have checked in one command is checked, not
  prefixed. Git state, test outcome, file existence and tool availability are never `UNVERIFIED:`.
- **A check that cannot fail is not a check.** Before offering a command as evidence, name the
  output that would have proved you wrong. A one-liner whose body is comments and `print`
  statements, or an assertion on a literal, passes against any state of the code and therefore
  establishes nothing. *Observable: the command shown could have failed.*
- **File content is written with the edit tool, never by a command.** A redirection, a heredoc,
  `tee`, `sed -i`, `dd` or an applied patch changes a file without ever showing what changed: the
  user reviews a diff, and a shell write produces none. *Observable: no hunk in the diff was
  produced by a command in the trace.* Two things fall outside this rule: output sent to a
  throwaway location (`/dev/null`, `$TMPDIR`), and a command whose declared product is the file it
  writes (a formatter, a scaffolder, a migration generator, a compiler). Content you wrote
  yourself is never that.
- **A tool is not unavailable until the project's declared runner has failed.** Try the runner
  declared in `docs/project.md` (for example `uv run`, `uvx`, `./gradlew`, `npx`) before
  reporting that anything is missing. *Observable: the failing invocation is shown.*
- **Refuted beats plausible.** When the user provides evidence contradicting a hypothesis, drop
  the hypothesis and say so. Do not restate it in weaker form.
- **Never claim done without running the gate.** "It works", "tests pass", "it is fixed" require
  the gate output in the same message. *Observable: gate command and its exit shown.*
- **Consult current documentation rather than recall**, for any library, framework, SDK, CLI or
  cloud service, and for every value you would otherwise guess: ports, flags, roles, status codes,
  defaults, file formats. Version-dependent facts are where recall fails.

## Design

- **Prefer the convention the tool already has.** Before introducing an alias, wrapper, indirection
  or naming scheme, check whether the tool, framework or ecosystem already provides one.
  *Observable: any new abstraction is justified in one line, naming what existing convention was
  found insufficient.*
- **Never move, rename or relocate something to escape a constraint.** If a constraint blocks a
  design, either satisfy it or report it as a blocker. Working around it by changing where a thing
  lives is a defect, not a solution.
- **A setting that should not exist is not fixed by a good default.** If the right answer is that
  the option should not be there, say that instead of tuning it.

## Workflow

Seven phases, always in this order. **Committing is cheap: commit autonomously.**

1. **Discuss** : understand the intent. No artefact.
2. **Spec** : approved by the **user**, plus an ADR.
3. **Plan** : reviewed by a **fresh subagent** before any dispatch.
4. **Act** : each task reviewed by a **fresh subagent** on completion.
5. **Verify** : the project gate, then a holistic review by a **fresh subagent**.
6. **Wrap** : backlog, handoff, integration, friction report, next steps.
7. **Improve** : turn what the gate missed into what it catches next time.

**Branch before the first file is written, in any phase.** Specs, ADRs and plans are files in the
repository, so the branching question of the Act phase is asked before Spec, not at Act. The
integration branch receives work only through integration.

### Tier selection

Not every request runs all seven phases. **The tier is the user's decision, not yours.** Classify
the request, state the tier you recommend and the trigger that produced it, and **wait for the
answer** before touching anything. If you find yourself choosing between two tiers, recommend the
higher one. If a trigger for a higher tier surfaces mid-task, stop and ask again.

| Tier | Trigger | Phases run |
|---|---|---|
| **Direct** | One file, no design decision, no new dependency, no public surface added or changed | Act, Verify, Improve |
| **Spec** | Several modules, or a design decision, or a new dependency, format, or public surface | Discuss, Spec, Act, Verify, Wrap, Improve |
| **Plan** | More than three tasks, or work dispatched to subagents, or a migration | All seven |

**Mandatory escalation to at least Spec, regardless of size:** security or authentication,
data migration, a change to a public contract (HTTP status codes, error shapes, config keys, CLI
flags, file formats), anything irreversible (deletion, force push, released artifact).

*Observable: the recommended tier, its trigger, and the user's answer all appear before the first
edit.*

### 1. Discuss

Plain conversation. No code, no plan, no files. Understand the intent and the constraints first.
Ask questions directly in the message.

### 2. Spec

The spec states the goal, the acceptance criteria, and explicitly what is out of scope. Simple
work: a few paragraphs inline in the conversation. Structured work: `docs/specs/<ISO date>-<slug>.md`.

**The spec is reviewed and approved by the user before any plan or code.** This is the only human
review gate in the workflow: everything downstream is judged against the spec, so an unapproved
spec propagates its errors into every task.

**Record an ADR in `docs/adr/<NNNN>-<slug>.md`** for the decisions the spec settles: context,
options considered, decision, consequences, status. Write it unless it is demonstrated that the
work settles no architectural question at all, and say why when skipping it. *Observable: either
the ADR path, or the one-line justification for its absence.* An ADR is never rewritten: a later
decision gets a new ADR, and the only edit permitted on an existing one is its `Status` field
(`Superseded by <NNNN>`).

### 3. Plan

The plan turns an approved spec into ordered, independently checkable tasks, in
`docs/plans/<ISO date>-<slug>.md`. Each task states its acceptance criteria, its files, and its
tests.

**The plan is reviewed by a fresh subagent before any task is dispatched** (mandate in Review
mandates).
Plan defects propagate into every task derived from them and are the most expensive class of
defect to discover late.

### 4. Act

`main` is integration only: never edit it directly. The branch already exists at this point, since
it was created before Spec.

Ask which branching option, and wait:

1. Stay on the current branch (not offered when the current branch is the integration branch)
2. New branch in place (`git switch -c <branch>`)
3. New worktree (isolated workspace, the user's editor stays on `main`)
4. Other, described by the user

Naming: `<type>/<kebab-slug>` with a conventional-commit type (`feat`, `fix`, `docs`, `chore`,
`test`, `refactor`).

**Execution is subagent-driven by default**, to keep the main context clean. Exception: a short,
localised change (one file, one edit) is done inline.

**Each task is reviewed by a fresh subagent when it completes** (mandate in Review mandates). The
implementer never reviews its own task.

### 5. Verify

1. **Run the full project gate** as declared in `docs/project.md`: format, lint, types, tests,
   coverage. The gate is run, not described.
2. **Holistic review by a fresh subagent** over the whole branch diff (mandate in Review mandates).
   This review catches cross-cutting defects that no per-task review can see. Do not skip it.

### 6. Wrap

Once the gate is green and the holistic review is resolved:

1. **Update the backlog** in the same branch (see Documentation).
2. **Write the handoff** in `docs/handoffs/<ISO date> - handoff - <context>.md`: current state,
   what was built, pitfalls learned, what is **not** validated against real conditions, suggested
   next step. Committed before continuing.
3. **Integrate.** Anything that is not documentation-only goes through a PR so CI runs before
   merge, even when you have the rights to merge locally. Linear history: squash or rebase, never
   a merge commit. Documentation-only changes (diff touches only `docs/**` and root `*.md`) may
   be merged locally. **`docs/project.md` is excluded from that exemption**: it declares the gate
   perimeter, so a change to it goes through a PR like code.
4. **Tag** if the spec called for a release.
5. **Clean up** the branch or worktree.
6. **Report**: what was done, the friction points encountered (wrong turns, corrections, rules
   that did not hold), then the suggested next steps. The friction report is the input to Improve:
   name what went wrong even when the outcome was fine.

### 7. Improve

**Not optional, and not skipped because the work went fine.** The question is what the gate should
have caught: a correction made mid-course, a bug the holistic review found, an assumption that
turned out false. A correction you made yourself counts the same as one the user had to make.

**It opens with a discussion, exactly like Discuss, and nothing in it is decided alone.** State in
plain text the **failures met** and the **remedy proposed** for each, then wait. The user validates
both lists. *Observable: the two lists and the user's answer appear before the first edit of the
phase.*

Each retained remedy takes the cheapest durable form: **`docs/project.md`** for a judgement call no
tool can check, **a test** for a structural invariant over the sources or the repository content,
**a lint rule** for a local code pattern, **a backlog item** when the fix is real work rather than a
rule. When the lesson is true of every project rather than of this one, it belongs in `AGENTS.md`,
which is generic and cannot be edited here: name it as such and propose it to the user for their
shared baseline (`agents-baseline`).

**Retaining nothing is a normal outcome.** Record nothing the gate already enforces, and prefer one
precise rule to three vague ones: a rule written to give this phase an output teaches that rules are
decoration.

## Review mandates

Every review in this workflow is performed by a **fresh subagent** that receives the artefact and
the criterion, never the reasoning that produced them. A review by the context that authored the
work is worth almost nothing. Reviewers report findings and never edit: resolving them is the
orchestrator's job.

The mandates live in their own files, one per review:

| Review | When | Mandate |
|---|---|---|
| Plan | Before any task is dispatched | `docs/reviews/plan.md` |
| Task | When each task completes | `docs/reviews/task.md` |
| Holistic | In Verify, after the gate is green | `docs/reviews/holistic.md` |

**Do not read these files.** Pass the path to the subagent and let it read its own mandate. An
implementer who knows the review criteria writes to the criteria instead of writing correct work,
and the review stops measuring anything. *Observable: no read of `docs/reviews/` appears in the
trace of a session that produced work.* The only exception is work whose subject is a mandate
itself.

## Engineering norms

- **Clean architecture.** The domain is pure: no I/O, no framework, no clock, no environment. All
  I/O lives in adapters. The dependency graph is a DAG pointing inward.
- **Strict TDD, red then green then refactor.** Write the failing test first and **run it, show it
  fail** (red). Write the minimal implementation that makes it pass (green). Then **refactor with
  the tests staying green**: remove duplication, fix naming, restore the structure the minimal
  step deliberately skipped. The refactor step is part of the cycle, not an optional afterthought.
- **The failing test is committed alone, before the commit that makes it pass**, as
  `test(scope): <behaviour>`. *Observable: `git log --oneline` over the task's commits shows a
  test-only commit preceding its implementation commit.* A red run shown only in a conversation is
  not evidence: that transcript is read by nobody, and the reviewer who judges the order receives a
  diff where it left no trace. The commit is the trace.
- **Review judges the tests before the code.** A test that would pass against a wrong
  implementation is a defect of the same rank as the bug it failed to catch.
- **100% branch coverage, verified after the fact.** Coverage is not a goal, it is the audit of the
  TDD cycle, and it answers two questions at once: is every line written to turn a test green
  actually exercised, and is there code no test ever demanded, written outside the spec? Uncovered
  code is therefore either an untested path or an unrequested feature, and both are defects. Never
  lower the threshold: add the missing test exercising both sides of the conditional, or delete the
  code nobody asked for.
- **The gate perimeter is decided by location, declared once in `docs/project.md`.** What is inside
  is covered at 100%; what is outside is not measured. There is no per-category exemption: a file
  is not spared because it is "just tooling".
- **TDD exemptions apply to the order only, never to the safety net.** Test-first is not required
  for: a behaviour-preserving refactor (the existing tests are the net and must pass unchanged),
  an exploratory spike (throwaway, never merged as-is), configuration, and documentation. Anything
  inside the gate perimeter still ends up covered.

## Documentation

Two regimes, and every document belongs to exactly one. **`docs/project.md` declares which is
which.** Guessing wrong is how a doc starts lying.

| Regime | Property | Rule |
|---|---|---|
| **Dated** (`docs/specs/`, `docs/plans/`, `docs/adr/`, `docs/handoffs/`, `docs/reference/`) | Append-only once frozen, therefore never wrong: it records what was believed on that date. | Never edit a frozen document. Write a new one that supersedes it. See the freezing rule below. |
| **Living** (`README.md`, `docs/backlog.md`, runbooks, architecture) | Describes the present. Useful immediately, drifts silently. | See simultaneity below. |

- **A dated document freezes when it is accepted, not when it is written.** A spec is mutable until
  the user approves it, a plan until its review is resolved, a handoff and a reference note from
  the moment they are committed. Once frozen, a change is a **new** dated document superseding the
  old one, cross-linked both ways, the old one carrying `Status: Superseded by <file>`.
- **Simultaneity.** A living document is updated **in the same commit** as the change it
  describes. Never in a follow-up `docs:` commit, never after the merge. If a PR changes behaviour
  a living doc describes, that PR updates the doc. *Observable: the doc hunk and the code hunk are
  in one commit.*
- **The backlog is updated in the branch before the wrap completes**, and it is also the pressure
  valve: writing to it is allowed at any time with the user's agreement, most usefully during
  Discuss and Spec. When something out of scope surfaces (an adjacent defect, a better design for
  later, a follow-up the spec excludes), propose adding it rather than doing it or losing it. This
  is how scope discipline stays cheap instead of feeling like amnesia.
- **Renumbering breaks anchors.** After renumbering or reordering any section, re-check every
  cross-file reference and link that pointed into it. *Observable: the check is run and reported.*
- **An edit that does not apply is reported.** If a documentation edit fails to match its target,
  say so. Never assume it landed.
- **Comments explain why, not what.** No tutorial prose, no restating the code. Density matches
  the surrounding file.

## Conventions

- **Everything written into the repository is in English**: identifiers, comments, docstrings,
  runtime messages and logs, CI step names, commit messages, PR titles and bodies, and all
  documents under `docs/`. Conversation with the user stays in the user's language. Genuine domain
  data (real titles, filenames, fixtures) is data, not prose, and keeps its own language.
- **Conventional commits**: `feat(scope):`, `fix(scope):`, `docs:`, `chore:`, `test:`, `refactor:`.
- **The working tree is clean before reporting completion.** `git status --porcelain` shows only
  intended files. Tool runs that produce artefacts (compiled fixtures, caches, reports) are cleaned
  up or gitignored, never committed. *Observable: the `git status` output is shown at wrap.*
- **Never use an em dash or an en dash anywhere.** Not in UI strings, CLI output, error messages,
  logs, READMEs, docs, commit messages, code, comments or docstrings. Use a colon, a period,
  parentheses, or a short hyphen. The only exception is text no human will ever read: scratchpad
  files, agent memories, and similar agent-only working artefacts.
- **When a convention needs to persist, write it into `docs/project.md`**, not only into session
  memory. Memory is not visible to CI, to a fresh clone, or to another agent.

## Project

Everything below is situated: it describes this project and only this project.

@docs/project.md
