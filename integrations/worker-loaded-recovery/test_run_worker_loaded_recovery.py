import importlib.util
import json
import os
from pathlib import Path
import signal
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


MODULE_PATH = Path(__file__).with_name("run_worker_loaded_recovery.py")
SPEC = importlib.util.spec_from_file_location("worker_loaded_recovery_runner", MODULE_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)


class WorkerLoadedRecoveryRunnerTest(unittest.TestCase):

    def test_inventory_uses_at_most_one_hundred_strict_records_per_file(self):
        with tempfile.TemporaryDirectory() as directory:
            sandbox = Path(directory) / "scenario-workers"
            RUNNER._generate_inventory(sandbox, 205)

            files = sorted((sandbox / RUNNER.WORKER_GROUP).glob("*.jsonl"))
            self.assertEqual(
                ["workers-000.jsonl", "workers-001.jsonl", "workers-002.jsonl"],
                [path.name for path in files],
            )
            self.assertEqual(
                [100, 100, 5],
                [len(path.read_text().splitlines()) for path in files],
            )
            observed = 0
            for path in files:
                for line_number, line in enumerate(path.read_text().splitlines(), 1):
                    value = json.loads(line)
                    self.assertEqual(2, value["schemaVersion"])
                    properties = value["workerProperties"]
                    self.assertEqual(path.name, properties["labInventoryKey"])
                    self.assertEqual(line_number, properties["labInventoryLine"])
                    self.assertEqual("loaded-recovery-ci", properties["region"])
                    self.assertEqual(
                        {
                            "runtime",
                            "capability",
                            "region",
                            "labInventoryKey",
                            "labInventoryLine",
                        },
                        set(properties),
                    )
                    observed += 1
            self.assertEqual(205, observed)

    def test_fifteen_thousand_workers_use_one_hundred_fifty_files(self):
        with tempfile.TemporaryDirectory() as directory:
            sandbox = Path(directory) / "scenario-workers"
            coordinates = RUNNER._generate_inventory(sandbox, 15_000)

            files = sorted((sandbox / RUNNER.WORKER_GROUP).glob("*.jsonl"))
            self.assertEqual(150, len(files))
            self.assertTrue(
                all(len(path.read_text().splitlines()) == 100 for path in files)
            )
            self.assertEqual("workers-149.jsonl:100", coordinates[-1])

            topology = RUNNER._partition_topology(coordinates, 10_000)
            self.assertEqual(10_000, len(topology["retainedLabWorkerKeys"]))
            self.assertEqual(5_000, len(topology["stoppedLabWorkerKeys"]))
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

    def test_fixed_stage_order_has_one_graceful_and_two_hard_restarts(self):
        self.assertEqual(
            (
                "initial-contraction",
                "graceful-restart",
                "hard-restart-1",
                "hard-restart-2",
            ),
            RUNNER.STAGES,
        )
        self.assertEqual(
            [signal.SIGTERM, RUNNER.HARD_KILL_SIGNAL, RUNNER.HARD_KILL_SIGNAL],
            [entry[2] for entry in RUNNER.RESTART_STAGES],
        )

    def test_each_stage_receives_the_fixed_loaded_recovery_workload_and_gate(self):
        options = _options()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            process = object()
            with patch.object(RUNNER, "_start_process", return_value=process) as start:
                observed, log = RUNNER._start_stage(
                    "hard-restart-1",
                    options,
                    "proof-a",
                    root / "topology.json",
                    root / "baseline.json",
                    root / "gate",
                    root / "summary.json",
                    root / "timeline.jsonl",
                    {},
                    root,
                )

        self.assertIs(process, observed)
        self.assertEqual(root / "loaded-recovery-harness-hard-restart-1.log", log)
        command = start.call_args.args[0]
        self.assertIn("--stage=hard-restart-1", command)
        self.assertIn("--prepared-workers=15000", command)
        self.assertIn("--retained-workers=10000", command)
        self.assertIn("--workload-items-per-task=5000", command)
        self.assertIn("--gate-directory=" + str(root / "gate"), command)
        self.assertFalse(any(value.startswith("--phase=") for value in command))

    def test_mutation_gate_is_bounded_and_keeps_all_tasks_nonterminal(self):
        options = _options()
        value = {
            "taskCount": 10,
            "succeededItems": 12_500,
            "unresolvedItems": 37_500,
        }
        RUNNER._validate_mutation_ready(value, options)

        value["succeededItems"] = 25_001
        value["unresolvedItems"] = 24_999
        with self.assertRaises(RuntimeError):
            RUNNER._validate_mutation_ready(value, options)

    def test_server_mutation_sends_exactly_one_requested_signal(self):
        process = Mock(pid=1234)
        process.poll.return_value = None
        mutation = {"atEpochMillis": int(time.time() * 1_000)}
        with patch.object(os, "killpg", create=True) as kill:
            RUNNER._terminate_server_for_stage(
                process,
                signal.SIGTERM,
                mutation,
            )

        kill.assert_called_once_with(1234, signal.SIGTERM)
        process.wait.assert_called_once_with(timeout=60)

    def test_resource_contract_covers_stable_checkpoints_and_final_drift(self):
        resources = _resources()
        checkpoints = _checkpoints()
        RUNNER._validate_resource_contract(resources, checkpoints)

        checkpoints["retained-after-hard-restart-2"]["samplesByProcess"][
            "worker-host"
        ][2]["openFileDescriptors"] = 10_129
        with self.assertRaises(RuntimeError):
            RUNNER._validate_resource_contract(resources, checkpoints)

    def test_stage_summary_requires_mutation_window_exports_and_hard_progress(self):
        options = _options()
        initial = _summary("initial-contraction")
        RUNNER._validate_stage_summary(initial, options, "initial-contraction")

        hard = _summary("hard-restart-1")
        hard["batchStopRequestCount"] = 0
        hard["postRecoveryProgress"] = True
        RUNNER._validate_stage_summary(hard, options, "hard-restart-1")

        hard["postRecoveryProgress"] = False
        with self.assertRaises(RuntimeError):
            RUNNER._validate_stage_summary(hard, options, "hard-restart-1")

    def test_identity_digests_must_remain_stable_across_all_four_stages(self):
        summaries = {
            stage: {
                "allWorkerIdSetSha256": "all",
                "retainedWorkerIdSetSha256": "retained",
                "stoppedWorkerIdSetSha256": "stopped",
            }
            for stage in RUNNER.STAGES
        }
        RUNNER._validate_identity_digests(summaries)
        summaries["hard-restart-2"]["retainedWorkerIdSetSha256"] = "changed"
        with self.assertRaises(RuntimeError):
            RUNNER._validate_identity_digests(summaries)

    def test_cpu_interval_is_expressed_as_average_cores(self):
        self.assertEqual(2.5, RUNNER._interval_cpu_cores(2.0, 5_000))
        self.assertIsNone(RUNNER._interval_cpu_cores(0.0, 10))
        self.assertIsNone(RUNNER._interval_cpu_cores(1.0, -1))


