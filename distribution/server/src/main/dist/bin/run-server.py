#!/usr/bin/env python3
"""Start one unpacked XA Mass Server Runtime distribution."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import venv
from pathlib import Path
from typing import Any


_MARKER_NAME = ".xa-mass-runtime.json"
_SHUTDOWN_TIMEOUT_SECONDS = 15
_OWNED_PROFILE = "scenario-workers"
_PYTHON_REQUIREMENT_PATTERN = re.compile(
    r">=(\d+)\.(\d+)\.(\d+),<(\d+)\.(\d+)"
)
_OWNED_SPRING_ARGUMENTS = (
    "--xa.mass.kernel-pacer.python-executable",
    "--xa.mass.kernel-pacer.working-directory",
    "--xa.mass.kernel-pacer.config-path",
    "--xa.mass.kernel-pacer.state-directory",
    "--spring.web.resources.static-locations",
)


class LauncherError(RuntimeError):
    """Safe launcher configuration or bootstrap failure."""


def _python_requirement_bounds(
    requirement: str,
) -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    match = _PYTHON_REQUIREMENT_PATTERN.fullmatch(requirement)
    if match is None:
        raise LauncherError(
            "Runtime manifest pythonRequires must use >=X.Y.Z,<A.B"
        )
    values = tuple(int(value) for value in match.groups())
    minimum = values[:3]
    maximum_exclusive = (*values[3:], 0)
    if minimum >= maximum_exclusive:
        raise LauncherError("Runtime manifest pythonRequires range is empty")
    return minimum, maximum_exclusive


def _runtime_root(script_path: Path | None = None) -> Path:
    script = (script_path or Path(__file__)).resolve()
    return script.parents[1]


def _load_manifest(root: Path) -> dict[str, Any]:
    path = root / "manifest.json"
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise LauncherError(f"Runtime manifest is unreadable: {path}") from error
    if not isinstance(document, dict) or document.get("schemaVersion") != 2:
        raise LauncherError("Runtime manifest schemaVersion must be 2")
    required = {
        "version": str,
        "gitCommit": str,
        "serverJar": str,
        "kernelWheel": str,
        "javaVersion": int,
        "pythonRequires": str,
        "springProfile": str,
        "frontendIncluded": bool,
    }
    for name, value_type in required.items():
        value = document.get(name)
        if not isinstance(value, value_type) or (
            value_type is str and not value
        ):
            raise LauncherError(f"Runtime manifest field is invalid: {name}")
    if document["springProfile"] != _OWNED_PROFILE:
        raise LauncherError("Runtime manifest does not own scenario-workers")
    worker_host = document.get("scenarioWorkerHost")
    if (
        not isinstance(worker_host, dict)
        or not isinstance(worker_host.get("launcher"), str)
        or not worker_host["launcher"]
        or worker_host.get("autoStart") is not False
    ):
        raise LauncherError("Runtime manifest Scenario Worker Host is invalid")
    _safe_member(
        root,
        worker_host["launcher"],
        name="scenarioWorkerHost.launcher",
    )
    _python_requirement_bounds(document["pythonRequires"])
    return document


def _safe_member(root: Path, relative: str, *, name: str) -> Path:
    candidate = Path(relative)
    if candidate.is_absolute() or ".." in candidate.parts:
        raise LauncherError(f"Runtime manifest {name} escapes the archive root")
    resolved = (root / candidate).resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as error:
        raise LauncherError(
            f"Runtime manifest {name} escapes the archive root"
        ) from error
    if not resolved.is_file():
        raise LauncherError(f"Runtime manifest {name} is missing: {resolved}")
    return resolved


def _wheel_fingerprint(wheelhouse: Path, version: str) -> dict[str, Any]:
    wheels = sorted(wheelhouse.glob("*.whl"), key=lambda path: path.name)
    if not wheels:
        raise LauncherError(f"Runtime wheelhouse is empty: {wheelhouse}")
    return {
        "schemaVersion": 1,
        "runtimeVersion": version,
        "wheels": {
            wheel.name: hashlib.sha256(wheel.read_bytes()).hexdigest()
            for wheel in wheels
        },
    }


def _venv_python(venv_root: Path) -> Path:
    relative = Path("Scripts/python.exe") if os.name == "nt" else Path("bin/python")
    return venv_root / relative


def _read_marker(venv_root: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(
            (venv_root / _MARKER_NAME).read_text(encoding="utf-8")
        )
    except (OSError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def _assert_owned_runtime_path(runtime_root: Path, target: Path) -> None:
    runtime_directory = (runtime_root / ".runtime").resolve()
    if target.is_symlink():
        raise LauncherError(f"Refusing to replace symlinked runtime path: {target}")
    try:
        target.resolve(strict=False).relative_to(runtime_directory)
    except ValueError as error:
        raise LauncherError(f"Runtime path escapes .runtime: {target}") from error


def _remove_owned_directory(runtime_root: Path, target: Path) -> None:
    _assert_owned_runtime_path(runtime_root, target)
    if target.exists():
        shutil.rmtree(target)


def _ensure_venv(
    runtime_root: Path,
    manifest: dict[str, Any],
    wheelhouse: Path,
) -> Path:
    minimum_python, maximum_python = _python_requirement_bounds(
        manifest["pythonRequires"]
    )
    running_python = sys.version_info[:3]
    if not minimum_python <= running_python < maximum_python:
        raise LauncherError(
            "XA Mass Runtime requires Python "
            f"{manifest['pythonRequires']}; running {sys.version_info.major}."
            f"{sys.version_info.minor}.{sys.version_info.micro}"
        )

    runtime_directory = runtime_root / ".runtime"
    runtime_directory.mkdir(parents=True, exist_ok=True)
    venv_root = runtime_directory / "python-venv"
    expected_marker = _wheel_fingerprint(wheelhouse, manifest["version"])
    python = _venv_python(venv_root)
    if python.is_file() and _read_marker(venv_root) == expected_marker:
        return python

    build_root = runtime_directory / f"python-venv.build-{os.getpid()}"
    _remove_owned_directory(runtime_root, build_root)
    try:
        venv.EnvBuilder(with_pip=True, clear=False).create(build_root)
        build_python = _venv_python(build_root)
        subprocess.run(
            [
                str(build_python),
                "-m",
                "pip",
                "install",
                "--disable-pip-version-check",
                "--no-index",
                "--find-links",
                str(wheelhouse),
                f"xa-mass-kernel-pacer=={manifest['version']}",
            ],
            check=True,
        )
        (build_root / _MARKER_NAME).write_text(
            json.dumps(expected_marker, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        _remove_owned_directory(runtime_root, venv_root)
        os.replace(build_root, venv_root)
    except (OSError, subprocess.CalledProcessError) as error:
        _remove_owned_directory(runtime_root, build_root)
        raise LauncherError("Offline Kernel Pacer environment setup failed") from error
    return _venv_python(venv_root)


def _forwarded_arguments(arguments: list[str]) -> list[str]:
    if not arguments:
        return []
    if arguments[0] != "--":
        raise LauncherError("Spring Boot arguments must follow --")
    forwarded = arguments[1:]
    for argument in forwarded:
        if argument == "--spring.profiles.active" or argument.startswith(
            "--spring.profiles.active="
        ):
            raise LauncherError(
                "The Runtime distribution owns spring.profiles.active=scenario-workers"
            )
        if any(
            argument == owned or argument.startswith(f"{owned}=")
            for owned in _OWNED_SPRING_ARGUMENTS
        ):
            raise LauncherError(
                f"The Runtime distribution owns Spring argument: {argument.split('=', 1)[0]}"
            )
    return forwarded


def _resolve_pacer_config(runtime_root: Path) -> Path:
    override = os.environ.get("XA_MASS_KERNEL_PACER_CONFIG")
    if override is None:
        path = runtime_root / "config/pacer-default.json"
    else:
        path = Path(override).expanduser()
        if not path.is_absolute():
            raise LauncherError("XA_MASS_KERNEL_PACER_CONFIG must be absolute")
    resolved = path.resolve()
    if not resolved.is_file():
        raise LauncherError(f"Kernel Pacer config is missing: {resolved}")
    return resolved


def _java_command(
    runtime_root: Path,
    manifest: dict[str, Any],
    pacer_python: Path,
    forwarded: list[str],
) -> list[str]:
    java = os.environ.get("XA_MASS_JAVA_EXECUTABLE", "java")
    server_jar = _safe_member(
        runtime_root, manifest["serverJar"], name="serverJar"
    )
    frontend = (runtime_root / "frontend/dist").resolve()
    if not frontend.is_dir():
        raise LauncherError(f"Frontend distribution is missing: {frontend}")
    config = _resolve_pacer_config(runtime_root)
    state = (runtime_root / ".runtime/kernel-pacer").resolve()
    return [
        java,
        "-jar",
        str(server_jar),
        *forwarded,
        f"--spring.profiles.active={_OWNED_PROFILE}",
        f"--xa.mass.kernel-pacer.python-executable={pacer_python}",
        f"--xa.mass.kernel-pacer.working-directory={runtime_root}",
        f"--xa.mass.kernel-pacer.config-path={config}",
        f"--xa.mass.kernel-pacer.state-directory={state}",
        f"--spring.web.resources.static-locations={frontend.as_uri()}/",
    ]


def _run_java(command: list[str], runtime_root: Path) -> int:
    process = subprocess.Popen(command, cwd=runtime_root)
    try:
        return process.wait()
    except KeyboardInterrupt:
        try:
            return process.wait(timeout=_SHUTDOWN_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                return process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                return process.wait()


def main(arguments: list[str] | None = None) -> int:
    try:
        root = _runtime_root()
        manifest = _load_manifest(root)
        kernel_wheel = _safe_member(
            root, manifest["kernelWheel"], name="kernelWheel"
        )
        forwarded = _forwarded_arguments(
            list(sys.argv[1:] if arguments is None else arguments)
        )
        pacer_python = _ensure_venv(root, manifest, kernel_wheel.parent)
        return _run_java(
            _java_command(root, manifest, pacer_python, forwarded),
            root,
        )
    except LauncherError as error:
        print(f"XA Mass Runtime: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
