from __future__ import annotations

from typing import Any, Sequence

from ..kernel.task_result_runtime import (
    TaskResultClass,
    TaskResultRuntime,
)
from ..kernel.worker_delivery import (
    DeliveryReport,
    decode_delivery_report,
    encode_delivery_report,
)
from .keyspace import RedisKeyspace


class RedisTaskResultRuntime(TaskResultRuntime):
    """Redis LIST implementation partitioned by Task result class."""

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

    def append_task_results(
        self,
        *,
        result_class: TaskResultClass,
        results: Sequence[DeliveryReport],
    ) -> int:
        if not isinstance(result_class, TaskResultClass):
            raise TypeError("result_class must be TaskResultClass")
        if not results:
            return 0
        encoded_results: list[str] = []
        for result in results:
            if not isinstance(result, DeliveryReport):
                raise TypeError("results must contain DeliveryReport values")
            encoded_results.append(encode_delivery_report(result))
        self.redis.rpush(
            self._queue_key(result_class),
            *encoded_results,
        )
        return len(results)

    def consume_task_results(
        self,
        *,
        result_class: TaskResultClass,
        limit: int,
    ) -> tuple[DeliveryReport, ...]:
        if not isinstance(result_class, TaskResultClass):
            raise TypeError("result_class must be TaskResultClass")
        if limit <= 0:
            raise ValueError("consume limit must be positive")

        raw_results = self.redis.lpop(self._queue_key(result_class), limit)
        if raw_results is None:
            return ()

        results: list[DeliveryReport] = []
        for raw_result in raw_results:
            if raw_result is None:
                continue
            result = decode_delivery_report(raw_result)
            if result is not None:
                results.append(result)
        return tuple(results)

    def _queue_key(self, result_class: TaskResultClass) -> str:
        return (
            f"{self.keyspace.base}:result:routing:"
            f"{result_class.value.lower()}"
        )
