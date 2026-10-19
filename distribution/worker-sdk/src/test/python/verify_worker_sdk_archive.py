#!/usr/bin/env python3
"""Verify and externally consume one XA Mass Worker SDK archive."""

from __future__ import annotations

import argparse
import json
import importlib.util
import os
import re
import stat
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree


ARTIFACTS = {
    "xa-mass-worker-delivery-contract": "jar",
    "xa-mass-worker-core": "jar",
    "xa-mass-android-worker": "aar",
    "xa-mass-android-capability-http": "aar",
}


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def _safe_members(archive: zipfile.ZipFile, expected_root: str) -> set[str]:
    names: set[str] = set()
    for entry in archive.infolist():
        path = PurePosixPath(entry.filename)
        _require(
            not path.is_absolute()
            and bool(path.parts)
            and ".." not in path.parts,
            f"unsafe Worker SDK ZIP entry: {entry.filename}",
        )
        _require(
            path.parts[0] == expected_root,
            "Worker SDK ZIP has an unexpected root",
        )
        _require(entry.filename not in names, "Worker SDK ZIP has duplicates")
        file_type = (entry.external_attr >> 16) & 0o170000
        _require(file_type != stat.S_IFLNK, "Worker SDK ZIP contains a symlink")
        names.add(entry.filename)
    return names


def _pom_dependencies(payload: bytes) -> set[tuple[str, str]]:
    root = ElementTree.fromstring(payload)
    namespace = ""
    if root.tag.startswith("{"):
        namespace = root.tag.partition("}")[0] + "}"
    dependencies: set[tuple[str, str]] = set()
    for dependency in root.findall(
        f"{namespace}dependencies/{namespace}dependency"
    ):
        group = dependency.findtext(f"{namespace}groupId")
        artifact = dependency.findtext(f"{namespace}artifactId")
        if group and artifact:
            dependencies.add((group, artifact))
    return dependencies


def _verify_archive(archive_path: Path, version: str) -> None:
    root = f"xa-mass-worker-sdk-{version}"
    with zipfile.ZipFile(archive_path) as archive:
        names = _safe_members(archive, root)
        for relative in ("manifest.json", "LICENSE", "THIRD_PARTY_NOTICES.md"):
            _require(f"{root}/{relative}" in names, f"missing {relative}")
        manifest = json.loads(archive.read(f"{root}/manifest.json"))
        _require(manifest.get("schemaVersion") == 1, "SDK manifest schema mismatch")
        _require(manifest.get("version") == version, "SDK manifest version mismatch")
        _require(
            re.fullmatch(r"[0-9a-f]{40}", manifest.get("gitCommit", ""))
            is not None,
            "SDK manifest commit is invalid",
        )
        _require(manifest.get("repository") == "repository", "repository mismatch")
        expected_manifest = [
            {
                "groupId": "com.xa.mass",
                "artifactId": artifact,
                "packaging": packaging,
            }
            for artifact, packaging in ARTIFACTS.items()
        ]
        _require(
            manifest.get("artifacts") == expected_manifest,
            "SDK manifest artifacts differ",
        )

        pom_dependencies: dict[str, set[tuple[str, str]]] = {}
        for artifact, packaging in ARTIFACTS.items():
            base = f"{root}/repository/com/xa/mass/{artifact}/{version}/{artifact}-{version}"
            required = {
                f"{base}.{packaging}",
                f"{base}.pom",
                f"{base}-sources.jar",
            }
            _require(not (required - names), f"{artifact} publication is incomplete")
            pom_dependencies[artifact] = _pom_dependencies(
                archive.read(f"{base}.pom")
            )

        _require(
            ("com.google.code.gson", "gson")
            in pom_dependencies["xa-mass-worker-delivery-contract"],
            "Delivery contract POM lost Gson",
        )
        _require(
            ("com.xa.mass", "xa-mass-worker-delivery-contract")
            in pom_dependencies["xa-mass-worker-core"],
            "Worker Core POM lost the Delivery contract",
        )
        _require(
            ("com.xa.mass", "xa-mass-worker-core")
            in pom_dependencies["xa-mass-android-worker"],
            "Android Worker POM lost Worker Core",
        )
        _require(
            ("com.squareup.okhttp3", "okhttp")
            in pom_dependencies["xa-mass-android-worker"],
            "Android Worker POM lost OkHttp",
        )
        _require(
            ("com.xa.mass", "xa-mass-worker-core")
            in pom_dependencies["xa-mass-android-capability-http"],
            "Capability HTTP POM lost Worker Core",
        )
        _require(
            ("org.nanohttpd", "nanohttpd")
            in pom_dependencies["xa-mass-android-capability-http"],
            "Capability HTTP POM lost NanoHTTPD",
        )


def _consume_outside_repository(
    archive_path: Path, version: str, consumer_project: Path
) -> None:
    spec = importlib.util.spec_from_file_location("xa_android_consumer", consumer_project / "run_consumer.py")
    consumer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(consumer)
    with zipfile.ZipFile(archive_path) as source:
        commit = json.loads(source.read(f"xa-mass-worker-sdk-{version}/manifest.json"))["gitCommit"]
    with tempfile.TemporaryDirectory(prefix="xa-mass-worker-sdk-proof-") as temporary:
        root = Path(temporary).resolve()
        sdk = consumer.prepare_sdk(archive_path, version, commit, consumer.digest(archive_path), root / "installation")
        project = root / "consumer"
        consumer.copy_consumer(project)
        wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [str(wrapper), "--no-daemon", "-p", str(project),
                   f"-PxaMassSdkRoot={sdk}", f"-PxaMassVersion={version}", f"-PxaMassCommit={commit}",
                   ":worker-demo:assembleDebug", ":worker-demo:assembleLab1",
                   ":worker-demo:assembleLab2", ":worker-demo:assembleLab3"]
        if os.name == "nt":
            command = ["cmd.exe", "/d", "/c", *command]
        subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--consumer-project", type=Path, required=True)
    arguments = parser.parse_args()
    archive = arguments.archive.resolve()
    _verify_archive(archive, arguments.version)
    _consume_outside_repository(
        archive, arguments.version, arguments.consumer_project.resolve()
    )
    print(f"Worker SDK distribution proof succeeded: {archive}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
