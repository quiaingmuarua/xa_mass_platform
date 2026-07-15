from __future__ import annotations

import os
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor

try:
    import redis as redis_module
except ImportError:  # pragma: no cover - exercised only without redis-py
    redis_module = None  # type: ignore[assignment]

from kernel_design.executable_spec import (
    RedisTaskItemScoreBandCore,
    TaskItemScoreBand,
    TaskItemScoreTransitionStatus,
)


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run real Redis integration proof",
)
class RedisTaskItemScoreBandIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        assert redis_module is not None
        assert _REDIS_URL is not None
        cls.redis = redis_module.Redis.from_url(_REDIS_URL, decode_responses=False)
        try:
            cls.redis.ping()
        except redis_module.RedisError as error:
            raise unittest.SkipTest(f"real Redis is unavailable: {error}") from error

    def setUp(self) -> None:
        self.prefix = f"item-integration-{uuid.uuid4().hex}"
        self.task_id = "task-1"
        self.core = RedisTaskItemScoreBandCore(
            self.redis,
            prefix=self.prefix,
        )
        self.score_key = self.core._score_key(self.task_id)

    def tearDown(self) -> None:
        self.redis.delete(self.score_key)

    def now_millis(self) -> int:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    def test_real_redis_initializes_and_acquires_due_item_scores(self) -> None:
        now_millis = self.now_millis()
        initialized = self.core.initialize_item_scores(
            task_id=self.task_id,
            initial_due_millis_by_message_id={
                "due": now_millis - 1_000,
                "future": now_millis + 5_000,
            },
            max_retry_times=2,
        )

        observations = self.core.acquire_item_score_candidates(
            task_id=self.task_id,
            limit=10,
        )

        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            initialized["due"].status,
        )
        self.assertEqual(["due"], list(observations))
        self.assertEqual(3, observations["due"][1])
        raw_members = self.redis.zrange(self.score_key, 0, -1)
        self.assertTrue(all(isinstance(member, bytes) for member in raw_members))

    def test_real_redis_exact_cas_allows_one_same_band_writer(self) -> None:
        now_millis = self.now_millis()
        initialized = self.core.initialize_item_scores(
            task_id=self.task_id,
            initial_due_millis_by_message_id={"message-1": now_millis - 1_000},
            max_retry_times=2,
        )
        observed_score = initialized["message-1"].score
        assert observed_score is not None
        target_time_millis = now_millis + 2_000

        def rewrite_once(_: int) -> TaskItemScoreTransitionStatus:
            return self.core.rewrite_observed_item_scores(
                task_id=self.task_id,
                observed_scores={"message-1": observed_score},
                target_time_millis=target_time_millis,
                remaining_budget_delta=-1,
            )["message-1"].status

        with ThreadPoolExecutor(max_workers=2) as executor:
            statuses = list(executor.map(rewrite_once, range(2)))

        self.assertEqual(1, statuses.count(TaskItemScoreTransitionStatus.TRANSITIONED))
        self.assertEqual(1, statuses.count(TaskItemScoreTransitionStatus.STALE))
        state = self.core.get_item_score_states(
            task_id=self.task_id,
            message_ids=["message-1"],
        )["message-1"]
        self.assertIsNotNone(state)
        self.assertEqual(2, state.remaining_budget)

    def test_real_redis_cross_band_uses_target_time_not_active_lease_time(
        self,
    ) -> None:
        now_millis = self.now_millis()
        initialized = self.core.initialize_item_scores(
            task_id=self.task_id,
            initial_due_millis_by_message_id={"message-1": now_millis - 1_000},
            max_retry_times=1,
        )
        observed_score = initialized["message-1"].score
        assert observed_score is not None
        lease_until_millis = now_millis + 10_000
        claimed = self.core.rewrite_observed_item_scores(
            task_id=self.task_id,
            observed_scores={"message-1": observed_score},
            target_time_millis=lease_until_millis,
            remaining_budget_delta=-1,
        )
        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            claimed["message-1"].status,
        )

        failed_at_millis = now_millis + 100
        failed = self.core.promote_item_outcomes(
            task_id=self.task_id,
            message_ids=["message-1"],
            target_band=TaskItemScoreBand.FINAL_FAILED,
            target_time_millis=failed_at_millis,
        )
        state = self.core.get_item_score_states(
            task_id=self.task_id,
            message_ids=["message-1"],
        )["message-1"]

        self.assertEqual(
            TaskItemScoreTransitionStatus.TRANSITIONED,
            failed["message-1"].status,
        )
        self.assertIsNotNone(state)
        self.assertEqual(TaskItemScoreBand.FINAL_FAILED, state.band)
        self.assertEqual(
            failed_at_millis // self.core.SLOT_MILLIS * self.core.SLOT_MILLIS,
            state.time_millis,
        )
        self.assertLess(state.time_millis, lease_until_millis)


if __name__ == "__main__":
    unittest.main()
