from __future__ import annotations

import argparse
import json
import uuid
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


WORKER_GROUP_ID = "android-demo-workers"
CAPABILITY_CALLS = (
    ("extension.worker.android.state.read", {}),
    ("extension.worker.android.battery.read", {}),
    (
        "extension.worker.android.string.digest",
        {"algorithm": "MD5", "value": "hello"},
    ),
)
EVENT_CODES = tuple(event_code for event_code, _ in CAPABILITY_CALLS)


@dataclass(frozen=True)
class ApiResponse:
    status_code: int
    body: dict[str, Any]


class RuntimeApiClient:
    def __init__(self, base_url: str, timeout_seconds: float) -> None:
        parsed = urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("server base URL must be absolute HTTP(S)")
        if timeout_seconds <= 0:
            raise ValueError("request timeout must be positive")
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def send(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None,
        operation: str,
    ) -> ApiResponse:
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
            raise RuntimeError(
                f"{operation} returned HTTP {error.code}"
            ) from error
        except (URLError, TimeoutError, OSError) as error:
            raise RuntimeError(f"{operation} request failed") from error

        try:
            decoded = {} if not raw_body else json.loads(raw_body)
        except json.JSONDecodeError as error:
            raise RuntimeError(
                f"{operation} returned invalid JSON"
            ) from error
        if not isinstance(decoded, dict):
            raise RuntimeError(f"{operation} returned a non-object response")
        return ApiResponse(status_code=status_code, body=decoded)


def require_status(
    response: ApiResponse,
    expected: int,
    operation: str,
) -> dict[str, Any]:
    if response.status_code != expected:
        raise RuntimeError(
            f"{operation} returned HTTP {response.status_code}"
        )
    return response.body


def run_demo(
    *,
    server_base_url: str,
    request_timeout_seconds: float,
    wait_timeout_millis: int,
) -> dict[str, Any]:
    if wait_timeout_millis <= 0 or wait_timeout_millis > 60_000:
        raise ValueError("wait timeout must be in 1..60000 milliseconds")

    client = RuntimeApiClient(server_base_url, request_timeout_seconds)
    task_id = managed_task_id(client)
    results = call_capabilities(
        client=client,
        task_id=task_id,
        wait_timeout_millis=wait_timeout_millis,
    )
    return {
        "workerGroupId": WORKER_GROUP_ID,
        "results": results,
    }


def managed_task_id(client: RuntimeApiClient) -> str:
    operation = "tasks.preview"
    response = client.send(
        "POST",
        "/api/v1/runtime-view/tasks:preview",
        {"sampleLimit": 100},
        operation,
    )
    body = require_status(response, 200, operation)
    entries = body.get("entries")
    if not isinstance(entries, list):
        raise RuntimeError(f"{operation} entries are missing")
    matches = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        task = entry.get("task")
        worker_group = entry.get("workerGroup")
        if (
            isinstance(task, dict)
            and isinstance(worker_group, dict)
            and task.get("workerGroupId") == WORKER_GROUP_ID
            and worker_group.get("workerGroupId") == WORKER_GROUP_ID
            and task.get("workerAllocationMechanism") == "DIRECT_ITEM_RULE"
            and task.get("idleDisposition") == "PARK_WHEN_IDLE"
            and entry.get("taskId") == task.get("taskId")
        ):
            matches.append(entry)
    if len(matches) != 1:
        raise RuntimeError(f"{operation} did not resolve exactly one Task")
    task_id = matches[0].get("taskId")
    task = matches[0].get("task")
    if not isinstance(task_id, str) or not task_id:
        raise RuntimeError(f"{operation} taskId is missing")
    if (
        not isinstance(task, dict) or task.get("taskId") != task_id
    ):
        raise RuntimeError(f"{operation} Task descriptor is missing")
    return task_id


def call_capabilities(
    *,
    client: RuntimeApiClient,
    task_id: str,
    wait_timeout_millis: int,
) -> list[dict[str, Any]]:
    calls = [
        (str(uuid.uuid4()), event_code, payload)
        for event_code, payload in CAPABILITY_CALLS
    ]
    operation = "taskItems.call"
    call = client.send(
        "POST",
        f"/api/v1/tasks/{quote(task_id, safe='')}/items:call",
        {
            "items": [
                {
                    "messageId": message_id,
                    "eventCode": event_code,
                    "payload": dict(payload),
                    "allocationRule": {},
                }
                for message_id, event_code, payload in calls
            ],
            "waitTimeoutMillis": wait_timeout_millis,
        },
        operation,
    )
    response_body = require_status(call, 200, operation)
    observed = response_body.get("results")
    if not isinstance(observed, dict):
        raise RuntimeError(f"{operation} results are missing")
    results: list[dict[str, Any]] = []
    for message_id, event_code, _ in calls:
        item_result = observed.get(message_id)
        if not isinstance(item_result, dict):
            raise RuntimeError(f"{operation} omitted {message_id}")
        if item_result.get("status") != "succeeded":
            raise RuntimeError(f"{operation} did not return succeeded")
        encoded_result = item_result.get("opaqueResultPayload")
        if not isinstance(encoded_result, str) or not encoded_result:
            raise RuntimeError(f"{operation} result payload is missing")
        try:
            result = json.loads(encoded_result)
        except json.JSONDecodeError as error:
            raise RuntimeError(
                f"{operation} returned invalid result JSON"
            ) from error
        if not isinstance(result, dict):
            raise RuntimeError(f"{operation} result must be a JSON object")
        results.append(
            {
                "messageId": message_id,
                "eventCode": event_code,
                "result": result,
            }
        )
    return results


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run the Android capability RPCs through one Worker."
    )
    parser.add_argument(
        "--server-base-url",
        default="http://127.0.0.1:18082",
    )
    parser.add_argument(
        "--request-timeout-seconds",
        type=float,
        default=35.0,
    )
    parser.add_argument(
        "--wait-timeout-millis",
        type=int,
        default=30_000,
    )
    args = parser.parse_args()
    result = run_demo(
        server_base_url=args.server_base_url,
        request_timeout_seconds=args.request_timeout_seconds,
        wait_timeout_millis=args.wait_timeout_millis,
    )
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
