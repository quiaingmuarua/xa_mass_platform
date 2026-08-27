from __future__ import annotations

import json
from collections import defaultdict
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from time import time_ns

from ..kernel.worker_delivery import DeliveryEndpoint, DeliveryReport
from ..kernel.worker_runtime import WorkerResourceCatalog
from ..kernel.worker_score import (
    WorkerScoreCore,
    WorkerScorePolarity,
    WorkerScoreState,
    WorkerScoreTransitionStatus,
)
from ..kernel.worker_serviceability import (
    ProbeRequestOfferStatus,
    WorkerServiceabilityRuntime,
)
from .task_scheduling_batch_source import DueTaskObservation


_PROBE_EVENT = "platform.adapter.worker-connections.snapshot"
_PROBE_FORWARD_PREFIX = "worker-serviceability:v1:"
_CONNECTION_CHANGED_EVENT = "platform.adapter.worker-connection.changed"
_DELIVERY_EXPIRED_EVENT = "platform.adapter.worker-delivery.expired"
_CONNECTION_EVIDENCE_FORWARD = "worker-serviceability-evidence:v1"
_CONNECTED = "CONNECTED"
_UNAVAILABLE_STATES = frozenset({"DISCONNECTED", "UNKNOWN"})
_CONNECTION_STATES = frozenset({_CONNECTED, "DISCONNECTED"})


def _current_time_millis() -> int:
    return time_ns() // 1_000_000


@dataclass(frozen=True, slots=True)
class WorkerServiceabilityDispatchConfig:
    recovery_retry_interval_millis: int = 60_000
    probe_sweep_restart_delay_millis: int = 10_000
    max_recovery_attempts: int = 5
    hot_scan_limit: int = 80
    recovery_scan_limit: int = 20
    probe_excluded_endpoint_manager_ids: tuple[str, ...] = (
        "system-polling",
    )

    def __post_init__(self) -> None:
        excluded = tuple(self.probe_excluded_endpoint_manager_ids)
        if (
            isinstance(self.probe_excluded_endpoint_manager_ids, (str, bytes))
            or len(excluded) > 100
            or len(set(excluded)) != len(excluded)
            or any(
                not isinstance(endpoint_id, str) or not endpoint_id
                for endpoint_id in excluded
            )
        ):
            raise ValueError(
                "probe excluded Endpoint ids must be 0..100 unique ids"
            )
        if any(
            isinstance(value, bool) or not isinstance(value, int) or value <= 0
            for value in (
                self.recovery_retry_interval_millis,
                self.probe_sweep_restart_delay_millis,
                self.max_recovery_attempts,
                self.hot_scan_limit,
                self.recovery_scan_limit,
            )
        ):
            raise ValueError("serviceability durations and limits must be positive")
        if self.hot_scan_limit + self.recovery_scan_limit > 100:
            raise ValueError("serviceability scan limits must total at most 100")
        if self.max_recovery_attempts > WorkerScoreCore.MAX_LANE_RANK:
            raise ValueError("max recovery attempts must be in 1..99")
        object.__setattr__(
            self,
            "probe_excluded_endpoint_manager_ids",
            excluded,
        )


@dataclass(frozen=True, slots=True)
class WorkerServiceabilityResultConfig:
    max_recovery_attempts: int = 5
    result_report_limit: int = 10
    evidence_max_age_millis: int = 30_000

    def __post_init__(self) -> None:
        if any(
            isinstance(value, bool) or not isinstance(value, int)
            for value in (
                self.max_recovery_attempts,
                self.result_report_limit,
                self.evidence_max_age_millis,
            )
        ):
            raise ValueError(
                "serviceability result configuration must use integers"
            )
        if not 1 <= self.max_recovery_attempts <= WorkerScoreCore.MAX_LANE_RANK:
            raise ValueError("max recovery attempts must be in 1..99")
        if not 1 <= self.result_report_limit <= 100:
            raise ValueError("result Report limit must be in 1..100")
        if self.evidence_max_age_millis <= 0:
            raise ValueError("Adapter evidence max age must be positive")


