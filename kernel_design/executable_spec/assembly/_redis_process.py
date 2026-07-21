from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..scheduling import (
    DueTaskItemAdmissionPolicy,
    PrioritySoftLimitSystemAdmissionPolicy,
    ResultRoutingBuiltinPolicies,
    ResultRoutingPacer,
    TaskItemDispatchPacer,
    TaskRunningActivationPacer,
    TaskWorkerAllocationPacer,
    WorkerCandidateMatcher,
)
from ..scheduling.worker_candidate import WorkerCandidateAcquirer
from ..redis_runtime import (
    RedisCandidateWorkerCache,
    RedisDeliverSeedRuntime,
    RedisTaskItemScoreBandCore,
    RedisTaskResourceCatalog,
    RedisTaskRuntime,
    RedisTaskScoreBandCore,
    RedisWorkerDynamicAttributeRuntime,
    RedisWorkerResourceCatalog,
    RedisWorkerScoreCore,
    RedisSeedResultRuntime,
)
from .assignment_dispatch_application import (
    AssignmentDispatchApplication,
    AssignmentDispatchApplicationConfig,
)
from .result_routing_application import (
    ResultRoutingApplication,
    ResultRoutingApplicationConfig,
)


@dataclass(frozen=True, slots=True)
class _RedisKernelProcessConfig:
    prefix: str
    running_task_soft_limit: int
    worker_candidate_scan_limit: int
    assignment_dispatch: AssignmentDispatchApplicationConfig
    result_routing: ResultRoutingApplicationConfig
    stop_timeout_millis: int

    def __post_init__(self) -> None:
        if not self.prefix:
            raise ValueError("Redis kernel prefix must be non-empty")
        if self.running_task_soft_limit <= 0:
            raise ValueError("running Task soft limit must be positive")
        if self.worker_candidate_scan_limit <= 0:
            raise ValueError("Worker candidate scan limit must be positive")
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

        self._worker_score = RedisWorkerScoreCore(
            redis_client,
            score_key_prefix=f"wr:{config.prefix}:score",
        )
        self._worker_resource_catalog = RedisWorkerResourceCatalog(
            redis_client,
            prefix=config.prefix,
        )
        self._worker_dynamic_attribute_runtime = (
            RedisWorkerDynamicAttributeRuntime(
                self._worker_resource_catalog,
                update_handlers={},
            )
        )

        candidate_cache = RedisCandidateWorkerCache(
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
        candidate_acquirer = WorkerCandidateAcquirer(
            candidate_cache,
            self._worker_score,
            worker_candidate_matcher,
            self._worker_dynamic_attribute_runtime,
            worker_scan_limit=config.worker_candidate_scan_limit,
        )

        worker_allocation_pacer = TaskWorkerAllocationPacer(
            self._task_score,
            self._task_resource_catalog,
            candidate_acquirer,
            candidate_cache,
        )
        running_activation_pacer = TaskRunningActivationPacer(
            self._task_score,
            self._task_resource_catalog,
            DueTaskItemAdmissionPolicy(task_item_score),
            PrioritySoftLimitSystemAdmissionPolicy(
                self._task_score,
                running_task_soft_limit=config.running_task_soft_limit,
            ),
        )
        task_item_dispatch_pacer = TaskItemDispatchPacer(
            self._task_score,
            self._task_resource_catalog,
            self._deliver_seed_runtime,
            task_item_score,
            self._task_runtime,
            candidate_acquirer,
        )
        self._assignment_dispatch_application = AssignmentDispatchApplication(
            worker_allocation_pacer,
            running_activation_pacer,
            task_item_dispatch_pacer,
        )
        self._seed_result_runtime = RedisSeedResultRuntime(
            redis_client,
            prefix=config.prefix,
        )
        result_routing_policies = ResultRoutingBuiltinPolicies(
            task_runtime=self._task_runtime,
            item_score=task_item_score,
            worker_score=self._worker_score,
        )
        self._result_routing_application = ResultRoutingApplication(
            ResultRoutingPacer(
                self._seed_result_runtime,
                task_result_handlers=result_routing_policies.default_task_result_handlers(),
                worker_result_handlers=result_routing_policies.default_worker_result_handlers(),
            )
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
        self._result_routing_application.start(config=self._config.result_routing)
        try:
            self._assignment_dispatch_application.start(
                config=self._config.assignment_dispatch,
            )
        except Exception as start_error:
            try:
                self._result_routing_application.stop(
                    timeout_millis=self._config.stop_timeout_millis,
                )
            except Exception as rollback_error:
                raise start_error from rollback_error
            raise

    def stop(self) -> None:
        assignment_error: Exception | None = None
        try:
            self._assignment_dispatch_application.stop(
                timeout_millis=self._config.stop_timeout_millis,
            )
        except Exception as error:
            assignment_error = error
        try:
            self._result_routing_application.stop(
                timeout_millis=self._config.stop_timeout_millis,
            )
        except Exception as result_error:
            if assignment_error is not None:
                raise assignment_error from result_error
            raise
        if assignment_error is not None:
            raise assignment_error
