from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from typing import Any, Mapping, Sequence

from ..kernel.worker_score import (
    LaneRank,
    WorkerId,
    WorkerScoreCore,
    WorkerScoreTransitionStatus,
)
from ..kernel.worker_runtime import (
    AttributeValue,
    WorkerDeclaration,
    WorkerDescriptor,
    WorkerPropertyIndex,
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


def _worker_descriptors_key(prefix: str, worker_group_id: WorkerGroupId) -> str:
    return f"wr:{prefix}:workers:{worker_group_id}"


def _worker_id_owners_key(prefix: str) -> str:
    return f"wr:{prefix}:worker-id-owners"


def _property_values_key(
    prefix: str,
    worker_group_id: WorkerGroupId,
    property_field: str,
) -> str:
    return f"wr:{prefix}:property-index:{worker_group_id}:{property_field}:values"


def _valid_id(value: str) -> bool:
    return isinstance(value, str) and bool(value)


def _valid_index_field(value: object) -> bool:
    return isinstance(value, str) and value.startswith("index.") and len(value) > 6


def _encode_worker_descriptor(descriptor: WorkerDescriptor) -> str | None:
    try:
        return json.dumps(
            {
                "workerId": descriptor.worker_id,
                "workerGroupId": descriptor.worker_group_id,
                "endpointManagerId": descriptor.endpoint_manager_id,
                "workerProperties": dict(descriptor.worker_properties),
                "platformProperties": dict(descriptor.platform_properties),
            },
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

    def upsert_worker_group(
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

        current = self._decode_worker_group_descriptor(
            self.redis.hget(self._groups_key(), descriptor.worker_group_id)
        )
        if current is None:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "stored worker group descriptor is invalid",
            )
        if (
            current.worker_group_id != descriptor.worker_group_id
            or current.event_codes != descriptor.event_codes
        ):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.CONFLICT,
                "worker group eventCodes are immutable",
            )

        self.redis.hset(self._groups_key(), descriptor.worker_group_id, encoded)
        return WorkerRuntimeResult(WorkerRuntimeStatus.OK)

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

        raw_rows = self.redis.hmget(
            self._workers_key(worker_group_id),
            list(worker_ids),
        )
        result: dict[WorkerId, WorkerDescriptor | None] = {}
        for worker_id, raw_descriptor in zip(worker_ids, raw_rows, strict=True):
            descriptor = self._decode_worker_descriptor(raw_descriptor)
            if (
                descriptor is None
                or descriptor.worker_id != worker_id
                or descriptor.worker_group_id != worker_group_id
            ):
                result[worker_id] = None
            else:
                result[worker_id] = descriptor
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
            self._workers_key(worker_group_id),
            count=sample_limit,
            withvalues=True,
        )
        raw_values = list(observed or ())
        if len(raw_values) % 2 != 0:
            raise RuntimeError("Redis HRANDFIELD returned an invalid response")

        result: dict[WorkerId, WorkerDescriptor | None] = {}
        for index in range(0, len(raw_values), 2):
            worker_id = self._decode_optional_text(raw_values[index])
            if worker_id is None:
                raise RuntimeError("Redis HRANDFIELD returned an invalid field")
            descriptor = self._decode_worker_descriptor(raw_values[index + 1])
            if (
                descriptor is None
                or descriptor.worker_id != worker_id
                or descriptor.worker_group_id != worker_group_id
            ):
                result[worker_id] = None
            else:
                result[worker_id] = descriptor
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

        workers_key = self._workers_key(worker_group_id)
        for _ in range(_MAX_DESCRIPTOR_CAS_ATTEMPTS):
            observed = self.redis.hget(workers_key, worker_id)
            descriptor = self._decode_worker_descriptor(observed)
            if descriptor is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker descriptor not found",
                )
            if descriptor.worker_id != worker_id:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "worker id mismatch",
                )
            if descriptor.worker_group_id != worker_group_id:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "worker group mismatch",
                )

            next_platform_properties = dict(descriptor.platform_properties)
            for property_name, value in properties.items():
                if value is None:
                    next_platform_properties.pop(property_name, None)
                else:
                    next_platform_properties[property_name] = value
            next_descriptor = WorkerDescriptor(
                worker_id=descriptor.worker_id,
                worker_group_id=descriptor.worker_group_id,
                endpoint_manager_id=descriptor.endpoint_manager_id,
                worker_properties=descriptor.worker_properties,
                platform_properties=next_platform_properties,
            )
            encoded = self._encode_worker_descriptor(next_descriptor)
            if encoded is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "invalid descriptor json",
                )
            if self.redis.eval(
                _COMPARE_AND_SET_HASH_FIELD_SCRIPT,
                1,
                workers_key,
                worker_id,
                observed,
                encoded,
            ) == 1:
                return WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        return WorkerRuntimeResult(
            WorkerRuntimeStatus.STALE,
            "worker descriptor changed during platform property patch",
        )

    def _groups_key(self) -> str:
        return _worker_groups_key(self.prefix)

    def _workers_key(self, worker_group_id: WorkerGroupId) -> str:
        return _worker_descriptors_key(self.prefix, worker_group_id)

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
    def _encode_worker_descriptor(
        cls,
        descriptor: WorkerDescriptor,
    ) -> str | None:
        return _encode_worker_descriptor(descriptor)

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
    def _decode_worker_descriptor(
        cls,
        raw: Any,
    ) -> WorkerDescriptor | None:
        payload = cls._decode_json_object(raw)
        if payload is None:
            return None
        if set(payload) != {
            "workerId",
            "workerGroupId",
            "endpointManagerId",
            "workerProperties",
            "platformProperties",
        }:
            return None
        try:
            return WorkerDescriptor(
                worker_id=cls._require_string(payload["workerId"]),
                worker_group_id=cls._require_string(payload["workerGroupId"]),
                endpoint_manager_id=cls._require_string(
                    payload["endpointManagerId"]
                ),
                worker_properties=cls._require_mapping(
                    payload["workerProperties"]
                ),
                platform_properties=cls._require_mapping(
                    payload["platformProperties"]
                ),
            )
        except (KeyError, TypeError, ValueError):
            return None

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
    """Redis Worker registration and worker-property snapshot owner."""

    def __init__(
        self,
        redis_client: Any,
        score_band: WorkerScoreCore,
        *,
        prefix: str = "default",
        initial_lane_rank: LaneRank = 50,
    ) -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        if (
            not isinstance(initial_lane_rank, int)
            or not score_band.MIN_LANE_RANK
            <= initial_lane_rank
            <= score_band.MAX_LANE_RANK
        ):
            raise ValueError("initial_lane_rank is out of range")
        self.redis = redis_client
        self.score_band = score_band
        self.prefix = prefix
        self.initial_lane_rank = initial_lane_rank

    def register_worker(
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

        descriptor = WorkerDescriptor(
            worker_id=declaration.worker_id,
            worker_group_id=declaration.worker_group_id,
            endpoint_manager_id=declaration.endpoint_manager_id,
            worker_properties=dict(declaration.worker_properties),
            platform_properties={},
        )
        encoded = _encode_worker_descriptor(descriptor)
        if encoded is None:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid descriptor json",
            )

        workers_key = _worker_descriptors_key(
            self.prefix,
            declaration.worker_group_id,
        )
        descriptor_created = bool(
            self.redis.hsetnx(workers_key, declaration.worker_id, encoded)
        )
        if not descriptor_created:
            current = RedisWorkerResourceCatalog._decode_worker_descriptor(
                self.redis.hget(workers_key, declaration.worker_id)
            )
            if current is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "stored worker descriptor is invalid",
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

        score_state = self.score_band.get_score_states(
            home_bucket_id=declaration.worker_group_id,
            worker_ids=[declaration.worker_id],
        ).get(declaration.worker_id)
        score_created = False
        if score_state is None:
            initialization = self.score_band.initialize_hot_acquire_score(
                home_bucket_id=declaration.worker_group_id,
                worker_id=declaration.worker_id,
                lane_rank=self.initial_lane_rank,
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

        if owner_created or descriptor_created or score_created:
            return WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        return WorkerRuntimeResult(WorkerRuntimeStatus.NOOP)

    def update_worker_properties(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        worker_properties: Mapping[str, AttributeValue],
    ) -> WorkerRuntimeResult:
        if not _valid_id(worker_group_id):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid workerGroupId",
            )
        if not _valid_id(worker_id):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid workerId",
            )
        if not isinstance(worker_properties, MappingABC):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "invalid workerProperties",
            )

        worker_group_owner = RedisWorkerResourceCatalog._decode_optional_text(
            self.redis.hget(_worker_id_owners_key(self.prefix), worker_id)
        )
        if worker_group_owner is None:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.NOT_FOUND,
                "worker not found",
            )
        if worker_group_owner != worker_group_id:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.CONFLICT,
                "workerId is owned by another workerGroupId",
            )

        workers_key = _worker_descriptors_key(self.prefix, worker_group_id)
        for _ in range(_MAX_DESCRIPTOR_CAS_ATTEMPTS):
            observed = self.redis.hget(workers_key, worker_id)
            if observed is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker descriptor not found",
                )
            current = RedisWorkerResourceCatalog._decode_worker_descriptor(
                observed
            )
            if current is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "stored worker descriptor is invalid",
                )
            if (
                current.worker_id != worker_id
                or current.worker_group_id != worker_group_id
            ):
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "stored worker identity does not match",
                )
            if current.worker_properties == worker_properties:
                return WorkerRuntimeResult(WorkerRuntimeStatus.NOOP)

            updated = WorkerDescriptor(
                worker_id=current.worker_id,
                worker_group_id=current.worker_group_id,
                endpoint_manager_id=current.endpoint_manager_id,
                worker_properties=dict(worker_properties),
                platform_properties=current.platform_properties,
            )
            encoded = _encode_worker_descriptor(updated)
            if encoded is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "invalid descriptor json",
                )
            if self.redis.eval(
                _COMPARE_AND_SET_HASH_FIELD_SCRIPT,
                1,
                workers_key,
                worker_id,
                observed,
                encoded,
            ) == 1:
                return WorkerRuntimeResult(WorkerRuntimeStatus.OK)

        return WorkerRuntimeResult(
            WorkerRuntimeStatus.STALE,
            "worker descriptor changed during snapshot refresh",
        )


