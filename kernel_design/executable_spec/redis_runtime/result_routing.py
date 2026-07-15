from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any, Sequence

from ..kernel.seed_result_runtime import SeedResult, SeedResultRuntime


class RedisSeedResultRuntime(SeedResultRuntime):
    """Redis LIST implementation of the unified SeedResult queue."""

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
        self.redis.rpush(
            self._queue_key,
            *(self._encode_result(result) for result in results),
        )
        return len(results)

    def consume_seed_results(self, *, limit: int) -> tuple[SeedResult, ...]:
        if limit <= 0:
            raise ValueError("consume limit must be positive")

        with self.redis.pipeline(transaction=True) as pipeline:
            for _ in range(limit):
                pipeline.lpop(self._queue_key)
            raw_results = pipeline.execute()

        results: list[SeedResult] = []
        for raw_result in raw_results:
            if raw_result is None:
                continue
            result = self._decode_result(raw_result)
            if result is not None:
                results.append(result)
        return tuple(results)

    @property
    def _queue_key(self) -> str:
        return f"rr:{self.prefix}:seed-results"

    @staticmethod
    def _encode_result(result: SeedResult) -> str:
        return json.dumps(
            {
                "opaqueResultContext": result.opaque_result_context,
                "outcomeCode": result.outcome_code,
                "opaqueResultPayload": result.opaque_result_payload,
            },
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )

    @staticmethod
    def _decode_result(raw_result: Any) -> SeedResult | None:
        try:
            text = (
                raw_result.decode("utf-8")
                if isinstance(raw_result, bytes)
                else raw_result
            )
            payload = json.loads(text)
            if not isinstance(payload, Mapping):
                return None
            opaque_result_context = payload["opaqueResultContext"]
            outcome_code = payload["outcomeCode"]
            opaque_result_payload = payload["opaqueResultPayload"]
        except (KeyError, TypeError, ValueError, UnicodeDecodeError):
            return None

        if any(
            not isinstance(value, str) or not value
            for value in (opaque_result_context, outcome_code)
        ):
            return None
        if (
            opaque_result_payload is not None
            and (
                not isinstance(opaque_result_payload, str)
                or not opaque_result_payload
            )
        ):
            return None
        return SeedResult(
            opaque_result_context=opaque_result_context,
            outcome_code=outcome_code,
            opaque_result_payload=opaque_result_payload,
        )
