"""Performance lane run records, nightly trend comparison and per-case A/B decisions (README.md#trend-and-ab).

Pure functions only: the runner owns processes and the workflow owns the data branch.
"""
from __future__ import annotations

from collections import Counter
import math
import statistics

# Bump when case semantics, fixture or metrics change; records of another version are never compared.
LANE_CONFIG_VERSION = 3
HISTORY_WINDOW = 7
MIN_HISTORY = 7
# A job whose host calibration deviates more than this from the history median is not judged.
HOST_TOLERANCE = 0.25
PROVISIONAL_BAND = 0.10
AB_MIN_PAIRS = 2
AB_MAX_PAIRS = 5

# metric -> (extractor over one case record, lower_is_better, normalize by host speed)
METRICS = {
    "p99LatencyMillis": (lambda r: (r.get("successfulCallLatencyMillis") or {}).get("p99"), True, True),
    "p50LatencyMillis": (lambda r: (r.get("successfulCallLatencyMillis") or {}).get("p50"), True, True),
    "successWithinWait": (lambda r: r.get("successWithinWait"), False, False),
    "completedPerSecond": (lambda r: r.get("completedPerSecond"), False, True),
}
OPEN_METRICS = ("p99LatencyMillis", "p50LatencyMillis", "successWithinWait", "completedPerSecond")
SATURATION_METRICS = ("completedPerSecond",)


def primary_metric(case):
    """A/B decision metric. One 30s window's p99 varies about +-35% on the same version, while p50
    stays within about 1.5%, so open-loop decisions use p50 and report pooled p99 separately."""
    return "completedPerSecond" if case.startswith("sat-") else "p50LatencyMillis"


def _stats(values):
    values = [v for v in values if isinstance(v, (int, float))]
    if not values:
        return None
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "n": len(values)}


def run_record(merged, meta):
    """Compact per-run record: host calibration per job and per-case aggregates over repetitions."""
    by_case = {}
    for case in merged.get("cases", []):
        by_case.setdefault(case["case"], []).append(case)
    cases = {}
    for name, rows in by_case.items():
        passed = [r for r in rows if r.get("status") == "passed"]
        names = SATURATION_METRICS if name.startswith("sat-") else OPEN_METRICS
        cases[name] = {"job": rows[0].get("job"), "mode": rows[0].get("mode", "open"),
                       "statuses": dict(Counter(r.get("status") for r in rows)),
                       "metrics": {metric: _stats([METRICS[metric][0](r) for r in passed]) for metric in names}}
    calibration = {job["job"]: job.get("calibration") for job in merged.get("jobs", []) if job.get("calibration")}
    configurations = {job["job"]: job.get("configurationId") for job in merged.get("jobs", [])}
    reference = all(job.get("referenceHost", True) for job in merged.get("jobs", []))
    return dict(meta, laneConfigVersion=LANE_CONFIG_VERSION, configurations=configurations, referenceHost=reference, status=merged.get("status"),
                calibration=calibration, cases=cases)


def host_speed(calibration):
    return (calibration or {}).get("cpuParallelOpsPerSecond")


def normalize(value, metric, speed, reference):
    """Express a value as if measured on the reference host speed."""
    _, lower_is_better, scaled = METRICS[metric]
    if value is None or not scaled or not speed or not reference:
        return value
    factor = speed / reference
    return value * factor if lower_is_better else value / factor


def history_for(record, history):
    comparable = [h for h in history if h.get("ref") == record.get("ref")
                  and h.get("laneConfigVersion") == LANE_CONFIG_VERSION and h.get("runId") != record.get("runId")
                  and h.get("referenceHost", True) and record.get("referenceHost", True)
                  and h.get("configurations", {}) == record.get("configurations", {})]
    return sorted(comparable, key=lambda h: h.get("createdAt", ""), reverse=True)[:HISTORY_WINDOW]


