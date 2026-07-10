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


_ACQUIRE_FIELDS_KEY = "acquire_fields"
_MATCH_RULES_KEY = "match_rules"
_QUERY_KEYS = frozenset({_ACQUIRE_FIELDS_KEY, _MATCH_RULES_KEY})
_WORKER_ID_FIELD = "workerId"
_WORKER_ID_OPERATORS = frozenset({"$eq", "$in"})
_FIELD_PREFIXES = ("system.", "static.", "dynamic.")
_DYNAMIC_PREFIX = "dynamic."
_EMPTY_FIELD_INDEX: Mapping[str, str] = MappingProxyType({})


@dataclass(frozen=True, init=False)
class WorkerConstraintQuery:
    """Validated worker match rules plus explicit dynamic-read dependencies."""

    acquire_fields: tuple[str, ...]
    match_rules: ConstraintMap
    metadata_rules: ConstraintMap
    dynamic_rules: ConstraintMap
    system_fields: Mapping[str, str]
    static_fields: Mapping[str, str]
    dynamic_fields: Mapping[str, str]
    worker_ids: frozenset[str] | None

    def __init__(self, document: Mapping[str, object]) -> None:
        acquire_fields_source, match_rules_source = self._validate_document(document)
        acquire_fields = self._validate_acquire_fields(acquire_fields_source)
        validated_rules = validate_constraint_map(match_rules_source)

        frozen_rules: dict[str, ConstraintOperatorMap] = {}
        metadata_rules: dict[str, ConstraintOperatorMap] = {}
        dynamic_rules: dict[str, ConstraintOperatorMap] = {}
        system_fields: dict[str, str] = {}
        static_fields: dict[str, str] = {}
        dynamic_fields: dict[str, str] = {}

        for field_name, operator_map in validated_rules.items():
            self._validate_field(field_name, operator_map)
            frozen_operator_map = self._freeze_operator_map(operator_map)
            frozen_rules[field_name] = frozen_operator_map

            if field_name.startswith("system."):
                system_fields[field_name] = field_name.removeprefix("system.")
            elif field_name.startswith("static."):
                static_fields[field_name] = field_name.removeprefix("static.")
            elif field_name.startswith(_DYNAMIC_PREFIX):
                dynamic_fields[field_name] = field_name.removeprefix(_DYNAMIC_PREFIX)
                dynamic_rules[field_name] = frozen_operator_map
                continue
            metadata_rules[field_name] = frozen_operator_map

        if set(acquire_fields) != set(dynamic_fields):
            missing = sorted(set(dynamic_fields).difference(acquire_fields))
            unused = sorted(set(acquire_fields).difference(dynamic_fields))
            details: list[str] = []
            if missing:
                details.append(f"undeclared dynamic match fields: {', '.join(missing)}")
            if unused:
                details.append(f"unused dynamic acquire fields: {', '.join(unused)}")
            raise ValueError("; ".join(details))

        object.__setattr__(self, "acquire_fields", acquire_fields)
        object.__setattr__(self, "match_rules", MappingProxyType(frozen_rules))
        object.__setattr__(self, "metadata_rules", MappingProxyType(metadata_rules))
        object.__setattr__(self, "dynamic_rules", MappingProxyType(dynamic_rules))
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
        return WorkerConstraintQuery(
            {
                _ACQUIRE_FIELDS_KEY: [],
                _MATCH_RULES_KEY: {},
            }
        )

    def worker_id_filter(self) -> frozenset[str] | None:
        return self.worker_ids

    def _compile_worker_ids(self) -> frozenset[str] | None:
        operator_map = self.match_rules.get(_WORKER_ID_FIELD)
        if operator_map is None:
            return None
        if "$eq" in operator_map:
            return frozenset({self._require_worker_id(operator_map["$eq"])})
        values = operator_map["$in"]
        assert isinstance(values, SequenceABC)
        return frozenset(self._require_worker_id(value) for value in values)

    @staticmethod
    def _validate_document(
        document: Mapping[str, object],
    ) -> tuple[object, object]:
        if not isinstance(document, MappingABC):
            raise ValueError("worker constraint query must be a mapping")
        document_keys = set(document)
        if document_keys != _QUERY_KEYS:
            missing = sorted(_QUERY_KEYS.difference(document_keys))
            unknown = sorted(document_keys.difference(_QUERY_KEYS))
            details: list[str] = []
            if missing:
                details.append(f"missing query fields: {', '.join(missing)}")
            if unknown:
                details.append(f"unknown query fields: {', '.join(unknown)}")
            raise ValueError("; ".join(details))
        return document[_ACQUIRE_FIELDS_KEY], document[_MATCH_RULES_KEY]

    @staticmethod
    def _validate_acquire_fields(source: object) -> tuple[str, ...]:
        if isinstance(source, (str, bytes)) or not isinstance(source, SequenceABC):
            raise ValueError("acquire_fields must be a sequence")
        fields: list[str] = []
        seen: set[str] = set()
        for field_name in source:
            if (
                not isinstance(field_name, str)
                or not field_name.startswith(_DYNAMIC_PREFIX)
                or len(field_name) == len(_DYNAMIC_PREFIX)
            ):
                raise ValueError("acquire_fields only accepts dynamic.* fields")
            if field_name in seen:
                raise ValueError(f"duplicate acquire field: {field_name}")
            seen.add(field_name)
            fields.append(field_name)
        return tuple(fields)

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
