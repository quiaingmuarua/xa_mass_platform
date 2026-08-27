from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from time import time_ns

from ..kernel.task_runtime import TaskDescriptor, TaskResourceCatalog
from ..kernel.task_score_band import (
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
)


def _current_time_millis() -> int:
    return time_ns() // 1_000_000


@dataclass(frozen=True, slots=True)
class DueTaskObservation:
    task_id: str
    score_state: TaskScoreState
    descriptor: TaskDescriptor

    def __post_init__(self) -> None:
        if not self.task_id:
            raise ValueError("Task id must be non-empty")
        if self.score_state.task_id != self.task_id:
            raise ValueError("Task score identity must match Task id")
        if self.descriptor.task_id != self.task_id:
            raise ValueError("Task descriptor identity must match Task id")


@dataclass(frozen=True, slots=True)
class TaskSchedulingBatch:
    normal_tasks: tuple[DueTaskObservation, ...]
    initial_tasks: tuple[DueTaskObservation, ...]


class TaskSchedulingBatchSource:
    """Read and verify bounded Task observations for Dispatch lanes."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        *,
        clock_millis: Callable[[], int] = _current_time_millis,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self._clock_millis = clock_millis

    def acquire_tasks(
        self,
        *,
        limit: int,
        include_normal: bool,
        include_initial: bool,
    ) -> TaskSchedulingBatch:
        if limit <= 0 or (not include_normal and not include_initial):
            return TaskSchedulingBatch((), ())
        now_millis = self._clock_millis()
        normal_ids = (
            self._require_ids(self.task_score.acquire_dispatch_work_tasks(
                limit=limit,
            ))
            if include_normal
            else ()
        )
        remaining = max(0, limit - len(normal_ids))
        initial_ids = (
            self._require_ids(self.task_score.acquire_initial_running_tasks(
                limit=remaining,
            ))
            if include_initial and remaining > 0
            else ()
        )
        all_ids = normal_ids + initial_ids
        if len(all_ids) != len(set(all_ids)):
            raise ValueError("Task scheduling source returned duplicate ids")
        if not all_ids:
            return TaskSchedulingBatch((), ())
        states = self.task_score.get_score_states(task_ids=all_ids)
        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=all_ids,
        )
        return TaskSchedulingBatch(
            normal_tasks=self._observe(
                task_ids=normal_ids,
                states=states,
                descriptors=descriptors,
                now_millis=now_millis,
                initial=False,
            ),
            initial_tasks=self._observe(
                task_ids=initial_ids,
                states=states,
                descriptors=descriptors,
                now_millis=now_millis,
                initial=True,
            ),
        )

    def _observe(
        self,
        *,
        task_ids: tuple[str, ...],
        states: Mapping[str, TaskScoreState | None],
        descriptors: Mapping[str, TaskDescriptor],
        now_millis: int,
        initial: bool,
    ) -> tuple[DueTaskObservation, ...]:
        if not task_ids:
            return ()
        observations: list[DueTaskObservation] = []
        for task_id in task_ids:
            state = states.get(task_id)
            descriptor = descriptors.get(task_id)
            if (
                state is None
                or descriptor is None
                or descriptor.task_id != task_id
                or state.band is not TaskScoreBand.RUNNING_VISIBLE
                or initial and not state.is_initial()
                or not initial and not state.is_due_normal(now_millis)
            ):
                continue
            observations.append(DueTaskObservation(
                task_id=task_id,
                score_state=state,
                descriptor=descriptor,
            ))
        return tuple(observations)

    @staticmethod
    def _require_ids(task_ids: Sequence[str]) -> tuple[str, ...]:
        normalized = tuple(task_ids)
        if (
            any(
                not isinstance(task_id, str) or not task_id
                for task_id in normalized
            )
            or len(normalized) != len(set(normalized))
        ):
            raise ValueError("Task scheduling source returned invalid ids")
        return normalized