def compare(record, history):
    """Nightly trend: flag cases whose normalized median leaves the recent history range in the worse direction."""
    recent = history_for(record, history)
    result = {"historyRuns": len(recent), "status": "insufficient-history" if len(recent) < MIN_HISTORY else "compared",
              "hosts": {}, "findings": []}
    if len(recent) < MIN_HISTORY:
        return result
    references = {}
    for job, calibration in record.get("calibration", {}).items():
        speeds = [host_speed(h.get("calibration", {}).get(job)) for h in recent]
        speeds = [s for s in speeds if s]
        reference = statistics.median(speeds) if speeds else None
        references[job] = reference
        current = host_speed(calibration)
        factor = current / reference if current and reference else None
        result["hosts"][job] = {"speedFactor": factor,
                                "qualified": factor is not None and abs(factor - 1) <= HOST_TOLERANCE}
    for name, case in record.get("cases", {}).items():
        job = case.get("job")
        host = result["hosts"].get(job, {})
        if not host.get("qualified"):
            result["findings"].append({"case": name, "verdict": "host-unqualified"})
            continue
        majority = lambda statuses: max(statuses, key=statuses.get) if statuses else None
        past_status = Counter(majority(h["cases"][name]["statuses"]) for h in recent if name in h.get("cases", {}))
        current_status = majority(case["statuses"])
        if past_status and current_status != past_status.most_common(1)[0][0]:
            result["findings"].append({"case": name, "verdict": "suspect", "reason": "status",
                                       "current": current_status, "history": past_status.most_common(1)[0][0]})
        for metric, stats in case.get("metrics", {}).items():
            if not stats:
                continue
            lower = METRICS[metric][1]
            reference = references.get(job)
            past = []
            for h in recent:
                past_case = h.get("cases", {}).get(name, {})
                past_stats = (past_case.get("metrics") or {}).get(metric)
                if past_stats:
                    past.append(normalize(past_stats["median"], metric,
                                          host_speed(h.get("calibration", {}).get(past_case.get("job"))), reference))
            if len(past) < MIN_HISTORY:
                continue
            current = normalize(stats["median"], metric, host_speed(record["calibration"].get(job)), reference)
            half_spread = (stats["max"] - stats["min"]) / 2
            worse = current - half_spread > max(past) if lower else current + half_spread < min(past)
            if worse:
                result["findings"].append({"case": name, "metric": metric, "verdict": "suspect",
                                           "current": current, "historyMin": min(past), "historyMax": max(past)})
    return result


def band(case, history, ref):
    """Same-version relative spread of the primary metric across recent runs; provisional without history."""
    metric = primary_metric(case)
    spreads = []
    for h in history:
        if h.get("ref") != ref or h.get("laneConfigVersion") != LANE_CONFIG_VERSION or not h.get("referenceHost", True):
            continue
        # Legacy A/B retains its own fixed world. A configured saturation job is a different
        # fixture and cannot provide that world's noise band.
        job = (h.get("cases", {}).get(case) or {}).get("job")
        if h.get("configurations", {}).get(job) is not None:
            continue
        stats = ((h.get("cases", {}).get(case) or {}).get("metrics") or {}).get(metric)
        if stats and stats["median"]:
            spreads.append((stats["max"] - stats["min"]) / stats["median"])
    if len(spreads) < 3:
        return {"relative": PROVISIONAL_BAND, "provisional": True, "runs": len(spreads)}
    return {"relative": max(PROVISIONAL_BAND / 2, statistics.median(spreads)), "provisional": False, "runs": len(spreads)}


def ab_decide(case, pairs, relative_band):
    """Sequential A/B: each pair is (A value, B value) of the primary metric or None when not comparable.

    Every comparable pair must agree: all within the band is "no-difference"; all beyond it in the
    same direction is "worse" or "better". Any disagreement asks for another pair, and after the
    maximum the result is "inconclusive". Positive deltas mean B is worse.
    """
    lower = METRICS[primary_metric(case)][1]
    deltas = []
    for a, b in pairs:
        if a is None or b is None or not a:
            continue
        change = (b - a) / a
        deltas.append(change if lower else -change)
    decision = "continue"
    if len(deltas) >= AB_MIN_PAIRS:
        if all(abs(d) <= relative_band for d in deltas):
            decision = "no-difference"
        elif all(d > relative_band for d in deltas):
            decision = "worse"
        elif all(d < -relative_band for d in deltas):
            decision = "better"
    if decision == "continue" and len(pairs) >= AB_MAX_PAIRS:
        decision = "inconclusive"
    return {"decision": decision, "deltas": deltas, "band": relative_band}


def pooled_percentile(latencies, fraction):
    """Nearest-rank percentile over samples pooled from every pair of one version."""
    if not latencies:
        return None
    ordered = sorted(latencies)
    # Same nearest rank as the Harness: ceil(n x fraction) - 1.
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]
