#!/usr/bin/env python3
"""Performance lane runner (README.md): processes, evidence and comparison; assertions live in the Java Harness."""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
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
if (ROOT / "acceptance/performance/harness").is_dir():
    sys.path.insert(0, str(ROOT / "acceptance"))
    from support.scenario_inventory import materialize_inventory
else:
    sys.path.insert(0, str(ROOT))
    from integrations.worker_proof_support.scenario_inventory import materialize_inventory
sys.path.insert(0, str(Path(__file__).resolve().parent))
import lane_trend  # noqa: E402  (sibling module; this directory name is not a package)

REDIS_IMAGE = "redis:7.4.10"
JVM = ("-Xms256m", "-Xmx1g", "-XX:+ExitOnOutOfMemoryError")
MODULE = ROOT / "integrations/worker-call-performance"
# Server settings shared by every lane world; lane_server_flags adds the Groups, Project and raised resources.
LANE_BASE_FLAGS = {
    "spring.profiles.active": "default",
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


def fresh_output(path, *, deployment=False):
    path = path.resolve()
    build = (ROOT / "build").resolve()
    if deployment and (ROOT / "acceptance/performance/harness").is_dir() and path.is_relative_to(ROOT.resolve()):
        raise ValueError("Deployment output must be outside the immutable Simulator kit")
    if path.exists() or (not deployment and (not path.is_relative_to(build) or path == build)):
        raise ValueError("Output must be a fresh directory" + ("" if deployment else " below repository build"))
    path.mkdir(parents=True, mode=0o700)
    return path


def harness_classpath(root=None):
    root = ROOT if root is None else root
    packaged = root / "acceptance/performance/harness/lib"
    return packaged / "*" if packaged.is_dir() else root / "integrations/worker-call-performance/build/install/xa-mass-worker-call-performance/lib/*"


def host_classpath(root=None):
    root = ROOT if root is None else root
    return root / "lib/*" if (root / "acceptance/performance/harness").is_dir() else root / "worker_simulator_jvm/build/install/xa-mass-worker-simulator/lib/*"


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


TCP_COUNTERS = ("RetransSegs", "OutSegs", "InErrs")
TCP_EXT_COUNTERS = ("TCPTimeouts", "TCPLossProbes", "TCPLostRetransmit", "TCPFastRetrans", "TCPSlowStartRetrans",
                    "TCPSpuriousRTOs", "TCPBacklogDrop", "TCPRcvQDrop", "TCPZeroWindowDrop", "ListenOverflows",
                    "ListenDrops", "TCPDelivered")


def proc_counters(text, prefix, names):
    """Named counters from /proc/net/snmp or netstat header/value line pairs; absent names are omitted."""
    lines = [line.split() for line in text.splitlines() if line.startswith(prefix + ":")]
    counters = {}
    for header, values in zip(lines[::2], lines[1::2]):
        counters.update({name: int(value) for name, value in zip(header[1:], values[1:]) if name in names})
    return counters


def network_sample():
    """Host-wide TCP retransmission/drop counters and loopback/softnet drops: kernel totals only."""
    sample = {}
    try:
        sample["tcp"] = proc_counters(Path("/proc/net/snmp").read_text(), "Tcp", TCP_COUNTERS)
        sample["tcpExt"] = proc_counters(Path("/proc/net/netstat").read_text(), "TcpExt", TCP_EXT_COUNTERS)
        rows = [line.split() for line in Path("/proc/net/softnet_stat").read_text().splitlines() if line.strip()]
        sample["softnet"] = {"dropped": sum(int(row[1], 16) for row in rows),
                             "timeSqueeze": sum(int(row[2], 16) for row in rows)}
        for line in Path("/proc/net/dev").read_text().splitlines():
            name, _, values = line.partition(":")
            if name.strip() == "lo":
                fields = values.split()
                sample["loopback"] = {"rxDrop": int(fields[3]), "txDrop": int(fields[11])}
    except (OSError, ValueError, IndexError) as error:
        sample["failureType"] = type(error).__name__
    return sample


def machine_sample():
    """Host-wide cumulative CPU jiffies (including steal), run-queue length and network drop counters."""
    fields = ("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal")
    sample = {"epochMillis": int(time.time() * 1000)}
    for line in Path("/proc/stat").read_text().splitlines():
        parts = line.split()
        if parts and parts[0] == "cpu":
            sample["cpuJiffies"] = dict(zip(fields, map(int, parts[1:1 + len(fields)])))
        elif parts and parts[0] == "procs_running":
            sample["procsRunning"] = int(parts[1])
    sample["loadAverage1"] = float(Path("/proc/loadavg").read_text().split()[0])
    sample["network"] = network_sample()
    return sample


REDIS_SLOW_MICROS = 5000
REDIS_SLOWLOG_ENTRIES = 4096


def enable_redis_latency(client):
    """Diagnostics only: the disposable container logs commands and latency events above 5ms."""
    client.config_set("slowlog-log-slower-than", REDIS_SLOW_MICROS)
    client.config_set("slowlog-max-len", REDIS_SLOWLOG_ENTRIES)
    client.config_set("latency-monitor-threshold", REDIS_SLOW_MICROS // 1000)
    client.slowlog_reset()
    client.execute_command("LATENCY", "RESET")


def redis_command_name(command):
    """The first token only; redis-py may return the logged command as bytes even with decoded responses."""
    if isinstance(command, bytes):
        command = command.decode("utf-8", "replace")
    return str(command).split(" ", 1)[0].upper()[:32]


def redis_latency(client):
    """Command names, durations and times only: keys, arguments and values never leave Redis."""
    entries = client.slowlog_get(REDIS_SLOWLOG_ENTRIES)
    commands = sorted((dict(epochSeconds=int(entry["start_time"]), durationMicros=int(entry["duration"]),
                            command=redis_command_name(entry["command"])) for entry in entries),
                      key=lambda row: row["epochSeconds"])
    history = {}
    for row in client.execute_command("LATENCY", "LATEST"):
        event = str(row[0])[:32]
        history[event] = [[int(at), int(millis)] for at, millis in client.execute_command("LATENCY", "HISTORY", row[0])]
    return {"complete": True, "slowLogThresholdMicros": REDIS_SLOW_MICROS,
            "latencyThresholdMillis": REDIS_SLOW_MICROS // 1000,
            "possiblyTruncated": len(entries) >= REDIS_SLOWLOG_ENTRIES,
            "meaning": "Redis execution time only, not client queueing or network; times are Redis epoch seconds.",
            "slowCommands": commands, "latencyHistory": history}


def write_redis_latency(client, destination):
    """Diagnostics evidence never changes a case outcome; a read failure is recorded as incomplete."""
    try:
        evidence = redis_latency(client)
    except Exception as error:
        evidence = {"complete": False, "failureType": type(error).__name__}
    destination.parent.mkdir(parents=True, exist_ok=True)
    write_json(destination, evidence)
    return evidence


class Sampler:
    def __init__(self, path, redis_client, fd_limit=8192, thread_limit=512, watch_swap=False, machine_path=None):
        self.path, self.redis = path, redis_client
        self.machine_path = machine_path
        self.processes = {}
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.failure = None
        self.counts = Counter()
        self.previous = {}
        self.fd_limit, self.thread_limit = fd_limit, thread_limit
        self.swap_start = swap_counters() if watch_swap else None
        self.thread = threading.Thread(target=self.run, name="performance-resource-sampler", daemon=True)

    def register(self, role, process):
        with self.lock:
            self.processes[role] = process

    def run(self):
        try:
            machine = self.machine_path.open("x", encoding="utf-8") if self.machine_path else None
            with self.path.open("x", encoding="utf-8") as output:
                while not self.stopped.is_set():
                    if machine:
                        machine.write(json.dumps(machine_sample()) + "\n")
                        machine.flush()
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
                        if sample["nativeThreads"] >= self.thread_limit or sample["openFileDescriptors"] >= self.fd_limit:
                            raise RuntimeError(f"Resource ceiling exceeded for {role}")
                    if self.swap_start is not None and swap_counters() != self.swap_start:
                        raise RuntimeError("WSL swap activity during experiment")
                    # Aggregate diagnostics only: no domain keys, command arguments or payloads.
                    if self.redis is not None:
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
            if machine:
                machine.close()
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


def swap_counters():
    values = dict(line.split() for line in Path("/proc/vmstat").read_text().splitlines())
    return (int(values["pswpin"]), int(values["pswpout"]))


def start_process(args, path, env, cpu_ids=None, fd_limit=None):
    prefix = []
    if fd_limit:
        prefix += ["prlimit", f"--nofile={fd_limit}:{fd_limit}", "--"]
    if cpu_ids:
        prefix += ["taskset", "--cpu-list", ",".join(map(str, cpu_ids))]
    with path.open("x", encoding="utf-8") as output:
        return subprocess.Popen([*prefix, *(str(x) for x in args)], cwd=ROOT, env=env,
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
    if (root / "experiment-manifest.json").is_file():
        return "server_boot_jvm"
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


PLATFORM_FIXTURE = "integrations/worker_proof_support/platform-runtime.yaml"


def fingerprint(root):
    current = ("server_boot_jvm/src/main/resources/application.yaml", PLATFORM_FIXTURE)
    if (root / PLATFORM_FIXTURE).is_file():
        if not all((root / path).is_file() for path in current):
            raise RuntimeError("Incomplete Server configuration group")
        return {path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in current}
    if root.resolve() == ROOT.resolve():
        raise RuntimeError("Server configuration group is missing")
    # Historical benchmark checkouts retain their exact versioned deployment inputs.
    for directory in ("server_boot_jvm/src/main/resources", "spring_server_jvm/src/main/resources", "server_jvm/src/main/resources"):
        files = tuple(f"{directory}/{name}" for name in ("application.yaml", "application-scenario-workers.yaml"))
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
    "resultIdleIntervalMillis": "10..100", "itemClaimLeaseMillis": 5000, "producers": "single-flight",
    # The scenario profile's command-consume-limit; the lane measures it and never raises it.
    "adapterCommandConsumeLimit": 500,
}


def lane_server_flags(root=None):
    root = ROOT if root is None else root
    inputs = fingerprint(root)
    flags = dict(LANE_BASE_FLAGS)
    if PLATFORM_FIXTURE in inputs:
        flags["spring.config.additional-location"] = (root / PLATFORM_FIXTURE).resolve().as_uri()
    else:
        flags["spring.profiles.active"] = "scenario-workers"  # Explicit historical benchmark only.
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


def lane_host_groups(workers=LANE_WORKERS_PER_GROUP):
    return {group: {"events": list(LANE_EVENTS), "count": workers, "propertiesTemplate": {},
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


def run_lane_harness(phase, harness_output, private, env, processes, sampler, deadline, *extra, accepted=("passed",), deployment=None, lab_url=None, java_options=()):
    """One bounded Harness phase while Server and Host must stay alive; returns its safe summary."""
    role = f"harness-{phase}-{len(processes)}"
    harness_output.mkdir(parents=True, exist_ok=True)
    if deployment is not None:
        extra = (*extra, f"--deployment-config={deployment}", f"--lab-url={lab_url}")
    processes[role] = start_process(["java", *JVM, *java_options, "-cp", harness_classpath(),
        "com.xa.mass.integration.workercallperformance.WorkerCallPerformanceMain", f"--phase={phase}",
        f"--output={harness_output}", *extra], private / f"{role}.log", env)
    sampler.register(role, processes[role])
    while processes[role].poll() is None:
        if time.monotonic() >= deadline or sampler.failure or any(processes[r].poll() is not None for r in ("server", "host") if r in processes):
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


def calibrate_host(client, harness_output, cpu_ids=None):
    """Stage 0: Redis round-trip and a fixed JVM CPU workload, measured before any lane process starts."""
    started = time.perf_counter()
    for _ in range(5000):
        client.ping()
    redis_micros = (time.perf_counter() - started) / 5000 * 1e6
    output = harness_output / "calibrate"
    affinity = ["taskset", "--cpu-list", ",".join(map(str, cpu_ids))] if cpu_ids is not None else []
    command([*affinity, "java", *JVM, "-cp", ROOT / "integrations/worker-call-performance/build/install/xa-mass-worker-call-performance/lib/*",
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
              "completedCount": case.get("completedCount"),
              "workersBound": case.get("workersBound"),
              "seedMillis": case.get("seedMillis"),
              "leaseHeldPeakRatio": case.get("leaseHeldPeakRatio"),
              "successWithinWait": metrics.get("successRate"),
              "successfulCallLatencyMillis": metrics.get("successfulCallLatencyMillis"),
              "generatorLimited": metrics.get("generatorLimited"),
              "httpVersions": metrics.get("httpVersions", {}),
              "acceptedResultsAfterDrain": metrics.get("acceptedResultsAfterDrain"),
              "workerSamples": len(case.get("workerSamples", []))}
    if cost:
        # An eventual drain count is not a count inside the sampled resource interval.
        record["cost"] = dict(cost, redisCommandsPerCompleted=None, serverCpuMillisPerCompleted=None)
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


def run_lane_cases(output, deadline, plan, warmup_case, env, processes, sampler, result, *, deployment=None, lab_url=None, java_options=(), started=None):
    """The same warmup, quiet gates, cases and records for both process compositions."""
    evidence, private = output / "evidence", output / "private"
    harness_output = evidence / "harness"
    world = harness_output / "bootstrap.json"
    records = result["cases"]
    warmup = [dict(lane_entry(warmup_case, 0), warmup=True)] if warmup_case else []
    phase_options = dict(deployment=deployment, lab_url=lab_url, java_options=java_options)
    for index, entry in enumerate(warmup + plan):
        case_output = harness_output / ("warmup" if entry.get("warmup") else f"r{entry['repetition']}") / entry["case"]
        try:
            run_lane_harness("quiesce", case_output, private, env, processes, sampler, deadline,
                             f"--world={world}", **phase_options)
        except RuntimeError:
            records.extend(dict(later, status="invalid", invalidReasons=["quiesce-timeout"])
                           for later in (warmup + plan)[index:] if not later.get("warmup"))
            break
        case = run_lane_harness("case", case_output, private, env, processes, sampler, deadline,
                               f"--case={entry['case']}", f"--repetition={entry['repetition']}", f"--world={world}",
                               accepted=("passed", "invalid", "saturated"), **phase_options)
        if entry.get("warmup"):
            result["warmup"] = {"case": entry["case"], "status": case.get("status"),
                                "p99LatencyMillis": (case.get("metrics", {}).get("successfulCallLatencyMillis") or {}).get("p99")}
            if started is not None:
                result["timingsMillis"]["warmedUp"] = int((time.monotonic() - started) * 1000)
            continue
        cost = None
        if case.get("measurementEndedEpochMillis"):
            cost = lane_window_cost(evidence / "process-resources.jsonl", case["measurementStartedEpochMillis"],
                                    case["measurementEndedEpochMillis"])
        records.append(lane_case_record(entry, case, cost))
        print("lane case " + json.dumps({k: records[-1][k] for k in ("repetition", "case", "status", "invalidReasons", "failureReason")}), flush=True)


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
    capacity_recording = any(entry.get("mode") == "saturation" for entry in plan)
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
        if diagnostics == "jfr":
            enable_redis_latency(client)
            (evidence / "diagnostics").mkdir(parents=True, exist_ok=True)
        sampler = Sampler(evidence / "process-resources.jsonl", client,
                          machine_path=evidence / "diagnostics/machine-cpu.jsonl" if diagnostics == "jfr" else None)
        sampler.thread.start()
        env = {k: v for k, v in os.environ.items() if not k.startswith(("XA_MASS_", "SPRING_"))
               and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        env.update(XA_MASS_REDIS_URL=redis_url, XA_MASS_REDIS_SCOPE=scope, XA_MASS_KERNEL_PACER_PRESET="DEFAULT")
        flags = lane_server_flags(root)
        flags.update({"xa.mass.redis.url": redis_url, "xa.mass.redis.scope": scope})
        if diagnostics == "jfr":
            # Optional servlet/executor observations; normal lane runs have no diagnostic beans.
            flags["xa.mass.diagnostics.enabled"] = "true"
        jars = [p for p in (root / server_distribution(root) / "build/libs").glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar")]
        jar = max(jars, key=lambda p: p.stat().st_mtime_ns)
        # Diagnostics mode records the complete diagnostics.jfc (a superset of the attribution events).
        recording = (jfr_options(private, "server", diagnostics) if diagnostics == "jfr" else
                     [f"-XX:StartFlightRecording=name=lane,settings={MODULE / 'lane-attribution.jfc'},"
                      f"filename={private / 'server.jfr'},maxsize=240m,dumponexit=true"] if attribution or capacity_recording else [])
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
        run_lane_cases(output, deadline, plan, warmup_case, env, processes, sampler, result, started=started)
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
        if client and diagnostics == "jfr":
            write_redis_latency(client, evidence / "diagnostics/redis-latency.json")
        if client:
            client.close()
        if container:
            try:
                command(["docker", "rm", "--force", container], timeout=20)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if (attribution or capacity_recording or diagnostics == "jfr") and records:
            try:
                result["attribution"] = attribute_lane(private, evidence, records)
            except Exception as error:
                result["attribution"] = {"complete": False, "failureType": type(error).__name__}
        for record in records:
            if record.get("mode") != "saturation" or record.get("status") not in ("passed", "invalid"):
                continue
            case_folder = harness_output / f"r{record['repetition']}" / record["case"]
            if not (case_folder / "case.json").is_file():
                continue
            try:
                command(["java", "-Xmx1g", "-cp", MODULE / "build/install/xa-mass-worker-call-performance/lib/*",
                         "com.xa.mass.integration.workercallperformance.WorkerCallPerformanceMain",
                         "--phase=experiment-analyze", f"--output={case_folder}",
                         f"--input={case_folder / 'case.json'}", f"--recording={private / 'server.jfr'}",
                         f"--resources={evidence / 'process-resources.jsonl'}"], timeout=180)
                measured = json.loads((case_folder / "capacity-case.json").read_text())
                record.update({key: value for key, value in measured.items() if key != "workerSamples"})
                costs = measured.get("resourceCosts", {})
                record["cost"] = dict(redisCommandsPerCompleted=costs.get("redis", {}).get("commandsPerCompleted"),
                                      serverCpuMillisPerCompleted=costs.get("server", {}).get("cpuMillisPerCompleted"))
            except Exception as error:
                record.update(status="invalid", evidenceStatus="incomplete",
                              invalidReasons=[*record.get("invalidReasons", []), "capacity-analysis-" + type(error).__name__])
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


def resolve_saturation_config(evidence, config_path):
    """Java-validated purpose=lane configuration, its single profile and the CPUs it runs on."""
    import capacity_experiment
    command(capacity_experiment.harness_command(sys.modules[__name__], "experiment-config", evidence, config_path.resolve()))
    resolved = evidence / "experiment.json"
    config = json.loads(resolved.read_text(encoding="utf-8"))
    if config["purpose"] != "lane":
        raise ValueError("The CI saturation lane requires a purpose=lane configuration")
    profile = config["profiles"][0]
    available = sorted(os.sched_getaffinity(0))
    if profile["cpuCount"] > len(available):
        raise ValueError("Insufficient CPUs for the saturation configuration")
    return resolved, config, profile, available[:profile["cpuCount"]]


def run_saturation_lane(output, plan, config_path, diagnostics="off"):
    """Reuse the packaged experiment lifecycle with one Java-validated CI profile; no QPS calculation."""
    import capacity_experiment
    evidence = output / "evidence"
    evidence.mkdir()
    resolved, config, profile, cpu_ids = resolve_saturation_config(evidence, config_path)
    manifest = capacity_experiment.verify_bundle(ROOT)
    config_id = hashlib.sha256(json.dumps(config, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    result = dict(lane="performance", status="failed", plan=plan, cases=[], experiment=config,
                  configurationId=config_id, workersPerGroup=profile["workersPerGroup"], cpuIds=cpu_ids,
                  timingsMillis={}, resourcePeaks={})
    if manifest is not None:
        result["artifactManifest"] = manifest
    write_json(evidence / "effective-config.json", dict(experiment=config, cpuIds=cpu_ids, configurationId=config_id))
    jar = next(p for p in (ROOT / "server_boot_jvm/build/libs").glob("*.jar") if not p.name.endswith("-plain.jar"))
    write_json(evidence / "artifact-fingerprints.json", artifact_fingerprints(ROOT, jar))
    write_json(evidence / "knob-audit.json", dict(resources=config["resources"], workersPerGroup=profile["workersPerGroup"],
        mechanismConstants=LANE_MECHANISM_CONSTANTS, meminfo=Path("/proc/meminfo").read_text()))
    started = time.monotonic()
    case_roots = output / "cases"
    case_roots.mkdir()
    for entry in plan:
        root = case_roots / f"r{entry['repetition']}-{entry['case']}"
        case = capacity_experiment.run_case(sys.modules[__name__], root, resolved, config, profile,
                                            "screening", entry["path"], entry["repetition"], cpu_ids, diagnostics)
        record = lane_case_record(entry, case, None)
        record.update({key: value for key, value in case.items() if key != "workerSamples"})
        costs = case.get("resourceCosts", {})
        record["cost"] = dict(redisCommandsPerCompleted=costs.get("redis", {}).get("commandsPerCompleted"),
                              serverCpuMillisPerCompleted=costs.get("server", {}).get("cpuMillisPerCompleted"))
        result["cases"].append(record)
        if case.get("calibration"):
            result["calibration"] = case["calibration"]
        for role, peak in case.get("resourcePeaks", {}).items():
            current = result["resourcePeaks"].setdefault(role, {})
            for name, value in peak.items():
                current[name] = max(current.get(name, 0), value)
        # Only the existing public evidence subtree is published; JFR, logs and inventory stay
        # outside evidence even though every repetition now has its own world and recording.
        destination = evidence / "harness" / f"r{entry['repetition']}" / entry["case"]
        shutil.copytree(root / "evidence", destination)
        write_json(destination / "result.json", case)
        if case.get("status") != "passed":
            result["runnerFailure"] = "Saturation configuration or evidence invalid; remaining cases not run"
            break
    result["timingsMillis"]["casesCompleted"] = int((time.monotonic() - started) * 1000)
    result["caseCounts"] = dict(Counter(case["status"] for case in result["cases"]))
    if len(result["cases"]) == len(plan) and all(case["status"] == "passed" for case in result["cases"]):
        result["status"] = "passed"
    write_json(evidence / "lane-summary.json", result)
    return result


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
                          "calibration": summary.get("calibration"), "warmup": summary.get("warmup"),
                          "experiment": summary.get("experiment"), "configurationId": summary.get("configurationId"),
                          "cpuCount": summary.get("cpuCount"),
                          "referenceHost": summary.get("referenceHost", False),
                          "workersPerGroup": summary.get("workersPerGroup", LANE_WORKERS_PER_GROUP)})
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
             f"{len(result.get('groups', LANE_GROUPS))} Groups, one Adapter, assignment ceiling "
             f"{result.get('assignmentBatchLimit', LANE_ASSIGNMENT_BATCH_LIMIT)}. Offered load splits evenly across the Groups.", "",
             "| Rep | Case | Status | HTTP success ratio | Completed/s | Success p50 / p99 ms | Checked Items/s | Shortfall | Acquisition rejected | Lease-held peak | Redis cmds / completed |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in result.get("cases", []):
        latency = r.get("successfulCallLatencyMillis") or {}
        attribution = r.get("attribution") or {}
        cost = r.get("cost") or {}
        status = r["status"] + (f" ({', '.join(r['invalidReasons'])})" if r.get("invalidReasons") else "") \
            + (f" ({r['failureReason']})" if r.get("failureReason") else "") \
            + (f" (admitted {r['admittedPerSecond']:.0f}/s)" if r["status"] == "saturated" and r.get("admittedPerSecond") else "")
        pct = lambda value: f"{value:.2%}" if isinstance(value, (int, float)) else "—"
        num = lambda value, fmt: format(value, fmt) if isinstance(value, (int, float)) else "—"
        completed = num(r.get("completedPerSecond"), ".1f")
        lines.append(f"| {r['repetition']} | {r['case']} | {status} | {pct(r.get('successWithinWait'))} "
                     f"| {completed} | {num(latency.get('p50'), '.1f')} / {num(latency.get('p99'), '.1f')} "
                     f"| {num(attribution.get('checkedItemsPerSecond'), '.0f')} | {pct(attribution.get('candidateShortfallRatio'))} "
                     f"| {pct(attribution.get('workerAcquisitionRejectedRatio'))} | {pct(r.get('leaseHeldPeakRatio'))} "
                     f"| {num(cost.get('redisCommandsPerCompleted'), '.1f')} |")
    versions = {r["case"]: r["httpVersions"] for r in result.get("cases", []) if r.get("httpVersions")}
    if versions:
        lines += ["", "HTTP versions observed in complete call responses: " + json.dumps(versions, sort_keys=True) + "."]
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
    worlds = result.get("jobs") or [dict(result, job="saturation" if result.get("experiment") else "lane")]
    lines += ["", "| Job configuration | Workers / Group | CPU | Server / Host / Harness heap MiB | Warmup / measure s | Items / Group | Redis limit / maxmemory MiB |",
              "| --- | --- | --- | --- | --- | --- | --- |"]
    for world in worlds:
        config = world.get("experiment")
        if config:
            profile, window, resource = config["profiles"][0], config["screening"], config["resources"]
            lines.append(f"| {world['job']} ({config['name']}) | {profile['workersPerGroup']} | {profile['cpuCount']} "
                         f"| {resource['serverHeapMiB']} / {resource['hostHeapMiB']} / {resource['harnessHeapMiB']} "
                         f"| {window['warmupSeconds']} / {window['measurementSeconds']} | {window['itemsPerGroup']} "
                         f"| {resource['redisMemoryMiB']} / {resource['redisMaxmemoryMiB']} |")
        elif world.get("executionEnvironment") == "existing-server":
            lines.append(f"| {world['job']} (existing deployment) | {world['workersPerGroup']} "
                         f"| {world.get('cpuCount') or '—'} | not collected / {world.get('hostMaxHeap', '—')} / 1024 "
                         "| original open-loop windows | per case | not collected |")
        else:
            lines.append(f"| {world['job']} (legacy world) | {world.get('workersPerGroup', LANE_WORKERS_PER_GROUP)} "
                         f"| {world.get('cpuCount') or '—'} | 1024 / 1024 / 1024 | per case | per case | unconstrained |")
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
    if result.get("executionEnvironment") == "existing-server":
        lines += ["", "Existing-deployment open-loop observation; not a same-host reference or saturation measurement.",
                  "Server/Redis resources, Redis calibration and Server JFR were not collected. "
                  "Completion rates include eventual Results after drain; they are not Server maximum TPS.",
                  "", "Server deployment (operator-declared): " + json.dumps(result.get("serverDeployment", {}), sort_keys=True),
                  "", "Consumer cleanup: " + json.dumps(result.get("cleanup", {}), sort_keys=True)]
    lines += ["", "Saturation cases count successful Result-store completion events inside the window and reconcile "
              "full-lifecycle claims, publications and stores with unique validated public exports. Warmup and drain "
              "are excluded; missing or inconsistent evidence is invalid. Resource cost denominators use the same sampled interval.", ""]
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


def deployment_java_options(options):
    if not options.java_truststore:
        return ()
    path = options.java_truststore.resolve(strict=True)
    return (f"-Djavax.net.ssl.trustStore={path}", f"-Djavax.net.ssl.trustStorePassword={options.java_truststore_password}")


def deployment_environment():
    allowed = {"PATH", "JAVA_HOME", "HOME", "USER", "LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP", "SSL_CERT_FILE"}
    return {key: value for key, value in os.environ.items() if key in allowed}


def wait_deployment_host(process, log, sampler, deadline):
    marker = re.compile(r"WORKER_SIMULATOR_READY control=(http://127\.0\.0\.1:([0-9]+)/lab)")
    while time.monotonic() < deadline:
        if process.poll() is not None or sampler.failure:
            raise RuntimeError("Worker Host exited or resource sampling failed before readiness")
        if log.is_file():
            match = marker.search(log.read_text(encoding="utf-8", errors="replace")[-65536:])
            if match and 1 <= int(match.group(2)) <= 65535:
                return match.group(1).removesuffix("/lab")
        time.sleep(.2)
    raise RuntimeError("Worker Host readiness deadline exceeded")


def deployment_inventory(lab_url):
    with deployment_open(lab_url + "/lab/v1/workers", timeout=5) as response:
        workers = json.load(response)["workers"]
    return sorted({row["workerId"] for row in workers if isinstance(row.get("workerId"), str) and row["workerId"]})


class DeploymentNoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def deployment_open(request, *, timeout):
    # In particular, an authenticated cleanup POST must never follow a redirect to another authority.
    return urllib.request.build_opener(DeploymentNoRedirect()).open(request, timeout=timeout)


def observe_deployment_disconnect(config, identities, seconds=30):
    """Cleanup observation only; never mutate remote Worker or Task state."""
    if not identities:
        return {"status": "unconfirmed", "reason": "no-owned-identity-evidence"}
    if any(not isinstance(identity, str) or not identity for identity in identities) or len(set(identities)) != len(identities):
        return {"status": "unconfirmed", "reason": "invalid-owned-identity-evidence"}
    key = Path(config["accessKeyFile"]).read_text(encoding="utf-8").strip()
    url = config["runtimeUrl"] + "/api/v1/runtime-view/endpoint-managers/" + config["endpointManager"] + "/workers:network-observe"
    started = time.monotonic()
    deadline = started + seconds
    pages = [tuple(identities[offset:offset + 100]) for offset in range(0, len(identities), 100)]
    scans = 0

    def read_page(page):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None, "deadline", None
        request = urllib.request.Request(url, data=json.dumps(page).encode("utf-8"), method="POST",
                                         headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        try:
            with deployment_open(request, timeout=min(5, remaining)) as response:
                states = json.load(response)["statesByWorkerId"]
            if not isinstance(states, dict) or set(states) != set(page):
                return None, "identity-set-mismatch", None
            if any(state not in ("connected", "disconnected", "unknown") for state in states.values()):
                return None, "unexpected-network-state", None
            return Counter(states.values()), None, time.monotonic()
        except Exception as error:
            # No response body, URL, request identity or authentication text enters diagnostics.
            return None, type(error).__name__, None

    while True:
        scans += 1
        counts = Counter()
        errors = Counter()
        completed = 0
        offset = 0
        pending = set()
        expired = False
        executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="deployment-disconnect")
        try:
            while pending or offset < len(pages):
                while offset < len(pages) and len(pending) < 8 and time.monotonic() < deadline:
                    pending.add(executor.submit(read_page, pages[offset]))
                    offset += 1
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    expired = True
                    break
                done, pending = wait(pending, timeout=remaining, return_when=FIRST_COMPLETED)
                if not done:
                    expired = True
                    break
                for future in done:
                    states, failure, ended = future.result()
                    if failure:
                        errors[failure] += 1
                    elif ended >= deadline or time.monotonic() >= deadline:
                        expired = True
                    else:
                        completed += 1
                        counts.update(states)
                if errors or expired:
                    break
        finally:
            # The observation decision must not wait on ThreadPoolExecutor.__exit__.
            # Active I/O retains its original remaining-budget timeout; it cannot amend this result.
            executor.shutdown(wait=False, cancel_futures=True)
        expired = expired or time.monotonic() >= deadline
        complete = completed == len(pages) and not errors and not expired
        result = {"status": "unconfirmed", "identityCount": len(identities), "states": dict(counts),
                  "observation": {"pageSize": 100, "pageConcurrency": 8, "totalPages": len(pages),
                                  "completedPages": completed, "observedIdentityCount": sum(counts.values()),
                                  "scanCount": scans, "complete": complete, "deadlineExpired": expired,
                                  "elapsedMillis": int((time.monotonic() - started) * 1000), "errorTypes": dict(errors)}}
        if complete and not counts["connected"]:
            result["status"] = "confirmed"
            return result
        if not complete or expired:
            return result
        time.sleep(min(1, max(0, deadline - time.monotonic())))
        if time.monotonic() >= deadline:
            result["observation"].update(deadlineExpired=True, elapsedMillis=int((time.monotonic() - started) * 1000))
            return result


def main_deployment(options):
    """Existing-server composition around the same lane phases; owns only local consumers."""
    import fcntl
    import resource
    os.umask(0o077)
    java_options = deployment_java_options(options)
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr
    if not re.search(r'version "21[.\"]', java):
        raise ValueError("Java 21 is required")
    for classpath in (host_classpath(), harness_classpath()):
        if not list(classpath.parent.glob("*.jar")):
            raise ValueError("The built or published Host and Performance Harness are required")
    output = fresh_output(options.output_root, deployment=True)
    evidence, private = output / "evidence", output / "private"
    evidence.mkdir()
    private.mkdir(mode=0o700)
    env = deployment_environment()
    input_config = options.deployment_config.resolve(strict=True)
    # Java owns measurement configuration and credential validation; Python consumes its resolved values.
    command(["java", *JVM, *java_options, "-cp", harness_classpath(),
             "com.xa.mass.integration.workercallperformance.WorkerCallPerformanceMain",
             "--phase=deployment-config", f"--output={evidence}", f"--deployment-config={input_config}"], timeout=30)
    config = json.loads((evidence / "deployment.json").read_text(encoding="utf-8"))
    if resource.getrlimit(resource.RLIMIT_NOFILE)[0] < config["hostFdLimit"]:
        raise ValueError("Consumer nofile soft limit is below the configured Host FD budget")
    lock_root = Path.home() / ".cache/xa-mass-performance"
    lock_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    identity = json.dumps([config["runtimeUrl"], config["project"], config["consumerId"]]).encode("utf-8")
    lock = (lock_root / (hashlib.sha256(identity).hexdigest() + ".lock")).open("a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        lock.close()
        raise ValueError("This consumer already owns a Performance run") from None
    plan = lane_plan(options.lane_rates or LANE_RATES, options.repetitions, ("open",))
    warmup_case = lane_warmup_case(options.lane_rates or LANE_RATES, ("open",))
    result = {"lane": "performance", "executionEnvironment": "existing-server", "referenceHost": False,
              "status": "failed", "plan": plan, "warmupCase": warmup_case, "cases": [], "timingsMillis": {},
              "groups": config["groups"], "workersPerGroup": config["workersPerGroup"],
              "assignmentBatchLimit": config["assignmentBatchLimit"], "serverDeployment": config["serverDeployment"],
              "hostMaxHeap": config["hostMaxHeap"],
              "runtimeUrl": config["runtimeUrl"], "cpuCount": os.cpu_count(), "java": java.strip(),
              "os": platform.freedesktop_os_release(), "machine": platform.machine(), "kernel": platform.release(),
              "notCollected": ["server-resources", "redis-resources", "redis-calibration", "server-jfr"],
              "attribution": None, "calibration": None}
    started = time.monotonic()
    deadline = started + LANE_WORLD_SECONDS + (len(plan) + 1) * LANE_CASE_SECONDS
    processes, credentials, cleanup_errors = {}, [], []
    sampler = None
    lab_url = None
    identities = []
    previous_term = signal.getsignal(signal.SIGTERM)
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        private_config = {key: value for key, value in config.items() if key != "rpcTaskIds"}
        for field, filename in (("accessKeyFile", "access-key.txt"), ("gatewayCredentialFile", "worker-gateway.json")):
            destination = private / filename
            shutil.copyfile(config[field], destination)
            destination.chmod(0o600)
            credentials.append(destination)
            private_config[field] = str(destination)
        config_file = private / "deployment.json"
        write_json(config_file, private_config)
        inventory = private / "data/scenario-workers"
        materialize_inventory(inventory, {group: tuple({"runtime": "java", "capability": "string-utils"}
                                                       for _ in range(config["workersPerGroup"])) for group in config["groups"]})
        host_groups = {group: dict(next(iter(lane_host_groups(config["workersPerGroup"]).values()))) for group in config["groups"]}
        host_config = private / "worker-simulator.json"
        write_json(host_config, {"runtimeApiBaseUrl": config["workerRuntimeUrl"],
                                "gatewayCredentialFile": private_config["gatewayCredentialFile"],
                                "sandboxRoot": str(inventory), "controlPort": 0, "workerGroups": host_groups})
        manifest = ROOT / "manifest.json"
        if manifest.is_file():
            result["artifactManifest"] = json.loads(manifest.read_text(encoding="utf-8"))
        write_json(evidence / "artifact-fingerprints.json", [
            {"role": role, "name": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            for role, cp in (("host", host_classpath()), ("harness", harness_classpath()))
            for path in sorted(cp.parent.glob("*.jar"))])
        write_json(evidence / "effective-config.json", {"deployment": config, "plan": plan,
                   "warmupCase": warmup_case, "hostMaxHeap": config["hostMaxHeap"],
                   "hostFdLimit": config["hostFdLimit"], "harnessJvmOptions": JVM, "nativeThreadLimit": 512})
        sampler = Sampler(evidence / "process-resources.jsonl", None, fd_limit=config["hostFdLimit"])
        sampler.thread.start()
        processes["host"] = start_process(["java", "-Xms256m", f"-Xmx{config['hostMaxHeap']}",
                                           "-XX:+ExitOnOutOfMemoryError", *java_options, "-cp", host_classpath(),
                                           "com.xa.mass.workersimulator.WorkerSimulatorMain", "--config", host_config],
                                          private / "host.log", env, fd_limit=config["hostFdLimit"])
        sampler.register("host", processes["host"])
        lab_url = wait_deployment_host(processes["host"], private / "host.log", sampler,
                                       min(deadline, time.monotonic() + 180))
        result["timingsMillis"]["hostListening"] = int((time.monotonic() - started) * 1000)
        result["bootstrap"] = run_lane_harness("bootstrap", evidence / "harness", private, env, processes, sampler,
                                               deadline, deployment=config_file, lab_url=lab_url, java_options=java_options)
        result["timingsMillis"]["bootstrapped"] = int((time.monotonic() - started) * 1000)
        run_lane_cases(output, deadline, plan, warmup_case, env, processes, sampler, result,
                       deployment=config_file, lab_url=lab_url, java_options=java_options, started=started)
        if sampler.failure or sampler.counts["host"] < 1 or not any(count for role, count in sampler.counts.items() if role.startswith("harness-")):
            raise RuntimeError("Consumer resource evidence incomplete")
        result["status"] = "passed" if len(result["cases"]) == len(plan) and not any(row["status"] == "failed" for row in result["cases"]) else "failed"
    except (Exception, KeyboardInterrupt) as error:
        result.update(status="failed", runnerFailure=type(error).__name__)
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        if lab_url:
            try:
                identities = deployment_inventory(lab_url)
                write_json(private / "owned-worker-identities.json", identities)
            except Exception as error:
                cleanup_errors.append("identity-evidence-" + type(error).__name__)
        for process in reversed(tuple(processes.values())):
            try:
                stop_process(process)
            except Exception as error:
                cleanup_errors.append("process-stop-" + type(error).__name__)
        if sampler:
            try:
                sampler.stop()
            except Exception as error:
                cleanup_errors.append("sampler-stop-" + type(error).__name__)
            if sampler.failure:
                result.update(status="failed", resourceFailure=sampler.failure)
            try:
                result["resourcePeaks"] = resource_peaks(evidence / "process-resources.jsonl")
            except Exception as error:
                cleanup_errors.append("resource-summary-" + type(error).__name__)
        try:
            disconnect = observe_deployment_disconnect(private_config, identities)
        except Exception as error:
            disconnect = {"status": "unconfirmed", "failureType": type(error).__name__}
        for path in credentials:
            try:
                path.unlink(missing_ok=True)
            except OSError as error:
                cleanup_errors.append("credential-removal-" + type(error).__name__)
        result["cleanup"] = {"ownedProcessesStopped": all(p.poll() is not None for p in processes.values()),
                             "workerConnections": disconnect,
                             "privateCredentialCopiesRemoved": all(not p.exists() for p in credentials), "errors": cleanup_errors}
        if cleanup_errors or disconnect["status"] != "confirmed":
            result["status"] = "failed"
        result["timingsMillis"]["completed"] = int((time.monotonic() - started) * 1000)
        result["caseCounts"] = dict(Counter(row["status"] for row in result["cases"]))
        result["pathRatios"] = lane_path_ratios(result["cases"])
        lock.close()
        signal.signal(signal.SIGTERM, previous_term)
        write_json(evidence / "lane-summary.json", result)
        (evidence / "summary.md").write_text(lane_markdown(result), encoding="utf-8")
    print(json.dumps({"status": result["status"], "cases": result["caseCounts"], "evidence": str(evidence / "lane-summary.json")}))
    return 0 if result["status"] == "passed" else 1


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
    parser.add_argument("--deployment-config", type=Path,
                        help="Connect the same open-loop lane to an existing deployment; no local Server or Redis")
    parser.add_argument("--java-truststore", type=Path, help="Explicit CA store for a packaged TLS fixture")
    parser.add_argument("--java-truststore-password", default="changeit")
    parser.add_argument("--experiment-config", type=Path,
                        help="Java-owned experiment configuration; purpose=lane with --lane-modes saturation or a sat-* A/B")
    parser.add_argument("--allow-nonreference-host", action="store_true", help="Local diagnostics only; no reference claim")
    options = parser.parse_args()
    if options.deployment_config:
        if ((options.lane_modes is not None and options.lane_modes != ("open",))
                or options.baseline_ref or options.lane_case or options.experiment_config or options.lane_history
                or options.merge_lane or options.merge_jobs or options.diagnostics != "off"):
            parser.error("Existing deployments support only open-loop execution, without A/B, reference history or Server recording")
        # The public path has no Server recording. An explicitly requested recording is an error.
        if any(arg == "--lane-attribution" or arg.startswith("--lane-attribution=") for arg in sys.argv[1:]) and options.lane_attribution != "off":
            parser.error("Existing deployments do not collect Server attribution; use --lane-attribution off")
        if sys.platform != "linux":
            parser.error("Existing deployment consumers require Linux with /proc and Java 21")
        return main_deployment(options)
    if options.java_truststore:
        parser.error("An explicit CA store is supported only with --deployment-config")
    configured_lane = options.experiment_config and options.lane_modes == ("saturation",)
    configured_ab = bool(options.experiment_config and options.lane_case)
    if configured_lane and any((options.baseline_ref, options.lane_case, options.lane_rates, options.merge_lane, options.merge_jobs)):
        parser.error("Configured saturation runs only the saturation lane")
    if options.experiment_config and not configured_lane and not configured_ab:
        if any((options.baseline_ref, options.lane_case, options.lane_history, options.merge_lane,
                options.merge_jobs, options.lane_rates, options.lane_modes, options.diagnostics != "off")):
            parser.error("Experiments cannot enter reference lanes, A/B or CI history")
        import capacity_experiment
        return capacity_experiment.run(options, sys.modules[__name__])
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
        # A saturation A/B measures the same configured world as the saturation job, never the open-loop world.
        if options.lane_case.startswith("sat-") != bool(options.experiment_config):
            parser.error("A sat-* A/B needs the lane --experiment-config; an open-loop A/B takes none")
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
    if options.experiment_config:
        result = run_saturation_lane(output, plan, options.experiment_config, options.diagnostics)
    else:
        result = run_lane(ROOT, output, run_started + LANE_WORLD_SECONDS + (len(plan) + 1) * LANE_CASE_SECONDS,
                          plan, options.lane_attribution == "on", lane_warmup_case(rates, modes), diagnostics=options.diagnostics)
    manifest = result.get("artifactManifest")
    result.update(referenceHost=reference, os=os_release, java=java.strip(), cpuCount=os.cpu_count(),
                  machine=platform.machine(), kernel=platform.release(), redisImageId=image_id,
                  harnessCommit=manifest["sourceHead"] if manifest else command(["git", "rev-parse", "HEAD"]),
                  worktreeDirty=manifest["worktreeDirty"] if manifest else bool(command(["git", "status", "--porcelain"])))
    write_json(output / "evidence/lane-summary.json", result)
    (output / "evidence/summary.md").write_text(lane_markdown(result), encoding="utf-8")
    print(json.dumps({"status": result["status"], "cases": result.get("caseCounts"),
                      "evidence": str(output / "evidence/lane-summary.json")}), flush=True)
    return 0 if result["status"] == "passed" else 1


def main_lane_ab(options, output, run_started, reference):
    """Per-case sequential A/B on one host: ABBA-ordered pairs until the history band decides (2..5 pairs).

    Open-loop cases run the lane world per version. Saturation cases run the configured saturation
    world of the saturation job, with each version's Server and Host and the current harness.
    """
    import capacity_experiment
    case = options.lane_case
    configured = case.startswith("sat-")
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
        warmup = case if not configured else None
        if configured:
            evidence = output / "evidence"
            evidence.mkdir(parents=True, exist_ok=True)
            resolved, config, profile, cpu_ids = resolve_saturation_config(evidence, options.experiment_config)
            final["experiment"] = dict(name=config["name"], profile=profile["name"],
                                       workersPerGroup=profile["workersPerGroup"], cpuIds=cpu_ids)
        deadline = run_started + 110 * 60
        values = []
        latencies = {}
        for pair in range(lane_trend.AB_MAX_PAIRS):
            order = ("A", "B") if pair % 2 == 0 else ("B", "A")
            observed = {}
            for version in order:
                print(f"lane ab pair={pair + 1} version={version} case={case}", flush=True)
                if configured:
                    case_root = output / f"pair-{pair + 1}" / version
                    case_root.parent.mkdir(parents=True, exist_ok=True)
                    row = capacity_experiment.run_case(sys.modules[__name__], case_root, resolved, config, profile,
                        "screening", lane_entry(case)["path"], pair + 1, cpu_ids, artifacts=roots[version])
                    # A worker-bound or incomplete saturation world cannot enter a comparison.
                    run_status = "passed" if row.get("status") == "passed" else "failed"
                    if row.get("calibration"):
                        final.setdefault("calibration", {})[f"{pair + 1}{version}"] = row["calibration"]
                else:
                    run = run_lane(roots[version], output / f"pair-{pair + 1}" / version, deadline,
                                   [lane_entry(case, pair + 1)], attribution=False, warmup_case=warmup, calibrate=False)
                    row = next(iter(run.get("cases", [])), {})
                    run_status = run["status"]
                observed[version] = extract(row) if row.get("status") == "passed" else None
                final["pairs"].append({"pair": pair + 1, "version": version, "runStatus": run_status,
                                       "caseStatus": row.get("status"), metric: extract(row)})
                if run_status != "passed":
                    reasons = "; ".join(row.get("invalidReasons") or [])
                    raise RuntimeError(f"Lane run failed for version {version} in pair {pair + 1}"
                                       + (f": {reasons}" if reasons else ""))
                if not configured:
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
