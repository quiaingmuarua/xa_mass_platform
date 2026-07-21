from __future__ import annotations

import unittest
from collections.abc import Callable

from kernel_design.executable_spec import (
    RedisTaskItemScoreBandCore,
    TaskItemScoreBand,
    TaskItemScoreTransitionStatus,
)


class FakePipeline:
    def __init__(self, redis: FakeRedis, transaction: bool = True) -> None:
        self.redis = redis
        self.transaction = transaction
        self.commands: list[tuple[str, tuple[object, ...], dict[str, object]]] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def zadd(
        self,
        key: str,
        mapping: dict[str, int],
        *,
        nx: bool = False,
    ) -> FakePipeline:
        self.commands.append(("zadd", (key, mapping), {"nx": nx}))
        return self

    def zscore(self, key: str, member: str) -> FakePipeline:
        self.commands.append(("zscore", (key, member), {}))
        return self

    def zrevrangebyscore(
        self,
        key: str,
        max_score: int,
        min_score: int,
        *,
        start: int = 0,
        num: int | None = None,
    ) -> FakePipeline:
        self.commands.append(
            (
                "zrevrangebyscore",
                (key, max_score, min_score),
                {"start": start, "num": num},
            )
        )
        return self

    def zrangebyscore(
        self,
        key: str,
        min_score: int,
        max_score: int,
        *,
        start: int = 0,
        num: int | None = None,
    ) -> FakePipeline:
        self.commands.append(
            (
                "zrangebyscore",
                (key, min_score, max_score),
                {"start": start, "num": num},
            )
        )
        return self

    def eval(self, script: str, numkeys: int, *args: object) -> FakePipeline:
        self.commands.append(("eval", (script, numkeys, *args), {}))
        return self

    def execute(self) -> list[object]:
        self.redis.pipeline_execute_count += 1
        results: list[object] = []
        for command, args, kwargs in self.commands:
            if command == "zadd":
                results.append(
                    self.redis.zadd(
                        str(args[0]),
                        args[1],
                        nx=bool(kwargs["nx"]),
                    )
                )
            elif command == "zscore":
                results.append(self.redis.zscore(str(args[0]), str(args[1])))
            elif command == "zrevrangebyscore":
                results.append(
                    self.redis.zrevrangebyscore(
                        str(args[0]),
                        int(args[1]),
                        int(args[2]),
                        start=int(kwargs["start"]),
                        num=kwargs["num"],
                    )
                )
            elif command == "zrangebyscore":
                results.append(
                    self.redis.zrangebyscore(
                        str(args[0]),
                        int(args[1]),
                        int(args[2]),
                        start=int(kwargs["start"]),
                        num=kwargs["num"],
                    )
                )
            elif command == "eval":
                results.append(
                    self.redis.eval(str(args[0]), int(args[1]), *args[2:])
                )
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
        self.before_next_eval: Callable[[], None] | None = None

    def pipeline(self, transaction: bool = True) -> FakePipeline:
        return FakePipeline(self, transaction)

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

    def zscore(self, key: str, member: str) -> int | None:
        return self.zsets.get(key, {}).get(member)

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
        sliced = rows[start:] if num is None else rows[start : start + num]
        return [member for _, member in sliced]

    def eval(self, script: str, numkeys: int, *args: object) -> list[object]:
        self.eval_count += 1
        if numkeys != 1:
            raise ValueError("unsupported fake Redis script")
        if self.before_next_eval is not None:
            callback = self.before_next_eval
            self.before_next_eval = None
            callback()

        key = str(args[0])
        message_id = str(args[1])
        stored_score = self.zscore(key, message_id)
        if stored_score is None:
            return ["not_found"]
        if "local observed_score" in script:
            observed_score = int(args[2])
            next_score = int(args[3])
            if stored_score != observed_score:
                return ["stale"]
            self.zadd(key, {message_id: next_score})
            return ["transitioned", next_score]
        if "local max_same_band_score_delta" in script:
            target_score = int(args[2])
            max_same_band_score_delta = int(args[3])
            if target_score - stored_score <= max_same_band_score_delta:
                return ["noop", stored_score]
            self.zadd(key, {message_id: target_score})
            return ["transitioned", target_score]
        raise ValueError("unsupported fake Redis script")

    def time(self) -> tuple[int, int]:
        return self.now_millis // 1_000, (self.now_millis % 1_000) * 1_000


class RedisTaskItemScoreBandCoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.core = RedisTaskItemScoreBandCore(self.redis, prefix="test")
        self.task_id = "task-1"
        self.score_key = self.core._score_key(self.task_id)

    def score(self, band: TaskItemScoreBand, time_slot: int, budget: int = 0) -> int:
        tag = self.core._tag_from_band(band)
        assert tag is not None
        return self.core._score(tag, time_slot, budget)

    def millis(self, time_slot: int) -> int:
        return time_slot * self.core.SLOT_MILLIS

    def store(self, message_id: str, score: int) -> None:
        self.redis.zadd(self.score_key, {message_id: score})

    def test_initialize_uses_due_mapping_and_does_not_replace_existing_score(
        self,
    ) -> None:
        first = self.core.initialize_item_scores(
            task_id=self.task_id,
            initial_due_millis_by_message_id={
                "message-1": self.millis(900),
                "message-2": self.millis(950),
            },
            max_retry_times=2,
        )
        second = self.core.initialize_item_scores(
            task_id=self.task_id,
            initial_due_millis_by_message_id={"message-1": self.millis(990)},
            max_retry_times=8,
        )
        states = self.core.get_item_score_states(
            task_id=self.task_id,
            message_ids=["message-1", "message-2"],
        )

        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            first["message-1"].status,
        )
        self.assertEqual(TaskItemScoreTransitionStatus.NOOP, second["message-1"].status)
        self.assertEqual(self.millis(900), states["message-1"].time_millis)
        self.assertEqual(3, states["message-1"].remaining_budget)
        self.assertEqual(0, self.redis.eval_count)

    def test_has_active_items_includes_due_future_and_zero_budget(self) -> None:
        self.store(
            "due",
            self.score(TaskItemScoreBand.ACTIVE, 900, 2),
        )
        self.store(
            "future-zero-budget",
            self.score(TaskItemScoreBand.ACTIVE, 2_000, 0),
        )
        other_key = self.core._score_key("task-final")
        self.redis.zadd(
            other_key,
            {"final": self.score(TaskItemScoreBand.FINAL_SUCCESS, 900)},
        )

        result = self.core.has_active_items(
            task_ids=(self.task_id, "task-final", "missing"),
        )

        self.assertEqual(
            {
                self.task_id: True,
                "task-final": False,
                "missing": False,
            },
            result,
        )
        self.assertEqual(1, self.redis.pipeline_execute_count)

    def test_acquire_returns_due_active_scores_and_remaining_budget_in_score_order(
        self,
    ) -> None:
        self.store("old", self.score(TaskItemScoreBand.ACTIVE, 900, 5))
        self.store("exhausted", self.score(TaskItemScoreBand.ACTIVE, 980, 0))
        self.store("new", self.score(TaskItemScoreBand.ACTIVE, 990, 2))
        self.store("future", self.score(TaskItemScoreBand.ACTIVE, 1_001, 4))
        self.store("failed", self.score(TaskItemScoreBand.FINAL_FAILED, 990))

        observations = self.core.acquire_item_score_candidates(
            task_id=self.task_id,
            limit=10,
        )

        self.assertEqual(["new", "exhausted", "old"], list(observations))
        self.assertEqual(2, observations["new"][1])
        self.assertEqual(0, observations["exhausted"][1])

    def test_has_due_active_items_is_batched_and_ignores_future_and_final(self) -> None:
        self.store("due", self.score(TaskItemScoreBand.ACTIVE, 990, 2))
        self.redis.zadd(
            self.core._score_key("task-2"),
            {"future": self.score(TaskItemScoreBand.ACTIVE, 1_001, 2)},
        )
        self.redis.zadd(
            self.core._score_key("task-3"),
            {"final": self.score(TaskItemScoreBand.FINAL_SUCCESS, 990)},
        )

        self.assertEqual(
            {"task-1": True, "task-2": False, "task-3": False},
            self.core.has_due_active_items(
                task_ids=("task-1", "task-2", "task-3"),
            ),
        )

    def test_rewrite_observed_scores_consumes_or_preserves_budget(self) -> None:
        observed = self.score(TaskItemScoreBand.ACTIVE, 990, 2)
        self.store("message-1", observed)

        claimed = self.core.rewrite_observed_item_scores(
            task_id=self.task_id,
            observed_scores={"message-1": observed},
            target_time_millis=self.millis(1_020),
            remaining_budget_delta=-1,
        )
        claim_score = claimed["message-1"].score
        retried = self.core.rewrite_observed_item_scores(
            task_id=self.task_id,
            observed_scores={"message-1": claim_score},
            target_time_millis=self.millis(1_050),
            remaining_budget_delta=0,
        )
        state = self.core.get_item_score_states(
            task_id=self.task_id,
            message_ids=["message-1"],
        )["message-1"]

        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            claimed["message-1"].status,
        )
        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            retried["message-1"].status,
        )
        self.assertEqual(1, state.remaining_budget)
        self.assertEqual(self.millis(1_050), state.time_millis)

    def test_same_band_rewrite_rejects_stale_score_and_budget_underflow(self) -> None:
        exhausted = self.score(TaskItemScoreBand.ACTIVE, 990, 0)
        self.store("message-1", exhausted)

        underflow = self.core.rewrite_observed_item_scores(
            task_id=self.task_id,
            observed_scores={"message-1": exhausted},
            target_time_millis=self.millis(1_020),
            remaining_budget_delta=-1,
        )
        stale = self.core.rewrite_observed_item_scores(
            task_id=self.task_id,
            observed_scores={
                "message-1": self.score(TaskItemScoreBand.ACTIVE, 900, 1)
            },
            target_time_millis=self.millis(1_020),
            remaining_budget_delta=0,
        )

        self.assertEqual(TaskItemScoreTransitionStatus.INVALID, underflow["message-1"].status)
        self.assertEqual(TaskItemScoreTransitionStatus.STALE, stale["message-1"].status)

    def test_same_band_rewrite_requires_a_later_time_slot(self) -> None:
        observed = self.score(TaskItemScoreBand.ACTIVE, 990, 2)
        self.store("message-1", observed)

        same_slot = self.core.rewrite_observed_item_scores(
            task_id=self.task_id,
            observed_scores={"message-1": observed},
            target_time_millis=self.millis(990),
            remaining_budget_delta=0,
        )
        backward = self.core.rewrite_observed_item_scores(
            task_id=self.task_id,
            observed_scores={"message-1": observed},
            target_time_millis=self.millis(989),
            remaining_budget_delta=0,
        )

        self.assertEqual(TaskItemScoreTransitionStatus.INVALID, same_slot["message-1"].status)
        self.assertEqual(TaskItemScoreTransitionStatus.INVALID, backward["message-1"].status)

    def test_promotion_uses_target_band_time_and_only_moves_to_higher_band(self) -> None:
        active = self.score(TaskItemScoreBand.ACTIVE, 1_200, 0)
        self.store("message-1", active)

        failed = self.core.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=["message-1"],
            target_band=TaskItemScoreBand.FINAL_FAILED,
            target_time_millis=self.millis(900),
        )
        succeeded = self.core.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=["message-1"],
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=self.millis(1_100),
        )
        lower_final = self.core.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=["message-1"],
            target_band=TaskItemScoreBand.FINAL_FAILED,
            target_time_millis=self.millis(1_500),
        )
        state = self.core.get_item_score_states(
            task_id=self.task_id,
            message_ids=["message-1"],
        )["message-1"]

        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            failed["message-1"].status,
        )
        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            succeeded["message-1"].status,
        )
        self.assertEqual(TaskItemScoreTransitionStatus.NOOP, lower_final["message-1"].status)
        self.assertEqual(TaskItemScoreBand.FINAL_SUCCESS, state.band)
        self.assertEqual(self.millis(1_100), state.time_millis)
        self.assertIsNone(state.remaining_budget)

    def test_same_band_rewrite_does_not_block_cross_band_promotion(self) -> None:
        current = self.score(TaskItemScoreBand.ACTIVE, 990, 1)
        newer = self.score(TaskItemScoreBand.ACTIVE, 1_050, 1)
        self.store("message-1", current)
        self.redis.before_next_eval = lambda: self.store("message-1", newer)

        result = self.core.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=["message-1"],
            target_band=TaskItemScoreBand.FINAL_SUCCESS,
            target_time_millis=self.millis(1_000),
        )

        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            result["message-1"].status,
        )
        state = self.core.get_item_score_states(
            task_id=self.task_id,
            message_ids=["message-1"],
        )["message-1"]
        self.assertEqual(TaskItemScoreBand.FINAL_SUCCESS, state.band)

    def test_promotion_reports_missing_score(self) -> None:
        results = self.core.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=["missing"],
            target_band=TaskItemScoreBand.FINAL_FAILED,
            target_time_millis=self.millis(1_000),
        )

        self.assertEqual(TaskItemScoreTransitionStatus.NOT_FOUND, results["missing"].status)

    def test_promotion_rejects_positive_same_band_delta_without_a_band_whitelist(
        self,
    ) -> None:
        active = self.score(TaskItemScoreBand.ACTIVE, 900, 2)
        self.store("message-1", active)

        result = self.core.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=["message-1"],
            target_band=TaskItemScoreBand.ACTIVE,
            target_time_millis=self.millis(1_000),
        )

        self.assertEqual(TaskItemScoreTransitionStatus.NOOP, result["message-1"].status)
        self.assertEqual(active, self.redis.zscore(self.score_key, "message-1"))

    def test_redis_script_is_only_exact_score_cas(self) -> None:
        script = self.core._CAS_UPDATE_SCRIPT

        self.assertIn('redis.call("ZSCORE"', script)
        self.assertIn("stored) ~= observed_score", script)
        self.assertNotIn("suffix", script)
        self.assertNotIn("band", script)
        self.assertNotIn("target_time", script)

    def test_promotion_script_only_compares_encoded_score_distance(self) -> None:
        script = self.core._PROMOTE_CROSS_BAND_SCRIPT

        self.assertIn('redis.call("ZSCORE"', script)
        self.assertIn("target_score - stored_score", script)
        self.assertIn("max_same_band_score_delta", script)
        self.assertNotIn("observed_score", script)
        self.assertNotIn("suffix", script)
        self.assertNotIn("tag", script)
        self.assertNotIn("ACTIVE", script)
        self.assertNotIn("FINAL_FAILED", script)
        self.assertNotIn("FINAL_SUCCESS", script)

    def test_task_scoped_key_shape_is_explicit(self) -> None:
        self.assertEqual("tr:test:task:task-1:item-score", self.score_key)


if __name__ == "__main__":
    unittest.main()
