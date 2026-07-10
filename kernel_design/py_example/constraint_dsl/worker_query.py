from __future__ import annotations

from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping

from .evaluator import (
    ConstraintMap,
    ConstraintOperatorMap,
    ConstraintValue,
    validate_constraint_map,
)


_WORKER_ID_FIELD = "workerId"
_WORKER_ID_OPERATORS = frozenset({"$eq", "$in"})
_FIELD_PREFIXES = ("system.", "static.", "dynamic.")
_EMPTY_FIELD_INDEX: Mapping[str, str] = MappingProxyType({})


@dataclass(frozen=True, init=False)
class WorkerConstraintQuery:
    """Validated flat worker constraint DSL with precompiled field indexes."""

    predicates: ConstraintMap
    non_dynamic_predicates: ConstraintMap
    dynamic_predicates: ConstraintMap
    system_fields: Mapping[str, str]
    static_fields: Mapping[str, str]
    dynamic_fields: Mapping[str, str]
    worker_ids: frozenset[str] | None

    def __init__(self, document: Mapping[str, object] | None = None) -> None:
        source = {} if document is None else document
        validated = validate_constraint_map(source)

        frozen_predicates: dict[str, ConstraintOperatorMap] = {}
        non_dynamic_predicates: dict[str, ConstraintOperatorMap] = {}
        dynamic_predicates: dict[str, ConstraintOperatorMap] = {}
        system_fields: dict[str, str] = {}
        static_fields: dict[str, str] = {}
        dynamic_fields: dict[str, str] = {}

        for field_name, operator_map in validated.items():
            self._validate_field(field_name, operator_map)
            frozen_operator_map = self._freeze_operator_map(operator_map)
            frozen_predicates[field_name] = frozen_operator_map

            if field_name.startswith("system."):
                system_fields[field_name] = field_name.removeprefix("system.")
            elif field_name.startswith("static."):
                static_fields[field_name] = field_name.removeprefix("static.")
            elif field_name.startswith("dynamic."):
                dynamic_fields[field_name] = field_name.removeprefix("dynamic.")
                dynamic_predicates[field_name] = frozen_operator_map
                continue
            if field_name == _WORKER_ID_FIELD:
                continue
            non_dynamic_predicates[field_name] = frozen_operator_map

        object.__setattr__(
            self,
            "predicates",
            MappingProxyType(frozen_predicates),
        )
        object.__setattr__(
            self,
            "non_dynamic_predicates",
            MappingProxyType(non_dynamic_predicates),
        )
        object.__setattr__(
            self,
            "dynamic_predicates",
            MappingProxyType(dynamic_predicates),
        )
        object.__setattr__(
            self,
            "system_fields",
            MappingProxyType(system_fields) if system_fields else _EMPTY_FIELD_INDEX,
        )
        object.__setattr__(
            self,
            "static_fields",
            MappingProxyType(static_fields) if static_fields else _EMPTY_FIELD_INDEX,
        )
        object.__setattr__(
            self,
            "dynamic_fields",
            MappingProxyType(dynamic_fields) if dynamic_fields else _EMPTY_FIELD_INDEX,
        )
        object.__setattr__(self, "worker_ids", self._compile_worker_ids())

    @staticmethod
    def empty() -> WorkerConstraintQuery:
        return WorkerConstraintQuery()

    def worker_id_filter(self) -> frozenset[str] | None:
        return self.worker_ids

    def _compile_worker_ids(self) -> frozenset[str] | None:
        operator_map = self.predicates.get(_WORKER_ID_FIELD)
        if operator_map is None:
            return None
        if "$eq" in operator_map:
            return frozenset({self._require_worker_id(operator_map["$eq"])})
        values = operator_map["$in"]
        assert isinstance(values, SequenceABC)
        return frozenset(self._require_worker_id(value) for value in values)

    @staticmethod
    def _freeze_operator_map(
        operator_map: ConstraintOperatorMap,
    ) -> ConstraintOperatorMap:
        frozen_values = {
            operator: tuple(value)
            if operator == "$in" and isinstance(value, SequenceABC)
            else value
            for operator, value in operator_map.items()
        }
        return MappingProxyType(frozen_values)

    @classmethod
    def _validate_field(
        cls,
        field_name: str,
        operator_map: ConstraintOperatorMap,
    ) -> None:
        if field_name == _WORKER_ID_FIELD:
            cls._validate_worker_id(operator_map)
            return

        for prefix in _FIELD_PREFIXES:
            if field_name.startswith(prefix):
                if len(field_name) == len(prefix):
                    raise ValueError(f"{prefix} constraint requires an attribute name")
                return
        raise ValueError(
            "constraint field must be workerId, system.*, static.*, or dynamic.*"
        )

    @classmethod
    def _validate_worker_id(cls, operator_map: ConstraintOperatorMap) -> None:
        if len(operator_map) != 1:
            raise ValueError("workerId supports exactly one operator")
        operator = next(iter(operator_map))
        if operator not in _WORKER_ID_OPERATORS:
            raise ValueError("workerId only supports $eq and $in")

        if operator == "$eq":
            cls._require_worker_id(operator_map[operator])
            return
        values = operator_map[operator]
        assert isinstance(values, SequenceABC)
        for worker_id in values:
            cls._require_worker_id(worker_id)

    @staticmethod
    def _require_worker_id(value: ConstraintValue) -> str:
        if not isinstance(value, str) or not value:
            raise ValueError("workerId requires a non-empty string value")
        return value
