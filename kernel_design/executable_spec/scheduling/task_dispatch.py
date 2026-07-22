from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from time import time_ns
from typing import cast

from ..kernel.assignment_dispatch_runtime import (
    CandidateWarmupSchedule,
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
    MessageId,
    TaskDescriptor,
    TaskItem,
    TaskResourceCatalog,
    TaskRuntime,
)
from ..kernel.task_score_band import (
    Score,
    TaskId,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionStatus,
    TimeMillis,
)
from ..kernel.worker_runtime import EndpointManagerId
from .worker_candidate import (
    WorkerCandidateAcquirer,
    WorkerCandidateRequest,
)
from .worker_candidate.rules import select_target_field
from .task_scheduling_profile import (
    TaskAllocationRuleOwner,
    resolve_task_scheduling_profile,
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
class TaskDispatchConfig:
    """Bounds and timing policy supplied to one RUNNING Task round."""

    task_batch_limit: int
    per_task_dispatch_limit: int
    item_claim_lease_duration_millis: TimeMillis
    max_empty_recheck_times: int
    empty_recheck_interval_millis: TimeMillis

    def __post_init__(self) -> None:
        if self.task_batch_limit <= 0:
            raise ValueError("task batch limit must be positive")
        if self.per_task_dispatch_limit <= 0:
            raise ValueError("per-task dispatch limit must be positive")
        if self.item_claim_lease_duration_millis <= 0:
            raise ValueError("item claim lease duration must be positive")
        if not (
            1 <= self.max_empty_recheck_times <= TaskScoreBandCore.MAX_SUFFIX
        ):
            raise ValueError("max empty recheck times must be in 1..99")
        if self.empty_recheck_interval_millis <= 0:
            raise ValueError("empty recheck interval must be positive")


class TaskDispatchPacer:
    """Pace RUNNING Tasks through Item dispatch or empty recheck."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        deliver_seed_runtime: DeliverSeedRuntime,
        item_score: TaskItemScoreBandCore,
        task_runtime: TaskRuntime,
        candidate_acquirer: WorkerCandidateAcquirer,
        candidate_warmup_schedule: CandidateWarmupSchedule,
        delivery_item_encoder: Callable[[TaskItem], str] = _encode_delivery_item,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self.deliver_seed_runtime = deliver_seed_runtime
        self.item_score = item_score
        self.task_runtime = task_runtime
        self.candidate_acquirer = candidate_acquirer
        self.candidate_warmup_schedule = candidate_warmup_schedule
        self._delivery_item_encoder = delivery_item_encoder

    def dispatch_tasks(
        self,
        *,
        config: TaskDispatchConfig,
    ) -> int:
        dispatch_time_millis = self._current_time_millis()
        claim_until_millis = (
            dispatch_time_millis + config.item_claim_lease_duration_millis
        )
        active_tasks = self._acquire_dispatchable_tasks(
            limit=config.task_batch_limit,
        )

        published_seed_count = 0
        activity_recheck_tasks: dict[
            TaskId,
            tuple[TaskDescriptor, TaskScoreState],
        ] = {}
        for task_id, descriptor, state in active_tasks:
            if state.suffix != TaskScoreBandCore.MIN_SUFFIX:
                activity_recheck_tasks[task_id] = (descriptor, state)
                continue

            claimable_items = self._observe_claimable_task_items(
                task_id=task_id,
                limit=config.per_task_dispatch_limit,
                observed_at_millis=dispatch_time_millis,
            )
            if not claimable_items:
                activity_recheck_tasks[task_id] = (descriptor, state)
                continue

            try:
                published_seed_count += self._dispatch_claimable_task_items(
                    task_id=task_id,
                    descriptor=descriptor,
                    claimable_items=claimable_items,
                    claim_until_millis=claim_until_millis,
                    warmup_due_time_millis=dispatch_time_millis,
                )
            finally:
                self.task_score.rewrite_same_band_time_millis(
                    task_id=task_id,
                    expected_band=TaskScoreBand.RUNNING_VISIBLE,
                    target_time_millis=dispatch_time_millis,
                )

        if activity_recheck_tasks:
            has_active_items = self.item_score.has_active_items(
                task_ids=tuple(activity_recheck_tasks),
            )
            for task_id, (descriptor, state) in activity_recheck_tasks.items():
                self._apply_activity_recheck(
                    task_id=task_id,
                    descriptor=descriptor,
                    state=state,
                    has_active_items=has_active_items.get(task_id, False),
                    dispatch_time_millis=dispatch_time_millis,
                    config=config,
                )
        return published_seed_count

    def _dispatch_claimable_task_items(
        self,
        *,
        task_id: TaskId,
        descriptor: TaskDescriptor,
        claimable_items: tuple[tuple[TaskItem, Score], ...],
        claim_until_millis: TimeMillis,
        warmup_due_time_millis: TimeMillis,
    ) -> int:
        candidate_workers_by_message_id = self._acquire_candidate_workers(
            task_id=task_id,
            descriptor=descriptor,
            claimable_items=claimable_items,
            lease_until_millis=claim_until_millis,
            warmup_due_time_millis=warmup_due_time_millis,
        )
        if not candidate_workers_by_message_id:
            return 0

        dispatch_assignments = self._claim_task_items(
            task_id=task_id,
            claimable_items=claimable_items,
            candidate_workers_by_message_id=candidate_workers_by_message_id,
            claim_until_millis=claim_until_millis,
        )
        if not dispatch_assignments:
            return 0

        return self._publish_deliver_seeds(
            task_id=task_id,
            dispatch_assignments=dispatch_assignments,
            claim_until_millis=claim_until_millis,
        )

    def _acquire_dispatchable_tasks(
        self,
        *,
        limit: int,
    ) -> tuple[tuple[TaskId, TaskDescriptor, TaskScoreState], ...]:
        task_ids = self.task_score.acquire_dispatch_work_tasks(limit=limit)
        if not task_ids:
            return ()

        states = self.task_score.get_score_states(task_ids=task_ids)
        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )
        return tuple(
            (task_id, descriptor, state)
            for task_id in task_ids
            if (descriptor := descriptors.get(task_id)) is not None
            and (state := states.get(task_id)) is not None
            and state.band is TaskScoreBand.RUNNING_VISIBLE
            and state.suffix is not None
        )

    def _apply_activity_recheck(
        self,
        *,
        task_id: TaskId,
        descriptor: TaskDescriptor,
        state: TaskScoreState,
        has_active_items: bool,
        dispatch_time_millis: TimeMillis,
        config: TaskDispatchConfig,
    ) -> None:
        suffix = state.suffix
        assert suffix is not None

        if has_active_items:
            if suffix == TaskScoreBandCore.MIN_SUFFIX:
                self.task_score.rewrite_same_band_time_millis(
                    task_id=task_id,
                    expected_band=TaskScoreBand.RUNNING_VISIBLE,
                    target_time_millis=dispatch_time_millis,
                )
                return
            reset = self.task_score.rewrite_observed_same_band_suffix(
                task_id=task_id,
                observed_score=state.score,
                target_time_millis=dispatch_time_millis,
                suffix_delta=-suffix,
            )
            if (
                reset.status is TaskScoreTransitionStatus.TRANSITIONED
                and resolve_task_scheduling_profile(
                    descriptor.task_type
                ).candidate_precomputation_enabled
            ):
                self.candidate_warmup_schedule.schedule_candidate_warmups(
                    task_ids=(task_id,),
                    due_time_millis=dispatch_time_millis,
                )
            return

        if suffix >= config.max_empty_recheck_times:
            empty_close_at_millis = descriptor.empty_close_at_millis
            assert empty_close_at_millis is not None
            if dispatch_time_millis >= empty_close_at_millis:
                self.task_score.close_score(
                    task_id=task_id,
                    terminal_score=TaskScoreBandCore.TERMINAL_SCORE_MAX,
                )
                return
            self.task_score.rewrite_same_band_time_millis(
                task_id=task_id,
                expected_band=TaskScoreBand.RUNNING_VISIBLE,
                target_time_millis=min(
                    empty_close_at_millis,
                    dispatch_time_millis
                    + config.max_empty_recheck_times
                    * config.empty_recheck_interval_millis,
                ),
            )
            return

        next_suffix = suffix + 1
        self.task_score.rewrite_observed_same_band_suffix(
            task_id=task_id,
            observed_score=state.score,
            target_time_millis=(
                dispatch_time_millis
                + next_suffix * config.empty_recheck_interval_millis
            ),
            suffix_delta=1,
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
        claimable_scores: dict[str, Score] = {
            message_id: observed_score
            for message_id, (observed_score, remaining_budget) in observations.items()
            if remaining_budget > 0
        }
        items = (
            self.task_runtime.load_task_items(
                task_id=task_id,
                message_ids=tuple(claimable_scores),
            )
            if claimable_scores
            else {}
        )
        expired_message_ids = tuple(
            message_id
            for message_id in claimable_scores
            if (task_item := items.get(message_id)) is not None
            and task_item.expire_at_millis is not None
            and observed_at_millis >= task_item.expire_at_millis
        )
        final_failed_message_ids = exhausted_message_ids + expired_message_ids
        if final_failed_message_ids:
            self.item_score.promote_item_outcomes(
                task_id=task_id,
                message_ids=final_failed_message_ids,
                target_band=TaskItemScoreBand.FINAL_FAILED,
                target_time_millis=observed_at_millis,
            )

        expired = frozenset(expired_message_ids)
        return tuple(
            (task_item, observed_score)
            for message_id, observed_score in claimable_scores.items()
            if message_id not in expired
            if (task_item := items.get(message_id)) is not None
        )

    def _acquire_candidate_workers(
        self,
        *,
        task_id: TaskId,
        descriptor: TaskDescriptor,
        claimable_items: tuple[tuple[TaskItem, Score], ...],
        lease_until_millis: TimeMillis,
        warmup_due_time_millis: TimeMillis,
    ) -> dict[MessageId, CandidateWorkerEntry]:
        priority = int(descriptor.config["priority"])
        task_items = tuple(item for item, _ in claimable_items)
        profile = resolve_task_scheduling_profile(descriptor.task_type)
        if profile.allocation_rule_owner is TaskAllocationRuleOwner.TASK:
            task_rule = cast(Mapping[str, object], descriptor.allocation_rule)
            precomputed = self.candidate_acquirer.acquire_worker_candidates(
                strategy=profile.dispatch_acquisition_strategy,
                worker_group_id=descriptor.worker_group_id,
                candidate_requests={
                    task_id: WorkerCandidateRequest(
                        priority=priority,
                        requested_count=len(task_items),
                        allocation_rule=task_rule,
                    )
                },
                lease_until_millis=lease_until_millis,
            ).get(task_id, ())
            self.candidate_warmup_schedule.schedule_candidate_warmups(
                task_ids=(task_id,),
                due_time_millis=warmup_due_time_millis,
            )
            return {
                item.message_id: candidate_worker
                for item, candidate_worker in zip(task_items, precomputed)
            }

        targeted_requests: dict[MessageId, WorkerCandidateRequest] = {}
        for item in task_items:
            item_rule = cast(Mapping[str, object], item.allocation_rule)
            targeted_requests[item.message_id] = WorkerCandidateRequest(
                priority=priority,
                requested_count=1,
                allocation_rule=item_rule,
                target_field=select_target_field(item_rule),
            )
        targeted = self.candidate_acquirer.acquire_worker_candidates(
            strategy=profile.dispatch_acquisition_strategy,
            worker_group_id=descriptor.worker_group_id,
            candidate_requests=targeted_requests,
            lease_until_millis=lease_until_millis,
        )
        return {
            item.message_id: entries[0]
            for item in task_items
            if (entries := targeted.get(item.message_id, ()))
        }

    def _claim_task_items(
        self,
        *,
        task_id: TaskId,
        claimable_items: tuple[tuple[TaskItem, Score], ...],
        candidate_workers_by_message_id: Mapping[
            MessageId,
            CandidateWorkerEntry,
        ],
        claim_until_millis: TimeMillis,
    ) -> tuple[tuple[CandidateWorkerEntry, TaskItem], ...]:
        candidate_backed_items = tuple(
            (task_item, observed_score)
            for task_item, observed_score in claimable_items
            if task_item.message_id in candidate_workers_by_message_id
        )
        if not candidate_backed_items:
            return ()

        record_backed_scores = {
            task_item.message_id: observed_score
            for task_item, observed_score in candidate_backed_items
        }

        claim_results = self.item_score.rewrite_observed_item_scores(
            task_id=task_id,
            observed_scores=record_backed_scores,
            target_time_millis=claim_until_millis,
            remaining_budget_delta=-1,
        )

        dispatch_assignments: list[tuple[CandidateWorkerEntry, TaskItem]] = []
        for task_item, _ in candidate_backed_items:
            result = claim_results.get(task_item.message_id)
            if (
                result is None
                or result.status is not TaskItemScoreTransitionStatus.TRANSITIONED
                or result.score is None
            ):
                continue
            dispatch_assignments.append(
                (candidate_workers_by_message_id[task_item.message_id], task_item)
            )
        return tuple(dispatch_assignments)

    def _publish_deliver_seeds(
        self,
        *,
        task_id: TaskId,
        dispatch_assignments: tuple[
            tuple[CandidateWorkerEntry, TaskItem],
            ...,
        ],
        claim_until_millis: TimeMillis,
    ) -> int:
        endpoint_batches: dict[EndpointManagerId, list[DeliverSeed]] = {}
        for candidate_worker, task_item in dispatch_assignments:
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
