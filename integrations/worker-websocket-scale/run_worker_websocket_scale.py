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
    "runtime-server-initial": 16_384,
    "runtime-server-graceful": 16_384,
    "runtime-server-hard-1": 16_384,
    "runtime-server-hard-2": 16_384,
}
STABLE_HOST_NATIVE_THREADS = 128
STABLE_SERVER_NATIVE_THREADS = 256
INITIAL_STABLE_OPEN_FILES = 15_512
RETAINED_STABLE_OPEN_FILES = 10_512
MAXIMUM_FINAL_HOST_FD_GROWTH = 128
MAXIMUM_FINAL_HOST_THREAD_GROWTH = 32
HARD_KILL_SIGNAL = getattr(signal, "SIGKILL", 9)
TASKS_PER_STAGE = 10
STAGES = (
    "initial-contraction",
    "graceful-restart",
    "hard-restart-1",
    "hard-restart-2",
)
RESTART_STAGES = (
    ("graceful-restart", "runtime-server-graceful", signal.SIGTERM),
    ("hard-restart-1", "runtime-server-hard-1", HARD_KILL_SIGNAL),
    ("hard-restart-2", "runtime-server-hard-2", HARD_KILL_SIGNAL),
)
WORKER_GROUP = "scenario-string-utils-workers"
ENDPOINT_MANAGER = "scenario-websocket"
TEST_SCOPE = re.compile(r"test_[a-z0-9_]+")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run the Java WebSocket 15k/10k loaded recovery proof."
    )
    parser.add_argument("--prepared-workers", type=int, default=15_000)
    parser.add_argument("--retained-workers", type=int, default=10_000)
    parser.add_argument("--minimum-initial-converged", type=int, default=14_800)
    parser.add_argument("--minimum-retained-converged", type=int, default=9_900)
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
    parser.add_argument("--task-result-wait-millis", type=int, default=900_000)
    parser.add_argument("--request-timeout-millis", type=int, default=30_000)
    parser.add_argument(
        "--workload-items-per-task",
        type=int,
        default=5_000,
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
    coordinates = _generate_inventory(sandbox, options.prepared_workers)
    topology = private_root / "worker-topology.json"
    _write_json(topology, _partition_topology(
        coordinates,
        options.retained_workers,
    ))
    capability_assembly = private_root / "capability-assembly.json"
    _write_json(capability_assembly, _capability_assembly())

    scope = "test_worker_websocket_scale_" + uuid.uuid4().hex[:12]
    proof_id = "worker-websocket-scale-" + scope[-12:]
    environment = os.environ.copy()
    environment["XA_MASS_REDIS_URL"] = options.redis_url
    environment["XA_MASS_REDIS_SCOPE"] = scope

    processes: dict[str, subprocess.Popen[str]] = {}
    stage_process: subprocess.Popen[str] | None = None
    sampler = _ProcessSampler(output_root / "process-resources.jsonl")
    sampler.start()
    try:
        server = _start_server(output_root, environment, "runtime-server-1.log")
        processes["runtime-server-initial"] = server
        sampler.register("runtime-server-initial", server)
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
        gates_root = private_root / "gates"
        stage_summaries: dict[str, dict[str, object]] = {}
        resource_checkpoints: dict[str, dict[str, object]] = {}
        restart_durations: dict[str, int] = {}

        initial_stage = "initial-contraction"
        initial_summary_path = evidence_root / f"{initial_stage}-summary.json"
        stage_process, stage_log = _start_stage(
            initial_stage,
            options,
            proof_id,
            topology,
            baseline,
            gates_root / initial_stage,
            initial_summary_path,
            timeline,
            environment,
            output_root,
        )
        headroom_ready = _wait_gate(
            gates_root / initial_stage / "headroom-ready.json",
            stage_process,
            stage_log,
            options.maximum_convergence_wait_millis,
        )
        _validate_gate(
            headroom_ready,
            proof_id,
            initial_stage,
            "initial-headroom",
        )
        resource_checkpoints["initial-headroom"] = sampler.checkpoint(
            "initial-headroom",
            ("worker-host", "runtime-server-initial"),
        )
        _write_gate_resume(
            gates_root / initial_stage / "headroom-resume.json",
            proof_id,
            initial_stage,
            "initial-headroom",
        )
        _wait_stage(
            stage_process,
            stage_log,
            options,
        )
        stage_process = None
        initial_summary = _read_json(initial_summary_path)
        _validate_stage_summary(initial_summary, options, initial_stage)
        stage_summaries[initial_stage] = initial_summary
        resource_checkpoints["retained-after-initial"] = sampler.checkpoint(
            "retained-after-initial",
            ("worker-host", "runtime-server-initial"),
        )

        current_server = server
        current_server_owner = "runtime-server-initial"
        current_server_log = output_root / "runtime-server-1.log"
        for restart_ordinal, (stage, server_owner, server_signal) in enumerate(
            RESTART_STAGES,
            start=2,
        ):
            summary_path = evidence_root / f"{stage}-summary.json"
            stage_process, stage_log = _start_stage(
                stage,
                options,
                proof_id,
                topology,
                baseline,
                gates_root / stage,
                summary_path,
                timeline,
                environment,
                output_root,
            )
            mutation_ready = _wait_gate(
                gates_root / stage / "mutation-ready.json",
                stage_process,
                stage_log,
                options.maximum_convergence_wait_millis,
            )
            _validate_gate(
                mutation_ready,
                proof_id,
                stage,
                "server-mutation",
            )
            _validate_mutation_ready(mutation_ready, options)
            _require_running(host, output_root / "scenario-worker-host.log")

            signal_started = time.monotonic()
            _terminate_server_for_stage(
                current_server,
                server_signal,
                mutation_ready,
            )
            processes.pop(current_server_owner, None)
            sampler.unregister(current_server_owner)
            _require_running(host, output_root / "scenario-worker-host.log")

            current_server_log = output_root / f"runtime-server-{restart_ordinal}.log"
            current_server = _start_server(
                output_root,
                environment,
                current_server_log.name,
            )
            current_server_owner = server_owner
            processes[current_server_owner] = current_server
            sampler.register(current_server_owner, current_server)
            _wait_http(
                f"{RUNTIME_API}/actuator/health/readiness",
                current_server,
                options.maximum_convergence_wait_millis,
                current_server_log,
            )
            restart_durations[stage] = round(
                (time.monotonic() - signal_started) * 1_000
            )
            _append_jsonl(timeline, {
                "atEpochMillis": int(time.time() * 1_000),
                "stage": stage,
                "event": "runtime-server-restarted",
                "signal": "SIGTERM" if server_signal == signal.SIGTERM else "SIGKILL",
                "restartDurationMillis": restart_durations[stage],
            })
            _write_gate_resume(
                gates_root / stage / "resume.json",
                proof_id,
                stage,
                "server-mutation",
            )
            _wait_stage(stage_process, stage_log, options)
            stage_process = None

            stage_summary = _read_json(summary_path)
            _validate_stage_summary(stage_summary, options, stage)
            stage_summaries[stage] = stage_summary
            resource_checkpoints[f"retained-after-{stage}"] = sampler.checkpoint(
                f"retained-after-{stage}",
                ("worker-host", current_server_owner),
            )

        sampler.sample_now()
        resources = sampler.summary()
        _validate_resource_contract(resources, resource_checkpoints)
        worker_resources = resources["worker-host"]
        _validate_identity_digests(stage_summaries)
        first_summary = stage_summaries[initial_stage]

        _write_json(
            evidence_root / "worker-websocket-scale-summary.json",
            {
                "proofId": proof_id,
                "lane": "worker-websocket-scale",
                "status": "passed",
                "preparedWorkers": options.prepared_workers,
                "retainedWorkers": options.retained_workers,
                "stoppedWorkers": (
                    options.prepared_workers - options.retained_workers
                ),
                "minimumInitialConnectedAndHot": (
                    options.minimum_initial_converged
                ),
                "minimumRetainedConnectedAndHot": (
                    options.minimum_retained_converged
                ),
                "allWorkerIdSetSha256": first_summary["allWorkerIdSetSha256"],
                "retainedWorkerIdSetSha256": (
                    first_summary["retainedWorkerIdSetSha256"]
                ),
                "stoppedWorkerIdSetSha256": (
                    first_summary["stoppedWorkerIdSetSha256"]
                ),
                "workloadStages": 4,
                "serverRestartCount": 3,
                "gracefulRestartCount": 1,
                "hardRestartCount": 2,
                "workloadTasksPerStage": TASKS_PER_STAGE,
                "workloadItemsPerTask": options.workload_items_per_task,
                "workloadItemsPerStage": (
                    TASKS_PER_STAGE * options.workload_items_per_task
                ),
                "totalWorkloadItems": (
                    4 * TASKS_PER_STAGE * options.workload_items_per_task
                ),
                "taskCount": 4 * TASKS_PER_STAGE,
                "succeededItems": sum(
                    int(summary["succeededItemCount"])
                    for summary in stage_summaries.values()
                ),
                "mutationCheckpointCounts": {
                    stage: {
                        "succeededItems": summary[
                            "mutationCheckpointSucceededItems"
                        ],
                        "unresolvedItems": summary[
                            "mutationCheckpointUnresolvedItems"
                        ],
                    }
                    for stage, summary in stage_summaries.items()
                },
                "restartAndRecoveryDurations": {
                    stage: {
                        "serverRestartMillis": restart_durations[stage],
                        "workloadRecoveryMillis": stage_summaries[stage][
                            "restartAndRecoveryMillis"
                        ],
                    }
                    for stage, _, _ in RESTART_STAGES
                },
                "resourceCheckpoints": resource_checkpoints,
                "maximumWorkerHostNativeThreads": worker_resources[
                    "maximumNativeThreads"
                ],
                "runtimeServers": {
                    owner: value
                    for owner, value in resources.items()
                    if owner.startswith("runtime-server-")
                },
                "maximumWorkerHostRssBytes": worker_resources[
                    "maximumRssBytes"
                ],
                "maximumWorkerHostOpenFileDescriptors": worker_resources[
                    "maximumOpenFileDescriptors"
                ],
                "averageWorkerHostCpuCores": worker_resources[
                    "averageCpuCores"
                ],
                "maximumWorkerHostCpuCores": worker_resources[
                    "maximumCpuCores"
                ],
                "workerHostPid": host.pid,
                "completedAtEpochMillis": int(time.time() * 1_000),
            },
        )
        baseline.unlink(missing_ok=True)
        print(
            "Worker WebSocket scale proof passed "
            f"preparedWorkers={options.prepared_workers} "
            f"retainedWorkers={options.retained_workers} "
            "workloadStages=4 totalWorkloadItems="
            f"{4 * TASKS_PER_STAGE * options.workload_items_per_task} "
            "maximumWorkerHostNativeThreads="
            f"{worker_resources['maximumNativeThreads']}"
        )
        return 0
    except BaseException as error:
        _write_failure_summary(evidence_root, proof_id, options, error)
        raise
    finally:
        if stage_process is not None:
            _stop_process(stage_process, force=True, timeout_seconds=15)
        for process in tuple(processes.values())[::-1]:
            _stop_process(process, force=True, timeout_seconds=15)
        processes.clear()
        sampler.close()
        shutil.rmtree(private_root / "gates", ignore_errors=True)
        baseline = private_root / "worker-ids.json"
        baseline.unlink(missing_ok=True)
        try:
            _cleanup_scope(options.redis_url, scope)
        except BaseException as cleanup_error:
            print(f"Redis cleanup could not run: {cleanup_error}", file=sys.stderr)


def _validate_options(parser: argparse.ArgumentParser, options: argparse.Namespace) -> None:
    if not 1 <= options.prepared_workers <= 15_000:
        parser.error("--prepared-workers must be in 1..15000")
    if not 1 <= options.retained_workers < options.prepared_workers:
        parser.error(
            "--retained-workers must be in 1..prepared-workers-1"
        )
    if not 1 <= options.minimum_initial_converged <= options.prepared_workers:
        parser.error(
            "--minimum-initial-converged must be in 1..prepared-workers"
        )
    if not 1 <= options.minimum_retained_converged <= options.retained_workers:
        parser.error(
            "--minimum-retained-converged must be in 1..retained-workers"
        )
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
    if not 1 <= options.workload_items_per_task <= 5_000:
        parser.error("--workload-items-per-task must be in 1..5000")


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
    if high - low + 1 < 45_000:
        raise RuntimeError(
            "Linux local ephemeral port range must contain at least 45000 ports"
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


def _generate_inventory(sandbox: Path, workers: int) -> tuple[str, ...]:
    coordinates = materialize_inventory(
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
    return coordinates[WORKER_GROUP]


def _partition_topology(
    coordinates: tuple[str, ...],
    retained_workers: int,
) -> dict[str, object]:
    return {
        "workerGroupId": WORKER_GROUP,
        "retainedLabWorkerKeys": list(coordinates[:retained_workers]),
        "stoppedLabWorkerKeys": list(coordinates[retained_workers:]),
    }


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


def _start_stage(
    stage: str,
    options: argparse.Namespace,
    proof_id: str,
    topology: Path,
    baseline: Path,
    gate_directory: Path,
    summary: Path,
    timeline: Path,
    environment: dict[str, str],
    output_root: Path,
) -> tuple[subprocess.Popen[str], Path]:
    executable = (
        REPOSITORY_ROOT
        / "integrations/worker-websocket-scale/build/install/"
        "xa-mass-worker-websocket-scale/bin/xa-mass-worker-websocket-scale"
    )
    if os.name == "nt":
        executable = executable.with_suffix(".bat")
    command = [
        str(executable),
        f"--stage={stage}",
        f"--proof-id={proof_id}",
        f"--server-base-url={RUNTIME_API}",
        f"--lab-base-url={LAB_CONTROL}",
        f"--worker-group-id={WORKER_GROUP}",
        f"--endpoint-manager-id={ENDPOINT_MANAGER}",
        f"--prepared-workers={options.prepared_workers}",
        f"--retained-workers={options.retained_workers}",
        "--minimum-initial-converged="
        + str(options.minimum_initial_converged),
        "--minimum-retained-converged="
        + str(options.minimum_retained_converged),
        "--workload-items-per-task=" + str(options.workload_items_per_task),
        "--stable-hold-millis="
        + str(
            options.stable_hold_millis
            if stage == "initial-contraction"
            else 0
        ),
        f"--scan-interval-millis={options.scan_interval_millis}",
        "--maximum-convergence-wait-millis="
        + str(options.maximum_convergence_wait_millis),
        f"--task-result-wait-millis={options.task_result_wait_millis}",
        f"--request-timeout-millis={options.request_timeout_millis}",
        f"--topology-file={topology}",
        f"--baseline-file={baseline}",
        f"--gate-directory={gate_directory}",
        f"--summary-file={summary}",
        f"--timeline-file={timeline}",
    ]
    log_path = output_root / f"scale-harness-{stage}.log"
    return _start_process(command, log_path, environment), log_path


def _wait_stage(
    process: subprocess.Popen[str],
    log_path: Path,
    options: argparse.Namespace,
) -> None:
    timeout_seconds = (
        options.maximum_convergence_wait_millis
        + options.task_result_wait_millis
        + options.stable_hold_millis
        + 120_000
    ) / 1_000
    try:
        result = process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(
            f"scale Java stage timed out; log:\n{_tail(log_path)}"
        ) from error
    if result != 0:
        raise RuntimeError(
            f"scale Java stage exited with {result}; log:\n{_tail(log_path)}"
        )


def _wait_gate(
    path: Path,
    process: subprocess.Popen[str],
    log_path: Path,
    maximum_wait_millis: int,
) -> dict[str, object]:
    deadline = time.monotonic() + maximum_wait_millis / 1_000
    while time.monotonic() < deadline:
        _require_running(process, log_path)
        if path.exists():
            return _read_json(path)
        time.sleep(0.05)
    raise RuntimeError(f"timed out waiting for scale gate {path.name}")


def _validate_gate(
    value: dict[str, object],
    proof_id: str,
    stage: str,
    checkpoint: str,
) -> None:
    expected_fields = {
        "proofId",
        "stage",
        "checkpoint",
        "atEpochMillis",
    }
    if checkpoint == "initial-headroom":
        expected_fields.update({
            "connectedAndHotWorkers",
            "qualifyingScans",
            "stableMillis",
        })
    else:
        expected_fields.update({
            "taskCount",
            "succeededItems",
            "unresolvedItems",
        })
    if set(value) != expected_fields:
        raise RuntimeError("scale process gate fields changed")
    if (
        value.get("proofId") != proof_id
        or value.get("stage") != stage
        or value.get("checkpoint") != checkpoint
    ):
        raise RuntimeError("scale process gate identity changed")
    if not isinstance(value.get("atEpochMillis"), int):
        raise RuntimeError("scale process gate timestamp is invalid")


def _validate_mutation_ready(
    value: dict[str, object],
    options: argparse.Namespace,
) -> None:
    expected_items = TASKS_PER_STAGE * options.workload_items_per_task
    succeeded = value.get("succeededItems")
    unresolved = value.get("unresolvedItems")
    if value.get("taskCount") != TASKS_PER_STAGE:
        raise RuntimeError("mutation gate must retain all ten Tasks")
    if not isinstance(succeeded, int) or not 1 <= succeeded <= expected_items // 2:
        raise RuntimeError("mutation gate succeeded count is outside its window")
    if (
        not isinstance(unresolved, int)
        or unresolved != expected_items - succeeded
        or unresolved < expected_items - expected_items // 2
    ):
        raise RuntimeError("mutation gate backlog is outside its window")


def _write_gate_resume(
    path: Path,
    proof_id: str,
    stage: str,
    checkpoint: str,
) -> None:
    _write_json(path, {
        "proofId": proof_id,
        "stage": stage,
        "checkpoint": checkpoint,
        "resumedAtEpochMillis": int(time.time() * 1_000),
    })


def _terminate_server_for_stage(
    process: subprocess.Popen[str],
    server_signal: signal.Signals,
    mutation_ready: dict[str, object],
) -> None:
    ready_at = mutation_ready["atEpochMillis"]
    assert isinstance(ready_at, int)
    signal_delay_millis = int(time.time() * 1_000) - ready_at
    if signal_delay_millis < 0 or signal_delay_millis > 2_000:
        raise RuntimeError(
            "Server signal did not occur within two seconds of mutation readiness"
        )
    if process.poll() is not None:
        raise RuntimeError("Runtime Server exited before the planned mutation")
    os.killpg(process.pid, server_signal)
    timeout_seconds = 60 if server_signal == signal.SIGTERM else 15
    try:
        process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired as error:
        signal_name = "SIGTERM" if server_signal == signal.SIGTERM else "SIGKILL"
        raise RuntimeError(
            f"Runtime Server did not exit after {signal_name}"
        ) from error


def _append_jsonl(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(value, separators=(",", ":"), sort_keys=True))
        output.write("\n")


class _ProcessSampler:

    def __init__(
        self,
        path: Path,
    ) -> None:
        self._path = path
        self._processes: dict[str, subprocess.Popen[str]] = {}
        self._lock = threading.Lock()
        self._sample_lock = threading.Lock()
        self._closed = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name="worker-websocket-scale-sampler",
            daemon=True,
        )
        self._summary: dict[str, dict[str, float | int]] = {}
        self._previous_cpu: dict[str, tuple[int, float, int]] = {}

    def start(self) -> None:
        self._thread.start()

    def register(self, owner: str, process: subprocess.Popen[str]) -> None:
        with self._lock:
            self._processes[owner] = process
            self._previous_cpu.pop(owner, None)

    def unregister(self, owner: str) -> None:
        with self._lock:
            self._processes.pop(owner, None)
            self._previous_cpu.pop(owner, None)

    def _run(self) -> None:
        while not self._closed.wait(5):
            self.sample_now()

    def sample_now(
        self,
        checkpoint: str | None = None,
    ) -> dict[str, dict[str, int]]:
        observed: dict[str, dict[str, int]] = {}
        with self._sample_lock:
            with self._lock:
                processes = tuple(self._processes.items())
            for owner, process in processes:
                if process.poll() is not None:
                    continue
                try:
                    sample = _process_sample(process.pid)
                except (FileNotFoundError, ProcessLookupError):
                    continue
                sampled_at = time.monotonic()
                value = {
                    "atEpochMillis": int(time.time() * 1_000),
                    "process": owner,
                    "pid": process.pid,
                    **sample,
                }
                if checkpoint is not None:
                    value["checkpoint"] = checkpoint
                with self._lock:
                    current = self._summary.setdefault(owner, {})
                    current["sampleCount"] = current.get("sampleCount", 0) + 1
                    for field, target in (
                        ("rssBytes", "maximumRssBytes"),
                        ("nativeThreads", "maximumNativeThreads"),
                        ("openFileDescriptors", "maximumOpenFileDescriptors"),
                    ):
                        current[target] = max(current.get(target, 0), sample[field])
                    previous = self._previous_cpu.get(owner)
                    if previous is not None and previous[0] == process.pid:
                        elapsed_seconds = sampled_at - previous[1]
                        cpu_delta_millis = sample["cpuTimeMillis"] - previous[2]
                        cpu_cores = _interval_cpu_cores(
                            elapsed_seconds,
                            cpu_delta_millis,
                        )
                        if cpu_cores is not None:
                            value["intervalAverageCpuCores"] = cpu_cores
                            current["maximumCpuCores"] = max(
                                current.get("maximumCpuCores", 0.0),
                                cpu_cores,
                            )
                            current["cpuCoreSeconds"] = (
                                current.get("cpuCoreSeconds", 0.0)
                                + cpu_cores * elapsed_seconds
                            )
                            current["cpuObservedSeconds"] = (
                                current.get("cpuObservedSeconds", 0.0)
                                + elapsed_seconds
                            )
                    self._previous_cpu[owner] = (
                        process.pid,
                        sampled_at,
                        sample["cpuTimeMillis"],
                    )
                    self._path.parent.mkdir(parents=True, exist_ok=True)
                    with self._path.open("a", encoding="utf-8", newline="\n") as out:
                        out.write(json.dumps(value, separators=(",", ":"), sort_keys=True))
                        out.write("\n")
                observed[owner] = dict(sample)
        return observed

    def checkpoint(
        self,
        name: str,
        owners: tuple[str, ...],
    ) -> dict[str, object]:
        samples: dict[str, list[dict[str, int]]] = {
            owner: [] for owner in owners
        }
        for ordinal in range(3):
            observed = self.sample_now(name)
            for owner in owners:
                if owner not in observed:
                    raise RuntimeError(
                        f"resource checkpoint {name} is missing {owner}"
                    )
                samples[owner].append(observed[owner])
            if ordinal < 2:
                time.sleep(2)
        return {
            "sampleCount": 3,
            "samplesByProcess": samples,
        }

    def summary(self) -> dict[str, dict[str, float | int]]:
        with self._lock:
            result: dict[str, dict[str, float | int]] = {}
            for owner, value in self._summary.items():
                observed_seconds = value.get("cpuObservedSeconds", 0.0)
                public = {
                    field: observed
                    for field, observed in value.items()
                    if field not in {"cpuCoreSeconds", "cpuObservedSeconds"}
                }
                public["averageCpuCores"] = (
                    value.get("cpuCoreSeconds", 0.0) / observed_seconds
                    if observed_seconds > 0
                    else 0.0
                )
                public.setdefault("maximumCpuCores", 0.0)
                result[owner] = public
            return result

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
    stat = (Path("/proc") / str(pid) / "stat").read_text(encoding="ascii")
    closing_parenthesis = stat.rfind(")")
    if closing_parenthesis < 0:
        raise RuntimeError(f"process {pid} stat is invalid")
    stat_fields = stat[closing_parenthesis + 2 :].split()
    if len(stat_fields) <= 12:
        raise RuntimeError(f"process {pid} stat is incomplete")
    clock_ticks = os.sysconf("SC_CLK_TCK")
    cpu_ticks = int(stat_fields[11]) + int(stat_fields[12])
    return {
        "rssBytes": int(rss_parts[0]) * 1024,
        "nativeThreads": threads,
        "openFileDescriptors": len(list((Path("/proc") / str(pid) / "fd").iterdir())),
        "cpuTimeMillis": cpu_ticks * 1_000 // clock_ticks,
    }


def _interval_cpu_cores(
    elapsed_seconds: float,
    cpu_delta_millis: int,
) -> float | None:
    if elapsed_seconds <= 0 or cpu_delta_millis < 0:
        return None
    return cpu_delta_millis / (elapsed_seconds * 1_000)


def _validate_resource_contract(
    resources: dict[str, dict[str, float | int]],
    checkpoints: dict[str, dict[str, object]],
) -> None:
    for owner in MAXIMUM_OPEN_FILE_DESCRIPTORS:
        label = owner.replace("-", " ").title()
        observed = resources.get(owner, {})
        native_threads = observed.get("maximumNativeThreads", 0)
        open_files = observed.get("maximumOpenFileDescriptors", 0)
        if native_threads <= 0:
            raise RuntimeError(f"{label} native-thread evidence is missing")
        if open_files <= 0:
            raise RuntimeError(f"{label} open-file evidence is missing")
        if "averageCpuCores" not in observed:
            raise RuntimeError(f"{label} CPU evidence is missing")
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

    stable_contracts = (
        (
            "initial-headroom",
            "runtime-server-initial",
            INITIAL_STABLE_OPEN_FILES,
        ),
        (
            "retained-after-initial",
            "runtime-server-initial",
            RETAINED_STABLE_OPEN_FILES,
        ),
        (
            "retained-after-graceful-restart",
            "runtime-server-graceful",
            RETAINED_STABLE_OPEN_FILES,
        ),
        (
            "retained-after-hard-restart-1",
            "runtime-server-hard-1",
            RETAINED_STABLE_OPEN_FILES,
        ),
        (
            "retained-after-hard-restart-2",
            "runtime-server-hard-2",
            RETAINED_STABLE_OPEN_FILES,
        ),
    )
    for checkpoint_name, server_owner, maximum_open_files in stable_contracts:
        checkpoint = checkpoints.get(checkpoint_name)
        if not isinstance(checkpoint, dict) or checkpoint.get("sampleCount") != 3:
            raise RuntimeError(
                f"stable resource checkpoint {checkpoint_name} is missing"
            )
        samples_by_process = checkpoint.get("samplesByProcess")
        if not isinstance(samples_by_process, dict):
            raise RuntimeError("stable resource samples are invalid")
        for owner, thread_limit in (
            ("worker-host", STABLE_HOST_NATIVE_THREADS),
            (server_owner, STABLE_SERVER_NATIVE_THREADS),
        ):
            samples = samples_by_process.get(owner)
            if not isinstance(samples, list) or len(samples) != 3:
                raise RuntimeError(
                    f"{checkpoint_name} must contain three {owner} samples"
                )
            for sample in samples:
                if not isinstance(sample, dict):
                    raise RuntimeError("stable resource sample is invalid")
                if sample.get("nativeThreads", 0) >= thread_limit:
                    raise RuntimeError(
                        f"{checkpoint_name} {owner} native threads exceeded "
                        f"<{thread_limit}"
                    )
                if sample.get("openFileDescriptors", 0) >= maximum_open_files:
                    raise RuntimeError(
                        f"{checkpoint_name} {owner} open files exceeded "
                        f"<{maximum_open_files}"
                    )

    first = _checkpoint_process_samples(
        checkpoints,
        "retained-after-initial",
        "worker-host",
    )
    final = _checkpoint_process_samples(
        checkpoints,
        "retained-after-hard-restart-2",
        "worker-host",
    )
    first_fds = max(sample["openFileDescriptors"] for sample in first)
    final_fds = max(sample["openFileDescriptors"] for sample in final)
    if final_fds - first_fds > MAXIMUM_FINAL_HOST_FD_GROWTH:
        raise RuntimeError("Worker Host stable file descriptors accumulated")
    first_threads = max(sample["nativeThreads"] for sample in first)
    final_threads = max(sample["nativeThreads"] for sample in final)
    if final_threads - first_threads > MAXIMUM_FINAL_HOST_THREAD_GROWTH:
        raise RuntimeError("Worker Host stable native threads accumulated")


def _checkpoint_process_samples(
    checkpoints: dict[str, dict[str, object]],
    checkpoint_name: str,
    owner: str,
) -> list[dict[str, int]]:
    checkpoint = checkpoints[checkpoint_name]
    samples_by_process = checkpoint["samplesByProcess"]
    assert isinstance(samples_by_process, dict)
    samples = samples_by_process[owner]
    assert isinstance(samples, list)
    return samples


def _validate_stage_summary(
    summary: dict[str, object],
    options: argparse.Namespace,
    stage: str,
) -> None:
    items_per_task = options.workload_items_per_task
    total_items = TASKS_PER_STAGE * items_per_task
    expected_batches = TASKS_PER_STAGE * ((items_per_task + 99) // 100)
    expected = {
        "status": "passed",
        "stage": stage,
        "preparedIdentities": options.prepared_workers,
        "retainedIdentities": options.retained_workers,
        "observedRetainedIdentities": options.retained_workers,
        "stoppedIdentities": (
            options.prepared_workers - options.retained_workers
        ),
        "activeTaskCount": TASKS_PER_STAGE,
        "maximumCandidateWorkersPerTask": 100,
        "offeredItemsPerTask": items_per_task,
        "totalOfferedItems": total_items,
        "appendBatchCount": expected_batches,
        "succeededItemCount": total_items,
        "postWorkStoppedConnected": 0,
        "postWorkStoppedHot": 0,
        "postWorkStoppedMissing": 0,
        "mutationCheckpointTaskCount": TASKS_PER_STAGE,
    }
    for field, value in expected.items():
        if summary.get(field) != value:
            raise RuntimeError(
                f"scale stage summary {field} must be {value}, "
                f"observed {summary.get(field)!r}"
            )
    tasks = summary.get("tasks")
    if not isinstance(tasks, list) or len(tasks) != TASKS_PER_STAGE:
        raise RuntimeError("scale stage must report ten Tasks")
    for task in tasks:
        if (
            not isinstance(task, dict)
            or task.get("succeededCount") != items_per_task
            or task.get("exported") is not True
        ):
            raise RuntimeError("scale stage Task export is incomplete")
    if (
        summary.get("minimumConnectedDuringWork", 0)
        < options.minimum_retained_converged
    ):
        raise RuntimeError("scale stage lost the minimum workload connections")
    if (
        summary.get("postWorkConnectedAndHot", 0)
        < options.minimum_retained_converged
    ):
        raise RuntimeError("scale stage did not reconverge after workload drain")

    checkpoint_succeeded = summary.get("mutationCheckpointSucceededItems")
    checkpoint_unresolved = summary.get("mutationCheckpointUnresolvedItems")
    if (
        not isinstance(checkpoint_succeeded, int)
        or not 1 <= checkpoint_succeeded <= total_items // 2
    ):
        raise RuntimeError("scale stage missed the mutation success window")
    if (
        checkpoint_unresolved != total_items - checkpoint_succeeded
        or checkpoint_unresolved < total_items - total_items // 2
    ):
        raise RuntimeError("scale stage missed the mutation backlog window")

    if (
        stage == "initial-contraction"
        and summary.get("initialHeadroomConnectedAndHot", 0)
        < options.minimum_initial_converged
    ):
        raise RuntimeError("scale stage did not establish initial headroom")
    if stage == "initial-contraction":
        if summary.get("initialHeadroomQualifyingScans", 0) < 3:
            raise RuntimeError("initial headroom did not contain three scans")
        if summary.get("initialHeadroomStableMillis", 0) < options.stable_hold_millis:
            raise RuntimeError("initial headroom did not sustain its hold")
    expected_stop_batches = (
        (options.prepared_workers - options.retained_workers + 99) // 100
        if stage == "initial-contraction"
        else 0
    )
    if summary.get("batchStopRequestCount") != expected_stop_batches:
        raise RuntimeError("scale stage batch-stop count changed")
    if stage.startswith("hard-restart-"):
        if summary.get("recoverySnapshotUnresolvedItems", 0) < 1:
            raise RuntimeError("hard restart did not retain post-restart backlog")
        if summary.get("postRecoveryProgress") is not True:
            raise RuntimeError("hard restart produced no post-restart progress")


def _validate_identity_digests(
    summaries: dict[str, dict[str, object]],
) -> None:
    first = summaries[STAGES[0]]
    for stage in STAGES[1:]:
        observed = summaries[stage]
        for field in (
            "allWorkerIdSetSha256",
            "retainedWorkerIdSetSha256",
            "stoppedWorkerIdSetSha256",
        ):
            if observed.get(field) != first.get(field):
                raise RuntimeError(f"{field} changed during {stage}")


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
    os.killpg(process.pid, HARD_KILL_SIGNAL if force else signal.SIGTERM)
    try:
        process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, HARD_KILL_SIGNAL)
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
            "preparedWorkers": options.prepared_workers,
            "retainedWorkers": options.retained_workers,
            "minimumInitialConnectedAndHot": (
                options.minimum_initial_converged
            ),
            "minimumRetainedConnectedAndHot": (
                options.minimum_retained_converged
            ),
            "workloadStages": 4,
            "taskCount": 4 * TASKS_PER_STAGE,
            "workloadItemsPerTask": options.workload_items_per_task,
            "totalWorkloadItems": (
                4 * TASKS_PER_STAGE * options.workload_items_per_task
            ),
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
