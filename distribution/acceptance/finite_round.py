#!/usr/bin/env python3
"""Run one finite-Task acceptance round against an existing XA Mass Runtime.

The round creates one raw finite Task, appends a few MD5 Items, approves it, waits until
it is terminal and checks every Result independently. It starts no Server or Worker and
changes no deployment: a Worker Host must already serve the Group. Standard library only.

stdout is exactly one JSON line: outcome passed (exit 0), failed (exit 1) or unconfirmed
(exit 3). Configuration errors exit 2 without a JSON line. The access key is read from a
file and never printed; Item payloads and Results are never printed.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import ipaddress
import json
from pathlib import Path
import re
import ssl
import sys
import time
from typing import Callable
from urllib.parse import urlsplit
import uuid

EVENT = "extension.worker.string.md5"
SELECTOR = {"executorName": "worker.any", "input": {}}
REFILL = [{"poolName": "any", "target": {}, "count": 100}]
VECTORS = ("a", "hello", "号码检查 ☕")
EXIT_CODES = {"passed": 0, "failed": 1, "unconfirmed": 3}
ACCESS_KEY = re.compile(r"xak_[A-Za-z0-9_-]{8,128}")
REQUEST_TIMEOUT_SECONDS = 30

Request = Callable[[str, str, "bytes | None", dict], "tuple[int, bytes]"]


class TransportError(Exception):
    """The request may or may not have reached the Runtime. Carries no response content."""


class _Outcome(Exception):
    def __init__(self, outcome: str, reason: str, *, status: int | None = None, payload: bytes = b""):
        super().__init__(reason)
        self.outcome, self.reason, self.status = outcome, reason, status
        self.error = _error_fields(payload)


def _failed(reason: str, **fields) -> _Outcome:
    return _Outcome("failed", reason, **fields)


def _unconfirmed(reason: str, **fields) -> _Outcome:
    return _Outcome("unconfirmed", reason, **fields)


def _error_fields(payload: bytes) -> dict:
    """Only the Server's numeric code and its request ID; messages and bodies stay private."""
    try:
        body = json.loads(payload)
    except (ValueError, UnicodeDecodeError):
        return {}
    if not isinstance(body, dict):
        return {}
    fields = {}
    if isinstance(body.get("code"), int):
        fields["errorCode"] = body["code"]
    if isinstance(body.get("requestId"), str) and len(body["requestId"]) <= 128:
        fields["serverRequestId"] = body["requestId"]
    return fields


def _require(condition, reason: str) -> None:
    if not condition:
        raise _failed(reason)


def expected_result(seed: str) -> dict:
    return {"input": seed, "valid": True, "md5": hashlib.md5(seed.encode("utf-8")).hexdigest()}


def assert_results(results: dict, expected: dict) -> None:
    _require(set(results) == set(expected), "Result identities differ from submitted Items")
    for message_id, seed in expected.items():
        result = results[message_id]
        _require(isinstance(result, dict) and result.get("status") == "succeeded", "An Item did not succeed")
        try:
            content = json.loads(result["opaqueResultPayload"])
        except (KeyError, TypeError, ValueError):
            raise _failed("A Result payload is not the MD5 Worker's JSON") from None
        # `is True` because JSON 1 would otherwise equal True in the dict comparison.
        _require(isinstance(content, dict) and content.get("valid") is True and content == expected_result(seed),
                 "Worker MD5 output differs from the independently computed UTF-8 digest")


def assert_preview(preview: dict, expected: dict) -> None:
    _require(isinstance(preview, dict) and isinstance(preview.get("results"), list), "Result preview is not an object")
    rows = preview["results"]
    _require(preview.get("truncated") is False and len(rows) == len(expected)
             and all(isinstance(row, dict) for row in rows), "Result preview is incomplete")
    _require(len({row.get("messageId") for row in rows}) == len(rows), "Result preview repeated an Item identity")
    assert_results({row["messageId"]: row.get("result") for row in rows}, expected)
    for row in rows:
        item = row.get("item")
        _require(isinstance(item, dict) and item.get("messageId") == row["messageId"]
                 and item.get("eventCode") == EVENT and item.get("workerSelector") == SELECTOR
                 and item.get("payload") == {"value": expected[row["messageId"]]},
                 "Stored Item differs from the submitted event, Pool query or input")


def seeds(count: int) -> list[str]:
    return [VECTORS[index % len(VECTORS)] + ("" if index < len(VECTORS) else f" #{index}") for index in range(count)]


