#!/usr/bin/env python3
"""Build and run one checked local XA Mass Runtime profile."""

from __future__ import annotations

import importlib.util

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Mapping, Sequence


ROOT = Path(__file__).resolve().parent
FRONTEND = ROOT / "frontend"
FRONTEND_DIST = FRONTEND / "dist"
DEFAULT_PROFILE = "preview"
SUPPORTED_PROFILES = (DEFAULT_PROFILE, "default", "consumer")
SHUTDOWN_TIMEOUT_SECONDS = 15


def parse_arguments(arguments: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build and run one built-in XA Mass local Runtime profile."
    )
    parser.add_argument(
        "--profile",
        choices=SUPPORTED_PROFILES,
        help="Default: preview, or consumer when --example is selected",
    )
    parser.add_argument("--example", choices=("android-messages",),
                        help="Local example defaults; uses the existing consumer profile")
    for option in ("count", "app-count", "seed", "port", "sandbox-root"):
        parser.add_argument("--" + option, help="Preview only; uses the Preview default when omitted")
    parser.add_argument("--runtime-config", type=Path, help="Consumer only: external Runtime YAML")
    parser.add_argument("--groups", type=Path, help="Consumer only: Group registration JSON")
    parser.add_argument("--server-port", type=int,
                        help="Consumer HTTP/readiness port: SERVER_PORT, then 18082 for the example or 18182 otherwise")
    args = parser.parse_args(arguments)
    args.profile = args.profile or ("consumer" if args.example else DEFAULT_PROFILE)
    if args.example and args.profile != "consumer":
        parser.error("--example requires the consumer profile")
    if args.example == "android-messages":
        args.runtime_config = args.runtime_config or ROOT / "xa-android/config/messages-runtime.yaml"
        args.groups = args.groups or ROOT / "xa-android/config/worker-groups.json"
    if args.profile != "preview" and any(
        getattr(args, name) is not None for name in ("count", "app_count", "seed", "port", "sandbox_root")
    ):
        parser.error("--count, --app-count, --seed, --port and --sandbox-root require --profile preview")
    if args.profile != "consumer" and any(
        getattr(args, name) is not None for name in ("runtime_config", "groups", "server_port")
    ):
        parser.error("--runtime-config, --groups and --server-port require --profile consumer")
    if args.server_port is not None and not 1 <= args.server_port <= 65_535:
        parser.error("--server-port must be in 1..65535")
    return args


def load_preview():
    path = ROOT / "distribution/server/run_preview.py"
    spec = importlib.util.spec_from_file_location("xa_mass_local_preview", path)
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
    except ModuleNotFoundError as error:
        if error.name not in {"psutil", "redis"}:
            raise
        raise RuntimeError(
            "Preview dependencies are missing; run python -m pip install -r "
            "distribution/server/requirements-preview.txt"
        ) from error
    return module


def package_manager() -> list[str]:
    corepack = shutil.which(
        "corepack.cmd" if os.name == "nt" else "corepack"
    )
    pnpm = shutil.which("pnpm.cmd" if os.name == "nt" else "pnpm")
    if corepack:
        return [corepack, "pnpm@11.9.0"]
    if pnpm:
        return [pnpm]
    raise RuntimeError("Neither corepack nor pnpm is available on PATH")


def build_frontend() -> None:
    if not (FRONTEND / ".env.local").exists():
        shutil.copyfile(FRONTEND / ".env.example", FRONTEND / ".env.local")
    command = package_manager()
    subprocess.run(
        [*command, "install", "--frozen-lockfile"],
        cwd=FRONTEND,
        check=True,
    )
    subprocess.run([*command, "build"], cwd=FRONTEND, check=True)


def gradle_tasks(profile: str) -> list[str]:
    tasks = [":server_boot_jvm:bootJar"]
    tasks.append(":distribution:server:installLocalPlatformDiagnosticCodes")
    return tasks


def build_runtime_processes(profile: str) -> Path:
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run(
        [str(gradle), *gradle_tasks(profile), "--console=plain"],
        cwd=ROOT,
        check=True,
    )
    version_match = re.search(
        r"\.orElse\('([^']+)'\)",
        (ROOT / "build.gradle").read_text(encoding="utf-8"),
    )
    if version_match is None:
        raise RuntimeError("Could not resolve the default Gradle project version")
    server_jar = (
        ROOT
        / "server_boot_jvm"
        / "build"
        / "libs"
        / f"xa-mass-server-jvm-{version_match.group(1)}.jar"
    )
    if not server_jar.is_file():
        raise RuntimeError(f"Expected Server Boot JAR is missing: {server_jar}")
    return server_jar


def java_executable(environ: Mapping[str, str]) -> str:
    return environ.get("XA_MASS_JAVA_EXECUTABLE", "java")


def _environment_port(
    environ: Mapping[str, str],
    name: str,
    default: int,
) -> int:
    raw = environ.get(name)
    if raw is None:
        return default
    try:
        port = int(raw)
    except ValueError as error:
        raise RuntimeError(f"{name} must be an integer") from error
    if not 1 <= port <= 65_535:
        raise RuntimeError(f"{name} must be in 1..65535")
    return port


