#!/usr/bin/env python3
"""Consume explicitly pinned Runtime/SDK ZIPs without a platform checkout."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import shutil
import stat
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
ARTIFACTS = {
    "xa-mass-worker-delivery-contract": "jar",
    "xa-mass-worker-core": "jar",
    "xa-mass-android-worker": "aar",
    "xa-mass-android-capability-http": "aar",
}
ADAPTER_ID = "runtime-websocket"
RPC_TASK_ID = "xa-android-rpc-v1.android-demo-workers"
RECEIPT = "consumption.json"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def copy_consumer(destination: Path) -> None:
    """Copy only the standalone consumer, never the SDK producer or build outputs."""
    destination.mkdir(parents=True)
    for name in ("README.md", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
                 "gradle", "capabilities", "worker-demo", "run_consumer.py", "config", "tests"):
        source = ROOT / name
        if source.is_dir():
            shutil.copytree(source, destination / name,
                            ignore=shutil.ignore_patterns("build", ".gradle", "__pycache__", "*.pyc"))
        else:
            shutil.copy2(source, destination / name)


def _inspect(archive: Path, expected_hash: str, version: str,
             commit: str, component: str) -> dict:
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is not None, "Expected release version is invalid")
    require(re.fullmatch(r"[0-9a-f]{40}", commit) is not None, "Expected full commit is invalid")
    require(re.fullmatch(r"[0-9a-f]{64}", expected_hash) is not None, "Expected ZIP SHA256 is invalid")
    require(archive.is_file() and digest(archive) == expected_hash, f"{component} ZIP SHA256 mismatch")
    root = f"xa-mass-{component}-{version}"
    files: dict[str, str] = {}
    seen: set[str] = set()
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            path = PurePosixPath(entry.filename)
            require(not path.is_absolute() and not PureWindowsPath(entry.filename).drive
                    and "\\" not in entry.filename and ":" not in entry.filename
                    and ".." not in path.parts and bool(path.parts)
                    and path.parts[0] == root, "Unsafe or unexpected ZIP path")
            key = path.as_posix().casefold()
            require(key not in seen, "Duplicate ZIP path")
            seen.add(key)
            require(stat.S_IFMT(entry.external_attr >> 16) in {0, stat.S_IFREG, stat.S_IFDIR},
                    "ZIP contains a non-regular entry")
            if not entry.is_dir():
                require(len(path.parts) > 1, "ZIP root must be a directory")
                relative = PurePosixPath(*path.parts[1:]).as_posix()
                require(relative != "consumer-origin.json", "ZIP contains reserved consumer metadata")
                with source.open(entry) as stream:
                    files[relative] = hashlib.file_digest(stream, "sha256").hexdigest()
        require("manifest.json" in files, "ZIP manifest is missing")
        manifest = json.loads(source.read(f"{root}/manifest.json"))
    require(manifest.get("version") == version and manifest.get("gitCommit") == commit,
            f"{component} manifest identity mismatch")
    if component == "server-runtime":
        require(manifest.get("schemaVersion") == 6 and manifest.get("javaVersion") == 21
                and manifest.get("defaultSpringProfile") == "default"
                and manifest.get("springProfiles") == ["default", "consumer", "preview"]
                and manifest.get("frontendIncluded") is True, "Runtime manifest contract mismatch")
        jar = f"lib/xa-mass-server-jvm-{version}.jar"
        require(manifest.get("serverJar") == jar, "Runtime Server JAR coordinate mismatch")
        required = {jar, "frontend/dist/index.html", "frontend/dist/reference/openapi.json",
                    "frontend/dist/reference/platform-diagnostic-codes.json"}
    else:
        require(manifest.get("schemaVersion") == 1 and manifest.get("repository") == "repository"
                and manifest.get("artifacts") == [
                    {"groupId": "com.xa.mass", "artifactId": name, "packaging": packaging}
                    for name, packaging in ARTIFACTS.items()], "Worker SDK manifest contract mismatch")
        required = set()
        for name, packaging in ARTIFACTS.items():
            base = f"repository/com/xa/mass/{name}/{version}/{name}-{version}"
            required.update({f"{base}.{packaging}", f"{base}.pom", f"{base}-sources.jar"})
    require(required <= files.keys(), f"{component} ZIP is missing required components")
    return {"root": root, "manifest": manifest, "files": files,
            "archive": {"name": archive.name, "sha256": expected_hash}}


def _materialize(archive: Path, inspected: dict, work_dir: Path) -> Path:
    root = work_dir / inspected["root"]
    require(not root.is_symlink(), "Consumer installation root is a symlink")
    for relative, expected in inspected["files"].items():
        target = root / relative
        require(target.resolve().is_relative_to(work_dir.resolve()), "Consumer installation path escapes work directory")
        if target.exists():
            require(not target.is_symlink() and target.is_file() and digest(target) == expected,
                    "Existing consumer installation has different bytes")
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(archive) as source, source.open(f"{inspected['root']}/{relative}") as incoming:
                with target.open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
            require(digest(target) == expected, "Extracted consumer file hash mismatch")
    return root.resolve()


def _sdk_origin(inspected: dict, root: Path, version: str, commit: str) -> dict:
    artifacts = {}
    for name, packaging in ARTIFACTS.items():
        relative = f"repository/com/xa/mass/{name}/{version}/{name}-{version}.{packaging}"
        artifacts[name] = {"path": relative, "packaging": packaging,
                           "sha256": inspected["files"][relative]}
    origin = {"version": version, "gitCommit": commit,
              "archiveSha256": inspected["archive"]["sha256"], "artifacts": artifacts}
    path = root / "consumer-origin.json"
    if path.exists():
        require(json.loads(path.read_text(encoding="utf-8")) == origin,
                "Existing SDK origin has different consumer inputs")
    else:
        path.write_text(json.dumps(origin, indent=2) + "\n", encoding="utf-8")
    return origin


def prepare_sdk(archive: Path, version: str, commit: str,
                expected_hash: str, work_dir: Path) -> Path:
    """SDK Owner's focused archive test uses the same real consumer build inputs."""
    inspected = _inspect(archive.resolve(), expected_hash, version, commit, "worker-sdk")
    root = _materialize(archive.resolve(), inspected, work_dir.resolve())
    _sdk_origin(inspected, root, version, commit)
    return root


