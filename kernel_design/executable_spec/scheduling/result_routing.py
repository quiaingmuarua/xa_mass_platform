from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterator
from dataclasses import dataclass
from time import time_ns

from ..kernel.result_context import ResultContext, decode_result_context
from ..kernel.seed_result_runtime import (
    SeedResult,
    SeedResultOutcomeClass,
    SeedResultRuntime,
    classify_seed_result_outcome_code,
)
from ..kernel.task_item_score_band import TaskItemScoreBand, TaskItemScoreBandCore
from ..kernel.task_runtime import MessageId, TaskResourceCatalog, TaskRuntime
from ..kernel.task_score_band import TaskId, TimeMillis
from ..kernel.worker_score import Score as WorkerScore
from ..kernel.worker_score import WorkerId, WorkerScoreCore


@dataclass(frozen=True, slots=True)
class ResultRoutingConfig:
    per_outcome_batch_limit: int

    def __post_init__(self) -> None:
        if self.per_outcome_batch_limit <= 0:
            raise ValueError("per-outcome result-routing batch limit must be positive")


@dataclass(frozen=True, slots=True)
class _DecodedSeedResult:
    result: SeedResult
    context: ResultContext


class ResultRoutingPacer:
    """Route outcome-class queues into TaskItem and Worker owners."""

    def __init__(
        self,
        seed_result_runtime: SeedResultRuntime,
        item_score: TaskItemScoreBandCore,
        worker_score: WorkerScoreCore,
        task_catalog: TaskResourceCatalog,
        task_runtime: TaskRuntime,
    ) -> None:
        self.seed_result_runtime = seed_result_runtime
        self.item_score = item_score
        self.worker_score = worker_score
        self.task_catalog = task_catalog
        self.task_runtime = task_runtime

    def route_seed_results(self, *, config: ResultRoutingConfig) -> int:
        result_time_millis = self._current_time_millis()
        handlers = (
            (
                SeedResultOutcomeClass.SUCCESS,
                self._handle_success_results,
            ),
            (
                SeedResultOutcomeClass.WORKER_FAILURE,
                self._handle_worker_failure_results,
            ),
            (
                SeedResultOutcomeClass.ADAPTER_REJECTION,
                self._handle_adapter_rejection_results,
            ),
        )
        routed_count = 0
        for outcome_class, handler in handlers:
            decoded = self._consume_decoded(
                outcome_class=outcome_class,
                limit=config.per_outcome_batch_limit,
            )
            if not decoded:
                continue
            handler(decoded, result_time_millis)
            routed_count += len(decoded)
        return routed_count

    def _handle_success_results(
        self,
        decoded: tuple[_DecodedSeedResult, ...],
        result_time_millis: TimeMillis,
    ) -> None:
        results_by_task: dict[TaskId, dict[MessageId, str]] = defaultdict(dict)
        for entry in decoded:
            payload = entry.result.opaque_result_payload
            if payload is not None:
                results_by_task[entry.context.task_id][
                    entry.context.message_id
                ] = payload

        for task_id, results in results_by_task.items():
            self.task_runtime.store_task_item_success_results(
                task_id=task_id,
                results=results,
            )
            self.item_score.promote_item_outcomes(
                task_id=task_id,
                message_ids=tuple(results),
                target_band=TaskItemScoreBand.FINAL_SUCCESS,
                target_time_millis=result_time_millis,
            )

        self._release_worker_leases(
            decoded,
            release_time_millis=result_time_millis,
        )

    def _handle_worker_failure_results(
        self,
        decoded: tuple[_DecodedSeedResult, ...],
        release_time_millis: TimeMillis,
    ) -> None:
        self._release_worker_leases(
            decoded,
            release_time_millis=release_time_millis,
        )

    def _handle_adapter_rejection_results(
        self,
        decoded: tuple[_DecodedSeedResult, ...],
        _result_time_millis: TimeMillis,
    ) -> None:
        self._demote_worker_leases(decoded)

    def _consume_decoded(
        self,
        *,
        outcome_class: SeedResultOutcomeClass,
        limit: int,
    ) -> tuple[_DecodedSeedResult, ...]:
        results = self.seed_result_runtime.consume_seed_results(
            outcome_class=outcome_class,
            limit=limit,
        )
        decoded: list[_DecodedSeedResult] = []
        for result in results:
            context = decode_result_context(result.opaque_result_context)
            if (
                context is not None
                and classify_seed_result_outcome_code(result.outcome_code)
                is outcome_class
            ):
                decoded.append(_DecodedSeedResult(result, context))
        return tuple(decoded)

    def _release_worker_leases(
        self,
        decoded: tuple[_DecodedSeedResult, ...],
        *,
        release_time_millis: TimeMillis,
    ) -> None:
        for worker_group_id, scores_by_worker in self._worker_scores(decoded).items():
            for observed_scores in self._score_rounds(scores_by_worker):
                self.worker_score.release_score_holds(
                    home_bucket_id=worker_group_id,
                    observed_scores=observed_scores,
                    release_time_millis=release_time_millis,
                )

    def _demote_worker_leases(
        self,
        decoded: tuple[_DecodedSeedResult, ...],
    ) -> None:
        for worker_group_id, scores_by_worker in self._worker_scores(decoded).items():
            for observed_scores in self._score_rounds(scores_by_worker):
                self.worker_score.demote_observed_worker_leases_to_recovery(
                    home_bucket_id=worker_group_id,
                    observed_scores=observed_scores,
                )

    def _worker_scores(
        self,
        decoded: tuple[_DecodedSeedResult, ...],
    ) -> dict[str, dict[WorkerId, list[WorkerScore]]]:
        task_ids = tuple(dict.fromkeys(entry.context.task_id for entry in decoded))
        descriptors = self.task_catalog.load_task_allocation_descriptors(
            task_ids=task_ids,
        )
        scores: dict[str, dict[WorkerId, list[WorkerScore]]] = defaultdict(
            lambda: defaultdict(list)
        )
        for entry in decoded:
            descriptor = descriptors.get(entry.context.task_id)
            if descriptor is not None:
                scores[descriptor.worker_group_id][entry.context.worker_id].append(
                    entry.context.worker_lease_score
                )
        return scores

    @staticmethod
    def _score_rounds(
        scores_by_worker: dict[WorkerId, list[WorkerScore]],
    ) -> Iterator[dict[WorkerId, WorkerScore]]:
        round_count = max(map(len, scores_by_worker.values()))
        for index in range(round_count):
            yield {
                worker_id: scores[index]
                for worker_id, scores in scores_by_worker.items()
                if index < len(scores)
            }

    @staticmethod
    def _current_time_millis() -> TimeMillis:
        return time_ns() // 1_000_000
