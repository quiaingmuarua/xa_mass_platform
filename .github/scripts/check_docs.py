#!/usr/bin/env python3
"""Verify stable current-document entrypoints without generating documentation."""

from __future__ import annotations

import html
import re
import subprocess
import sys
import unicodedata
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
    "doc/kernel/README.md": "Status: current",
    "kernel_jvm/README.md": "Status: stable",
    "kernel_pacer_jvm/README.md": "Status: Kernel-owned",
    "worker_matching_jvm/README.md": "Status: current",
    "server_jvm/README.md": "Status: current",
    "transport/README.md": "Status: repository-local",
}
REQUIRED_ROOT_LINKS = {
    "doc/README.md",
    "doc/kernel/README.md",
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
    "xa.mass.kernel-redis",
    "XA_MASS_KERNEL_PACER_REDIS_PREFIX",
    "tc:{prefix}",
    "tr:{prefix}",
    "wr:{prefix}",
    "wd:{prefix}",
    "rr:{prefix}",
    "ad:{prefix}",
    "ws:{prefix}",
    "wi:{prefix}",
    "kernel_" + "design",
    "executable" + "_spec",
    "Python " + "Oracle",
    "Kernel " + "Oracle",
    "mechanism " + "oracle",
    "kernel-" + "oracle",
    "KERNEL_" + "DESIGN_REDIS_URL",
    "Worker WebSocket " + "Scale",
    "worker-websocket-" + "scale",
    "worker_websocket_" + "scale",
}

MARKDOWN_LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
MARKDOWN_REFERENCE = re.compile(r"^ {0,3}\[[^\]]+\]:\s*(.+)$")
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
    return sorted(
        path
        for path in result.stdout.splitlines()
        if path and (ROOT / path).is_file()
    )


def current_markdown_files() -> list[str]:
    return [
        path
        for path in repository_files(["*.md", "**/*.md"])
        if not path.replace("\\", "/").startswith(ARCHIVE_PREFIX)
        and Path(path).name not in IGNORED_NAMES
    ]


def link_destination(raw: str) -> str:
    destination = raw.strip()
    if not destination:
        return ""
    if destination.startswith("<") and ">" in destination:
        destination = destination[1 : destination.index(">")]
    else:
        destination = destination.split(maxsplit=1)[0]
    return unquote(destination)


def is_external(destination: str) -> bool:
    return (
        not destination
        or destination.startswith("/")
        or bool(re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", destination))
    )


def prose_lines(text: str) -> list[tuple[int, str]]:
    """Keep source line numbers while excluding fenced and indented code."""
    result = []
    fence = None
    for number, line in enumerate(text.splitlines(), start=1):
        marker = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line)
        if fence:
            if (marker and marker[1][0] == fence[0]
                    and len(marker[1]) >= len(fence) and not marker[2].strip()):
                fence = None
            result.append((number, ""))
        elif marker:
            fence = marker[1]
            result.append((number, ""))
        elif line.startswith(("    ", "\t")):
            result.append((number, ""))
        else:
            result.append((number, line))
    return result


def heading_slug(heading: str) -> str:
    """GitHub-style heading IDs for repository prose, including Unicode."""
    heading = MARKDOWN_LINK.sub(lambda match: match[0].split("]", 1)[0].lstrip("!["), heading)
    heading = html.unescape(re.sub(r"<[^>]*>", "", heading)).lower()
    # Inline code and emphasis contribute visible text, not Markdown delimiters.
    heading = heading.replace("`", "").replace("*", "").replace("~", "")
    return "".join(
        "-" if char.isspace() else char
        for char in heading
        if char in "-_" or char.isspace()
        or unicodedata.category(char)[0] not in "PSC"
    )


def markdown_anchors(text: str) -> set[str]:
    lines = prose_lines(text)
    anchors = set(HTML_ID.findall("\n".join(line for _, line in lines)))
    heading_ids: set[str] = set()
    previous = ""
    for _, line in lines:
        heading = re.match(r"^ {0,3}#{1,6}(?:[ \t]+(.*?)|[ \t]*)$", line)
        title = None
        if heading:
            title = re.sub(r"[ \t]+#+[ \t]*$", "", heading[1] or "").strip()
        elif previous.strip() and re.fullmatch(r" {0,3}(?:=+|-+)[ \t]*", line):
            title = previous.strip()
        if title is not None:
            base = heading_slug(title)
            slug = base
            suffix = 0
            while slug in heading_ids:
                suffix += 1
                slug = f"{base}-{suffix}"
            heading_ids.add(slug)
        previous = line
    return anchors | heading_ids


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
    anchor_cache: dict[Path, set[str]] = {}
    for relative in files:
        path = ROOT / relative
        text = path.read_text(encoding="utf-8")
        for line_number, line in prose_lines(text):
            destinations = [match[1] for match in MARKDOWN_LINK.finditer(line)]
            reference = MARKDOWN_REFERENCE.match(line)
            if reference:
                destinations.append(reference[1])
            for raw in destinations:
                destination = link_destination(raw)
                if is_external(destination):
                    continue
                local_part, separator, fragment = destination.partition("#")
                local_part = local_part.split("?", 1)[0]
                target = (path.parent / local_part).resolve() if local_part else path.resolve()
                if not target.exists():
                    errors.append(
                        f"{relative}:{line_number}: missing link target {destination!r}"
                    )
                    continue
                if not separator or not fragment or not target.is_file():
                    continue
                if target.suffix.lower() not in {".md", ".markdown", ".html", ".htm"}:
                    continue
                if target not in anchor_cache:
                    target_text = target.read_text(encoding="utf-8")
                    anchor_cache[target] = (
                        markdown_anchors(target_text)
                        if target.suffix.lower() in {".md", ".markdown"}
                        else set(HTML_ID.findall(target_text))
                    )
                if fragment not in anchor_cache[target]:
                    errors.append(
                        f"{relative}:{line_number}: missing link anchor {destination!r}"
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
