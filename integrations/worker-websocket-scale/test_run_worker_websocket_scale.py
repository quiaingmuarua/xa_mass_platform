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

    def test_fifteen_thousand_workers_use_one_hundred_fifty_files(self):
        with tempfile.TemporaryDirectory() as directory:
            sandbox = Path(directory) / "scenario-workers"
            coordinates = RUNNER._generate_inventory(sandbox, 15_000)

            files = sorted((sandbox / RUNNER.WORKER_GROUP).glob("*.jsonl"))
            self.assertEqual(150, len(files))
            self.assertTrue(all(len(path.read_text().splitlines()) == 100 for path in files))
            self.assertEqual("workers-149.jsonl:100", coordinates[-1])

            topology = RUNNER._partition_topology(coordinates, 10_000)
            self.assertEqual(
                10_000,
                len(topology["retainedLabWorkerKeys"]),
            )
            self.assertEqual(
                5_000,
                len(topology["stoppedLabWorkerKeys"]),
            )
            self.assertEqual(
                "workers-099.jsonl:100",
                topology["retainedLabWorkerKeys"][-1],
            )
            self.assertEqual(
                "workers-100.jsonl:1",
                topology["stoppedLabWorkerKeys"][0],
            )

    def test_capability_assembly_keeps_only_the_existing_md5_capability(self):
        assembly = RUNNER._capability_assembly()
        self.assertEqual([RUNNER.WORKER_GROUP], list(assembly))
        group = assembly[RUNNER.WORKER_GROUP]
        self.assertEqual(["extension.worker.string.md5"], group["eventCodes"])
        self.assertEqual(600, group["reconnectPolicy"]["maxUnstableAttempts"])

    def test_each_phase_receives_the_fixed_loaded_operation(self):
        options = SimpleNamespace(
            prepared_workers=15_000,
            retained_workers=10_000,
            minimum_initial_converged=14_800,
            minimum_retained_converged=9_900,
            workload_items_per_task=5_000,
            stable_hold_millis=60_000,
            scan_interval_millis=10_000,
            maximum_convergence_wait_millis=900_000,
            task_result_wait_millis=900_000,
            request_timeout_millis=30_000,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(RUNNER, "_run") as run:
                RUNNER._run_phase(
                    "initial",
                    options,
                    "proof-a",
                    root / "topology.json",
                    root / "baseline.json",
                    root / "summary.json",
                    root / "timeline.jsonl",
                    {},
                )

        command = run.call_args.args[0]
        self.assertIn("--prepared-workers=15000", command)
        self.assertIn("--retained-workers=10000", command)
        self.assertIn("--workload-items-per-task=5000", command)
        self.assertIn("--topology-file=" + str(root / "topology.json"), command)
        self.assertFalse(
            any(value.startswith("--workers=") for value in command)
        )

    def test_resource_contract_covers_both_processes(self):
        RUNNER._validate_resource_contract({
            "worker-host": {
                "maximumNativeThreads": 511,
                "maximumOpenFileDescriptors": 32_767,
                "averageCpuCores": 2.0,
            },
            "runtime-server-initial": {
                "maximumNativeThreads": 511,
                "maximumOpenFileDescriptors": 16_383,
                "averageCpuCores": 2.0,
            },
            "runtime-server-restarted": {
                "maximumNativeThreads": 511,
                "maximumOpenFileDescriptors": 16_383,
                "averageCpuCores": 2.0,
            },
        })

        for owner, field, value in (
            ("worker-host", "maximumNativeThreads", 512),
            ("worker-host", "maximumOpenFileDescriptors", 32_768),
            (
                "runtime-server-initial",
                "maximumOpenFileDescriptors",
                16_384,
            ),
        ):
            resources = {
                "worker-host": {
                    "maximumNativeThreads": 100,
                    "maximumOpenFileDescriptors": 1_000,
                    "averageCpuCores": 1.0,
                },
                "runtime-server-initial": {
                    "maximumNativeThreads": 100,
                    "maximumOpenFileDescriptors": 1_000,
                    "averageCpuCores": 1.0,
                },
                "runtime-server-restarted": {
                    "maximumNativeThreads": 100,
                    "maximumOpenFileDescriptors": 1_000,
                    "averageCpuCores": 1.0,
                },
            }
            resources[owner][field] = value
            with self.assertRaises(RuntimeError):
                RUNNER._validate_resource_contract(resources)

    def test_phase_summary_requires_ten_exports_and_reconvergence(self):
        options = SimpleNamespace(
            prepared_workers=15_000,
            retained_workers=10_000,
            workload_items_per_task=5_000,
            minimum_initial_converged=14_800,
            minimum_retained_converged=9_900,
        )
        summary = {
            "phase": "initial",
            "preparedIdentities": 15_000,
            "retainedIdentities": 10_000,
            "stoppedIdentities": 5_000,
            "activeTaskCount": 10,
            "maximumCandidateWorkersPerTask": 100,
            "offeredItemsPerTask": 5_000,
            "totalOfferedItems": 50_000,
            "appendBatchCount": 500,
            "succeededItemCount": 50_000,
            "tasks": [
                {"succeededCount": 5_000, "exported": True}
                for _ in range(10)
            ],
            "minimumConnectedDuringWork": 9_900,
            "retainedConnectedAndHotWorkers": 9_900,
            "stoppedConnectedWorkers": 0,
            "stoppedHotWorkers": 0,
            "initialHeadroomConnectedAndHot": 14_800,
            "postWorkConnectedAndHot": 9_900,
            "postWorkStoppedConnected": 0,
            "postWorkStoppedHot": 0,
            "batchStopRequestCount": 50,
        }
        RUNNER._validate_phase_summary(summary, options)

        summary["tasks"][9]["succeededCount"] = 4_999
        with self.assertRaises(RuntimeError):
            RUNNER._validate_phase_summary(summary, options)

    def test_cpu_interval_is_expressed_as_average_cores(self):
        self.assertEqual(2.5, RUNNER._interval_cpu_cores(2.0, 5_000))
        self.assertIsNone(RUNNER._interval_cpu_cores(0.0, 10))
        self.assertIsNone(RUNNER._interval_cpu_cores(1.0, -1))


if __name__ == "__main__":
    unittest.main()
