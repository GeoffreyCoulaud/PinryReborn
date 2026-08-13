"""What the guard must allow, and what it must block. One table each.

The guard is a script and not an importable module, its name carrying a hyphen,
so every case runs it the way the harness does: a payload on stdin, the verdict
as the exit code. Nothing is mocked and nothing is written, which is what keeps
this file self-contained.

A case is a shell command, or a whole payload when the point is the payload
itself. The working directory is synthetic: the guard only compares path
prefixes and never touches the disk, so a real path would tie these expectations
to where the repository happens to be cloned (under /tmp, half of BLOCKED would
be disposable and the suite would go red for a reason that is not the guard).

    python3 -m unittest discover --start-directory .claude/hooks
"""

import json
import pathlib
import subprocess
import sys
import unittest

GUARD = pathlib.Path(__file__).with_name("evidence-guard.py")
CWD = "/repo"

ALLOWED = (
    "echo note > /dev/null",
    "echo note > $TMPDIR/scratch",
    "echo note | tee /tmp/scratch",
    "sed s/a/b/ AGENTS.md",
    "perl -Ilib script.pl",
    "grep -rn tee api-domain/",
    "./gradlew gate 2>&1",
    "git apply --check change.patch",
    "python3 -c 'import sys; sys.exit(0)'",
    # The size is the value of -s, not a file called 0.
    "truncate -s 0 /tmp/scratch",
    # Input the guard cannot read is never a reason to block a call.
    [],
    {"tool_name": "Bash", "cwd": CWD, "tool_input": "not an object"},
    {"tool_name": "Bash", "cwd": CWD, "tool_input": {"command": None}},
    # Only Bash is inspected; the edit tools return at once.
    {"tool_name": "Edit", "tool_input": {"file_path": "AGENTS.md"}},
)

BLOCKED = (
    "echo note > AGENTS.md",
    "echo note >> AGENTS.md",
    "echo note | tee AGENTS.md",
    "sudo tee AGENTS.md",
    "sed -i 1d AGENTS.md",
    "sed -i 1d",
    "perl -pi -e s/a/b/ AGENTS.md",
    "ruby -i -pe s/a/b/ AGENTS.md",
    # An in-place editor is refused whatever it names, because the operand is
    # not where the write lands: this one rewrites AGENTS.md from its script.
    "sed -i -e '1w AGENTS.md' /tmp/scratch.md",
    "sed -i 1d /tmp/scratch.md",
    "perl -pi -e s/a/b/ /tmp/scratch.md",
    "truncate -s 0 AGENTS.md",
    "dd of=AGENTS.md",
    "git apply change.patch",
    "patch -p1 < change.patch",
    "python3 -c 'print(1)'",
    "python3 - <<EOF",
    # The redirection is inside the nested shell.
    "sh -c 'echo x > AGENTS.md'",
    # A disposable prefix that walks back out of itself is not disposable.
    f"echo hi > /tmp/..{CWD}/AGENTS.md",
    f"echo hi | tee /tmp/..{CWD}/AGENTS.md",
    # -i still rewrites the file when clustered, or carrying its suffix.
    "sed -ni s/x/y/p AGENTS.md",
    "sed -ibak s/x/y/ AGENTS.md",
    # The editor is the command here, whatever option the wrapper took first.
    "git ls-files | xargs -I{} sed -i s/a/b/ {}",
    # A member of the wrong type is read as absent, never as permission.
    {"tool_name": "Bash", "cwd": 42, "tool_input": {"command": "echo x > AGENTS.md"}},
)


def verdict(case) -> int:
    """The guard's answer to one case: 0 allows, 2 blocks."""
    payload = (
        {"tool_name": "Bash", "cwd": CWD, "tool_input": {"command": case}}
        if isinstance(case, str)
        else case
    )
    return subprocess.run(
        [sys.executable, str(GUARD)],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
    ).returncode


class EvidenceGuardTest(unittest.TestCase):
    def test_allowed_cases_are_let_through(self):
        for case in ALLOWED:
            with self.subTest(case=case):
                self.assertEqual(0, verdict(case))

    def test_blocked_cases_are_refused(self):
        for case in BLOCKED:
            with self.subTest(case=case):
                self.assertEqual(2, verdict(case))


if __name__ == "__main__":
    unittest.main()