def prepare_archives(runtime_archive: Path, sdk_archive: Path, version: str, commit: str,
                     runtime_sha256: str, sdk_sha256: str, work_dir: Path) -> dict:
    runtime = _inspect(runtime_archive.resolve(), runtime_sha256, version, commit, "server-runtime")
    sdk = _inspect(sdk_archive.resolve(), sdk_sha256, version, commit, "worker-sdk")
    work_dir = work_dir.resolve()
    receipt_path = work_dir / RECEIPT
    identity = {"version": version, "gitCommit": commit,
                "archives": {"runtime": runtime["archive"], "sdk": sdk["archive"]}}
    if receipt_path.exists():
        previous = json.loads(receipt_path.read_text(encoding="utf-8"))
        require(all(previous.get(key) == value for key, value in identity.items()),
                "Work directory belongs to different consumer inputs; choose a new directory")
        allowed = {RECEIPT, f"{sdk['root']}/consumer-origin.json"}
        for inspected in (runtime, sdk):
            allowed.update(f"{inspected['root']}/{name}" for name in inspected["files"])
        directories = {parent.as_posix() for name in allowed
                       for parent in PurePosixPath(name).parents if parent.as_posix() != "."}
        for path in work_dir.rglob("*"):
            relative = path.relative_to(work_dir).as_posix()
            require(not path.is_symlink() and relative in (directories if path.is_dir() else allowed),
                    "Work directory contains unexpected consumer installation content")
    else:
        require(not work_dir.exists() or not any(work_dir.iterdir()),
                "Work directory is not an empty consumer installation")
    runtime_root = _materialize(runtime_archive.resolve(), runtime, work_dir)
    sdk_root = _materialize(sdk_archive.resolve(), sdk, work_dir)
    origin = _sdk_origin(sdk, sdk_root, version, commit)
    jar = runtime_root / runtime["manifest"]["serverJar"]
    receipt = dict(identity, runtimeRoot=str(runtime_root), sdkRoot=str(sdk_root),
                   serverJar=str(jar), serverJarSha256=digest(jar), sdkArtifacts=origin["artifacts"])
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt


