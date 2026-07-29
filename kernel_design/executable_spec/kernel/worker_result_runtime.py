from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Sequence

from .worker_delivery import (
    WorkerResult,
    WorkerResultOutcomeClass,
)


class WorkerResultRuntime(ABC):
    """Best-effort WorkerResult queues partitioned by outcome class."""

    @abstractmethod
    def append_worker_results(
        self,
        *,
        results: Sequence[WorkerResult],
    ) -> int:
        pass

    @abstractmethod
    def consume_worker_results(
        self,
        *,
        outcome_class: WorkerResultOutcomeClass,
        limit: int,
    ) -> tuple[WorkerResult, ...]:
        pass