class RedisHashWorkerPropertyIndexProvider:
    """Create per-field HASH projections over one shared Redis client."""

    def __init__(self, redis_client: Any, *, prefix: str = "default") -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        self.redis = redis_client
        self.prefix = prefix

    def create(self, property_field: str) -> WorkerPropertyIndex:
        if not _valid_index_field(property_field):
            raise ValueError("property index fields must use index.*")
        return RedisHashWorkerPropertyIndex(
            self,
            property_field=property_field,
        )


class RedisHashWorkerPropertyIndex(WorkerPropertyIndex):
    """One Redis-backed point-readable property projection."""

    def __init__(
        self,
        provider: RedisHashWorkerPropertyIndexProvider,
        *,
        property_field: str,
    ) -> None:
        self.provider = provider
        self._property_field = property_field

    def update(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        value: object | None,
    ) -> WorkerRuntimeResult:
        if value is None:
            self.provider.redis.hdel(
                _property_values_key(
                    self.provider.prefix,
                    worker_group_id,
                    self._property_field,
                ),
                worker_id,
            )
            return WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        try:
            encoded = self._encode_indexed_value(value)
        except (TypeError, ValueError):
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "property projection requires a JSON-compatible value",
            )
        self.provider.redis.hset(
            _property_values_key(
                self.provider.prefix,
                worker_group_id,
                self._property_field,
            ),
            worker_id,
            encoded,
        )
        return WorkerRuntimeResult(WorkerRuntimeStatus.OK)

    def load(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, object]:
        if not worker_group_id:
            raise ValueError("workerGroupId must be non-empty")
        unique_worker_ids = tuple(dict.fromkeys(worker_ids))
        if not unique_worker_ids:
            return {}
        raw_values = self.provider.redis.hmget(
            _property_values_key(
                self.provider.prefix,
                worker_group_id,
                self._property_field,
            ),
            unique_worker_ids,
        )
        loaded: dict[WorkerId, object] = {}
        for worker_id, raw_value in zip(unique_worker_ids, raw_values):
            if raw_value is None:
                continue
            loaded[worker_id] = self._decode_indexed_value(raw_value)
        return loaded

    @staticmethod
    def _encode_indexed_value(value: object) -> str:
        return json.dumps(
            {"value": value},
            ensure_ascii=True,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )

    @staticmethod
    def _decode_indexed_value(encoded_value: str | bytes) -> object:
        if isinstance(encoded_value, bytes):
            encoded_value = encoded_value.decode("utf-8")
        payload = json.loads(encoded_value)
        if not isinstance(payload, dict) or set(payload) != {"value"}:
            raise ValueError("invalid Redis property projection")
        value = payload["value"]
        if value is None:
            raise ValueError("Redis property projection cannot contain null")
        return value