class _Client:
    def __init__(self, request: Request):
        self._request = request

    def call(self, method: str, path: str, body=None, *, write: bool, raw: bool = False):
        data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode("utf-8"))
        headers = {}
        if body is not None and not isinstance(body, bytes):
            headers["Content-Type"] = "application/json"
        action = f"{method} {path.split('?', 1)[0]}"
        try:
            status, payload = self._request(method, path, data, headers)
        except TransportError:
            raise _unconfirmed(("Response lost for " if write else "Read failed for ") + action) from None
        if status >= 500:
            raise _unconfirmed(f"{action} returned HTTP {status}", status=status, payload=payload)
        if status >= 400 or status < 200:
            raise _failed(f"{action} was rejected with HTTP {status}", status=status, payload=payload)
        if raw:
            return payload
        try:
            value = json.loads(payload)
        except (ValueError, UnicodeDecodeError):
            value = None
        _require(isinstance(value, dict), f"{action} did not return a JSON object")
        return value


def run_round(request: Request, *, project: str, group: str, items: int = 3, timeout_seconds: float = 300,
              poll_seconds: float = 1.0, clock=time.monotonic, sleep=time.sleep, label: str = "round") -> dict:
    """Return one payload-free outcome record; never raises for Runtime or transport behavior."""
    client = _Client(request)
    started = clock()
    record = {"outcome": "failed", "phase": "create", "projectId": project, "group": group, "items": items,
              "requestId": f"{label}-{uuid.uuid4().hex}", "taskId": None,
              "startedAt": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    durations = {}
    try:
        created = client.call("POST", "/api/v1/tasks", {
            "requestId": record["requestId"], "projectId": project, "workerGroupId": group,
            "name": "Acceptance round", "priority": 50, "maxRetryTimes": 3, "refill": REFILL,
        }, write=True)
        task_id = created.get("taskId")
        _require(isinstance(task_id, str) and task_id, "Task creation returned no taskId")
        record["taskId"] = task_id
        task_path = f"/api/v1/projects/{project}/tasks/{task_id}"

        record["phase"] = "append"
        expected = {f"{label}-{index}-{uuid.uuid4().hex}": seed for index, seed in enumerate(seeds(items))}
        appended = client.call("POST", f"/api/v1/tasks/{task_id}/items", [
            {"messageId": message_id, "eventCode": EVENT, "payload": {"value": seed}, "workerSelector": SELECTOR}
            for message_id, seed in expected.items()], write=True)
        _require(set(appended) == set(expected)
                 and all(isinstance(value, dict) and value.get("status") == "applied" for value in appended.values()),
                 "Item append was not fully applied")

        record["phase"] = "descriptor"
        descriptor = client.call("GET", task_path, write=False).get("task")
        _require(isinstance(descriptor, dict) and isinstance(descriptor.get("metadata"), dict)
                 and descriptor.get("projectId") == project and descriptor.get("workerGroupId") == group
                 and descriptor.get("idleDisposition") == "CLOSE_WHEN_IDLE"
                 and descriptor["metadata"].get("server.inputOwner") == "raw" and descriptor.get("refill") == REFILL,
                 "Saved Task descriptor differs from the requested Project, Group and Any supply")

        record["phase"] = "approve"
        approved = client.call("POST", f"/api/v1/tasks/{task_id}/approve", {"expectedItemCount": items}, write=True)
        _require(approved.get("status") in ("applied", "unchanged"), "Task approval was not confirmed")
        approved_at = clock()

        record["phase"] = "wait"
        deadline = approved_at + timeout_seconds
        while True:
            try:
                results = client.call("POST", f"/api/v1/tasks/{task_id}/results:load", list(expected), write=False)
                _require(all(not isinstance(value, dict) or value.get("status") != "failed"
                             for value in results.values()), "An Item failed")
                if set(results) == set(expected) and all(
                        isinstance(value, dict) and value.get("status") == "succeeded" for value in results.values()) \
                        and client.call("GET", task_path, write=False).get("scoreBand") == "terminal":
                    break
            except _Outcome as outcome:
                if outcome.outcome == "failed":
                    raise
            if clock() >= deadline:
                raise _unconfirmed(f"Task did not become terminal within {timeout_seconds:g} seconds")
            sleep(poll_seconds)
        durations["approveToTerminal"] = round((clock() - approved_at) * 1000)

        record["phase"] = "verify"
        assert_results(results, expected)
        states = client.call("POST", f"/api/v1/tasks/{task_id}/items:states", list(expected), write=False)
        _require(set(states) == set(expected) and all(
            isinstance(value, dict) and value.get("band") == "terminal" for value in states.values()),
            "Item Scores are not all terminal")
        assert_preview(client.call("GET", f"/api/v1/tasks/{task_id}/results:preview", write=False), expected)

        record["phase"] = "export"
        exported = client.call("POST", f"/api/v1/tasks/{task_id}/results:export", b"", write=False, raw=True)
        try:
            lines = [json.loads(line) for line in exported.decode("utf-8").splitlines() if line]
        except (ValueError, UnicodeDecodeError):
            raise _failed("Result export is not JSONL") from None
        _require(len(lines) == len(expected) and all(
            isinstance(row, dict) and set(row) == {"messageId", "opaqueResultPayload"} for row in lines),
            "Result export shape or quantity differs")
        _require({row["messageId"]: row["opaqueResultPayload"] for row in lines}
                 == {key: value["opaqueResultPayload"] for key, value in results.items()},
                 "Result export differs from the loaded Results")
        record.update(outcome="passed", phase="done", exportSha256=hashlib.sha256(exported).hexdigest())
    except _Outcome as outcome:
        record.update(outcome=outcome.outcome, reason=outcome.reason, **outcome.error)
        if outcome.status is not None:
            record["httpStatus"] = outcome.status
    except Exception as unexpected:  # an unexpected response shape; only the type is safe to report
        record.update(outcome="failed", reason=f"Unexpected response shape ({type(unexpected).__name__})")
    durations["total"] = round((clock() - started) * 1000)
    record["durationsMillis"] = durations
    return record


def http_request(base_url: str, access_key: str, timeout: float = REQUEST_TIMEOUT_SECONDS) -> Request:
    """Send each request only to base_url's origin; redirects are never followed."""
    parts = urlsplit(base_url)
    connection_type = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    context = ssl.create_default_context() if parts.scheme == "https" else None
    prefix = parts.path.rstrip("/")

    def request(method: str, path: str, body, headers: dict):
        arguments = {"timeout": timeout}
        if context is not None:
            arguments["context"] = context
        connection = connection_type(parts.hostname, parts.port, **arguments)
        try:
            connection.request(method, prefix + path, body=body,
                               headers={**headers, "Authorization": "Bearer " + access_key})
            response = connection.getresponse()
            return response.status, response.read()
        except (OSError, http.client.HTTPException):
            raise TransportError() from None
        finally:
            connection.close()
    return request


def _runtime_url(value: str) -> str:
    parts = urlsplit(value)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password \
            or parts.query or parts.fragment:
        raise argparse.ArgumentTypeError("must be an absolute http(s) URL without credentials, query or fragment")
    if parts.scheme == "http" and not _loopback(parts.hostname):
        raise argparse.ArgumentTypeError("plain http is accepted only for a loopback Runtime; use https")
    return value


def _loopback(host: str) -> bool:
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _read_access_key(path: Path) -> str | None:
    try:
        if path.stat().st_size > 4096:
            return None
        key = path.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeDecodeError):
        return None
    return key if ACCESS_KEY.fullmatch(key) else None


