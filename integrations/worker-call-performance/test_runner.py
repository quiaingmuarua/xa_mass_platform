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


class RunnerTest(unittest.TestCase):
    def test_configuration_fingerprints_use_complete_current_or_historical_groups(self):
        names = ("application.yaml", "application-scenario-workers.yaml")
        self.assertEqual({f"server_boot_jvm/src/main/resources/{name}" for name in names},
                         set(runner.fingerprint(runner.ROOT)))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old = root / "server_jvm/src/main/resources"
            old.mkdir(parents=True)
            for name in names:
                (old / name).write_text("historical", encoding="utf-8")
            self.assertEqual({f"server_jvm/src/main/resources/{name}" for name in names}, set(runner.fingerprint(root)))
            with patch.object(runner, "ROOT", root):
                with self.assertRaisesRegex(RuntimeError, "configuration group is missing"):
                    runner.fingerprint(root)
            historical = root / "spring_server_jvm/src/main/resources"
            historical.mkdir(parents=True)
            (historical / names[0]).write_text("historical host", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "Incomplete"):
                runner.fingerprint(root)
            (historical / names[1]).write_text("historical host", encoding="utf-8")
            self.assertEqual({f"spring_server_jvm/src/main/resources/{name}" for name in names},
                             set(runner.fingerprint(root)))
            with patch.object(runner, "ROOT", root):
                with self.assertRaisesRegex(RuntimeError, "configuration group is missing"):
                    runner.fingerprint(root)
            current = root / "server_boot_jvm/src/main/resources"
            current.mkdir(parents=True)
            (current / names[0]).write_text("current", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "Incomplete"):
                runner.fingerprint(root)
            (current / names[1]).write_text("current", encoding="utf-8")
            with patch.object(runner, "ROOT", root):
                self.assertEqual({f"server_boot_jvm/src/main/resources/{name}" for name in names},
                                 set(runner.fingerprint(root)))

    def test_current_build_and_immutable_baseline_use_their_own_server_entrypoints(self):
        self.assertEqual("server_boot_jvm", runner.server_distribution(runner.ROOT))
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
            historical_source = baseline / "distribution/server" / main
            historical_source.parent.mkdir(parents=True)
            historical_source.touch()
            with patch.object(runner.subprocess, "run") as launch:
                runner.build(baseline)
                self.assertIn(":distribution:server:bootJar", launch.call_args.args[0])
            with patch.object(runner, "ROOT", baseline):
                with self.assertRaisesRegex(RuntimeError, "entrypoint is missing"):
                    runner.server_distribution(baseline)
            historical_host = baseline / "spring_server_jvm" / main
            historical_host.parent.mkdir(parents=True)
            historical_host.touch()
            with patch.object(runner.subprocess, "run") as launch:
                runner.build(baseline)
                self.assertIn(":spring_server_jvm:bootJar", launch.call_args.args[0])
            with patch.object(runner, "ROOT", baseline):
                with self.assertRaisesRegex(RuntimeError, "entrypoint is missing"):
                    runner.server_distribution(baseline)
            current_source = baseline / "server_boot_jvm" / main
            current_source.parent.mkdir(parents=True)
            current_source.touch()
            with patch.object(runner.subprocess, "run") as launch:
                runner.build(baseline, harness=True)
                self.assertIn(":server_boot_jvm:bootJar", launch.call_args.args[0])
                self.assertIn(":integrations:worker-call-performance:installDist", launch.call_args.args[0])

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
            value = runner.export_diagnostics(Path(directory) / "private", evidence, 1000, 30, "TASK")
            self.assertFalse(value["complete"])
            self.assertEqual({"server", "host"}, set(value["roles"]))
            self.assertTrue((evidence / "host-diagnostics.json").is_file())
            self.assertFalse(json.loads((evidence / "server-diagnostics.json").read_text())["complete"])

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

    def test_diagnostics_sampler_records_machine_cpu_separately_and_default_writes_none(self):
        stat = "cpu  10 1 5 100 2 0 1 3 0 0\ncpu0 1 0 1 1 0 0 0 0 0 0\nprocs_running 6\n"
        original = Path.read_text
        def read(path, *args, **kwargs):
            if path.as_posix() == "/proc/stat":
                return stat
            if path.as_posix() == "/proc/loadavg":
                return "3.50 2.00 1.00 5/300 99\n"
            return original(path, *args, **kwargs)
        for machine in (False, True):
            with self.subTest(machine=machine), tempfile.TemporaryDirectory() as directory:
                redis = Mock()
                redis.info.side_effect = [{"total_commands_processed": 10}, {"used_memory": 10, "used_memory_rss": 20},
                                         {"used_cpu_user": 1, "used_cpu_sys": 1}, {}]
                machine_path = Path(directory) / "machine-cpu.jsonl" if machine else None
                sampler = runner.Sampler(Path(directory) / "resources.jsonl", redis, machine_path=machine_path)
                redis.info.side_effect = lambda section: (sampler.stopped.set() or {}) if section == "commandstats" else {
                    "stats": {"total_commands_processed": 10}, "memory": {"used_memory": 10, "used_memory_rss": 20},
                    "cpu": {"used_cpu_user": 1, "used_cpu_sys": 1}}[section]
                with patch.object(Path, "read_text", read):
                    sampler.run()
                self.assertIsNone(sampler.failure)
                roles = {json.loads(line)["role"] for line in (Path(directory) / "resources.jsonl").read_text().splitlines()}
                self.assertEqual({"redis"}, roles)
                if machine:
                    row = json.loads(machine_path.read_text().splitlines()[0])
                    self.assertEqual({"user": 10, "nice": 1, "system": 5, "idle": 100, "iowait": 2, "irq": 0,
                                      "softirq": 1, "steal": 3}, row["cpuJiffies"])
                    self.assertEqual((6, 3.5), (row["procsRunning"], row["loadAverage1"]))
                else:
                    self.assertEqual(["resources.jsonl"], sorted(p.name for p in Path(directory).iterdir()))

    def test_redis_latency_keeps_command_names_times_and_durations_only(self):
        client = Mock()
        client.slowlog_get.return_value = [
            {"id": 2, "start_time": 1790750002, "duration": 9100, "command": "hset xa_mass:test_x:task:items m-1 {\"secret\":1}"},
            {"id": 1, "start_time": 1790750001, "duration": 6200, "command": "EVALSHA abc 1 xa_mass:test_x:score worker-7"}]
        client.execute_command.side_effect = lambda *args: {
            ("LATENCY", "LATEST"): [["command", 1790750002, 9, 12]],
            ("LATENCY", "HISTORY", "command"): [[1790750001, 6], [1790750002, 9]]}[args]
        evidence = runner.redis_latency(client)
        client.slowlog_get.assert_called_once_with(runner.REDIS_SLOWLOG_ENTRIES)
        self.assertEqual([dict(epochSeconds=1790750001, durationMicros=6200, command="EVALSHA"),
                          dict(epochSeconds=1790750002, durationMicros=9100, command="HSET")], evidence["slowCommands"])
        self.assertEqual({"command": [[1790750001, 6], [1790750002, 9]]}, evidence["latencyHistory"])
        self.assertFalse(evidence["possiblyTruncated"])
        self.assertNotIn("secret", json.dumps(evidence))
        self.assertNotIn("xa_mass", json.dumps(evidence))
        self.assertNotIn("worker-7", json.dumps(evidence))
        enabling = Mock()
        runner.enable_redis_latency(enabling)
        enabling.config_set.assert_any_call("slowlog-log-slower-than", 5000)
        enabling.config_set.assert_any_call("slowlog-max-len", 4096)
        enabling.config_set.assert_any_call("latency-monitor-threshold", 5)
        enabling.slowlog_reset.assert_called_once_with()
        enabling.execute_command.assert_called_once_with("LATENCY", "RESET")
        with tempfile.TemporaryDirectory() as directory:
            broken = Mock()
            broken.slowlog_get.side_effect = ConnectionError("gone")
            destination = Path(directory) / "diagnostics/redis-latency.json"
            self.assertEqual({"complete": False, "failureType": "ConnectionError"},
                             runner.write_redis_latency(broken, destination))
            self.assertTrue(destination.is_file())


