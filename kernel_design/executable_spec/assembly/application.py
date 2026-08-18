from __future__ import annotations

import hashlib
import json
import logging
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from enum import Enum
from threading import Lock
from time import time_ns
from types import MappingProxyType
from typing import Any

from ..scheduling import (
    TaskDispatchConfig,
    TaskRunningActivationConfig,
    TaskWorkerAllocationConfig,
    WorkerServiceabilityDispatchConfig,
    WorkerServiceabilityResultConfig,
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
from .worker_serviceability_application import (
    WorkerServiceabilityDispatchApplicationConfig,
    WorkerServiceabilityResultApplicationConfig,
)


_DEFAULT_REDIS_URL = "redis://localhost:6379/15"
_DEFAULT_REDIS_PREFIX = "default"
_DEFAULT_PACER_INTERVAL_MILLIS = 100
_DEFAULT_RESULT_ROUTING_INTERVAL_MILLIS = 100
_DEFAULT_SERVICEABILITY_DISPATCH_INTERVAL_MILLIS = 1_000
_DEFAULT_SERVICEABILITY_RESULT_INTERVAL_MILLIS = 100
_DEFAULT_STOP_TIMEOUT_MILLIS = 5_000
_DEFAULT_RUNNING_TASK_SOFT_LIMIT = 100
_REDIS_HASH_INDEX = "redis-hash"

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
_LOGGER = logging.getLogger(__name__)


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


def _valid_index_field(value: object) -> bool:
    return isinstance(value, str) and value.startswith("index.") and len(value) > 6


def _parse_worker_property_index_registry(
    registry_json: str | None,
) -> Mapping[str, str]:
    encoded = "{}" if registry_json is None else registry_json
    if not isinstance(encoded, str) or not encoded:
        raise ValueError(
            "Worker property index registry must be non-empty JSON text"
        )
    try:
        raw_registry = json.loads(encoded)
    except json.JSONDecodeError as error:
        raise ValueError(
            "Worker property index registry is not valid JSON"
        ) from error
    registry = _mapping(
        raw_registry,
        name="Worker property index registry",
    )
    return {
        property_field: _non_empty_string(
            implementation,
            name=f"Worker property index {property_field} implementation",
        )
        for property_field, implementation in registry.items()
    }


def _worker_property_index_registry_fingerprint(
    registry: Mapping[str, str],
) -> str:
    canonical_json = json.dumps(
        dict(registry),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    )
    return hashlib.sha256(canonical_json.encode("utf-8")).hexdigest()


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
class WorkerServiceabilityConfig:
    worker_group_ids: tuple[str, ...]
    dispatch_interval_millis: int = _DEFAULT_SERVICEABILITY_DISPATCH_INTERVAL_MILLIS
    result_interval_millis: int = _DEFAULT_SERVICEABILITY_RESULT_INTERVAL_MILLIS
    stale_hot_after_millis: int = 300_000
    recovery_retry_interval_millis: int = 60_000
    max_recovery_attempts: int = 5
    hot_scan_limit: int = 80
    recovery_scan_limit: int = 20
    result_report_limit: int = 10

    def __post_init__(self) -> None:
        if isinstance(self.worker_group_ids, (str, bytes)):
            raise ValueError("serviceability WorkerGroup ids must be a sequence")
        for value, name in (
            (self.dispatch_interval_millis, "serviceability dispatch interval"),
            (self.result_interval_millis, "serviceability result interval"),
            (self.stale_hot_after_millis, "stale HOT duration"),
            (self.recovery_retry_interval_millis, "recovery retry interval"),
            (self.max_recovery_attempts, "max recovery attempts"),
            (self.hot_scan_limit, "HOT scan limit"),
            (self.recovery_scan_limit, "recovery scan limit"),
            (self.result_report_limit, "result Report limit"),
        ):
            _positive_integer(value, name=name)
        groups = tuple(self.worker_group_ids)
        dispatch = WorkerServiceabilityDispatchConfig(
            worker_group_ids=groups,
            stale_hot_after_millis=self.stale_hot_after_millis,
            recovery_retry_interval_millis=self.recovery_retry_interval_millis,
            hot_scan_limit=self.hot_scan_limit,
            recovery_scan_limit=self.recovery_scan_limit,
        )
        WorkerServiceabilityResultConfig(
            max_recovery_attempts=self.max_recovery_attempts,
            result_report_limit=self.result_report_limit,
        )
        object.__setattr__(self, "worker_group_ids", dispatch.worker_group_ids)


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
    worker_serviceability: WorkerServiceabilityConfig | None = None
    worker_property_indexes: Mapping[str, str] = field(
        default_factory=lambda: MappingProxyType({})
    )

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
        if self.worker_serviceability is not None and not isinstance(
            self.worker_serviceability,
            WorkerServiceabilityConfig,
        ):
            raise TypeError(
                "worker_serviceability must be WorkerServiceabilityConfig or None"
            )
        indexes = dict(self.worker_property_indexes)
        for property_field, implementation in indexes.items():
            if not _valid_index_field(property_field):
                raise ValueError("Worker property index fields must use index.*")
            if implementation != _REDIS_HASH_INDEX:
                raise ValueError(
                    "unknown Worker property index implementation: "
                    f"{implementation}"
                )
        object.__setattr__(
            self,
            "worker_property_indexes",
            MappingProxyType(indexes),
        )

    @classmethod
    def from_json(
        cls,
        config_json: str | None = None,
        *,
        worker_property_index_registry_json: str | None = None,
    ) -> KernelApplicationConfig:
        if config_json is None:
            config_json = "{}"
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
                    "workerServiceability",
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

        serviceability_config: WorkerServiceabilityConfig | None = None
        if "workerServiceability" in config:
            raw_serviceability = _mapping(
                config["workerServiceability"],
                name="workerServiceability config",
            )
            _reject_unknown(
                raw_serviceability,
                allowed=frozenset(
                    {
                        "workerGroupIds",
                        "dispatchIntervalMillis",
                        "resultIntervalMillis",
                        "staleHotAfterMillis",
                        "recoveryRetryIntervalMillis",
                        "maxRecoveryAttempts",
                        "hotScanLimit",
                        "recoveryScanLimit",
                        "resultReportLimit",
                    }
                ),
                name="workerServiceability config",
            )
            raw_group_ids = raw_serviceability.get("workerGroupIds")
            if not isinstance(raw_group_ids, list):
                raise ValueError(
                    "workerServiceability workerGroupIds must be an array"
                )
            serviceability_config = WorkerServiceabilityConfig(
                worker_group_ids=tuple(raw_group_ids),
                dispatch_interval_millis=_positive_integer(
                    raw_serviceability.get(
                        "dispatchIntervalMillis",
                        _DEFAULT_SERVICEABILITY_DISPATCH_INTERVAL_MILLIS,
                    ),
                    name="serviceability dispatch interval",
                ),
                result_interval_millis=_positive_integer(
                    raw_serviceability.get(
                        "resultIntervalMillis",
                        _DEFAULT_SERVICEABILITY_RESULT_INTERVAL_MILLIS,
                    ),
                    name="serviceability result interval",
                ),
                stale_hot_after_millis=_positive_integer(
                    raw_serviceability.get("staleHotAfterMillis", 300_000),
                    name="stale HOT duration",
                ),
                recovery_retry_interval_millis=_positive_integer(
                    raw_serviceability.get(
                        "recoveryRetryIntervalMillis",
                        60_000,
                    ),
                    name="recovery retry interval",
                ),
                max_recovery_attempts=_positive_integer(
                    raw_serviceability.get("maxRecoveryAttempts", 5),
                    name="max recovery attempts",
                ),
                hot_scan_limit=_positive_integer(
                    raw_serviceability.get("hotScanLimit", 80),
                    name="HOT scan limit",
                ),
                recovery_scan_limit=_positive_integer(
                    raw_serviceability.get("recoveryScanLimit", 20),
                    name="recovery scan limit",
                ),
                result_report_limit=_positive_integer(
                    raw_serviceability.get("resultReportLimit", 10),
                    name="result Report limit",
                ),
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
            worker_serviceability=serviceability_config,
            worker_property_indexes=_parse_worker_property_index_registry(
                worker_property_index_registry_json
            ),
        )

    def worker_property_index_registry_fingerprint(self) -> str:
        return _worker_property_index_registry_fingerprint(
            self.worker_property_indexes
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
        *,
        worker_property_index_registry_json: str | None = None,
    ) -> KernelApplication:
        return cls(KernelApplicationConfig.from_json(
            config_json,
            worker_property_index_registry_json=(
                worker_property_index_registry_json
            ),
        ))

    def start(self) -> None:
        with self._lifecycle_lock:
            if self._started:
                raise RuntimeError("kernel application is already started")
            _LOGGER.info(
                "Worker Property Index registry fields=%d fingerprint=%s",
                len(self._config.worker_property_indexes),
                self._config.worker_property_index_registry_fingerprint(),
            )
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

    def wake_task_dispatch(self, *, task_ids: tuple[TaskId, ...]) -> int:
        """Offer droppable Task Dispatch acceleration hints."""
        self._require_started()
        return self._process._task_dispatch_wake_inbox.offer(
            task_ids=task_ids,
        )

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
            worker_property_indexes=config.worker_property_indexes,
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
            worker_serviceability_dispatch=(
                None
                if config.worker_serviceability is None
                else WorkerServiceabilityDispatchApplicationConfig(
                    dispatch=WorkerServiceabilityDispatchConfig(
                        worker_group_ids=(
                            config.worker_serviceability.worker_group_ids
                        ),
                        stale_hot_after_millis=(
                            config.worker_serviceability.stale_hot_after_millis
                        ),
                        recovery_retry_interval_millis=(
                            config.worker_serviceability
                            .recovery_retry_interval_millis
                        ),
                        hot_scan_limit=(
                            config.worker_serviceability.hot_scan_limit
                        ),
                        recovery_scan_limit=(
                            config.worker_serviceability.recovery_scan_limit
                        ),
                    ),
                    interval_millis=(
                        config.worker_serviceability.dispatch_interval_millis
                    ),
                )
            ),
            worker_serviceability_result=(
                None
                if config.worker_serviceability is None
                else WorkerServiceabilityResultApplicationConfig(
                    result=WorkerServiceabilityResultConfig(
                        max_recovery_attempts=(
                            config.worker_serviceability.max_recovery_attempts
                        ),
                        result_report_limit=(
                            config.worker_serviceability.result_report_limit
                        ),
                    ),
                    interval_millis=(
                        config.worker_serviceability.result_interval_millis
                    ),
                )
            ),
            stop_timeout_millis=config.stop_timeout_millis,
        )
