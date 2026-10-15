"""Opt-in finite Task storage proof; fresh Preview scope, real Workers, read-only Redis diagnostics."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

import psutil
import redis

REPO = Path(__file__).resolve().parents[2]
HEADERS = {"X-XA-Dev-User": "MTAwMA", "X-XA-Dev-Role": "admin"}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def request(base, path, body=None, *, method=None, text=False):
    headers = {**HEADERS, "Content-Type": "text/plain; charset=utf-8" if text else "application/json"}
    data = None if body is None else (body.encode() if text else json.dumps(body).encode())
    req = urllib.request.Request(base + "/api/v1" + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=180) as response:
        return json.load(response)


def export(base, path):
    req = urllib.request.Request(base + "/api/v1" + path, headers=HEADERS, method="POST")
    size = lines = 0
    digest = hashlib.sha256()
    started = time.monotonic()
    with urllib.request.urlopen(req, timeout=180) as response:
        while chunk := response.read(65536):
            size += len(chunk)
            lines += chunk.count(b"\n")
            digest.update(chunk)
    return {"bytes": size, "lines": lines, "sha256": digest.hexdigest(), "seconds": time.monotonic() - started}


def snapshot(client, prefix, task):
    rows = {}
    for suffix in ("descriptor", "items", "results", "item_score"):
        key = prefix + ":task:" + task + ":" + suffix
        rows[suffix] = {"bytes": client.memory_usage(key, samples=100) or 0, "exists": bool(client.exists(key))}
    return {"resources": rows, "bytes": sum(row["bytes"] for row in rows.values())}


def archived_successes(folder):
    count = 0
    digest = hashlib.sha256()
    with (folder / "results.jsonl").open(encoding="utf-8") as records:
        for line in records:
            row = json.loads(line)
            if row["result"]["code"] == "200":
                value = {"messageId": row["messageId"], "opaqueResultPayload": row["result"]["opaqueResultPayload"]}
                digest.update((json.dumps(value, ensure_ascii=False, separators=(",", ":")) + os.linesep).encode())
                count += 1
    return count, digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO / "distribution/server")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--port", type=int, default=18720)
    parser.add_argument("--app-count", type=int, default=2000)
    args = parser.parse_args()
    output = (args.output or REPO / "build" / ("finite-retention-" + uuid.uuid4().hex)).resolve()
    output.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location("retention_preview", args.root.resolve() / "run_preview.py")
    preview = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(preview)

    class RetentionPreview(preview.Preview):
        def launch(self, name, command, env):
            if name == "server":
                env = dict(env)
                # Exercise elapsed eligibility without changing production defaults or Redis clocks.
                env["SPRING_APPLICATION_JSON"] = json.dumps({"xa.mass.task-retention": {
                    "enabled": True, "grace": "2s", "retention": "7d", "interval": "1h"}})
                self.server_command, self.server_environment = command, env
            super().launch(name, command, env)

    run = RetentionPreview(1, args.port, root=args.root, output=output / "private",
                           sandbox_root=output / "private/data/scenario-workers", app_count=args.app_count)
    report = {"passed": False, "cases": [], "samples": [], "claim": "Finite storage lifecycle with simulated App Workers; no throughput SLA or RSS reclamation guarantee"}
    stop = threading.Event()
    monitor = None
    heap_process = None
    heap_stream = None
    stage = "startup"

    def save():
        (output / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    def sample():
        client = redis.Redis.from_url(run.redis_url, socket_timeout=3)
        try:
            while not stop.wait(.5):
                try:
                    started = time.monotonic()
                    client.ping()
                    report["samples"].append({"at": time.time(), "stage": stage,
                        "pingMillis": (time.monotonic() - started) * 1000,
                        "serverRssBytes": psutil.Process(run.processes["server"].pid).memory_info().rss})
                except (OSError, redis.RedisError, psutil.Error):
                    pass
        finally:
            client.close()

    try:
        with run:
            report.update(scope=run.scope, artifacts=run.artifacts, workersPerApp=args.app_count)
            prefix = "xa_mass:" + run.scope
            client = run.redis
            jstat = Path(shutil.which("java")).with_name("jstat.exe" if os.name == "nt" else "jstat")
            heap_stream = (output / "heap-samples.txt").open("w", encoding="utf-8")
            heap_process = subprocess.Popen([str(jstat), "-gc", "-t", str(run.processes["server"].pid), "500"],
                stdout=heap_stream, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            jcmd = jstat.with_name("jcmd.exe" if os.name == "nt" else "jcmd")
            subprocess.run([str(jcmd), str(run.processes["server"].pid), "JFR.start", "name=retention", "settings=profile"],
                           check=True, capture_output=True)
            monitor = threading.Thread(target=sample, daemon=True)
            monitor.start()
            for count, mode in ((10000, "natural"), (100000, "natural"), (10000, "closed"), (100000, "closed")):
                stage = f"{mode}-{count}"
                body = {"requestId": f"retention-{mode}-{count}", "appId": "app-a", "country": "CN"}
                task = request(run.url, "/app-checks/tasks", body)["taskId"]
                case = {"count": count, "mode": mode, "taskId": task, "creation": body}
                report["cases"].append(case)
                started = time.monotonic()
                receipt = request(run.url, f"/app-checks/tasks/{task}/numbers:import",
                    "\n".join("+" + str(8618100000000 + i) for i in range(count)), text=True)
                require(receipt["addedCount"] == count, "Import did not confirm every Item")
                case["importSeconds"] = time.monotonic() - started
                case["imported"] = snapshot(client, prefix, task)
                request(run.url, f"/tasks/{task}/approve", {"expectedItemCount": count})
                began = time.monotonic()
                if mode == "closed":
                    run.wait_for(lambda: client.hlen(prefix + f":task:{task}:results") > 0, 60, "first actual result")
                    request(run.url, f"/tasks/{task}/close", method="POST")
                run.wait_for(lambda: request(run.url, f"/projects/app-checks/tasks/{task}")["scoreBand"] == "terminal",
                             900, "natural or explicit terminal state")
                case["executionSeconds"] = time.monotonic() - began
                ended = request(run.url, f"/projects/app-checks/tasks/{task}")["retention"]["terminatedAtMillis"]
                # Let real in-flight completions use the configured grace window.
                while time.time() * 1000 < ended + 4000:
                    run.check()
                    time.sleep(.2)
                case["before"] = snapshot(client, prefix, task)
                case["countsBeforeFreeze"] = request(run.url, "/projects/app-checks/tasks:stats", [task])[task]
                case["liveJsonl"] = export(run.url, f"/tasks/{task}/results:export")
                case["liveCsv"] = export(run.url, f"/app-checks/tasks/{task}/results:export?filter=all")
                stage = f"archive-{mode}-{count}"
                case["archiveStartedAt"] = time.time()
                started = time.monotonic()
                outcome = request(run.url, f"/tasks/{task}/retention:run", method="POST")
                case["archiveSeconds"] = time.monotonic() - started
                case["archiveFinishedAt"] = time.time()
                require(outcome["retention"]["phase"] == "RELEASED", "Archive was not confirmed")
                case["after"] = snapshot(client, prefix, task)
                require(not case["after"]["resources"]["items"]["exists"] and not case["after"]["resources"]["results"]["exists"],
                        "Payload hashes survived archive release")
                case["counts"] = request(run.url, "/projects/app-checks/tasks:stats", [task])[task]
                require(case["counts"]["total"] == case["countsBeforeFreeze"]["total"] == count, "Archive lost Item counts")
                case["archivedJsonl"] = export(run.url, f"/tasks/{task}/results:export")
                case["archivedCsv"] = export(run.url, f"/app-checks/tasks/{task}/results:export?filter=all")
                if mode == "natural":
                    require(case["counts"] == case["countsBeforeFreeze"], "Completed Task counts changed")
                    for kind in ("Jsonl", "Csv"):
                        require(case["live" + kind]["sha256"] == case["archived" + kind]["sha256"], "Archive changed completed export content")
                view = request(run.url, f"/app-checks/tasks/{task}/results")
                require(len(view["results"]) <= 100, "Preview exceeded its bound")
                folder = run.task_archives / hashlib.sha256(prefix.encode()).hexdigest() / hashlib.sha256(task.encode()).hexdigest()
                manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
                successes, raw_digest = archived_successes(folder)
                require(case["archivedJsonl"]["sha256"] == raw_digest, "Raw export differs from frozen archive content")
                require(case["archivedCsv"]["lines"] == successes + 1, "CSV lost valid App results")
                require(request(run.url, "/projects/app-checks/tasks:stats", [task])[task] == case["counts"], "Frozen counts continued changing")
                case["archive"] = {key: manifest[key] for key in ("records", "bytes", "sha256")}
                case["previewCount"], case["truncated"] = len(view["results"]), view["truncated"]
                require(request(run.url, "/app-checks/tasks", body)["taskId"] == task, "Retained creation changed execution identity")
                try:
                    request(run.url, f"/tasks/{task}/results:load", ["not-observed"])
                    raise AssertionError("Archived point lookup did not reject")
                except urllib.error.HTTPError as error:
                    require(error.code == 400 and json.load(error)["code"] == 12014, "Wrong archived point-query error")
                save()
                print(json.dumps({key: case[key] for key in ("count", "mode", "archiveSeconds", "archive")}), flush=True)

            # Restart only Server with a shorter proof retention. Startup maintenance must purge
            # existing terminal Tasks using their original timestamps; no direct score mutation.
            stage = "restart-expiry"
            subprocess.run([str(jcmd), str(run.processes["server"].pid), "JFR.dump", "name=retention",
                            "filename=" + str(output / "private/retention.jfr")], check=True, capture_output=True)
            run.processes["server"].terminate()
            run.processes["server"].wait(timeout=15)
            env = dict(run.server_environment)
            env["SPRING_APPLICATION_JSON"] = json.dumps({"xa.mass.task-retention": {
                "enabled": True, "grace": "0ms", "retention": "1ms", "interval": "1h"}})
            preview.Preview.launch(run, "server", run.server_command, env)
            run.wait_for(lambda: preview.http(run.url, "/actuator/health"), 60, "restarted Server")
            run.wait_for(lambda: all(client.zscore(prefix + ":task:score", case["taskId"]) is None for case in report["cases"]),
                         60, "startup final cleanup")
            for case in report["cases"]:
                task = case["taskId"]
                require(snapshot(client, prefix, task)["bytes"] == 0, "Task resources survived purge")
                folder = run.task_archives / hashlib.sha256(prefix.encode()).hexdigest() / hashlib.sha256(task.encode()).hexdigest()
                require(not folder.exists(), "Archive survived purge")
                new_task = request(run.url, "/app-checks/tasks", case["creation"])["taskId"]
                require(new_task != task, "Purged request reused an old execution identity")
                case["newTaskId"] = new_task
            report["passed"] = True
    except Exception as failure:
        report["failureType"] = type(failure).__name__
        if isinstance(failure, AssertionError):
            report["failure"] = str(failure)
        (output / "failure.txt").write_text(str(failure), encoding="utf-8")
        raise
    finally:
        stop.set()
        if monitor is not None:
            monitor.join(timeout=5)
        if heap_process is not None and heap_process.poll() is None:
            heap_process.terminate()
            heap_process.wait(timeout=5)
        if heap_stream is not None:
            heap_stream.close()
            lines = (output / "heap-samples.txt").read_text(encoding="utf-8").splitlines()
            columns = lines[0].split() if lines else []
            heap = []
            for line in lines[1:]:
                values = line.split()
                if len(values) == len(columns) and all(name in columns for name in ("S0U", "S1U", "EU", "OU")):
                    heap.append(sum(float(values[columns.index(name)]) for name in ("S0U", "S1U", "EU", "OU")) * 1024)
            if heap:
                report["sampledHeapPeakBytes"] = max(heap)
        latencies = sorted(row["pingMillis"] for row in report["samples"])
        if latencies:
            report["redisPingMillis"] = {"median": statistics.median(latencies), "p95": latencies[int(.95 * (len(latencies) - 1))], "max": max(latencies)}
        report["resourcePeaks"] = run.peaks
        save()


if __name__ == "__main__":
    main()
