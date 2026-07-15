from __future__ import annotations

import unittest

from kernel_design.executable_spec import RedisSeedResultRuntime, SeedResult


class FakeRedis:
    def __init__(self) -> None:
        self.lists: dict[str, list[str]] = {}

    def rpush(self, key: str, *values: str) -> int:
        row = self.lists.setdefault(key, [])
        row.extend(values)
        return len(row)

    def lpop(self, key: str) -> str | None:
        row = self.lists.get(key, [])
        return row.pop(0) if row else None

    def pipeline(self, *, transaction: bool) -> FakePipeline:
        return FakePipeline(self, transaction=transaction)


class FakePipeline:
    def __init__(self, redis: FakeRedis, *, transaction: bool) -> None:
        self.redis = redis
        self.transaction = transaction
        self.commands: list[str] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> None:
        pass

    def lpop(self, key: str) -> FakePipeline:
        self.commands.append(key)
        return self

    def execute(self) -> list[str | None]:
        return [self.redis.lpop(key) for key in self.commands]


class RedisSeedResultRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.runtime = RedisSeedResultRuntime(self.redis, prefix="test")
        self.key = "rr:test:seed-results"

    def test_batch_append_and_bounded_consume_are_fifo_on_one_queue(self) -> None:
        first = SeedResult("context-1", "200", '{"value":1}')
        second = SeedResult("context-2", "500")

        self.assertEqual(
            2,
            self.runtime.append_seed_results(results=(first, second)),
        )
        self.assertEqual({self.key}, set(self.redis.lists))
        self.assertEqual(
            (first,),
            self.runtime.consume_seed_results(limit=1),
        )
        self.assertEqual(
            (second,),
            self.runtime.consume_seed_results(limit=10),
        )

    def test_corrupt_envelopes_are_consumed_and_skipped(self) -> None:
        valid = SeedResult("context-1", "200")
        self.redis.lists[self.key] = [
            "{bad-json",
            '{"outcomeCode":"200"}',
            RedisSeedResultRuntime._encode_result(valid),
        ]

        self.assertEqual((valid,), self.runtime.consume_seed_results(limit=3))
        self.assertEqual([], self.redis.lists[self.key])

    def test_empty_append_and_invalid_limit(self) -> None:
        self.assertEqual(0, self.runtime.append_seed_results(results=()))
        self.assertEqual({}, self.redis.lists)
        with self.assertRaises(ValueError):
            self.runtime.consume_seed_results(limit=0)


if __name__ == "__main__":
    unittest.main()
