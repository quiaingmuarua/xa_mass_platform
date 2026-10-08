"""Opt-in, isolated A/B workload; observation fixture never enters a production artifact."""
from __future__ import annotations

import hashlib
import json
import os
import platform
from pathlib import Path
import statistics
import subprocess
import time
import uuid
import zipfile


def compile_observer(root, output):
    source_layout = (root / "build.gradle").is_file()
    jars = list((root / "build/preview/server" if source_layout else root / "lib").glob("xa-mass-server-jvm-*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Benchmark requires exactly one staged Server JAR")
    extracted = (output / "private/observer-runtime").resolve()
    extracted.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jars[0]) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/")):
                continue
            target = (extracted / entry.filename).resolve()
            if not target.is_relative_to(extracted):
                raise ValueError("Invalid artifact path")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    observer = extracted / "observer"
    observer.mkdir(exist_ok=True)
    dependencies = [extracted / "BOOT-INF/classes", *sorted((extracted / "BOOT-INF/lib").glob("*.jar"))]
    source = Path(__file__).parent / "benchmark/BenchmarkServer.java"
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, dependencies)),
                    "-d", str(observer), str(source)], check=True)
    return os.pathsep.join(map(str, [observer, *dependencies])), hashlib.sha256(source.read_bytes()).hexdigest()


def benchmark(preview, root, output, port, baseline, configure, create_import_approve, request, require):
    classpath, observer_hash = compile_observer(root, output)

    class ObservedPreview(preview.Preview):
        def launch(self, name, args, env):
            if name == "server":
                jar = args.index("-jar")
                args = [*args[:jar], "-cp", classpath,
                        "com.xa.mass.scenario.appchecks.benchmark.BenchmarkServer", *args[jar + 2:]]
            super().launch(name, args, env)

    cases = []
    result = {"passed": False, "mode": "benchmark", "path": "any-query" if baseline else "window-pool",
              "baselineRef": "217393902" if baseline else None, "observerSha256": observer_hash,
              "workers": 20, "items": 1360, "windowMillis": 60000, "maxAssignments": 1000,
              "simulatedDelayMillis": 0, "repetitions": cases}
    result["environment"] = {"platform": platform.platform(), "logicalProcessors": os.cpu_count(),
                             "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip(),
                             "serverActiveProcessors": 8, "serverHeap": "2g", "hostHeap": "1g"}
    output.mkdir(parents=True, exist_ok=True)
    try:
        for repetition in range(1, 4):
            folder = output / ("run-" + str(repetition))
            inventory = folder / "private" / ("inventory-" + uuid.uuid4().hex) / "data/scenario-workers"
            run = ObservedPreview(1, port, root=root, output=folder / "private", sandbox_root=inventory, app_count=20)
            with configure(baseline=baseline, window_millis=60000), run:
                workers = preview.http(run.host, "/lab/v1/workers")["workers"]
                ids = {worker["workerId"] for worker in workers if worker["workerGroupId"] == "app-a-sim"}
                require(len(ids) == 20, "Benchmark requires 20 prepared App A Workers")

                def ready():
                    sample = preview.http(run.url, "/api/v1/runtime-view/worker-groups/app-a-sim/workers:preview", 100)
                    return ids <= {row["workerId"] for row in sample["workers"] if row["workerProperties"].get("application") == "app-a"}
                run.wait_for(ready, 15, "benchmark Facts publication")
                case = request("benchmark", "app-a", 0, 1360, ([0, 1000], [1000, 1000], [1000, 1000]), [0, 0])
                task_id = create_import_approve(run, preview.http, case, approve=False)
                preview.http(run.url, "/__benchmark/app-checks/start", {})
                started = time.perf_counter()
                preview.http(run.url, f"/api/v1/app-checks/tasks/{task_id}/approve", 1360)
                detail = {}

                def completed():
                    nonlocal detail
                    detail = preview.http(run.url, "/api/v1/app-checks/tasks/" + task_id)
                    task = detail["task"]
                    return task["state"] == "terminal" and task["activeCount"] == 0
                run.wait_for(completed, 180, "benchmark completion")
                elapsed = time.perf_counter() - started
                measurements = preview.http(run.url, "/__benchmark/app-checks/stop", {})
                task = detail["task"]
                require(task["succeededCount"] == 1360 and task["failedCount"] == 0, "Benchmark outcomes differ")
                require(measurements["outside"]["factsReads"] == 0, "Unattributed Facts reads")
                expected = "take" if baseline else "refill"
                other = "refill" if baseline else "take"
                require(measurements[expected]["factsReads"] > 0 and measurements[other]["factsReads"] == 0,
                        "Facts reads did not occur exclusively in the expected phase")
                require(all(value["failures"] == 0 for value in measurements.values()), "Matching call failed")
                cases.append({"repetition": repetition, "completionSeconds": elapsed,
                              "itemsPerSecond": 1360 / elapsed, "stages": measurements, "artifacts": run.artifacts})
                print(f"Benchmark {result['path']} {repetition}/3: {elapsed:.3f}s, Facts reads={measurements[expected]['factsReads']}", flush=True)
        result["passed"] = True
        result["medianCompletionSeconds"] = statistics.median(case["completionSeconds"] for case in cases)
        result["nonclaims"] = ["strict window quota", "production throughput", "uninstrumented latency", "immediate window recovery"]
    finally:
        (output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result
