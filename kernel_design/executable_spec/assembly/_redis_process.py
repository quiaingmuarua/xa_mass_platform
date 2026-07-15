from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..assignment_dispatch import (
    TaskItemDispatchPacer,
    TaskRunningActivationPacer,
    TaskWorkerAllocationPacer,
    WorkerCandidateMatcher,
    minimum_candidate_workers_satisfied,
)
from ..redis_runtime import (
    RedisAssignmentDispatchRuntime,
    RedisDeliverSeedRuntime,
    RedisTaskItemScoreBandCore,
    RedisTaskResourceCatalog,
    RedisTaskRuntime,
    RedisTaskScoreBandCore,
    RedisWorkerDynamicAttributeRuntime,
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisWorkerScoreCore,
)
from .assignment_dispatch_application import (
    AssignmentDispatchApplication,
    AssignmentDispatchApplicationConfig,
)


@dataclass(frozen=True, slots=True)
class _RedisKernelProcessConfig:
    prefix: str
    assignment_dispatch: AssignmentDispatchApplicationConfig
    stop_timeout_millis: int

    def __post_init__(self) -> None:
        if not self.prefix:
            raise ValueError("Redis kernel prefix must be non-empty")
        if self.stop_timeout_millis <= 0:
            raise ValueError("process stop timeout must be positive")


class _RedisKernelProcess:
    """Private Redis composition root used only by KernelApplication."""

    def __init__(
        self,
        redis_client: Any,
        *,
        config: _RedisKernelProcessConfig,
    ) -> None:
        self._redis = redis_client
        self._config = config

        self._task_score = RedisTaskScoreBandCore(
            redis_client,
            score_key=f"tr:{config.prefix}:task:score",
        )
        task_item_score = RedisTaskItemScoreBandCore(
            redis_client,
            prefix=config.prefix,
        )
        self._task_runtime = RedisTaskRuntime(
            redis_client,
            self._task_score,
            task_item_score,
            prefix=config.prefix,
        )
        self._task_resource_catalog = RedisTaskResourceCatalog(
            redis_client,
            prefix=config.prefix,
        )

        worker_score = RedisWorkerScoreCore(
            redis_client,
            score_key_prefix=f"wr:{config.prefix}:score",
        )
        self._worker_resource_catalog = RedisWorkerResourceCatalog(
            redis_client,
            prefix=config.prefix,
        )
        self._worker_runtime = RedisWorkerRuntime(
            redis_client,
            worker_score,
            prefix=config.prefix,
        )
        self._worker_dynamic_attribute_runtime = (
            RedisWorkerDynamicAttributeRuntime(
                self._worker_resource_catalog,
                update_handlers={},
            )
        )

        candidate_runtime = RedisAssignmentDispatchRuntime(
            redis_client,
            prefix=config.prefix,
        )
        self._deliver_seed_runtime = RedisDeliverSeedRuntime(
            redis_client,
            prefix=config.prefix,
        )
        worker_candidate_matcher = WorkerCandidateMatcher(
            self._worker_resource_catalog,
            self._worker_dynamic_attribute_runtime,
        )

        worker_allocation_pacer = TaskWorkerAllocationPacer(
            self._task_score,
            self._task_resource_catalog,
            worker_score,
            worker_candidate_matcher,
            candidate_runtime,
        )
        running_activation_pacer = TaskRunningActivationPacer(
            self._task_score,
            self._task_resource_catalog,
            candidate_runtime,
            minimum_candidate_workers_satisfied,
        )
        task_item_dispatch_pacer = TaskItemDispatchPacer(
            self._task_score,
            candidate_runtime,
            self._deliver_seed_runtime,
            task_item_score,
            self._task_runtime,
        )
        self._assignment_dispatch_application = AssignmentDispatchApplication(
            worker_allocation_pacer,
            running_activation_pacer,
            task_item_dispatch_pacer,
        )

    @classmethod
    def from_url(
        cls,
        *,
        redis_url: str,
        config: _RedisKernelProcessConfig,
    ) -> _RedisKernelProcess:
        if not redis_url:
            raise ValueError("Redis URL must be non-empty")
        try:
            import redis
        except ImportError as error:
            raise RuntimeError("redis-py is required for KernelApplication") from error
        return cls(
            redis.Redis.from_url(redis_url, decode_responses=False),
            config=config,
        )

    def start(self) -> None:
        self._redis.ping()
        self._assignment_dispatch_application.start(
            config=self._config.assignment_dispatch,
        )

    def stop(self) -> None:
        self._assignment_dispatch_application.stop(
            timeout_millis=self._config.stop_timeout_millis,
        )
