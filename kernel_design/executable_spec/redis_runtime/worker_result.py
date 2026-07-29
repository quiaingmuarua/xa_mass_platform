from __future__ import annotations

from typing import Any, Sequence

from ..kernel.worker_delivery import (
    WorkerResult,
    WorkerResultOutcomeClass,
    classify_worker_result_outcome_code,
    decode_worker_result,
    encode_worker_result,
)
from ..kernel.worker_result_runtime import WorkerResultRuntime


class RedisWorkerResultRuntime(WorkerResultRuntime):
    """Redis LIST implementation partitioned by outcome class."""

    def __init__(
        self,
        redis_client: Any,
        *,
        prefix: str = "default",
    ) -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        self.redis = redis_client
        self.prefix = prefix

    def append_worker_results(
        self,
        *,
        results: Sequence[WorkerResult],
    ) -> int:
        if not results:
            return 0
        grouped: dict[WorkerResultOutcomeClass, list[str]] = {}
        for result in results:
            outcome_class = classify_worker_result_outcome_code(result.outcome_code)
            if outcome_class is None:
                raise ValueError("WorkerResult outcome code is invalid")
            grouped.setdefault(outcome_class, []).append(
                encode_worker_result(result)
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
        outcome_class: WorkerResultOutcomeClass,
        limit: int,
    ) -> tuple[WorkerResult, ...]:
        if not isinstance(outcome_class, WorkerResultOutcomeClass):
            raise TypeError("outcome_class must be WorkerResultOutcomeClass")
        if limit <= 0:
            raise ValueError("consume limit must be positive")

        queue_key = self._queue_key(outcome_class)
        with self.redis.pipeline(transaction=True) as pipeline:
            for _ in range(limit):
                pipeline.lpop(queue_key)
            raw_results = pipeline.execute()

        results: list[WorkerResult] = []
        for raw_result in raw_results:
            if raw_result is None:
                continue
            result = decode_worker_result(raw_result)
            if result is not None:
                results.append(result)
        return tuple(results)

    def _queue_key(self, outcome_class: WorkerResultOutcomeClass) -> str:
        return (
            f"rr:{self.prefix}:worker-results:"
            f"{outcome_class.value.lower().replace('_', '-')}"
        )
