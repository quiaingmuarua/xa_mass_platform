from __future__ import annotations

from typing import Any, ClassVar, Mapping, Sequence

from .kernel import (
    EpochSecond,
    Score,
    Suffix,
    TaskId,
    TaskScoreBand,
    TaskScoreBandKernel,
    TaskScoreState,
    TaskScoreTransitionResult,
    TaskScoreTransitionStatus,
)


class RedisZsetTaskScoreBandKernel(TaskScoreBandKernel):
    """Redis ZSET implementation of the task score-band kernel.

    This class intentionally assumes a redis-py shaped client and one ZSET key.
    It is meant to make the score-band mechanics executable, not to hide Redis
    behind another framework.
    """

    _INITIALIZE_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local initial_score = tonumber(ARGV[2])

local stored = redis.call("ZSCORE", key, task_id)
if stored then
  return {"noop", tonumber(stored)}
end

redis.call("ZADD", key, initial_score, task_id)
return {"transitioned", initial_score}
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

    _REWRITE_POSITIVE_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local expected_tag = tonumber(ARGV[2])
local target_tag = tonumber(ARGV[3])
local target_epoch_second = tonumber(ARGV[4])
local target_suffix = tonumber(ARGV[5])
local tag_factor = tonumber(ARGV[6])
local suffix_factor = tonumber(ARGV[7])
local max_epoch_second = tonumber(ARGV[8])
local max_suffix = tonumber(ARGV[9])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
if stored_score <= 0 then
  return {"stale", stored_score}
end

local stored_tag = math.floor(stored_score / tag_factor)
local rest = stored_score % tag_factor
local stored_epoch_second = math.floor(rest / suffix_factor)
local stored_suffix = rest % suffix_factor

if stored_tag ~= 1 and stored_tag ~= 2 and stored_tag ~= 3 then
  return {"stale", stored_score}
end
if stored_epoch_second < 0 or stored_epoch_second > max_epoch_second then
  return {"stale", stored_score}
end
if stored_suffix < 0 or stored_suffix > max_suffix then
  return {"stale", stored_score}
end
if stored_tag ~= expected_tag then
  return {"stale", stored_score}
end

if target_tag > stored_tag then
  return {"invalid"}
end
if target_epoch_second <= stored_epoch_second then
  return {"invalid"}
end
if target_suffix < 0 then
  target_suffix = stored_suffix
end
if target_suffix > max_suffix then
  return {"invalid"}
end

local next_score = (
  target_tag * tag_factor
  + target_epoch_second * suffix_factor
  + target_suffix
)
redis.call("ZADD", key, next_score, task_id)
return {"transitioned", next_score}
"""

    _REWRITE_SAME_BAND_EPOCH_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local expected_tag = tonumber(ARGV[2])
local target_epoch_second = tonumber(ARGV[3])
local tag_factor = tonumber(ARGV[4])
local suffix_factor = tonumber(ARGV[5])
local max_epoch_second = tonumber(ARGV[6])
local max_suffix = tonumber(ARGV[7])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
if stored_score <= 0 then
  return {"stale", stored_score}
end

local stored_tag = math.floor(stored_score / tag_factor)
local rest = stored_score % tag_factor
local stored_epoch_second = math.floor(rest / suffix_factor)
local stored_suffix = rest % suffix_factor

if stored_tag ~= 1 and stored_tag ~= 2 and stored_tag ~= 3 then
  return {"stale", stored_score}
end
if stored_epoch_second < 0 or stored_epoch_second > max_epoch_second then
  return {"stale", stored_score}
end
if stored_suffix < 0 or stored_suffix > max_suffix then
  return {"stale", stored_score}
end
if stored_tag ~= expected_tag then
  return {"stale", stored_score}
end
if target_epoch_second <= stored_epoch_second then
  return {"invalid"}
end

local next_score = (
  stored_tag * tag_factor
  + target_epoch_second * suffix_factor
  + stored_suffix
)
redis.call("ZADD", key, next_score, task_id)
return {"transitioned", next_score}
"""

    _BUMP_SAME_BAND_EPOCH_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local task_id = ARGV[1]
local expected_tag = tonumber(ARGV[2])
local max_bumpable_epoch_second = tonumber(ARGV[3])
local delta_seconds = tonumber(ARGV[4])
local tag_factor = tonumber(ARGV[5])
local suffix_factor = tonumber(ARGV[6])
local max_epoch_second = tonumber(ARGV[7])
local max_suffix = tonumber(ARGV[8])

