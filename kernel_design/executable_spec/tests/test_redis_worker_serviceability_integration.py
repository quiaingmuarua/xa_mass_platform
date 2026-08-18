from __future__ import annotations

import json
import os
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor

from kernel_design.executable_spec import (
    DeliveryEndpoint,
    DeliveryReport,
    ProbeRequestOfferStatus,
    RedisWorkerScoreCore,
    RedisWorkerServiceabilityRuntime,
    WorkerScoreCore,
    WorkerScorePolarity,
    WorkerScoreTransitionStatus,
    WorkerServiceabilityCheck,
)

try:
    import redis as redis_module
except ImportError:  # pragma: no cover
    redis_module = None  # type: ignore[assignment]


_REDIS_URL = os.environ.get("KERNEL_DESIGN_REDIS_URL")


@unittest.skipUnless(
    redis_module is not None and _REDIS_URL,
    "set KERNEL_DESIGN_REDIS_URL to run Worker serviceability Redis proof",
)
class RedisWorkerServiceabilityIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        assert redis_module is not None
        assert _REDIS_URL is not None
        self.redis = redis_module.Redis.from_url(_REDIS_URL, decode_responses=False)
        self.redis.ping()
        self.prefix = f"serviceability-{uuid.uuid4().hex}"
        self.runtime = RedisWorkerServiceabilityRuntime(
            self.redis,
            prefix=self.prefix,
            request_capacity_per_adapter=3,
            result_capacity=3,
        )
        self.score = RedisWorkerScoreCore(
            self.redis,
            score_key_prefix=f"wr:{self.prefix}:score",
        )
        self.group_id = "group-a"

    def tearDown(self) -> None:
        keys = tuple(self.redis.scan_iter(match=f"*{self.prefix}*"))
        if keys:
            self.redis.delete(*keys)

    def test_probe_request_offer_is_atomic_coalesced_and_capacity_bounded(self) -> None:
        def offer(worker_ids: tuple[str, ...]) -> dict[str, ProbeRequestOfferStatus]:
            return dict(self.runtime.offer_probe_requests(
                adapter_id="adapter-a",
                worker_ids=worker_ids,
            ))

        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(offer, ("worker-1", "worker-2"))
            second = executor.submit(offer, ("worker-2", "worker-3", "worker-4"))
            results = (first.result(), second.result())

        offered = {
            worker_id
            for result in results
            for worker_id, status in result.items()
            if status is ProbeRequestOfferStatus.OFFERED
        }
        self.assertEqual(3, len(offered))
        self.assertIn("worker-2", offered)
        consumed = self.runtime.consume_probe_requests(
            adapter_id="adapter-a",
            limit=3,
        )
        self.assertEqual(offered, set(consumed))
        self.assertEqual(
            (),
            self.runtime.consume_probe_requests(
                adapter_id="adapter-a",
                limit=3,
            ),
        )

    def test_one_result_item_preserves_a_multi_worker_snapshot(self) -> None:
        report = DeliveryReport.create(
            src=DeliveryEndpoint.ADAPTER,
            source_id="adapter-a",
            dst=DeliveryEndpoint.KERNEL,
            message_type="platform.adapter.worker-connections.snapshot",
            outcome_code="200",
            payload=json.dumps(
                {"stateByWorkerId": {"worker-1": "CONNECTED", "worker-2": "UNKNOWN"}}
            ),
            forward="worker-serviceability:v1:1000",
        )

        self.assertEqual(1, self.runtime.append_probe_results(reports=(report,)))
        self.assertEqual((report,), self.runtime.consume_probe_results(limit=1))

    def test_score_check_is_fenced_by_newer_score_time(self) -> None:
        now_millis = self.redis.time()[0] * 1_000
        check_millis = now_millis - 10_000
        old_slot = (check_millis - 10_000) // WorkerScoreCore.SLOT_MILLIS
        score_key = self.score._score_key(self.group_id)
        old_score = old_slot * WorkerScoreCore.SLOT_FACTOR + 1
        self.redis.zadd(score_key, {"worker-1": old_score})

        transitioned = self.score.apply_worker_serviceability_checks(
            home_bucket_id=self.group_id,
            checks_by_worker_id={
                "worker-1": WorkerServiceabilityCheck(check_millis, False),
            },
            max_recovery_attempts=5,
        )["worker-1"]
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            transitioned.status,
        )
        state = self.score.get_score_states(
            home_bucket_id=self.group_id,
            worker_ids=("worker-1",),
        )["worker-1"]
        self.assertIsNotNone(state)
        assert state is not None
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(1, state.dirty)

        stale = self.score.apply_worker_serviceability_checks(
            home_bucket_id=self.group_id,
            checks_by_worker_id={
                "worker-1": WorkerServiceabilityCheck(check_millis, True),
            },
            max_recovery_attempts=5,
        )["worker-1"]
        self.assertEqual(WorkerScoreTransitionStatus.STALE, stale.status)

    def test_recovery_scan_never_returns_cold_park_coordinate(self) -> None:
        now_slot = self.score._current_time_slot()
        due_slot = now_slot - 1
        score_key = self.score._score_key(self.group_id)
        parked_score = self.score._score(
            WorkerScorePolarity.RECOVERY_RECHECK,
            self.score.COLD_PARK_TIME_SLOT,
            5,
            0,
        )
        due_score = self.score._score(
            WorkerScorePolarity.RECOVERY_RECHECK,
            due_slot,
            0,
            0,
        )
        self.redis.zadd(
            score_key,
            {
                "parked": parked_score,
                "due": due_score,
            },
        )

        self.assertEqual(
            [("due", due_score)],
            self.score.acquire_recovery_recheck_candidates(
                home_bucket_id=self.group_id,
                limit=10,
            ),
        )


if __name__ == "__main__":
    unittest.main()