@dataclass(slots=True)
class _ProbeScoreSweep:
    current_max_worker_score: int = WorkerScoreCore.ZERO_SCORE
    resume_at_millis: int = 0


class WorkerServiceabilityDispatchPolicy:
    """Offer pre-epoch HOT and due RECOVERY Workers for Adapter probing."""

    def __init__(
        self,
        worker_score: WorkerScoreCore,
        worker_catalog: WorkerResourceCatalog,
        runtime: WorkerServiceabilityRuntime,
        *,
        hot_eligibility_floor_millis: int,
        clock_millis: Callable[[], int] = _current_time_millis,
    ) -> None:
        if (
            isinstance(hot_eligibility_floor_millis, bool)
            or not isinstance(hot_eligibility_floor_millis, int)
            or hot_eligibility_floor_millis <= 0
            or hot_eligibility_floor_millis % WorkerScoreCore.SLOT_MILLIS != 0
        ):
            raise ValueError("HOT eligibility floor must be score-slot aligned")
        self.worker_score = worker_score
        self.worker_catalog = worker_catalog
        self.runtime = runtime
        self.hot_eligibility_floor_millis = hot_eligibility_floor_millis
        self._clock_millis = clock_millis
        self._group_cursor = 0
        self._hot_sweeps: dict[str, _ProbeScoreSweep] = {}
        self._recovery_sweeps: dict[str, _ProbeScoreSweep] = {}

    def dispatch_probes(
        self,
        tasks: Sequence[DueTaskObservation],
        *,
        config: WorkerServiceabilityDispatchConfig,
    ) -> int:
        now_millis = self._clock_millis()
        worker_group_ids = tuple(dict.fromkeys(
            task.descriptor.worker_group_id
            for task in tasks
        ))
        self._retain_active_group_sweeps(worker_group_ids)
        if not worker_group_ids:
            self._group_cursor = 0
            return 0

        worker_group_id = worker_group_ids[
            self._group_cursor % len(worker_group_ids)
        ]
        self._group_cursor = (self._group_cursor + 1) % len(worker_group_ids)
        hot = self._hot_page(
            worker_group_id=worker_group_id,
            now_millis=now_millis,
            config=config,
        )
        recovery = self._recovery_page(
            worker_group_id=worker_group_id,
            now_millis=now_millis,
            config=config,
        )
        candidate_ids = tuple(
            dict.fromkeys(
                (
                    *(row[0] for row in hot),
                    *(row[0] for row in recovery),
                )
            )
        )
        if not candidate_ids:
            return 0

        states = self.worker_score.get_score_states(
            home_bucket_id=worker_group_id,
            worker_ids=candidate_ids,
        )
        eligible_ids = tuple(
            worker_id
            for worker_id in candidate_ids
            if self._eligible(
                states.get(worker_id),
                now_millis=now_millis,
                config=config,
            )
        )
        if not eligible_ids:
            return 0

        descriptors = self.worker_catalog.get_worker_descriptors(
            worker_group_id=worker_group_id,
            worker_ids=eligible_ids,
        )
        worker_ids_by_adapter: dict[str, list[str]] = defaultdict(list)
        for worker_id in eligible_ids:
            descriptor = descriptors.get(worker_id)
            if (
                descriptor is None
                or descriptor.worker_group_id != worker_group_id
                or descriptor.worker_id != worker_id
            ):
                continue
            if descriptor.endpoint_manager_id in (
                config.probe_excluded_endpoint_manager_ids
            ):
                self._cold_park_excluded(
                    worker_group_id=worker_group_id,
                    worker_id=worker_id,
                    state=states[worker_id],
                    max_recovery_attempts=config.max_recovery_attempts,
                )
                continue
            worker_ids_by_adapter[descriptor.endpoint_manager_id].append(worker_id)

        offered = 0
        for adapter_id, worker_ids in worker_ids_by_adapter.items():
            statuses = self.runtime.offer_probe_requests(
                adapter_id=adapter_id,
                worker_ids=tuple(worker_ids),
            )
            offered += sum(
                status is ProbeRequestOfferStatus.OFFERED
                for status in statuses.values()
            )
        return offered

    def _retain_active_group_sweeps(
        self,
        worker_group_ids: tuple[str, ...],
    ) -> None:
        retained = frozenset(worker_group_ids)
        for sweeps in (self._hot_sweeps, self._recovery_sweeps):
            for worker_group_id in tuple(sweeps):
                if worker_group_id not in retained:
                    del sweeps[worker_group_id]

    def _hot_page(
        self,
        *,
        worker_group_id: str,
        now_millis: int,
        config: WorkerServiceabilityDispatchConfig,
    ) -> tuple[tuple[str, int], ...]:
        sweep = self._hot_sweeps.setdefault(
            worker_group_id,
            _ProbeScoreSweep(),
        )
        if now_millis < sweep.resume_at_millis:
            return ()
        page = tuple(
            self.worker_score.acquire_pre_epoch_hot_candidates(
                home_bucket_id=worker_group_id,
                hot_eligibility_floor_millis=self.hot_eligibility_floor_millis,
                maximum_score_exclusive=sweep.current_max_worker_score,
                limit=config.hot_scan_limit,
            )
        )
        self._advance_sweep(
            sweep,
            page=page,
            now_millis=now_millis,
            restart_delay_millis=config.probe_sweep_restart_delay_millis,
        )
        return page

    def _recovery_page(
        self,
        *,
        worker_group_id: str,
        now_millis: int,
        config: WorkerServiceabilityDispatchConfig,
    ) -> tuple[tuple[str, int], ...]:
        sweep = self._recovery_sweeps.setdefault(
            worker_group_id,
            _ProbeScoreSweep(),
        )
        if now_millis < sweep.resume_at_millis:
            return ()
        page = tuple(
            self.worker_score.acquire_recovery_recheck_candidates(
                home_bucket_id=worker_group_id,
                maximum_score_exclusive=sweep.current_max_worker_score,
                limit=config.recovery_scan_limit,
            )
        )
        self._advance_sweep(
            sweep,
            page=page,
            now_millis=now_millis,
            restart_delay_millis=config.probe_sweep_restart_delay_millis,
        )
        return page

    @staticmethod
    def _advance_sweep(
        sweep: _ProbeScoreSweep,
        *,
        page: tuple[tuple[str, int], ...],
        now_millis: int,
        restart_delay_millis: int,
    ) -> None:
        if page:
            sweep.current_max_worker_score = page[-1][1]
            sweep.resume_at_millis = 0
            return
        sweep.current_max_worker_score = WorkerScoreCore.ZERO_SCORE
        sweep.resume_at_millis = now_millis + restart_delay_millis

    def _eligible(
        self,
        state: object,
        *,
        now_millis: int,
        config: WorkerServiceabilityDispatchConfig,
    ) -> bool:
        if state is None:
            return False
        if state.polarity is WorkerScorePolarity.HOT_ACQUIRE:
            return state.time_millis < self.hot_eligibility_floor_millis
        if state.polarity is WorkerScorePolarity.RECOVERY_RECHECK:
            return (
                state.time_millis
                <= now_millis
                - (state.lane_rank + 1)
                * config.recovery_retry_interval_millis
            )
        return False

    def _cold_park_excluded(
        self,
        *,
        worker_group_id: str,
        worker_id: str,
        state: WorkerScoreState,
        max_recovery_attempts: int,
    ) -> None:
        if state.time_millis == WorkerScoreCore.PAUSE_TIME_MILLIS:
            return
        observed_score = state.score
        if state.polarity is WorkerScorePolarity.HOT_ACQUIRE:
            toggled = self.worker_score.toggle_current_polarity(
                home_bucket_id=worker_group_id,
                worker_id=worker_id,
                observed_score=state.score,
            )
            if toggled.status is not WorkerScoreTransitionStatus.TRANSITIONED:
                return
            assert toggled.score is not None
            observed_score = toggled.score
        self.worker_score.exhaust_recovery_recheck(
            home_bucket_id=worker_group_id,
            worker_id=worker_id,
            observed_score=observed_score,
            max_recovery_attempts=max_recovery_attempts,
        )


