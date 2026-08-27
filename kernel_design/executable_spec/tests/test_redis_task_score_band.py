from __future__ import annotations

import unittest

from kernel_design.executable_spec import (
    RedisKeyspace,
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
        self.zrevrange_calls: list[tuple[str, int, int, bool]] = []

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

        if "local pre_review_min" in script:
            return self._eval_start_pre_review(key, argv)
        if "local normal_min_score" in script:
            return self._eval_promote_initial(key, argv)
        if "local idle_park_score" in script:
            return self._eval_try_release_idle_park(key, argv)
        if "local terminal_score" in script:
            return self._eval_close_positive(key, argv)
        if "local observed_score" in script:
            return self._eval_cas_update(key, argv)
        if "local min_expected_score" in script:
            return self._eval_mint_from_range(key, argv)
        raise ValueError("unsupported fake redis script")

    def _eval_start_pre_review(
        self,
        key: str,
        argv: tuple[object, ...],
    ) -> list[object]:
        task_id = str(argv[0])
        observed_score = int(argv[1])
        pre_review_min = int(argv[2])
        pre_review_max = int(argv[3])
        initial_score = int(argv[4])
        stored = self.zscore(key, task_id)
        if stored is None:
            return ["stale"]
        if stored != observed_score:
            return ["stale", stored]
        if not pre_review_min <= stored <= pre_review_max:
            return ["invalid", stored]
        self.zadd(key, {task_id: initial_score})
        return ["transitioned", initial_score]

    def _eval_promote_initial(
        self,
        key: str,
        argv: tuple[object, ...],
    ) -> list[object]:
        task_id = str(argv[0])
        observed_score = int(argv[1])
        normal_min_score = int(argv[2])
        running_min_score = int(argv[3])
        idle_park_score = int(argv[4])
        slot_millis = int(argv[5])
        suffix_factor = int(argv[6])
        stored = self.zscore(key, task_id)
        if stored is None:
            return ["stale"]
        if stored != observed_score:
            return ["stale", stored]
        next_score = running_min_score + (
            self.now_millis // slot_millis
        ) * suffix_factor
        next_score = max(next_score, normal_min_score)
        if next_score >= idle_park_score:
            return ["invalid", stored]
        self.zadd(key, {task_id: next_score})
        return ["transitioned", next_score]

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

    def zrevrange(
        self,
        key: str,
        start: int,
        end: int,
        *,
        withscores: bool = False,
    ) -> list[object]:
        self.zrevrange_calls.append((key, start, end, withscores))
        rows = sorted(
            (
                (score, member)
                for member, score in self.zsets.get(key, {}).items()
            ),
            reverse=True,
        )
        selected = rows[start : end + 1]
        if withscores:
            return [(member, score) for score, member in selected]
        return [member for _, member in selected]

    def zrevrangebyscore(
        self,
        key: str,
        max_score: int,
        min_score: int,
        *,
        start: int = 0,
        num: int | None = None,
        withscores: bool = False,
    ) -> list[object]:
        rows = sorted(
            (
                (score, member)
                for member, score in self.zsets.get(key, {}).items()
                if min_score <= score <= max_score
            ),
            reverse=True,
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
        self.kernel = RedisTaskScoreBandCore(
            self.redis,
            keyspace=RedisKeyspace("test_task_score_unit"),
        )

    def score(self, tag: int, time_slot: int, suffix: int) -> int:
        return tag * self.kernel.tag_factor + time_slot * 100 + suffix

    def millis(self, time_slot: int) -> int:
        return time_slot * self.kernel.SLOT_MILLIS

    def store_score(self, task_id: str, score: int) -> None:
        self.redis.zadd(self.kernel.score_key, {task_id: score})

    def test_preview_score_states_is_one_bounded_descending_read(self) -> None:
        scores = {
            "terminal": -1,
            "initial": self.score(self.kernel.RUNNING_VISIBLE_TAG, 1, 0),
            "normal": self.score(self.kernel.RUNNING_VISIBLE_TAG, 101, 0),
            "review": self.score(self.kernel.PRE_REVIEW_TAG, 3, 4),
        }
        for task_id, score in scores.items():
            self.store_score(task_id, score)

        states = self.kernel.preview_score_states(limit=4)

        self.assertEqual(
            ["review", "normal", "initial", "terminal"],
            [state.task_id for state in states],
        )
        self.assertEqual(
            [
                TaskScoreBand.PRE_REVIEW,
                TaskScoreBand.RUNNING_VISIBLE,
                TaskScoreBand.RUNNING_VISIBLE,
                TaskScoreBand.TERMINAL,
            ],
            [state.band for state in states],
        )
        self.assertEqual(
            [(self.kernel.score_key, 0, 3, True)],
            self.redis.zrevrange_calls,
        )

    def test_preview_score_states_enforces_limit_and_decoding(self) -> None:
        for limit in (0, 101, True, 1.5):
            with self.subTest(limit=limit):
                with self.assertRaises(ValueError):
                    self.kernel.preview_score_states(limit=limit)  # type: ignore[arg-type]
        self.assertEqual([], self.redis.zrevrange_calls)

        self.store_score("invalid", 0)
        with self.assertRaises(ValueError):
            self.kernel.preview_score_states(limit=1)

    def test_initial_and_normal_reads_use_disjoint_ranges_and_order(self) -> None:
        self.store_score(
            "priority-99",
            self.score(self.kernel.RUNNING_VISIBLE_TAG, 1, 0),
        )
        self.store_score(
            "priority-1",
            self.score(self.kernel.RUNNING_VISIBLE_TAG, 99, 0),
        )
        self.store_score(
            "priority-0",
            self.score(self.kernel.RUNNING_VISIBLE_TAG, 100, 0),
        )
        self.store_score(
            "normal",
            self.score(self.kernel.RUNNING_VISIBLE_TAG, 999, 0),
        )

        self.assertEqual(
            ["priority-0", "priority-1", "priority-99"],
            self.kernel.acquire_initial_running_tasks(limit=10),
        )
        self.assertEqual(
            ["normal"],
            self.kernel.acquire_dispatch_work_tasks(limit=10),
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

    def test_start_pre_review_uses_priority_coordinate_without_capacity_policy(
        self,
    ) -> None:
        priority_zero = self.score(self.kernel.PRE_REVIEW_TAG, 1_000, 1)
        priority_99 = self.score(self.kernel.PRE_REVIEW_TAG, 1_001, 1)
        blocked = self.score(self.kernel.PRE_REVIEW_TAG, 1_002, 1)
        self.store_score("priority-0", priority_zero)
        self.store_score("priority-99", priority_99)
        self.store_score("blocked", blocked)

        first = self.kernel.start_observed_pre_review_task(
            task_id="priority-0",
            observed_pre_review_score=priority_zero,
            priority=0,
        )
        second = self.kernel.start_observed_pre_review_task(
            task_id="priority-99",
            observed_pre_review_score=priority_99,
            priority=99,
        )
        third = self.kernel.start_observed_pre_review_task(
            task_id="blocked",
            observed_pre_review_score=blocked,
            priority=50,
        )

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, first.status)
        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, second.status)
        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, third.status)
        self.assertEqual(3, self.kernel.count_running_tasks())
        states = self.kernel.get_score_states(
            task_ids=("priority-0", "priority-99", "blocked"),
        )
        self.assertEqual(10_000, states["priority-0"].time_millis)
        self.assertEqual(100, states["priority-99"].time_millis)
        self.assertEqual(TaskScoreBand.RUNNING_VISIBLE, states["blocked"].band)

    def test_promote_initial_uses_redis_time_and_exact_observation(self) -> None:
        initial = self.score(self.kernel.RUNNING_VISIBLE_TAG, 100, 0)
        self.store_score("task", initial)

        result = self.kernel.promote_observed_initial_task(
            task_id="task",
            observed_initial_score=initial,
        )
        stale = self.kernel.promote_observed_initial_task(
            task_id="task",
            observed_initial_score=initial,
        )
        state = self.kernel.get_score_states(task_ids=("task",))["task"]

        self.assertEqual(TaskScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertEqual(TaskScoreTransitionStatus.STALE, stale.status)
        self.assertEqual(self.redis.now_millis, state.time_millis)
        self.assertEqual(0, state.suffix)

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

    def test_running_pacing_cannot_promote_initial_coordinate(self) -> None:
        initial = self.score(self.kernel.RUNNING_VISIBLE_TAG, 100, 0)
        self.store_score("task", initial)

        result = self.kernel.rewrite_same_band_time_millis(
            task_id="task",
            expected_band=TaskScoreBand.RUNNING_VISIBLE,
            target_time_millis=self.millis(1_002),
        )

        self.assertEqual(TaskScoreTransitionStatus.STALE, result.status)
        self.assertEqual(
            initial,
            self.redis.zscore("xa_mass:test_task_score_unit:task:score", "task"),
        )

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

    def test_park_observed_idle_task_rejects_initial_coordinate(self) -> None:
        initial = self.score(self.kernel.RUNNING_VISIBLE_TAG, 100, 0)
        self.store_score("task", initial)

        result = self.kernel.park_observed_idle_task(
            task_id="task",
            observed_score=initial,
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
                self.assertEqual(
                    original,
                    self.redis.zscore(
                        "xa_mass:test_task_score_unit:task:score",
                        "task",
                    ),
                )

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
                self.assertEqual(
                    protected,
                    self.redis.zscore(
                        "xa_mass:test_task_score_unit:task:score",
                        "task",
                    ),
                )

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
