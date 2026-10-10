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
import struct
import psutil
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "integrations"))
from worker_proof_support.scenario_inventory import materialize_inventory, product_worker_world
PREVIEW = REPO / "distribution" / "server"
spec = importlib.util.spec_from_file_location("product_preview", PREVIEW / "run_preview.py")
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)
http, all_pages = preview.http, preview.all_pages


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def complete_stage(run):
    if getattr(run, "current_stage", None) is not None:
        run.completed_stages.append(run.current_stage)
        run.current_stage = None


def begin_stage(run, name):
    if not hasattr(run, "completed_stages"):
        run.completed_stages = []
    complete_stage(run)
    run.current_stage = name
    print("Proof stage: " + name, flush=True)


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


def campaign(run, request="batch", count=2, country="CN", instructions=None, recipient_country=None, app="demo", content=None):
    recipient_country = recipient_country or country or "CN"
    prefix = {"CN": "+86138", "US": "+1202", "GB": "+4477"}[recipient_country]
    body = {"appId": app, "requestId": request, "name": request, "recipientCountry": recipient_country, "senderCountry": country,
            "body": content if content is not None else json.dumps(instructions or {})}
    recipients = [f"{prefix}{i:08d}" for i in range(count)]
    created = http(run.url, "/api/v1/messages/tasks", body, timeout=2)
    receipt = upload_recipients(run.url, created["taskId"], recipients)
    require(receipt["confirmedAddedCount"] == count and receipt["existingCount"] == 0, "Recipient import was not fully confirmed")
    http(run.url, f'/api/v1/messages/tasks/{created["taskId"]}/approve', count, timeout=2)
    return {**created, "expectedCount": count, "recipientIds": recipients}, body


def directed_control_task(run, worker_id, request="control", count=2, instructions=None):
    """Generic Task identity witness, never a Messages creation API escape hatch."""
    group = run.input_workers_by_id[worker_id]["workerGroupId"]
    content = json.dumps(instructions or {})
    created = http(run.url, "/api/v1/tasks", {"projectId": "messages", "workerGroupId": group,
        "name": request, "priority": 50, "maxRetryTimes": 3, "refill": [],
        "metadata": {"scenario": "messages", "recipientCountry": "CN", "body": content}})
    task = created["taskId"]
    recipients = [f"+86138{i:08d}" for i in range(count)]
    items = [{"messageId": f"control-{uuid.uuid4().hex}", "eventCode": "extension.worker.message.send",
              "payload": {"campaignId": task, "country": "CN", "recipientId": recipient, "body": content},
              "priority": 5, "workerSelector": {"executorName": "workerId", "input": worker_id}}
             for recipient in recipients]
    for item in items:
        item["payload"]["messageId"] = item["messageId"]
    appended = http(run.url, f"/api/v1/tasks/{task}/items", items)
    require(set(appended) == {item["messageId"] for item in items}
            and all(value["status"] == "applied" for value in appended.values()), "Control Items were not confirmed")
    http(run.url, f"/api/v1/tasks/{task}/approve", {})
    return {**created, "expectedCount": count, "recipientIds": recipients}, items


