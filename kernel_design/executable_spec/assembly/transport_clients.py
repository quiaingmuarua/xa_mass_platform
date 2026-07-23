from __future__ import annotations

from collections.abc import Mapping, Sequence

from ..kernel import DeliverSeed, SeedResult, WorkerId
from ..redis_runtime import RedisDeliverSeedRuntime, RedisSeedResultRuntime
from .application import KernelApplicationConfig


def _redis_client(config: KernelApplicationConfig):
    try:
        import redis
    except ImportError as error:
        raise RuntimeError("redis-py is required for transport clients") from error
    return redis.Redis.from_url(config.redis_url, decode_responses=False)


class DeliverSeedConsumerClient:
    """External boundary for consuming Worker-addressed assigned seeds."""

    def __init__(self, config: KernelApplicationConfig | None = None) -> None:
        if config is not None and not isinstance(config, KernelApplicationConfig):
            raise TypeError("config must be KernelApplicationConfig or None")
        resolved_config = config or KernelApplicationConfig.from_json()
        self._runtime = RedisDeliverSeedRuntime(
            _redis_client(resolved_config),
            prefix=resolved_config.redis_prefix,
        )

    @classmethod
    def from_json(
        cls,
        config_json: str | None = None,
    ) -> DeliverSeedConsumerClient:
        return cls(KernelApplicationConfig.from_json(config_json))

    def consume_deliver_seeds(
        self,
        *,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, DeliverSeed]:
        return self._runtime.consume_deliver_seeds(
            worker_ids=worker_ids,
        )


class SeedResultCommandClient:
    """External transport boundary for appending result evidence."""

    def __init__(self, config: KernelApplicationConfig | None = None) -> None:
        if config is not None and not isinstance(config, KernelApplicationConfig):
            raise TypeError("config must be KernelApplicationConfig or None")
        resolved_config = config or KernelApplicationConfig.from_json()
        self._runtime = RedisSeedResultRuntime(
            _redis_client(resolved_config),
            prefix=resolved_config.redis_prefix,
        )

    @classmethod
    def from_json(
        cls,
        config_json: str | None = None,
    ) -> SeedResultCommandClient:
        return cls(KernelApplicationConfig.from_json(config_json))

    def append_seed_results(self, *, results: Sequence[SeedResult]) -> int:
        return self._runtime.append_seed_results(results=results)
