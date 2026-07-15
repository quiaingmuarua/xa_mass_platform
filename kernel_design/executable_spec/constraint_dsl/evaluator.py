from __future__ import annotations

from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from types import MappingProxyType
from typing import Mapping


ConstraintOperator = str
ConstraintValue = object
ConstraintOperatorMap = Mapping[ConstraintOperator, ConstraintValue]
ConstraintMap = Mapping[str, ConstraintOperatorMap]

_SUPPORTED_OPERATORS = frozenset(
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
_MISSING_VALUE = object()

# Missing may satisfy `$exists: false`; an owner read failure must fail closed.
UNRESOLVED_VALUE = object()


class ConstraintEvaluator:
    """Stateless compiler and evaluator for domain-qualified match rules."""

    @staticmethod
    def compile_match_rules(document: object) -> ConstraintMap:
        """Validate and freeze one domain-qualified match-rule document."""

        rules = ConstraintEvaluator._validate_match_rules(document)
        compiled: dict[str, ConstraintOperatorMap] = {}
        for field_name, operator_map in rules.items():
            domain, separator, field = field_name.partition(".")
            if separator and (not domain or not field):
                raise ValueError("qualified match rules require a domain and field")
            compiled[field_name] = MappingProxyType(
                {
                    operator: tuple(value)
                    if operator == "$in" and isinstance(value, SequenceABC)
                    else value
                    for operator, value in operator_map.items()
                }
            )
        return MappingProxyType(compiled)

    @staticmethod
    def evaluate_match_rules(
        context: Mapping[str, ConstraintValue],
        match_rules: ConstraintMap,
    ) -> bool:
        """Evaluate rules by splitting each qualified field at its first dot."""

        for field_name, operator_map in match_rules.items():
            value = ConstraintEvaluator._resolve_context_value(context, field_name)
            if value is _MISSING_VALUE:
                if len(operator_map) != 1 or operator_map.get("$exists") is not False:
                    return False
                continue
            if value is UNRESOLVED_VALUE:
                return False
            if not all(
                ConstraintEvaluator._matches_operator(value, operator, expected)
                for operator, expected in operator_map.items()
            ):
                return False
        return True

    @staticmethod
    def _validate_match_rules(document: object) -> ConstraintMap:
        if not isinstance(document, MappingABC):
            raise ValueError("match rules must be a mapping")
        for field_name, operator_map in document.items():
            if not isinstance(field_name, str) or not field_name:
                raise ValueError("constraint field name must be a non-empty string")
            ConstraintEvaluator._validate_operator_map(operator_map)
        return document

    @staticmethod
    def _validate_operator_map(operator_map: object) -> ConstraintOperatorMap:
        if not isinstance(operator_map, MappingABC) or not operator_map:
            raise ValueError("constraint field requires a non-empty operator map")
        for operator, expected in operator_map.items():
            if operator not in _SUPPORTED_OPERATORS:
                raise ValueError(f"unsupported constraint operator: {operator}")
            if operator == "$in" and (
                isinstance(expected, (str, bytes))
                or not isinstance(expected, SequenceABC)
            ):
                raise ValueError("$in requires a non-string sequence")
            if operator == "$exists" and not isinstance(expected, bool):
                raise ValueError("$exists requires a boolean")
        return operator_map

    @staticmethod
    def _resolve_context_value(
        context: Mapping[str, ConstraintValue],
        field_name: str,
    ) -> ConstraintValue:
        domain, separator, field = field_name.partition(".")
        if not separator:
            return context[field_name] if field_name in context else _MISSING_VALUE

        domain_values = context.get(domain, _MISSING_VALUE)
        if not isinstance(domain_values, MappingABC) or field not in domain_values:
            return _MISSING_VALUE
        return domain_values[field]

    @staticmethod
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
            return value in expected  # type: ignore[operator]
        if operator == "$exists":
            return bool(expected)
        if operator == "$gt":
            return ConstraintEvaluator._safe_compare(value, expected, ">")
        if operator == "$gte":
            return ConstraintEvaluator._safe_compare(value, expected, ">=")
        if operator == "$lt":
            return ConstraintEvaluator._safe_compare(value, expected, "<")
        if operator == "$lte":
            return ConstraintEvaluator._safe_compare(value, expected, "<=")
        return False

    @staticmethod
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
