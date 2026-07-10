from __future__ import annotations

from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from typing import Iterable, Mapping


ConstraintOperator = str
ConstraintValue = object
ConstraintOperatorMap = Mapping[ConstraintOperator, ConstraintValue]
ConstraintMap = Mapping[str, ConstraintOperatorMap]

SUPPORTED_CONSTRAINT_OPERATORS = frozenset(
    {
        "$eq",
        "$equal",
        "$ne",
        "$gt",
        "$gte",
        "$lt",
        "$lte",
        "$in",
        "$exists",
    }
)

# A missing key and a failed owner read are different. Missing may satisfy
# `$exists: false`; unresolved must fail closed.
UNRESOLVED_VALUE = object()


def validate_constraint_map(constraints: object) -> ConstraintMap:
    """Validate a flat field-to-operator mapping."""

    if not isinstance(constraints, MappingABC):
        raise ValueError("constraint query must be a mapping")
    for field_name, operator_map in constraints.items():
        if not isinstance(field_name, str) or not field_name:
            raise ValueError("constraint field name must be a non-empty string")
        validate_operator_map(operator_map)
    return constraints


def validate_operator_map(operator_map: object) -> ConstraintOperatorMap:
    if not isinstance(operator_map, MappingABC) or not operator_map:
        raise ValueError("constraint field requires a non-empty operator map")

    for operator, expected in operator_map.items():
        if operator not in SUPPORTED_CONSTRAINT_OPERATORS:
            raise ValueError(f"unsupported constraint operator: {operator}")
        if operator == "$in":
            if isinstance(expected, (str, bytes)) or not isinstance(
                expected,
                SequenceABC,
            ):
                raise ValueError("$in requires a non-string sequence")
        if operator == "$exists" and not isinstance(expected, bool):
            raise ValueError("$exists requires a boolean")
    return operator_map


def matches_mapping(
    values: Mapping[str, ConstraintValue],
    constraints: ConstraintMap,
) -> bool:
    """Evaluate a validated flat rule map against one assembled flat value map."""

    return matches_fields(values, constraints, constraints)


def matches_fields(
    values: Mapping[str, ConstraintValue],
    constraints: ConstraintMap,
    field_names: Iterable[str],
) -> bool:
    """Evaluate selected fields from one validated rule map."""

    for field_name in field_names:
        operator_map = constraints[field_name]
        if field_name not in values:
            if len(operator_map) != 1 or operator_map.get("$exists") is not False:
                return False
            continue

        value = values[field_name]
        if value is UNRESOLVED_VALUE:
            return False
        if not all(
            _matches_operator(value, operator, expected)
            for operator, expected in operator_map.items()
        ):
            return False
    return True


def _matches_operator(
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