def server_command(receipt: dict, redis_scope: str, server_port: int = 18182,
                   adapter_port: int = 18183, groups: Path | None = None) -> list[str]:
    require(bool(redis_scope.strip()), "Explicit Redis scope is required")
    require(1 <= server_port <= 65535 and 1 <= adapter_port <= 65535 and server_port != adapter_port,
            "Server and Adapter ports must be distinct valid ports")
    jar = Path(receipt["serverJar"])
    require(jar.is_file() and digest(jar) == receipt["serverJarSha256"], "Verified Server JAR bytes changed")
    group_document = json.loads((groups or ROOT / "config/worker-groups.json").read_text(encoding="utf-8"))
    require(isinstance(group_document, dict), "Consumer Group configuration must be an object")
    return ["java", "-jar", str(jar), "--spring.profiles.active=consumer",
            "--spring.web.resources.static-locations="
            + (Path(receipt["runtimeRoot"]) / "frontend/dist").as_uri() + "/",
            f"--server.port={server_port}", "--server.address=127.0.0.1",
            "--xa.mass.redis.url=${XA_MASS_CONSUMER_REDIS_URL}",
            f"--xa.mass.redis.scope={redis_scope}", "--xa.mass.kernel-pacer.preset=DEFAULT",
            "--xa.mass.project-assembly.projects[0].project-id=default",
            "--xa.mass.worker-assembly.group-config-json=" + json.dumps(group_document, separators=(",", ":")),
            f"--xa.mass.worker-delivery.adapter.instances.{ADAPTER_ID}.listen-port={adapter_port}",
            f"--xa.mass.worker-endpoints.endpoints.{ADAPTER_ID}.public-uri="
            + f"ws://127.0.0.1:{adapter_port}/api/v1/worker-delivery/websocket"]


def add_archive_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--runtime-archive", type=Path, required=True)
    parser.add_argument("--sdk-archive", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--runtime-sha256", required=True)
    parser.add_argument("--sdk-sha256", required=True)


def prepare_options(options: argparse.Namespace, work_dir: Path) -> dict:
    return prepare_archives(options.runtime_archive, options.sdk_archive, options.version,
                            options.commit, options.runtime_sha256, options.sdk_sha256, work_dir)


def main(arguments=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_archive_arguments(parser)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--redis-url")
    parser.add_argument("--redis-scope")
    parser.add_argument("--server-port", type=int, default=18182)
    parser.add_argument("--adapter-port", type=int, default=18183)
    parser.add_argument("--groups", type=Path, default=ROOT / "config/worker-groups.json")
    options = parser.parse_args(arguments)
    if not options.prepare_only and (not options.redis_url or not options.redis_scope):
        parser.error("Normal startup requires --redis-url and --redis-scope")
    try:
        receipt = prepare_options(options, options.work_dir)
        print(f"Verified consumer installation: {options.work_dir.resolve()}")
        print(f"Worker SDK root: {receipt['sdkRoot']}")
        if options.prepare_only:
            return 0
        command = server_command(receipt, options.redis_scope, options.server_port,
                                 options.adapter_port, options.groups)
        environment = dict(os.environ, XA_MASS_CONSUMER_REDIS_URL=options.redis_url)
        process = subprocess.Popen(command, cwd=options.work_dir.resolve(), env=environment)
        try:
            return process.wait()
        except KeyboardInterrupt:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            return 130
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Consumer startup failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
