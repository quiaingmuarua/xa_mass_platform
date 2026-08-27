from __future__ import annotations

from abc import ABC, abstractmethod
from enum import Enum
from typing import Sequence

from .worker_delivery import DeliveryReport


class TaskResultClass(Enum):
    SUCCESS = "SUCCESS"
    FAILURE = "FAILURE"


class TaskResultRuntime(ABC):
    """Best-effort Task DeliveryReport queues partitioned by result class."""

    @abstractmethod
    def append_task_results(
        self,
        *,
        result_class: TaskResultClass,
        results: Sequence[DeliveryReport],
    ) -> int:
        pass

    @abstractmethod
    def consume_task_results(
        self,
        *,
        result_class: TaskResultClass,
        limit: int,
    ) -> tuple[DeliveryReport, ...]:
        pass
