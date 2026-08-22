from __future__ import annotations

from ..kernel import (
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeResult,
)
from ..redis_runtime import (
    RedisKeyspace,
    RedisWorkerResourceCatalog,
    RedisWorkerRuntime,
    RedisWorkerScoreCore,
)
from .application import KernelApplicationConfig


class ResourcesCommandClient:
    """Low-frequency resource commands independent of scheduler lifecycle."""

    def __init__(
        self,
        config: KernelApplicationConfig | None = None,
    ) -> None:
        if config is not None and not isinstance(config, KernelApplicationConfig):
            raise TypeError("config must be KernelApplicationConfig or None")
        resolved_config = config or KernelApplicationConfig.from_json()
        try:
            import redis
        except ImportError as error:
            raise RuntimeError(
                "redis-py is required for ResourcesCommandClient"
            ) from error

        redis_client = redis.Redis.from_url(
            resolved_config.redis_url,
            decode_responses=False,
        )
        keyspace = RedisKeyspace(resolved_config.redis_scope)
        worker_score = RedisWorkerScoreCore(
            redis_client,
            keyspace=keyspace,
        )
        self._resource_catalog = RedisWorkerResourceCatalog(
            redis_client,
            keyspace=keyspace,
        )
        self._worker_runtime = RedisWorkerRuntime(
            redis_client,
            worker_score,
            keyspace=keyspace,
        )

    @classmethod
    def from_json(
        cls,
        config_json: str | None = None,
    ) -> ResourcesCommandClient:
        return cls(KernelApplicationConfig.from_json(config_json))

    def register_worker_group(
        self,
        *,
        descriptor: WorkerGroupDescriptor,
    ) -> WorkerRuntimeResult:
        return self._resource_catalog.register_worker_group(
            descriptor=descriptor,
        )

    def upsert_worker(
        self,
        *,
        declaration: WorkerDeclaration,
    ) -> WorkerRuntimeResult:
        return self._worker_runtime.upsert_worker(
            declaration=declaration,
        )
