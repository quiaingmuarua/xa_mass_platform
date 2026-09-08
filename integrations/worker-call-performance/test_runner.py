from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("call_performance", Path(__file__).with_name("run_worker_call_performance.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def complete_runs():
    return [{"pair": pair, "version": version, "case": case, "status": "passed",
             "generatorLimited": False, "successRate": .9,
             "successfulCallLatencyMillis": {"p99": 100, "samples": 1000}}
            for pair in range(3) for version in ("A", "B") for case in runner.CASES]


class RunnerTest(unittest.TestCase):
    def test_compare_requires_two_pairs_and_keeps_inconclusive_distinct(self):
        runs = complete_runs()
        self.assertEqual("no_detected_regression", runner.comparison(runs)["status"])
        changes = [r for r in runs if r["version"] == "B" and r["case"] == "any-100"]
        changes[0]["successRate"] = .8
        self.assertEqual("no_detected_regression", runner.comparison(runs)["status"])
        changes[1]["successRate"] = .8
        self.assertEqual("regressed", runner.comparison(runs)["status"])
        changes[1]["generatorLimited"] = True
        self.assertEqual("inconclusive", runner.comparison(runs)["status"])

    def test_latency_comparison_does_not_penalize_a_large_success_improvement(self):
        runs = complete_runs()
        for row in runs:
            if row["version"] == "B":
                row["successfulCallLatencyMillis"]["p99"] = 121
        self.assertEqual("regressed", runner.comparison(runs)["status"])
        for row in runs:
            if row["version"] == "B":
                row["successRate"] = .99
        self.assertEqual("no_detected_regression", runner.comparison(runs)["status"])
        self.assertEqual("inconclusive", runner.comparison([])["status"])

    def test_missing_success_latencies_are_inconclusive_unless_completion_clearly_regressed(self):
        runs = complete_runs()
        for row in runs:
            row["successRate"] = 0
            row["successfulCallLatencyMillis"] = {"samples": 0, "p99": 0}
        self.assertEqual("inconclusive", runner.comparison(runs)["status"])
        for row in runs:
            if row["version"] == "A":
                row["successRate"] = .9
                row["successfulCallLatencyMillis"] = {"samples": 900, "p99": 100}
        self.assertEqual("regressed", runner.comparison(runs)["status"])

    def test_proc_parsing_handles_spaces_in_process_name(self):
        stat = "5 (java worker thread) " + " ".join(["S"] + ["0"] * 10 + ["250", "150"] + ["0"] * 10)
        row = runner.parse_proc("Threads:\t8\nVmRSS:\t1024 kB\n", stat, 19, 100)
        self.assertEqual(4, row["cpuSeconds"])
        self.assertEqual(1048576, row["rssBytes"])
        self.assertEqual(8, row["nativeThreads"])

    def test_output_cannot_escape_build_or_overwrite(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runner, "ROOT", Path(directory)):
            with self.assertRaises(ValueError):
                runner.fresh_output(Path(directory) / "outside")
            path = runner.fresh_output(Path(directory) / "build" / "case")
            with self.assertRaises(ValueError):
                runner.fresh_output(path)
            with self.assertRaises(ValueError):
                runner.fresh_output(path / ".." / ".." / "outside")

    def test_cleanup_kills_only_the_started_process_group_after_bounded_wait(self):
        process = Mock(pid=123)
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired("java", 10), 0]
        with patch.object(runner.os, "killpg", create=True) as kill, patch.object(runner.signal, "SIGKILL", 9, create=True):
            runner.stop_process(process)
        self.assertEqual([123, 123], [call.args[0] for call in kill.call_args_list])
        self.assertEqual([10, 5], [call.kwargs["timeout"] for call in process.wait.call_args_list])

    def test_sampler_records_resource_breach_as_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            sampler = runner.Sampler(Path(directory) / "resources.jsonl", Mock())
            process = Mock(pid=10)
            process.poll.return_value = None
            sampler.register("server", process)
            with patch.object(runner, "process_sample", return_value={"nativeThreads": 513,
                     "openFileDescriptors": 1, "rssBytes": 100, "cpuSeconds": 1}):
                sampler.run()
            self.assertIn("Resource ceiling", sampler.failure)
            self.assertEqual(1, sampler.counts["server"])

    def test_resource_summary_excludes_startup_and_keeps_redis_commands_aggregate(self):
        rows = []
        for timestamp, cpu, memory in ((0, 0, 99999), (1000, 2, 100), (3000, 3, 150), (10000, 10, 99999)):
            for role in ("server", "host", "harness"):
                rows.append(dict(role=role, epochMillis=timestamp, cpuSeconds=cpu, rssBytes=memory,
                                 nativeThreads=20, openFileDescriptors=40))
            rows.append(dict(role="redis", epochMillis=timestamp, cpuUserSeconds=cpu, cpuSystemSeconds=0,
                             usedMemory=memory, usedMemoryRss=memory,
                             commandStats={"cmdstat_hset": {"calls": timestamp}}))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in rows))
            result = runner.resource_summary(path, 1000, 3)
            self.assertEqual(.5, result["server"]["meanCpuCores"])
            self.assertEqual(150, result["server"]["peakRssBytes"])
            self.assertEqual(2000, result["redis"]["aggregateCommandCallDeltas"]["cmdstat_hset"])
            with self.assertRaises(RuntimeError):
                runner.resource_summary(path, 20000)

    def test_failed_bootstrap_summary_does_not_invent_measurements(self):
        value = runner.markdown_summary(dict(status="failed", referenceHost=True, completeSuite=True,
                runs=[dict(pair=0, version="A", case="any-100", status="failed")]))
        self.assertIn("failed", value)
        self.assertNotIn("100.00%", value)


if __name__ == "__main__":
    unittest.main()
