from __future__ import annotations

import json
from abc import ABC, abstractmethod
from collections.abc import Mapping as MappingABC
from dataclasses import dataclass
from enum import Enum
from typing import Any, Mapping
from uuid import UUID

from .task_score_band import TimeMillis
from .worker_runtime import EndpointManagerId
from .worker_score import WorkerId


class WorkerMessageEndpoint(Enum):
    TASK = "TASK"
    SYSTEM = "SYSTEM"
    ADAPTER = "ADAPTER"
    WORKER = "WORKER"


class WorkerResultOutcomeClass(Enum):
    SUCCESS = "SUCCESS"
    WORKER_FAILURE = "WORKER_FAILURE"
    ADAPTER_REJECTION = "ADAPTER_REJECTION"


SUCCESS_OUTCOME_CODE = "200"


def classify_worker_result_outcome_code(
    outcome_code: str,
) -> WorkerResultOutcomeClass | None:
    if outcome_code == SUCCESS_OUTCOME_CODE:
        return WorkerResultOutcomeClass.SUCCESS
    if not isinstance(outcome_code, str) or not outcome_code.strip():
        return None
    if outcome_code.startswith("3"):
        return WorkerResultOutcomeClass.WORKER_FAILURE
    return WorkerResultOutcomeClass.ADAPTER_REJECTION


@dataclass(frozen=True, slots=True)
class WorkerConnectionBind:
    worker_id: WorkerId

    def __post_init__(self) -> None:
        _require_canonical_uuid(self.worker_id, "worker id")


@dataclass(frozen=True, slots=True)
class WorkerCommand:
    message_id: str
    src: WorkerMessageEndpoint
    dst: WorkerMessageEndpoint
    message_type: str
    execute_before_millis: TimeMillis
    payload: str
    forward: str

    def __post_init__(self) -> None:
        _require_canonical_uuid(self.message_id, "message id")
        if (
            not isinstance(self.src, WorkerMessageEndpoint)
            or self.src is WorkerMessageEndpoint.WORKER
        ):
            raise ValueError(
                "Worker command src must be TASK, SYSTEM, or ADAPTER"
            )
        if self.dst is not WorkerMessageEndpoint.WORKER:
            raise ValueError("Worker command dst must be WORKER")
        _require_non_empty_text(self.message_type, "message type")
        if (
            isinstance(self.execute_before_millis, bool)
            or not isinstance(self.execute_before_millis, int)
            or self.execute_before_millis <= 0
        ):
            raise ValueError("execute-before deadline must be positive")
        _require_text(self.payload, "payload")
        _require_text(self.forward, "forward")
        if self.src is WorkerMessageEndpoint.TASK and not self.forward:
            raise ValueError("TASK command forward must be non-empty")


@dataclass(frozen=True, slots=True)
class WorkerResult:
    message_id: str
    dst: WorkerMessageEndpoint
    message_type: str
    outcome_code: str
    payload: str
    forward: str

    def __post_init__(self) -> None:
        _require_canonical_uuid(self.message_id, "message id")
        if (
            not isinstance(self.dst, WorkerMessageEndpoint)
            or self.dst is WorkerMessageEndpoint.WORKER
        ):
            raise ValueError(
                "Worker result dst must be TASK, SYSTEM, or ADAPTER"
            )
        _require_non_empty_text(self.message_type, "message type")
        if classify_worker_result_outcome_code(self.outcome_code) is None:
            raise ValueError("outcome code must be non-empty")
        _require_text(self.payload, "payload")
        _require_text(self.forward, "forward")
        if self.dst is WorkerMessageEndpoint.TASK and not self.forward:
            raise ValueError("TASK result forward must be non-empty")


class WorkerCommandAppendStatus(Enum):
    APPENDED = "APPENDED"
    REPLACED = "REPLACED"


