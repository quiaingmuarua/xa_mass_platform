from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Mapping as MappingABC
from dataclasses import dataclass
from enum import Enum
from typing import ClassVar, Mapping, Sequence

from .task_score_band import Suffix, TaskId
from .worker_runtime import WorkerGroupId


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
        if not 1 <= priority <= 100:
            raise ValueError("task priority must be in 1..100")
        if maximum_candidates <= 0:
            raise ValueError("maximum candidate workers must be positive")
        if not 1 <= minimum_candidates <= maximum_candidates:
            raise ValueError(
                "minimum candidate workers must be in 1..maximumCandidateWorkers"
            )

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
    """Task runtime owner surface."""

    @abstractmethod
    def create_task(
        self,
        *,
        descriptor: TaskDescriptor,
        suffix: Suffix,
    ) -> TaskCreationResult:
        """Create one Task through its initialization score lease."""
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
