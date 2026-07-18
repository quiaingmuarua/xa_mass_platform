from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from time import time_ns

from ..kernel.result_context import decode_result_context
from ..kernel.seed_result_runtime import (
    SeedResultOutcomeClass,
    SeedResultRuntime,
    classify_seed_result_outcome_code,
)
from ..kernel.task_item_score_band import TaskItemScoreBand, TaskItemScoreBandCore
from ..kernel.task_runtime import MessageId, TaskRuntime
from ..kernel.task_score_band import TaskId, TimeMillis
from ..kernel.worker_runtime import WorkerGroupId
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId, WorkerScoreCore


@dataclass(frozen=True, slots=True)
class ResultRoutingConfig:
    per_outcome_batch_limit: int

    def __post_init__(self) -> None:
        if self.per_outcome_batch_limit <= 0:
            raise ValueError("per-outcome result-routing batch limit must be positive")


@dataclass(frozen=True, slots=True)
class _TaskResultEvidence:
    task_id: TaskId
    message_id: MessageId
    opaque_result_payload: str


@dataclass(frozen=True, slots=True)
class _WorkerResultEvidence:
    task_id: TaskId
    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    worker_lease_score: WorkerScore
    outcome_code: str


@dataclass(frozen=True, slots=True)
class _DecodedSeedResultBatch:
    decoded_count: int
    results_by_task: dict[TaskId, tuple[_TaskResultEvidence, ...]]
    results_by_worker_group: dict[
        WorkerGroupId, tuple[_WorkerResultEvidence, ...]
    ]


