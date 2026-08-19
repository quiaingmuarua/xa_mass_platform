#!/usr/bin/env python3
"""Verify stable current-document entrypoints without generating documentation."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[2]
ARCHIVE_PREFIX = "doc/archive/"
IGNORED_NAMES = {"THIRD_PARTY_NOTICES.md"}
REQUIRED_DOCS = {
    "README.md": "Status: current",
    "doc/README.md": "Status: current",
    "AGENTS.md": "Status: current",
    "TESTING.md": "Status: current",
    "kernel_design/README.md": "Status: current",
    "kernel_design/doc/README.md": "Status: current",
    "server_jvm/README.md": "Status: current",
    "transport/README.md": "Status: repository-local",
}
REQUIRED_ROOT_LINKS = {
    "doc/README.md",
    "kernel_design/README.md",
    "TESTING.md",
    "AGENTS.md",
    "frontend/public/overview.htm",
}
REQUIRED_OVERVIEW_IDS = {
    "authority",
    "task-mainline",
    "direct-call",
    "transport",
    "runtime-topology",
    "proof-and-stability",
}
FORBIDDEN_CURRENT_TERMS = {
    "/control-commands:consume",
    "/control-results:append",
    "/workers/controls:call",
    "/controls:call",
    "CONTROL_ONLY",
    "control-only:v1:",
    "workerCommandsByWorkerId",
    "TASK_COMMAND",
    "TASK_REPORT",
    "WorkerPropertyIndex",
    "XA_MASS_WORKER_PROPERTY_INDEX_REGISTRY_JSON",
    "property-index",
    "indexed-properties",
    "indexedPropertyFields",
    "indexedPropertyUpdates",
    "publishPropertiesChanged",
    "replaceWorkerProperties",
    "replace_worker_properties",
    "platform.worker.properties.changed",
    "platform.adapter.worker-properties.changed",
}

MARKDOWN_LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
HTML_ID = re.compile(r"\bid=[\"']([^\"']+)[\"']")
HTML_LOCAL_HREF = re.compile(r"\bhref=[\"']#([^\"']+)[\"']")


def repository_files(patterns: list[str]) -> list[str]:
    command = [
        "git",
        "ls-files",
        "--cached",
        "--others",
        "--exclude-standard",
        "--",
        *patterns,
    ]
    result = subprocess.run(
        command,
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return sorted(path for path in result.stdout.splitlines() if path)


def current_markdown_files() -> list[str]:
    return [
        path
        for path in repository_files(["*.md", "**/*.md"])
        if not path.replace("\\", "/").startswith(ARCHIVE_PREFIX)
        and Path(path).name not in IGNORED_NAMES
    ]


def link_destination(raw: str) -> str:
    destination = raw.strip()
    if destination.startswith("<") and ">" in destination:
        destination = destination[1 : destination.index(">")]
    else:
        destination = destination.split(maxsplit=1)[0]
    return unquote(destination)


def is_external_or_anchor(destination: str) -> bool:
    lowered = destination.lower()
    return (
        not destination
        or destination.startswith("#")
        or destination.startswith("/")
        or lowered.startswith(("http://", "https://", "mailto:", "data:"))
    )


def validate_required_docs(errors: list[str]) -> None:
    for relative, marker in REQUIRED_DOCS.items():
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"missing canonical document: {relative}")
            continue
        text = path.read_text(encoding="utf-8")
        if marker not in text:
            errors.append(f"{relative}: missing canonical marker {marker!r}")


def validate_markdown_links(files: list[str], errors: list[str]) -> None:
    for relative in files:
        path = ROOT / relative
        text = path.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), start=1):
            for match in MARKDOWN_LINK.finditer(line):
                destination = link_destination(match.group(1))
                if is_external_or_anchor(destination):
                    continue
                local_part = destination.split("#", 1)[0].split("?", 1)[0]
                if not local_part:
                    continue
                target = (path.parent / local_part).resolve()
                if not target.exists():
                    errors.append(
                        f"{relative}:{line_number}: missing link target {destination!r}"
                    )


def validate_root_entrypoints(errors: list[str]) -> None:
    text = (ROOT / "README.md").read_text(encoding="utf-8")
    destinations = {
        link_destination(match.group(1)).split("#", 1)[0]
        for match in MARKDOWN_LINK.finditer(text)
    }
    for required in sorted(REQUIRED_ROOT_LINKS - destinations):
        errors.append(f"README.md: missing required entrypoint link {required!r}")


def validate_overview(errors: list[str]) -> None:
    overview = ROOT / "frontend/public/overview.htm"
    if not overview.is_file():
        errors.append("missing frontend/public/overview.htm")
        return
    text = overview.read_text(encoding="utf-8")
    ids = set(HTML_ID.findall(text))
    for required in sorted(REQUIRED_OVERVIEW_IDS - ids):
        errors.append(f"frontend/public/overview.htm: missing section id {required!r}")
    for target in sorted(set(HTML_LOCAL_HREF.findall(text)) - ids):
        errors.append(
            "frontend/public/overview.htm: local anchor "
            f"#{target} has no matching id"
        )


def validate_stale_terms(files: list[str], errors: list[str]) -> None:
    for relative in files:
        text = (ROOT / relative).read_text(encoding="utf-8")
        for term in sorted(FORBIDDEN_CURRENT_TERMS):
            if term in text:
                errors.append(f"{relative}: contains retired current-contract term {term!r}")


def validate_generated_overview(errors: list[str]) -> None:
    tracked = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "frontend/dist/overview.htm"],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if tracked.returncode == 0:
        errors.append(
            "frontend/dist/overview.htm must not be tracked; "
            "frontend/public/overview.htm is the source"
        )


def main() -> int:
    errors: list[str] = []
    markdown_files = current_markdown_files()
    validate_required_docs(errors)
    validate_markdown_links(markdown_files, errors)
    validate_root_entrypoints(errors)
    validate_overview(errors)
    validate_stale_terms(
        [*markdown_files, "frontend/public/overview.htm"],
        errors,
    )
    validate_generated_overview(errors)

    if errors:
        print("Documentation contract failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(
        "Documentation contract passed: "
        f"{len(markdown_files)} current Markdown files, "
        f"{len(REQUIRED_OVERVIEW_IDS)} overview sections."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
