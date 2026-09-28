from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lane_trend as trend  # noqa: E402


def case_row(case, status="passed", p99=300.0, p50=200.0, success=1.0, completed=1000.0, turnaround=None, job="rate-1000"):
    row = dict(case=case, job=job, mode="saturation" if case.startswith("sat-") else "open", status=status,
               successfulCallLatencyMillis={"p99": p99, "p50": p50}, successWithinWait=success, completedPerSecond=completed)
    if turnaround is not None:
        row["perWorkerTurnaroundMillis"] = turnaround
    return row


def history_run(run_id, day, p99=300.0, speed=100_000.0, status="passed", ref="main"):
    merged = {"status": "passed", "jobs": [{"job": "rate-1000", "calibration": {"cpuParallelOpsPerSecond": speed}}],
              "cases": [case_row("task-any-1000", status=status, p99=p99 + d) for d in (-5, 0, 5)]}
    return trend.run_record(merged, {"runId": run_id, "ref": ref, "createdAt": f"2026-10-{day:02d}T19:00:00Z"})


class LaneTrendTest(unittest.TestCase):
    def test_record_aggregates_passed_repetitions_per_case(self):
        merged = {"status": "passed", "jobs": [{"job": "saturation", "calibration": {"cpuParallelOpsPerSecond": 9.0}}],
                  "cases": [case_row("task-any-2000", status="saturated"), case_row("task-any-2000", p99=320),
                            case_row("sat-task-any", turnaround=400.0, job="saturation"),
                            case_row("sat-task-any", turnaround=440.0, job="saturation")]}
        record = trend.run_record(merged, {"runId": "1", "ref": "main"})
        self.assertEqual(trend.LANE_CONFIG_VERSION, record["laneConfigVersion"])
        self.assertEqual({"saturated": 1, "passed": 1}, record["cases"]["task-any-2000"]["statuses"])
        self.assertEqual(1, record["cases"]["task-any-2000"]["metrics"]["p99LatencyMillis"]["n"])
        self.assertEqual(420.0, record["cases"]["sat-task-any"]["metrics"]["perWorkerTurnaroundMillis"]["median"])
        self.assertEqual({"saturation": {"cpuParallelOpsPerSecond": 9.0}}, record["calibration"])

    def test_trend_needs_seven_comparable_runs_from_the_same_ref(self):
        history = [history_run(str(i), i) for i in range(1, 7)] + [history_run("x", 7, ref="perf/branch")]
        result = trend.compare(history_run("now", 20), history)
        self.assertEqual("insufficient-history", result["status"])
        self.assertEqual(6, result["historyRuns"])

    def test_normalized_regression_is_suspect_but_host_speed_difference_is_not(self):
        history = [history_run(str(i), i, p99=300 + i) for i in range(1, 8)]
        # A 20% slower host with 20% higher latency normalizes back into the history range.
        slower = trend.compare(history_run("slow", 20, p99=364, speed=80_000.0), history)
        self.assertEqual("compared", slower["status"])
        self.assertTrue(slower["hosts"]["rate-1000"]["qualified"])
        self.assertEqual([], [f for f in slower["findings"] if f["verdict"] == "suspect"])
        regressed = trend.compare(history_run("regressed", 20, p99=420), history)
        suspects = [f for f in regressed["findings"] if f.get("metric") == "p99LatencyMillis"]
        self.assertEqual("suspect", suspects[0]["verdict"])
        faster = trend.compare(history_run("faster", 20, p99=200), history)
        self.assertEqual([], [f for f in faster["findings"] if f.get("metric") == "p99LatencyMillis"])

    def test_outlier_host_is_not_judged_and_status_changes_are_suspect(self):
        history = [history_run(str(i), i) for i in range(1, 8)]
        outlier = trend.compare(history_run("slow", 20, p99=900, speed=60_000.0), history)
        self.assertFalse(outlier["hosts"]["rate-1000"]["qualified"])
        self.assertEqual(["host-unqualified"], [f["verdict"] for f in outlier["findings"]])
        saturated = trend.compare(history_run("sat", 20, status="saturated"), history)
        self.assertIn({"case": "task-any-1000", "verdict": "suspect", "reason": "status", "current": "saturated",
                       "history": "passed"}, saturated["findings"])

    def test_band_is_provisional_until_three_runs_then_uses_median_spread(self):
        self.assertTrue(trend.band("task-any-1000", [], "main")["provisional"])
        history = [history_run(str(i), i) for i in range(1, 5)]
        value = trend.band("task-any-1000", history, "main")
        self.assertFalse(value["provisional"])
        # Each run spans 295..305 around 300 (3.3%), below the 5% floor of half the provisional band.
        self.assertEqual(0.05, value["relative"])
        self.assertEqual(4, value["runs"])

    def test_ab_stops_early_on_consistent_evidence(self):
        self.assertEqual("continue", trend.ab_decide("task-any-1000", [(300, 305)], .1)["decision"])
        self.assertEqual("no-difference", trend.ab_decide("task-any-1000", [(300, 305), (300, 290)], .1)["decision"])
        self.assertEqual("worse", trend.ab_decide("task-any-1000", [(300, 360), (300, 350)], .1)["decision"])
        self.assertEqual("better", trend.ab_decide("sat-task-any", [(400, 300), (410, 320)], .1)["decision"])
        mixed = [(300, 360), (300, 250), (300, 360), (300, 250), (300, 360)]
        self.assertEqual("inconclusive", trend.ab_decide("task-any-1000", mixed, .1)["decision"])
        self.assertEqual("continue", trend.ab_decide("task-any-1000", [(300, None), (300, 305)], .1)["decision"])


if __name__ == "__main__":
    unittest.main()
