from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC
from typing import Any, Sequence

from ..kernel.task_dispatch_runtime import (
    CandidateWorkerEntry,
    TaskDispatchRuntime,
)
from ..kernel.task_score_band import TaskId, TimeMillis

_CONSUME_CANDIDATES_SCRIPT = """
local entries = {}
for index = 1, tonumber(ARGV[1]) do
    local entry = redis.call('LPOP', KEYS[1])
    if not entry then
        break
    end
    entries[#entries + 1] = entry
end
return entries
"""


class RedisTaskDispatchRuntime(TaskDispatchRuntime):
    """Redis LIST-backed transient candidate-worker runtime."""

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
    ) -> None:
        self._validate_task_id(task_id)
        if not candidate_workers:
            return

        self.redis.rpush(
            self._candidate_key(task_id),
            *(self._encode_entry(entry) for entry in candidate_workers),
        )

    def candidate_worker_count(
        self,
        *,
        task_id: TaskId,
    ) -> int:
        self._validate_task_id(task_id)
        return int(self.redis.llen(self._candidate_key(task_id)))

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
            limit,
        )
        if raw_entries is None:
            return ()
        if isinstance(raw_entries, (str, bytes)):
            raw_entries = [raw_entries]

        now_millis = self._current_time_millis()
        entries: list[CandidateWorkerEntry] = []
        for raw_entry in raw_entries:
            entry = self._decode_entry(raw_entry)
            if entry is not None and entry.expires_at_millis > now_millis:
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
                "observedWorkerScore": entry.observed_worker_score,
                "expiresAtMillis": entry.expires_at_millis,
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
            observed_worker_score = payload["observedWorkerScore"]
            expires_at_millis = payload["expiresAtMillis"]
        except (KeyError, TypeError, ValueError, UnicodeDecodeError):
            return None
        if not isinstance(worker_id, str) or not worker_id:
            return None
        if not isinstance(worker_group_id, str) or not worker_group_id:
            return None
        if (
            isinstance(observed_worker_score, bool)
            or not isinstance(observed_worker_score, int)
            or observed_worker_score <= 0
        ):
            return None
        if (
            isinstance(expires_at_millis, bool)
            or not isinstance(expires_at_millis, int)
            or expires_at_millis <= 0
        ):
            return None
        return CandidateWorkerEntry(
            worker_id=worker_id,
            worker_group_id=worker_group_id,
            observed_worker_score=observed_worker_score,
            expires_at_millis=expires_at_millis,
        )

    @staticmethod
    def _validate_task_id(task_id: TaskId) -> None:
        if not task_id:
            raise ValueError("task id must be non-empty")
