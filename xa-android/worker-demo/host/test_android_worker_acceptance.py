from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import android_worker_acceptance as acceptance


WORKER_ID = "6bd3b23c-cbea-4803-a0d2-62e196d24d1d"
OTHER_WORKER_ID = "0ab51c74-f16c-4ad8-8e68-cedea1b121c2"


class FakeClock:
    def __init__(self) -> None:
        self.value = 0.0

    def monotonic(self) -> float:
        return self.value

    def sleep(self, seconds: float) -> None:
        self.value += seconds


class FakeDevice:
    def __init__(
        self,
        worker_id: str = WORKER_ID,
        state: str = "RUNNING",
    ) -> None:
        self.worker_id = worker_id
        self.state = state
        self.processed_commands = 0
        self.last_event: str | None = None
        self.events_value = sorted(acceptance.REQUIRED_DEVICE_EVENTS)
        self.runtime: FakeRuntime | None = None
        self.health_failure = False
        self.snapshots_before_identity = 0
        self.connect_on_health = False
        self.expose_identity_when_stopped = False

    def health(self) -> None:
        if self.health_failure:
            raise acceptance.ProofFailure("health", "not ready")
        if self.connect_on_health and self.runtime is not None:
            self.runtime.network_value = "connected"
            self.connect_on_health = False

    def events(self) -> list[str]:
        return list(self.events_value)

    def snapshot(self) -> acceptance.WorkerSnapshot:
        worker_id = (
            None
            if self.state == "STOPPED"
            and not self.expose_identity_when_stopped
            else self.worker_id
        )
        if self.state != "STOPPED" and self.snapshots_before_identity > 0:
            self.snapshots_before_identity -= 1
            worker_id = None
        return acceptance.WorkerSnapshot(
            state=self.state,
            worker_id=worker_id,
            endpoint_uri="ws://127.0.0.1:18083/worker",
            diagnostic_message=None,
            processed_commands=self.processed_commands,
            last_event=self.last_event,
        )

    def call(self, event_name: str, payload: dict[str, object]):
        if event_name == acceptance.HOST_SNAPSHOT_EVENT:
            snapshot = self.snapshot()
            return {
                "state": snapshot.state,
                "workerId": snapshot.worker_id,
                "endpointUri": snapshot.endpoint_uri,
                "diagnosticMessage": snapshot.diagnostic_message,
                "processedCommands": snapshot.processed_commands,
                "lastEvent": snapshot.last_event,
            }
        if event_name in {
            acceptance.HOST_START_EVENT,
            acceptance.HOST_STOP_EVENT,
        }:
            raise AssertionError("Host lifecycle calls use request_state")
        self.processed_commands += 1
        self.last_event = event_name
        return {"result": "present"}

    def request_state(self, target: str) -> None:
        self.state = target
        if self.runtime is not None:
            self.runtime.network_value = (
                "connected" if target == "RUNNING" else "disconnected"
            )


class FakeRuntime:
    def __init__(self, device: FakeDevice) -> None:
        self.device = device
        self.network_value: str | None = (
            "connected" if device.state == "RUNNING" else None
        )
        self.worker_properties = {"sdkInt": 33, "dynamic": "value"}
        self.adapter_properties = dict(self.worker_properties)
        self.worker_calls: list[str] = []
        self.task_calls: list[str] = []
        device.runtime = self

    def network_state(self, endpoint_manager_id: str, worker_id: str):
        if endpoint_manager_id != acceptance.DEFAULT_ENDPOINT_MANAGER_ID:
            raise AssertionError(endpoint_manager_id)
        if worker_id != self.device.worker_id:
            return None
        return self.network_value

    def call_worker(
        self, endpoint_manager_id: str, worker_id: str, message_type: str
    ) -> str:
        if self.network_value != "connected":
            raise acceptance.ProofFailure("worker.call", "not connected")
        if worker_id != self.device.worker_id:
            raise acceptance.ProofFailure("worker.call", "wrong worker")
        self.worker_calls.append(message_type)
        if message_type == acceptance.WORKER_PROPERTIES_EVENT:
            return json.dumps({"properties": self.worker_properties})
        return "null"

    def call_adapter(
        self,
        endpoint_manager_id: str,
        message_type: str,
        opaque_payload: str,
    ) -> str:
        requested = json.loads(opaque_payload)
        if requested != {"workerIds": [self.device.worker_id]}:
            raise AssertionError(requested)
        if message_type != acceptance.ADAPTER_PROPERTIES_EVENT:
            raise AssertionError(message_type)
        return json.dumps(
            {
                "propertiesByWorkerId": {
                    self.device.worker_id: {
                        "updatedAtMillis": 123,
                        "properties": self.adapter_properties,
                    }
                }
            }
        )

    def task_call(self, event_name: str, payload: dict[str, object]) -> str:
        self.task_calls.append(event_name)
        self.device.processed_commands += 1
        self.device.last_event = event_name
        return f"00000000-0000-4000-8000-{len(self.task_calls):012d}"


