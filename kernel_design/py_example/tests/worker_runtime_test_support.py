from __future__ import annotations

import unittest

from kernel_design.py_example import (
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisZsetWorkerScoreCore,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerRuntimeStatus,
)


class FakeRedis:
    def __init__(self) -> None:
        self.hashes: dict[str, dict[str, str]] = {}
        self.zsets: dict[str, dict[str, int]] = {}
        self.hmget_calls: list[tuple[str, tuple[str, ...]]] = []
        self.now_millis = 100_000

    def hset(
        self,
        name: str,
        key: str | None = None,
        value: object | None = None,
        mapping: dict[str, object] | None = None,
    ) -> int:
        hash_row = self.hashes.setdefault(name, {})
        before = set(hash_row)
        if mapping is not None:
            for field, mapped_value in mapping.items():
                hash_row[str(field)] = self._stringify(mapped_value)
        else:
            assert key is not None
            hash_row[str(key)] = self._stringify(value)
        return len(set(hash_row) - before)

    def hget(
        self,
        name: str,
        key: str,
    ) -> str | None:
        return self.hashes.get(name, {}).get(key)

    def hmget(
        self,
        name: str,
        keys: list[str],
    ) -> list[str | None]:
        self.hmget_calls.append((name, tuple(keys)))
        hash_row = self.hashes.get(name, {})
        return [hash_row.get(key) for key in keys]

    def hdel(
        self,
        name: str,
        *keys: str,
    ) -> int:
        hash_row = self.hashes.get(name, {})
        removed = 0
        for key in keys:
            if key in hash_row:
                removed += 1
                del hash_row[key]
        return removed

    def zadd(
        self,
        name: str,
        mapping: dict[str, int],
        *,
        nx: bool = False,
    ) -> int:
        zset = self.zsets.setdefault(name, {})
        added = 0
        for member, score in mapping.items():
            if nx and member in zset:
                continue
            if member not in zset:
                added += 1
            zset[member] = score
        return added

    def eval(self, script: str, numkeys: int, *args: object) -> list[object]:
        if numkeys != 1:
            raise ValueError("unsupported fake redis script")
        key = str(args[0])
        if "local observed_score" not in script:
            raise ValueError("unsupported fake redis script")
        worker_id = str(args[1])
        observed_score = int(args[2])
        next_score = int(args[3])
        stored = self.zscore(key, worker_id)
        if stored is None:
            return ["stale"]
        if stored != observed_score:
            return ["stale", stored]
        self.zadd(key, {worker_id: next_score})
        return ["transitioned", next_score]

    def zscore(self, name: str, member: str) -> int | None:
        return self.zsets.get(name, {}).get(member)

    def zrangebyscore(
        self,
        name: str,
        min_score: int,
        max_score: int,
        *,
        start: int = 0,
        num: int | None = None,
        withscores: bool = False,
    ) -> list[object]:
        rows = sorted(
            (score, member)
            for member, score in self.zsets.get(name, {}).items()
            if min_score <= score <= max_score
        )
        sliced = rows[start:] if num is None else rows[start : start + num]
        if withscores:
            return [(member, score) for score, member in sliced]
        return [member for _, member in sliced]

    def time(self) -> tuple[int, int]:
        return self.now_millis // 1_000, (self.now_millis % 1_000) * 1_000

    @staticmethod
    def _stringify(value: object) -> str:
        if isinstance(value, bytes):
            return value.decode("utf-8")
        return "" if value is None else str(value)


class WorkerRuntimeRedisFixture(unittest.TestCase):
    LANE_RANK = 5

    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.catalog = RedisWorkerResourceCatalog(self.redis, prefix="test")
        self.score_band = RedisZsetWorkerScoreCore(
            self.redis,
            score_key_prefix="wr:test:score",
        )
        self.runtime = RedisWorkerRuntime(
            self.redis,
            self.score_band,
            prefix="test",
        )
        self.group = WorkerGroupDescriptor(
            worker_group_id="image-workers",
            attributes={"kind": "image"},
            event_codes=frozenset({"resize"}),
        )

    def register_group(self) -> None:
        result = self.catalog.register_worker_group_descriptor(descriptor=self.group)
        self.assertEqual(result.status, WorkerRuntimeStatus.OK)

    def worker_descriptor(
        self,
        worker_id: str,
        *,
        worker_group_id: str = "image-workers",
        endpoint_manager_id: str = "endpoint-manager-1",
        system_metadata: dict[str, object] | None = None,
        static_attributes: dict[str, object] | None = None,
        dynamic_attribute_names: frozenset[str] = frozenset({"battery"}),
    ) -> WorkerDescriptor:
        return WorkerDescriptor(
            worker_id=worker_id,
            worker_group_id=worker_group_id,
            endpoint_manager_id=endpoint_manager_id,
            system_metadata=system_metadata or {},
            static_attributes=static_attributes or {},
            dynamic_attribute_names=dynamic_attribute_names,
        )

    def register_worker(
        self,
        descriptor: WorkerDescriptor,
    ) -> None:
        result = self.runtime.register_worker_descriptor(
            descriptor=descriptor,
            lane_rank=self.LANE_RANK,
        )
        self.assertEqual(result.status, WorkerRuntimeStatus.OK)
