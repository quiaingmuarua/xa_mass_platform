from __future__ import annotations

from collections.abc import Mapping
from collections import defaultdict
from dataclasses import dataclass
from time import time_ns
from typing import Protocol

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
class TaskResultEvidence:
    task_id: TaskId
    message_id: MessageId
    opaque_result_payload: str


@dataclass(frozen=True, slots=True)
class WorkerResultEvidence:
    task_id: TaskId
    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    worker_lease_score: WorkerScore
    outcome_code: str


class TaskResultHandler(Protocol):
    def __call__(
        self,
        *,
        task_id: TaskId,
        results: tuple[TaskResultEvidence, ...],
        result_time_millis: TimeMillis,
    ) -> None: ...


class WorkerResultHandler(Protocol):
    def __call__(
        self,
        *,
        worker_group_id: WorkerGroupId,
        results: tuple[WorkerResultEvidence, ...],
        result_time_millis: TimeMillis,
    ) -> None: ...


@dataclass(frozen=True, slots=True)
class _DecodedSeedResultBatch:
    decoded_count: int
    results_by_task: dict[TaskId, tuple[TaskResultEvidence, ...]]
    results_by_worker_group: dict[
        WorkerGroupId, tuple[WorkerResultEvidence, ...]
    ]


_OUTCOME_CLASSES = (
    SeedResultOutcomeClass.SUCCESS,
    SeedResultOutcomeClass.WORKER_FAILURE,
    SeedResultOutcomeClass.ADAPTER_REJECTION,
)


class ResultRoutingPacer:
    """Route outcome-class queues into TaskItem and Worker owners."""

    def __init__(
        self,
        seed_result_runtime: SeedResultRuntime,
        item_score: TaskItemScoreBandCore,
        worker_score: WorkerScoreCore,
        task_runtime: TaskRuntime,
        *,
        task_result_handlers: Mapping[
            SeedResultOutcomeClass, TaskResultHandler
        ] | None = None,
        worker_result_handlers: Mapping[
            SeedResultOutcomeClass, WorkerResultHandler
        ] | None = None,
    ) -> None:
        self.seed_result_runtime = seed_result_runtime
        self.item_score = item_score
        self.worker_score = worker_score
        self.task_runtime = task_runtime
        default_task_handlers = {SeedResultOutcomeClass.SUCCESS: self._handle_task_success_results,
        }
        default_worker_handlers = {
            SeedResultOutcomeClass.SUCCESS: self._release_worker_score_holds,
            SeedResultOutcomeClass.WORKER_FAILURE: self._release_worker_score_holds,
            SeedResultOutcomeClass.ADAPTER_REJECTION: self._demote_worker_score_holds_to_recovery,
        }
        self._task_result_handlers = dict(
            default_task_handlers
            if task_result_handlers is None
            else task_result_handlers
        )
        self._worker_result_handlers = dict(
            default_worker_handlers
            if worker_result_handlers is None
            else worker_result_handlers
        )
        self._validate_result_handlers()

    def route_seed_results(self, *, config: ResultRoutingConfig) -> int:
        result_time_millis = self._current_time_millis()
        routed_count = 0
        for outcome_class in _OUTCOME_CLASSES:
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
        results_by_task: dict[TaskId, tuple[TaskResultEvidence, ...]],
        result_time_millis: TimeMillis,
    ) -> None:
        handler = self._task_result_handlers.get(outcome_class)
        if handler is None:
            return
        for task_id, entries in results_by_task.items():
            handler(
                task_id=task_id,
                results=entries,
                result_time_millis=result_time_millis,
            )

    def _handle_worker_results(
        self,
        *,
        outcome_class: SeedResultOutcomeClass,
        results_by_worker_group: dict[
            WorkerGroupId, tuple[WorkerResultEvidence, ...]
        ],
        result_time_millis: TimeMillis,
    ) -> None:
        handler = self._worker_result_handlers[outcome_class]
        for worker_group_id, entries in results_by_worker_group.items():
            handler(
                worker_group_id=worker_group_id,
                results=entries,
                result_time_millis=result_time_millis,
            )

    def _handle_task_success_results(
        self,
        *,
        task_id: TaskId,
        results: tuple[TaskResultEvidence, ...],
        result_time_millis: TimeMillis,
    ) -> None:
        payloads_by_message_id: dict[MessageId, str] = {}
        for result in results:
            payloads_by_message_id[result.message_id] = result.opaque_result_payload
        self.task_runtime.store_task_item_success_results(
            task_id=task_id,
            results=payloads_by_message_id,
        )
        self.item_score.promote_item_outcomes(
            task_id=task_id,
            message_ids=tuple(payloads_by_message_id),
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=result_time_millis,
        )

    def _release_worker_score_holds(
        self,
        *,
        worker_group_id: WorkerGroupId,
        results: tuple[WorkerResultEvidence, ...],
        result_time_millis: TimeMillis,
    ) -> None:
        self.worker_score.release_score_holds(
            home_bucket_id=worker_group_id,
            observed_scores=self._latest_worker_scores(results),
            release_time_millis=result_time_millis,
        )

    def _demote_worker_score_holds_to_recovery(
        self,
        *,
        worker_group_id: WorkerGroupId,
        results: tuple[WorkerResultEvidence, ...],
        result_time_millis: TimeMillis,
    ) -> None:
        self.worker_score.demote_observed_worker_leases_to_recovery(
            home_bucket_id=worker_group_id,
            observed_scores=self._latest_worker_scores(results),
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
        results_by_task: dict[TaskId, list[TaskResultEvidence]] = defaultdict(list)
        results_by_worker_group: dict[
            WorkerGroupId, list[WorkerResultEvidence]
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
                            TaskResultEvidence(
                                task_id=context.task_id,
                                message_id=context.message_id,
                                opaque_result_payload=payload,
                            )
                        )
                results_by_worker_group[context.worker_group_id].append(
                    WorkerResultEvidence(
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
        results: tuple[WorkerResultEvidence, ...],
    ) -> dict[WorkerId, WorkerScore]:
        return {
            result.worker_id: result.worker_lease_score
            for result in results
        }

    def _validate_result_handlers(self) -> None:
        if set(self._task_result_handlers) != {SeedResultOutcomeClass.SUCCESS}:
            raise ValueError("Task result handlers must define SUCCESS exactly")
        if set(self._worker_result_handlers) != set(_OUTCOME_CLASSES):
            raise ValueError("Worker result handlers must define every outcome class")
        if not all(callable(handler) for handler in self._task_result_handlers.values()):
            raise TypeError("Task result handlers must be callable")
        if not all(callable(handler) for handler in self._worker_result_handlers.values()):
            raise TypeError("Worker result handlers must be callable")

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
