#!/usr/bin/env python3
"""One-shot Linux owner for the Java WebSocket Worker scale proof."""

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
import threading
import time
import urllib.error
import urllib.request
import uuid


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT))

from integrations.worker_proof_support.scenario_inventory import (  # noqa: E402
    materialize_inventory,
)


MODULE_ROOT = Path(__file__).resolve().parent
RUNTIME_API = "http://127.0.0.1:18082"
LAB_CONTROL = "http://127.0.0.1:18086"
MAXIMUM_NATIVE_THREADS = 512
MAXIMUM_OPEN_FILE_DESCRIPTORS = {
    "worker-host": 32_768,
    "runtime-server": 16_384,
}
WORKER_GROUP = "scenario-string-utils-workers"
ENDPOINT_MANAGER = "scenario-websocket"
TEST_SCOPE = re.compile(r"test_[a-z0-9_]+")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run the Java WebSocket 10k Worker scale proof."
    )
    parser.add_argument("--workers", type=int, default=10_000)
    parser.add_argument("--minimum-converged", type=int, default=9_900)
    parser.add_argument("--redis-url", required=True)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=(
            REPOSITORY_ROOT / "build/worker-websocket-scale-proof"
        ),
    )
    parser.add_argument(
        "--maximum-convergence-wait-millis",
        type=int,
        default=900_000,
    )
    parser.add_argument("--stable-hold-millis", type=int, default=60_000)
    parser.add_argument("--scan-interval-millis", type=int, default=10_000)
    parser.add_argument("--task-result-wait-millis", type=int, default=300_000)
    parser.add_argument("--request-timeout-millis", type=int, default=30_000)
    parser.add_argument(
        "--workload-items-per-task",
        type=int,
        default=500,
    )
    options = parser.parse_args()
    _validate_options(parser, options)
    if not sys.platform.startswith("linux"):
        parser.error("the scale proof requires Linux /proc resource evidence")

    _preflight(options.redis_url)
    _build_artifacts()
    output_root = options.output_root.resolve()
    _reset_output_root(output_root)
    evidence_root = output_root / "evidence"
    private_root = output_root / "private"
    sandbox = output_root / "data" / "scenario-workers"
    evidence_root.mkdir(parents=True)
    private_root.mkdir(parents=True)
    _generate_inventory(sandbox, options.workers)
    capability_assembly = private_root / "capability-assembly.json"
    _write_json(capability_assembly, _capability_assembly())

    scope = "test_worker_websocket_scale_" + uuid.uuid4().hex[:12]
    proof_id = "worker-websocket-scale-" + scope[-12:]
    environment = os.environ.copy()
    environment["XA_MASS_REDIS_URL"] = options.redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope

    processes: dict[str, subprocess.Popen[str]] = {}
    sampler = _ProcessSampler(output_root / "process-resources.jsonl")
    sampler.start()
    try:
        server = _start_server(output_root, environment, "runtime-server-1.log")
        processes["runtime-server"] = server
        sampler.register("runtime-server", server)
        _wait_http(
            f"{RUNTIME_API}/actuator/health/readiness",
            server,
            options.maximum_convergence_wait_millis,
            output_root / "runtime-server-1.log",
        )

        host = _start_host(
            output_root,
            sandbox,
            capability_assembly,
            environment,
        )
        processes["worker-host"] = host
        sampler.register("worker-host", host)
        _wait_http(
            f"{LAB_CONTROL}/lab/v1/workers",
            host,
            options.maximum_convergence_wait_millis,
            output_root / "scenario-worker-host.log",
        )

        baseline = private_root / "worker-ids.json"
        timeline = evidence_root / "worker-websocket-scale-timeline.jsonl"
        initial_summary = evidence_root / "initial-summary.json"
        reconnected_summary = evidence_root / "reconnected-summary.json"
        _run_phase(
            "initial",
            options,
            proof_id,
            baseline,
            initial_summary,
            timeline,
            environment,
        )

        _stop_process(server, force=False, timeout_seconds=60)
        processes.pop("runtime-server", None)
        sampler.unregister("runtime-server")
        _require_running(host, output_root / "scenario-worker-host.log")

        server = _start_server(output_root, environment, "runtime-server-2.log")
        processes["runtime-server"] = server
        sampler.register("runtime-server", server)
        _wait_http(
            f"{RUNTIME_API}/actuator/health/readiness",
            server,
            options.maximum_convergence_wait_millis,
            output_root / "runtime-server-2.log",
        )
        _run_phase(
            "reconnected",
            options,
            proof_id,
            baseline,
            reconnected_summary,
            timeline,
            environment,
        )

        sampler.sample_now()
        resources = sampler.summary()
        _validate_resource_contract(resources)
        worker_resources = resources["worker-host"]
        server_resources = resources["runtime-server"]
        initial = _read_json(initial_summary)
        reconnected = _read_json(reconnected_summary)
        if initial.get("status") != "passed" or reconnected.get("status") != "passed":
            raise RuntimeError("one or more Java scale phases did not pass")
        _validate_phase_summary(initial, options)
        _validate_phase_summary(reconnected, options)
        if initial.get("workerIdSetSha256") != reconnected.get(
            "workerIdSetSha256"
        ):
            raise RuntimeError("Worker identity digest changed across restart")

        _write_json(
            evidence_root / "worker-websocket-scale-summary.json",
            {
                "proofId": proof_id,
                "lane": "worker-websocket-scale",
                "status": "passed",
                "offeredWorkers": options.workers,
                "minimumConnectedAndHot": options.minimum_converged,
                "workerIdSetSha256": initial["workerIdSetSha256"],
                "workloadTasksPerPhase": 2,
                "workloadItemsPerTask": options.workload_items_per_task,
                "workloadItemsPerPhase": 2 * options.workload_items_per_task,
                "totalWorkloadItems": 4 * options.workload_items_per_task,
                "initialSucceededTaskItems": (
                    initial["taskASucceededCount"]
                    + initial["taskBSucceededCount"]
                ),
                "reconnectedSucceededTaskItems": (
                    reconnected["taskASucceededCount"]
                    + reconnected["taskBSucceededCount"]
                ),
                "maximumWorkerHostNativeThreads": worker_resources[
                    "maximumNativeThreads"
                ],
                "maximumRuntimeServerNativeThreads": server_resources[
                    "maximumNativeThreads"
                ],
                "maximumWorkerHostRssBytes": worker_resources[
                    "maximumRssBytes"
                ],
                "maximumRuntimeServerRssBytes": server_resources[
                    "maximumRssBytes"
                ],
                "maximumWorkerHostOpenFileDescriptors": worker_resources[
                    "maximumOpenFileDescriptors"
                ],
                "maximumRuntimeServerOpenFileDescriptors": server_resources[
                    "maximumOpenFileDescriptors"
                ],
                "serverRestartCount": 1,
                "completedAtEpochMillis": int(time.time() * 1_000),
            },
        )
        baseline.unlink(missing_ok=True)
        print(
            "Worker WebSocket scale proof passed "
            f"offeredWorkers={options.workers} "
            f"minimumConnectedAndHot={options.minimum_converged} "
            f"workloadItemsPerPhase={2 * options.workload_items_per_task} "
            "maximumWorkerHostNativeThreads="
            f"{worker_resources['maximumNativeThreads']}"
        )
        return 0
    except BaseException as error:
        _write_failure_summary(evidence_root, proof_id, options, error)
        raise
    finally:
        for process in tuple(processes.values())[::-1]:
            _stop_process(process, force=False, timeout_seconds=60)
        processes.clear()
        sampler.close()
        try:
            _cleanup_scope(options.redis_url, scope)
        except BaseException as cleanup_error:
            print(f"Redis cleanup could not run: {cleanup_error}", file=sys.stderr)


