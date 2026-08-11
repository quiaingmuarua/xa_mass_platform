from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Sequence

from .worker_delivery import (
    DeliveryReport,
    DeliveryReportOutcomeClass,
)


class WorkerResultRuntime(ABC):
    """Best-effort DeliveryReport queues partitioned by outcome class."""

    @abstractmethod
    def append_worker_results(
        self,
        *,
        results: Sequence[DeliveryReport],
    ) -> int:
        pass

    @abstractmethod
    def consume_worker_results(
        self,
        *,
        outcome_class: DeliveryReportOutcomeClass,
        limit: int,
    ) -> tuple[DeliveryReport, ...]:
        pass
