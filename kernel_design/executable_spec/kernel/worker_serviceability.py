from __future__ import annotations

from abc import ABC, abstractmethod
from enum import Enum
from typing import Mapping, Sequence

from .worker_delivery import DeliveryReport
from .worker_runtime import EndpointManagerId
from .worker_score import WorkerId


class ProbeRequestOfferStatus(Enum):
    OFFERED = "OFFERED"
    ALREADY_REQUESTED = "ALREADY_REQUESTED"
    CAPACITY = "CAPACITY"


class WorkerServiceabilityRuntime(ABC):
    """Best-effort Adapter probe requests and batch result handoff."""

    MAX_BATCH_SIZE = 100

    @abstractmethod
    def offer_probe_requests(
        self,
        *,
        adapter_id: EndpointManagerId,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, ProbeRequestOfferStatus]:
        pass

    @abstractmethod
    def consume_probe_requests(
        self,
        *,
        adapter_id: EndpointManagerId,
        limit: int,
    ) -> tuple[WorkerId, ...]:
        pass

    @abstractmethod
    def append_probe_results(
        self,
        *,
        reports: Sequence[DeliveryReport],
    ) -> int:
        pass

    @abstractmethod
    def consume_probe_results(
        self,
        *,
        limit: int,
    ) -> tuple[DeliveryReport, ...]:
        pass
