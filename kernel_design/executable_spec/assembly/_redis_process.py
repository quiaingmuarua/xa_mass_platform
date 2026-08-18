from __future__ import annotations

from dataclasses import dataclass
from time import monotonic
from typing import Any, Mapping

from ..kernel.worker_runtime import MappedWorkerPropertyIndexRuntime

from ..scheduling import (
    DueTaskItemAdmissionPolicy,
    RunningSoftLimitSystemAdmissionPolicy,
    ResultRoutingBuiltinPolicies,
    ResultRoutingPacer,
    TaskDispatchPacer,
    TaskDispatchWakeInbox,
    TaskItemDispatcher,
    TaskRunningActivationPacer,
    TaskWorkerAllocationPacer,
    WorkerCandidateMatcher,
    WorkerServiceabilityDispatchPacer,
    WorkerServiceabilityResultPacer,
)
from ..scheduling.worker_candidate import WorkerCandidateAcquirer
from ..redis_runtime import (
    RedisCandidateWorkerCache,
    RedisWorkerCommandRuntime,
    RedisTaskItemScoreBandCore,
    RedisTaskResourceCatalog,
    RedisTaskRuntime,
    RedisTaskScoreBandCore,
    RedisHashWorkerPropertyIndexProvider,
    RedisWorkerResourceCatalog,
    RedisWorkerScoreCore,
    RedisWorkerResultRuntime,
    RedisWorkerServiceabilityRuntime,
)
from ..redis_runtime.assignment_dispatch import RedisCandidateWarmupSchedule
from .assignment_dispatch_application import (
    AssignmentDispatchApplication,
    AssignmentDispatchApplicationConfig,
)
from .result_routing_application import (
    ResultRoutingApplication,
    ResultRoutingApplicationConfig,
)
from .worker_serviceability_application import (
    WorkerServiceabilityDispatchApplication,
    WorkerServiceabilityDispatchApplicationConfig,
    WorkerServiceabilityResultApplication,
    WorkerServiceabilityResultApplicationConfig,
)


