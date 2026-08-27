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
from .keyspace import RedisKeyspace


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

    _START_PRE_REVIEW_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local observed_score = tonumber(ARGV[2])
local pre_review_min = tonumber(ARGV[3])
local pre_review_max = tonumber(ARGV[4])
local initial_score = tonumber(ARGV[5])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end
local stored_score = tonumber(stored)
if stored_score ~= observed_score then
  return {"stale", stored_score}
end
if stored_score < pre_review_min or stored_score > pre_review_max then
  return {"invalid", stored_score}
end
redis.call("ZADD", key, initial_score, task_id)
return {"transitioned", initial_score}
"""

    _PROMOTE_INITIAL_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local observed_score = tonumber(ARGV[2])
local normal_min_score = tonumber(ARGV[3])
local running_min_score = tonumber(ARGV[4])
local idle_park_score = tonumber(ARGV[5])
local slot_millis = tonumber(ARGV[6])
local suffix_factor = tonumber(ARGV[7])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end
local stored_score = tonumber(stored)
if stored_score ~= observed_score then
  return {"stale", stored_score}
end
local redis_time = redis.call("TIME")
local now_millis = tonumber(redis_time[1]) * 1000
    + math.floor(tonumber(redis_time[2]) / 1000)
local now_time_slot = math.floor(now_millis / slot_millis)
local next_score = running_min_score + now_time_slot * suffix_factor
if next_score < normal_min_score then
  next_score = normal_min_score
end
if next_score >= idle_park_score then
  return {"invalid", stored_score}
end
redis.call("ZADD", key, next_score, task_id)
return {"transitioned", next_score}
"""

    _TRY_RELEASE_IDLE_PARK_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local idle_park_score = tonumber(ARGV[2])
local running_pause_max_score = tonumber(ARGV[3])
local slot_millis = tonumber(ARGV[4])
local suffix_factor = tonumber(ARGV[5])
local running_min = tonumber(ARGV[6])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
if stored_score == idle_park_score then
  local redis_time = redis.call("TIME")
  local now_millis = tonumber(redis_time[1]) * 1000
      + math.floor(tonumber(redis_time[2]) / 1000)
  local now_time_slot = math.floor(now_millis / slot_millis)
  local next_score = running_min + now_time_slot * suffix_factor
  if next_score <= running_min or next_score >= idle_park_score then
    return {"invalid", stored_score}
  end

  redis.call("ZADD", key, next_score, task_id)
  return {"transitioned", next_score}
end

if (stored_score > 0 and stored_score < idle_park_score)
    or stored_score > running_pause_max_score then
  return {"noop", stored_score}
