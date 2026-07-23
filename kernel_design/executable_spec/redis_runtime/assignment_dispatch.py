from __future__ import annotations

import json
import zlib
from collections.abc import Mapping as MappingABC
from typing import Any, Mapping, Sequence

from ..kernel.assignment_dispatch_runtime import (
    CandidateId,
    CandidateWarmupSchedule,
    CandidateWorkerCache,
    CandidateWorkerEntry,
    DeliverSeed,
    DeliverSeedAppendStatus,
    DeliverSeedRuntime,
)
from ..kernel.task_score_band import TaskId, TimeMillis

_CONSUME_CANDIDATES_SCRIPT = """
local now_millis = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_millis)
local entries = redis.call(
    'ZRANGEBYSCORE', KEYS[1], '(' .. now_millis, '+inf',
    'LIMIT', 0, limit
)
if #entries > 0 then
    redis.call('ZREM', KEYS[1], unpack(entries))
end
return entries
"""

_CONSUME_DELIVER_SEEDS_SCRIPT = """
local results = {}

for index = 1, #ARGV do
    local worker_id = ARGV[index]
    local current = redis.call('HGET', KEYS[1], worker_id)
    if current then
        redis.call('HDEL', KEYS[1], worker_id)
        table.insert(results, worker_id)
        table.insert(results, current)
    end
end

return results
"""


class RedisCandidateWarmupSchedule(CandidateWarmupSchedule):
    """Redis ZSET schedule for disposable candidate-warmup hints."""

    def __init__(
        self,
        redis_client: Any,
        *,
        prefix: str = "default",
    ) -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        self.redis = redis_client
        self.prefix = prefix

    def schedule_candidate_warmups(
        self,
        *,
        task_ids: Sequence[TaskId],
        due_time_millis: TimeMillis,
    ) -> None:
        unique_task_ids = tuple(dict.fromkeys(task_ids))
        if any(not task_id for task_id in unique_task_ids):
            raise ValueError("Task id must be non-empty")
        if due_time_millis <= 0:
            raise ValueError("candidate warmup due time must be positive")
        if not unique_task_ids:
            return
        self.redis.zadd(
            self._warmup_key(),
            {task_id: due_time_millis for task_id in unique_task_ids},
        )

    def consume_due_candidate_warmups(
        self,
        *,
        before_time_millis: TimeMillis,
        limit: int,
    ) -> tuple[TaskId, ...]:
        if before_time_millis <= 0:
            raise ValueError("candidate warmup cutoff must be positive")
        if limit <= 0:
            raise ValueError("candidate warmup limit must be positive")

        raw_task_ids = self.redis.zrangebyscore(
            self._warmup_key(),
            "-inf",
            before_time_millis,
            start=0,
            num=limit,
        )
        if not raw_task_ids:
            return ()

        # Warmup hints are derived evidence. Duplicate consumption is harmless:
        # Worker exact-score lease acquisition remains the concurrency fence.
        self.redis.zrem(self._warmup_key(), *raw_task_ids)
        return tuple(
            raw_task_id.decode("utf-8")
            if isinstance(raw_task_id, bytes)
            else raw_task_id
            for raw_task_id in raw_task_ids
        )

    def _warmup_key(self) -> str:
        return f"ad:{self.prefix}:candidate-warmups"