def _items(value: str) -> int:
    try:
        count = int(value)
    except ValueError:
        count = 0
    if not 1 <= count <= 100:
        raise argparse.ArgumentTypeError("must be an integer within 1..100")
    return count


def kit_version() -> str:
    manifest = Path(__file__).resolve().parents[1] / "manifest.json"
    try:
        version = json.loads(manifest.read_text(encoding="utf-8")).get("version")
    except (OSError, ValueError):
        return "source"
    return version if isinstance(version, str) else "source"


def main(arguments=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runtime-url", type=_runtime_url, required=True,
                        help="Console/API origin, for example https://staging.xamass.net")
    parser.add_argument("--access-key-file", type=Path, required=True, help="Private file with one xak_ access key")
    parser.add_argument("--group", required=True, help="Worker Group served by an already running Host")
    parser.add_argument("--project", default="default")
    parser.add_argument("--items", type=_items, default=3, help="Items in the round, 1..100 (default 3)")
    parser.add_argument("--timeout-seconds", type=float, default=300, help="Approve-to-terminal wait (default 300)")
    options = parser.parse_args(arguments)
    if not 1 <= options.timeout_seconds <= 3600:
        parser.error("--timeout-seconds must be within 1..3600")
    access_key = _read_access_key(options.access_key_file)
    if access_key is None:
        parser.error("--access-key-file must be a readable file holding exactly one xak_ access key")
    request = http_request(options.runtime_url, access_key)
    record = run_round(request, project=options.project, group=options.group, items=options.items,
                       timeout_seconds=options.timeout_seconds)
    record.update(runtimeUrl=options.runtime_url, kitVersion=kit_version())
    print(json.dumps(record, ensure_ascii=False, sort_keys=True), flush=True)
    return EXIT_CODES[record["outcome"]]


if __name__ == "__main__":
    sys.exit(main())