@dataclass(frozen=True, slots=True)
class _RedisKernelProcessConfig:
    prefix: str
    running_task_soft_limit: int
    worker_candidate_scan_limit: int
    worker_property_indexes: Mapping[str, str]
    assignment_dispatch: AssignmentDispatchApplicationConfig
    result_routing: ResultRoutingApplicationConfig
    worker_serviceability_dispatch: (
        WorkerServiceabilityDispatchApplicationConfig | None
    )
    worker_serviceability_result: (
        WorkerServiceabilityResultApplicationConfig | None
    )
    stop_timeout_millis: int

    def __post_init__(self) -> None:
        if not self.prefix:
            raise ValueError("Redis kernel prefix must be non-empty")
        if self.running_task_soft_limit <= 0:
            raise ValueError("running Task soft limit must be positive")
        if self.worker_candidate_scan_limit <= 0:
            raise ValueError("Worker candidate scan limit must be positive")
        if any(
            implementation != "redis-hash"
            for implementation in self.worker_property_indexes.values()
        ):
            raise ValueError("unsupported Worker property index implementation")
        if self.stop_timeout_millis <= 0:
            raise ValueError("process stop timeout must be positive")
        if (self.worker_serviceability_dispatch is None) != (
            self.worker_serviceability_result is None
        ):
            raise ValueError(
                "serviceability dispatch and result must be configured together"
            )


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
        self._worker_property_index_provider = (
            RedisHashWorkerPropertyIndexProvider(
                redis_client,
                prefix=config.prefix,
            )
        )
        self._worker_property_index_runtime = (
            MappedWorkerPropertyIndexRuntime(
                self._worker_resource_catalog,
                {
                    property_field: self._worker_property_index_provider.create(
                        property_field
                    )
                    for property_field, implementation
                    in config.worker_property_indexes.items()
                    if implementation == "redis-hash"
                },
            )
        )

        candidate_cache = RedisCandidateWorkerCache(
            redis_client,
            prefix=config.prefix,
        )
        candidate_warmup_schedule = RedisCandidateWarmupSchedule(
            redis_client,
            prefix=config.prefix,
        )
        self._worker_command_runtime = RedisWorkerCommandRuntime(
            redis_client,
            prefix=config.prefix,
        )
        worker_candidate_matcher = WorkerCandidateMatcher(
            self._worker_resource_catalog,
            self._worker_property_index_runtime,
        )
        candidate_acquirer = WorkerCandidateAcquirer(
            candidate_cache,
            self._worker_score,
            worker_candidate_matcher,
            worker_scan_limit=config.worker_candidate_scan_limit,
        )

        worker_allocation_pacer = TaskWorkerAllocationPacer(
            candidate_warmup_schedule,
            self._task_score,
            self._task_resource_catalog,
            candidate_acquirer,
            candidate_cache,
        )
        running_activation_pacer = TaskRunningActivationPacer(
            self._task_score,
            self._task_resource_catalog,
            DueTaskItemAdmissionPolicy(task_item_score),
            RunningSoftLimitSystemAdmissionPolicy(
                self._task_score,
                running_task_soft_limit=config.running_task_soft_limit,
            ),
            candidate_warmup_schedule,
        )
        task_item_dispatcher = TaskItemDispatcher(
            task_item_score,
            self._task_runtime,
            candidate_acquirer,
            candidate_warmup_schedule,
        )
        self._task_dispatch_wake_inbox = TaskDispatchWakeInbox()
        task_dispatch_pacer = TaskDispatchPacer(
            self._task_score,
            self._task_resource_catalog,
            self._worker_command_runtime,
            task_item_score,
            candidate_warmup_schedule,
            task_item_dispatcher,
            self._task_dispatch_wake_inbox,
        )
        self._assignment_dispatch_application = AssignmentDispatchApplication(
            worker_allocation_pacer,
            running_activation_pacer,
            task_dispatch_pacer,
        )
        self._worker_result_runtime = RedisWorkerResultRuntime(
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
                self._worker_result_runtime,
                task_result_handlers=result_routing_policies.default_task_result_handlers(),
                worker_result_handlers=result_routing_policies.default_worker_result_handlers(),
            )
        )
        self._worker_serviceability_dispatch_application: (
            WorkerServiceabilityDispatchApplication | None
        ) = None
        self._worker_serviceability_result_application: (
            WorkerServiceabilityResultApplication | None
        ) = None
        if config.worker_serviceability_dispatch is not None:
            serviceability_runtime = RedisWorkerServiceabilityRuntime(
                redis_client,
                prefix=config.prefix,
            )
            self._worker_serviceability_result_application = (
                WorkerServiceabilityResultApplication(
                    WorkerServiceabilityResultPacer(
                        serviceability_runtime,
                        self._worker_resource_catalog,
                        self._worker_score,
                    )
                )
            )
            self._worker_serviceability_dispatch_application = (
                WorkerServiceabilityDispatchApplication(
                    WorkerServiceabilityDispatchPacer(
                        self._worker_score,
                        self._worker_resource_catalog,
                        serviceability_runtime,
                    )
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
        started_serviceability_result = False
        started_serviceability_dispatch = False
        try:
            if self._worker_serviceability_result_application is not None:
                assert self._config.worker_serviceability_result is not None
                self._worker_serviceability_result_application.start(
                    config=self._config.worker_serviceability_result,
                )
                started_serviceability_result = True
            if self._worker_serviceability_dispatch_application is not None:
                assert self._config.worker_serviceability_dispatch is not None
                self._worker_serviceability_dispatch_application.start(
                    config=self._config.worker_serviceability_dispatch,
                )
                started_serviceability_dispatch = True
            self._assignment_dispatch_application.start(
                config=self._config.assignment_dispatch,
            )
        except Exception as start_error:
            rollback_error: Exception | None = None
            rollback_deadline = (
                monotonic() + self._config.stop_timeout_millis / 1_000
            )
            for started, application in (
                (
                    started_serviceability_dispatch,
                    self._worker_serviceability_dispatch_application,
                ),
                (
                    started_serviceability_result,
                    self._worker_serviceability_result_application,
                ),
                (True, self._result_routing_application),
            ):
                if not started or application is None:
                    continue
                try:
                    application.stop(
                        timeout_millis=self._remaining_timeout_millis(
                            rollback_deadline
                        ),
                    )
                except Exception as error:
                    rollback_error = rollback_error or error
            if rollback_error is not None:
                raise start_error from rollback_error
            raise

    def stop(self) -> None:
        first_error: Exception | None = None
        deadline = monotonic() + self._config.stop_timeout_millis / 1_000
        for application in (
            self._assignment_dispatch_application,
            self._worker_serviceability_dispatch_application,
            self._worker_serviceability_result_application,
            self._result_routing_application,
        ):
            if application is None:
                continue
            try:
                application.stop(
                    timeout_millis=self._remaining_timeout_millis(deadline),
                )
            except Exception as error:
                first_error = first_error or error
        if first_error is not None:
            raise first_error

    @staticmethod
    def _remaining_timeout_millis(deadline: float) -> int:
        return max(1, int((deadline - monotonic()) * 1_000))
