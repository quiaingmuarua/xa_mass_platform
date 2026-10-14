"""Finite app-checks proof through public APIs and the actual Simulator process."""
from __future__ import annotations

import base64
import functools
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import time
import uuid
import urllib.request
import tempfile

SCENARIO = Path(__file__).resolve().parent
PREVIEW = SCENARIO.parents[1] / "distribution/server"


@contextmanager
def regression_window_configuration(*, baseline=False, window_millis=None, parameters_by_app=None):
    """Keep the content/closure workload finite; the Boot window proof owns low-threshold recovery."""
    if not baseline:
        previous_seed = os.environ.get("XA_MASS_APP_CHECKS_SEED")
        with tempfile.TemporaryDirectory(prefix="app-checks-config-") as directory:
            configuration = json.loads((SCENARIO / "src/main/resources/app-checks/preview-configuration.json").read_text(encoding="utf-8"))
            for limit in configuration["poolGroups"].values():
                limit["maxCount"] = 1000
                if window_millis is not None:
                    limit["windowMillis"] = window_millis
            for app_id, app in configuration["applications"].items():
                if parameters_by_app and app_id in parameters_by_app:
                    app["parameters"] = parameters_by_app[app_id]
            seed = Path(directory) / "seed.json"
            seed.write_text(json.dumps(configuration), encoding="utf-8")
            os.environ["XA_MASS_APP_CHECKS_SEED"] = seed.resolve().as_uri()
            try:
                yield
            finally:
                if previous_seed is None:
                    os.environ.pop("XA_MASS_APP_CHECKS_SEED", None)
                else:
                    os.environ["XA_MASS_APP_CHECKS_SEED"] = previous_seed
        return
    previous = os.environ.get("SPRING_APPLICATION_JSON")
    configuration = json.loads(previous) if previous else {}
    for group in ("app-a-sim", "app-b-sim"):
        # Only the explicit historical fixture uses the pre-Pool baseline's binding.
        prefix = f"xa.mass.worker-matching.groups.{group}.assignment-window"
        configuration[f"{prefix}.max-assignments"] = 1000
        if window_millis is not None:
            configuration[f"{prefix}.window-millis"] = window_millis
    os.environ["SPRING_APPLICATION_JSON"] = json.dumps(configuration)
    try:
        yield
    finally:
        if previous is None:
            os.environ.pop("SPRING_APPLICATION_JSON", None)
        else:
            os.environ["SPRING_APPLICATION_JSON"] = previous


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def hash_value(domain, worker_id, salt, number):
    digest = hashlib.sha256()
    for field in ("app-checks/v1/" + domain, worker_id, salt, number):
        encoded = field.encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
    return int.from_bytes(digest.digest()[:8], "big")


def request(label, app, offset, count, ranges, delay):
    return {"userId": "1000", "requestId": label, "appId": app, "country": "CN",
            "numbers": [f"+8613800{index:06d}" for index in range(offset, offset + count)],
            "simulation": {"ranges": dict(zip(("registered", "unregistered", "failed"), ranges)), "delayMs": delay}}


def caller_headers(user, role):
    return {"X-XA-Dev-User": base64.urlsafe_b64encode(user.encode("utf-8")).decode("ascii").rstrip("="),
            "X-XA-Dev-Role": role}


def creation_input(case):
    return {key: case[key] for key in ("requestId", "appId", "country")}


def create_import_approve(run, http, case, *, approve=True):
    headers = caller_headers(case["userId"], "admin")
    api = functools.partial(http, headers=headers)
    task_id = api(run.url, "/api/v1/app-checks/tasks", creation_input(case))["taskId"]
    route = f"/api/v1/app-checks/tasks/{task_id}"
    require(api(run.url, "/api/v1/projects/app-checks/tasks:stats", [task_id])[task_id]["total"] == 0, "Creation imported work implicitly")
    request = urllib.request.Request(run.url + route + "/numbers:import",
                                     data="\n".join(case["numbers"]).encode("utf-8"),
                                     headers={"Content-Type": "text/plain;charset=UTF-8", **headers}, method="POST")
    with urllib.request.urlopen(request, timeout=15) as response:
        receipt = json.load(response)
    require(receipt["addedCount"] == len(case["numbers"]), "Import did not confirm every input")
    if approve:
        api(run.url, f"/api/v1/tasks/{task_id}/approve", {"expectedItemCount": len(case["numbers"])})
    return task_id


