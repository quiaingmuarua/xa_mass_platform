"""Distribution-owned Runtime, Worker SDK and Preview archive verification."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tempfile
import zipfile
from xml.etree import ElementTree


def _runtime_require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


HOST_CONFIGURATIONS = {"application.yaml",
                       "application-consumer.yaml", "application-preview.yaml"}


def is_application_configuration(name: str) -> bool:
    return re.fullmatch(r"application(?:[-.][^/]*)?\.(?:yaml|yml|properties)", PurePosixPath(name).name) is not None


def verify_server_configuration(server_jar: zipfile.ZipFile) -> None:
    """Both distributions must deliver the same complete host-owned configuration."""
    entries = server_jar.namelist()
    _runtime_require(not any("platform-runtime.yaml" in name for name in entries), "Proof fixture must not be packaged")
    expected = {"BOOT-INF/classes/" + name for name in HOST_CONFIGURATIONS}
    actual = {name for name in entries if not name.startswith("BOOT-INF/lib/") and is_application_configuration(name)}
    _runtime_require(actual == expected, "Server Boot JAR has missing or unexpected application configuration")
    libraries = [name for name in entries if name.startswith("BOOT-INF/lib/xa-mass-") and name.endswith(".jar")]
    _runtime_require(sum(name.startswith("BOOT-INF/lib/xa-mass-server-jvm-") for name in libraries) == 1,
             "Server Boot JAR must contain one platform library")
    for library in libraries:
        with zipfile.ZipFile(io.BytesIO(server_jar.read(library))) as dependency:
            _runtime_require(not any(is_application_configuration(name) for name in dependency.namelist()),
                     "Platform or Scenario dependency contains application configuration: " + library)


def verify_runtime(archive: Path, version: str) -> None:
    root = f"xa-mass-server-runtime-{version}"
    with zipfile.ZipFile(archive) as runtime:
        names = runtime.namelist()
        _runtime_require(names, "runtime archive is empty")
        _runtime_require(not any(is_application_configuration(name) for name in names),
                 "Runtime archive contains unexpected external application configuration")
        _runtime_require(
            len(names) == len(set(names))
            and all(not PurePosixPath(name).is_absolute() and "\\" not in name
                    and ".." not in PurePosixPath(name).parts
                    and bool(PurePosixPath(name).parts)
                    and PurePosixPath(name).parts[0] == root for name in names)
            and all((entry.external_attr >> 16) & 0o170000 != stat.S_IFLNK
                    for entry in runtime.infolist()),
            "runtime archive has unsafe or duplicate entries",
        )
        _runtime_require(
            not any(
                "__pycache__" in PurePosixPath(name).parts
                or name.endswith(".pyc")
                for name in names
            ),
            "runtime archive contains Python build cache",
        )
        _runtime_require(
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
        _runtime_require(not missing, f"runtime archive is missing: {sorted(missing)}")
        _runtime_require(
            not any(
                name.startswith(f"{root}/scenario-workers/")
                for name in names
            ),
            "runtime archive contains the repository-local Worker Simulator",
        )
        _runtime_require(
            not any(name.startswith(f"{root}/config/") for name in names),
            "runtime archive retains a Java Pacer config directory",
        )

        manifest = json.loads(runtime.read(f"{root}/manifest.json"))
        _runtime_require(manifest.get("schemaVersion") == 6, "manifest schema mismatch")
        _runtime_require(manifest.get("version") == version, "manifest version mismatch")
        _runtime_require(
            re.fullmatch(r"[0-9a-f]{40}", manifest.get("gitCommit", ""))
            is not None,
            "manifest gitCommit is not full SHA",
        )
        _runtime_require(
            manifest.get("serverJar")
            == f"lib/xa-mass-server-jvm-{version}.jar",
            "manifest Server JAR mismatch",
        )
        _runtime_require(manifest.get("javaVersion") == 21, "Java version mismatch")
        _runtime_require(
            "kernelWheel" not in manifest and "pythonRequires" not in manifest,
            "manifest retains Python production fields",
        )
        _runtime_require(
            manifest.get("defaultSpringProfile") == "default",
            "Default Spring Profile mismatch",
        )
        _runtime_require(
            manifest.get("springProfiles")
            == ["default", "consumer", "preview"],
            "Spring Profiles mismatch",
        )
        _runtime_require(manifest.get("frontendIncluded") is True, "Frontend mismatch")
        _runtime_require(
            "workerSimulatorHost" not in manifest,
            "manifest retains the repository-local Worker Simulator",
        )

        diagnostic_codes = json.loads(
            runtime.read(
                f"{root}/frontend/dist/reference/"
                "platform-diagnostic-codes.json"
            )
        )
        _runtime_require(
            diagnostic_codes.get("schemaVersion") == 1,
            "diagnostic dictionary schema mismatch",
        )
        _runtime_require(
            diagnostic_codes.get("version") == manifest.get("version"),
            "diagnostic dictionary version mismatch",
        )
        _runtime_require(
            diagnostic_codes.get("gitCommit") == manifest.get("gitCommit"),
            "diagnostic dictionary gitCommit mismatch",
        )
        diagnostic_owners = diagnostic_codes.get("owners")
        _runtime_require(
            isinstance(diagnostic_owners, list),
            "diagnostic dictionary owners are invalid",
        )
        _runtime_require(
            all(isinstance(owner, dict) for owner in diagnostic_owners),
            "diagnostic dictionary owner entry is invalid",
        )
        _runtime_require(
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
        _runtime_require(
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
        _runtime_require(openapi.get("openapi") == "3.1.0", "OpenAPI version mismatch")
        _runtime_require(
            "servers" not in openapi,
            "OpenAPI snapshot contains a runtime URL",
        )
        _runtime_require(
            openapi.get("info", {}).get("title") == "XA Mass Runtime API",
            "OpenAPI title mismatch",
        )
        openapi_paths = openapi.get("paths")
        _runtime_require(
            isinstance(openapi_paths, dict) and bool(openapi_paths),
            "OpenAPI paths are invalid",
        )
        _runtime_require(
            all(path.startswith("/api/v1/") for path in openapi_paths),
            "OpenAPI snapshot contains a non-public path",
        )
        _runtime_require(
            [tag.get("name") for tag in openapi.get("tags", [])]
            == [
                "Development Users",
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
                _runtime_require(
                    not any(name.startswith("BOOT-INF/classes/sms-frontend/")
                            for name in server_entries),
                    "Server Boot JAR contains retired independent SMS frontend assets",
                )
                _runtime_require(
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


ARTIFACTS = {
    "xa-mass-worker-delivery-contract": "jar",
    "xa-mass-worker-core": "jar",
    "xa-mass-android-worker": "aar",
    "xa-mass-android-capability-http": "aar",
}


def _sdk_require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def _safe_members(archive: zipfile.ZipFile, expected_root: str) -> set[str]:
    names: set[str] = set()
    for entry in archive.infolist():
        path = PurePosixPath(entry.filename)
        _sdk_require(
            not path.is_absolute()
            and bool(path.parts)
            and ".." not in path.parts,
            f"unsafe Worker SDK ZIP entry: {entry.filename}",
        )
        _sdk_require(
            path.parts[0] == expected_root,
            "Worker SDK ZIP has an unexpected root",
        )
        _sdk_require(entry.filename not in names, "Worker SDK ZIP has duplicates")
        file_type = (entry.external_attr >> 16) & 0o170000
        _sdk_require(file_type != stat.S_IFLNK, "Worker SDK ZIP contains a symlink")
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


def verify_sdk(archive_path: Path, version: str) -> None:
    root = f"xa-mass-worker-sdk-{version}"
    with zipfile.ZipFile(archive_path) as archive:
        names = _safe_members(archive, root)
        for relative in ("manifest.json", "LICENSE", "THIRD_PARTY_NOTICES.md"):
            _sdk_require(f"{root}/{relative}" in names, f"missing {relative}")
        manifest = json.loads(archive.read(f"{root}/manifest.json"))
        _sdk_require(manifest.get("schemaVersion") == 1, "SDK manifest schema mismatch")
        _sdk_require(manifest.get("version") == version, "SDK manifest version mismatch")
        _sdk_require(
            re.fullmatch(r"[0-9a-f]{40}", manifest.get("gitCommit", ""))
            is not None,
            "SDK manifest commit is invalid",
        )
        _sdk_require(manifest.get("repository") == "repository", "repository mismatch")
        expected_manifest = [
            {
                "groupId": "com.xa.mass",
                "artifactId": artifact,
                "packaging": packaging,
            }
            for artifact, packaging in ARTIFACTS.items()
        ]
        _sdk_require(
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
            _sdk_require(not (required - names), f"{artifact} publication is incomplete")
            pom_dependencies[artifact] = _pom_dependencies(
                archive.read(f"{base}.pom")
            )

        _sdk_require(
            ("com.google.code.gson", "gson")
            in pom_dependencies["xa-mass-worker-delivery-contract"],
            "Delivery contract POM lost Gson",
        )
        _sdk_require(
            ("com.xa.mass", "xa-mass-worker-delivery-contract")
            in pom_dependencies["xa-mass-worker-core"],
            "Worker Core POM lost the Delivery contract",
        )
        _sdk_require(
            ("com.xa.mass", "xa-mass-worker-core")
            in pom_dependencies["xa-mass-android-worker"],
            "Android Worker POM lost Worker Core",
        )
        _sdk_require(
            ("com.squareup.okhttp3", "okhttp")
            in pom_dependencies["xa-mass-android-worker"],
            "Android Worker POM lost OkHttp",
        )
        _sdk_require(
            ("com.xa.mass", "xa-mass-worker-core")
            in pom_dependencies["xa-mass-android-capability-http"],
            "Capability HTTP POM lost Worker Core",
        )
        _sdk_require(
            ("org.nanohttpd", "nanohttpd")
            in pom_dependencies["xa-mass-android-capability-http"],
            "Capability HTTP POM lost NanoHTTPD",
        )


def consume_sdk(
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


def verify_preview(archive, frontend):
    root = "xa-mass-scenario-preview-0.1.0-preview"
    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        if not names or len(names) != len(set(names)) or any("\\" in name or ".." in PurePosixPath(name).parts
                or PurePosixPath(name).parts[0] != root for name in names):
            raise ValueError("Unsafe or duplicate archive member")
        files = {name[len(root) + 1:]: bundle.read(name) for name in names if not name.endswith("/")}
        if any(is_application_configuration(name) and name != "config/application-preview.yaml" for name in files):
            raise ValueError("Preview contains unexpected external application configuration")
        required = {"run_preview.py", "requirements.txt", "README.md", "preview-manifest.json", "config/application-preview.yaml"}
        required.update("worker-simulator/config/" + name + ".json" for name in ("lab", "sms", "messages", "products"))
        if not required <= files.keys():
            raise ValueError("Incomplete Preview entrypoints")
        manifest = json.loads(files["preview-manifest.json"])
        if manifest["scenarios"] != ["sms", "messages", "app-checks"] or manifest["processModel"] != ["server", "host"]:
            raise ValueError("Wrong default composition")
        if len(manifest["gitCommit"]) != 40:
            raise ValueError("Missing build identity")
        for name, fingerprint in manifest["sha256"].items():
            if hashlib.sha256(files[name]).hexdigest() != fingerprint:
                raise ValueError("Artifact fingerprint mismatch: " + name)
        prefix = "frontend/dist/"
        expected = {path.relative_to(frontend).as_posix(): path.read_bytes() for path in frontend.rglob("*") if path.is_file()}
        actual = {name[len(prefix):]: data for name, data in files.items() if name.startswith(prefix)}
        dictionary = "reference/platform-diagnostic-codes.json"
        if dictionary not in actual or json.loads(actual[dictionary])["gitCommit"] != manifest["gitCommit"]:
            raise ValueError("Missing current-build diagnostic dictionary")
        expected.pop(dictionary, None)
        actual.pop(dictionary)
        if "index.html" not in expected or actual != expected:
            raise ValueError("Preview does not contain the current unified frontend")
        for component in ("SmsPage-", "MessagePage-"):
            if not any(name.startswith("static/js/" + component) for name in actual):
                raise ValueError("Missing lazy product page")
        jars = [name for name in files if name.startswith("lib/") and name.endswith(".jar")]
        if len(jars) != 1:
            raise ValueError("Expected one Server JAR")
        if not any(name.startswith("worker-simulator/lib/xa-mass-worker-simulator-") for name in files):
            raise ValueError("Missing actual Worker Simulator")
        if any("node_modules" in PurePosixPath(name).parts or "private" in PurePosixPath(name).parts
                or "data/app-checks/" in name
                or name.endswith((".jsonl", "package.json", "gradlew", "build.gradle", ".log", ".mv.db", ".trace.db", ".lock", ".tmp")) for name in files):
            raise ValueError("Source toolchain or private run material in Preview")
        with zipfile.ZipFile(io.BytesIO(files[jars[0]])) as jar:
            verify_server_configuration(jar)
            if jar.read("BOOT-INF/classes/application-preview.yaml") != files["config/application-preview.yaml"]:
                raise ValueError("External preview configuration differs from the packaged host configuration")
            entries = jar.namelist()
            if any("sms-frontend/" in name or "worker-simulator" in name for name in entries):
                raise ValueError("Server embeds a separate frontend or Worker Simulator")
            if not any("xa-mass-message-campaigns-" in name for name in entries):
                raise ValueError("Messages backend absent")
            if not any("xa-mass-app-checks-" in name for name in entries):
                raise ValueError("App checks backend absent")
            if any(name.startswith("BOOT-INF/lib/h2-") and name.endswith(".jar") for name in entries):
                raise ValueError("Retired H2 runtime dependency in Preview")
            scenario = next(name for name in entries if "xa-mass-app-checks-" in name)
            with zipfile.ZipFile(io.BytesIO(jar.read(scenario))) as module:
                if "app-checks/preview-configuration.json" not in module.namelist():
                    raise ValueError("App Checks configuration seed absent")
    return {"passed": True, "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "fingerprintedFiles": len(manifest["sha256"]), "unifiedFrontendFiles": len(actual),
            "gitCommit": manifest["gitCommit"], "scenarios": manifest["scenarios"]}


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="component", required=True)
    for component in ("runtime", "sdk", "preview"):
        action = actions.add_parser(component)
        action.add_argument("--archive", type=Path, required=True)
        if component == "preview":
            action.add_argument("--frontend", type=Path, required=True)
            action.add_argument("--output", type=Path)
        else:
            action.add_argument("--version", required=True)
        if component == "sdk":
            action.add_argument("--consumer-project", type=Path)
    args = parser.parse_args(arguments)
    archive = args.archive.resolve()
    if args.component == "runtime":
        verify_runtime(archive, args.version)
        print(f"Runtime archive verified: {archive}")
    elif args.component == "sdk":
        verify_sdk(archive, args.version)
        if args.consumer_project is not None:
            consume_sdk(archive, args.version, args.consumer_project.resolve())
        print(f"Worker SDK distribution proof succeeded: {archive}")
    else:
        result = json.dumps(verify_preview(archive, args.frontend), indent=2)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(result, encoding="utf-8")
        print(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
