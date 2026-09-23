#!/usr/bin/env python3
"""Verify the stable XA Mass Server Runtime ZIP ABI."""

from __future__ import annotations

import argparse
import io
import json
import re
import zipfile
from pathlib import Path, PurePosixPath


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


HOST_CONFIGURATIONS = {"application.yaml", "application-scenario-workers.yaml",
                       "application-agentforge.yaml", "application-preview.yaml"}


def is_application_configuration(name: str) -> bool:
    return re.fullmatch(r"application(?:[-.][^/]*)?\.(?:yaml|yml|properties)", PurePosixPath(name).name) is not None


def verify_server_configuration(server_jar: zipfile.ZipFile) -> None:
    """Both distributions must deliver the same complete host-owned configuration."""
    entries = server_jar.namelist()
    expected = {"BOOT-INF/classes/" + name for name in HOST_CONFIGURATIONS}
    actual = {name for name in entries if not name.startswith("BOOT-INF/lib/") and is_application_configuration(name)}
    _require(actual == expected, "Server Boot JAR has missing or unexpected application configuration")
    libraries = [name for name in entries if name.startswith("BOOT-INF/lib/xa-mass-") and name.endswith(".jar")]
    _require(sum(name.startswith("BOOT-INF/lib/xa-mass-server-jvm-") for name in libraries) == 1,
             "Server Boot JAR must contain one platform library")
    for library in libraries:
        with zipfile.ZipFile(io.BytesIO(server_jar.read(library))) as dependency:
            _require(not any(is_application_configuration(name) for name in dependency.namelist()),
                     "Platform or Scenario dependency contains application configuration: " + library)


