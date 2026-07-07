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
        self.commands: list[tuple[str, str, str]] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def zscore(self, key: str, member: str) -> object:
        if not self.transaction:
            self.commands.append(("zscore", key, member))
            return self
        return self.redis.zscore(key, member)

    def execute(self) -> list[object]:
        results: list[object] = []
        for _, key, member in self.commands:
            results.append(self.redis.zscore(key, member))
        self.commands.clear()
        return results


class FakeRedis:
    def __init__(self) -> None:
        self.zsets: dict[str, dict[str, int]] = {}
        self.now = 1_000
        self.eval_count = 0

    def pipeline(self, transaction: bool = True) -> FakePipeline:
        return FakePipeline(self, transaction)

    def zscore(self, key: str, member: str) -> int | None:
        return self.zsets.get(key, {}).get(member)

    def zadd(
        self,
        key: str,
        mapping: dict[str, int],
        *,
        nx: bool = False,
    ) -> int:
        zset = self.zsets.setdefault(key, {})
        added = 0
        for member, score in mapping.items():
            if nx and member in zset:
                continue
            if member not in zset:
                added += 1
            zset[member] = score
        return added

    def eval(self, script: str, numkeys: int, *args: object) -> list[object]:
        self.eval_count += 1
        if numkeys != 1:
            raise ValueError(f"unsupported fake eval key count: {numkeys}")
        key = str(args[0])
        argv = args[1:]

        if "local terminal_score" in script:
            return self._eval_close_positive(key, argv)
        if "local observed_score" in script:
            return self._eval_cas_update(key, argv)
        if "local min_expected_score" in script:
            return self._eval_mint_from_range(key, argv)
        raise ValueError("unsupported fake redis script")

    def _eval_cas_update(self, key: str, argv: tuple[object, ...]) -> list[object]:
        task_id = str(argv[0])
        observed_score = int(argv[1])
        next_score = int(argv[2])

        stored = self.zscore(key, task_id)
        if stored is None:
            return ["stale"]
        if stored != observed_score:
            return ["stale", stored]

        self.zadd(key, {task_id: next_score})
        return ["transitioned", next_score]

    def _eval_close_positive(self, key: str, argv: tuple[object, ...]) -> list[object]:
        task_id = str(argv[0])
        terminal_score = int(argv[1])

        stored = self.zscore(key, task_id)
        if stored is None:
            return ["stale"]
        if stored < 0:
            return ["noop", stored]

        self.zadd(key, {task_id: terminal_score})
        return ["transitioned", terminal_score]

    def _eval_mint_from_range(
        self,
        key: str,
        argv: tuple[object, ...],
    ) -> list[object]:
        task_id = str(argv[0])
        min_expected_score = int(argv[1])
        max_expected_score = int(argv[2])
        target_score_base = int(argv[3])
        target_suffix = int(argv[4])
        suffix_delta = int(argv[5])
        suffix_factor = int(argv[6])
        max_suffix = int(argv[7])

        stored = self.zscore(key, task_id)
        if stored is None:
            return ["stale"]
        if stored < min_expected_score or stored > max_expected_score:
            return ["stale", stored]

        stored_suffix = stored % suffix_factor
        if target_suffix < 0:
            target_suffix = stored_suffix + suffix_delta
        if target_suffix < 0 or target_suffix > max_suffix:
            return ["invalid", stored]

        next_score = target_score_base + target_suffix
        self.zadd(key, {task_id: next_score})
        return ["transitioned", next_score]

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

    def store_score(self, task_id: str, score: int) -> None:
        self.redis.zadd(self.kernel.score_key, {task_id: score})

    def test_acquire_prefers_running_then_ready_and_skips_pause(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 999, 5)
        ready = self.score(self.kernel.READY_APPROVED_TAG, 999, 5)
        paused = self.score(
            self.kernel.RUNNING_VISIBLE_TAG,
            self.kernel.PAUSE_EPOCH_SECOND,
            5,
        )

        self.store_score("running", running)
        self.store_score("ready", ready)
        self.store_score("paused", paused)

        self.assertEqual(
            ["running", "ready"],
            self.kernel.acquire_worker_allocatable_tasks(limit=10),
        )
        self.assertEqual(
            ["running"],
            self.kernel.acquire_dispatch_work_tasks(limit=10),
        )

    def test_initialize_score_enters_pre_review_with_internal_epoch(self) -> None:
        existing = self.score(self.kernel.PRE_REVIEW_TAG, 900, 1)
        self.redis.zadd(self.kernel.score_key, {"existing": existing})

        existing_result = self.kernel.initialize_score(
            task_id="existing",
            suffix=7,
        )
        new_result = self.kernel.initialize_score(
            task_id="new",
            suffix=7,
        )
        invalid_result = self.kernel.initialize_score(
            task_id="invalid",
            suffix=100,
        )
        states = self.kernel.get_score_states(task_ids=["existing", "new"])

        self.assertEqual(0, self.redis.eval_count)
        self.assertEqual(TaskScoreTransitionStatus.NOOP, existing_result.status)
        self.assertEqual(existing, existing_result.score)
        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, new_result.status)
        self.assertEqual(
            self.score(self.kernel.PRE_REVIEW_TAG, self.redis.now, 7),
            new_result.score,
        )
        self.assertEqual(TaskScoreTransitionStatus.INVALID, invalid_result.status)
        self.assertIsNotNone(states["existing"])
        self.assertEqual(existing, states["existing"].score)
        self.assertIsNotNone(states["new"])
        self.assertEqual(TaskScoreBand.PRE_REVIEW, states["new"].band)
        self.assertEqual(self.redis.now, states["new"].epoch_second)
        self.assertEqual(7, states["new"].suffix)

    def test_rewrite_allows_downward_lifecycle_jump(self) -> None:
        pre_review = self.score(self.kernel.PRE_REVIEW_TAG, 1_000, 7)
        self.store_score("task", pre_review)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.PRE_REVIEW,
            target_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_001,
            target_suffix=9,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, state.band)
        self.assertEqual(9, state.suffix)

    def test_rewrite_rejects_lifecycle_regression(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_band=TaskScoreBand.READY_APPROVED,
            target_epoch_second=1_001,
        )

        self.assertEqual(TaskScoreTransitionStatus.INVALID, result.status)

    def test_rewrite_allows_suffix_change_only_with_newer_epoch(self) -> None:
        ready = self.score(self.kernel.READY_APPROVED_TAG, 1_000, 5)
        self.store_score("task", ready)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.READY_APPROVED,
            target_epoch_second=1_001,
            target_suffix=4,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.READY_APPROVED, state.band)
        self.assertEqual(1_001, state.epoch_second)
        self.assertEqual(4, state.suffix)

    def test_rewrite_rejects_suffix_change_without_newer_epoch(self) -> None:
        ready = self.score(self.kernel.READY_APPROVED_TAG, 1_000, 5)
        self.store_score("task", ready)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.READY_APPROVED,
            target_epoch_second=1_000,
            target_suffix=4,
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)

    def test_rewrite_same_band_epoch_preserves_suffix(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_epoch(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_001,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, state.band)
        self.assertEqual(1_001, state.epoch_second)
        self.assertEqual(7, state.suffix)

    def test_rewrite_same_band_epoch_rejects_non_increasing_epoch(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_epoch(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_000,
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)

    def test_rewrite_same_band_epoch_with_suffix_delta_zero_preserves_suffix(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_epoch(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_002,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, state.band)
        self.assertEqual(1_002, state.epoch_second)
        self.assertEqual(7, state.suffix)

    def test_rewrite_same_band_epoch_uses_absolute_target_epoch(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 998, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_epoch(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_002,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(1_002, state.epoch_second)
        self.assertEqual(7, state.suffix)

    def test_rewrite_same_band_epoch_applies_suffix_delta(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_epoch(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_002,
            suffix_delta=-1,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(1_002, state.epoch_second)
        self.assertEqual(6, state.suffix)

    def test_rewrite_same_band_epoch_rejects_suffix_delta_underflow(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 0)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_epoch(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_002,
            suffix_delta=-1,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.INVALID, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(1_000, state.epoch_second)
        self.assertEqual(0, state.suffix)

    def test_rewrite_same_band_epoch_rejects_future_hold(self) -> None:
        paused = self.score(self.kernel.RUNNING_VISIBLE_TAG, 2_000, 7)
        self.store_score("task", paused)

        result = self.kernel.rewrite_same_band_epoch(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_002,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(2_000, state.epoch_second)
        self.assertEqual(7, state.suffix)

    def test_rewrite_rejects_stale_expected_band(self) -> None:
        ready = self.score(self.kernel.READY_APPROVED_TAG, 1_000, 5)
        self.store_score("task", ready)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_001,
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)

    def test_close_score_closes_positive_score(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        terminal = -1_001_00
        self.store_score("task", running)

        result = self.kernel.close_score(
            task_id="task",
            terminal_score=terminal,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.TERMINAL, state.band)
        self.assertEqual(terminal, state.score)

    def test_close_score_overrides_changed_positive_score(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        terminal = -1_001_00
        self.store_score("task", running)
        self.kernel.rewrite_same_band_epoch(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_epoch_second=1_002,
        )

        result = self.kernel.close_score(
            task_id="task",
            terminal_score=terminal,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.TERMINAL, state.band)
        self.assertEqual(terminal, state.score)

    def test_close_score_noops_when_already_terminal(self) -> None:
        terminal = -1_001_00
        self.store_score("task", terminal)

        result = self.kernel.close_score(
            task_id="task",
            terminal_score=-1_002_00,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.NOOP, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(terminal, state.score)

    def test_release_score_lease_preserves_suffix(self) -> None:
        paused = self.score(
            self.kernel.RUNNING_VISIBLE_TAG,
            self.kernel.PAUSE_EPOCH_SECOND,
            4,
        )
        self.store_score("task", paused)

        result = self.kernel.release_score_lease(
            task_id="task",
            observed_lease_score=paused,
            release_epoch_second=1_000,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(1_000, state.epoch_second)
        self.assertEqual(4, state.suffix)

    def test_release_score_lease_rejects_later_epoch(self) -> None:
        held = self.score(self.kernel.RUNNING_VISIBLE_TAG, 2_000, 4)
        self.store_score("task", held)

        result = self.kernel.release_score_lease(
            task_id="task",
            observed_lease_score=held,
            release_epoch_second=2_001,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.INVALID, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(2_000, state.epoch_second)
        self.assertEqual(4, state.suffix)


if __name__ == "__main__":
    unittest.main()
