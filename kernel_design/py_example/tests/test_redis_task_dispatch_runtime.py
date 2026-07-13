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
        self.lists: dict[str, list[str]] = {}

    def time(self) -> tuple[int, int]:
        return self.now_millis // 1_000, (self.now_millis % 1_000) * 1_000

    def rpush(self, key: str, *values: object) -> int:
        row = self.lists.setdefault(key, [])
        row.extend(str(value) for value in values)
        return len(row)

    def eval(
        self,
        script: str,
        key_count: int,
        key: str,
        limit: int,
    ) -> list[str]:
        if key_count != 1:
            raise AssertionError("runtime scripts must own exactly one Redis key")
        if "LPOP" not in script:
            raise AssertionError("only atomic consume requires Lua")
        row = self.lists.get(key, [])
        popped = row[:limit]
        del row[:limit]
        return popped

    def llen(self, key: str) -> int:
        return len(self.lists.get(key, ()))

    def lpop(self, key: str, count: int) -> list[str] | None:
        row = self.lists.get(key)
        if not row:
            return None
        popped = row[:count]
        del row[:count]
        return popped


class RedisTaskDispatchRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.runtime = RedisTaskDispatchRuntime(
            self.redis,
            prefix="test",
        )
        self.key = "ad:test:task:task-1:candidate-workers"

    def test_append_stores_every_supplied_entry_and_exposes_size(self) -> None:
        self.runtime.append_candidate_workers(
            task_id="task-1",
            candidate_workers=(
                self._entry("worker-1", 99_000),
                self._entry("worker-2", 102_000),
                self._entry("worker-3", 103_000),
            ),
        )

        payloads = [json.loads(raw) for raw in self.redis.lists[self.key]]
        self.assertEqual(
            [payload["workerId"] for payload in payloads],
            ["worker-1", "worker-2", "worker-3"],
        )
        self.assertEqual(
            self.runtime.candidate_worker_count(task_id="task-1"),
            3,
        )

    def test_consume_atomically_pops_and_filters_expired_or_corrupt_rows(self) -> None:
        self.redis.lists[self.key] = [
            RedisTaskDispatchRuntime._encode_entry(self._entry("expired", 99_999)),
            "{bad-json",
            RedisTaskDispatchRuntime._encode_entry(self._entry("live", 101_000)),
        ]

        entries = self.runtime.consume_candidate_workers(
            task_id="task-1",
            limit=3,
        )

        self.assertEqual([entry.worker_id for entry in entries], ["live"])
        self.assertEqual(self.redis.lists[self.key], [])
        self.assertEqual(
            self.runtime.candidate_worker_count(task_id="task-1"),
            0,
        )

    def test_runtime_validates_task_id_and_consume_limit(self) -> None:
        with self.assertRaises(ValueError):
            self.runtime.append_candidate_workers(
                task_id="",
                candidate_workers=(self._entry("worker-1", 101_000),),
            )
        with self.assertRaises(ValueError):
            self.runtime.consume_candidate_workers(task_id="task-1", limit=0)
        with self.assertRaises(ValueError):
            self.runtime.candidate_worker_count(task_id="")

    @staticmethod
    def _entry(worker_id: str, expires_at_millis: int) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="image-workers",
            observed_worker_score=123,
            expires_at_millis=expires_at_millis,
        )


if __name__ == "__main__":
    unittest.main()
