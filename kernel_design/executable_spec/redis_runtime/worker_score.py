from __future__ import annotations

from typing import Any, ClassVar, Mapping, Sequence

from ..kernel.worker_score import (
    Dirty,
    HomeBucketId,
    LaneRank,
    Score,
    TimeMillis,
    WorkerId,
    WorkerScoreCore,
    WorkerScorePolarity,
    WorkerScoreState,
    WorkerScoreTransitionResult,
    WorkerScoreTransitionStatus,
)


class RedisWorkerScoreCore(WorkerScoreCore):
    """Redis ZSET implementation of worker score core mechanics."""

    _CURRENT_REWRITE_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local worker_id = ARGV[1]
local target_min_abs_score = tonumber(ARGV[2])
local target_lane_rank = tonumber(ARGV[3])
local slot_factor = tonumber(ARGV[4])
local dirty_factor = tonumber(ARGV[5])

local stored = redis.call("ZSCORE", key, worker_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
local abs_score = math.abs(stored_score)
if abs_score <= 0 then
  return {"invalid", stored_score}
end
local sign = stored_score / abs_score

local slot_remainder = abs_score % slot_factor
local stored_lane_rank = math.floor(slot_remainder / dirty_factor)
local stored_dirty = slot_remainder % dirty_factor

if abs_score >= target_min_abs_score then
  return {"stale", stored_score}
end

if target_lane_rank < 0 then
  target_lane_rank = stored_lane_rank
end

local target_abs_score =
  target_min_abs_score + target_lane_rank * dirty_factor + stored_dirty
if target_abs_score <= 0 then
  return {"invalid", stored_score}
end
local target_score = sign * target_abs_score
redis.call("ZADD", key, target_score, worker_id)
return {"transitioned", target_score}
"""

    _CAS_UPDATE_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local worker_id = ARGV[1]
local observed_score = tonumber(ARGV[2])
local next_score = tonumber(ARGV[3])

local stored = redis.call("ZSCORE", key, worker_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
if stored_score ~= observed_score then
  return {"stale", stored_score}
end

redis.call("ZADD", key, next_score, worker_id)
return {"transitioned", next_score}
"""

    _MARK_LEASE_DIRTY_SCRIPT: ClassVar[str] = """
local key = KEYS[1]
local worker_id = ARGV[1]
local dirty_factor = tonumber(ARGV[2])

local stored = redis.call("ZSCORE", key, worker_id)
if not stored then
  return {"stale"}
end

local stored_score = tonumber(stored)
local abs_score = math.abs(stored_score)
if abs_score <= 0 then
  return {"invalid", stored_score}
end
local sign = stored_score / abs_score

local stored_dirty = abs_score % dirty_factor

if stored_dirty == 1 then
  return {"noop", stored_score}
end

local target_abs_score = abs_score + 1
local target_score = sign * target_abs_score
redis.call("ZADD", key, target_score, worker_id)
return {"transitioned", target_score}
"""

    COLD_TIME_SLOT: ClassVar[int] = 1

    def __init__(
        self,
        redis_client: Any,
        *,
        score_key_prefix: str = "worker:score",
        lane_rank_factor: int = WorkerScoreCore.LANE_RANK_FACTOR,
        dirty_factor: int = WorkerScoreCore.DIRTY_FACTOR,
        recovery_lookback_millis: int = 86_400_000,
    ) -> None:
        super().__init__(
            lane_rank_factor=lane_rank_factor,
            dirty_factor=dirty_factor,
        )
        if recovery_lookback_millis <= 0:
            raise ValueError("recovery_lookback_millis must be positive")
        self.redis = redis_client
        self.score_key_prefix = score_key_prefix
        self.lane_rank_factor = lane_rank_factor
        self.dirty_factor = dirty_factor
        self.slot_factor = lane_rank_factor * dirty_factor
        self.recovery_lookback_slots = max(
            1,
            (recovery_lookback_millis + self.SLOT_MILLIS - 1) // self.SLOT_MILLIS,
        )

    def get_score_states(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, WorkerScoreState | None]:
        if not worker_ids:
            return {}

        key = self._score_key(home_bucket_id)
        with self.redis.pipeline(transaction=False) as pipe:
            for worker_id in worker_ids:
                pipe.zscore(key, worker_id)
            raw_scores = pipe.execute()

        states: dict[WorkerId, WorkerScoreState | None] = {}
        for worker_id, raw_score in zip(worker_ids, raw_scores, strict=True):
            states[worker_id] = (
                None if raw_score is None else self._decode_state(worker_id, raw_score)
            )
        return states

    def acquire_hot_acquire_candidates(
        self,
        *,
        home_bucket_id: HomeBucketId,
        limit: int,
    ) -> Mapping[WorkerId, Score]:
        if limit <= 0:
            return {}

        due_time_slot = self._current_time_slot() - 1
        if due_time_slot < self.MIN_TIME_SLOT:
            return {}

        return dict(
            self._range_worker_candidates(
                self._score_key(home_bucket_id),
                self.MIN_BASE,
                self._abs_score(
                    due_time_slot,
                    self.MAX_LANE_RANK,
                    self.MAX_DIRTY,
                ),
                limit,
                reverse=False,
            )
        )

    def acquire_recovery_recheck_candidates(
        self,
        *,
        home_bucket_id: HomeBucketId,
        limit: int,
    ) -> Sequence[tuple[WorkerId, Score]]:
        if limit <= 0:
            return []

        now_time_slot = self._current_time_slot()
        due_time_slot = now_time_slot - 1
        if due_time_slot < self.MIN_TIME_SLOT:
            return []

        window_start = max(
            self.MIN_TIME_SLOT,
            now_time_slot - self.recovery_lookback_slots,
        )
        max_score = -self._abs_score(window_start, self.MIN_LANE_RANK, self.MIN_DIRTY)
        min_score = -self._abs_score(due_time_slot, self.MAX_LANE_RANK, self.MAX_DIRTY)
        return self._range_worker_candidates(
            self._score_key(home_bucket_id),
            min_score,
            max_score,
            limit,
            reverse=True,
        )

    def initialize_hot_acquire_score(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        lane_rank: LaneRank,
    ) -> WorkerScoreTransitionResult:
        if not self._valid_lane_rank(lane_rank):
            return WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID)

        initial_score = self._score(
            WorkerScorePolarity.HOT_ACQUIRE,
            self._current_time_slot(),
            lane_rank,
            self.MIN_DIRTY,
        )
        added_count = self.redis.zadd(
            self._score_key(home_bucket_id),
            {worker_id: initial_score},
            nx=True,
        )
        if added_count == 1:
            return WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                initial_score,
            )

        stored_score = self.redis.zscore(self._score_key(home_bucket_id), worker_id)
        if stored_score is None:
            return WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE)
        return WorkerScoreTransitionResult(
            WorkerScoreTransitionStatus.NOOP,
            self._score_to_int(stored_score),
        )

    def rewrite_current_scores(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_ids: Sequence[WorkerId],
        target_time_millis: TimeMillis,
        target_lane_rank: LaneRank | None = None,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        unique_worker_ids = tuple(dict.fromkeys(worker_ids))
        if not unique_worker_ids:
            return {}
        if not self._valid_time_millis(target_time_millis):
            return self._uniform_results(
                unique_worker_ids,
                WorkerScoreTransitionStatus.INVALID,
            )
        if target_lane_rank is not None and not self._valid_lane_rank(target_lane_rank):
            return self._uniform_results(
                unique_worker_ids,
                WorkerScoreTransitionStatus.INVALID,
            )

        target_time_slot = self._time_slot_from_millis(target_time_millis)
        target_min_abs_score = self._abs_score(
            target_time_slot,
            self.MIN_LANE_RANK,
            self.MIN_DIRTY,
        )
        key = self._score_key(home_bucket_id)
        with self.redis.pipeline(transaction=False) as pipe:
            for worker_id in unique_worker_ids:
                pipe.eval(
                    self._CURRENT_REWRITE_SCRIPT,
                    1,
                    key,
                    worker_id,
                    target_min_abs_score,
                    -1 if target_lane_rank is None else target_lane_rank,
                    self.slot_factor,
                    self.dirty_factor,
                )
            raw_results = pipe.execute()
        return {
            worker_id: self._script_result(raw_result)
            for worker_id, raw_result in zip(
                unique_worker_ids,
                raw_results,
                strict=True,
            )
        }

    def acquire_observed_hot_score_leases(
        self,
        *,
        home_bucket_id: HomeBucketId,
        observed_scores: Mapping[WorkerId, Score],
        target_time_millis: TimeMillis,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        if not observed_scores:
            return {}
        if not self._valid_time_millis(target_time_millis):
            return self._uniform_results(
                observed_scores,
                WorkerScoreTransitionStatus.INVALID,
            )

        now_time_millis = self._current_time_millis()
        now_time_slot = self._time_slot_from_millis(now_time_millis)
        target_time_slot = self._time_slot_from_millis(target_time_millis)
        if target_time_millis <= now_time_millis or target_time_slot <= now_time_slot:
            return self._uniform_results(
                observed_scores,
                WorkerScoreTransitionStatus.INVALID,
            )

        immediate_results: dict[WorkerId, WorkerScoreTransitionResult] = {}
        pending_updates: dict[WorkerId, tuple[Score, Score]] = {}
        for worker_id, observed_score in observed_scores.items():
            observed = self._decode_score(observed_score)
            if observed is None:
                immediate_results[worker_id] = WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID
                )
                continue
            polarity, observed_time_slot, lane_rank, _ = observed
            if polarity is not WorkerScorePolarity.HOT_ACQUIRE:
                immediate_results[worker_id] = WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID
                )
                continue
            if observed_time_slot >= now_time_slot:
                immediate_results[worker_id] = WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.STALE
                )
                continue
            pending_updates[worker_id] = (
                observed_score,
                self._score(
                    WorkerScorePolarity.HOT_ACQUIRE,
                    target_time_slot,
                    lane_rank,
                    self.MIN_DIRTY,
                ),
            )
        return self._merge_batch_results(
            observed_scores,
            immediate_results,
            self._pipeline_cas_updates(home_bucket_id, pending_updates),
        )

    def renew_active_hot_score_leases(
        self,
        *,
        home_bucket_id: HomeBucketId,
        observed_scores: Mapping[WorkerId, Score],
        target_time_millis: TimeMillis,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        if not observed_scores:
            return {}
        if not self._valid_time_millis(target_time_millis):
            return self._uniform_results(
                observed_scores,
                WorkerScoreTransitionStatus.INVALID,
            )

        current_time_slot = self._current_time_slot()
        target_time_slot = self._time_slot_from_millis(target_time_millis)
        immediate_results: dict[WorkerId, WorkerScoreTransitionResult] = {}
        pending_updates: dict[WorkerId, tuple[Score, Score]] = {}
        for worker_id, observed_score in observed_scores.items():
            observed = self._decode_score(observed_score)
            if observed is None:
                immediate_results[worker_id] = WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID
                )
                continue
            polarity, observed_time_slot, lane_rank, dirty = observed
            if polarity is not WorkerScorePolarity.HOT_ACQUIRE:
                immediate_results[worker_id] = WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID
                )
                continue
            if dirty != self.MIN_DIRTY or observed_time_slot < current_time_slot:
                immediate_results[worker_id] = WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.STALE,
                    observed_score,
                )
                continue
            if target_time_slot <= observed_time_slot:
                immediate_results[worker_id] = WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID
                )
                continue
            pending_updates[worker_id] = (
                observed_score,
                self._score(
                    polarity,
                    target_time_slot,
                    lane_rank,
                    self.MIN_DIRTY,
                ),
            )
        return self._merge_batch_results(
            observed_scores,
            immediate_results,
            self._pipeline_cas_updates(home_bucket_id, pending_updates),
        )

    def mark_current_lease_dirty(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
    ) -> WorkerScoreTransitionResult:
        return self._script_result(
            self.redis.eval(
                self._MARK_LEASE_DIRTY_SCRIPT,
                1,
                self._score_key(home_bucket_id),
                worker_id,
                self.dirty_factor,
            )
        )

    def toggle_current_polarity(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        observed_score: Score,
        target_lane_rank: LaneRank,
    ) -> WorkerScoreTransitionResult:
        if not self._valid_lane_rank(target_lane_rank):
            return WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID)

        observed = self._decode_score(observed_score)
        if observed is None:
            return WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID)

        polarity, time_slot, _, dirty = observed
        if self._abs_score(time_slot, target_lane_rank, dirty) < self.MIN_BASE:
            return WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID)

        next_score = self._score(
            WorkerScorePolarity(-int(polarity)),
            time_slot,
            target_lane_rank,
            dirty,
        )
        return self._cas_update(home_bucket_id, worker_id, observed_score, next_score)

    def exhaust_recovery_recheck(
        self,
        *,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        observed_score: Score,
    ) -> WorkerScoreTransitionResult:
        observed = self._decode_score(observed_score)
        if observed is None:
            return WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID)

        polarity, _, lane_rank, dirty = observed
        if polarity != WorkerScorePolarity.RECOVERY_RECHECK:
            return WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID)

        next_score = self._score(
            WorkerScorePolarity.RECOVERY_RECHECK,
            self.COLD_TIME_SLOT,
            lane_rank,
            dirty,
        )
        return self._cas_update(home_bucket_id, worker_id, observed_score, next_score)

    def release_score_holds(
        self,
        *,
        home_bucket_id: HomeBucketId,
        observed_scores: Mapping[WorkerId, Score],
        release_time_millis: TimeMillis,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        if not observed_scores:
            return {}
        if not self._valid_time_millis(release_time_millis):
            return self._uniform_results(
                observed_scores,
                WorkerScoreTransitionStatus.INVALID,
            )

        release_time_slot = self._time_slot_from_millis(release_time_millis)
        current_slot_start_millis = self._time_millis_from_slot(
            self._current_time_slot()
        )
        release_slot_base = self._abs_score(
            release_time_slot,
            self.MIN_LANE_RANK,
            self.MIN_DIRTY,
        )
        if release_time_millis < current_slot_start_millis:
            return self._uniform_results(
                observed_scores,
                WorkerScoreTransitionStatus.INVALID,
            )

        immediate_results: dict[WorkerId, WorkerScoreTransitionResult] = {}
        pending_updates: dict[WorkerId, tuple[Score, Score]] = {}
        for worker_id, observed_score in observed_scores.items():
            observed_abs_score = abs(observed_score)
            observed_low_bits = observed_abs_score % self.slot_factor
            if release_slot_base >= observed_abs_score:
                immediate_results[worker_id] = WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.INVALID
                )
                continue

            polarity = 1 if observed_score > 0 else -1
            next_abs_score = release_slot_base + observed_low_bits
            pending_updates[worker_id] = (
                observed_score,
                polarity * next_abs_score,
            )
        return self._merge_batch_results(
            observed_scores,
            immediate_results,
            self._pipeline_cas_updates(home_bucket_id, pending_updates),
        )

    def _pipeline_cas_updates(
        self,
        home_bucket_id: HomeBucketId,
        updates: Mapping[WorkerId, tuple[Score, Score]],
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        if not updates:
            return {}
        key = self._score_key(home_bucket_id)
        with self.redis.pipeline(transaction=False) as pipe:
            for worker_id, (observed_score, next_score) in updates.items():
                pipe.eval(
                    self._CAS_UPDATE_SCRIPT,
                    1,
                    key,
                    worker_id,
                    observed_score,
                    next_score,
                )
            raw_results = pipe.execute()
        return {
            worker_id: self._script_result(raw_result)
            for worker_id, raw_result in zip(updates, raw_results, strict=True)
        }

    @staticmethod
    def _uniform_results(
        worker_ids: Sequence[WorkerId] | Mapping[WorkerId, object],
        status: WorkerScoreTransitionStatus,
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        return {
            worker_id: WorkerScoreTransitionResult(status)
            for worker_id in worker_ids
        }

    @staticmethod
    def _merge_batch_results(
        worker_ids: Mapping[WorkerId, object],
        immediate_results: Mapping[WorkerId, WorkerScoreTransitionResult],
        persisted_results: Mapping[WorkerId, WorkerScoreTransitionResult],
    ) -> Mapping[WorkerId, WorkerScoreTransitionResult]:
        return {
            worker_id: immediate_results.get(worker_id)
            or persisted_results[worker_id]
            for worker_id in worker_ids
        }

    def _cas_update(
        self,
        home_bucket_id: HomeBucketId,
        worker_id: WorkerId,
        observed_score: Score,
        next_score: Score,
    ) -> WorkerScoreTransitionResult:
        return self._script_result(
            self.redis.eval(
                self._CAS_UPDATE_SCRIPT,
                1,
                self._score_key(home_bucket_id),
                worker_id,
                observed_score,
                next_score,
            )
        )

    def _script_result(self, raw: Sequence[Any]) -> WorkerScoreTransitionResult:
        if not raw:
            raise ValueError("empty redis script result")

        status_value = raw[0]
        if isinstance(status_value, bytes):
            status_value = status_value.decode("utf-8")

        score = None
        if len(raw) > 1 and raw[1] is not None:
            score = self._score_to_int(raw[1])
        return WorkerScoreTransitionResult(
            WorkerScoreTransitionStatus(str(status_value)),
            score,
        )

    def _range_worker_candidates(
        self,
        key: str,
        min_score: Score,
        max_score: Score,
        limit: int,
        *,
        reverse: bool,
    ) -> list[tuple[WorkerId, Score]]:
        if reverse:
            raw_rows = self.redis.zrevrangebyscore(
                key,
                max_score,
                min_score,
                start=0,
                num=limit,
                withscores=True,
            )
        else:
            raw_rows = self.redis.zrangebyscore(
                key,
                min_score,
                max_score,
                start=0,
                num=limit,
                withscores=True,
            )
        return [
            (self._decode_worker_id(raw_worker_id), self._score_to_int(raw_score))
            for raw_worker_id, raw_score in raw_rows
        ]

    def _decode_state(
        self,
        worker_id: WorkerId,
        raw_score: Any,
    ) -> WorkerScoreState:
        score = self._score_to_int(raw_score)
        decoded = self._decode_score(score)
        if decoded is None:
            raise ValueError(f"invalid worker score: worker_id={worker_id!r}")

        polarity, time_slot, lane_rank, dirty = decoded
        return WorkerScoreState(
            worker_id,
            score,
            polarity,
            self._time_millis_from_slot(time_slot),
            lane_rank,
            dirty,
        )

    def _decode_score(
        self,
        score: Score,
    ) -> tuple[WorkerScorePolarity, int, LaneRank, Dirty] | None:
        if score == 0:
            return None

        polarity = (
            WorkerScorePolarity.HOT_ACQUIRE
            if score > 0
            else WorkerScorePolarity.RECOVERY_RECHECK
        )
        abs_score = abs(score)
        time_slot = abs_score // self.slot_factor
        slot_remainder = abs_score % self.slot_factor
        lane_rank = slot_remainder // self.dirty_factor
        dirty = slot_remainder % self.dirty_factor

        if not self._valid_time_slot(time_slot):
            return None
        if not self._valid_lane_rank(lane_rank):
            return None
        if not self.MIN_DIRTY <= dirty <= self.MAX_DIRTY:
            return None
        return polarity, time_slot, lane_rank, dirty

    def _score(
        self,
        polarity: WorkerScorePolarity,
        time_slot: int,
        lane_rank: LaneRank,
        dirty: Dirty,
    ) -> Score:
        abs_score = self._abs_score(time_slot, lane_rank, dirty)
        return int(polarity) * abs_score

    def _abs_score(
        self,
        time_slot: int,
        lane_rank: LaneRank,
        dirty: Dirty,
    ) -> Score:
        return time_slot * self.slot_factor + lane_rank * self.dirty_factor + dirty

    def _score_key(self, home_bucket_id: HomeBucketId) -> str:
        return f"{self.score_key_prefix}:{home_bucket_id}"

    def _current_time_slot(self) -> int:
        return self._time_slot_from_millis(self._current_time_millis())

    def _current_time_millis(self) -> TimeMillis:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    def _time_slot_from_millis(self, time_millis: TimeMillis) -> int:
        return int(time_millis) // self.SLOT_MILLIS

    def _time_millis_from_slot(self, time_slot: int) -> TimeMillis:
        return int(time_slot) * self.SLOT_MILLIS

    def _score_to_int(self, raw_score: Any) -> Score:
        score = int(raw_score)
        if float(raw_score) != float(score):
            raise ValueError(f"non-integer redis zset score: {raw_score!r}")
        return score

    def _decode_worker_id(self, raw_worker_id: Any) -> WorkerId:
        if isinstance(raw_worker_id, bytes):
            return raw_worker_id.decode("utf-8")
        return str(raw_worker_id)

    def _valid_time_slot(self, time_slot: int) -> bool:
        return self.MIN_TIME_SLOT <= time_slot <= self.MAX_TIME_SLOT

    def _valid_time_millis(self, time_millis: int) -> bool:
        return self.MIN_TIME_MILLIS <= time_millis <= self.MAX_TIME_MILLIS

    def _valid_lane_rank(self, lane_rank: int) -> bool:
        return self.MIN_LANE_RANK <= lane_rank <= self.MAX_LANE_RANK
