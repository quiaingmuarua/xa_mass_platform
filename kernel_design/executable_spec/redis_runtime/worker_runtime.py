from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from ..kernel.worker_score import (
    WorkerId,
    WorkerScoreCore,
    WorkerScoreTransitionStatus,
)
from ..kernel.worker_runtime import (
    AttributeValue,
    WorkerDeclaration,
    WorkerDescriptor,
    WorkerGroupDescriptor,
    WorkerGroupId,
    WorkerResourceCatalog,
    WorkerRuntime,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)


_COMPARE_AND_SET_HASH_FIELD_SCRIPT = """
local current = redis.call('HGET', KEYS[1], ARGV[1])
if not current or current ~= ARGV[2] then
    return 0
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
return 1
"""
_MAX_DESCRIPTOR_CAS_ATTEMPTS = 8


def _worker_groups_key(prefix: str) -> str:
    return f"wr:{prefix}:groups"


def _worker_metadata_key(prefix: str, worker_group_id: WorkerGroupId) -> str:
    return f"wr:{prefix}:worker-metadata:{worker_group_id}"


def _worker_properties_key(prefix: str, worker_group_id: WorkerGroupId) -> str:
    return f"wr:{prefix}:worker-properties:{worker_group_id}"


def _worker_id_owners_key(prefix: str) -> str:
    return f"wr:{prefix}:worker-id-owners"


def _valid_id(value: str) -> bool:
    return isinstance(value, str) and bool(value)


@dataclass(frozen=True)
class _WorkerMetadata:
    worker_id: WorkerId
    worker_group_id: WorkerGroupId
    endpoint_manager_id: str
    platform_properties: Mapping[str, AttributeValue]


