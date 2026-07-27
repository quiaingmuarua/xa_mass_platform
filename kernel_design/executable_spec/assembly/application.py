from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass, replace
from enum import Enum
from threading import Lock
from time import time_ns
from typing import Any

from ..scheduling import (
    TaskDispatchConfig,
    TaskRunningActivationConfig,
    TaskWorkerAllocationConfig,
)
from ..kernel import (
    TaskCreationResult,
    TaskDescriptor,
    TaskId,
    TaskResourceCatalog,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionStatus,
    TaskType,
)
from ..scheduling import ResultRoutingConfig
from ._redis_process import _RedisKernelProcess, _RedisKernelProcessConfig
from .assignment_dispatch_application import AssignmentDispatchApplicationConfig
from .result_routing_application import ResultRoutingApplicationConfig


_DEFAULT_REDIS_URL = "redis://localhost:6379/15"
_DEFAULT_REDIS_PREFIX = "default"
_DEFAULT_PACER_INTERVAL_MILLIS = 100
_DEFAULT_RESULT_ROUTING_INTERVAL_MILLIS = 100
_DEFAULT_STOP_TIMEOUT_MILLIS = 5_000
_DEFAULT_RUNNING_TASK_SOFT_LIMIT = 100

_INITIAL_PRE_REVIEW_SUFFIX = 1

_TASK_BATCH_LIMIT = 100
_WORKER_SCAN_LIMIT = 100
_WORKER_LEASE_DURATION_MILLIS = 5_000
_PER_TASK_DISPATCH_LIMIT = 100
_ITEM_CLAIM_LEASE_DURATION_MILLIS = 5_000
_MAX_EMPTY_RECHECK_TIMES = 5
_EMPTY_RECHECK_INTERVAL_MILLIS = 1_000
_ADMISSION_PRIORITY_RECHECK_STEP_MILLIS = 1_000
_ITEM_DRIVEN_DEFAULT_EMPTY_CLOSE_DELAY_MILLIS = 3 * 24 * 60 * 60 * 1_000
_RESULT_ROUTING_PER_OUTCOME_BATCH_LIMIT = 100


