"""Linux process-resource evidence shared by the loaded Worker runners."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import threading
import time

RESOURCE_SAMPLE_INTERVAL_SECONDS = 5.0
MAXIMUM_RESOURCE_SAMPLE_GAP_SECONDS = 15.0


def _append_jsonl(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(value, separators=(",", ":"), sort_keys=True))
        output.write("\n")


class _ResourceSamplingError(RuntimeError):
    pass


class _ProcessSampler:

    def __init__(
        self,
        path: Path,
        *,
        interval_seconds: float = RESOURCE_SAMPLE_INTERVAL_SECONDS,
        maximum_gap_seconds: float = MAXIMUM_RESOURCE_SAMPLE_GAP_SECONDS,
    ) -> None:
        self._path = path
        self._interval_seconds = interval_seconds
        self._maximum_gap_seconds = maximum_gap_seconds
        self._processes: dict[str, subprocess.Popen[str]] = {}
        self._lock = threading.Lock()
        self._sample_lock = threading.Lock()
        self._closed = threading.Event()
        self._thread = threading.Thread(
            target=self._run,
            name="worker-loaded-recovery-sampler",
            daemon=True,
        )
        self._summary: dict[str, dict[str, float | int]] = {}
        self._previous_cpu: dict[str, tuple[int, float, int]] = {}
        self._failure: BaseException | None = None
        self._failure_details: dict[str, object] | None = None
        self._started_at: int | None = None
        self._finished_at: int | None = None
        self._last_periodic_at: int | None = None
        self._last_periodic_monotonic = time.monotonic()
        self._periodic_cycles = 0
        self._maximum_periodic_gap_millis = 0
        self._exited_during_sample: list[dict[str, object]] = []

    def start(self) -> None:
        self._started_at = int(time.time() * 1_000)
        self._last_periodic_monotonic = time.monotonic()
        self._thread.start()

    def register(self, owner: str, process: subprocess.Popen[str]) -> None:
        self.check()
        with self._lock:
            self._processes[owner] = process
            self._previous_cpu.pop(owner, None)

    def unregister(self, owner: str) -> None:
        with self._lock:
            self._processes.pop(owner, None)
            self._previous_cpu.pop(owner, None)

    def _record_failure(
        self,
        error: BaseException,
        owner: str | None = None,
        pid: int | None = None,
    ) -> None:
        with self._lock:
            if self._failure is None:
                self._failure = error
                self._failure_details = {
                    "type": type(error).__name__,
                    "message": (str(error) or type(error).__name__)[:300],
                    "atEpochMillis": int(time.time() * 1_000),
                    "process": owner,
                    "pid": pid,
                }

    def check(self) -> None:
        problem = None
        with self._lock:
            if self._failure is None:
                if self._started_at is None:
                    problem = "resource sampler has not started"
                elif not self._closed.is_set():
                    if not self._thread.is_alive():
                        problem = "resource sampler thread stopped unexpectedly"
                    elif (
                        time.monotonic() - self._last_periodic_monotonic
                        > self._maximum_gap_seconds
                    ):
                        problem = "resource sampler exceeded the maximum periodic sample gap"
        if problem is not None:
            self._record_failure(RuntimeError(problem))
        with self._lock:
            failure, details = self._failure, self._failure_details
        if failure is not None:
            raise _ResourceSamplingError(f"resource sampling failed: {details}") from failure

    def _run(self) -> None:
        try:
            while not self._closed.wait(self._interval_seconds):
                self.sample_now(periodic=True)
                now = time.monotonic()
                with self._lock:
                    gap = now - self._last_periodic_monotonic
                    self._maximum_periodic_gap_millis = max(
                        self._maximum_periodic_gap_millis,
                        round(gap * 1_000),
                    )
                    self._last_periodic_monotonic = now
                    self._last_periodic_at = int(time.time() * 1_000)
                    self._periodic_cycles += 1
                if gap > self._maximum_gap_seconds:
                    self._record_failure(RuntimeError(
                        "resource sampler exceeded the maximum periodic sample gap"
                    ))
                    return
        except BaseException as error:
            self._record_failure(error)

    def sample_now(
        self,
        checkpoint: str | None = None,
        *,
        periodic: bool = False,
    ) -> dict[str, dict[str, int]]:
        self.check()
        observed: dict[str, dict[str, int]] = {}
        owner, pid = None, None
        try:
            while not self._sample_lock.acquire(timeout=0.2):
                self.check()
            try:
                self.check()
                with self._lock:
                    processes = tuple(self._processes.items())
                for owner, process in processes:
                    pid = process.pid
                    if process.poll() is not None:
                        continue
                    try:
                        sample = _process_sample(pid)
                    except (FileNotFoundError, ProcessLookupError, PermissionError) as error:
                        # A dying Linux process can lose /proc/fd access before waitpid
                        # observes exit. Confirm exit once; never retry the resource read.
                        if process.poll() is None:
                            try:
                                process.wait(timeout=1)
                            except subprocess.TimeoutExpired:
                                raise error
                        with self._lock:
                            self._exited_during_sample.append({
                                "process": owner,
                                "pid": pid,
                                "errorType": type(error).__name__,
                                "atEpochMillis": int(time.time() * 1_000),
                            })
                        continue
                    sampled_at = time.monotonic()
                    value = {
                        "atEpochMillis": int(time.time() * 1_000),
                        "process": owner,
                        "pid": pid,
                        "sampleKind": (
                            "periodic" if periodic
                            else "checkpoint" if checkpoint else "final"
                        ),
                        **sample,
                    }
                    if checkpoint is not None:
                        value["checkpoint"] = checkpoint
                    with self._lock:
                        current = self._summary.setdefault(owner, {})
                        current["sampleCount"] = current.get("sampleCount", 0) + 1
                        current["periodicSampleCount"] = (
                            current.get("periodicSampleCount", 0) + int(periodic)
                        )
                        current.setdefault("firstSampleAtEpochMillis", value["atEpochMillis"])
                        current["lastSampleAtEpochMillis"] = value["atEpochMillis"]
                        for field, target in (
                            ("rssBytes", "maximumRssBytes"),
                            ("nativeThreads", "maximumNativeThreads"),
                            ("openFileDescriptors", "maximumOpenFileDescriptors"),
                        ):
                            current[target] = max(current.get(target, 0), sample[field])
                        previous = self._previous_cpu.get(owner)
                        if previous is not None and previous[0] == pid:
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
                            pid,
                            sampled_at,
                            sample["cpuTimeMillis"],
                        )
                    # File I/O must not hold the health-state lock: the main thread
                    # still needs to detect a stalled writer within the sample budget.
                    _append_jsonl(self._path, value)
                    observed[owner] = dict(sample)
            finally:
                self._sample_lock.release()
        except BaseException as error:
            self._record_failure(error, owner, pid)
            self.check()
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
        self.check()
        if self.diagnostics()["status"] != "complete":
            raise _ResourceSamplingError("resource sampling coverage is incomplete")
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

    def diagnostics(self) -> dict[str, object]:
        with self._lock:
            return {
                "status": (
                    "complete"
                    if self._finished_at is not None
                    and self._failure is None
                    and self._periodic_cycles > 0
                    else "incomplete"
                ),
                "intervalMillis": round(self._interval_seconds * 1_000),
                "maximumAllowedGapMillis": round(self._maximum_gap_seconds * 1_000),
                "maximumPeriodicGapMillis": self._maximum_periodic_gap_millis,
                "periodicCycles": self._periodic_cycles,
                "startedAtEpochMillis": self._started_at,
                "lastPeriodicAtEpochMillis": self._last_periodic_at,
                "finishedAtEpochMillis": self._finished_at,
                "failure": dict(self._failure_details) if self._failure_details else None,
                "confirmedExitsDuringSample": list(self._exited_during_sample),
                "processes": {
                    owner: {
                        field: value[field]
                        for field in (
                            "sampleCount",
                            "periodicSampleCount",
                            "firstSampleAtEpochMillis",
                            "lastSampleAtEpochMillis",
                        )
                    }
                    for owner, value in self._summary.items()
                },
            }

    def close(self) -> None:
        if self._closed.is_set():
            return
        self._closed.set()
        if self._started_at is not None:
            self._thread.join(timeout=10)
            if self._thread.is_alive():
                self._record_failure(RuntimeError(
                    "resource sampler did not stop within ten seconds"
                ))
            gap = time.monotonic() - self._last_periodic_monotonic
            with self._lock:
                self._maximum_periodic_gap_millis = max(
                    self._maximum_periodic_gap_millis,
                    round(gap * 1_000),
                )
            if gap > self._maximum_gap_seconds:
                self._record_failure(RuntimeError(
                    "resource sampler exceeded the maximum periodic sample gap"
                ))
        self._finished_at = int(time.time() * 1_000)


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


