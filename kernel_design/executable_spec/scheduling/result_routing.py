from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from time import time_ns
from collections.abc import Callable, Sequence

from ..kernel.result_context import decode_result_context
from ..kernel.worker_delivery import DeliveryEndpoint, DeliveryReport
from ..kernel.task_item_score_band import TaskItemScoreBand, TaskItemScoreBandCore
from ..kernel.task_runtime import MessageId, TaskRuntime
from ..kernel.task_score_band import TaskId, TimeMillis
from ..kernel.worker_runtime import WorkerGroupId
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId, WorkerScoreCore


@dataclass(frozen=True, slots=True)
class TaskResultEvidence:
    task_id: TaskId
    message_id: MessageId
    opaque_result_payload: str


@dataclass(frozen=True, slots=True)
class WorkerResultEvidence:
    worker_id: WorkerId
    worker_lease_score: WorkerScore


@dataclass(frozen=True, slots=True)
class _DecodedTaskResultBatch:
    decoded_count: int
    results_by_task: dict[TaskId, tuple[TaskResultEvidence, ...]]
    results_by_worker_group: dict[
        WorkerGroupId, tuple[WorkerResultEvidence, ...]
    ]


class TaskResultBatchPolicy:
    """Apply one already-classified homogeneous Task result batch."""

    def __init__(
        self,
        *,
        task_runtime: TaskRuntime,
        item_score: TaskItemScoreBandCore,
        worker_score: WorkerScoreCore,
        clock_millis: Callable[[], int] | None = None,
    ) -> None:
        self.task_runtime = task_runtime
        self.item_score = item_score
        self.worker_score = worker_score
        self._clock_millis = clock_millis or self._current_time_millis

    def handle_success(self, batch: Sequence[DeliveryReport]) -> None:
        decoded = self._decode(batch, include_task_evidence=True)
        if decoded.decoded_count == 0:
            return
        result_time_millis = self._clock_millis()
        for task_id, entries in decoded.results_by_task.items():
            payloads_by_message_id: dict[MessageId, str] = {}
            for result in entries:
                payloads_by_message_id[result.message_id] = (
                    result.opaque_result_payload
                )
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
        self._release_workers(
            decoded.results_by_worker_group,
            completed=True,
        )

    def handle_failure(self, batch: Sequence[DeliveryReport]) -> None:
        decoded = self._decode(batch, include_task_evidence=False)
        if decoded.decoded_count == 0:
            return
        self._release_workers(
            decoded.results_by_worker_group,
            completed=False,
        )

    @staticmethod
    def _decode(
        batch: Sequence[DeliveryReport],
        *,
        include_task_evidence: bool,
    ) -> _DecodedTaskResultBatch:
        decoded_count = 0
        results_by_task: dict[TaskId, list[TaskResultEvidence]] = defaultdict(list)
        results_by_worker_group: dict[
            WorkerGroupId, list[WorkerResultEvidence]
        ] = defaultdict(list)
        for result in batch:
            context = decode_result_context(result.forward)
            if result.dst is not DeliveryEndpoint.TASK or context is None:
                continue
            decoded_count += 1
            if include_task_evidence:
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
        return _DecodedTaskResultBatch(
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

    def _release_workers(
        self,
        results_by_worker_group: dict[
            WorkerGroupId, tuple[WorkerResultEvidence, ...]
        ],
        *,
        completed: bool,
    ) -> None:
        for worker_group_id, results in results_by_worker_group.items():
            latest_worker_scores = {
                result.worker_id: result.worker_lease_score
                for result in results
            }
            release_time_millis = self._clock_millis()
            if completed:
                self.worker_score.release_completed_hot_score_holds(
                    home_bucket_id=worker_group_id,
                    observed_hot_scores=latest_worker_scores,
                    release_time_millis=release_time_millis,
                )
            else:
                self.worker_score.release_score_holds(
                    home_bucket_id=worker_group_id,
                    observed_scores=latest_worker_scores,
                    release_time_millis=release_time_millis,
                )

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
