#!/usr/bin/env python3
"""Check the current tracked working tree for this project's credential literals.

This bounded check reads no Git history and never prints a matched value.
It complements GitHub Secret Scanning; it is not a general secret detector.
"""
from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
ACCESS_KEY = re.compile(r"(?<![A-Za-z0-9_-])xak_[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])")
PRIVATE_KEY = re.compile(
    r"-----BEGIN (?P<label>(?:(?:RSA|EC|DSA|OPENSSH|ENCRYPTED) )?PRIVATE KEY)-----"
    r"(?P<body>[\s\S]+?)-----END (?P=label)-----"
)
GATEWAY_SECRET = re.compile(
    r"(?i)(?:[\"']?(?:secret|(?:worker[_-])?gateway[_-]secret)[\"']?\s*[:=]\s*)"
    r"[\"']?(?P<value>[A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])"
)
SYNTHETIC = "credential-check: synthetic"


@dataclass(frozen=True, order=True)
class Finding:
    path: str
    line: int
    kind: str


def fixture_path(path: str) -> bool:
    parts = Path(path).parts
    return any(part in {"test", "tests"} for part in parts) or Path(path).name.startswith("test_")


def synthetic_match(path: str, text: str, offset: int) -> bool:
    if not fixture_path(path):
        return False
    line_start = text.rfind("\n", 0, offset) + 1
    previous_start = text.rfind("\n", 0, max(0, line_start - 1)) + 1
    line_end = text.find("\n", offset)
    return SYNTHETIC in text[previous_start:line_end if line_end >= 0 else len(text)]


def scan_text(path: str, text: str) -> list[Finding]:
    matches = []
    for match in ACCESS_KEY.finditer(text):
        if not match.group().startswith("xak_dev_"):
            matches.append((match.start(), "production-access-key"))
    for match in PRIVATE_KEY.finditer(text):
        try:
            body = "".join(line.strip() for line in match.group("body").splitlines() if ":" not in line)
            material = base64.b64decode(body, validate=True)
        except ValueError:
            continue
        if len(material) >= 32:
            matches.append((match.start(), "private-key-material"))
    matches.extend((match.start("value"), "gateway-secret") for match in GATEWAY_SECRET.finditer(text))
    return sorted({Finding(path, text.count("\n", 0, offset) + 1, kind)
                   for offset, kind in matches if not synthetic_match(path, text, offset)})


def scan(root: Path) -> list[Finding]:
    paths = subprocess.run(["git", "-c", "core.fsmonitor=false", "ls-files", "-z"],
                           cwd=root, check=True, capture_output=True).stdout.split(b"\0")
    findings = []
    for encoded in paths:
        if not encoded:
            continue
        relative = os.fsdecode(encoded)
        path = root / relative
        if path.is_symlink():
            text = os.readlink(path)  # The tracked link itself, never its external target.
        elif path.is_file():
            content = path.read_bytes()
            if content.startswith((b"\xff\xfe\0\0", b"\0\0\xfe\xff")):
                text = content.decode("utf-32")
            elif content.startswith((b"\xff\xfe", b"\xfe\xff")):
                text = content.decode("utf-16")
            elif b"\0" in content:
                continue
            else:
                text = content.decode("utf-8", errors="replace")
        else:
            continue
        findings.extend(scan_text(relative.replace("\\", "/"), text))
    return sorted(findings)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    options = parser.parse_args(argv)
    try:
        findings = scan(options.root.resolve())
    except (OSError, UnicodeError, subprocess.SubprocessError):
        print("Credential check could not read the current tracked tree", file=sys.stderr)
        return 2
    for finding in findings:
        print(f"{finding.path}:{finding.line}: {finding.kind}")
    if findings:
        return 1
    print("Current tracked-tree credential check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
