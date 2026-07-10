from __future__ import annotations

from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping, cast

from .evaluator import ConstraintMap, ConstraintOperatorMap, validate_constraint_map


_QUERY_KEYS = frozenset({"acquire_fields", "match_rules"})
_FIELD_PREFIXES = ("system.", "static.", "dynamic.")
_DYNAMIC_PREFIX = "dynamic."


@dataclass(frozen=True, init=False, slots=True)
class WorkerConstraintQuery:
    """Validated match rules with explicit dynamic-read dependencies."""

    acquire_fields: tuple[str, ...]
    match_rules: ConstraintMap

    def __init__(self, document: Mapping[str, object]) -> None:
        if not isinstance(document, MappingABC) or set(document) != _QUERY_KEYS:
            raise ValueError("query requires acquire_fields and match_rules")

        acquire_fields = self._validate_acquire_fields(document["acquire_fields"])
        match_rules = self._freeze_match_rules(document["match_rules"])
        dynamic_rule_fields = {
            field_name
            for field_name in match_rules
            if field_name.startswith(_DYNAMIC_PREFIX)
        }
        if set(acquire_fields) != dynamic_rule_fields:
            raise ValueError(
                "acquire_fields must exactly match dynamic fields in match_rules"
            )

        object.__setattr__(self, "acquire_fields", acquire_fields)
        object.__setattr__(self, "match_rules", match_rules)

    @staticmethod
    def empty() -> WorkerConstraintQuery:
        return WorkerConstraintQuery({"acquire_fields": (), "match_rules": {}})

    @classmethod
    def _freeze_match_rules(cls, source: object) -> ConstraintMap:
        rules = validate_constraint_map(source)
        frozen_rules: dict[str, ConstraintOperatorMap] = {}
        for field_name, operator_map in rules.items():
            cls._validate_field(field_name, operator_map)
            frozen_rules[field_name] = MappingProxyType(
                {
                    operator: tuple(value)
                    if operator == "$in" and isinstance(value, SequenceABC)
                    else value
                    for operator, value in operator_map.items()
                }
            )
        return MappingProxyType(frozen_rules)

    @staticmethod
    def _validate_acquire_fields(source: object) -> tuple[str, ...]:
        if isinstance(source, (str, bytes)) or not isinstance(source, SequenceABC):
            raise ValueError("acquire_fields must be a sequence of dynamic.* fields")
        fields = tuple(source)
        if any(
            not isinstance(field_name, str)
            or not field_name.startswith(_DYNAMIC_PREFIX)
            or field_name == _DYNAMIC_PREFIX
            for field_name in fields
        ):
            raise ValueError("acquire_fields must contain unique dynamic.* fields")
        string_fields = cast(tuple[str, ...], fields)
        if len(string_fields) != len(set(string_fields)):
            raise ValueError("acquire_fields must contain unique dynamic.* fields")
        return string_fields

    @staticmethod
    def _validate_field(
        field_name: str,
        operator_map: ConstraintOperatorMap,
    ) -> None:
        if field_name == "workerId":
            if set(operator_map) not in ({"$eq"}, {"$in"}):
                raise ValueError("workerId supports exactly one $eq or $in operator")
            values = (
                operator_map["$in"]
                if "$in" in operator_map
                else (operator_map["$eq"],)
            )
            if not all(isinstance(value, str) and value for value in values):
                raise ValueError("workerId values must be non-empty strings")
            return

        if not any(
            field_name.startswith(prefix) and field_name != prefix
            for prefix in _FIELD_PREFIXES
        ):
            raise ValueError(
                "constraint field must be workerId, system.*, static.*, or dynamic.*"
            )
