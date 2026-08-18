from __future__ import annotations

import json
from collections import defaultdict
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from time import time_ns

from ..kernel.worker_delivery import DeliveryEndpoint, DeliveryReport
from ..kernel.worker_runtime import WorkerResourceCatalog
from ..kernel.worker_score import (
    WorkerScoreCore,
    WorkerScorePolarity,
    WorkerServiceabilityCheck,
)
from ..kernel.worker_serviceability import (
    ProbeRequestOfferStatus,
    WorkerServiceabilityRuntime,
)


_SYSTEM_POLLING_ENDPOINT = "system-polling"
_PROBE_EVENT = "platform.adapter.worker-connections.snapshot"
_PROBE_FORWARD_PREFIX = "worker-serviceability:v1:"
_CONNECTED = "CONNECTED"
_UNAVAILABLE_STATES = frozenset({"DISCONNECTED", "UNKNOWN"})


def _current_time_millis() -> int:
    return time_ns() // 1_000_000


@dataclass(frozen=True, slots=True)
class WorkerServiceabilityDispatchConfig:
    worker_group_ids: tuple[str, ...]
    stale_hot_after_millis: int = 300_000
    recovery_retry_interval_millis: int = 60_000
    hot_scan_limit: int = 80
    recovery_scan_limit: int = 20

    def __post_init__(self) -> None:
        if isinstance(self.worker_group_ids, (str, bytes)):
            raise ValueError("serviceability WorkerGroup ids must be a sequence")
        groups = tuple(self.worker_group_ids)
        if (
            not groups
            or len(groups) > 100
            or len(set(groups)) != len(groups)
            or any(not isinstance(group_id, str) or not group_id for group_id in groups)
        ):
            raise ValueError("serviceability WorkerGroup ids must be 1..100 unique ids")
        if any(
            isinstance(value, bool) or not isinstance(value, int) or value <= 0
            for value in (
                self.stale_hot_after_millis,
                self.recovery_retry_interval_millis,
                self.hot_scan_limit,
                self.recovery_scan_limit,
            )
        ):
            raise ValueError("serviceability durations and limits must be positive")
        if self.hot_scan_limit + self.recovery_scan_limit > 100:
            raise ValueError("serviceability scan limits must total at most 100")
        object.__setattr__(self, "worker_group_ids", groups)


@dataclass(frozen=True, slots=True)
class WorkerServiceabilityResultConfig:
    max_recovery_attempts: int = 5
    result_report_limit: int = 10

    def __post_init__(self) -> None:
        if any(
            isinstance(value, bool) or not isinstance(value, int)
            for value in (
                self.max_recovery_attempts,
                self.result_report_limit,
            )
        ):
            raise ValueError("serviceability result limits must be integers")
        if not 1 <= self.max_recovery_attempts <= WorkerScoreCore.MAX_LANE_RANK:
            raise ValueError("max recovery attempts must be in 1..99")
        if not 1 <= self.result_report_limit <= 100:
            raise ValueError("result Report limit must be in 1..100")