class WorkerCommandRuntime(ABC):
    """Runtime owner for Adapter-partitioned Worker command mailboxes."""

    @abstractmethod
    def append_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_commands_by_worker_id: Mapping[WorkerId, WorkerCommand],
    ) -> Mapping[WorkerId, WorkerCommandAppendStatus]:
        pass

    @abstractmethod
    def consume_worker_command(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_id: WorkerId,
    ) -> WorkerCommand | None:
        pass

    @abstractmethod
    def consume_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        limit: int,
    ) -> Mapping[WorkerId, WorkerCommand]:
        pass


def encode_worker_connection_bind(bind: WorkerConnectionBind) -> str:
    return _encode_json({"workerId": bind.worker_id})


def decode_worker_connection_bind(
    value: str | bytes,
) -> WorkerConnectionBind | None:
    payload = _decode_json_mapping(value)
    if payload is None or set(payload) != {"workerId"}:
        return None
    try:
        return WorkerConnectionBind(worker_id=payload["workerId"])
    except (TypeError, ValueError):
        return None


def encode_worker_command(command: WorkerCommand) -> str:
    return _encode_json(
        {
            "dst": command.dst.value,
            "executeBeforeMillis": command.execute_before_millis,
            "forward": command.forward,
            "messageId": command.message_id,
            "messageType": command.message_type,
            "payload": command.payload,
            "src": command.src.value,
        }
    )


def decode_worker_command(value: str | bytes) -> WorkerCommand | None:
    payload = _decode_json_mapping(value)
    if payload is None or set(payload) != {
        "dst",
        "executeBeforeMillis",
        "forward",
        "messageId",
        "messageType",
        "payload",
        "src",
    }:
        return None
    try:
        return WorkerCommand(
            message_id=payload["messageId"],
            src=WorkerMessageEndpoint(payload["src"]),
            dst=WorkerMessageEndpoint(payload["dst"]),
            message_type=payload["messageType"],
            execute_before_millis=payload["executeBeforeMillis"],
            payload=payload["payload"],
            forward=payload["forward"],
        )
    except (TypeError, ValueError):
        return None


def encode_worker_result(result: WorkerResult) -> str:
    return _encode_json(
        {
            "dst": result.dst.value,
            "forward": result.forward,
            "messageId": result.message_id,
            "messageType": result.message_type,
            "outcomeCode": result.outcome_code,
            "payload": result.payload,
        }
    )


def decode_worker_result(value: str | bytes) -> WorkerResult | None:
    payload = _decode_json_mapping(value)
    if payload is None or set(payload) != {
        "dst",
        "forward",
        "messageId",
        "messageType",
        "outcomeCode",
        "payload",
    }:
        return None
    try:
        return WorkerResult(
            message_id=payload["messageId"],
            dst=WorkerMessageEndpoint(payload["dst"]),
            message_type=payload["messageType"],
            outcome_code=payload["outcomeCode"],
            payload=payload["payload"],
            forward=payload["forward"],
        )
    except (TypeError, ValueError):
        return None


def _require_text(value: object, name: str) -> None:
    if not isinstance(value, str):
        raise TypeError(f"{name} must be text")


def _require_non_empty_text(value: object, name: str) -> None:
    _require_text(value, name)
    if not value:
        raise ValueError(f"{name} must be non-empty")


def _require_canonical_uuid(value: object, name: str) -> None:
    _require_non_empty_text(value, name)
    assert isinstance(value, str)
    try:
        parsed = UUID(value)
    except ValueError as error:
        raise ValueError(f"{name} must be a canonical UUID") from error
    if str(parsed) != value:
        raise ValueError(f"{name} must be a canonical UUID")


def _encode_json(payload: Mapping[str, object]) -> str:
    return json.dumps(
        payload,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _decode_json_mapping(value: Any) -> Mapping[str, Any] | None:
    try:
        text = value.decode("utf-8") if isinstance(value, bytes) else value
        payload = json.loads(text)
    except (TypeError, ValueError, UnicodeDecodeError):
        return None
    return payload if isinstance(payload, MappingABC) else None
