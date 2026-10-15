"""RPC Result consumption/idle memory proof through real HTTP and Workers in a fresh scope."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import statistics
import threading
import time
import urllib.parse
import uuid

REPO = Path(__file__).resolve().parents[2]
CALLER = {"X-XA-Dev-User": "MTAwMA", "X-XA-Dev-Role": "admin"}


def require(value, message):
    if not value:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO / "distribution/server")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18730)
    parser.add_argument("--items", type=int, default=10000)
    parser.add_argument("--workers", type=int, default=200)
    args = parser.parse_args()
    require(args.items > 0 and args.workers > 0, "Positive workload dimensions required")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location("rpc_result_preview", args.root.resolve() / "run_preview.py")
    preview = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(preview)

    class RpcPreview(preview.Preview):
        def launch(self, name, command, env):
            if name == "server":
                env = dict(env)
                env["SPRING_APPLICATION_JSON"] = json.dumps({"xa.mass.task-rpc.result-idle-ttl": "15s"})
            super().launch(name, command, env)

    run = RpcPreview(args.workers, args.port, root=args.root, output=output / "private",
                     sandbox_root=output / "private/data/scenario-workers", app_count=0)
    report = {"passed": False, "items": args.items, "workers": args.workers,
              "claim": "Result HASH reclamation, not Item cleanup, reliable delivery or a throughput SLA"}
    stop = threading.Event()
    monitor = None
    samples, take_latencies, call_latencies = [], [], []
    task = "rpc-result-proof-" + uuid.uuid4().hex

    def http(path, body=None, base=None):
        return preview.http(base or run.url, path, body, headers=CALLER, timeout=90)

    def snapshot():
        prefix = "xa_mass:" + run.scope + ":task:" + task
        return {name: {"bytes": run.redis.memory_usage(prefix + ":" + name, samples=100) or 0,
                       "exists": bool(run.redis.exists(prefix + ":" + name))}
                for name in ("descriptor", "items", "results", "item_score")}

    try:
        with run:
            report.update(scope=run.scope, artifacts=run.artifacts)
            workers = preview.all_pages(run.host, "/lab/v1/messages/inventory")
            require(len(workers) == args.workers, "Unexpected proof Worker inventory")
            group = workers[0]["workerGroupId"]
            http(f"/api/v1/tasks/{task}/rpc:prepare", {"projectId": "default", "workerGroupId": group, "refill": []})
            result_key = "xa_mass:" + run.scope + ":task:" + task + ":results"

            def monitor_results():
                while not stop.wait(.1):
                    began = time.monotonic()
                    run.redis.ping()
                    samples.append({"pingMillis": (time.monotonic() - began) * 1000,
                                    "fields": run.redis.hlen(result_key), "bytes": run.redis.memory_usage(result_key) or 0})

            monitor = threading.Thread(target=monitor_results, daemon=True)
            monitor.start()
            received = {}

            def retain(rows):
                for mid, result in rows.items():
                    require(result["status"] in ("succeeded", "not_observed"), "RPC execution failed")
                    if result["status"] == "succeeded":
                        value = json.loads(result["opaqueResultPayload"])
                        require(value.get("input") == mid and value.get("md5") == hashlib.md5(mid.encode()).hexdigest(),
                                "Result content/identity mismatch")
                        received[mid] = result

            def item(mid, worker):
                return {"messageId": mid, "eventCode": "extension.worker.string.md5", "payload": {"value": mid},
                        "ttlMillis": 300000, "workerSelector": {"executorName": "workerId", "input": worker["workerId"]}}

            began = time.monotonic()
            ids = ["rpc-proof-" + str(i) for i in range(args.items)]
            for offset in range(0, len(ids), 100):
                page = ids[offset:offset + 100]
                started = time.monotonic()
                retain(http(f"/api/v1/tasks/{task}/items:call", {
                    "items": [item(mid, workers[(offset + index) % len(workers)]) for index, mid in enumerate(page)],
                    "waitTimeoutMillis": 30000}))
                call_latencies.append((time.monotonic() - started) * 1000)
                if (offset + len(page)) % 1000 == 0:
                    print(f"Submitted {offset + len(page)} Items; received {len(received)} Results", flush=True)
            report["afterCalls"] = snapshot()
            until = time.monotonic() + 120
            while len(received) < len(ids) and time.monotonic() < until:
                pending = [mid for mid in ids if mid not in received]
                for offset in range(0, len(pending), 1000):
                    started = time.monotonic()
                    retain(http(f"/api/v1/tasks/{task}/results:take", pending[offset:offset + 1000]))
                    take_latencies.append((time.monotonic() - started) * 1000)
                if len(received) < len(ids):
                    time.sleep(.05)
            require(len(received) == len(ids), "Some RPC responses were not received in the controlled workload")
            run.wait_for(lambda: run.redis.hlen(result_key) == 0, 10, "consumed Result HASH empty")
            report.update(received=len(received), durationSeconds=time.monotonic() - began, afterConsumption=snapshot())

            # A stopped directed Worker prevents immediate consumption by the short Call.
            worker = workers[0]
            control = "/lab/v1/workers/" + urllib.parse.quote(group, safe="") + "/" + urllib.parse.quote(worker["replicaKey"], safe="")
            http(control + ":stop", {}, run.host)
            orphan = "idle-orphan"
            response = http(f"/api/v1/tasks/{task}/items:call", {"items": [item(orphan, worker)], "waitTimeoutMillis": 1})
            require(response[orphan]["status"] == "not_observed", "Stopped Worker unexpectedly produced a Result")
            http(control + ":start", {}, run.host)
            run.wait_for(lambda: run.redis.hexists(result_key, orphan), 60, "unclaimed late Result")
            report["unclaimed"] = snapshot()
            report["unclaimedTtlMillis"] = run.redis.pttl(result_key)
            require(0 < report["unclaimedTtlMillis"] <= 15000, "Proof idle TTL was not applied")
            run.wait_for(lambda: not run.redis.exists(result_key), 30, "idle Result expiry")
            after_idle = snapshot()
            require(all(after_idle[key]["exists"] for key in ("descriptor", "items", "item_score")), "Idle expiry removed execution resources")
            fresh = "after-idle"
            retain(http(f"/api/v1/tasks/{task}/items:call", {"items": [item(fresh, worker)], "waitTimeoutMillis": 30000}))
            require(fresh in received, "Same RPC Task did not accept new work after Result expiry")
            report.update(afterIdle=after_idle, resultAfterIdle=True, passed=True)
            stop.set()
            monitor.join(5)
    except Exception as error:
        report.update(passed=False, failureType=type(error).__name__)
        if isinstance(error, AssertionError):
            report["failure"] = str(error)
        (output / "failure.txt").write_text(str(error), encoding="utf-8")
    finally:
        stop.set()
        if monitor is not None:
            monitor.join(5)
        report["samples"] = samples
        report["takeLatencyMillis"] = take_latencies
        report["callBatchLatencyMillis"] = call_latencies
        if samples:
            pings = sorted(sample["pingMillis"] for sample in samples)
            report["peakResultFields"] = max(sample["fields"] for sample in samples)
            report["peakResultBytes"] = max(sample["bytes"] for sample in samples)
            report["redisPingMillis"] = {"median": statistics.median(pings), "p95": pings[int(.95 * (len(pings) - 1))], "max": max(pings)}
        (output / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in ("samples", "artifacts")}, indent=2), flush=True)
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