class WorkerServiceabilityDispatchPacer:
    """Offer stale Worker ids to Adapter-scoped probe request sets."""

    def __init__(
        self,
        worker_score: WorkerScoreCore,
        worker_catalog: WorkerResourceCatalog,
        runtime: WorkerServiceabilityRuntime,
        *,
        clock_millis: Callable[[], int] = _current_time_millis,
    ) -> None:
        self.worker_score = worker_score
        self.worker_catalog = worker_catalog
        self.runtime = runtime
        self._clock_millis = clock_millis
        self._group_cursor = 0

    def dispatch_probes(
        self,
        *,
        config: WorkerServiceabilityDispatchConfig,
    ) -> int:
        worker_group_id = config.worker_group_ids[
            self._group_cursor % len(config.worker_group_ids)
        ]
        self._group_cursor = (self._group_cursor + 1) % len(
            config.worker_group_ids
        )
        now_millis = self._clock_millis()

        hot = self.worker_score.acquire_hot_acquire_candidates(
            home_bucket_id=worker_group_id,
            limit=config.hot_scan_limit,
        )
        recovery = self.worker_score.acquire_recovery_recheck_candidates(
            home_bucket_id=worker_group_id,
            limit=config.recovery_scan_limit,
        )
        candidate_ids = tuple(dict.fromkeys((*hot, *(row[0] for row in recovery))))
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
                or descriptor.endpoint_manager_id == _SYSTEM_POLLING_ENDPOINT
            ):
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

    @staticmethod
    def _eligible(
        state: object,
        *,
        now_millis: int,
        config: WorkerServiceabilityDispatchConfig,
    ) -> bool:
        if state is None:
            return False
        if state.polarity is WorkerScorePolarity.HOT_ACQUIRE:
            return state.time_millis <= now_millis - config.stale_hot_after_millis
        if state.polarity is WorkerScorePolarity.RECOVERY_RECHECK:
            return (
                state.time_millis
                <= now_millis - config.recovery_retry_interval_millis
            )
        return False


class WorkerServiceabilityResultPacer:
    """Apply best-effort Adapter route snapshots to Worker scores."""

    def __init__(
        self,
        runtime: WorkerServiceabilityRuntime,
        worker_catalog: WorkerResourceCatalog,
        worker_score: WorkerScoreCore,
    ) -> None:
        self.runtime = runtime
        self.worker_catalog = worker_catalog
        self.worker_score = worker_score

    def route_probe_results(
        self,
        *,
        config: WorkerServiceabilityResultConfig,
    ) -> int:
        reports = self.runtime.consume_probe_results(
            limit=config.result_report_limit,
        )
        applied = 0
        for report in reports:
            decoded = self._decode_report(report)
            if decoded is None:
                continue
            check_started_at_millis, serviceable_by_worker_id = decoded
            group_ids = self.worker_catalog.get_worker_group_ids(
                worker_ids=tuple(serviceable_by_worker_id),
            )
            checks_by_group: dict[
                str, dict[str, WorkerServiceabilityCheck]
            ] = defaultdict(dict)
            for worker_id, serviceable in serviceable_by_worker_id.items():
                worker_group_id = group_ids.get(worker_id)
                if worker_group_id is None:
                    continue
                checks_by_group[worker_group_id][worker_id] = (
                    WorkerServiceabilityCheck(
                        check_started_at_millis=check_started_at_millis,
                        serviceable=serviceable,
                    )
                )
            for worker_group_id, checks in checks_by_group.items():
                self.worker_score.apply_worker_serviceability_checks(
                    home_bucket_id=worker_group_id,
                    checks_by_worker_id=checks,
                    max_recovery_attempts=config.max_recovery_attempts,
                )
                applied += len(checks)
        return applied

    @staticmethod
    def _decode_report(
        report: DeliveryReport,
    ) -> tuple[int, dict[str, bool]] | None:
        if (
            report.src is not DeliveryEndpoint.ADAPTER
            or report.dst is not DeliveryEndpoint.KERNEL
            or report.message_type != _PROBE_EVENT
            or report.outcome_code != "200"
            or not report.source_id
            or not report.forward.startswith(_PROBE_FORWARD_PREFIX)
        ):
            return None
        raw_check_started = report.forward[len(_PROBE_FORWARD_PREFIX):]
        if not raw_check_started.isdigit():
            return None
        try:
            check_started_at_millis = int(raw_check_started)
        except ValueError:
            return None
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
            or any(not isinstance(worker_id, str) or not worker_id for worker_id in states)
        ):
            return None
        serviceable_by_worker_id: dict[str, bool] = {}
        for worker_id, state in states.items():
            if state == _CONNECTED:
                serviceable_by_worker_id[worker_id] = True
            elif state in _UNAVAILABLE_STATES:
                serviceable_by_worker_id[worker_id] = False
            else:
                return None
        return check_started_at_millis, serviceable_by_worker_id
