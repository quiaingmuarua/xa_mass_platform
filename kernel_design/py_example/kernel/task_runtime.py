from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Mapping as MappingABC
from dataclasses import dataclass
from enum import Enum
from typing import ClassVar, Mapping, Sequence

from .task_score_band import Suffix, TaskId, TimeMillis
from .worker_runtime import EventCode, WorkerGroupId


MessageId = str
ItemPriority = int


@dataclass(frozen=True, slots=True, kw_only=True)
class TaskItem:
    """Canonical Task-scoped item record; scheduling state lives elsewhere."""

    message_id: MessageId
    event_code: EventCode
    created_at_millis: TimeMillis
    payload: Mapping[str, object] | None = None
    payload_ref: str | None = None
    priority: ItemPriority = 5
    expire_at_millis: TimeMillis | None = None

    def __post_init__(self) -> None:
        if not self.message_id:
            raise ValueError("message id must be non-empty")
        if not self.event_code:
            raise ValueError("event code must be non-empty")
        if (self.payload is None) == (self.payload_ref is None):
            raise ValueError("exactly one of payload and payload_ref is required")
        if self.payload is not None and not isinstance(self.payload, MappingABC):
            raise ValueError("task item payload must be a mapping")
        if self.payload_ref is not None and not self.payload_ref:
            raise ValueError("payload ref must be non-empty")
        if (
            not isinstance(self.priority, int)
            or isinstance(self.priority, bool)
            or not 0 <= self.priority <= 10
        ):
            raise ValueError("task item priority must be in 0..10")
        if self.created_at_millis < 0:
            raise ValueError("created_at_millis must be non-negative")
        if (
            self.expire_at_millis is not None
            and self.expire_at_millis <= self.created_at_millis
        ):
            raise ValueError("expire_at_millis must be after created_at_millis")


class TaskItemAppendStatus(Enum):
    APPENDED = "appended"
    DUPLICATE_REJECTED = "duplicate_rejected"
    CONFLICT = "conflict"
    RETRYABLE = "retryable"
    NOT_FOUND = "not_found"
    INVALID = "invalid"


@dataclass(frozen=True)
class TaskItemAppendResult:
    status: TaskItemAppendStatus
    reason: str | None = None


@dataclass(frozen=True)
class TaskDescriptor:
    """Stable task allocation metadata, separate from task runtime truth."""

    task_id: TaskId
    worker_group_id: WorkerGroupId
    allocation_rule: Mapping[str, object]
    config: Mapping[str, str]

    CONFIG_KEYS: ClassVar[frozenset[str]] = frozenset(
        {
            "priority",
            "maximumCandidateWorkers",
            "runningVisibleMinimumCandidateWorkers",
            "maxRetryTimes",
        }
    )

    def __post_init__(self) -> None:
        if not self.task_id:
            raise ValueError("task id must be non-empty")
        if not self.worker_group_id:
            raise ValueError("worker group id must be non-empty")
        if not isinstance(self.allocation_rule, MappingABC):
            raise ValueError("task allocation rule must be a mapping")
        if not isinstance(self.config, MappingABC):
            raise ValueError("task config must be a mapping")
        if set(self.config) != self.CONFIG_KEYS:
            raise ValueError("task config must contain exactly the declared keys")
        if any(
            not isinstance(value, str)
            for value in self.config.values()
        ):
            raise ValueError("task config values must be strings")

        priority = self._decimal_config("priority")
        maximum_candidates = self._decimal_config("maximumCandidateWorkers")
        minimum_candidates = self._decimal_config(
            "runningVisibleMinimumCandidateWorkers"
        )
        max_retry_times = self._decimal_config("maxRetryTimes")
        if not 1 <= priority <= 100:
            raise ValueError("task priority must be in 1..100")
        if maximum_candidates <= 0:
            raise ValueError("maximum candidate workers must be positive")
        if not 1 <= minimum_candidates <= maximum_candidates:
            raise ValueError(
                "minimum candidate workers must be in 1..maximumCandidateWorkers"
            )
        if not 0 <= max_retry_times <= 98:
            raise ValueError("max retry times must be in 0..98")

    def _decimal_config(self, key: str) -> int:
        value = self.config[key]
        if not value.isascii() or not value.isdecimal():
            raise ValueError(f"task config {key} must be decimal text")
        return int(value)


class TaskCreationStatus(Enum):
    CREATED = "created"
    RETRYABLE = "retryable"
    CONFLICT = "conflict"
    INVALID = "invalid"


@dataclass(frozen=True)
class TaskCreationResult:
    status: TaskCreationStatus
    reason: str | None = None


class TaskRuntime(ABC):
    """Task and canonical TaskItem record owner surface."""

    @abstractmethod
    def create_task(
        self,
        *,
        descriptor: TaskDescriptor,
        suffix: Suffix,
    ) -> TaskCreationResult:
        """Create one Task through its initialization score lease."""
        pass

    @abstractmethod
    def append_items(
        self,
        *,
        task_id: TaskId,
        items: Sequence[TaskItem],
    ) -> Mapping[MessageId, TaskItemAppendResult]:
        """Persist one bounded TaskItem batch and coordinate score initialization."""
        pass

    @abstractmethod
    def load_task_items(
        self,
        *,
        task_id: TaskId,
        message_ids: Sequence[MessageId],
    ) -> Mapping[MessageId, TaskItem | None]:
        """Load canonical records for one bounded Task-scoped message-id batch."""
        pass


class TaskResourceCatalog(ABC):
    """Bounded allocation descriptor reads.

    This surface does not own task score, lifecycle, work, allocation handoff,
    result state, or query/list APIs. Task ids are globally unique.
    """

    @abstractmethod
    def load_task_allocation_descriptors(
        self,
        *,
        task_ids: Sequence[TaskId],
    ) -> Mapping[TaskId, TaskDescriptor | None]:
        """Load one bounded allocation batch; missing ids map to None.

        This is an assignment-dispatch input surface, not a general Task read
        or query API.
        """
        pass