class FakeHttpResponse:
    def __init__(self, body: dict[str, object]) -> None:
        self.status = 200
        self._encoded = json.dumps(body).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, error_type, error, traceback) -> None:
        return None

    def read(self) -> bytes:
        return self._encoded


class AndroidWorkerAcceptanceTest(unittest.TestCase):
    def test_initial_closes_local_runtime_and_business_relations(self) -> None:
        device = FakeDevice()
        device.snapshots_before_identity = 1
        runtime = FakeRuntime(device)
        runner = self.runner("initial", device, runtime)

        runner.run()

        self.assertEqual(WORKER_ID, runner.evidence.worker_id)
        self.assertEqual(3, runner.evidence.checks["taskResultCount"])
        self.assertEqual(4, runner.evidence.checks["processedCommandDelta"])
        self.assertTrue(
            runner.evidence.checks["explicitRestartIdentityMatched"]
        )
        self.assertEqual(
            [
                acceptance.WORKER_PROBE_EVENT,
                acceptance.WORKER_PROPERTIES_EVENT,
                acceptance.WORKER_PROBE_EVENT,
            ],
            runtime.worker_calls,
        )
        self.assertEqual(
            [event for event, _ in acceptance.BUSINESS_CALLS],
            runtime.task_calls,
        )

    def test_initial_rejects_missing_device_event(self) -> None:
        device = FakeDevice()
        device.events_value.remove(acceptance.HOST_STOP_EVENT)
        runtime = FakeRuntime(device)

        with self.assertRaisesRegex(
            acceptance.ProofFailure, "fixed Host assembly"
        ):
            self.runner("initial", device, runtime).run()

    def test_initial_rejects_unexpected_device_event(self) -> None:
        device = FakeDevice()
        device.events_value.append("extension.worker.android.unexpected")
        runtime = FakeRuntime(device)

        with self.assertRaisesRegex(
            acceptance.ProofFailure,
            "fixed Host assembly",
        ):
            self.runner("initial", device, runtime).run()

    def test_initial_times_out_when_route_is_not_connected(self) -> None:
        device = FakeDevice()
        runtime = FakeRuntime(device)
        runtime.network_value = None

        with self.assertRaisesRegex(
            acceptance.ProofFailure,
            "did not become connected",
        ):
            self.runner(
                "initial",
                device,
                runtime,
                maximum_wait_millis=100,
            ).run()

    def test_initial_rejects_properties_mismatch(self) -> None:
        device = FakeDevice()
        runtime = FakeRuntime(device)
        runtime.adapter_properties = {"sdkInt": 32}

        with self.assertRaisesRegex(
            acceptance.ProofFailure, "does not match"
        ):
            self.runner("initial", device, runtime).run()

    def test_terminal_accepts_stopped_snapshot_without_active_identity(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            baseline = self.baseline(Path(directory))
            device = FakeDevice(state="STOPPED")
            runtime = FakeRuntime(device)
            runner = self.runner(
                "terminal", device, runtime, baseline_file=baseline
            )

            runner.run()

            self.assertIsNone(device.snapshot().worker_id)
            self.assertEqual(WORKER_ID, runner.evidence.worker_id)
            self.assertTrue(runner.evidence.baseline_identity_matched)
            self.assertEqual(
                "STOPPED", runner.evidence.checks["endpointTerminalState"]
            )

    def test_terminal_rejects_conflicting_stopped_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            baseline = self.baseline(Path(directory))
            device = FakeDevice(worker_id=OTHER_WORKER_ID, state="STOPPED")
            device.expose_identity_when_stopped = True
            runtime = FakeRuntime(device)

            with self.assertRaisesRegex(
                acceptance.ProofFailure,
                "conflicting identity",
            ):
                self.runner(
                    "terminal",
                    device,
                    runtime,
                    baseline_file=baseline,
                ).run()

    def test_server_restart_observes_stopped_before_explicit_start(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            baseline = self.baseline(Path(directory))
            device = FakeDevice(state="STOPPED")
            runtime = FakeRuntime(device)
            runner = self.runner(
                "server-restart", device, runtime, baseline_file=baseline
            )

            runner.run()

            self.assertEqual(WORKER_ID, runner.evidence.worker_id)
            self.assertGreaterEqual(
                runner.evidence.checks["noAutomaticStartSamples"], 29
            )
            self.assertEqual("connected", runtime.network_value)
            self.assertTrue(runner.evidence.baseline_identity_matched)

    def test_server_restart_rejects_automatic_running_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            baseline = self.baseline(Path(directory))
            device = FakeDevice(state="RUNNING")
            runtime = FakeRuntime(device)

            with self.assertRaisesRegex(
                acceptance.ProofFailure, "started automatically"
            ):
                self.runner(
                    "server-restart",
                    device,
                    runtime,
                    baseline_file=baseline,
                ).run()

    def test_process_restart_rejects_replaced_worker_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            baseline = self.baseline(Path(directory))
            device = FakeDevice(worker_id=OTHER_WORKER_ID)
            runtime = FakeRuntime(device)
            runtime.network_value = "disconnected"

            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(acceptance.ProofFailure):
                    self.runner(
                        "process-restart",
                        device,
                        runtime,
                        baseline_file=baseline,
                        maximum_wait_millis=100,
                    ).run()

    def test_process_restart_observes_old_route_before_new_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            baseline = self.baseline(Path(directory))
            device = FakeDevice()
            runtime = FakeRuntime(device)
            runtime.network_value = "disconnected"
            device.connect_on_health = True
            output = io.StringIO()
            runner = self.runner(
                "process-restart",
                device,
                runtime,
                baseline_file=baseline,
            )

            with contextlib.redirect_stdout(output):
                runner.run()

            self.assertEqual(
                acceptance.PROCESS_STOP_OBSERVED_MARKER,
                output.getvalue().strip(),
            )
            self.assertTrue(
                runner.evidence.checks["processStopDisconnected"]
            )
            self.assertEqual(
                [acceptance.STRING_DIGEST_EVENT],
                runtime.task_calls,
            )

    def test_execute_writes_safe_partial_evidence_before_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            options = self.options(
                "initial", evidence_file=root / "initial.json"
            )
            device = FakeDevice()
            device.events_value = []
            runtime = FakeRuntime(device)
            with patch.object(
                acceptance,
                "DeviceApiClient",
                return_value=device,
            ), patch.object(
                acceptance,
                "RuntimeApiClient",
                return_value=runtime,
            ):
                with self.assertRaises(acceptance.ProofFailure):
                    acceptance.execute(options)

            evidence = json.loads(options.evidence_file.read_text())
            self.assertEqual("failed", evidence["status"])
            self.assertEqual("initial", evidence["phase"])
            self.assertEqual([], evidence["checks"].get("taskPayloads", []))
            encoded = options.evidence_file.read_text()
            self.assertNotIn("sdkInt", encoded)
            self.assertNotIn("opaqueResultPayload", encoded)
            self.assertNotIn("updatedAtMillis", encoded)

    def test_parse_options_requires_baseline_for_later_phase(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                acceptance.parse_options(
                    [
                        "--phase=terminal",
                        "--proof-id=proof",
                        "--evidence-file=evidence.json",
                    ]
                )

    def test_runtime_client_uses_public_network_view_contract(self) -> None:
        response = FakeHttpResponse(
            {"statesByWorkerId": {WORKER_ID: "connected"}}
        )
        with patch.object(
            acceptance,
            "urlopen",
            return_value=response,
        ) as urlopen:
            client = acceptance.RuntimeApiClient(
                acceptance.DEFAULT_SERVER_BASE_URL,
                1_000,
            )

            state = client.network_state(
                acceptance.DEFAULT_ENDPOINT_MANAGER_ID,
                WORKER_ID,
            )

        self.assertEqual("connected", state)
        request = urlopen.call_args.args[0]
        self.assertEqual(
            acceptance.DEFAULT_SERVER_BASE_URL
            + "/api/v1/runtime-view/endpoint-managers/"
            "scenario-websocket/workers:network-observe",
            request.full_url,
        )
        self.assertEqual(
            {"workerIds": [WORKER_ID]},
            json.loads(request.data),
        )

    def test_runtime_client_rejects_uncorrelated_task_response(self) -> None:
        configured = FakeHttpResponse(
            {
                "entries": [{
                    "taskId": "managed-task",
                    "scoreBand": "running_visible",
                    "task": {
                        "taskId": "managed-task",
                        "workerGroupId": acceptance.WORKER_GROUP_ID,
                        "workerAllocationMechanism": "ON_DEMAND_ITEM_RULE",
                        "idleDisposition": "PARK_WHEN_IDLE",
                    },
                    "workerGroup": {
                        "workerGroupId": acceptance.WORKER_GROUP_ID,
                    },
                }]
            }
        )
        call = FakeHttpResponse(
            {
                "results": {
                    OTHER_WORKER_ID: {
                        "status": "succeeded",
                        "opaqueResultPayload": "{}",
                    }
                },
            }
        )
        with patch.object(
            acceptance,
            "urlopen",
            side_effect=[configured, call],
        ) as urlopen:
            client = acceptance.RuntimeApiClient(
                acceptance.DEFAULT_SERVER_BASE_URL,
                1_000,
            )

            with self.assertRaisesRegex(
                acceptance.ProofFailure,
                "Task Call result",
            ):
                client.task_call(acceptance.STRING_DIGEST_EVENT, {})

        requests = [call.args[0] for call in urlopen.call_args_list]
        self.assertEqual(
            acceptance.DEFAULT_SERVER_BASE_URL
            + "/api/v1/runtime-view/tasks:preview",
            requests[0].full_url,
        )
        self.assertEqual("POST", requests[0].method)
        self.assertEqual(
            {"sampleLimit": 100},
            json.loads(requests[0].data),
        )
        self.assertEqual(
            acceptance.DEFAULT_SERVER_BASE_URL
            + "/api/v1/tasks/managed-task/items:call",
            requests[1].full_url,
        )

    def test_driver_has_no_repository_implementation_imports(self) -> None:
        source = Path(acceptance.__file__).read_text()
        for forbidden in (
            "import server_jvm",
            "import kernel_jvm",
            "import netty_adapter",
            "import com.xa.mass",
            "from com.xa.mass",
        ):
            self.assertNotIn(forbidden, source)

    def runner(
        self,
        phase: str,
        device: FakeDevice,
        runtime: FakeRuntime,
        *,
        baseline_file: Path | None = None,
        maximum_wait_millis: int = 30_000,
    ) -> acceptance.AndroidWorkerAcceptance:
        clock = FakeClock()
        return acceptance.AndroidWorkerAcceptance(
            self.options(
                phase,
                baseline_file=baseline_file,
                maximum_wait_millis=maximum_wait_millis,
            ),
            device,
            runtime,
            monotonic=clock.monotonic,
            sleep=clock.sleep,
        )

    def options(
        self,
        phase: str,
        *,
        evidence_file: Path = Path("evidence.json"),
        baseline_file: Path | None = None,
        maximum_wait_millis: int = 30_000,
    ) -> acceptance.Options:
        return acceptance.Options(
            phase=phase,
            proof_id="android-ci-proof",
            server_base_url=acceptance.DEFAULT_SERVER_BASE_URL,
            device_base_url=acceptance.DEFAULT_DEVICE_BASE_URL,
            endpoint_manager_id=acceptance.DEFAULT_ENDPOINT_MANAGER_ID,
            evidence_file=evidence_file,
            baseline_file=baseline_file,
            maximum_wait_millis=maximum_wait_millis,
            request_timeout_millis=120_000,
            android_api_level=33,
        )

    def baseline(self, root: Path) -> Path:
        path = root / "initial.json"
        path.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "proofId": "android-ci-proof",
                    "phase": "initial",
                    "status": "succeeded",
                    "applicationId": acceptance.APPLICATION_ID,
                    "workerGroupId": acceptance.WORKER_GROUP_ID,
                    "endpointManagerId": acceptance.DEFAULT_ENDPOINT_MANAGER_ID,
                    "workerId": WORKER_ID,
                }
            )
        )
        return path


if __name__ == "__main__":
    unittest.main()
