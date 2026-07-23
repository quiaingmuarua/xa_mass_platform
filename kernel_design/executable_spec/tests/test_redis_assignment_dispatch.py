from __future__ import annotations

import json
import unittest

from kernel_design.executable_spec import (
    CandidateWorkerEntry,
    DeliverSeed,
    DeliverSeedAppendStatus,
    RedisCandidateWorkerCache,
    RedisDeliverSeedRuntime,
)
from kernel_design.executable_spec.redis_runtime.assignment_dispatch import (
    RedisCandidateWarmupSchedule,
)


class FakeRedis:
    def __init__(self) -> None:
        self.now_millis = 100_000
        self.zsets: dict[str, dict[str, int]] = {}
        self.hashes: dict[str, dict[str, str]] = {}
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

    def zrangebyscore(
        self,
        key: str,
        minimum: str,
        maximum: int,
        *,
        start: int,
        num: int,
    ) -> list[str]:
        assert minimum == "-inf"
        return [
            member
            for member, _ in sorted(
                (
                    (member, score)
                    for member, score in self.zsets.get(key, {}).items()
                    if score <= maximum
                ),
                key=lambda item: (item[1], item[0]),
            )[start : start + num]
        ]

    def zrem(self, key: str, *members: str) -> int:
        row = self.zsets.get(key, {})
        removed = 0
        for member in members:
            if member in row:
                del row[member]
                removed += 1
        return removed

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

    def hget(self, key: str, field: str):
        return self.hashes.get(key, {}).get(field)

    def hset(self, key: str, field: str, value: str) -> int:
        row = self.hashes.setdefault(key, {})
        created = field not in row
        row[field] = value
        return int(created)

    def hsetnx(self, key: str, field: str, value: str) -> int:
        row = self.hashes.setdefault(key, {})
        if field in row:
            return 0
        row[field] = value
        return 1

    def hdel(self, key: str, *fields: str) -> int:
        row = self.hashes.get(key, {})
        removed = 0
        for field in fields:
            if field in row:
                del row[field]
                removed += 1
        return removed

    def eval(
        self,
        script: str,
        key_count: int,
        key: str,
        *args: object,
    ) -> list[str]:
        if key_count != 1:
            raise AssertionError("unexpected Redis script key count")
        if "ZRANGEBYSCORE" in script:
            now_millis, limit = args
            row = self.zsets.get(key, {})
            expired = [
                member for member, score in row.items() if score <= now_millis
            ]
            for member in expired:
                del row[member]
            selected = [
                member
                for member, _ in sorted(row.items(), key=lambda item: item[1])[
                    : int(limit)
                ]
            ]
            for member in selected:
                del row[member]
            return selected
        if "HDEL" in script:
            results = []
            for worker_id in map(str, args):
                current = self.hget(key, worker_id)
                if current is not None:
                    self.hdel(key, worker_id)
                    results.extend((worker_id, current))
            return results
        raise AssertionError("unexpected Redis script")


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

    def eval(
        self,
        script: str,
        key_count: int,
        key: str,
        *args: object,
    ) -> FakePipeline:
        self.commands.append(("eval", (script, key_count, key, *args)))
        return self

    def hsetnx(
        self,
        key: str,
        field: str,
        value: str,
    ) -> FakePipeline:
        self.commands.append(("hsetnx", (key, field, value)))
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

    def test_append_deliver_seeds_writes_worker_mailbox_hash(self) -> None:
        seed = DeliverSeed(
            worker_id="worker-1",
            opaque_delivery_item='{"eventCode":"image.resize"}',
            opaque_result_context='{"taskId":"task-1"}',
            task_item_claim_until_millis=103_000,
        )

        result = self.deliver_seed_runtime.append_deliver_seeds(
            deliver_seeds_by_worker_id={"worker-1": seed},
        )

        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.APPENDED},
            result,
        )
        key = "ad:test:deliver-seeds:32"
        payload = json.loads(self.redis.hashes[key]["worker-1"])
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

    def test_consume_deliver_seeds_atomically_pops_requested_mailboxes(self) -> None:
        first = self._deliver_seed("message-1", "worker-1")
        second = self._deliver_seed("message-2", "worker-2")
        self.deliver_seed_runtime.append_deliver_seeds(
            deliver_seeds_by_worker_id={
                "worker-1": first,
                "worker-2": second,
            },
        )

        self.assertEqual(
            {"worker-1": first},
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("worker-1",),
            ),
        )
        self.assertEqual(
            {"worker-2": second},
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("worker-1", "worker-2"),
            ),
        )
        self.assertEqual(
            {},
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("worker-1", "worker-2"),
            ),
        )

        with self.assertRaises(ValueError):
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("",),
            )
        with self.assertRaises(ValueError):
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("worker-1", "worker-1"),
            )

    def test_mailbox_append_never_overwrites_an_occupied_slot(self) -> None:
        first = self._deliver_seed("message-1", "worker-1")
        conflicting = self._deliver_seed("message-2", "worker-1")

        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.APPENDED},
            self.deliver_seed_runtime.append_deliver_seeds(
                deliver_seeds_by_worker_id={"worker-1": first}
            ),
        )
        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.OCCUPIED},
            self.deliver_seed_runtime.append_deliver_seeds(
                deliver_seeds_by_worker_id={"worker-1": first}
            ),
        )
        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.OCCUPIED},
            self.deliver_seed_runtime.append_deliver_seeds(
                deliver_seeds_by_worker_id={"worker-1": conflicting}
            ),
        )
        self.assertEqual(
            {"worker-1": first},
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("worker-1",)
            ),
        )

    def test_mailbox_keeps_expired_or_corrupt_rows_until_consume(self) -> None:
        seed = self._deliver_seed("message-2", "worker-1", 104_000)
        key = "ad:test:deliver-seeds:32"
        expired = self._deliver_seed("message-1", "worker-1")
        expired_value = RedisDeliverSeedRuntime._encode_deliver_seed(expired)
        self.redis.hashes[key] = {"worker-1": expired_value}
        self.redis.now_millis = expired.task_item_claim_until_millis

        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.OCCUPIED},
            self.deliver_seed_runtime.append_deliver_seeds(
                deliver_seeds_by_worker_id={"worker-1": seed}
            ),
        )
        self.assertEqual(expired_value, self.redis.hashes[key]["worker-1"])
        self.assertEqual(
            {},
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("worker-1",)
            ),
        )
        self.redis.hashes[key]["worker-1"] = "{bad-json"
        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.OCCUPIED},
            self.deliver_seed_runtime.append_deliver_seeds(
                deliver_seeds_by_worker_id={"worker-1": seed}
            ),
        )
        self.assertEqual(
            {},
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("worker-1",)
            ),
        )
        self.assertEqual(
            {"worker-1": DeliverSeedAppendStatus.APPENDED},
            self.deliver_seed_runtime.append_deliver_seeds(
                deliver_seeds_by_worker_id={"worker-1": seed}
            ),
        )

    def test_mailbox_sharding_uses_stable_crc32_vectors(self) -> None:
        self.assertEqual(32, RedisDeliverSeedRuntime._deliver_seed_shard_id("worker-1"))
        self.assertEqual(26, RedisDeliverSeedRuntime._deliver_seed_shard_id("worker-2"))
        self.assertEqual(59, RedisDeliverSeedRuntime._deliver_seed_shard_id("shared"))

    def test_consume_drops_expired_corrupt_and_mismatched_mailboxes(self) -> None:
        rows = {
            "worker-1": RedisDeliverSeedRuntime._encode_deliver_seed(
                self._deliver_seed("expired", "worker-1", self.redis.now_millis)
            ),
            "worker-2": "{bad-json",
            "worker-3": RedisDeliverSeedRuntime._encode_deliver_seed(
                self._deliver_seed("mismatch", "other-worker")
            ),
        }
        for worker_id, value in rows.items():
            shard_id = RedisDeliverSeedRuntime._deliver_seed_shard_id(worker_id)
            key = self.deliver_seed_runtime._deliver_seed_key(shard_id)
            self.redis.hset(key, worker_id, value)

        self.assertEqual(
            {},
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=tuple(rows)
            ),
        )
        self.assertTrue(all(not row for row in self.redis.hashes.values()))

    def test_only_one_consumer_obtains_one_worker_mailbox(self) -> None:
        seed = self._deliver_seed("message-1", "worker-1")
        self.deliver_seed_runtime.append_deliver_seeds(
            deliver_seeds_by_worker_id={"worker-1": seed}
        )
        competing_runtime = RedisDeliverSeedRuntime(self.redis, prefix="test")

        self.assertEqual(
            {"worker-1": seed},
            self.deliver_seed_runtime.consume_deliver_seeds(
                worker_ids=("worker-1",)
            ),
        )
        self.assertEqual(
            {},
            competing_runtime.consume_deliver_seeds(worker_ids=("worker-1",)),
        )

    def test_mailbox_rejects_expired_or_mismatched_input(self) -> None:
        with self.assertRaises(ValueError):
            self.deliver_seed_runtime.append_deliver_seeds(
                deliver_seeds_by_worker_id={
                    "worker-1": self._deliver_seed(
                        "expired",
                        "worker-1",
                        self.redis.now_millis,
                    )
                }
            )
        seed = self._deliver_seed("message-1", "worker-1")
        with self.assertRaises(ValueError):
            self.deliver_seed_runtime.append_deliver_seeds(
                deliver_seeds_by_worker_id={"different-worker": seed}
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
        self.assertTrue(
            all(
                set(payload)
                == {"workerId", "workerGroupId", "workerLeaseScore"}
                for payload in payloads
            )
        )

    def test_consume_atomically_pops_by_score_and_skips_corrupt_rows(self) -> None:
        legacy_entry = {
            "workerId": "legacy",
            "workerGroupId": "group-1",
            "endpointManagerId": "endpoint-1",
            "workerLeaseScore": 250,
        }
        self.redis.zsets[self.key] = {
            RedisCandidateWorkerCache._encode_entry(
                self._entry("expired", 300)
            ): 99_999,
            "{bad-json": 100_500,
            json.dumps(legacy_entry): 100_750,
            RedisCandidateWorkerCache._encode_entry(
                self._entry("live", 200)
            ): 101_000,
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
            worker_lease_score=worker_lease_score,
        )

    @staticmethod
    def _deliver_seed(
        message_id: str,
        worker_id: str,
        claim_until_millis: int = 103_000,
    ) -> DeliverSeed:
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
            task_item_claim_until_millis=claim_until_millis,
        )


class RedisCandidateWarmupScheduleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.schedule = RedisCandidateWarmupSchedule(
            self.redis,
            prefix="test",
        )
        self.key = "ad:test:candidate-warmups"

    def test_schedule_deduplicates_and_consume_is_due_ordered_and_bounded(self) -> None:
        self.schedule.schedule_candidate_warmups(
            task_ids=("task-2", "task-1", "task-2"),
            due_time_millis=100_000,
        )
        self.schedule.schedule_candidate_warmups(
            task_ids=("task-3",),
            due_time_millis=101_000,
        )

        self.assertEqual(
            ("task-1",),
            self.schedule.consume_due_candidate_warmups(
                before_time_millis=100_000,
                limit=1,
            ),
        )
        self.assertEqual(
            ("task-2",),
            self.schedule.consume_due_candidate_warmups(
                before_time_millis=100_000,
                limit=10,
            ),
        )
        self.assertEqual({"task-3": 101_000}, self.redis.zsets[self.key])

    def test_empty_and_invalid_schedule_operations(self) -> None:
        self.schedule.schedule_candidate_warmups(
            task_ids=(),
            due_time_millis=100_000,
        )
        self.assertNotIn(self.key, self.redis.zsets)

        for task_ids, due_time_millis in ((('',), 100_000), (("task-1",), 0)):
            with self.subTest(task_ids=task_ids), self.assertRaises(ValueError):
                self.schedule.schedule_candidate_warmups(
                    task_ids=task_ids,
                    due_time_millis=due_time_millis,
                )
        with self.assertRaises(ValueError):
            self.schedule.consume_due_candidate_warmups(
                before_time_millis=100_000,
                limit=0,
            )


if __name__ == "__main__":
    unittest.main()
