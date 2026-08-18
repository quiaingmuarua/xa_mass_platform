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


class FakeScore:
    def __init__(self) -> None:
        self.hot_by_group: dict[str, dict[str, int]] = {}
        self.recovery_by_group: dict[str, list[tuple[str, int]]] = {}
        self.states_by_group: dict[str, dict[str, WorkerScoreState | None]] = {}
        self.applied: list[tuple[str, dict[str, object], int]] = []
        self.scanned_groups: list[str] = []

    def acquire_hot_acquire_candidates(
        self, *, home_bucket_id: str, limit: int
    ) -> dict[str, int]:
        self.scanned_groups.append(home_bucket_id)
        return dict(tuple(self.hot_by_group.get(home_bucket_id, {}).items())[:limit])

    def acquire_recovery_recheck_candidates(
        self, *, home_bucket_id: str, limit: int
    ) -> list[tuple[str, int]]:
        return self.recovery_by_group.get(home_bucket_id, [])[:limit]

    def get_score_states(
        self, *, home_bucket_id: str, worker_ids: tuple[str, ...]
    ) -> dict[str, WorkerScoreState | None]:
        states = self.states_by_group.get(home_bucket_id, {})
        return {worker_id: states.get(worker_id) for worker_id in worker_ids}

    def apply_worker_serviceability_checks(
        self,
        *,
        home_bucket_id: str,
        checks_by_worker_id: dict[str, object],
        max_recovery_attempts: int,
    ) -> dict[str, WorkerScoreTransitionResult]:
        self.applied.append(
            (home_bucket_id, dict(checks_by_worker_id), max_recovery_attempts)
        )
        return {
            worker_id: WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED
            )
            for worker_id in checks_by_worker_id
        }


class FakeCatalog:
    def __init__(self) -> None:
        self.descriptors_by_group: dict[str, dict[str, WorkerDescriptor | None]] = {}
        self.group_by_worker: dict[str, str | None] = {}
        self.descriptor_reads: list[tuple[str, tuple[str, ...]]] = []

    def get_worker_descriptors(
        self, *, worker_group_id: str, worker_ids: tuple[str, ...]
    ) -> dict[str, WorkerDescriptor | None]:
        self.descriptor_reads.append((worker_group_id, worker_ids))
        values = self.descriptors_by_group.get(worker_group_id, {})
        return {worker_id: values.get(worker_id) for worker_id in worker_ids}

    def get_worker_group_ids(
        self, *, worker_ids: tuple[str, ...]
    ) -> dict[str, str | None]:
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

    def consume_probe_results(self, *, limit: int) -> tuple[DeliveryReport, ...]:
        consumed = tuple(self.reports[:limit])
        del self.reports[:limit]
        return consumed


