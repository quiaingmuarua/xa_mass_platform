from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from time import time_ns

from ..kernel.assignment_dispatch_runtime import (
    CandidateWorkerEntry,
    DeliverSeed,
    DeliverSeedRuntime,
)
from ..kernel.result_context import ResultContext, encode_result_context
from ..kernel.task_item_score_band import (
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreTransitionStatus,
)
from ..kernel.task_runtime import (
    TaskDescriptor,
    TaskItem,
    TaskResourceCatalog,
    TaskRuntime,
)
from ..kernel.task_score_band import Score, TaskId, TaskScoreBandCore, TimeMillis
from ..kernel.worker_runtime import EndpointManagerId
from .worker_candidate import (
    WorkerCandidateAcquirer,
    WorkerCandidateAcquisitionStrategy,
    WorkerCandidateRequest,
)


def _encode_delivery_item(task_item: TaskItem) -> str:
    """Encode the built-in event-handler envelope."""
    return json.dumps(
        {
            "eventCode": task_item.event_code,
            "payload": dict(task_item.payload),
        },
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


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


WorkerCandidateAcquisitionStrategyResolver = Callable[
    [TaskDescriptor, tuple[TaskItem, ...]],
    WorkerCandidateAcquisitionStrategy,
]


class TaskItemDispatchPacer:
    """Compose Task discovery, candidate consumption, Item claim, and handoff."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        deliver_seed_runtime: DeliverSeedRuntime,
        item_score: TaskItemScoreBandCore,
        task_runtime: TaskRuntime,
        candidate_acquirer: WorkerCandidateAcquirer,
        candidate_strategy_resolver: WorkerCandidateAcquisitionStrategyResolver,
        delivery_item_encoder: Callable[[TaskItem], str] = _encode_delivery_item,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self.deliver_seed_runtime = deliver_seed_runtime
        self.item_score = item_score
        self.task_runtime = task_runtime
        self.candidate_acquirer = candidate_acquirer
        self.candidate_strategy_resolver = candidate_strategy_resolver
        self._delivery_item_encoder = delivery_item_encoder

    def dispatch_task_items(
        self,
        *,
        config: TaskItemDispatchConfig,
    ) -> int:
        dispatch_time_millis = self._current_time_millis()
        claim_until_millis = (
            dispatch_time_millis + config.item_claim_lease_duration_millis
        )
        active_tasks = self._acquire_dispatchable_task_descriptors(
            limit=config.task_batch_limit,
        )

        published_seed_count = 0
        for task_id, descriptor in active_tasks:
            claimable_items = self._observe_claimable_task_items(
                task_id=task_id,
                limit=config.per_task_dispatch_limit,
                observed_at_millis=dispatch_time_millis,
            )
            if not claimable_items:
                continue

            candidate_workers = self._acquire_candidate_workers(
                task_id=task_id,
                descriptor=descriptor,
                claimable_items=claimable_items,
                lease_until_millis=claim_until_millis,
            )
            if not candidate_workers:
                continue

            claimed_items = self._claim_task_items(
                task_id=task_id,
                claimable_items=claimable_items,
                maximum_claims=len(candidate_workers),
                claim_until_millis=claim_until_millis,
            )
            if not claimed_items:
                continue

            published_seed_count += self._publish_deliver_seeds(
                task_id=task_id,
                candidate_workers=candidate_workers,
                claimed_items=claimed_items,
                claim_until_millis=claim_until_millis,
            )
        return published_seed_count

    def _acquire_dispatchable_task_descriptors(
        self,
        *,
        limit: int,
    ) -> tuple[tuple[TaskId, TaskDescriptor], ...]:
        task_ids = self.task_score.acquire_dispatch_work_tasks(limit=limit)
        if not task_ids:
            return ()

        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )
        return tuple(
            (task_id, descriptor)
            for task_id in task_ids
            if (descriptor := descriptors.get(task_id)) is not None
        )

    def _observe_claimable_task_items(
        self,
        *,
        task_id: TaskId,
        limit: int,
        observed_at_millis: TimeMillis,
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
                target_time_millis=observed_at_millis,
            )

        claimable_scores: dict[str, Score] = {
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
        return tuple(
            (task_item, observed_score)
            for message_id, observed_score in claimable_scores.items()
            if (task_item := items.get(message_id)) is not None
        )

    def _acquire_candidate_workers(
        self,
        *,
        task_id: TaskId,
        descriptor: TaskDescriptor,
        claimable_items: tuple[tuple[TaskItem, Score], ...],
        lease_until_millis: TimeMillis,
    ) -> tuple[CandidateWorkerEntry, ...]:
        task_items = tuple(item for item, _ in claimable_items)
        strategy = self.candidate_strategy_resolver(
            descriptor,
            task_items,
        )
        acquired_candidates = self.candidate_acquirer.acquire_worker_candidates(
            strategy=strategy,
            worker_group_id=descriptor.worker_group_id,
            candidate_requests={
                task_id: WorkerCandidateRequest(
                    priority=int(descriptor.config["priority"]),
                    requested_count=len(claimable_items),
                    match_rules=descriptor.allocation_rule,
                )
            },
            lease_until_millis=lease_until_millis,
        )
        return acquired_candidates.get(task_id, ())

    def _claim_task_items(
        self,
        *,
        task_id: TaskId,
        claimable_items: tuple[tuple[TaskItem, Score], ...],
        maximum_claims: int,
        claim_until_millis: TimeMillis,
    ) -> tuple[TaskItem, ...]:
        bounded_items = claimable_items[:maximum_claims]
        if not bounded_items:
            return ()

        record_backed_scores = {
            task_item.message_id: observed_score
            for task_item, observed_score in bounded_items
        }

        claim_results = self.item_score.rewrite_observed_item_scores(
            task_id=task_id,
            observed_scores=record_backed_scores,
            target_time_millis=claim_until_millis,
            remaining_budget_delta=-1,
        )

        claimed_items: list[TaskItem] = []
        for task_item, _ in bounded_items:
            result = claim_results.get(task_item.message_id)
            if (
                result is None
                or result.status is not TaskItemScoreTransitionStatus.TRANSITIONED
                or result.score is None
            ):
                continue
            claimed_items.append(task_item)
        return tuple(claimed_items)

    def _publish_deliver_seeds(
        self,
        *,
        task_id: TaskId,
        candidate_workers: tuple[CandidateWorkerEntry, ...],
        claimed_items: tuple[TaskItem, ...],
        claim_until_millis: TimeMillis,
    ) -> int:
        endpoint_batches: dict[EndpointManagerId, list[DeliverSeed]] = {}
        for candidate_worker, task_item in zip(candidate_workers, claimed_items):
            seed = DeliverSeed(
                worker_id=candidate_worker.worker_id,
                opaque_delivery_item=self._delivery_item_encoder(task_item),
                opaque_result_context=encode_result_context(
                    ResultContext(
                        task_id=task_id,
                        message_id=task_item.message_id,
                        worker_id=candidate_worker.worker_id,
                        worker_group_id=candidate_worker.worker_group_id,
                        worker_lease_score=candidate_worker.worker_lease_score,
                    )
                ),
                task_item_claim_until_millis=claim_until_millis,
            )
            endpoint_batches.setdefault(
                candidate_worker.endpoint_manager_id,
                [],
            ).append(seed)

        published_seed_count = 0
        for endpoint_manager_id, endpoint_seeds in endpoint_batches.items():
            self.deliver_seed_runtime.append_deliver_seeds(
                endpoint_manager_id=endpoint_manager_id,
                deliver_seeds=tuple(endpoint_seeds),
            )
            published_seed_count += len(endpoint_seeds)
        return published_seed_count

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
