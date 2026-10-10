"""Evaluate the predeclared full-candidate experiment, without changing runtime state."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import median


def improvement(baseline, candidate):
    return (baseline - candidate) / baseline if baseline else (0 if not candidate else -1)


def metrics(case):
    result = {"notObservedRate": case["initialResponses"]["notObservedRate"],
              "acquisitionP95": case["requestLatencyMillis"]["p95"],
              "acquisitionP99": case["requestLatencyMillis"]["p99"],
              "queryP95": case["queryLatencyMillis"]["p95"],
              "queryP99": case["queryLatencyMillis"]["p99"],
              "checkedCoordinates": case["metrics"]["projection"]["matching"]["sms-reception"]["checkedCoordinates"]}
    for process in ("server", "host"):
        for key in ("cpuSeconds", "rssBytes", "threads"):
            result[process + "." + key] = case["resourcePeaks"][process][key]
    return result


def evaluate(cases):
    pairs = [{"pair": n, "A": metrics(cases[f"{n}-A"]), "B": metrics(cases[f"{n}-B"])} for n in range(1, 4)]
    benefit = {}
    for key, threshold in (("notObservedRate", .30), ("acquisitionP95", .20)):
        changes = [improvement(pair["A"][key], pair["B"][key]) for pair in pairs]
        benefit[key] = {"pairImprovements": changes, "medianImprovement": median(changes),
                        "threshold": threshold, "passed": sum(change >= threshold for change in changes) >= 2
                        and median(changes) >= threshold}
    costs = {}
    limits = {"server.cpuSeconds": .20, "host.cpuSeconds": .20, "server.rssBytes": .20,
              "host.rssBytes": .20, "checkedCoordinates": .50, "acquisitionP99": .10}
    for key, limit in limits.items():
        a, b = median(pair["A"][key] for pair in pairs), median(pair["B"][key] for pair in pairs)
        growth = -improvement(a, b)
        costs[key] = {"baselineMedian": a, "candidateMedian": b, "growth": growth,
                      "maximumGrowth": limit, "passed": growth <= limit}
    valid = all(case["passed"] and case["established"] == 5400 and not case["httpErrors"]
                and case["generatorRejected"] == 0 and case["comparison"]["falseSuccesses"] == 0
                and case["initialResponses"]["total"] == 5400
                and len(case["clientLatencySamplesMillis"]["acquisition"]) == 5400
                and len(case["clientLatencySamplesMillis"]["query"]) == 5400 for case in cases.values())
    common = {json.dumps({"host": case["artifacts"]["hostClasspath"], "frontend": case["artifacts"]["frontendSha256"],
                         "launcher": case["launcherSha256"]}, sort_keys=True) for case in cases.values()}
    pins = {variant: sorted({cases[f"{n}-{variant}"]["artifacts"]["server"]["sha256"] for n in range(1, 4)})
            for variant in ("A", "B")}
    artifact_valid = len(common) == 1 and all(len(values) == 1 for values in pins.values()) and pins["A"] != pins["B"]
    keep = valid and artifact_valid and any(row["passed"] for row in benefit.values()) and all(row["passed"] for row in costs.values())
    return {"performanceDecision": "retain" if keep else "withdraw", "validFormalRuns": valid,
            "sameSupportingArtifacts": artifact_valid, "serverPins": pins, "pairs": pairs,
            "benefit": benefit, "cost": costs,
            "scope": "Performance gate only; retaining also requires the named functional and regression proofs."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    cases = {f"{n}-{variant}": json.loads((args.root / "runs" / f"{n}-{variant}" / "summary.json").read_text(encoding="utf-8"))
             for n in range(1, 4) for variant in ("A", "B")}
    result = evaluate(cases)
    (args.root / "comparison.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
