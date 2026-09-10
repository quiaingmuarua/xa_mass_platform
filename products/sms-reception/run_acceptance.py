"""Finite product proof through public product/platform APIs and the real Java Host."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
import hashlib
import json
from pathlib import Path
import threading
import time
import urllib.error
import uuid

from run_preview import PRODUCT, Preview, all_pages, build, http


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def create(run, app="A", country="CN", seconds=60, request=None):
    return http(run.url, "/api/v1/sms/listeners", {"requestId": request or str(uuid.uuid4()),
            "applicationId": app, "country": country, "listenSeconds": seconds})


def wait_state(run, listener, expected, timeout=20):
    result = {}
    def observe():
        nonlocal result
        result = http(run.url, "/api/v1/sms/listeners/" + listener["id"])
        return result["status"] in expected
    run.wait_for(observe, timeout, "listener state " + "/".join(expected))
    return result


def inject(run, phone, text, sms_id=None):
    return http(run.host, "/sms", {"phone": phone, "text": text,
                "smsId": sms_id or str(uuid.uuid4())})


def all_records(base, path):
    return all_pages(base, path)


def compare(run):
    host = all_records(run.host, "/records")
    observed = all_records(run.url, "/api/v1/sms/listeners")
    by_id = {item["id"]: item for item in observed}
    expected = {(item["listenerId"], item["smsId"]) for item in host if item["status"] == "RECEIVED"}
    actual = {(item["id"], item["sms"]["smsId"]) for item in observed if item["status"] == "RECEIVED"}
    false_success = actual - expected
    missing = expected - actual
    mismatches = sum(1 for item in host if item["listenerId"] not in by_id or
                     item["status"] != by_id[item["listenerId"]]["status"])
    digest = hashlib.sha256(json.dumps(sorted(expected)).encode()).hexdigest()
    return {"hostRecords": len(host), "observedRecords": len(observed), "hostMatched": len(expected),
            "observedSms": len(actual), "missingSmsObservations": len(missing),
            "falseSuccesses": len(false_success), "stateMismatches": mismatches,
            "smsObservationRate": len(expected & actual) / len(expected) if expected else None,
            "matchedIdentityDigest": digest}


def functional(run):
    catalog = http(run.url, "/api/v1/sms/catalog")
    inventory = all_records(run.host, "/inventory")
    require(len(inventory) == 3, "Functional fixture must contain one number per country")
    cn = next(sim["phone"] for sim in inventory if sim["country"] == "CN")
    def properties_observed():
        for country in ("CN", "US", "GB"):
            workers = http(run.url, f"/api/v1/runtime-view/worker-groups/sms-{country.lower()}/workers:preview", 1)["workers"]
            if len(workers) != 1 or workers[0]["workerProperties"].get("country") != country \
                    or workers[0]["workerProperties"].get("simulated") != "true":
                return False
        return True
    run.wait_for(properties_observed, 10, "Adapter baseline Properties observations")
    require(inject(run, cn, "[A] 123456")["status"] == "IGNORED", "SMS without listener was reported")
    listeners = [wait_state(run, create(run, app, request="shared-" + app), {"LISTENING"}) for app in ("A", "B", "C")]
    require({item["phone"] for item in listeners} == {cn}, "A/B/C did not share a number")
    repeated = create(run, "A", request="shared-A")
    require(repeated["id"] == listeners[0]["id"], "Application request dedup failed")
    try:
        create(run, "A", "US", request="shared-A")
        raise AssertionError("Different request with same ID was accepted")
    except urllib.error.HTTPError as error:
        require(error.code == 409, "Expected request conflict")
    first = inject(run, cn, "[A] 123456", "duplicate-sms")
    require(first["listenerId"] == listeners[0]["id"], "A priority failed")
    received = wait_state(run, listeners[0], {"RECEIVED"})
    require(received["phone"] == cn and received["sms"]["code"] == "123456", "Incomplete later business snapshot")
    require(inject(run, cn, "[A] 123456", "duplicate-sms")["status"] == "DUPLICATE", "SMS dedup failed")
    for listener in listeners[1:]:
        require(http(run.url, "/api/v1/sms/listeners/" + listener["id"])["status"] == "LISTENING", "SMS fanned out")
    require(inject(run, cn, "[B] 222222")["listenerId"] == listeners[1]["id"], "B priority failed")
    require(inject(run, cn, "unrelated text")["listenerId"] == listeners[2]["id"], "Wildcard fallback failed")
    for listener in listeners:
        wait_state(run, listener, {"RECEIVED"})

    older = wait_state(run, create(run), {"LISTENING"})
    younger = wait_state(run, create(run), {"LISTENING"})
    require(inject(run, cn, "no matching template")["status"] == "IGNORED", "Nonmatching SMS reported")
    require(inject(run, cn, "[A] 333333")["listenerId"] == older["id"], "Equal priority order unstable")
    wait_state(run, older, {"RECEIVED"})
    cancelled = http(run.url, "/api/v1/sms/listeners/" + younger["id"] + "/cancel", {})
    require(cancelled["status"] in ("CANCELLING", "CANCELLED"), "Cancellation falsely completed")
    wait_state(run, younger, {"CANCELLED"})

    # Duplicate Task delivery uses a NEW Item but the original listening identity and full request.
    task = next(c["taskId"] for c in catalog["countries"] if c["id"] == "CN")
    duplicate_message = str(uuid.uuid4())
    payload = {"listenerId": older["id"], "applicationId": "A", "country": "CN", "listenSeconds": 60,
               "setupDeadline": older["createdAt"] + catalog["limits"]["setupMillis"],
               "templates": next(a["templates"] for a in catalog["applications"] if a["id"] == "A")}
    before = http(run.host, "/metrics")["host"]["listeners"]
    http(run.url, f"/api/v1/tasks/{task}/items:call", {"items": [{"messageId": duplicate_message,
         "eventCode": "extension.worker.sms.listen.start", "payload": payload, "ttlMillis": 10000,
         "workerSelector": []}], "waitTimeoutMillis": 1})
    duplicate_result = {}
    def duplicate_observed():
        nonlocal duplicate_result
        result = http(run.url, f"/api/v1/tasks/{task}/results:load", [duplicate_message])[duplicate_message]
        if result["status"] != "succeeded":
            return False
        duplicate_result = json.loads(result["opaqueResultPayload"])
        return True
    run.wait_for(duplicate_observed, 20, "duplicate Task execution result")
    require(duplicate_result["status"] == "RECEIVED" and duplicate_result["phone"] == cn, "Duplicate execution lost original snapshot")
    require(http(run.host, "/metrics")["host"]["listeners"] == before, "Task duplicate allocated another listener")
    for country in ("US", "GB"):
        listener = wait_state(run, create(run, country=country, seconds=1), {"LISTENING", "EXPIRED"})
        expired = wait_state(run, listener, {"EXPIRED"})
        require(inject(run, expired["phone"], "[A] 111111")["status"] == "IGNORED", "Window boundary failed")
    early = create(run)
    http(run.url, "/api/v1/sms/listeners/" + early["id"] + "/cancel", {})
    wait_state(run, early, {"CANCELLED"})
    # Both the cancellation command and the original Task have actual platform Result evidence.
    run.wait_for(lambda: http(run.url, f"/api/v1/tasks/{task}/results:load", [early["id"] + "-cancel"])
                 [early["id"] + "-cancel"]["status"] == "succeeded", 10, "Task cancellation Result")
    http(run.host, "/traffic/start", {"ratePerSecond": 5, "durationSeconds": 1})
    run.wait_for(lambda: not http(run.host, "/metrics")["traffic"]["running"], 5, "finite auto traffic")
    evidence = compare(run)
    require(evidence["falseSuccesses"] == 0 and evidence["missingSmsObservations"] == 0
            and evidence["stateMismatches"] == 0, "Host and Backend business observations diverged")
    return {"passed": True, "scenario": "functional", "checks": ["country pools", "Adapter Properties baseline", "A/B/C shared number",
            "priority and stable ties", "request conflict", "SMS dedup", "Task duplicate", "no-listener/no-match",
            "expiry window", "targeted cancellation", "cancel during establishment", "finite auto traffic"],
            "comparison": evidence, "metrics": http(run.url, "/api/v1/sms/metrics")}


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    import math
    return ordered[max(0, math.ceil(len(ordered) * p) - 1)]


def concurrency(run):
    rate, duration, total = 200, 60, 12_000
    request_latencies, accepted, failed, starts, schedule_lags = [], [], [], [], []
    permits = threading.BoundedSemaphore(512)
    lock = threading.Lock()
    start = time.monotonic()
    http(run.host, "/traffic/start", {"ratePerSecond": 300, "durationSeconds": 135})
    def submit(index):
        began = time.monotonic()
        try:
            country = "CN" if index % 10 < 7 else "US" if index % 10 < 9 else "GB"
            result = create(run, ("A", "B", "C")[index % 3], country, 60, f"load-{index}")
            with lock:
                accepted.append(result["id"])
                request_latencies.append((time.monotonic() - began) * 1000)
        except Exception as error:
            with lock:
                failed.append(type(error).__name__ + (f":{error.code}" if isinstance(error, urllib.error.HTTPError) else ""))
        finally:
            with lock:
                starts.append(began - start)
                schedule_lags.append(max(0, began - start - index / rate))
            permits.release()
    generator_rejected = 0
    peak_active = 0
    with ThreadPoolExecutor(max_workers=128) as pool:
        for index in range(total):
            delay = start + index / rate - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            if permits.acquire(blocking=False):
                pool.submit(submit, index)
            else:
                generator_rejected += 1
            if index % 200 == 0:
                run.check()
                peak_active = max(peak_active, http(run.host, "/metrics")["host"]["activeListeners"])
            if index and index % 2000 == 0:
                print(f"Offered {index}/{total}; accepted {len(accepted)}; HTTP errors {len(failed)}", flush=True)
    offered_seconds = time.monotonic() - start
    # Wait for definite observed outcomes or the product's explicit observation deadline.
    def finished():
        nonlocal peak_active
        statuses = http(run.url, "/api/v1/sms/metrics")["statuses"]
        peak_active = max(peak_active, http(run.host, "/metrics")["host"]["activeListeners"])
        return not any(statuses.get(state, 0) for state in ("ESTABLISHING", "LISTENING", "CANCELLING"))
    run.wait_for(finished, 110, "finite load observation deadline")
    http(run.host, "/traffic/stop", {})
    metrics = http(run.url, "/api/v1/sms/metrics")
    evidence = compare(run)
    records = all_records(run.url, "/api/v1/sms/listeners")
    established = [r for r in records if "startedAt" in r]
    if established:
        establishment_seconds = max(.001, (max(r["startedAt"] for r in established) - min(r["createdAt"] for r in established)) / 1000)
    else:
        establishment_seconds = offered_seconds
    passed = len(accepted) == total and not failed and generator_rejected == 0 and evidence["falseSuccesses"] == 0 \
        and evidence["missingSmsObservations"] == 0 and evidence["stateMismatches"] == 0 \
        and metrics["statuses"].get("UNCONFIRMED", 0) == 0 and len(established) == total \
        and offered_seconds <= duration + 3 and max(schedule_lags, default=0) < 1
    return {"passed": passed, "scenario": "concurrency", "workers": 1000, "countryCounts": [700, 200, 100],
            "offeredRequests": total, "targetRequestsPerSecond": rate, "targetSeconds": duration,
            "actualSubmissionSeconds": offered_seconds, "actualRequestRate": len(starts) / offered_seconds,
            "acceptedRequestRate": len(accepted) / offered_seconds, "maximumGeneratorLagSeconds": max(schedule_lags, default=0),
            "accepted": len(accepted), "httpErrors": dict(Counter(failed)), "generatorRejected": generator_rejected,
            "requestLatencyMillis": {"p95": percentile(request_latencies, .95), "p99": percentile(request_latencies, .99)},
            "established": len(established), "establishmentRate": len(established) / establishment_seconds,
            "peakActiveListeners": peak_active, "comparison": evidence, "metrics": metrics,
            "hostMetrics": http(run.host, "/metrics"), "scope": "fixed product fixture, not a platform capacity limit"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["functional", "concurrency"], default="functional")
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--root", type=Path, default=PRODUCT, help="Product directory, including an extracted ZIP")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--port", type=int, default=18400)
    args = parser.parse_args()
    if args.build:
        build()
    output = (args.output or PRODUCT / "build" / "acceptance" / (args.scenario + "-" + time.strftime("%Y%m%d-%H%M%S"))).resolve()
    counts = (1, 1, 1) if args.scenario == "functional" else (700, 200, 100)
    result = {"passed": False, "scenario": args.scenario}
    run = Preview(counts, args.port, root=args.root, output=output / "private")
    try:
        with run:
            print("Real processes and verified Worker routes ready", flush=True)
            result = functional(run) if args.scenario == "functional" else concurrency(run)
            result["resourcePeaks"] = run.peaks
    except Exception as error:
        result["failure"] = str(error)
    result["artifacts"] = run.artifacts
    result["processModel"] = ["server", "host"]
    result["profile"] = "sms-reception"
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    (output / "summary.md").write_text("# SMS Reception acceptance\n\n" +
            ("PASS" if result["passed"] else "FAIL") + " · " + args.scenario + "\n\n```json\n" +
            json.dumps(result, ensure_ascii=False, indent=2) + "\n```\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
