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
    WorkerDescriptor,
    WorkerScoreCore,
    WorkerScorePolarity,
    WorkerScoreTransitionStatus,
    WorkerServiceabilityDispatchConfig,
    WorkerServiceabilityDispatchPacer,
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

        self.assertEqual(
            1,
            self.runtime.append_adapter_evidence_results(reports=(report,)),
        )
        self.assertEqual(
            (report,),
            self.runtime.consume_adapter_evidence_results(limit=1),
        )

    def test_score_primitives_preserve_exact_fence_and_dirty(self) -> None:
        now_millis = self.redis.time()[0] * 1_000
        target_millis = now_millis - 10_000
        old_slot = (target_millis - 10_000) // WorkerScoreCore.SLOT_MILLIS
        score_key = self.score._score_key(self.group_id)
        old_score = old_slot * WorkerScoreCore.SLOT_FACTOR + 1
        self.redis.zadd(score_key, {"worker-1": old_score})

        transitioned = self.score.toggle_current_polarity(
            home_bucket_id=self.group_id,
            worker_id="worker-1",
            observed_score=old_score,
        )
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            transitioned.status,
        )
        stale = self.score.toggle_current_polarity(
            home_bucket_id=self.group_id,
            worker_id="worker-1",
            observed_score=old_score,
        )
        self.assertEqual(WorkerScoreTransitionStatus.STALE, stale.status)

        rewritten = self.score.rewrite_current_scores(
            home_bucket_id=self.group_id,
            worker_ids=("worker-1",),
            target_time_millis=target_millis,
            target_lane_rank=0,
        )["worker-1"]
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            rewritten.status,
        )
        state = self.score.get_score_states(
            home_bucket_id=self.group_id,
            worker_ids=("worker-1",),
        )["worker-1"]
        self.assertIsNotNone(state)
        assert state is not None
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(1, state.dirty)

    def test_completed_task_repairs_exact_serviceability_demotion(self) -> None:
        redis_time = self.redis.time()
        now_millis = redis_time[0] * 1_000 + redis_time[1] // 1_000
        held_slot = (
            now_millis // WorkerScoreCore.SLOT_MILLIS + 100
        )
        held = self.score._score(
            WorkerScorePolarity.HOT_ACQUIRE,
            held_slot,
            7,
            1,
        )
        score_key = self.score._score_key(self.group_id)
        self.redis.zadd(score_key, {"worker-1": held})

        demoted = self.score.toggle_current_polarity(
            home_bucket_id=self.group_id,
            worker_id="worker-1",
            observed_score=held,
        )
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            demoted.status,
        )
        repaired = self.score.release_completed_hot_score_holds(
            home_bucket_id=self.group_id,
            observed_hot_scores={"worker-1": held},
            release_time_millis=now_millis,
        )["worker-1"]
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            repaired.status,
        )
        state = self.score.get_score_states(
            home_bucket_id=self.group_id,
            worker_ids=("worker-1",),
        )["worker-1"]
        self.assertIsNotNone(state)
        assert state is not None
        self.assertEqual(WorkerScorePolarity.HOT_ACQUIRE, state.polarity)
        self.assertEqual(0, state.lane_rank)
        self.assertEqual(1, state.dirty)

        newer = self.score._score(
            WorkerScorePolarity.HOT_ACQUIRE,
            held_slot + 1,
            0,
            0,
        )
        self.redis.zadd(score_key, {"worker-1": newer})
        stale = self.score.release_completed_hot_score_holds(
            home_bucket_id=self.group_id,
            observed_hot_scores={"worker-1": held},
            release_time_millis=now_millis,
        )["worker-1"]
        self.assertEqual(WorkerScoreTransitionStatus.STALE, stale.status)
        self.assertEqual(newer, int(self.redis.zscore(score_key, "worker-1")))

    def test_hot_epoch_partitions_real_redis_candidate_ranges(self) -> None:
        now_millis = self.redis.time()[0] * 1_000
        floor_millis = (
            (now_millis - 10_000)
            // WorkerScoreCore.SLOT_MILLIS
            * WorkerScoreCore.SLOT_MILLIS
        )
        floor_slot = floor_millis // WorkerScoreCore.SLOT_MILLIS
        score_key = self.score._score_key(self.group_id)
        scores = {
            "below": self.score._score(
                WorkerScorePolarity.HOT_ACQUIRE,
                floor_slot - 1,
                0,
                0,
            ),
            "at-floor": self.score._score(
                WorkerScorePolarity.HOT_ACQUIRE,
                floor_slot,
                0,
                0,
            ),
            "above": self.score._score(
                WorkerScorePolarity.HOT_ACQUIRE,
                floor_slot + 1,
                0,
                0,
            ),
        }
        self.redis.zadd(score_key, scores)

        self.assertEqual(
            {"at-floor", "above"},
            set(self.score.acquire_hot_acquire_candidates(
                home_bucket_id=self.group_id,
                hot_eligibility_floor_millis=floor_millis,
                limit=10,
            )),
        )
        self.assertEqual(
            {"below"},
            {
                worker_id
                for worker_id, _ in self.score.acquire_pre_epoch_hot_candidates(
                    home_bucket_id=self.group_id,
                    hot_eligibility_floor_millis=floor_millis,
                    maximum_score_exclusive=0,
                    limit=10,
                )
            },
        )
        self.assertEqual(
            {"at-floor", "above"},
            set(self.score.observe_due_hot_scores(
                home_bucket_id=self.group_id,
                worker_ids=("below", "at-floor", "above"),
                hot_eligibility_floor_millis=floor_millis,
            )),
        )

    def test_excluded_endpoint_is_cold_parked_without_probe_offer(self) -> None:
        now_millis = self.redis.time()[0] * 1_000
        floor_millis = (
            now_millis
            // WorkerScoreCore.SLOT_MILLIS
            * WorkerScoreCore.SLOT_MILLIS
        )
        old_slot = floor_millis // WorkerScoreCore.SLOT_MILLIS - 10
        score_key = self.score._score_key(self.group_id)
        hot_score = self.score._score(
            WorkerScorePolarity.HOT_ACQUIRE,
            old_slot,
            0,
            1,
        )
        self.redis.zadd(score_key, {"polling-worker": hot_score})

        class Catalog:
            @staticmethod
            def get_worker_descriptors(*, worker_group_id, worker_ids):
                return {
                    worker_id: WorkerDescriptor(
                        worker_id=worker_id,
                        worker_group_id=worker_group_id,
                        endpoint_manager_id="system-polling",
                        worker_properties={},
                        platform_properties={},
                    )
                    for worker_id in worker_ids
                }

        class Runtime:
            @staticmethod
            def offer_probe_requests(*, adapter_id, worker_ids):
                raise AssertionError("excluded endpoint must not receive probes")

        pacer = WorkerServiceabilityDispatchPacer(
            self.score,
            Catalog(),
            Runtime(),
            hot_eligibility_floor_millis=floor_millis,
            clock_millis=lambda: now_millis,
        )
        self.assertEqual(
            0,
            pacer.dispatch_probes(config=WorkerServiceabilityDispatchConfig(
                worker_group_ids=(self.group_id,),
                hot_scan_limit=1,
                recovery_scan_limit=1,
                max_recovery_attempts=5,
            )),
        )
        state = self.score.get_score_states(
            home_bucket_id=self.group_id,
            worker_ids=("polling-worker",),
        )["polling-worker"]
        self.assertIsNotNone(state)
        assert state is not None
        self.assertEqual(WorkerScorePolarity.RECOVERY_RECHECK, state.polarity)
        self.assertEqual(
            self.score.COLD_PARK_TIME_SLOT * WorkerScoreCore.SLOT_MILLIS,
            state.time_millis,
        )
        self.assertEqual(5, state.lane_rank)
        self.assertEqual(1, state.dirty)

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

        exhausted = self.score.exhaust_recovery_recheck(
            home_bucket_id=self.group_id,
            worker_id="due",
            observed_score=due_score,
            max_recovery_attempts=5,
        )
        self.assertEqual(
            WorkerScoreTransitionStatus.TRANSITIONED,
            exhausted.status,
        )

        self.assertEqual(
            [],
            self.score.acquire_recovery_recheck_candidates(
                home_bucket_id=self.group_id,
                maximum_score_exclusive=0,
                limit=10,
            ),
        )


if __name__ == "__main__":
    unittest.main()
