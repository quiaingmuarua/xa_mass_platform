from __future__ import annotations

import unittest

from kernel_design.executable_spec import (
    RedisTaskScoreBandCore,
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
        self.now_millis = 100_000
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

        if "local idle_park_score" in script:
            return self._eval_try_release_idle_park(key, argv)
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

    def _eval_try_release_idle_park(
        self,
        key: str,
        argv: tuple[object, ...],
    ) -> list[object]:
        task_id = str(argv[0])
        idle_park_score = int(argv[1])
        running_pause_max_score = int(argv[2])
        slot_millis = int(argv[3])
        suffix_factor = int(argv[4])
        running_min = int(argv[5])

        stored = self.zscore(key, task_id)
        if stored is None:
            return ["stale"]
        if stored == idle_park_score:
            now_time_slot = self.now_millis // slot_millis
            next_score = running_min + now_time_slot * suffix_factor
            if not running_min < next_score < idle_park_score:
                return ["invalid", stored]
            self.zadd(key, {task_id: next_score})
            return ["transitioned", next_score]
        if (0 < stored < idle_park_score) or stored > running_pause_max_score:
            return ["noop", stored]
        return ["invalid", stored]

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
        suffix_factor = int(argv[5])

        stored = self.zscore(key, task_id)
        if stored is None:
            return ["stale"]
        if stored < min_expected_score or stored > max_expected_score:
            return ["stale", stored]

        stored_suffix = stored % suffix_factor
        if target_suffix < 0:
            target_suffix = stored_suffix

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
        withscores: bool = False,
    ) -> list[object]:
        rows = sorted(
            (score, member)
            for member, score in self.zsets.get(key, {}).items()
            if min_score <= score <= max_score
        )
        selected = rows[start:] if num is None else rows[start : start + num]
        if withscores:
            return [(member, score) for score, member in selected]
        return [member for _, member in selected]

    def zcount(self, key: str, min_score: int, max_score: int) -> int:
        return sum(
            1
            for score in self.zsets.get(key, {}).values()
            if min_score <= score <= max_score
        )

    def time(self) -> tuple[int, int]:
        return self.now_millis // 1_000, (self.now_millis % 1_000) * 1_000

    @property
    def now_slot(self) -> int:
        return self.now_millis // 100


class RedisTaskScoreBandCoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.kernel = RedisTaskScoreBandCore(self.redis)

    def score(self, tag: int, time_slot: int, suffix: int) -> int:
        return tag * self.kernel.tag_factor + time_slot * 100 + suffix

    def millis(self, time_slot: int) -> int:
        return time_slot * self.kernel.SLOT_MILLIS

    def store_score(self, task_id: str, score: int) -> None:
        self.redis.zadd(self.kernel.score_key, {task_id: score})

    def test_running_capacity_count_excludes_only_private_idle_park(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 999, 5)
        running_current_second = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 1)
        admission_visible = self.score(self.kernel.ADMISSION_VISIBLE_TAG, 999, 5)
        paused = self.score(
            self.kernel.RUNNING_VISIBLE_TAG,
            self.kernel.PAUSE_TIME_SLOT,
            5,
        )
        idle_park = self.score(
            self.kernel.RUNNING_VISIBLE_TAG,
            self.kernel.MAX_TIME_SLOT - 1,
            self.kernel.MAX_SUFFIX,
        )

        self.store_score("running", running)
        self.store_score("running-current-second", running_current_second)
        self.store_score("admission-visible", admission_visible)
        self.store_score("paused", paused)
        self.store_score("idle-park", idle_park)

        self.assertEqual(3, self.kernel.count_running_capacity_tasks())
        self.assertEqual(
            ["running"],
            self.kernel.acquire_dispatch_work_tasks(limit=10),
        )

    def test_acquire_band_candidates_uses_exact_band_and_exclusive_horizon(
        self,
    ) -> None:
        self.store_score(
            "running-before",
            self.score(self.kernel.RUNNING_VISIBLE_TAG, 999, 5),
        )
        self.store_score(
            "running-at-horizon",
            self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 5),
        )
        self.store_score(
            "admission-before",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 999, 5),
        )

        self.assertEqual(
            ["admission-before"],
            self.kernel.acquire_band_task_candidates(
                band=TaskScoreBand.ADMISSION_VISIBLE,
                before_time_millis=self.millis(1_000),
                limit=10,
            ),
        )
        self.assertEqual(
            ["running-before"],
            self.kernel.acquire_band_task_candidates(
                band=TaskScoreBand.RUNNING_VISIBLE,
                before_time_millis=self.millis(1_000),
                limit=10,
            ),
        )

    def test_admission_candidates_sort_bounded_window_by_priority(self) -> None:
        self.store_score(
            "earlier-low-priority",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 998, 99),
        )
        self.store_score(
            "same-slot-low-priority",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 999, 99),
        )
        self.store_score(
            "same-slot-high-priority",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 999, 0),
        )

        self.assertEqual(
            ["same-slot-high-priority", "earlier-low-priority"],
            self.kernel.acquire_band_task_candidates(
                band=TaskScoreBand.ADMISSION_VISIBLE,
                before_time_millis=self.millis(1_000),
                limit=2,
            ),
        )

    def test_admission_candidates_use_time_order_for_window_membership(self) -> None:
        self.store_score(
            "old-priority-99",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 997, 99),
        )
        self.store_score(
            "middle-priority-50",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 998, 50),
        )
        self.store_score(
            "new-priority-0",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 999, 0),
        )

        self.assertEqual(
            ["middle-priority-50", "old-priority-99"],
            self.kernel.acquire_band_task_candidates(
                band=TaskScoreBand.ADMISSION_VISIBLE,
                before_time_millis=self.millis(1_000),
                limit=2,
            ),
        )

    def test_admission_candidates_use_time_then_id_for_equal_priority(self) -> None:
        self.store_score(
            "task-b",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 998, 5),
        )
        self.store_score(
            "task-c",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 999, 5),
        )
        self.store_score(
            "task-a",
            self.score(self.kernel.ADMISSION_VISIBLE_TAG, 999, 5),
        )

        self.assertEqual(
            ["task-b", "task-a", "task-c"],
            self.kernel.acquire_band_task_candidates(
                band=TaskScoreBand.ADMISSION_VISIBLE,
                before_time_millis=self.millis(1_000),
                limit=3,
            ),
        )

    def test_running_candidates_keep_score_order(self) -> None:
        self.store_score(
            "earlier-suffix-99",
            self.score(self.kernel.RUNNING_VISIBLE_TAG, 998, 99),
        )
        self.store_score(
            "later-suffix-0",
            self.score(self.kernel.RUNNING_VISIBLE_TAG, 999, 0),
        )

        self.assertEqual(
            ["earlier-suffix-99", "later-suffix-0"],
            self.kernel.acquire_band_task_candidates(
                band=TaskScoreBand.RUNNING_VISIBLE,
                before_time_millis=self.millis(1_000),
                limit=2,
            ),
        )
    def test_acquire_band_candidates_rejects_terminal_band(self) -> None:
        with self.assertRaises(ValueError):
            self.kernel.acquire_band_task_candidates(
                band=TaskScoreBand.TERMINAL,
                before_time_millis=self.millis(1_000),
                limit=10,
            )

    def test_initialize_score_writes_duration_lease(self) -> None:
        new_result = self.kernel.initialize_score(
            task_id="new",
            suffix=7,
            lease_duration_millis=3_000,
        )
        same_slot_result = self.kernel.initialize_score(
            task_id="new",
            suffix=7,
            lease_duration_millis=3_000,
        )
        invalid_suffix_result = self.kernel.initialize_score(
            task_id="invalid",
            suffix=100,
            lease_duration_millis=3_000,
        )
        invalid_duration_result = self.kernel.initialize_score(
            task_id="invalid-duration",
            suffix=7,
            lease_duration_millis=0,
        )
        states = self.kernel.get_score_states(task_ids=["new"])

        self.assertEqual(0, self.redis.eval_count)
        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, new_result.status)
        self.assertEqual(
            self.score(self.kernel.PRE_REVIEW_TAG, self.redis.now_slot + 30, 7),
            new_result.score,
        )
        self.assertEqual(TaskScoreTransitionStatus.NOOP, same_slot_result.status)
        self.assertEqual(TaskScoreTransitionStatus.INVALID, invalid_suffix_result.status)
        self.assertEqual(
            TaskScoreTransitionStatus.INVALID,
            invalid_duration_result.status,
        )
        self.assertIsNotNone(states["new"])
        self.assertEqual(TaskScoreBand.PRE_REVIEW, states["new"].band)
        self.assertEqual(self.redis.now_millis + 3_000, states["new"].time_millis)
        self.assertEqual(7, states["new"].suffix)

    def test_existing_due_score_cannot_be_reinitialized(self) -> None:
        first = self.kernel.initialize_score(
            task_id="task",
            suffix=1,
            lease_duration_millis=3_000,
        )
        self.redis.now_millis += 3_000 + self.kernel.SLOT_MILLIS
        second = self.kernel.initialize_score(
            task_id="task",
            suffix=1,
            lease_duration_millis=3_000,
        )

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, first.status)
        self.assertEqual(TaskScoreTransitionStatus.NOOP, second.status)
        self.assertEqual(first.score, second.score)

    def test_existing_score_fails_initialization_without_state_interpretation(self) -> None:
        self.store_score(
            "task",
            self.score(self.kernel.PRE_REVIEW_TAG, self.redis.now_slot - 1, 7),
        )

        result = self.kernel.initialize_score(
            task_id="task",
            suffix=1,
            lease_duration_millis=3_000,
        )

        self.assertEqual(TaskScoreTransitionStatus.NOOP, result.status)

    def test_rewrite_allows_downward_lifecycle_jump(self) -> None:
        pre_review = self.score(self.kernel.PRE_REVIEW_TAG, 1_000, 7)
        self.store_score("task", pre_review)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.PRE_REVIEW,
            target_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_001),
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
            target_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_time_millis=self.millis(1_001),
        )

        self.assertEqual(TaskScoreTransitionStatus.INVALID, result.status)

    def test_rewrite_allows_suffix_change_only_with_newer_time(self) -> None:
        admission_visible = self.score(self.kernel.ADMISSION_VISIBLE_TAG, 1_000, 5)
        self.store_score("task", admission_visible)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_time_millis=self.millis(1_001),
            target_suffix=4,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.ADMISSION_VISIBLE, state.band)
        self.assertEqual(self.millis(1_001), state.time_millis)
        self.assertEqual(4, state.suffix)

    def test_rewrite_rejects_suffix_change_without_newer_time(self) -> None:
        admission_visible = self.score(self.kernel.ADMISSION_VISIBLE_TAG, 1_000, 5)
        self.store_score("task", admission_visible)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.ADMISSION_VISIBLE,
            target_time_millis=self.millis(1_000),
            target_suffix=4,
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)

    def test_rewrite_same_band_time_millis_preserves_suffix(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_time_millis(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_001),
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, state.band)
        self.assertEqual(self.millis(1_001), state.time_millis)
        self.assertEqual(7, state.suffix)

    def test_rewrite_same_band_time_millis_rejects_non_increasing_time(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_time_millis(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_000),
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)

    def test_rewrite_same_band_time_millis_preserves_suffix_on_later_time(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_time_millis(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_002),
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, state.band)
        self.assertEqual(self.millis(1_002), state.time_millis)
        self.assertEqual(7, state.suffix)

    def test_rewrite_same_band_time_millis_uses_absolute_target_time(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 998, 7)
        self.store_score("task", running)

        result = self.kernel.rewrite_same_band_time_millis(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_002),
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.millis(1_002), state.time_millis)
        self.assertEqual(7, state.suffix)

    def test_park_observed_idle_task_uses_private_coordinate(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 0)
        self.store_score("task", running)

        result = self.kernel.park_observed_idle_task(
            task_id="task",
            observed_score=running,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(
            self.millis(self.kernel.MAX_TIME_SLOT - 1),
            state.time_millis,
        )
        self.assertEqual(self.kernel.MAX_SUFFIX, state.suffix)

    def test_park_observed_idle_task_requires_suffix_zero(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 7)
        self.store_score("task", running)

        result = self.kernel.park_observed_idle_task(
            task_id="task",
            observed_score=running,
        )

        self.assertEqual(TaskScoreTransitionStatus.INVALID, result.status)

    def test_park_observed_idle_task_rejects_stale_observed_score(self) -> None:
        observed = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 0)
        newer = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_500, 0)
        self.store_score("task", newer)

        result = self.kernel.park_observed_idle_task(
            task_id="task",
            observed_score=observed,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.millis(1_500), state.time_millis)

    def test_try_release_idle_park_releases_exact_private_coordinate(self) -> None:
        park = self.score(
            self.kernel.RUNNING_VISIBLE_TAG,
            self.kernel.MAX_TIME_SLOT - 1,
            self.kernel.MAX_SUFFIX,
        )
        self.store_score("task", park)

        result = self.kernel.try_release_idle_park(task_id="task")
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.redis.now_millis, state.time_millis)
        self.assertEqual(0, state.suffix)

    def test_try_release_idle_park_accepts_scores_outside_running_pause(self) -> None:
        cases = (
            (self.kernel.RUNNING_VISIBLE_TAG, 7),
            (self.kernel.ADMISSION_VISIBLE_TAG, self.kernel.MAX_SUFFIX),
            (self.kernel.PRE_REVIEW_TAG, self.kernel.MAX_SUFFIX),
        )
        for tag, suffix in cases:
            with self.subTest(tag=tag):
                time_slot = (
                    1_000
                    if tag == self.kernel.RUNNING_VISIBLE_TAG
                    else self.kernel.PAUSE_TIME_SLOT
                )
                original = self.score(tag, time_slot, suffix)
                self.store_score("task", original)

                result = self.kernel.try_release_idle_park(task_id="task")

                self.assertEqual(TaskScoreTransitionStatus.NOOP, result.status)
                self.assertEqual(original, self.redis.zscore("task:score", "task"))

    def test_try_release_idle_park_rejects_terminal_and_running_pause(self) -> None:
        protected_scores = (
            self.score(
                self.kernel.RUNNING_VISIBLE_TAG,
                self.kernel.PAUSE_TIME_SLOT,
                0,
            ),
            self.score(
                self.kernel.RUNNING_VISIBLE_TAG,
                self.kernel.PAUSE_TIME_SLOT,
                self.kernel.MAX_SUFFIX,
            ),
            0,
            -1,
        )
        for protected in protected_scores:
            with self.subTest(score=protected):
                self.store_score("task", protected)

                result = self.kernel.try_release_idle_park(task_id="task")

                self.assertEqual(TaskScoreTransitionStatus.INVALID, result.status)
                self.assertEqual(protected, self.redis.zscore("task:score", "task"))

    def test_try_release_idle_park_returns_stale_for_missing_score(self) -> None:
        result = self.kernel.try_release_idle_park(task_id="missing")

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)

    def test_rewrite_same_band_time_millis_rejects_future_hold(self) -> None:
        paused = self.score(self.kernel.RUNNING_VISIBLE_TAG, 2_000, 7)
        self.store_score("task", paused)

        result = self.kernel.rewrite_same_band_time_millis(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_002),
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.millis(2_000), state.time_millis)
        self.assertEqual(7, state.suffix)

    def test_rewrite_rejects_stale_expected_band(self) -> None:
        admission_visible = self.score(self.kernel.ADMISSION_VISIBLE_TAG, 1_000, 5)
        self.store_score("task", admission_visible)

        result = self.kernel.rewrite_score(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_001),
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
        self.kernel.rewrite_same_band_time_millis(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_002),
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

    def test_close_observed_score_closes_exact_positive_score(self) -> None:
        running = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 0)
        terminal = -1_001_00
        self.store_score("task", running)

        result = self.kernel.close_observed_score(
            task_id="task",
            observed_score=running,
            terminal_score=terminal,
        )

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertEqual(
            terminal,
            self.kernel.get_score_states(task_ids=["task"])["task"].score,
        )

    def test_close_observed_score_rejects_stale_positive_score(self) -> None:
        observed = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_000, 0)
        newer = self.score(self.kernel.RUNNING_VISIBLE_TAG, 1_001, 0)
        self.store_score("task", newer)

        result = self.kernel.close_observed_score(
            task_id="task",
            observed_score=observed,
            terminal_score=-1,
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)
        self.assertEqual(
            newer,
            self.kernel.get_score_states(task_ids=["task"])["task"].score,
        )

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

    def test_release_observed_score_hold_preserves_suffix(self) -> None:
        paused = self.score(
            self.kernel.RUNNING_VISIBLE_TAG,
            self.kernel.PAUSE_TIME_SLOT,
            4,
        )
        self.store_score("task", paused)

        result = self.kernel.release_observed_score_hold(
            task_id="task",
            observed_hold_score=paused,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.millis(1_000), state.time_millis)
        self.assertEqual(4, state.suffix)

    def test_release_observed_pre_review_initialization_lease(self) -> None:
        lease = self.score(
            self.kernel.PRE_REVIEW_TAG,
            self.redis.now_slot + 30,
            1,
        )
        self.store_score("task", lease)

        result = self.kernel.release_observed_score_hold(
            task_id="task",
            observed_hold_score=lease,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.redis.now_millis, state.time_millis)
        self.assertEqual(1, state.suffix)

    def test_release_observed_score_hold_rejects_expired_score(self) -> None:
        held = self.score(self.kernel.RUNNING_VISIBLE_TAG, 999, 4)
        self.store_score("task", held)

        result = self.kernel.release_observed_score_hold(
            task_id="task",
            observed_hold_score=held,
        )
        state = self.kernel.get_score_states(task_ids=["task"])["task"]

        self.assertEqual(TaskScoreTransitionStatus.INVALID, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.millis(999), state.time_millis)
        self.assertEqual(4, state.suffix)


if __name__ == "__main__":
    unittest.main()
