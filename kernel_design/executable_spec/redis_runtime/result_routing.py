from __future__ import annotations

from typing import Any, Sequence

from ..kernel.seed_result_runtime import (
    SeedResult,
    SeedResultOutcomeClass,
    SeedResultRuntime,
    classify_seed_result_outcome_code,
    decode_seed_result,
    encode_seed_result,
)


class RedisSeedResultRuntime(SeedResultRuntime):
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

    def append_seed_results(
        self,
        *,
        results: Sequence[SeedResult],
    ) -> int:
        if not results:
            return 0
        grouped: dict[SeedResultOutcomeClass, list[str]] = {}
        for result in results:
            outcome_class = classify_seed_result_outcome_code(result.outcome_code)
            if outcome_class is None:
                raise ValueError("SeedResult outcome code is invalid")
            grouped.setdefault(outcome_class, []).append(
                encode_seed_result(result)
            )

        with self.redis.pipeline(transaction=False) as pipeline:
            for outcome_class, encoded_results in grouped.items():
                pipeline.rpush(
                    self._queue_key(outcome_class),
                    *encoded_results,
                )
            pipeline.execute()
        return len(results)

    def consume_seed_results(
        self,
        *,
        outcome_class: SeedResultOutcomeClass,
        limit: int,
    ) -> tuple[SeedResult, ...]:
        if not isinstance(outcome_class, SeedResultOutcomeClass):
            raise TypeError("outcome_class must be SeedResultOutcomeClass")
        if limit <= 0:
            raise ValueError("consume limit must be positive")

        queue_key = self._queue_key(outcome_class)
        with self.redis.pipeline(transaction=True) as pipeline:
            for _ in range(limit):
                pipeline.lpop(queue_key)
            raw_results = pipeline.execute()

        results: list[SeedResult] = []
        for raw_result in raw_results:
            if raw_result is None:
                continue
            result = decode_seed_result(raw_result)
            if result is not None:
                results.append(result)
        return tuple(results)

    def _queue_key(self, outcome_class: SeedResultOutcomeClass) -> str:
        return (
            f"rr:{self.prefix}:seed-results:"
            f"{outcome_class.value.lower().replace('_', '-')}"
        )
