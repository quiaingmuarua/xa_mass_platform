import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
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
                    self.assertEqual(str(line_number), properties["labInventoryLine"])
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

    def test_configured_group_keeps_only_the_existing_md5_capability(self):
        assembly = RUNNER._worker_groups(15000)
        self.assertEqual([RUNNER.WORKER_GROUP], list(assembly))
        group = assembly[RUNNER.WORKER_GROUP]
        self.assertEqual(["extension.worker.string.md5"], group["events"])
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
                Mock(),
            )

        kill.assert_called_once_with(1234, signal.SIGTERM)
        process.wait.assert_called_once_with(timeout=1.0)

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

    def test_checkpoint_samples_cannot_replace_periodic_resource_evidence(self):
        resources = _resources()
        resources["runtime-server-hard-1"]["periodicSampleCount"] = 0
        with self.assertRaisesRegex(RuntimeError, "periodic resource evidence is missing"):
            RUNNER._validate_resource_contract(resources, _checkpoints())


class ProcessSamplerTest(unittest.TestCase):

    def sampler(self, root):
        sampler = RUNNER._ProcessSampler(
            root / "resources.jsonl", interval_seconds=0.01, maximum_gap_seconds=1,
        )
        self.addCleanup(sampler.close)
        sampler.start()
        return sampler

    def wait_until(self, predicate):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.005)
        self.fail("sampler did not reach the expected state")

    def live_process(self, pid=123):
        process = Mock(pid=pid)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("still-alive", 1)
        return process

    def test_background_read_failure_reaches_stage_and_cannot_be_healed(self):
        for error in (PermissionError("fd access denied"), FileNotFoundError("status missing"), ValueError("invalid stat")):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with patch.object(RUNNER, "_process_sample", side_effect=error):
                    sampler = self.sampler(root)
                    sampler.register("worker-host", self.live_process())
                    self.wait_until(lambda: sampler.diagnostics()["failure"] is not None)
                with patch.object(RUNNER, "_process_sample", return_value=_sample(fds=10, threads=2)) as read:
                    stage = Mock()
                    stage.wait.return_value = 0
                    with self.assertRaises(RUNNER._ResourceSamplingError) as failure:
                        RUNNER._wait_stage(stage, root / "stage.log", _options(), sampler)
                    stage.wait.assert_not_called()
                    with self.assertRaises(RUNNER._ResourceSamplingError):
                        sampler.checkpoint("later", ("worker-host",))
                    sampler.close()
                    with self.assertRaises(RUNNER._ResourceSamplingError):
                        sampler.summary()
                    read.assert_not_called()
                RUNNER._write_failure_summary(root, "proof", _options(), failure.exception, sampler)
                summary = json.loads((root / "worker-loaded-recovery-summary.json").read_text())
                self.assertEqual("failed", summary["status"])
                self.assertEqual("resource-sampling-failed", summary["failureKind"])
                self.assertEqual("incomplete", summary["resourceSampling"]["status"])
                self.assertEqual(type(error).__name__, summary["resourceSampling"]["failure"]["type"])
                self.assertNotIn("maximumWorkerHostNativeThreads", summary)

    def test_confirmed_exit_race_keeps_sampling_other_processes(self):
        with tempfile.TemporaryDirectory() as directory:
            exiting = self.live_process(123)
            def finish_exit(**_):
                exiting.poll.return_value = 137
                return 137
            exiting.wait.side_effect = finish_exit
            def sample(pid):
                if pid == 123:
                    raise PermissionError("exiting process fd")
                return _sample(fds=10, threads=2)
            with patch.object(RUNNER, "_process_sample", side_effect=sample):
                sampler = self.sampler(Path(directory))
                sampler.register("runtime-server-graceful", exiting)
                sampler.register("worker-host", self.live_process(456))
                self.wait_until(lambda: sampler.diagnostics()["periodicCycles"] >= 3)
                sampler.close()
                self.assertGreaterEqual(sampler.summary()["worker-host"]["periodicSampleCount"], 3)
                diagnostics = sampler.diagnostics()
                self.assertEqual("complete", diagnostics["status"])
                self.assertIsNone(diagnostics["failure"])
                self.assertEqual(1, len(diagnostics["confirmedExitsDuringSample"]))
                self.assertEqual(123, diagnostics["confirmedExitsDuringSample"][0]["pid"])

    def test_background_writer_failure_is_not_hidden_by_collected_maxima(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(RUNNER, "_process_sample", return_value=_sample(fds=10, threads=2)), \
             patch.object(RUNNER, "_append_jsonl", side_effect=OSError("disk full")):
            sampler = self.sampler(Path(directory))
            sampler.register("worker-host", self.live_process())
            self.wait_until(lambda: sampler.diagnostics()["failure"] is not None)
            sampler.close()
            self.assertEqual("OSError", sampler.diagnostics()["failure"]["type"])
            with self.assertRaises(RUNNER._ResourceSamplingError):
                sampler.summary()

    def test_stalled_writer_fails_the_main_wait_and_checkpoint_without_hanging(self):
        blocked, release = threading.Event(), threading.Event()
        def stalled_write(*_):
            blocked.set()
            release.wait(3)
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(RUNNER, "_process_sample", return_value=_sample(fds=10, threads=2)), \
             patch.object(RUNNER, "_append_jsonl", side_effect=stalled_write):
            sampler = RUNNER._ProcessSampler(Path(directory) / "resources.jsonl", interval_seconds=0.01, maximum_gap_seconds=0.1)
            sampler.start()
            try:
                sampler.register("worker-host", self.live_process())
                self.assertTrue(blocked.wait(1))
                started = time.monotonic()
                with self.assertRaisesRegex(RUNNER._ResourceSamplingError, "periodic sample gap"):
                    sampler.checkpoint("tail", ("worker-host",))
                self.assertLess(time.monotonic() - started, 1)
            finally:
                release.set()
                sampler.close()
            self.assertEqual("incomplete", sampler.diagnostics()["status"])

    def test_close_joins_a_late_sampler_failure_before_summary(self):
        blocked, release = threading.Event(), threading.Event()
        def failing_write(*_):
            blocked.set()
            release.wait(3)
            raise OSError("last write failed")
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(RUNNER, "_process_sample", return_value=_sample(fds=10, threads=2)), \
             patch.object(RUNNER, "_append_jsonl", side_effect=failing_write):
            sampler = self.sampler(Path(directory))
            sampler.register("worker-host", self.live_process())
            self.assertTrue(blocked.wait(1))
            release.set()
            sampler.close()
            with self.assertRaises(RUNNER._ResourceSamplingError):
                sampler.summary()

    def test_completed_slow_cycle_cannot_erase_a_sampling_gap(self):
        def slow_write(*_):
            time.sleep(0.08)
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(RUNNER, "_process_sample", return_value=_sample(fds=10, threads=2)), \
             patch.object(RUNNER, "_append_jsonl", side_effect=slow_write):
            sampler = RUNNER._ProcessSampler(Path(directory) / "resources.jsonl", interval_seconds=0.01, maximum_gap_seconds=0.05)
            sampler.start()
            try:
                sampler.register("worker-host", self.live_process())
                # Do not call check() while the slow sample runs. The completed
                # cycle itself must remember the gap before updating its heartbeat.
                self.wait_until(lambda: sampler.diagnostics()["failure"] is not None)
                self.assertGreater(sampler.diagnostics()["maximumPeriodicGapMillis"], 50)
            finally:
                sampler.close()
            with self.assertRaises(RUNNER._ResourceSamplingError):
                sampler.summary()

    @unittest.skipUnless(sys.platform.startswith("linux") and os.geteuid() != 0, "requires unprivileged Linux /proc")
    def test_real_proc_permission_loss_interrupts_a_running_child_wait(self):
        script = (
            "import ctypes,sys,time; print('ready',flush=True); sys.stdin.readline(); "
            "assert ctypes.CDLL(None).prctl(4,0,0,0,0)==0; "
            "print('restricted',flush=True); time.sleep(30)"
        )
        with tempfile.TemporaryDirectory() as directory, subprocess.Popen(
            [sys.executable, "-c", script], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
        ) as process:
            sampler = RUNNER._ProcessSampler(Path(directory) / "resources.jsonl", interval_seconds=0.05, maximum_gap_seconds=3)
            sampler.start()
            try:
                self.assertEqual("ready\n", process.stdout.readline())
                sampler.register("worker-host", process)
                self.wait_until(lambda: sampler.diagnostics()["periodicCycles"] >= 2)
                process.stdin.write("restrict\n")
                process.stdin.flush()
                self.assertEqual("restricted\n", process.stdout.readline())
                started = time.monotonic()
                with self.assertRaises(RUNNER._ResourceSamplingError) as failure:
                    RUNNER._wait_stage(process, Path(directory) / "stage.log", _options(), sampler)
                self.assertLess(time.monotonic() - started, 5)
                self.assertIsNone(process.poll())
                self.assertEqual("PermissionError", sampler.diagnostics()["failure"]["type"])
                RUNNER._write_failure_summary(Path(directory), "linux-proc", _options(), failure.exception, sampler)
                self.assertEqual("failed", json.loads((Path(directory) / "worker-loaded-recovery-summary.json").read_text())["status"])
            finally:
                sampler.close()
                process.terminate()
                process.wait(timeout=3)


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
            "periodicSampleCount": 20,
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
            "periodicSampleCount": 10,
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
