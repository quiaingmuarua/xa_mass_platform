from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from time import time_ns
from typing import Sequence

from ..kernel.task_dispatch_runtime import TaskDispatchRuntime
from ..kernel.task_item_score_band import (
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreTransitionStatus,
)
from ..kernel.task_runtime import TaskItem, TaskRuntime
from ..kernel.task_score_band import Score, TaskId, TaskScoreBandCore, TimeMillis
from ..kernel.worker_runtime import EndpointManagerId, WorkerGroupId
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId


@dataclass(frozen=True)
class DeliverSeed:
    """Already-assigned TaskItem handoff for one endpoint manager."""

    task_id: TaskId
    selected_worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: EndpointManagerId
    task_item: TaskItem
    claim_score: Score
    worker_lease_score: WorkerScore


class DeliverSeedQueue(ABC):
    """Owner surface for endpoint-manager-partitioned outbound handoff."""

    @abstractmethod
    def append_deliver_seeds(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        deliver_seeds: Sequence[DeliverSeed],
    ) -> None:
        """Append one bounded batch to exactly one endpoint-manager queue."""
        pass


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
        dispatch_runtime: TaskDispatchRuntime,
        item_score: TaskItemScoreBandCore,
        task_runtime: TaskRuntime,
        deliver_seed_queue: DeliverSeedQueue,
    ) -> None:
        self.task_score = task_score
        self.dispatch_runtime = dispatch_runtime
        self.item_score = item_score
        self.task_runtime = task_runtime
        self.deliver_seed_queue = deliver_seed_queue

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
        seeds_by_endpoint_manager: dict[EndpointManagerId, list[DeliverSeed]] = {}

        for task_id in task_ids:
            candidate_workers = self.dispatch_runtime.consume_candidate_workers(
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
            for candidate_worker, (task_item, claim_score) in zip(
                candidate_workers,
                claimed_items,
            ):
                seed = DeliverSeed(
                    task_id=task_id,
                    selected_worker_id=candidate_worker.worker_id,
                    worker_group_id=candidate_worker.worker_group_id,
                    endpoint_manager_id=candidate_worker.endpoint_manager_id,
                    task_item=task_item,
                    claim_score=claim_score,
                    worker_lease_score=candidate_worker.worker_lease_score,
                )
                seeds_by_endpoint_manager.setdefault(
                    candidate_worker.endpoint_manager_id,
                    [],
                ).append(seed)

        appended_seed_count = 0
        for endpoint_manager_id, deliver_seeds in seeds_by_endpoint_manager.items():
            self.deliver_seed_queue.append_deliver_seeds(
                endpoint_manager_id=endpoint_manager_id,
                deliver_seeds=tuple(deliver_seeds),
            )
            appended_seed_count += len(deliver_seeds)
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
