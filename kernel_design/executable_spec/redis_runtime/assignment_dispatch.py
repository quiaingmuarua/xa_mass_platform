from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC
from typing import Any, Mapping, Sequence

from ..assignment_dispatch.runtime import (
    AssignmentDispatchRuntime,
    CandidateWorkerEntry,
    DeliverSeed,
    DeliverSeedRuntime,
)
from ..kernel.task_score_band import TaskId, TimeMillis
from ..kernel.worker_runtime import EndpointManagerId

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


class RedisAssignmentDispatchRuntime(AssignmentDispatchRuntime):
    """Redis-backed Task-local candidate Worker runtime."""

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
        task_id: TaskId,
        candidate_workers: Sequence[CandidateWorkerEntry],
        expires_at_millis: TimeMillis,
    ) -> None:
        self._validate_task_id(task_id)
        if expires_at_millis <= 0:
            raise ValueError("candidate batch expiry must be positive")
        if not candidate_workers:
            return

        now_millis = self._current_time_millis()
        if expires_at_millis <= now_millis:
            raise ValueError("candidate batch expiry must be in the future")

        key = self._candidate_key(task_id)
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
        task_ids: Sequence[TaskId],
    ) -> Mapping[TaskId, int]:
        unique_task_ids = tuple(dict.fromkeys(task_ids))
        for task_id in unique_task_ids:
            self._validate_task_id(task_id)
        if not unique_task_ids:
            return {}

        now_millis = self._current_time_millis()
        with self.redis.pipeline(transaction=False) as pipe:
            for task_id in unique_task_ids:
                key = self._candidate_key(task_id)
                pipe.zremrangebyscore(key, "-inf", now_millis)
                pipe.zcount(key, f"({now_millis}", "+inf")
            results = pipe.execute()

        return {
            task_id: int(results[index * 2 + 1])
            for index, task_id in enumerate(unique_task_ids)
        }

    def consume_candidate_workers(
        self,
        *,
        task_id: TaskId,
        limit: int,
    ) -> tuple[CandidateWorkerEntry, ...]:
        self._validate_task_id(task_id)
        if limit <= 0:
            raise ValueError("consume limit must be positive")

        raw_entries = self.redis.eval(
            _CONSUME_CANDIDATES_SCRIPT,
            1,
            self._candidate_key(task_id),
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

    def _candidate_key(self, task_id: TaskId) -> str:
        return f"ad:{self.prefix}:task:{task_id}:candidate-workers"

    def _current_time_millis(self) -> TimeMillis:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    @staticmethod
    def _encode_entry(entry: CandidateWorkerEntry) -> str:
        return json.dumps(
            {
                "workerId": entry.worker_id,
                "workerGroupId": entry.worker_group_id,
                "endpointManagerId": entry.endpoint_manager_id,
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
            worker_id = payload["workerId"]
            worker_group_id = payload["workerGroupId"]
            endpoint_manager_id = payload["endpointManagerId"]
            worker_lease_score = payload["workerLeaseScore"]
        except (KeyError, TypeError, ValueError, UnicodeDecodeError):
            return None
        if not isinstance(worker_id, str) or not worker_id:
            return None
        if not isinstance(worker_group_id, str) or not worker_group_id:
            return None
        if not isinstance(endpoint_manager_id, str) or not endpoint_manager_id:
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
            endpoint_manager_id=endpoint_manager_id,
            worker_lease_score=worker_lease_score,
        )

    @staticmethod
    def _validate_task_id(task_id: TaskId) -> None:
        if not task_id:
            raise ValueError("task id must be non-empty")


class RedisDeliverSeedRuntime(DeliverSeedRuntime):
    """Redis-backed endpoint-manager DeliverSeed queue runtime."""

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
        endpoint_manager_id: EndpointManagerId,
        deliver_seeds: Sequence[DeliverSeed],
    ) -> None:
        if not endpoint_manager_id:
            raise ValueError("endpoint manager id must be non-empty")
        if not deliver_seeds:
            return
        encoded_seeds = tuple(
            self._encode_deliver_seed(seed) for seed in deliver_seeds
        )
        self.redis.rpush(
            self._deliver_seed_key(endpoint_manager_id),
            *encoded_seeds,
        )

    def consume_deliver_seeds(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        limit: int,
    ) -> tuple[DeliverSeed, ...]:
        if not endpoint_manager_id:
            raise ValueError("endpoint manager id must be non-empty")
        if limit <= 0:
            raise ValueError("consume limit must be positive")

        with self.redis.pipeline(transaction=True) as pipeline:
            for _ in range(limit):
                pipeline.lpop(self._deliver_seed_key(endpoint_manager_id))
            raw_seeds = pipeline.execute()

        deliver_seeds: list[DeliverSeed] = []
        for raw_seed in raw_seeds:
            if raw_seed is None:
                continue
            seed = self._decode_deliver_seed(raw_seed)
            if seed is not None:
                deliver_seeds.append(seed)
        return tuple(deliver_seeds)

    def _deliver_seed_key(self, endpoint_manager_id: EndpointManagerId) -> str:
        return (
            f"ad:{self.prefix}:endpoint-manager:{endpoint_manager_id}:deliver-seeds"
        )

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
