from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from time import time_ns

from ..kernel.result_context import ResultContext, decode_result_context
from ..kernel.seed_result_runtime import (
    SeedResult,
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
class _DecodedSeedResult:
    result: SeedResult
    context: ResultContext


@dataclass(frozen=True, slots=True)
class _DecodedSeedResultBatch:
    decoded_count: int
    results_by_task: dict[TaskId, tuple[_DecodedSeedResult, ...]]
    results_by_worker_group: dict[
        WorkerGroupId, tuple[_DecodedSeedResult, ...]
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
        self._outcome_handlers = (
            (
                SeedResultOutcomeClass.SUCCESS,
                self._handle_success_results,
            ),
            (
                SeedResultOutcomeClass.WORKER_FAILURE,
                self._handle_worker_failure_results,
            ),
            (
                SeedResultOutcomeClass.ADAPTER_REJECTION,
                self._handle_adapter_rejection_results,
            ),
        )

    def route_seed_results(self, *, config: ResultRoutingConfig) -> int:
        result_time_millis = self._current_time_millis()
        routed_count = 0
        for outcome_class, handler in self._outcome_handlers:
            batch = self._consume_decoded(
                outcome_class=outcome_class,
                limit=config.per_outcome_batch_limit,
            )
            if batch.decoded_count == 0:
                continue
            handler(batch, result_time_millis)
            routed_count += batch.decoded_count
        return routed_count

    def _handle_success_results(
        self,
        batch: _DecodedSeedResultBatch,
        result_time_millis: TimeMillis,
    ) -> None:
        for task_id, entries in batch.results_by_task.items():
            results: dict[MessageId, str] = {}
            for entry in entries:
                payload = entry.result.opaque_result_payload
                if payload is not None:
                    results[entry.context.message_id] = payload
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

        for worker_group_id, entries in batch.results_by_worker_group.items():
            self.worker_score.release_score_holds(
                home_bucket_id=worker_group_id,
                observed_scores=self._latest_worker_scores(entries),
                release_time_millis=result_time_millis,
            )

    def _handle_worker_failure_results(
        self,
        batch: _DecodedSeedResultBatch,
        release_time_millis: TimeMillis,
    ) -> None:
        for worker_group_id, entries in batch.results_by_worker_group.items():
            self.worker_score.release_score_holds(
                home_bucket_id=worker_group_id,
                observed_scores=self._latest_worker_scores(entries),
                release_time_millis=release_time_millis,
            )

    def _handle_adapter_rejection_results(
        self,
        batch: _DecodedSeedResultBatch,
        _result_time_millis: TimeMillis,
    ) -> None:
        for worker_group_id, entries in batch.results_by_worker_group.items():
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
        results_by_task: dict[TaskId, list[_DecodedSeedResult]] = defaultdict(list)
        results_by_worker_group: dict[
            WorkerGroupId, list[_DecodedSeedResult]
        ] = defaultdict(list)
        for result in results:
            context = decode_result_context(result.opaque_result_context)
            if (
                context is not None
                and classify_seed_result_outcome_code(result.outcome_code)
                is outcome_class
            ):
                entry = _DecodedSeedResult(result, context)
                decoded_count += 1
                results_by_task[context.task_id].append(entry)
                results_by_worker_group[context.worker_group_id].append(entry)
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
        entries: tuple[_DecodedSeedResult, ...],
    ) -> dict[WorkerId, WorkerScore]:
        return {
            entry.context.worker_id: entry.context.worker_lease_score
            for entry in entries
        }

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