def _encode_worker_metadata(metadata: _WorkerMetadata) -> str | None:
    try:
        return json.dumps(
            {
                "workerId": metadata.worker_id,
                "workerGroupId": metadata.worker_group_id,
                "endpointManagerId": metadata.endpoint_manager_id,
                "platformProperties": dict(metadata.platform_properties),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    except (TypeError, ValueError):
        return None


def _encode_worker_properties(
    worker_properties: Mapping[str, AttributeValue],
) -> str | None:
    try:
        return json.dumps(
            dict(worker_properties),
            sort_keys=True,
            separators=(",", ":"),
        )
    except (TypeError, ValueError):
        return None


class RedisWorkerResourceCatalog(WorkerResourceCatalog):
    """Redis-backed worker resource catalog for the executable spec."""

    def __init__(
        self,
        redis_client: Any,
        *,
        prefix: str = "default",
    ) -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        self.redis = redis_client
        self.prefix = prefix

    def register_worker_group(
        self,
        *,
        descriptor: WorkerGroupDescriptor,
    ) -> WorkerRuntimeResult:
        if not self._valid_id(descriptor.worker_group_id):
            return WorkerRuntimeResult(WorkerRuntimeStatus.INVALID, "invalid workerGroupId")
        encoded = self._encode_worker_group_descriptor(descriptor)
        if encoded is None:
            return WorkerRuntimeResult(WorkerRuntimeStatus.INVALID, "invalid descriptor json")

        if self.redis.hsetnx(
            self._groups_key(),
            descriptor.worker_group_id,
            encoded,
        ):
            return WorkerRuntimeResult(WorkerRuntimeStatus.OK)

        observed = self.redis.hget(
            self._groups_key(),
            descriptor.worker_group_id,
        )
        current = self._decode_worker_group_descriptor(observed)
        if current is None:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "stored worker group descriptor is invalid",
            )
        if current.worker_group_id != descriptor.worker_group_id:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "stored worker group identity does not match",
            )
        if current == descriptor:
            return WorkerRuntimeResult(WorkerRuntimeStatus.NOOP)
        return WorkerRuntimeResult(
            WorkerRuntimeStatus.CONFLICT,
            "worker group is already registered with a different descriptor",
        )

    def sample_worker_group_descriptors(
        self,
        *,
        sample_limit: int,
    ) -> Mapping[WorkerGroupId, WorkerGroupDescriptor | None]:
        if (
            isinstance(sample_limit, bool)
            or not isinstance(sample_limit, int)
            or sample_limit < 1
            or sample_limit > self.MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT
        ):
            raise ValueError(
                "sampleLimit must be between 1 and "
                f"{self.MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT}"
            )

        observed = self.redis.hrandfield(
            self._groups_key(),
            count=sample_limit,
            withvalues=True,
        )
        raw_values = list(observed or ())
        if len(raw_values) % 2 != 0:
            raise RuntimeError("Redis HRANDFIELD returned an invalid response")

        result: dict[WorkerGroupId, WorkerGroupDescriptor | None] = {}
        for index in range(0, len(raw_values), 2):
            worker_group_id = self._decode_optional_text(raw_values[index])
            if worker_group_id is None:
                raise RuntimeError("Redis HRANDFIELD returned an invalid field")
            descriptor = self._decode_worker_group_descriptor(raw_values[index + 1])
            result[worker_group_id] = (
                descriptor
                if descriptor is not None
                and descriptor.worker_group_id == worker_group_id
                else None
            )
        return result

    def get_worker_group_descriptors(
        self,
        *,
        worker_group_ids: Sequence[WorkerGroupId],
    ) -> Mapping[WorkerGroupId, WorkerGroupDescriptor | None]:
        if not worker_group_ids:
            return {}

        raw_rows = self.redis.hmget(self._groups_key(), list(worker_group_ids))
        result: dict[WorkerGroupId, WorkerGroupDescriptor | None] = {}
        for worker_group_id, raw in zip(
            worker_group_ids,
            raw_rows,
            strict=True,
        ):
            descriptor = self._decode_worker_group_descriptor(raw)
            result[worker_group_id] = (
                descriptor
                if descriptor is not None
                and descriptor.worker_group_id == worker_group_id
                else None
            )
        return result

    def get_worker_descriptors(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, WorkerDescriptor | None]:
        if not worker_ids:
            return {}

        if not self._valid_id(worker_group_id):
            return {worker_id: None for worker_id in worker_ids}

        metadata_rows = self.redis.hmget(
            self._metadata_key(worker_group_id),
            list(worker_ids),
        )
        property_rows = self.redis.hmget(
            self._properties_key(worker_group_id),
            list(worker_ids),
        )
        result: dict[WorkerId, WorkerDescriptor | None] = {}
        for worker_id, raw_metadata, raw_properties in zip(
            worker_ids,
            metadata_rows,
            property_rows,
            strict=True,
        ):
            result[worker_id] = self._compose_worker_descriptor(
                worker_group_id,
                worker_id,
                raw_metadata,
                raw_properties,
            )
        return result

    def get_worker_group_ids(
        self,
        *,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, WorkerGroupId | None]:
        if len(worker_ids) > self.MAX_WORKER_GROUP_LOOKUP_LIMIT:
            raise ValueError(
                "workerIds must contain at most "
                f"{self.MAX_WORKER_GROUP_LOOKUP_LIMIT} entries"
            )
        if any(not _valid_id(worker_id) for worker_id in worker_ids):
            raise ValueError("workerIds must contain only non-empty strings")
        if not worker_ids:
            return {}

        owner_rows = self.redis.hmget(
            _worker_id_owners_key(self.prefix),
            list(worker_ids),
        )
        result: dict[WorkerId, WorkerGroupId | None] = {}
        for worker_id, raw_owner in zip(worker_ids, owner_rows, strict=True):
            owner = self._decode_optional_text(raw_owner)
            result[worker_id] = owner if _valid_id(owner) else None
        return result

    def sample_worker_descriptors(
        self,
        *,
        worker_group_id: WorkerGroupId,
        sample_limit: int,
    ) -> Mapping[WorkerId, WorkerDescriptor | None]:
        if not self._valid_id(worker_group_id):
            raise ValueError("workerGroupId must be non-empty")
        if (
            isinstance(sample_limit, bool)
            or not isinstance(sample_limit, int)
            or sample_limit < 1
            or sample_limit > self.MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT
        ):
            raise ValueError(
                "sampleLimit must be between 1 and "
                f"{self.MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT}"
            )

        observed = self.redis.hrandfield(
            self._metadata_key(worker_group_id),
            count=sample_limit,
            withvalues=True,
        )
        raw_values = list(observed or ())
        if len(raw_values) % 2 != 0:
            raise RuntimeError("Redis HRANDFIELD returned an invalid response")

        sampled_worker_ids: list[WorkerId] = []
        metadata_by_worker_id: dict[WorkerId, Any] = {}
        for index in range(0, len(raw_values), 2):
            worker_id = self._decode_optional_text(raw_values[index])
            if worker_id is None:
                raise RuntimeError("Redis HRANDFIELD returned an invalid field")
            sampled_worker_ids.append(worker_id)
            metadata_by_worker_id[worker_id] = raw_values[index + 1]
        if not sampled_worker_ids:
            return {}
        property_rows = self.redis.hmget(
            self._properties_key(worker_group_id),
            sampled_worker_ids,
        )
        result: dict[WorkerId, WorkerDescriptor | None] = {}
        for worker_id, raw_properties in zip(
            sampled_worker_ids,
            property_rows,
            strict=True,
        ):
            result[worker_id] = self._compose_worker_descriptor(
                worker_group_id,
                worker_id,
                metadata_by_worker_id[worker_id],
                raw_properties,
            )
        return result

    def patch_worker_platform_properties(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        properties: Mapping[str, AttributeValue | None],
    ) -> WorkerRuntimeResult:
        for property_name, value in properties.items():
            if not isinstance(property_name, str) or not property_name:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "platform property names must be non-empty",
                )
        if not self._valid_id(worker_group_id):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid workerGroupId",
            )
        if not self._valid_id(worker_id):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid workerId",
            )

        metadata_key = self._metadata_key(worker_group_id)
        for _ in range(_MAX_DESCRIPTOR_CAS_ATTEMPTS):
            observed = self.redis.hget(metadata_key, worker_id)
            metadata = self._decode_worker_metadata(observed)
            if metadata is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker metadata not found",
                )
            if metadata.worker_id != worker_id:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "worker id mismatch",
                )
            if metadata.worker_group_id != worker_group_id:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "worker group mismatch",
                )

            next_platform_properties = dict(metadata.platform_properties)
            for property_name, value in properties.items():
                if value is None:
                    next_platform_properties.pop(property_name, None)
                else:
                    next_platform_properties[property_name] = value
            next_metadata = _WorkerMetadata(
                worker_id=metadata.worker_id,
                worker_group_id=metadata.worker_group_id,
                endpoint_manager_id=metadata.endpoint_manager_id,
                platform_properties=next_platform_properties,
            )
            encoded = self._encode_worker_metadata(next_metadata)
            if encoded is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "invalid descriptor json",
                )
            if self.redis.eval(
                _COMPARE_AND_SET_HASH_FIELD_SCRIPT,
                1,
                metadata_key,
                worker_id,
                observed,
                encoded,
            ) == 1:
                return WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        return WorkerRuntimeResult(
            WorkerRuntimeStatus.STALE,
            "worker metadata changed during platform property patch",
        )

    def _groups_key(self) -> str:
        return _worker_groups_key(self.prefix)

    def _metadata_key(self, worker_group_id: WorkerGroupId) -> str:
        return _worker_metadata_key(self.prefix, worker_group_id)

    def _properties_key(self, worker_group_id: WorkerGroupId) -> str:
        return _worker_properties_key(self.prefix, worker_group_id)

    @staticmethod
    def _valid_id(value: str) -> bool:
        return _valid_id(value)

    @classmethod
    def _encode_worker_group_descriptor(
        cls,
        descriptor: WorkerGroupDescriptor,
    ) -> str | None:
        return cls._encode_json(
            {
                "workerGroupId": descriptor.worker_group_id,
                "attributes": dict(descriptor.attributes),
                "eventCodes": sorted(descriptor.event_codes),
            }
        )

    @classmethod
    def _encode_worker_metadata(
        cls,
        metadata: _WorkerMetadata,
    ) -> str | None:
        return _encode_worker_metadata(metadata)

    @staticmethod
    def _encode_json(payload: Mapping[str, Any]) -> str | None:
        try:
            return json.dumps(payload, sort_keys=True, separators=(",", ":"))
        except (TypeError, ValueError):
            return None

    @classmethod
    def _decode_worker_group_descriptor(
        cls,
        raw: Any,
    ) -> WorkerGroupDescriptor | None:
        payload = cls._decode_json_object(raw)
        if payload is None:
            return None
        if set(payload) != {
            "workerGroupId",
            "attributes",
            "eventCodes",
        }:
            return None
        try:
            return WorkerGroupDescriptor(
                worker_group_id=cls._require_string(payload["workerGroupId"]),
                attributes=cls._require_mapping(payload["attributes"]),
                event_codes=frozenset(
                    cls._require_string(event_code)
                    for event_code in cls._require_sequence(payload["eventCodes"])
                ),
            )
        except (KeyError, TypeError, ValueError):
            return None

    @classmethod
    def _decode_worker_metadata(
        cls,
        raw: Any,
    ) -> _WorkerMetadata | None:
        payload = cls._decode_json_object(raw)
        if payload is None:
            return None
        if set(payload) != {
            "workerId",
            "workerGroupId",
            "endpointManagerId",
            "platformProperties",
        }:
            return None
        try:
            return _WorkerMetadata(
                worker_id=cls._require_string(payload["workerId"]),
                worker_group_id=cls._require_string(payload["workerGroupId"]),
                endpoint_manager_id=cls._require_string(
                    payload["endpointManagerId"]
                ),
                platform_properties=cls._require_mapping(
                    payload["platformProperties"]
                ),
            )
        except (KeyError, TypeError, ValueError):
            return None

    @classmethod
    def _decode_worker_properties(
        cls,
        raw: Any,
    ) -> Mapping[str, AttributeValue] | None:
        payload = cls._decode_json_object(raw)
        return dict(payload) if payload is not None else None

    @classmethod
    def _compose_worker_descriptor(
        cls,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        raw_metadata: Any,
        raw_properties: Any,
    ) -> WorkerDescriptor | None:
        metadata = cls._decode_worker_metadata(raw_metadata)
        worker_properties = cls._decode_worker_properties(raw_properties)
        if (
            metadata is None
            or worker_properties is None
            or metadata.worker_id != worker_id
            or metadata.worker_group_id != worker_group_id
        ):
            return None
        return WorkerDescriptor(
            worker_id=metadata.worker_id,
            worker_group_id=metadata.worker_group_id,
            endpoint_manager_id=metadata.endpoint_manager_id,
            worker_properties=worker_properties,
            platform_properties=metadata.platform_properties,
        )

    @staticmethod
    def _decode_json_object(raw: Any) -> Mapping[str, Any] | None:
        text = RedisWorkerResourceCatalog._decode_optional_text(raw)
        if text is None:
            return None
        try:
            payload = json.loads(text)
        except (TypeError, ValueError):
            return None
        if not isinstance(payload, MappingABC):
            return None
        return payload

    @staticmethod
    def _decode_optional_text(raw: Any) -> str | None:
        if raw is None:
            return None
        if isinstance(raw, bytes):
            try:
                return raw.decode("utf-8")
            except UnicodeDecodeError:
                return None
        return str(raw)

    @staticmethod
    def _require_string(value: Any) -> str:
        if not isinstance(value, str):
            raise TypeError("value must be a string")
        return value

    @staticmethod
    def _require_mapping(value: Any) -> Mapping[str, AttributeValue]:
        if not isinstance(value, MappingABC):
            raise TypeError("value must be a mapping")
        return dict(value)

    @staticmethod
    def _require_sequence(value: Any) -> Sequence[Any]:
        if isinstance(value, (str, bytes)) or not isinstance(value, SequenceABC):
            raise TypeError("value must be a sequence")
        return value


