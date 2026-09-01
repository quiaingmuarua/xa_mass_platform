#!/usr/bin/env python3
"""Explain which claim-driven proof lanes a Git diff selects."""

from __future__ import annotations

import argparse
import subprocess
import sys

from check_proof_selection import ROOT, lane_matches, load_filters


def changed_paths(base: str) -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACDMRTUXB", f"{base}...HEAD"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return sorted(path for path in result.stdout.splitlines() if path)


def explain(base: str) -> int:
    filters = load_filters()
    paths = changed_paths(base)
    print(f"base={base}")
    if not paths:
        print("changedFiles=0")
        print("selectedLanes=none")
        return 0

    print(f"changedFiles={len(paths)}")
    selected: dict[str, list[str]] = {}
    for path in paths:
        lanes = sorted(
            lane
            for lane, rules in filters.items()
            if lane_matches(path, rules)
        )
        print(f"file {path}: {', '.join(lanes) if lanes else 'docs-only'}")
        for lane in lanes:
            selected.setdefault(lane, []).append(path)

    print("selectedLanes=" + (",".join(sorted(selected)) or "none"))
    for lane in sorted(selected):
        print(f"reason {lane}: matched {len(selected[lane])} changed path(s)")
        for path in selected[lane]:
            print(f"  - {path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Explain proof-lane selection for changes since a base ref."
    )
    parser.add_argument("--base", default="origin/main")
    options = parser.parse_args()
    try:
        return explain(options.base)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Could not explain proof selection: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
