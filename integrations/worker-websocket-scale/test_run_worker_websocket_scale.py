import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("run_worker_websocket_scale.py")
SPEC = importlib.util.spec_from_file_location("worker_websocket_scale_runner", MODULE_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


class WorkerWebSocketScaleRunnerTest(unittest.TestCase):

    def test_inventory_uses_at_most_one_hundred_strict_records_per_file(self):
        with tempfile.TemporaryDirectory() as directory:
            sandbox = Path(directory) / "scenario-workers"
            RUNNER._generate_inventory(sandbox, 205)

            files = sorted((sandbox / RUNNER.WORKER_GROUP).glob("*.jsonl"))
            self.assertEqual(
                ["workers-000.jsonl", "workers-001.jsonl", "workers-002.jsonl"],
                [path.name for path in files],
            )
            self.assertEqual([100, 100, 5], [len(path.read_text().splitlines()) for path in files])
            observed = 0
            for path in files:
                for line_number, line in enumerate(path.read_text().splitlines(), 1):
                    value = json.loads(line)
                    self.assertEqual(2, value["schemaVersion"])
                    properties = value["workerProperties"]
                    self.assertEqual(path.name, properties["labInventoryKey"])
                    self.assertEqual(line_number, properties["labInventoryLine"])
                    observed += 1
            self.assertEqual(205, observed)

    def test_ten_thousand_workers_use_one_hundred_inventory_files(self):
        with tempfile.TemporaryDirectory() as directory:
            sandbox = Path(directory) / "scenario-workers"
            RUNNER._generate_inventory(sandbox, 10_000)

            files = sorted((sandbox / RUNNER.WORKER_GROUP).glob("*.jsonl"))
            self.assertEqual(100, len(files))
            self.assertTrue(all(len(path.read_text().splitlines()) == 100 for path in files))

    def test_capability_assembly_keeps_only_the_existing_md5_capability(self):
        assembly = RUNNER._capability_assembly()
        self.assertEqual([RUNNER.WORKER_GROUP], list(assembly))
        group = assembly[RUNNER.WORKER_GROUP]
        self.assertEqual(["extension.worker.string.md5"], group["eventCodes"])
        self.assertEqual(600, group["reconnectPolicy"]["maxUnstableAttempts"])

    def test_each_phase_receives_the_fixed_dual_task_workload(self):
        options = SimpleNamespace(
            workers=10_000,
            minimum_converged=9_900,
            workload_items_per_task=500,
            stable_hold_millis=60_000,
            scan_interval_millis=10_000,
            maximum_convergence_wait_millis=900_000,
            task_result_wait_millis=300_000,
            request_timeout_millis=30_000,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(RUNNER, "_run") as run:
                RUNNER._run_phase(
                    "initial",
                    options,
                    "proof-a",
                    root / "baseline.json",
                    root / "summary.json",
                    root / "timeline.jsonl",
                    {},
                )

        command = run.call_args.args[0]
        self.assertIn("--workload-items-per-task=500", command)
        self.assertFalse(
            any(value.startswith("--task-item-count=") for value in command)
        )

    def test_resource_contract_covers_both_processes(self):
        RUNNER._validate_resource_contract({
            "worker-host": {
                "maximumNativeThreads": 511,
                "maximumOpenFileDescriptors": 32_767,
            },
            "runtime-server": {
                "maximumNativeThreads": 511,
                "maximumOpenFileDescriptors": 16_383,
            },
        })

        for owner, field, value in (
            ("worker-host", "maximumNativeThreads", 512),
            ("worker-host", "maximumOpenFileDescriptors", 32_768),
            ("runtime-server", "maximumOpenFileDescriptors", 16_384),
        ):
            resources = {
                "worker-host": {
                    "maximumNativeThreads": 100,
                    "maximumOpenFileDescriptors": 1_000,
                },
                "runtime-server": {
                    "maximumNativeThreads": 100,
                    "maximumOpenFileDescriptors": 1_000,
                },
            }
            resources[owner][field] = value
            with self.assertRaises(RuntimeError):
                RUNNER._validate_resource_contract(resources)

    def test_phase_summary_requires_two_complete_tasks_and_reconvergence(self):
        options = SimpleNamespace(
            workload_items_per_task=500,
            minimum_converged=9_900,
        )
        summary = {
            "activeTaskCount": 2,
            "offeredItemsPerTask": 500,
            "totalOfferedItems": 1_000,
            "appendBatchCount": 10,
            "taskASucceededCount": 500,
            "taskBSucceededCount": 500,
            "minimumConnectedDuringWork": 9_900,
            "postWorkConnectedAndHot": 9_900,
        }
        RUNNER._validate_phase_summary(summary, options)

        summary["taskBSucceededCount"] = 499
        with self.assertRaises(RuntimeError):
            RUNNER._validate_phase_summary(summary, options)


if __name__ == "__main__":
    unittest.main()