def _positive_integer(value: object, *, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{name} must be a positive integer")
    return value


def _non_empty_string(value: object, *, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{name} must be a non-empty string")
    return value


def _mapping(value: object, *, name: str) -> Mapping[str, object]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be an object")
    if any(not isinstance(key, str) for key in value):
        raise ValueError(f"{name} keys must be strings")
    return value


def _reject_unknown(
    values: Mapping[str, object],
    *,
    allowed: frozenset[str],
    name: str,
) -> None:
    unknown = set(values) - allowed
    if unknown:
        raise ValueError(f"unknown {name} fields: {', '.join(sorted(unknown))}")


@dataclass(frozen=True, slots=True)
class KernelApplicationConfig:
    redis_url: str = _DEFAULT_REDIS_URL
    redis_prefix: str = _DEFAULT_REDIS_PREFIX
    worker_allocation_interval_millis: int = _DEFAULT_PACER_INTERVAL_MILLIS
    running_activation_interval_millis: int = _DEFAULT_PACER_INTERVAL_MILLIS
    task_dispatch_interval_millis: int = _DEFAULT_PACER_INTERVAL_MILLIS
    result_routing_interval_millis: int = _DEFAULT_RESULT_ROUTING_INTERVAL_MILLIS
    running_task_soft_limit: int = _DEFAULT_RUNNING_TASK_SOFT_LIMIT
    stop_timeout_millis: int = _DEFAULT_STOP_TIMEOUT_MILLIS

    def __post_init__(self) -> None:
        _non_empty_string(self.redis_url, name="Redis URL")
        _non_empty_string(self.redis_prefix, name="Redis prefix")
        _positive_integer(
            self.worker_allocation_interval_millis,
            name="worker allocation interval",
        )
        _positive_integer(
            self.running_activation_interval_millis,
            name="running activation interval",
        )
        _positive_integer(
            self.task_dispatch_interval_millis,
            name="Task dispatch interval",
        )
        _positive_integer(
            self.result_routing_interval_millis,
            name="result-routing interval",
        )
        _positive_integer(
            self.running_task_soft_limit,
            name="running Task soft limit",
        )
        _positive_integer(self.stop_timeout_millis, name="stop timeout")

    @classmethod
    def from_json(cls, config_json: str | None = None) -> KernelApplicationConfig:
        if config_json is None:
            return _DEFAULT_KERNEL_APPLICATION_CONFIG
        if not isinstance(config_json, str) or not config_json:
            raise ValueError("kernel application config must be non-empty JSON text")
        try:
            raw_config = json.loads(config_json)
        except json.JSONDecodeError as error:
            raise ValueError("kernel application config is not valid JSON") from error
        config = _mapping(raw_config, name="kernel application config")
        _reject_unknown(
            config,
            allowed=frozenset(
                {
                    "redis",
                    "assignmentDispatch",
                    "systemPolicy",
                    "resultRouting",
                    "stopTimeoutMillis",
                }
            ),
            name="kernel application config",
        )

        redis_config = _mapping(config.get("redis", {}), name="redis config")
        _reject_unknown(
            redis_config,
            allowed=frozenset({"url", "prefix"}),
            name="redis config",
        )
        scheduling_config = _mapping(
            config.get("assignmentDispatch", {}),
            name="assignmentDispatch config",
        )
        _reject_unknown(
            scheduling_config,
            allowed=frozenset(
                {
                    "workerAllocationIntervalMillis",
                    "runningActivationIntervalMillis",
                    "taskDispatchIntervalMillis",
                }
            ),
            name="assignmentDispatch config",
        )
        result_routing_config = _mapping(
            config.get("resultRouting", {}),
            name="resultRouting config",
        )
        _reject_unknown(
            result_routing_config,
            allowed=frozenset({"intervalMillis"}),
            name="resultRouting config",
        )
        system_policy_config = _mapping(
            config.get("systemPolicy", {}),
            name="systemPolicy config",
        )
        _reject_unknown(
            system_policy_config,
            allowed=frozenset({"runningTaskSoftLimit"}),
            name="systemPolicy config",
        )

        defaults = _DEFAULT_KERNEL_APPLICATION_CONFIG
        return cls(
            redis_url=_non_empty_string(
                redis_config.get("url", defaults.redis_url),
                name="Redis URL",
            ),
            redis_prefix=_non_empty_string(
                redis_config.get("prefix", defaults.redis_prefix),
                name="Redis prefix",
            ),
            worker_allocation_interval_millis=_positive_integer(
                scheduling_config.get(
                    "workerAllocationIntervalMillis",
                    defaults.worker_allocation_interval_millis,
                ),
                name="worker allocation interval",
            ),
            running_activation_interval_millis=_positive_integer(
                scheduling_config.get(
                    "runningActivationIntervalMillis",
                    defaults.running_activation_interval_millis,
                ),
                name="running activation interval",
            ),
            task_dispatch_interval_millis=_positive_integer(
                scheduling_config.get(
                    "taskDispatchIntervalMillis",
                    defaults.task_dispatch_interval_millis,
                ),
                name="Task dispatch interval",
            ),
            result_routing_interval_millis=_positive_integer(
                result_routing_config.get(
                    "intervalMillis",
                    defaults.result_routing_interval_millis,
                ),
                name="result-routing interval",
            ),
            running_task_soft_limit=_positive_integer(
                system_policy_config.get(
                    "runningTaskSoftLimit",
                    defaults.running_task_soft_limit,
                ),
                name="running Task soft limit",
            ),
            stop_timeout_millis=_positive_integer(
                config.get("stopTimeoutMillis", defaults.stop_timeout_millis),
                name="stop timeout",
            ),
        )


_DEFAULT_KERNEL_APPLICATION_CONFIG = KernelApplicationConfig()


class TaskApprovalStatus(Enum):
    APPROVED = "approved"
    ALREADY_APPROVED = "already_approved"
    NOT_FOUND = "not_found"
    CONFLICT = "conflict"
    RETRYABLE = "retryable"
    INVALID = "invalid"


@dataclass(frozen=True, slots=True)
class TaskApprovalResult:
    status: TaskApprovalStatus
    reason: str | None = None


class TaskCloseStatus(Enum):
    CLOSED = "closed"
    ALREADY_CLOSED = "already_closed"
    NOT_FOUND = "not_found"
    RETRYABLE = "retryable"
    INVALID = "invalid"


@dataclass(frozen=True, slots=True)
class TaskCloseResult:
    status: TaskCloseStatus
    reason: str | None = None


class _TaskLifecycleManager:
    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
    ) -> None:
        self._task_score = task_score
        self._task_catalog = task_catalog

    def approve_task(self, *, task_id: TaskId) -> TaskApprovalResult:
        if not isinstance(task_id, str) or not task_id:
            return TaskApprovalResult(TaskApprovalStatus.INVALID, "task id is required")

        descriptor = self._task_catalog.load_task_allocation_descriptors(
            task_ids=(task_id,),
        ).get(task_id)
        if descriptor is None:
            return TaskApprovalResult(TaskApprovalStatus.NOT_FOUND)

        state = self._task_score.get_score_states(task_ids=(task_id,)).get(task_id)
        classified = self._classify_state(state)
        if classified is not None:
            return classified
        assert state is not None and state.time_millis is not None
        approval_time_millis = time_ns() // 1_000_000

        transition = self._task_score.rewrite_score(
            task_id=task_id,
            expected_band=TaskScoreBand.PRE_REVIEW,
            target_time_millis=max(
                approval_time_millis,
                state.time_millis + TaskScoreBandCore.SLOT_MILLIS,
            ),
            target_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_suffix=int(descriptor.config["priority"]),
        )
        if transition.status == TaskScoreTransitionStatus.TRANSITIONED:
            return TaskApprovalResult(TaskApprovalStatus.APPROVED)
        if transition.status == TaskScoreTransitionStatus.INVALID:
            return TaskApprovalResult(
                TaskApprovalStatus.INVALID,
                "task approval transition was rejected",
            )

        current = self._task_score.get_score_states(task_ids=(task_id,)).get(task_id)
        reclassified = self._classify_state(current)
        if reclassified is not None:
            return reclassified
        return TaskApprovalResult(
            TaskApprovalStatus.RETRYABLE,
            "task score changed during approval",
        )

    def close_task(self, *, task_id: TaskId) -> TaskCloseResult:
        if not isinstance(task_id, str) or not task_id:
            return TaskCloseResult(TaskCloseStatus.INVALID, "task id is required")

        state = self._task_score.get_score_states(task_ids=(task_id,)).get(task_id)
        if state is None:
            return TaskCloseResult(TaskCloseStatus.NOT_FOUND)
        if state.band is TaskScoreBand.TERMINAL:
            return TaskCloseResult(TaskCloseStatus.ALREADY_CLOSED)

        transition = self._task_score.close_score(
            task_id=task_id,
            terminal_score=TaskScoreBandCore.TERMINAL_SCORE_MAX,
        )
        if transition.status is TaskScoreTransitionStatus.TRANSITIONED:
            return TaskCloseResult(TaskCloseStatus.CLOSED)
        if transition.status is TaskScoreTransitionStatus.NOOP:
            return TaskCloseResult(TaskCloseStatus.ALREADY_CLOSED)
        if transition.status is TaskScoreTransitionStatus.INVALID:
            return TaskCloseResult(
                TaskCloseStatus.INVALID,
                "task close transition was rejected",
            )
        return TaskCloseResult(
            TaskCloseStatus.RETRYABLE,
            "task score changed during close",
        )

    @staticmethod
    def _classify_state(state: TaskScoreState | None) -> TaskApprovalResult | None:
        if state is None:
            return TaskApprovalResult(TaskApprovalStatus.NOT_FOUND)
        if state.band in {
            TaskScoreBand.ADMISSION_VISIBLE,
            TaskScoreBand.RUNNING_VISIBLE,
        }:
            return TaskApprovalResult(TaskApprovalStatus.ALREADY_APPROVED)
        if state.band == TaskScoreBand.TERMINAL:
            return TaskApprovalResult(
                TaskApprovalStatus.CONFLICT,
                "terminal task cannot be approved",
            )
        if state.band != TaskScoreBand.PRE_REVIEW or state.time_millis is None:
            return TaskApprovalResult(
                TaskApprovalStatus.CONFLICT,
                "task is not in an approvable score state",
            )
        return None


class KernelApplication:
    """Scheduling-process application boundary for the executable spec."""

    def __init__(
        self,
        config: KernelApplicationConfig | None = None,
    ) -> None:
        if config is not None and not isinstance(config, KernelApplicationConfig):
            raise TypeError("config must be KernelApplicationConfig or None")
        self._config = config or _DEFAULT_KERNEL_APPLICATION_CONFIG
        self._process = _RedisKernelProcess.from_url(
            redis_url=self._config.redis_url,
            config=self._internal_process_config(self._config),
        )
        self._task_lifecycle = _TaskLifecycleManager(
            self._process._task_score,
            self._process._task_resource_catalog,
        )
        self._lifecycle_lock = Lock()
        self._started = False

    @classmethod
    def from_json(
        cls,
        config_json: str | None = None,
    ) -> KernelApplication:
        return cls(KernelApplicationConfig.from_json(config_json))

    def start(self) -> None:
        with self._lifecycle_lock:
            if self._started:
                raise RuntimeError("kernel application is already started")
            self._process.start()
            self._started = True

    def stop(self) -> None:
        with self._lifecycle_lock:
            if not self._started:
                return
            self._process.stop()
            self._started = False

    def create_task(
        self,
        *,
        descriptor: TaskDescriptor,
    ) -> TaskCreationResult:
        self._require_started()
        if descriptor.empty_close_at_millis is None:
            creation_time_millis = time_ns() // 1_000_000
            descriptor = replace(
                descriptor,
                empty_close_at_millis=(
                    0
                    if descriptor.task_type is TaskType.TASK_DRIVEN
                    else creation_time_millis
                    + _ITEM_DRIVEN_DEFAULT_EMPTY_CLOSE_DELAY_MILLIS
                ),
            )
        return self._process._task_runtime.create_task(
            descriptor=descriptor,
            suffix=_INITIAL_PRE_REVIEW_SUFFIX,
        )

    def approve_task(self, *, task_id: TaskId) -> TaskApprovalResult:
        self._require_started()
        return self._task_lifecycle.approve_task(task_id=task_id)

    def close_task(self, *, task_id: TaskId) -> TaskCloseResult:
        self._require_started()
        return self._task_lifecycle.close_task(task_id=task_id)

    def _require_started(self) -> None:
        with self._lifecycle_lock:
            if not self._started:
                raise RuntimeError("kernel application is not started")

    @staticmethod
    def _internal_process_config(
        config: KernelApplicationConfig,
    ) -> _RedisKernelProcessConfig:
        return _RedisKernelProcessConfig(
            prefix=config.redis_prefix,
            running_task_soft_limit=config.running_task_soft_limit,
            worker_candidate_scan_limit=_WORKER_SCAN_LIMIT,
            assignment_dispatch=AssignmentDispatchApplicationConfig(
                worker_allocation=TaskWorkerAllocationConfig(
                    task_batch_limit=_TASK_BATCH_LIMIT,
                    worker_lease_duration_millis=_WORKER_LEASE_DURATION_MILLIS,
                ),
                running_activation=TaskRunningActivationConfig(
                    task_batch_limit=_TASK_BATCH_LIMIT,
                    priority_recheck_step_millis=(
                        _ADMISSION_PRIORITY_RECHECK_STEP_MILLIS
                    ),
                ),
                task_dispatch=TaskDispatchConfig(
                    task_batch_limit=_TASK_BATCH_LIMIT,
                    per_task_dispatch_limit=_PER_TASK_DISPATCH_LIMIT,
                    item_claim_lease_duration_millis=(
                        _ITEM_CLAIM_LEASE_DURATION_MILLIS
                    ),
                    max_empty_recheck_times=_MAX_EMPTY_RECHECK_TIMES,
                    empty_recheck_interval_millis=(
                        _EMPTY_RECHECK_INTERVAL_MILLIS
                    ),
                ),
                worker_allocation_interval_millis=(
                    config.worker_allocation_interval_millis
                ),
                running_activation_interval_millis=(
                    config.running_activation_interval_millis
                ),
                task_dispatch_interval_millis=(
                    config.task_dispatch_interval_millis
                ),
            ),
            result_routing=ResultRoutingApplicationConfig(
                routing=ResultRoutingConfig(
                    per_outcome_batch_limit=(
                        _RESULT_ROUTING_PER_OUTCOME_BATCH_LIMIT
                    ),
                ),
                interval_millis=config.result_routing_interval_millis,
            ),
            stop_timeout_millis=config.stop_timeout_millis,
        )