def upload_recipients(base, task, recipients, timeout=10):
    request = urllib.request.Request(base + f"/api/v1/messages/tasks/{task}/recipients:import",
            data="\n".join(recipients).encode("utf-8"), headers={"Content-Type": "text/plain;charset=UTF-8"}, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def campaign_messages(run, value):
    if value.get("expectedCount", 0) <= 100:
        detail = http(run.url, f'/api/v1/messages/tasks/{value["taskId"]}')
        return [{**row, "id": row["messageId"]} for row in detail["results"]]
    # Large proof reads known execution IDs through the existing Result API; UI stays bounded.
    ids = [row["messageId"] for row in all_pages(run.host, "/lab/v1/messages/records")
           if row["campaignId"] == value["taskId"]]
    rows = []
    for start in range(0, len(ids), 100):
        results = http(run.url, f'/api/v1/tasks/{value["taskId"]}/results:load', ids[start:start + 100])
        for identity, result in results.items():
            if result["status"] == "succeeded":
                rows.append({**json.loads(result["opaqueResultPayload"]), "id": identity})
            else:
                rows.append({"id": identity, "status": result["status"]})
    return rows


def sent(run, value):
    require(bool(value["taskId"]), "Submission was not confirmed")
    rows = wait(run, lambda: (r if len(r := campaign_messages(run, value)) == value["expectedCount"]
                and all(m.get("status") in ("SENT", "DELIVERED", "READ", "REPLIED") for m in r) else None),
                30, "real Worker sends")
    return value, rows


def task_state(run, task):
    entries = http(run.url, "/api/v1/runtime-view/tasks:preview", 1000)["entries"]
    return next((e["scoreBand"] for e in entries if e["taskId"] == task), None)


def export_results(run, task):
    request = urllib.request.Request(run.url + f"/api/v1/tasks/{task}/results:export", data=b"", method="POST")
    with urllib.request.urlopen(request, timeout=5) as response:
        require(response.headers.get_content_type() == "application/x-ndjson", "Unexpected export content type")
        lines = [json.loads(line) for line in response.read().decode("utf-8").splitlines()]
    require(all(set(line) == {"messageId", "opaqueResultPayload"} for line in lines), "Export shape changed")
    require(len({line["messageId"] for line in lines}) == len(lines), "Duplicate export identity")
    return {line["messageId"]: line["opaqueResultPayload"] for line in lines}


def observe_receipt(run, value, message, expected, started, reply=None, reply_request_id=None):
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
                if (snapshot["status"] == expected and (reply is None or snapshot.get("reply") == reply)
                        and (reply_request_id is None or snapshot.get("replyRequestId") == reply_request_id)):
                    states = http(run.url, f'/api/v1/tasks/{value["taskId"]}/items:states', [message["id"]], timeout=2)
                    if states[message["id"]]["tag"] == tag and platform_ms is None:
                        platform_ms = (time.monotonic() - started) * 1000
            observed = next(m for m in campaign_messages(run, value) if m["id"] == message["id"])
            if (observed["status"] == expected and (reply is None or observed.get("reply") == reply) and product_ms is None
                    and (reply_request_id is None or observed.get("replyRequestId") == reply_request_id)):
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



def lab_message(run, message):
    target = run.input_workers_by_id[message["workerId"]]
    path = "/lab/v1/workers/" + urllib.parse.quote(target["workerGroupId"], safe="") + "/" + urllib.parse.quote(target["replicaKey"], safe="")
    return next(m for m in all_pages(run.host, path + ":messages") if m["messageId"] == message["id"])


def delivered_id(run, message):
    receipt = lab_message(run, message)["receipts"][0]
    require(receipt["status"] == "DELIVERED", "First receipt must follow Lab acceptance")
    return receipt["receiptId"]


def release_delivered(run, message):
    started = time.monotonic()
    response = http(run.host, "/lab/v1/messages/receipts:release", {"receiptIds": [delivered_id(run, message)]})
    require(response == {"offered": 1, "queued": 1}, "Delivered callback was not queued")
    return started


def callback_observed(run, message, receipt_id, accepted):
    def completed():
        receipt = next(r for r in lab_message(run, message)["receipts"] if r["receiptId"] == receipt_id)
        return receipt if receipt["attempts"] else None
    receipt = wait(run, completed, 5, "business callback completion")
    if accepted:
        require(receipt["reportAccepted"] is True and receipt["httpStatus"] == 200 and receipt["failure"] is None,
                "Callback transport or Reporter acceptance failed")
    else:
        require(receipt["httpStatus"] == 404 and receipt["reportAccepted"] is None,
                "Old association was not explicitly rejected by the Worker callback route")
    return receipt


def sms_create(run, country="CN", application="A"):
    return http(run.url, "/api/v1/sms/numbers:lease", {"country": country,
                "applicationId": application, "leaseSeconds": 60}, timeout=6)


def sms_wait(run, listener, state):
    return wait(run, lambda: (v if (v := http(run.url, "/api/v1/sms/messages/" + listener["messageId"]))["status"] == state else None),
                20, "SMS " + state)


def functional(run):
    run.phase_deadline = time.monotonic() + 180
    begin_stage(run, "shared-supply-preconditions")
    sms_catalog = http(run.url, "/api/v1/sms/catalog")
    messages_catalog = http(run.url, "/api/v1/messages/catalog")
    require(set(sms_catalog["countries"]) == {"CN", "US", "GB"}
            and {c["workerGroupId"] for c in messages_catalog["applications"] if c["id"] == "demo"} == {"demo-sim"}, "Unexpected shared product configuration")
    inventory = all_pages(run.host, "/lab/v1/messages/inventory")
    require(len(inventory) == 12, "Expected 12 shared Workers")
    listener = sms_wait(run, sms_create(run), "WAITING")
    require(run.input_workers_by_id[listener["workerId"]]["workerGroupId"] == "demo-sim", "SMS used another Group")
    begin_stage(run, "application-create-identity")
    request = {"appId": "demo", "requestId": "identity", "name": "identity", "recipientCountry": "CN", "body": "{}"}
    created = http(run.url, "/api/v1/messages/tasks", request)
    require(http(run.url, "/api/v1/messages/tasks", request)["taskId"] == created["taskId"], "Campaign request was duplicated")
    try:
        http(run.url, "/api/v1/messages/tasks", {**request, "name": "conflicting"})
        raise AssertionError("Conflicting campaign was accepted")
    except urllib.error.HTTPError as error:
        require(error.code == 409, "Expected campaign conflict")
    http(run.url, f'/api/v1/messages/tasks/{created["taskId"]}/close', {})
    begin_stage(run, "shared-worker-control-send")
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": True})
    created, _ = directed_control_task(run, listener["workerId"])
    value, rows = sent(run, created)
    require(all(m["workerId"] == listener["workerId"] for m in rows), "Targeted campaign did not execute on SMS Worker")
    require(http(run.url, "/api/v1/sms/messages/" + listener["messageId"])["status"] == "WAITING", "Campaign stopped SMS listening")
    begin_stage(run, "terminal-sent-export")
    wait(run, lambda: task_state(run, value["taskId"]) == "terminal", 30, "finite Task automatic completion")
    initial_export = export_results(run, value["taskId"])
    require(set(initial_export) == {m["id"] for m in rows} and all(json.loads(p)["status"] == "SENT" for p in initial_export.values()),
            "Export did not retain actual send results while receipts were held")
    target = run.input_workers_by_id[rows[0]["workerId"]]
    worker_path = "/lab/v1/workers/" + urllib.parse.quote(target["workerGroupId"], safe="") + "/" + urllib.parse.quote(target["replicaKey"], safe="")
    require({m["messageId"] for m in all_pages(run.host, worker_path + ":messages")}
            == {m["id"] for m in rows}, "Worker inbox does not match its real executed messages")
    other = next(w for w in inventory if w["workerId"] != rows[0]["workerId"])
    other_path = "/lab/v1/workers/" + urllib.parse.quote(other["workerGroupId"], safe="") + "/" + urllib.parse.quote(other["replicaKey"], safe="")
    try:
        http(run.host, other_path + ":inputs", {"eventName": "message.read", "payload": {"messageId": rows[0]["id"]}})
        raise AssertionError("Cross-Worker receipt was accepted")
    except urllib.error.HTTPError as error:
        require(error.code == 404, "Expected message ownership rejection")
    require(all(m["status"] == "DELIVERED" for m in all_pages(run.host, worker_path + ":messages")),
            "Rejected cross-Worker input changed local records")
    require(all(m["status"] == "SENT" for m in campaign_messages(run, value)),
            "Rejected cross-Worker input changed product observations")
    begin_stage(run, "completed-task-receipts")
    # Generated before terminality, withheld on the wire until after terminality.
    began = release_delivered(run, rows[0])
    checkpoints = run.receipt_checkpoints = []
    checkpoints.append(observe_receipt(run, value, rows[0], "DELIVERED", began))
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": False})
    for name, status, text in [("read", "READ", None), ("reply", "REPLIED", "first"), ("reply", "REPLIED", "second")]:
        began, receipt = action(run, rows[0], name, text)
        require(receipt["callbackQueued"], "Normal callback was not queued")
        checkpoints.append(observe_receipt(run, value, rows[0], status, began, text))
        require(task_state(run, value["taskId"]) == "terminal", "Receipt reopened completed Task")
    begin_stage(run, "reordered-and-duplicate-receipts")
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": True})
    ids = [delivered_id(run, rows[1])]
    for name, text in [("read", None), ("reply", "old"), ("reply", "latest")]:
        _, receipt = action(run, rows[1], name, text)
        require(receipt["held"] and not receipt["callbackQueued"], "Receipt hold bypassed")
        ids.append(receipt["receiptId"])
    started = time.monotonic()
    released = http(run.host, "/lab/v1/messages/receipts:release", {"receiptIds": [ids[3], ids[3], ids[2], ids[1], ids[0]]})
    require(released == {"offered": 5, "queued": 5}, "Held receipt release not accepted")
    checkpoints.append(observe_receipt(run, value, rows[1], "REPLIED", started, "latest"))
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": False})
    _, duplicate = action(run, rows[0], "reply", "second", "duplicate-reply")
    require(duplicate["callbackQueued"], "First identified reply callback was not queued")
    _, duplicate = action(run, rows[0], "reply", "second", "duplicate-reply")
    require(duplicate["unchanged"] and not duplicate["callbackQueued"], "Reply operation was replayed")
    begin_stage(run, "duplicate-execution-association")
    # Execute the identical send through a real Worker using the shared managed Task via direct identity.
    task = next(c["taskId"] for c in sms_catalog["countries"] if c["id"] == "CN")
    payload = {k: rows[0][k] for k in ("campaignId", "messageId", "country", "recipientId", "body")}
    duplicate_id = str(uuid.uuid4())
    http(run.url, f"/api/v1/tasks/{task}/items:call", {"items": [{"messageId": duplicate_id,
        "eventCode": "extension.worker.message.send", "payload": payload, "ttlMillis": 10000,
        "workerSelector": {"executorName": "workerId", "input": rows[0]["workerId"]}}], "waitTimeoutMillis": 1})
    wait(run, lambda: http(run.url, f"/api/v1/tasks/{task}/results:load", [duplicate_id])[duplicate_id]["status"] == "succeeded", 20, "duplicate real execution")
    require(http(run.host, "/lab/v1/messages/metrics")["messages"] == 2, "Duplicate execution delivered another message")
    # Same Reporter must still target the original Item, not the duplicate execution Item.
    began, receipt = action(run, rows[0], "reply", "after-duplicate")
    require(receipt["callbackQueued"], "Original message callback was not queued")
    checkpoints.append(observe_receipt(run, value, rows[0], "REPLIED", began, "after-duplicate"))
    begin_stage(run, "shared-worker-sms-reception")
    target = run.input_workers_by_id[listener["workerId"]]
    input_path = "/lab/v1/workers/" + urllib.parse.quote(target["workerGroupId"], safe="") + "/" + urllib.parse.quote(target["replicaKey"], safe="") + ":inputs"
    injected = http(run.host, input_path, {"eventName": "sms.receive", "payload": {
        "phone": listener["phoneNumber"], "text": "[A] 123456", "smsId": "shared-input"}})
    require(injected["status"] == "MATCHED", "Same Worker SMS did not match")
    received = sms_wait(run, listener, "RECEIVED")
    require(received["workerId"] == rows[0]["workerId"], "Cross-product identity mismatch")
    begin_stage(run, "closed-task-send")
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": True})
    closed, _ = directed_control_task(run, listener["workerId"], "closed-batch", 1)
    closed, closed_rows = sent(run, closed)
    http(run.url, f'/api/v1/tasks/{closed["taskId"]}/close', {})
    wait(run, lambda: task_state(run, closed["taskId"]) == "terminal", 10, "explicit Task close")
    begin_stage(run, "closed-task-receipts")
    began = release_delivered(run, closed_rows[0])
    checkpoints.append(observe_receipt(run, closed, closed_rows[0], "DELIVERED", began))
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": False})
    for name, status, text in [("read", "READ", None), ("reply", "REPLIED", "closed-reply")]:
        began, receipt = action(run, closed_rows[0], name, text)
        require(receipt["callbackQueued"], "Closed Task callback was not queued")
        checkpoints.append(observe_receipt(run, closed, closed_rows[0], status, began, text))
        require(task_state(run, closed["taskId"]) == "terminal", "Receipt reopened closed Task")
    begin_stage(run, "automatic-send-and-receipts")
    automatic, _ = directed_control_task(run, listener["workerId"], "automatic", 1, instructions={
        "receipts_status": ["read", "replied", "replied"], "delayMs": 200, "probability": 0, "text": "auto"})
    automatic, automatic_rows = sent(run, automatic)
    message = automatic_rows[0]
    local = wait(run, lambda: (m if len((m := lab_message(run, message))["receipts"]) == 4
                              and all(r["attempts"] and r["reportAccepted"] for r in m["receipts"]) else None), 5, "automatic HTTP callbacks")
    require(local["plan"]["completedSteps"] == 3 and not local["plan"]["droppedLast"], "Automatic plan mismatch")
    checkpoints.append(observe_receipt(run, automatic, message, "REPLIED", time.monotonic(), "auto", local["replyRequestId"]))
    results_path = f'/api/v1/tasks/{automatic["taskId"]}/results:load'
    first = http(run.url, results_path, [message["id"]])[message["id"]]
    require(http(run.url, results_path, [message["id"]])[message["id"]] == first, "Reading deleted latest content")
    require(json.loads(first["opaqueResultPayload"])["replyRequestId"] == local["replyRequestId"], "Latest auto reply was not retained")

    begin_stage(run, "latest-result-export")
    exported = export_results(run, value["taskId"])
    require(json.loads(exported[rows[0]["id"]])["reply"] == "after-duplicate", "Export missed the later reply")
    began, receipt = action(run, rows[0], "reply", "after-export")
    checkpoints.append(observe_receipt(run, value, rows[0], "REPLIED", began, "after-export"))
    newest = export_results(run, value["taskId"])
    require(json.loads(newest[rows[0]["id"]])["reply"] == "after-export" and export_results(run, value["taskId"]) == newest,
            "Repeated export did not retain the latest reply")
    return {"passed": True, "workers": 12, "sharedWorkerId": listener["workerId"], "smsReceived": True,
            "messages": 4, "automaticHttpReceipts": 4, "checkpoints": checkpoints, "requestIdempotency": True, "duplicateExecution": True,
            "repeatedLatestResultExport": True,
            "reorderedAndDuplicateReceipts": True, "completedAndClosedTasksRemainTerminal": True,
            "hostMetrics": http(run.host, "/lab/v1/messages/metrics")}


def require_managed_tasks_initial(run):
    for project in ("sms", "messages"):
        directory = http(run.url, f"/api/v1/projects/{project}")
        managed = directory["managedTaskIds"]
        require(set(managed) == ({"demo-sim"} if project == "sms" else {"demo-sim", "app-a-sim", "app-b-sim"}),
                "Unexpected managed Group in Pool fixture")
        page = http(run.url, f"/api/v1/projects/{project}/tasks?limit=100")
        require(not page["truncated"], "Pool fixture Project observation was truncated")
        rows = {row["taskId"]: row for row in page["tasks"]}
        require(all(identity in rows and rows[identity]["scoreBand"] == "running-initial"
                    for identity in managed.values()), "Managed Task became active in Pool-only fixture")


def require_messaging_supply(run, value, country, app="demo"):
    page = http(run.url, "/api/v1/projects/messages/tasks?limit=100")
    require(not page["truncated"], "Pool Task observation was truncated")
    row = next((row for row in page["tasks"] if row["taskId"] == value["taskId"]), None)
    require(row is not None and row["task"] is not None, "Pool Task descriptor is missing")
    target = {} if country is None else {"worker.country": [country]}
    require(row["task"]["refill"] == [{"poolName": "messaging", "target": target, "count": 100}],
            "Ordinary Messages did not declare Messaging Pool supply")
    require("senderPhone" not in row["task"]["metadata"], "Pool Task unexpectedly selected a phone")
    require(row["task"]["workerGroupId"] == app + "-sim" and row["task"]["metadata"].get("appId") == app,
            "Application supply was bound to another Group")


def pool_selection(run):
    run.phase_deadline = time.monotonic() + 180
    begin_stage(run, "pool-supply-preconditions")
    for group in ("demo-sim", "app-a-sim", "app-b-sim"):
        require(Counter(w["country"] for w in run.input_workers_by_id.values() if w["workerGroupId"] == group)
                == {"CN": 4, "US": 4, "GB": 4}, "Expected four Workers per country and Group in Pool fixture")
    require_managed_tasks_initial(run)
    catalog = http(run.url, "/api/v1/messages/catalog")
    require([(app["id"], app["workerGroupId"]) for app in catalog["applications"]]
            == [(app, app + "-sim") for app in ("demo", "app-a", "app-b")], "Wrong application Catalog")
    for app in ("demo", "app-a", "app-b"):
        reference = None
        for country in ("US", None):
            begin_stage(run, app + ("-pool-us-send" if country else "-pool-any-send"))
            value, request = campaign(run, app + ("-cross-country" if country else "-any-country"),
                                      2, country=country, recipient_country="CN", app=app)
            require(http(run.url, "/api/v1/messages/tasks", request)["taskId"] == value["taskId"], "Created Task identity changed")
            try:
                http(run.url, "/api/v1/messages/tasks", {**request, "appId": "app-a" if app == "demo" else "demo"})
                raise AssertionError("Cross-application request identity conflict was accepted")
            except urllib.error.HTTPError as error:
                require(error.code == 409, "Wrong application conflict response")
            require_messaging_supply(run, value, country, app)
            if reference is not None:
                require(value["recipientIds"] == reference, "Country comparison changed recipient fixture")
            reference = value["recipientIds"]
            _, rows = sent(run, value)
            for message in rows:
                require(message["workerId"] in run.input_workers_by_id, "Unknown executor")
                worker = run.input_workers_by_id[message["workerId"]]
                require(worker["workerGroupId"] == app + "-sim", "Execution crossed application Group")
                require(worker["country"] == country if country else worker["country"] in ("CN", "US", "GB"),
                        "Recipient country still constrained actual sender country")
                local = lab_message(run, message)
                require(message["country"] == "CN" and local["recipientId"] == message["recipientId"]
                        and local["workerId"] == worker["workerId"] and local["campaignId"] == value["taskId"],
                        "Pool send bypassed actual Lab reception")
            wait(run, lambda: (len(observed := campaign_messages(run, value)) == value["expectedCount"]
                               and all(row["status"] == "DELIVERED" for row in observed)), 5, "automatic delivery")
    begin_stage(run, "opaque-text-lab-rejection")
    content = "  你好 {{name}}\nplain template text  "
    value, _ = campaign(run, "opaque-text", 1, country=None, app="app-b", content=content)
    detail = wait(run, lambda: (d if (d := http(run.url, f'/api/v1/messages/tasks/{value["taskId"]}'))["task"].get("failedCount") == 1
                              and len(d["results"]) == 1 and d["results"][0]["resultStatus"] == "failed" else None),
                  30, "Lab rejection observed")
    require(detail["task"]["body"] == content and detail["task"]["sentCount"] == 0
            and detail["task"]["deliveredCount"] == 0 and all(row["resultStatus"] == "failed" for row in detail["results"]),
            "Rejected Lab content fabricated successful sending")
    require(not any(row["campaignId"] == value["taskId"] for row in all_pages(run.host, "/lab/v1/messages/records")),
            "Lab accepted ordinary text")
    begin_stage(run, "pool-supply-isolation")
    require_managed_tasks_initial(run)
    return {"passed": True, "workers": 36, "messages": 12, "rejectedContents": 1,
            "applications": 3, "applicationGroupIsolation": True, "managedTasksRemainInitial": True,
            "independentRecipientAndSenderCountries": True, "anyMessagingSend": True}


def lifecycle(run):
    run.phase_deadline = time.monotonic() + 180
    begin_stage(run, "lifecycle-supply-preconditions")
    listener = sms_wait(run, sms_create(run), "WAITING")
    require(run.input_workers_by_id[listener["workerId"]]["workerGroupId"] == "demo-sim", "SMS used another Group")
    begin_stage(run, "lifecycle-old-run-send")
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": True})
    value, _ = directed_control_task(run, listener["workerId"], "old-run", 1)
    value, rows = sent(run, value)
    target = next(w for w in all_pages(run.host, "/lab/v1/messages/inventory") if w["workerId"] == rows[0]["workerId"])
    control = f'/lab/v1/messages/workers/{target["workerGroupId"]}/{target["replicaKey"]}'
    begin_stage(run, "lifecycle-stopped-run-receipt")
    receipt_id = delivered_id(run, rows[0])
    http(run.host, control + ":stop", {})
    require(not any(row["messageId"] == rows[0]["id"] for row in all_pages(run.host, "/lab/v1/messages/records")),
            "Stopped Worker retained message content")
    try:
        http(run.host, "/lab/v1/messages/receipts:release", {"receiptIds": [receipt_id]})
        raise AssertionError("Stopped Worker retained a held receipt")
    except urllib.error.HTTPError as error:
        require(error.code == 400, "Unexpected released-receipt response")
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": False})
    begin_stage(run, "lifecycle-restart-isolation")
    http(run.host, control + ":start", {})
    wait(run, run.connected, 30, "Worker restart")
    for name, text in (("read", None), ("reply", "old-run-local-only")):
        try:
            action(run, rows[0], name, text)
            raise AssertionError("New run adopted an old observation")
        except urllib.error.HTTPError as error:
            require(error.code == 404, "Unexpected old observation response")
    begin_stage(run, "lifecycle-new-run-send")
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": True})
    fresh, _ = directed_control_task(run, target["workerId"], "new-run", 1)
    fresh, fresh_rows = sent(run, fresh)
    require(fresh_rows[0]["workerId"] == rows[0]["workerId"], "Restart changed Worker identity")
    begin_stage(run, "lifecycle-new-run-receipt")
    began = release_delivered(run, fresh_rows[0])
    checkpoint = observe_receipt(run, fresh, fresh_rows[0], "DELIVERED", began)
    begin_stage(run, "lifecycle-old-result-retained")
    require(campaign_messages(run, value)[0]["status"] == "SENT", "Product fabricated outcome for old run")
    old_result = http(run.url, f'/api/v1/tasks/{value["taskId"]}/results:load', [rows[0]["id"]])[rows[0]["id"]]
    require(json.loads(old_result["opaqueResultPayload"])["status"] == "SENT", "Old run Result changed")
    return {"passed": True, "workers": 12, "reporterRebound": False, "oldObservationReleased": True,
            "oldResultRetained": True, "newRunCheckpoint": checkpoint}


def percentiles(values):
    import math
    values = sorted(values)
    return {"count": len(values), **{f"p{p}": values[math.ceil(len(values) * p / 100) - 1] if values else None for p in (50, 95, 99)}}


def load_callbacks_drained(run):
    metrics = http(run.host, "/lab/v1/messages/metrics")
    require(metrics["callbackFailed"] == metrics["droppedReceipts"] == 0, "Load callback was dropped or failed")
    return metrics["callbackQueued"] == metrics["httpSucceeded"] == metrics["reportAccepted"]


def load_recipient_actions(run, message, stop, record_latency):
    # Use the existing hold/release control to bound outstanding callbacks, including
    # automatic delivery. Each committed receipt is released exactly once.
    target = {"id": message["messageId"], "workerId": message["workerId"]}
    receipts = [message["receipts"][0]["receiptId"]]
    for name, text in [("read", None), ("reply", "first reply"), ("reply", "latest reply")]:
        if stop.is_set():
            return
        began, result = action(run, target, name, text, request=message["messageId"] + "-" + str(text))
        require(result["held"] and not result["unchanged"], "Load action did not retain its receipt")
        record_latency((time.monotonic() - began) * 1000)
        receipts.append(result["receiptId"])
    require(http(run.host, "/lab/v1/messages/receipts:release", {"receiptIds": receipts})
            == {"offered": 4, "queued": 4}, "Load callbacks were not queued")
    # 24 input workers x four receipts leaves at most 96 outstanding callbacks.
    # Each receipt still traverses its own real HTTP and original Reporter.
    wait(run, lambda: load_callbacks_drained(run), 5, "bounded load callbacks")


def load_1k(run):
    total, rate, seconds = 12_000, 200, 60
    inventory = all_pages(run.host, "/lab/v1/messages/inventory")
    require(Counter(w["country"] for w in inventory) == {"CN": 700, "US": 200, "GB": 100}, "Wrong 1k fixture")
    accepted, campaigns, errors, latencies, lag, action_latencies = [], [], [], [], [], []
    lock, stop, permits = threading.Lock(), threading.Event(), threading.BoundedSemaphore(512)
    receipts_scheduled = set()
    peak = {"hostActiveSms": 0}
    http(run.host, "/lab/v1/messages/receipts:hold", {"enabled": True})
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
            result = sms_create(run, country, ("A", "B", "C")[index % 3])
            with lock:
                accepted.append(result); latencies.append((time.monotonic() - began) * 1000)
                lag.append(max(0, began - start - index / (rate / 8)))
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
            def recorded(latency):
                with lock:
                    action_latencies.append(latency)
            load_recipient_actions(run, message, stop, recorded)
        except Exception as error:
            failure(error, "message.receipt")

    def recipients():
        # Pages only discover channel records that real Worker execution has created.
        offset = 0
        with ThreadPoolExecutor(max_workers=24) as pool:
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
            for index in range(total // 8):
                delay = start + index / (rate / 8) - time.monotonic()
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
        require(len(accepted) == total // 8 and not errors,
                "Load submissions or receipts failed: accepted=" + str(len(accepted))
                + ", errors=" + json.dumps(dict(Counter(errors)), sort_keys=True))
        require(offered_seconds <= seconds + 3 and max(lag, default=0) < 1, "SMS offered rate fell below fixture")
        run.phase_deadline = time.monotonic() + 120

        def converged():
            require(not errors, "Recipient flow failed")
            messages = http(run.url, "/api/v1/messages/tasks?limit=100")
            peak["hostActiveSms"] = max(peak["hostActiveSms"], http(run.host, "/lab/v1/sms/metrics")["host"]["activeAssociations"])
            return sum(task.get("repliedCount", 0) for task in messages["tasks"]) == total and http(run.host, "/lab/v1/sms/metrics")["host"]["activeAssociations"] == 0
        wait(run, converged, 120, "1k complete business observations")
        wait(run, lambda: len(action_latencies) == total * 3, 120, "all manual recipient actions complete")
        http(run.host, "/lab/v1/sms/traffic/stop", {})
        require(len(action_latencies) == total * 3 and len(receipts_scheduled) == total, "Not every message got all recipient actions")
        channel = all_pages(run.host, "/lab/v1/messages/records")
        actual = {m["messageId"]: m for m in channel}
        require(len(channel) == len(actual) == total, "Channel delivery identities differ")
        require(all(len(m["receipts"]) == 4 and m["receipts"][0]["status"] == "DELIVERED" for m in channel),
                "Expected actual delivery plus read and two reply facts for every message")
        require(all(r["attempts"] == 1 and r["reportAccepted"] is True and r["httpStatus"] == 200
                    for m in channel for r in m["receipts"]), "A receipt was lost, rejected or replayed")
        compared = []
        for value in campaigns:
            def latest_observed():
                rows = campaign_messages(run, value)
                return rows if len(rows) == value["expectedCount"] and all(message.get("replyRequestId") == actual[message["id"]].get("replyRequestId")
                    and message.get("observedAtMillis") == actual[message["id"]].get("observedAtMillis") for message in rows) else None
            for message in wait(run, latest_observed, 120, "latest reply projection"):
                expected = actual[message["id"]]
                for key in ("campaignId", "recipientId", "country", "body", "workerId", "phone", "status", "reply", "replyRequestId", "observedAtMillis"):
                    require(message.get(key) == expected.get(key), "Messages association or latest reply mismatch")
                compared.append(message["id"])
        require(len(set(compared)) == total, "Message projection missing identities")
        host_sms = all_pages(run.host, "/lab/v1/sms/records")
        product_sms = {r["messageId"]: http(run.url, "/api/v1/sms/messages/" + r["messageId"]) for r in accepted}
        require(len(host_sms) == len(product_sms) == total // 8, "SMS record count mismatch")
        for record in host_sms:
            observed = product_sms[record["messageId"]]
            require(not observed["leaseActive"] and record["workerId"] == observed["workerId"], "SMS identity or state mismatch")
            if record["status"] == "RECEIVED":
                require(record["smsId"] == observed["sms"]["smsId"], "SMS payload association mismatch")
        return {"passed": True, "workers": 1000, "countryCounts": [700, 200, 100], "smsAccepted": total // 8, "smsRequestsPerSecond": rate / 8,
                "campaigns": 12, "messagesSentAndReplied": total, "recipientActions": len(action_latencies),
                "targetRequestsPerSecond": rate / 8, "targetSeconds": seconds, "actualSubmissionSeconds": offered_seconds,
                "actualRequestRate": len(accepted) / offered_seconds, "maximumGeneratorLagSeconds": max(lag),
                "httpErrors": dict(Counter(errors)), "requestLatencyMillis": percentiles(latencies),
                "recipientActionLatencyMillis": percentiles(action_latencies), "backlogPeaks": peak,
                "messagesIdentityDigest": hashlib.sha256(json.dumps(sorted(compared)).encode()).hexdigest(),
                "messageCounts": [{key: task.get(key) for key in ("sendTotal", "sentCount", "deliveredCount", "readCount", "repliedCount", "failedCount")}
                    for task in http(run.url, "/api/v1/messages/tasks?limit=100")["tasks"]], "smsMetrics": http(run.url, "/api/v1/sms/metrics"),
                "hostMessageMetrics": http(run.host, "/lab/v1/messages/metrics"), "claim": "fixed combined load, no capacity or fairness claim"}
    finally:
        stop.set()
        campaign_thread.join(timeout=5); recipient_thread.join(timeout=10)


def message_identity(task, recipient):
    return "message-" + tuple_digest("messages/v2/message", task, recipient)


def message_send_worker_world():
    world = {}
    for app, count, first_phone in (("demo", 400, 861700000001), ("app-a", 300, 861710000001), ("app-b", 300, 861720000001)):
        world[app + "-sim"] = tuple({"runtime": "java", "simulated": "true", "application": app,
                "phone": str(first_phone + index), "country": "CN", "operator": "Preview SIM", "messaging.enabled": "true"}
                for index in range(count))
    return world


def tuple_digest(*values):
    digest = hashlib.sha256()
    for value in values:
        encoded = value.encode("utf-8")
        digest.update(struct.pack(">I", len(encoded))); digest.update(encoded)
    return digest.hexdigest()


def verify_send_evidence(task, expected, results, states, accepted, group, inventory):
    """Independent public Result, Item and receiving-service witnesses; never a preview count."""
    verified = []
    witnesses = {row["messageId"]: row for row in accepted["items"]}
    require(len(witnesses) == len(accepted["items"]), "Duplicate acceptance witness")
    require(set(witnesses).issubset(expected), "Unexpected acceptance identity")
    for identity, (recipient, body) in expected.items():
        result = results.get(identity, {})
        require(result.get("status") != "failed", "A sending Item failed")
        if result.get("status") != "succeeded":
            continue
        require(identity in witnesses, "Successful Result lacks a retained acceptance witness")
        witness = witnesses[identity]
        require(witness["inputFingerprint"] == tuple_digest("lab-message-input/v1", task, identity, "CN", recipient, body),
                "Acceptance input fingerprint differs")
        sender = witness["sender"]
        require(sender["workerGroupId"] == group and inventory.get(sender["workerId"], {}).get("workerGroupId") == group,
                "Send escaped the selected application Group")
        snapshot = json.loads(result["opaqueResultPayload"])
        require(all(snapshot.get(key) == value for key, value in {
            "campaignId": task, "messageId": identity, "recipientId": recipient, "country": "CN", "body": body,
            "status": "SENT", "workerId": sender["workerId"], "phone": sender["phone"],
            "observedAtMillis": witness["observedAtMillis"]}.items()), "SENT content or association differs")
        state = states.get(identity, {})
        require(state.get("tag") != 5, "Item is terminal-failed despite acceptance")
        if state.get("tag") == 6 and state.get("band") == "terminal":
            verified.append(identity)
    return verified


def messages_send_100k(run):
    require(Counter(worker["workerGroupId"] for worker in run.input_workers_by_id.values())
            == {"demo-sim": 400, "app-a-sim": 300, "app-b-sim": 300}, "Wrong sending workload inventory")
    body = "  您好 {{name}}\nMessages text sending proof.  "
    task_reports, verified_ids, import_seconds = [], set(), 0.0
    send_seconds, last_sample, cache_peaks = 0.0, 0.0, {}
    resource_path = run.output.parent / "process-resources.jsonl"
    run.sending_progress = {"verifiedSends": 0, "completedTasks": 0, "importSeconds": 0, "sendAndObserveSeconds": 0}

    def sample(force=False):
        nonlocal last_sample
        now = time.monotonic()
        if not force and now - last_sample < 5:
            return
        run.check(); last_sample = now
        metrics = http(run.host, "/lab/v1/messages/metrics", timeout=5)
        run.sending_progress["lastHostMessageMetrics"] = metrics
        for key, bound in {"dedupEntries": 200000, "trackedMessages": 20000, "recentSentRecords": 1000,
                           "held": 10000, "replyIds": 100000}.items():
            require(0 <= metrics[key] <= bound, "Messages cache exceeded its configured bound")
            cache_peaks[key] = max(cache_peaks.get(key, 0), metrics[key])
        require(metrics["reporters"] + metrics["pendingAssociations"] <= 20000, "Reporter bound exceeded")
        processes = {}
        for name, process in run.processes.items():
            observed = psutil.Process(process.pid)
            record = {"rssBytes": observed.memory_info().rss, "threads": observed.num_threads()}
            if hasattr(observed, "num_fds"):
                record["fileDescriptors"] = observed.num_fds()
            else:
                record["handles"] = observed.num_handles()
            processes[name] = record
        with resource_path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"observedAtMillis": int(time.time() * 1000), "processes": processes,
                                     "messages": metrics}) + "\n")

    for index in range(51):
        app, count = ("app-a", 100000) if index == 0 else (("demo", "app-a", "app-b")[(index - 1) % 3], 100)
        created = http(run.url, "/api/v1/messages/tasks", {"appId": app, "requestId": f"send-proof-{index}",
                "name": f"send-proof-{index}", "recipientCountry": "CN", "senderCountry": None, "body": body}, timeout=5)
        task = created["taskId"]
        run.sending_progress.update(currentTask=index + 1, currentApplication=app, currentExpectedCount=count)
        recipients = [f"+86138{i:08d}" for i in range(count)]
        began_import = time.monotonic()
        receipt = upload_recipients(run.url, task, recipients, timeout=180)
        elapsed_import = time.monotonic() - began_import; import_seconds += elapsed_import
        run.sending_progress["importSeconds"] = import_seconds
        require(receipt["confirmedAddedCount"] == count and receipt["existingCount"] == 0, "Sending import incomplete")
        expected = {message_identity(task, number): (number, body) for number in recipients}
        pending = dict(expected)
        began_send = time.monotonic()
        remaining_seconds = 900 - send_seconds
        require(remaining_seconds > 0, "Sending phases exceeded 15 minutes")
        run.phase_deadline = began_send + remaining_seconds
        http(run.url, f"/api/v1/messages/tasks/{task}/approve", count, timeout=5)
        while pending:
            require(time.monotonic() < run.phase_deadline, "Sending evidence did not converge within 15 minutes")
            before = len(pending)
            pass_ids = list(pending)
            for offset in range(0, len(pass_ids), 100):
                ids = pass_ids[offset:offset + 100]
                if not ids:
                    break
                sample()
                require(time.monotonic() < run.phase_deadline, "Sending phases exceeded 15 minutes")
                results = http(run.url, f"/api/v1/tasks/{task}/results:load", ids, timeout=5)
                succeeded = [identity for identity in ids if results.get(identity, {}).get("status") == "succeeded"]
                require(not any(row.get("status") == "failed" for row in results.values()), "A sending Item failed")
                if not succeeded:
                    continue
                acceptances = http(run.host, "/lab/v1/messages/acceptances:load", {"messageIds": succeeded}, timeout=5)
                states = http(run.url, f"/api/v1/tasks/{task}/items:states", succeeded, timeout=5)
                confirmed = verify_send_evidence(task, {identity: expected[identity] for identity in succeeded},
                        results, states, acceptances, app + "-sim", run.input_workers_by_id)
                for identity in confirmed:
                    require(identity not in verified_ids, "Message identity reused by another Task")
                    verified_ids.add(identity); pending.pop(identity)
                run.sending_progress["verifiedSends"] = len(verified_ids)
            if len(pending) == before:
                time.sleep(.05)
        wait(run, lambda: task_state(run, task) == "terminal", remaining_seconds, "sending Task terminal")
        detail = http(run.url, f"/api/v1/messages/tasks/{task}", timeout=5)["task"]
        require(detail["sendTotal"] == detail["sentCount"] == count and detail["failedCount"] == 0
                and detail["deliveredCount"] == detail["readCount"] == detail["repliedCount"] == 0,
                "Task counts differ from exact no-receipt sending evidence")
        elapsed_send = time.monotonic() - began_send; send_seconds += elapsed_send
        run.sending_progress.update(completedTasks=index + 1, sendAndObserveSeconds=send_seconds)
        task_reports.append({"appId": app, "count": count, "importSeconds": elapsed_import,
                             "sendAndObserveSeconds": elapsed_send, "idsSha256": hashlib.sha256("\n".join(sorted(expected)).encode()).hexdigest()})
        sample(True)
        print(f"Messages sending proof: {index + 1}/51 Tasks, {len(verified_ids)}/105000 verified sends", flush=True)
    metrics = http(run.host, "/lab/v1/messages/metrics", timeout=5)
    require(len(verified_ids) == metrics["acceptedMessages"] == 105000, "Receiving count differs from verified identities")
    require(metrics["callbackOffered"] == 0 and metrics["scheduled"] == 0, "Plain text manufactured automatic receipts")
    require(metrics["skippedAssociations"] > 0, "Workload did not cross the receipt association bound")
    return {"passed": True, "workers": 1000, "taskCount": 51, "verifiedSends": len(verified_ids),
            "importSeconds": import_seconds, "sendAndObserveSeconds": send_seconds,
            "observedSendsPerSecond": len(verified_ids) / send_seconds, "tasks": task_reports,
            "cachePeaks": cache_peaks, "hostMessageMetrics": metrics,
            "additionalSendAttempts": metrics["sendAttempts"] - len(verified_ids),
            "additionalAttemptsMeaning": "Worker send invocations beyond unique messages; not a Kernel retry counter",
            "messageIdsSha256": hashlib.sha256("\n".join(sorted(verified_ids)).encode()).hexdigest(),
            "claim": "Actual Redis/HTTP/Worker execution and simulated receiver acceptance without receipts; no third-party delivery or throughput SLA"}


