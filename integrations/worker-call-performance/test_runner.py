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


def diagnosis_runs():
    return [dict(pair=pair, version=version, case=case, status="passed", generatorLimited=False,
                 windows={name: dict(generatorLimited=False, successRate=.99,
                     successfulCallLatencyMillis={"p99": 100, "samples": 1000}, serverCpuSecondsPerHttpResponse=.001)
                     for name in ("surge", "sustained")})
            for pair in range(3) for version in ("A", "B") for case in runner.DIAGNOSIS_CASES]


class RunnerTest(unittest.TestCase):
    def test_current_build_and_immutable_baseline_use_their_own_server_entrypoints(self):
        self.assertEqual("distribution/server", runner.server_distribution(runner.ROOT))
        main = "src/main/java/com/xa/mass/server/XaMassServerApplication.java"
        with tempfile.TemporaryDirectory() as directory:
            baseline = Path(directory)
            source = baseline / "server_jvm" / main
            source.parent.mkdir(parents=True)
            source.touch()
            self.assertEqual("server_jvm", runner.server_distribution(baseline))
            with patch.object(runner.subprocess, "run") as launch:
                runner.build(baseline)
                self.assertIn(":server_jvm:bootJar", launch.call_args.args[0])
            with patch.object(runner, "ROOT", baseline):
                with self.assertRaisesRegex(RuntimeError, "entrypoint is missing"):
                    runner.server_distribution(baseline)
            current_source = baseline / "distribution/server" / main
            current_source.parent.mkdir(parents=True)
            current_source.touch()
            with patch.object(runner.subprocess, "run") as launch:
                runner.build(baseline, harness=True)
                self.assertIn(":distribution:server:bootJar", launch.call_args.args[0])
                self.assertIn(":integrations:worker-call-performance:installDist", launch.call_args.args[0])

    def test_rpc_rotates_only_paths_and_retains_the_exact_seven_case_manifest(self):
        expected = ("direct-step", "rpc-targeted", "rpc-any")
        for repetition in range(3):
            cases = runner.repetition_cases(repetition)
            self.assertEqual("rpc-any-500", cases[0])
            self.assertCountEqual(runner.RPC_CASES, cases)
            order = expected[repetition:] + expected[:repetition]
            self.assertEqual(tuple(f"{p}-1000" for p in order), cases[1:4])
            self.assertEqual(tuple(f"{p}-2000" for p in order), cases[4:7])
        self.assertEqual(runner.RPC_CASES + ("mixed-500",), runner.SUITES["nightly"])
        self.assertEqual(8, len(set(runner.NIGHTLY_CASES)))

    def test_rpc_formal_repetitions_cannot_be_candidate_or_diagnostic_comparisons(self):
        runner.validate_repetitions("rpc-diagnosis", 3, None, "off", False)
        runner.validate_repetitions("rpc-diagnosis", 1, None, "jfr", False)
        for args in (("rpc-diagnosis", 3, None, "jfr", False), ("nightly", 3, None, "off", False),
                     ("task", 3, None, "off", False), ("rpc-diagnosis", 1, "baseline", "off", False),
                     ("nightly", 1, None, "jfr", False)):
            with self.assertRaises(ValueError): runner.validate_repetitions(*args)

    def test_nightly_manifest_rejects_missing_duplicate_or_failed_cases(self):
        runs = [dict(pair=0, case=case, status="passed") for case in runner.NIGHTLY_CASES]
        runner.require_manifest(runs, runner.NIGHTLY_CASES, 1)
        for changed in (runs[:-1], runs + [runs[-1]], [dict(pair=0, case="mixed-500", status="passed")]):
            with self.assertRaisesRegex(RuntimeError, "Incomplete or duplicate"):
                runner.require_manifest(changed, runner.NIGHTLY_CASES, 1)
        failed = runs[:-1] + [dict(runs[-1], status="failed", acceptedResultsAfterDrain={"not_observed": 3})]
        with self.assertRaisesRegex(RuntimeError, "validation failed: repetition 1: mixed-500"):
            runner.require_manifest(failed, runner.NIGHTLY_CASES, 1)
        summary = runner.markdown_summary(dict(suite="nightly", status="failed", referenceHost=True,
                                               completeSuite=True, runs=failed))
        self.assertIn("3 accepted Items remain unobserved", summary)
        self.assertIn("Full suite selected: True", summary)

    def test_jfr_pair_is_explicit_and_cannot_enter_the_formal_three_pair_schedule(self):
        self.assertEqual(("comparison", runner.ORDER), runner.execution_mode("immutable", "off", False))
        self.assertEqual(("diagnostic_pair", (("A", "B"),)), runner.execution_mode("immutable", "jfr", True))
        self.assertEqual(("measurement", (("B",),)), runner.execution_mode(None, "off", False))
        for args in (("immutable", "jfr", False), (None, "jfr", True), ("immutable", "off", True)):
            with self.assertRaises(ValueError):
                runner.execution_mode(*args)

    def test_diagnosis_needs_three_valid_pairs_and_two_repeated_benefits(self):
        runs = diagnosis_runs()
        self.assertEqual("no_clear_benefit", runner.diagnosis_comparison(runs)["status"])
        candidates = [r for r in runs if r["version"] == "B" and r["case"] == "direct-step-2000"]
        candidates[0]["windows"]["sustained"]["successfulCallLatencyMillis"]["p99"] = 85
        self.assertEqual("no_clear_benefit", runner.diagnosis_comparison(runs)["status"])
        candidates[1]["windows"]["sustained"]["serverCpuSecondsPerHttpResponse"] = .00085
        self.assertEqual("eligible_candidate", runner.diagnosis_comparison(runs)["status"])
        candidates[2]["windows"]["sustained"]["generatorLimited"] = True
        self.assertEqual("inconclusive", runner.diagnosis_comparison(runs)["status"])

    def test_a_sustained_benefit_cannot_hide_surge_regression_or_a_limited_guard(self):
        runs = diagnosis_runs()
        for row in runs:
            row["generatorLimited"] = True  # Whole-case flag never overwrites a fixed window's evidence.
            if row["version"] == "B":
                row["windows"]["sustained"]["successfulCallLatencyMillis"]["p99"] = 70
        self.assertEqual("eligible_candidate", runner.diagnosis_comparison(runs)["status"])
        for row in runs:
            if row["version"] == "A":
                row["windows"]["surge"]["generatorLimited"] = True
        value = runner.diagnosis_comparison(runs)
        self.assertEqual("inconclusive", value["status"])
        self.assertTrue(any(w["status"] == "improved" for w in value["windows"]))
        for row in runs:
            row["windows"]["surge"]["generatorLimited"] = False
            if row["version"] == "B":
                row["windows"]["surge"]["successRate"] = .93
        self.assertEqual("regressed", runner.diagnosis_comparison(runs)["status"])

    def test_diagnosis_keeps_success_change_and_latency_denominators_separate(self):
        runs = diagnosis_runs()
        for row in runs:
            for window in row["windows"].values():
                window["successRate"] = .90 if row["version"] == "A" else .95
                window["successfulCallLatencyMillis"]["p99"] = 100 if row["version"] == "A" else 120
        self.assertEqual("eligible_candidate", runner.diagnosis_comparison(runs)["status"])
        for row in runs:
            if row["version"] == "B":
                for window in row["windows"].values():
                    window["successfulCallLatencyMillis"]["p99"] = 121
        self.assertEqual("regressed", runner.diagnosis_comparison(runs)["status"])

    def test_diagnostic_recordings_are_private_bounded_and_off_by_default(self):
        private = Path("build/fixture/private")
        self.assertEqual([], runner.jfr_options(private, "server", "off"))
        chunk_option, option = runner.jfr_options(private, "server", "jfr")
        self.assertIn("maxchunksize=8m", chunk_option)
        self.assertIn("maxsize=240m", option)
        self.assertIn("private", option)
        self.assertNotIn("evidence", option)
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence"
            value = runner.export_diagnostics(Path(directory) / "private", evidence, 1000, 120)
            self.assertFalse(value["complete"])
            self.assertFalse(json.loads((evidence / "server-diagnostics.json").read_text())["complete"])

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

    def test_sampler_does_not_misclassify_a_concurrent_normal_process_reap(self):
        with tempfile.TemporaryDirectory() as directory:
            redis = Mock()
            redis.info.side_effect = [{"total_commands_processed": 10}, {"used_memory": 10, "used_memory_rss": 20},
                                     {"used_cpu_user": 1, "used_cpu_sys": 1}, {}]
            sampler = runner.Sampler(Path(directory) / "resources.jsonl", redis)
            process = Mock(pid=10)
            process.poll.return_value = None  # Popen's nonblocking poll may race the runner's waitpid lock.
            sampler.register("harness", process)
            def exited(_):
                sampler.stopped.set()
                raise FileNotFoundError("Process has just exited")
            with patch.object(runner, "process_sample", side_effect=exited):
                sampler.run()
            self.assertIsNone(sampler.failure)
            self.assertEqual(1, sampler.counts["redis"])
            process.wait.assert_called_once_with(timeout=.05)

    def test_sampler_only_ignores_revoked_proc_access_after_confirmed_exit(self):
        for exited in (False, True):
            with self.subTest(exited=exited), tempfile.TemporaryDirectory() as directory:
                redis = Mock()
                redis.info.side_effect = [{"total_commands_processed": 10}, {"used_memory": 10, "used_memory_rss": 20},
                                         {"used_cpu_user": 1, "used_cpu_sys": 1}, {}]
                sampler = runner.Sampler(Path(directory) / "resources.jsonl", redis)
                process = Mock(pid=10)
                process.poll.return_value = None
                if not exited:
                    process.wait.side_effect = subprocess.TimeoutExpired("java", .05)
                sampler.register("harness", process)
                def revoked(_):
                    sampler.stopped.set()
                    raise PermissionError("/proc fd access revoked")
                with patch.object(runner, "process_sample", side_effect=revoked):
                    sampler.run()
                self.assertEqual(sampler.failure is None, exited)
                if not exited:
                    self.assertIn("PermissionError", sampler.failure)

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

    def test_direct_suite_keeps_task_nightly_cases_and_does_not_invent_drain(self):
        self.assertEqual(6, len(runner.SUITES["task"]))
        self.assertEqual(("direct-100", "direct-500", "direct-1000", "direct-2000", "direct-5000"), runner.SUITES["direct"])
        row = dict(pair=0, version="B", case="direct-100", offeredRate=100,
                   sent=3000, planned=3000, successfulCohortPerSecond=90, successRate=.9,
                   withinOneSecondRateOfSent=.8, successfulCallLatencyMillis={"p99": 1200}, generatorLimited=False)
        value = runner.markdown_summary(dict(suite="direct", status="passed", referenceHost=True, completeSuite=True, runs=[row]))
        self.assertIn("90.00%", value)
        self.assertIn("80.00%", value)
        self.assertIn("no results:load", value)
        self.assertNotIn("Result success after drain", value)

    def test_direct_comparison_only_compares_the_named_direct_fixture(self):
        runs = complete_runs()
        for row in runs:
            row["case"] = "direct-100"
        value = runner.comparison(runs, ("direct-100",))
        self.assertEqual("no_detected_regression", value["status"])
        self.assertEqual(["direct-100"], [row["case"] for row in value["cases"]])


if __name__ == "__main__":
    unittest.main()
