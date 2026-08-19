from __future__ import annotations

import json
import unittest

from kernel_design.executable_spec import (
    DeliveryEndpoint,
    DeliveryReport,
    ProbeRequestOfferStatus,
    WorkerDescriptor,
    WorkerScorePolarity,
    WorkerScoreState,
    WorkerScoreTransitionResult,
    WorkerScoreTransitionStatus,
    WorkerServiceabilityDispatchConfig,
    WorkerServiceabilityDispatchPacer,
    WorkerServiceabilityResultConfig,
    WorkerServiceabilityResultPacer,
)


_FLOOR = 900_000


class FakeScore:
    def __init__(self) -> None:
        self.hot_by_group: dict[str, list[tuple[str, int]]] = {}
        self.recovery_by_group: dict[str, list[tuple[str, int]]] = {}
        self.states_by_group: dict[str, dict[str, WorkerScoreState | None]] = {}
        self.scanned_groups: list[str] = []
        self.hot_scan_calls: list[tuple[str, int]] = []
        self.recovery_scan_calls: list[tuple[str, int]] = []
        self.rewrites: list[tuple[str, tuple[str, ...], int, int | None]] = []
        self.toggles: list[tuple[str, str, int]] = []
        self.exhausted: list[tuple[str, str, int, int]] = []

    def acquire_pre_epoch_hot_candidates(
        self,
        *,
        home_bucket_id: str,
        hot_eligibility_floor_millis: int,
        maximum_score_exclusive: int,
        limit: int,
    ) -> list[tuple[str, int]]:
        self.scanned_groups.append(home_bucket_id)
        self.hot_scan_calls.append((home_bucket_id, maximum_score_exclusive))
        assert hot_eligibility_floor_millis == _FLOOR
        rows = self.hot_by_group.get(home_bucket_id, [])
        return [
            row
            for row in rows
            if maximum_score_exclusive == 0 or row[1] < maximum_score_exclusive
        ][:limit]

    def acquire_recovery_recheck_candidates(
        self,
        *,
        home_bucket_id: str,
        maximum_score_exclusive: int,
        limit: int,
    ) -> list[tuple[str, int]]:
        self.recovery_scan_calls.append(
            (home_bucket_id, maximum_score_exclusive)
        )
        rows = self.recovery_by_group.get(home_bucket_id, [])
        return [
            row
            for row in rows
            if maximum_score_exclusive == 0 or row[1] < maximum_score_exclusive
        ][:limit]

    def get_score_states(
        self, *, home_bucket_id: str, worker_ids: tuple[str, ...]
    ) -> dict[str, WorkerScoreState | None]:
        states = self.states_by_group.get(home_bucket_id, {})
        return {worker_id: states.get(worker_id) for worker_id in worker_ids}

    def rewrite_current_scores(
        self,
        *,
        home_bucket_id: str,
        worker_ids: tuple[str, ...],
        target_time_millis: int,
        target_lane_rank: int | None = None,
    ) -> dict[str, WorkerScoreTransitionResult]:
        self.rewrites.append(
            (
                home_bucket_id,
                tuple(worker_ids),
                target_time_millis,
                target_lane_rank,
            )
        )
        return {
            worker_id: WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                100 + index,
            )
            for index, worker_id in enumerate(worker_ids)
        }

    def toggle_current_polarity(
        self, *, home_bucket_id: str, worker_id: str, observed_score: int
    ) -> WorkerScoreTransitionResult:
        self.toggles.append((home_bucket_id, worker_id, observed_score))
        return WorkerScoreTransitionResult(
            WorkerScoreTransitionStatus.TRANSITIONED,
            -observed_score,
        )

    def exhaust_recovery_recheck(
        self,
        *,
        home_bucket_id: str,
        worker_id: str,
        observed_score: int,
        max_recovery_attempts: int,
    ) -> WorkerScoreTransitionResult:
        self.exhausted.append(
            (
                home_bucket_id,
                worker_id,
                observed_score,
                max_recovery_attempts,
            )
        )
        return WorkerScoreTransitionResult(
            WorkerScoreTransitionStatus.TRANSITIONED,
            observed_score,
        )