def verify(archive: Path, version: str) -> None:
    root = f"xa-mass-server-runtime-{version}"
    with zipfile.ZipFile(archive) as runtime:
        names = runtime.namelist()
        _require(names, "runtime archive is empty")
        _require(not any(is_application_configuration(name) for name in names),
                 "Runtime archive contains unexpected external application configuration")
        _require(
            all(PurePosixPath(name).parts[0] == root for name in names),
            "runtime archive has an entry outside its versioned root",
        )
        _require(
            not any(
                "__pycache__" in PurePosixPath(name).parts
                or name.endswith(".pyc")
                for name in names
            ),
            "runtime archive contains Python build cache",
        )
        _require(
            not any(
                name.endswith(".py")
                or "wheelhouse" in PurePosixPath(name).parts
                or "python-venv" in PurePosixPath(name).parts
                for name in names
            ),
            "runtime archive contains a Python production artifact",
        )
        required = {
            f"{root}/lib/xa-mass-server-jvm-{version}.jar",
            f"{root}/frontend/dist/index.html",
            f"{root}/frontend/dist/reference/"
            "platform-diagnostic-codes.json",
            f"{root}/frontend/dist/reference/openapi.json",
            f"{root}/manifest.json",
            f"{root}/THIRD_PARTY_NOTICES.md",
            f"{root}/LICENSE.pure-admin-thin",
        }
        missing = required - set(names)
        _require(not missing, f"runtime archive is missing: {sorted(missing)}")
        _require(
            not any(
                name.startswith(f"{root}/scenario-workers/")
                for name in names
            ),
            "runtime archive contains the repository-local Worker Simulator",
        )
        _require(
            not any(name.startswith(f"{root}/config/") for name in names),
            "runtime archive retains a Java Pacer config directory",
        )

        manifest = json.loads(runtime.read(f"{root}/manifest.json"))
        _require(manifest.get("schemaVersion") == 5, "manifest schema mismatch")
        _require(manifest.get("version") == version, "manifest version mismatch")
        _require(
            re.fullmatch(r"[0-9a-f]{40}", manifest.get("gitCommit", ""))
            is not None,
            "manifest gitCommit is not full SHA",
        )
        _require(
            manifest.get("serverJar")
            == f"lib/xa-mass-server-jvm-{version}.jar",
            "manifest Server JAR mismatch",
        )
        _require(manifest.get("javaVersion") == 21, "Java version mismatch")
        _require(
            "kernelWheel" not in manifest and "pythonRequires" not in manifest,
            "manifest retains Python production fields",
        )
        _require(
            manifest.get("defaultSpringProfile") == "scenario-workers",
            "Default Spring Profile mismatch",
        )
        _require(
            manifest.get("springProfiles")
            == ["scenario-workers", "agentforge", "preview"],
            "Spring Profiles mismatch",
        )
        _require(manifest.get("frontendIncluded") is True, "Frontend mismatch")
        _require(
            "workerSimulatorHost" not in manifest,
            "manifest retains the repository-local Worker Simulator",
        )

        diagnostic_codes = json.loads(
            runtime.read(
                f"{root}/frontend/dist/reference/"
                "platform-diagnostic-codes.json"
            )
        )
        _require(
            diagnostic_codes.get("schemaVersion") == 1,
            "diagnostic dictionary schema mismatch",
        )
        _require(
            diagnostic_codes.get("version") == manifest.get("version"),
            "diagnostic dictionary version mismatch",
        )
        _require(
            diagnostic_codes.get("gitCommit") == manifest.get("gitCommit"),
            "diagnostic dictionary gitCommit mismatch",
        )
        diagnostic_owners = diagnostic_codes.get("owners")
        _require(
            isinstance(diagnostic_owners, list),
            "diagnostic dictionary owners are invalid",
        )
        _require(
            all(isinstance(owner, dict) for owner in diagnostic_owners),
            "diagnostic dictionary owner entry is invalid",
        )
        _require(
            [owner.get("owner") for owner in diagnostic_owners]
            == [
                "server_jvm",
                "transport:netty-adapter",
                "transport:worker-core",
            ],
            "diagnostic dictionary owner allowlist mismatch",
        )
        diagnostic_owner_coordinates = " ".join(
            str(owner.get(field, ""))
            for owner in diagnostic_owners
            for field in ("owner", "module", "definition")
        ).lower()
        _require(
            all(
                excluded not in diagnostic_owner_coordinates
                for excluded in (
                    "scenario",
                    "integration",
                    "capability",
                    "android",
                    "frontend",
                    "distribution",
                )
            ),
            "diagnostic dictionary contains a non-Platform owner",
        )

        openapi = json.loads(
            runtime.read(f"{root}/frontend/dist/reference/openapi.json")
        )
        _require(openapi.get("openapi") == "3.1.0", "OpenAPI version mismatch")
        _require(
            "servers" not in openapi,
            "OpenAPI snapshot contains a runtime URL",
        )
        _require(
            openapi.get("info", {}).get("title") == "XA Mass Runtime API",
            "OpenAPI title mismatch",
        )
        openapi_paths = openapi.get("paths")
        _require(
            isinstance(openapi_paths, dict) and bool(openapi_paths),
            "OpenAPI paths are invalid",
        )
        _require(
            all(path.startswith("/api/v1/") for path in openapi_paths),
            "OpenAPI snapshot contains a non-public path",
        )
        _require(
            [tag.get("name") for tag in openapi.get("tags", [])]
            == [
                "Worker Resources",
                "Tasks",
                "Runtime View",
                "Worker Delivery",
            ],
            "OpenAPI tag order mismatch",
        )

        server_jar_name = f"{root}/lib/xa-mass-server-jvm-{version}.jar"
        server_jar_path = archive.parent / f".{archive.name}.server-jar.tmp"
        try:
            server_jar_path.write_bytes(runtime.read(server_jar_name))
            with zipfile.ZipFile(server_jar_path) as server_jar:
                verify_server_configuration(server_jar)
                server_entries = server_jar.namelist()
                _require(
                    not any(name.startswith("BOOT-INF/classes/sms-frontend/")
                            for name in server_entries),
                    "Server Boot JAR contains retired independent SMS frontend assets",
                )
                _require(
                    not any(
                        name.startswith(
                            "BOOT-INF/lib/xa-mass-worker-simulator"
                        )
                        or name.startswith("BOOT-INF/lib/libphonenumber-")
                        or name.startswith("BOOT-INF/lib/carrier-")
                        for name in server_entries
                    ),
                    "Server Boot JAR contains Worker Simulator implementation",
                )
        finally:
            server_jar_path.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--version", required=True)
    arguments = parser.parse_args()
    verify(arguments.archive.resolve(), arguments.version)
    print(f"Runtime archive verified: {arguments.archive}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
