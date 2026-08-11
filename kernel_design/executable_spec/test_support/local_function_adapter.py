from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from time import time_ns
from types import MappingProxyType

from ..assembly import (
    WorkerMessageEndpoint,
    WorkerResult,
    WorkerResultCommandClient,
    WorkerResultOutcomeClass,
    WorkerCommandConsumerClient,
    classify_worker_result_outcome_code,
)


WORKER_HANDLER_FAILURE_OUTCOME_CODE = "3500"
WORKER_HANDLER_UNAVAILABLE_OUTCOME_CODE = "3404"


@dataclass(frozen=True, slots=True)
class WorkerMeta:
    """Process-local context supplied to a test Worker handler."""

    attributes: Mapping[str, object]

    def __post_init__(self) -> None:
        if not isinstance(self.attributes, Mapping):
            raise ValueError("worker attributes must be a mapping")
        object.__setattr__(
            self,
            "attributes",
            MappingProxyType(dict(self.attributes)),
        )


@dataclass(frozen=True, slots=True)
class EventHandlerResult:
    outcome_code: str
    payload: object | None = None

    def __post_init__(self) -> None:
        outcome_class = classify_worker_result_outcome_code(self.outcome_code)
        if outcome_class not in {
            WorkerResultOutcomeClass.SUCCESS,
            WorkerResultOutcomeClass.WORKER_FAILURE,
        }:
            raise ValueError(
                "handler outcome code must be success or Worker failure"
            )


EventHandler = Callable[[Mapping[str, object], WorkerMeta], EventHandlerResult]


class LocalFunctionTransportAdapter:
    """Deterministic test harness backed by local Python handlers."""

    def __init__(
        self,
        *,
        endpoint_manager_id: str,
        worker_command_consumer: WorkerCommandConsumerClient,
        worker_result_commands: WorkerResultCommandClient,
    ) -> None:
        if not endpoint_manager_id:
            raise ValueError("endpoint manager id must be non-empty")
        self.endpoint_manager_id = endpoint_manager_id
        self.worker_command_consumer = worker_command_consumer
        self.worker_result_commands = worker_result_commands
        self.workers: dict[str, WorkerMeta] = {}
        self.handlers: dict[str, EventHandler] = {}

    def register_worker(self, worker_id: str, metadata: WorkerMeta) -> None:
        if not worker_id:
            raise ValueError("worker id must be non-empty")
        if not isinstance(metadata, WorkerMeta):
            raise TypeError("metadata must be WorkerMeta")
        self.workers[worker_id] = metadata

    def unregister_worker(self, worker_id: str) -> None:
        if not worker_id:
            raise ValueError("worker id must be non-empty")
        self.workers.pop(worker_id, None)

    def register_event_handler(
        self,
        event_code: str,
        handler: EventHandler,
    ) -> None:
        if not event_code:
            raise ValueError("event code must be non-empty")
        if not callable(handler):
            raise TypeError("event handler must be callable")
        self.handlers[event_code] = handler

    def drain_once(self, *, limit: int) -> int:
        if limit <= 0:
            raise ValueError("drain limit must be positive")
        worker_commands = self.worker_command_consumer.consume_worker_commands(
            endpoint_manager_id=self.endpoint_manager_id,
            limit=limit,
        )
        results: list[WorkerResult] = []

        for worker_id, command in worker_commands.items():
            if self._current_time_millis() >= command.execute_before_millis:
                continue
            item_payload = self._decode_event_payload(command.payload)
            if item_payload is None:
                continue
            worker = self.workers.get(worker_id)
            if worker is None:
                continue
            handler = self.handlers.get(command.message_type)
            if handler is None:
                result = WorkerResult(
                    message_id=command.message_id,
                    dst=command.src,
                    message_type=command.message_type,
                    outcome_code=WORKER_HANDLER_UNAVAILABLE_OUTCOME_CODE,
                    payload="null",
                    forward=command.forward,
                )
                results.append(result)
                continue

            try:
                handled = handler(item_payload, worker)
                if not isinstance(handled, EventHandlerResult):
                    raise TypeError("handler must return EventHandlerResult")
                opaque_payload = self._encode_result_payload(handled.payload)
                result = WorkerResult(
                    message_id=command.message_id,
                    dst=command.src,
                    message_type=command.message_type,
                    outcome_code=handled.outcome_code,
                    payload=opaque_payload,
                    forward=command.forward,
                )
            except Exception:
                result = WorkerResult(
                    message_id=command.message_id,
                    dst=command.src,
                    message_type=command.message_type,
                    outcome_code=WORKER_HANDLER_FAILURE_OUTCOME_CODE,
                    payload="null",
                    forward=command.forward,
                )
            results.append(result)

        if not results:
            return 0
        return self.worker_result_commands.append_worker_results(
            results=tuple(results)
        )

    @staticmethod
    def _decode_event_payload(
        value: str,
    ) -> Mapping[str, object] | None:
        try:
            payload = json.loads(value)
            if not isinstance(payload, Mapping):
                return None
        except (TypeError, ValueError):
            return None
        return MappingProxyType(dict(payload))

    @staticmethod
    def _encode_result_payload(payload: object | None) -> str:
        return json.dumps(
            payload,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )

    @staticmethod
    def _current_time_millis() -> int:
        return time_ns() // 1_000_000
