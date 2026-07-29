from __future__ import annotations

import inspect
import unittest
from unittest.mock import Mock, patch

from kernel_design.executable_spec import (
    DeliverSeed,
    SeedResult,
    WorkerCommandEnvelope,
    WorkerMessageType,
    encode_deliver_seed,
)
from kernel_design.executable_spec.assembly import (
    SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
    WorkerCommandConsumerClient,
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
                "RedisWorkerCommandRuntime",
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

    def test_system_polling_binding_is_a_fixed_logical_route(self) -> None:
        self.assertEqual(
            "system-polling",
            SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
        )

    def test_clients_are_independent_command_surfaces_without_lifecycle(self) -> None:
        deliver_client = WorkerCommandConsumerClient(self.config)
        result_client = SeedResultCommandClient(self.config)

        self.assertEqual(
            {"consume_worker_command", "consume_worker_commands"},
            {
                name
                for name, method in inspect.getmembers(
                    WorkerCommandConsumerClient,
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
            ["self", "endpoint_manager_id", "worker_id"],
            list(
                inspect.signature(
                    WorkerCommandConsumerClient.consume_worker_command
                ).parameters
            ),
        )
        self.assertEqual(
            ["self", "endpoint_manager_id", "limit"],
            list(
                inspect.signature(
                    WorkerCommandConsumerClient.consume_worker_commands
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
        command = WorkerCommandEnvelope(
            "a5e9e10d-f78b-469e-93ab-864b49c189c1",
            WorkerMessageType.TASK_ITEM,
            10_000,
            encode_deliver_seed(
                DeliverSeed("worker-1", "delivery", "context")
            ),
        )
        commands = {"worker-1": command}
        seed_result = SeedResult(
            command.command_id,
            "context",
            "200",
            "null",
        )
        self.deliver_runtime.consume_worker_command.return_value = command
        self.deliver_runtime.consume_worker_commands.return_value = commands
        self.result_runtime.append_seed_results.return_value = 1

        deliver_client = WorkerCommandConsumerClient(self.config)
        result_client = SeedResultCommandClient(self.config)

        self.assertEqual(
            command,
            deliver_client.consume_worker_command(
                endpoint_manager_id="endpoint-manager-1",
                worker_id="worker-1",
            ),
        )
        self.assertEqual(
            commands,
            deliver_client.consume_worker_commands(
                endpoint_manager_id="endpoint-manager-1",
                limit=10,
            ),
        )
        self.assertEqual(
            1,
            result_client.append_seed_results(results=(seed_result,)),
        )
        self.deliver_runtime.consume_worker_command.assert_called_once_with(
            endpoint_manager_id="endpoint-manager-1",
            worker_id="worker-1",
        )
        self.deliver_runtime.consume_worker_commands.assert_called_once_with(
            endpoint_manager_id="endpoint-manager-1",
            limit=10,
        )
        self.result_runtime.append_seed_results.assert_called_once_with(
            results=(seed_result,),
        )

    def test_result_client_accepts_all_seed_result_outcome_classes(self) -> None:
        client = SeedResultCommandClient(self.config)
        results = (
            SeedResult(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                "worker-context",
                "1500",
            ),
            SeedResult(
                "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                "adapter-context",
                "3001",
            ),
        )
        self.result_runtime.append_seed_results.return_value = 2

        self.assertEqual(2, client.append_seed_results(results=results))
        self.result_runtime.append_seed_results.assert_called_once_with(
            results=results
        )


if __name__ == "__main__":
    unittest.main()
