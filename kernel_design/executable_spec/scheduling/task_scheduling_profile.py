from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from types import MappingProxyType

from ..kernel.task_runtime import TaskType
from .worker_candidate import WorkerCandidateAcquisitionStrategy


class TaskAllocationRuleOwner(Enum):
    TASK = "TASK"
    TASK_ITEM = "TASK_ITEM"


@dataclass(frozen=True, slots=True)
class ResolvedTaskSchedulingProfile:
    """Internal scheduling decisions fixed by one public Task type."""

    allocation_rule_owner: TaskAllocationRuleOwner
    candidate_precomputation_enabled: bool
    dispatch_acquisition_strategy: WorkerCandidateAcquisitionStrategy


_TASK_SCHEDULING_PROFILES = MappingProxyType(
    {
        TaskType.TASK_DRIVEN: ResolvedTaskSchedulingProfile(
            allocation_rule_owner=TaskAllocationRuleOwner.TASK,
            candidate_precomputation_enabled=True,
            dispatch_acquisition_strategy=(
                WorkerCandidateAcquisitionStrategy.PRECOMPUTED
            ),
        ),
        TaskType.ITEM_DRIVEN: ResolvedTaskSchedulingProfile(
            allocation_rule_owner=TaskAllocationRuleOwner.TASK_ITEM,
            candidate_precomputation_enabled=False,
            dispatch_acquisition_strategy=(
                WorkerCandidateAcquisitionStrategy.DIRECT
            ),
        ),
    }
)


def resolve_task_scheduling_profile(
    task_type: TaskType,
) -> ResolvedTaskSchedulingProfile:
    """Resolve the only supported policy bundle for a public Task type."""
    if not isinstance(task_type, TaskType):
        raise ValueError("task type is invalid")
    return _TASK_SCHEDULING_PROFILES[task_type]