class _EvidenceKind(Enum):
    CONNECTED = "connected"
    ROUTE_UNAVAILABLE = "route-unavailable"
    PROBE_UNAVAILABLE = "probe-unavailable"


@dataclass(frozen=True, slots=True)
class _WorkerEvidence:
    observed_at_millis: int
    kind: _EvidenceKind


class WorkerServiceabilityResultPolicy:
    """Converge Adapter route evidence through explicit Score primitives."""

    def __init__(
        self,
        worker_catalog: WorkerResourceCatalog,
        worker_score: WorkerScoreCore,
        *,
        config: WorkerServiceabilityResultConfig,
        hot_eligibility_floor_millis: int,
        clock_millis: Callable[[], int] = _current_time_millis,
    ) -> None:
        if (
            isinstance(hot_eligibility_floor_millis, bool)
            or not isinstance(hot_eligibility_floor_millis, int)
            or hot_eligibility_floor_millis <= 0
            or hot_eligibility_floor_millis % WorkerScoreCore.SLOT_MILLIS != 0
        ):
            raise ValueError("HOT eligibility floor must be score-slot aligned")
        self.worker_catalog = worker_catalog
        self.worker_score = worker_score
        self.config = config
        self.hot_eligibility_floor_millis = hot_eligibility_floor_millis
        self._clock_millis = clock_millis

    def handle(self, reports: Sequence[DeliveryReport]) -> None:
        now_millis = self._clock_millis()
        latest_evidence: dict[str, _WorkerEvidence] = {}
        for report in reports:
            decoded = self._decode_report(
                report,
                now_millis=now_millis,
                evidence_max_age_millis=self.config.evidence_max_age_millis,
            )
            if decoded is None:
                continue
            for worker_id, evidence in decoded.items():
                previous = latest_evidence.get(worker_id)
                if (
                    previous is None
                    or evidence.observed_at_millis
                    >= previous.observed_at_millis
                ):
                    latest_evidence[worker_id] = evidence
        if not latest_evidence:
            return

        group_ids: dict[str, str | None] = {}
        worker_ids = tuple(latest_evidence)
        lookup_limit = WorkerResourceCatalog.MAX_WORKER_GROUP_LOOKUP_LIMIT
        for offset in range(0, len(worker_ids), lookup_limit):
            chunk = worker_ids[offset:offset + lookup_limit]
            group_ids.update(
                self.worker_catalog.get_worker_group_ids(worker_ids=chunk)
            )

        evidence_by_group: dict[str, dict[str, _WorkerEvidence]] = defaultdict(dict)
        for worker_id, evidence in latest_evidence.items():
            worker_group_id = group_ids.get(worker_id)
            if worker_group_id is not None:
                evidence_by_group[worker_group_id][worker_id] = evidence

        for worker_group_id, evidence_by_worker_id in evidence_by_group.items():
            states = self.worker_score.get_score_states(
                home_bucket_id=worker_group_id,
                worker_ids=tuple(evidence_by_worker_id),
            )
            for worker_id, evidence in evidence_by_worker_id.items():
                state = states.get(worker_id)
                if state is None:
                    continue
                self._apply_evidence(
                    worker_group_id=worker_group_id,
                    worker_id=worker_id,
                    state=state,
                    evidence=evidence,
                    max_recovery_attempts=self.config.max_recovery_attempts,
                )

    def _apply_evidence(
        self,
        *,
        worker_group_id: str,
        worker_id: str,
        state: WorkerScoreState,
        evidence: _WorkerEvidence,
        max_recovery_attempts: int,
    ) -> None:
        if state.time_millis == WorkerScoreCore.PAUSE_TIME_MILLIS:
            return
        if evidence.kind is _EvidenceKind.CONNECTED:
            self._apply_connected(
                worker_group_id=worker_group_id,
                worker_id=worker_id,
                state=state,
            )
            return
        if evidence.kind is _EvidenceKind.ROUTE_UNAVAILABLE:
            if state.polarity is WorkerScorePolarity.HOT_ACQUIRE:
                self.worker_score.toggle_current_polarity(
                    home_bucket_id=worker_group_id,
                    worker_id=worker_id,
                    observed_score=state.score,
                )
            return
        self._apply_probe_unavailable(
            worker_group_id=worker_group_id,
            worker_id=worker_id,
            state=state,
            observed_at_millis=evidence.observed_at_millis,
            max_recovery_attempts=max_recovery_attempts,
        )

    def _apply_connected(
        self,
        *,
        worker_group_id: str,
        worker_id: str,
        state: WorkerScoreState,
    ) -> None:
        if state.polarity is WorkerScorePolarity.RECOVERY_RECHECK:
            toggled = self.worker_score.toggle_current_polarity(
                home_bucket_id=worker_group_id,
                worker_id=worker_id,
                observed_score=state.score,
            )
            if toggled.status is not WorkerScoreTransitionStatus.TRANSITIONED:
                return
        self.worker_score.rewrite_current_scores(
            home_bucket_id=worker_group_id,
            worker_ids=(worker_id,),
            target_time_millis=self.hot_eligibility_floor_millis,
            target_lane_rank=WorkerScoreCore.MIN_LANE_RANK,
        )

    def _apply_probe_unavailable(
        self,
        *,
        worker_group_id: str,
        worker_id: str,
        state: WorkerScoreState,
        observed_at_millis: int,
        max_recovery_attempts: int,
    ) -> None:
        if state.polarity is WorkerScorePolarity.HOT_ACQUIRE:
            toggled = self.worker_score.toggle_current_polarity(
                home_bucket_id=worker_group_id,
                worker_id=worker_id,
                observed_score=state.score,
            )
            if toggled.status is not WorkerScoreTransitionStatus.TRANSITIONED:
                return
            self.worker_score.rewrite_current_scores(
                home_bucket_id=worker_group_id,
                worker_ids=(worker_id,),
                target_time_millis=observed_at_millis,
                target_lane_rank=WorkerScoreCore.MIN_LANE_RANK,
            )
            return

        next_attempt = state.lane_rank + 1
        if next_attempt >= max_recovery_attempts:
            self.worker_score.exhaust_recovery_recheck(
                home_bucket_id=worker_group_id,
                worker_id=worker_id,
                observed_score=state.score,
                max_recovery_attempts=max_recovery_attempts,
            )
            return
        self.worker_score.rewrite_current_scores(
            home_bucket_id=worker_group_id,
            worker_ids=(worker_id,),
            target_time_millis=observed_at_millis,
            target_lane_rank=next_attempt,
        )

    @staticmethod
    def _decode_report(
        report: DeliveryReport,
        *,
        now_millis: int,
        evidence_max_age_millis: int,
    ) -> dict[str, _WorkerEvidence] | None:
        if (
            report.src is not DeliveryEndpoint.ADAPTER
            or report.dst is not DeliveryEndpoint.KERNEL
            or report.outcome_code != "200"
            or not report.source_id
        ):
            return None
        if report.message_type == _CONNECTION_CHANGED_EVENT:
            decoded = WorkerServiceabilityResultPolicy._decode_connection_change(
                report
            )
        elif report.message_type == _DELIVERY_EXPIRED_EVENT:
            decoded = WorkerServiceabilityResultPolicy._decode_delivery_expired(
                report
            )
        elif report.message_type == _PROBE_EVENT:
            decoded = WorkerServiceabilityResultPolicy._decode_probe_snapshot(
                report
            )
        else:
            return None
        if decoded is None:
            return None
        if any(
            now_millis - evidence.observed_at_millis < 0
            or now_millis - evidence.observed_at_millis
            > evidence_max_age_millis
            for evidence in decoded.values()
        ):
            return None
        return decoded

    @staticmethod
    def _decode_connection_change(
        report: DeliveryReport,
    ) -> dict[str, _WorkerEvidence] | None:
        if report.forward != _CONNECTION_EVIDENCE_FORWARD:
            return None
        try:
            payload = json.loads(report.payload)
        except (TypeError, json.JSONDecodeError):
            return None
        if not isinstance(payload, dict) or set(payload) != {
            "workerId",
            "state",
            "observedAtMillis",
        }:
            return None
        worker_id = payload["workerId"]
        state = payload["state"]
        observed_at_millis = payload["observedAtMillis"]
        if (
            not isinstance(worker_id, str)
            or not worker_id
            or state not in _CONNECTION_STATES
            or isinstance(observed_at_millis, bool)
            or not isinstance(observed_at_millis, int)
            or observed_at_millis <= 0
        ):
            return None
        kind = (
            _EvidenceKind.CONNECTED
            if state == _CONNECTED
            else _EvidenceKind.ROUTE_UNAVAILABLE
        )
        return {worker_id: _WorkerEvidence(observed_at_millis, kind)}

    @staticmethod
    def _decode_delivery_expired(
        report: DeliveryReport,
    ) -> dict[str, _WorkerEvidence] | None:
        if report.forward != _CONNECTION_EVIDENCE_FORWARD:
            return None
        try:
            payload = json.loads(report.payload)
        except (TypeError, json.JSONDecodeError):
            return None
        if not isinstance(payload, dict) or set(payload) != {
            "workerId",
            "observedAtMillis",
        }:
            return None
        worker_id = payload["workerId"]
        observed_at_millis = payload["observedAtMillis"]
        if (
            not isinstance(worker_id, str)
            or not worker_id
            or isinstance(observed_at_millis, bool)
            or not isinstance(observed_at_millis, int)
            or observed_at_millis <= 0
        ):
            return None
        return {
            worker_id: _WorkerEvidence(
                observed_at_millis,
                _EvidenceKind.ROUTE_UNAVAILABLE,
            )
        }

    @staticmethod
    def _decode_probe_snapshot(
        report: DeliveryReport,
    ) -> dict[str, _WorkerEvidence] | None:
        if not report.forward.startswith(_PROBE_FORWARD_PREFIX):
            return None
        raw_check_started = report.forward[len(_PROBE_FORWARD_PREFIX):]
        if not raw_check_started.isdigit():
            return None
        check_started_at_millis = int(raw_check_started)
        if check_started_at_millis <= 0:
            return None
        try:
            payload = json.loads(report.payload)
        except (TypeError, json.JSONDecodeError):
            return None
        if not isinstance(payload, dict) or set(payload) != {"stateByWorkerId"}:
            return None
        states = payload["stateByWorkerId"]
        if (
            not isinstance(states, dict)
            or not 1 <= len(states) <= 100
            or any(
                not isinstance(worker_id, str) or not worker_id
                for worker_id in states
            )
        ):
            return None
        evidence: dict[str, _WorkerEvidence] = {}
        for worker_id, state in states.items():
            if state == _CONNECTED:
                kind = _EvidenceKind.CONNECTED
            elif state in _UNAVAILABLE_STATES:
                kind = _EvidenceKind.PROBE_UNAVAILABLE
            else:
                return None
            evidence[worker_id] = _WorkerEvidence(
                check_started_at_millis,
                kind,
            )
        return evidence
