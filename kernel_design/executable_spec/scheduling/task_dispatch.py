from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from time import time_ns
from typing import cast

from ..kernel.assignment_dispatch_runtime import (
    CandidateWarmupSchedule,
    CandidateWorkerEntry,
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
    TaskIdleDisposition,
    TaskItem,
    TaskResourceCatalog,
    TaskRuntime,
    WorkerAllocationMechanism,
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
from ..kernel.worker_delivery import (
    WorkerCommandAppendStatus,
    DeliveryCommand,
    WorkerCommandRuntime,
    DeliveryEndpoint,
)
from .worker_candidate import (
    WorkerCandidateAcquirer,
    WorkerCandidateAcquisitionStrategy,
    WorkerCandidateRequest,
)


def _encode_event_payload(task_item: TaskItem) -> str:
    return json.dumps(
        dict(task_item.payload),
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

    def __post_init__(self) -> None:
        if self.task_batch_limit <= 0:
            raise ValueError("task batch limit must be positive")
        if self.per_task_dispatch_limit <= 0:
            raise ValueError("per-task dispatch limit must be positive")
        if self.item_claim_lease_duration_millis <= 0:
            raise ValueError("item claim lease duration must be positive")


class TaskItemDispatcher:
    """Bind bounded TaskItems to Workers and build commands for one Task."""

    def __init__(
        self,
        item_score: TaskItemScoreBandCore,
        task_runtime: TaskRuntime,
        candidate_acquirer: WorkerCandidateAcquirer,
        candidate_warmup_schedule: CandidateWarmupSchedule,
        payload_encoder: Callable[[TaskItem], str] = _encode_event_payload,
    ) -> None:
        self.item_score = item_score
        self.task_runtime = task_runtime
        self.candidate_acquirer = candidate_acquirer
        self.candidate_warmup_schedule = candidate_warmup_schedule
        self._payload_encoder = payload_encoder

    def observe_claimable_task_items(
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

    def dispatch_task_items(
        self,
        *,
        task_id: TaskId,
        descriptor: TaskDescriptor,
        claimable_items: tuple[tuple[TaskItem, Score], ...],
        claim_until_millis: TimeMillis,
        warmup_due_time_millis: TimeMillis,
    ) -> dict[
        EndpointManagerId,
        dict[str, DeliveryCommand],
    ]:
        candidate_workers_by_message_id = self._acquire_candidate_workers(
            task_id=task_id,
            descriptor=descriptor,
            claimable_items=claimable_items,
            lease_until_millis=claim_until_millis,
            warmup_due_time_millis=warmup_due_time_millis,
        )
        if not candidate_workers_by_message_id:
            return {}

        dispatch_assignments = self._claim_task_items(
            task_id=task_id,
            claimable_items=claimable_items,
            candidate_workers_by_message_id=candidate_workers_by_message_id,
            claim_until_millis=claim_until_millis,
        )
        if not dispatch_assignments:
            return {}

        worker_commands: dict[
            EndpointManagerId,
            dict[str, DeliveryCommand],
        ] = {}
        for candidate_worker, task_item in dispatch_assignments:
            command = DeliveryCommand.create(
                src=DeliveryEndpoint.TASK,
                dst=DeliveryEndpoint.WORKER,
                message_type=task_item.event_code,
                execute_before_millis=claim_until_millis,
                payload=self._payload_encoder(task_item),
                forward=encode_result_context(
                    ResultContext(
                        task_id=task_id,
                        message_id=task_item.message_id,
                        worker_id=candidate_worker.worker_id,
                        worker_group_id=candidate_worker.worker_group_id,
                        worker_lease_score=candidate_worker.worker_lease_score,
                    )
                ),
            )
            worker_commands.setdefault(
                candidate_worker.endpoint_manager_id,
                {},
            )[candidate_worker.worker_id] = command
        return worker_commands

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
        if (
            descriptor.worker_allocation_mechanism
            is WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
        ):
            task_rule = cast(Mapping[str, object], descriptor.allocation_rule)
            precomputed = self.candidate_acquirer.acquire_worker_candidates(
                strategy=WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
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

        direct_requests: dict[MessageId, WorkerCandidateRequest] = {}
        for item in task_items:
            item_rule = cast(Mapping[str, object], item.allocation_rule)
            direct_requests[item.message_id] = WorkerCandidateRequest(
                priority=priority,
                requested_count=1,
                allocation_rule=item_rule,
            )
        direct = self.candidate_acquirer.acquire_worker_candidates(
            strategy=WorkerCandidateAcquisitionStrategy.DIRECT,
            worker_group_id=descriptor.worker_group_id,
            candidate_requests=direct_requests,
            lease_until_millis=lease_until_millis,
        )
        return {
            item.message_id: entries[0]
            for item in task_items
            if (entries := direct.get(item.message_id, ()))
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


class TaskDispatchPacer:
    """Pace RUNNING Tasks through Item dispatch or their idle disposition."""

    def __init__(
        self,
        task_score: TaskScoreBandCore,
        task_catalog: TaskResourceCatalog,
        worker_command_runtime: WorkerCommandRuntime,
        item_score: TaskItemScoreBandCore,
        task_item_dispatcher: TaskItemDispatcher,
    ) -> None:
        self.task_score = task_score
        self.task_catalog = task_catalog
        self.worker_command_runtime = worker_command_runtime
        self.item_score = item_score
        self.task_item_dispatcher = task_item_dispatcher

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

        round_worker_commands: dict[
            EndpointManagerId,
            dict[str, DeliveryCommand],
        ] = {}
        activity_recheck_tasks: dict[
            TaskId,
            tuple[TaskDescriptor, TaskScoreState],
        ] = {}
        for task_id, descriptor, state in active_tasks:
            claimable_items = self.task_item_dispatcher.observe_claimable_task_items(
                task_id=task_id,
                limit=config.per_task_dispatch_limit,
                observed_at_millis=dispatch_time_millis,
            )
            if not claimable_items:
                activity_recheck_tasks[task_id] = (descriptor, state)
                continue

            try:
                task_worker_commands = (
                    self.task_item_dispatcher.dispatch_task_items(
                        task_id=task_id,
                        descriptor=descriptor,
                        claimable_items=claimable_items,
                        claim_until_millis=claim_until_millis,
                        warmup_due_time_millis=dispatch_time_millis,
                    )
                )
                for endpoint_manager_id, commands in task_worker_commands.items():
                    endpoint_commands = round_worker_commands.setdefault(
                        endpoint_manager_id,
                        {},
                    )
                    duplicate_worker_ids = (
                        endpoint_commands.keys() & commands.keys()
                    )
                    if duplicate_worker_ids:
                        raise RuntimeError(
                            "one Worker received multiple commands in one round"
                        )
                    endpoint_commands.update(commands)
            finally:
                self.task_score.rewrite_same_band_time_millis(
                    task_id=task_id,
                    expected_band=TaskScoreBand.RUNNING_VISIBLE,
                    target_time_millis=dispatch_time_millis,
                )

        published_command_count = self._publish_worker_commands(
            worker_commands_by_endpoint_manager=round_worker_commands,
        )

        if activity_recheck_tasks:
            has_active_items = self.item_score.has_active_items(
                task_ids=tuple(activity_recheck_tasks),
            )
            parked_scores: dict[TaskId, Score] = {}
            for task_id, (descriptor, state) in activity_recheck_tasks.items():
                parked_score = self._apply_activity_recheck(
                    task_id=task_id,
                    descriptor=descriptor,
                    state=state,
                    has_active_items=has_active_items.get(task_id, False),
                    dispatch_time_millis=dispatch_time_millis,
                )
                if parked_score is not None:
                    parked_scores[task_id] = parked_score
            self._release_parks_with_concurrent_items(
                parked_scores,
                release_time_millis=dispatch_time_millis,
            )
        return published_command_count

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
            and state.suffix == TaskScoreBandCore.MIN_SUFFIX
        )

    def _apply_activity_recheck(
        self,
        *,
        task_id: TaskId,
        descriptor: TaskDescriptor,
        state: TaskScoreState,
        has_active_items: bool,
        dispatch_time_millis: TimeMillis,
    ) -> Score | None:
        if has_active_items:
            self.task_score.rewrite_same_band_time_millis(
                task_id=task_id,
                expected_band=TaskScoreBand.RUNNING_VISIBLE,
                target_time_millis=dispatch_time_millis,
            )
            return None

        if descriptor.idle_disposition is TaskIdleDisposition.CLOSE_WHEN_IDLE:
            self.task_score.close_observed_score(
                task_id=task_id,
                observed_score=state.score,
                terminal_score=TaskScoreBandCore.TERMINAL_SCORE_MAX,
            )
            return None

        parked = self.task_score.park_observed_idle_task(
            task_id=task_id,
            observed_score=state.score,
        )
        if parked.status is TaskScoreTransitionStatus.TRANSITIONED:
            return parked.score
        return None

    def _release_parks_with_concurrent_items(
        self,
        parked_scores: Mapping[TaskId, Score],
        *,
        release_time_millis: TimeMillis,
    ) -> None:
        if not parked_scores:
            return
        has_active_items = self.item_score.has_active_items(
            task_ids=tuple(parked_scores),
        )
        for task_id, parked_score in parked_scores.items():
            if has_active_items.get(task_id, False):
                self.task_score.release_observed_idle_task(
                    task_id=task_id,
                    observed_park_score=parked_score,
                    release_time_millis=release_time_millis,
                )

    def _publish_worker_commands(
        self,
        *,
        worker_commands_by_endpoint_manager: Mapping[
            EndpointManagerId,
            Mapping[str, DeliveryCommand],
        ],
    ) -> int:
        if not worker_commands_by_endpoint_manager:
            return 0

        worker_ids = tuple(
            worker_id
            for commands in worker_commands_by_endpoint_manager.values()
            for worker_id in commands
        )
        if len(set(worker_ids)) != len(worker_ids):
            raise RuntimeError(
                "one Worker received multiple commands in one round"
            )

        published = 0
        for endpoint_manager_id, worker_commands in (
            worker_commands_by_endpoint_manager.items()
        ):
            append_results = self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id=endpoint_manager_id,
                worker_commands_by_worker_id=worker_commands,
            )
            published += sum(
                status
                in {
                    WorkerCommandAppendStatus.APPENDED,
                    WorkerCommandAppendStatus.REPLACED,
                }
                for status in append_results.values()
            )
        return published

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
