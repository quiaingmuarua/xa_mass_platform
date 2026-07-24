from __future__ import annotations

import json
from abc import ABC, abstractmethod
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Any, Sequence
from uuid import UUID


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

    command_id: str
    opaque_result_context: str
    outcome_code: str
    opaque_result_payload: str | None = None

    def __post_init__(self) -> None:
        _require_canonical_uuid(self.command_id)
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


def encode_seed_result(result: SeedResult) -> str:
    return json.dumps(
        {
            "commandId": result.command_id,
            "opaqueResultContext": result.opaque_result_context,
            "opaqueResultPayload": result.opaque_result_payload,
            "outcomeCode": result.outcome_code,
        },
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def decode_seed_result(value: str | bytes) -> SeedResult | None:
    try:
        text = value.decode("utf-8") if isinstance(value, bytes) else value
        payload = json.loads(text)
    except (TypeError, ValueError, UnicodeDecodeError):
        return None
    if not isinstance(payload, Mapping) or set(payload) != {
        "commandId",
        "opaqueResultContext",
        "opaqueResultPayload",
        "outcomeCode",
    }:
        return None
    try:
        return SeedResult(
            command_id=payload["commandId"],
            opaque_result_context=payload["opaqueResultContext"],
            outcome_code=payload["outcomeCode"],
            opaque_result_payload=payload["opaqueResultPayload"],
        )
    except (TypeError, ValueError):
        return None


def _require_canonical_uuid(value: object) -> None:
    if not isinstance(value, str) or not value:
        raise ValueError("command id must be non-empty")
    try:
        parsed = UUID(value)
    except ValueError as error:
        raise ValueError("command id must be a canonical UUID") from error
    if str(parsed) != value:
        raise ValueError("command id must be a canonical UUID")


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
