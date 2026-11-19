"""Performance measurement projections over the current Harness evidence format.

Stdlib only: the runner imports this sibling; a controller pins a copy for its
Run and loads it only while collecting. This module owns no remote lifecycle.
Public transport applies its secret/identity scrub after this projection.
"""
from __future__ import annotations

from collections import Counter
import json
import math
from pathlib import Path
import re


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
              "workerSamples": len(case["workerSamples"]) if isinstance(case.get("workerSamples"), list) else None}
    if cost:
        # An eventual drain count is not a count inside the sampled resource interval.
        record["cost"] = dict(cost, redisCommandsPerCompleted=None, serverCpuMillisPerCompleted=None)
    return record


def lane_markdown(result, *, defaults=None, trend_policy=None):
    defaults, trend_policy = defaults or {}, trend_policy or {}
    timings = result.get("timingsMillis", {})
    lines = ["# Performance Lane", "", f"Status: **{result.get('status', 'unknown')}**. Cases: {result.get('caseCounts', {})}.", "",
             f"{len(result.get('groups', defaults.get('groups', []))) or 'unavailable'} Groups, one Adapter, assignment ceiling "
             f"{result.get('assignmentBatchLimit', defaults.get('assignmentBatchLimit', 'unavailable'))}. Offered load splits evenly across the Groups.", "",
             "| Rep | Case | Status | HTTP success ratio | Completed/s | Success p50 / p99 ms | Checked Items/s | Shortfall | Acquisition rejected | Lease-held peak | Redis cmds / completed |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in result.get("cases", []):
        latency = r.get("successfulCallLatencyMillis") or {}
        attribution = r.get("attribution") or {}
        cost = r.get("cost") or {}
        status = r.get("status", "unknown") + (f" ({', '.join(r['invalidReasons'])})" if r.get("invalidReasons") else "") \
            + (f" ({r['failureReason']})" if r.get("failureReason") else "") \
            + (f" (admitted {r['admittedPerSecond']:.0f}/s)" if r.get("status", "unknown") == "saturated" and r.get("admittedPerSecond") else "")
        pct = lambda value: f"{value:.2%}" if isinstance(value, (int, float)) else "—"
        num = lambda value, fmt: format(value, fmt) if isinstance(value, (int, float)) else "—"
        completed = num(r.get("completedPerSecond"), ".1f")
        lines.append(f"| {r.get('repetition', 'unavailable')} | {r.get('case', 'unavailable')} | {status} | {pct(r.get('successWithinWait'))} "
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
            lines.append(f"| {world['job']} (existing deployment) | {world.get('workersPerGroup', 'unavailable')} "
                         f"| {world.get('cpuCount') or '—'} | not collected / {world.get('hostMaxHeap', '—')} / 1024 "
                         "| original open-loop windows | per case | not collected |")
        else:
            lines.append(f"| {world['job']} (legacy world) | {world.get('workersPerGroup', defaults.get('workersPerGroup', 'unavailable'))} "
                         f"| {world.get('cpuCount') or '—'} | 1024 / 1024 / 1024 | per case | per case | unconstrained |")
    if result.get("trend"):
        trend = result["trend"]
        lines += ["", f"Trend: **{trend['status']}** against {trend['historyRuns']} comparable run(s) "
                  f"(needs {trend_policy.get('minHistory', 'unavailable')} from the same ref and lane config version {trend_policy.get('laneConfigVersion', 'unavailable')})."]
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
        lines += [f"| {role} | {peak.get('peakNativeThreads', '—')} | {peak.get('peakFileDescriptors', '—')} | {format(peak['peakRssBytes'] / 2**20, '.0f') if isinstance(peak.get('peakRssBytes'), (int, float)) else '—'} |"
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


def safe_result(raw):
    top = ("lane", "executionEnvironment", "referenceHost", "status", "timingsMillis", "assignmentBatchLimit",
           "runtimeUrl", "groups", "workersPerGroup", "artifactManifest", "warmup",
           "serverDeployment", "cpuCount", "java", "os", "machine", "kernel", "notCollected", "resourcePeaks", "hostMaxHeap", "caseCounts", "cleanup", "runnerFailure", "resourceFailure")
    case_keys = ("repetition", "mode", "rate", "path", "case", "status", "invalidReasons", "failureReason", "fastFail",
                 "measurementStartedEpochMillis", "measurementEndedEpochMillis", "completedPerSecond", "admittedPerSecond",
                 "completedCount", "workersBound", "seedMillis", "leaseHeldPeakRatio", "successWithinWait",
                 "successfulCallLatencyMillis", "generatorLimited", "httpVersions", "acceptedResultsAfterDrain", "workerSamples", "metrics", "originalMetrics", "originalMetricsStatus")
    value = {key: raw[key] for key in top if key in raw}
    value["cases"] = [{key: row[key] for key in case_keys if key in row} for row in raw.get("cases", [])]
    # The summary's workerSamples field is a count, never the private sample/identity arrays.
    for row in value["cases"]:
        if not isinstance(row.get("workerSamples"), int):
            row.pop("workerSamples", None)
    return value


def case_directory(row):
    name, repetition = row.get("case"), row.get("repetition")
    if (isinstance(name, str) and re.fullmatch(r"(?:task-any|task-targeted|direct)-(?:500|1000|2000)", name)
            and type(repetition) is int and repetition > 0):
        return Path(f"r{repetition}") / name
    return None


def original_metrics(result, cases):
    """Retain original counters from the known case shape, without filling gaps."""
    expected_samples = []
    for case in cases:
        relative = case_directory(case)
        if relative is None:
            case["originalMetricsStatus"] = "unavailable"
            continue
        try:
            raw = json.loads((result / "evidence/harness" / relative / "case.json").read_text(encoding="utf-8"))
            if not isinstance(raw, dict) or not isinstance(raw.get("metrics"), dict) or not raw["metrics"]:
                raise ValueError("Missing case metrics")
            case["originalMetrics"] = raw["metrics"]
            case["originalMetricsStatus"] = "complete"
            if "warmup" in raw:
                expected_samples.append(relative / "warmup-samples.jsonl")
        except (ValueError, OSError, UnicodeError):
            case["originalMetricsStatus"] = "unavailable"
    return expected_samples


def sample_counts(result, expected_cases=(), *, expected_samples=()):
    """Count only observed rows; formal totals exclude both kinds of warmup."""
    totals, cases, phases, unavailable = Counter(), {}, {}, {}
    base = result / "evidence/harness"
    samples = sorted({*base.rglob("samples.jsonl"), *base.rglob("warmup-samples.jsonl")})
    outcomes = {"succeeded", "failed", "not_observed", "unknown", "not_sent", "protocol_error", "rejected", "timed_out"}
    exceptions = {"HttpConnectTimeoutException", "HttpTimeoutException", "TimeoutException", "ConnectException",
                  "SSLHandshakeException", "SSLException", "UnknownHostException", "SocketException", "IOException",
                  "InterruptedException", "Exception"}
    for path in samples:
        counts = Counter()
        relative = path.relative_to(base)
        phase = "discardedWarmup" if relative.parts[0] == "warmup" else "innerWarmup" if path.name == "warmup-samples.jsonl" else "formal"
        key = phase + "/" + relative.as_posix()
        try:
            with path.open(encoding="utf-8") as source:
                for line in source:
                    try:
                        row = json.loads(line)
                    except ValueError:
                        counts["invalidRows"] += 1
                        continue
                    if (not isinstance(row, dict) or type(row.get("sentOffsetMillis")) not in (int, float)
                            or not math.isfinite(row["sentOffsetMillis"])
                            or type(row.get("httpStatus")) is not int or row["httpStatus"] < 0
                            or not isinstance(row.get("outcome"), str) or row["outcome"] not in outcomes):
                        counts["invalidRows"] += 1
                        continue
                    sent = row["sentOffsetMillis"] >= 0
                    counts["samples"] += 1
                    counts["sent" if sent else "notSent"] += 1
                    if sent and row["httpStatus"] == 0:
                        counts["sentHttp0Unknown"] += 1
                    if row["httpStatus"] == 429:
                        counts["http429"] += 1
                    version = row.get("httpVersion")
                    if isinstance(version, str) and version in {"HTTP_1_1", "HTTP_2"}:
                        counts["httpVersion:" + version] += 1
                    if row.get("detail") == "client-timeout":
                        counts["clientTimeouts"] += 1
                        timeout = row.get("timeoutPhase", "unclassified")
                        if not isinstance(timeout, str) or timeout not in {"connect-tls", "before-headers", "response-body", "unclassified"}:
                            timeout = "unclassified"
                        counts["timeoutPhase:" + timeout] += 1
                    exception = row.get("exceptionType")
                    if exception is not None:
                        counts["exceptionType:" + (exception if isinstance(exception, str) and exception in exceptions else "other")] += 1
                    counts["outcome:" + row["outcome"]] += 1
                    observed = row.get("observedResult")
                    if isinstance(observed, str) and observed in {"succeeded", "failed", "not_observed"}:
                        counts["observed:" + observed] += 1
        except (OSError, UnicodeError) as error:
            unavailable[key] = type(error).__name__
        if not counts:
            unavailable.setdefault(key, "empty-samples")
        cases[key] = dict(counts)
        phases.setdefault(phase, Counter()).update(counts)
        if phase == "formal":
            totals.update(counts)
    for case in expected_cases:
        relative = case_directory(case)
        if relative is not None and not (base / relative / "samples.jsonl").is_file():
            unavailable["formal/" + (relative / "samples.jsonl").as_posix()] = "missing-samples"
    for relative in expected_samples:
        if not (base / relative).is_file():
            phase = "discardedWarmup" if relative.parts[0] == "warmup" else "innerWarmup"
            unavailable[phase + "/" + relative.as_posix()] = "missing-samples"
    status = "unavailable" if not samples else "incomplete" if unavailable or any(c.get("invalidRows") for c in cases.values()) else "complete"
    value = {"status": status, "totals": dict(totals), "phases": {key: dict(value) for key, value in phases.items()}, "cases": cases}
    if unavailable:
        value["unavailable"] = unavailable
    return value


def collect_measurement(result):
    """Project existing evidence only; no workload, cleanup, retry or version dispatch."""
    result = Path(result)
    expected_samples = []
    try:
        raw = json.loads((result / "evidence/lane-summary.json").read_text(encoding="utf-8"))
        if (not isinstance(raw, dict) or not isinstance(raw.get("cases"), list)
                or any(not isinstance(row, dict) for row in raw["cases"])):
            raise ValueError("Invalid summary shape")
        summary = safe_result(raw)
        expected_samples = original_metrics(result, summary["cases"])
        warmup = raw.get("warmup")
        if isinstance(warmup, dict) and case_directory(dict(warmup, repetition=1)) is not None:
            expected_samples.extend(Path("warmup") / warmup["case"] / filename for filename in ("samples.jsonl", "warmup-samples.jsonl"))
        summary["reportCollection"] = {"status": "complete"}
    except (ValueError, OSError) as error:
        raw = {}
        summary = {"status": "unknown", "cases": [], "summaryParseFailure": type(error).__name__,
                   "reportCollection": {"status": "unavailable", "failureType": type(error).__name__}}
    summary["submissionEvidence"] = sample_counts(result, summary["cases"], expected_samples=expected_samples)
    if summary["reportCollection"]["status"] == "complete" and (
            summary["submissionEvidence"]["status"] != "complete"
            or any(row.get("originalMetricsStatus") != "complete" for row in summary["cases"])):
        summary["reportCollection"]["status"] = "incomplete"
    # Regenerate measurements from the same evidence reader, never from a retained Markdown snapshot.
    content = lane_markdown(dict(raw, status=summary.get("status", "unknown"), cases=raw.get("cases", [])))
    content += "\nMeasurement evidence: **" + summary["reportCollection"]["status"] + "**.\n"
    content += "\nSubmission evidence by phase (formal totals exclude warmup):\n\n```json\n" + json.dumps(summary["submissionEvidence"], indent=2, sort_keys=True) + "\n```\n"
    return summary, content
