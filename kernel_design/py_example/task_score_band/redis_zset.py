from __future__ import annotations

from typing import Any, Mapping, Sequence

from .kernel import (
    EpochSecond,
    Score,
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

    def transition_score(
        self,
        *,
        task_id: TaskId,
        expected_score: Score,
        next_score: Score,
    ) -> TaskScoreTransitionResult:
        if not self._is_valid_transition(expected_score, next_score):
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        return self._cas_update(task_id, expected_score, next_score)

    def release_score_lease(
        self,
        *,
        task_id: TaskId,
        expected_lease_score: Score,
        release_epoch_second: EpochSecond,
    ) -> TaskScoreTransitionResult:
        expected = self._decode_positive(expected_lease_score)
        if expected is None:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        tag, _, suffix = expected
        if tag not in {self.RUNNING_VISIBLE_TAG, self.READY_APPROVED_TAG}:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)
        if not self.MIN_EPOCH_SECOND <= release_epoch_second <= self.MAX_EPOCH_SECOND:
            return TaskScoreTransitionResult(TaskScoreTransitionStatus.INVALID)

        release_score = self._score(tag, release_epoch_second, suffix)
        return self._cas_update(task_id, expected_lease_score, release_score)

    def _initialize_one(
        self,
        task_id: TaskId,
        initial_score: Score,
    ) -> TaskScoreTransitionResult:
        with self.redis.pipeline() as pipe:
            while True:
                try:
                    pipe.watch(self.score_key)
                    stored_score = pipe.zscore(self.score_key, task_id)
                    if stored_score is not None:
                        pipe.unwatch()
                        return TaskScoreTransitionResult(
                            TaskScoreTransitionStatus.NOOP,
                            self._score_to_int(stored_score),
                        )
                    pipe.multi()
                    pipe.zadd(self.score_key, {task_id: initial_score})
                    pipe.execute()
                    return TaskScoreTransitionResult(
                        TaskScoreTransitionStatus.TRANSITIONED,
                        initial_score,
                    )
                except Exception as exc:
                    if self._is_watch_error(exc):
                        continue
                    raise

    def _cas_update(
        self,
        task_id: TaskId,
        expected_score: Score,
        next_score: Score,
    ) -> TaskScoreTransitionResult:
        with self.redis.pipeline() as pipe:
            while True:
                try:
                    pipe.watch(self.score_key)
                    stored_raw = pipe.zscore(self.score_key, task_id)
                    if stored_raw is None:
                        pipe.unwatch()
                        return TaskScoreTransitionResult(
                            TaskScoreTransitionStatus.STALE
                        )

                    stored_score = self._score_to_int(stored_raw)
                    if stored_score != expected_score:
                        pipe.unwatch()
                        return TaskScoreTransitionResult(
                            TaskScoreTransitionStatus.STALE,
                            stored_score,
                        )

                    pipe.multi()
                    pipe.zadd(self.score_key, {task_id: next_score})
                    pipe.execute()
                    return TaskScoreTransitionResult(
                        TaskScoreTransitionStatus.TRANSITIONED,
                        next_score,
                    )
                except Exception as exc:
                    if self._is_watch_error(exc):
                        continue
                    raise

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

    def _is_valid_transition(self, expected_score: Score, next_score: Score) -> bool:
        if expected_score < 0:
            return False
        if next_score < 0:
            return True

        current = self._decode_positive(expected_score)
        target = self._decode_positive(next_score)
        if current is None or target is None:
            return False

        current_tag, current_epoch_second, _ = current
        target_tag, target_epoch_second, _ = target
        if target_epoch_second <= current_epoch_second:
            return False
        return target_tag <= current_tag

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

    def _is_watch_error(self, exc: Exception) -> bool:
        return exc.__class__.__name__ == "WatchError"
