from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Mapping, Sequence

from .task_score_band import TaskId
from .worker_runtime import WorkerGroupId


@dataclass(frozen=True)
class TaskAllocationRule:
    """Declarative worker constraint stored with one task descriptor."""

    acquire_fields: tuple[str, ...]
    match_rules: Mapping[str, object]


@dataclass(frozen=True)
class TaskDescriptor:
    """Stable task allocation metadata, separate from task runtime truth."""

    task_id: TaskId
    worker_group_id: WorkerGroupId
    allocation_rule: TaskAllocationRule
    config: Mapping[str, str]


class TaskDescriptorRegistrationStatus(Enum):
    REGISTERED = "registered"
    CONFLICT = "conflict"
    INVALID = "invalid"


@dataclass(frozen=True)
class TaskDescriptorRegistrationResult:
    status: TaskDescriptorRegistrationStatus
    reason: str | None = None


class TaskResourceCatalog(ABC):
    """Create-only Task descriptor registration and bounded batch reads.

    This surface does not own task score, lifecycle, work, allocation handoff,
    result state, or query/list APIs. Task ids are globally unique.
    """

    @abstractmethod
    def register_task_descriptor(
        self,
        *,
        descriptor: TaskDescriptor,
    ) -> TaskDescriptorRegistrationResult:
        """Create a validated descriptor without replacement semantics."""
        pass

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
