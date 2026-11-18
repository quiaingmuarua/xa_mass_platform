from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location("deployment_performance", Path(__file__).with_name("run_worker_call_performance.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class DeploymentRunnerTest(unittest.TestCase):
    def test_deployment_dispatch_has_no_docker_build_or_reference_host_dependency(self):
        with patch.object(runner.sys, "argv", ["runner", "--deployment-config", "consumer.json"]), \
                patch.object(runner.sys, "platform", "linux"), \
                patch.object(runner, "main_deployment", return_value=0) as deployment, \
                patch.object(runner, "command", side_effect=AssertionError("local orchestration reached")), \
                patch.object(runner, "build", side_effect=AssertionError("build reached")):
            self.assertEqual(0, runner.main())
            self.assertEqual(Path("consumer.json"), deployment.call_args.args[0].deployment_config)

    def test_unsupported_server_operations_are_rejected_before_deployment(self):
        for extra in (("--lane-modes", "saturation"), ("--lane-attribution=on",), ("--diagnostics", "jfr"),
                      ("--lane-history", "history"), ("--experiment-config", "experiment.json")):
            with self.subTest(extra=extra), patch.object(runner.sys, "argv", ["runner", "--deployment-config", "consumer.json", *extra]), \
                    patch.object(runner, "main_deployment") as deployment, patch("sys.stderr", new=io.StringIO()):
                with self.assertRaises(SystemExit):
                    runner.main()
                deployment.assert_not_called()

    def test_published_classpaths_and_external_output_need_no_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "kit"
            (root / "acceptance/performance/harness/lib").mkdir(parents=True)
            with patch.object(runner, "ROOT", root):
                self.assertEqual(root / "acceptance/performance/harness/lib/*", runner.harness_classpath())
                self.assertEqual(root / "lib/*", runner.host_classpath())
                with self.assertRaisesRegex(ValueError, "outside the immutable"):
                    runner.fresh_output(root / "runs/run-1", deployment=True)
                output = runner.fresh_output(Path(directory) / "runs/run-1", deployment=True)
                self.assertTrue(output.is_dir())
                with self.assertRaises(ValueError):
                    runner.fresh_output(output, deployment=True)

    def test_consumer_sampler_never_manufactures_remote_resource_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "resources.jsonl"
            sampler = runner.Sampler(path, None)
            process = Mock(pid=123)
            process.poll.return_value = None
            sampler.register("host", process)
            sample = {"cpuSeconds": 1, "nativeThreads": 20, "openFileDescriptors": 30, "rssBytes": 1024}
            with patch.object(runner, "process_sample", return_value=sample), \
                    patch.object(sampler.stopped, "wait", side_effect=lambda _: sampler.stopped.set()):
                sampler.run()
            self.assertIsNone(sampler.failure)
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(["host"], [row["role"] for row in rows])

    def test_cleanup_uses_exact_owned_ids_and_never_mutates_remote_state(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / "key.txt"
            key.write_text("xak_private_test_key")
            config = {"accessKeyFile": str(key), "runtimeUrl": "https://runtime.example", "endpointManager": "public"}
            responses = [io.BytesIO(json.dumps({"statesByWorkerId": {"a": "unknown", "b": "disconnected"}}).encode())]
            with patch.object(runner, "deployment_open", side_effect=responses) as request:
                result = runner.observe_deployment_disconnect(config, ["a", "b"])
            self.assertEqual("confirmed", result["status"])
            sent = request.call_args.args[0]
            self.assertTrue(sent.full_url.endswith("/workers:network-observe"))
            self.assertEqual(["a", "b"], json.loads(sent.data))
            self.assertTrue(key.is_file())
            with patch.object(runner, "deployment_open", return_value=io.BytesIO(b'{"statesByWorkerId":{}}')):
                with self.assertRaisesRegex(RuntimeError, "identity set"):
                    runner.observe_deployment_disconnect(config, ["a"])

    def test_authenticated_cleanup_does_not_follow_redirects(self):
        handler = runner.DeploymentNoRedirect()
        request = runner.urllib.request.Request("https://runtime.example/path", headers={"Authorization": "Bearer private"})
        self.assertIsNone(handler.redirect_request(request, None, 302, "redirect", {}, "https://other.example/path"))

    def test_existing_deployment_report_identifies_missing_remote_evidence(self):
        result = {"status": "passed", "cases": [], "executionEnvironment": "existing-server", "groups": ["a", "b"],
                  "workersPerGroup": 5000, "assignmentBatchLimit": 100, "hostMaxHeap": "3g", "serverDeployment": {"version": "4.4.0"}}
        report = runner.lane_markdown(result)
        self.assertIn("assignment ceiling 100.", report)
        self.assertIn("not a same-host reference", report)
        self.assertIn("were not collected", report)
        self.assertNotIn("legacy world", report)

    def test_invalid_measurement_preserves_original_next_case_quiesce_flow(self):
        with tempfile.TemporaryDirectory() as directory:
            result = {"cases": [], "timingsMillis": {}}
            plan = runner.lane_plan((500,), 1, ("open",))
            def phase(name, *args, **kwargs):
                return {"status": "passed"} if name == "quiesce" else {"status": "invalid", "invalidReasons": ["workers-exhausted"]}
            with patch.object(runner, "run_lane_harness", side_effect=phase) as harness, patch("sys.stdout", new=io.StringIO()):
                runner.run_lane_cases(Path(directory), 100, plan, None, {}, {}, Mock(), result,
                                      deployment=Path(directory) / "config.json", lab_url="http://127.0.0.1:1")
            self.assertEqual(3, len(result["cases"]))
            self.assertEqual(["quiesce", "case"] * 3, [call.args[0] for call in harness.call_args_list])


if __name__ == "__main__":
    unittest.main()
