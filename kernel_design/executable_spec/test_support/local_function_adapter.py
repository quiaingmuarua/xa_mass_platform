from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from time import time_ns
from types import MappingProxyType

from ..assembly import (
    SeedResult,
    SeedResultCommandClient,
    SeedResultOutcomeClass,
    WorkerCommandConsumerClient,
    classify_seed_result_outcome_code,
    decode_deliver_seed,
)


WORKER_HANDLER_FAILURE_OUTCOME_CODE = "1500"
WORKER_HANDLER_UNAVAILABLE_OUTCOME_CODE = "1404"


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
        outcome_class = classify_seed_result_outcome_code(self.outcome_code)
        if outcome_class not in {
            SeedResultOutcomeClass.SUCCESS,
            SeedResultOutcomeClass.WORKER_FAILURE,
        }:
            raise ValueError("handler outcome code must be 200 or 1xxx")


@dataclass(frozen=True, slots=True)
class _DeliveryItem:
    event_code: str
    payload: Mapping[str, object]


EventHandler = Callable[[Mapping[str, object], WorkerMeta], EventHandlerResult]


class LocalFunctionTransportAdapter:
    """Deterministic test harness backed by local Python handlers."""

    def __init__(
        self,
        *,
        endpoint_manager_id: str,
        worker_command_consumer: WorkerCommandConsumerClient,
        seed_result_commands: SeedResultCommandClient,
    ) -> None:
        if not endpoint_manager_id:
            raise ValueError("endpoint manager id must be non-empty")
        self.endpoint_manager_id = endpoint_manager_id
        self.worker_command_consumer = worker_command_consumer
        self.seed_result_commands = seed_result_commands
        self.workers: dict[str, WorkerMeta] = {}
        self.handlers: dict[str, EventHandler] = {}
        self._cursor: str | None = None

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
        page = self.worker_command_consumer.consume_worker_commands(
            endpoint_manager_id=self.endpoint_manager_id,
            cursor=self._cursor,
            scan_count=limit,
        )
        self._cursor = page.next_cursor
        results: list[SeedResult] = []

        for worker_id, command in page.worker_commands_by_worker_id.items():
            if self._current_time_millis() >= command.execute_before_millis:
                continue
            seed = decode_deliver_seed(command.opaque_item)
            if seed is None or seed.worker_id != worker_id:
                continue
            item = self._decode_delivery_item(seed.opaque_delivery_item)
            if item is None:
                continue
            worker = self.workers.get(worker_id)
            if worker is None:
                continue
            handler = self.handlers.get(item.event_code)
            if handler is None:
                result = SeedResult(
                    command_id=command.command_id,
                    opaque_result_context=seed.opaque_result_context,
                    outcome_code=WORKER_HANDLER_UNAVAILABLE_OUTCOME_CODE,
                )
                results.append(result)
                continue

            try:
                handled = handler(item.payload, worker)
                if not isinstance(handled, EventHandlerResult):
                    raise TypeError("handler must return EventHandlerResult")
                opaque_payload = self._encode_result_payload(handled.payload)
                result = SeedResult(
                    command_id=command.command_id,
                    opaque_result_context=seed.opaque_result_context,
                    outcome_code=handled.outcome_code,
                    opaque_result_payload=opaque_payload,
                )
            except Exception:
                result = SeedResult(
                    command_id=command.command_id,
                    opaque_result_context=seed.opaque_result_context,
                    outcome_code=WORKER_HANDLER_FAILURE_OUTCOME_CODE,
                )
            results.append(result)

        if not results:
            return 0
        return self.seed_result_commands.append_seed_results(
            results=tuple(results)
        )

    @staticmethod
    def _decode_delivery_item(value: str) -> _DeliveryItem | None:
        try:
            payload = json.loads(value)
            if not isinstance(payload, Mapping):
                return None
            event_code = payload["eventCode"]
            item_payload = payload["payload"]
        except (KeyError, TypeError, ValueError):
            return None
        if not isinstance(event_code, str) or not event_code:
            return None
        if not isinstance(item_payload, Mapping):
            return None
        return _DeliveryItem(event_code, MappingProxyType(dict(item_payload)))

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
