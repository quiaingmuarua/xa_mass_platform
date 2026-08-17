from __future__ import annotations

import json
import unittest

from kernel_design.executable_spec import (
    CandidateWorkerEntry,
    DeliveryCommand,
    WorkerCommandAppendStatus,
    WorkerCommandOfferStatus,
    DeliveryEndpoint,
    RedisCandidateWorkerCache,
    RedisWorkerCommandRuntime,
    encode_delivery_command,
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
        self.before_exact_consume = None

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

    def hset(
        self,
        key: str,
        field: str | None = None,
        value: str | None = None,
        *,
        mapping: dict[str, str] | None = None,
    ) -> int:
        row = self.hashes.setdefault(key, {})
        if mapping is not None:
            created = sum(field_name not in row for field_name in mapping)
            row.update(mapping)
            return created
        if field is None or value is None:
            raise TypeError("field and value are required without mapping")
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

    def hrandfield(
        self,
        key: str,
        *,
        count: int,
        withvalues: bool,
    ) -> list[str]:
        assert count > 0
        assert withvalues
        row = self.hashes.get(key, {})
        selected_fields = sorted(row)[:count]
        return [
            value
            for field in selected_fields
            for value in (field, row[field])
        ]

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
        if "HSETNX" in script:
            results = []
            for index in range(0, len(args), 2):
                results.append(
                    self.hsetnx(
                        key,
                        str(args[index]),
                        str(args[index + 1]),
                    )
                )
            return results
        if "current == observed" in script:
            if self.before_exact_consume is not None:
                self.before_exact_consume(key)
            results = []
            for index in range(0, len(args), 2):
                worker_id = str(args[index])
                observed = args[index + 1]
                current = self.hget(key, worker_id)
                if current is not None and current == observed:
                    self.hdel(key, worker_id)
                    results.extend((worker_id, current))
            return results
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
        self.worker_command_runtime = RedisWorkerCommandRuntime(
            self.redis,
            prefix="test",
        )
        self.key = "ad:test:candidate:task-1:workers"

    def test_candidate_and_worker_command_owners_are_separate(self) -> None:
        self.assertFalse(hasattr(self.runtime, "append_worker_commands"))
        self.assertFalse(
            hasattr(self.worker_command_runtime, "append_candidate_workers")
        )

    def test_append_worker_commands_writes_sparse_adapter_hash(self) -> None:
        command = self._worker_command("message-1", "worker-1")

        result = self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={"worker-1": command},
        )

        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.APPENDED},
            result,
        )
        key = (
            "wd:test:endpoint-manager:endpoint-manager-1:worker-commands"
        )
        payload = json.loads(self.redis.hashes[key]["worker-1"])
        self.assertEqual(
            {
                "dst": "WORKER",
                "executeBeforeMillis": 103_000,
                "forward": command.forward,
                "messageType": "test.event",
                "payload": command.payload,
                "src": "TASK",
            },
            payload,
        )

    def test_adapter_buckets_are_isolated(self) -> None:
        first = self._worker_command("message-1", "worker-1")
        second = self._worker_command("message-2", "worker-2")
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={"worker-1": first},
        )
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-2",
            worker_commands_by_worker_id={"worker-2": second},
        )

        self.assertIsNone(
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-2",
                worker_id="worker-1",
            )
        )
        self.assertEqual(
            first,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )
        self.assertEqual(
            second,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-2",
                worker_id="worker-2",
            ),
        )

    def test_mailbox_append_replaces_existing_residue(self) -> None:
        first = self._worker_command("message-1", "worker-1")
        replacement = self._worker_command("message-2", "worker-1")

        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.APPENDED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": first}
            ),
        )
        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.REPLACED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": first}
            ),
        )
        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.REPLACED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": replacement}
            ),
        )
        self.assertEqual(
            replacement,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )

    def test_mailbox_offer_only_fills_empty_worker_slots(self) -> None:
        occupied = self._worker_command("task", "worker-1")
        offered = self._worker_command("direct", "worker-1")
        other = self._worker_command("other", "worker-2")
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={"worker-1": occupied},
        )

        result = self.worker_command_runtime.offer_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={
                "worker-1": offered,
                "worker-2": other,
            },
        )

        self.assertEqual(
            {
                "worker-1": WorkerCommandOfferStatus.OCCUPIED,
                "worker-2": WorkerCommandOfferStatus.OFFERED,
            },
            result,
        )
        self.assertEqual(
            occupied,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )
        self.assertEqual(
            other,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-2",
            ),
        )

    def test_authoritative_append_replaces_an_unconsumed_offer(self) -> None:
        direct = self._worker_command("direct", "worker-1")
        task = self._worker_command("task", "worker-1")
        self.assertEqual(
            {"worker-1": WorkerCommandOfferStatus.OFFERED},
            self.worker_command_runtime.offer_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": direct},
            ),
        )

        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.REPLACED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": task},
            ),
        )
        self.assertEqual(
            task,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )

    def test_mailbox_append_batches_new_fields_and_residue_replacements(
        self,
    ) -> None:
        old_seed = self._worker_command("old-message", "worker-1")
        replacement = self._worker_command("new-message", "worker-1")
        appended = self._worker_command("other-message", "worker-2")
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={"worker-1": old_seed},
        )

        results = self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={
                "worker-1": replacement,
                "worker-2": appended,
            },
        )

        self.assertEqual(
            {
                "worker-1": WorkerCommandAppendStatus.REPLACED,
                "worker-2": WorkerCommandAppendStatus.APPENDED,
            },
            results,
        )
        self.assertEqual(
            replacement,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )
        self.assertEqual(
            appended,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-2",
            ),
        )

    def test_mailbox_replaces_expired_or_corrupt_residue(self) -> None:
        seed = self._worker_command("message-2", "worker-1", 104_000)
        key = (
            "wd:test:endpoint-manager:endpoint-manager-1:worker-commands"
        )
        expired = self._worker_command("message-1", "worker-1")
        expired_value = encode_delivery_command(expired)
        self.redis.hashes[key] = {"worker-1": expired_value}
        self.redis.now_millis = expired.execute_before_millis

        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.REPLACED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": seed}
            ),
        )
        self.assertEqual(
            seed,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )
        self.redis.hashes[key]["worker-1"] = "{bad-json"
        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.REPLACED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": seed}
            ),
        )
        self.assertEqual(
            seed,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )
        self.assertEqual(
            {"worker-1": WorkerCommandAppendStatus.APPENDED},
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={"worker-1": seed}
            ),
        )

    def test_bounded_random_observation_consumes_sparse_batches(self) -> None:
        worker_commands_by_worker_id = {
            f"worker-{index}": self._worker_command(
                f"message-{index}",
                f"worker-{index}",
            )
            for index in range(1, 4)
        }
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id=worker_commands_by_worker_id,
        )

        first_batch = self.worker_command_runtime.consume_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            limit=2,
        )
        second_batch = self.worker_command_runtime.consume_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            limit=2,
        )

        self.assertEqual(
            {
                worker_id: worker_commands_by_worker_id[worker_id]
                for worker_id in ("worker-1", "worker-2")
            },
            first_batch,
        )
        self.assertEqual(
            {"worker-3": worker_commands_by_worker_id["worker-3"]},
            second_batch,
        )

    def test_empty_hash_returns_empty_batch(self) -> None:
        commands = self.worker_command_runtime.consume_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            limit=10,
        )

        self.assertEqual({}, commands)

    def test_consume_drops_expired_and_corrupt_mailboxes(self) -> None:
        rows = {
            "worker-1": encode_delivery_command(
                self._worker_command("expired", "worker-1", self.redis.now_millis)
            ),
            "worker-2": "{bad-json",
        }
        key = self.worker_command_runtime._worker_command_key("endpoint-manager-1")
        for worker_id, value in rows.items():
            self.redis.hset(key, worker_id, value)

        self.assertEqual(
            {},
            self.worker_command_runtime.consume_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                limit=10,
            ),
        )
        self.assertEqual({}, self.redis.hashes[key])

    def test_scanned_value_is_deleted_only_when_it_is_still_current(self) -> None:
        old_seed = self._worker_command("message-1", "worker-1")
        replacement = self._worker_command("message-2", "worker-1")
        key = self.worker_command_runtime._worker_command_key("endpoint-manager-1")
        self.redis.hset(
            key,
            "worker-1",
            encode_delivery_command(old_seed),
        )
        replacement_value = encode_delivery_command(
            replacement
        )
        self.redis.before_exact_consume = (
            lambda current_key: self.redis.hset(
                current_key,
                "worker-1",
                replacement_value,
            )
        )

        commands = self.worker_command_runtime.consume_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            limit=10,
        )

        self.assertEqual({}, commands)
        self.assertEqual(replacement_value, self.redis.hget(key, "worker-1"))

    def test_only_one_consumer_obtains_one_worker_mailbox(self) -> None:
        seed = self._worker_command("message-1", "worker-1")
        self.worker_command_runtime.append_worker_commands(
            endpoint_manager_id="endpoint-manager-1",
            worker_commands_by_worker_id={"worker-1": seed}
        )
        competing_runtime = RedisWorkerCommandRuntime(self.redis, prefix="test")

        self.assertEqual(
            seed,
            self.worker_command_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )
        self.assertIsNone(
            competing_runtime.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            )
        )

    def test_mailbox_rejects_expired_or_invalid_input(self) -> None:
        with self.assertRaises(ValueError):
            self.worker_command_runtime.append_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                worker_commands_by_worker_id={
                    "worker-1": self._worker_command(
                        "expired",
                        "worker-1",
                        self.redis.now_millis,
                    )
                }
            )
        with self.assertRaises(ValueError):
            self.worker_command_runtime.consume_worker_commands(
                endpoint_manager_id="",
                limit=1,
            )
        with self.assertRaises(ValueError):
            self.worker_command_runtime.consume_worker_commands(
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
        self.assertTrue(
            all(
                set(payload)
                == {
                    "workerId",
                    "workerGroupId",
                    "endpointManagerId",
                    "workerLeaseScore",
                }
                for payload in payloads
            )
        )

    def test_consume_atomically_pops_by_score_and_skips_corrupt_rows(self) -> None:
        old_entry_without_route = {
            "workerId": "legacy",
            "workerGroupId": "group-1",
            "workerLeaseScore": 250,
        }
        self.redis.zsets[self.key] = {
            RedisCandidateWorkerCache._encode_entry(
                self._entry("expired", 300)
            ): 99_999,
            "{bad-json": 100_500,
            json.dumps(old_entry_without_route): 100_750,
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
            endpoint_manager_id="endpoint-manager-1",
            worker_lease_score=worker_lease_score,
        )

    @staticmethod
    def _worker_command(
        message_id: str,
        worker_id: str,
        claim_until_millis: int = 103_000,
    ) -> DeliveryCommand:
        return DeliveryCommand.create(
            src=DeliveryEndpoint.TASK,
            dst=DeliveryEndpoint.WORKER,
            message_type="test.event",
            execute_before_millis=claim_until_millis,
            payload=json.dumps(
                {"messageId": message_id},
                sort_keys=True,
                separators=(",", ":"),
            ),
            forward=json.dumps(
                {"taskId": "task-1", "messageId": message_id},
                sort_keys=True,
                separators=(",", ":"),
            ),
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