class RedisCandidateWorkerCache(CandidateWorkerCache):
    """Redis-backed expiring Worker candidate cache."""

    def __init__(
        self,
        redis_client: Any,
        *,
        prefix: str = "default",
    ) -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        self.redis = redis_client
        self.prefix = prefix

    def append_candidate_workers(
        self,
        *,
        candidate_id: CandidateId,
        candidate_workers: Sequence[CandidateWorkerEntry],
        expires_at_millis: TimeMillis,
    ) -> None:
        self._validate_candidate_id(candidate_id)
        if expires_at_millis <= 0:
            raise ValueError("candidate batch expiry must be positive")
        if not candidate_workers:
            return

        now_millis = self._current_time_millis()
        if expires_at_millis <= now_millis:
            raise ValueError("candidate batch expiry must be in the future")

        key = self._candidate_key(candidate_id)
        with self.redis.pipeline(transaction=False) as pipe:
            pipe.zremrangebyscore(key, "-inf", now_millis)
            pipe.zadd(
                key,
                {
                    self._encode_entry(entry): expires_at_millis
                    for entry in candidate_workers
                },
            )
            pipe.execute()

    def candidate_worker_counts(
        self,
        *,
        candidate_ids: Sequence[CandidateId],
    ) -> Mapping[CandidateId, int]:
        unique_candidate_ids = tuple(dict.fromkeys(candidate_ids))
        for candidate_id in unique_candidate_ids:
            self._validate_candidate_id(candidate_id)
        if not unique_candidate_ids:
            return {}

        now_millis = self._current_time_millis()
        with self.redis.pipeline(transaction=False) as pipe:
            for candidate_id in unique_candidate_ids:
                key = self._candidate_key(candidate_id)
                pipe.zremrangebyscore(key, "-inf", now_millis)
                pipe.zcount(key, f"({now_millis}", "+inf")
            results = pipe.execute()

        return {
            candidate_id: int(results[index * 2 + 1])
            for index, candidate_id in enumerate(unique_candidate_ids)
        }

    def consume_candidate_workers(
        self,
        *,
        candidate_id: CandidateId,
        limit: int,
    ) -> tuple[CandidateWorkerEntry, ...]:
        self._validate_candidate_id(candidate_id)
        if limit <= 0:
            raise ValueError("consume limit must be positive")

        raw_entries = self.redis.eval(
            _CONSUME_CANDIDATES_SCRIPT,
            1,
            self._candidate_key(candidate_id),
            self._current_time_millis(),
            limit,
        )
        if raw_entries is None:
            return ()
        if isinstance(raw_entries, (str, bytes)):
            raw_entries = [raw_entries]

        entries: list[CandidateWorkerEntry] = []
        for raw_entry in raw_entries:
            entry = self._decode_entry(raw_entry)
            if entry is not None:
                entries.append(entry)
        return tuple(entries)

    def _candidate_key(self, candidate_id: CandidateId) -> str:
        return f"ad:{self.prefix}:candidate:{candidate_id}:workers"

    def _current_time_millis(self) -> TimeMillis:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    @staticmethod
    def _encode_entry(entry: CandidateWorkerEntry) -> str:
        return json.dumps(
            {
                "workerId": entry.worker_id,
                "workerGroupId": entry.worker_group_id,
                "workerLeaseScore": entry.worker_lease_score,
            },
            sort_keys=True,
            separators=(",", ":"),
        )

    @staticmethod
    def _decode_entry(raw_entry: Any) -> CandidateWorkerEntry | None:
        try:
            text = (
                raw_entry.decode("utf-8")
                if isinstance(raw_entry, bytes)
                else raw_entry
            )
            payload = json.loads(text)
            if not isinstance(payload, MappingABC):
                return None
            if set(payload) != {
                "workerId",
                "workerGroupId",
                "workerLeaseScore",
            }:
                return None
            worker_id = payload["workerId"]
            worker_group_id = payload["workerGroupId"]
            worker_lease_score = payload["workerLeaseScore"]
        except (KeyError, TypeError, ValueError, UnicodeDecodeError):
            return None
        if not isinstance(worker_id, str) or not worker_id:
            return None
        if not isinstance(worker_group_id, str) or not worker_group_id:
            return None
        if (
            isinstance(worker_lease_score, bool)
            or not isinstance(worker_lease_score, int)
            or worker_lease_score <= 0
        ):
            return None
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id=worker_group_id,
            worker_lease_score=worker_lease_score,
        )

    @staticmethod
    def _validate_candidate_id(candidate_id: CandidateId) -> None:
        if not candidate_id:
            raise ValueError("candidate id must be non-empty")