local stored = redis.call("ZSCORE", key, task_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
if stored_score <= 0 then
  return {"stale", stored_score}
end

local stored_tag = math.floor(stored_score / tag_factor)
local rest = stored_score % tag_factor
local stored_epoch_second = math.floor(rest / suffix_factor)
local stored_suffix = rest % suffix_factor

if stored_tag ~= 1 and stored_tag ~= 2 and stored_tag ~= 3 then
  return {"stale", stored_score}
end
if stored_epoch_second < 0 or stored_epoch_second > max_epoch_second then
  return {"stale", stored_score}
end
if stored_suffix < 0 or stored_suffix > max_suffix then
  return {"stale", stored_score}
end
if stored_tag ~= expected_tag then
  return {"stale", stored_score}
end
if stored_epoch_second > max_bumpable_epoch_second then
  return {"stale", stored_score}
end
if delta_seconds <= 0 then
  return {"invalid"}
end
if stored_epoch_second + delta_seconds > max_epoch_second then
  return {"invalid"}
end

local next_score = redis.call(
  "ZINCRBY",
  key,
  delta_seconds * suffix_factor,
  task_id
)
return {"transitioned", tonumber(next_score)}
"""

    def __init__(
        self,
        redis_client: Any,
        *,
        score_key: str = "task:score",
        tag_factor: int = TaskScoreBandKernel.DEFAULT_TAG_FACTOR,
        suffix_factor: int = TaskScoreBandKernel.SUFFIX_FACTOR,
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

    def acquire_worker_allocatable_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        if limit <= 0:
            return []

        now_epoch_second = self._current_epoch_second()
        running_limit = limit
        running = self._range_task_ids(
            self._score(self.RUNNING_VISIBLE_TAG, 0, 0),
            self._score(self.RUNNING_VISIBLE_TAG, now_epoch_second, 99),
            running_limit,
        )
        remaining = limit - len(running)
        if remaining <= 0:
            return running

        ready = self._range_task_ids(
            self._score(self.READY_APPROVED_TAG, 0, 0),
            self._score(self.READY_APPROVED_TAG, now_epoch_second, 99),
            remaining,
        )
        return [*running, *ready]

    def acquire_dispatch_work_tasks(
        self,
        *,
        limit: int,
    ) -> Sequence[TaskId]:
        if limit <= 0:
            return []
        now_epoch_second = self._current_epoch_second()
        return self._range_task_ids(
            self._score(self.RUNNING_VISIBLE_TAG, 0, 0),
            self._score(self.RUNNING_VISIBLE_TAG, now_epoch_second, 99),
            limit,
        )

    def initialize_scores(
        self,
        *,
        initial_scores: Mapping[TaskId, Score],
    ) -> Mapping[TaskId, TaskScoreTransitionResult]:
        if not initial_scores:
            return {}

        results: dict[TaskId, TaskScoreTransitionResult] = {}
        for task_id, initial_score in initial_scores.items():
            if not self._is_valid_initial_score(initial_score):
                results[task_id] = TaskScoreTransitionResult(
                    TaskScoreTransitionStatus.INVALID
                )
                continue
            results[task_id] = self._initialize_one(task_id, initial_score)
        return results

    def rewrite_score(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_epoch_second: EpochSecond,
        target_band: TaskScoreBand | None = None,
        target_suffix: Suffix | None = None,
    ) -> TaskScoreTransitionResult:
        if expected_band == TaskScoreBand.TERMINAL:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if target_band == TaskScoreBand.TERMINAL:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if not self.MIN_EPOCH_SECOND <= target_epoch_second <= self.MAX_EPOCH_SECOND:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if target_suffix is not None and not (
            self.MIN_SUFFIX <= target_suffix <= self.MAX_SUFFIX
        ):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if target_band is None and target_suffix is None:
            return self.rewrite_same_band_epoch(
                task_id=task_id,
                expected_band=expected_band,
                target_epoch_second=target_epoch_second,
            )

        return self._rewrite_positive_score(
            task_id=task_id,
            expected_band=expected_band,
            target_epoch_second=target_epoch_second,
            target_band=target_band,
            target_suffix=target_suffix,
        )

    def rewrite_same_band_epoch(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_epoch_second: EpochSecond,
    ) -> TaskScoreTransitionResult:
        if expected_band == TaskScoreBand.TERMINAL:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if not self.MIN_EPOCH_SECOND <= target_epoch_second <= self.MAX_EPOCH_SECOND:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        expected_tag = self._tag_from_band(expected_band)
        if expected_tag is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._script_result(
            self.redis.eval(
                self._REWRITE_SAME_BAND_EPOCH_SCRIPT,
                1,
                self.score_key,
                task_id,
                expected_tag,
                target_epoch_second,
                self.tag_factor,
                self.suffix_factor,
                self.MAX_EPOCH_SECOND,
                self.MAX_SUFFIX,
            )
        )

    def bump_same_band_epoch(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        max_bumpable_epoch_second: EpochSecond,
        delta_seconds: int,
    ) -> TaskScoreTransitionResult:
        if expected_band == TaskScoreBand.TERMINAL:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if not (
            self.MIN_EPOCH_SECOND
            <= max_bumpable_epoch_second
            <= self.MAX_EPOCH_SECOND
        ):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if delta_seconds <= 0:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        expected_tag = self._tag_from_band(expected_band)
        if expected_tag is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._script_result(
            self.redis.eval(
                self._BUMP_SAME_BAND_EPOCH_SCRIPT,
                1,
                self.score_key,
                task_id,
                expected_tag,
                max_bumpable_epoch_second,
                delta_seconds,
                self.tag_factor,
                self.suffix_factor,
                self.MAX_EPOCH_SECOND,
                self.MAX_SUFFIX,
            )
        )

    def close_score(
        self,
        *,
        task_id: TaskId,
        observed_score: Score,
        terminal_score: Score,
    ) -> TaskScoreTransitionResult:
        if observed_score < self.MUTABLE_SCORE_MIN:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if terminal_score > self.TERMINAL_SCORE_MAX:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._cas_update(task_id, observed_score, terminal_score)

    def release_score_lease(
        self,
        *,
        task_id: TaskId,
        observed_lease_score: Score,
        release_epoch_second: EpochSecond,
    ) -> TaskScoreTransitionResult:
        observed = self._decode_positive(observed_lease_score)
        if observed is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        tag, _, suffix = observed
        if tag not in {self.RUNNING_VISIBLE_TAG, self.READY_APPROVED_TAG}:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if not self.MIN_EPOCH_SECOND <= release_epoch_second <= self.MAX_EPOCH_SECOND:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        release_score = self._score(tag, release_epoch_second, suffix)
        return self._cas_update(task_id, observed_lease_score, release_score)

    def _initialize_one(
        self,
        task_id: TaskId,
        initial_score: Score,
    ) -> TaskScoreTransitionResult:
        return self._script_result(
            self.redis.eval(
                self._INITIALIZE_SCRIPT,
                1,
                self.score_key,
                task_id,
                initial_score,
            )
        )

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

    def _rewrite_positive_score(
        self,
        *,
        task_id: TaskId,
        expected_band: TaskScoreBand,
        target_epoch_second: EpochSecond,
        target_band: TaskScoreBand | None,
        target_suffix: Suffix | None,
    ) -> TaskScoreTransitionResult:
        expected_tag = self._tag_from_band(expected_band)
        target_tag = self._tag_from_band(target_band or expected_band)
        if expected_tag is None or target_tag is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._script_result(
            self.redis.eval(
                self._REWRITE_POSITIVE_SCRIPT,
                1,
                self.score_key,
                task_id,
                expected_tag,
                target_tag,
                target_epoch_second,
                -1 if target_suffix is None else target_suffix,
                self.tag_factor,
                self.suffix_factor,
                self.MAX_EPOCH_SECOND,
                self.MAX_SUFFIX,
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

        tag, epoch_second, suffix = decoded
        return TaskScoreState(
            task_id,
            score,
            self._band_from_tag(tag),
            epoch_second,
            suffix,
        )

    def _is_valid_initial_score(self, score: Score) -> bool:
        if score < 0:
            return True
        return self._decode_positive(score) is not None

    def _decode_positive(
        self,
        score: Score,
    ) -> tuple[int, EpochSecond, int] | None:
        if score <= 0:
            return None

        tag = score // self.tag_factor
        rest = score % self.tag_factor
        epoch_second = rest // self.suffix_factor
        suffix = rest % self.suffix_factor

        if tag not in self.VALID_POSITIVE_TAGS:
            return None
        if not self.MIN_EPOCH_SECOND <= epoch_second <= self.MAX_EPOCH_SECOND:
            return None
        if not self.MIN_SUFFIX <= suffix <= self.MAX_SUFFIX:
            return None
        return tag, epoch_second, suffix

    def _score(
        self,
        tag: int,
        epoch_second: EpochSecond,
        suffix: int,
    ) -> Score:
        return tag * self.tag_factor + epoch_second * self.suffix_factor + suffix

    def _band_from_tag(self, tag: int) -> TaskScoreBand:
        if tag == self.RUNNING_VISIBLE_TAG:
            return TaskScoreBand.RUNNING_VISIBLE
        if tag == self.READY_APPROVED_TAG:
            return TaskScoreBand.READY_APPROVED
        if tag == self.PRE_REVIEW_TAG:
            return TaskScoreBand.PRE_REVIEW
        raise ValueError(f"unknown task score tag: {tag}")

    def _tag_from_band(self, band: TaskScoreBand) -> int | None:
        if band == TaskScoreBand.RUNNING_VISIBLE:
            return self.RUNNING_VISIBLE_TAG
        if band == TaskScoreBand.READY_APPROVED:
            return self.READY_APPROVED_TAG
        if band == TaskScoreBand.PRE_REVIEW:
            return self.PRE_REVIEW_TAG
        return None

    def _current_epoch_second(self) -> EpochSecond:
        seconds, _ = self.redis.time()
        return int(seconds)

    def _score_to_int(self, raw_score: Any) -> Score:
        score = int(raw_score)
        if float(raw_score) != float(score):
            raise ValueError(f"non-integer redis zset score: {raw_score!r}")
        return score

    def _decode_task_id(self, raw_task_id: Any) -> TaskId:
        if isinstance(raw_task_id, bytes):
            return raw_task_id.decode("utf-8")
        return str(raw_task_id)
