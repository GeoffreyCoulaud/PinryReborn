"""What the guard must allow, and what it must block. One table each.

The guard is a script and not an importable module, its name carrying a hyphen,
so every case runs it the way the harness does: a payload on stdin, the verdict
as the exit code. Nothing is mocked and nothing is written, which is what keeps
this file self-contained.

    python3 -m unittest discover --start-directory .claude/hooks
"""

import json
import pathlib
import subprocess
import sys
import unittest

GUARD = pathlib.Path(__file__).with_name("evidence-guard.py")
REPOSITORY = str(pathlib.Path(__file__).resolve().parents[2])

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
)

BLOCKED = (
    "echo note > AGENTS.md",
    "echo note >> AGENTS.md",
    "echo note | tee AGENTS.md",
    "sed -i 1d AGENTS.md",
    "sed -i 1d",
    "perl -pi -e s/a/b/ AGENTS.md",
    # An in-place editor is refused whatever it names, because the operand is
    # not where the write lands: this one rewrites AGENTS.md from its script.
    "sed -i -e '1w AGENTS.md' /tmp/scratch.md",
    "sed -i 1d /tmp/scratch.md",
    "perl -pi -e s/a/b/ /tmp/scratch.md",
    "truncate -s 0 AGENTS.md",
    "dd of=AGENTS.md",
    "git apply change.patch",
    "python3 -c 'print(1)'",
)


def verdict(command: str) -> int:
    """The guard's answer to one Bash command: 0 allows, 2 blocks."""
    payload = json.dumps(
        {"tool_name": "Bash", "cwd": REPOSITORY, "tool_input": {"command": command}}
    )
    return subprocess.run(
        [sys.executable, str(GUARD)], input=payload, capture_output=True, text=True
    ).returncode


class EvidenceGuardTest(unittest.TestCase):
    def test_allowed_commands_are_let_through(self):
        for command in ALLOWED:
            with self.subTest(command=command):
                self.assertEqual(0, verdict(command))

    def test_blocked_commands_are_refused(self):
        for command in BLOCKED:
            with self.subTest(command=command):
                self.assertEqual(2, verdict(command))


if __name__ == "__main__":
    unittest.main()
