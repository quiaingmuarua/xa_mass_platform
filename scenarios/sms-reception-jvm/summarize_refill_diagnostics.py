"""Export only aggregate Owner and sampled-stack evidence from a private recording."""
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    exported = args.recording.with_suffix(".json")
    with exported.open("w", encoding="utf-8") as stream:
        subprocess.run(["jfr", "print", "--json", "--events",
                        "xa.mass.TaskDispatch,xa.mass.CandidateRecycle,jdk.ExecutionSample,jdk.GarbageCollection,jdk.DataLoss",
                        str(args.recording)], stdout=stream, check=True)
    events = json.loads(exported.read_text(encoding="utf-8"))["recording"]["events"]
    stages, rows, counts, cpu = Counter(), Counter(), Counter(), Counter()
    hints, lost, gc = None, 0, 0
    for event in events:
        values = event["values"]
        if event["type"] == "xa.mass.TaskDispatch" and not values.get("key"):
            stage = values["stage"]; stages[stage] += 1; rows[stage] += values["batchSize"]; counts[stage] += values["count"]
        elif event["type"] == "xa.mass.CandidateRecycle":
            hints = {key: values[key] for key in ("pending", "peak", "accepted", "dropped", "attempted", "recycled", "stale", "failed", "retired")}
        elif event["type"] == "jdk.ExecutionSample":
            frames = (values.get("stackTrace") or {}).get("frames", [])
            if frames:
                method = frames[0]["method"]
                cpu[method["type"]["name"].replace("/", ".") + "." + method["name"]] += 1
        elif event["type"] == "jdk.DataLoss": lost += 1
        elif event["type"] == "jdk.GarbageCollection": gc += 1
    result = {"ownerStageCalls": dict(stages), "ownerRequestedRows": dict(rows), "ownerStageCounts": dict(counts), "hints": hints,
              "scoreRefillRedisCommands": {"ZRANGEBYSCORE": stages["REFILL_OBSERVATION"] + stages["RECYCLE_OBSERVATION"],
                                           "EVAL": stages["CANDIDATEIZE"] + stages["CANDIDATE_RECYCLE"]},
              "commandScope": "Successful Pacer Score supply/recycle operations: one range read or exact EVAL per recorded stage; excludes Matching and other Owners.",
              "executionSampleCount": sum(cpu.values()), "topSampledFrames": cpu.most_common(15),
              "gcEvents": gc, "dataLossEvents": lost}
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__": main()
