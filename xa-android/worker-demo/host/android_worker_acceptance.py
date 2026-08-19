from __future__ import annotations

import argparse
import json
import os
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


APPLICATION_ID = "com.xa.mass.integration.androidworker"
WORKER_GROUP_ID = "android-demo-workers"
DEFAULT_ENDPOINT_MANAGER_ID = "scenario-websocket"
DEFAULT_DEVICE_BASE_URL = "http://127.0.0.1:18084"
DEFAULT_SERVER_BASE_URL = "http://127.0.0.1:18082"

HOST_SNAPSHOT_EVENT = "extension.worker.android-demo.host.snapshot"
HOST_START_EVENT = "extension.worker.android-demo.host.start"
HOST_STOP_EVENT = "extension.worker.android-demo.host.stop"
STATE_READ_EVENT = "extension.worker.android.state.read"
BATTERY_READ_EVENT = "extension.worker.android.battery.read"
STRING_DIGEST_EVENT = "extension.worker.android.string.digest"
BUSINESS_CALLS: tuple[tuple[str, dict[str, Any]], ...] = (
    (STATE_READ_EVENT, {}),
    (BATTERY_READ_EVENT, {}),
    (STRING_DIGEST_EVENT, {"algorithm": "MD5", "value": "hello"}),
)
REQUIRED_DEVICE_EVENTS = frozenset(
    {
        HOST_SNAPSHOT_EVENT,
        HOST_START_EVENT,
        HOST_STOP_EVENT,
        *(event_name for event_name, _ in BUSINESS_CALLS),
    }
)
WORKER_PROBE_EVENT = "platform.worker.probe"
WORKER_PROPERTIES_EVENT = "platform.worker.properties.snapshot"
ADAPTER_PROPERTIES_EVENT = "platform.adapter.worker-properties.snapshot"
PROCESS_STOP_OBSERVED_MARKER = "android-worker-process-stop-observed"


class ProofFailure(RuntimeError):
    def __init__(
        self,
        invariant: str,
        message: str,
        *,
        missing_ids: tuple[str, ...] = (),
        unexpected_ids: tuple[str, ...] = (),
        inconsistent_ids: tuple[str, ...] = (),
    ) -> None:
        super().__init__(message)
        self.invariant = invariant
        self.safe_message = message
        self.missing_ids = missing_ids
        self.unexpected_ids = unexpected_ids
        self.inconsistent_ids = inconsistent_ids


@dataclass(frozen=True)
class Options:
    phase: str
    proof_id: str
    server_base_url: str
    device_base_url: str
    endpoint_manager_id: str
    evidence_file: Path
    baseline_file: Path | None
    maximum_wait_millis: int
    request_timeout_millis: int
    android_api_level: int


@dataclass(frozen=True)
class WorkerSnapshot:
    state: str
    worker_id: str | None
    endpoint_uri: str | None
    diagnostic_message: str | None
    processed_commands: int
    last_event: str | None