class FakeCatalog:
    def __init__(self) -> None:
        self.descriptors_by_group: dict[str, dict[str, WorkerDescriptor | None]] = {}
        self.group_by_worker: dict[str, str | None] = {}
        self.group_reads: list[tuple[str, ...]] = []

    def get_worker_descriptors(
        self, *, worker_group_id: str, worker_ids: tuple[str, ...]
    ) -> dict[str, WorkerDescriptor | None]:
        values = self.descriptors_by_group.get(worker_group_id, {})
        return {worker_id: values.get(worker_id) for worker_id in worker_ids}

    def get_worker_group_ids(
        self, *, worker_ids: tuple[str, ...]
    ) -> dict[str, str | None]:
        self.group_reads.append(worker_ids)
        return {worker_id: self.group_by_worker.get(worker_id) for worker_id in worker_ids}


class FakeRuntime:
    def __init__(self) -> None:
        self.offers: list[tuple[str, tuple[str, ...]]] = []
        self.offer_status = ProbeRequestOfferStatus.OFFERED
        self.reports: list[DeliveryReport] = []

    def offer_probe_requests(
        self, *, adapter_id: str, worker_ids: tuple[str, ...]
    ) -> dict[str, ProbeRequestOfferStatus]:
        self.offers.append((adapter_id, worker_ids))
        return {worker_id: self.offer_status for worker_id in worker_ids}

    def consume_adapter_evidence_results(
        self, *, limit: int
    ) -> tuple[DeliveryReport, ...]:
        consumed = tuple(self.reports[:limit])
        del self.reports[:limit]
        return consumed


def score_state(
    worker_id: str,
    polarity: WorkerScorePolarity,
    time_millis: int,
    *,
    lane_rank: int = 0,
) -> WorkerScoreState:
    score = 100 + lane_rank
    if polarity is WorkerScorePolarity.RECOVERY_RECHECK:
        score = -score
    return WorkerScoreState(
        worker_id=worker_id,
        score=score,
        polarity=polarity,
        time_millis=time_millis,
        lane_rank=lane_rank,
        dirty=0,
    )


def descriptor(worker_id: str, group_id: str, adapter_id: str) -> WorkerDescriptor:
    return WorkerDescriptor(
        worker_id=worker_id,
        worker_group_id=group_id,
        endpoint_manager_id=adapter_id,
        worker_properties={},
        platform_properties={},
    )


def probe_report(states: dict[str, str], *, observed_at_millis: int = 95_000) -> DeliveryReport:
    return DeliveryReport.create(
        src=DeliveryEndpoint.ADAPTER,
        source_id="adapter-a",
        dst=DeliveryEndpoint.KERNEL,
        message_type="platform.adapter.worker-connections.snapshot",
        outcome_code="200",
        payload=json.dumps({"stateByWorkerId": states}),
        forward=f"worker-serviceability:v1:{observed_at_millis}",
    )


def connection_report(
    worker_id: str,
    state: str,
    *,
    observed_at_millis: int = 98_000,
) -> DeliveryReport:
    return DeliveryReport.create(
        src=DeliveryEndpoint.ADAPTER,
        source_id="adapter-a",
        dst=DeliveryEndpoint.KERNEL,
        message_type="platform.adapter.worker-connection.changed",
        outcome_code="200",
        payload=json.dumps(
            {
                "workerId": worker_id,
                "state": state,
                "observedAtMillis": observed_at_millis,
            }
        ),
        forward="worker-serviceability-evidence:v1",
    )


def expired_report(
    worker_id: str,
    *,
    observed_at_millis: int = 99_000,
) -> DeliveryReport:
    return DeliveryReport.create(
        src=DeliveryEndpoint.ADAPTER,
        source_id="adapter-a",
        dst=DeliveryEndpoint.KERNEL,
        message_type="platform.adapter.worker-delivery.expired",
        outcome_code="200",
        payload=json.dumps(
            {
                "workerId": worker_id,
                "observedAtMillis": observed_at_millis,
            }
        ),
        forward="worker-serviceability-evidence:v1",
    )


class WorkerServiceabilityDispatchPacerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.score = FakeScore()
        self.catalog = FakeCatalog()
        self.runtime = FakeRuntime()
        self.now_millis = 1_000_000
        self.pacer = WorkerServiceabilityDispatchPacer(
            self.score,
            self.catalog,
            self.runtime,
            hot_eligibility_floor_millis=_FLOOR,
            clock_millis=lambda: self.now_millis,
        )
        self.config = WorkerServiceabilityDispatchConfig(
            worker_group_ids=("group-a", "group-b"),
        )

    def test_round_rotates_one_explicit_group_and_uses_pre_epoch_range(self) -> None:
        self.pacer.dispatch_probes(config=self.config)
        self.pacer.dispatch_probes(config=self.config)
        self.assertEqual(["group-a", "group-b"], self.score.scanned_groups)

    def test_offers_pre_epoch_hot_and_linearly_due_recovery(self) -> None:
        self.score.hot_by_group["group-a"] = [("hot", 1)]
        self.score.recovery_by_group["group-a"] = [
            ("recovery-due", -1),
            ("recovery-early", -2),
        ]
        self.score.states_by_group["group-a"] = {
            "hot": score_state("hot", WorkerScorePolarity.HOT_ACQUIRE, 899_900),
            "recovery-due": score_state(
                "recovery-due",
                WorkerScorePolarity.RECOVERY_RECHECK,
                879_999,
                lane_rank=1,
            ),
            "recovery-early": score_state(
                "recovery-early",
                WorkerScorePolarity.RECOVERY_RECHECK,
                880_001,
                lane_rank=1,
            ),
        }
        self.catalog.descriptors_by_group["group-a"] = {
            worker_id: descriptor(worker_id, "group-a", "adapter-a")
            for worker_id in ("hot", "recovery-due", "recovery-early")
        }

        self.assertEqual(2, self.pacer.dispatch_probes(config=self.config))
        self.assertEqual(
            [("adapter-a", ("hot", "recovery-due"))],
            self.runtime.offers,
        )

    def test_scan_cursor_advances_from_raw_page_before_candidate_filtering(
        self,
    ) -> None:
        self.score.hot_by_group["group-a"] = [
            ("missing-state", 300),
            ("eligible", 200),
        ]
        self.score.states_by_group["group-a"] = {
            "eligible": score_state(
                "eligible",
                WorkerScorePolarity.HOT_ACQUIRE,
                1,
            )
        }
        self.catalog.descriptors_by_group["group-a"] = {
            "eligible": descriptor("eligible", "group-a", "adapter-a")
        }
        config = WorkerServiceabilityDispatchConfig(
            worker_group_ids=("group-a",),
            hot_scan_limit=1,
            recovery_scan_limit=1,
        )

        self.assertEqual(0, self.pacer.dispatch_probes(config=config))
        self.assertEqual(1, self.pacer.dispatch_probes(config=config))

        self.assertEqual(
            [("group-a", 0), ("group-a", 300)],
            self.score.hot_scan_calls,
        )
        self.assertEqual([("adapter-a", ("eligible",))], self.runtime.offers)

    def test_empty_ranges_restart_after_non_blocking_cooldown(self) -> None:
        config = WorkerServiceabilityDispatchConfig(
            worker_group_ids=("group-a",),
            probe_sweep_restart_delay_millis=10_000,
        )

        self.assertEqual(0, self.pacer.dispatch_probes(config=config))
        self.assertEqual(0, self.pacer.dispatch_probes(config=config))
        self.now_millis += 9_999
        self.assertEqual(0, self.pacer.dispatch_probes(config=config))

        self.assertEqual([("group-a", 0)], self.score.hot_scan_calls)
        self.assertEqual([("group-a", 0)], self.score.recovery_scan_calls)

        self.now_millis += 1
        self.assertEqual(0, self.pacer.dispatch_probes(config=config))
        self.assertEqual(
            [("group-a", 0), ("group-a", 0)],
            self.score.hot_scan_calls,
        )
        self.assertEqual(
            [("group-a", 0), ("group-a", 0)],
            self.score.recovery_scan_calls,
        )

    def test_hot_cooldown_does_not_block_recovery_cursor(self) -> None:
        self.score.recovery_by_group["group-a"] = [
            ("first", -100),
            ("second", -200),
        ]
        self.score.states_by_group["group-a"] = {
            worker_id: score_state(
                worker_id,
                WorkerScorePolarity.RECOVERY_RECHECK,
                1,
            )
            for worker_id in ("first", "second")
        }
        self.catalog.descriptors_by_group["group-a"] = {
            worker_id: descriptor(worker_id, "group-a", "adapter-a")
            for worker_id in ("first", "second")
        }
        config = WorkerServiceabilityDispatchConfig(
            worker_group_ids=("group-a",),
            hot_scan_limit=1,
            recovery_scan_limit=1,
        )

        self.assertEqual(1, self.pacer.dispatch_probes(config=config))
        self.assertEqual(1, self.pacer.dispatch_probes(config=config))

        self.assertEqual([("group-a", 0)], self.score.hot_scan_calls)
        self.assertEqual(
            [("group-a", 0), ("group-a", -100)],
            self.score.recovery_scan_calls,
        )
        self.assertEqual(
            [
                ("adapter-a", ("first",)),
                ("adapter-a", ("second",)),
            ],
            self.runtime.offers,
        )

    def test_excluded_endpoint_is_exactly_cold_parked(self) -> None:
        self.score.hot_by_group["group-a"] = [("hot", 1)]
        self.score.recovery_by_group["group-a"] = [("recovery", -1)]
        self.score.states_by_group["group-a"] = {
            "hot": score_state("hot", WorkerScorePolarity.HOT_ACQUIRE, 1),
            "recovery": score_state(
                "recovery",
                WorkerScorePolarity.RECOVERY_RECHECK,
                1,
            ),
        }
        self.catalog.descriptors_by_group["group-a"] = {
            worker_id: descriptor(worker_id, "group-a", "system-polling")
            for worker_id in ("hot", "recovery")
        }

        self.assertEqual(0, self.pacer.dispatch_probes(config=self.config))
        self.assertEqual([("group-a", "hot", 100)], self.score.toggles)
        self.assertEqual(
            {
                ("group-a", "hot", -100, 5),
                ("group-a", "recovery", -100, 5),
            },
            set(self.score.exhausted),
        )
        self.assertEqual([], self.runtime.offers)

    def test_empty_exclusion_set_allows_polling_endpoint_probe(self) -> None:
        self.score.hot_by_group["group-a"] = [("polling", 1)]
        self.score.states_by_group["group-a"] = {
            "polling": score_state(
                "polling", WorkerScorePolarity.HOT_ACQUIRE, 1
            )
        }
        self.catalog.descriptors_by_group["group-a"] = {
            "polling": descriptor("polling", "group-a", "system-polling")
        }
        config = WorkerServiceabilityDispatchConfig(
            worker_group_ids=("group-a",),
            probe_excluded_endpoint_manager_ids=(),
        )

        self.assertEqual(1, self.pacer.dispatch_probes(config=config))


class WorkerServiceabilityResultPacerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.runtime = FakeRuntime()
        self.catalog = FakeCatalog()
        self.score = FakeScore()
        self.pacer = WorkerServiceabilityResultPacer(
            self.runtime,
            self.catalog,
            self.score,
            hot_eligibility_floor_millis=_FLOOR,
            clock_millis=lambda: 100_000,
        )
        self.config = WorkerServiceabilityResultConfig()

    def _own(self, worker_ids: tuple[str, ...]) -> None:
        self.catalog.group_by_worker.update(
            {worker_id: "group-a" for worker_id in worker_ids}
        )

    def test_connected_recovery_toggles_then_rewrites_to_hot_floor(self) -> None:
        self.runtime.reports.append(connection_report("worker", "CONNECTED"))
        self._own(("worker",))
        self.score.states_by_group["group-a"] = {
            "worker": score_state(
                "worker", WorkerScorePolarity.RECOVERY_RECHECK, 800_000
            )
        }

        self.assertEqual(1, self.pacer.route_adapter_evidence(config=self.config))
        self.assertEqual([("group-a", "worker", -100)], self.score.toggles)
        self.assertEqual(
            [("group-a", ("worker",), _FLOOR, 0)],
            self.score.rewrites,
        )

    def test_disconnect_and_delivery_expiry_only_toggle_hot_scores(self) -> None:
        self.runtime.reports.extend(
            (
                connection_report("disconnected", "DISCONNECTED"),
                expired_report("expired"),
                expired_report("already-recovery"),
            )
        )
        self._own(("disconnected", "expired", "already-recovery"))
        self.score.states_by_group["group-a"] = {
            "disconnected": score_state(
                "disconnected", WorkerScorePolarity.HOT_ACQUIRE, 950_000
            ),
            "expired": score_state(
                "expired", WorkerScorePolarity.HOT_ACQUIRE, 960_000
            ),
            "already-recovery": score_state(
                "already-recovery",
                WorkerScorePolarity.RECOVERY_RECHECK,
                960_000,
            ),
        }

        self.assertEqual(3, self.pacer.route_adapter_evidence(config=self.config))
        self.assertEqual(
            {
                ("group-a", "disconnected", 100),
                ("group-a", "expired", 100),
            },
            set(self.score.toggles),
        )
        self.assertEqual([], self.score.rewrites)

    def test_probe_failure_retries_linearly_then_cold_parks(self) -> None:
        self.runtime.reports.append(
            probe_report(
                {
                    "hot": "UNKNOWN",
                    "retry": "DISCONNECTED",
                    "exhaust": "UNKNOWN",
                }
            )
        )
        self._own(("hot", "retry", "exhaust"))
        self.score.states_by_group["group-a"] = {
            "hot": score_state("hot", WorkerScorePolarity.HOT_ACQUIRE, 1),
            "retry": score_state(
                "retry", WorkerScorePolarity.RECOVERY_RECHECK, 1, lane_rank=3
            ),
            "exhaust": score_state(
                "exhaust", WorkerScorePolarity.RECOVERY_RECHECK, 1, lane_rank=4
            ),
        }

        self.assertEqual(3, self.pacer.route_adapter_evidence(config=self.config))
        self.assertIn(("group-a", "hot", 100), self.score.toggles)
        self.assertIn(("group-a", ("hot",), 95_000, 0), self.score.rewrites)
        self.assertIn(("group-a", ("retry",), 95_000, 4), self.score.rewrites)
        self.assertEqual(
            [("group-a", "exhaust", -104, 5)],
            self.score.exhausted,
        )

    def test_equal_timestamp_uses_later_report(self) -> None:
        self.runtime.reports.extend(
            (
                connection_report(
                    "worker", "CONNECTED", observed_at_millis=99_000
                ),
                expired_report("worker", observed_at_millis=99_000),
            )
        )
        self._own(("worker",))
        self.score.states_by_group["group-a"] = {
            "worker": score_state(
                "worker", WorkerScorePolarity.HOT_ACQUIRE, 1
            )
        }

        self.pacer.route_adapter_evidence(config=self.config)
        self.assertEqual([("group-a", "worker", 100)], self.score.toggles)
        self.assertEqual([], self.score.rewrites)

    def test_future_expired_and_unknown_worker_are_dropped(self) -> None:
        self.runtime.reports.extend(
            (
                connection_report(
                    "old", "DISCONNECTED", observed_at_millis=69_999
                ),
                expired_report("future", observed_at_millis=100_001),
                connection_report("missing", "CONNECTED"),
            )
        )
        self.catalog.group_by_worker["missing"] = None

        self.assertEqual(0, self.pacer.route_adapter_evidence(config=self.config))
        self.assertEqual([], self.score.toggles)

    def test_configs_reject_invalid_bounds_and_exclusions(self) -> None:
        with self.assertRaises(ValueError):
            WorkerServiceabilityDispatchConfig(worker_group_ids=())
        with self.assertRaises(ValueError):
            WorkerServiceabilityDispatchConfig(
                worker_group_ids=("group-a",),
                probe_excluded_endpoint_manager_ids=("same", "same"),
            )
        with self.assertRaises(ValueError):
            WorkerServiceabilityDispatchConfig(
                worker_group_ids=("group-a",),
                probe_sweep_restart_delay_millis=0,
            )
        with self.assertRaises(ValueError):
            WorkerServiceabilityResultConfig(max_recovery_attempts=0)


if __name__ == "__main__":
    unittest.main()
