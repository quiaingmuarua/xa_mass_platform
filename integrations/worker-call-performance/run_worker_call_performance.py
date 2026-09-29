#!/usr/bin/env python3
"""Performance lane runner (README.md): processes, evidence and comparison; assertions live in the Java Harness."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from integrations.worker_proof_support.scenario_inventory import materialize_inventory
sys.path.insert(0, str(Path(__file__).resolve().parent))
import lane_trend  # noqa: E402  (sibling module; this directory name is not a package)

REDIS_IMAGE = "redis:7.4.10"
JVM = ("-Xms256m", "-Xmx1g", "-XX:+ExitOnOutOfMemoryError")
MODULE = ROOT / "integrations/worker-call-performance"
# Server settings shared by every lane world; lane_server_flags adds the Groups, Project and raised resources.
LANE_BASE_FLAGS = {
    "spring.profiles.active": "scenario-workers",
    "server.address": "127.0.0.1", "server.port": "18082",
    "xa.mass.kernel-pacer.preset": "DEFAULT",
    "xa.mass.kernel-pacer.enabled": "true",
    "xa.mass.task-rpc.default-wait-timeout-millis": "30000",
    "xa.mass.task-rpc.max-wait-timeout-millis": "60000",
    "xa.mass.task-rpc.max-waiters": "10000",
    "xa.mass.task-rpc.max-pending-observations": "100000",
    "xa.mass.task-rpc.initial-probe-interval-millis": "50",
    "xa.mass.task-rpc.normal-probe-interval-millis": "100",
    "xa.mass.task-rpc.long-probe-interval-millis": "250",
}


def command(args, *, cwd=ROOT, timeout=600):
    return subprocess.run([str(x) for x in args], cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout).stdout.strip()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def fresh_output(path):
    path = path.resolve()
    build = (ROOT / "build").resolve()
    if not path.is_relative_to(build) or path == build or path.exists():
        raise ValueError("Output must be a fresh directory below repository build")
    path.mkdir(parents=True)
    return path


def parse_proc(status, stat, open_files, ticks):
    fields = dict(line.split(":", 1) for line in status.splitlines() if ":" in line)
    tail = stat[stat.rfind(")") + 2:].split()
    return {"nativeThreads": int(fields["Threads"].strip()),
            "rssBytes": int(fields.get("VmRSS", "0 kB").split()[0]) * 1024,
            "cpuSeconds": (int(tail[11]) + int(tail[12])) / ticks,
            "openFileDescriptors": open_files}


def process_sample(pid):
    proc = Path("/proc") / str(pid)
    return parse_proc((proc / "status").read_text(), (proc / "stat").read_text(),
                      len(list((proc / "fd").iterdir())), os.sysconf("SC_CLK_TCK"))


class Sampler:
    def __init__(self, path, redis_client):
        self.path, self.redis = path, redis_client
        self.processes = {}
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.failure = None
        self.counts = Counter()
        self.previous = {}
        self.thread = threading.Thread(target=self.run, name="performance-resource-sampler", daemon=True)

    def register(self, role, process):
        with self.lock:
            self.processes[role] = process

    def run(self):
        try:
            with self.path.open("x", encoding="utf-8") as output:
                while not self.stopped.is_set():
                    with self.lock:
                        processes = tuple(self.processes.items())
                    for role, process in processes:
                        if process.poll() is not None:
                            continue
                        now = time.monotonic()
                        try:
                            sample = process_sample(process.pid)
                        except (FileNotFoundError, PermissionError) as sample_error:
                            # Linux may revoke /proc access during exit before Popen's concurrent poll observes it.
                            # Confirm exit with a bounded wait; permission/coverage failures for a live process stay fatal.
                            try:
                                process.wait(timeout=.05)
                            except subprocess.TimeoutExpired:
                                raise sample_error
                            continue
                        previous = self.previous.get(role)
                        sample["averageCpuCores"] = (sample["cpuSeconds"] - previous[1]) / (now - previous[0]) if previous else 0
                        self.previous[role] = (now, sample["cpuSeconds"])
                        sample.update(role=role, pid=process.pid, epochMillis=int(time.time() * 1000))
                        output.write(json.dumps(sample) + "\n")
                        self.counts[role] += 1
                        if sample["nativeThreads"] >= 512 or sample["openFileDescriptors"] >= 8192:
                            raise RuntimeError(f"Resource ceiling exceeded for {role}")
                    # Aggregate diagnostics only: no domain keys, command arguments or payloads.
                    stats = self.redis.info("stats")
                    memory = self.redis.info("memory")
                    cpu = self.redis.info("cpu")
                    output.write(json.dumps({"role": "redis", "epochMillis": int(time.time() * 1000),
                        "totalCommandsProcessed": stats["total_commands_processed"],
                        "usedMemory": memory["used_memory"], "usedMemoryRss": memory["used_memory_rss"],
                        "cpuUserSeconds": cpu["used_cpu_user"], "cpuSystemSeconds": cpu["used_cpu_sys"],
                        "commandStats": self.redis.info("commandstats")}) + "\n")
                    self.counts["redis"] += 1
                    output.flush()
                    self.stopped.wait(1)
        except Exception as error:
            self.failure = type(error).__name__ + ": " + str(error)

    def stop(self):
        self.stopped.set()
        self.thread.join(5)
        if self.thread.is_alive():
            raise RuntimeError("Resource sampler did not stop")


def stop_process(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def start_process(args, path, env):
    with path.open("x", encoding="utf-8") as output:
        return subprocess.Popen([str(x) for x in args], cwd=ROOT, env=env,
                                stdout=output, stderr=subprocess.STDOUT, start_new_session=True)


def wait_http(url, process, sampler, deadline):
    while time.monotonic() < deadline:
        if process.poll() is not None or sampler.failure:
            raise RuntimeError("Process failed during readiness or resource sampling")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(.25)
    raise RuntimeError("Readiness deadline exceeded")


def server_distribution(root):
    main = "src/main/java/com/xa/mass/server/XaMassServerApplication.java"
    if (root / "server_boot_jvm" / main).is_file():
        return "server_boot_jvm"
    # Explicit immutable A/B checkouts retain their own historical build layout.
    # This never redirects the current checkout to another JAR producer.
    if root.resolve() != ROOT.resolve():
        for historical_module in ("spring_server_jvm", "distribution/server", "server_jvm"):
            if (root / historical_module / main).is_file():
                return historical_module
    raise RuntimeError("Server distribution entrypoint is missing from the checkout")


def build(root, harness=False):
    module = server_distribution(root).replace("/", ":")
    tasks = [f":{module}:bootJar", ":worker_simulator_jvm:installDist"]
    if harness:
        tasks.append(":integrations:worker-call-performance:installDist")
    # Keep builds outside all measurement windows.
    result = subprocess.run([str(root / "gradlew"), "--no-daemon", *tasks], cwd=root, timeout=900)
    result.check_returncode()


def fingerprint(root):
    names = ("application.yaml", "application-scenario-workers.yaml")
    directories = ["server_boot_jvm/src/main/resources"]
    if root.resolve() != ROOT.resolve():
        # Only an explicit historical checkout may retain the former layout.
        directories.extend(("spring_server_jvm/src/main/resources", "server_jvm/src/main/resources"))
    for directory in directories:
        files = tuple(f"{directory}/{name}" for name in names)
        if all((root / path).is_file() for path in files):
            return {path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in files}
        if any((root / path).is_file() for path in files):
            raise RuntimeError(f"Incomplete Server configuration group: {directory}")
    raise RuntimeError("Server configuration group is missing")


def configuration_sources(root):
    # These are repository configuration inputs, never runtime payloads or environment secrets.
    files = [*fingerprint(root),
        "kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/KernelPacerPolicyConfig.java",
        "kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/dispatch/AssignmentDispatchConfig.java",
        "kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/dispatch/DispatchConvergenceRuntime.java",
        "kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/result/ResultConvergenceConfig.java",
        "kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/result/ResultConvergenceRuntime.java",
        "kernel_pacer_jvm/src/main/java/com/xa/mass/kernel/pacer/result/WorkerServiceabilityResultConfig.java"]
    return {path: (root / path).read_text(encoding="utf-8") for path in files}


def lane_rates(value):
    rates = tuple(int(part) for part in value.split(","))
    if not rates or len(set(rates)) != len(rates) or any(rate not in (500, 1000, 2000) for rate in rates):
        raise argparse.ArgumentTypeError("lane rates must be distinct values from 500,1000,2000")
    return rates


def lane_modes(value):
    modes = tuple(part for part in value.split(","))
    if not modes or len(set(modes)) != len(modes) or any(mode not in LANE_MODES for mode in modes):
        raise argparse.ArgumentTypeError("lane modes must be distinct values from open,saturation")
    return modes


def artifact_fingerprints(root, server_jar):
    artifacts = [server_jar, *sorted((root / "worker_simulator_jvm/build/install/xa-mass-worker-simulator/lib").glob("*.jar")),
                 *sorted((ROOT / "integrations/worker-call-performance/build/install/xa-mass-worker-call-performance/lib").glob("*.jar"))]
    result = {}
    for path in artifacts:
        digest = hashlib.sha256()
        with path.open("rb") as artifact:
            for chunk in iter(lambda: artifact.read(1024 * 1024), b""):
                digest.update(chunk)
        role = "server" if path == server_jar else "host" if "worker_simulator_jvm" in path.parts else "harness"
        result[role + "/" + path.name] = digest.hexdigest()
    return result


# Performance lane world (README.md).
LANE_GROUPS = ("perf-a", "perf-b")
LANE_PROJECT = "perf-lane"
LANE_WORKERS_PER_GROUP = 1000
LANE_ASSIGNMENT_BATCH_LIMIT = 1000
LANE_EVENTS = ("extension.worker.string.md5", "extension.worker.lab.delay")
LANE_ADAPTER = "xa.mass.worker-delivery.adapter.instances.scenario-websocket."
LANE_WORLD_SECONDS = 6 * 60
# Configurable resources: raised so they do not bind, or audited as already non-binding.
# "evidence" names how the lane shows the value was not the binding constraint.
LANE_KNOB_AUDIT = (
    {"knob": "xa.mass.kernel-pacer.assignment-batch-limit", "laneValue": str(LANE_ASSIGNMENT_BATCH_LIMIT),
     "productionDefault": "100", "disposition": "raised",
     "reason": "Single-Task check budget is about 10 x B Items/s; 1000 is the configured maximum.",
     "evidence": "Dispatch checked-Item counts per round stay below B (S2)."},
    {"knob": LANE_ADAPTER + "report-queue-capacity", "laneValue": "10000", "productionDefault": "1000",
     "disposition": "raised", "reason": "Report admission must not drop or back-pressure Results at 2000/s.",
     "evidence": "No Report queue drop or capacity rejection (S2)."},
    {"knob": "xa.mass.task-rpc.max-probe-items-per-round", "laneValue": "1000", "productionDefault": "256",
     "disposition": "raised", "reason": "256 Items per 100ms probe round is about 2560/s, too close to 2000/s; 1000 is the maximum.",
     "evidence": "Probe batches stay below the limit (S2)."},
    {"knob": "xa.mass.task-rpc.refill-by-worker-group[<group>]", "laneValue": "any / {} / 1000",
     "productionDefault": "per profile", "disposition": "raised",
     "reason": "Pool watermark per Group at least B, so Refill supply does not trail assignment.",
     "evidence": "Candidate takes rarely come back empty (S2 validity)."},
    {"knob": "xa.mass.task-rpc.max-waiters / max-pending-observations", "laneValue": "10000 / 100000",
     "productionDefault": "10000 / 100000", "disposition": "audited",
     "reason": "At 2000/s with a 1s wait about 2000 waiters exist.", "evidence": "No waiter-capacity rejection (S2)."},
    {"knob": "xa.mass.direct-call.*", "laneValue": "3000 / 10000 / 1000 / 10000",
     "productionDefault": "3000 / 10000 / 1000 / 10000", "disposition": "audited",
     "reason": "Direct wait, per-Adapter command and pending bounds exceed 2000/s with a 1s wait.",
     "evidence": "No occupied-slot or HTTP 429 outcome in Direct cases (S2)."},
    {"knob": "server.tomcat.threads.max / spring.threads.virtual.enabled", "laneValue": "200 / false",
     "productionDefault": "200 / false", "disposition": "audited",
     "reason": "items:call and direct-calls complete through DeferredResult, so waiting holds no servlet thread.",
     "evidence": "HTTP executor observations in diagnostics mode (S2)."},
    {"knob": "Harness maximum in-flight", "laneValue": "4096", "productionDefault": "n/a", "disposition": "audited",
     "reason": "2000/s with at most 1s wait keeps about 2000 requests in flight.", "evidence": "No not-sent request (generator validity)."},
    {"knob": "Workers per Group / Handler", "laneValue": "1000 / MD5", "productionDefault": "n/a", "disposition": "raised",
     "reason": "Near-zero Handler time keeps Workers idle most of the time at 2000/s.",
     "evidence": "Minimum HOT Workers during the window stays at least twice the executing count (S2)."},
)
# Mechanism constants are measured, never raised (Owner: Pacer and Kernel Score).
LANE_MECHANISM_CONSTANTS = {
    "dispatchIntervalMillis": 50, "initializationIntervalMillis": 100, "taskScoreSlotMillis": 100,
    "refillIntervalMillis": 50, "refillRoundBudget": 1000, "recycleGroupBudget": 100,
    "resultBatchLimit": 100, "resultSuccessConcurrency": "6..10", "resultGlobalConcurrency": 10,
    "resultIdleIntervalMillis": 100, "itemClaimLeaseMillis": 5000, "producers": "single-flight",
    # Server delivery contract (DirectCallService.MAX_CONSUME_LIMIT): an Adapter consume above 100 is rejected
    # with HTTP 400, so the scenario profile's command-consume-limit 100 is already the maximum.
    "adapterCommandConsumeLimit": 100,
}


def lane_server_flags():
    flags = dict(LANE_BASE_FLAGS)
    flags["xa.mass.project-assembly.projects[0].project-id"] = LANE_PROJECT
    flags["xa.mass.worker-assembly.group-config-json"] = json.dumps({group: {
        "attributes": {"capability": "string-utils"}, "eventCodes": list(LANE_EVENTS)} for group in LANE_GROUPS})
    for index, group in enumerate(LANE_GROUPS):
        flags[f"xa.mass.project-assembly.projects[0].worker-group-ids[{index}]"] = group
        flags[f"xa.mass.worker-matching.groups[{group}].pools[0]"] = "any"
        flags[f"xa.mass.worker-matching.groups[{group}].functions[0]"] = "worker.any"
        flags[f"xa.mass.task-rpc.refill-by-worker-group[{group}][0]"] = json.dumps(
            {"poolName": "any", "target": {}, "count": LANE_ASSIGNMENT_BATCH_LIMIT})
    for audit in LANE_KNOB_AUDIT:
        if audit["disposition"] == "raised" and audit["knob"].startswith("xa.mass.") and "<" not in audit["knob"]:
            flags[audit["knob"]] = audit["laneValue"]
    return flags


def lane_host_groups():
    return {group: {"events": list(LANE_EVENTS), "count": LANE_WORKERS_PER_GROUP, "propertiesTemplate": {},
                    "newEnvironment": False, "requestTimeoutMillis": 60_000,
                    "reconnectPolicy": {"maxUnstableAttempts": 600, "reconnectIntervalMillis": 500,
                                        "stableConnectionDurationMillis": 10_000}} for group in LANE_GROUPS}


def resource_peaks(path):
    """Whole-run peaks per role, including startup and quiesce samples."""
    peaks = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if row["role"] == "redis":
            continue
        peak = peaks.setdefault(row["role"], {"samples": 0, "peakNativeThreads": 0, "peakFileDescriptors": 0, "peakRssBytes": 0})
        peak["samples"] += 1
        peak["peakNativeThreads"] = max(peak["peakNativeThreads"], row["nativeThreads"])
        peak["peakFileDescriptors"] = max(peak["peakFileDescriptors"], row["openFileDescriptors"])
        peak["peakRssBytes"] = max(peak["peakRssBytes"], row["rssBytes"])
    return peaks


def run_lane_harness(phase, harness_output, private, env, processes, sampler, deadline, *extra, accepted=("passed",)):
    """One bounded Harness phase while Server and Host must stay alive; returns its safe summary."""
    role = f"harness-{phase}-{len(processes)}"
    harness_output.mkdir(parents=True, exist_ok=True)
    processes[role] = start_process(["java", *JVM, "-cp", ROOT / "integrations/worker-call-performance/build/install/xa-mass-worker-call-performance/lib/*",
        "com.xa.mass.integration.workercallperformance.WorkerCallPerformanceMain", f"--phase={phase}",
        f"--output={harness_output}", *extra], private / f"{role}.log", env)
    sampler.register(role, processes[role])
    while processes[role].poll() is None:
        if time.monotonic() >= deadline or sampler.failure or any(processes[r].poll() is not None for r in ("server", "host")):
            raise RuntimeError(f"Deadline, resource evidence or process survival failed during {phase}")
        time.sleep(.25)
    path = harness_output / ("case.json" if phase == "case" else f"{phase}.json")
    phase_summary = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {"status": "failed"}
    if phase_summary.get("status") not in accepted:
        if phase == "case":
            return phase_summary
        raise RuntimeError(f"Lane {phase} failed; inspect {path.relative_to(harness_output.parents[1])}")
    return phase_summary


LANE_PATHS = ("task-any", "task-targeted", "direct")
LANE_SATURATION_PATHS = ("task-any", "task-targeted")
LANE_RATES = (500, 1000, 2000)
LANE_MODES = ("open", "saturation")
LANE_CASE_SECONDS = 180


def rotate(values, repetition):
    shift = (repetition - 1) % len(values)
    return values[shift:] + values[:shift]


def lane_plan(rates, repetitions, modes=LANE_MODES):
    """Open-loop cases run every path per rate on one host, then saturation cases; path order rotates per repetition."""
    plan = []
    for repetition in range(1, repetitions + 1):
        if "open" in modes:
            plan += [dict(repetition=repetition, mode="open", rate=rate, path=path, case=f"{path}-{rate}")
                     for rate in rates for path in rotate(LANE_PATHS, repetition)]
        if "saturation" in modes:
            plan += [dict(repetition=repetition, mode="saturation", rate=None, path=path, case=f"sat-{path}")
                     for path in rotate(LANE_SATURATION_PATHS, repetition)]
    return plan


def lane_entry(case, repetition=1):
    """Plan entry for one lane case name: path-rate (open) or sat-path (saturation)."""
    if case.startswith("sat-") and case[4:] in LANE_SATURATION_PATHS:
        return dict(repetition=repetition, mode="saturation", rate=None, path=case[4:], case=case)
    path, _, rate = case.rpartition("-")
    if path in LANE_PATHS and rate.isdigit() and int(rate) in LANE_RATES:
        return dict(repetition=repetition, mode="open", rate=int(rate), path=path, case=case)
    raise ValueError(f"Unknown lane case {case}")


def lane_warmup_case(rates, modes):
    """Job-level warmup: one discarded case at the job's highest open rate, or 1000/s for saturation-only jobs."""
    return f"task-any-{max(rates)}" if "open" in modes else "task-any-1000"


