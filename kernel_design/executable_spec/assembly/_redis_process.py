from __future__ import annotations

from dataclasses import dataclass
from time import monotonic
from typing import Any

from ..kernel.worker_score import WorkerScoreCore

from ..scheduling import (
    TaskResultBatchPolicy,
    TaskCallItemSubmission,
    TaskDispatchPolicy,
    TaskItemDispatcher,
    TaskInitializationPolicy,
    TaskSchedulingBatchSource,
    TaskWorkerAllocationPolicy,
    WorkerCandidateMatcher,
    WorkerServiceabilityDispatchPolicy,
    WorkerServiceabilityResultConfig,
    WorkerServiceabilityResultPolicy,
)
from ..kernel.task_result_runtime import TaskResultClass
from ..scheduling.worker_candidate import WorkerCandidateAcquirer
from ..redis_runtime import (
    RedisKeyspace,
    RedisCandidateWorkerCache,
    RedisWorkerCommandRuntime,
    RedisTaskItemScoreBandCore,
    RedisTaskResourceCatalog,
    RedisTaskRuntime,
    RedisTaskScoreBandCore,
    RedisWorkerResourceCatalog,
    RedisWorkerScoreCore,
    RedisTaskResultRuntime,
    RedisWorkerServiceabilityRuntime,
)
from .dispatch_convergence_application import (
    AssignmentDispatchConfig,
    DispatchConvergenceApplication,
    WorkerServiceabilityDispatchLaneConfig,
)
from .result_convergence_application import (
    ResultConvergenceApplication,
    _ResultLane,
    _ResultLaneId,
)


_RESULT_CONVERGENCE_GLOBAL_MAX_CONCURRENCY = 10
_TASK_SUCCESS_TARGET_CONCURRENCY = 6
_TASK_SUCCESS_MAX_CONCURRENCY = 10
_TASK_FAILURE_TARGET_CONCURRENCY = 3
_TASK_FAILURE_MAX_CONCURRENCY = 10
_ADAPTER_EVIDENCE_TARGET_CONCURRENCY = 1
_ADAPTER_EVIDENCE_MAX_CONCURRENCY = 1


