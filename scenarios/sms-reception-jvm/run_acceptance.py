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
PREVIEW = PRODUCT.parents[1] / "distribution" / "server"
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




def inject(run, phone, text, sms_id=None, worker=None):
    target = worker if worker is not None else next(item for item in all_pages(run.host, "/lab/v1/sms/inventory")
                                                   if item["phone"] == phone)
    path = "/lab/v1/workers/" + urllib.parse.quote(target["workerGroupId"], safe="") + "/" + urllib.parse.quote(target["replicaKey"], safe="")
    return http(run.host, path + ":inputs", {"eventName": "sms.receive", "payload": {
                "phone": phone, "text": text, "smsId": sms_id or str(uuid.uuid4())}})

def all_records(base, path):
    return all_pages(base, path)

def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    import math
    return ordered[max(0, math.ceil(len(ordered) * p) - 1)]

def prepare_counts(path):
    counts = {"prepare": 0, "prepare-batch": 0}
    for line in path.read_text(encoding="utf-8").splitlines():
        record = re.fullmatch(r"([A-Z]+) (/\S*) ([1-5][0-9]{2})", line)
        require(record is not None, "Incomplete HTTP access record")
        route = re.fullmatch(r"/api/v1/worker-groups/[^/]+/workers:(prepare|prepare-batch)", record[2])
        if route:
            counts[route[1]] += 1
    return counts

def require_idle_messages(run):
    backend = http(run.url, "/api/v1/messages/tasks?limit=100")
    host = http(run.host, "/lab/v1/messages/metrics")
    require(all(task.get("managed") for task in backend["tasks"]) and host["messages"] == 0,
            "SMS-only workload created Messages business records")
    return {"messageTasks": sum(not task.get("managed") for task in backend["tasks"]),
            "backendMessages": sum(task.get("sendTotal", 0) for task in backend["tasks"]),
            "hostMessages": host["messages"]}


def create(run, app="A", country="CN", seconds=60):
    result = http(run.url, "/api/v1/sms/numbers:lease", {
        "applicationId": app, "country": country, "leaseSeconds": seconds})
    if not hasattr(run, "sms_ids"):
        run.sms_ids = []
    run.sms_ids.append(result["messageId"])
    return result


def read(run, message_id):
    return http(run.url, "/api/v1/sms/messages/" + urllib.parse.quote(message_id, safe=""))


def wait_state(run, reception, expected, timeout=30):
    result = {}
    def observe():
        nonlocal result
        result = read(run, reception["messageId"])
        return result["status"] in expected
    run.wait_for(observe, timeout, "SMS result " + "/".join(sorted(expected)))
    return result


def query_records(run):
    with ThreadPoolExecutor(max_workers=24) as readers:
        return list(readers.map(lambda mid: read(run, mid), list(run.sms_ids)))


def compare(run):
    host = all_records(run.host, "/lab/v1/sms/records")
    observed = query_records(run)
    expected = {(item["messageId"], item["smsId"]) for item in host if item.get("smsId")}
    actual = {(item["messageId"], item["sms"]["smsId"]) for item in observed if item.get("sms")}
    return {"hostRecords": len(host), "observedRecords": len(observed), "hostMatched": len(expected),
            "observedSms": len(actual), "missingSmsObservations": len(expected - actual),
            "falseSuccesses": len(actual - expected),
            "smsObservationRate": len(expected & actual) / len(expected) if expected else None,
            "matchedIdentityDigest": hashlib.sha256(json.dumps(sorted(expected)).encode()).hexdigest()}


def await_sms(run, reception, sms_id):
    latest = {}
    def observed():
        nonlocal latest
        latest = read(run, reception["messageId"])
        return latest.get("sms", {}).get("smsId") == sms_id
    run.wait_for(observed, 10, "latest original-Item SMS")
    return latest


