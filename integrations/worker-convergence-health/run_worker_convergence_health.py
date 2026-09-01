#!/usr/bin/env python3
"""One-shot owner for the two Worker convergence-health scenarios."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MODULE_TASK = ":integrations:worker-convergence-health"
RUNTIME_API = "http://127.0.0.1:18082"
LAB_CONTROL = "http://127.0.0.1:18086"
ENDPOINT_MANAGER = "scenario-websocket"
PHONE_GROUP = "scenario-phone-number-workers"
STRING_GROUP = "scenario-string-utils-workers"
PHONE_FILE = "convergence-phone-workers.jsonl"
STRING_FILE = "convergence-string-workers.jsonl"
PHONE_WORKERS = tuple((PHONE_GROUP, f"{PHONE_FILE}:{line}") for line in range(1, 51))
STRING_WORKERS = tuple((STRING_GROUP, f"{STRING_FILE}:{line}") for line in range(1, 51))
CONVERGENCE_WORKERS = PHONE_WORKERS + STRING_WORKERS
FAULT_TARGET = STRING_WORKERS[0]
FAULT_BACKUP = STRING_WORKERS[1]
TEST_SCOPE = re.compile(r"test_[a-z0-9_]+")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run isolated Worker convergence-health scenarios."
    )
    parser.add_argument(
        "--scenario",
        choices=("state", "task-fault", "all"),
        default="all",
    )
    parser.add_argument("--redis-url", required=True)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=REPOSITORY_ROOT / "build/worker-convergence-health-proof",
    )
    parser.add_argument("--maximum-wait-millis", type=int, default=120_000)
    parser.add_argument("--request-timeout-millis", type=int, default=10_000)
    options = parser.parse_args()
    if not 1_000 <= options.maximum_wait_millis <= 300_000:
        parser.error("--maximum-wait-millis must be in 1000..300000")
    if not 100 <= options.request_timeout_millis <= 60_000:
        parser.error("--request-timeout-millis must be in 100..60000")

    output_root = options.output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    _build_artifacts()
    scenarios = (
        ("state", "task-fault")
        if options.scenario == "all"
        else (options.scenario,)
    )
    failures: list[tuple[str, BaseException]] = []
    for scenario in scenarios:
        try:
            _run_scenario(scenario, options, output_root)
        except BaseException as error:  # Preserve cleanup for Ctrl+C too.
            failures.append((scenario, error))
            if isinstance(error, KeyboardInterrupt):
                break
    if failures:
        for scenario, error in failures:
            print(
                f"Worker convergence scenario {scenario} failed: {error}",
                file=sys.stderr,
            )
        return 1
    return 0


def _build_artifacts() -> None:
    _run(
        [
            str(_gradle_wrapper()),
            "--no-daemon",
            ":server_jvm:bootJar",
            ":scenario_workers_jvm:installDist",
            f"{MODULE_TASK}:classes",
        ],
        cwd=REPOSITORY_ROOT,
    )


def _run_scenario(
    scenario: str,
    options: argparse.Namespace,
    output_root: Path,
) -> None:
    scenario_root = (output_root / scenario).resolve()
    _reset_scenario_directory(output_root, scenario_root)
    evidence = scenario_root / "evidence"
    sandbox = scenario_root / "data" / "scenario-workers"
    evidence.mkdir(parents=True)
    _write_convergence_inventory(sandbox)
    scope = "test_worker_convergence_{}_{}".format(
        scenario.replace("-", "_"),
        uuid.uuid4().hex[:12],
    )
    proof_id = f"worker-convergence-{scenario}-{scope[-12:]}"
    environment = os.environ.copy()
    environment["XA_MASS_REDIS_URL"] = options.redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope
    server: subprocess.Popen[str] | None = None
    host: subprocess.Popen[str] | None = None
    failure: BaseException | None = None
    try:
        server = _start_server(scenario_root, environment)
        _wait_http(
            f"{RUNTIME_API}/actuator/health/readiness",
            server,
            options.maximum_wait_millis,
            scenario_root / "runtime-server.log",
        )
        if scenario == "state":
            phase_state = scenario_root / "state-server-phase.json"
            host = _start_host(
                scenario_root,
                sandbox,
                _startup_plan(CONVERGENCE_WORKERS, ()),
                environment,
            )
            _wait_lab(host, options, scenario_root)
            _run_state_phase(
                "before-server-restart",
                options,
                proof_id,
                evidence,
                phase_state,
                environment,
            )
            _stop_process(server, force=False)
            server = _start_server(
                scenario_root,
                environment,
                log_name="runtime-server-restarted.log",
            )
            _wait_http(
                f"{RUNTIME_API}/actuator/health/readiness",
                server,
                options.maximum_wait_millis,
                scenario_root / "runtime-server-restarted.log",
            )
            _run_state_phase(
                "after-server-restart",
                options,
                proof_id,
                evidence,
                phase_state,
                environment,
            )
        elif scenario == "task-fault":
            phase_state = scenario_root / "task-fault-state.json"
            host = _start_host(
                scenario_root,
                sandbox,
                _startup_plan(CONVERGENCE_WORKERS, ()),
                environment,
                log_name="scenario-host-target.log",
            )
            _wait_lab(
                host,
                options,
                scenario_root,
                "scenario-host-target.log",
            )
            _run_task_fault_phase(
                "arm", options, proof_id, evidence, phase_state, environment
            )
            _stop_process(host, force=True)
            host = None
            _run_task_fault_phase(
                "down", options, proof_id, evidence, phase_state, environment
            )
            _replace_worker_lab_slot(sandbox, FAULT_BACKUP, 1)
            host = _start_host(
                scenario_root,
                sandbox,
                _startup_plan(
                    tuple(
                        worker
                        for worker in CONVERGENCE_WORKERS
                        if worker != FAULT_TARGET
                    ),
                    (),
                ),
                environment,
                log_name="scenario-host-recovery.log",
            )
            _wait_lab(
                host,
                options,
                scenario_root,
                "scenario-host-recovery.log",
            )
            _run_task_fault_phase(
                "recover", options, proof_id, evidence, phase_state, environment
            )
            _stop_process(host, force=True)
            host = None
            _run_task_fault_phase(
                "finality", options, proof_id, evidence, phase_state, environment
            )
        else:
            raise ValueError(f"Unsupported scenario: {scenario}")
    except BaseException as error:
        failure = error
        _write_runner_failure(evidence, scenario, proof_id, error)
        raise
    finally:
        if host is not None:
            _stop_process(host, force=False)
        if server is not None:
            _stop_process(server, force=False)
        try:
            _cleanup_scope(options.redis_url, scope)
        except BaseException as cleanup_error:
            if failure is None:
                raise
            print(
                f"Redis cleanup also failed for {scope}: {cleanup_error}",
                file=sys.stderr,
            )


def _run_state_phase(
    phase: str,
    options: argparse.Namespace,
    proof_id: str,
    evidence: Path,
    phase_state: Path,
    environment: dict[str, str],
) -> None:
    arguments = _common_harness_arguments(options, proof_id, evidence)
    arguments.extend((f"--phase={phase}", f"--phase-state={phase_state}"))
    _run_harness("runWorkerStateAndServerConvergence", arguments, environment)


def _run_task_fault_phase(
    phase: str,
    options: argparse.Namespace,
    proof_id: str,
    evidence: Path,
    phase_state: Path,
    environment: dict[str, str],
) -> None:
    arguments = _common_harness_arguments(options, proof_id, evidence)
    arguments.extend((f"--phase={phase}", f"--phase-state={phase_state}"))
    _run_harness("runWorkerTaskFaultConvergence", arguments, environment)


def _common_harness_arguments(
    options: argparse.Namespace,
    proof_id: str,
    evidence: Path,
) -> list[str]:
    return [
        f"--proof-id={proof_id}",
        f"--runtime-api-base-url={RUNTIME_API}",
        f"--lab-control-base-url={LAB_CONTROL}",
        f"--endpoint-manager-id={ENDPOINT_MANAGER}",
        f"--evidence-dir={evidence}",
        f"--maximum-wait-millis={options.maximum_wait_millis}",
        f"--request-timeout-millis={options.request_timeout_millis}",
    ]


def _run_harness(
    task: str,
    arguments: list[str],
    environment: dict[str, str],
) -> None:
    encoded = " ".join(_gradle_argument(argument) for argument in arguments)
    _run(
        [
            str(_gradle_wrapper()),
            "--no-daemon",
            f"{MODULE_TASK}:{task}",
            f"--args={encoded}",
        ],
        cwd=REPOSITORY_ROOT,
        env=environment,
    )


def _start_server(
    scenario_root: Path,
    environment: dict[str, str],
    *,
    log_name: str = "runtime-server.log",
) -> subprocess.Popen[str]:
    jars = sorted(
        path
        for path in (REPOSITORY_ROOT / "server_jvm/build/libs").glob(
            "xa-mass-server-jvm-*.jar"
        )
        if not path.name.endswith("-plain.jar")
    )
    if not jars:
        raise RuntimeError("Runtime Server Boot JAR was not built")
    boot_jar = max(jars, key=lambda path: path.stat().st_mtime_ns)
    return _start_process(
        [
            "java",
            "-jar",
            str(boot_jar),
            "--spring.profiles.active=scenario-workers",
        ],
        scenario_root / log_name,
        environment,
    )


def _start_host(
    scenario_root: Path,
    sandbox: Path,
    plan: dict[str, object],
    environment: dict[str, str],
    *,
    log_name: str = "scenario-host.log",
) -> subprocess.Popen[str]:
    plan_path = scenario_root / f"startup-{log_name.removesuffix('.log')}.json"
    assembly_path = scenario_root / "capability-assembly.json"
    _atomic_write_json(plan_path, plan)
    _atomic_write_json(assembly_path, _capability_assembly())
    classpath = (
        REPOSITORY_ROOT
        / "scenario_workers_jvm/build/install/xa-mass-scenario-workers/lib/*"
    )
    return _start_process(
        [
            "java",
            "-cp",
            str(classpath),
            "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain",
            f"--runtime-api-base-url={RUNTIME_API}",
            f"--sandbox-root={sandbox}",
            "--control-port=18086",
            f"--startup-plan={plan_path}",
            f"--capability-assembly={assembly_path}",
        ],
        scenario_root / log_name,
        environment,
    )


def _capability_assembly() -> dict[str, object]:
    reconnect_policy = {
        "maxUnstableAttempts": 600,
        "reconnectIntervalMillis": 500,
        "stableConnectionDurationMillis": 10_000,
    }
    return {
        PHONE_GROUP: {
            "eventCodes": ["extension.worker.phonenumber.e164"],
            "requestTimeoutMillis": 10_000,
            "reconnectPolicy": reconnect_policy,
        },
        STRING_GROUP: {
            "eventCodes": [
                "extension.worker.string.md5",
                "extension.worker.lab.checkpoint",
            ],
            "requestTimeoutMillis": 10_000,
            "reconnectPolicy": reconnect_policy,
        },
    }


def _wait_lab(
    host: subprocess.Popen[str],
    options: argparse.Namespace,
    scenario_root: Path,
    log_name: str = "scenario-host.log",
) -> None:
    _wait_http(
        f"{LAB_CONTROL}/lab/v1/workers",
        host,
        options.maximum_wait_millis,
        scenario_root / log_name,
    )


def _startup_plan(
    initial_workers: tuple[tuple[str, str], ...],
    scheduled_stops: tuple[tuple[tuple[str, str], int], ...],
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "initialWorkers": [
            {"workerGroupId": group, "labWorkerKey": key}
            for group, key in initial_workers
        ],
        "scheduledStops": [
            {
                "workerGroupId": worker[0],
                "labWorkerKey": worker[1],
                "delayMillis": delay,
            }
            for worker, delay in scheduled_stops
        ],
    }


def _write_convergence_inventory(sandbox: Path) -> None:
    _write_group_inventory(
        sandbox,
        PHONE_GROUP,
        PHONE_FILE,
        "libphonenumber",
    )
    _write_group_inventory(
        sandbox,
        STRING_GROUP,
        STRING_FILE,
        "string-utils",
    )


def _write_group_inventory(
    sandbox: Path,
    worker_group_id: str,
    filename: str,
    capability: str,
) -> None:
    lines: list[str] = []
    for line in range(1, 51):
        lines.append(
            json.dumps(
                {
                    "schemaVersion": 2,
                    "workerProperties": {
                        "labInventoryKey": filename,
                        "labInventoryLine": line,
                        "runtime": "java",
                        "capability": capability,
                        "region": "local",
                        "labSlot": line,
                        "convergenceSlot": "A",
                    },
                },
                separators=(",", ":"),
                sort_keys=True,
            )
        )
    _atomic_write_text(
        sandbox / worker_group_id / filename,
        "\n".join(lines) + "\n",
    )


def _replace_worker_lab_slot(
    sandbox: Path,
    worker: tuple[str, str],
    lab_slot: int,
) -> None:
    filename, separator, raw_line_number = worker[1].rpartition(":")
    if separator != ":" or not filename.endswith(".jsonl"):
        raise RuntimeError(f"Worker key is not filename:line: {worker[1]}")
    try:
        line_number = int(raw_line_number)
    except ValueError as error:
        raise RuntimeError(
            f"Worker key has an invalid line number: {worker[1]}"
        ) from error
    if line_number < 1:
        raise RuntimeError(f"Worker key has an invalid line number: {worker[1]}")

    path = sandbox / worker[0] / filename
    lines = path.read_text(encoding="utf-8").splitlines()
    if line_number > len(lines):
        raise RuntimeError(f"Worker state line does not exist: {worker[1]}")
    value = json.loads(lines[line_number - 1])
    if set(value) != {"schemaVersion", "workerProperties"}:
        raise RuntimeError(f"Worker state line has unexpected fields: {worker[1]}")
    if value["schemaVersion"] != 2 or not isinstance(
        value["workerProperties"], dict
    ):
        raise RuntimeError(f"Worker state line is invalid: {worker[1]}")
    value["workerProperties"]["labSlot"] = lab_slot
    lines[line_number - 1] = json.dumps(
        value, separators=(",", ":"), sort_keys=True
    )
    _atomic_write_text(path, "\n".join(lines) + "\n")


def _atomic_write_json(path: Path, value: object) -> None:
    _atomic_write_text(
        path,
        json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n",
    )


def _atomic_write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _start_process(
    command: list[str],
    log_path: Path,
    environment: dict[str, str],
) -> subprocess.Popen[str]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = log_path.open("w", encoding="utf-8", newline="\n")
    creationflags = (
        subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    )
    try:
        process = subprocess.Popen(
            command,
            cwd=REPOSITORY_ROOT,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            creationflags=creationflags,
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
    deadline = time.monotonic() + maximum_wait_millis / 1_000
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
    raise RuntimeError(
        f"Timed out waiting for {url}: {latest}; log:\n{_tail(log_path)}"
    )


def _cleanup_scope(redis_url: str, scope: str) -> None:
    if TEST_SCOPE.fullmatch(scope) is None:
        raise ValueError("cleanup scope must be an exact test_* scope")
    _run(
        [
            sys.executable,
            str(REPOSITORY_ROOT / ".github/scripts/cleanup_redis_test_scope.py"),
            "--redis-url",
            redis_url,
            "--scope",
            scope,
        ],
        cwd=REPOSITORY_ROOT,
    )


def _write_runner_failure(
    evidence: Path,
    lane: str,
    proof_id: str,
    error: BaseException,
) -> None:
    evidence.mkdir(parents=True, exist_ok=True)
    canonical_lane = _lane_file_name(lane)
    path = evidence / f"worker-convergence-{canonical_lane}-summary.json"
    if path.exists():
        return
    _atomic_write_json(
        path,
        {
            "proofId": proof_id,
            "lane": canonical_lane,
            "status": "failed",
            "failureKind": "proof-not-established",
            "failure": str(error) or type(error).__name__,
            "completedAtEpochMillis": int(time.time() * 1_000),
        },
    )


def _lane_file_name(lane: str) -> str:
    return {
        "state": "state-server-convergence",
        "task-fault": "in-flight-loss-convergence",
    }[lane]


def _reset_scenario_directory(
    output_root: Path,
    scenario_root: Path,
) -> None:
    if scenario_root.parent != output_root or scenario_root == output_root:
        raise RuntimeError(
            "Scenario output must be a direct child of output root"
        )
    if scenario_root.exists():
        shutil.rmtree(scenario_root)
    scenario_root.mkdir(parents=True)


def _tail(path: Path, maximum_lines: int = 80) -> str:
    if not path.exists():
        return "<missing>"
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-maximum_lines:])


def _gradle_wrapper() -> Path:
    return REPOSITORY_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")


def _gradle_argument(value: str) -> str:
    if not any(character.isspace() for character in value) and '"' not in value:
        return value
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _run(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
) -> None:
    subprocess.run(command, cwd=cwd, env=env, check=True)


if __name__ == "__main__":
    raise SystemExit(main())
