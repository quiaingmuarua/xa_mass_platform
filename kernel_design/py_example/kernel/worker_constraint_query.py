from __future__ import annotations

from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from dataclasses import dataclass, field
from typing import Mapping

from .worker_score import WorkerId


ConstraintOperator = str
ConstraintValue = object


_SUPPORTED_CONSTRAINT_OPERATORS = frozenset(
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
_WORKER_ID_CONSTRAINT_FIELD = "worker.id"
_WORKER_ID_CONSTRAINT_OPERATORS = frozenset({"$eq", "$in"})
_ATTRIBUTE_CONSTRAINT_PREFIXES = ("system.", "static.", "dynamic.")


@dataclass(frozen=True)
class WorkerConstraintQuery:
    """Small Mongo-like implicit-AND worker constraint query.

    It only expresses worker identity and attribute predicates inside an already
    selected worker group. worker_group_id and event_code stay outside the query:
    worker_group_id chooses the worker universe, and event_code is handler/group
    promise evidence rather than per-worker match input.
    """

    predicates: Mapping[str, Mapping[ConstraintOperator, ConstraintValue]] = field(
        default_factory=dict
    )

    @staticmethod
    def empty() -> WorkerConstraintQuery:
        return WorkerConstraintQuery()

    def __post_init__(self) -> None:
        for field_name, operator_map in self.predicates.items():
            self._validate_field_name(field_name)
            if not isinstance(operator_map, MappingABC) or not operator_map:
                raise ValueError("constraint field requires a non-empty operator map")

            if field_name == _WORKER_ID_CONSTRAINT_FIELD and len(operator_map) != 1:
                raise ValueError("worker.id supports exactly one operator")

            for operator, value in operator_map.items():
                self._validate_operator(field_name, operator, value)

    def worker_id_filter(self) -> frozenset[WorkerId] | None:
        """Return the optional reserved worker identity hard filter."""
        operator_map = self.predicates.get(_WORKER_ID_CONSTRAINT_FIELD)
        if operator_map is None:
            return None
        if "$eq" in operator_map:
            return frozenset({self._require_worker_id(operator_map["$eq"])})
        values = operator_map["$in"]
        assert isinstance(values, SequenceABC)
        return frozenset(self._require_worker_id(value) for value in values)

    @staticmethod
    def _validate_field_name(field_name: str) -> None:
        if not isinstance(field_name, str) or not field_name:
            raise ValueError("constraint field name must be a non-empty string")
        if field_name == _WORKER_ID_CONSTRAINT_FIELD:
            return
        if field_name.startswith(_ATTRIBUTE_CONSTRAINT_PREFIXES):
            return
        raise ValueError(
            "constraint field must be worker.id, system.*, static.*, or dynamic.*"
        )

    @staticmethod
    def _validate_operator(
        field_name: str,
        operator: str,
        value: ConstraintValue,
    ) -> None:
        if operator not in _SUPPORTED_CONSTRAINT_OPERATORS:
            raise ValueError(f"unsupported constraint operator: {operator}")
        if (
            field_name == _WORKER_ID_CONSTRAINT_FIELD
            and operator not in _WORKER_ID_CONSTRAINT_OPERATORS
        ):
            raise ValueError("worker.id only supports $eq and $in")
        if operator == "$in":
            if isinstance(value, (str, bytes)) or not isinstance(value, SequenceABC):
                raise ValueError("$in requires a non-string sequence")
            if field_name == _WORKER_ID_CONSTRAINT_FIELD:
                for worker_id in value:
                    WorkerConstraintQuery._require_worker_id(worker_id)
        if operator == "$exists" and not isinstance(value, bool):
            raise ValueError("$exists requires a boolean")
        if operator == "$eq" and field_name == _WORKER_ID_CONSTRAINT_FIELD:
            WorkerConstraintQuery._require_worker_id(value)

    @staticmethod
    def _require_worker_id(value: ConstraintValue) -> WorkerId:
        if not isinstance(value, str) or not value:
            raise ValueError("worker.id requires a non-empty string value")
        return value