def execute_scenario(run, scenario, output):
    result = {"passed": False}
    started = time.monotonic()
    small = scenario not in ("load-1k", "messages-send-100k")
    if small:
        begin_stage(run, "startup")
    try:
        with run:
            print("Shared Server and Host ready; beginning " + scenario, flush=True)
            if small:
                begin_stage(run, "inventory")
            run.input_workers_by_id = {worker["workerId"]: worker
                                       for worker in all_pages(run.host, "/lab/v1/messages/inventory")}
            result = {"functional": functional, "pool-selection": pool_selection,
                      "lifecycle": lifecycle, "load-1k": load_1k, "messages-send-100k": messages_send_100k}[scenario](run)
            if small:
                begin_stage(run, "shutdown")
        if small:
            complete_stage(run)
    except Exception as error:
        result["passed"] = False
        result["failureType"] = type(error).__name__
        # Assertion text is proof-authored; never publish a remote response/body.
        if isinstance(error, AssertionError):
            result["failure"] = str(error)
        (output / "private" / "failure.txt").write_text(str(error), encoding="utf-8")
        if hasattr(run, "sending_progress"):
            result["sendingProgress"] = run.sending_progress
    if small:
        result["completedStages"] = run.completed_stages
        if run.current_stage is not None:
            result["failedStage"] = run.current_stage
        if hasattr(run, "receipt_checkpoints"):
            result["checkpoints"] = run.receipt_checkpoints
    result.update(scenario=scenario, durationSeconds=round(time.monotonic() - started, 3),
                  artifacts=run.artifacts, resourcePeaks=run.peaks, processModel=["server", "host"])
    return result


def main():
    global preview, http, all_pages
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["functional", "pool-selection", "lifecycle", "load-1k", "messages-send-100k"], default="functional")
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
    counts = (400, 0, 0) if args.scenario == "messages-send-100k" else (700, 200, 100) if args.scenario == "load-1k" else (4, 4, 4)
    sandbox_root = output / "private" / ("inventory-" + uuid.uuid4().hex) / "data" / "scenario-workers"
    world = message_send_worker_world() if args.scenario == "messages-send-100k" else product_worker_world(counts)
    app_count = 300 if args.scenario == "messages-send-100k" else 0
    if args.scenario == "pool-selection":
        app_count = 12
        for app, offset in (("app-a", 1_000_000), ("app-b", 2_000_000)):
            world[app + "-sim"] = tuple({**properties, "application": app, "phone": str(int(properties["phone"]) + offset)}
                                         for properties in world["demo-sim"][:app_count])
    materialize_inventory(sandbox_root, world)
    run = preview.Preview(sum(counts), args.port, root=args.root, output=output / "private", sandbox_root=sandbox_root, app_count=app_count,
                          message_content_mode="text" if args.scenario == "messages-send-100k" else "lab-json")
    result = execute_scenario(run, args.scenario, output)
    (output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
