from __future__ import annotations

import json
from abc import ABC, abstractmethod
from collections.abc import Mapping as MappingABC
from dataclasses import dataclass
from enum import Enum
from typing import Any, Mapping

from .task_score_band import TimeMillis
from .worker_runtime import EndpointManagerId
from .worker_score import WorkerId


class DeliveryEndpoint(Enum):
    TASK = "TASK"
    SYSTEM = "SYSTEM"
    KERNEL = "KERNEL"
    ADAPTER = "ADAPTER"
    WORKER = "WORKER"


class DeliveryReportOutcomeClass(Enum):
    SUCCESS = "SUCCESS"
    WORKER_FAILURE = "WORKER_FAILURE"
    ADAPTER_REJECTION = "ADAPTER_REJECTION"


SUCCESS_OUTCOME_CODE = "200"
WORKER_CONNECTION_IDENTIFY_EVENT_CODE = "worker.connection.identify"
WORKER_CONNECTION_CLOSE_EVENT_CODE = "worker.connection.close"


def classify_delivery_report_outcome_code(
    outcome_code: str,
) -> DeliveryReportOutcomeClass | None:
    if outcome_code == SUCCESS_OUTCOME_CODE:
        return DeliveryReportOutcomeClass.SUCCESS
    if not isinstance(outcome_code, str) or not outcome_code.strip():
        return None
    if outcome_code.startswith("3"):
        return DeliveryReportOutcomeClass.WORKER_FAILURE
    return DeliveryReportOutcomeClass.ADAPTER_REJECTION


@dataclass(frozen=True, slots=True, init=False)
class DeliveryCommand:
    src: DeliveryEndpoint
    dst: DeliveryEndpoint
    message_type: str
    execute_before_millis: TimeMillis
    payload: str
    forward: str

    def __new__(cls, *args: object, **kwargs: object) -> DeliveryCommand:
        raise TypeError("use DeliveryCommand.create()")

    @classmethod
    def create(
        cls,
        *,
        src: DeliveryEndpoint,
        dst: DeliveryEndpoint,
        message_type: str,
        execute_before_millis: TimeMillis,
        payload: str,
        forward: str,
    ) -> DeliveryCommand:
        return cls._restore(
            src=src,
            dst=dst,
            message_type=message_type,
            execute_before_millis=execute_before_millis,
            payload=payload,
            forward=forward,
        )

    @classmethod
    def _restore(
        cls,
        *,
        src: DeliveryEndpoint,
        dst: DeliveryEndpoint,
        message_type: str,
        execute_before_millis: TimeMillis,
        payload: str,
        forward: str,
    ) -> DeliveryCommand:
        if not isinstance(src, DeliveryEndpoint):
            raise TypeError("src must be a DeliveryEndpoint")
        if not isinstance(dst, DeliveryEndpoint):
            raise TypeError("dst must be a DeliveryEndpoint")
        _require_non_empty_text(message_type, "message type")
        if (
            isinstance(execute_before_millis, bool)
            or not isinstance(execute_before_millis, int)
            or execute_before_millis <= 0
        ):
            raise ValueError("execute-before deadline must be positive")
        _require_text(payload, "payload")
        _require_text(forward, "forward")
        if src is DeliveryEndpoint.TASK and not forward:
            raise ValueError("TASK command forward must be non-empty")
        command = object.__new__(cls)
        object.__setattr__(command, "src", src)
        object.__setattr__(command, "dst", dst)
        object.__setattr__(command, "message_type", message_type)
        object.__setattr__(
            command,
            "execute_before_millis",
            execute_before_millis,
        )
        object.__setattr__(command, "payload", payload)
        object.__setattr__(command, "forward", forward)
        return command