def calibrate_host(client, harness_output):
    """Stage 0: Redis round-trip and a fixed JVM CPU workload, measured before any lane process starts."""
    started = time.perf_counter()
    for _ in range(5000):
        client.ping()
    redis_micros = (time.perf_counter() - started) / 5000 * 1e6
    output = harness_output / "calibrate"
    command(["java", *JVM, "-cp", ROOT / "integrations/worker-call-performance/build/install/xa-mass-worker-call-performance/lib/*",
             "com.xa.mass.integration.workercallperformance.WorkerCallPerformanceMain", "--phase=calibrate",
             f"--output={output}"], timeout=120)
    calibration = json.loads((output / "calibrate.json").read_text(encoding="utf-8"))
    calibration["redisPingMicros"] = redis_micros
    return {key: value for key, value in calibration.items() if key not in ("phase", "status")}


def lane_window_cost(path, started, ended):
    """Server CPU and Redis command deltas across one case measurement window."""
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    result = {}
    for role, cpu in (("server", lambda row: row["cpuSeconds"]),
                      ("redis", lambda row: row["cpuUserSeconds"] + row["cpuSystemSeconds"])):
        window = [row for row in rows if row["role"] == role and started <= row["epochMillis"] <= ended]
        if len(window) < 2:
            return None
        first, last = window[0], window[-1]
        seconds = (last["epochMillis"] - first["epochMillis"]) / 1000
        result[role + "CpuSeconds"] = cpu(last) - cpu(first)
        result[role + "CoveredSeconds"] = seconds
        if role == "redis":
            result["redisCommands"] = last["totalCommandsProcessed"] - first["totalCommandsProcessed"]
    return result


