#!/usr/bin/env python3
"""Build and run one checked local XA Mass Runtime profile."""

from __future__ import annotations

import argparse
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
DEFAULT_PROFILE = "scenario-workers"
SUPPORTED_PROFILES = (DEFAULT_PROFILE, "agentforge")
SHUTDOWN_TIMEOUT_SECONDS = 15


def parse_profile(arguments: Sequence[str] | None = None) -> str:
    parser = argparse.ArgumentParser(
        description="Build and run one built-in XA Mass local Runtime profile."
    )
    parser.add_argument(
        "--profile",
        choices=SUPPORTED_PROFILES,
        default=DEFAULT_PROFILE,
    )
    return parser.parse_args(arguments).profile


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
    tasks = [":server_jvm:bootJar"]
    if profile == DEFAULT_PROFILE:
        tasks.append(":scenario_workers_jvm:installDist")
    tasks.append(":distribution:server:installLocalPlatformDiagnosticCodes")
    return tasks


def build_runtime_processes(profile: str) -> tuple[Path, list[Path]]:
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
        / "server_jvm"
        / "build"
        / "libs"
        / f"xa-mass-server-jvm-{version_match.group(1)}.jar"
    )
    if not server_jar.is_file():
        raise RuntimeError(f"Expected Server Boot JAR is missing: {server_jar}")
    if profile != DEFAULT_PROFILE:
        return server_jar, []
    host_lib = (
        ROOT
        / "scenario_workers_jvm"
        / "build"
        / "install"
        / "xa-mass-scenario-workers"
        / "lib"
    )
    host_jars = sorted(host_lib.glob("*.jar"))
    if not host_jars:
        raise RuntimeError(f"Scenario Worker Host libraries missing: {host_lib}")
    return server_jar, host_jars


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
    elif profile == "agentforge":
        port = _environment_port(
            environ,
            "XA_MASS_AGENTFORGE_SERVER_PORT",
            18_182,
        )
    else:
        port = 18_082
    return f"http://127.0.0.1:{port}"


def start_server(
    server_jar: Path,
    profile: str,
    environ: Mapping[str, str],
) -> subprocess.Popen[bytes]:
    command = [
        java_executable(environ),
        "-jar",
        str(server_jar),
        f"--spring.profiles.active={profile}",
        "--spring.web.resources.static-locations="
        f"{FRONTEND_DIST.resolve().as_uri()}/",
    ]
    return subprocess.Popen(command, cwd=ROOT, env=dict(environ))


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


def start_worker_host(
    host_jars: list[Path],
    runtime_api_url: str,
    environ: Mapping[str, str],
) -> subprocess.Popen[bytes]:
    classpath = os.pathsep.join(str(path) for path in host_jars)
    return subprocess.Popen(
        [
            java_executable(environ),
            "-cp",
            classpath,
            "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain",
            f"--runtime-api-base-url={runtime_api_url}",
            f"--sandbox-root={ROOT / 'data' / 'scenario-workers'}",
        ],
        cwd=ROOT,
        env=dict(environ),
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


def supervise(
    server: subprocess.Popen[bytes],
    worker_host: subprocess.Popen[bytes] | None,
) -> int:
    while True:
        if worker_host is not None:
            host_exit = worker_host.poll()
            if host_exit is not None:
                print(f"Scenario Worker Host exited with code {host_exit}")
                return host_exit if host_exit != 0 else 1
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
    profile = parse_profile(arguments)
    values = os.environ if environ is None else environ
    server: subprocess.Popen[bytes] | None = None
    worker_host: subprocess.Popen[bytes] | None = None
    try:
        print("[1/3] Building frontend", flush=True)
        build_frontend()
        print(f"[2/3] Building local Runtime for {profile}", flush=True)
        server_jar, host_jars = build_runtime_processes(profile)
        print(f"[3/3] Starting local Runtime for {profile}", flush=True)
        runtime_api_url = runtime_api_base_url(profile, values)
        server = start_server(server_jar, profile, values)
        wait_for_server(server, runtime_api_url)
        if profile == DEFAULT_PROFILE:
            worker_host = start_worker_host(host_jars, runtime_api_url, values)
        print(f"Open {runtime_api_url}/runtime/workers")
        print(f"Tasks {runtime_api_url}/runtime/tasks")
        return supervise(server, worker_host)
    except KeyboardInterrupt:
        return 0
    finally:
        stop_process(worker_host, "Scenario Worker Host")
        stop_process(server, "XA Mass Server")


if __name__ == "__main__":
    sys.exit(main())