def _options():
    return SimpleNamespace(
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


def _sample(*, fds: int, threads: int) -> dict[str, int]:
    return {
        "rssBytes": 1_000_000,
        "nativeThreads": threads,
        "openFileDescriptors": fds,
        "cpuTimeMillis": 1_000,
    }


def _resources() -> dict[str, dict[str, int | float]]:
    result = {
        "worker-host": {
            "maximumNativeThreads": 120,
            "maximumOpenFileDescriptors": 15_000,
            "maximumRssBytes": 1_000_000,
            "averageCpuCores": 2.0,
            "maximumCpuCores": 3.0,
        }
    }
    for owner in RUNNER.MAXIMUM_OPEN_FILE_DESCRIPTORS:
        if owner == "worker-host":
            continue
        result[owner] = {
            "maximumNativeThreads": 240,
            "maximumOpenFileDescriptors": 15_000,
            "maximumRssBytes": 1_000_000,
            "averageCpuCores": 2.0,
            "maximumCpuCores": 3.0,
        }
    return result


def _checkpoint(server_owner: str, fds: int) -> dict[str, object]:
    return {
        "sampleCount": 3,
        "samplesByProcess": {
            "worker-host": [
                _sample(fds=fds, threads=100) for _ in range(3)
            ],
            server_owner: [
                _sample(fds=fds, threads=200) for _ in range(3)
            ],
        },
    }


def _checkpoints() -> dict[str, dict[str, object]]:
    return {
        "initial-headroom": _checkpoint("runtime-server-initial", 15_000),
        "retained-after-initial": _checkpoint("runtime-server-initial", 10_000),
        "retained-after-graceful-restart": _checkpoint(
            "runtime-server-graceful", 10_010
        ),
        "retained-after-hard-restart-1": _checkpoint(
            "runtime-server-hard-1", 10_020
        ),
        "retained-after-hard-restart-2": _checkpoint(
            "runtime-server-hard-2", 10_100
        ),
    }


def _summary(stage: str) -> dict[str, object]:
    value = {
        "status": "passed",
        "stage": stage,
        "preparedIdentities": 15_000,
        "retainedIdentities": 10_000,
        "observedRetainedIdentities": 10_000,
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
        "postWorkConnectedAndHot": 9_900,
        "postWorkStoppedConnected": 0,
        "postWorkStoppedHot": 0,
        "postWorkStoppedMissing": 0,
        "mutationCheckpointTaskCount": 10,
        "mutationCheckpointSucceededItems": 10_000,
        "mutationCheckpointUnresolvedItems": 40_000,
        "recoverySnapshotUnresolvedItems": 30_000,
        "postRecoveryProgress": False,
        "batchStopRequestCount": 50,
    }
    if stage == "initial-contraction":
        value.update({
            "initialHeadroomConnectedAndHot": 14_800,
            "initialHeadroomQualifyingScans": 7,
            "initialHeadroomStableMillis": 60_000,
        })
    return value


if __name__ == "__main__":
    unittest.main()