def lane_case_record(entry, case, cost):
    metrics = case.get("metrics", {})
    record = {"repetition": entry["repetition"], "mode": entry["mode"], "rate": entry["rate"], "path": entry["path"],
              "case": entry["case"],
              "status": case.get("status", "failed"), "invalidReasons": case.get("invalidReasons", []),
              "failureReason": case.get("failureReason"), "fastFail": case.get("fastFail"),
              "measurementStartedEpochMillis": case.get("measurementStartedEpochMillis"),
              "measurementEndedEpochMillis": case.get("measurementEndedEpochMillis"),
              "completedPerSecond": case.get("completedPerSecond"),
              "admittedPerSecond": case.get("admittedPerSecond"),
              "completedPerSecondErrorBound": case.get("completedPerSecondErrorBound"),
              "heldLeasesAtWindowEnd": case.get("heldLeasesAtWindowEnd"),
              "perWorkerTurnaroundMillis": case.get("perWorkerTurnaroundMillis"),
              "workersBound": case.get("workersBound"),
              "seedMillis": case.get("seedMillis"),
              "leaseHeldPeakRatio": case.get("leaseHeldPeakRatio"),
              "successWithinWait": metrics.get("successRate"),
              "successfulCallLatencyMillis": metrics.get("successfulCallLatencyMillis"),
              "generatorLimited": metrics.get("generatorLimited"),
              "acceptedResultsAfterDrain": metrics.get("acceptedResultsAfterDrain"),
              "workerSamples": len(case.get("workerSamples", []))}
    completed = (case.get("completedPerSecond") or 0) * LANE_MEASUREMENT_SECONDS
    if cost:
        record["cost"] = dict(cost, redisCommandsPerCompleted=cost["redisCommands"] / completed if completed else None,
                              serverCpuMillisPerCompleted=1000 * cost["serverCpuSeconds"] / completed if completed else None)
    return record