def score_state(
    worker_id: str,
    polarity: WorkerScorePolarity,
    time_millis: int,
) -> WorkerScoreState:
    return WorkerScoreState(
        worker_id=worker_id,
        score=1 if polarity is WorkerScorePolarity.HOT_ACQUIRE else -1,
        polarity=polarity,
        time_millis=time_millis,
        lane_rank=0,
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


def probe_report(
    states: dict[str, str],
    *,
    forward: str = "worker-serviceability:v1:95000",
    outcome_code: str = "200",
    payload_override: str | None = None,
) -> DeliveryReport:
    return DeliveryReport.create(
        src=DeliveryEndpoint.ADAPTER,
        source_id="adapter-a",
        dst=DeliveryEndpoint.KERNEL,
        message_type="platform.adapter.worker-connections.snapshot",
        outcome_code=outcome_code,
        payload=(
            payload_override
            if payload_override is not None
            else json.dumps({"stateByWorkerId": states})
        ),
        forward=forward,
    )


class WorkerServiceabilityDispatchPacerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.score = FakeScore()
        self.catalog = FakeCatalog()
        self.runtime = FakeRuntime()
        self.pacer = WorkerServiceabilityDispatchPacer(
            self.score,
            self.catalog,
            self.runtime,
            clock_millis=lambda: 1_000_000,
        )
        self.config = WorkerServiceabilityDispatchConfig(
            worker_group_ids=("group-a", "group-b"),
        )

    def test_round_rotates_one_explicit_group(self) -> None:
        self.pacer.dispatch_probes(config=self.config)
        self.pacer.dispatch_probes(config=self.config)

        self.assertEqual(["group-a", "group-b"], self.score.scanned_groups)

    def test_offers_only_stale_candidates_grouped_by_active_adapter(self) -> None:
        self.score.hot_by_group["group-a"] = {"hot-old": 1, "hot-new": 2}
        self.score.recovery_by_group["group-a"] = [("recovery", -1)]
        self.score.states_by_group["group-a"] = {
            "hot-old": score_state(
                "hot-old", WorkerScorePolarity.HOT_ACQUIRE, 699_999
            ),
            "hot-new": score_state(
                "hot-new", WorkerScorePolarity.HOT_ACQUIRE, 700_001
            ),
            "recovery": score_state(
                "recovery", WorkerScorePolarity.RECOVERY_RECHECK, 940_000
            ),
        }
        self.catalog.descriptors_by_group["group-a"] = {
            "hot-old": descriptor("hot-old", "group-a", "adapter-a"),
            "recovery": descriptor("recovery", "group-a", "adapter-a"),
        }

        offered = self.pacer.dispatch_probes(config=self.config)

        self.assertEqual(2, offered)
        self.assertEqual(
            [("adapter-a", ("hot-old", "recovery"))],
            self.runtime.offers,
        )

    def test_skips_polling_and_missing_descriptors(self) -> None:
        self.score.hot_by_group["group-a"] = {"polling": 1, "missing": 2}
        self.score.states_by_group["group-a"] = {
            worker_id: score_state(
                worker_id, WorkerScorePolarity.HOT_ACQUIRE, 1
            )
            for worker_id in ("polling", "missing")
        }
        self.catalog.descriptors_by_group["group-a"] = {
            "polling": descriptor("polling", "group-a", "system-polling"),
            "missing": None,
        }

        self.assertEqual(0, self.pacer.dispatch_probes(config=self.config))
        self.assertEqual([], self.runtime.offers)

    def test_already_requested_is_not_counted_as_new_offer(self) -> None:
        self.score.hot_by_group["group-a"] = {"worker-1": 1}
        self.score.states_by_group["group-a"] = {
            "worker-1": score_state(
                "worker-1", WorkerScorePolarity.HOT_ACQUIRE, 1
            )
        }
        self.catalog.descriptors_by_group["group-a"] = {
            "worker-1": descriptor("worker-1", "group-a", "adapter-a")
        }
        self.runtime.offer_status = ProbeRequestOfferStatus.ALREADY_REQUESTED

        self.assertEqual(0, self.pacer.dispatch_probes(config=self.config))


class WorkerServiceabilityResultPacerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.runtime = FakeRuntime()
        self.catalog = FakeCatalog()
        self.score = FakeScore()
        self.pacer = WorkerServiceabilityResultPacer(
            self.runtime,
            self.catalog,
            self.score,
        )
        self.config = WorkerServiceabilityResultConfig()

    def test_routes_one_batch_by_existing_worker_group_owners(self) -> None:
        self.runtime.reports.append(
            probe_report(
                {
                    "worker-a": "CONNECTED",
                    "worker-b": "DISCONNECTED",
                    "worker-c": "UNKNOWN",
                }
            )
        )
        self.catalog.group_by_worker = {
            "worker-a": "group-a",
            "worker-b": "group-a",
            "worker-c": "group-b",
        }

        applied = self.pacer.route_probe_results(config=self.config)

        self.assertEqual(3, applied)
        by_group = {group: checks for group, checks, _ in self.score.applied}
        self.assertTrue(by_group["group-a"]["worker-a"].serviceable)
        self.assertFalse(by_group["group-a"]["worker-b"].serviceable)
        self.assertFalse(by_group["group-b"]["worker-c"].serviceable)
        self.assertEqual(
            95_000,
            by_group["group-a"]["worker-a"].check_started_at_millis,
        )

    def test_missing_worker_owner_is_skipped(self) -> None:
        self.runtime.reports.append(probe_report({"missing": "CONNECTED"}))

        self.assertEqual(0, self.pacer.route_probe_results(config=self.config))
        self.assertEqual([], self.score.applied)

    def test_malformed_or_failed_report_is_dropped_without_score_write(self) -> None:
        self.runtime.reports.extend(
            (
                probe_report({"worker-a": "VERIFYING"}),
                probe_report({"worker-a": "CONNECTED"}, outcome_code="23004"),
                probe_report(
                    {"worker-a": "CONNECTED"},
                    payload_override='{"stateByWorkerId":{},"extra":1}',
                ),
                probe_report(
                    {"worker-a": "CONNECTED"},
                    forward="worker-serviceability:v1:not-a-time",
                ),
            )
        )
        self.catalog.group_by_worker["worker-a"] = "group-a"

        self.assertEqual(0, self.pacer.route_probe_results(config=self.config))
        self.assertEqual([], self.score.applied)

    def test_configs_reject_unbounded_or_invalid_policy(self) -> None:
        with self.assertRaises(ValueError):
            WorkerServiceabilityDispatchConfig(worker_group_ids=())
        with self.assertRaises(ValueError):
            WorkerServiceabilityDispatchConfig(
                worker_group_ids=("group-a",),
                hot_scan_limit=81,
                recovery_scan_limit=20,
            )
        with self.assertRaises(ValueError):
            WorkerServiceabilityResultConfig(max_recovery_attempts=0)


if __name__ == "__main__":
    unittest.main()
