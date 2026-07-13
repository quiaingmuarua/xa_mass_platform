from __future__ import annotations

import json
import unittest

from kernel_design.py_example import (
    CandidateWorkerEntry,
    RedisTaskDispatchRuntime,
)


class FakeRedis:
    def __init__(self) -> None:
        self.now_millis = 100_000
        self.zsets: dict[str, dict[str, int]] = {}

    def time(self) -> tuple[int, int]:
        return self.now_millis // 1_000, (self.now_millis % 1_000) * 1_000

    def zadd(self, key: str, mapping: dict[str, int]) -> int:
        row = self.zsets.setdefault(key, {})
        before = len(row)
        row.update(mapping)
        return len(row) - before

    def zcount(self, key: str, minimum: str, maximum: str) -> int:
        assert minimum.startswith("(")
        assert maximum == "+inf"
        lower_bound = int(minimum[1:])
        return sum(
            score > lower_bound for score in self.zsets.get(key, {}).values()
        )

    def eval(
        self,
        script: str,
        key_count: int,
        key: str,
        now_millis: int,
        limit: int,
    ) -> list[str]:
        if key_count != 1 or "ZRANGEBYSCORE" not in script:
            raise AssertionError("unexpected candidate consume script")
        row = self.zsets.get(key, {})
        expired = [member for member, score in row.items() if score <= now_millis]
        for member in expired:
            del row[member]
        selected = [
            member
            for member, _ in sorted(row.items(), key=lambda item: item[1])[:limit]
        ]
        for member in selected:
            del row[member]
        return selected


class RedisTaskDispatchRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.runtime = RedisTaskDispatchRuntime(
            self.redis,
            prefix="test",
        )
        self.key = "ad:test:task:task-1:candidate-workers"

    def test_append_stores_batch_expiry_as_zset_score(self) -> None:
        self.runtime.append_candidate_workers(
            task_id="task-1",
            candidate_workers=(
                self._entry("worker-1", 300),
                self._entry("worker-2", 100),
                self._entry("worker-3", 200),
            ),
            expires_at_millis=110_000,
        )

        payloads = [json.loads(raw) for raw in self.redis.zsets[self.key]]
        self.assertEqual(
            [payload["workerId"] for payload in payloads],
            ["worker-1", "worker-2", "worker-3"],
        )
        self.assertEqual(
            self.runtime.candidate_worker_count(task_id="task-1"),
            3,
        )
        self.assertEqual(
            set(self.redis.zsets[self.key].values()),
            {110_000},
        )
        self.assertEqual(
            {payload["workerLeaseScore"] for payload in payloads},
            {100, 200, 300},
        )

    def test_consume_atomically_pops_by_score_and_skips_corrupt_rows(self) -> None:
        self.redis.zsets[self.key] = {
            RedisTaskDispatchRuntime._encode_entry(self._entry("expired", 300)): 99_999,
            "{bad-json": 100_500,
            RedisTaskDispatchRuntime._encode_entry(self._entry("live", 200)): 101_000,
        }

        entries = self.runtime.consume_candidate_workers(
            task_id="task-1",
            limit=3,
        )

        self.assertEqual(
            [entry.worker_id for entry in entries],
            ["live"],
        )
        self.assertEqual(self.redis.zsets[self.key], {})
        self.assertEqual(
            self.runtime.candidate_worker_count(task_id="task-1"),
            0,
        )

    def test_append_refreshes_same_candidate_without_growing_count(self) -> None:
        self.runtime.append_candidate_workers(
            task_id="task-1",
            candidate_workers=(self._entry("worker-1", 300),),
            expires_at_millis=101_000,
        )
        self.runtime.append_candidate_workers(
            task_id="task-1",
            candidate_workers=(self._entry("worker-1", 300),),
            expires_at_millis=102_000,
        )

        self.assertEqual(
            self.runtime.candidate_worker_count(task_id="task-1"),
            1,
        )
        self.assertEqual(
            self.runtime.consume_candidate_workers(task_id="task-1", limit=1),
            (self._entry("worker-1", 300),),
        )

    def test_expired_old_lease_does_not_duplicate_new_live_candidate(self) -> None:
        self.runtime.append_candidate_workers(
            task_id="task-1",
            candidate_workers=(self._entry("worker-1", 300),),
            expires_at_millis=self.redis.now_millis,
        )
        self.runtime.append_candidate_workers(
            task_id="task-1",
            candidate_workers=(self._entry("worker-1", 500),),
            expires_at_millis=self.redis.now_millis + 1_000,
        )

        self.assertEqual(
            self.runtime.candidate_worker_count(task_id="task-1"),
            1,
        )
        self.assertEqual(
            self.runtime.consume_candidate_workers(task_id="task-1", limit=2),
            (self._entry("worker-1", 500),),
        )

    def test_runtime_validates_task_id_and_consume_limit(self) -> None:
        with self.assertRaises(ValueError):
            self.runtime.append_candidate_workers(
                task_id="",
                candidate_workers=(self._entry("worker-1", 100),),
                expires_at_millis=101_000,
            )
        with self.assertRaises(ValueError):
            self.runtime.append_candidate_workers(
                task_id="task-1",
                candidate_workers=(self._entry("worker-1", 100),),
                expires_at_millis=0,
            )
        with self.assertRaises(ValueError):
            self.runtime.consume_candidate_workers(task_id="task-1", limit=0)
        with self.assertRaises(ValueError):
            self.runtime.candidate_worker_count(task_id="")

    @staticmethod
    def _entry(worker_id: str, worker_lease_score: int) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="image-workers",
            worker_lease_score=worker_lease_score,
        )


if __name__ == "__main__":
    unittest.main()