@dataclass(frozen=True, slots=True)
class _RedisKernelProcessConfig:
    keyspace: RedisKeyspace
    worker_candidate_scan_limit: int
    hot_eligibility_floor_millis: int | None
    assignment_dispatch: AssignmentDispatchConfig
    task_result_batch_limit: int
    task_result_idle_interval_millis: int
    worker_serviceability_dispatch: (
        WorkerServiceabilityDispatchLaneConfig | None
    )
    worker_serviceability_result: WorkerServiceabilityResultConfig | None
    worker_serviceability_result_idle_interval_millis: int | None
    stop_timeout_millis: int

    def __post_init__(self) -> None:
        if not isinstance(self.keyspace, RedisKeyspace):
            raise TypeError("Redis keyspace must be RedisKeyspace")
        if self.worker_candidate_scan_limit <= 0:
            raise ValueError("Worker candidate scan limit must be positive")
        if self.hot_eligibility_floor_millis is not None and (
            isinstance(self.hot_eligibility_floor_millis, bool)
            or not isinstance(self.hot_eligibility_floor_millis, int)
            or self.hot_eligibility_floor_millis <= 0
            or self.hot_eligibility_floor_millis
            % WorkerScoreCore.SLOT_MILLIS != 0
        ):
            raise ValueError("HOT eligibility floor must be score-slot aligned")
        if self.stop_timeout_millis <= 0:
            raise ValueError("process stop timeout must be positive")
        if not 1 <= self.task_result_batch_limit <= 100:
            raise ValueError("Task result batch limit must be between 1 and 100")
        if self.task_result_idle_interval_millis <= 0:
            raise ValueError("Task result idle interval must be positive")
        if self.worker_serviceability_result is None:
            if self.worker_serviceability_result_idle_interval_millis is not None:
                raise ValueError(
                    "Adapter evidence idle interval requires result policy"
                )
        elif (
            self.worker_serviceability_result_idle_interval_millis is None
            or self.worker_serviceability_result_idle_interval_millis <= 0
        ):
            raise ValueError("Adapter evidence idle interval must be positive")
        if (
            self.worker_serviceability_result is not None
            or self.worker_serviceability_dispatch is not None
        ) and self.hot_eligibility_floor_millis is None:
            raise ValueError(
                "HOT eligibility floor belongs to enabled serviceability"
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
            keyspace=config.keyspace,
        )
        self._task_item_score = RedisTaskItemScoreBandCore(
            redis_client,
            keyspace=config.keyspace,
        )
        self._task_runtime = RedisTaskRuntime(
            redis_client,
            self._task_score,
            self._task_item_score,
            keyspace=config.keyspace,
        )
        self._task_resource_catalog = RedisTaskResourceCatalog(
            redis_client,
            keyspace=config.keyspace,
        )

        self._worker_score = RedisWorkerScoreCore(
            redis_client,
            keyspace=config.keyspace,
        )
        self._worker_resource_catalog = RedisWorkerResourceCatalog(
            redis_client,
            keyspace=config.keyspace,
        )
        candidate_cache = RedisCandidateWorkerCache(
            redis_client,
            keyspace=config.keyspace,
        )
        self._worker_command_runtime = RedisWorkerCommandRuntime(
            redis_client,
            keyspace=config.keyspace,
        )
        worker_candidate_matcher = WorkerCandidateMatcher(
            self._worker_resource_catalog,
        )
        candidate_acquirer = WorkerCandidateAcquirer(
            candidate_cache,
            self._worker_score,
            worker_candidate_matcher,
            worker_scan_limit=config.worker_candidate_scan_limit,
            hot_eligibility_floor_millis=(
                config.hot_eligibility_floor_millis
            ),
        )

        worker_allocation_policy = TaskWorkerAllocationPolicy(
            candidate_acquirer,
            candidate_cache,
        )
        task_initialization_policy = TaskInitializationPolicy(
            self._task_score,
            self._task_item_score,
        )
        task_item_dispatcher = TaskItemDispatcher(
            self._task_item_score,
            self._task_runtime,
            candidate_acquirer,
        )
        task_dispatch_policy = TaskDispatchPolicy(
            self._task_score,
            self._worker_command_runtime,
            self._task_item_score,
            task_item_dispatcher,
        )
        self._task_call_item_submission = TaskCallItemSubmission(
            self._task_score,
            self._task_runtime,
        )
        self._task_result_runtime = RedisTaskResultRuntime(
            redis_client,
            keyspace=config.keyspace,
        )
        task_result_policy = TaskResultBatchPolicy(
            task_runtime=self._task_runtime,
            item_score=self._task_item_score,
            worker_score=self._worker_score,
        )
        result_lanes = [
            _ResultLane(
                lane_id=_ResultLaneId.TASK_SUCCESS,
                batch_limit=config.task_result_batch_limit,
                idle_poll_interval_millis=(
                    config.task_result_idle_interval_millis
                ),
                target_concurrency=_TASK_SUCCESS_TARGET_CONCURRENCY,
                max_concurrency=_TASK_SUCCESS_MAX_CONCURRENCY,
                consumer=lambda limit: (
                    self._task_result_runtime.consume_task_results(
                        result_class=TaskResultClass.SUCCESS,
                        limit=limit,
                    )
                ),
                policy=task_result_policy.handle_success,
            ),
            _ResultLane(
                lane_id=_ResultLaneId.TASK_FAILURE,
                batch_limit=config.task_result_batch_limit,
                idle_poll_interval_millis=(
                    config.task_result_idle_interval_millis
                ),
                target_concurrency=_TASK_FAILURE_TARGET_CONCURRENCY,
                max_concurrency=_TASK_FAILURE_MAX_CONCURRENCY,
                consumer=lambda limit: (
                    self._task_result_runtime.consume_task_results(
                        result_class=TaskResultClass.FAILURE,
                        limit=limit,
                    )
                ),
                policy=task_result_policy.handle_failure,
            ),
        ]
        serviceability_dispatch_policy: (
            WorkerServiceabilityDispatchPolicy | None
        ) = None
        if (
            config.worker_serviceability_dispatch is not None
            or config.worker_serviceability_result is not None
        ):
            serviceability_runtime = RedisWorkerServiceabilityRuntime(
                redis_client,
                keyspace=config.keyspace,
            )
            if config.worker_serviceability_result is not None:
                assert config.hot_eligibility_floor_millis is not None
                assert (
                    config.worker_serviceability_result_idle_interval_millis
                    is not None
                )
                evidence_policy = WorkerServiceabilityResultPolicy(
                    self._worker_resource_catalog,
                    self._worker_score,
                    config=config.worker_serviceability_result,
                    hot_eligibility_floor_millis=(
                        config.hot_eligibility_floor_millis
                    ),
                )
                result_lanes.append(_ResultLane(
                    lane_id=_ResultLaneId.ADAPTER_EVIDENCE,
                    batch_limit=(
                        config.worker_serviceability_result.result_report_limit
                    ),
                    idle_poll_interval_millis=(
                        config
                        .worker_serviceability_result_idle_interval_millis
                    ),
                    target_concurrency=(
                        _ADAPTER_EVIDENCE_TARGET_CONCURRENCY
                    ),
                    max_concurrency=_ADAPTER_EVIDENCE_MAX_CONCURRENCY,
                    consumer=lambda limit: (
                        serviceability_runtime
                        .consume_adapter_evidence_results(limit=limit)
                    ),
                    policy=evidence_policy.handle,
                ))
            if config.worker_serviceability_dispatch is not None:
                serviceability_dispatch_policy = (
                    WorkerServiceabilityDispatchPolicy(
                        self._worker_score,
                        self._worker_resource_catalog,
                        serviceability_runtime,
                        hot_eligibility_floor_millis=(
                            config.hot_eligibility_floor_millis
                        ),
                    )
                )
        self._dispatch_convergence_application = (
            DispatchConvergenceApplication(
                TaskSchedulingBatchSource(
                    self._task_score,
                    self._task_resource_catalog,
                ),
                task_initialization_policy,
                worker_allocation_policy,
                task_dispatch_policy,
                serviceability_dispatch_policy,
            )
        )
        self._result_convergence_application = ResultConvergenceApplication(
            result_lanes,
            global_max_concurrency=(
                _RESULT_CONVERGENCE_GLOBAL_MAX_CONCURRENCY
            ),
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
        result_convergence_started = False
        dispatch_convergence_started = False
        try:
            self._result_convergence_application.start()
            result_convergence_started = True
            self._dispatch_convergence_application.start(
                assignment=self._config.assignment_dispatch,
                serviceability=self._config.worker_serviceability_dispatch,
            )
            dispatch_convergence_started = True
        except Exception as start_error:
            rollback_error: Exception | None = None
            rollback_deadline = (
                monotonic() + self._config.stop_timeout_millis / 1_000
            )
            for started, application in (
                (
                    dispatch_convergence_started,
                    self._dispatch_convergence_application,
                ),
                (
                    result_convergence_started,
                    self._result_convergence_application,
                ),
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
            self._dispatch_convergence_application,
            self._result_convergence_application,
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