end
return {"invalid", stored_score}
"""

    _IDLE_PARK_TIME_SLOT: ClassVar[int] = TaskScoreBandCore.MAX_TIME_SLOT - 1

    def __init__(
        self,
        redis_client: Any,
        *,
        keyspace: RedisKeyspace,
        tag_factor: int = TaskScoreBandCore.DEFAULT_TAG_FACTOR,
        suffix_factor: int = TaskScoreBandCore.SUFFIX_FACTOR,
    ) -> None:
        super().__init__(tag_factor=tag_factor, suffix_factor=suffix_factor)
        if not isinstance(keyspace, RedisKeyspace):
            raise TypeError("keyspace must be RedisKeyspace")
        self.redis = redis_client
        self.score_key = f"{keyspace.base}:task:score"
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

    def preview_score_states(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskScoreState]:
        if (
            isinstance(limit, bool)
            or not isinstance(limit, int)
            or limit < 1
            or limit > self.MAX_TASK_SCORE_PREVIEW_LIMIT
        ):
            raise ValueError(
                "limit must be between 1 and "
                f"{self.MAX_TASK_SCORE_PREVIEW_LIMIT}"
            )

        raw_rows = self.redis.zrevrange(
            self.score_key,
            0,
            limit - 1,
            withscores=True,
        )
        return tuple(
            self._decode_state(
                self._decode_task_id(raw_task_id),
                raw_score,
            )
            for raw_task_id, raw_score in raw_rows
        )

    def count_running_tasks(self) -> int:
        return int(
            self.redis.zcount(
                self.score_key,
                self._score(
                    self.RUNNING_VISIBLE_TAG,
                    self.MIN_TIME_SLOT,
                    self.MIN_SUFFIX,
                ),
                self._score(
                    self.RUNNING_VISIBLE_TAG,
                    self.MAX_TIME_SLOT,
                    self.MAX_SUFFIX,
                ),
            )
        )

    def acquire_dispatch_work_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        if limit <= 0:
            return []
        current_time_slot = self._time_slot_from_millis(
            self._current_time_millis()
        )
        minimum_time_slot = self.NORMAL_TIME_MIN_MILLIS // self.SLOT_MILLIS
        maximum_time_slot = current_time_slot - 1
        if maximum_time_slot < minimum_time_slot:
            return []
        return self._range_task_ids(
            self._score(
                self.RUNNING_VISIBLE_TAG,
                minimum_time_slot,
                self.MIN_SUFFIX,
            ),
            self._score(
                self.RUNNING_VISIBLE_TAG,
                maximum_time_slot,
                self.MAX_SUFFIX,
            ),
            limit,
        )

    def acquire_initial_running_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        if limit <= 0:
            return []
        raw_rows = self.redis.zrevrangebyscore(
            self.score_key,
            self._score(
                self.RUNNING_VISIBLE_TAG,
                self.INITIAL_TIME_CEILING_MILLIS // self.SLOT_MILLIS,
                self.MAX_SUFFIX,
            ),
            self._score(
                self.RUNNING_VISIBLE_TAG,
                self.MIN_TIME_SLOT,
                self.MIN_SUFFIX,
            ),
            start=0,
            num=limit,
            withscores=True,
        )
        task_ids: list[TaskId] = []
        for raw_task_id, raw_score in raw_rows:
            decoded = self._decode_positive(self._score_to_int(raw_score))
            if (
                decoded is not None
                and decoded[0] == self.RUNNING_VISIBLE_TAG
                and decoded[2] == self.MIN_SUFFIX
                and decoded[1]
                <= self.INITIAL_TIME_CEILING_MILLIS // self.SLOT_MILLIS
            ):
                task_ids.append(self._decode_task_id(raw_task_id))
        return task_ids

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
        if (
            lease_until_slot > self.MAX_TIME_SLOT
            or lease_until_slot == self._IDLE_PARK_TIME_SLOT
        ):
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

    def start_observed_pre_review_task(
        self,
        *,
        task_id: TaskId,
        observed_pre_review_score: Score,
        priority: int,
    ) -> TaskScoreTransitionResult:
        observed = self._decode_positive(observed_pre_review_score)
        if (
            observed is None
            or observed[0] != self.PRE_REVIEW_TAG
            or isinstance(priority, bool)
            or not isinstance(priority, int)
            or not self.MIN_SUFFIX <= priority <= self.MAX_SUFFIX
        ):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        initial_time_millis = max(
            self.MIN_TIME_MILLIS,
            self.INITIAL_TIME_CEILING_MILLIS
            - priority * self.INITIAL_PRIORITY_STEP_MILLIS,
        )
        return self._script_result(
            self.redis.eval(
                self._START_PRE_REVIEW_SCRIPT,
                1,
                self.score_key,
                task_id,
                observed_pre_review_score,
                self._score(
                    self.PRE_REVIEW_TAG,
                    self.MIN_TIME_SLOT,
                    self.MIN_SUFFIX,
                ),
                self._score(
                    self.PRE_REVIEW_TAG,
                    self.MAX_TIME_SLOT,
                    self.MAX_SUFFIX,
                ),
                self._score(
                    self.RUNNING_VISIBLE_TAG,
                    initial_time_millis // self.SLOT_MILLIS,
                    self.MIN_SUFFIX,
                ),
            )
        )

    def promote_observed_initial_task(
        self,
        *,
        task_id: TaskId,
        observed_initial_score: Score,
    ) -> TaskScoreTransitionResult:
        observed = self._decode_positive(observed_initial_score)
        if (
            observed is None
            or observed[0] != self.RUNNING_VISIBLE_TAG
            or observed[2] != self.MIN_SUFFIX
            or observed[1]
            > self.INITIAL_TIME_CEILING_MILLIS // self.SLOT_MILLIS
        ):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        return self._script_result(
            self.redis.eval(
                self._PROMOTE_INITIAL_SCRIPT,
                1,
                self.score_key,
                task_id,
                observed_initial_score,
                self._score(
                    self.RUNNING_VISIBLE_TAG,
                    self.NORMAL_TIME_MIN_MILLIS // self.SLOT_MILLIS,
                    self.MIN_SUFFIX,
                ),
                self._score(
                    self.RUNNING_VISIBLE_TAG,
                    self.MIN_TIME_SLOT,
                    self.MIN_SUFFIX,
                ),
                self._idle_park_score(),
                self.SLOT_MILLIS,
                self.suffix_factor,
            )
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
        if not self._valid_public_target_time_millis(target_time_millis):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        target_time_slot = self._time_slot_from_millis(target_time_millis)
        if target_time_slot <= self.MIN_TIME_SLOT:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        expected_tag = self._tag_from_band(expected_band)
        if expected_tag is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        minimum_expected_time_slot = (
            self.NORMAL_TIME_MIN_MILLIS // self.SLOT_MILLIS
            if expected_band == TaskScoreBand.RUNNING_VISIBLE
            else self.MIN_TIME_SLOT
        )
        if target_time_slot < minimum_expected_time_slot:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        return self._mint_from_range(
            task_id=task_id,
            min_expected_score=self._score(
                expected_tag,
                minimum_expected_time_slot,
                0,
            ),
            max_expected_score=self._score(
                expected_tag,
                target_time_slot - 1,
                self.MAX_SUFFIX,
            ),
            target_score_base=self._score(expected_tag, target_time_slot, 0),
            target_suffix=None,
        )

    def park_observed_idle_task(
        self,
        *,
        task_id: TaskId,
        observed_score: Score,
    ) -> TaskScoreTransitionResult:
        observed = self._decode_positive(observed_score)
        if observed is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        observed_tag, observed_time_slot, observed_suffix = observed
        if (
            observed_tag != self.RUNNING_VISIBLE_TAG
            or observed_suffix != self.MIN_SUFFIX
            or observed_time_slot
            < self.NORMAL_TIME_MIN_MILLIS // self.SLOT_MILLIS
            or observed_time_slot >= self._IDLE_PARK_TIME_SLOT
        ):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._cas_update(
            task_id,
            observed_score,
            self._idle_park_score(),
        )

    def try_release_idle_park(
        self,
        *,
        task_id: TaskId,
    ) -> TaskScoreTransitionResult:
        return self._script_result(
            self.redis.eval(
                self._TRY_RELEASE_IDLE_PARK_SCRIPT,
                1,
                self.score_key,
                task_id,
                self._idle_park_score(),
                self._score(
                    self.RUNNING_VISIBLE_TAG,
                    self.PAUSE_TIME_SLOT,
                    self.MAX_SUFFIX,
                ),
                self.SLOT_MILLIS,
                self.suffix_factor,
                self._score(
                    self.RUNNING_VISIBLE_TAG,
                    self.MIN_TIME_SLOT,
                    self.MIN_SUFFIX,
                ),
            )
        )

    def close_score(
        self,
        *,
        task_id: TaskId,
        terminal_score: Score,
    ) -> TaskScoreTransitionResult:
        if terminal_score > self.TERMINAL_SCORE_MAX:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._close_positive_score(task_id, terminal_score)

    def close_observed_score(
        self,
        *,
        task_id: TaskId,
        observed_score: Score,
        terminal_score: Score,
    ) -> TaskScoreTransitionResult:
        if terminal_score > self.TERMINAL_SCORE_MAX:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if self._decode_positive(observed_score) is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        return self._cas_update(task_id, observed_score, terminal_score)

    def release_observed_score_hold(
        self,
        *,
        task_id: TaskId,
        observed_hold_score: Score,
    ) -> TaskScoreTransitionResult:
        if observed_hold_score == self._idle_park_score():
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        observed = self._decode_positive(observed_hold_score)
        if observed is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        tag, observed_time_slot, suffix = observed
        if tag not in {
            self.RUNNING_VISIBLE_TAG,
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
        if tag == self.PRE_REVIEW_TAG:
            return TaskScoreBand.PRE_REVIEW
        raise ValueError(f"unknown task score tag: {tag}")

    def _tag_from_band(self, band: TaskScoreBand) -> int | None:
        if band == TaskScoreBand.RUNNING_VISIBLE:
            return self.RUNNING_VISIBLE_TAG
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

    def _valid_public_target_time_millis(self, time_millis: int) -> bool:
        return (
            self._valid_time_millis(time_millis)
            and self._time_slot_from_millis(time_millis)
            != self._IDLE_PARK_TIME_SLOT
        )

    def _idle_park_score(self) -> Score:
        return self._score(
            self.RUNNING_VISIBLE_TAG,
            self._IDLE_PARK_TIME_SLOT,
            self.MAX_SUFFIX,
        )

    def _score_to_int(self, raw_score: Any) -> Score:
        score = int(raw_score)
        if float(raw_score) != float(score):
            raise ValueError(f"non-integer redis zset score: {raw_score!r}")
        return score

    def _decode_task_id(self, raw_task_id: Any) -> TaskId:
        if isinstance(raw_task_id, bytes):
            return raw_task_id.decode("utf-8")
        return str(raw_task_id)
