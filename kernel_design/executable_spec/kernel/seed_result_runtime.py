from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Sequence


class SeedResultOutcomeClass(Enum):
    SUCCESS = "SUCCESS"
    WORKER_FAILURE = "WORKER_FAILURE"
    ADAPTER_REJECTION = "ADAPTER_REJECTION"


SUCCESS_OUTCOME_CODE = "200"


def classify_seed_result_outcome_code(
    outcome_code: str,
) -> SeedResultOutcomeClass | None:
    """Classify the stable transport outcome protocol without parsing subcodes."""
    if outcome_code == SUCCESS_OUTCOME_CODE:
        return SeedResultOutcomeClass.SUCCESS
    if (
        isinstance(outcome_code, str)
        and len(outcome_code) == 4
        and all("0" <= character <= "9" for character in outcome_code)
    ):
        if outcome_code[0] == "1":
            return SeedResultOutcomeClass.WORKER_FAILURE
        if outcome_code[0] == "3":
            return SeedResultOutcomeClass.ADAPTER_REJECTION
    return None


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
        if classify_seed_result_outcome_code(self.outcome_code) is None:
            raise ValueError("outcome code must be 200, 1xxx, or 3xxx")
        if (
            self.outcome_code == SUCCESS_OUTCOME_CODE
            and self.opaque_result_payload is None
        ):
            raise ValueError("successful result must carry an opaque payload")
        if (
            self.opaque_result_payload is not None
            and (
                not isinstance(self.opaque_result_payload, str)
                or not self.opaque_result_payload
            )
        ):
            raise ValueError("opaque result payload must be non-empty when present")


class SeedResultRuntime(ABC):
    """Best-effort SeedResult evidence queues partitioned by outcome class."""

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
        outcome_class: SeedResultOutcomeClass,
        limit: int,
    ) -> tuple[SeedResult, ...]:
        """Consume at most limit results from one outcome-class queue."""
        pass
