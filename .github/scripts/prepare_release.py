#!/usr/bin/env python3
"""Promote exact full-Proof ZIP bytes to a resumable GitHub Draft Release."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
REQUIRED_JOBS = {
    "Select proof lanes", "Docs Contract", "JVM Contracts", "Redis Owner",
    "Runtime Boundary", "Worker Correctness", "Worker Dynamic Matching",
    "Android Host", "Android APK Assembly", "Android Worker Proof", "Frontend",
    "Runtime And Worker SDK Distribution", "Product Coexistence", "Proof Gate",
}
COMPONENTS = ("server-runtime", "worker-sdk")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def gh(*arguments: str):
    result = subprocess.run(["gh", *arguments], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("GitHub operation failed: " + " ".join(arguments[:3]))
    return result.stdout


def api(endpoint: str):
    return json.loads(gh("api", "--method", "GET", endpoint))


def validate_run(run: dict, jobs: list[dict], commit: str, repository: str) -> None:
    require(run.get("head_sha") == commit, "Proof SHA differs from release commit")
    require(run.get("event") == "workflow_dispatch", "Release requires the complete manual Proof")
    require(run.get("path") == ".github/workflows/proof-ci.yml", "Unexpected proof workflow")
    require(run.get("repository", {}).get("full_name") == repository, "Unexpected proof repository")
    require(run.get("status") == "completed" and run.get("conclusion") == "success", "Proof has not succeeded")
    names = {job.get("name") for job in jobs}
    require(REQUIRED_JOBS <= {str(name).split(" / ", 1)[0] for name in names}, "Proof is missing required jobs")
    require(sum(str(name).startswith("Worker Convergence Health (") for name in names) == 2,
            "Proof is missing convergence matrix jobs")
    require(all(job.get("status") == "completed" and job.get("conclusion") == "success" for job in jobs),
            "Proof contains failed, incomplete or skipped jobs")


def validate_artifacts(artifacts: list[dict], attempt: int) -> list[str]:
    expected = [f"xa-mass-{component}-{attempt}" for component in COMPONENTS]
    for name in expected:
        found = [artifact for artifact in artifacts if artifact.get("name") == name]
        require(len(found) == 1 and not found[0].get("expired", True), f"Proof artifact unavailable: {name}")
    return expected


def archive_manifest(path: Path, component: str, version: str, commit: str) -> dict:
    root = f"xa-mass-{component}-{version}"
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read(root + "/manifest.json"))
    require(manifest.get("version") == version, "Archive version differs from release tag")
    require(manifest.get("gitCommit") == commit, "Archive commit differs from Proof SHA")
    require(manifest.get("schemaVersion") == (6 if component == "server-runtime" else 1),
            "Archive manifest schema differs from the current contract")
    return manifest


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checksum(path: Path) -> Path:
    sidecar = path.with_name(path.name + ".sha256")
    content = f"{digest(path)}  {path.name}\n"
    if sidecar.exists():
        require(sidecar.read_text(encoding="utf-8") == content, "Existing checksum differs")
    else:
        sidecar.write_text(content, encoding="utf-8")
    return sidecar


def validate_draft(release: dict, tag: str, assets: list[Path]) -> None:
    require(release.get("tag_name") == tag and release.get("draft") is True,
            "Release already published or coordinate differs; it cannot be overwritten")
    names = [asset.get("name") for asset in release.get("assets", [])]
    require(len(names) == len(set(names)), "Draft contains duplicate assets")
    require(set(names) <= {path.name for path in assets}, "Draft contains unexpected assets")


def verify_existing_asset(expected: Path, downloaded: Path) -> None:
    require(downloaded.is_file() and digest(expected) == digest(downloaded),
            "Existing Draft asset differs; no asset will be overwritten")


def paged(endpoint: str, key: str | None) -> list[dict]:
    output = []
    separator = "&" if "?" in endpoint else "?"
    page = 1
    while True:
        payload = api(f"{endpoint}{separator}per_page=100&page={page}")
        batch = payload[key] if key else payload
        output.extend(batch)
        if len(batch) < 100:
            return output
        page += 1


def find_run(repository: str, commit: str) -> tuple[dict, list[str]]:
    runs = paged(f"repos/{repository}/actions/workflows/proof-ci.yml/runs?event=workflow_dispatch&head_sha={commit}", "workflow_runs")
    require(bool(runs), "No complete manual Proof exists for release SHA")
    run = max(runs, key=lambda value: value["id"])
    # Read current attempt status, never promote an older successful attempt.
    run = api(f"repos/{repository}/actions/runs/{run['id']}")
    jobs = paged(f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest", "jobs")
    validate_run(run, jobs, commit, repository)
    artifacts = paged(f"repos/{repository}/actions/runs/{run['id']}/artifacts", "artifacts")
    return run, validate_artifacts(artifacts, run["run_attempt"])


def verify_tag(repository: str, tag: str, commit: str) -> None:
    obj = api(f"repos/{repository}/git/ref/tags/{tag}")["object"]
    while obj["type"] == "tag":
        obj = api(f"repos/{repository}/git/tags/{obj['sha']}")["object"]
    require(obj["type"] == "commit" and obj["sha"] == commit, "Tag does not point to release commit")
    comparison = api(f"repos/{repository}/compare/{commit}...main")
    require(comparison["status"] in {"identical", "ahead"}, "Release commit does not belong to main")


def prepare(repository: str, tag: str, commit: str, output: Path) -> None:
    require(bool(re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag)), "Release tag must be v<major>.<minor>.<patch>")
    require(bool(re.fullmatch(r"[0-9a-f]{40}", commit)), "Release commit must be a full SHA")
    version = tag[1:]
    verify_tag(repository, tag, commit)
    run, artifact_names = find_run(repository, commit)
    output.mkdir(parents=True, exist_ok=True)
    output = output.resolve()
    archives = []
    for component, artifact_name in zip(COMPONENTS, artifact_names):
        name = f"xa-mass-{component}-{version}.zip"
        with tempfile.TemporaryDirectory(prefix="proof-artifact-", dir=output) as temporary:
            directory = Path(temporary).resolve()
            require(directory.is_relative_to(output), "Artifact staging escaped output directory")
            gh("run", "download", str(run["id"]), "--repo", repository,
               "--name", artifact_name, "--dir", str(directory))
            source = directory / name
            require(source.is_file(), f"Expected archive missing: {name}")
            archive_manifest(source, component, version, commit)
            destination = output / name
            if destination.exists():
                verify_existing_asset(source, destination)
            else:
                shutil.copyfile(source, destination)
            archives.append(destination)
    # Content belongs to Distribution's existing archive validators.
    runpy.run_path(str(ROOT / "distribution/server/src/test/python/verify_runtime_archive.py"))["verify"](archives[0], version)
    runpy.run_path(str(ROOT / "distribution/worker-sdk/src/test/python/verify_worker_sdk_archive.py"))["_verify_archive"](archives[1], version)
    assets = [*archives, *(checksum(path) for path in archives)]

    release = next((candidate for candidate in paged(f"repos/{repository}/releases", None)
                    if candidate.get("tag_name") == tag), None)
    if release is None:
        notes = output / "release-notes.md"
        contract = (ROOT / "distribution/server/RELEASE-1.0.md").read_text(encoding="utf-8")
        notes.write_text(contract + f"\nVerified full Proof: {run['html_url']}\n\nCommit: `{commit}`\n", encoding="utf-8")
        gh("release", "create", tag, "--repo", repository, "--verify-tag", "--draft",
           "--title", "XA Mass " + tag, "--notes-file", str(notes))
        release = next(candidate for candidate in paged(f"repos/{repository}/releases", None)
                       if candidate.get("tag_name") == tag)
    validate_draft(release, tag, assets)
    existing = {asset["name"] for asset in release.get("assets", [])}
    with tempfile.TemporaryDirectory(prefix="draft-assets-", dir=output) as temporary:
        directory = Path(temporary).resolve()
        require(directory.is_relative_to(output), "Draft staging escaped output directory")
        for path in assets:
            if path.name in existing:
                gh("release", "download", tag, "--repo", repository, "--pattern", path.name, "--dir", str(directory))
                verify_existing_asset(path, directory / path.name)
        for path in assets:
            if path.name not in existing:
                gh("release", "upload", tag, str(path), "--repo", repository)
    release = api(f"repos/{repository}/releases/{release['id']}")
    validate_draft(release, tag, assets)
    require({asset['name'] for asset in release['assets']} == {path.name for path in assets}, "Draft assets are incomplete")
    print(f"Draft ready: {tag} commit={commit} proofRun={run['id']} assets=4")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/release-candidate")
    options = parser.parse_args()
    prepare(options.repository, options.tag, options.commit, options.output)


if __name__ == "__main__":
    main()
