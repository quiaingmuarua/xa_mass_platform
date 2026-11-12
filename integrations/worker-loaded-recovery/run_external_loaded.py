#!/usr/bin/env python3
"""One-shot Linux consumer of the published Worker Simulator capacity kit.

This entry owns only its two local JVMs, private input copies and process evidence.
The Java Harness owns remote admission, workload, identity and result assertions.
It never builds artifacts, starts a Runtime, accesses Redis, or invokes SSH.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform as platform_info
import re
import shutil
import signal
import subprocess
import sys
import time
from urllib.parse import urlsplit

try:
    from support.process_sampling import _ProcessSampler, _ResourceSamplingError
    from support.processes import start_process, stop_process
except ModuleNotFoundError:
    # Source import is for unit tests. Execution still requires the installed kit.
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from integrations.worker_proof_support.process_sampling import _ProcessSampler, _ResourceSamplingError
    from integrations.worker_proof_support.processes import start_process, stop_process


DEFAULTS = {
    "prepared_workers": 15_000,
    "retained_workers": 10_000,
    "minimum_initial_connected": 14_800,
    "minimum_retained_connected": 9_900,
    "workload_items_per_task": 5_000,
    "stable_hold_millis": 60_000,
    "maximum_convergence_wait_millis": 900_000,
    "task_result_wait_millis": 900_000,
    "request_timeout_millis": 30_000,
    "scan_interval_millis": 10_000,
}
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
ACCESS_KEY = re.compile(r"xak_[A-Za-z0-9_-]{8,128}")
HOST_MAIN = "com.xa.mass.workersimulator.WorkerSimulatorMain"
HARNESS_MAIN = "com.xa.mass.integration.workerloadedrecovery.WorkerExternalCapacityMain"
READY = re.compile(r"WORKER_SIMULATOR_READY control=(http://127\.0\.0\.1:([0-9]+)/lab) initialWorkerCount=0 scheduledStopCount=0 groupCount=1")
MAXIMUM_THREADS = 512
MAXIMUM_FDS = {"worker-host": 32_768, "external-harness": 8_192}


class RunFailure(RuntimeError):
    """Safe operator-authored text only, never a response or child log."""


def _object(text: str, label: str) -> dict:
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ValueError(label + " contains a duplicate field")
            result[key] = value
        return result
    try:
        result = json.loads(text, object_pairs_hook=pairs)
    except (ValueError, UnicodeError) as error:
        raise ValueError(label + " must contain a valid JSON object") from None
    if not isinstance(result, dict):
        raise ValueError(label + " must contain a JSON object")
    return result


def _origin(value: str) -> str:
    try:
        parsed = urlsplit(value)
        valid = (parsed.scheme == "https" and bool(parsed.hostname)
                 and parsed.username is None and parsed.password is None
                 and not parsed.query and not parsed.fragment and parsed.path in ("", "/")
                 and (parsed.port is None or 1 <= parsed.port <= 65535))
    except ValueError:
        valid = False
    if not valid:
        raise argparse.ArgumentTypeError("Runtime addresses must be HTTPS origins without credentials, path, query or fragment")
    return value.rstrip("/")


def _identifier(value: str) -> str:
    if not IDENTIFIER.fullmatch(value):
        raise argparse.ArgumentTypeError("Identity must be 1..128 safe ASCII identifier characters")
    return value


def options(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-url", type=_origin, required=True)
    parser.add_argument("--worker-runtime-url", type=_origin, required=True)
    parser.add_argument("--project", type=_identifier, required=True)
    parser.add_argument("--group", type=_identifier, required=True)
    parser.add_argument("--endpoint-manager", type=_identifier, required=True)
    parser.add_argument("--access-key-file", type=Path, required=True)
    parser.add_argument("--gateway-credential-file", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--diagnostic", action="store_true", help="Reduced fixture only; never a capacity acceptance")
    parser.add_argument("--java-truststore", type=Path, help="Diagnostic fixture CA store only; normal runs use JVM trust")
    parser.add_argument("--java-truststore-password", help="Nonsecret diagnostic CA-store password (never a gateway credential)")
    for name, default in DEFAULTS.items():
        parser.add_argument("--" + name.replace("_", "-"), type=int, default=default)
    result = parser.parse_args(arguments)
    if bool(result.java_truststore) != bool(result.java_truststore_password) or result.java_truststore and not result.diagnostic:
        parser.error("An explicit CA store and its fixture password require --diagnostic and must be supplied together")
    if any(getattr(result, name) != default for name, default in DEFAULTS.items()) and not result.diagnostic:
        parser.error("Changed workload/observation values require --diagnostic")
    if not 1 <= result.retained_workers < result.prepared_workers <= 15_000:
        parser.error("Worker counts must satisfy 1 <= retained < prepared <= 15000")
    if not 1 <= result.minimum_initial_connected <= result.prepared_workers:
        parser.error("Initial connection minimum must fit the prepared fleet")
    if not 1 <= result.minimum_retained_connected <= result.retained_workers:
        parser.error("Retained connection minimum must fit the retained fleet")
    if any(getattr(result, name) <= 0 for name in DEFAULTS):
        parser.error("Workload and time bounds must be positive")
    if result.workload_items_per_task > 5_000:
        parser.error("This consumer accepts at most 5000 Items per Task")
    return result


def _private_inputs(access_path: Path, gateway_path: Path) -> tuple[str, dict]:
    try:
        if access_path.stat().st_size > 4096 or gateway_path.stat().st_size > 65536:
            raise ValueError("Private input exceeds its size bound")
        access = access_path.read_text(encoding="utf-8").strip()
        gateway = _object(gateway_path.read_text(encoding="utf-8"), "Worker credential")
    except OSError:
        raise ValueError("Cannot read the explicitly supplied private input files") from None
    except UnicodeError:
        raise ValueError("Private input must be UTF-8") from None
    if not ACCESS_KEY.fullmatch(access):
        raise ValueError("Access-key file must contain exactly one valid access key")
    if set(gateway) != {"keyId", "secret"} or any(not isinstance(gateway[k], str) for k in gateway):
        raise ValueError("Worker credential requires only keyId and secret strings")
    key, secret = gateway["keyId"], gateway["secret"]
    if not 1 <= len(key) <= 128 or any(ord(c) < 33 or ord(c) > 126 or c == ':' for c in key):
        raise ValueError("Worker keyId is invalid")
    if not secret.strip() or len(secret.encode("utf-8")) > 4096 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in secret):
        raise ValueError("Worker secret is invalid")
    return access, gateway


def _kit(root: Path) -> dict:
    manifest = _object((root / "manifest.json").read_text(encoding="utf-8"), "Kit manifest")
    expected = {"schemaVersion": 2, "version": manifest.get("version"), "gitCommit": manifest.get("gitCommit"),
                "javaVersion": 21, "simulatorEntry": "bin/xa-mass-worker-simulator",
                "roundEntry": "acceptance/finite_round.py", "loadedEntry": "acceptance/run_loaded.py"}
    if manifest != expected or not re.fullmatch(r"[0-9a-f]{40}", str(manifest.get("gitCommit"))):
        raise ValueError("This entry requires the verified version-2 Worker Simulator kit")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", str(manifest.get("version"))):
        raise ValueError("Kit version must be a released numeric version")
    for relative in ("lib", "acceptance/loaded/lib"):
        directory = (root / relative).resolve()
        if not directory.is_relative_to(root) or not directory.is_dir() or not list(directory.glob("*.jar")):
            raise ValueError("Published Host or Harness libraries are missing")
    return manifest


def _preflight(root: Path, output: Path, *, diagnostic: bool = False, prepared_workers: int = 15_000) -> tuple[dict, str, dict]:
    if not sys.platform.startswith("linux"):
        raise ValueError("External loaded acceptance requires Linux /proc evidence")
    import resource
    minimum_fds = max(1024, prepared_workers * 3 + 256) if diagnostic else 65536
    if resource.getrlimit(resource.RLIMIT_NOFILE)[0] < minimum_fds:
        raise ValueError("External consumer has insufficient open-file allowance")
    ports = [int(value) for value in Path("/proc/sys/net/ipv4/ip_local_port_range").read_text().split()]
    minimum_ports = prepared_workers * 3 if diagnostic else 45000
    if len(ports) != 2 or ports[1] - ports[0] + 1 < minimum_ports:
        raise ValueError("External consumer has insufficient ephemeral ports")
    if output == Path(output.anchor) or output.is_relative_to(root) or root.is_relative_to(output):
        raise ValueError("Output must be outside the immutable kit")
    if output.exists():
        raise ValueError("Output directory already exists; retain evidence and choose a new directory")
    java = shutil.which("java")
    if not java:
        raise ValueError("Java 21 executable is required")
    version = subprocess.run([java, "-version"], capture_output=True, text=True, check=True)
    java_version = (version.stderr + version.stdout).strip()
    if not re.search(r'(?:openjdk|java) version "21(?:[.+\"]|$)', java_version):
        raise ValueError("Java 21 executable is required")
    metadata = _platform_metadata(java, java_version)
    metadata.update(nofile=resource.getrlimit(resource.RLIMIT_NOFILE)[0], ephemeralPortRange=ports)
    return _kit(root), java, metadata


def _optional_cgroup(name: str) -> dict:
    try:
        entries = Path("/proc/self/cgroup").read_text(encoding="ascii").splitlines()
        relative = next(line.split(":", 2)[2] for line in entries if line.startswith("0::"))
        if ".." in Path(relative).parts:
            return {"value": None, "status": "unavailable"}
        path = Path("/sys/fs/cgroup") / relative.lstrip("/") / name
        text = path.read_text(encoding="ascii").strip()
        if len(text) > 256:
            return {"value": None, "status": "unavailable"}
        return {"value": text, "path": str(path), "status": "observed"}
    except (OSError, UnicodeError, StopIteration):
        return {"value": None, "status": "unavailable"}


def _platform_metadata(java: str, java_version: str) -> dict:
    memory = Path("/proc/meminfo").read_text(encoding="ascii")
    total = re.search(r"^MemTotal:\s+([0-9]+)\s+kB$", memory, re.MULTILINE)
    if total is None or int(total.group(1)) <= 0:
        raise ValueError("Consumer MemTotal evidence is unavailable")
    try:
        affinity = len(os.sched_getaffinity(0))
    except (AttributeError, OSError):
        affinity = None
    return {"os": platform_info.system(), "kernel": platform_info.release(), "architecture": platform_info.machine(),
            "logicalCpuCount": os.cpu_count(), "affinityCpuCount": affinity,
            "memTotalBytes": int(total.group(1)) * 1024, "pythonVersion": platform_info.python_version(),
            "javaExecutable": java, "javaVersion": java_version,
            "cgroupCpuMax": _optional_cgroup("cpu.max"), "cgroupMemoryMax": _optional_cgroup("memory.max")}


def _environment() -> dict[str, str]:
    allowed = {"PATH", "JAVA_HOME", "HOME", "USER", "LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP"}
    return {key: value for key, value in os.environ.items() if key in allowed}


def _write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _host_configuration(value, private: Path) -> dict:
    return {"runtimeApiBaseUrl": value.worker_runtime_url,
            "gatewayCredentialFile": str(private / "worker-gateway.json"),
            "controlPort": 0, "sandboxRoot": str(private / "data/scenario-workers"),
            "startupPlan": {"initialWorkers": [], "scheduledStops": []},
            "workerGroups": {value.group: {"count": value.prepared_workers,
                                         "events": ["extension.worker.string.md5"], "propertiesTemplate": {},
                                         "newEnvironment": False, "requestTimeoutMillis": 60_000,
                                         "reconnectPolicy": {"maxUnstableAttempts": 600,
                                                             "reconnectIntervalMillis": 500,
                                                             "stableConnectionDurationMillis": 10_000}}}}


def _tls_options(value) -> list[str]:
    if value.java_truststore is None:
        return []
    path = value.java_truststore.resolve()
    if not path.is_file():
        raise ValueError("Explicit diagnostic CA store does not exist")
    return ["-Djavax.net.ssl.trustStore=" + str(path),
            "-Djavax.net.ssl.trustStorePassword=" + value.java_truststore_password]


def _host_command(java: str, kit: Path, config: Path, tls=()) -> list[str]:
    return [java, "-Xms256m", "-Xmx3g", "-XX:+ExitOnOutOfMemoryError", *tls, "-cp", str(kit / "lib/*"),
            HOST_MAIN, "--config", str(config)]


def _harness_command(java: str, kit: Path, value, lab: str, private: Path, evidence: Path) -> list[str]:
    command = [java, "-Xms128m", "-Xmx1g", "-XX:+ExitOnOutOfMemoryError", *_tls_options(value), "-cp", str(kit / "acceptance/loaded/lib/*"), HARNESS_MAIN,
               "--server-base-url=" + value.runtime_url, "--lab-base-url=" + lab,
               "--project-id=" + value.project, "--worker-group-id=" + value.group,
               "--endpoint-manager-id=" + value.endpoint_manager,
               "--access-key-file=" + str(private / "access-key.txt"), "--output-dir=" + str(evidence)]
    command += ["--" + name.replace("_", "-") + "=" + str(getattr(value, name)) for name in DEFAULTS]
    return command


def _resources(sampler: _ProcessSampler, *, checkpoint: str) -> None:
    for owner, sample in sampler.sample_now(checkpoint).items():
        if not 0 < sample["nativeThreads"] < MAXIMUM_THREADS or not 0 < sample["openFileDescriptors"] < MAXIMUM_FDS[owner]:
            raise RunFailure("External consumer process exceeded its native thread or FD guard")


def _wait_host(process, log: Path, sampler: _ProcessSampler, seconds: float = 180) -> str:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        sampler.check()
        if process.poll() is not None:
            raise RunFailure("Published Worker Host exited before inventory readiness; inspect private local log")
        if log.is_file():
            # The empty startup plan has no worker traffic before this marker.
            match = READY.search(log.read_text(encoding="utf-8", errors="replace")[-65536:])
            if match:
                if not 1 <= int(match.group(2)) <= 65535:
                    raise RunFailure("Worker Host reported an invalid loopback control port")
                return match.group(1).removesuffix("/lab")
        time.sleep(0.2)
    raise RunFailure("Worker Host inventory readiness timed out")


def _wait_harness(host, harness, sampler: _ProcessSampler, seconds: float = 4200) -> int:
    deadline = time.monotonic() + seconds
    guard_at = 0.0
    while time.monotonic() < deadline:
        sampler.check()
        if host.poll() is not None:
            raise RunFailure("External Worker Host exited during acceptance")
        if time.monotonic() >= guard_at:
            _resources(sampler, checkpoint="consumer-guard")
            guard_at = time.monotonic() + 5
        try:
            result = harness.wait(timeout=1)
            sampler.check()
            return result
        except subprocess.TimeoutExpired:
            pass
    raise RunFailure("External consumer exceeded its 70-minute bound; known Task receipts are retained")


def _audit_passed(value: dict, configured, directory: Path) -> None:
    def require(condition, message):
        if not condition:
            raise RunFailure("Java acceptance evidence failed audit: " + message)
    expected_items = 10 * configured.workload_items_per_task
    require(value.get("schemaVersion") == 1 and value.get("outcome") == "passed" and value.get("phase") == "complete",
            "completed outcome is missing")
    for name, expected in (("preparedWorkers", configured.prepared_workers), ("retainedWorkers", configured.retained_workers),
                           ("workloadItemsPerTask", configured.workload_items_per_task), ("expectedItems", 2 * expected_items),
                           ("identityCount", configured.prepared_workers)):
        require(type(value.get(name)) is int and value[name] == expected, name + " differs from the configured workload")
    require(value.get("unknownWrites") == [] and value.get("cleanup") == [], "passed work contains unknown writes or cleanup")
    identity_path = directory / "private/worker-identities.json"
    require(identity_path.is_file() and identity_path.stat().st_size <= 8 * 1024 * 1024, "bounded identity evidence is missing")
    baseline = _object(identity_path.read_text(encoding="utf-8"), "Private identity evidence")
    identities = baseline.get("workerIdsByLabWorkerKey")
    require(baseline.get("workerGroupId") == configured.group and isinstance(identities, dict)
            and len(identities) == configured.prepared_workers, "private identity scope or count differs")
    require(all(isinstance(key, str) and key and isinstance(worker, str) and worker for key, worker in identities.items())
            and len(set(identities.values())) == configured.prepared_workers, "Worker identities are missing or duplicated")
    digest = hashlib.sha256("".join(worker + "\n" for worker in sorted(identities.values())).encode("utf-8")).hexdigest()
    require(value.get("identityDigest") == digest, "identity digest differs from the private baseline")
    cases = value.get("cases")
    require(isinstance(cases, list) and len(cases) == 2, "both fleet cases are required")
    task_ids = []
    for case, label, active, minimum in zip(cases, ("initial", "retained"),
                                            (configured.prepared_workers, configured.retained_workers),
                                            (configured.minimum_initial_connected, configured.minimum_retained_connected)):
        require(isinstance(case, dict) and case.get("case") == label and case.get("status") == "passed", "case identity or outcome differs")
        for name, expected in (("preparedWorkers", configured.prepared_workers), ("activeWorkers", active), ("taskCount", 10),
                               ("expectedItems", expected_items), ("succeededItems", expected_items),
                               ("verifiedItems", expected_items), ("terminalTaskCount", 10)):
            require(type(case.get(name)) is int and case[name] == expected, label + ": " + name + " differs")
        ids = case.get("taskIds")
        require(isinstance(ids, list) and len(ids) == 10 and all(isinstance(task, str) and task for task in ids)
                and len(set(ids)) == 10, label + ": Task identities are missing or duplicated")
        task_ids.extend(ids)
        require(type(case.get("minimumConnected")) is int and minimum <= case["minimumConnected"] <= active,
                label + ": in-work connection threshold is missing")
        elapsed, rate = case.get("elapsedMillis"), case.get("observedItemsPerSecond")
        require(type(elapsed) is int and elapsed > 0 and isinstance(rate, (int, float)) and not isinstance(rate, bool)
                and math.isfinite(rate) and math.isclose(rate, expected_items * 1000.0 / elapsed, rel_tol=1e-9),
                label + ": elapsed time and observed completion rate disagree")
        require(all(type(case.get(name)) is int and case[name] >= 0
                    for name in ("preloadMillis", "verificationMillis", "exportMillis"))
                and case["exportMillis"] <= case["verificationMillis"], label + ": stage durations are missing or inconsistent")
        per_task = case.get("tasks")
        require(isinstance(per_task, list) and len(per_task) == 10 and all(isinstance(task, dict) for task in per_task),
                label + ": per-Task evidence is missing")
        require([task.get("taskId") for task in per_task] == ids, label + ": per-Task identities differ")
        for task in per_task:
            require(all(type(task.get(name)) is int and task[name] == configured.workload_items_per_task
                        for name in ("expectedItems", "verifiedItems", "totalItems", "succeededItems"))
                    and type(task.get("failedItems")) is int and task["failedItems"] == 0,
                    label + ": per-Task verification or final counts differ")
            require(type(task.get("terminalObservedMillis")) is int and 0 <= task["terminalObservedMillis"] <= elapsed
                    and type(task.get("exportMillis")) is int and task["exportMillis"] >= 0,
                    label + ": per-Task durations are invalid")
        for gate_name in ("preWorkStability", "postWorkStability"):
            gate = case.get(gate_name)
            require(isinstance(gate, dict), label + ": stability evidence is missing")
            require(type(gate.get("qualifyingScans")) is int and gate["qualifyingScans"] >= 3
                    and type(gate.get("stableMillis")) is int and gate["stableMillis"] >= configured.stable_hold_millis,
                    label + ": connection stability window is incomplete")
            require(type(gate.get("requiredConnected")) is int and gate["requiredConnected"] == minimum
                    and type(gate.get("minimumConnected")) is int and minimum <= gate["minimumConnected"] <= active
                    and type(gate.get("activeConnected")) is int and minimum <= gate["activeConnected"] <= active,
                    label + ": stability connection threshold differs")
            require(type(gate.get("stoppedConnected")) is int and gate["stoppedConnected"] == 0
                    and type(gate.get("missingSchedulingIdentities")) is int and gate["missingSchedulingIdentities"] == 0,
                    label + ": missing identities or connected stopped Workers")
    known = value.get("knownTaskIds")
    require(isinstance(known, list) and len(known) == 20 and all(isinstance(task, str) for task in known)
            and len(set(task_ids)) == 20 and set(known) == set(task_ids), "whole-run Task identities differ")


def _public_summary(path: Path, exit_code: int, configured) -> dict:
    if not path.is_file():
        raise RunFailure("Java acceptance did not publish a summary; incremental receipts remain available")
    value = _object(path.read_text(encoding="utf-8"), "Java acceptance summary")
    expected = {0: "passed", 1: "failed", 3: "unconfirmed"}.get(exit_code)
    if expected is None or value.get("status") != expected:
        raise RunFailure("Java exit status and acceptance summary disagree")
    if expected == "passed":
        _audit_passed(value, configured, path.parent)
    return value


def _remote_cleanup(value: dict) -> str:
    if value.get("status") == "passed":
        return "not-required-verified-terminal"
    known, cleanup = value.get("knownTaskIds"), value.get("cleanup")
    if value.get("unknownWrites") != [] or not isinstance(known, list) or not isinstance(cleanup, list):
        return "unconfirmed"
    try:
        confirmed = {entry["taskId"] for entry in cleanup
                     if entry.get("status") in ("already-terminal", "close-confirmed")}
        return "confirmed-known-tasks" if set(known) <= confirmed else "unconfirmed"
    except (TypeError, KeyError):
        return "unconfirmed"


def run(value, kit_root: Path | None = None) -> int:
    kit = (kit_root or Path(__file__).resolve().parent.parent).resolve()
    output = value.output_dir.resolve()
    manifest, java, platform = _preflight(kit, output, diagnostic=value.diagnostic, prepared_workers=value.prepared_workers)
    access, gateway = _private_inputs(value.access_key_file.resolve(), value.gateway_credential_file.resolve())
    tls = _tls_options(value)
    output.mkdir(parents=True, mode=0o700)
    private = output / "private"
    private.mkdir(mode=0o700)
    evidence = output / "evidence"
    secret_paths = (private / "access-key.txt", private / "worker-gateway.json")
    processes = []
    sampler = _ProcessSampler(output / "process-resources.jsonl")
    result = {"status": "failed", "claim": "diagnostic-only" if value.diagnostic else "external-release-capacity",
              "kitVersion": manifest["version"], "gitCommit": manifest["gitCommit"],
              "runtimeUrl": value.runtime_url, "workerRuntimeUrl": value.worker_runtime_url,
              "projectId": value.project, "workerGroupId": value.group, "endpointManagerId": value.endpoint_manager,
              "configured": {name: getattr(value, name) for name in DEFAULTS}, "platform": platform,
              "tls": "explicit-diagnostic-ca" if tls else "default-jvm-trust",
              "remoteTaskCleanup": "unconfirmed"}
    exit_code = 1
    sampler_started = False
    try:
        secret_paths[0].write_text(access + "\n", encoding="utf-8")
        _write_json(secret_paths[1], gateway)
        for path in secret_paths: path.chmod(0o600)
        access = None
        gateway = None
        config = private / "simulator.json"
        host_configuration = _host_configuration(value, private)
        _write_json(config, host_configuration)
        group_configuration = host_configuration["workerGroups"][value.group]
        result["workerConnection"] = {name: group_configuration[name]
                                      for name in ("requestTimeoutMillis", "reconnectPolicy")}
        result["hostConfigurationSha256"] = hashlib.sha256(config.read_bytes()).hexdigest()
        sampler.start()
        sampler_started = True
        host_log = private / "worker-host.log"
        host = start_process(_host_command(java, kit, config, tls), host_log, _environment(), cwd=private)
        processes.append(host)
        sampler.register("worker-host", host)
        lab = _wait_host(host, host_log, sampler)
        harness = start_process(_harness_command(java, kit, value, lab, private, evidence),
                                private / "harness.log", _environment(), cwd=private)
        processes.append(harness)
        sampler.register("external-harness", harness)
        exit_code = _wait_harness(host, harness, sampler)
        result["acceptance"] = _public_summary(evidence / "summary.json", exit_code, value)
        result["remoteTaskCleanup"] = _remote_cleanup(result["acceptance"])
        sampler.sample_now("final-host")
        sampler.close()
        resources = sampler.summary()
        for owner in MAXIMUM_FDS:
            current = resources.get(owner, {})
            if (current.get("periodicSampleCount", 0) < 1
                    or not 0 < current.get("maximumNativeThreads", 0) < MAXIMUM_THREADS
                    or not 0 < current.get("maximumOpenFileDescriptors", 0) < MAXIMUM_FDS[owner]
                    or "averageCpuCores" not in current):
                raise RunFailure("Periodic evidence is missing for an external consumer process")
        result.update(status={0: "passed", 1: "failed", 3: "unconfirmed"}[exit_code], resources=resources)
        if value.diagnostic and result["status"] == "passed":
            result["status"] = "diagnostic-passed"
    except BaseException as error:
        exit_code = 1
        result.update(status="failed", failureKind="resource-sampling-failed" if isinstance(error, _ResourceSamplingError) else "consumer-failed",
                      reason=str(error) if isinstance(error, RunFailure) else type(error).__name__)
    finally:
        if sampler_started:
            sampler.close()
            result["resourceSampling"] = sampler.diagnostics()
            if result["resourceSampling"]["status"] != "complete":
                result["status"] = "failed"
                result.setdefault("failureKind", "resource-sampling-failed")
                exit_code = 1
        cleanup_errors = []
        for process in reversed(processes):
            try:
                stop_process(process, force=False)
            except BaseException as error:
                cleanup_errors.append({"pid": process.pid, "errorType": type(error).__name__})
        for path in secret_paths:
            try:
                path.unlink(missing_ok=True)
            except OSError as error:
                cleanup_errors.append({"privateFile": path.name, "errorType": type(error).__name__})
        result["cleanup"] = {"ownedProcessesStopped": not any(process.poll() is None for process in processes),
                             "privateCredentialCopiesRemoved": not any(path.exists() for path in secret_paths), "errors": cleanup_errors}
        if cleanup_errors or not result["cleanup"]["ownedProcessesStopped"]:
            result["status"] = "failed"
            result.setdefault("failureKind", "cleanup-failed")
            exit_code = 1
        _write_json(output / "summary.json", result)
    print(json.dumps({"status": result["status"], "summary": str(output / "summary.json"), "claim": result["claim"]}))
    return exit_code


def main(arguments=None) -> int:
    value = options(arguments)
    def interrupted(signum, frame):
        raise RunFailure("External consumer interrupted; known Task receipts are retained")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        return run(value)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("External consumer configuration failed: " + (str(error) if isinstance(error, ValueError) else type(error).__name__), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
