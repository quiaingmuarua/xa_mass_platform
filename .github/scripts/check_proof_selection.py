#!/usr/bin/env python3
"""Verify that proof-lane selection remains explicit and non-stale."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FILTERS_FILE = ROOT / ".github/proof-paths.yml"
CONTRACT_FILE = ROOT / ".github/proof-selection-contract.json"
WORKFLOW_FILE = ROOT / ".github/workflows/proof-ci.yml"
EXPECTED_LANES = {
    "kernel_oracle",
    "jvm_contracts",
    "redis_owner",
    "runtime_boundary",
    "task_batch",
    "worker_fleet",
    "android_host",
    "android_emulator",
    "frontend",
    "runtime_distribution",
}
LANE = re.compile(r"^([a-z][a-z0-9_]*):$")
RULE = re.compile(r"^  - '([^']+)'$")
UNSUPPORTED_GLOB = re.compile(r"[\[\]{}()|+@]")


def load_filters(path: Path = FILTERS_FILE) -> dict[str, list[str]]:
    filters: dict[str, list[str]] = {}
    current: str | None = None
    for line_number, raw in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        if not raw or raw.startswith("#"):
            continue
        lane = LANE.fullmatch(raw)
        if lane:
            current = lane.group(1)
            if current in filters:
                raise ValueError(f"{path}:{line_number}: duplicate lane {current}")
            filters[current] = []
            continue
        rule = RULE.fullmatch(raw)
        if rule and current is not None:
            filters[current].append(rule.group(1))
            continue
        raise ValueError(f"{path}:{line_number}: unsupported filter syntax {raw!r}")
    return filters


def glob_regex(pattern: str) -> re.Pattern[str]:
    """Translate the deliberately small picomatch subset used by this repo."""
    if UNSUPPORTED_GLOB.search(pattern):
        raise ValueError(f"unsupported proof-path glob {pattern!r}")
    expression: list[str] = ["^"]
    index = 0
    while index < len(pattern):
        if pattern.startswith("**/", index):
            expression.append("(?:.*/)?")
            index += 3
        elif pattern.startswith("**", index):
            expression.append(".*")
            index += 2
        elif pattern[index] == "*":
            expression.append("[^/]*")
            index += 1
        elif pattern[index] == "?":
            expression.append("[^/]")
            index += 1
        else:
            expression.append(re.escape(pattern[index]))
            index += 1
    expression.append("$")
    return re.compile("".join(expression))


def matches(path: str, pattern: str) -> bool:
    return glob_regex(pattern).fullmatch(path) is not None


def lane_matches(path: str, rules: list[str]) -> bool:
    included = any(matches(path, rule) for rule in rules if not rule.startswith("!"))
    excluded = any(matches(path, rule[1:]) for rule in rules if rule.startswith("!"))
    return included and not excluded


def selected_lanes(path: str, filters: dict[str, list[str]]) -> set[str]:
    return {
        lane
        for lane, rules in filters.items()
        if lane_matches(path, rules)
    }


def repository_files() -> set[str]:
    result = subprocess.run(
        [
            "git",
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
        ],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return set(result.stdout.splitlines())


def load_contract(path: Path = CONTRACT_FILE) -> list[dict[str, object]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schemaVersion") != 1:
        raise ValueError(f"{path}: unsupported schemaVersion")
    cases = document.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"{path}: cases must be a non-empty list")
    return cases


def validate() -> list[str]:
    errors: list[str] = []
    try:
        filters = load_filters()
    except (OSError, ValueError) as error:
        return [str(error)]

    actual_lanes = set(filters)
    if actual_lanes != EXPECTED_LANES:
        errors.append(
            "proof lanes differ: "
            f"missing={sorted(EXPECTED_LANES - actual_lanes)}, "
            f"extra={sorted(actual_lanes - EXPECTED_LANES)}"
        )

    workflow = WORKFLOW_FILE.read_text(encoding="utf-8")
    if workflow.count("filters: .github/proof-paths.yml") != 1:
        errors.append(
            "proof-ci.yml must consume the checked proof-paths.yml exactly once"
        )
    if workflow.count("predicate-quantifier: some-with-excludes") != 1:
        errors.append(
            "proof-ci.yml must make negative Markdown rules override positive rules"
        )
    if "python3 .github/scripts/check_proof_selection.py" not in workflow:
        errors.append(
            "proof-ci.yml must verify selection before invoking paths-filter"
        )

    files = repository_files()
    for lane, rules in filters.items():
        positive = [rule for rule in rules if not rule.startswith("!")]
        if not positive:
            errors.append(f"{lane}: has no positive path rule")
        if "!**/*.md" not in rules:
            errors.append(f"{lane}: must leave Markdown to Docs Contract")
        for rule in positive:
            try:
                found = any(matches(path, rule) for path in files)
            except ValueError as error:
                errors.append(f"{lane}: {error}")
                continue
            if not found:
                errors.append(f"{lane}: positive rule matches no repository file: {rule}")

    try:
        cases = load_contract()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return [*errors, str(error)]

    seen_paths: set[str] = set()
    for index, case in enumerate(cases):
        path = case.get("path")
        expected = case.get("lanes")
        reason = case.get("reason")
        label = f"contract case {index + 1}"
        if not isinstance(path, str) or not path:
            errors.append(f"{label}: path must be non-empty")
            continue
        if path in seen_paths:
            errors.append(f"{label}: duplicate path {path}")
        seen_paths.add(path)
        if path not in files:
            errors.append(f"{label}: path is not a repository file: {path}")
        if not isinstance(reason, str) or not reason.strip():
            errors.append(f"{label}: reason must explain the proof boundary")
        if not isinstance(expected, list) or any(
            not isinstance(lane, str) for lane in expected
        ):
            errors.append(f"{label}: lanes must be a string list")
            continue
        expected_set = set(expected)
        if len(expected_set) != len(expected):
            errors.append(f"{label}: lanes contain duplicates")
        unknown = expected_set - EXPECTED_LANES
        if unknown:
            errors.append(f"{label}: unknown lanes {sorted(unknown)}")
        actual = selected_lanes(path, filters)
        if actual != expected_set:
            errors.append(
                f"{label}: {path} selects {sorted(actual)}, "
                f"expected {sorted(expected_set)}"
            )
    return errors


def main() -> int:
    errors = validate()
    if errors:
        print("Proof selection contract failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(
        "Proof selection contract passed: "
        f"{len(EXPECTED_LANES)} lanes, "
        f"{len(load_contract())} representative owner paths."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
