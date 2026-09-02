#!/usr/bin/env python3
"""Run the isolated 20-Worker correctness proof."""

from __future__ import annotations

import argparse
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE = ":integrations:worker-correctness"
RUNTIME_API = "http://127.0.0.1:18082"
LAB_API = "http://127.0.0.1:18086"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--redis-url", required=True)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=ROOT / "build/worker-correctness-proof",
    )
    parser.add_argument("--maximum-wait-millis", type=int, default=120_000)
    parser.add_argument("--request-timeout-millis", type=int, default=120_000)
    options = parser.parse_args()

    output = options.output_root.resolve()
    if output.exists():
        shutil.rmtree(output)
    evidence = output / "evidence"
    sandbox = output / "data" / "scenario-workers"
    evidence.mkdir(parents=True)
    sandbox.parent.mkdir(parents=True)

    scope = "test_worker_correctness_" + uuid.uuid4().hex[:12]
    proof_id = "worker-correctness-" + scope[-12:]
    environment = os.environ.copy()
    environment["XA_MASS_REDIS_URL"] = options.redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope

    _run([
        str(_gradle()),
        "--no-daemon",
        ":server_jvm:bootJar",
        ":scenario_workers_jvm:installDist",
        f"{MODULE}:classes",
    ], environment)

    server: subprocess.Popen[str] | None = None
    host: subprocess.Popen[str] | None = None
    try:
        server = _start_server(output, environment)
        _wait_http(
            f"{RUNTIME_API}/actuator/health/readiness",
            server,
            options.maximum_wait_millis,
            output / "runtime-server.log",
        )
        host = _start_host(output, sandbox, environment, "scenario-host-initial.log")
        _wait_http(
            f"{LAB_API}/lab/v1/workers",
            host,
            options.maximum_wait_millis,
            output / "scenario-host-initial.log",
        )

        initial = evidence / "worker-correctness-initial.json"
        _run_phase("initial", proof_id, sandbox, initial, None, options, environment)

        _stop_process(host, force=False)
        host = _start_host(output, sandbox, environment, "scenario-host-restart.log")
        _wait_http(
            f"{LAB_API}/lab/v1/workers",
            host,
            options.maximum_wait_millis,
            output / "scenario-host-restart.log",
        )
        _run_phase(
            "restart",
            proof_id,
            sandbox,
            evidence / "worker-correctness-restart.json",
            initial,
            options,
            environment,
        )
    finally:
        if host is not None:
            _stop_process(host, force=False)
        if server is not None:
            _stop_process(server, force=False)
        try:
            _run([
                sys.executable,
                str(ROOT / ".github/scripts/cleanup_redis_test_scope.py"),
                "--redis-url",
                options.redis_url,
                "--scope",
                scope,
                "--best-effort",
            ], environment)
        except BaseException as cleanup_error:
            print(f"Redis cleanup could not run: {cleanup_error}", file=sys.stderr)
    return 0


def _run_phase(
    phase: str,
    proof_id: str,
    sandbox: Path,
    evidence: Path,
    baseline: Path | None,
    options: argparse.Namespace,
    environment: dict[str, str],
) -> None:
    arguments = [
        f"--phase={phase}",
        f"--proof-id={proof_id}",
        f"--server-base-url={RUNTIME_API}",
        f"--correctness-spec={ROOT / 'integrations/worker-correctness/correctness-spec.json'}",
        f"--scenario-worker-lab-root={sandbox}",
        f"--phone-seed-path={ROOT / 'integrations/worker-correctness/phone-seed.txt'}",
        f"--string-seed-path={ROOT / 'integrations/worker-correctness/string-seed.txt'}",
        f"--evidence-file={evidence}",
        f"--maximum-wait-millis={options.maximum_wait_millis}",
        f"--request-timeout-millis={options.request_timeout_millis}",
    ]
    if baseline is not None:
        arguments.append(f"--baseline-file={baseline}")
    encoded = " ".join(_gradle_argument(value) for value in arguments)
    _run([
        str(_gradle()),
        "--no-daemon",
        f"{MODULE}:runWorkerCorrectness",
        f"--args={encoded}",
    ], environment)


def _start_server(
    output: Path,
    environment: dict[str, str],
) -> subprocess.Popen[str]:
    jars = [
        path for path in (ROOT / "server_jvm/build/libs").glob(
            "xa-mass-server-jvm-*.jar"
        ) if not path.name.endswith("-plain.jar")
    ]
    if not jars:
        raise RuntimeError("Runtime Server Boot JAR was not built")
    jar = max(jars, key=lambda path: path.stat().st_mtime_ns)
    return _start_process([
        "java",
        "-jar",
        str(jar),
        "--spring.profiles.active=scenario-workers",
    ], output / "runtime-server.log", environment)


def _start_host(
    output: Path,
    sandbox: Path,
    environment: dict[str, str],
    log_name: str,
) -> subprocess.Popen[str]:
    classpath = ROOT / "scenario_workers_jvm/build/install/xa-mass-scenario-workers/lib/*"
    return _start_process([
        "java",
        "-cp",
        str(classpath),
        "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain",
        f"--runtime-api-base-url={RUNTIME_API}",
        f"--sandbox-root={sandbox}",
        "--control-port=18086",
    ], output / log_name, environment)


def _start_process(
    command: list[str],
    log_path: Path,
    environment: dict[str, str],
) -> subprocess.Popen[str]:
    log = log_path.open("w", encoding="utf-8", newline="\n")
    try:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            creationflags=(
                subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
            ),
            start_new_session=os.name != "nt",
        )
    except BaseException:
        log.close()
        raise
    log.close()
    return process


def _stop_process(process: subprocess.Popen[str], *, force: bool) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        command = ["taskkill", "/PID", str(process.pid), "/T"]
        if force:
            command.append("/F")
        subprocess.run(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    elif force:
        os.killpg(process.pid, signal.SIGKILL)
    else:
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        else:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=15)


def _wait_http(
    url: str,
    process: subprocess.Popen[str],
    maximum_wait_millis: int,
    log_path: Path,
) -> None:
    deadline = time.monotonic() + maximum_wait_millis / 1000
    latest: BaseException | None = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(
                f"Process exited with {process.returncode}; log:\n{_tail(log_path)}"
            )
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if 200 <= response.status < 300:
                    return
        except (OSError, urllib.error.URLError) as error:
            latest = error
        time.sleep(0.2)
    raise RuntimeError(f"Timed out waiting for {url}: {latest}; log:\n{_tail(log_path)}")


def _run(command: list[str], environment: dict[str, str]) -> None:
    subprocess.run(command, cwd=ROOT, env=environment, check=True)


def _tail(path: Path, maximum_lines: int = 80) -> str:
    if not path.exists():
        return "<missing>"
    return "\n".join(
        path.read_text(encoding="utf-8", errors="replace").splitlines()[
            -maximum_lines:
        ]
    )


def _gradle() -> Path:
    return ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")


def _gradle_argument(value: str) -> str:
    if not any(character.isspace() for character in value) and '"' not in value:
        return value
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


if __name__ == "__main__":
    raise SystemExit(main())