def task_observations(run, api, task_id):
    """The workload composes independent descriptor/count/business-content observations."""
    entry = api(run.url, "/api/v1/projects/app-checks/tasks/" + task_id)
    counts = api(run.url, "/api/v1/projects/app-checks/tasks:stats", [task_id])[task_id]
    results = api(run.url, "/api/v1/app-checks/tasks/" + task_id + "/results")
    require(set(results) == {"taskId", "results", "truncated"} and results["taskId"] == task_id, "Business view shape changed")
    metadata = entry["task"]["metadata"]
    task = {**metadata, "taskId": task_id, "workerGroupId": entry["task"]["workerGroupId"],
            "state": entry["scoreBand"], "totalCount": counts["total"], "activeCount": counts["countsByTag"]["1"],
            "failedCount": counts["countsByTag"]["5"],
            "succeededCount": sum(counts["countsByTag"][str(tag)] for tag in range(6, 10)),
            "simulation": json.loads(metadata["contractParameters"])}
    return {"task": task, "results": results["results"], "resultsTruncated": results["truncated"]}


def functional(run, http, cases):
    api = functools.partial(http, headers=caller_headers("1000", "admin"))
    catalog = http(run.url, "/api/v1/app-checks/catalog")
    require(catalog["projectId"] == "app-checks", "Wrong project")
    groups = {row["appId"]: row["workerGroupId"] for row in catalog["apps"]}
    require(groups == {"app-a": "app-a-sim", "app-b": "app-b-sim"}, "Wrong application assembly")
    workers = http(run.host, "/lab/v1/workers")["workers"]
    for app, group in groups.items():
        expected = {row["workerId"] for row in workers if row["workerGroupId"] == group and row.get("workerId")}
        require(len(expected) == 2,
                "App fixture must contain two prepared Workers per Group")
        # Observe only these two known fixture identities; Prepare/route evidence does not establish Facts.
        def facts_observed():
            sample = http(run.url, f"/api/v1/runtime-view/worker-groups/{group}/workers:preview", 100)
            return expected <= {row["workerId"] for row in sample["workers"]
                                if row["workerProperties"].get("application") == app}

        run.wait_for(facts_observed, 15, "App Worker Properties publication")
    # Submit before observing closure: Tasks coexist within and across Groups.
    tasks = [(case, create_import_approve(run, http, case)) for case in cases]
    require(len({task for _, task in tasks}) == len(cases), "Task identities collided")
    require(api(run.url, "/api/v1/app-checks/tasks", creation_input(cases[0]))["taskId"] == tasks[0][1], "Submission identity changed")
    summaries = []
    for case, task_id in tasks:
        detail = {}

        def observed():
            nonlocal detail
            detail = task_observations(run, api, task_id)
            task = detail["task"]
            return task["totalCount"] == len(case["numbers"]) and task["activeCount"] == 0 \
                and task["state"] == "terminal" \
                and task["succeededCount"] + task["failedCount"] == len(case["numbers"]) \
                and len(detail["results"]) == len(case["numbers"])

        run.wait_for(observed, 45, "finite app-checks execution closure")
        task = detail["task"]
        require(task["userId"] == case["userId"], "Task creator changed")
        require(task["appId"] == case["appId"] and task["simulation"] == case["simulation"], "Stored configuration changed")
        require(not detail["resultsTruncated"], "Small result preview is incomplete")
        require({row["number"] for row in detail["results"]} == set(case["numbers"]), "Item association mismatch")
        failure_range = case["simulation"]["ranges"]["failed"]
        if failure_range == [0, 1000]:
            require(task["failedCount"] == len(case["numbers"]), "Full-failure Task unexpectedly succeeded")
        elif failure_range[0] == failure_range[1]:
            require(task["succeededCount"] == len(case["numbers"]), "Success-only Task did not succeed")
        recomputed = 0
        for row in detail["results"]:
            if row["resultStatus"] == "failed":
                require("registered" not in row, "Execution failure fabricated a business answer")
                continue
            require(row["resultStatus"] == "succeeded" and "contentError" not in row, "Unusable success Result")
            require(row["workerGroupId"] == groups[case["appId"]], "Executor Group mismatch")
            require(any(worker["workerId"] == row["workerId"] and worker["workerGroupId"] == row["workerGroupId"]
                        for worker in workers), "Result identity is not an actual fixture Worker")
            bucket = hash_value("outcome", row["workerId"], task["salt"], row["number"]) % 1000
            outcome = next(name for name, (low, high) in case["simulation"]["ranges"].items() if low <= bucket < high)
            require(outcome != "failed" and row["registered"] == (outcome == "registered"), "Result hash mismatch")
            low, high = case["simulation"]["delayMs"]
            delay = low + hash_value("delay", row["workerId"], task["salt"], row["number"]) % (high - low + 1)
            require(row["simulatedDelayMillis"] == delay, "Delay hash mismatch")
            recomputed += 1
        summaries.append({"case": case["requestId"], "total": task["totalCount"], "succeeded": task["succeededCount"],
                          "failed": task["failedCount"], "recomputed": recomputed})
    listing = api(run.url, "/api/v1/projects/app-checks/tasks?limit=100")
    require({task for _, task in tasks} <= {row["taskId"] for row in listing["tasks"]}, "Project list lost a Task")
    require(all(row["task"]["metadata"].get("userId") == "1000" for row in listing["tasks"]
                if row["taskId"] in {task for _, task in tasks}), "Project list lost Task creators")
    return {"passed": True, "appWorkers": 4, "tasks": summaries, "idempotentSubmission": True,
            "groupIsolation": True, "hashAndDelayRecomputed": True, "creatorRetained": True,
            "nonclaims": ["exact distribution", "fixed executor", "single execution", "failure attempt count"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--root", type=Path, default=PREVIEW)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--port", type=int, default=18620, help="First functional fixture base port; second uses +10, each uses Adapter +3 and Host +4")
    parser.add_argument("--benchmark", action="store_true", help="Run three isolated 20-Worker/1360-number measurements")
    parser.add_argument("--baseline", action="store_true", help="Benchmark only: use the pinned pre-migration configuration")
    args = parser.parse_args()
    if args.baseline and not args.benchmark:
        parser.error("--baseline requires --benchmark")
    if not args.benchmark and not 1 <= args.port <= 65521:
        parser.error("--port must be in 1..65521 for the two independent functional fixture port sets")
    root = args.root.resolve()
    if args.build and root != PREVIEW.resolve():
        parser.error("--build requires the source Preview; omit it for an extracted ZIP")
    specification = importlib.util.spec_from_file_location("app_checks_preview", root / "run_preview.py")
    preview = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(preview)
    if args.build:
        preview.build()
    output = (args.output or SCENARIO / "build/acceptance" / time.strftime("%Y%m%d-%H%M%S")).resolve()
    if args.benchmark:
        from pool_benchmark import benchmark
        result = benchmark(preview, root, output, args.port, args.baseline, regression_window_configuration,
                           create_import_approve, request, require)
        print(json.dumps(result, indent=2), flush=True)
        return
    # Each startup fixture fixes execution parameters; requests never override them.
    fixtures = [
        [request("registered", "app-a", 0, 6, ([0, 1000], [1000, 1000], [1000, 1000]), [0, 30]),
         request("failed", "app-b", 200, 2, ([0, 0], [0, 0], [0, 1000]), [0, 0])],
        [request("unregistered", "app-a", 100, 6, ([0, 0], [0, 1000], [1000, 1000]), [10, 40]),
         request("mixed", "app-b", 300, 16, ([0, 500], [500, 900], [900, 1000]), [10, 50])]
    ]
    # Configuration is shared per app, but independent Tasks must still coexist in each Group.
    for cases in fixtures:
        peers = []
        for case in cases:
            peer = json.loads(json.dumps(case))
            peer["requestId"] += "-peer"
            peer["numbers"] = ["+8613900" + number[-6:] for number in case["numbers"]]
            peers.append(peer)
        cases.extend(peers)
    result = {"passed": False, "fixtures": []}
    try:
        for index, cases in enumerate(fixtures):
            folder = output / ("fixture-" + str(index + 1))
            inventory = folder / "private" / ("inventory-" + uuid.uuid4().hex) / "data/scenario-workers"
            # A closed JVM can leave TCP connections behind. Each startup fixture owns
            # separate ports; keep the launcher's strict occupancy check unchanged.
            fixture_port = args.port + index * 10
            run = preview.Preview(1, fixture_port, root=root, output=folder / "private", sandbox_root=inventory, app_count=2)
            with regression_window_configuration(parameters_by_app={case["appId"]: case["simulation"] for case in cases}), run:
                evidence = functional(run, preview.http, cases)
                evidence["windowThresholdOverride"] = 1000
                evidence["basePort"] = fixture_port
            evidence["artifacts"] = run.artifacts
            result["fixtures"].append(evidence)
        result["passed"] = True
    except Exception as error:
        result.update(failureType=type(error).__name__)
        raise
    finally:
        result["launcherSha256"] = hashlib.sha256((root / "run_preview.py").read_bytes()).hexdigest()
        output.mkdir(parents=True, exist_ok=True)
        (output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
