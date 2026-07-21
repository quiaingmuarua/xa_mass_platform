from __future__ import annotations

from typing import Any, ClassVar, Mapping, Sequence

from ..kernel.task_score_band import (
    Score,
    Suffix,
    TaskId,
    TimeMillis,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
)


class RedisTaskScoreBandCore(TaskScoreBandCore):
    """Redis ZSET implementation of the task score-band core.

    This class intentionally assumes a redis-py shaped client and one ZSET key.
    It is meant to make the score-band mechanics executable, not to hide Redis
    behind another framework.
    """

    _CAS_UPDATE_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local observed_score = tonumber(ARGV[2])
local next_score = tonumber(ARGV[3])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
if stored_score ~= observed_score then
  return {"stale", stored_score}
end

redis.call("ZADD", key, next_score, task_id)
return {"transitioned", next_score}
"""

    _CLOSE_POSITIVE_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local terminal_score = tonumber(ARGV[2])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
if stored_score < 0 then
  return {"noop", stored_score}
end

redis.call("ZADD", key, terminal_score, task_id)
return {"transitioned", terminal_score}
"""

    _MINT_FROM_RANGE_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local min_expected_score = tonumber(ARGV[2])
local max_expected_score = tonumber(ARGV[3])
local target_score_base = tonumber(ARGV[4])
local target_suffix = tonumber(ARGV[5])
local suffix_factor = tonumber(ARGV[6])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
if stored_score < min_expected_score or stored_score > max_expected_score then
  return {"stale", stored_score}
end

local stored_suffix = stored_score % suffix_factor
if target_suffix < 0 then
  target_suffix = stored_suffix
end

