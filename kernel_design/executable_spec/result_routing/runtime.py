from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Sequence


@dataclass(frozen=True, slots=True)
class SeedResult:
    """Opaque transport outcome evidence consumed by result-routing."""

    opaque_result_context: str
    outcome_code: str
    opaque_result_payload: str | None = None

    def __post_init__(self) -> None:
        if (
            not isinstance(self.opaque_result_context, str)
            or not self.opaque_result_context
        ):
            raise ValueError("opaque result context must be non-empty")
        if not isinstance(self.outcome_code, str) or not self.outcome_code:
            raise ValueError("outcome code must be non-empty")
        if (
            self.opaque_result_payload is not None
            and (
                not isinstance(self.opaque_result_payload, str)
                or not self.opaque_result_payload
            )
        ):
            raise ValueError("opaque result payload must be non-empty when present")


class SeedResultRuntime(ABC):
    """Best-effort unified SeedResult evidence queue."""

    @abstractmethod
    def append_seed_results(
        self,
        *,
        results: Sequence[SeedResult],
    ) -> int:
        """Append one bounded result batch and return the accepted count."""
        pass

    @abstractmethod
    def consume_seed_results(
        self,
        *,
        limit: int,
    ) -> tuple[SeedResult, ...]:
        """Consume at most limit results from the single logical queue."""
        pass
