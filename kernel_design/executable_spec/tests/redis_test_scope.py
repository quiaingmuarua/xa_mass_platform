from __future__ import annotations

import logging
import re
import uuid
from dataclasses import dataclass
from time import time
from typing import Any

from kernel_design.executable_spec import RedisKeyspace


_LOGGER = logging.getLogger(__name__)
_LANE_PATTERN = re.compile(r"[a-z0-9_]+")


@dataclass(frozen=True, slots=True)
class RedisTestScope:
    """One proof-owned Redis scope with exact, non-destructive cleanup."""

    scope: str
    run_token: str

    @classmethod
    def create(cls, lane: str) -> RedisTestScope:
        if not isinstance(lane, str) or _LANE_PATTERN.fullmatch(lane) is None:
            raise ValueError("Redis test lane must contain lowercase words")
        run_token = f"{int(time())}_{uuid.uuid4().hex[:8]}"
        return cls(
            scope=f"test_{lane}_{run_token}",
            run_token=run_token,
        )

    def __post_init__(self) -> None:
        RedisKeyspace(self.scope)
        if not self.scope.startswith("test_") or not self.run_token:
            raise ValueError("Redis test cleanup requires an exact test scope")
        if not self.scope.endswith(f"_{self.run_token}"):
            raise ValueError("Redis test scope does not own its run token")

    @property
    def keyspace(self) -> RedisKeyspace:
        return RedisKeyspace(self.scope)

    def cleanup(self, redis_client: Any) -> int:
        pattern = f"{self.keyspace.base}:*"
        keys = tuple(redis_client.scan_iter(match=pattern, count=100))
        removed = 0
        for offset in range(0, len(keys), 100):
            removed += int(redis_client.unlink(*keys[offset:offset + 100]))
        _LOGGER.info(
            "cleaned Redis test scope scope=%s observedKeys=%d removedKeys=%d",
            self.scope,
            len(keys),
            removed,
        )
        return removed
