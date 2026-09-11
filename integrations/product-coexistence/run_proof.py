"""Two real JVMs, public APIs and finite recipient inputs. No implementation imports."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import uuid

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "integrations"))
from worker_proof_support.scenario_inventory import materialize_inventory, product_worker_world
PREVIEW = REPO / "distribution" / "product-preview"
spec = importlib.util.spec_from_file_location("product_preview", PREVIEW / "run_preview.py")
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)
http, all_pages = preview.http, preview.all_pages


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wait(run, predicate, seconds, label):
    deadline = min(time.monotonic() + seconds, getattr(run, "phase_deadline", float("inf")))
    while time.monotonic() < deadline:
        run.check()
        try:
            value = predicate()
            if value:
                return value
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise
        except (OSError, TimeoutError):
            pass
        time.sleep(.05)
    raise AssertionError("Timed out: " + label)


def campaign(run, request="batch", count=2, country="CN", phone=None):
    body = {"requestId": request, "name": request, "country": country, "body": "finite campaign proof",
            "recipientIds": [f"{request}-recipient-{i}" for i in range(count)]}
    if phone:
        body["senderPhone"] = phone
    return http(run.url, "/api/v1/messages/campaigns", body, timeout=2), body


def campaign_messages(run, value):
    return all_pages(run.url, f'/api/v1/messages/campaigns/{value["id"]}/messages')


def sent(run, value):
    value = wait(run, lambda: (v if (v := http(run.url, "/api/v1/messages/campaigns/" + value["id"]))
                             ["submission"] != "SUBMITTING" else None), 30, "campaign submission")
    require(value["submission"] == "SUBMITTED" and value["taskId"], "Submission was not confirmed")
    rows = wait(run, lambda: (r if (r := campaign_messages(run, value)) and all(m["status"] == "SENT" for m in r) else None),
                30, "real Worker sends")
    return value, rows


def task_state(run, task):
    entries = http(run.url, "/api/v1/runtime-view/tasks:preview", 1000)["entries"]
    return next((e["scoreBand"] for e in entries if e["taskId"] == task), None)


def observe_receipt(run, value, message, expected, started, reply=None):
    deadline = min(started + 5, getattr(run, "phase_deadline", float("inf")))
    platform_ms = product_ms = None
    tag = {"SENT": 6, "DELIVERED": 7, "READ": 8, "REPLIED": 9}[expected]
    while time.monotonic() < deadline:
        try:
            results = http(run.url, f'/api/v1/tasks/{value["taskId"]}/results:load', [message["id"]], timeout=2)
            result = results[message["id"]]
            if result["status"] == "succeeded":
                snapshot = json.loads(result["opaqueResultPayload"])
                require(snapshot["messageId"] == message["id"] and snapshot["recipientId"] == message["recipientId"]
                        and snapshot["workerId"] == message["workerId"], "Platform business association changed")
                if snapshot["status"] == expected and (reply is None or snapshot.get("reply") == reply):
                    states = http(run.url, f'/api/v1/tasks/{value["taskId"]}/items:states', [message["id"]], timeout=2)
                    if states[message["id"]]["tag"] == tag and platform_ms is None:
                        platform_ms = (time.monotonic() - started) * 1000
            observed = next(m for m in campaign_messages(run, value) if m["id"] == message["id"])
            if observed["status"] == expected and (reply is None or observed.get("reply") == reply) and product_ms is None:
                product_ms = (time.monotonic() - started) * 1000
            if platform_ms is not None and product_ms is not None:
                require(max(platform_ms, product_ms) <= 5000, "Receipt budget exceeded")
                return {"stage": expected, "platformMillis": round(platform_ms, 2), "productMillis": round(product_ms, 2)}
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise
        except (OSError, TimeoutError):
            pass
        time.sleep(.05)
    raise AssertionError("Receipt did not converge within 5 seconds: " + expected)


def action(run, message, name, text=None, request=None):
    started = time.monotonic()
    target = run.input_workers_by_id[message["workerId"]]
    path = "/lab/v1/workers/" + urllib.parse.quote(target["workerGroupId"], safe="") + "/" + urllib.parse.quote(target["replicaKey"], safe="")
    payload = {"messageId": message["id"]}
    if text is not None:
        payload.update(requestId=request or str(uuid.uuid4()), text=text)
    response = http(run.host, path + ":inputs", {"eventName": "message." + name, "payload": payload}, timeout=2)
    require(response["persisted"], "Recipient action did not persist")
    return started, response


def sms_create(run, request="shared-sms", country="CN", application="A"):
    return http(run.url, "/api/v1/sms/listeners", {"requestId": request, "country": country,
                "applicationId": application, "listenSeconds": 60}, timeout=2)


def sms_wait(run, listener, state):
    return wait(run, lambda: (v if (v := http(run.url, "/api/v1/sms/listeners/" + listener["id"]))["status"] == state else None),
                20, "SMS " + state)


def functional(run):
    run.phase_deadline = time.monotonic() + 180
    sms_catalog = http(run.url, "/api/v1/sms/catalog")
    messages_catalog = http(run.url, "/api/v1/messages/catalog")
    require({c["workerGroupId"] for c in sms_catalog["countries"]}
            == {c["workerGroupId"] for c in messages_catalog["countries"]} == {"demo-sim"}, "Products did not share Groups")
    inventory = all_pages(run.host, "/lab/v1/messages/inventory")
    require(len(inventory) == 12, "Expected 12 shared Workers")
    listener = sms_wait(run, sms_create(run), "LISTENING")
    created, request = campaign(run, phone=listener["phone"])
    require(http(run.url, "/api/v1/messages/campaigns", request)["id"] == created["id"], "Campaign request was duplicated")
    try:
        http(run.url, "/api/v1/messages/campaigns", {**request, "name": "conflicting"})
        raise AssertionError("Conflicting campaign was accepted")
    except urllib.error.HTTPError as error:
        require(error.code == 409, "Expected campaign conflict")
    value, rows = sent(run, created)
    require(all(m["workerId"] == listener["workerId"] for m in rows), "Targeted campaign did not execute on SMS Worker")
    require(http(run.url, "/api/v1/sms/listeners/" + listener["id"])["status"] == "LISTENING", "Campaign stopped SMS listening")
    wait(run, lambda: task_state(run, value["taskId"]) == "terminal", 30, "finite Task automatic completion")
    target = run.input_workers_by_id[rows[0]["workerId"]]
    worker_path = "/lab/v1/workers/" + urllib.parse.quote(target["workerGroupId"], safe="") + "/" + urllib.parse.quote(target["replicaKey"], safe="")
    require({m["messageId"] for m in all_pages(run.host, worker_path + ":messages")}
            == {m["id"] for m in rows}, "Worker inbox does not match its real executed messages")
    other = next(w for w in inventory if w["workerId"] != rows[0]["workerId"])
    other_path = "/lab/v1/workers/" + urllib.parse.quote(other["workerGroupId"], safe="") + "/" + urllib.parse.quote(other["replicaKey"], safe="")
    try:
        http(run.host, other_path + ":inputs", {"eventName": "message.deliver", "payload": {"messageId": rows[0]["id"]}})
        raise AssertionError("Cross-Worker receipt was accepted")
    except urllib.error.HTTPError as error:
        require(error.code == 404, "Expected message ownership rejection")
    require(all(m["status"] == "SENT" for m in all_pages(run.host, worker_path + ":messages")),
            "Rejected cross-Worker input changed local records")
    require(all(m["status"] == "SENT" for m in campaign_messages(run, value)),
            "Rejected cross-Worker input changed product observations")
    checkpoints = []
    for name, status, text in [("deliver", "DELIVERED", None), ("read", "READ", None), ("reply", "REPLIED", "first"), ("reply", "REPLIED", "second")]:
        began, receipt = action(run, rows[0], name, text)
        require(receipt["sendAccepted"], "Normal receipt send not accepted")
        checkpoints.append(observe_receipt(run, value, rows[0], status, began, text))
        require(task_state(run, value["taskId"]) == "terminal", "Receipt reopened completed Task")
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": True})
    ids = []
    for name, text in [("deliver", None), ("read", None), ("reply", "old"), ("reply", "latest")]:
        _, receipt = action(run, rows[1], name, text)
        require(receipt["held"] and not receipt["sendAccepted"], "Receipt hold bypassed")
        ids.append(receipt["receiptId"])
    started = time.monotonic()
    released = http(run.host, "/lab/v1/messages/receipts:release", {"receiptIds": [ids[3], ids[3], ids[2], ids[1], ids[0]]})
    require(released == {"published": 5, "sendAccepted": 5}, "Held receipt release not accepted")
    checkpoints.append(observe_receipt(run, value, rows[1], "REPLIED", started, "latest"))
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": False})
    _, duplicate = action(run, rows[0], "reply", "second", "duplicate-reply")
    require(duplicate["sendAccepted"], "First identified reply rejected")
    _, duplicate = action(run, rows[0], "reply", "second", "duplicate-reply")
    require(duplicate["unchanged"] and not duplicate["sendAccepted"], "Reply operation was replayed")
    # Execute the identical send through a real Worker using the shared ON_DEMAND Task.
    task = next(c["taskId"] for c in sms_catalog["countries"] if c["id"] == "CN")
    payload = {k: rows[0][k] for k in ("campaignId", "messageId", "country", "recipientId", "body")}
    duplicate_id = str(uuid.uuid4())
    http(run.url, f"/api/v1/tasks/{task}/items:call", {"items": [{"messageId": duplicate_id,
        "eventCode": "extension.worker.message.send", "payload": payload, "ttlMillis": 10000,
        "workerSelector": {"workerId": [rows[0]["workerId"]]}}], "waitTimeoutMillis": 1})
    wait(run, lambda: http(run.url, f"/api/v1/tasks/{task}/results:load", [duplicate_id])[duplicate_id]["status"] == "succeeded", 20, "duplicate real execution")
    require(http(run.host, "/lab/v1/messages/metrics")["messages"] == 2, "Duplicate execution delivered another message")
    # Same Reporter must still target the original Item, not the duplicate execution Item.
    began, receipt = action(run, rows[0], "reply", "after-duplicate")
    require(receipt["sendAccepted"], "Original Reporter lost")
    checkpoints.append(observe_receipt(run, value, rows[0], "REPLIED", began, "after-duplicate"))
    target = run.input_workers_by_id[listener["workerId"]]
    input_path = "/lab/v1/workers/" + urllib.parse.quote(target["workerGroupId"], safe="") + "/" + urllib.parse.quote(target["replicaKey"], safe="") + ":inputs"
    injected = http(run.host, input_path, {"eventName": "sms.receive", "payload": {
        "phone": listener["phone"], "text": "[A] 123456", "smsId": "shared-input"}})
    require(injected["status"] == "MATCHED", "Same Worker SMS did not match")
    received = sms_wait(run, listener, "RECEIVED")
    require(received["workerId"] == rows[0]["workerId"], "Cross-product identity mismatch")
    closed, _ = campaign(run, "closed-batch", 1, phone=listener["phone"])
    closed, closed_rows = sent(run, closed)
    http(run.url, f'/api/v1/tasks/{closed["taskId"]}/close', {})
    wait(run, lambda: task_state(run, closed["taskId"]) == "terminal", 10, "explicit Task close")
    for name, status, text in [("deliver", "DELIVERED", None), ("read", "READ", None), ("reply", "REPLIED", "closed-reply")]:
        began, receipt = action(run, closed_rows[0], name, text)
        require(receipt["sendAccepted"], "Closed Task receipt not accepted")
        checkpoints.append(observe_receipt(run, closed, closed_rows[0], status, began, text))
        require(task_state(run, closed["taskId"]) == "terminal", "Receipt reopened closed Task")
    return {"passed": True, "workers": 12, "sharedWorkerId": listener["workerId"], "smsReceived": True,
            "messages": 3, "checkpoints": checkpoints, "requestIdempotency": True, "duplicateExecution": True,
            "reorderedAndDuplicateReceipts": True, "completedAndClosedTasksRemainTerminal": True,
            "hostMetrics": http(run.host, "/lab/v1/messages/metrics")}


def lifecycle(run):
    run.phase_deadline = time.monotonic() + 180
    listener = sms_wait(run, sms_create(run), "LISTENING")
    value, _ = campaign(run, "old-run", 1, phone=listener["phone"])
    value, rows = sent(run, value)
    target = next(w for w in all_pages(run.host, "/lab/v1/messages/inventory") if w["workerId"] == rows[0]["workerId"])
    control = f'/lab/v1/messages/workers/{target["workerGroupId"]}/{target["replicaKey"]}'
    http(run.host, control + ":stop", {})
    _, receipt = action(run, rows[0], "deliver")
    require(not receipt["sendAccepted"], "Stopped run accepted receipt")
    http(run.host, control + ":start", {})
    wait(run, run.connected, 30, "Worker restart")
    _, receipt = action(run, rows[0], "read")
    require(not receipt["sendAccepted"], "New run adopted old Reporter")
    _, receipt = action(run, rows[0], "reply", "old-run-local-only")
    require(not receipt["sendAccepted"], "Old reply entered new run")
    fresh, _ = campaign(run, "new-run", 1, phone=target["phone"])
    fresh, fresh_rows = sent(run, fresh)
    require(fresh_rows[0]["workerId"] == rows[0]["workerId"], "Restart changed Worker identity")
    began, receipt = action(run, fresh_rows[0], "deliver")
    require(receipt["sendAccepted"], "New run did not report its own message")
    checkpoint = observe_receipt(run, fresh, fresh_rows[0], "DELIVERED", began)
    require(campaign_messages(run, value)[0]["status"] == "SENT", "Product fabricated outcome for old run")
    old_result = http(run.url, f'/api/v1/tasks/{value["taskId"]}/results:load', [rows[0]["id"]])[rows[0]["id"]]
    require(json.loads(old_result["opaqueResultPayload"])["status"] == "SENT", "Old run Result changed")
    return {"passed": True, "workers": 12, "reporterRebound": False, "localReceiptsWithoutSend": 3,
            "oldObservationRetained": True, "newRunCheckpoint": checkpoint}


def percentiles(values):
    import math
    values = sorted(values)
    return {"count": len(values), **{f"p{p}": values[math.ceil(len(values) * p / 100) - 1] if values else None for p in (50, 95, 99)}}


def load_1k(run):
    total, rate, seconds = 12_000, 200, 60
    inventory = all_pages(run.host, "/lab/v1/messages/inventory")
    require(Counter(w["country"] for w in inventory) == {"CN": 700, "US": 200, "GB": 100}, "Wrong 1k fixture")
    accepted, campaigns, errors, latencies, lag, action_latencies = [], [], [], [], [], []
    lock, stop, permits = threading.Lock(), threading.Event(), threading.BoundedSemaphore(512)
    receipts_scheduled = set()
    peak = {"submissionQueue": 0, "hostActiveSms": 0}
    http(run.host, "/lab/v1/sms/traffic/start", {"ratePerSecond": 300, "durationSeconds": 135})
    start = time.monotonic()
    run.phase_deadline = start + 180

    def failure(error, source):
        # Keep safe stage/type diagnostics when the workload aborts before its final summary.
        kind = source + ":" + type(error).__name__
        if isinstance(error, urllib.error.HTTPError):
            kind += ":" + str(error.code)
        elif isinstance(error, AssertionError):
            kind += ":" + str(error)  # Only proof-authored assertions, never remote bodies.
        with lock:
            first = kind not in errors
            errors.append(kind)
        if first:
            frames = "/".join(frame.name for frame in traceback.extract_tb(error.__traceback__)[-5:])
            print("1k input failure: " + kind + " at " + frames
                  + ", elapsedSeconds=" + str(round(time.monotonic() - start, 3)), flush=True)

    def submit_sms(index):
        began = time.monotonic()
        try:
            country = "CN" if index % 10 < 7 else "US" if index % 10 < 9 else "GB"
            result = sms_create(run, f"load-sms-{index}", country, ("A", "B", "C")[index % 3])
            with lock:
                accepted.append(result["id"]); latencies.append((time.monotonic() - began) * 1000)
                lag.append(max(0, began - start - index / rate))
        except Exception as error:
            failure(error, "sms.submit")
        finally:
            permits.release()

    def send_campaigns():
        for index in range(12):
            if stop.wait(max(0, start + index * 5 - time.monotonic())):
                return
            try:
                value, _ = campaign(run, f"load-campaign-{index}", 1000, ("CN", "US", "GB")[index % 3])
                with lock:
                    campaigns.append(value)
            except Exception as error:
                failure(error, "campaign.submit"); return

    def recipient_actions(message):
        try:
            for name, text in [("deliver", None), ("read", None), ("reply", "first reply"), ("reply", "latest reply")]:
                if stop.is_set():
                    return
                began, result = action(run, {"id": message["messageId"], "workerId": message["workerId"]}, name, text,
                                        request=message["messageId"] + "-" + str(text))
                require(result["sendAccepted"] and not result["unchanged"], "Load receipt not accepted")
                with lock:
                    action_latencies.append((time.monotonic() - began) * 1000)
        except Exception as error:
            failure(error, "message." + name)

    def recipients():
        # Pages only discover channel records that real Worker execution has created.
        offset = 0
        with ThreadPoolExecutor(max_workers=48) as pool:
            pending = set()
            while not stop.is_set():
                try:
                    page = http(run.host, f"/lab/v1/messages/records?offset={offset}&limit=1000")
                    for message in page["items"]:
                        while len(pending) >= 256 and not stop.is_set():
                            pending = {f for f in pending if not f.done()}
                            time.sleep(.005)
                        if stop.is_set():
                            return
                        require(message["messageId"] not in receipts_scheduled, "Duplicate channel delivery")
                        receipts_scheduled.add(message["messageId"])
                        pending.add(pool.submit(recipient_actions, message))
                    offset += len(page["items"])
                    pending = {f for f in pending if not f.done()}
                    if offset == total and not pending:
                        return
                except Exception as error:
                    failure(error, "message.discovery"); return
                stop.wait(.1)

    campaign_thread = threading.Thread(target=send_campaigns, name="campaign-input")
    recipient_thread = threading.Thread(target=recipients, name="recipient-input")
    campaign_thread.start(); recipient_thread.start()
    try:
        with ThreadPoolExecutor(max_workers=96) as pool:
            for index in range(total):
                delay = start + index / rate - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                require(permits.acquire(blocking=False), "Load generator exceeded bounded admission")
                pool.submit(submit_sms, index)
                if index % 200 == 0:
                    run.check()
                if index and index % 2000 == 0:
                    print(f"1k offered SMS {index}/{total}; campaigns {len(campaigns)}/12; receipt messages {len(receipts_scheduled)}", flush=True)
        offered_seconds = time.monotonic() - start
        campaign_thread.join(timeout=5)
        require(not campaign_thread.is_alive() and len(campaigns) == 12, "Campaign schedule incomplete")
        require(len(accepted) == total and not errors,
                "Load submissions or receipts failed: accepted=" + str(len(accepted))
                + ", errors=" + json.dumps(dict(Counter(errors)), sort_keys=True))
        require(offered_seconds <= seconds + 3 and max(lag, default=0) < 1, "SMS offered rate fell below fixture")
        run.phase_deadline = time.monotonic() + 120

        def converged():
            require(not errors, "Recipient flow failed")
            sms = http(run.url, "/api/v1/sms/metrics")
            messages = http(run.url, "/api/v1/messages/metrics")
            peak["submissionQueue"] = max(peak["submissionQueue"], messages["submissionQueue"])
            peak["hostActiveSms"] = max(peak["hostActiveSms"], http(run.host, "/lab/v1/sms/metrics")["host"]["activeListeners"])
            return messages["statuses"].get("REPLIED", 0) == total and sum(sms["statuses"].get(s, 0) for s in ("RECEIVED", "EXPIRED")) == total
        wait(run, converged, 120, "1k complete business observations")
        wait(run, lambda: len(action_latencies) == total * 4, 120, "all recipient actions complete")
        http(run.host, "/lab/v1/sms/traffic/stop", {})
        require(len(action_latencies) == total * 4 and len(receipts_scheduled) == total, "Not every message got all recipient actions")
        channel = all_pages(run.host, "/lab/v1/messages/records")
        actual = {m["messageId"]: m for m in channel}
        require(len(channel) == len(actual) == total, "Channel delivery identities differ")
        compared = []
        for value in campaigns:
            def latest_observed():
                rows = campaign_messages(run, value)
                return rows if all(message.get("replyRequestId") == actual[message["id"]].get("replyRequestId")
                    and message.get("observedAtMillis") == actual[message["id"]].get("observedAtMillis") for message in rows) else None
            for message in wait(run, latest_observed, 120, "latest reply projection"):
                expected = actual[message["id"]]
                for key in ("campaignId", "recipientId", "country", "body", "workerId", "phone", "status", "reply", "replyRequestId", "observedAtMillis"):
                    require(message.get(key) == expected.get(key), "Messages association or latest reply mismatch")
                compared.append(message["id"])
        require(len(set(compared)) == total, "Message projection missing identities")
        host_sms = all_pages(run.host, "/lab/v1/sms/records")
        product_sms = {r["id"]: r for r in all_pages(run.url, "/api/v1/sms/listeners")}
        require(len(host_sms) == len(product_sms) == total, "SMS record count mismatch")
        for record in host_sms:
            observed = product_sms[record["listenerId"]]
            require(record["status"] == observed["status"] and record["workerId"] == observed["workerId"], "SMS identity or state mismatch")
            if record["status"] == "RECEIVED":
                require(record["smsId"] == observed["sms"]["smsId"], "SMS payload association mismatch")
        return {"passed": True, "workers": 1000, "countryCounts": [700, 200, 100], "smsAccepted": total,
                "campaigns": 12, "messagesSentAndReplied": total, "recipientActions": len(action_latencies),
                "targetRequestsPerSecond": rate, "targetSeconds": seconds, "actualSubmissionSeconds": offered_seconds,
                "actualRequestRate": len(accepted) / offered_seconds, "maximumGeneratorLagSeconds": max(lag),
                "httpErrors": dict(Counter(errors)), "requestLatencyMillis": percentiles(latencies),
                "recipientActionLatencyMillis": percentiles(action_latencies), "backlogPeaks": peak,
                "messagesIdentityDigest": hashlib.sha256(json.dumps(sorted(compared)).encode()).hexdigest(),
                "messageMetrics": http(run.url, "/api/v1/messages/metrics"), "smsMetrics": http(run.url, "/api/v1/sms/metrics"),
                "hostMessageMetrics": http(run.host, "/lab/v1/messages/metrics"), "claim": "fixed combined load, no capacity or fairness claim"}
    finally:
        stop.set()
        campaign_thread.join(timeout=5); recipient_thread.join(timeout=10)


def main():
    global preview, http, all_pages
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["functional", "lifecycle", "load-1k"], default="functional")
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--root", type=Path, default=PREVIEW)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--port", type=int, default=18520)
    args = parser.parse_args()
    if args.build:
        preview.build()
    launcher = args.root.resolve() / "run_preview.py"
    loaded = importlib.util.spec_from_file_location("selected_product_preview", launcher)
    preview = importlib.util.module_from_spec(loaded)
    loaded.loader.exec_module(preview)
    http, all_pages = preview.http, preview.all_pages
    output = (args.output or Path(__file__).parent / "build" / args.scenario).resolve()
    output.mkdir(parents=True, exist_ok=True)
    counts = (700, 200, 100) if args.scenario == "load-1k" else (4, 4, 4)
    sandbox_root = output / "private" / ("inventory-" + uuid.uuid4().hex) / "data" / "scenario-workers"
    materialize_inventory(sandbox_root, product_worker_world(counts))
    run = preview.Preview(sum(counts), args.port, root=args.root, output=output / "private", sandbox_root=sandbox_root)
    result = {"passed": False}
    started = time.monotonic()
    try:
        with run:
            print("Shared Server and Host ready; beginning " + args.scenario, flush=True)
            run.input_workers_by_id = {worker["workerId"]: worker
                                       for worker in all_pages(run.host, "/lab/v1/messages/inventory")}
            result = {"functional": functional, "lifecycle": lifecycle, "load-1k": load_1k}[args.scenario](run)
    except Exception as error:
        result["failureType"] = type(error).__name__
        # Assertion text is proof-authored; never publish a remote response/body.
        if isinstance(error, AssertionError):
            result["failure"] = str(error)
        (output / "private" / "failure.txt").write_text(str(error), encoding="utf-8")
    result.update(scenario=args.scenario, durationSeconds=round(time.monotonic() - started, 3),
                  artifacts=run.artifacts, resourcePeaks=run.peaks, processModel=["server", "host"])
    (output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
