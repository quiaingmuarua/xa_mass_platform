from __future__ import annotations

from collections.abc import Mapping, Sequence

from ..kernel import (
    EndpointManagerId,
    DeliveryReport,
    DeliveryCommand,
    WorkerId,
)
from ..redis_runtime import RedisWorkerResultRuntime, RedisWorkerCommandRuntime
from .application import KernelApplicationConfig


SYSTEM_POLLING_ENDPOINT_MANAGER_ID: EndpointManagerId = "system-polling"


def _redis_client(config: KernelApplicationConfig):
    try:
        import redis
    except ImportError as error:
        raise RuntimeError("redis-py is required for transport clients") from error
    return redis.Redis.from_url(config.redis_url, decode_responses=False)


class WorkerCommandConsumerClient:
    """External boundary for consuming one Adapter's Worker commands."""

    def __init__(self, config: KernelApplicationConfig | None = None) -> None:
        if config is not None and not isinstance(config, KernelApplicationConfig):
            raise TypeError("config must be KernelApplicationConfig or None")
        resolved_config = config or KernelApplicationConfig.from_json()
        self._runtime = RedisWorkerCommandRuntime(
            _redis_client(resolved_config),
            prefix=resolved_config.redis_prefix,
        )

    @classmethod
    def from_json(
        cls,
        config_json: str | None = None,
    ) -> WorkerCommandConsumerClient:
        return cls(KernelApplicationConfig.from_json(config_json))

    def consume_worker_command(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_id: WorkerId,
    ) -> DeliveryCommand | None:
        return self._runtime.consume_worker_command(
            endpoint_manager_id=endpoint_manager_id,
            worker_id=worker_id,
        )

    def consume_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        limit: int,
    ) -> Mapping[WorkerId, DeliveryCommand]:
        return self._runtime.consume_worker_commands(
            endpoint_manager_id=endpoint_manager_id,
            limit=limit,
        )


class WorkerResultCommandClient:
    """External boundary for appending semantic DeliveryReport evidence."""

    def __init__(self, config: KernelApplicationConfig | None = None) -> None:
        if config is not None and not isinstance(config, KernelApplicationConfig):
            raise TypeError("config must be KernelApplicationConfig or None")
        resolved_config = config or KernelApplicationConfig.from_json()
        self._runtime = RedisWorkerResultRuntime(
            _redis_client(resolved_config),
            prefix=resolved_config.redis_prefix,
        )

    @classmethod
    def from_json(
        cls,
        config_json: str | None = None,
    ) -> WorkerResultCommandClient:
        return cls(KernelApplicationConfig.from_json(config_json))

    def append_worker_results(
        self,
        *,
        results: Sequence[DeliveryReport],
    ) -> int:
        return self._runtime.append_worker_results(results=results)