class RedisWorkerRuntime(WorkerRuntime):
    """Redis Worker runtime coordinates and property-snapshot owner."""

    def __init__(
        self,
        redis_client: Any,
        score_band: WorkerScoreCore,
        *,
        prefix: str = "default",
    ) -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        self.redis = redis_client
        self.score_band = score_band
        self.prefix = prefix

    def upsert_worker(
        self,
        *,
        declaration: WorkerDeclaration,
    ) -> WorkerRuntimeResult:
        if not _valid_id(declaration.worker_id):
            return WorkerRuntimeResult(WorkerRuntimeStatus.INVALID, "invalid workerId")
        if not _valid_id(declaration.worker_group_id):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid workerGroupId",
            )
        if not _valid_id(declaration.endpoint_manager_id):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid endpointManagerId",
            )
        if not isinstance(declaration.worker_properties, MappingABC):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid workerProperties",
            )
        encoded_properties = _encode_worker_properties(
            declaration.worker_properties
        )
        if encoded_properties is None:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid workerProperties json",
            )
        if (
            self.redis.hget(
                _worker_groups_key(self.prefix),
                declaration.worker_group_id,
            )
            is None
        ):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.NOT_FOUND,
                "worker group not found",
            )

        owner_key = _worker_id_owners_key(self.prefix)
        owner_created = bool(
            self.redis.hsetnx(
                owner_key,
                declaration.worker_id,
                declaration.worker_group_id,
            )
        )
        worker_group_owner = RedisWorkerResourceCatalog._decode_optional_text(
            self.redis.hget(owner_key, declaration.worker_id)
        )
        if worker_group_owner != declaration.worker_group_id:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.CONFLICT,
                "workerId is already owned by another workerGroupId",
            )

        metadata = _WorkerMetadata(
            worker_id=declaration.worker_id,
            worker_group_id=declaration.worker_group_id,
            endpoint_manager_id=declaration.endpoint_manager_id,
            platform_properties={},
        )
        encoded_metadata = _encode_worker_metadata(metadata)
        if encoded_metadata is None:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid worker metadata json",
            )

        metadata_key = _worker_metadata_key(
            self.prefix,
            declaration.worker_group_id,
        )
        metadata_created = bool(
            self.redis.hsetnx(
                metadata_key,
                declaration.worker_id,
                encoded_metadata,
            )
        )
        if not metadata_created:
            current = RedisWorkerResourceCatalog._decode_worker_metadata(
                self.redis.hget(metadata_key, declaration.worker_id)
            )
            if current is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "stored worker metadata is invalid",
                )
            if (
                current.worker_id != declaration.worker_id
                or current.worker_group_id != declaration.worker_group_id
                or current.endpoint_manager_id
                != declaration.endpoint_manager_id
            ):
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "worker identity declaration is immutable",
                )

        properties_key = _worker_properties_key(
            self.prefix,
            declaration.worker_group_id,
        )
        observed_properties = RedisWorkerResourceCatalog._decode_optional_text(
            self.redis.hget(properties_key, declaration.worker_id)
        )
        properties_changed = observed_properties != encoded_properties
        if properties_changed:
            self.redis.hset(
                properties_key,
                declaration.worker_id,
                encoded_properties,
            )

        score_state = self.score_band.get_score_states(
            home_bucket_id=declaration.worker_group_id,
            worker_ids=[declaration.worker_id],
        ).get(declaration.worker_id)
        score_created = False
        if score_state is None:
            initialization = self.score_band.initialize_hot_acquire_score(
                home_bucket_id=declaration.worker_group_id,
                worker_id=declaration.worker_id,
            )
            if initialization.status == WorkerScoreTransitionStatus.TRANSITIONED:
                score_created = True
            elif initialization.status == WorkerScoreTransitionStatus.INVALID:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "worker score initialization was rejected",
                )
            elif initialization.status != WorkerScoreTransitionStatus.NOOP:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.STALE,
                    "worker score initialization could not be confirmed",
                )
            if not score_created:
                score_state = self.score_band.get_score_states(
                    home_bucket_id=declaration.worker_group_id,
                    worker_ids=[declaration.worker_id],
                ).get(declaration.worker_id)
                if score_state is None:
                    return WorkerRuntimeResult(
                        WorkerRuntimeStatus.STALE,
                        "worker score initialization could not be observed",
                    )

        if (
            owner_created
            or metadata_created
            or properties_changed
            or score_created
        ):
            return WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        return WorkerRuntimeResult(WorkerRuntimeStatus.NOOP)