class RedisDeliverSeedRuntime(DeliverSeedRuntime):
    """Redis-backed sharded Worker DeliverSeed mailbox runtime."""

    SHARD_COUNT = 64

    def __init__(
        self,
        redis_client: Any,
        *,
        prefix: str = "default",
    ) -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        self.redis = redis_client
        self.prefix = prefix

    def append_deliver_seeds(
        self,
        *,
        deliver_seeds_by_worker_id: Mapping[str, DeliverSeed],
    ) -> Mapping[str, DeliverSeedAppendStatus]:
        if not deliver_seeds_by_worker_id:
            return {}

        worker_ids = tuple(deliver_seeds_by_worker_id)
        self._validate_worker_ids(worker_ids)
        if any(
            worker_id != seed.worker_id
            for worker_id, seed in deliver_seeds_by_worker_id.items()
        ):
            raise ValueError("DeliverSeed map key must equal seed.worker_id")
        now_millis = self._current_time_millis()
        if any(
            seed.task_item_claim_until_millis <= now_millis
            for seed in deliver_seeds_by_worker_id.values()
        ):
            raise ValueError("DeliverSeed claim deadline must be in the future")

        with self.redis.pipeline(transaction=False) as pipeline:
            for worker_id, seed in deliver_seeds_by_worker_id.items():
                pipeline.hsetnx(
                    self._deliver_seed_key(
                        self._deliver_seed_shard_id(worker_id)
                    ),
                    worker_id,
                    self._encode_deliver_seed(seed),
                )
            inserted = pipeline.execute()

        return {
            worker_id: (
                DeliverSeedAppendStatus.APPENDED
                if was_inserted
                else DeliverSeedAppendStatus.OCCUPIED
            )
            for worker_id, was_inserted in zip(worker_ids, inserted)
        }

    def consume_deliver_seeds(
        self,
        *,
        worker_ids: Sequence[str],
    ) -> Mapping[str, DeliverSeed]:
        if not worker_ids:
            return {}
        self._validate_worker_ids(worker_ids)

        worker_ids_by_shard: dict[int, list[str]] = {}
        for worker_id in worker_ids:
            shard_id = self._deliver_seed_shard_id(worker_id)
            worker_ids_by_shard.setdefault(shard_id, []).append(worker_id)

        shard_ids = tuple(worker_ids_by_shard)
        with self.redis.pipeline(transaction=False) as pipeline:
            for shard_id in shard_ids:
                pipeline.eval(
                    _CONSUME_DELIVER_SEEDS_SCRIPT,
                    1,
                    self._deliver_seed_key(shard_id),
                    *worker_ids_by_shard[shard_id],
                )
            raw_results = pipeline.execute()

        now_millis = self._current_time_millis()
        consumed: dict[str, DeliverSeed] = {}
        for raw_result in raw_results:
            values = self._decode_text_sequence(raw_result)
            for index in range(0, len(values), 2):
                worker_id = values[index]
                seed = self._decode_deliver_seed(values[index + 1])
                if (
                    seed is not None
                    and seed.worker_id == worker_id
                    and seed.task_item_claim_until_millis > now_millis
                ):
                    consumed[worker_id] = seed
        return {
            worker_id: consumed[worker_id]
            for worker_id in worker_ids
            if worker_id in consumed
        }

    def _current_time_millis(self) -> TimeMillis:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    @classmethod
    def _deliver_seed_shard_id(cls, worker_id: str) -> int:
        return zlib.crc32(worker_id.encode("utf-8")) % cls.SHARD_COUNT

    def _deliver_seed_key(self, shard_id: int) -> str:
        return f"ad:{self.prefix}:deliver-seeds:{shard_id:02d}"

    @staticmethod
    def _validate_worker_ids(worker_ids: Sequence[str]) -> None:
        if any(
            not isinstance(worker_id, str) or not worker_id
            for worker_id in worker_ids
        ):
            raise ValueError("Worker ids must be non-empty")
        if len(set(worker_ids)) != len(worker_ids):
            raise ValueError("Worker ids must not contain duplicates")

    @staticmethod
    def _decode_text_sequence(raw_values: Any) -> tuple[str, ...]:
        if raw_values is None:
            return ()
        if isinstance(raw_values, (str, bytes)):
            raw_values = (raw_values,)
        values = tuple(
            value.decode("utf-8") if isinstance(value, bytes) else value
            for value in raw_values
        )
        if len(values) % 2 != 0 or any(
            not isinstance(value, str) for value in values
        ):
            raise RuntimeError("Redis DeliverSeed script returned an invalid response")
        return values

    @staticmethod
    def _encode_deliver_seed(seed: DeliverSeed) -> str:
        return json.dumps(
            {
                "workerId": seed.worker_id,
                "opaqueDeliveryItem": seed.opaque_delivery_item,
                "opaqueResultContext": seed.opaque_result_context,
                "taskItemClaimUntilMillis": seed.task_item_claim_until_millis,
            },
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )

    @staticmethod
    def _decode_deliver_seed(raw_seed: Any) -> DeliverSeed | None:
        try:
            text = raw_seed.decode("utf-8") if isinstance(raw_seed, bytes) else raw_seed
            payload = json.loads(text)
            if not isinstance(payload, MappingABC):
                return None
            worker_id = payload["workerId"]
            opaque_delivery_item = payload["opaqueDeliveryItem"]
            opaque_result_context = payload["opaqueResultContext"]
            task_item_claim_until_millis = payload["taskItemClaimUntilMillis"]
        except (KeyError, TypeError, ValueError, UnicodeDecodeError):
            return None

        if any(
            not isinstance(value, str) or not value
            for value in (
                worker_id,
                opaque_delivery_item,
                opaque_result_context,
            )
        ):
            return None
        if (
            isinstance(task_item_claim_until_millis, bool)
            or not isinstance(task_item_claim_until_millis, int)
            or task_item_claim_until_millis <= 0
        ):
            return None
        return DeliverSeed(
            worker_id=worker_id,
            opaque_delivery_item=opaque_delivery_item,
            opaque_result_context=opaque_result_context,
            task_item_claim_until_millis=task_item_claim_until_millis,
        )