def lane_path_ratios(records):
    """Task paths relative to Direct on the same host, rate and repetition; passed open-loop cases only."""
    records = [r for r in records if r.get("mode", "open") == "open"]
    ratios = []
    for repetition in sorted({r["repetition"] for r in records}):
        for rate in sorted({r["rate"] for r in records}):
            cell = {r["path"]: r for r in records if r["repetition"] == repetition and r["rate"] == rate and r["status"] == "passed"}
            direct = cell.get("direct")
            for path in ("task-any", "task-targeted"):
                task = cell.get(path)
                if not direct or not task:
                    continue
                dp, tp = direct["successfulCallLatencyMillis"]["p99"], task["successfulCallLatencyMillis"]["p99"]
                ratios.append({"repetition": repetition, "rate": rate, "path": path,
                               "p99LatencyRatio": tp / dp if dp else None,
                               "completionRatio": task["completedPerSecond"] / direct["completedPerSecond"]
                               if direct["completedPerSecond"] else None})
    return ratios


LANE_MEASUREMENT_SECONDS = 30


def attribute_lane(private, evidence, records):
    windows = [{"name": f"r{r['repetition']}/{r['case']}", "startEpochMillis": r["measurementStartedEpochMillis"],
                "endEpochMillis": r["measurementEndedEpochMillis"]} for r in records if r.get("measurementEndedEpochMillis")]
    recording = private / "server.jfr"
    if not windows or not recording.is_file():
        return {"complete": False, "reason": "no recording or no measured window"}
    write_json(private / "attribution-windows.json", windows)
    destination = evidence / "attribution.json"
    command(["java", "-Xmx512m", "-cp", MODULE / "build/install/xa-mass-worker-call-performance/lib/*",
             "com.xa.mass.integration.workercallperformance.LaneAttribution", recording,
             private / "attribution-windows.json", destination], timeout=120)
    result = json.loads(destination.read_text(encoding="utf-8"))
    for record in records:
        record["attribution"] = result["cases"].get(f"r{record['repetition']}/{record['case']}")
    return {key: result[key] for key in ("complete", "dataLoss", "dispatchEvents")}


