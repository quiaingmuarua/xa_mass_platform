from __future__ import annotations

import inspect
import unittest
from unittest.mock import Mock, patch

from kernel_design.executable_spec import DeliverSeed, SeedResult
from kernel_design.executable_spec.assembly import (
    DeliverSeedConsumerClient,
    KernelApplicationConfig,
    SeedResultCommandClient,
)


class TransportClientsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis_client = Mock()
        self.deliver_runtime = Mock()
        self.result_runtime = Mock()
        patches = (
            patch("redis.Redis.from_url", return_value=self.redis_client),
            patch(
                "kernel_design.executable_spec.assembly.transport_clients."
                "RedisDeliverSeedRuntime",
                return_value=self.deliver_runtime,
            ),
            patch(
                "kernel_design.executable_spec.assembly.transport_clients."
                "RedisSeedResultRuntime",
                return_value=self.result_runtime,
            ),
        )
        for active in patches:
            active.start()
            self.addCleanup(active.stop)
        self.config = KernelApplicationConfig(
            redis_url="redis://redis:6379/9",
            redis_prefix="transport-test",
        )

    def test_clients_are_independent_command_surfaces_without_lifecycle(self) -> None:
        deliver_client = DeliverSeedConsumerClient(self.config)
        result_client = SeedResultCommandClient(self.config)

        self.assertEqual(
            {"consume_deliver_seeds"},
            {
                name
                for name, method in inspect.getmembers(
                    DeliverSeedConsumerClient,
                    predicate=inspect.isfunction,
                )
                if not name.startswith("_")
            },
        )
        self.assertEqual(
            {"append_seed_results"},
            {
                name
                for name, method in inspect.getmembers(
                    SeedResultCommandClient,
                    predicate=inspect.isfunction,
                )
                if not name.startswith("_")
            },
        )
        for client in (deliver_client, result_client):
            self.assertFalse(hasattr(client, "start"))
            self.assertFalse(hasattr(client, "stop"))
        self.assertEqual(
            ["self", "worker_ids"],
            list(
                inspect.signature(
                    DeliverSeedConsumerClient.consume_deliver_seeds
                ).parameters
            ),
        )
        self.assertEqual(
            ["self", "results"],
            list(
                inspect.signature(
                    SeedResultCommandClient.append_seed_results
                ).parameters
            ),
        )

    def test_clients_delegate_to_their_redis_runtime(self) -> None:
        seed = DeliverSeed("worker-1", "delivery", "context", 1)
        result = SeedResult("context", "200", "null")
        self.deliver_runtime.consume_deliver_seeds.return_value = {
            "worker-1": seed
        }
        self.result_runtime.append_seed_results.return_value = 1

        deliver_client = DeliverSeedConsumerClient(self.config)
        result_client = SeedResultCommandClient(self.config)

        self.assertEqual(
            {"worker-1": seed},
            deliver_client.consume_deliver_seeds(
                worker_ids=("worker-1",),
            ),
        )
        self.assertEqual(
            1,
            result_client.append_seed_results(results=(result,)),
        )
        self.deliver_runtime.consume_deliver_seeds.assert_called_once_with(
            worker_ids=("worker-1",),
        )
        self.result_runtime.append_seed_results.assert_called_once_with(
            results=(result,),
        )


if __name__ == "__main__":
    unittest.main()
