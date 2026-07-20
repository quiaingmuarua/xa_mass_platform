from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from time import time_ns

from ..kernel.assignment_dispatch_runtime import (
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


WorkerCandidateAcquirerResolver = Callable[
    [TaskDescriptor, tuple[TaskItem, ...]],
    WorkerCandidateAcquirer,
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
        candidate_acquirer_resolver: WorkerCandidateAcquirerResolver,
        delivery_item_encoder: Callable[[TaskItem], str] = _encode_delivery_item,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self.deliver_seed_runtime = deliver_seed_runtime
        self.item_score = item_score
        self.task_runtime = task_runtime
        self.candidate_acquirer_resolver = candidate_acquirer_resolver
        self._delivery_item_encoder = delivery_item_encoder

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
        task_descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )
        appended_seed_count = 0

        for task_id in task_ids:
            descriptor = task_descriptors.get(task_id)
            if descriptor is None:
                continue

            claimable_items = self._load_claimable_task_items(
                task_id=task_id,
                limit=config.per_task_dispatch_limit,
                now_millis=now_millis,
            )
            if not claimable_items:
                continue

            task_items = tuple(item for item, _ in claimable_items)
            candidate_acquirer = self.candidate_acquirer_resolver(
                descriptor,
                task_items,
            )
            acquired_candidates = candidate_acquirer.acquire_worker_candidates(
                worker_group_id=descriptor.worker_group_id,
                candidate_requests={
                    task_id: WorkerCandidateRequest(
                        priority=int(descriptor.config["priority"]),
                        requested_count=len(claimable_items),
                        match_rules=descriptor.allocation_rule,
                    )
                },
                lease_until_millis=claim_lease_until_millis,
            )
            candidate_workers = acquired_candidates.get(task_id, ())
            if not candidate_workers:
                continue

            claimed_items = self._claim_task_items(
                task_id=task_id,
                claimable_items=claimable_items[: len(candidate_workers)],
                claim_lease_until_millis=claim_lease_until_millis,
            )
            endpoint_batches: dict[EndpointManagerId, list[DeliverSeed]] = {}
            for candidate_worker, task_item in zip(
                candidate_workers,
                claimed_items,
            ):
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

    def _load_claimable_task_items(
        self,
        *,
        task_id: TaskId,
        limit: int,
        now_millis: TimeMillis,
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

    def _claim_task_items(
        self,
        *,
        task_id: TaskId,
        claimable_items: tuple[tuple[TaskItem, Score], ...],
        claim_lease_until_millis: TimeMillis,
    ) -> tuple[TaskItem, ...]:
        if not claimable_items:
            return ()

        record_backed_scores = {
            task_item.message_id: observed_score
            for task_item, observed_score in claimable_items
        }

        claim_results = self.item_score.rewrite_observed_item_scores(
            task_id=task_id,
            observed_scores=record_backed_scores,
            target_time_millis=claim_lease_until_millis,
            remaining_budget_delta=-1,
        )

        claimed_items: list[TaskItem] = []
        for task_item, _ in claimable_items:
            result = claim_results.get(task_item.message_id)
            if (
                result is None
                or result.status is not TaskItemScoreTransitionStatus.TRANSITIONED
                or result.score is None
            ):
                continue
            claimed_items.append(task_item)
        return tuple(claimed_items)

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
