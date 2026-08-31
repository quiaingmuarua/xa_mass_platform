#!/usr/bin/env python3
"""One-shot owner for the three Worker Lab convergence proof lanes."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
MODULE_TASK = ":integrations:worker-lab-reliability"
RUNTIME_API = "http://127.0.0.1:18082"
LAB_CONTROL = "http://127.0.0.1:18086"
ENDPOINT_MANAGER = "scenario-websocket"
PHONE_GROUP = "scenario-phone-number-workers"
STRING_GROUP = "scenario-string-utils-workers"
PHONE_ONE = (PHONE_GROUP, "scenario-phone-number-worker-001")
PHONE_TWO = (PHONE_GROUP, "scenario-phone-number-worker-002")
STRING_ONE = (STRING_GROUP, "scenario-string-utils-worker-001")
STRING_TWO = (STRING_GROUP, "scenario-string-utils-worker-002")
CONTROLLED = (PHONE_ONE, PHONE_TWO, STRING_ONE, STRING_TWO)
TEST_SCOPE = re.compile(r"test_[a-z0-9_]+")
REDIS_BATCH_SIZE = 100


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run isolated Worker Lab convergence proof lanes."
    )
    parser.add_argument(
        "--lane",
        choices=("state", "task-fault", "campaign", "all"),
        default="all",
    )
    parser.add_argument("--redis-url", required=True)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=REPOSITORY_ROOT / "build/worker-lab-convergence-proof",
    )
    parser.add_argument("--maximum-wait-millis", type=int, default=120_000)
    parser.add_argument("--request-timeout-millis", type=int, default=10_000)
    parser.add_argument("--seed", type=int, default=20_260_831)
    parser.add_argument("--rounds", type=int, default=20)
    options = parser.parse_args()
    if not 1_000 <= options.maximum_wait_millis <= 300_000:
        parser.error("--maximum-wait-millis must be in 1000..300000")
    if not 100 <= options.request_timeout_millis <= 60_000:
        parser.error("--request-timeout-millis must be in 100..60000")
    if not 1 <= options.rounds <= 100:
        parser.error("--rounds must be in 1..100")

    output_root = options.output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    _build_artifacts()
    lanes = (
        ("state", "task-fault", "campaign")
        if options.lane == "all"
        else (options.lane,)
    )
    failures: list[tuple[str, BaseException]] = []
    for lane in lanes:
        try:
            _run_lane(lane, options, output_root)
        except BaseException as error:  # Preserve cleanup for Ctrl+C too.
            failures.append((lane, error))
            if isinstance(error, KeyboardInterrupt):
                break
    if failures:
        for lane, error in failures:
            print(f"Worker Lab lane {lane} failed: {error}", file=sys.stderr)
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


def _run_lane(lane: str, options: argparse.Namespace, output_root: Path) -> None:
    lane_root = (output_root / lane).resolve()
    _reset_lane_directory(output_root, lane_root)
    evidence = lane_root / "evidence"
    sandbox = lane_root / "data" / "scenario-workers"
    evidence.mkdir(parents=True)
    sandbox.parent.mkdir(parents=True)
    scope = "test_worker_lab_{}_{}".format(
        lane.replace("-", "_"),
        uuid.uuid4().hex[:12],
    )
    proof_id = f"worker-lab-{lane}-{scope[-12:]}"
    environment = os.environ.copy()
    environment["XA_MASS_REDIS_URL"] = options.redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope
    server: subprocess.Popen[str] | None = None
    host: subprocess.Popen[str] | None = None
    failure: BaseException | None = None
    try:
        server = _start_server(lane_root, environment)
        _wait_http(
            f"{RUNTIME_API}/actuator/health/readiness",
            server,
            options.maximum_wait_millis,
            lane_root / "runtime-server.log",
        )
        if lane == "state":
            plan = _startup_plan(
                CONTROLLED,
                ((STRING_ONE, 15_000),),
            )
            host = _start_host(lane_root, sandbox, plan, environment)
            _wait_lab(host, options, lane_root)
            _run_harness(
                "runWorkerStateConvergence",
                _common_harness_arguments(options, proof_id, evidence),
                environment,
            )
        elif lane == "campaign":
            host = _start_host(
                lane_root,
                sandbox,
                _startup_plan(CONTROLLED, ()),
                environment,
            )
            _wait_lab(host, options, lane_root)
            arguments = _common_harness_arguments(options, proof_id, evidence)
            arguments.extend((f"--seed={options.seed}", f"--rounds={options.rounds}"))
            _run_harness("runWorkerConvergenceCampaign", arguments, environment)
        elif lane == "task-fault":
            phase_state = lane_root / "task-fault-state.json"
            host = _start_host(
                lane_root,
                sandbox,
                _startup_plan((STRING_ONE,), ()),
                environment,
                log_name="scenario-host-target.log",
            )
            _wait_lab(host, options, lane_root, "scenario-host-target.log")
            _run_task_fault_phase(
                "arm", options, proof_id, evidence, phase_state, environment
            )
            _stop_process(host, force=True)
            host = None
            _run_task_fault_phase(
                "down", options, proof_id, evidence, phase_state, environment
            )
            _replace_worker_lab_slot(sandbox, STRING_TWO, 1)
            host = _start_host(
                lane_root,
                sandbox,
                _startup_plan((STRING_TWO,), ()),
                environment,
                log_name="scenario-host-recovery.log",
            )
            _wait_lab(host, options, lane_root, "scenario-host-recovery.log")
            _run_task_fault_phase(
                "recover", options, proof_id, evidence, phase_state, environment
            )
            _stop_process(host, force=True)
            host = None
            _run_task_fault_phase(
                "finality", options, proof_id, evidence, phase_state, environment
            )
        else:
            raise ValueError(f"Unsupported lane: {lane}")
    except BaseException as error:
        failure = error
        _write_runner_failure(evidence, lane, proof_id, error)
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
    lane_root: Path,
    environment: dict[str, str],
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
        lane_root / "runtime-server.log",
        environment,
    )


def _start_host(
    lane_root: Path,
    sandbox: Path,
    plan: dict[str, object],
    environment: dict[str, str],
    *,
    log_name: str = "scenario-host.log",
) -> subprocess.Popen[str]:
    plan_path = lane_root / f"startup-{log_name.removesuffix('.log')}.json"
    _atomic_write_json(plan_path, plan)
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
        ],
        lane_root / log_name,
        environment,
    )


def _wait_lab(
    host: subprocess.Popen[str],
    options: argparse.Namespace,
    lane_root: Path,
    log_name: str = "scenario-host.log",
) -> None:
    _wait_http(
        f"{LAB_CONTROL}/lab/v1/workers",
        host,
        options.maximum_wait_millis,
        lane_root / log_name,
    )


def _startup_plan(
    initial_workers: tuple[tuple[str, str], ...],
    scheduled_stops: tuple[tuple[tuple[str, str], int], ...],
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "initialWorkers": [
            {"workerGroupId": group, "clientWorkerKey": key}
            for group, key in initial_workers
        ],
        "scheduledStops": [
            {
                "workerGroupId": worker[0],
                "clientWorkerKey": worker[1],
                "delayMillis": delay,
            }
            for worker, delay in scheduled_stops
        ],
    }


def _replace_worker_lab_slot(
    sandbox: Path,
    worker: tuple[str, str],
    lab_slot: int,
) -> None:
    path = sandbox / worker[0] / f"{worker[1]}.json"
    value = json.loads(path.read_text(encoding="utf-8"))
    if set(value) != {"schemaVersion", "workerProperties"}:
        raise RuntimeError(f"Worker state file has unexpected fields: {path}")
    if value["schemaVersion"] != 2 or not isinstance(
        value["workerProperties"], dict
    ):
        raise RuntimeError(f"Worker state file is invalid: {path}")
    value["workerProperties"]["labSlot"] = lab_slot
    _atomic_write_json(path, value)


def _atomic_write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            json.dump(value, output, separators=(",", ":"), sort_keys=True)
            output.write("\n")
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
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
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
        if os.name != "nt":
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
    observed: set[bytes] = set()
    with _RedisConnection(redis_url) as redis:
        cursor = 0
        while True:
            response = redis.command(
                "SCAN",
                str(cursor),
                "MATCH",
                f"xa_mass:{scope}:*",
                "COUNT",
                str(REDIS_BATCH_SIZE),
            )
            if not isinstance(response, list) or len(response) != 2:
                raise RuntimeError("Redis SCAN returned an invalid response")
            cursor = int(_redis_text(response[0], "SCAN cursor"))
            keys = response[1]
            if not isinstance(keys, list) or any(
                not isinstance(key, bytes) for key in keys
            ):
                raise RuntimeError("Redis SCAN keys are invalid")
            observed.update(keys)
            if cursor == 0:
                break

        removed = 0
        ordered = sorted(observed)
        for offset in range(0, len(ordered), REDIS_BATCH_SIZE):
            result = redis.command(
                "UNLINK",
                *ordered[offset:offset + REDIS_BATCH_SIZE],
            )
            if not isinstance(result, int):
                raise RuntimeError("Redis UNLINK returned an invalid response")
            removed += result
    print(
        "cleaned Redis test scope "
        f"scope={scope} observedKeys={len(observed)} removedKeys={removed}"
    )


class _RedisConnection:

    def __init__(self, redis_url: str) -> None:
        parsed = urllib.parse.urlsplit(redis_url)
        if parsed.scheme not in {"redis", "rediss"}:
            raise ValueError("Redis URL scheme must be redis or rediss")
        if parsed.hostname is None:
            raise ValueError("Redis URL must contain a host")
        if parsed.query or parsed.fragment:
            raise ValueError("Redis URL must not contain query or fragment")
        try:
            port = parsed.port or 6379
        except ValueError as error:
            raise ValueError("Redis URL port is invalid") from error
        database_text = parsed.path.removeprefix("/")
        if "/" in database_text or (
            database_text and not database_text.isdigit()
        ):
            raise ValueError("Redis URL database must be a non-negative integer")
        database = int(database_text or "0")
        username = (
            urllib.parse.unquote(parsed.username)
            if parsed.username is not None
            else None
        )
        password = (
            urllib.parse.unquote(parsed.password)
            if parsed.password is not None
            else None
        )
        if username is not None and password is None:
            raise ValueError("Redis URL username requires a password")

        raw = socket.create_connection((parsed.hostname, port), timeout=10)
        try:
            self._socket = (
                ssl.create_default_context().wrap_socket(
                    raw,
                    server_hostname=parsed.hostname,
                )
                if parsed.scheme == "rediss"
                else raw
            )
            self._reader = self._socket.makefile("rb")
            if password is not None:
                if username:
                    self.command("AUTH", username, password)
                else:
                    self.command("AUTH", password)
            if database != 0:
                self.command("SELECT", str(database))
        except BaseException:
            reader = getattr(self, "_reader", None)
            if reader is not None:
                reader.close()
            connection = getattr(self, "_socket", raw)
            connection.close()
            raise

    def __enter__(self) -> "_RedisConnection":
        return self

    def __exit__(self, *_: object) -> None:
        self._reader.close()
        self._socket.close()

    def command(self, *parts: str | bytes) -> object:
        encoded = [_redis_bytes(part) for part in parts]
        request = [f"*{len(encoded)}\r\n".encode("ascii")]
        for part in encoded:
            request.extend((
                f"${len(part)}\r\n".encode("ascii"),
                part,
                b"\r\n",
            ))
        self._socket.sendall(b"".join(request))
        return _read_redis_response(self._reader)


def _read_redis_response(reader: object) -> object:
    marker = reader.read(1)
    if not marker:
        raise RuntimeError("Redis closed the connection")
    line = reader.readline()
    if not line.endswith(b"\r\n"):
        raise RuntimeError("Redis returned a malformed response")
    value = line[:-2]
    if marker == b"+":
        return value.decode("utf-8")
    if marker == b"-":
        raise RuntimeError(
            "Redis command failed: " + value.decode("utf-8", errors="replace")
        )
    if marker == b":":
        return int(value)
    if marker == b"$":
        length = int(value)
        if length == -1:
            return None
        payload = reader.read(length)
        if len(payload) != length or reader.read(2) != b"\r\n":
            raise RuntimeError("Redis returned a truncated bulk response")
        return payload
    if marker == b"*":
        length = int(value)
        if length == -1:
            return None
        return [_read_redis_response(reader) for _ in range(length)]
    raise RuntimeError("Redis returned an unsupported response type")


def _redis_bytes(value: str | bytes) -> bytes:
    return value if isinstance(value, bytes) else value.encode("utf-8")


def _redis_text(value: object, owner: str) -> str:
    if not isinstance(value, bytes):
        raise RuntimeError(f"Redis {owner} is invalid")
    return value.decode("ascii")


def _write_runner_failure(
    evidence: Path,
    lane: str,
    proof_id: str,
    error: BaseException,
) -> None:
    evidence.mkdir(parents=True, exist_ok=True)
    canonical_lane = _lane_file_name(lane)
    path = evidence / f"worker-lab-{canonical_lane}-summary.json"
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
        "state": "state-convergence",
        "task-fault": "task-fault-convergence",
        "campaign": "convergence-campaign",
    }[lane]


def _reset_lane_directory(output_root: Path, lane_root: Path) -> None:
    if lane_root.parent != output_root or lane_root == output_root:
        raise RuntimeError("Lane output must be a direct child of output root")
    if lane_root.exists():
        shutil.rmtree(lane_root)
    lane_root.mkdir(parents=True)


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
