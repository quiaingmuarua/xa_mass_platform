import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import capacity_experiment
import test_runner


class CapacityRunnerTest(unittest.TestCase):
    def test_packaged_manifest_requires_matching_artifacts_without_git(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "experiment-manifest.json").write_text('{"sourceHead":"fixture"}')
            (root / "payload.jar").write_bytes(b"fixture")
            digest = hashlib.sha256(b"fixture").hexdigest()
            (root / "SHA256SUMS").write_text(digest + "  payload.jar\n")
            self.assertEqual("fixture", capacity_experiment.verify_bundle(root)["sourceHead"])
            (root / "payload.jar").write_bytes(b"modified")
            with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
                capacity_experiment.verify_bundle(root)

    def test_process_affinity_and_fd_limit_apply_to_child_launch(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(test_runner.runner.subprocess, "Popen") as launch:
            test_runner.runner.start_process(["java", "-version"], Path(folder) / "log", {},
                                             cpu_ids=[0, 1, 2, 3], fd_limit=32768)
            self.assertEqual(["prlimit", "--nofile=32768:32768", "--", "taskset", "--cpu-list", "0,1,2,3",
                              "java", "-version"], launch.call_args.args[0])

    def test_capacity_input_cannot_be_mixed_with_ci_history_or_reference_cases(self):
        runner = test_runner.runner
        for extra in (["--lane-history", "build/history"], ["--lane-modes", "open,saturation"],
                      ["--merge-lane", "build/jobs"]):
            with patch.object(runner.sys, "argv", ["runner", "--experiment-config", "capacity.json", *extra]), \
                    patch("sys.stderr"), self.assertRaises(SystemExit):
                runner.main()

    def test_configured_lane_passes_the_resolved_profile_and_keeps_raw_recordings_private(self):
        runner = test_runner.runner
        config = json.loads((runner.MODULE / "configs/lane-saturation.json").read_text())
        original_read = Path.read_text
        for bound in (False, True):
            with self.subTest(worker_bound=bound), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                jar = root / "server_boot_jvm/build/libs/server.jar"
                jar.parent.mkdir(parents=True)
                jar.touch()
                output = root / "build/lane"
                output.mkdir(parents=True)
                def resolve(args, **kwargs):
                    self.assertIn("--phase=experiment-config", args)
                    runner.write_json(output / "evidence/experiment.json", config)
                def execute(module, case_root, resolved, actual, profile, stage, path, repetition, cpus, diagnostics):
                    self.assertEqual(config, actual)
                    self.assertEqual(config["profiles"][0], profile)
                    self.assertEqual([2, 3, 4, 5], cpus)
                    self.assertEqual(output / "evidence/experiment.json", resolved)
                    (case_root / "private").mkdir(parents=True)
                    (case_root / "private/server.jfr").write_bytes(b"private")
                    case = dict(case="sat-" + path, status="invalid" if bound else "passed", workersBound=bound,
                                invalidReasons=["workers-exhausted"] if bound else [], completedPerSecond=8123.5, workerSamples=[],
                                resourcePeaks={}, resourceCosts={})
                    runner.write_json(case_root / "evidence/result.json", case)
                    return case
                def read(path, *args, **kwargs):
                    return "MemTotal: 16000000 kB\n" if path.as_posix() == "/proc/meminfo" else original_read(path, *args, **kwargs)
                with patch.dict(runner.sys.modules, {runner.__name__: runner}), \
                        patch.object(runner, "ROOT", root), patch.object(runner, "command", side_effect=resolve), \
                        patch.object(runner, "artifact_fingerprints", return_value={"server": "fixture"}), \
                        patch.object(runner.os, "sched_getaffinity", return_value=set(range(2, 10)), create=True), \
                        patch.object(Path, "read_text", read), \
                        patch.object(capacity_experiment, "run_case", side_effect=execute) as run:
                    result = runner.run_saturation_lane(output, runner.lane_plan((), 1, ("saturation",)), root / "input.json")
                self.assertEqual("failed" if bound else "passed", result["status"])
                self.assertEqual(1 if bound else 2, run.call_count)
                self.assertEqual(8123.5, result["cases"][0]["completedPerSecond"])
                self.assertEqual([], list((output / "evidence").rglob("*.jfr")))
                self.assertEqual(3000, result["workersPerGroup"])
                report = runner.lane_markdown(result)
                self.assertIn("3000 | 4 | 1024 / 1024 / 1024 | 15 / 30 | 600000", report)
                if bound:
                    self.assertEqual(["workers-exhausted"], result["cases"][0]["invalidReasons"])

    def test_saturation_ab_runs_the_configured_world_with_each_versions_artifacts(self):
        runner = test_runner.runner
        config = json.loads((runner.MODULE / "configs/lane-saturation.json").read_text())
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            output = root / "build/lane-ab"
            output.mkdir(parents=True)
            def git(args, **kwargs):
                if "--phase=experiment-config" in args:
                    runner.write_json(output / "evidence/experiment.json", config)
                    return ""
                if args[:2] == ["git", "rev-parse"]:
                    return "a" * 40 if "--verify" in args else "b" * 40
                if args[:3] == ["git", "worktree", "add"]:
                    Path(args[4]).mkdir(parents=True)
                return ""
            calls = []
            def execute(module, case_root, resolved, actual, profile, stage, path, repetition, cpus,
                        diagnostics="off", artifacts=None):
                self.assertEqual((config, config["profiles"][0], "screening", "any"), (actual, profile, stage, path[5:]))
                calls.append((repetition, artifacts))
                (case_root / "private").mkdir(parents=True)
                (case_root / "private/server.jfr").write_bytes(b"private")
                case = dict(case="sat-" + path, status="passed", completedPerSecond=8000.0, invalidReasons=[])
                runner.write_json(case_root / "evidence/result.json", case)
                return case
            options = runner.argparse.Namespace(lane_case="sat-task-any", baseline_ref="main", lane_history=None,
                                                skip_build=True, experiment_config=root / "lane.json")
            with patch.dict(runner.sys.modules, {runner.__name__: runner}), \
                    patch.object(runner, "ROOT", root), patch.object(runner, "command", side_effect=git), \
                    patch.object(runner, "build"), \
                    patch.object(runner.os, "sched_getaffinity", return_value=set(range(4)), create=True), \
                    patch.object(capacity_experiment, "run_case", side_effect=execute):
                self.assertEqual(0, runner.main_lane_ab(options, output, runner.time.monotonic(), True))
            baseline = output / "baseline-checkout"
            # ABBA pairs alternate the baseline checkout's and the current checkout's Server/Host.
            self.assertEqual([(1, baseline), (1, root), (2, root), (2, baseline)], calls)
            summary = json.loads((output / "evidence/lane-ab-summary.json").read_text())
            self.assertEqual(("passed", "no-difference", 3000),
                             (summary["status"], summary["decision"]["decision"], summary["experiment"]["workersPerGroup"]))
            self.assertEqual([], [p for p in output.rglob("evidence/**/*.jfr")])

    def test_harness_receives_the_exact_shared_config_and_profile(self):
        command = capacity_experiment.harness_command(test_runner.runner, "bootstrap", Path("output"),
            Path("resolved.json"), "--experiment-profile=c16-w4000", "--experiment-stage=confirmation")
        self.assertIn("--experiment-config=resolved.json", command)
        self.assertIn("--experiment-profile=c16-w4000", command)
        self.assertIn("--experiment-stage=confirmation", command)

    def test_local_capacity_summary_is_not_reference_history(self):
        local = {"measurementVersion": 2, "referenceHost": False, "experiment": "capacity-10k",
                 "ref": "main", "targetStatus": "met", "cases": []}
        current = {"runId": "ci", "ref": "main"}
        self.assertEqual([], test_runner.runner.lane_trend.history_for(current, [local]))
