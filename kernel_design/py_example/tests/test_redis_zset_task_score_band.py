from __future__ import annotations

import unittest

from kernel_design.py_example import (
    RedisZsetTaskScoreBandKernel,
    TaskScoreBand,
    TaskScoreTransitionStatus,
)


class FakePipeline:
    def __init__(self, redis: FakeRedis, transaction: bool = True) -> None:
        self.redis = redis
        self.transaction = transaction
        self.commands: list[tuple[str, str, object]] = []
        self.in_multi = False

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def watch(self, key: str) -> None:
        self.key = key

    def unwatch(self) -> None:
        pass

    def multi(self) -> None:
        self.in_multi = True

    def zscore(self, key: str, member: str) -> object:
        if self.in_multi or not self.transaction:
            self.commands.append(("zscore", key, member))
            return self
        return self.redis.zscore(key, member)

    def zadd(self, key: str, mapping: dict[str, int]) -> object:
        if self.in_multi or not self.transaction:
            self.commands.append(("zadd", key, mapping))
            return self
        return self.redis.zadd(key, mapping)

    def execute(self) -> list[object]:
        results: list[object] = []
        for command, key, value in self.commands:
            if command == "zscore":
                results.append(self.redis.zscore(key, str(value)))
            elif command == "zadd":
                results.append(self.redis.zadd(key, value))  # type: ignore[arg-type]
        self.commands.clear()
        return results


class FakeRedis:
    def __init__(self) -> None:
        self.zsets: dict[str, dict[str, int]] = {}
        self.now = 1_000

    def pipeline(self, transaction: bool = True) -> FakePipeline:
        return FakePipeline(self, transaction)

    def zscore(self, key: str, member: str) -> int | None:
        return self.zsets.get(key, {}).get(member)

    def zadd(self, key: str, mapping: dict[str, int]) -> int:
        self.zsets.setdefault(key, {}).update(mapping)
        return len(mapping)

    def zrangebyscore(
        self,
        key: str,
        min_score: int,
        max_score: int,
        *,
        start: int = 0,
        num: int | None = None,
    ) -> list[str]:
        rows = sorted(
            (score, member)
            for member, score in self.zsets.get(key, {}).items()
            if min_score <= score <= max_score
        )
        if num is None:
            return [member for _, member in rows[start:]]
        return [member for _, member in rows[start : start + num]]

    def time(self) -> tuple[int, int]:
        return self.now, 0


class RedisZsetTaskScoreBandKernelTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.kernel = RedisZsetTaskScoreBandKernel(self.redis)

    def score(self, tag: int, epoch_second: int, suffix: int) -> int:
        return tag * self.kernel.tag_factor + epoch_second * 100 + suffix

    def test_acquire_prefers_running_then_ready_and_skips_pause(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 999, 5)
        ready = self.score(self.kernel.READY_APPROVED_TAG, 999, 5)
        paused = self.score(
            self.kernel.RUNNING_VISIBLE_TAG,
            self.kernel.PAUSE_EPOCH_SECOND,
            5,
        )

        results = self.kernel.initialize_scores(
            initial_scores={"running": running, "ready": ready, "paused": paused}
        )

        self.assertEqual(
            TaskScoreTransitionStatus.TRANSITIONED,
            results["running"].status,
        )
        self.assertEqual(
            ["running", "ready"],
            self.kernel.acquire_worker_allocatable_tasks(limit=10),
        )
        self.assertEqual(
            ["running"],
            self.kernel.acquire_dispatch_work_tasks(limit=10),
        )

    def test_transition_allows_downward_lifecycle_jump(self) -> None:
        pre_review = self.score(self.kernel.PRE_REVIEW_TAG, 1_000, 7)
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_001, 9)
        self.kernel.initialize_scores(initial_scores={"task": pre_review})

        result = self.kernel.transition_score(
            task_id="task",
            expected_score=pre_review,
            next_score=running,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, state.band)

    def test_transition_rejects_lifecycle_regression(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        ready = self.score(self.kernel.READY_APPROVED_TAG, 1_001, 7)
        self.kernel.initialize_scores(initial_scores={"task": running})

        result = self.kernel.transition_score(
            task_id="task",
            expected_score=running,
            next_score=ready,
        )

        self.assertEqual(TaskScoreTransitionStatus.INVALID, result.status)

    def test_release_score_lease_preserves_suffix(self) -> None:
        paused = self.score(
            self.kernel.RUNNING_VISIBLE_TAG,
            self.kernel.PAUSE_EPOCH_SECOND,
            4,
        )
        self.kernel.initialize_scores(initial_scores={"task": paused})

        result = self.kernel.release_score_lease(
            task_id="task",
            expected_lease_score=paused,
            release_epoch_second=1_000,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(1_000, state.epoch_second)
        self.assertEqual(4, state.suffix)


if __name__ == "__main__":
    unittest.main()