local next_score = target_score_base + target_suffix
redis.call("ZADD", key, next_score, task_id)
return {"transitioned", tonumber(next_score)}
"""

    def __init__(
        self,
        redis_client: Any,
        *,
        score_key: str = "task:score",
        tag_factor: int = TaskScoreBandCore.DEFAULT_TAG_FACTOR,
        suffix_factor: int = TaskScoreBandCore.SUFFIX_FACTOR,
    ) -> None:
        super().__init__(tag_factor=tag_factor, suffix_factor=suffix_factor)
        self.redis = redis_client
        self.score_key = score_key
        self.tag_factor = tag_factor
        self.suffix_factor = suffix_factor

    def get_score_states(
        self,
        *,
        task_ids: Sequence[TaskId],
    ) -> Mapping[TaskId, TaskScoreState | None]:
        if not task_ids:
            return {}

        with self.redis.pipeline(transaction=False) as pipe:
            for task_id in task_ids:
                pipe.zscore(self.score_key, task_id)
            raw_scores = pipe.execute()

        states: dict[TaskId, TaskScoreState | None] = {}
        for task_id, raw_score in zip(task_ids, raw_scores, strict=True):
            states[task_id] = (
                None if raw_score is None else self._decode_state(task_id, raw_score)
            )
        return states

    def count_running_visible_tasks(self) -> int:
        tag = self.RUNNING_VISIBLE_TAG
        return int(
            self.redis.zcount(
                self.score_key,
                self._score(tag, self.MIN_TIME_SLOT, self.MIN_SUFFIX),
                self._score(tag, self.MAX_TIME_SLOT, self.MAX_SUFFIX),
            )
        )

    def acquire_band_task_candidates(
        self,
        *,
        band: TaskScoreBand,
        before_time_millis: TimeMillis,
        limit: int,
    ) -> Sequence[TaskId]:
        if limit <= 0:
            return []
        if band is TaskScoreBand.TERMINAL:
            raise ValueError("terminal band is not a positive score range")
        if not self._valid_time_millis(before_time_millis):
            return []

        tag = self._tag_from_band(band)
        before_time_slot = self._time_slot_from_millis(before_time_millis)
        max_time_slot = before_time_slot - 1
        if tag is None or max_time_slot < self.MIN_TIME_SLOT:
            return []

        return self._range_task_ids(
            self._score(tag, self.MIN_TIME_SLOT, self.MIN_SUFFIX),
            self._score(tag, max_time_slot, self.MAX_SUFFIX),
            limit,
        )

    def acquire_dispatch_work_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        if limit <= 0:
            return []
        return self.acquire_band_task_candidates(
            band=TaskScoreBand.RUNNING_VISIBLE,
            before_time_millis=self._current_time_millis(),
            limit=limit,
        )

    def initialize_score(
        self,
        *,
        task_id: TaskId,
        suffix: Suffix,
        lease_duration_millis: TimeMillis,
    ) -> TaskScoreTransitionResult:
        if not self.MIN_SUFFIX <= suffix <= self.MAX_SUFFIX:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if lease_duration_millis <= 0:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        current_time_millis = self._current_time_millis()
        current_time_slot = self._time_slot_from_millis(current_time_millis)
        if current_time_slot <= self.MIN_TIME_SLOT:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        lease_until_millis = current_time_millis + lease_duration_millis
        lease_until_slot = self._time_slot_from_millis(lease_until_millis)
        if lease_until_slot > self.MAX_TIME_SLOT:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        lease_score = self._score(
            self.PRE_REVIEW_TAG,
            lease_until_slot,
            suffix,
        )
        added_count = self.redis.zadd(
            self.score_key,
            {task_id: lease_score},
            nx=True,
        )
        if added_count == 1:
            return TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                lease_score,
            )

        stored_score = self.redis.zscore(self.score_key, task_id)
        if stored_score is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.STALE)
        return TaskScoreTransitionResult(
            TaskScoreTransitionStatus.NOOP,
            self._score_to_int(stored_score),
        )

    def rewrite_score(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_time_millis: TimeMillis,
        target_band: TaskScoreBand | None = None,
        target_suffix: Suffix | None = None,
    ) -> TaskScoreTransitionResult:
        if expected_band == TaskScoreBand.TERMINAL:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if target_band == TaskScoreBand.TERMINAL:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if not self._valid_time_millis(target_time_millis):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if target_suffix is not None and not (
            self.MIN_SUFFIX <= target_suffix <= self.MAX_SUFFIX
        ):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        target_time_slot = self._time_slot_from_millis(target_time_millis)
        if target_band is None and target_suffix is None:
            return self.rewrite_same_band_time_millis(
                task_id=task_id,
                expected_band=expected_band,
                target_time_millis=target_time_millis,
            )

        return self._rewrite_positive_score(
            task_id=task_id,
            expected_band=expected_band,
            target_time_slot=target_time_slot,
            target_band=target_band,
            target_suffix=target_suffix,
        )

    def rewrite_same_band_time_millis(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_time_millis: TimeMillis,
    ) -> TaskScoreTransitionResult:
        if expected_band == TaskScoreBand.TERMINAL:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if not self._valid_time_millis(target_time_millis):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        target_time_slot = self._time_slot_from_millis(target_time_millis)
        if target_time_slot <= self.MIN_TIME_SLOT:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        expected_tag = self._tag_from_band(expected_band)
        if expected_tag is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        return self._mint_from_range(
            task_id=task_id,
            min_expected_score=self._score(expected_tag, self.MIN_TIME_SLOT, 0),
            max_expected_score=self._score(
                expected_tag,
                target_time_slot - 1,
                self.MAX_SUFFIX,
            ),
            target_score_base=self._score(expected_tag, target_time_slot, 0),
            target_suffix=None,
        )

    def rewrite_observed_same_band_suffix(
        self,
        *,
        task_id: TaskId,
        observed_score: Score,
        target_time_millis: TimeMillis,
        suffix_delta: int,
    ) -> TaskScoreTransitionResult:
        if suffix_delta == 0:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if not self._valid_time_millis(target_time_millis):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        observed = self._decode_positive(observed_score)
        if observed is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        observed_tag, observed_time_slot, observed_suffix = observed
        target_time_slot = self._time_slot_from_millis(target_time_millis)
        if observed_tag not in {self.RUNNING_VISIBLE_TAG, self.PRE_DISPATCH_VISIBLE_TAG}:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if target_time_slot <= observed_time_slot:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        target_suffix = observed_suffix + suffix_delta
        if target_suffix < self.MIN_SUFFIX or target_suffix > self.MAX_SUFFIX:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        next_score = self._score(observed_tag, target_time_slot, target_suffix)
        return self._cas_update(task_id, observed_score, next_score)

    def close_score(
        self,
        *,
        task_id: TaskId,
        terminal_score: Score,
    ) -> TaskScoreTransitionResult:
        if terminal_score > self.TERMINAL_SCORE_MAX:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._close_positive_score(task_id, terminal_score)

    def release_observed_score_hold(
        self,
        *,
        task_id: TaskId,
        observed_hold_score: Score,
    ) -> TaskScoreTransitionResult:
        observed = self._decode_positive(observed_hold_score)
        if observed is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        tag, observed_time_slot, suffix = observed
        if tag not in {
            self.RUNNING_VISIBLE_TAG,
            self.PRE_DISPATCH_VISIBLE_TAG,
            self.PRE_REVIEW_TAG,
        }:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        release_time_millis = self._current_time_millis()
        release_time_slot = self._time_slot_from_millis(release_time_millis)
        if release_time_slot > observed_time_slot:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        release_score = self._score(tag, release_time_slot, suffix)
        return self._cas_update(task_id, observed_hold_score, release_score)

    def _cas_update(
        self,
        task_id: TaskId,
        observed_score: Score,
        next_score: Score,
    ) -> TaskScoreTransitionResult:
        return self._script_result(
            self.redis.eval(
                self._CAS_UPDATE_SCRIPT,
                1,
                self.score_key,
                task_id,
                observed_score,
                next_score,
            )
        )

    def _close_positive_score(
        self,
        task_id: TaskId,
        terminal_score: Score,
    ) -> TaskScoreTransitionResult:
        return self._script_result(
            self.redis.eval(
                self._CLOSE_POSITIVE_SCRIPT,
                1,
                self.score_key,
                task_id,
                terminal_score,
            )
        )

    def _rewrite_positive_score(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_time_slot: int,
        target_band: TaskScoreBand | None,
        target_suffix: Suffix | None,
    ) -> TaskScoreTransitionResult:
        expected_tag = self._tag_from_band(expected_band)
        target_tag = self._tag_from_band(target_band or expected_band)
        if expected_tag is None or target_tag is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if target_tag > expected_tag:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if target_time_slot <= self.MIN_TIME_SLOT:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._mint_from_range(
            task_id=task_id,
            min_expected_score=self._score(expected_tag, self.MIN_TIME_SLOT, 0),
            max_expected_score=self._score(
                expected_tag,
                target_time_slot - 1,
                self.MAX_SUFFIX,
            ),
            target_score_base=self._score(target_tag, target_time_slot, 0),
            target_suffix=target_suffix,
        )

    def _mint_from_range(
        self,
        *,
        task_id: TaskId,
        min_expected_score: Score,
        max_expected_score: Score,
        target_score_base: Score,
        target_suffix: Suffix | None,
    ) -> TaskScoreTransitionResult:
        return self._script_result(
            self.redis.eval(
                self._MINT_FROM_RANGE_SCRIPT,
                1,
                self.score_key,
                task_id,
                min_expected_score,
                max_expected_score,
                target_score_base,
                -1 if target_suffix is None else target_suffix,
                self.suffix_factor,
            )
        )

    def _script_result(self, raw: Sequence[Any]) -> TaskScoreTransitionResult:
        if not raw:
            raise ValueError("empty redis script result")

        status_value = raw[0]
        if isinstance(status_value, bytes):
            status_value = status_value.decode("utf-8")

        score = None
        if len(raw) > 1 and raw[1] is not None:
            score = self._score_to_int(raw[1])
        return TaskScoreTransitionResult(
            TaskScoreTransitionStatus(str(status_value)),
            score,
        )

    def _range_task_ids(
        self,
        min_score: Score,
        max_score: Score,
        limit: int,
    ) -> list[TaskId]:
        raw_ids = self.redis.zrangebyscore(
            self.score_key,
            min_score,
            max_score,
            start=0,
            num=limit,
        )
        return [self._decode_task_id(raw_id) for raw_id in raw_ids]

    def _decode_state(
        self,
        task_id: TaskId,
        raw_score: Any,
    ) -> TaskScoreState:
        score = self._score_to_int(raw_score)
        if score < 0:
            return TaskScoreState(task_id, score, TaskScoreBand.TERMINAL, None, None)

        decoded = self._decode_positive(score)
        if decoded is None:
            raise ValueError(f"invalid positive task score: task_id={task_id!r}")

        tag, time_slot, suffix = decoded
        return TaskScoreState(
            task_id,
            score,
            self._band_from_tag(tag),
            self._time_millis_from_slot(time_slot),
            suffix,
        )

    def _decode_positive(
        self,
        score: Score,
    ) -> tuple[int, int, int] | None:
        if score <= 0:
            return None

        tag = score // self.tag_factor
        rest = score % self.tag_factor
        time_slot = rest // self.suffix_factor
        suffix = rest % self.suffix_factor

        if tag not in self.VALID_POSITIVE_TAGS:
            return None
        if not self.MIN_TIME_SLOT <= time_slot <= self.MAX_TIME_SLOT:
            return None
        if not self.MIN_SUFFIX <= suffix <= self.MAX_SUFFIX:
            return None
        return tag, time_slot, suffix

    def _score(
        self,
        tag: int,
        time_slot: int,
        suffix: int,
    ) -> Score:
        return tag * self.tag_factor + time_slot * self.suffix_factor + suffix

    def _band_from_tag(self, tag: int) -> TaskScoreBand:
        if tag == self.RUNNING_VISIBLE_TAG:
            return TaskScoreBand.RUNNING_VISIBLE
        if tag == self.PRE_DISPATCH_VISIBLE_TAG:
            return TaskScoreBand.PRE_DISPATCH_VISIBLE
        if tag == self.PRE_REVIEW_TAG:
            return TaskScoreBand.PRE_REVIEW
        raise ValueError(f"unknown task score tag: {tag}")

    def _tag_from_band(self, band: TaskScoreBand) -> int | None:
        if band == TaskScoreBand.RUNNING_VISIBLE:
            return self.RUNNING_VISIBLE_TAG
        if band == TaskScoreBand.PRE_DISPATCH_VISIBLE:
            return self.PRE_DISPATCH_VISIBLE_TAG
        if band == TaskScoreBand.PRE_REVIEW:
            return self.PRE_REVIEW_TAG
        return None

    def _current_time_slot(self) -> int:
        return self._time_slot_from_millis(self._current_time_millis())

    def _current_time_millis(self) -> TimeMillis:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    def _time_slot_from_millis(self, time_millis: TimeMillis) -> int:
        return int(time_millis) // self.SLOT_MILLIS

    def _time_millis_from_slot(self, time_slot: int) -> TimeMillis:
        return int(time_slot) * self.SLOT_MILLIS

    def _valid_time_millis(self, time_millis: int) -> bool:
        return self.MIN_TIME_MILLIS <= time_millis <= self.MAX_TIME_MILLIS

    def _score_to_int(self, raw_score: Any) -> Score:
        score = int(raw_score)
        if float(raw_score) != float(score):
            raise ValueError(f"non-integer redis zset score: {raw_score!r}")
        return score

    def _decode_task_id(self, raw_task_id: Any) -> TaskId:
        if isinstance(raw_task_id, bytes):
            return raw_task_id.decode("utf-8")
        return str(raw_task_id)
