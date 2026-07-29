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


class WorkerMessageType(Enum):
    """Stable Worker protocol message family."""

    TASK_ITEM = "TASK_ITEM"


@dataclass(frozen=True, slots=True)
class DeliverSeed:
    """Already-assigned TaskItem handoff encoded inside one Worker command."""

    worker_id: WorkerId
    opaque_delivery_item: str
    opaque_result_context: str

    def __post_init__(self) -> None:
        _require_non_empty_text(self.worker_id, "worker id")
        _require_non_empty_text(
            self.opaque_delivery_item,
            "opaque delivery item",
        )
        _require_non_empty_text(
            self.opaque_result_context,
            "opaque result context",
        )


@dataclass(frozen=True, slots=True)
class WorkerCommandEnvelope:
    """Transport-neutral command passed unchanged through a Worker Adapter."""

    command_id: str
    message_type: WorkerMessageType
    execute_before_millis: TimeMillis
    opaque_item: str

    def __post_init__(self) -> None:
        _require_canonical_uuid(self.command_id)
        if not isinstance(self.message_type, WorkerMessageType):
            raise TypeError("message type must be WorkerMessageType")
        if (
            isinstance(self.execute_before_millis, bool)
            or not isinstance(self.execute_before_millis, int)
            or self.execute_before_millis <= 0
        ):
            raise ValueError("execute-before deadline must be positive")
        _require_non_empty_text(self.opaque_item, "opaque item")


class WorkerCommandAppendStatus(Enum):
    """Per-Worker outcome of one mailbox append."""

    APPENDED = "APPENDED"
    REPLACED = "REPLACED"


class WorkerCommandRuntime(ABC):
    """Runtime owner for Adapter-partitioned Worker command mailboxes."""

    @abstractmethod
    def append_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_commands_by_worker_id: Mapping[
            WorkerId,
            WorkerCommandEnvelope,
        ],
    ) -> Mapping[WorkerId, WorkerCommandAppendStatus]:
        """Publish each command to its Adapter-local Worker slot."""
        pass

    @abstractmethod
    def consume_worker_command(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_id: WorkerId,
    ) -> WorkerCommandEnvelope | None:
        """Atomically consume one Worker slot from one Adapter mailbox."""
        pass

    @abstractmethod
    def consume_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        limit: int,
    ) -> Mapping[WorkerId, WorkerCommandEnvelope]:
        """Observe and atomically consume a bounded Worker-command batch."""
        pass


def encode_deliver_seed(seed: DeliverSeed) -> str:
    return _encode_json(
        {
            "opaqueDeliveryItem": seed.opaque_delivery_item,
            "opaqueResultContext": seed.opaque_result_context,
            "workerId": seed.worker_id,
        }
    )


def decode_deliver_seed(value: str | bytes) -> DeliverSeed | None:
    payload = _decode_json_mapping(value)
    if payload is None or set(payload) != {
        "opaqueDeliveryItem",
        "opaqueResultContext",
        "workerId",
    }:
        return None
    try:
        return DeliverSeed(
            worker_id=payload["workerId"],
            opaque_delivery_item=payload["opaqueDeliveryItem"],
            opaque_result_context=payload["opaqueResultContext"],
        )
    except (TypeError, ValueError):
        return None


def encode_worker_command_envelope(
    command: WorkerCommandEnvelope,
) -> str:
    return _encode_json(
        {
            "commandId": command.command_id,
            "executeBeforeMillis": command.execute_before_millis,
            "messageType": command.message_type.value,
            "opaqueItem": command.opaque_item,
        }
    )


def decode_worker_command_envelope(
    value: str | bytes,
) -> WorkerCommandEnvelope | None:
    payload = _decode_json_mapping(value)
    if payload is None or set(payload) != {
        "commandId",
        "executeBeforeMillis",
        "messageType",
        "opaqueItem",
    }:
        return None
    try:
        return WorkerCommandEnvelope(
            command_id=payload["commandId"],
            message_type=WorkerMessageType(payload["messageType"]),
            execute_before_millis=payload["executeBeforeMillis"],
            opaque_item=payload["opaqueItem"],
        )
    except (TypeError, ValueError):
        return None


def _require_non_empty_text(value: object, name: str) -> None:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{name} must be non-empty")


def _require_canonical_uuid(value: object) -> None:
    _require_non_empty_text(value, "command id")
    assert isinstance(value, str)
    try:
        parsed = UUID(value)
    except ValueError as error:
        raise ValueError("command id must be a canonical UUID") from error
    if str(parsed) != value:
        raise ValueError("command id must be a canonical UUID")


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