def mixed_capabilities(run, reception):
    # A separate real execution must run while the SMS association remains active.
    task = http(run.url, "/api/v1/tasks", {"projectId": "sms", "workerGroupId": "demo-sim", "refill": []})["taskId"]
    message_id = str(uuid.uuid4())
    http(run.url, f"/api/v1/tasks/{task}/items", [{"messageId": message_id,
         "eventCode": "extension.worker.string.md5", "payload": {"value": "shared-sms-worker"},
         "workerSelector": {"executorName": "workerId", "input": reception["workerId"]}, "ttlMillis": 30000}])
    http(run.url, f"/api/v1/tasks/{task}/approve", {})
    result = {}
    def observed():
        nonlocal result
        result = http(run.url, f"/api/v1/tasks/{task}/results:load", [message_id])[message_id]
        return result["status"] == "succeeded"
    run.wait_for(observed, 30, "execution while receiving SMS")
    require(json.loads(result["opaqueResultPayload"])["md5"] == hashlib.md5(b"shared-sms-worker").hexdigest(),
            "Independent Worker execution returned wrong content")
    require(read(run, reception["messageId"])["leaseActive"], "Independent execution ended reception")
    return {"sameWorkerExecuted": True, "smsRemainedActive": True}


def restart_server(run):
    process = run.processes.pop("server")
    args = process.args
    process.terminate()
    try:
        process.wait(timeout=10)
    except Exception:
        process.kill(); process.wait(timeout=5)
    # Preserve the first process log; the same packaged launcher owns the replacement process.
    for stream in run.logs:
        if Path(stream.name).name == "server.log" and not stream.closed:
            stream.close()
    log = run.output / "server.log"
    if log.exists():
        (run.output / "server-before-restart.log").write_bytes(log.read_bytes())
    env = os.environ.copy()
    env.update(XA_MASS_REDIS_SCOPE=run.scope, XA_MASS_REDIS_URL=run.redis_url,
               PREVIEW_SERVER_PORT=str(run.port), PREVIEW_ADAPTER_PORT=str(run.port + 3))
    run.launch("server", args, env)
    run.wait_for(lambda: http(run.url, "/actuator/health"), 60, "restarted Server")
    run.wait_for(run.connected, 60, "Worker reconnect after Server restart")


