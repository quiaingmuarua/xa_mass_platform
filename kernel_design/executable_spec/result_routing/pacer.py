from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from time import time_ns

from ..kernel.task_item_score_band import (
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreTransitionResult,
    TaskItemScoreTransitionStatus,
)
from ..kernel.task_runtime import MessageId, TaskResourceCatalog
from ..kernel.task_score_band import TaskId, TimeMillis
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId, WorkerScoreCore
from .context import ResultContext, decode_result_context
from .runtime import SeedResult, SeedResultRuntime


@dataclass(frozen=True, slots=True)
class ResultRoutingConfig:
    batch_limit: int
    retry_delay_millis: TimeMillis

    def __post_init__(self) -> None:
        if self.batch_limit <= 0:
            raise ValueError("result-routing batch limit must be positive")
        if self.retry_delay_millis <= 0:
            raise ValueError("result retry delay must be positive")


@dataclass(frozen=True, slots=True)
class _DecodedSeedResult:
    result: SeedResult
    context: ResultContext


class ResultRoutingPacer:
    """Route bounded SeedResult evidence into Item and Worker score owners."""

    SUCCESS_OUTCOME_CODE = "200"

    def __init__(
        self,
        seed_result_runtime: SeedResultRuntime,
        item_score: TaskItemScoreBandCore,
        worker_score: WorkerScoreCore,
        task_catalog: TaskResourceCatalog,
    ) -> None:
        self.seed_result_runtime = seed_result_runtime
        self.item_score = item_score
        self.worker_score = worker_score
        self.task_catalog = task_catalog

    def route_seed_results(self, *, config: ResultRoutingConfig) -> int:
        now_millis = self._current_time_millis()
        results = self.seed_result_runtime.consume_seed_results(
            limit=config.batch_limit,
        )
        decoded = self._decode_results(results)
        if not decoded:
            return 0

        retained = self._retain_item_outcomes(decoded)
        transitioned_count = self._apply_item_outcomes(
            retained,
            now_millis=now_millis,
            retry_delay_millis=config.retry_delay_millis,
        )
        self._release_worker_holds(decoded, release_time_millis=now_millis)
        return transitioned_count

    @staticmethod
    def _decode_results(
        results: tuple[SeedResult, ...],
    ) -> tuple[_DecodedSeedResult, ...]:
        decoded: list[_DecodedSeedResult] = []
        for result in results:
            context = decode_result_context(result.opaque_result_context)
            if context is not None:
                decoded.append(_DecodedSeedResult(result, context))
        return tuple(decoded)

    @classmethod
    def _retain_item_outcomes(
        cls,
        decoded: tuple[_DecodedSeedResult, ...],
    ) -> tuple[_DecodedSeedResult, ...]:
        retained: dict[tuple[TaskId, MessageId], _DecodedSeedResult] = {}
        for entry in decoded:
            key = (entry.context.task_id, entry.context.message_id)
            current = retained.get(key)
            if current is not None and current.result.outcome_code == cls.SUCCESS_OUTCOME_CODE:
                continue
            retained[key] = entry
        return tuple(retained.values())

    def _apply_item_outcomes(
        self,
        retained: tuple[_DecodedSeedResult, ...],
        *,
        now_millis: TimeMillis,
        retry_delay_millis: TimeMillis,
    ) -> int:
        successes: dict[TaskId, list[MessageId]] = defaultdict(list)
        failures: dict[TaskId, dict[MessageId, int]] = defaultdict(dict)
        retry_base_by_task: dict[TaskId, TimeMillis] = {}

        for entry in retained:
            context = entry.context
            if entry.result.outcome_code == self.SUCCESS_OUTCOME_CODE:
                successes[context.task_id].append(context.message_id)
                continue
            failures[context.task_id][context.message_id] = context.claim_score
            retry_base_by_task[context.task_id] = max(
                retry_base_by_task.get(context.task_id, now_millis),
                context.task_item_claim_until_millis,
            )

        transitioned_count = 0
        for task_id, message_ids in successes.items():
            outcomes = self.item_score.promote_item_outcomes(
                task_id=task_id,
                message_ids=tuple(message_ids),
                target_band=TaskItemScoreBand.FINAL_SUCCESS,
                target_time_millis=now_millis,
            )
            transitioned_count += self._transitioned_count(outcomes.values())

        for task_id, observed_scores in failures.items():
            retries = self.item_score.rewrite_observed_item_scores(
                task_id=task_id,
                observed_scores=observed_scores,
                target_time_millis=(
                    retry_base_by_task[task_id] + retry_delay_millis
                ),
                remaining_budget_delta=0,
            )
            transitioned_count += self._transitioned_count(retries.values())
        return transitioned_count

    def _release_worker_holds(
        self,
        decoded: tuple[_DecodedSeedResult, ...],
        *,
        release_time_millis: TimeMillis,
    ) -> None:
        task_ids = tuple(dict.fromkeys(entry.context.task_id for entry in decoded))
        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )
        observed_by_group: dict[
            str,
            dict[WorkerId, list[WorkerScore]],
        ] = defaultdict(lambda: defaultdict(list))
        for entry in decoded:
            descriptor = descriptors.get(entry.context.task_id)
            if descriptor is None:
                continue
            worker_scores = observed_by_group[descriptor.worker_group_id][
                entry.context.worker_id
            ]
            if entry.context.worker_lease_score not in worker_scores:
                worker_scores.append(entry.context.worker_lease_score)

        for worker_group_id, scores_by_worker in observed_by_group.items():
            release_round_count = max(map(len, scores_by_worker.values()))
            for index in range(release_round_count):
                observed_scores = {
                    worker_id: scores[index]
                    for worker_id, scores in scores_by_worker.items()
                    if index < len(scores)
                }
                self.worker_score.release_score_holds(
                    home_bucket_id=worker_group_id,
                    observed_scores=observed_scores,
                    release_time_millis=release_time_millis,
                )

    @staticmethod
    def _transitioned_count(
        results: Iterable[TaskItemScoreTransitionResult],
    ) -> int:
        return sum(
            result.status is TaskItemScoreTransitionStatus.TRANSITIONED
            for result in results
        )

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
