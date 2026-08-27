from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC
from typing import Any, Mapping, Sequence

from ..kernel.assignment_dispatch_runtime import (
    CandidateId,
    CandidateWorkerCache,
    CandidateWorkerEntry,
)
from ..kernel.task_score_band import TaskId, TimeMillis
from .keyspace import RedisKeyspace

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

class RedisCandidateWorkerCache(CandidateWorkerCache):
    """Redis-backed expiring Worker candidate cache."""

    def __init__(
        self,
        redis_client: Any,
        *,
        keyspace: RedisKeyspace,
    ) -> None:
        if not isinstance(keyspace, RedisKeyspace):
            raise TypeError("keyspace must be RedisKeyspace")
        self.redis = redis_client
        self.keyspace = keyspace

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
        return (
            f"{self.keyspace.base}:dispatch:candidate:"
            f"{candidate_id}:workers"
        )

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
            if set(payload) != {
                "workerId",
                "workerGroupId",
                "endpointManagerId",
                "workerLeaseScore",
            }:
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
    def _validate_candidate_id(candidate_id: CandidateId) -> None:
        if not candidate_id:
            raise ValueError("candidate id must be non-empty")
