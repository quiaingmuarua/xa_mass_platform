#!/usr/bin/env python3
"""Download one explicitly selected immutable Release's Simulator capacity kit.

GitHub metadata/tag verification is orchestration; archive structure remains owned
by distribution.archive_tools. No build, remote administration or task is started.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from distribution.archive_tools import verify_simulator


def require(condition, message):
    if not condition:
        raise ValueError(message)


def api(endpoint: str):
    result = subprocess.run(["gh", "api", "--method", "GET", endpoint], capture_output=True, text=True)
    require(result.returncode == 0, "GitHub release metadata request failed")
    return json.loads(result.stdout)


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require(urllib.parse.urlsplit(newurl).scheme == "https", "Release download attempted an insecure redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(asset: dict, destination: Path, expected_url: str) -> None:
    require(asset.get("browser_download_url") == expected_url, "Release asset URL differs from its fixed coordinate")
    size = asset.get("size")
    require(type(size) is int and 0 < size <= 256 * 1024 * 1024, "Release asset size is outside the allowed bound")
    digest = asset.get("digest", "")
    require(isinstance(digest, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest), "Release asset has no SHA256 digest")
    opener = urllib.request.build_opener(HttpsRedirects())
    request = urllib.request.Request(expected_url, headers={"User-Agent": "XA-Mass-external-consumer"})
    observed = 0
    checksum = hashlib.sha256()
    try:
        with opener.open(request, timeout=60) as response, destination.open("xb") as target:
            while data := response.read(1024 * 1024):
                observed += len(data)
                require(observed <= size, "Release download exceeded the declared size")
                target.write(data)
                checksum.update(data)
        require(observed == size and digest == "sha256:" + checksum.hexdigest(), "Release asset digest or size differs")
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def prepare(repository: str, tag: str, commit: str, output: Path) -> dict:
    require(bool(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)), "Invalid GitHub repository")
    require(bool(re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag)), "Release tag must be a numeric vX.Y.Z")
    require(bool(re.fullmatch(r"[0-9a-f]{40}", commit)), "Installed Release commit must be a complete SHA")
    require(not output.exists(), "Consumer installation already exists; select a fresh output directory")
    release = api(f"repos/{repository}/releases/tags/{tag}")
    require(release.get("tag_name") == tag and release.get("draft") is False and release.get("immutable") is True,
            "External acceptance requires the installed published immutable Release")
    obj = api(f"repos/{repository}/git/ref/tags/{tag}").get("object", {})
    for _ in range(8):
        if obj.get("type") != "tag":
            break
        obj = api(f"repos/{repository}/git/tags/{obj['sha']}").get("object", {})
    require(obj.get("type") == "commit" and obj.get("sha") == commit, "Release tag differs from the configured installed commit")
    version = tag[1:]
    name = f"xa-mass-worker-simulator-{version}.zip"
    assets = release.get("assets", [])
    selected = {}
    for filename in (name, name + ".sha256"):
        matches = [asset for asset in assets if asset.get("name") == filename and asset.get("state") == "uploaded"]
        require(len(matches) == 1, "Release kit or sidecar is missing or ambiguous")
        selected[filename] = matches[0]
    output.mkdir(parents=True, mode=0o700)
    for filename, asset in selected.items():
        download(asset, output / filename, f"https://github.com/{repository}/releases/download/{tag}/{filename}")
    archive = output / name
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    require((output / (name + ".sha256")).read_text(encoding="utf-8").split() == [digest, name],
            "Published sidecar differs from the Simulator kit")
    verify_simulator(archive, version)
    kit = output / f"xa-mass-worker-simulator-{version}"
    with zipfile.ZipFile(archive) as source:
        manifest = json.loads(source.read(f"{kit.name}/manifest.json"))
        require(manifest.get("schemaVersion") == 2 and manifest.get("loadedEntry") == "acceptance/run_loaded.py"
                and manifest.get("gitCommit") == commit and manifest.get("version") == version,
                "Published kit does not supply the matching external capacity entry")
        source.extractall(output)
    result = {"release": release["html_url"], "releaseId": release["id"], "tag": tag, "gitCommit": commit,
              "kitSha256": digest, "kitRoot": str(kit.resolve()), "manifest": manifest}
    (output / "consumption.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(arguments)
    try:
        result = prepare(args.repository, args.tag, args.commit, args.output_dir.resolve())
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("External Release preparation failed: " + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        return 2
    if path := os.environ.get("GITHUB_OUTPUT"):
        with open(path, "a", encoding="utf-8") as output:
            output.write("kit-root=" + result["kitRoot"] + "\n")
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