def functional(run):
    inventory = all_records(run.host, "/lab/v1/sms/inventory")
    cn = next(row for row in inventory if row["country"] == "CN")
    require(inject(run, cn["phone"], "[A] 000000")["status"] == "IGNORED", "Unallocated SMS produced a result")
    run.stage = "cross-app-acquisition"
    allocated = [wait_state(run, create(run, app, seconds=120), {"WAITING"}) for app in ("A", "B", "C")]
    a, b, c = allocated
    require({row["phoneNumber"] for row in allocated} == {cn["phone"]}, "Different apps did not share the number")
    for row, text, sms_id in ((a, "[A] 111111", "a-1"), (b, "[B] 222222", "b-1"),
                               (c, "announcement", "c-1"), (a, "[A] 333333", "a-2")):
        matched = inject(run, cn["phone"], text, sms_id)
        require(matched["messageId"] == row["messageId"], "SMS selected another app association")
        latest = await_sms(run, row, sms_id)
        require(latest["leaseActive"], "First SMS ended the reception window")
    require(inject(run, cn["phone"], "[A] 333333", "a-2")["status"] == "DUPLICATE", "SMS dedup failed")
    before = http(run.host, "/lab/v1/sms/metrics")["host"]["leaseExecutions"]
    for _ in range(5):
        require(read(run, a["messageId"])["sms"]["smsId"] == "a-2", "Result read consumed or regressed content")
    require(http(run.host, "/lab/v1/sms/metrics")["host"]["leaseExecutions"] == before, "Queries executed Worker handlers")
    mixed = mixed_capabilities(run, a)
    run.stage = "expiry"
    short = wait_state(run, create(run, "A", "US", 2), {"WAITING"})
    inject(run, short["phoneNumber"], "[A] 444444", "short")
    await_sms(run, short, "short")
    run.wait_for(lambda: not read(run, short["messageId"])["leaseActive"], 5, "lease expiry")
    require(read(run, short["messageId"])["sms"]["smsId"] == "short", "Expiry erased the last SMS")
    require(inject(run, short["phoneNumber"], "[A] 555555", "after-expiry")["status"] == "IGNORED", "Expired association received SMS")
    began = time.monotonic()
    run.stage = "expired-number-reuse"
    reused = wait_state(run, create(run, "A", "US", 2), {"WAITING"})
    reuse_seconds = time.monotonic() - began
    require(reused["phoneNumber"] == short["phoneNumber"], "Expired number was not reusable")
    run.wait_for(lambda: http(run.url, "/api/v1/sms/metrics")["projection"]["recorded"] >= 5, 5, "lease state projection")
    deadline = a["leaseUntil"]
    run.stage = "server-restart"
    restart_server(run)
    require(read(run, a["messageId"])["leaseUntil"] == deadline, "Restart lost the stored Result")
    exhausted = create(run, "A", "CN", 60)
    require(exhausted["status"] == "NOT_OBSERVED" and "phoneNumber" not in exhausted,
            "Persisted same-app lease was ignored after restart")
    began = time.monotonic()
    run.stage = "restart-refill"
    fresh = wait_state(run, create(run, "B", "US", 30), {"WAITING"})
    refill_seconds = time.monotonic() - began
    require(fresh["phoneNumber"] == short["phoneNumber"], "Restarted Pool did not refill")
    inject(run, cn["phone"], "[A] 666666", "after-server-restart")
    await_sms(run, a, "after-server-restart")
    evidence = compare(run)
    require(evidence["falseSuccesses"] == 0 and evidence["missingSmsObservations"] == 0,
            "Host and stored latest SMS results differ")
    run.stage = "hot-properties"
    hot = dynamic_properties(run, inventory, a)
    return {"passed": True, "scenario": "functional", "checks": ["shared number across apps", "continuous latest SMS",
            "repeatable pure Result query", "expiry retains content", "expired tuple reuse", "persisted lease after Server restart",
            "original Reporter after Server restart", "stock exhaustion returns original messageId", "memory Pool refill"],
            "expiredNumberReuseSeconds": reuse_seconds, "restartRefillSeconds": refill_seconds,
            "comparison": evidence, "mixedCapabilities": mixed, "dynamicProperties": hot, "metrics": http(run.url, "/api/v1/sms/metrics")}