class JsonHttpClient:
    def __init__(self, base_url: str, timeout_millis: int) -> None:
        parsed = urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("base URL must be absolute HTTP(S)")
        if timeout_millis <= 0:
            raise ValueError("request timeout must be positive")
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_millis / 1_000

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None,
        operation: str,
    ) -> dict[str, Any]:
        encoded = None
        headers: dict[str, str] = {}
        if body is not None:
            encoded = json.dumps(
                body,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(
            self._base_url + path,
            data=encoded,
            headers=headers,
            method=method,
        )
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:
                status_code = response.status
                raw_body = response.read().decode("utf-8")
        except HTTPError as error:
            raise ProofFailure(
                f"{operation}.http",
                f"{operation} returned an unexpected HTTP status",
            ) from error
        except (URLError, TimeoutError, OSError) as error:
            raise ProofFailure(
                f"{operation}.request",
                f"{operation} request failed",
            ) from error
        if status_code != 200:
            raise ProofFailure(
                f"{operation}.http",
                f"{operation} returned an unexpected HTTP status",
            )
        try:
            decoded = json.loads(raw_body)
        except json.JSONDecodeError as error:
            raise ProofFailure(
                f"{operation}.json",
                f"{operation} returned invalid JSON",
            ) from error
        if not isinstance(decoded, dict):
            raise ProofFailure(
                f"{operation}.shape",
                f"{operation} returned a non-object response",
            )
        return decoded


class DeviceApiClient:
    def __init__(self, base_url: str, timeout_millis: int) -> None:
        self._http = JsonHttpClient(base_url, timeout_millis)

    def health(self) -> None:
        response = self._http.request(
            "GET", "/health", None, "androidDevice.health"
        )
        if response.get("status") != "ok":
            raise ProofFailure(
                "device.health",
                "Android device HTTP health is not ok",
            )

    def events(self) -> list[str]:
        response = self._http.request(
            "GET", "/events", None, "androidDevice.events"
        )
        raw_events = response.get("events")
        if not isinstance(raw_events, list) or not all(
            isinstance(value, str) for value in raw_events
        ):
            raise ProofFailure(
                "device.events.shape",
                "Android device events response is invalid",
            )
        return list(raw_events)

    def call(self, event_name: str, payload: dict[str, Any]) -> Any:
        response = self._http.request(
            "POST",
            f"/events/{quote(event_name, safe='.')}:call",
            payload,
            f"androidDevice.event[{event_name}]",
        )
        if (
            response.get("status") != "succeeded"
            or response.get("eventCode") != event_name
            or response.get("outcomeCode") != "200"
            or "result" not in response
        ):
            raise ProofFailure(
                "device.event.outcome",
                "Android device event did not succeed",
            )
        return response["result"]

    def snapshot(self) -> WorkerSnapshot:
        result = self.call(HOST_SNAPSHOT_EVENT, {})
        data = require_object(result, "Android Host snapshot")
        state = data.get("state")
        worker_id = optional_string(data.get("workerId"))
        endpoint_uri = optional_string(data.get("endpointUri"))
        diagnostic = optional_string(data.get("diagnosticMessage"))
        processed = data.get("processedCommands")
        last_event = optional_string(data.get("lastEvent"))
        if state not in {"STOPPED", "RUNNING"}:
            raise ProofFailure(
                "device.snapshot.state",
                "Android Host snapshot state is invalid",
            )
        if (
            not isinstance(processed, int)
            or isinstance(processed, bool)
            or processed < 0
        ):
            raise ProofFailure(
                "device.snapshot.processed-count",
                "Android Host processed command count is invalid",
            )
        return WorkerSnapshot(
            state=state,
            worker_id=worker_id,
            endpoint_uri=endpoint_uri,
            diagnostic_message=diagnostic,
            processed_commands=processed,
            last_event=last_event,
        )

    def request_state(self, target: str) -> None:
        event_name = HOST_START_EVENT if target == "RUNNING" else HOST_STOP_EVENT
        result = require_object(
            self.call(event_name, {}),
            "Android Host lifecycle result",
        )
        if (
            result.get("accepted") is not True
            or result.get("requestedState") != target
        ):
            raise ProofFailure(
                "device.lifecycle.accepted",
                "Android Host lifecycle request was not accepted",
            )


class RuntimeApiClient:
    def __init__(self, base_url: str, timeout_millis: int) -> None:
        self._http = JsonHttpClient(base_url, timeout_millis)

    def network_state(self, endpoint_manager_id: str, worker_id: str) -> str | None:
        response = self._http.request(
            "POST",
            "/api/v1/runtime-view/endpoint-managers/"
            f"{quote(endpoint_manager_id, safe='')}"
            "/workers:network-observe",
            {"workerIds": [worker_id]},
            "workerNetwork.observe",
        )
        states = require_object(
            response.get("statesByWorkerId"),
            "Worker Network states",
        )
        unexpected = sorted(set(states) - {worker_id})
        if unexpected:
            raise ProofFailure(
                "network.observed-identities",
                "Worker Network returned unexpected identities",
                unexpected_ids=tuple(unexpected),
            )
        value = states.get(worker_id)
        if value is not None and not isinstance(value, str):
            raise ProofFailure(
                "network.state.shape",
                "Worker Network state is invalid",
            )
        return value

    def call_worker(
        self,
        endpoint_manager_id: str,
        worker_id: str,
        message_type: str,
    ) -> str:
        response = self._direct_call(
            endpoint_manager_id,
            {
                "workerGroupId": WORKER_GROUP_ID,
                "workerPayloads": {worker_id: "null"},
                "messageType": message_type,
                "waitTimeoutMillis": 10_000,
            },
        )
        results = require_object(response.get("results"), "Direct Call results")
        if set(results) != {worker_id}:
            raise ProofFailure(
                "direct-call.worker-identities",
                "Worker Direct Call result identities do not match",
                missing_ids=(() if worker_id in results else (worker_id,)),
                unexpected_ids=tuple(sorted(set(results) - {worker_id})),
            )
        target = require_object(results[worker_id], "Worker Direct Call result")
        payload = target.get("opaqueResultPayload")
        if (
            response.get("status") != "observed"
            or target.get("status") != "observed"
            or target.get("outcomeCode") != "200"
            or not isinstance(payload, str)
        ):
            raise ProofFailure(
                "direct-call.worker-outcome",
                "Worker Direct Call was not observed successfully",
                inconsistent_ids=(worker_id,),
            )
        return payload

    def call_adapter(
        self,
        endpoint_manager_id: str,
        message_type: str,
        opaque_payload: str,
    ) -> str:
        response = self._direct_call(
            endpoint_manager_id,
            {
                "messageType": message_type,
                "opaquePayload": opaque_payload,
                "waitTimeoutMillis": 10_000,
            },
        )
        results = require_object(response.get("results"), "Direct Call results")
        if set(results) != {endpoint_manager_id}:
            raise ProofFailure(
                "direct-call.adapter-identities",
                "Adapter Direct Call result identities do not match",
            )
        target = require_object(
            results[endpoint_manager_id],
            "Adapter Direct Call result",
        )
        payload = target.get("opaqueResultPayload")
        if (
            response.get("status") != "observed"
            or target.get("status") != "observed"
            or target.get("outcomeCode") != "200"
            or not isinstance(payload, str)
        ):
            raise ProofFailure(
                "direct-call.adapter-outcome",
                "Adapter Direct Call was not observed successfully",
            )
        return payload

    def task_call(self, event_name: str, payload: dict[str, Any]) -> str:
        message_id = str(uuid.uuid4())
        response = self._http.request(
            "POST",
            "/api/v1/worker-groups/"
            f"{quote(WORKER_GROUP_ID, safe='')}/items:call",
            {
                "item": {
                    "messageId": message_id,
                    "eventCode": event_name,
                    "createdAtMillis": int(time.time() * 1_000),
                    "payload": dict(payload),
                    "allocationRule": {},
                },
                "waitTimeoutMillis": 30_000,
            },
            f"workerGroupItem.call[{event_name}]",
        )
        encoded_result = response.get("opaqueResultPayload")
        if (
            response.get("status") != "succeeded"
            or response.get("messageId") != message_id
            or not isinstance(encoded_result, str)
        ):
            raise ProofFailure(
                "task-call.outcome",
                "Android WorkerGroup call did not close the requested item",
            )
        try:
            decoded = json.loads(encoded_result)
        except json.JSONDecodeError as error:
            raise ProofFailure(
                "task-call.result-json",
                "Android WorkerGroup result is not valid JSON",
            ) from error
        require_object(decoded, "Android WorkerGroup result")
        return message_id

    def _direct_call(
        self,
        endpoint_manager_id: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        return self._http.request(
            "POST",
            "/api/v1/worker-delivery/endpoint-managers/"
            f"{quote(endpoint_manager_id, safe='')}/direct-calls",
            body,
            "workerDirectCall",
        )


class Evidence:
    def __init__(self, options: Options) -> None:
        self._options = options
        self.worker_id: str | None = None
        self.baseline_identity_matched: bool | None = None
        self.checks: dict[str, Any] = {}
        self.failures: list[dict[str, Any]] = []

    def check(self, name: str, value: Any) -> None:
        self.checks[name] = value

    def failure(self, error: ProofFailure) -> None:
        self.failures.append(
            {
                "invariant": error.invariant,
                "message": error.safe_message,
                "missingIds": list(error.missing_ids),
                "unexpectedIds": list(error.unexpected_ids),
                "inconsistentIds": list(error.inconsistent_ids),
            }
        )

    def unexpected_failure(self, error: BaseException) -> None:
        self.failures.append(
            {
                "invariant": "android-worker.acceptance",
                "message": type(error).__name__,
                "missingIds": [],
                "unexpectedIds": [],
                "inconsistentIds": [],
            }
        )

    def write(self) -> None:
        encoded = {
            "schemaVersion": 1,
            "proofId": self._options.proof_id,
            "phase": self._options.phase,
            "status": "succeeded" if not self.failures else "failed",
            "androidApiLevel": self._options.android_api_level,
            "applicationId": APPLICATION_ID,
            "workerGroupId": WORKER_GROUP_ID,
            "endpointManagerId": self._options.endpoint_manager_id,
            "workerId": self.worker_id,
            "baselineIdentityMatched": self.baseline_identity_matched,
            "checks": self.checks,
            "failures": self.failures,
        }
        self._options.evidence_file.parent.mkdir(parents=True, exist_ok=True)
        with self._options.evidence_file.open("x", encoding="utf-8") as output:
            json.dump(
                encoded,
                output,
                ensure_ascii=False,
                separators=(",", ":"),
            )


class AndroidWorkerAcceptance:
    def __init__(
        self,
        options: Options,
        device: DeviceApiClient,
        runtime: RuntimeApiClient,
        *,
        monotonic: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.options = options
        self.device = device
        self.runtime = runtime
        self.evidence = Evidence(options)
        self._monotonic = monotonic
        self._sleep = sleep
        self._wait_seconds = options.maximum_wait_millis / 1_000

    def run(self) -> None:
        baseline_worker_id = self._load_baseline_worker_id()
        if self.options.phase == "initial":
            self._initial()
        elif self.options.phase == "terminal":
            self._terminal(baseline_worker_id)
        elif self.options.phase == "server-restart":
            self._server_restart(baseline_worker_id)
        elif self.options.phase == "process-restart":
            self._process_restart(baseline_worker_id)
        else:
            raise ValueError("Unsupported phase")

    def _initial(self) -> None:
        self._await_health()
        events = self.device.events()
        actual_events = set(events)
        missing_events = sorted(REQUIRED_DEVICE_EVENTS - actual_events)
        unexpected_events = sorted(actual_events - REQUIRED_DEVICE_EVENTS)
        if missing_events or unexpected_events or len(events) != len(actual_events):
            raise ProofFailure(
                "device.events.required",
                "Android device events do not match the fixed Host assembly",
                missing_ids=tuple(missing_events),
                unexpected_ids=tuple(unexpected_events),
            )
        self.evidence.check("deviceEventCodes", sorted(actual_events))

        initial = self._await_snapshot("RUNNING", None)
        worker_id = require_worker_id(initial.worker_id)
        self.evidence.worker_id = worker_id
        self._await_connected(worker_id)
        self.evidence.check("initialConnected", True)
        self._verify_probe(worker_id)
        self.evidence.check("initialProbeObserved", True)
        self._verify_properties(worker_id)
        self.evidence.check("propertiesMatched", True)

        before = initial.processed_commands
        self.device.call(
            STRING_DIGEST_EVENT,
            {"algorithm": "MD5", "value": "local-proof"},
        )
        after_local = self.device.snapshot()
        if after_local.processed_commands != before + 1:
            raise ProofFailure(
                "device.capability-count.local",
                "Local capability call did not increment the shared count",
            )
        message_ids = [
            self.runtime.task_call(event_name, payload)
            for event_name, payload in BUSINESS_CALLS
        ]
        if len(set(message_ids)) != len(BUSINESS_CALLS):
            raise ProofFailure(
                "task-call.message-identities",
                "Android WorkerGroup message IDs are not unique",
            )
        after_tasks = self.device.snapshot()
        if after_tasks.processed_commands != before + 1 + len(BUSINESS_CALLS):
            raise ProofFailure(
                "device.capability-count.worker",
                "Worker capability calls did not increment the shared count",
            )
        self.evidence.check(
            "businessEventCodes", [event for event, _ in BUSINESS_CALLS]
        )
        self.evidence.check("taskMessageIds", message_ids)
        self.evidence.check("taskResultCount", len(message_ids))
        self.evidence.check(
            "processedCommandDelta",
            after_tasks.processed_commands - before,
        )

        self.device.request_state("STOPPED")
        stopped = self._await_stopped(worker_id)
        self._await_not_connected(worker_id)
        self.evidence.check("explicitStopState", stopped.state)
        self.evidence.check("explicitStopDisconnected", True)

        self.device.request_state("RUNNING")
        restarted = self._await_snapshot("RUNNING", worker_id)
        self._await_connected(worker_id)
        self._verify_probe(worker_id)
        self.evidence.check("explicitRestartState", restarted.state)
        self.evidence.check("explicitRestartIdentityMatched", True)
        self.evidence.check("explicitRestartProbeObserved", True)

    def _terminal(self, baseline_worker_id: str) -> None:
        self._await_health()
        stopped = self._await_stopped(baseline_worker_id)
        self.evidence.worker_id = baseline_worker_id
        self.evidence.baseline_identity_matched = True
        self.evidence.check("endpointTerminalState", stopped.state)

    def _server_restart(self, baseline_worker_id: str) -> None:
        self._await_health()
        observation_deadline = self._monotonic() + 3.0
        samples = 0
        while self._monotonic() < observation_deadline:
            snapshot = self.device.snapshot()
            if snapshot.state != "STOPPED":
                raise ProofFailure(
                    "server-restart.no-automatic-start",
                    "Android Worker started automatically after Server restart",
                    inconsistent_ids=(baseline_worker_id,),
                )
            self._require_compatible_stopped_identity(
                snapshot,
                baseline_worker_id,
            )
            if self.runtime.network_state(
                self.options.endpoint_manager_id, baseline_worker_id
            ) == "connected":
                raise ProofFailure(
                    "server-restart.no-automatic-route",
                    "Android Worker connected automatically after Server restart",
                    inconsistent_ids=(baseline_worker_id,),
                )
            samples += 1
            self._sleep(0.1)
        self.evidence.check("noAutomaticStartObservationMillis", 3_000)
        self.evidence.check("noAutomaticStartSamples", samples)

        self.device.request_state("RUNNING")
        snapshot = self._await_snapshot("RUNNING", baseline_worker_id)
        self._await_connected(baseline_worker_id)
        self._verify_probe(baseline_worker_id)
        self.evidence.worker_id = snapshot.worker_id
        self.evidence.baseline_identity_matched = True
        self.evidence.check("serverRestartConnected", True)
        self.evidence.check("serverRestartProbeObserved", True)

    def _process_restart(self, baseline_worker_id: str) -> None:
        self._await_not_connected(baseline_worker_id)
        self.evidence.worker_id = baseline_worker_id
        self.evidence.check("processStopDisconnected", True)
        print(PROCESS_STOP_OBSERVED_MARKER, flush=True)
        self._await_health()
        snapshot = self._await_snapshot("RUNNING", baseline_worker_id)
        self._await_connected(baseline_worker_id)
        self._verify_probe(baseline_worker_id)
        message_id = self.runtime.task_call(
            STRING_DIGEST_EVENT,
            {"algorithm": "MD5", "value": "process-restart"},
        )
        self.evidence.worker_id = snapshot.worker_id
        self.evidence.baseline_identity_matched = True
        self.evidence.check("processRestartConnected", True)
        self.evidence.check("processRestartProbeObserved", True)
        self.evidence.check("processRestartEventCode", STRING_DIGEST_EVENT)
        self.evidence.check("processRestartMessageId", message_id)
        self.evidence.check("processRestartResultCount", 1)

    def _await_health(self) -> None:
        deadline = self._monotonic() + self._wait_seconds
        while self._monotonic() < deadline:
            try:
                self.device.health()
                return
            except RuntimeError:
                self._sleep(0.1)
        raise ProofFailure(
            "device.health.ready",
            "Android device HTTP did not become ready",
        )

    def _await_snapshot(
        self, target_state: str, expected_worker_id: str | None
    ) -> WorkerSnapshot:
        deadline = self._monotonic() + self._wait_seconds
        latest: WorkerSnapshot | None = None
        while self._monotonic() < deadline:
            try:
                latest = self.device.snapshot()
                if latest.state == target_state and (
                    (
                        expected_worker_id is None
                        and (
                            target_state != "RUNNING"
                            or latest.worker_id is not None
                        )
                    )
                    or (
                        expected_worker_id is not None
                        and latest.worker_id == expected_worker_id
                    )
                ):
                    return latest
            except RuntimeError:
                pass
            self._sleep(0.1)
        inconsistent = ()
        if latest is not None and latest.worker_id is not None:
            inconsistent = (latest.worker_id,)
        raise ProofFailure(
            "device.lifecycle.state",
            "Android Worker did not reach the expected local state",
            inconsistent_ids=inconsistent,
        )

    def _await_stopped(self, expected_worker_id: str) -> WorkerSnapshot:
        snapshot = self._await_snapshot("STOPPED", None)
        self._require_compatible_stopped_identity(
            snapshot,
            expected_worker_id,
        )
        return snapshot

    @staticmethod
    def _require_compatible_stopped_identity(
        snapshot: WorkerSnapshot,
        expected_worker_id: str,
    ) -> None:
        if (
            snapshot.worker_id is not None
            and snapshot.worker_id != expected_worker_id
        ):
            raise ProofFailure(
                "device.lifecycle.stopped-identity",
                "Stopped Android Worker exposed a conflicting identity",
                inconsistent_ids=(snapshot.worker_id,),
            )

    def _await_connected(self, worker_id: str) -> None:
        deadline = self._monotonic() + self._wait_seconds
        while self._monotonic() < deadline:
            try:
                if self.runtime.network_state(
                    self.options.endpoint_manager_id, worker_id
                ) == "connected":
                    return
            except RuntimeError:
                pass
            self._sleep(0.1)
        raise ProofFailure(
            "network.connected",
            "Android Worker did not become connected",
            missing_ids=(worker_id,),
        )

    def _await_not_connected(self, worker_id: str) -> None:
        deadline = self._monotonic() + self._wait_seconds
        while self._monotonic() < deadline:
            try:
                if self.runtime.network_state(
                    self.options.endpoint_manager_id, worker_id
                ) != "connected":
                    return
            except RuntimeError:
                pass
            self._sleep(0.1)
        raise ProofFailure(
            "network.disconnected",
            "Android Worker remained connected",
            inconsistent_ids=(worker_id,),
        )

    def _verify_probe(self, worker_id: str) -> None:
        self.runtime.call_worker(
            self.options.endpoint_manager_id,
            worker_id,
            WORKER_PROBE_EVENT,
        )

    def _verify_properties(self, worker_id: str) -> None:
        worker_payload = self.runtime.call_worker(
            self.options.endpoint_manager_id,
            worker_id,
            WORKER_PROPERTIES_EVENT,
        )
        worker_snapshot = parse_json_object(
            worker_payload, "Worker Properties snapshot"
        )
        worker_properties = require_object(
            worker_snapshot.get("properties"),
            "Worker Properties",
        )
        adapter_payload = self.runtime.call_adapter(
            self.options.endpoint_manager_id,
            ADAPTER_PROPERTIES_EVENT,
            json.dumps(
                {"workerIds": [worker_id]},
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        )
        adapter_snapshot = parse_json_object(
            adapter_payload, "Adapter Properties snapshot"
        )
        observations = require_object(
            adapter_snapshot.get("propertiesByWorkerId"),
            "Adapter Properties observations",
        )
        if set(observations) != {worker_id}:
            raise ProofFailure(
                "properties.adapter-identities",
                "Adapter Properties identities do not match",
                missing_ids=(() if worker_id in observations else (worker_id,)),
                unexpected_ids=tuple(sorted(set(observations) - {worker_id})),
            )
        observation = require_object(
            observations[worker_id], "Adapter Worker Properties observation"
        )
        updated_at = observation.get("updatedAtMillis")
        cached_properties = require_object(
            observation.get("properties"), "Adapter cached Worker Properties"
        )
        if (
            not isinstance(updated_at, (int, float))
            or isinstance(updated_at, bool)
            or cached_properties != worker_properties
        ):
            raise ProofFailure(
                "properties.adapter-match",
                "Adapter Properties observation does not match Worker Report",
                inconsistent_ids=(worker_id,),
            )

    def _load_baseline_worker_id(self) -> str:
        if self.options.phase == "initial":
            return ""
        path = self.options.baseline_file
        if path is None:
            raise ValueError("baseline file is required")
        try:
            with path.open(encoding="utf-8") as source:
                baseline = json.load(source)
        except (OSError, json.JSONDecodeError) as error:
            raise ProofFailure(
                "baseline.read",
                "Android proof baseline could not be read",
            ) from error
        if (
            not isinstance(baseline, dict)
            or baseline.get("schemaVersion") != 1
            or baseline.get("phase") != "initial"
            or baseline.get("status") != "succeeded"
            or baseline.get("proofId") != self.options.proof_id
            or baseline.get("applicationId") != APPLICATION_ID
            or baseline.get("workerGroupId") != WORKER_GROUP_ID
            or baseline.get("endpointManagerId")
            != self.options.endpoint_manager_id
        ):
            raise ProofFailure(
                "baseline.contract",
                "Android proof baseline is incompatible",
            )
        return require_worker_id(baseline.get("workerId"))


def execute(options: Options) -> None:
    device = DeviceApiClient(
        options.device_base_url, options.request_timeout_millis
    )
    runtime = RuntimeApiClient(
        options.server_base_url, options.request_timeout_millis
    )
    acceptance = AndroidWorkerAcceptance(options, device, runtime)
    failure: BaseException | None = None
    try:
        acceptance.run()
    except ProofFailure as error:
        acceptance.evidence.failure(error)
        failure = error
    except BaseException as error:
        acceptance.evidence.unexpected_failure(error)
        failure = error
    try:
        acceptance.evidence.write()
    except BaseException as write_error:
        if failure is not None:
            raise failure from write_error
        raise
    if failure is not None:
        raise failure


def parse_options(arguments: list[str] | None = None) -> Options:
    parser = argparse.ArgumentParser(
        description="Prove the Android Worker through device and Runtime APIs."
    )
    parser.add_argument(
        "--phase",
        required=True,
        choices=("initial", "terminal", "server-restart", "process-restart"),
    )
    parser.add_argument("--proof-id", required=True)
    parser.add_argument("--server-base-url", default=DEFAULT_SERVER_BASE_URL)
    parser.add_argument("--device-base-url", default=DEFAULT_DEVICE_BASE_URL)
    parser.add_argument(
        "--endpoint-manager-id", default=DEFAULT_ENDPOINT_MANAGER_ID
    )
    parser.add_argument("--evidence-file", required=True, type=Path)
    parser.add_argument("--baseline-file", type=Path)
    parser.add_argument("--maximum-wait-millis", type=int, default=30_000)
    parser.add_argument("--request-timeout-millis", type=int, default=120_000)
    parser.add_argument(
        "--android-api-level",
        type=int,
        default=int(os.environ.get("ANDROID_API_LEVEL", "33")),
    )
    args = parser.parse_args(arguments)
    proof_id = require_non_blank(args.proof_id, "proof ID")
    endpoint_manager_id = require_non_blank(
        args.endpoint_manager_id, "endpoint manager ID"
    )
    if args.maximum_wait_millis <= 0 or args.maximum_wait_millis > 300_000:
        parser.error("maximum wait must be in 1..300000 milliseconds")
    if args.request_timeout_millis <= 0 or args.request_timeout_millis > 300_000:
        parser.error("request timeout must be in 1..300000 milliseconds")
    if args.android_api_level <= 0:
        parser.error("Android API level must be positive")
    if args.phase != "initial" and args.baseline_file is None:
        parser.error("baseline file is required outside the initial phase")
    return Options(
        phase=args.phase,
        proof_id=proof_id,
        server_base_url=args.server_base_url,
        device_base_url=args.device_base_url,
        endpoint_manager_id=endpoint_manager_id,
        evidence_file=args.evidence_file,
        baseline_file=args.baseline_file,
        maximum_wait_millis=args.maximum_wait_millis,
        request_timeout_millis=args.request_timeout_millis,
        android_api_level=args.android_api_level,
    )


def require_object(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict) or not all(
        isinstance(key, str) for key in value
    ):
        raise ProofFailure(
            "json.object-shape", f"{name} must be a JSON object"
        )
    return dict(value)


def parse_json_object(value: str, name: str) -> dict[str, Any]:
    try:
        decoded = json.loads(value)
    except json.JSONDecodeError as error:
        raise ProofFailure(
            "json.decode", f"{name} is not valid JSON"
        ) from error
    return require_object(decoded, name)


def optional_string(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ProofFailure(
            "json.optional-string", "Optional string field is invalid"
        )
    return value


def require_worker_id(value: Any) -> str:
    if not isinstance(value, str):
        raise ProofFailure("worker.identity", "Worker ID is missing")
    try:
        parsed = uuid.UUID(value)
    except ValueError as error:
        raise ProofFailure("worker.identity", "Worker ID is invalid") from error
    if str(parsed) != value:
        raise ProofFailure("worker.identity", "Worker ID is not canonical")
    return value


def require_non_blank(value: str, name: str) -> str:
    if value is None or not value.strip():
        raise ValueError(f"{name} must be non-blank")
    return value.strip()


def main() -> None:
    execute(parse_options())


if __name__ == "__main__":
    main()
