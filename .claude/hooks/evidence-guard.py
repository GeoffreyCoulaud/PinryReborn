"""PreToolUse guard for the rules of AGENTS.md that prose cannot hold.

Two rules, applied by blocking the tool call before it runs:

- file content is written with the edit tool, never by a command, so that every
  change reaches the user as a reviewable diff
- a check that cannot fail is not a check

Writing is judged by its target, not by the command's name: a redirection is
allowed only into an explicitly disposable location. That allow-list is what
makes the guard survive `/add-dir`, worktrees and monorepos without knowing
anything about the layout of the working tree.

There is no shebang on purpose. The hook is invoked as `python3 <path>`, so a
copy that drops the executable bit cannot silently disable it.

Exit 0 allows the call. Exit 2 blocks it and returns the reason to the model,
which corrects itself without involving the user.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import sys

# Locations whose contents nobody reviews and nobody keeps.
DISPOSABLE_EXACT = {
    "/dev/null",
    "/dev/stdout",
    "/dev/stderr",
    "/dev/tty",
    "/dev/fd/1",
    "/dev/fd/2",
}
DISPOSABLE_PREFIXES = (
    "/tmp/",
    "/var/tmp/",
    "/private/tmp/",
    "/private/var/tmp/",
    "/dev/shm/",
    "$TMPDIR",
    "${TMPDIR}",
    "$TMP/",
    "${TMP}",
)

WRITE_OP = re.compile(r"\A(?:&?>>?\|?)\Z")
FD_DUP_OP = re.compile(r"\A\d*>&\Z")
SEPARATORS = {"|", "||", "&&", ";", "&", "|&"}
REDIRECT_IN = ("<", "<<", "<<<", "<&")

# Tokens after which the next word is still a command name, not an argument.
WRAPPERS = {
    "sudo", "env", "time", "nohup", "nice", "command", "exec", "xargs", "then",
    "do", "else", "uv", "uvx", "npx", "bunx", "pnpm", "yarn", "poetry", "hatch",
    "pdm", "rye", "pipx", "run", "-exec", "-execdir", "watch",
}

NESTED_SHELLS = {"bash", "sh", "zsh", "dash", "ksh"}
PATCH_READONLY = {"--check", "--stat", "--summary", "--numstat", "--dry-run"}

INPLACE_EDITORS = {"sed", "perl", "ruby"}
# Flags carrying the script, so neither they nor their value is a file to rewrite.
INPLACE_SCRIPT_FLAGS = ("-e", "-f", "--expression", "--file")
# Short perl and ruby flag clusters only, so that -Ilib is not read as in-place.
INPLACE_CLUSTER = re.compile(r"\A-[pnealwsi]*i[pnealwsi]*\Z")
INPLACE_LONG = re.compile(r"\A--in-place(=.*)?\Z")
TRUNCATING = {"truncate", "dd", "install"}

ONELINER_FLAGS = {"-c", "-e"}
ONELINER_HOSTS = {"python", "python3", "node", "nodejs", "deno", "bun"}
# Any of these means the one-liner observes something real, so it can fail.
FALSIFIABLE = re.compile(
    r"\b(import|require|assert|raise|throw|open|exit|readFile|process|sys|"
    r"subprocess|Path|os|fs|input|argv)\b|[=!<>]=|\bnot\b|\bin\b"
)
PRINTING = re.compile(r"\b(print|console\.log)\b")

HEREDOC = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1")

WRITE_ADVICE = (
    "File content is authored with the Write or Edit tool, so the change "
    "reaches the user as a diff. Send throwaway output to $TMPDIR instead."
)
CHECK_ADVICE = (
    "A check that cannot fail is not evidence. Run the project gate, or a "
    "command whose failure would have proved the claim wrong."
)


def strip_heredoc_bodies(command: str) -> str:
    """Remove heredoc bodies so their content is never parsed as shell.

    The body is data. Whether it lands in a file is decided by the redirection
    or the command the heredoc feeds, both of which stay in the stripped text.
    """
    lines = command.split("\n")
    out, skip_until = [], None
    for line in lines:
        if skip_until is not None:
            if line.strip() == skip_until:
                skip_until = None
            continue
        out.append(line)
        match = HEREDOC.search(line)
        if match:
            skip_until = match.group(2)
    return "\n".join(out)


def tokenize(command: str) -> list[str]:
    lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    try:
        return list(lexer)
    except ValueError:
        # Unbalanced quotes: let the shell report it, do not guess.
        return []


def basename(token: str) -> str:
    return token.rsplit("/", 1)[-1]


def is_disposable(target: str, cwd: str | None = None) -> bool:
    if target in DISPOSABLE_EXACT or target.startswith(DISPOSABLE_PREFIXES):
        return True
    # A relative target is disposable only when the session itself sits in a
    # disposable directory, which is the scratchpad case. With no cwd, refuse.
    if cwd and not target.startswith(("/", "~", "$")):
        return os.path.normpath(os.path.join(cwd, target)).startswith(
            DISPOSABLE_PREFIXES
        )
    return False


def command_positions(tokens: list[str]) -> set[int]:
    """Indices holding a command name.

    Without this, `grep -rn tee src/` reads as a `tee` into `src/`. A name only
    means a command where a command can start.
    """
    found, expect, skip = set(), True, False
    for index, token in enumerate(tokens):
        if skip:
            skip = False
            continue
        if token in SEPARATORS:
            expect = True
            continue
        operator = token.lstrip("0123456789")
        if (operator and WRITE_OP.match(operator)) or FD_DUP_OP.match(token) \
                or token in REDIRECT_IN:
            skip = True
            continue
        if expect:
            found.add(index)
        expect = basename(token) in WRAPPERS or token in WRAPPERS
    return found


def arguments_after(tokens: list[str], index: int) -> list[str]:
    """Arguments belonging to the command starting at index."""
    out = []
    for token in tokens[index + 1:]:
        if token in SEPARATORS or WRITE_OP.match(token):
            break
        out.append(token)
    return out


def check_writes(tokens, commands, cwd):
    for index, token in enumerate(tokens):
        if FD_DUP_OP.match(token):
            continue
        operator = token.lstrip("0123456789")
        if operator and WRITE_OP.match(operator):
            target = tokens[index + 1] if index + 1 < len(tokens) else ""
            if not is_disposable(target, cwd):
                return f"redirection into {target or '(missing target)'}"
    return None


def check_tee(tokens, commands, cwd):
    for index, token in enumerate(tokens):
        if index not in commands or basename(token) != "tee":
            continue
        for argument in arguments_after(tokens, index):
            if argument.startswith("-"):
                continue
            if not is_disposable(argument, cwd):
                return f"tee into {argument}"
    return None


def inplace_targets(name: str, arguments: list[str]) -> list[str]:
    """The files an in-place editor would rewrite.

    Neither its flags, nor the value a flag consumes, nor sed's script when it
    is given as the first operand rather than through -e. A token this cannot
    classify is returned as a target, so an unparsed command blocks rather than
    passes.
    """
    targets = []
    script_pending = name == "sed"
    skip_value = False
    for argument in arguments:
        if skip_value:
            skip_value = False
            continue
        if argument.startswith("-"):
            if argument in INPLACE_SCRIPT_FLAGS:
                skip_value = True
            if argument.startswith(INPLACE_SCRIPT_FLAGS):
                script_pending = False
            continue
        if script_pending:
            script_pending = False
            continue
        targets.append(argument)
    return targets


def check_inplace(tokens, commands, cwd):
    for index, token in enumerate(tokens):
        name = basename(token)
        if index not in commands or name not in INPLACE_EDITORS:
            continue
        arguments = arguments_after(tokens, index)
        in_place = any(
            argument == "-i"
            or argument.startswith("-i.")
            or INPLACE_LONG.match(argument)
            or (name != "sed" and INPLACE_CLUSTER.match(argument))
            for argument in arguments
        )
        if not in_place:
            continue
        targets = inplace_targets(name, arguments)
        if targets and all(is_disposable(target, cwd) for target in targets):
            continue
        return f"in-place edit by {name}"
    return None


def check_truncating(tokens, commands, cwd):
    for index, token in enumerate(tokens):
        name = basename(token)
        if index not in commands or name not in TRUNCATING:
            continue
        for argument in arguments_after(tokens, index):
            if argument.startswith("-") or argument.startswith("if="):
                continue
            target = argument[3:] if argument.startswith("of=") else argument
            if not is_disposable(target, cwd):
                return f"{name} writing {target}"
    return None


def check_stdin_script(tokens, commands, cwd):
    """`python - <<EOF` runs a program the user never sees as a file."""
    for index, token in enumerate(tokens):
        if index in commands and basename(token) in ONELINER_HOSTS:
            following = arguments_after(tokens, index)
            if following and following[0] == "-":
                return f"{basename(token)} running a script from stdin"
    return None


def check_patching(tokens, commands, cwd):
    """Applying a patch rewrites files with content nobody saw as a diff."""
    for index, token in enumerate(tokens):
        if index not in commands:
            continue
        name = basename(token)
        following = arguments_after(tokens, index)
        flags = {argument for argument in following if argument.startswith("-")}
        if name == "git":
            verbs = [a for a in following if not a.startswith("-")]
            if verbs and verbs[0] in ("apply", "am") and not (
                flags & PATCH_READONLY
            ):
                return f"git {verbs[0]} rewriting files"
        elif name == "patch" and not (flags & PATCH_READONLY):
            return "patch rewriting files"
    return None


def check_tautology(tokens, commands, cwd):
    piped_into = False
    for index, token in enumerate(tokens):
        if token in SEPARATORS:
            piped_into = token in ("|", "|&")
            continue
        if index not in commands or basename(token) not in ONELINER_HOSTS:
            continue
        following = arguments_after(tokens, index)
        for offset, argument in enumerate(following):
            if argument not in ONELINER_FLAGS:
                continue
            if offset + 1 >= len(following):
                break
            body = following[offset + 1]
            # A file argument or an upstream pipe means it reads something.
            has_input = piped_into or len(following) > offset + 2
            if (
                not has_input
                and PRINTING.search(body)
                and not FALSIFIABLE.search(body)
            ):
                return f"one-liner that cannot fail: {body[:60]}"
            break
    return None


CHECKS = (
    (check_writes, WRITE_ADVICE),
    (check_tee, WRITE_ADVICE),
    (check_inplace, WRITE_ADVICE),
    (check_truncating, WRITE_ADVICE),
    (check_stdin_script, WRITE_ADVICE),
    (check_patching, WRITE_ADVICE),
    (check_tautology, CHECK_ADVICE),
)


def evaluate(command: str, cwd: str | None = None, depth: int = 0):
    """Return (reason, advice) when the command must be blocked, else None."""
    tokens = tokenize(strip_heredoc_bodies(command))
    if not tokens:
        return None
    commands = command_positions(tokens)
    for check, advice in CHECKS:
        reason = check(tokens, commands, cwd)
        if reason:
            return reason, advice
    if depth >= 3:
        return None
    # A nested shell hides its own redirections inside a quoted argument. The
    # `sh -c <body>` sequence is specific enough not to need a command position:
    # `xargs -I{} sh -c '...'` reaches it through an option value.
    for index, token in enumerate(tokens):
        name = basename(token)
        nested = None
        if name in NESTED_SHELLS:
            following = arguments_after(tokens, index)
            if len(following) >= 2 and following[0] == "-c":
                nested = following[1]
        elif name == "eval":
            following = arguments_after(tokens, index)
            nested = " ".join(following) if following else None
        if nested:
            verdict = evaluate(nested, cwd, depth + 1)
            if verdict:
                return f"{verdict[0]} (inside {name})", verdict[1]
    return None


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        # Never block a call because the guard could not read its own input.
        return 0
    if not isinstance(payload, dict):
        # Valid JSON that is not an object is unreadable input all the same.
        return 0
    if payload.get("tool_name") != "Bash":
        return 0
    command = payload.get("tool_input", {}).get("command", "")
    verdict = evaluate(command, payload.get("cwd"))
    if verdict is None:
        return 0
    reason, advice = verdict
    print(f"Blocked: {reason}. {advice}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