def run_lane(root, output, deadline, plan, attribution=True, warmup_case=None, calibrate=True, diagnostics="off"):
    """Start the world once, then run each case between quiesce gates (README.md)."""
    import redis
    evidence = output / "evidence"
    private = output / "private"
    harness_output = evidence / "harness"
    for path in (evidence, private, harness_output):
        path.mkdir(parents=True)
    scope = "test_worker_call_performance_" + uuid.uuid4().hex[:16]
    container = client = sampler = None
    processes = {}
    cleanup_errors = []
    records = []
    result = {"lane": "performance", "status": "failed", "scope": scope, "plan": plan, "warmupCase": warmup_case,
              "attribution": attribution, "timingsMillis": {}, "cases": records}
    timings = result["timingsMillis"]
    started = time.monotonic()
    elapsed = lambda: int((time.monotonic() - started) * 1000)
    try:
        for port in (18082, 18083, 18086):
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(("127.0.0.1", port))
        container = command(["docker", "run", "--rm", "-d", "--label", f"xa-mass-proof={scope}",
                             "-p", "127.0.0.1::6379", REDIS_IMAGE])
        if not re.fullmatch(r"[0-9a-f]{64}", container):
            container = None
            raise RuntimeError("Docker did not return an exact container identity")
        port = int(command(["docker", "port", container, "6379/tcp"]).split(":")[-1])
        redis_url = f"redis://127.0.0.1:{port}/15"
        client = redis.Redis.from_url(redis_url, decode_responses=True, socket_timeout=2)
        for attempt in range(40):
            try:
                client.ping()
                break
            except redis.ConnectionError:
                if attempt == 39:
                    raise
                time.sleep(.25)
        if client.info("server")["redis_version"] != "7.4.10":
            raise RuntimeError("Unexpected Redis version")
        if calibrate:
            result["calibration"] = calibrate_host(client, harness_output)
            timings["calibrated"] = elapsed()
        sampler = Sampler(evidence / "process-resources.jsonl", client)
        sampler.thread.start()
        env = {k: v for k, v in os.environ.items() if not k.startswith(("XA_MASS_", "SPRING_"))
               and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        env.update(XA_MASS_REDIS_URL=redis_url, XA_MASS_REDIS_SCOPE=scope, XA_MASS_KERNEL_PACER_PRESET="DEFAULT")
        flags = lane_server_flags()
        flags.update({"xa.mass.redis.url": redis_url, "xa.mass.redis.scope": scope})
        if diagnostics == "jfr":
            # Optional servlet/executor observations; normal lane runs have no diagnostic beans.
            flags["xa.mass.diagnostics.enabled"] = "true"
        jars = [p for p in (root / server_distribution(root) / "build/libs").glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar")]
        jar = max(jars, key=lambda p: p.stat().st_mtime_ns)
        # Diagnostics mode records the complete diagnostics.jfc (a superset of the attribution events).
        recording = (jfr_options(private, "server", diagnostics) if diagnostics == "jfr" else
                     [f"-XX:StartFlightRecording=name=lane,settings={MODULE / 'lane-attribution.jfc'},"
                      f"filename={private / 'server.jfr'},maxsize=240m,dumponexit=true"] if attribution else [])
        write_json(evidence / "knob-audit.json", {"resources": list(LANE_KNOB_AUDIT), "mechanismConstants": LANE_MECHANISM_CONSTANTS})
        write_json(evidence / "effective-config.json", {"serverOverrides": flags, "jvmOptions": JVM, "serverRecording": recording,
            "groups": list(LANE_GROUPS), "workersPerGroup": LANE_WORKERS_PER_GROUP, "project": LANE_PROJECT,
            "assignmentBatchLimit": LANE_ASSIGNMENT_BATCH_LIMIT, "hostGroups": lane_host_groups(),
            "plan": plan, "warmupCase": warmup_case,
            "configurationSourceSha256": fingerprint(root), "configurationSources": configuration_sources(root),
            "sourceHead": command(["git", "rev-parse", "HEAD"], cwd=root), "redisImage": REDIS_IMAGE})
        write_json(evidence / "artifact-fingerprints.json", artifact_fingerprints(root, jar))
        processes["server"] = start_process(["java", *JVM, *recording, "-jar", jar,
                                             *(f"--{key}={value}" for key, value in flags.items())], private / "server.log", env)
        sampler.register("server", processes["server"])
        wait_http("http://127.0.0.1:18082/actuator/health/readiness", processes["server"], sampler, min(deadline, time.monotonic() + 180))
        timings["serverReady"] = elapsed()
        inventory = private / "data/scenario-workers"  # Simulator-owned root naming
        materialize_inventory(inventory, {group: tuple({"runtime": "java", "capability": "string-utils"}
                                                       for _ in range(LANE_WORKERS_PER_GROUP)) for group in LANE_GROUPS})
        config_path = private / "worker-simulator.json"
        write_json(config_path, {"runtimeApiBaseUrl": "http://127.0.0.1:18082", "sandboxRoot": str(inventory.resolve()),
                                 "controlPort": 18086, "workerGroups": lane_host_groups()})
        processes["host"] = start_process(["java", *JVM, *jfr_options(private, "host", diagnostics),
            "-cp", root / "worker_simulator_jvm/build/install/xa-mass-worker-simulator/lib/*",
            "com.xa.mass.workersimulator.WorkerSimulatorMain", "--config", str(config_path)], private / "host.log", env)
        sampler.register("host", processes["host"])
        wait_http("http://127.0.0.1:18086/lab/v1/workers", processes["host"], sampler, min(deadline, time.monotonic() + 180))
        timings["hostListening"] = elapsed()
        result["bootstrap"] = run_lane_harness("bootstrap", harness_output, private, env, processes, sampler, deadline)
        timings["bootstrapped"] = elapsed()
        world = harness_output / "bootstrap.json"
        warmup = [dict(lane_entry(warmup_case, 0), warmup=True)] if warmup_case else []
        for index, entry in enumerate(warmup + plan):
            case_output = harness_output / ("warmup" if entry.get("warmup") else f"r{entry['repetition']}") / entry["case"]
            try:
                run_lane_harness("quiesce", case_output, private, env, processes, sampler, deadline, f"--world={world}")
            except RuntimeError:
                # F6: a polluted world makes this and every later case incomparable.
                records.extend(dict(later, status="invalid", invalidReasons=["quiesce-timeout"])
                               for later in (warmup + plan)[index:] if not later.get("warmup"))
                break
            case = run_lane_harness("case", case_output, private, env, processes, sampler, deadline,
                                    f"--case={entry['case']}", f"--repetition={entry['repetition']}", f"--world={world}",
                                    accepted=("passed", "invalid", "saturated"))
            if entry.get("warmup"):
                # Discarded: it only brings the JIT and connection pools to the job's load before measurement.
                result["warmup"] = {"case": entry["case"], "status": case.get("status"),
                                    "p99LatencyMillis": (case.get("metrics", {}).get("successfulCallLatencyMillis") or {}).get("p99")}
                timings["warmedUp"] = elapsed()
                continue
            cost = None
            if case.get("measurementEndedEpochMillis"):
                cost = lane_window_cost(evidence / "process-resources.jsonl", case["measurementStartedEpochMillis"],
                                        case["measurementEndedEpochMillis"])
            records.append(lane_case_record(entry, case, cost))
            print("lane case " + json.dumps({k: records[-1][k] for k in ("repetition", "case", "status", "invalidReasons", "failureReason")}), flush=True)
        timings["casesCompleted"] = elapsed()
        if sampler.failure or any(sampler.counts[r] < 1 for r in ("server", "host", "redis")):
            raise RuntimeError("Resource evidence incomplete")
        result["status"] = "failed" if any(r["status"] == "failed" for r in records) else "passed"
    except Exception as error:
        result.update(status="failed", runnerFailure=type(error).__name__ + ": " + str(error))
    finally:
        if sampler:
            try:
                sampler.stop()
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
            if sampler.failure:
                result.update(status="failed", resourceFailure=sampler.failure)
            result["resourcePeaks"] = resource_peaks(evidence / "process-resources.jsonl")
        for process in reversed(tuple(processes.values())):
            try:
                stop_process(process)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if client:
            client.close()
        if container:
            try:
                command(["docker", "rm", "--force", container], timeout=20)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if (attribution or diagnostics == "jfr") and records:
            try:
                result["attribution"] = attribute_lane(private, evidence, records)
            except Exception as error:
                result["attribution"] = {"complete": False, "failureType": type(error).__name__}
        if diagnostics == "jfr":
            result["diagnostics"] = diagnose_lane(private, evidence, records)
        result["pathRatios"] = lane_path_ratios(records)
        result["caseCounts"] = dict(Counter(r["status"] for r in records))
        if cleanup_errors:
            result.update(status="failed", cleanupErrors=cleanup_errors)
        write_json(evidence / "lane-summary.json", result)
    return result


def load_history(directory):
    """Run records previously stored on the data branch; an absent directory is an empty history."""
    if not directory or not Path(directory).is_dir():
        return []
    return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(Path(directory).rglob("*.json"))]


def run_meta():
    """Identity of this workflow run for its data-branch record."""
    return {"runId": os.environ.get("GITHUB_RUN_ID", "local"), "attempt": os.environ.get("GITHUB_RUN_ATTEMPT", "1"),
            "ref": os.environ.get("GITHUB_REF_NAME") or command(["git", "rev-parse", "--abbrev-ref", "HEAD"]),
            "sha": os.environ.get("GITHUB_SHA") or command(["git", "rev-parse", "HEAD"]),
            "event": os.environ.get("GITHUB_EVENT_NAME", "local"),
            "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}


def merge_lane(evidence_root, jobs, history=None, meta=None):
    """Combine the per-job lane summaries of one workflow run; a missing or failed job fails the merge."""
    found = {}
    for path in sorted(Path(evidence_root).rglob("lane-summary.json")):
        # Downloaded artifact directories carry the workflow attempt: lane-<job>-<attempt>.
        job = next((name for name in jobs if any(re.fullmatch(rf"lane-{re.escape(name)}(-\d+)?", part)
                                                  for part in path.parts)), None)
        if job is None or job in found:
            raise RuntimeError(f"Unexpected or duplicate lane summary: {path}")
        found[job] = json.loads(path.read_text(encoding="utf-8"))
    cases, summaries = [], []
    for job in jobs:
        summary = found.get(job)
        if summary is None:
            summaries.append({"job": job, "status": "missing"})
            continue
        cases += [dict(case, job=job) for case in summary.get("cases", [])]
        summaries.append({"job": job, "status": summary["status"], "caseCounts": summary.get("caseCounts", {}),
                          "casesCompletedMillis": summary.get("timingsMillis", {}).get("casesCompleted"),
                          "runnerFailure": summary.get("runnerFailure"), "resourcePeaks": summary.get("resourcePeaks", {}),
                          "calibration": summary.get("calibration"), "warmup": summary.get("warmup")})
    status = "passed" if all(s["status"] == "passed" for s in summaries) else "failed"
    merged = {"lane": "performance", "status": status, "jobs": summaries, "cases": cases,
              "caseCounts": dict(Counter(case["status"] for case in cases)), "pathRatios": lane_path_ratios(cases)}
    if meta is not None:
        merged["record"] = lane_trend.run_record(merged, meta)
        merged["trend"] = lane_trend.compare(merged["record"], history or [])
    return merged


def lane_markdown(result):
    timings = result.get("timingsMillis", {})
    lines = ["# Performance Lane", "", f"Status: **{result['status']}**. Cases: {result.get('caseCounts', {})}.", "",
             f"{len(LANE_GROUPS)} Groups x {LANE_WORKERS_PER_GROUP} Workers, one Adapter, assignment ceiling "
             f"{LANE_ASSIGNMENT_BATCH_LIMIT}. Offered load splits evenly across the Groups.", "",
             "| Rep | Case | Status | Success within 1s | Completed/s | Success p50 / p99 ms | Checked Items/s | Shortfall | STALE | Lease-held peak | Redis cmds / completed |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in result.get("cases", []):
        latency = r.get("successfulCallLatencyMillis") or {}
        attribution = r.get("attribution") or {}
        cost = r.get("cost") or {}
        status = r["status"] + (f" ({', '.join(r['invalidReasons'])})" if r.get("invalidReasons") else "") \
            + (f" ({r['failureReason']})" if r.get("failureReason") else "") \
            + (f" (admitted {r['admittedPerSecond']:.0f}/s)" if r["status"] == "saturated" and r.get("admittedPerSecond") else "") \
            + (f" (turnaround {r['perWorkerTurnaroundMillis']:.0f} ms)" if isinstance(r.get("perWorkerTurnaroundMillis"), (int, float)) else "")
        pct = lambda value: f"{value:.2%}" if isinstance(value, (int, float)) else "—"
        num = lambda value, fmt: format(value, fmt) if isinstance(value, (int, float)) else "—"
        completed = num(r.get("completedPerSecond"), ".1f") + (
            f" ± {r['completedPerSecondErrorBound']:.1f}" if r.get("completedPerSecondErrorBound") is not None else "")
        lines.append(f"| {r['repetition']} | {r['case']} | {status} | {pct(r.get('successWithinWait'))} "
                     f"| {completed} | {num(latency.get('p50'), '.1f')} / {num(latency.get('p99'), '.1f')} "
                     f"| {num(attribution.get('checkedItemsPerSecond'), '.0f')} | {pct(attribution.get('candidateShortfallRatio'))} "
                     f"| {pct(attribution.get('strictAcquisitionStaleRatio'))} | {pct(r.get('leaseHeldPeakRatio'))} "
                     f"| {num(cost.get('redisCommandsPerCompleted'), '.1f')} |")
    if result.get("jobs"):
        lines += ["", "| Job | Status | Cases | Case time s | Host CPU ops/s (4 threads) | Redis ping µs | Warmup | Server peak threads / FDs |",
                  "| --- | --- | --- | --- | --- | --- | --- | --- |"]
        for job in result["jobs"]:
            server = job.get("resourcePeaks", {}).get("server", {})
            elapsed = job.get("casesCompletedMillis")
            calibration = job.get("calibration") or {}
            warmup = job.get("warmup") or {}
            speed = calibration.get("cpuParallelOpsPerSecond")
            ping = calibration.get("redisPingMicros")
            lines.append(f"| {job['job']} | {job['status']} | {job.get('caseCounts', {})} "
                         f"| {f'{elapsed / 1000:.0f}' if elapsed is not None else '—'} "
                         f"| {f'{speed:,.0f}' if speed else '—'} | {f'{ping:.0f}' if ping else '—'} "
                         f"| {warmup.get('case', '—')} {warmup.get('status', '')} "
                         f"| {server.get('peakNativeThreads', '—')} / {server.get('peakFileDescriptors', '—')} |")
    if result.get("trend"):
        trend = result["trend"]
        lines += ["", f"Trend: **{trend['status']}** against {trend['historyRuns']} comparable run(s) "
                  f"(needs {lane_trend.MIN_HISTORY} from the same ref and lane config version {lane_trend.LANE_CONFIG_VERSION})."]
        for job, host in trend.get("hosts", {}).items():
            factor = host.get("speedFactor")
            lines.append(f"- Host {job}: speed factor {factor:.2f}, {'qualified' if host['qualified'] else 'not judged'}"
                         if factor else f"- Host {job}: no calibration reference")
        suspects = [f for f in trend.get("findings", []) if f["verdict"] == "suspect"]
        lines += [f"- Suspect {f['case']} {f.get('metric', f.get('reason'))}: current {f.get('current')} "
                  f"vs history {f.get('historyMin', f.get('history'))}..{f.get('historyMax', '')}" for f in suspects]
        if trend["status"] == "compared" and not suspects:
            lines.append("- No suspect case.")
    if result.get("pathRatios"):
        lines += ["", "Task path relative to Direct on the same host (passed cases only):", "",
                  "| Rep | Rate | Path | p99 latency ratio | Completion ratio |", "| --- | --- | --- | --- | --- |"]
        lines += [f"| {x['repetition']} | {x['rate']} | {x['path']} | {x['p99LatencyRatio']:.2f} | {x['completionRatio']:.2f} |"
                  for x in result["pathRatios"] if x["p99LatencyRatio"] is not None and x["completionRatio"] is not None]
    if timings:
        lines += ["", "| Milestone | Elapsed s |", "| --- | --- |"]
        lines += [f"| {name} | {value / 1000:.1f} |" for name, value in timings.items()]
    peaks = {role: peak for role, peak in result.get("resourcePeaks", {}).items() if not role.startswith("harness-")}
    if peaks:
        lines += ["", "| Role | Peak threads | Peak FDs | Peak RSS MiB |", "| --- | --- | --- | --- |"]
        lines += [f"| {role} | {peak['peakNativeThreads']} | {peak['peakFileDescriptors']} | {peak['peakRssBytes'] / 2**20:.0f} |"
                  for role, peak in peaks.items()]
    if result.get("runnerFailure"):
        lines += ["", f"Failure: {result['runnerFailure']}"]
    lines += ["", "Saturation cases (sat-*) seed a deep backlog per Group and count Items completed within the window; "
              "the ± bound is the leases still held when the window closed. With a near-zero Handler every Worker is "
              "expected to stay busy, so the turnaround (Workers x window / completed) is the platform time of one "
              "Worker lease cycle.", ""]
    lines += ["Saturated means the offered rate exceeds the path capacity: responses outlasted their wait until "
              "arrivals met the in-flight cap; admitted/s is not a completion ceiling (the saturation mode measures that). "
              "Invalid means the generator lagged or a Group ran out of idle Workers; its numbers are not comparable. "
              "Failed means a correctness floor or fast-fail rule was violated. Performance values never fail the lane.", ""]
    return "\n".join(lines)


def jfr_options(private, role, diagnostics):
    if diagnostics == "off":
        return []
    # Leave a final-chunk margin below the independently checked 256 MiB file limit.
    return ["-XX:FlightRecorderOptions=maxchunksize=8m",
            f"-XX:StartFlightRecording=name=call-proof,settings={MODULE / 'diagnostics.jfc'},"
            f"filename={private / (role + '.jfr')},maxsize=240m,dumponexit=true"]


def export_diagnostics(private, evidence, started, seconds, call_path="DIRECT_CALL", roles=("server", "host")):
    """Bounded whitelist export of one measurement window from each role's private recording."""
    result = {}
    evidence.mkdir(parents=True, exist_ok=True)  # The reader creates only its file, never parent directories.
    for role in roles:
        recording = private / (role + ".jfr")
        destination = evidence / (role + "-diagnostics.json")
        try:
            if not recording.is_file() or recording.stat().st_size > 256 * 1024 * 1024:
                raise RuntimeError("Recording absent or above its fixed size bound")
            command(["java", "-Xmx512m", "-cp", MODULE / "build/install/xa-mass-worker-call-performance/lib/*",
                     "com.xa.mass.integration.workercallperformance.JfrDiagnostics", recording, destination,
                     str(int(started)), str(seconds), role, call_path], timeout=60)
            result[role] = json.loads(destination.read_text(encoding="utf-8"))
        except (OSError, RuntimeError, subprocess.SubprocessError, ValueError) as error:
            result[role] = {"complete": False, "failureType": type(error).__name__}
            write_json(destination, result[role])
    return {"complete": all(value.get("complete", False) for value in result.values()),
            "roles": {role: {k: v for k, v in value.items() if k not in ("buckets", "stacks")}
                      for role, value in result.items()}}


def diagnose_lane(private, evidence, records):
    """Per-case JFR diagnosis windows of the Server and Host recordings; incomplete exports stay explicit."""
    summary = {}
    for record in records:
        if not record.get("measurementEndedEpochMillis"):
            continue
        name = f"r{record['repetition']}-{record['case']}"
        seconds = max(1, min(120, round((record["measurementEndedEpochMillis"] - record["measurementStartedEpochMillis"]) / 1000)))
        record["diagnostics"] = export_diagnostics(private, evidence / "diagnostics" / name, record["measurementStartedEpochMillis"],
                                                   seconds, "DIRECT_CALL" if record["path"] == "direct" else "TASK")
        summary[name] = record["diagnostics"]["complete"]
    return {"complete": bool(summary) and all(summary.values()), "cases": summary}


def main():
    run_started = time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, default=ROOT / "build/worker-call-performance-proof")
    parser.add_argument("--repetitions", type=int, choices=(1, 3), default=1,
                        help="Repetitions per case with rotated path order")
    parser.add_argument("--lane-rates", type=lane_rates, help="Comma-separated subset of 500,1000,2000")
    parser.add_argument("--lane-modes", type=lane_modes, help="Comma-separated subset of open,saturation")
    parser.add_argument("--lane-attribution", choices=("on", "off"), default="on",
                        help="Server Dispatch Owner events for per-case attribution")
    parser.add_argument("--diagnostics", choices=("off", "jfr"), default="off",
                        help="Private bounded JFR of Server and Host with per-case export; never an A/B input")
    parser.add_argument("--baseline-ref", help="A/B only: immutable baseline commit compared on --lane-case")
    parser.add_argument("--lane-case", help="A/B only: the single case compared against --baseline-ref")
    parser.add_argument("--lane-history", type=Path, help="Directory of data-branch run records for trend and A/B bands")
    parser.add_argument("--merge-lane", type=Path, help="Merge the per-job lane evidence found below this directory")
    parser.add_argument("--merge-jobs", help="Comma-separated lane job names expected by --merge-lane")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--allow-nonreference-host", action="store_true", help="Local diagnostics only; no reference claim")
    options = parser.parse_args()
    if options.merge_lane:
        jobs = tuple(job for job in (options.merge_jobs or "").split(",") if job)
        if not jobs:
            parser.error("--merge-lane requires --merge-jobs")
        output = fresh_output(options.output_root)
        merged = merge_lane(options.merge_lane, jobs, load_history(options.lane_history), run_meta())
        write_json(output / "evidence/run-record.json", merged["record"])
        write_json(output / "evidence/lane-summary.json", merged)
        (output / "evidence/summary.md").write_text(lane_markdown(merged), encoding="utf-8")
        print(json.dumps({"status": merged["status"], "cases": merged["caseCounts"]}), flush=True)
        return 0 if merged["status"] == "passed" else 1
    if bool(options.baseline_ref) != bool(options.lane_case):
        parser.error("Lane A/B needs both --baseline-ref and --lane-case")
    if options.lane_case:
        try:
            lane_entry(options.lane_case)
        except ValueError as error:
            parser.error(str(error))
        if options.lane_rates or options.lane_modes or options.repetitions != 1 or options.diagnostics != "off":
            parser.error("A/B runs exactly one case; rates, modes, repetitions and diagnostics do not apply")
    if sys.platform != "linux":
        parser.error("Linux with Docker and /proc is required")
    os_release = platform.freedesktop_os_release()
    reference = os_release.get("ID") == "ubuntu" and os_release.get("VERSION_ID") == "24.04" and os.cpu_count() == 4
    if not reference and not options.allow_nonreference_host:
        parser.error("Reference environment is Ubuntu 24.04 with 4 CPUs; use --allow-nonreference-host only for diagnostics")
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr
    if not re.search(r'version "21[.\"]', java):
        parser.error("Java 21 is required")
    command(["docker", "info", "--format", "{{.ServerVersion}}"])
    try:
        image_id = command(["docker", "image", "inspect", REDIS_IMAGE, "--format", "{{.Id}}"])
    except subprocess.CalledProcessError:
        command(["docker", "pull", REDIS_IMAGE])
        image_id = command(["docker", "image", "inspect", REDIS_IMAGE, "--format", "{{.Id}}"])
    output = fresh_output(options.output_root)
    if options.lane_case:
        return main_lane_ab(options, output, run_started, reference)
    return main_lane(options, output, run_started, reference, os_release, java, image_id)


def main_lane(options, output, run_started, reference, os_release, java, image_id):
    if not options.skip_build:
        build(ROOT, harness=True)
    rates = options.lane_rates or LANE_RATES
    modes = options.lane_modes or LANE_MODES
    plan = lane_plan(rates, options.repetitions, modes)
    result = run_lane(ROOT, output, run_started + LANE_WORLD_SECONDS + (len(plan) + 1) * LANE_CASE_SECONDS,
                      plan, options.lane_attribution == "on", lane_warmup_case(rates, modes), diagnostics=options.diagnostics)
    result.update(referenceHost=reference, os=os_release, java=java.strip(), cpuCount=os.cpu_count(),
                  machine=platform.machine(), kernel=platform.release(), redisImageId=image_id,
                  harnessCommit=command(["git", "rev-parse", "HEAD"]),
                  worktreeDirty=bool(command(["git", "status", "--porcelain"])))
    write_json(output / "evidence/lane-summary.json", result)
    (output / "evidence/summary.md").write_text(lane_markdown(result), encoding="utf-8")
    print(json.dumps({"status": result["status"], "cases": result.get("caseCounts"),
                      "evidence": str(output / "evidence/lane-summary.json")}), flush=True)
    return 0 if result["status"] == "passed" else 1


def main_lane_ab(options, output, run_started, reference):
    """Per-case sequential A/B on one host: ABBA-ordered pairs until the history band decides (2..5 pairs)."""
    case = options.lane_case
    metric = lane_trend.primary_metric(case)
    extract = lane_trend.METRICS[metric][0]
    history = load_history(options.lane_history)
    band = lane_trend.band(case, history, "main")
    versions = {"B": command(["git", "rev-parse", "HEAD"]),
                "A": command(["git", "rev-parse", "--verify", options.baseline_ref + "^{commit}"])}
    baseline = output / "baseline-checkout"
    final = {"lane": "performance-ab", "status": "failed", "case": case, "metric": metric, "band": band,
             "versions": versions, "referenceHost": reference, "pairs": []}
    try:
        command(["git", "worktree", "add", "--detach", baseline, versions["A"]])
        build(baseline)
        if not options.skip_build:
            build(ROOT, harness=True)
        roots = {"A": baseline, "B": ROOT}
        warmup = case if not case.startswith("sat-") else "task-any-1000"
        deadline = run_started + 110 * 60
        values = []
        latencies = {}
        for pair in range(lane_trend.AB_MAX_PAIRS):
            order = ("A", "B") if pair % 2 == 0 else ("B", "A")
            observed = {}
            for version in order:
                print(f"lane ab pair={pair + 1} version={version} case={case}", flush=True)
                run = run_lane(roots[version], output / f"pair-{pair + 1}" / version, deadline,
                               [lane_entry(case, pair + 1)], attribution=False, warmup_case=warmup, calibrate=False)
                row = next(iter(run.get("cases", [])), {})
                observed[version] = extract(row) if row.get("status") == "passed" else None
                final["pairs"].append({"pair": pair + 1, "version": version, "runStatus": run["status"],
                                       "caseStatus": row.get("status"), metric: extract(row)})
                if run["status"] != "passed":
                    raise RuntimeError(f"Lane run failed for version {version} in pair {pair + 1}")
                latencies.setdefault(version, []).extend(successful_latencies(
                    output / f"pair-{pair + 1}" / version / "evidence/harness" / f"r{pair + 1}" / case / "samples.jsonl"))
            values.append((observed["A"], observed["B"]))
            final["decision"] = lane_trend.ab_decide(case, values, band["relative"])
            print("lane ab decision " + json.dumps(final["decision"]), flush=True)
            if final["decision"]["decision"] != "continue":
                break
        # Secondary, never decisive: tail latency over every pair's samples of each version.
        final["pooledP99LatencyMillis"] = {version: lane_trend.pooled_percentile(values, .99)
                                           for version, values in latencies.items()}
        final["status"] = "passed"
    except Exception as error:
        final.update(status="failed", failure=type(error).__name__ + ": " + str(error))
    finally:
        if baseline.exists():
            try:
                command(["git", "worktree", "remove", "--force", baseline], timeout=30)
            except subprocess.CalledProcessError:
                final["baselineCheckoutRetained"] = True
        write_json(output / "evidence/lane-ab-summary.json", final)
        (output / "evidence/summary.md").write_text(lane_ab_markdown(final), encoding="utf-8")
    print(json.dumps({"status": final["status"], "decision": final.get("decision", {}).get("decision")}), flush=True)
    return 0 if final["status"] == "passed" else 1


def successful_latencies(path):
    """Planned-arrival-to-response latency of successful calls; the same basis as successfulCallLatencyMillis."""
    if not path.is_file():
        return []
    latencies = []
    for line in path.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if row.get("outcome") == "succeeded" and row.get("endedOffsetMillis", -1) >= 0:
            latencies.append(row["endedOffsetMillis"] - row["plannedOffsetMillis"])
    return latencies


def lane_ab_markdown(final):
    band = final.get("band", {})
    decision = final.get("decision", {})
    band_source = "provisional" if band.get("provisional") else f"from {band.get('runs')} runs"
    lines = ["# Performance Lane A/B", "",
             f"Status: **{final['status']}**. Case `{final['case']}`, metric `{final['metric']}`. "
             f"Decision: **{decision.get('decision', 'none')}**.", "",
             f"A = `{final['versions']['A'][:12]}`, B = `{final['versions']['B'][:12]}`. Band ±{band.get('relative', 0):.1%} "
             f"({band_source}).", "",
             "| Pair | Version | Run | Case status | Value |", "| --- | --- | --- | --- | --- |"]
    for row in final.get("pairs", []):
        value = row.get(final["metric"])
        lines.append(f"| {row['pair']} | {row['version']} | {row['runStatus']} | {row.get('caseStatus')} "
                     f"| {f'{value:.1f}' if isinstance(value, (int, float)) else '—'} |")
    if decision.get("deltas"):
        lines += ["", "Per-pair change of B against A (positive is worse): "
                  + ", ".join(f"{delta:+.1%}" for delta in decision["deltas"])]
    pooled = final.get("pooledP99LatencyMillis") or {}
    if pooled.get("A") is not None and pooled.get("B") is not None:
        lines += ["", f"Pooled p99 over all pairs (secondary, not decisive): A {pooled['A']:.1f} ms, B {pooled['B']:.1f} ms."]
    if final.get("failure"):
        lines += ["", f"Failure: {final['failure']}"]
    lines += ["", "A decision needs at least two pairs and every comparable pair must agree. "
              "No difference within the band is not a speedup claim.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
