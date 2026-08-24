"""Build and run the complete local Scenario Workers Lab."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent
FRONTEND = ROOT / "frontend"
SERVER_READY_URL = "http://127.0.0.1:18082/actuator/health/readiness"
RUNTIME_API_BASE_URL = "http://127.0.0.1:18082"
SHUTDOWN_TIMEOUT_SECONDS = 15


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


def build_runtime_processes() -> tuple[Path, list[Path]]:
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run(
        [
            str(gradle),
            ":server_jvm:bootJar",
            ":scenario_workers_jvm:installDist",
            "--console=plain",
        ],
        cwd=ROOT,
        check=True,
    )
    version_match = re.search(
        r"\.orElse\('([^']+)'\)",
        (ROOT / "build.gradle").read_text(encoding="utf-8"),
    )
    if version_match is None:
        raise RuntimeError(
            "Could not resolve the default Gradle project version"
        )
    server_jar = (
        ROOT
        / "server_jvm"
        / "build"
        / "libs"
        / f"xa-mass-server-jvm-{version_match.group(1)}.jar"
    )
    if not server_jar.is_file():
        raise RuntimeError(
            f"Expected Server Boot JAR is missing: {server_jar}"
        )
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


def java_executable() -> str:
    return os.environ.get("XA_MASS_JAVA_EXECUTABLE", "java")


def start_server(server_jar: Path) -> subprocess.Popen[bytes]:
    return subprocess.Popen(
        [
            java_executable(),
            "-jar",
            str(server_jar),
            "--spring.profiles.active=scenario-workers",
            "--xa.mass.kernel-pacer.config-path="
            "integrations/worker-capability-task/kernel-config.json",
        ],
        cwd=ROOT,
    )


def wait_for_server(server: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + 90
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        exit_code = server.poll()
        if exit_code is not None:
            raise RuntimeError(
                f"XA Mass Server exited before readiness with code {exit_code}"
            )
        try:
            with urllib.request.urlopen(SERVER_READY_URL, timeout=2) as response:
                if response.status == 200 and b'"status":"UP"' in response.read():
                    return
        except (OSError, urllib.error.URLError) as error:
            last_error = error
        time.sleep(0.25)
    raise RuntimeError(
        f"XA Mass Server was not ready within 90 seconds: {last_error}"
    )


def start_worker_host(host_jars: list[Path]) -> subprocess.Popen[bytes]:
    classpath = os.pathsep.join(str(path) for path in host_jars)
    return subprocess.Popen(
        [
            java_executable(),
            "-cp",
            classpath,
            "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain",
            f"--runtime-api-base-url={RUNTIME_API_BASE_URL}",
            f"--sandbox-root={ROOT / 'data' / 'scenario-workers'}",
        ],
        cwd=ROOT,
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
    worker_host: subprocess.Popen[bytes],
) -> int:
    while True:
        host_exit = worker_host.poll()
        server_exit = server.poll()
        if host_exit is not None:
            print(f"Scenario Worker Host exited with code {host_exit}")
            return host_exit if host_exit != 0 else 1
        if server_exit is not None:
            print(f"XA Mass Server exited with code {server_exit}")
            return server_exit if server_exit != 0 else 1
        time.sleep(0.25)


def main() -> int:
    server: subprocess.Popen[bytes] | None = None
    worker_host: subprocess.Popen[bytes] | None = None
    try:
        print("[1/3] Building frontend", flush=True)
        build_frontend()
        print("[2/3] Building Server and Scenario Worker Host", flush=True)
        server_jar, host_jars = build_runtime_processes()
        print("[3/3] Starting Server, Pacer, Adapter, and Worker Host", flush=True)
        server = start_server(server_jar)
        wait_for_server(server)
        worker_host = start_worker_host(host_jars)
        print("Open http://127.0.0.1:18082/runtime/workers")
        print("Tasks http://127.0.0.1:18082/runtime/tasks")
        return supervise(server, worker_host)
    except KeyboardInterrupt:
        return 0
    finally:
        stop_process(worker_host, "Scenario Worker Host")
        stop_process(server, "XA Mass Server")


if __name__ == "__main__":
    sys.exit(main())
