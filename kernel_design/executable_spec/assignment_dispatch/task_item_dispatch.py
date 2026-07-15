from __future__ import annotations

import json
from dataclasses import dataclass
from time import time_ns

from ..kernel.task_item_score_band import (
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreTransitionStatus,
)
from ..kernel.task_runtime import TaskItem, TaskRuntime
from ..kernel.task_score_band import Score, TaskId, TaskScoreBandCore, TimeMillis
from ..kernel.worker_runtime import EndpointManagerId
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId
from .runtime import AssignmentDispatchRuntime, DeliverSeed, DeliverSeedRuntime


@dataclass(frozen=True)
class TaskItemDispatchConfig:
    """Bounds and claim duration supplied to one TaskItem dispatch round."""

    task_batch_limit: int
    per_task_dispatch_limit: int
    item_claim_lease_duration_millis: TimeMillis

    def __post_init__(self) -> None:
        if self.task_batch_limit <= 0:
            raise ValueError("task batch limit must be positive")
        if self.per_task_dispatch_limit <= 0:
            raise ValueError("per-task dispatch limit must be positive")
        if self.item_claim_lease_duration_millis <= 0:
            raise ValueError("item claim lease duration must be positive")


class TaskItemDispatchPacer:
    """Compose Task discovery, candidate consumption, Item claim, and handoff."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        candidate_runtime: AssignmentDispatchRuntime,
        deliver_seed_runtime: DeliverSeedRuntime,
        item_score: TaskItemScoreBandCore,
        task_runtime: TaskRuntime,
    ) -> None:
        self.task_score = task_score
        self.candidate_runtime = candidate_runtime
        self.deliver_seed_runtime = deliver_seed_runtime
        self.item_score = item_score
        self.task_runtime = task_runtime

    def dispatch_task_items(
        self,
        *,
        config: TaskItemDispatchConfig,
    ) -> int:
        now_millis = self._current_time_millis()
        claim_lease_until_millis = (
            now_millis + config.item_claim_lease_duration_millis
        )
        task_ids = self.task_score.acquire_dispatch_work_tasks(
            limit=config.task_batch_limit,
        )
        appended_seed_count = 0

        for task_id in task_ids:
            candidate_workers = self.candidate_runtime.consume_candidate_workers(
                task_id=task_id,
                limit=config.per_task_dispatch_limit,
            )
            if not candidate_workers:
                continue

            claimed_items = self._claim_task_items(
                task_id=task_id,
                limit=len(candidate_workers),
                now_millis=now_millis,
                claim_lease_until_millis=claim_lease_until_millis,
            )
            endpoint_batches: dict[EndpointManagerId, list[DeliverSeed]] = {}
            for candidate_worker, (task_item, claim_score) in zip(
                candidate_workers,
                claimed_items,
            ):
                seed = DeliverSeed(
                    worker_id=candidate_worker.worker_id,
                    opaque_delivery_item=self._encode_delivery_item(task_item),
                    opaque_result_context=self._encode_result_context(
                        task_id=task_id,
                        task_item=task_item,
                        worker_id=candidate_worker.worker_id,
                        claim_score=claim_score,
                        worker_lease_score=candidate_worker.worker_lease_score,
                    ),
                    task_item_claim_until_millis=claim_lease_until_millis,
                )
                endpoint_batches.setdefault(
                    candidate_worker.endpoint_manager_id,
                    [],
                ).append(seed)

            for endpoint_manager_id, endpoint_seeds in endpoint_batches.items():
                self.deliver_seed_runtime.append_deliver_seeds(
                    endpoint_manager_id=endpoint_manager_id,
                    deliver_seeds=tuple(endpoint_seeds),
                )
                appended_seed_count += len(endpoint_seeds)
        return appended_seed_count

    def _claim_task_items(
        self,
        *,
        task_id: TaskId,
        limit: int,
        now_millis: TimeMillis,
        claim_lease_until_millis: TimeMillis,
    ) -> tuple[tuple[TaskItem, Score], ...]:
        observations = self.item_score.acquire_item_score_candidates(
            task_id=task_id,
            limit=limit,
        )
        if not observations:
            return ()

        exhausted_message_ids = tuple(
            message_id
            for message_id, (_, remaining_budget) in observations.items()
            if remaining_budget == 0
        )
        if exhausted_message_ids:
            self.item_score.promote_item_outcomes(
                task_id=task_id,
                message_ids=exhausted_message_ids,
                target_band=TaskItemScoreBand.FINAL_FAILED,
                target_time_millis=now_millis,
            )

        claimable_scores = {
            message_id: observed_score
            for message_id, (observed_score, remaining_budget) in observations.items()
            if remaining_budget > 0
        }
        if not claimable_scores:
            return ()

        items = self.task_runtime.load_task_items(
            task_id=task_id,
            message_ids=tuple(claimable_scores),
        )
        record_backed_scores = {
            message_id: observed_score
            for message_id, observed_score in claimable_scores.items()
            if items.get(message_id) is not None
        }
        if not record_backed_scores:
            return ()

        claim_results = self.item_score.rewrite_observed_item_scores(
            task_id=task_id,
            observed_scores=record_backed_scores,
            target_time_millis=claim_lease_until_millis,
            remaining_budget_delta=-1,
        )

        claimed_items: list[tuple[TaskItem, Score]] = []
        for message_id in record_backed_scores:
            result = claim_results.get(message_id)
            task_item = items.get(message_id)
            if (
                result is None
                or result.status is not TaskItemScoreTransitionStatus.TRANSITIONED
                or result.score is None
                or task_item is None
            ):
                continue
            claimed_items.append((task_item, result.score))
        return tuple(claimed_items)

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000

    @staticmethod
    def _encode_delivery_item(task_item: TaskItem) -> str:
        return json.dumps(
            {
                "messageId": task_item.message_id,
                "eventCode": task_item.event_code,
                "payload": (
                    dict(task_item.payload)
                    if task_item.payload is not None
                    else None
                ),
                "payloadRef": task_item.payload_ref,
                "priority": task_item.priority,
                "createdAtMillis": task_item.created_at_millis,
                "expireAtMillis": task_item.expire_at_millis,
            },
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )

    @staticmethod
    def _encode_result_context(
        *,
        task_id: TaskId,
        task_item: TaskItem,
        worker_id: WorkerId,
        claim_score: Score,
        worker_lease_score: WorkerScore,
    ) -> str:
        return json.dumps(
            {
                "taskId": task_id,
                "messageId": task_item.message_id,
                "workerId": worker_id,
                "claimScore": claim_score,
                "workerLeaseScore": worker_lease_score,
            },
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
