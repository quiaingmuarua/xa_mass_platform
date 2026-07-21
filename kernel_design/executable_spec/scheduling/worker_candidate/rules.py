from __future__ import annotations

from collections.abc import Collection, Mapping, Sequence

from ...constraint_dsl import ConstraintEvaluator
from ...kernel.worker_runtime import WorkerDynamicAttributeRuntime
from ...kernel.worker_score import WorkerId


def validate_targeted_allocation_rule(
    allocation_rule: Mapping[str, object],
    *,
    allowed_fields: Collection[str],
    dynamic_attributes: WorkerDynamicAttributeRuntime,
) -> None:
    compiled = ConstraintEvaluator.compile_match_rules(allocation_rule)
    unsupported_fields = set(compiled) - set(allowed_fields)
    if unsupported_fields:
        raise ValueError(
            "Item allocation fields are not allowed by WorkerGroup: "
            + ", ".join(sorted(unsupported_fields))
        )

    for field_name, operator_rule in compiled.items():
        if field_name == "workerId":
            worker_ids_from_target_rule(operator_rule)
            continue
        domain, separator, attribute_name = field_name.partition(".")
        if not separator or domain != "dynamic" or not attribute_name:
            raise ValueError(
                f"Item allocation field has no candidate source: {field_name}"
            )
        if not dynamic_attributes.supports_candidate_query(
            attribute_name=attribute_name,
            operator_rule=operator_rule,
        ):
            raise ValueError(
                f"dynamic allocation field has no candidate query: {field_name}"
            )


def select_target_field(allocation_rule: Mapping[str, object]) -> str:
    if "workerId" in allocation_rule:
        return "workerId"
    fields = sorted(
        field_name
        for field_name in allocation_rule
        if field_name.startswith("dynamic.")
    )
    if not fields:
        raise ValueError("Item allocation rule has no targeted candidate field")
    return fields[0]


def worker_ids_from_target_rule(
    operator_rule: Mapping[str, object],
    *,
    limit: int | None = None,
) -> tuple[WorkerId, ...]:
    if len(operator_rule) != 1:
        raise ValueError("workerId target requires exactly one operator")
    operator, operand = next(iter(operator_rule.items()))
    if operator == "$eq":
        values = (operand,)
    elif (
        operator == "$in"
        and not isinstance(operand, (str, bytes))
        and isinstance(operand, Sequence)
    ):
        values = operand
    else:
        raise ValueError("workerId target only supports $eq or $in")

    if not values or any(
        not isinstance(value, str) or not value for value in values
    ):
        raise ValueError("workerId target values must be non-empty strings")
    worker_ids = tuple(dict.fromkeys(values))
    return worker_ids if limit is None else worker_ids[:limit]
