from __future__ import annotations

from collections.abc import Callable
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

    def acquire_admission_tasks(
        self,
        *,
        limit: int,
    ) -> tuple[DueTaskObservation, ...]:
        now_millis = self._clock_millis()
        task_ids = tuple(self.task_score.acquire_band_task_candidates(
            band=TaskScoreBand.ADMISSION_VISIBLE,
            before_time_millis=now_millis,
            limit=limit,
        ))
        return self._observe(
            task_ids=task_ids,
            expected_band=TaskScoreBand.ADMISSION_VISIBLE,
            now_millis=now_millis,
            require_running_suffix=False,
        )

    def acquire_running_tasks(
        self,
        *,
        limit: int,
    ) -> tuple[DueTaskObservation, ...]:
        now_millis = self._clock_millis()
        return self._observe(
            task_ids=tuple(
                self.task_score.acquire_dispatch_work_tasks(limit=limit)
            ),
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            now_millis=now_millis,
            require_running_suffix=True,
        )

    def _observe(
        self,
        *,
        task_ids: tuple[str, ...],
        expected_band: TaskScoreBand,
        now_millis: int,
        require_running_suffix: bool,
    ) -> tuple[DueTaskObservation, ...]:
        if not task_ids:
            return ()
        states = self.task_score.get_score_states(task_ids=task_ids)
        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )
        current_slot_millis = (
            now_millis
            // TaskScoreBandCore.SLOT_MILLIS
            * TaskScoreBandCore.SLOT_MILLIS
        )
        observations: list[DueTaskObservation] = []
        for task_id in task_ids:
            state = states.get(task_id)
            descriptor = descriptors.get(task_id)
            if (
                state is None
                or descriptor is None
                or descriptor.task_id != task_id
                or state.band is not expected_band
                or state.time_millis is None
                or state.time_millis >= current_slot_millis
                or state.suffix is None
                or (
                    require_running_suffix
                    and state.suffix != TaskScoreBandCore.MIN_SUFFIX
                )
            ):
                continue
            observations.append(DueTaskObservation(
                task_id=task_id,
                score_state=state,
                descriptor=descriptor,
            ))
        return tuple(observations)
