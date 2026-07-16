from __future__ import annotations

import unittest

from kernel_design.executable_spec import (
    RedisWorkerScoreCore,
    WorkerScorePolarity,
    WorkerScoreTransitionStatus,
)


class FakePipeline:
    def __init__(self, redis: FakeRedis, transaction: bool = True) -> None:
        self.redis = redis
        self.transaction = transaction
        self.commands: list[tuple[str, tuple[object, ...]]] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def zscore(self, key: str, member: str) -> object:
        if not self.transaction:
            self.commands.append(("zscore", (key, member)))
            return self
        return self.redis.zscore(key, member)

    def eval(self, script: str, numkeys: int, *args: object) -> FakePipeline:
        self.commands.append(("eval", (script, numkeys, *args)))
        return self

    def execute(self) -> list[object]:
        self.redis.pipeline_execute_count += 1
        results: list[object] = []
        for command, args in self.commands:
            if command == "zscore":
                results.append(self.redis.zscore(str(args[0]), str(args[1])))
            elif command == "eval":
                results.append(self.redis.eval(str(args[0]), int(args[1]), *args[2:]))
            else:
                raise ValueError(f"unsupported fake pipeline command: {command}")
        self.commands.clear()
        return results


class FakeRedis:
    def __init__(self) -> None:
        self.zsets: dict[str, dict[str, int]] = {}
        self.now_millis = 100_000
        self.eval_count = 0
        self.pipeline_execute_count = 0

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

        if "local target_min_abs_score" in script and "local target_lane_rank" in script:
            return self._eval_current_rewrite(key, argv)
        if "target_score = abs_score + (1 - stored_dirty)" in script:
            return self._eval_reconcile_online(key, argv)
        if "local stored_dirty" in script:
            return self._eval_mark_lease_dirty(key, argv)
        if "local observed_score" in script:
            return self._eval_cas_update(key, argv)
        raise ValueError("unsupported fake redis script")

    def _eval_current_rewrite(self, key: str, argv: tuple[object, ...]) -> list[object]:
        worker_id = str(argv[0])
        target_min_abs_score = int(argv[1])
        target_lane_rank = int(argv[2])
        slot_factor = int(argv[3])
        dirty_factor = int(argv[4])

        stored = self.zscore(key, worker_id)
        if stored is None:
            return ["stale"]
        abs_score = abs(stored)
        if abs_score <= 0:
            return ["invalid", stored]
        sign = stored // abs_score

        slot_remainder = abs_score % slot_factor
        stored_lane_rank = slot_remainder // dirty_factor
        stored_dirty = slot_remainder % dirty_factor

        if abs_score >= target_min_abs_score:
            return ["stale", stored]
        if target_lane_rank < 0:
            target_lane_rank = stored_lane_rank

        next_score = sign * (
            target_min_abs_score + target_lane_rank * dirty_factor + stored_dirty
        )
        if abs(next_score) <= 0:
            return ["invalid", stored]

        self.zadd(key, {worker_id: next_score})
        return ["transitioned", next_score]

    def _eval_mark_lease_dirty(self, key: str, argv: tuple[object, ...]) -> list[object]:
        worker_id = str(argv[0])
        dirty_factor = int(argv[1])

        stored = self.zscore(key, worker_id)
        if stored is None:
            return ["stale"]
        abs_score = abs(stored)
        if abs_score <= 0:
            return ["invalid", stored]
        sign = stored // abs_score

        stored_dirty = abs_score % dirty_factor

        if stored_dirty == 1:
            return ["noop", stored]

        next_score = sign * (abs_score + 1)
        self.zadd(key, {worker_id: next_score})
        return ["transitioned", next_score]

    def _eval_reconcile_online(
        self,
        key: str,
        argv: tuple[object, ...],
    ) -> list[object]:
        worker_id = str(argv[0])
        dirty_factor = int(argv[1])
        stored = self.zscore(key, worker_id)
        if stored is None:
            return ["stale"]
        abs_score = abs(stored)
        if abs_score <= 0:
            return ["invalid", stored]
        dirty = abs_score % dirty_factor
        if dirty not in {0, 1}:
            return ["invalid", stored]
        if stored > 0 and dirty == 1:
            return ["noop", stored]
        next_score = abs_score + (1 - dirty)
        self.zadd(key, {worker_id: next_score})
        return ["transitioned", next_score]

    def _eval_cas_update(self, key: str, argv: tuple[object, ...]) -> list[object]:
        worker_id = str(argv[0])
        observed_score = int(argv[1])
        next_score = int(argv[2])

        stored = self.zscore(key, worker_id)
        if stored is None:
            return ["stale"]
        if stored != observed_score:
            return ["stale", stored]

        if next_score == stored:
            return ["noop", stored]

        self.zadd(key, {worker_id: next_score})
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
        sliced = rows[start:] if num is None else rows[start : start + num]
        if withscores:
            return [(member, score) for score, member in sliced]
        return [member for _, member in sliced]

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
        sliced = rows[start:] if num is None else rows[start : start + num]
        if withscores:
            return [(member, score) for score, member in sliced]
        return [member for _, member in sliced]

    def time(self) -> tuple[int, int]:
        return self.now_millis // 1_000, (self.now_millis % 1_000) * 1_000

    @property
    def now_slot(self) -> int:
        return self.now_millis // 100


class RedisWorkerScoreCoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.kernel = RedisWorkerScoreCore(
            self.redis,
            recovery_lookback_millis=10_000,
        )
        self.home_bucket_id = "group-a"
        self.score_key = self.kernel._score_key(self.home_bucket_id)

    def score(
        self,
        polarity: WorkerScorePolarity,
        time_slot: int,
        lane_rank: int,
        dirty: int = 0,
    ) -> int:
        return int(polarity) * (
            time_slot * self.kernel.slot_factor
            + lane_rank * self.kernel.dirty_factor
            + dirty
        )

    def millis(self, time_slot: int) -> int:
        return time_slot * self.kernel.SLOT_MILLIS

    def store_score(self, worker_id: str, score: int) -> None:
        self.redis.zadd(self.score_key, {worker_id: score})

    def test_acquire_hot_acquire_candidates_returns_due_positive_scores(self) -> None:
        hot_early = self.score(WorkerScorePolarity.HOT_ACQUIRE, 999, 3, 1)
        hot_now = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_000, 4)
        hot_future = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_001, 1)
        recovery = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 999, 1)

        self.store_score("hot-early", hot_early)
        self.store_score("hot-now", hot_now)
        self.store_score("hot-future", hot_future)
        self.store_score("recovery", recovery)

        self.assertEqual(
            {"hot-early": hot_early},
            self.kernel.acquire_hot_acquire_candidates(
                home_bucket_id=self.home_bucket_id,
                limit=10,
            ),
        )
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["hot-early"],
        )["hot-early"]
        self.assertIsNotNone(state)
        self.assertEqual(3, state.lane_rank)
        self.assertEqual(1, state.dirty)
        self.assertEqual(self.millis(999), state.time_millis)
        self.assertEqual(0, self.redis.eval_count)

    def test_acquire_hot_acquire_candidates_is_bounded_and_read_only(self) -> None:
        for index in range(3):
            self.store_score(
                f"worker-{index}",
                self.score(WorkerScorePolarity.HOT_ACQUIRE, 990 + index, index),
            )

        first = self.kernel.acquire_hot_acquire_candidates(
            home_bucket_id=self.home_bucket_id,
            limit=2,
        )
        second = self.kernel.acquire_hot_acquire_candidates(
            home_bucket_id=self.home_bucket_id,
            limit=2,
        )

        self.assertEqual({"worker-0", "worker-1"}, set(first))
        self.assertEqual(first, second)
        self.assertEqual(0, self.redis.eval_count)

    def test_acquire_recovery_recheck_candidates_uses_recent_window(self) -> None:
        too_old = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 899, 1)
        window_start = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 900, 2)
        middle = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 950, 3)
        current_second = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 1_000, 4)
        future = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 1_001, 1)
        hot = self.score(WorkerScorePolarity.HOT_ACQUIRE, 950, 1)

        self.store_score("too-old", too_old)
        self.store_score("window-start", window_start)
        self.store_score("middle", middle)
        self.store_score("current-second", current_second)
        self.store_score("future", future)
        self.store_score("hot", hot)

        self.assertEqual(
            [("window-start", window_start), ("middle", middle)],
            self.kernel.acquire_recovery_recheck_candidates(
                home_bucket_id=self.home_bucket_id,
                limit=10,
            ),
        )

    def test_initialize_hot_acquire_score_uses_internal_time(self) -> None:
        existing_score = self.score(WorkerScorePolarity.HOT_ACQUIRE, 900, 1)
        self.store_score("existing", existing_score)

        existing = self.kernel.initialize_hot_acquire_score(
            home_bucket_id=self.home_bucket_id,
            worker_id="existing",
            lane_rank=7,
        )
        created = self.kernel.initialize_hot_acquire_score(
            home_bucket_id=self.home_bucket_id,
            worker_id="created",
            lane_rank=7,
        )
        invalid = self.kernel.initialize_hot_acquire_score(
            home_bucket_id=self.home_bucket_id,
            worker_id="invalid",
            lane_rank=100,
        )

        self.assertEqual(WorkerScoreTransitionStatus.NOOP, existing.status)
        self.assertEqual(existing_score, existing.score)
        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, created.status)
        self.assertEqual(
            self.score(WorkerScorePolarity.HOT_ACQUIRE, self.redis.now_slot, 7),
            created.score,
        )
        self.assertEqual(WorkerScoreTransitionStatus.INVALID, invalid.status)

    def test_rewrite_current_scores_preserves_polarity_lane_and_dirty(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 900, 5, 1)
        self.store_score("worker", current)

        result = self.kernel.rewrite_current_scores(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
            target_time_millis=self.millis(950),
        )["worker"]
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.HOT_ACQUIRE, state.polarity)
        self.assertEqual(self.millis(950), state.time_millis)
        self.assertEqual(5, state.lane_rank)
        self.assertEqual(1, state.dirty)

    def test_rewrite_current_scores_can_update_lane_rank(self) -> None:
        current = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 900, 5, 1)
        self.store_score("worker", current)

        result = self.kernel.rewrite_current_scores(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
            target_time_millis=self.millis(950),
            target_lane_rank=8,
        )["worker"]
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(self.millis(950), state.time_millis)
        self.assertEqual(8, state.lane_rank)
        self.assertEqual(1, state.dirty)

    def test_rewrite_current_scores_rejects_lower_time(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 950, 5)
        self.store_score("worker", current)

        result = self.kernel.rewrite_current_scores(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
            target_time_millis=self.millis(949),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.STALE, result.status)
        self.assertEqual(current, result.score)

    def test_rewrite_current_scores_rejects_same_slot_rewrite(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 950, 5)
        self.store_score("worker", current)

        result = self.kernel.rewrite_current_scores(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
            target_time_millis=self.millis(950),
            target_lane_rank=8,
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.STALE, result.status)
        self.assertEqual(current, result.score)

    def test_rewrite_current_scores_return_independent_batch_results(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 900, 5)
        already_rewritten = self.score(WorkerScorePolarity.HOT_ACQUIRE, 950, 6)
        self.store_score("worker-1", current)
        self.store_score("worker-2", already_rewritten)

        results = self.kernel.rewrite_current_scores(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker-1", "worker-2", "missing"],
            target_time_millis=self.millis(950),
        )

        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            results["worker-1"].status,
        )
        self.assertEqual(WorkerScoreTransitionStatus.STALE, results["worker-2"].status)
        self.assertEqual(WorkerScoreTransitionStatus.STALE, results["missing"].status)

    def test_acquire_observed_hot_score_leases_use_exact_score_cas(self) -> None:
        observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 999, 5, 1)
        self.store_score("worker", observed)

        result = self.kernel.acquire_observed_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": observed},
            target_time_millis=self.millis(1_030),
        )["worker"]
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.millis(1_030), state.time_millis)
        self.assertEqual(5, state.lane_rank)
        self.assertEqual(0, state.dirty)

    def test_acquire_observed_hot_score_leases_reject_changed_score(self) -> None:
        observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 999, 5, 0)
        changed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 999, 5, 1)
        self.store_score("worker", changed)

        result = self.kernel.acquire_observed_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": observed},
            target_time_millis=self.millis(1_030),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.STALE, result.status)
        self.assertEqual(changed, result.score)

    def test_acquire_observed_hot_score_leases_reject_non_due_score(self) -> None:
        observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_000, 5, 0)
        self.store_score("worker", observed)

        result = self.kernel.acquire_observed_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": observed},
            target_time_millis=self.millis(1_030),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.STALE, result.status)

    def test_acquire_observed_hot_score_leases_reject_recovery_score(self) -> None:
        observed = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 999, 5, 0)
        self.store_score("worker", observed)

        result = self.kernel.acquire_observed_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": observed},
            target_time_millis=self.millis(1_030),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.INVALID, result.status)

    def test_acquire_observed_hot_score_leases_pipeline_independent_results(
        self,
    ) -> None:
        first_observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 998, 4, 1)
        second_observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 998, 5, 0)
        second_stored = self.score(WorkerScorePolarity.HOT_ACQUIRE, 999, 5, 0)
        recovery_observed = self.score(
            WorkerScorePolarity.RECOVERY_RECHECK,
            998,
            6,
            0,
        )
        self.store_score("worker-1", first_observed)
        self.store_score("worker-2", second_stored)
        self.store_score("worker-3", recovery_observed)
        pipeline_count = self.redis.pipeline_execute_count
        eval_count = self.redis.eval_count

        results = self.kernel.acquire_observed_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={
                "worker-1": first_observed,
                "worker-2": second_observed,
                "worker-3": recovery_observed,
            },
            target_time_millis=self.millis(1_030),
        )

        self.assertEqual(pipeline_count + 1, self.redis.pipeline_execute_count)
        self.assertEqual(eval_count + 2, self.redis.eval_count)
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            results["worker-1"].status,
        )
        self.assertEqual(WorkerScoreTransitionStatus.STALE, results["worker-2"].status)
        self.assertEqual(
            WorkerScoreTransitionStatus.INVALID,
            results["worker-3"].status,
        )
        self.assertEqual(0, abs(results["worker-1"].score) % self.kernel.dirty_factor)

    def test_renew_active_hot_score_leases_extend_clean_active_lease(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_020, 5, 0)
        self.store_score("worker", current)

        result = self.kernel.renew_active_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": current},
            target_time_millis=self.millis(1_040),
        )["worker"]
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(self.millis(1_040), state.time_millis)
        self.assertEqual(5, state.lane_rank)
        self.assertEqual(0, state.dirty)

    def test_renew_active_hot_score_leases_reject_dirty_observed_score(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_020, 5, 1)
        self.store_score("worker", current)

        result = self.kernel.renew_active_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": current},
            target_time_millis=self.millis(1_040),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.STALE, result.status)
        self.assertEqual(current, result.score)

    def test_renew_active_hot_score_leases_reject_due_score(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 999, 5, 0)
        self.store_score("worker", current)

        result = self.kernel.renew_active_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": current},
            target_time_millis=self.millis(1_040),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.STALE, result.status)
        self.assertEqual(current, result.score)

    def test_renew_active_hot_score_leases_reject_recovery_recheck_score(self) -> None:
        current = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 1_020, 5, 0)
        self.store_score("worker", current)

        result = self.kernel.renew_active_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": current},
            target_time_millis=self.millis(1_040),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.INVALID, result.status)

    def test_renew_active_hot_score_leases_use_observed_score_cas(self) -> None:
        observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_020, 5, 0)
        changed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_025, 5, 0)
        self.store_score("worker", observed)
        self.store_score("worker", changed)

        result = self.kernel.renew_active_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": observed},
            target_time_millis=self.millis(1_040),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.STALE, result.status)
        self.assertEqual(changed, result.score)

    def test_renew_active_hot_score_leases_exact_validates_covered_target(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_020, 5, 0)
        self.store_score("worker", current)

        result = self.kernel.renew_active_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": current},
            target_time_millis=self.millis(1_020),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.NOOP, result.status)
        self.assertEqual(current, result.score)

    def test_reconcile_worker_online_sets_dirty_and_preserves_coordinate(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_030, 5, 0)
        self.store_score("worker", current)

        result = self.kernel.reconcile_worker_online(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
        )
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.HOT_ACQUIRE, state.polarity)
        self.assertEqual(self.millis(1_030), state.time_millis)
        self.assertEqual(5, state.lane_rank)
        self.assertEqual(1, state.dirty)

    def test_reconcile_worker_online_flips_negative_and_noops_positive_dirty(self) -> None:
        current = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 1_030, 7, 0)
        self.store_score("worker", current)

        transitioned = self.kernel.reconcile_worker_online(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
        )
        noop = self.kernel.reconcile_worker_online(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
        )
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, transitioned.status)
        self.assertEqual(WorkerScoreTransitionStatus.NOOP, noop.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.HOT_ACQUIRE, state.polarity)
        self.assertEqual(self.millis(1_030), state.time_millis)
        self.assertEqual(7, state.lane_rank)
        self.assertEqual(1, state.dirty)

    def test_reconcile_worker_online_returns_stale_for_missing_score(self) -> None:
        result = self.kernel.reconcile_worker_online(
            home_bucket_id=self.home_bucket_id,
            worker_id="missing",
        )

        self.assertEqual(WorkerScoreTransitionStatus.STALE, result.status)

    def test_mark_observed_worker_leases_offline_uses_exact_clean_hot_fence(self) -> None:
        observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_030, 5, 0)
        self.store_score("worker", observed)

        result = self.kernel.mark_observed_worker_leases_offline(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": observed},
        )["worker"]
        stale = self.kernel.mark_observed_worker_leases_offline(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": observed},
        )["worker"]
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertEqual(WorkerScoreTransitionStatus.STALE, stale.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(abs(observed), abs(state.score))

    def test_mark_observed_worker_leases_offline_rejects_dirty_or_negative(self) -> None:
        dirty = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_030, 5, 1)
        negative = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 1_030, 6, 0)
        self.store_score("dirty", dirty)
        self.store_score("negative", negative)

        results = self.kernel.mark_observed_worker_leases_offline(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"dirty": dirty, "negative": negative},
        )

        self.assertEqual(WorkerScoreTransitionStatus.INVALID, results["dirty"].status)
        self.assertEqual(
            WorkerScoreTransitionStatus.INVALID,
            results["negative"].status,
        )

    def test_renew_active_hot_score_leases_return_independent_batch_results(
        self,
    ) -> None:
        clean = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_020, 4, 0)
        dirty = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_020, 5, 1)
        stale_observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_020, 6, 0)
        stale_stored = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_025, 6, 0)
        self.store_score("worker-1", clean)
        self.store_score("worker-2", dirty)
        self.store_score("worker-3", stale_stored)

        results = self.kernel.renew_active_hot_score_leases(
            home_bucket_id=self.home_bucket_id,
            observed_scores={
                "worker-1": clean,
                "worker-2": dirty,
                "worker-3": stale_observed,
            },
            target_time_millis=self.millis(1_040),
        )

        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            results["worker-1"].status,
        )
        self.assertEqual(WorkerScoreTransitionStatus.STALE, results["worker-2"].status)
        self.assertEqual(WorkerScoreTransitionStatus.STALE, results["worker-3"].status)

    def test_mark_current_lease_dirty_sets_dirty_for_future_score(self) -> None:
        current = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 1_030, 5, 0)
        self.store_score("worker", current)

        result = self.kernel.mark_current_lease_dirty(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
        )
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(self.millis(1_030), state.time_millis)
        self.assertEqual(5, state.lane_rank)
        self.assertEqual(1, state.dirty)

    def test_mark_current_lease_dirty_sets_dirty_for_current_second_score(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_000, 5, 0)
        self.store_score("worker", current)

        result = self.kernel.mark_current_lease_dirty(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
        )
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(1, state.dirty)

    def test_mark_current_lease_dirty_sets_dirty_for_expired_score(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 999, 5, 0)
        self.store_score("worker", current)

        result = self.kernel.mark_current_lease_dirty(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
        )
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(1, state.dirty)

    def test_mark_current_lease_dirty_noops_when_already_dirty(self) -> None:
        current = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_030, 5, 1)
        self.store_score("worker", current)

        result = self.kernel.mark_current_lease_dirty(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
        )

        self.assertEqual(WorkerScoreTransitionStatus.NOOP, result.status)
        self.assertEqual(current, result.score)

    def test_toggle_current_polarity_uses_observed_score_cas(self) -> None:
        observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 950, 5, 1)
        self.store_score("worker", observed)

        result = self.kernel.toggle_current_polarity(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
            observed_score=observed,
            target_lane_rank=8,
        )
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]
        stale = self.kernel.toggle_current_polarity(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
            observed_score=observed,
            target_lane_rank=3,
        )

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(self.millis(950), state.time_millis)
        self.assertEqual(8, state.lane_rank)
        self.assertEqual(1, state.dirty)
        self.assertEqual(WorkerScoreTransitionStatus.STALE, stale.status)
        self.assertEqual(state.score, stale.score)

    def test_exhaust_recovery_recheck_writes_internal_cold_time(self) -> None:
        observed = self.score(WorkerScorePolarity.RECOVERY_RECHECK, 950, 4, 1)
        self.store_score("worker", observed)

        result = self.kernel.exhaust_recovery_recheck(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
            observed_score=observed,
        )
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(self.millis(self.kernel.COLD_TIME_SLOT), state.time_millis)
        self.assertEqual(4, state.lane_rank)
        self.assertEqual(1, state.dirty)
        self.assertEqual(
            [],
            self.kernel.acquire_recovery_recheck_candidates(
                home_bucket_id=self.home_bucket_id,
                limit=10,
            ),
        )

    def test_exhaust_recovery_recheck_rejects_hot_score(self) -> None:
        observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 950, 4)
        self.store_score("worker", observed)

        result = self.kernel.exhaust_recovery_recheck(
            home_bucket_id=self.home_bucket_id,
            worker_id="worker",
            observed_score=observed,
        )

        self.assertEqual(WorkerScoreTransitionStatus.INVALID, result.status)

    def test_release_score_holds_preserve_polarity_lane_and_dirty(self) -> None:
        held = self.score(
            WorkerScorePolarity.RECOVERY_RECHECK,
            self.kernel.PAUSE_TIME_SLOT,
            4,
            1,
        )
        self.store_score("worker", held)

        result = self.kernel.release_score_holds(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": held},
            release_time_millis=self.millis(self.redis.now_slot),
        )["worker"]
        state = self.kernel.get_score_states(
            home_bucket_id=self.home_bucket_id,
            worker_ids=["worker"],
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.TRANSITIONED, result.status)
        self.assertIsNotNone(state)
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(self.millis(self.redis.now_slot), state.time_millis)
        self.assertEqual(4, state.lane_rank)
        self.assertEqual(1, state.dirty)

    def test_release_score_holds_rejects_base_not_below_observed_score(self) -> None:
        held = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_050, 4)
        self.store_score("worker", held)

        result = self.kernel.release_score_holds(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": held},
            release_time_millis=self.millis(1_051),
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.INVALID, result.status)

    def test_release_score_holds_rejects_time_before_current_slot(self) -> None:
        held = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_050, 4)
        self.store_score("worker", held)

        result = self.kernel.release_score_holds(
            home_bucket_id=self.home_bucket_id,
            observed_scores={"worker": held},
            release_time_millis=self.millis(self.redis.now_slot) - 1,
        )["worker"]

        self.assertEqual(WorkerScoreTransitionStatus.INVALID, result.status)

    def test_release_score_holds_return_independent_batch_results(self) -> None:
        first_held = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_050, 4)
        second_observed = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_050, 5)
        second_stored = self.score(WorkerScorePolarity.HOT_ACQUIRE, 1_060, 5)
        self.store_score("worker-1", first_held)
        self.store_score("worker-2", second_stored)

        results = self.kernel.release_score_holds(
            home_bucket_id=self.home_bucket_id,
            observed_scores={
                "worker-1": first_held,
                "worker-2": second_observed,
                "invalid": 0,
            },
            release_time_millis=self.millis(1_000),
        )

        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            results["worker-1"].status,
        )
        self.assertEqual(WorkerScoreTransitionStatus.STALE, results["worker-2"].status)
        self.assertEqual(WorkerScoreTransitionStatus.INVALID, results["invalid"].status)


if __name__ == "__main__":
    unittest.main()
