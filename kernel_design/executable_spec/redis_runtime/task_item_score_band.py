from __future__ import annotations

from typing import Any, ClassVar, Mapping, Sequence

from ..kernel.task_item_score_band import (
    RemainingBudget,
    TaskItemScoreBand,
    TaskItemScoreBandCore,
    TaskItemScoreObservation,
    TaskItemScoreState,
    TaskItemScoreTransitionResult,
    TaskItemScoreTransitionStatus,
)
from ..kernel.task_runtime import MessageId
from ..kernel.task_score_band import Score, TaskId, TimeMillis


class RedisTaskItemScoreBandCore(TaskItemScoreBandCore):
    """Task-scoped Redis ZSET implementation of the TaskItem score axis."""

    _CAS_UPDATE_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local message_id = ARGV[1]
local observed_score = tonumber(ARGV[2])
local next_score = tonumber(ARGV[3])

local stored = redis.call("ZSCORE", key, message_id)
if not stored then
  return {"not_found"}
end

if tonumber(stored) ~= observed_score then
  return {"stale"}
end

redis.call("ZADD", key, next_score, message_id)
return {"transitioned", next_score}
"""

    _PROMOTE_CROSS_BAND_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local message_id = ARGV[1]
local target_score = tonumber(ARGV[2])
local max_same_band_score_delta = tonumber(ARGV[3])

local stored = redis.call("ZSCORE", key, message_id)
if not stored then
  return {"not_found"}
end

local stored_score = tonumber(stored)
if target_score - stored_score <= max_same_band_score_delta then
  return {"noop", stored_score}
end

redis.call("ZADD", key, target_score, message_id)
return {"transitioned", target_score}
"""

    def __init__(
        self,
        redis_client: Any,
        *,
        prefix: str = "default",
    ) -> None:
        self.redis = redis_client
        self.prefix = prefix

    def initialize_item_scores(
        self,
        *,
        task_id: TaskId,
        initial_due_millis_by_message_id: Mapping[MessageId, TimeMillis],
        max_retry_times: int,
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        if not initial_due_millis_by_message_id:
            return {}
        if not task_id or not self._valid_max_retry_times(max_retry_times):
            return self._uniform_results(
                initial_due_millis_by_message_id,
                TaskItemScoreTransitionStatus.INVALID,
            )

        remaining_budget = 1 + max_retry_times
        immediate: dict[MessageId, TaskItemScoreTransitionResult] = {}
        pending_scores: dict[MessageId, Score] = {}
        for message_id, initial_due_millis in (
            initial_due_millis_by_message_id.items()
        ):
            if not message_id or not self._valid_time_millis(initial_due_millis):
                immediate[message_id] = TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.INVALID
                )
                continue
            pending_scores[message_id] = self._score(
                self.ACTIVE_TAG,
                self._time_slot_from_millis(initial_due_millis),
                remaining_budget,
            )

        persisted: dict[MessageId, TaskItemScoreTransitionResult] = {}
        if pending_scores:
            key = self._score_key(task_id)
            with self.redis.pipeline(transaction=False) as pipe:
                for message_id, score in pending_scores.items():
                    pipe.zadd(key, {message_id: score}, nx=True)
                raw_results = pipe.execute()
            for (message_id, score), raw_result in zip(
                pending_scores.items(),
                raw_results,
                strict=True,
            ):
                persisted[message_id] = TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.TRANSITIONED
                    if int(raw_result) == 1
                    else TaskItemScoreTransitionStatus.NOOP,
                    score if int(raw_result) == 1 else None,
                )

        return self._merge_results(
            initial_due_millis_by_message_id,
            immediate,
            persisted,
        )

    def acquire_item_score_candidates(
        self,
        *,
        task_id: TaskId,
        limit: int,
    ) -> Mapping[MessageId, TaskItemScoreObservation]:
        if not task_id or limit <= 0:
            return {}

        now_millis = self._current_time_millis()
        if not self._valid_time_millis(now_millis):
            return {}
        max_score = self._score(
            self.ACTIVE_TAG,
            self._time_slot_from_millis(now_millis),
            self.MAX_REMAINING_BUDGET,
        )
        min_score = self._score(
            self.ACTIVE_TAG,
            self.MIN_TIME_SLOT,
            self.MIN_REMAINING_BUDGET,
        )
        raw_rows = self.redis.zrevrangebyscore(
            self._score_key(task_id),
            max_score,
            min_score,
            start=0,
            num=limit,
            withscores=True,
        )

        observations: dict[MessageId, TaskItemScoreObservation] = {}
        for raw_message_id, raw_score in raw_rows:
            score = self._score_to_int(raw_score)
            decoded = self._decode_score(score)
            if decoded is None or decoded[0] != self.ACTIVE_TAG:
                continue
            observations[self._decode_message_id(raw_message_id)] = (
                score,
                decoded[2],
            )
        return observations

    def has_due_active_items(
        self,
        *,
        task_ids: Sequence[TaskId],
    ) -> Mapping[TaskId, bool]:
        if not task_ids:
            return {}

        now_millis = self._current_time_millis()
        if not self._valid_time_millis(now_millis):
            return {task_id: False for task_id in task_ids}
        min_score = self._score(
            self.ACTIVE_TAG,
            self.MIN_TIME_SLOT,
            self.MIN_REMAINING_BUDGET,
        )
        max_score = self._score(
            self.ACTIVE_TAG,
            self._time_slot_from_millis(now_millis),
            self.MAX_REMAINING_BUDGET,
        )
        with self.redis.pipeline(transaction=False) as pipe:
            for task_id in task_ids:
                pipe.zrevrangebyscore(
                    self._score_key(task_id),
                    max_score,
                    min_score,
                    start=0,
                    num=1,
                )
            rows_by_task = pipe.execute()
        return {
            task_id: bool(rows)
            for task_id, rows in zip(task_ids, rows_by_task, strict=True)
        }

    def rewrite_observed_item_scores(
        self,
        *,
        task_id: TaskId,
        observed_scores: Mapping[MessageId, Score],
        target_time_millis: TimeMillis,
        remaining_budget_delta: int,
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        if not observed_scores:
            return {}
        if (
            not task_id
            or not self._valid_time_millis(target_time_millis)
            or remaining_budget_delta not in {-1, 0}
        ):
            return self._uniform_results(
                observed_scores,
                TaskItemScoreTransitionStatus.INVALID,
            )

        target_time_slot = self._time_slot_from_millis(target_time_millis)
        immediate: dict[MessageId, TaskItemScoreTransitionResult] = {}
        pending: dict[MessageId, tuple[Score, Score]] = {}
        for message_id, observed_score in observed_scores.items():
            decoded = self._decode_score(observed_score)
            if not message_id or decoded is None or decoded[0] != self.ACTIVE_TAG:
                immediate[message_id] = TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.INVALID
                )
                continue

            _, observed_time_slot, observed_budget = decoded
            target_budget = observed_budget + remaining_budget_delta
            if not self.MIN_REMAINING_BUDGET <= target_budget <= observed_budget:
                immediate[message_id] = TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.INVALID
                )
                continue
            if target_time_slot <= observed_time_slot:
                immediate[message_id] = TaskItemScoreTransitionResult(
                    TaskItemScoreTransitionStatus.INVALID
                )
                continue

            pending[message_id] = (
                observed_score,
                self._score(self.ACTIVE_TAG, target_time_slot, target_budget),
            )

        persisted = self._pipeline_cas_updates(task_id, pending)
        return self._merge_results(observed_scores, immediate, persisted)

    def promote_item_outcomes(
        self,
        *,
        task_id: TaskId,
        message_ids: Sequence[MessageId],
        target_band: TaskItemScoreBand,
        target_time_millis: TimeMillis,
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        ordered_message_ids = tuple(dict.fromkeys(message_ids))
        if not ordered_message_ids:
            return {}
        target_tag = self._tag_from_band(target_band)
        if not task_id or target_tag is None or not self._valid_time_millis(
            target_time_millis
        ):
            return self._uniform_results(
                ordered_message_ids,
                TaskItemScoreTransitionStatus.INVALID,
            )

        key = self._score_key(task_id)
        target_score = self._score(
            target_tag,
            self._time_slot_from_millis(target_time_millis),
            self.FINAL_SUFFIX,
        )
        with self.redis.pipeline(transaction=False) as pipe:
            for message_id in ordered_message_ids:
                pipe.eval(
                    self._PROMOTE_CROSS_BAND_SCRIPT,
                    1,
                    key,
                    message_id,
                    target_score,
                    self.MAX_SAME_BAND_SCORE_DELTA,
                )
            raw_results = pipe.execute()
        return {
            message_id: self._script_result(raw_result)
            for message_id, raw_result in zip(
                ordered_message_ids,
                raw_results,
                strict=True,
            )
        }

    def get_item_score_states(
        self,
        *,
        task_id: TaskId,
        message_ids: Sequence[MessageId],
    ) -> Mapping[MessageId, TaskItemScoreState | None]:
        if not message_ids:
            return {}

        key = self._score_key(task_id)
        with self.redis.pipeline(transaction=False) as pipe:
            for message_id in message_ids:
                pipe.zscore(key, message_id)
            raw_scores = pipe.execute()

        states: dict[MessageId, TaskItemScoreState | None] = {}
        for message_id, raw_score in zip(message_ids, raw_scores, strict=True):
            states[message_id] = (
                None if raw_score is None else self._decode_state(message_id, raw_score)
            )
        return states

    def _pipeline_cas_updates(
        self,
        task_id: TaskId,
        updates: Mapping[MessageId, tuple[Score, Score]],
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        if not updates:
            return {}
        key = self._score_key(task_id)
        with self.redis.pipeline(transaction=False) as pipe:
            for message_id, (observed_score, next_score) in updates.items():
                pipe.eval(
                    self._CAS_UPDATE_SCRIPT,
                    1,
                    key,
                    message_id,
                    observed_score,
                    next_score,
                )
            raw_results = pipe.execute()
        return {
            message_id: self._script_result(raw_result)
            for message_id, raw_result in zip(updates, raw_results, strict=True)
        }

    @staticmethod
    def _uniform_results(
        message_ids: Sequence[MessageId] | Mapping[MessageId, object],
        status: TaskItemScoreTransitionStatus,
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        return {
            message_id: TaskItemScoreTransitionResult(status)
            for message_id in message_ids
        }

    @staticmethod
    def _merge_results(
        message_ids: Sequence[MessageId] | Mapping[MessageId, object],
        immediate: Mapping[MessageId, TaskItemScoreTransitionResult],
        persisted: Mapping[MessageId, TaskItemScoreTransitionResult],
    ) -> Mapping[MessageId, TaskItemScoreTransitionResult]:
        return {
            message_id: immediate.get(message_id) or persisted[message_id]
            for message_id in message_ids
        }

    def _decode_state(self, message_id: MessageId, raw_score: Any) -> TaskItemScoreState:
        score = self._score_to_int(raw_score)
        decoded = self._decode_score(score)
        if decoded is None:
            raise ValueError(f"invalid TaskItem score: message_id={message_id!r}")
        tag, time_slot, suffix = decoded
        band = self._band_from_tag(tag)
        return TaskItemScoreState(
            score=score,
            band=band,
            time_millis=self._time_millis_from_slot(time_slot),
            remaining_budget=suffix if band is TaskItemScoreBand.ACTIVE else None,
        )

    def _decode_score(self, score: Score) -> tuple[int, int, int] | None:
        if (
            not isinstance(score, int)
            or isinstance(score, bool)
            or score <= 0
        ):
            return None
        tag = score // self.TAG_FACTOR
        remainder = score % self.TAG_FACTOR
        time_slot = remainder // self.SUFFIX_FACTOR
        suffix = remainder % self.SUFFIX_FACTOR
        if tag not in self.VALID_TAGS:
            return None
        if not self.MIN_TIME_SLOT <= time_slot <= self.MAX_TIME_SLOT:
            return None
        if tag == self.ACTIVE_TAG:
            if not self.MIN_REMAINING_BUDGET <= suffix <= self.MAX_REMAINING_BUDGET:
                return None
        elif suffix != self.FINAL_SUFFIX:
            return None
        return tag, time_slot, suffix

    def _score(self, tag: int, time_slot: int, suffix: int) -> Score:
        return tag * self.TAG_FACTOR + time_slot * self.SUFFIX_FACTOR + suffix

    def _score_key(self, task_id: TaskId) -> str:
        return f"tr:{self.prefix}:task:{task_id}:item-score"

    def _tag_from_band(self, band: TaskItemScoreBand) -> int | None:
        if band is TaskItemScoreBand.ACTIVE:
            return self.ACTIVE_TAG
        if band is TaskItemScoreBand.FINAL_FAILED:
            return self.FINAL_FAILED_TAG
        if band is TaskItemScoreBand.FINAL_SUCCESS:
            return self.FINAL_SUCCESS_TAG
        return None

    def _band_from_tag(self, tag: int) -> TaskItemScoreBand:
        if tag == self.ACTIVE_TAG:
            return TaskItemScoreBand.ACTIVE
        if tag == self.FINAL_FAILED_TAG:
            return TaskItemScoreBand.FINAL_FAILED
        if tag == self.FINAL_SUCCESS_TAG:
            return TaskItemScoreBand.FINAL_SUCCESS
        raise ValueError(f"unknown TaskItem score tag: {tag}")

    def _script_result(self, raw: Sequence[Any]) -> TaskItemScoreTransitionResult:
        if not raw:
            raise ValueError("empty Redis TaskItem score script result")
        status_value = raw[0]
        if isinstance(status_value, bytes):
            status_value = status_value.decode("utf-8")
        score = None
        if len(raw) > 1 and raw[1] is not None:
            score = self._score_to_int(raw[1])
        return TaskItemScoreTransitionResult(
            TaskItemScoreTransitionStatus(str(status_value)),
            score,
        )

    def _current_time_millis(self) -> TimeMillis:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    def _time_slot_from_millis(self, time_millis: TimeMillis) -> int:
        return int(time_millis) // self.SLOT_MILLIS

    def _time_millis_from_slot(self, time_slot: int) -> TimeMillis:
        return int(time_slot) * self.SLOT_MILLIS

    def _valid_time_millis(self, time_millis: object) -> bool:
        return (
            isinstance(time_millis, int)
            and not isinstance(time_millis, bool)
            and self.MIN_TIME_MILLIS <= time_millis <= self.MAX_TIME_MILLIS
        )

    def _valid_max_retry_times(self, max_retry_times: object) -> bool:
        return (
            isinstance(max_retry_times, int)
            and not isinstance(max_retry_times, bool)
            and 0 <= max_retry_times < self.MAX_REMAINING_BUDGET
        )

    def _score_to_int(self, raw_score: Any) -> Score:
        score = int(raw_score)
        if float(raw_score) != float(score):
            raise ValueError(f"non-integer Redis TaskItem score: {raw_score!r}")
        return score

    @staticmethod
    def _decode_message_id(raw_message_id: Any) -> MessageId:
        if isinstance(raw_message_id, bytes):
            return raw_message_id.decode("utf-8")
        return str(raw_message_id)
