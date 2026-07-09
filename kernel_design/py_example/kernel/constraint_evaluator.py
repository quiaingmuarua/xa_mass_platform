from __future__ import annotations

from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from dataclasses import dataclass
from typing import Callable, Mapping


ConstraintOperator = str
ConstraintValue = object


@dataclass(frozen=True)
class ConstraintFieldResolution:
    """Resolved field value for generic constraint evaluation.

    `missing` means the field is known to be absent. `unresolved` means the
    owner could not safely resolve the field value, for example because the
    dynamic-attribute handler is missing or rejected the read.
    """

    present: bool
    value: ConstraintValue | None = None
    resolvable: bool = True

    @staticmethod
    def present_value(value: ConstraintValue) -> ConstraintFieldResolution:
        return ConstraintFieldResolution(present=True, value=value)

    @staticmethod
    def missing() -> ConstraintFieldResolution:
        return ConstraintFieldResolution(present=False)

    @staticmethod
    def unresolved() -> ConstraintFieldResolution:
        return ConstraintFieldResolution(present=False, resolvable=False)


ConstraintFieldResolver = Callable[[str], ConstraintFieldResolution]


def evaluate_constraint_operator_map(
    *,
    field: ConstraintFieldResolution,
    operator_map: Mapping[ConstraintOperator, ConstraintValue],
) -> bool:
    """Evaluate a Mongo-like operator map against one resolved field."""

    if not field.resolvable:
        return False
    if not field.present:
        return len(operator_map) == 1 and operator_map.get("$exists") is False

    return all(
        evaluate_constraint_operator(
            value=field.value,
            operator=operator,
            expected=expected,
        )
        for operator, expected in operator_map.items()
    )


def evaluate_constraint_operator(
    *,
    value: ConstraintValue,
    operator: ConstraintOperator,
    expected: ConstraintValue,
) -> bool:
    if operator in {"$eq", "$equal"}:
        return value == expected
    if operator == "$ne":
        return value != expected
    if operator == "$in":
        if isinstance(expected, (str, bytes)) or not isinstance(expected, SequenceABC):
            return False
        return value in expected
    if operator == "$exists":
        return bool(expected)
    if operator == "$gt":
        return _safe_compare(value, expected, ">")
    if operator == "$gte":
        return _safe_compare(value, expected, ">=")
    if operator == "$lt":
        return _safe_compare(value, expected, "<")
    if operator == "$lte":
        return _safe_compare(value, expected, "<=")
    return False


def _safe_compare(
    value: ConstraintValue,
    expected: ConstraintValue,
    operator: str,
) -> bool:
    try:
        if operator == ">":
            return value > expected  # type: ignore[operator]
        if operator == ">=":
            return value >= expected  # type: ignore[operator]
        if operator == "<":
            return value < expected  # type: ignore[operator]
        if operator == "<=":
            return value <= expected  # type: ignore[operator]
    except TypeError:
        return False
    return False


def require_operator_map(
    operator_map: object,
) -> Mapping[ConstraintOperator, ConstraintValue]:
    if not isinstance(operator_map, MappingABC) or not operator_map:
        raise ValueError("constraint field requires a non-empty operator map")
    return operator_map