def dynamic_properties(run, inventory, old):
    """Separate local, Adapter and Matching observations, then actual country-index execution."""
    cn = next(worker for worker in inventory if worker["country"] == "CN")
    us = next(worker for worker in inventory if worker["country"] == "US")
    prepare_before = prepare_counts(run.output / "runtime-http.log")
    require(prepare_before["prepare"] == 0,
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
    local = next(record for record in all_pages(run.host, "/lab/v1/sms/records") if record["messageId"] == old["messageId"])
    require(local["trackingStatus"] == "INTERRUPTED", "Hot change emitted a synthetic ending report")
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
    received = wait_state(run, create(run, country="US", seconds=120), {"WAITING"})
    require(received["workerId"] == cn["workerId"] and received["phoneNumber"] == new_phone,
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
    restored = wait_state(run, create(run, app="B", country="US"), {"WAITING"})
    require(restored["workerId"] == cn["workerId"] and restored["phoneNumber"] == new_phone, "Restart did not retain edits")
    inject(run, new_phone, "[B] 555555", "hot-restart-sms")
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
    return {"passed": True, "adapterObserved": True, "matchingObserved": True, "countryPoolExecutorChanged": True,
            "identitiesUnchanged": True, "hostRestartRestoredEdits": True, "oldListeningInterruptedLocally": True,
            "noHotPrepareObserved": True, "hotPrepareRequestDelta": 0,
            "composedVerificationCapabilityExecuted": True}

def lifecycle(run):
    inventory = all_records(run.host, "/lab/v1/sms/inventory")
    cn = next(sim for sim in inventory if sim["country"] == "CN")
    old = wait_state(run, create(run, seconds=3), {"WAITING"})
    control = '/lab/v1/sms/workers/' + cn["workerGroupId"] + '/' + urllib.parse.quote(cn["replicaKey"], safe="")
    http(run.host, control + ":stop", {})
    run.wait_for(lambda: http(run.host, "/lab/v1/sms/metrics")["host"]["activeAssociations"] == 0, 5, "stopped association cleanup")
    local = next(row for row in all_records(run.host, "/lab/v1/sms/records") if row["messageId"] == old["messageId"])
    require(local["trackingStatus"] == "INTERRUPTED" and not local["reportAccepted"], "Stop synthesized a report")
    require(inject(run, cn["phone"], "[A] 111111", "stopped")["status"] == "IGNORED", "Stopped number received SMS")
    http(run.host, control + ":start", {})
    run.wait_for(run.connected, 30, "new Worker run")
    # B is independent of the persisted A lease; no early release or old-run repair is required.
    fresh = wait_state(run, create(run, "B"), {"WAITING"})
    require(fresh["workerId"] == old["workerId"], "Worker file identity changed")
    require(inject(run, cn["phone"], "[A] 111111", "stopped")["status"] == "DUPLICATE", "Recent dedup was lost")
    inject(run, cn["phone"], "[B] 222222", "new-run")
    await_sms(run, fresh, "new-run")
    expired = wait_state(run, old, {"EXPIRED"})
    require("sms" not in expired, "New run wrote to the old association")
    return {"passed": True, "scenario": "lifecycle", "reporterRebound": False, "newRunSmsObserved": True,
            "oldResultRetained": True, "metrics": http(run.url, "/api/v1/sms/metrics")}


def concurrency(run):
    rate, duration, total = 30, 180, 5400
    acquired, failures, request_latencies, query_latencies, schedule_lags = [], [], [], [], []
    response_counts, timeline = Counter(), []
    lock = threading.Lock()
    permits = threading.BoundedSemaphore(256)
    http(run.host, "/lab/v1/sms/traffic/start", {"ratePerSecond": 300, "durationSeconds": 240})
    def submit(index):
        began = time.monotonic()
        try:
            country = ("CN", "US", "GB")[0 if index % 10 < 7 else 1 if index % 10 < 9 else 2]
            result = create(run, "ABC"[(index // 10 + index % 10) % 3], country, 60)
            request_ms = (time.monotonic() - began) * 1000
            query_start = time.monotonic(); read(run, result["messageId"])
            with lock:
                acquired.append(result); request_latencies.append(request_ms)
                query_latencies.append((time.monotonic() - query_start) * 1000)
                response_counts[country + ":" + result["status"]] += 1
        except Exception as error:
            with lock: failures.append(type(error).__name__)
        finally:
            permits.release()
    peak_active, rejected = 0, 0
    run.sms_ids = []
    start = time.monotonic()
    # 30/sec times a 3s Call wait (plus servlet timeout granularity) needs more than 64 clients.
    with ThreadPoolExecutor(max_workers=128) as executor:
        for index in range(total):
            due = start + index / rate
            time.sleep(max(0, due - time.monotonic()))
            schedule_lags.append(max(0, time.monotonic() - due))
            if permits.acquire(blocking=False): executor.submit(submit, index)
            else: rejected += 1
            if index % 300 == 0:
                run.check()
                peak_active = max(peak_active, http(run.host, "/lab/v1/sms/metrics")["host"]["activeAssociations"])
                with lock:
                    checkpoint = {"offered": index, "responses": dict(response_counts), "httpErrors": len(failures)}
                timeline.append(checkpoint)
                print(json.dumps(checkpoint), flush=True)
    offered_seconds = time.monotonic() - start
    run.wait_for(lambda: http(run.host, "/lab/v1/sms/metrics")["host"]["activeAssociations"] == 0, 100, "finite lease windows")
    http(run.host, "/lab/v1/sms/traffic/stop", {})
    observed = query_records(run)
    established = [row for row in observed if row.get("phoneNumber")]
    overlaps = 0
    windows = {}
    for row in sorted(established, key=lambda row: row["startedAt"]):
        key = (row["phoneNumber"], row["applicationId"], row["workerId"])
        if windows.get(key, 0) > row["startedAt"]: overlaps += 1
        windows[key] = max(windows.get(key, 0), row["leaseUntil"])
    evidence = compare(run)
    passed = len(established) == total and not failures and not rejected and evidence["falseSuccesses"] == 0
    return {"passed": passed, "scenario": "concurrency", "workers": 1000, "countryCounts": [700, 200, 100],
            "offeredRequests": total, "targetRequestsPerSecond": rate, "targetSeconds": duration,
            "actualSubmissionSeconds": offered_seconds, "maximumGeneratorLagSeconds": max(schedule_lags, default=0),
            "httpErrors": dict(Counter(failures)), "generatorRejected": rejected, "established": len(established),
            "responseTimeline": timeline,
            "requestLatencyMillis": {"p95": percentile(request_latencies, .95), "p99": percentile(request_latencies, .99)},
            "queryLatencyMillis": {"p95": percentile(query_latencies, .95), "p99": percentile(query_latencies, .99)},
            "duplicateLeaseCount": overlaps, "duplicateLeaseRate": overlaps / len(established) if established else None,
            "peakActiveAssociations": peak_active, "comparison": evidence,
            "metrics": http(run.url, "/api/v1/sms/metrics"), "hostMetrics": http(run.host, "/lab/v1/sms/metrics"),
            "queryWorkload": "one point read per acquisition and final Result reads; not all active leases polled each second",
            "scope": "fixed scenario workload; best-effort observations and leases, not a platform capacity limit"}


def main():
    global http, all_pages
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["functional", "lifecycle", "concurrency"], default="functional")
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--root", type=Path, default=PREVIEW, help="Scenario Preview directory or extracted ZIP root")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--port", type=int, default=18400)
    args = parser.parse_args()
    preview = load_preview(args.root)
    http, all_pages = preview.http, preview.all_pages
    if args.build:
        if args.root.resolve() != PREVIEW:
            parser.error("--build requires the checkout Scenario Preview; omit it for an extracted ZIP")
        preview.build()
    output = (args.output or PRODUCT / "build" / "acceptance" / (args.scenario + "-" + time.strftime("%Y%m%d-%H%M%S"))).resolve()
    counts = (700, 200, 100) if args.scenario == "concurrency" else (1, 1, 1)
    result = {"passed": False, "scenario": args.scenario}
    sandbox_root = output / "private" / ("inventory-" + uuid.uuid4().hex) / "data" / "scenario-workers"
    materialize_inventory(sandbox_root, product_worker_world(counts, messages=False))
    run = preview.Preview(sum(counts), args.port, root=args.root, output=output / "private", app_count=0,
                          sandbox_root=sandbox_root)
    try:
        with run:
            print("Real processes and verified Worker routes ready", flush=True)
            result = {"functional": functional, "lifecycle": lifecycle, "concurrency": concurrency}[args.scenario](run)
            result["idleMessagesScenario"] = require_idle_messages(run)
            result["resourcePeaks"] = run.peaks
    except Exception as error:
        result["passed"] = False
        result["failure"] = str(error)
        result["failedStage"] = getattr(run, "stage", "startup")
    result["artifacts"] = run.artifacts
    result["launcherSha256"] = hashlib.sha256((args.root / "run_preview.py").read_bytes()).hexdigest()
    result["processModel"] = ["server", "host"]
    result["profile"] = "preview"
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    (output / "summary.md").write_text("# SMS Reception acceptance\n\n" +
            ("PASS" if result["passed"] else "FAIL") + " · " + args.scenario + "\n\n```json\n" +
            json.dumps(result, ensure_ascii=False, indent=2) + "\n```\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
