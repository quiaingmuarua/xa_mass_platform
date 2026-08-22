from __future__ import annotations

from typing import Any, Sequence

from ..kernel.worker_delivery import (
    DeliveryReport,
    DeliveryReportOutcomeClass,
    classify_delivery_report_outcome_code,
    decode_delivery_report,
    encode_delivery_report,
)
from ..kernel.worker_result_runtime import WorkerResultRuntime
from .keyspace import RedisKeyspace


class RedisWorkerResultRuntime(WorkerResultRuntime):
    """Redis LIST implementation partitioned by outcome class."""

    def __init__(
        self,
        redis_client: Any,
        *,
        keyspace: RedisKeyspace,
    ) -> None:
        if not isinstance(keyspace, RedisKeyspace):
            raise TypeError("keyspace must be RedisKeyspace")
        self.redis = redis_client
        self.keyspace = keyspace

    def append_worker_results(
        self,
        *,
        results: Sequence[DeliveryReport],
    ) -> int:
        if not results:
            return 0
        grouped: dict[DeliveryReportOutcomeClass, list[str]] = {}
        for result in results:
            outcome_class = classify_delivery_report_outcome_code(result.outcome_code)
            if outcome_class is None:
                raise ValueError("DeliveryReport outcome code is invalid")
            grouped.setdefault(outcome_class, []).append(
                encode_delivery_report(result)
            )

        with self.redis.pipeline(transaction=False) as pipeline:
            for outcome_class, encoded_results in grouped.items():
                pipeline.rpush(
                    self._queue_key(outcome_class),
                    *encoded_results,
                )
            pipeline.execute()
        return len(results)

    def consume_worker_results(
        self,
        *,
        outcome_class: DeliveryReportOutcomeClass,
        limit: int,
    ) -> tuple[DeliveryReport, ...]:
        if not isinstance(outcome_class, DeliveryReportOutcomeClass):
            raise TypeError("outcome_class must be DeliveryReportOutcomeClass")
        if limit <= 0:
            raise ValueError("consume limit must be positive")

        queue_key = self._queue_key(outcome_class)
        with self.redis.pipeline(transaction=True) as pipeline:
            for _ in range(limit):
                pipeline.lpop(queue_key)
            raw_results = pipeline.execute()

        results: list[DeliveryReport] = []
        for raw_result in raw_results:
            if raw_result is None:
                continue
            result = decode_delivery_report(raw_result)
            if result is not None:
                results.append(result)
        return tuple(results)

    def _queue_key(self, outcome_class: DeliveryReportOutcomeClass) -> str:
        return (
            f"{self.keyspace.base}:result:routing:"
            f"{outcome_class.value.lower().replace('_', '-')}"
        )
