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
          "rpc-diagnosis": RPC_CASES, "nightly": NIGHTLY_CASES}
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


def build(root, harness=False):
    tasks = [":server_jvm:bootJar", ":scenario_workers_jvm:installDist"]
    if harness:
        tasks.append(":integrations:worker-call-performance:installDist")
    # Keep builds outside all measurement windows.
    result = subprocess.run([str(root / "gradlew"), "--no-daemon", *tasks], cwd=root, timeout=900)
    result.check_returncode()


def fingerprint(root):
    files = ("server_jvm/src/main/resources/application.yaml", "server_jvm/src/main/resources/application-scenario-workers.yaml")
    return {path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in files}


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
            f"Status: **{final['status']}**. Reference host: {final['referenceHost']}. Complete manifest: {final['completeSuite']}.", "",
            "Same-version observations under unchanged policy. Each main case uses 1000 Workers; mixed-500 retains its original 100 Workers and 30 seconds.", "",
            "| Repetition | Case | Window | Sent / planned | HTTP responses/s | Original success | Successful cohort/s | Accepted success after drain | Success p99 ms | Limited |",
            "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for row in final["runs"]:
            for name, window in {"whole": row, **row.get("windows", {})}.items():
                if "sent" not in window:
                    lines.append(f"| {row['pair'] + 1} | {row['case']} | {name}: {row['status']} | — | — | — | — | — | — | — |")
                    continue
                drained = f"{window['acceptedSuccessRateAfterDrain']:.2%}" if "acceptedSuccessRateAfterDrain" in window else "N/A (Direct)"
                lines.append(f"| {row['pair'] + 1} | {row['case']} | {name} | {window['sent']}/{window['planned']} "
                    f"| {window['httpResponsesDuringWindowPerSecond']:.2f} | {window['successRate']:.2%} "
                    f"| {window['successfulCohortPerSecond']:.2f} | {drained} "
                    f"| {window['successfulCallLatencyMillis']['p99']:.2f} | {window['generatorLimited']} |")
        lines += ["", "Original success uses sent requests; drain uses HTTP-accepted Items and never rewrites call latency. "
            "Planned cohorts and responses arriving within a window are separate. Limited windows cannot quantify capacity. "
            "Pass means the finite measurement contract passed, not a QPS SLA or an A/B improvement. "
            "Task budget is at most 100 checked Items per round plus 100ms after completion; sampled stages are observations, not finality.", ""]
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


def run_case(root, case, output, version, deadline, diagnostics="off"):
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
            "presetSelection": {"name": "DEFAULT", "assignmentIntervalMillis": 100,
                "resultIdleIntervalMillis": 100, "serviceabilityDispatchEnabled": False},
            "redisImage": REDIS_IMAGE, "maximumInFlight": 4096, "warmupSeconds": 20,
            "measurementSeconds": seconds, "httpRequestTimeoutSeconds": 5, "waitTimeoutMillis": 1000,
            "diagnostics": diagnostics, "maximumPlannedRequests": 300000,
            "diagnosticsSettingsSha256": hashlib.sha256((MODULE / "diagnostics.jfc").read_bytes()).hexdigest(),
            "jfrOptionsByRole": {role: jfr_options(private, role, diagnostics) for role in ("server", "host", "harness")},
            "callPath": "DIRECT_CALL" if direct else "TASK", "drainSeconds": None if direct else 180})
        jars = [p for p in (root / "server_jvm/build/libs").glob("xa-mass-server-jvm-*.jar") if not p.name.endswith("-plain.jar")]
        jar = max(jars, key=lambda p: p.stat().st_mtime_ns)
        processes["server"] = start_process(["java", *JVM, *jfr_options(private, "server", diagnostics), "-jar", jar,
            *(f"--{key}={value}" for key, value in flags.items())], private / "server.log", env)
        sampler.register("server", processes["server"])
        wait_http("http://127.0.0.1:18082/actuator/health/readiness", processes["server"], sampler, min(deadline, time.monotonic() + 180))
        inventory = private / "data/scenario-workers"
        materialize_inventory(inventory, {GROUP: tuple({"runtime": "java", "capability": "string-utils"} for _ in range(worker_count))})
        assembly = private / "capabilities.json"
        write_json(assembly, {GROUP: {"eventCodes": ["extension.worker.string.md5", "extension.worker.lab.delay"],
            "requestTimeoutMillis": 60_000, "reconnectPolicy": {"maxUnstableAttempts": 600,
            "reconnectIntervalMillis": 500, "stableConnectionDurationMillis": 10_000}}})
        write_json(evidence / "host-configuration.json", json.loads(assembly.read_text()))
        processes["host"] = start_process(["java", *JVM, *jfr_options(private, "host", diagnostics), "-cp", root / "scenario_workers_jvm/build/install/xa-mass-scenario-workers/lib/*",
            "com.xa.mass.scenarioworkers.ScenarioWorkerHostMain", "--runtime-api-base-url=http://127.0.0.1:18082",
            f"--sandbox-root={inventory}", "--control-port=18086", f"--capability-assembly={assembly}"], private / "host.log", env)
        sampler.register("host", processes["host"])
        wait_http("http://127.0.0.1:18086/lab/v1/workers", processes["host"], sampler, min(deadline, time.monotonic() + 180))
        harness_output = evidence / "harness"
        processes["harness"] = start_process(["java", *JVM, *jfr_options(private, "harness", diagnostics), "-cp", ROOT / "integrations/worker-call-performance/build/install/xa-mass-worker-call-performance/lib/*",
            "com.xa.mass.integration.workercallperformance.WorkerCallPerformanceMain", f"--case={case}",
            f"--output={harness_output}"], private / "harness.log", env)
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
    if actual != expected or any(run["status"] != "passed" for run in runs):
        raise RuntimeError("Incomplete or duplicate performance case manifest")


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
    parser.add_argument("--allow-nonreference-host", action="store_true", help="Local diagnostics only; no reference performance claim")
    options = parser.parse_args()
    try:
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
    if options.suite in ("direct-diagnosis", "rpc-diagnosis", "nightly"):
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
    versions = {"B": command(["git", "rev-parse", "HEAD"])}
    roots = {"B": ROOT}
    baseline = None
    runs = []
    final = {"status": "failed", "referenceHost": reference, "completeSuite": options.case is None,
             "suite": options.suite,
             "fixtureVersion": 3 if options.suite in ("rpc-diagnosis", "nightly") else 2 if options.suite == "direct-diagnosis" else 1,
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
                    result = run_case(roots[version], case, output / f"pair-{pair + 1}" / version / case, version, deadline, options.diagnostics)
                    result["pair"] = pair
                    runs.append(result)
                    write_json(output / "evidence/summary.json", final)
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


if __name__ == "__main__":
    raise SystemExit(main())
