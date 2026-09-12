"""Finite product proof through public product/platform APIs and the real Java Host."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import uuid

PRODUCT = Path(__file__).resolve().parent
PREVIEW = PRODUCT.parents[1] / "distribution" / "product-preview"
sys.path.insert(0, str(PRODUCT.parents[1] / "integrations"))
from worker_proof_support.scenario_inventory import materialize_inventory, product_worker_world


def load_preview(root):
    spec = importlib.util.spec_from_file_location("sms_acceptance_preview", Path(root) / "run_preview.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


preview = load_preview(PREVIEW)
http, all_pages = preview.http, preview.all_pages


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


def inject(run, phone, text, sms_id=None, worker=None):
    target = worker if worker is not None else next(item for item in all_pages(run.host, "/lab/v1/sms/inventory")
                                                   if item["phone"] == phone)
    path = "/lab/v1/workers/" + urllib.parse.quote(target["workerGroupId"], safe="") + "/" + urllib.parse.quote(target["replicaKey"], safe="")
    return http(run.host, path + ":inputs", {"eventName": "sms.receive", "payload": {
                "phone": phone, "text": text, "smsId": sms_id or str(uuid.uuid4())}})


def all_records(base, path):
    return all_pages(base, path)


def compare(run):
    host = all_records(run.host, "/lab/v1/sms/records")
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


def mixed_capabilities(run, inventory):
    cn = next(sim for sim in inventory if sim["country"] == "CN")
    capabilities = http(run.url, f"/api/v1/worker-delivery/endpoint-managers/{run.adapter}/direct-calls", {
        "workerGroupId": "demo-sim", "workerPayloads": {cn["workerId"]: "null"},
        "messageType": "platform.worker.events.snapshot", "waitTimeoutMillis": 5000})
    target = capabilities["results"][cn["workerId"]]
    require(target["status"] == "observed", "Actual Worker event snapshot was not observed")
    events = json.loads(target["opaqueResultPayload"])["eventNames"]
    require(set(["extension.worker.sms.listen.start", "extension.worker.sms.listen.cancel",
                 "extension.worker.string.md5", "extension.worker.string.sha1",
                 "extension.worker.string.base64.encode"]).issubset(events), "Mixed events are not installed")
    listener = wait_state(run, create(run, request="mixed-worker"), {"LISTENING"})
    require(listener["workerId"] == cn["workerId"], "SMS listener identity changed")
    task = http(run.url, "/api/v1/tasks", {"workerGroupId": "demo-sim"})["taskId"]
    messages = [str(uuid.uuid4()) for _ in range(3)]
    http(run.url, f"/api/v1/tasks/{task}/items", [
        {"messageId": message, "eventCode": "extension.worker.string.md5",
         "payload": {"value": "shared-sms-worker"}, "workerSelector": {"workerId": [cn["workerId"]]}, "ttlMillis": 30000} for message in messages])
    http(run.url, f"/api/v1/tasks/{task}/approve", {})
    results = {}
    def strings_observed():
        nonlocal results
        results = http(run.url, f"/api/v1/tasks/{task}/results:load", messages)
        return all(results[message]["status"] == "succeeded" for message in messages)
    run.wait_for(strings_observed, 30, "same Group targeted string results")
    expected = hashlib.md5(b"shared-sms-worker").hexdigest()
    require(all(json.loads(results[message]["opaqueResultPayload"])["md5"] == expected for message in messages),
            "String handler results mismatch")
    require(http(run.url, "/api/v1/sms/listeners/" + listener["id"])["status"] == "LISTENING",
            "String execution ended the SMS subscription")
    inject(run, cn["phone"], "[A] 654321", "mixed-worker-sms")
    received = wait_state(run, listener, {"RECEIVED"})
    require(received["workerId"] == cn["workerId"], "Shared Worker identity mismatch")
    return {"workerId": cn["workerId"], "targetedTaskId": task, "stringResults": len(results),
            "smsObserved": True, "installedEvents": events,
            "claim": "one actual Worker serves both Tasks; no fairness or capacity claim"}


def lifecycle(run):
    inventory = all_records(run.host, "/lab/v1/sms/inventory")
    cn = next(sim for sim in inventory if sim["country"] == "CN")
    listener = wait_state(run, create(run, seconds=3, request="stop-fault"), {"LISTENING"})
    control = f'/lab/v1/sms/workers/{cn["workerGroupId"]}/{cn["replicaKey"]}'
    http(run.host, control + ":stop", {})
    run.wait_for(lambda: http(run.host, "/lab/v1/sms/metrics")["host"]["activeListeners"] == 0,
                 5, "stopped number cleanup")
    local = next(record for record in all_records(run.host, "/lab/v1/sms/records") if record["listenerId"] == listener["id"])
    require(local["status"] == "INTERRUPTED" and not local["reportAccepted"], "Stop synthesized an ending Report")
    require(inject(run, cn["phone"], "[A] 111111", "stopped-sms")["status"] == "IGNORED", "Stopped number matched SMS")
    http(run.host, control + ":start", {})
    run.wait_for(run.connected, 30, "restarted Worker verified route")
    restarted = next(sim for sim in all_records(run.host, "/lab/v1/sms/inventory") if sim["country"] == "CN")
    require(restarted["workerId"] == cn["workerId"], "Restart changed file-coordinate identity")
    fresh = wait_state(run, create(run, request="after-stop"), {"LISTENING"})
    require(inject(run, cn["phone"], "[A] 111111", "stopped-sms")["status"] == "DUPLICATE", "Restart cleared SMS dedup")
    inject(run, cn["phone"], "[A] 222222", "restarted-sms")
    wait_state(run, fresh, {"RECEIVED"})
    deadline_seconds = max(1, (listener["observationDeadline"] - time.time() * 1000) / 1000 + 5)
    unconfirmed = wait_state(run, listener, {"UNCONFIRMED"}, timeout=deadline_seconds)
    records = all_records(run.host, "/lab/v1/sms/records")
    observed = all_records(run.url, "/api/v1/sms/listeners")
    require(len(records) == 2 and len(observed) == 2, "Fault scenario created unexpected records")
    require(unconfirmed.get("sms") is None, "Interrupted listener received a new run's SMS")
    return {"passed": True, "scenario": "lifecycle", "workerId": cn["workerId"], "hostInterrupted": 1,
            "expectedUnconfirmed": 1, "newRunSmsObserved": 1, "dedupRetained": True,
            "reporterRebound": False, "metrics": http(run.url, "/api/v1/sms/metrics")}


def dynamic_properties(run, inventory):
    """Separate local, Adapter and Matching observations, then actual country-index execution."""
    cn = next(worker for worker in inventory if worker["country"] == "CN")
    us = next(worker for worker in inventory if worker["country"] == "US")
    old = wait_state(run, create(run, request="before-hot-change"), {"LISTENING"})
    prepare_before = prepare_counts(run.output / "runtime-http.log")
    require(prepare_before["prepare"] == 0 and prepare_before["prepare-batch"] > 0,
            "Product initialization did not exclusively use file batch Prepare")
    before = {worker["replicaKey"]: worker["workerId"] for worker in inventory}
    new_phone = "+861700999999"

    def patch(worker, values):
        path = "/lab/v1/workers/" + worker["workerGroupId"] + "/" + urllib.parse.quote(worker["replicaKey"], safe="")
        response = http(run.host, path + ":inputs", {"eventName": "properties.update", "payload": values})
        require(response["persisted"] and response["sendAccepted"], "Hot properties not accepted locally")
        return http(run.host, path)["workerProperties"]

    expected = {cn["workerId"]: patch(cn, {"phone": new_phone, "country": "US"}),
                us["workerId"]: patch(us, {"country": "CN"})}
    local = next(record for record in all_pages(run.host, "/lab/v1/sms/records") if record["listenerId"] == old["id"])
    require(local["status"] == "INTERRUPTED" and not local["reportAccepted"], "Hot change emitted a synthetic ending report")
    try:
        inject(run, cn["phone"], "[A] 222222", "old-phone-after-change", worker=cn)
        raise AssertionError("Old phone is still routed")
    except urllib.error.HTTPError as error:
        require(error.code == 400, "Old phone rejection has wrong status")

    def adapter_observed():
        response = http(run.url, f"/api/v1/worker-delivery/endpoint-managers/{run.adapter}/direct-calls", {
            "messageType": "platform.adapter.worker-properties.snapshot", "waitTimeoutMillis": 1000,
            "opaquePayload": json.dumps({"workerIds": list(expected)})})["results"][run.adapter]
        if response["status"] != "observed":
            return False
        rows = json.loads(response["opaqueResultPayload"])["propertiesByWorkerId"]
        return all(rows[worker].get("properties") == properties for worker, properties in expected.items())

    def matching_observed():
        rows = http(run.url, "/api/v1/runtime-view/worker-groups/demo-sim/workers:preview", 100)["workers"]
        actual = {worker["workerId"]: worker["workerProperties"] for worker in rows}
        return all(actual.get(worker) == properties for worker, properties in expected.items())

    run.wait_for(adapter_observed, 15, "Adapter complete hot Properties")
    run.wait_for(matching_observed, 15, "Matching complete hot Properties")
    received = wait_state(run, create(run, country="US", request="after-hot-change"), {"LISTENING"})
    require(received["workerId"] == cn["workerId"] and received["phone"] == new_phone,
            "Country index did not select the changed Worker")
    inject(run, new_phone, "[A] 444444", "hot-change-sms")
    wait_state(run, received, {"RECEIVED"})
    require({worker["replicaKey"]: worker["workerId"] for worker in all_pages(run.host, "/lab/v1/sms/inventory")} == before,
            "Hot Properties changed identity or topology")
    require(prepare_counts(run.output / "runtime-http.log") == prepare_before, "Hot Properties invoked Prepare")

    # Restart the real Host with exactly the same inventory and Server scope.
    # The same product Workers now also install one explicitly selected proof Handler.
    config_path = run.worker_config_path
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config["workerGroups"]["demo-sim"]["events"].append("extension.worker.lab.execution-witness")
    config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    process = run.processes["host"]
    args = list(process.args)
    process.terminate()
    process.wait(timeout=10)
    run.launch("host", args, os.environ.copy())
    run.wait_for(run.host_ready, 60, "Host inventory restart")
    run.wait_for(run.connected, 30, "restarted Host routes")
    require({worker["replicaKey"]: worker["workerId"] for worker in all_pages(run.host, "/lab/v1/sms/inventory")} == before,
            "Host restart changed file-coordinate identities")
    run.wait_for(adapter_observed, 15, "restarted Adapter baseline")
    run.wait_for(matching_observed, 15, "restarted Matching facts")
    restored = wait_state(run, create(run, country="US", request="after-hot-restart"), {"LISTENING"})
    require(restored["workerId"] == cn["workerId"] and restored["phone"] == new_phone, "Restart did not retain edits")
    inject(run, new_phone, "[A] 555555", "hot-restart-sms")
    wait_state(run, restored, {"RECEIVED"})
    witness = http(run.url, f"/api/v1/worker-delivery/endpoint-managers/{run.adapter}/direct-calls", {
        "workerGroupId": "demo-sim", "workerPayloads": {
            cn["workerId"]: json.dumps({"probeToken": "product-with-witness", "delayMillis": 0})},
        "messageType": "extension.worker.lab.execution-witness", "waitTimeoutMillis": 5000})["results"][cn["workerId"]]
    require(witness["status"] == "observed" and witness["messageType"] == "platform.worker.command.succeeded",
            "Product Worker cannot execute its selected verification Handler")
    records = http(run.host, "/lab/v1/execution-witnesses?after=0&limit=100")["records"]
    require([record["state"] for record in records] == ["ENTERED", "COMPLETED"]
            and all(record["labWorkerKey"] == cn["replicaKey"] for record in records),
            "Verification witness did not execute on the product Worker")
    return {"passed": True, "adapterObserved": True, "matchingObserved": True, "countryIndexExecutorChanged": True,
            "identitiesUnchanged": True, "hostRestartRestoredEdits": True, "oldListeningInterruptedLocally": True,
            "initialBatchPrepareObserved": True, "hotPrepareRequestDelta": 0,
            "composedVerificationCapabilityExecuted": True}


def prepare_counts(path):
    counts = {"prepare": 0, "prepare-batch": 0}
    for line in path.read_text(encoding="utf-8").splitlines():
        record = re.fullmatch(r"([A-Z]+) (/\S*) ([1-5][0-9]{2})", line)
        require(record is not None, "Incomplete HTTP access record")
        route = re.fullmatch(r"/api/v1/worker-groups/[^/]+/workers:(prepare|prepare-batch)", record[2])
        if route:
            counts[route[1]] += 1
    return counts


def functional(run):
    catalog = http(run.url, "/api/v1/sms/catalog")
    inventory = all_records(run.host, "/lab/v1/sms/inventory")
    require(len(inventory) == 3, "Functional fixture must contain one number per country")
    cn = next(sim["phone"] for sim in inventory if sim["country"] == "CN")
    def properties_observed():
        workers = http(run.url, "/api/v1/runtime-view/worker-groups/demo-sim/workers:preview", 100)["workers"]
        return len(workers) == 3 and {w["workerProperties"].get("country") for w in workers} == {"CN", "US", "GB"} \
            and all(w["workerProperties"].get("simulated") == "true" for w in workers)
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
    before = http(run.host, "/lab/v1/sms/metrics")["host"]["listeners"]
    http(run.url, f"/api/v1/tasks/{task}/items:call", {"items": [{"messageId": duplicate_message,
         "eventCode": "extension.worker.sms.listen.start", "payload": payload, "ttlMillis": 10000,
         "workerSelector": {"workerId": [older["workerId"]]}}], "waitTimeoutMillis": 1})
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
    require(http(run.host, "/lab/v1/sms/metrics")["host"]["listeners"] == before, "Task duplicate allocated another listener")
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
    http(run.host, "/lab/v1/sms/traffic/start", {"ratePerSecond": 5, "durationSeconds": 1})
    run.wait_for(lambda: not http(run.host, "/lab/v1/sms/metrics")["traffic"]["running"], 5, "finite auto traffic")
    mixed = mixed_capabilities(run, inventory)
    evidence = compare(run)
    require(evidence["falseSuccesses"] == 0 and evidence["missingSmsObservations"] == 0
            and evidence["stateMismatches"] == 0, "Host and Backend business observations diverged")
    hot = dynamic_properties(run, inventory)
    return {"passed": True, "scenario": "functional", "checks": ["country pools", "Adapter Properties baseline", "A/B/C shared number",
            "priority and stable ties", "request conflict", "SMS dedup", "Task duplicate", "no-listener/no-match",
            "expiry window", "targeted cancellation", "cancel during establishment", "finite auto traffic"],
            "comparison": evidence, "mixedCapabilities": mixed, "dynamicProperties": hot, "metrics": http(run.url, "/api/v1/sms/metrics")}


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
    http(run.host, "/lab/v1/sms/traffic/start", {"ratePerSecond": 300, "durationSeconds": 135})
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
                peak_active = max(peak_active, http(run.host, "/lab/v1/sms/metrics")["host"]["activeListeners"])
            if index and index % 2000 == 0:
                print(f"Offered {index}/{total}; accepted {len(accepted)}; HTTP errors {len(failed)}", flush=True)
    offered_seconds = time.monotonic() - start
    # Wait for definite observed outcomes or the product's explicit observation deadline.
    def finished():
        nonlocal peak_active
        statuses = http(run.url, "/api/v1/sms/metrics")["statuses"]
        peak_active = max(peak_active, http(run.host, "/lab/v1/sms/metrics")["host"]["activeListeners"])
        return not any(statuses.get(state, 0) for state in ("ESTABLISHING", "LISTENING", "CANCELLING"))
    run.wait_for(finished, 110, "finite load observation deadline")
    http(run.host, "/lab/v1/sms/traffic/stop", {})
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
            "workersUsedByCountry": {country: len({r["workerId"] for r in established if r["country"] == country})
                                     for country in ("CN", "US", "GB")},
            "maxEstablishedPerWorker": max(Counter(r["workerId"] for r in established).values(), default=0),
            "rejectionReasons": dict(Counter(r.get("reason", "unknown") for r in records if r["status"] == "REJECTED")),
            "peakActiveListeners": peak_active, "comparison": evidence, "metrics": metrics,
            "hostMetrics": http(run.host, "/lab/v1/sms/metrics"), "scope": "fixed product fixture, not a platform capacity limit"}


def main():
    global http, all_pages
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["functional", "lifecycle", "concurrency"], default="functional")
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--root", type=Path, default=PREVIEW, help="Unified Product Preview directory or extracted ZIP root")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--port", type=int, default=18400)
    args = parser.parse_args()
    preview = load_preview(args.root)
    http, all_pages = preview.http, preview.all_pages
    if args.build:
        if args.root.resolve() != PREVIEW:
            parser.error("--build requires the checkout Product Preview; omit it for an extracted ZIP")
        preview.build()
    output = (args.output or PRODUCT / "build" / "acceptance" / (args.scenario + "-" + time.strftime("%Y%m%d-%H%M%S"))).resolve()
    counts = (700, 200, 100) if args.scenario == "concurrency" else (1, 1, 1)
    result = {"passed": False, "scenario": args.scenario}
    sandbox_root = output / "private" / ("inventory-" + uuid.uuid4().hex) / "data" / "scenario-workers"
    materialize_inventory(sandbox_root, product_worker_world(counts, messages=False))
    run = preview.Preview(sum(counts), args.port, root=args.root, output=output / "private", products="sms",
                          sandbox_root=sandbox_root)
    try:
        with run:
            print("Real processes and verified Worker routes ready", flush=True)
            result = {"functional": functional, "lifecycle": lifecycle, "concurrency": concurrency}[args.scenario](run)
            result["resourcePeaks"] = run.peaks
    except Exception as error:
        result["failure"] = str(error)
    result["artifacts"] = run.artifacts
    result["launcherSha256"] = hashlib.sha256((args.root / "run_preview.py").read_bytes()).hexdigest()
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
