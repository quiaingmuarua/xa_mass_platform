"""Finite app-checks proof through public APIs and the actual Simulator process."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import time
import uuid

SCENARIO = Path(__file__).resolve().parent
PREVIEW = SCENARIO.parents[1] / "distribution/server"


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
    return {"requestId": label, "name": label, "appId": app, "country": "CN",
            "numbers": [f"+8613800{index:06d}" for index in range(offset, offset + count)],
            "simulation": {"ranges": dict(zip(("registered", "unregistered", "failed"), ranges)), "delayMs": delay}}


def functional(run, http):
    catalog = http(run.url, "/api/v1/app-checks/catalog")
    require(catalog["projectId"] == "app-checks", "Wrong project")
    groups = {row["appId"]: row["workerGroupId"] for row in catalog["apps"]}
    require(groups == {"app-a": "app-a-sim", "app-b": "app-b-sim"}, "Wrong application assembly")
    workers = http(run.host, "/lab/v1/workers")["workers"]
    for group in groups.values():
        require(sum(row["workerGroupId"] == group and bool(row.get("workerId")) for row in workers) == 2,
                "App fixture must contain two prepared Workers per Group")
    cases = [
        request("registered", "app-a", 0, 6, ([0, 1000], [1000, 1000], [1000, 1000]), [0, 30]),
        request("unregistered", "app-a", 100, 6, ([0, 0], [0, 1000], [1000, 1000]), [10, 40]),
        request("failed", "app-b", 200, 2, ([0, 0], [0, 0], [0, 1000]), [0, 0]),
        request("mixed", "app-b", 300, 16, ([0, 500], [500, 900], [900, 1000]), [10, 50]),
    ]
    # Submit before observing closure: Tasks coexist within and across Groups.
    tasks = [(case, http(run.url, "/api/v1/app-checks/tasks", case)["taskId"]) for case in cases]
    require(len({task for _, task in tasks}) == len(cases), "Task identities collided")
    require(http(run.url, "/api/v1/app-checks/tasks", cases[0])["taskId"] == tasks[0][1], "Submission identity changed")
    summaries = []
    for case, task_id in tasks:
        detail = {}

        def observed():
            nonlocal detail
            detail = http(run.url, "/api/v1/app-checks/tasks/" + task_id)
            task = detail["task"]
            return task["totalCount"] == len(case["numbers"]) and task["activeCount"] == 0 \
                and task["state"] == "terminal" \
                and task["succeededCount"] + task["failedCount"] == len(case["numbers"]) \
                and len(detail["results"]) == len(case["numbers"])

        run.wait_for(observed, 45, "finite app-checks execution closure")
        task = detail["task"]
        require(task["appId"] == case["appId"] and task["simulation"] == case["simulation"], "Stored configuration changed")
        require(not detail["resultsTruncated"], "Small result preview is incomplete")
        require({row["number"] for row in detail["results"]} == set(case["numbers"]), "Item association mismatch")
        if case["requestId"] == "failed":
            require(task["failedCount"] == 2, "Full-failure Task unexpectedly succeeded")
        elif case["requestId"] != "mixed":
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
    listing = http(run.url, "/api/v1/app-checks/tasks?limit=100")
    require({task for _, task in tasks} <= {row["taskId"] for row in listing["tasks"]}, "Project list lost a Task")
    return {"passed": True, "appWorkers": 4, "tasks": summaries, "idempotentSubmission": True,
            "groupIsolation": True, "hashAndDelayRecomputed": True,
            "nonclaims": ["exact distribution", "fixed executor", "single execution", "failure attempt count"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--root", type=Path, default=PREVIEW)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--port", type=int, default=18620)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.build and root != PREVIEW.resolve():
        parser.error("--build requires the source Preview; omit it for an extracted ZIP")
    specification = importlib.util.spec_from_file_location("app_checks_preview", root / "run_preview.py")
    preview = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(preview)
    if args.build:
        preview.build()
    output = (args.output or SCENARIO / "build/acceptance" / time.strftime("%Y%m%d-%H%M%S")).resolve()
    inventory = output / "private" / ("inventory-" + uuid.uuid4().hex) / "data/scenario-workers"
    run = preview.Preview(1, args.port, root=root, output=output / "private", sandbox_root=inventory, app_count=2)
    result = {"passed": False}
    phase = "startup"
    try:
        with run:
            phase = "execution"
            result = functional(run, preview.http)
    except Exception as error:
        # No phone numbers, opaque Result content or HTTP body in public proof artifacts.
        result.update(passed=False, failedPhase=phase, failureType=type(error).__name__)
        print("App checks failed at " + phase + ": " + type(error).__name__, flush=True)
        raise
    finally:
        result["artifacts"] = run.artifacts
        result["launcherSha256"] = hashlib.sha256((root / "run_preview.py").read_bytes()).hexdigest()
        output.mkdir(parents=True, exist_ok=True)
        (output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
