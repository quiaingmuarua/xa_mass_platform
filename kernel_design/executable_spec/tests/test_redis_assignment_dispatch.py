from __future__ import annotations

import json
import unittest

from kernel_design.executable_spec import (
    CandidateWorkerEntry,
    DeliverSeed,
    RedisCandidateWorkerCache,
    RedisDeliverSeedRuntime,
)


class FakeRedis:
    def __init__(self) -> None:
        self.now_millis = 100_000
        self.zsets: dict[str, dict[str, int]] = {}
        self.lists: dict[str, list[str]] = {}

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

    def zremrangebyscore(self, key: str, minimum: str, maximum: int) -> int:
        assert minimum == "-inf"
        row = self.zsets.get(key, {})
        expired = [member for member, score in row.items() if score <= maximum]
        for member in expired:
            del row[member]
        return len(expired)

    def pipeline(self, *, transaction: bool) -> FakePipeline:
        return FakePipeline(self, transaction=transaction)

    def rpush(self, key: str, *values: str) -> int:
        row = self.lists.setdefault(key, [])
        row.extend(values)
        return len(row)

    def lpop(self, key: str, count: int | None = None):
        row = self.lists.get(key, [])
        if not row:
            return None
        if count is None:
            return row.pop(0)
        values = row[:count]
        del row[:count]
        return values

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


class FakePipeline:
    def __init__(self, redis: FakeRedis, *, transaction: bool) -> None:
        self.redis = redis
        self.transaction = transaction
        self.commands: list[tuple[str, tuple[object, ...]]] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> None:
        pass

    def zremrangebyscore(
        self,
        key: str,
        minimum: str,
        maximum: int,
    ) -> FakePipeline:
        self.commands.append(("zremrangebyscore", (key, minimum, maximum)))
        return self

    def zadd(self, key: str, mapping: dict[str, int]) -> FakePipeline:
        self.commands.append(("zadd", (key, mapping)))
        return self

    def zcount(
        self,
        key: str,
        minimum: str,
        maximum: str,
    ) -> FakePipeline:
        self.commands.append(("zcount", (key, minimum, maximum)))
        return self

    def lpop(self, key: str) -> FakePipeline:
        self.commands.append(("lpop", (key,)))
        return self

    def execute(self) -> list[object]:
        return [
            getattr(self.redis, method)(*args)
            for method, args in self.commands
        ]


class RedisCandidateWorkerCacheTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.runtime = RedisCandidateWorkerCache(
            self.redis,
            prefix="test",
        )
        self.deliver_seed_runtime = RedisDeliverSeedRuntime(
            self.redis,
            prefix="test",
        )
        self.key = "ad:test:candidate:task-1:workers"

    def test_candidate_and_deliver_seed_owners_are_separate(self) -> None:
        self.assertFalse(hasattr(self.runtime, "append_deliver_seeds"))
        self.assertFalse(
            hasattr(self.deliver_seed_runtime, "append_candidate_workers")
        )

    def test_append_deliver_seeds_writes_endpoint_manager_list(self) -> None:
        seed = DeliverSeed(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"image.resize"}',
            opaque_result_context='{"taskId":"task-1"}',
            task_item_claim_until_millis=103_000,
        )

        self.deliver_seed_runtime.append_deliver_seeds(
            endpoint_manager_id="endpoint-manager-1",
            deliver_seeds=(seed,),
        )

        key = "ad:test:endpoint-manager:endpoint-manager-1:deliver-seeds"
        payload = json.loads(self.redis.lists[key][0])
        self.assertEqual(
            {
                "workerId": "worker-1",
                "opaqueDeliveryItem": '{"eventCode":"image.resize"}',
                "opaqueResultContext": '{"taskId":"task-1"}',
                "taskItemClaimUntilMillis": 103_000,
            },
            payload,
        )
        self.assertTrue(
            {
                "taskId",
                "selectedWorkerId",
                "workerGroupId",
                "endpointManagerId",
                "taskItem",
                "claimScore",
                "workerLeaseScore",
            }.isdisjoint(payload)
        )

    def test_consume_deliver_seeds_atomically_pops_one_endpoint_queue(self) -> None:
        first = self._deliver_seed("message-1", "worker-1")
        second = self._deliver_seed("message-2", "worker-2")
        self.deliver_seed_runtime.append_deliver_seeds(
            endpoint_manager_id="endpoint-manager-1",
            deliver_seeds=(first, second),
        )

        self.assertEqual(
            (first,),
            self.deliver_seed_runtime.consume_deliver_seeds(
                endpoint_manager_id="endpoint-manager-1",
                limit=1,
            ),
        )
        self.assertEqual(
            (second,),
            self.deliver_seed_runtime.consume_deliver_seeds(
                endpoint_manager_id="endpoint-manager-1",
                limit=10,
            ),
        )
        self.assertEqual(
            (),
            self.deliver_seed_runtime.consume_deliver_seeds(
                endpoint_manager_id="endpoint-manager-1",
                limit=10,
            ),
        )

        with self.assertRaises(ValueError):
            self.deliver_seed_runtime.consume_deliver_seeds(
                endpoint_manager_id="",
                limit=1,
            )
        with self.assertRaises(ValueError):
            self.deliver_seed_runtime.consume_deliver_seeds(
                endpoint_manager_id="endpoint-manager-1",
                limit=0,
            )

    def test_append_stores_batch_expiry_as_zset_score(self) -> None:
        self.runtime.append_candidate_workers(
            candidate_id="task-1",
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
            self.runtime.candidate_worker_counts(candidate_ids=("task-1",))[
                "task-1"
            ],
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
        self.assertEqual(
            {payload["endpointManagerId"] for payload in payloads},
            {"endpoint-manager-1"},
        )

    def test_consume_atomically_pops_by_score_and_skips_corrupt_rows(self) -> None:
        self.redis.zsets[self.key] = {
            RedisCandidateWorkerCache._encode_entry(self._entry("expired", 300)): 99_999,
            "{bad-json": 100_500,
            RedisCandidateWorkerCache._encode_entry(self._entry("live", 200)): 101_000,
        }

        entries = self.runtime.consume_candidate_workers(
            candidate_id="task-1",
            limit=3,
        )

        self.assertEqual(
            [entry.worker_id for entry in entries],
            ["live"],
        )
        self.assertEqual(self.redis.zsets[self.key], {})
        self.assertEqual(
            self.runtime.candidate_worker_counts(candidate_ids=("task-1",))[
                "task-1"
            ],
            0,
        )

    def test_append_refreshes_same_candidate_without_growing_count(self) -> None:
        self.runtime.append_candidate_workers(
            candidate_id="task-1",
            candidate_workers=(self._entry("worker-1", 300),),
            expires_at_millis=101_000,
        )
        self.runtime.append_candidate_workers(
            candidate_id="task-1",
            candidate_workers=(self._entry("worker-1", 300),),
            expires_at_millis=102_000,
        )

        self.assertEqual(
            self.runtime.candidate_worker_counts(candidate_ids=("task-1",))[
                "task-1"
            ],
            1,
        )
        self.assertEqual(
            self.runtime.consume_candidate_workers(candidate_id="task-1", limit=1),
            (self._entry("worker-1", 300),),
        )

    def test_expired_old_lease_does_not_duplicate_new_live_candidate(self) -> None:
        self.redis.zsets[self.key] = {
            RedisCandidateWorkerCache._encode_entry(
                self._entry("worker-1", 300)
            ): self.redis.now_millis,
        }
        self.runtime.append_candidate_workers(
            candidate_id="task-1",
            candidate_workers=(self._entry("worker-1", 500),),
            expires_at_millis=self.redis.now_millis + 1_000,
        )

        self.assertEqual(
            self.runtime.candidate_worker_counts(candidate_ids=("task-1",))[
                "task-1"
            ],
            1,
        )
        self.assertEqual(
            self.runtime.consume_candidate_workers(candidate_id="task-1", limit=2),
            (self._entry("worker-1", 500),),
        )

    def test_batch_count_cleans_expired_members_for_each_task(self) -> None:
        second_key = "ad:test:candidate:task-2:workers"
        self.redis.zsets[self.key] = {
            RedisCandidateWorkerCache._encode_entry(
                self._entry("expired-1", 100)
            ): self.redis.now_millis,
            RedisCandidateWorkerCache._encode_entry(
                self._entry("live-1", 200)
            ): self.redis.now_millis + 1_000,
        }
        self.redis.zsets[second_key] = {
            RedisCandidateWorkerCache._encode_entry(
                self._entry("expired-2", 300)
            ): self.redis.now_millis - 1,
        }

        counts = self.runtime.candidate_worker_counts(
            candidate_ids=("task-1", "task-2", "task-1"),
        )

        self.assertEqual(counts, {"task-1": 1, "task-2": 0})
        self.assertEqual(len(self.redis.zsets[self.key]), 1)
        self.assertEqual(self.redis.zsets[second_key], {})

    def test_cache_validates_candidate_id_and_consume_limit(self) -> None:
        with self.assertRaises(ValueError):
            self.runtime.append_candidate_workers(
                candidate_id="",
                candidate_workers=(self._entry("worker-1", 100),),
                expires_at_millis=101_000,
            )
        with self.assertRaises(ValueError):
            self.runtime.append_candidate_workers(
                candidate_id="task-1",
                candidate_workers=(self._entry("worker-1", 100),),
                expires_at_millis=0,
            )
        with self.assertRaises(ValueError):
            self.runtime.append_candidate_workers(
                candidate_id="task-1",
                candidate_workers=(self._entry("worker-1", 100),),
                expires_at_millis=self.redis.now_millis,
            )
        with self.assertRaises(ValueError):
            self.runtime.consume_candidate_workers(candidate_id="task-1", limit=0)
        with self.assertRaises(ValueError):
            self.runtime.candidate_worker_counts(candidate_ids=("",))

    @staticmethod
    def _entry(worker_id: str, worker_lease_score: int) -> CandidateWorkerEntry:
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id="image-workers",
            endpoint_manager_id="endpoint-manager-1",
            worker_lease_score=worker_lease_score,
        )

    @staticmethod
    def _deliver_seed(message_id: str, worker_id: str) -> DeliverSeed:
        return DeliverSeed(
            worker_id=worker_id,
            opaque_delivery_item=json.dumps(
                {"messageId": message_id},
                sort_keys=True,
                separators=(",", ":"),
            ),
            opaque_result_context=json.dumps(
                {"taskId": "task-1", "messageId": message_id},
                sort_keys=True,
                separators=(",", ":"),
            ),
            task_item_claim_until_millis=103_000,
        )


if __name__ == "__main__":
    unittest.main()