def _validate_options(parser: argparse.ArgumentParser, options: argparse.Namespace) -> None:
    if not 1 <= options.workers <= 10_000:
        parser.error("--workers must be in 1..10000")
    if not 1 <= options.minimum_converged <= options.workers:
        parser.error("--minimum-converged must be in 1..workers")
    if options.maximum_convergence_wait_millis < 10_000:
        parser.error("--maximum-convergence-wait-millis must be at least 10000")
    if options.stable_hold_millis < 0:
        parser.error("--stable-hold-millis must not be negative")
    if options.scan_interval_millis < 100:
        parser.error("--scan-interval-millis must be at least 100")
    if options.task_result_wait_millis < 1_000:
        parser.error("--task-result-wait-millis must be at least 1000")
    if options.request_timeout_millis < 1_000:
        parser.error("--request-timeout-millis must be at least 1000")
    if not 1 <= options.workload_items_per_task <= 500:
        parser.error("--workload-items-per-task must be in 1..500")


def _preflight(redis_url: str) -> None:
    import resource
    import redis

    soft_nofile, _ = resource.getrlimit(resource.RLIMIT_NOFILE)
    if soft_nofile != resource.RLIM_INFINITY and soft_nofile < 65_536:
        raise RuntimeError(f"nofile must be >=65536, observed {soft_nofile}")
    port_range = Path("/proc/sys/net/ipv4/ip_local_port_range").read_text(
        encoding="ascii"
    ).split()
    if len(port_range) != 2:
        raise RuntimeError("Linux local port range is unreadable")
    low, high = (int(value) for value in port_range)
    if high - low + 1 < 20_000:
        raise RuntimeError(
            "Linux local ephemeral port range must contain at least 20000 ports"
        )
    version = subprocess.run(
        ["java", "-version"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=True,
    ).stdout
    if re.search(r'version "21(?:[.\"]|$)', version) is None:
        raise RuntimeError("Java 21 is required")
    with redis.Redis.from_url(redis_url) as client:
        if client.ping() is not True:
            raise RuntimeError("Redis did not answer PONG")


def _build_artifacts() -> None:
    _run(
        [
            str(_gradle_wrapper()),
            "--no-daemon",
            ":server_jvm:bootJar",
            ":scenario_workers_jvm:installDist",
            ":integrations:worker-websocket-scale:installDist",
        ],
        cwd=REPOSITORY_ROOT,
    )


def _generate_inventory(sandbox: Path, workers: int) -> None:
    materialize_inventory(
        sandbox,
        {
            WORKER_GROUP: tuple(
                {
                    "runtime": "java",
                    "capability": "string-utils",
                    "region": "scale-ci",
                    "scaleIndex": worker_index,
                }
                for worker_index in range(1, workers + 1)
            )
        },
    )


def _capability_assembly() -> dict[str, object]:
    return {
        WORKER_GROUP: {
            "eventCodes": ["extension.worker.string.md5"],
            "requestTimeoutMillis": 60_000,
            "reconnectPolicy": {
                "maxUnstableAttempts": 600,
                "reconnectIntervalMillis": 500,
                "stableConnectionDurationMillis": 10_000,
            },
        }
    }


def _start_server(
    output_root: Path,
    environment: dict[str, str],
    log_name: str,
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
    config = (
        MODULE_ROOT
        / "server-config/application-worker-websocket-scale.yaml"
    ).resolve()
    return _start_process(
        [
            "java",
            "-Xmx3g",
            "-XX:+ExitOnOutOfMemoryError",
            "-jar",
            str(boot_jar),
            "--spring.profiles.active=scenario-workers",
            f"--spring.config.additional-location={config.as_uri()}",
        ],
        output_root / log_name,
        environment,
    )


def _start_host(
    output_root: Path,
    sandbox: Path,
    capability_assembly: Path,
    environment: dict[str, str],
) -> subprocess.Popen[str]:
    classpath = (
        REPOSITORY_ROOT
        / "scenario_workers_jvm/build/install/xa-mass-scenario-workers/lib/*"
    )
    return _start_process(
        [
            "java",
            "-Xmx3g",
            "-XX:+ExitOnOutOfMemoryError",
            "-cp",
            str(classpath),
            "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain",
            f"--runtime-api-base-url={RUNTIME_API}",
            f"--sandbox-root={sandbox}",
            "--control-port=18086",
            f"--capability-assembly={capability_assembly}",
        ],
        output_root / "scenario-worker-host.log",
        environment,
    )


def _run_phase(
    phase: str,
    options: argparse.Namespace,
    proof_id: str,
    baseline: Path,
    summary: Path,
    timeline: Path,
    environment: dict[str, str],
) -> None:
    executable = (
        REPOSITORY_ROOT
        / "integrations/worker-websocket-scale/build/install/"
        "xa-mass-worker-websocket-scale/bin/xa-mass-worker-websocket-scale"
    )
    if os.name == "nt":
        executable = executable.with_suffix(".bat")
    command = [
        str(executable),
        f"--phase={phase}",
        f"--proof-id={proof_id}",
        f"--server-base-url={RUNTIME_API}",
        f"--lab-base-url={LAB_CONTROL}",
        f"--worker-group-id={WORKER_GROUP}",
        f"--endpoint-manager-id={ENDPOINT_MANAGER}",
        f"--offered-workers={options.workers}",
        f"--minimum-converged={options.minimum_converged}",
        "--workload-items-per-task=" + str(options.workload_items_per_task),
        "--stable-hold-millis="
        + str(options.stable_hold_millis if phase == "initial" else 0),
        f"--scan-interval-millis={options.scan_interval_millis}",
        "--maximum-convergence-wait-millis="
        + str(options.maximum_convergence_wait_millis),
        f"--task-result-wait-millis={options.task_result_wait_millis}",
        f"--request-timeout-millis={options.request_timeout_millis}",
        f"--baseline-file={baseline}",
        f"--summary-file={summary}",
        f"--timeline-file={timeline}",
    ]
    _run(command, cwd=REPOSITORY_ROOT, env=environment)


class _ProcessSampler:

    def __init__(
        self,
        path: Path,
    ) -> None:
        self._path = path
        self._processes: dict[str, subprocess.Popen[str]] = {}
        self._lock = threading.Lock()
        self._closed = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name="worker-websocket-scale-sampler",
            daemon=True,
        )
        self._summary: dict[str, dict[str, int]] = {}

    def start(self) -> None:
        self._thread.start()

    def register(self, owner: str, process: subprocess.Popen[str]) -> None:
        with self._lock:
            self._processes[owner] = process

    def unregister(self, owner: str) -> None:
        with self._lock:
            self._processes.pop(owner, None)

    def _run(self) -> None:
        while not self._closed.wait(5):
            self.sample_now()

    def sample_now(self) -> None:
        with self._lock:
            processes = tuple(self._processes.items())
        for owner, process in processes:
            if process.poll() is not None:
                continue
            try:
                sample = _process_sample(process.pid)
            except (FileNotFoundError, ProcessLookupError):
                continue
            value = {
                "atEpochMillis": int(time.time() * 1_000),
                "process": owner,
                "pid": process.pid,
                **sample,
            }
            with self._lock:
                current = self._summary.setdefault(owner, {})
                current["sampleCount"] = current.get("sampleCount", 0) + 1
                for field, target in (
                    ("rssBytes", "maximumRssBytes"),
                    ("nativeThreads", "maximumNativeThreads"),
                    ("openFileDescriptors", "maximumOpenFileDescriptors"),
                ):
                    current[target] = max(current.get(target, 0), sample[field])
                self._path.parent.mkdir(parents=True, exist_ok=True)
                with self._path.open("a", encoding="utf-8", newline="\n") as out:
                    out.write(json.dumps(value, separators=(",", ":"), sort_keys=True))
                    out.write("\n")

    def summary(self) -> dict[str, dict[str, int]]:
        with self._lock:
            return {owner: dict(value) for owner, value in self._summary.items()}

    def close(self) -> None:
        self._closed.set()
        self._thread.join(timeout=10)
        self.sample_now()


def _process_sample(pid: int) -> dict[str, int]:
    status = (Path("/proc") / str(pid) / "status").read_text(encoding="ascii")
    fields: dict[str, str] = {}
    for line in status.splitlines():
        if ":" in line:
            name, value = line.split(":", 1)
            fields[name] = value.strip()
    rss_parts = fields.get("VmRSS", "0 kB").split()
    if len(rss_parts) != 2 or rss_parts[1] != "kB":
        raise RuntimeError(f"process {pid} VmRSS is invalid")
    threads = int(fields.get("Threads", "0"))
    return {
        "rssBytes": int(rss_parts[0]) * 1024,
        "nativeThreads": threads,
        "openFileDescriptors": len(list((Path("/proc") / str(pid) / "fd").iterdir())),
    }


def _validate_resource_contract(resources: dict[str, dict[str, int]]) -> None:
    for owner, label in (
        ("worker-host", "Worker Host"),
        ("runtime-server", "Runtime Server"),
    ):
        observed = resources.get(owner, {})
        native_threads = observed.get("maximumNativeThreads", 0)
        open_files = observed.get("maximumOpenFileDescriptors", 0)
        if native_threads <= 0:
            raise RuntimeError(f"{label} native-thread evidence is missing")
        if open_files <= 0:
            raise RuntimeError(f"{label} open-file evidence is missing")
        if native_threads >= MAXIMUM_NATIVE_THREADS:
            raise RuntimeError(
                f"{label} native threads exceeded the "
                f"<{MAXIMUM_NATIVE_THREADS} contract: {native_threads}"
            )
        maximum_open_files = MAXIMUM_OPEN_FILE_DESCRIPTORS[owner]
        if open_files >= maximum_open_files:
            raise RuntimeError(
                f"{label} open files exceeded the "
                f"<{maximum_open_files} contract: {open_files}"
            )


def _validate_phase_summary(
    summary: dict[str, object],
    options: argparse.Namespace,
) -> None:
    items_per_task = options.workload_items_per_task
    expected_batches = 2 * ((items_per_task + 99) // 100)
    expected = {
        "activeTaskCount": 2,
        "offeredItemsPerTask": items_per_task,
        "totalOfferedItems": 2 * items_per_task,
        "appendBatchCount": expected_batches,
        "taskASucceededCount": items_per_task,
        "taskBSucceededCount": items_per_task,
    }
    for field, value in expected.items():
        if summary.get(field) != value:
            raise RuntimeError(
                f"scale phase summary {field} must be {value}, "
                f"observed {summary.get(field)!r}"
            )
    if summary.get("minimumConnectedDuringWork", 0) < options.minimum_converged:
        raise RuntimeError("scale phase lost the minimum workload connections")
    if summary.get("postWorkConnectedAndHot", 0) < options.minimum_converged:
        raise RuntimeError("scale phase did not reconverge after workload drain")


def _start_process(
    command: list[str],
    log_path: Path,
    environment: dict[str, str],
) -> subprocess.Popen[str]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = log_path.open("w", encoding="utf-8", newline="\n")
    try:
        process = subprocess.Popen(
            command,
            cwd=REPOSITORY_ROOT,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
    except BaseException:
        log.close()
        raise
    log.close()
    return process


def _stop_process(
    process: subprocess.Popen[str],
    *,
    force: bool,
    timeout_seconds: int,
) -> None:
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGKILL if force else signal.SIGTERM)
    try:
        process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
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
        _require_running(process, log_path)
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if 200 <= response.status < 300:
                    return
        except (OSError, urllib.error.URLError) as error:
            latest = error
        time.sleep(0.2)
    raise RuntimeError(
        f"timed out waiting for {url}: {latest}; log:\n{_tail(log_path)}"
    )


def _require_running(process: subprocess.Popen[str], log_path: Path) -> None:
    if process.poll() is not None:
        raise RuntimeError(
            f"process exited with {process.returncode}; log:\n{_tail(log_path)}"
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
            "--best-effort",
        ],
        cwd=REPOSITORY_ROOT,
    )


def _reset_output_root(output_root: Path) -> None:
    if output_root in {Path(output_root.anchor), REPOSITORY_ROOT}:
        raise RuntimeError("refusing to replace an unsafe output root")
    if output_root.exists():
        shutil.rmtree(output_root)
    output_root.mkdir(parents=True)


def _write_failure_summary(
    evidence_root: Path,
    proof_id: str,
    options: argparse.Namespace,
    error: BaseException,
) -> None:
    evidence_root.mkdir(parents=True, exist_ok=True)
    path = evidence_root / "worker-websocket-scale-summary.json"
    _write_json(
        path,
        {
            "proofId": proof_id,
            "lane": "worker-websocket-scale",
            "status": "failed",
            "failureKind": "proof-not-established",
            "failure": (str(error) or type(error).__name__)[:500],
            "offeredWorkers": options.workers,
            "minimumConnectedAndHot": options.minimum_converged,
            "workloadItemsPerTask": options.workload_items_per_task,
            "completedAtEpochMillis": int(time.time() * 1_000),
        },
    )


def _write_json(path: Path, value: object) -> None:
    _write_text(path, json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n")


def _write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("x", encoding="utf-8", newline="\n") as output:
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _read_json(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"JSON object required: {path}")
    return value


def _tail(path: Path, maximum_lines: int = 80) -> str:
    if not path.exists():
        return "<missing>"
    return "\n".join(
        path.read_text(encoding="utf-8", errors="replace").splitlines()[
            -maximum_lines:
        ]
    )


def _gradle_wrapper() -> Path:
    return REPOSITORY_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")


def _run(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
) -> None:
    subprocess.run(command, cwd=cwd, env=env, check=True)


if __name__ == "__main__":
    raise SystemExit(main())