class ResultRoutingPacer:
    """Route outcome-class queues into TaskItem and Worker owners."""

    def __init__(
        self,
        seed_result_runtime: SeedResultRuntime,
        item_score: TaskItemScoreBandCore,
        worker_score: WorkerScoreCore,
        task_runtime: TaskRuntime,
    ) -> None:
        self.seed_result_runtime = seed_result_runtime
        self.item_score = item_score
        self.worker_score = worker_score
        self.task_runtime = task_runtime
        self._outcome_classes = (
            SeedResultOutcomeClass.SUCCESS,
            SeedResultOutcomeClass.WORKER_FAILURE,
            SeedResultOutcomeClass.ADAPTER_REJECTION,
        )
        self._task_result_handlers = {
            SeedResultOutcomeClass.SUCCESS: self._handle_task_success_results,
        }
        self._worker_result_handlers = {
            SeedResultOutcomeClass.SUCCESS: self._handle_worker_success_results,
            SeedResultOutcomeClass.WORKER_FAILURE: (
                self._handle_worker_failure_results
            ),
            SeedResultOutcomeClass.ADAPTER_REJECTION: (
                self._handle_worker_adapter_rejections
            ),
        }

    def route_seed_results(self, *, config: ResultRoutingConfig) -> int:
        result_time_millis = self._current_time_millis()
        routed_count = 0
        for outcome_class in self._outcome_classes:
            batch = self._consume_decoded(
                outcome_class=outcome_class,
                limit=config.per_outcome_batch_limit,
            )
            if batch.decoded_count == 0:
                continue
            self._handle_task_results(
                outcome_class=outcome_class,
                results_by_task=batch.results_by_task,
                result_time_millis=result_time_millis,
            )
            self._handle_worker_results(
                outcome_class=outcome_class,
                results_by_worker_group=batch.results_by_worker_group,
                result_time_millis=result_time_millis,
            )
            routed_count += batch.decoded_count
        return routed_count

    def _handle_task_results(
        self,
        *,
        outcome_class: SeedResultOutcomeClass,
        results_by_task: dict[TaskId, tuple[_TaskResultEvidence, ...]],
        result_time_millis: TimeMillis,
    ) -> None:
        handler = self._task_result_handlers.get(outcome_class)
        if handler is None:
            return
        for task_id, entries in results_by_task.items():
            handler(task_id, entries, result_time_millis)

    def _handle_worker_results(
        self,
        *,
        outcome_class: SeedResultOutcomeClass,
        results_by_worker_group: dict[
            WorkerGroupId, tuple[_WorkerResultEvidence, ...]
        ],
        result_time_millis: TimeMillis,
    ) -> None:
        handler = self._worker_result_handlers[outcome_class]
        for worker_group_id, entries in results_by_worker_group.items():
            handler(worker_group_id, entries, result_time_millis)

    def _handle_task_success_results(
        self,
        task_id: TaskId,
        entries: tuple[_TaskResultEvidence, ...],
        result_time_millis: TimeMillis,
    ) -> None:
        results: dict[MessageId, str] = {}
        for entry in entries:
            results[entry.message_id] = entry.opaque_result_payload
        self.task_runtime.store_task_item_success_results(
            task_id=task_id,
            results=results,
        )
        self.item_score.promote_item_outcomes(
            task_id=task_id,
            message_ids=tuple(results),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=result_time_millis,
        )

    def _handle_worker_success_results(
        self,
        worker_group_id: WorkerGroupId,
        entries: tuple[_WorkerResultEvidence, ...],
        release_time_millis: TimeMillis,
    ) -> None:
        self.worker_score.release_score_holds(
            home_bucket_id=worker_group_id,
            observed_scores=self._latest_worker_scores(entries),
            release_time_millis=release_time_millis,
        )

    def _handle_worker_failure_results(
        self,
        worker_group_id: WorkerGroupId,
        entries: tuple[_WorkerResultEvidence, ...],
        release_time_millis: TimeMillis,
    ) -> None:
        self.worker_score.release_score_holds(
            home_bucket_id=worker_group_id,
            observed_scores=self._latest_worker_scores(entries),
            release_time_millis=release_time_millis,
        )

    def _handle_worker_adapter_rejections(
        self,
        worker_group_id: WorkerGroupId,
        entries: tuple[_WorkerResultEvidence, ...],
        _result_time_millis: TimeMillis,
    ) -> None:
        self.worker_score.demote_observed_worker_leases_to_recovery(
            home_bucket_id=worker_group_id,
            observed_scores=self._latest_worker_scores(entries),
        )

    def _consume_decoded(
        self,
        *,
        outcome_class: SeedResultOutcomeClass,
        limit: int,
    ) -> _DecodedSeedResultBatch:
        results = self.seed_result_runtime.consume_seed_results(
            outcome_class=outcome_class,
            limit=limit,
        )
        decoded_count = 0
        results_by_task: dict[TaskId, list[_TaskResultEvidence]] = defaultdict(list)
        results_by_worker_group: dict[
            WorkerGroupId, list[_WorkerResultEvidence]
        ] = defaultdict(list)
        for result in results:
            context = decode_result_context(result.opaque_result_context)
            if (
                context is not None
                and classify_seed_result_outcome_code(result.outcome_code)
                is outcome_class
            ):
                decoded_count += 1
                if outcome_class is SeedResultOutcomeClass.SUCCESS:
                    payload = result.opaque_result_payload
                    if payload is not None:
                        results_by_task[context.task_id].append(
                            _TaskResultEvidence(
                                task_id=context.task_id,
                                message_id=context.message_id,
                                opaque_result_payload=payload,
                            )
                        )
                results_by_worker_group[context.worker_group_id].append(
                    _WorkerResultEvidence(
                        task_id=context.task_id,
                        worker_id=context.worker_id,
                        worker_group_id=context.worker_group_id,
                        worker_lease_score=context.worker_lease_score,
                        outcome_code=result.outcome_code,
                    )
                )
        return _DecodedSeedResultBatch(
            decoded_count=decoded_count,
            results_by_task={
                task_id: tuple(entries)
                for task_id, entries in results_by_task.items()
            },
            results_by_worker_group={
                worker_group_id: tuple(entries)
                for worker_group_id, entries in results_by_worker_group.items()
            },
        )

    @staticmethod
    def _latest_worker_scores(
        entries: tuple[_WorkerResultEvidence, ...],
    ) -> dict[WorkerId, WorkerScore]:
        return {
            entry.worker_id: entry.worker_lease_score
            for entry in entries
        }

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
