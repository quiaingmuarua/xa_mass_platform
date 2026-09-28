#!/usr/bin/env python3
"""One finite call-performance lane; public API assertions live in its Java Harness."""
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

CASES = ("any-100", "any-500", "any-1000", "any-2000", "targeted-500", "mixed-500")
DIRECT_CASES = ("direct-100", "direct-500", "direct-1000", "direct-2000", "direct-5000")
DIAGNOSIS_CASES = ("direct-step-1000", "direct-step-2000")
RPC_TASK_CASES = ("rpc-any-500", "rpc-any-1000", "rpc-any-2000", "rpc-targeted-1000", "rpc-targeted-2000")
RPC_CASES = RPC_TASK_CASES + DIAGNOSIS_CASES
NIGHTLY_CASES = RPC_CASES + ("mixed-500",)
SUITES = {"task": CASES, "direct": DIRECT_CASES, "direct-diagnosis": DIAGNOSIS_CASES,
          "rpc-diagnosis": RPC_CASES, "nightly": NIGHTLY_CASES, "lane": ()}
ORDER = (("A", "B"), ("B", "A"), ("A", "B"))
GROUP = "scenario-string-utils-workers"
REDIS_IMAGE = "redis:7.4.10"
JVM = ("-Xms256m", "-Xmx1g", "-XX:+ExitOnOutOfMemoryError")
MODULE = ROOT / "integrations/worker-call-performance"
SERVER_FLAGS = {
    "spring.profiles.active": "scenario-workers",
    "server.address": "127.0.0.1", "server.port": "18082",
    "xa.mass.kernel-pacer.preset": "DEFAULT",
    "xa.mass.kernel-pacer.enabled": "true",
    "xa.mass.task-rpc.default-wait-timeout-millis": "30000",
    "xa.mass.task-rpc.max-wait-timeout-millis": "60000",
    "xa.mass.task-rpc.max-waiters": "10000",
    "xa.mass.task-rpc.max-pending-observations": "100000",
    "xa.mass.task-rpc.max-probe-items-per-round": "256",
    "xa.mass.task-rpc.initial-probe-interval-millis": "50",
    "xa.mass.task-rpc.normal-probe-interval-millis": "100",
    "xa.mass.task-rpc.long-probe-interval-millis": "250",
    "xa.mass.project-assembly.projects[0].project-id": "scenario-workers",
    "xa.mass.project-assembly.projects[0].worker-group-ids[0]": GROUP,
    "xa.mass.worker-assembly.group-config-json": json.dumps({GROUP: {
        "attributes": {"capability": "string-utils"},
        "eventCodes": ["extension.worker.string.md5", "extension.worker.lab.delay"]}}),
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


def resource_summary(path, started, seconds=30):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    result = {}
    for role in ("server", "host", "harness", "redis"):
        window = [row for row in rows if row["role"] == role
                  and started <= row["epochMillis"] <= started + seconds * 1000]
        if len(window) < 2:
            raise RuntimeError(f"Measurement resource evidence incomplete for {role}")
        first, last = window[0], window[-1]
        covered = (last["epochMillis"] - first["epochMillis"]) / 1000
        if covered <= 0:
            raise RuntimeError("Resource sample clock did not advance")
        values = {"samples": len(window), "coveredSeconds": covered}
        if role == "redis":
            values.update(peakUsedMemoryBytes=max(row["usedMemory"] for row in window),
                peakRssBytes=max(row["usedMemoryRss"] for row in window),
                meanCpuCores=((last["cpuUserSeconds"] + last["cpuSystemSeconds"])
                              - (first["cpuUserSeconds"] + first["cpuSystemSeconds"])) / covered,
                aggregateCommandCallDeltas={name: value["calls"] - first["commandStats"].get(name, {}).get("calls", 0)
                                           for name, value in last["commandStats"].items()})
        else:
            values.update(meanCpuCores=(last["cpuSeconds"] - first["cpuSeconds"]) / covered,
                peakRssBytes=max(row["rssBytes"] for row in window),
                peakNativeThreads=max(row["nativeThreads"] for row in window),
                peakFileDescriptors=max(row["openFileDescriptors"] for row in window))
        result[role] = values
    return result


def markdown_summary(final):
    if final.get("suite") in ("rpc-diagnosis", "nightly"):
        lines = ["# RPC mainline attribution", "",
            f"Status: **{final['status']}**. Reference host: {final['referenceHost']}. Full suite selected: {final['completeSuite']}.", "",
            f"Assignment ceiling: {final.get('assignmentBatchLimit', 100)}. Each main case uses 1000 Workers; mixed-500 retains its original 100 Workers and 30 seconds.", "",
            "| Repetition | Case | Window | Sent / planned | HTTP responses/s | Original success | Successful cohort/s | Accepted success after drain | Success p99 ms | Limited |",
            "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for row in final["runs"]:
            windows = [("whole", row)] + [(name, row["windows"][name]) for name in ("surge", "sustained") if name in row.get("windows", {})]
            for name, window in windows:
                if "sent" not in window:
                    lines.append(f"| {row['pair'] + 1} | {row['case']} | {name}: {row['status']} | — | — | — | — | — | — | — |")
                    continue
                drained = f"{window['acceptedSuccessRateAfterDrain']:.2%}" if "acceptedSuccessRateAfterDrain" in window else "N/A (Direct)"
                lines.append(f"| {row['pair'] + 1} | {row['case']} | {name} | {window['sent']}/{window['planned']} "
                    f"| {window['httpResponsesDuringWindowPerSecond']:.2f} | {window['successRate']:.2%} "
                    f"| {window['successfulCohortPerSecond']:.2f} | {drained} "
                    f"| {window['successfulCallLatencyMillis']['p99']:.2f} | {window['generatorLimited']} |")
        drains = [row for row in final["runs"] if "drainBudgetSeconds" in row]
        if drains:
            lines += ["", "| Repetition | Case | Drain budget s | Last follow-up observation s | Targets without success after 60s |",
                      "| --- | --- | --- | --- | --- |"]
            for row in drains:
                last = row.get("followupObservationMaxMillis", -1)
                last = f"{last / 1000:.1f}" if last >= 0 else "—"
                lines.append(f"| {row['pair'] + 1} | {row['case']} | {row['drainBudgetSeconds']} | {last} "
                             f"| {row.get('targetsWithoutSuccessAfter60Seconds', 'N/A')} |")
        failed = [row for row in final["runs"] if row["status"] != "passed"]
        if failed:
            lines += ["", "Case validation failures:", ""]
            for row in failed:
                unresolved = row.get("acceptedResultsAfterDrain", {}).get("not_observed", 0)
                budget = row.get("drainBudgetSeconds", 180)
                reason = f"{unresolved} accepted Items remain unobserved after the {budget}-second drain budget" if unresolved else "see case-summary.json for the failed prerequisite or evidence check"
                lines.append(f"- Repetition {row['pair'] + 1}, {row['case']}: {reason}.")
        lines += ["", "Original success uses sent requests; drain uses HTTP-accepted Items and never rewrites call latency. "
            "Planned cohorts and responses arriving within a window are separate. Limited windows cannot quantify capacity. "
            "Pass means the finite measurement contract passed, not a QPS SLA or an A/B improvement. "
            "Task checks use the recorded instance ceiling; DEFAULT retains its 50ms completion interval and independent 100ms Score slots. "
            "Result closure waits 180 seconds within the single-Task budget of 10 x ceiling checks/s, otherwise Item TTL plus accepted Items divided by that budget. "
            "Targets without success after 60s is diagnostic evidence of unassignable targeted Workers, not a gate. "
            "Sampled stages are observations, not finality.", ""]
        return "\n".join(lines)
    if final.get("suite") in ("direct", "direct-diagnosis"):
        lines = ["# Worker Direct Call Performance", "",
            f"Status: **{final['status']}**. Reference host: {final['referenceHost']}. Complete suite: {final['completeSuite']}.", "",
            "1,000 connected Java Workers, caller round-robin, one Worker per HTTP request; no background Task.", "",
            "| Pair | Version | Offered /s | Sent / planned | Successful cohort /s | Success in original response | Within 1s / sent | Success p99 ms | Limited |",
            "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for row in final["runs"]:
            if "sent" not in row:
                lines.append(f"| {row['pair'] + 1} | {row['version']} | {row['case']} | {row['status']} | — | — | — | — | — |")
                continue
            lines.append(f"| {row['pair'] + 1} | {row['version']} | {row['offeredRate']} | {row['sent']} / {row['planned']} "
                f"| {row['successfulCohortPerSecond']:.2f} | {row['successRate']:.2%} | {row['withinOneSecondRateOfSent']:.2%} "
                f"| {row['successfulCallLatencyMillis']['p99']:.2f} | {row['generatorLimited']} |")
        lines.extend(["", "Passed means the measurement contract passed, not an RPC service-level objective. "
            "HTTP 200 may contain a timeout or rejected target. Outcome counts and reason/code counts are in the JSON. "
            "Successful cohort /s includes this cohort's responses after the offered window; responses inside the window are also recorded. "
            "Timeout is unobserved execution, not proof of business failure. Direct Call has no results:load or durable follow-up. "
            "Generator-limited rows cannot establish server capacity.", ""])
        if "comparison" in final:
            lines.extend([f"Comparison: **{final['comparison']['status']}**.", ""])
        if final.get("suite") == "direct-diagnosis":
            lines.extend(["## Fixed windows", "", "Cohorts use planned arrival; response rates use actual completion time. All original samples remain retained.", "",
                "| Pair/version | Case | Window | HTTP responses/s | Successful cohort/s | Success | Occupied | 429 | Timeout | Unknown | Not sent | Success p99 ms | Limited |",
                "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"])
            for row in final["runs"]:
                for name, window in row.get("windows", {}).items():
                    reasons, counts = window["details"], window["outcomes"]
                    lines.append(f"| {row['pair'] + 1}/{row['version']} | {row['case']} | {name} "
                        f"| {window['httpResponsesDuringWindowPerSecond']:.2f} | {window['successfulCohortPerSecond']:.2f} "
                        f"| {window['successRate']:.2%} | {reasons.get('command-slot-occupied', 0)} | {reasons.get('http-429', 0)} "
                        f"| {counts['timed_out']} | {counts['unknown']} | {counts['not_sent']} "
                        f"| {window['successfulCallLatencyMillis']['p99']:.2f} | {window['generatorLimited']} |")
            lines.extend(["", f"Diagnostics: **{final.get('diagnostics', 'off')}**. JFR observations are separate from performance acceptance.", ""])
        return "\n".join(lines)
    lines = ["# Worker Call Performance", "", f"Status: **{final['status']}**. "
             f"Reference host: {final['referenceHost']}. Complete suite: {final['completeSuite']}.", "",
             f"Assignment ceiling: {final.get('assignmentBatchLimit', 100)}.", "",
             "| Pair | Version | Case | Sent / planned | Response success | Result success after drain | Success p99 ms | Generator limited |",
             "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for row in final["runs"]:
        if "sent" not in row:
            lines.append(f"| {row['pair'] + 1} | {row['version']} | {row['case']} | {row['status']} | — | — | — | — |")
            continue
        lines.append(f"| {row['pair'] + 1} | {row['version']} | {row['case']} | {row['sent']} / {row['planned']} "
                     f"| {row['successRate']:.2%} | {row['acceptedSuccessRateAfterDrain']:.2%} "
                     f"| {row['successfulCallLatencyMillis']['p99']:.2f} | {row['generatorLimited']} |")
    if "comparison" in final:
        lines.extend(["", f"Comparison: **{final['comparison']['status']}**. No detected regression does not establish speedup."])
    lines.extend(["", "Response success uses all sent requests; drained success uses HTTP-accepted requests. "
                  "Follow-up observations are excluded from original call latency. "
                  "Raw safe samples, resource windows and aggregate Redis diagnostics accompany this summary.", ""])
    return "\n".join(lines)


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


def assignment_limit(value):
    limit = int(value)
    if not 1 <= limit <= 1000:
        raise argparse.ArgumentTypeError("assignment-batch-limit must be in 1..1000")
    return limit


def assignment_overrides(root, limit):
    # Historical fixed-100 baselines have no configuration field to bind.
    if any("assignment-batch-limit:" in (root / path).read_text(encoding="utf-8")
           for path in fingerprint(root)):
        return {"xa.mass.kernel-pacer.assignment-batch-limit": str(limit)}
    if limit != 100:
        raise RuntimeError("Selected version does not support an assignment batch override")
    return {}


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


def run_case(root, case, output, version, deadline, diagnostics="off", assignment_batch_limit=100):
    import redis
    output.mkdir(parents=True)
    evidence = output / "evidence"
    evidence.mkdir()
    private = output / "private"
    private.mkdir()
    scope = "test_worker_call_performance_" + uuid.uuid4().hex[:16]
    container = None
    processes = {}
    sampler = None
    client = None
    result = {"case": case, "version": version, "status": "failed", "scope": scope}
    cleanup_errors = []
    direct = case in DIRECT_CASES + DIAGNOSIS_CASES
    seconds = 120 if case in RPC_CASES else 30
    worker_count = 1000 if direct or case in RPC_TASK_CASES else 100
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
        sampler = Sampler(evidence / "process-resources.jsonl", client)
        sampler.thread.start()
        env = {k: v for k, v in os.environ.items() if not k.startswith(("XA_MASS_", "SPRING_"))
               and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        env.update(XA_MASS_REDIS_URL=redis_url, XA_MASS_REDIS_SCOPE=scope, XA_MASS_KERNEL_PACER_PRESET="DEFAULT")
        flags = dict(SERVER_FLAGS)
        flags.update(assignment_overrides(root, assignment_batch_limit))
        flags.update({"xa.mass.redis.url": redis_url, "xa.mass.redis.scope": scope})
        if diagnostics == "jfr":
            flags["xa.mass.diagnostics.enabled"] = "true"
        if direct or case in RPC_TASK_CASES:
            flags.update({"xa.mass.direct-call.default-wait-timeout-millis": "3000",
                "xa.mass.direct-call.max-wait-timeout-millis": "10000",
                "xa.mass.direct-call.max-adapter-commands-per-adapter": "1000",
                "xa.mass.direct-call.max-pending-calls": "10000"})
        write_json(evidence / "effective-config.json", {"serverOverrides": flags, "jvmOptions": JVM,
            "configurationSourceSha256": fingerprint(root), "workers": worker_count, "group": GROUP,
            "configurationSources": configuration_sources(root),
            "assignmentBatchLimit": assignment_batch_limit,
            "sourceHead": command(["git", "rev-parse", "HEAD"], cwd=root),
            "presetSelection": {"name": "DEFAULT", "assignmentIntervalMillis": 50,
                "resultIdleIntervalMillis": 100, "serviceabilityDispatchEnabled": False},
            "redisImage": REDIS_IMAGE, "maximumInFlight": 4096, "warmupSeconds": 20,
            "measurementSeconds": seconds, "httpRequestTimeoutSeconds": 5, "waitTimeoutMillis": 1000,
            "diagnostics": diagnostics, "maximumPlannedRequests": 300000,
            "diagnosticsSettingsSha256": hashlib.sha256((MODULE / "diagnostics.jfc").read_bytes()).hexdigest(),
            "jfrOptionsByRole": {role: jfr_options(private, role, diagnostics) for role in ("server", "host", "harness")},
            "callPath": "DIRECT_CALL" if direct else "TASK", "drainSeconds": None if direct else 180})
        jars = [p for p in (root / server_distribution(root) / "build/libs").glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar")]
        jar = max(jars, key=lambda p: p.stat().st_mtime_ns)
        write_json(evidence / "artifact-fingerprints.json", artifact_fingerprints(root, jar))
        result["assignmentBatchLimit"] = assignment_batch_limit
        processes["server"] = start_process(["java", *JVM, *jfr_options(private, "server", diagnostics), "-jar", jar,
            *(f"--{key}={value}" for key, value in flags.items())], private / "server.log", env)
        sampler.register("server", processes["server"])
        wait_http("http://127.0.0.1:18082/actuator/health/readiness", processes["server"], sampler, min(deadline, time.monotonic() + 180))
        inventory = private / "data/scenario-workers"
        materialize_inventory(inventory, {GROUP: tuple({"runtime": "java", "capability": "string-utils"} for _ in range(worker_count))})
        config_path = private / "worker-simulator.json"
        groups = {GROUP: {"events": ["extension.worker.string.md5", "extension.worker.lab.delay"],
            "count": worker_count, "propertiesTemplate": {}, "newEnvironment": False,
            "requestTimeoutMillis": 60_000, "reconnectPolicy": {"maxUnstableAttempts": 600,
            "reconnectIntervalMillis": 500, "stableConnectionDurationMillis": 10_000}}}
        write_json(config_path, {"runtimeApiBaseUrl": "http://127.0.0.1:18082", "sandboxRoot": str(inventory.resolve()),
                                "controlPort": 18086, "workerGroups": groups})
        write_json(evidence / "host-configuration.json", groups)
        processes["host"] = start_process(["java", *JVM, *jfr_options(private, "host", diagnostics), "-cp", root / "worker_simulator_jvm/build/install/xa-mass-worker-simulator/lib/*",
            "com.xa.mass.workersimulator.WorkerSimulatorMain", "--config", str(config_path)], private / "host.log", env)
        sampler.register("host", processes["host"])
        wait_http("http://127.0.0.1:18086/lab/v1/workers", processes["host"], sampler, min(deadline, time.monotonic() + 180))
        harness_output = evidence / "harness"
        processes["harness"] = start_process(["java", *JVM, *jfr_options(private, "harness", diagnostics), "-cp", ROOT / "integrations/worker-call-performance/build/install/xa-mass-worker-call-performance/lib/*",
            "com.xa.mass.integration.workercallperformance.WorkerCallPerformanceMain", f"--case={case}",
            f"--output={harness_output}", f"--assignment-batch-limit={assignment_batch_limit}"], private / "harness.log", env)
        sampler.register("harness", processes["harness"])
        while processes["harness"].poll() is None:
            if time.monotonic() >= deadline or sampler.failure or any(processes[r].poll() is not None for r in ("server", "host")):
                raise RuntimeError("Deadline, resource evidence or process survival failed")
            time.sleep(.25)
        path = harness_output / "summary.json"
        if path.is_file():
            result.update(json.loads(path.read_text()))
        if processes["harness"].returncode != 0 or result["status"] != "passed":
            raise RuntimeError("Java Harness failed; inspect evidence/harness/summary.json")
        if any(processes[r].poll() is not None for r in ("server", "host")):
            raise RuntimeError("Server or Host exited unexpectedly")
        if sampler.failure or any(sampler.counts[r] < 1 for r in ("server", "host", "harness", "redis")):
            raise RuntimeError("Resource evidence incomplete")
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
            result["resourceSampleCounts"] = dict(sampler.counts)
            if "measurementStartedEpochMillis" in result:
                try:
                    result["measurementResources"] = resource_summary(evidence / "process-resources.jsonl",
                                                                       result["measurementStartedEpochMillis"], seconds)
                    for window in [*result.get("windows", {}).values(), *result.get("fiveSecondBuckets", [])]:
                        window["measurementResources"] = resource_summary(evidence / "process-resources.jsonl",
                            result["measurementStartedEpochMillis"] + window["fromSeconds"] * 1000,
                            window["toSeconds"] - window["fromSeconds"])
                        rate = window["httpResponsesDuringWindowPerSecond"]
                        window["serverCpuSecondsPerHttpResponse"] = (window["measurementResources"]["server"]["meanCpuCores"] / rate
                                                                    if rate else None)
                except Exception as error:
                    result.update(status="failed", resourceSummaryFailure=type(error).__name__ + ": " + str(error))
        for process in reversed(tuple(processes.values())):
            try:
                stop_process(process)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        if diagnostics == "jfr" and "measurementStartedEpochMillis" in result:
            result["diagnosticEvidence"] = export_diagnostics(private, evidence, result["measurementStartedEpochMillis"], seconds,
                                                               "DIRECT_CALL" if direct else "TASK")
        if client:
            client.close()
        if container:
            try:
                command(["docker", "rm", "--force", container], timeout=20)
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        # Each case owns a disposable container; no key deletion or shared Redis is used.
        if cleanup_errors:
            result.update(status="failed", cleanupErrors=cleanup_errors)
        write_json(evidence / "case-summary.json", result)
    return result


# Performance lane world (DESIGN-performance-lane.md, slice S1). The legacy suites above retire in S6.
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
    flags = {key: value for key, value in SERVER_FLAGS.items()
             if not key.startswith(("xa.mass.project-assembly.", "xa.mass.worker-assembly."))}
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


def run_lane(root, output, deadline, rates=LANE_RATES, repetitions=1, attribution=True, modes=LANE_MODES):
    """Start the world once, then run each case between quiesce gates (DESIGN-performance-lane.md, S1-S2)."""
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
    result = {"lane": "performance", "status": "failed", "scope": scope, "rates": list(rates), "modes": list(modes),
              "repetitions": repetitions, "attribution": attribution, "timingsMillis": {}, "cases": records}
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
        sampler = Sampler(evidence / "process-resources.jsonl", client)
        sampler.thread.start()
        env = {k: v for k, v in os.environ.items() if not k.startswith(("XA_MASS_", "SPRING_"))
               and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        env.update(XA_MASS_REDIS_URL=redis_url, XA_MASS_REDIS_SCOPE=scope, XA_MASS_KERNEL_PACER_PRESET="DEFAULT")
        flags = lane_server_flags()
        flags.update({"xa.mass.redis.url": redis_url, "xa.mass.redis.scope": scope})
        jars = [p for p in (root / server_distribution(root) / "build/libs").glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar")]
        jar = max(jars, key=lambda p: p.stat().st_mtime_ns)
        recording = ([f"-XX:StartFlightRecording=name=lane,settings={MODULE / 'lane-attribution.jfc'},"
                      f"filename={private / 'server.jfr'},maxsize=240m,dumponexit=true"] if attribution else [])
        write_json(evidence / "knob-audit.json", {"resources": list(LANE_KNOB_AUDIT), "mechanismConstants": LANE_MECHANISM_CONSTANTS})
        write_json(evidence / "effective-config.json", {"serverOverrides": flags, "jvmOptions": JVM, "serverRecording": recording,
            "groups": list(LANE_GROUPS), "workersPerGroup": LANE_WORKERS_PER_GROUP, "project": LANE_PROJECT,
            "assignmentBatchLimit": LANE_ASSIGNMENT_BATCH_LIMIT, "hostGroups": lane_host_groups(),
            "plan": lane_plan(rates, repetitions, modes),
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
        processes["host"] = start_process(["java", *JVM, "-cp", root / "worker_simulator_jvm/build/install/xa-mass-worker-simulator/lib/*",
            "com.xa.mass.workersimulator.WorkerSimulatorMain", "--config", str(config_path)], private / "host.log", env)
        sampler.register("host", processes["host"])
        wait_http("http://127.0.0.1:18086/lab/v1/workers", processes["host"], sampler, min(deadline, time.monotonic() + 180))
        timings["hostListening"] = elapsed()
        result["bootstrap"] = run_lane_harness("bootstrap", harness_output, private, env, processes, sampler, deadline)
        timings["bootstrapped"] = elapsed()
        world = harness_output / "bootstrap.json"
        plan = lane_plan(rates, repetitions, modes)
        for index, entry in enumerate(plan):
            case_output = harness_output / f"r{entry['repetition']}" / entry["case"]
            try:
                run_lane_harness("quiesce", case_output, private, env, processes, sampler, deadline, f"--world={world}")
            except RuntimeError:
                # F6: a polluted world makes this and every later case incomparable.
                records.extend(dict(later, status="invalid", invalidReasons=["quiesce-timeout"]) for later in plan[index:])
                break
            case = run_lane_harness("case", case_output, private, env, processes, sampler, deadline,
                                    f"--case={entry['case']}", f"--repetition={entry['repetition']}", f"--world={world}",
                                    accepted=("passed", "invalid", "saturated"))
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
        if attribution and records:
            try:
                result["attribution"] = attribute_lane(private, evidence, records)
            except Exception as error:
                result["attribution"] = {"complete": False, "failureType": type(error).__name__}
        result["pathRatios"] = lane_path_ratios(records)
        result["caseCounts"] = dict(Counter(r["status"] for r in records))
        if cleanup_errors:
            result.update(status="failed", cleanupErrors=cleanup_errors)
        write_json(evidence / "lane-summary.json", result)
    return result


def merge_lane(evidence_root, jobs):
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
                          "runnerFailure": summary.get("runnerFailure"), "resourcePeaks": summary.get("resourcePeaks", {})})
    status = "passed" if all(s["status"] == "passed" for s in summaries) else "failed"
    return {"lane": "performance", "status": status, "jobs": summaries, "cases": cases,
            "caseCounts": dict(Counter(case["status"] for case in cases)), "pathRatios": lane_path_ratios(cases)}


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
        lines += ["", "| Job | Status | Cases | Case time s | Server peak threads / FDs |", "| --- | --- | --- | --- | --- |"]
        for job in result["jobs"]:
            server = job.get("resourcePeaks", {}).get("server", {})
            elapsed = job.get("casesCompletedMillis")
            lines.append(f"| {job['job']} | {job['status']} | {job.get('caseCounts', {})} "
                         f"| {elapsed / 1000:.0f} | {server.get('peakNativeThreads', '—')} / {server.get('peakFileDescriptors', '—')} |"
                         if elapsed is not None else
                         f"| {job['job']} | {job['status']} | {job.get('caseCounts', {})} | — | — |")
    if result.get("pathRatios"):
        lines += ["", "Task path relative to Direct on the same host (passed cases only):", "",
                  "| Rep | Rate | Path | p99 latency ratio | Completion ratio |", "| --- | --- | --- | --- | --- |"]
        lines += [f"| {x['repetition']} | {x['rate']} | {x['path']} | {x['p99LatencyRatio']:.2f} | {x['completionRatio']:.2f} |"
                  for x in result["pathRatios"] if x["p99LatencyRatio"] is not None and x["completionRatio"] is not None]
    lines += ["", "| Milestone | Elapsed s |", "| --- | --- |"]
    lines += [f"| {name} | {value / 1000:.1f} |" for name, value in timings.items()]
    lines += ["", "| Role | Peak threads | Peak FDs | Peak RSS MiB |", "| --- | --- | --- | --- |"]
    lines += [f"| {role} | {peak['peakNativeThreads']} | {peak['peakFileDescriptors']} | {peak['peakRssBytes'] / 2**20:.0f} |"
              for role, peak in result.get("resourcePeaks", {}).items() if not role.startswith("harness-")]
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


def export_diagnostics(private, evidence, started, seconds, call_path="DIRECT_CALL"):
    result = {}
    for role in ("server", "host", "harness"):
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


def comparison(runs, cases=CASES):
    findings = []
    for case in cases:
        regressions = 0
        comparable = 0
        observations = []
        for pair in range(3):
            values = {r["version"]: r for r in runs if r["case"] == case and r["pair"] == pair}
            if set(values) != {"A", "B"}:
                continue
            a, b = values["A"], values["B"]
            if any(r["status"] != "passed" or r.get("generatorLimited", True) for r in (a, b)):
                continue
            success_delta = b["successRate"] - a["successRate"]
            pa, pb = a["successfulCallLatencyMillis"], b["successfulCallLatencyMillis"]
            p99_ratio = pb["p99"] / pa["p99"] if pa["p99"] > 0 and pa["samples"] and pb["samples"] else None
            completion_regressed = success_delta < -.05 - 1e-12
            if p99_ratio is None and not completion_regressed:
                observations.append({"pair": pair, "successRateDelta": success_delta,
                                     "p99Ratio": None, "reason": "Insufficient successful latency samples"})
                continue
            regressed = completion_regressed or (abs(success_delta) <= .05 + 1e-12 and p99_ratio is not None and p99_ratio > 1.20)
            comparable += 1
            regressions += int(regressed)
            observations.append({"pair": pair, "successRateDelta": success_delta, "p99Ratio": p99_ratio, "regressed": regressed})
        findings.append({"case": case, "comparablePairs": comparable, "regressedPairs": regressions,
                         "status": "regressed" if regressions >= 2 else "no_detected_regression" if comparable == 3 else "inconclusive",
                         "observations": observations})
    status = "regressed" if any(f["status"] == "regressed" for f in findings) else (
        "no_detected_regression" if all(f["status"] == "no_detected_regression" for f in findings) else "inconclusive")
    return {"status": status, "cases": findings}


def diagnosis_comparison(runs, cases=DIAGNOSIS_CASES):
    findings = []
    for case in cases:
        for window_name in ("surge", "sustained"):
            observations = []
            regressions = improvements = comparable = 0
            for pair in range(3):
                values = {r["version"]: r for r in runs if r["case"] == case and r["pair"] == pair}
                if set(values) != {"A", "B"}:
                    continue
                a_run, b_run = values["A"], values["B"]
                a, b = a_run.get("windows", {}).get(window_name), b_run.get("windows", {}).get(window_name)
                if (not a or not b or any(r["status"] != "passed" for r in (a_run, b_run))
                        or any(w.get("generatorLimited", True) for w in (a, b))):
                    observations.append({"pair": pair, "comparable": False, "reason": "Missing, failed or generator-limited window"})
                    continue
                delta = b["successRate"] - a["successRate"]
                pa, pb = a["successfulCallLatencyMillis"], b["successfulCallLatencyMillis"]
                ratio = pb["p99"] / pa["p99"] if pa["p99"] > 0 and pa["samples"] and pb["samples"] else None
                ca, cb = a.get("serverCpuSecondsPerHttpResponse"), b.get("serverCpuSecondsPerHttpResponse")
                cpu_ratio = cb / ca if ca is not None and cb is not None and ca > 0 else None
                regressed = delta < -.05 - 1e-12 or (abs(delta) <= .05 + 1e-12 and ratio is not None and ratio > 1.20)
                if ratio is None and not regressed:
                    observations.append({"pair": pair, "comparable": False, "reason": "Insufficient successful latency samples"})
                    continue
                improved = delta >= .05 - 1e-12 or (abs(delta) <= .01 + 1e-12 and (
                    (ratio is not None and ratio <= .85 + 1e-12) or (cpu_ratio is not None and cpu_ratio <= .85 + 1e-12)))
                comparable += 1
                regressions += int(regressed)
                improvements += int(improved and not regressed)
                observations.append({"pair": pair, "comparable": True, "successRateDelta": delta,
                    "p99Ratio": ratio, "serverCpuPerResponseRatio": cpu_ratio, "improved": improved and not regressed, "regressed": regressed})
            status = "regressed" if regressions >= 2 else "inconclusive" if comparable < 3 else (
                "improved" if improvements >= 2 else "no_clear_benefit")
            findings.append({"case": case, "window": window_name, "status": status,
                "comparablePairs": comparable, "regressedPairs": regressions, "improvedPairs": improvements, "observations": observations})
    status = "regressed" if any(f["status"] == "regressed" for f in findings) else (
        "inconclusive" if any(f["status"] == "inconclusive" for f in findings) else
        "eligible_candidate" if any(f["status"] == "improved" for f in findings) else "no_clear_benefit")
    return {"status": status, "windows": findings,
            "meaning": "Eligibility still requires mechanism evidence and the existing Task/recovery checks; incomplete guard windows cannot be hidden by a benefit elsewhere."}


def execution_mode(baseline, diagnostics, diagnostic_pair):
    if diagnostic_pair:
        if not baseline or diagnostics != "jfr":
            raise ValueError("--diagnostic-pair requires --baseline-ref and --diagnostics jfr")
        return "diagnostic_pair", (("A", "B"),)
    if baseline and diagnostics != "off":
        raise ValueError("Formal A/B comparisons require --diagnostics off; use --diagnostic-pair for separate JFR evidence")
    return ("comparison", ORDER) if baseline else ("diagnostic" if diagnostics == "jfr" else "measurement", (("B",),))


def repetition_cases(repetition):
    """Same-version path rotation; no production A/B candidate verdict."""
    paths = ("direct-step", "rpc-targeted", "rpc-any")
    paths = paths[repetition % 3:] + paths[:repetition % 3]
    return ("rpc-any-500",) + tuple(f"{path}-{rate}" for rate in (1000, 2000) for path in paths)


def validate_repetitions(suite, repetitions, baseline, diagnostics, diagnostic_pair):
    if repetitions not in (1, 3):
        raise ValueError("Repetitions must be 1 or 3")
    if suite in ("rpc-diagnosis", "nightly") and (baseline or diagnostic_pair):
        raise ValueError("RPC attribution uses one immutable version, not candidate A/B comparisons")
    if repetitions != 1 and (suite != "rpc-diagnosis" or diagnostics != "off"):
        raise ValueError("Three repetitions require rpc-diagnosis with JFR off")
    if suite == "nightly" and diagnostics != "off":
        raise ValueError("Nightly requires JFR off")


def require_manifest(runs, cases, repetitions):
    expected = Counter((rep, case) for rep in range(repetitions) for case in cases)
    actual = Counter((run["pair"], run["case"]) for run in runs)
    if actual != expected:
        raise RuntimeError("Incomplete or duplicate performance case manifest")
    failed = [f"repetition {run['pair'] + 1}: {run['case']}" for run in runs if run["status"] != "passed"]
    if failed:
        raise RuntimeError("Performance case validation failed: " + ", ".join(failed))


def main():
    run_started = time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, default=ROOT / "build/worker-call-performance-proof")
    parser.add_argument("--baseline-ref", help="Build this immutable Git commit as A; compare A/B, B/A, A/B")
    parser.add_argument("--suite", choices=SUITES, default="task", help="Fixed Task Call or 1000-Worker Direct Call fixture")
    parser.add_argument("--case", choices=CASES + DIRECT_CASES + DIAGNOSIS_CASES + RPC_TASK_CASES, help="Diagnostic subset; never a complete suite result")
    parser.add_argument("--repetitions", type=int, choices=(1, 3), default=1,
                        help="Same-version RPC repetitions with rotated path order; formal measurements only")
    parser.add_argument("--diagnostics", choices=("off", "jfr"), default="off", help="Bounded private JFR recording; excluded from performance comparison")
    parser.add_argument("--diagnostic-pair", action="store_true", help="One same-host A/B JFR pair, never a formal benefit comparison")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--assignment-batch-limit", type=assignment_limit, default=100,
                        help="Instance assignment ceiling (1..1000); supply still follows Matching deficits")
    parser.add_argument("--allow-nonreference-host", action="store_true", help="Local diagnostics only; no reference performance claim")
    parser.add_argument("--lane-rates", type=lane_rates, help="Lane only: comma-separated subset of 500,1000,2000")
    parser.add_argument("--lane-modes", type=lane_modes, help="Lane only: comma-separated subset of open,saturation")
    parser.add_argument("--merge-lane", type=Path, help="Merge the per-job lane evidence found below this directory")
    parser.add_argument("--merge-jobs", help="Comma-separated lane job names expected by --merge-lane")
    parser.add_argument("--lane-attribution", choices=("on", "off"), default="on",
                        help="Lane only: Server Dispatch Owner events for per-case attribution")
    options = parser.parse_args()
    if options.merge_lane:
        jobs = tuple(job for job in (options.merge_jobs or "").split(",") if job)
        if not jobs:
            parser.error("--merge-lane requires --merge-jobs")
        output = fresh_output(options.output_root)
        merged = merge_lane(options.merge_lane, jobs)
        write_json(output / "evidence/lane-summary.json", merged)
        (output / "evidence/summary.md").write_text(lane_markdown(merged), encoding="utf-8")
        print(json.dumps({"status": merged["status"], "cases": merged["caseCounts"]}), flush=True)
        return 0 if merged["status"] == "passed" else 1
    if options.suite == "lane" and (options.baseline_ref or options.case or options.diagnostics != "off"
                                    or options.diagnostic_pair or options.assignment_batch_limit != 100):
        parser.error("The lane fixes its own configuration; baseline, case, diagnostics "
                     "and assignment ceiling options do not apply")
    if options.suite != "lane" and (options.lane_rates or options.lane_modes or options.lane_attribution != "on"):
        parser.error("--lane-rates, --lane-modes and --lane-attribution apply only to --suite lane")
    try:
        if options.suite != "lane":
            validate_repetitions(options.suite, options.repetitions, options.baseline_ref, options.diagnostics, options.diagnostic_pair)
        purpose, orders = execution_mode(options.baseline_ref, options.diagnostics, options.diagnostic_pair)
        if options.repetitions == 3:
            purpose, orders = "same_version_repetitions", (("B",),) * 3
    except ValueError as error:
        parser.error(str(error))
    cases = SUITES[options.suite]
    if options.case and options.case not in cases:
        parser.error("Selected case does not belong to --suite")
    if sys.platform != "linux":
        parser.error("Linux with Docker and /proc is required")
    os_release = platform.freedesktop_os_release()
    reference = os_release.get("ID") == "ubuntu" and os_release.get("VERSION_ID") == "24.04"
    if options.suite in ("direct-diagnosis", "rpc-diagnosis", "nightly", "lane"):
        reference = reference and os.cpu_count() == 4
    if not reference and not options.allow_nonreference_host:
        parser.error("Reference environment is Ubuntu 24.04; use --allow-nonreference-host only for diagnostics")
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
    if options.suite == "lane":
        return main_lane(options, output, run_started, reference, os_release, java, image_id)
    versions = {"B": command(["git", "rev-parse", "HEAD"])}
    roots = {"B": ROOT}
    baseline = None
    runs = []
    final = {"status": "failed", "referenceHost": reference, "completeSuite": options.case is None,
             "suite": options.suite, "assignmentBatchLimit": options.assignment_batch_limit,
             "fixtureVersion": 4 if options.suite in ("task", "rpc-diagnosis", "nightly") else 2 if options.suite == "direct-diagnosis" else 1,
             "repetitions": options.repetitions, "expectedCases": list((options.case,) if options.case else cases),
             "diagnostics": options.diagnostics,
             "purpose": purpose,
             "os": os_release, "java": java.strip(), "cpuCount": os.cpu_count(),
             "machine": platform.machine(), "kernel": platform.release(),
             "redisImageId": image_id,
             "harnessCommit": versions["B"], "worktreeDirty": bool(command(["git", "status", "--porcelain"])),
             "versions": versions, "runs": runs}
    try:
        if options.baseline_ref:
            versions["A"] = command(["git", "rev-parse", "--verify", options.baseline_ref + "^{commit}"])
            baseline = output / "baseline-checkout"
            command(["git", "worktree", "add", "--detach", baseline, versions["A"]])
            roots["A"] = baseline
            build(baseline)
        if not options.skip_build:
            build(ROOT, harness=True)
        deadline = run_started + (120 if baseline or options.repetitions == 3 else 45) * 60
        for pair, order in enumerate(orders):
            for version in order:
                ordered_cases = repetition_cases(pair) if options.suite in ("rpc-diagnosis", "nightly") else cases
                if options.suite == "nightly":
                    ordered_cases += ("mixed-500",)
                for case in (options.case,) if options.case else ordered_cases:
                    print(f"performance pair={pair + 1} version={version} case={case}", flush=True)
                    result = run_case(roots[version], case, output / f"pair-{pair + 1}" / version / case, version, deadline,
                                      options.diagnostics, options.assignment_batch_limit)
                    result["pair"] = pair
                    runs.append(result)
                    write_json(output / "evidence/summary.json", final)
                    print("performance result " + json.dumps({"repetition": pair + 1, "version": version,
                        "case": case, "status": result["status"], "generatorLimited": result.get("generatorLimited"),
                        "unobservedAcceptedAfterDrain": result.get("acceptedResultsAfterDrain", {}).get("not_observed")}), flush=True)
                    if result["status"] != "passed" and options.suite not in ("rpc-diagnosis", "nightly"):
                        raise RuntimeError(f"Case {case} failed")
        if options.suite in ("rpc-diagnosis", "nightly"):
            require_manifest(runs, (options.case,) if options.case else cases, options.repetitions)
        final["status"] = "passed"
        if baseline and not options.diagnostic_pair:
            final["comparison"] = diagnosis_comparison(runs, cases) if options.suite == "direct-diagnosis" else comparison(runs, cases)
            if final["comparison"]["status"] == "regressed":
                final["status"] = "regressed"
    except Exception as error:
        final.update(status="failed", failure=type(error).__name__ + ": " + str(error))
    finally:
        if baseline and baseline.exists():
            try:
                command(["git", "worktree", "remove", baseline], timeout=30)
            except subprocess.CalledProcessError:
                # Build products are ignored; preserve the checkout if unexpected edits prevent removal.
                final["baselineCheckoutRetained"] = True
        write_json(output / "evidence/summary.json", final)
        (output / "evidence/summary.md").write_text(markdown_summary(final), encoding="utf-8")
    print(json.dumps({"status": final["status"], "runs": len(runs), "evidence": str(output / "evidence/summary.json")}), flush=True)
    return 0 if final["status"] == "passed" else 1


def main_lane(options, output, run_started, reference, os_release, java, image_id):
    if not options.skip_build:
        build(ROOT, harness=True)
    rates = options.lane_rates or LANE_RATES
    modes = options.lane_modes or LANE_MODES
    plan = lane_plan(rates, options.repetitions, modes)
    result = run_lane(ROOT, output, run_started + LANE_WORLD_SECONDS + len(plan) * LANE_CASE_SECONDS,
                      rates, options.repetitions, options.lane_attribution == "on", modes)
    result.update(referenceHost=reference, os=os_release, java=java.strip(), cpuCount=os.cpu_count(),
                  machine=platform.machine(), kernel=platform.release(), redisImageId=image_id,
                  harnessCommit=command(["git", "rev-parse", "HEAD"]),
                  worktreeDirty=bool(command(["git", "status", "--porcelain"])))
    write_json(output / "evidence/lane-summary.json", result)
    (output / "evidence/summary.md").write_text(lane_markdown(result), encoding="utf-8")
    print(json.dumps({"status": result["status"], "cases": result.get("caseCounts"),
                      "evidence": str(output / "evidence/lane-summary.json")}), flush=True)
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
