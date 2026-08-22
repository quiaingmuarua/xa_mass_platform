from __future__ import annotations

import json
import unittest

from kernel_design.executable_spec import (
    DeliveryEndpoint,
    DeliveryReport,
    ProbeRequestOfferStatus,
    RedisKeyspace,
    RedisWorkerServiceabilityRuntime,
    encode_delivery_report,
)


class FakeRedis:
    def __init__(self) -> None:
        self.hashes: dict[str, dict[str, str]] = {}
        self.lists: dict[str, list[str]] = {}

    def eval(self, script: str, numkeys: int, *args: object) -> object:
        self.assert_numkeys(numkeys)
        key = str(args[0])
        argv = args[1:]
        if "worker_serviceability_offer_probe_requests" in script:
            capacity = int(argv[0])
            row = self.hashes.setdefault(key, {})
            results: list[str] = []
            for raw_worker_id in argv[1:]:
                worker_id = str(raw_worker_id)
                if worker_id in row:
                    results.append("ALREADY_REQUESTED")
                elif len(row) >= capacity:
                    results.append("CAPACITY")
                else:
                    row[worker_id] = "1"
                    results.append("OFFERED")
            return results
        if "worker_serviceability_consume_probe_requests" in script:
            limit = int(argv[0])
            row = self.hashes.setdefault(key, {})
            worker_ids = list(row)[:limit]
            for worker_id in worker_ids:
                del row[worker_id]
            return worker_ids
        if "worker_serviceability_append_adapter_evidence_results" in script:
            capacity = int(argv[0])
            row = self.lists.setdefault(key, [])
            accepted = min(capacity - len(row), len(argv) - 1)
            if accepted <= 0:
                return 0
            row.extend(str(value) for value in argv[1:accepted + 1])
            return accepted
        raise AssertionError("unknown Lua script")

    def pipeline(self, *, transaction: bool) -> FakePipeline:
        if not transaction:
            raise AssertionError("result consumption must be transactional")
        return FakePipeline(self)

    @staticmethod
    def assert_numkeys(numkeys: int) -> None:
        if numkeys != 1:
            raise AssertionError("serviceability scripts must use one key")


class FakePipeline:
    def __init__(self, redis: FakeRedis) -> None:
        self.redis = redis
        self.keys: list[str] = []

    def __enter__(self) -> FakePipeline:
        return self

    def __exit__(self, *args: object) -> None:
        pass

    def lpop(self, key: str) -> FakePipeline:
        self.keys.append(key)
        return self

    def execute(self) -> list[str | None]:
        results: list[str | None] = []
        for key in self.keys:
            row = self.redis.lists.setdefault(key, [])
            results.append(row.pop(0) if row else None)
        return results


class RedisWorkerServiceabilityRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.redis = FakeRedis()
        self.runtime = RedisWorkerServiceabilityRuntime(
            self.redis,
            keyspace=RedisKeyspace("test_serviceability_unit"),
            request_capacity_per_adapter=2,
            result_capacity=2,
        )

    def test_probe_request_hash_is_per_adapter_coalescing_and_bounded(self) -> None:
        first = self.runtime.offer_probe_requests(
            adapter_id="adapter-1",
            worker_ids=("worker-1", "worker-2"),
        )
        repeated = self.runtime.offer_probe_requests(
            adapter_id="adapter-1",
            worker_ids=("worker-1", "worker-3"),
        )
        other_adapter = self.runtime.offer_probe_requests(
            adapter_id="adapter-2",
            worker_ids=("worker-1",),
        )

        self.assertEqual(
            {
                "worker-1": ProbeRequestOfferStatus.OFFERED,
                "worker-2": ProbeRequestOfferStatus.OFFERED,
            },
            first,
        )
        self.assertEqual(
            {
                "worker-1": ProbeRequestOfferStatus.ALREADY_REQUESTED,
                "worker-3": ProbeRequestOfferStatus.CAPACITY,
            },
            repeated,
        )
        self.assertEqual(
            {"worker-1": ProbeRequestOfferStatus.OFFERED},
            other_adapter,
        )

    def test_probe_request_consume_is_bounded_and_destructive(self) -> None:
        self.runtime.offer_probe_requests(
            adapter_id="adapter-1",
            worker_ids=("worker-1", "worker-2"),
        )

        first = self.runtime.consume_probe_requests(
            adapter_id="adapter-1",
            limit=1,
        )
        second = self.runtime.consume_probe_requests(
            adapter_id="adapter-1",
            limit=2,
        )

        self.assertEqual(1, len(first))
        self.assertEqual({"worker-1", "worker-2"}, set(first + second))
        self.assertEqual(
            (),
            self.runtime.consume_probe_requests(
                adapter_id="adapter-1",
                limit=2,
            ),
        )

    def test_one_result_item_carries_multiple_worker_states(self) -> None:
        report = self.report(
            {
                "worker-1": "CONNECTED",
                "worker-2": "DISCONNECTED",
            }
        )

        self.assertEqual(
            1,
            self.runtime.append_adapter_evidence_results(reports=(report,)),
        )
        self.assertEqual(
            (report,),
            self.runtime.consume_adapter_evidence_results(limit=1),
        )

    def test_result_capacity_accepts_whole_report_items_only(self) -> None:
        reports = tuple(
            self.report({f"worker-{index}": "CONNECTED"})
            for index in range(3)
        )

        self.assertEqual(
            2,
            self.runtime.append_adapter_evidence_results(reports=reports),
        )
        self.assertEqual(
            reports[:2],
            self.runtime.consume_adapter_evidence_results(limit=3),
        )

    def test_corrupt_or_wrong_owner_results_are_consumed_and_skipped(self) -> None:
        key = self.runtime._results_key()
        wrong = DeliveryReport.create(
            src=DeliveryEndpoint.WORKER,
            source_id="worker-1",
            dst=DeliveryEndpoint.KERNEL,
            message_type="platform.adapter.worker-connections.snapshot",
            outcome_code="200",
            payload='{"stateByWorkerId":{"worker-1":"CONNECTED"}}',
            forward="worker-serviceability:v1:1000",
        )
        self.redis.lists[key] = ["{bad-json", encode_delivery_report(wrong)]

        self.assertEqual(
            (),
            self.runtime.consume_adapter_evidence_results(limit=2),
        )
        self.assertEqual([], self.redis.lists[key])

    def test_input_bounds_are_strict(self) -> None:
        with self.assertRaises(ValueError):
            self.runtime.offer_probe_requests(
                adapter_id="adapter-1",
                worker_ids=("worker-1", "worker-1"),
            )
        with self.assertRaises(ValueError):
            self.runtime.consume_probe_requests(
                adapter_id="adapter-1",
                limit=101,
            )
        with self.assertRaises(ValueError):
            self.runtime.append_adapter_evidence_results(
                reports=(
                    DeliveryReport.create(
                        src=DeliveryEndpoint.WORKER,
                        source_id="worker-1",
                        dst=DeliveryEndpoint.KERNEL,
                        message_type="event",
                        outcome_code="200",
                        payload="null",
                        forward="context",
                    ),
                )
            )

    @staticmethod
    def report(states: dict[str, str]) -> DeliveryReport:
        return DeliveryReport.create(
            src=DeliveryEndpoint.ADAPTER,
            source_id="adapter-1",
            dst=DeliveryEndpoint.KERNEL,
            message_type="platform.adapter.worker-connections.snapshot",
            outcome_code="200",
            payload=json.dumps(
                {"stateByWorkerId": states},
                separators=(",", ":"),
                sort_keys=True,
            ),
            forward="worker-serviceability:v1:1000",
        )


if __name__ == "__main__":
    unittest.main()