@dataclass(frozen=True, slots=True, init=False)
class DeliveryReport:
    src: DeliveryEndpoint
    source_id: str
    dst: DeliveryEndpoint
    message_type: str
    outcome_code: str
    payload: str
    forward: str

    def __new__(cls, *args: object, **kwargs: object) -> DeliveryReport:
        raise TypeError(
            "use DeliveryReport.create() or DeliveryReport.from_command()"
        )

    @classmethod
    def from_command(
        cls,
        *,
        command: DeliveryCommand,
        src: DeliveryEndpoint,
        source_id: str,
        outcome_code: str,
        payload: str,
    ) -> DeliveryReport:
        if not isinstance(command, DeliveryCommand):
            raise TypeError("command must be a DeliveryCommand")
        return cls._restore(
            src=src,
            source_id=source_id,
            dst=command.src,
            message_type=command.message_type,
            outcome_code=outcome_code,
            payload=payload,
            forward=command.forward,
        )

    @classmethod
    def create(
        cls,
        *,
        src: DeliveryEndpoint,
        source_id: str,
        dst: DeliveryEndpoint,
        message_type: str,
        outcome_code: str,
        payload: str,
        forward: str,
    ) -> DeliveryReport:
        return cls._restore(
            src=src,
            source_id=source_id,
            dst=dst,
            message_type=message_type,
            outcome_code=outcome_code,
            payload=payload,
            forward=forward,
        )

    @classmethod
    def _restore(
        cls,
        *,
        src: DeliveryEndpoint,
        source_id: str,
        dst: DeliveryEndpoint,
        message_type: str,
        outcome_code: str,
        payload: str,
        forward: str,
    ) -> DeliveryReport:
        if not isinstance(src, DeliveryEndpoint):
            raise TypeError("src must be a DeliveryEndpoint")
        _require_non_blank_text(source_id, "source id")
        if not isinstance(dst, DeliveryEndpoint):
            raise TypeError("dst must be a DeliveryEndpoint")
        _require_non_empty_text(message_type, "message type")
        if classify_delivery_report_outcome_code(outcome_code) is None:
            raise ValueError("outcome code must be non-empty")
        _require_text(payload, "payload")
        _require_text(forward, "forward")
        if dst is DeliveryEndpoint.TASK and not forward:
            raise ValueError("TASK report forward must be non-empty")
        report = object.__new__(cls)
        object.__setattr__(report, "src", src)
        object.__setattr__(report, "source_id", source_id)
        object.__setattr__(report, "dst", dst)
        object.__setattr__(report, "message_type", message_type)
        object.__setattr__(report, "outcome_code", outcome_code)
        object.__setattr__(report, "payload", payload)
        object.__setattr__(report, "forward", forward)
        return report


class WorkerCommandAppendStatus(Enum):
    APPENDED = "APPENDED"
    REPLACED = "REPLACED"


class WorkerCommandOfferStatus(Enum):
    OFFERED = "OFFERED"
    OCCUPIED = "OCCUPIED"


class WorkerCommandRuntime(ABC):
    """Runtime owner for Adapter-partitioned Worker command mailboxes."""

    @abstractmethod
    def append_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_commands_by_worker_id: Mapping[WorkerId, DeliveryCommand],
    ) -> Mapping[WorkerId, WorkerCommandAppendStatus]:
        pass

    @abstractmethod
    def offer_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_commands_by_worker_id: Mapping[WorkerId, DeliveryCommand],
    ) -> Mapping[WorkerId, WorkerCommandOfferStatus]:
        pass

    @abstractmethod
    def consume_worker_command(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_id: WorkerId,
    ) -> DeliveryCommand | None:
        pass

    @abstractmethod
    def consume_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        limit: int,
    ) -> Mapping[WorkerId, DeliveryCommand]:
        pass


def encode_delivery_command(command: DeliveryCommand) -> str:
    return _encode_json(
        {
            "dst": command.dst.value,
            "executeBeforeMillis": command.execute_before_millis,
            "forward": command.forward,
            "messageType": command.message_type,
            "payload": command.payload,
            "src": command.src.value,
        }
    )


def decode_delivery_command(value: str | bytes) -> DeliveryCommand | None:
    payload = _decode_json_mapping(value)
    if payload is None or set(payload) != {
        "dst",
        "executeBeforeMillis",
        "forward",
        "messageType",
        "payload",
        "src",
    }:
        return None
    try:
        return DeliveryCommand._restore(
            src=DeliveryEndpoint(payload["src"]),
            dst=DeliveryEndpoint(payload["dst"]),
            message_type=payload["messageType"],
            execute_before_millis=payload["executeBeforeMillis"],
            payload=payload["payload"],
            forward=payload["forward"],
        )
    except (TypeError, ValueError):
        return None


def encode_delivery_report(report: DeliveryReport) -> str:
    return _encode_json(
        {
            "dst": report.dst.value,
            "forward": report.forward,
            "messageType": report.message_type,
            "outcomeCode": report.outcome_code,
            "payload": report.payload,
            "sourceId": report.source_id,
            "src": report.src.value,
        }
    )


def decode_delivery_report(value: str | bytes) -> DeliveryReport | None:
    payload = _decode_json_mapping(value)
    if payload is None or set(payload) != {
        "dst",
        "forward",
        "messageType",
        "outcomeCode",
        "payload",
        "sourceId",
        "src",
    }:
        return None
    try:
        return DeliveryReport._restore(
            src=DeliveryEndpoint(payload["src"]),
            source_id=payload["sourceId"],
            dst=DeliveryEndpoint(payload["dst"]),
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


def _require_non_blank_text(value: object, name: str) -> None:
    _require_text(value, name)
    if not value.strip():
        raise ValueError(f"{name} must be non-blank")


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
