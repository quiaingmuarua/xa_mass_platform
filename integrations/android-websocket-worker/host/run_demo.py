from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


WORKER_GROUP_ID = "android-demo-workers"
EVENT_CODE = "android.demo.state.read"


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
    worker_id: str,
    request_timeout_seconds: float,
    wait_timeout_millis: int,
) -> dict[str, Any]:
    if not isinstance(worker_id, str) or not worker_id.strip():
        raise ValueError("worker ID must be non-blank")
    if wait_timeout_millis <= 0 or wait_timeout_millis > 60_000:
        raise ValueError("wait timeout must be in 1..60000 milliseconds")

    client = RuntimeApiClient(server_base_url, request_timeout_seconds)
    task_id = "android-worker-demo-" + uuid.uuid4().hex[:12]
    message_id = str(uuid.uuid4())
    task_created = False
    primary_failure: BaseException | None = None
    try:
        create = client.send(
            "POST",
            "/api/v1/tasks",
            {
                "taskId": task_id,
                "workerGroupId": WORKER_GROUP_ID,
                "taskType": "ITEM_DRIVEN",
                "config": {
                    "priority": "0",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3",
                },
                "emptyCloseAtMillis": int(time.time() * 1000) + 3_600_000,
            },
            "task.create",
        )
        require_status(create, 201, "task.create")
        task_created = True

        approve = client.send(
            "POST",
            f"/api/v1/tasks/{quote(task_id, safe='')}/approve",
            None,
            "task.approve",
        )
        require_status(approve, 200, "task.approve")

        call = client.send(
            "POST",
            f"/api/v1/tasks/{quote(task_id, safe='')}/items:call",
            {
                "item": {
                    "messageId": message_id,
                    "eventCode": EVENT_CODE,
                    "createdAtMillis": int(time.time() * 1000),
                    "payload": {},
                    "allocationRule": {
                        "workerId": {"$eq": worker_id}
                    },
                },
                "waitTimeoutMillis": wait_timeout_millis,
            },
            "taskItem.call",
        )
        response_body = require_status(call, 200, "taskItem.call")
        if response_body.get("status") != "succeeded":
            raise RuntimeError("taskItem.call did not return succeeded")
        encoded_result = response_body.get("opaqueResultPayload")
        if not isinstance(encoded_result, str) or not encoded_result:
            raise RuntimeError("taskItem.call result payload is missing")
        try:
            result = json.loads(encoded_result)
        except json.JSONDecodeError as error:
            raise RuntimeError(
                "Android Worker returned invalid JSON"
            ) from error
        if not isinstance(result, dict):
            raise RuntimeError("Android Worker result must be a JSON object")
        return {
            "taskId": task_id,
            "messageId": message_id,
            "workerGroupId": WORKER_GROUP_ID,
            "workerId": worker_id,
            "eventCode": EVENT_CODE,
            "result": result,
        }
    except BaseException as error:
        primary_failure = error
        raise
    finally:
        if task_created:
            try:
                close = client.send(
                    "POST",
                    f"/api/v1/tasks/{quote(task_id, safe='')}/close",
                    None,
                    "task.close",
                )
                require_status(close, 200, "task.close")
            except Exception as close_error:
                if primary_failure is None:
                    raise
                print(
                    f"task.close also failed: {close_error}",
                    file=sys.stderr,
                )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run one RPC through the Android WebSocket Worker."
    )
    parser.add_argument("--worker-id", required=True)
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
        worker_id=args.worker_id,
        request_timeout_seconds=args.request_timeout_seconds,
        wait_timeout_millis=args.wait_timeout_millis,
    )
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
