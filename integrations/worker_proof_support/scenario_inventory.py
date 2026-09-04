"""Materialize deterministic Scenario Worker JSONL inventories."""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any


PHONE_GROUP = "scenario-phone-number-workers"
STRING_GROUP = "scenario-string-utils-workers"
MAX_RECORDS_PER_FILE = 100
MAX_RECORDS_PER_GROUP = 15_000
_RESERVED_PROPERTIES = frozenset({"labInventoryKey", "labInventoryLine"})


def canonical_100_worker_world() -> dict[str, tuple[dict[str, object], ...]]:
    """Return the two-group, 100-Worker correctness baseline."""
    return _canonical_two_group_world(workers_per_group=50)


def canonical_1000_worker_world() -> dict[str, tuple[dict[str, object], ...]]:
    """Return the two-group, 1,000-Worker convergence baseline."""
    return _canonical_two_group_world(workers_per_group=500)


def _canonical_two_group_world(
    *,
    workers_per_group: int,
) -> dict[str, tuple[dict[str, object], ...]]:
    return {
        PHONE_GROUP: _canonical_group(
            capability="libphonenumber",
            worker_count=workers_per_group,
        ),
        STRING_GROUP: _canonical_group(
            capability="string-utils",
            worker_count=workers_per_group,
        ),
    }


def inventory_coordinates(
    groups: Mapping[str, Sequence[Mapping[str, object]]],
) -> dict[str, tuple[str, ...]]:
    """Return the deterministic Lab keys produced for complete properties."""
    validated = _validate_groups(groups)
    return {
        group_id: tuple(
            _coordinate(index)
            for index in range(len(properties))
        )
        for group_id, properties in validated.items()
    }


def materialize_inventory(
    root: Path,
    groups: Mapping[str, Sequence[Mapping[str, object]]],
) -> dict[str, tuple[str, ...]]:
    """Write a fresh Scenario inventory and return its Lab coordinates."""
    root = Path(root).resolve()
    validated = _validate_groups(groups)
    group_directories = {
        group_id: root / group_id
        for group_id in validated
    }
    existing = [
        str(path)
        for path in group_directories.values()
        if path.exists()
    ]
    if existing:
        raise ValueError(
            "Scenario inventory group directories already exist: "
            + ", ".join(existing)
        )

    root.mkdir(parents=True, exist_ok=True)
    for group_id, properties_by_worker in validated.items():
        directory = group_directories[group_id]
        directory.mkdir()
        for offset in range(0, len(properties_by_worker), MAX_RECORDS_PER_FILE):
            file_index = offset // MAX_RECORDS_PER_FILE
            filename = _inventory_filename(file_index)
            lines = []
            for line_index, source_properties in enumerate(
                properties_by_worker[offset:offset + MAX_RECORDS_PER_FILE],
                start=1,
            ):
                properties = dict(source_properties)
                properties["labInventoryKey"] = filename
                properties["labInventoryLine"] = line_index
                lines.append(_encode_document(properties))
            (directory / filename).write_text(
                "\n".join(lines) + "\n",
                encoding="utf-8",
                newline="\n",
            )
    return inventory_coordinates(validated)


def _canonical_group(
    *,
    capability: str,
    worker_count: int,
) -> tuple[dict[str, object], ...]:
    return tuple(
        {
            "runtime": "java",
            "capability": capability,
            "region": "local",
            "labSlot": index,
            "convergenceSlot": "A",
        }
        for index in range(1, worker_count + 1)
    )


def _validate_groups(
    groups: Mapping[str, Sequence[Mapping[str, object]]],
) -> dict[str, tuple[dict[str, object], ...]]:
    if not isinstance(groups, Mapping) or not groups:
        raise ValueError("groups must contain at least one WorkerGroup")
    validated: dict[str, tuple[dict[str, object], ...]] = {}
    entries = []
    for raw_group_id, raw_properties in groups.items():
        group_id = _require_path_segment(raw_group_id, "workerGroupId")
        entries.append((group_id, raw_properties))

    for group_id, raw_properties in sorted(entries, key=lambda entry: entry[0]):
        if group_id in validated:
            raise ValueError(f"duplicate normalized workerGroupId: {group_id}")
        if isinstance(raw_properties, (str, bytes)) or not isinstance(
            raw_properties,
            Sequence,
        ):
            raise ValueError(f"{group_id} records must be a sequence")
        if not 1 <= len(raw_properties) <= MAX_RECORDS_PER_GROUP:
            raise ValueError(
                f"{group_id} must contain 1..{MAX_RECORDS_PER_GROUP} records"
            )
        copied = []
        for index, raw_value in enumerate(raw_properties, start=1):
            if not isinstance(raw_value, Mapping):
                raise ValueError(
                    f"{group_id} record {index} properties must be an object"
                )
            properties = dict(raw_value)
            reserved = sorted(_RESERVED_PROPERTIES.intersection(properties))
            if reserved:
                raise ValueError(
                    f"{group_id} record {index} contains reserved properties: "
                    + ", ".join(reserved)
                )
            _encode_document(properties)
            copied.append(properties)
        validated[group_id] = tuple(copied)
    return validated


def _encode_document(properties: Mapping[str, Any]) -> str:
    try:
        return json.dumps(
            {
                "schemaVersion": 2,
                "workerProperties": properties,
            },
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        )
    except (TypeError, ValueError) as error:
        raise ValueError("workerProperties must be strict JSON") from error


def _coordinate(worker_index: int) -> str:
    file_index = worker_index // MAX_RECORDS_PER_FILE
    line_number = worker_index % MAX_RECORDS_PER_FILE + 1
    return f"{_inventory_filename(file_index)}:{line_number}"


def _inventory_filename(file_index: int) -> str:
    return f"workers-{file_index:03d}.jsonl"


def _require_path_segment(value: object, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-blank")
    normalized = value.strip()
    if (
        normalized in {".", ".."}
        or "/" in normalized
        or "\\" in normalized
        or Path(normalized).name != normalized
    ):
        raise ValueError(f"{name} must be one path segment")
    return normalized
