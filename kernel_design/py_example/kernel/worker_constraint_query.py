from __future__ import annotations

from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from dataclasses import dataclass
from typing import Mapping, cast

from ..constraint_dsl import ConstraintDsl, ConstraintMap


@dataclass(frozen=True, init=False, slots=True)
class WorkerConstraintQuery:
    """Worker-matching rules plus owner-declared external field reads."""

    acquire_fields: tuple[str, ...]
    match_rules: ConstraintMap

    def __init__(self, document: Mapping[str, object]) -> None:
        if not isinstance(document, MappingABC) or set(document) != {
            "acquire_fields",
            "match_rules",
        }:
            raise ValueError("query requires acquire_fields and match_rules")

        acquire_fields = self._validate_acquire_fields(document["acquire_fields"])
        match_rules = ConstraintDsl.compile_match_rules(document["match_rules"])
        if not set(acquire_fields).issubset(match_rules):
            raise ValueError("every acquire field must be used by match_rules")

        object.__setattr__(self, "acquire_fields", acquire_fields)
        object.__setattr__(self, "match_rules", match_rules)

    @staticmethod
    def empty() -> WorkerConstraintQuery:
        return WorkerConstraintQuery({"acquire_fields": (), "match_rules": {}})

    @staticmethod
    def _validate_acquire_fields(source: object) -> tuple[str, ...]:
        if isinstance(source, (str, bytes)) or not isinstance(source, SequenceABC):
            raise ValueError("acquire_fields must be a sequence of field paths")
        fields = tuple(source)
        if any(
            not isinstance(field_name, str)
            or not field_name
            or any(not part for part in field_name.split("."))
            for field_name in fields
        ):
            raise ValueError("acquire_fields must contain valid field paths")
        string_fields = cast(tuple[str, ...], fields)
        if len(string_fields) != len(set(string_fields)):
            raise ValueError("acquire_fields must contain unique field paths")
        return string_fields
