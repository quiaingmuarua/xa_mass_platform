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
        for extra in (["--lane-history", "build/history"], ["--lane-modes", "saturation"],
                      ["--merge-lane", "build/jobs"]):
            with patch.object(runner.sys, "argv", ["runner", "--experiment-config", "capacity.json", *extra]), \
                    patch("sys.stderr"), self.assertRaises(SystemExit):
                runner.main()

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
