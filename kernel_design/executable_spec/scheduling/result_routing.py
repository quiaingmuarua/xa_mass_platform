from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from time import time_ns

from ..kernel.result_context import decode_result_context
from ..kernel.task_result_runtime import (
    TaskResultClass,
    TaskResultRuntime,
)
from ..kernel.worker_delivery import DeliveryEndpoint
from ..kernel.task_item_score_band import TaskItemScoreBand, TaskItemScoreBandCore
from ..kernel.task_runtime import MessageId, TaskRuntime
from ..kernel.task_score_band import TaskId, TimeMillis
from ..kernel.worker_runtime import WorkerGroupId
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId, WorkerScoreCore


@dataclass(frozen=True, slots=True)
class ResultRoutingConfig:
    per_result_class_batch_limit: int

    def __post_init__(self) -> None:
        if self.per_result_class_batch_limit <= 0:
            raise ValueError(
                "per-result-class routing batch limit must be positive"
            )


@dataclass(frozen=True, slots=True)
class TaskResultEvidence:
    task_id: TaskId
    message_id: MessageId
    opaque_result_payload: str


@dataclass(frozen=True, slots=True)
class WorkerResultEvidence:
    worker_id: WorkerId
    worker_lease_score: WorkerScore


class ResultRoutingBuiltinPolicies:
    def __init__(
        self,
        *,
        task_runtime: TaskRuntime,
        item_score: TaskItemScoreBandCore,
        worker_score: WorkerScoreCore,
    ) -> None:
        self.task_runtime = task_runtime
        self.item_score = item_score
        self.worker_score = worker_score

    def store_task_success_results(
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

    def release_worker_score_holds(
        self,
        *,
        worker_group_id: WorkerGroupId,
        results: tuple[WorkerResultEvidence, ...],
        result_time_millis: TimeMillis,
    ) -> None:
        self.worker_score.release_score_holds(
            home_bucket_id=worker_group_id,
            observed_scores=self._latest_worker_scores(results),
            release_time_millis=self._current_time_millis(),
        )

    def release_completed_hot_score_holds(
        self,
        *,
        worker_group_id: WorkerGroupId,
        results: tuple[WorkerResultEvidence, ...],
        result_time_millis: TimeMillis,
    ) -> None:
        self.worker_score.release_completed_hot_score_holds(
            home_bucket_id=worker_group_id,
            observed_hot_scores=self._latest_worker_scores(results),
            release_time_millis=self._current_time_millis(),
        )

    @staticmethod
    def _latest_worker_scores(
        results: tuple[WorkerResultEvidence, ...],
    ) -> dict[WorkerId, WorkerScore]:
        return {
            result.worker_id: result.worker_lease_score
            for result in results
        }

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000


@dataclass(frozen=True, slots=True)
class _DecodedWorkerResultBatch:
    decoded_count: int
    results_by_task: dict[TaskId, tuple[TaskResultEvidence, ...]]
    results_by_worker_group: dict[
        WorkerGroupId, tuple[WorkerResultEvidence, ...]
    ]


_RESULT_CLASSES = (
    TaskResultClass.SUCCESS,
    TaskResultClass.FAILURE,
)


class ResultRoutingPacer:
    """Route Task result-class queues into TaskItem and Worker owners."""

    def __init__(
        self,
        task_result_runtime: TaskResultRuntime,
        *,
        task_runtime: TaskRuntime,
        item_score: TaskItemScoreBandCore,
        worker_score: WorkerScoreCore,
    ) -> None:
        if not isinstance(task_result_runtime, TaskResultRuntime):
            raise TypeError("task_result_runtime must be TaskResultRuntime")
        self.task_result_runtime = task_result_runtime
        self._policies = ResultRoutingBuiltinPolicies(
            task_runtime=task_runtime,
            item_score=item_score,
            worker_score=worker_score,
        )

    def route_worker_results(self, *, config: ResultRoutingConfig) -> int:
        result_time_millis = self._current_time_millis()
        routed_count = 0
        for result_class in _RESULT_CLASSES:
            batch = self._consume_decoded(
                result_class=result_class,
                limit=config.per_result_class_batch_limit,
            )
            if batch.decoded_count == 0:
                continue
            if result_class is TaskResultClass.SUCCESS:
                for task_id, entries in batch.results_by_task.items():
                    self._policies.store_task_success_results(
                        task_id=task_id,
                        results=entries,
                        result_time_millis=result_time_millis,
                    )
            for worker_group_id, entries in batch.results_by_worker_group.items():
                if result_class is TaskResultClass.SUCCESS:
                    self._policies.release_completed_hot_score_holds(
                        worker_group_id=worker_group_id,
                        results=entries,
                        result_time_millis=result_time_millis,
                    )
                else:
                    self._policies.release_worker_score_holds(
                        worker_group_id=worker_group_id,
                        results=entries,
                        result_time_millis=result_time_millis,
                    )
            routed_count += batch.decoded_count
        return routed_count

    def _consume_decoded(
        self,
        *,
        result_class: TaskResultClass,
        limit: int,
    ) -> _DecodedWorkerResultBatch:
        results = self.task_result_runtime.consume_task_results(
            result_class=result_class,
            limit=limit,
        )
        decoded_count = 0
        results_by_task: dict[TaskId, list[TaskResultEvidence]] = defaultdict(list)
        results_by_worker_group: dict[
            WorkerGroupId, list[WorkerResultEvidence]
        ] = defaultdict(list)
        for result in results:
            context = decode_result_context(result.forward)
            if (
                result.dst is DeliveryEndpoint.TASK
                and context is not None
            ):
                decoded_count += 1
                if result_class is TaskResultClass.SUCCESS:
                    results_by_task[context.task_id].append(
                        TaskResultEvidence(
                            task_id=context.task_id,
                            message_id=context.message_id,
                            opaque_result_payload=result.payload,
                        )
                    )
                results_by_worker_group[context.worker_group_id].append(
                    WorkerResultEvidence(
                        worker_id=context.worker_id,
                        worker_lease_score=context.worker_lease_score,
                    )
                )
        return _DecodedWorkerResultBatch(
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
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
