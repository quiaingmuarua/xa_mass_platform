from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Mapping, Sequence

from .task_score_band import Suffix, TaskId
from .worker_runtime import WorkerGroupId


@dataclass(frozen=True)
class TaskDescriptor:
    """Stable task allocation metadata, separate from task runtime truth."""

    task_id: TaskId
    worker_group_id: WorkerGroupId
    allocation_rule: Mapping[str, object]
    config: Mapping[str, str]


class TaskCreationStatus(Enum):
    CREATED = "created"
    RETRYABLE = "retryable"
    CONFLICT = "conflict"
    INVALID = "invalid"


@dataclass(frozen=True)
class TaskCreationResult:
    status: TaskCreationStatus
    reason: str | None = None


class TaskCreationRuntime(ABC):
    """Score-owned Task creation surface."""

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