class LaneWorldConfigTest(unittest.TestCase):
    def test_lane_raises_resource_knobs_and_keeps_adapter_invariants(self):
        flags = runner.lane_server_flags()
        adapter = runner.LANE_ADAPTER
        self.assertEqual("1000", flags["xa.mass.kernel-pacer.assignment-batch-limit"])
        self.assertEqual("1000", flags["xa.mass.task-rpc.max-probe-items-per-round"])
        self.assertEqual("10000", flags[adapter + "report-queue-capacity"])
        self.assertEqual("scenario-workers", flags["spring.profiles.active"])
        # The lane measures the scenario profile's consume limit and must not raise it.
        self.assertNotIn(adapter + "command-consume-limit", flags)
        self.assertEqual(500, runner.LANE_MECHANISM_CONSTANTS["adapterCommandConsumeLimit"])
        self.assertEqual("DEFAULT", flags["xa.mass.kernel-pacer.preset"])
        for audit in runner.LANE_KNOB_AUDIT:
            self.assertIn(audit["disposition"], ("raised", "audited"))
            self.assertTrue(audit["reason"] and audit["evidence"])
            if audit["disposition"] == "raised" and audit["knob"] in flags:
                self.assertEqual(audit["laneValue"], flags[audit["knob"]])

    def test_lane_world_replaces_the_scenario_project_with_two_groups(self):
        flags = runner.lane_server_flags()
        self.assertEqual("perf-lane", flags["xa.mass.project-assembly.projects[0].project-id"])
        self.assertEqual(list(runner.LANE_GROUPS), [flags[f"xa.mass.project-assembly.projects[0].worker-group-ids[{i}]"]
                                                    for i in range(len(runner.LANE_GROUPS))])
        self.assertNotIn("scenario-string-utils-workers", json.dumps({k: v for k, v in flags.items() if "assembly" in k}))
        self.assertEqual(set(runner.LANE_GROUPS), set(json.loads(flags["xa.mass.worker-assembly.group-config-json"])))
        for group in runner.LANE_GROUPS:
            self.assertEqual("any", flags[f"xa.mass.worker-matching.groups[{group}].pools[0]"])
            supply = json.loads(flags[f"xa.mass.task-rpc.refill-by-worker-group[{group}][0]"])
            self.assertGreaterEqual(supply["count"], runner.LANE_ASSIGNMENT_BATCH_LIMIT)
        hosts = runner.lane_host_groups()
        self.assertEqual({group: 1000 for group in runner.LANE_GROUPS}, {g: v["count"] for g, v in hosts.items()})

    def test_resource_peaks_cover_every_process_sample_and_skip_redis(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "samples.jsonl"
            rows = [dict(role="server", nativeThreads=40, openFileDescriptors=100, rssBytes=10),
                    dict(role="server", nativeThreads=60, openFileDescriptors=90, rssBytes=30),
                    dict(role="redis", totalCommandsProcessed=1),
                    dict(role="host", nativeThreads=30, openFileDescriptors=2100, rssBytes=20)]
            path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            peaks = runner.resource_peaks(path)
        self.assertEqual({"server", "host"}, set(peaks))
        self.assertEqual(dict(samples=2, peakNativeThreads=60, peakFileDescriptors=100, peakRssBytes=30), peaks["server"])

    def test_invalid_lane_options_are_rejected_before_any_process_starts(self):
        for extra in (["--suite", "task"], ["--case", "any-100"], ["--assignment-batch-limit", "500"],
                      ["--lane-rates", "700"], ["--lane-rates", "500,500"], ["--lane-modes", "open,open"],
                      ["--baseline-ref", "HEAD", "--lane-case", "task-any-500", "--repetitions", "3"],
                      ["--baseline-ref", "HEAD", "--lane-case", "task-any-500", "--diagnostics", "jfr"]):
            with patch.object(runner.sys, "argv", ["runner", *extra]), patch("sys.stderr"), self.assertRaises(SystemExit):
                runner.main()

    def test_lane_plan_runs_every_path_per_rate_and_rotates_path_order(self):
        plan = runner.lane_plan((500, 2000), 3)
        self.assertEqual(24, len(plan))
        open_cases = lambda rep, rate: [e["path"] for e in plan if e["mode"] == "open" and e["repetition"] == rep and e["rate"] == rate]
        self.assertEqual(["task-any", "task-targeted", "direct"], open_cases(1, 500))
        self.assertEqual(["task-targeted", "direct", "task-any"], open_cases(2, 500))
        self.assertEqual(["direct", "task-any", "task-targeted"], open_cases(3, 2000))
        saturation = [e["case"] for e in plan if e["mode"] == "saturation" and e["repetition"] == 2]
        self.assertEqual(["sat-task-targeted", "sat-task-any"], saturation)
        self.assertEqual(["sat-task-any", "sat-task-targeted"],
                         [e["case"] for e in runner.lane_plan((500,), 1, ("saturation",))])
        self.assertEqual(3, len(runner.lane_plan((500,), 1, ("open",))))
        self.assertEqual((500, 1000, 2000), runner.lane_rates("500,1000,2000"))
        self.assertEqual(("saturation",), runner.lane_modes("saturation"))
        with self.assertRaises(runner.argparse.ArgumentTypeError):
            runner.lane_modes("open,open")

    def test_path_ratios_compare_passed_task_cases_with_direct_on_the_same_cell(self):
        def record(path, status="passed", p99=100.0, completed=500.0, rate=500, repetition=1):
            return dict(repetition=repetition, rate=rate, path=path, status=status, completedPerSecond=completed,
                        successfulCallLatencyMillis={"p99": p99})
        saturation = dict(record("task-any"), mode="saturation", rate=None)
        ratios = runner.lane_path_ratios([record("direct", p99=50, completed=500), record("task-any", p99=150, completed=400),
                                          record("task-targeted", status="invalid"), record("task-any", rate=1000), saturation])
        self.assertEqual([dict(repetition=1, rate=500, path="task-any", p99LatencyRatio=3.0, completionRatio=0.8)], ratios)

    def test_case_record_normalizes_cost_per_completed_call(self):
        case = dict(status="invalid", invalidReasons=["generator-lag"], completedPerSecond=100.0,
                    measurementStartedEpochMillis=1, measurementEndedEpochMillis=2,
                    metrics=dict(successRate=.9, successfulCallLatencyMillis={"p50": 10, "p99": 20}, generatorLimited=True))
        entry = dict(repetition=2, mode="open", rate=1000, path="task-any", case="task-any-1000")
        record = runner.lane_case_record(entry, case,
                                         dict(serverCpuSeconds=6.0, redisCommands=30_000, redisCpuSeconds=1.0,
                                              serverCoveredSeconds=30, redisCoveredSeconds=30))
        self.assertEqual("task-any-1000", record["case"])
        self.assertIsNone(record["cost"]["redisCommandsPerCompleted"])
        self.assertIsNone(record["cost"]["serverCpuMillisPerCompleted"])
        self.assertEqual(["generator-lag"], record["invalidReasons"])

    def test_window_cost_uses_only_samples_inside_the_measurement(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "samples.jsonl"
            rows = [dict(role="server", epochMillis=t, cpuSeconds=t / 1000) for t in (0, 1000, 31_000, 40_000)]
            rows += [dict(role="redis", epochMillis=t, cpuUserSeconds=t / 2000, cpuSystemSeconds=0,
                          totalCommandsProcessed=t) for t in (0, 1000, 31_000, 40_000)]
            path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            cost = runner.lane_window_cost(path, 1000, 31_000)
            self.assertIsNone(runner.lane_window_cost(path, 32_000, 33_000))
        self.assertEqual(30.0, cost["serverCpuSeconds"])
        self.assertEqual(30_000, cost["redisCommands"])
        self.assertEqual(15.0, cost["redisCpuSeconds"])

    def test_merge_combines_jobs_and_fails_on_a_missing_or_failed_job(self):
        def job(status, cases):
            return dict(status=status, cases=cases, caseCounts={}, timingsMillis={"casesCompleted": 600_000},
                        resourcePeaks={"server": dict(peakNativeThreads=200, peakFileDescriptors=3000)})
        direct = dict(repetition=1, mode="open", rate=500, path="direct", case="direct-500", status="passed",
                      completedPerSecond=500.0, successfulCallLatencyMillis={"p99": 100.0})
        task = dict(direct, path="task-any", case="task-any-500", successfulCallLatencyMillis={"p99": 300.0})
        saturation = dict(repetition=1, mode="saturation", rate=None, path="task-any", case="sat-task-any", status="passed")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name, summary in (("rate-500", job("passed", [direct, task])), ("saturation", job("passed", [saturation]))):
                path = root / f"lane-{name}-1" / "lane-summary.json"
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps(summary), encoding="utf-8")
            merged = runner.merge_lane(root, ("rate-500", "saturation"))
            missing = runner.merge_lane(root, ("rate-500", "rate-1000", "saturation"))
        self.assertEqual("passed", merged["status"])
        self.assertEqual({"passed": 3}, merged["caseCounts"])
        self.assertEqual(["rate-500", "rate-500", "saturation"], [case["job"] for case in merged["cases"]])
        self.assertEqual(3.0, merged["pathRatios"][0]["p99LatencyRatio"])
        self.assertEqual("failed", missing["status"])
        self.assertIn("| rate-1000 | missing |", runner.lane_markdown(missing))
        self.assertNotIn("| Milestone |", runner.lane_markdown(merged))
        self.assertNotIn("| Role |", runner.lane_markdown(merged))

    def test_lane_entry_warmup_and_ab_options(self):
        self.assertEqual(dict(repetition=2, mode="open", rate=1000, path="task-targeted", case="task-targeted-1000"),
                         runner.lane_entry("task-targeted-1000", 2))
        self.assertEqual("saturation", runner.lane_entry("sat-task-any")["mode"])
        for unknown in ("task-any-700", "sat-direct", "direct"):
            with self.assertRaises(ValueError):
                runner.lane_entry(unknown)
        self.assertEqual("task-any-2000", runner.lane_warmup_case((500, 2000), ("open",)))
        self.assertEqual("task-any-1000", runner.lane_warmup_case((500,), ("saturation",)))
        for extra in (["--baseline-ref", "HEAD"], ["--lane-case", "task-any-500"],
                      ["--baseline-ref", "HEAD", "--lane-case", "task-any-700"],
                      # A saturation A/B needs the configured world; an open-loop A/B takes none.
                      ["--baseline-ref", "HEAD", "--lane-case", "sat-task-any"],
                      ["--baseline-ref", "HEAD", "--lane-case", "task-any-1000", "--experiment-config", "lane.json"]):
            with patch.object(runner.sys, "argv", ["runner", *extra]), patch("sys.stderr"), self.assertRaises(SystemExit):
                runner.main()

    def test_merge_with_meta_records_the_run_and_reports_trend(self):
        case = dict(repetition=1, mode="open", rate=500, path="task-any", case="task-any-500", status="passed",
                    completedPerSecond=500.0, successWithinWait=1.0, successfulCallLatencyMillis={"p99": 300.0, "p50": 200.0})
        summary = dict(status="passed", cases=[case], caseCounts={"passed": 1}, timingsMillis={"casesCompleted": 1},
                       calibration={"cpuParallelOpsPerSecond": 1e6, "redisPingMicros": 40.0},
                       warmup={"case": "task-any-500", "status": "passed"})
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / "lane-rate-500-1" / "lane-summary.json"
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(summary), encoding="utf-8")
            history_dir = root / "history" / "runs"
            history_dir.mkdir(parents=True)
            (history_dir / "old.json").write_text(json.dumps({"runId": "old", "ref": "main"}), encoding="utf-8")
            history = runner.load_history(root / "history")
            merged = runner.merge_lane(root, ("rate-500",), history,
                                       dict(runId="now", ref="main", createdAt="2026-10-01T19:00:00Z"))
        self.assertEqual(1, len(history))
        self.assertEqual([], runner.load_history(Path(folder) / "absent"))
        self.assertEqual(1e6, merged["record"]["calibration"]["rate-500"]["cpuParallelOpsPerSecond"])
        self.assertEqual(300.0, merged["record"]["cases"]["task-any-500"]["metrics"]["p99LatencyMillis"]["median"])
        self.assertEqual("insufficient-history", merged["trend"]["status"])
        value = runner.lane_markdown(merged)
        self.assertIn("1,000,000", value)
        self.assertIn("Trend: **insufficient-history**", value)

    def test_ab_markdown_reports_pairs_band_and_decision(self):
        value = runner.lane_ab_markdown(dict(status="passed", case="task-any-1000", metric="p50LatencyMillis",
            versions={"A": "a" * 40, "B": "b" * 40}, band={"relative": .1, "provisional": True},
            pairs=[dict(pair=1, version="A", runStatus="passed", caseStatus="passed", p50LatencyMillis=300.0),
                   dict(pair=1, version="B", runStatus="passed", caseStatus="saturated", p50LatencyMillis=None)],
            decision={"decision": "continue", "deltas": [.05]}, pooledP99LatencyMillis={"A": 390.0, "B": 402.5}))
        self.assertIn("Decision: **continue**", value)
        self.assertIn("Band ±10.0% (provisional)", value)
        self.assertIn("| 1 | B | passed | saturated | — |", value)
        self.assertIn("+5.0%", value)
        self.assertIn("A 390.0 ms, B 402.5 ms", value)

    def test_successful_latencies_match_the_harness_success_basis(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "samples.jsonl"
            rows = [dict(outcome="succeeded", plannedOffsetMillis=10.0, endedOffsetMillis=225.5),
                    dict(outcome="not_observed", plannedOffsetMillis=20.0, endedOffsetMillis=1020.0),
                    dict(outcome="succeeded", plannedOffsetMillis=30.0, endedOffsetMillis=-1)]
            path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            self.assertEqual([215.5], runner.successful_latencies(path))
        self.assertEqual([], runner.successful_latencies(Path(folder) / "absent.jsonl"))

    def test_lane_markdown_separates_invalid_and_failed_cases(self):
        cases = [dict(repetition=1, case="task-any-500", status="passed", invalidReasons=[], successWithinWait=.95,
                      completedPerSecond=500.0, successfulCallLatencyMillis={"p50": 20.0, "p99": 90.0},
                      attribution=dict(checkedItemsPerSecond=1200, candidateShortfallRatio=.01, workerAcquisitionRejectedRatio=.002),
                      cost=dict(redisCommandsPerCompleted=12.5)),
                 dict(repetition=1, case="direct-2000", status="invalid", invalidReasons=["generator-lag"]),
                 dict(repetition=1, case="task-any-2000", status="saturated", invalidReasons=[], admittedPerSecond=1500.2,
                      leaseHeldPeakRatio=.946),
                 dict(repetition=1, case="task-targeted-2000", status="failed", invalidReasons=[], failureReason="drain-stalled"),
                 dict(repetition=1, case="sat-task-any", status="passed", invalidReasons=[], completedPerSecond=1580.0,
                      measurementVersion=2)]
        value = runner.lane_markdown(dict(status="failed", caseCounts={"passed": 1, "invalid": 1, "saturated": 1, "failed": 1}, cases=cases,
            timingsMillis={"serverReady": 12_000}, resourcePeaks={"host": dict(samples=3, peakNativeThreads=50,
            peakFileDescriptors=2100, peakRssBytes=2**29)}))
        self.assertIn("| 1 | task-any-500 | passed | 95.00% | 500.0 | 20.0 / 90.0 | 1200 | 1.00% | 0.20% | — | 12.5 |", value)
        self.assertIn("invalid (generator-lag)", value)
        self.assertIn("saturated (admitted 1500/s)", value)
        self.assertIn("| 1 | sat-task-any | passed | — | 1580.0 |", value)
        self.assertIn("94.60%", value)
        self.assertIn("failed (drain-stalled)", value)
        self.assertIn("| host | 50 | 2100 | 512 |", value)
        self.assertIn("Performance values never fail the lane", value)


if __name__ == "__main__":
    unittest.main()
