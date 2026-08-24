#!/usr/bin/env python3
"""Start the optional standalone Scenario Worker Host."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path


_MAIN_CLASS = "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain"
_SHUTDOWN_TIMEOUT_SECONDS = 15


class LauncherError(RuntimeError):
    """Safe Scenario Worker Host launcher failure."""


def _runtime_root(script_path: Path | None = None) -> Path:
    script = (script_path or Path(__file__)).resolve()
    return script.parents[1]


def _java_command(
    runtime_root: Path,
    runtime_api_base_url: str,
    sandbox_root: Path,
) -> list[str]:
    library_root = (runtime_root / "scenario-workers/lib").resolve()
    if not library_root.is_dir() or not any(library_root.glob("*.jar")):
        raise LauncherError(
            f"Scenario Worker Host libraries are missing: {library_root}"
        )
    java = os.environ.get("XA_MASS_JAVA_EXECUTABLE", "java")
    return [
        java,
        "-cp",
        str(library_root / "*"),
        _MAIN_CLASS,
        f"--runtime-api-base-url={runtime_api_base_url}",
        f"--sandbox-root={sandbox_root.resolve()}",
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


def _arguments(arguments: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--runtime-api-base-url",
        default="http://127.0.0.1:18082",
    )
    parser.add_argument("--sandbox-root", type=Path)
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    try:
        root = _runtime_root()
        options = _arguments(
            list(sys.argv[1:] if arguments is None else arguments)
        )
        sandbox_root = options.sandbox_root or root / "data/scenario-workers"
        return _run_java(
            _java_command(root, options.runtime_api_base_url, sandbox_root),
            root,
        )
    except LauncherError as error:
        print(f"XA Mass Scenario Worker Host: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