def runtime_api_base_url(profile: str, environ: Mapping[str, str]) -> str:
    if "SERVER_PORT" in environ:
        port = _environment_port(environ, "SERVER_PORT", 18_082)
    elif profile == "consumer":
        port = 18_182
    else:
        port = 18_082
    return f"http://127.0.0.1:{port}"


def start_server(
    server_jar: Path,
    profile: str,
    environ: Mapping[str, str],
    extra_arguments: Sequence[str],
) -> subprocess.Popen[bytes]:
    command = [
        java_executable(environ),
        "-jar",
        str(server_jar),
        f"--spring.profiles.active={profile}",
        "--spring.web.resources.static-locations="
        f"{FRONTEND_DIST.resolve().as_uri()}/",
        *extra_arguments,
    ]
    return subprocess.Popen(command, cwd=ROOT, env=dict(environ))


def consumer_arguments(args: argparse.Namespace, environ: Mapping[str, str]) -> tuple[list[str], int]:
    """Freeze file inputs before building; Server owns their resource semantics."""
    default_port = 18_082 if args.example else 18_182
    port = args.server_port if args.server_port is not None else _environment_port(environ, "SERVER_PORT", default_port)
    if args.example and port == _environment_port(environ, "XA_MASS_ADAPTER_PORT", 18_083):
        raise ValueError("Server and Adapter ports must be different")
    command = [f"--server.port={port}"]
    if args.runtime_config is not None:
        configuration = args.runtime_config.resolve()
        if not configuration.is_file():
            raise ValueError(f"Runtime configuration file does not exist: {configuration}")
        command.append("--spring.config.additional-location=" + configuration.as_uri())
    if args.groups is not None:
        path = args.groups.resolve()
        if not path.is_file():
            raise ValueError(f"Group registration file does not exist: {path}")
        groups = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(groups, dict):
            raise ValueError("Group registration JSON must be an object")
        command.append("--xa.mass.worker-assembly.group-config-json=" + json.dumps(groups, separators=(",", ":")))
    return command, port


def wait_for_server(
    server: subprocess.Popen[bytes],
    runtime_api_url: str,
) -> None:
    readiness_url = f"{runtime_api_url}/actuator/health/readiness"
    deadline = time.monotonic() + 90
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        exit_code = server.poll()
        if exit_code is not None:
            raise RuntimeError(
                f"XA Mass Server exited before readiness with code {exit_code}"
            )
        try:
            with urllib.request.urlopen(readiness_url, timeout=2) as response:
                if response.status == 200 and b'"status":"UP"' in response.read():
                    return
        except (OSError, urllib.error.URLError) as error:
            last_error = error
        time.sleep(0.25)
    raise RuntimeError(
        f"XA Mass Server was not ready within 90 seconds: {last_error}"
    )


def stop_process(process: subprocess.Popen[bytes] | None, name: str) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        print(f"{name} did not stop within 15 seconds; killing it")
        process.kill()
        process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)


def supervise(server: subprocess.Popen[bytes]) -> int:
    while True:
        server_exit = server.poll()
        if server_exit is not None:
            print(f"XA Mass Server exited with code {server_exit}")
            return server_exit if server_exit != 0 else 1
        time.sleep(0.25)


def main(
    arguments: Sequence[str] | None = None,
    *,
    environ: Mapping[str, str] | None = None,
) -> int:
    args = parse_arguments(arguments)
    profile = args.profile
    if profile == "preview":
        forwarded = ["--build"]
        for name in ("count", "app_count", "seed", "port", "sandbox_root"):
            value = getattr(args, name)
            if value is not None:
                forwarded.extend(["--" + name.replace("_", "-"), value])
        return load_preview().main(forwarded)
    values = dict(os.environ if environ is None else environ)
    if args.example == "android-messages":
        values.setdefault("XA_MASS_ADAPTER_PORT", "18083")
        values.setdefault("XA_MASS_REDIS_SCOPE", "profile_android_messages_local")
        values.setdefault("XA_MASS_TASK_ARCHIVE_DIRECTORY", str(ROOT / "data/android-messages/task-archives"))
    server: subprocess.Popen[bytes] | None = None
    try:
        extra_arguments: list[str] = []
        if profile == "consumer":
            extra_arguments, port = consumer_arguments(args, values)
            runtime_api_url = f"http://127.0.0.1:{port}"
        else:
            runtime_api_url = runtime_api_base_url(profile, values)
        print("[1/3] Building frontend", flush=True)
        build_frontend()
        print(f"[2/3] Building local Runtime for {profile}", flush=True)
        server_jar = build_runtime_processes(profile)
        print(f"[3/3] Starting local Runtime for {profile}", flush=True)
        server = start_server(server_jar, profile, values, extra_arguments)
        wait_for_server(server, runtime_api_url)
        print(f"Open {runtime_api_url}/runtime/workers")
        print(f"Tasks {runtime_api_url}/runtime/tasks")
        if args.example == "android-messages":
            print(f"Messages {runtime_api_url}/messages")
            print("Start the installed Android Demo separately; its Runtime address and ADB mappings must match.")
        return supervise(server)
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Runtime startup failed: {error}", file=sys.stderr)
        return 1
    finally:
        stop_process(server, "XA Mass Server")


if __name__ == "__main__":
    sys.exit(main())
