from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC, Sequence as SequenceABC
from typing import Any, Callable, Mapping, Sequence

from ..kernel.worker_score import (
    LaneRank,
    TimeMillis,
    WorkerId,
    WorkerScoreCore,
    WorkerScorePolarity,
    WorkerScoreTransitionStatus,
)
from ..kernel.worker_runtime import (
    AttributeName,
    AttributeValue,
    DynamicAttributePayload,
    DynamicAttributeReadResult,
    WorkerDeclaration,
    WorkerDescriptor,
    WorkerDynamicAttributeRuntime,
    WorkerGroupDescriptor,
    WorkerGroupId,
    WorkerResourceCatalog,
    WorkerRuntime,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)


_DynamicAttributeUpdateFn = Callable[
    [WorkerId, DynamicAttributePayload, TimeMillis],
    WorkerRuntimeResult,
]
_DynamicAttributeQueryFn = Callable[
    [WorkerGroupId, Sequence[WorkerId]],
    Mapping[WorkerId, DynamicAttributeReadResult],
]
_DynamicAttributeUpdateHandlers = Mapping[AttributeName, _DynamicAttributeUpdateFn]
_DynamicAttributeQueryHandlers = Mapping[AttributeName, _DynamicAttributeQueryFn]


def _worker_groups_key(prefix: str) -> str:
    return f"wr:{prefix}:groups"


def _worker_descriptors_key(prefix: str, worker_group_id: WorkerGroupId) -> str:
    return f"wr:{prefix}:workers:{worker_group_id}"


def _valid_id(value: str) -> bool:
    return isinstance(value, str) and bool(value)


def _encode_worker_descriptor(descriptor: WorkerDescriptor) -> str | None:
    try:
        return json.dumps(
            {
                "workerId": descriptor.worker_id,
                "workerGroupId": descriptor.worker_group_id,
                "endpointManagerId": descriptor.endpoint_manager_id,
                "attributes": dict(descriptor.attributes),
                "platformAttributes": dict(descriptor.platform_attributes),
                "dynamicAttributeNames": sorted(descriptor.dynamic_attribute_names),
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
        return {
            worker_group_id: self._decode_worker_group_descriptor(raw)
            for worker_group_id, raw in zip(worker_group_ids, raw_rows, strict=True)
        }

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
            if descriptor is None or descriptor.worker_group_id != worker_group_id:
                result[worker_id] = None
            else:
                result[worker_id] = descriptor
        return result

    def update_worker_platform_attributes(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        attributes: Mapping[str, AttributeValue],
    ) -> WorkerRuntimeResult:
        loaded = self._load_worker_descriptor(worker_group_id, worker_id)
        if loaded[0] is not WorkerRuntimeStatus.OK:
            return WorkerRuntimeResult(loaded[0], loaded[1])
        descriptor = loaded[2]
        assert descriptor is not None

        next_descriptor = WorkerDescriptor(
            worker_id=descriptor.worker_id,
            worker_group_id=descriptor.worker_group_id,
            endpoint_manager_id=descriptor.endpoint_manager_id,
            attributes=descriptor.attributes,
            platform_attributes={
                **descriptor.platform_attributes,
                **dict(attributes),
            },
            dynamic_attribute_names=descriptor.dynamic_attribute_names,
        )
        return self._store_worker_descriptor(next_descriptor)

    def _load_worker_descriptor(
        self,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
    ) -> tuple[WorkerRuntimeStatus, str | None, WorkerDescriptor | None]:
        if not self._valid_id(worker_group_id):
            return WorkerRuntimeStatus.INVALID, "invalid workerGroupId", None
        if not self._valid_id(worker_id):
            return WorkerRuntimeStatus.INVALID, "invalid workerId", None

        descriptor = self._decode_worker_descriptor(
            self.redis.hget(self._workers_key(worker_group_id), worker_id)
        )
        if descriptor is None:
            return WorkerRuntimeStatus.NOT_FOUND, "worker descriptor not found", None
        if descriptor.worker_group_id != worker_group_id:
            return WorkerRuntimeStatus.CONFLICT, "worker group mismatch", None
        return WorkerRuntimeStatus.OK, None, descriptor

    def _store_worker_descriptor(
        self,
        descriptor: WorkerDescriptor,
    ) -> WorkerRuntimeResult:
        encoded = self._encode_worker_descriptor(descriptor)
        if encoded is None:
            return WorkerRuntimeResult(WorkerRuntimeStatus.INVALID, "invalid descriptor json")
        self.redis.hset(
            self._workers_key(descriptor.worker_group_id),
            descriptor.worker_id,
            encoded,
        )
        return WorkerRuntimeResult(WorkerRuntimeStatus.OK)

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
        try:
            return WorkerGroupDescriptor(
                worker_group_id=cls._require_string(payload["workerGroupId"]),
                attributes=cls._require_mapping(payload.get("attributes", {})),
                event_codes=frozenset(
                    cls._require_string(event_code)
                    for event_code in cls._require_sequence(payload.get("eventCodes", []))
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
        try:
            return WorkerDescriptor(
                worker_id=cls._require_string(payload["workerId"]),
                worker_group_id=cls._require_string(payload["workerGroupId"]),
                endpoint_manager_id=cls._require_string(
                    payload["endpointManagerId"]
                ),
                attributes=cls._require_mapping(payload["attributes"]),
                platform_attributes=cls._require_mapping(
                    payload["platformAttributes"]
                ),
                dynamic_attribute_names=frozenset(
                    cls._require_string(name)
                    for name in cls._require_sequence(
                        payload.get("dynamicAttributeNames", [])
                    )
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
            return raw.decode("utf-8")
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
    """Redis worker declaration and reconnect owner."""

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

        descriptor = WorkerDescriptor(
            worker_id=declaration.worker_id,
            worker_group_id=declaration.worker_group_id,
            endpoint_manager_id=declaration.endpoint_manager_id,
            attributes=dict(declaration.attributes),
            platform_attributes={},
            dynamic_attribute_names=declaration.dynamic_attribute_names,
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
        if not self.redis.hsetnx(workers_key, declaration.worker_id, encoded):
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
                or current.endpoint_manager_id != declaration.endpoint_manager_id
                or current.dynamic_attribute_names
                != declaration.dynamic_attribute_names
            ):
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.CONFLICT,
                    "worker identity declaration is immutable",
                )
            descriptor = WorkerDescriptor(
                worker_id=current.worker_id,
                worker_group_id=current.worker_group_id,
                endpoint_manager_id=current.endpoint_manager_id,
                attributes=dict(declaration.attributes),
                platform_attributes=current.platform_attributes,
                dynamic_attribute_names=current.dynamic_attribute_names,
            )
            encoded = _encode_worker_descriptor(descriptor)
            if encoded is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "invalid descriptor json",
                )
            self.redis.hset(workers_key, declaration.worker_id, encoded)

        score_state = self.score_band.get_score_states(
            home_bucket_id=declaration.worker_group_id,
            worker_ids=[declaration.worker_id],
        ).get(declaration.worker_id)
        if score_state is None:
            initialization = self.score_band.initialize_hot_acquire_score(
                home_bucket_id=declaration.worker_group_id,
                worker_id=declaration.worker_id,
                lane_rank=self.initial_lane_rank,
            )
            if initialization.status == WorkerScoreTransitionStatus.TRANSITIONED:
                return WorkerRuntimeResult(WorkerRuntimeStatus.OK)
            if initialization.status == WorkerScoreTransitionStatus.INVALID:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.INVALID,
                    "worker score initialization was rejected",
                )
            if initialization.status != WorkerScoreTransitionStatus.NOOP:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.STALE,
                    "worker score initialization could not be confirmed",
                )
            score_state = self.score_band.get_score_states(
                home_bucket_id=declaration.worker_group_id,
                worker_ids=[declaration.worker_id],
            ).get(declaration.worker_id)
            if score_state is None:
                return WorkerRuntimeResult(
                    WorkerRuntimeStatus.STALE,
                    "worker score initialization could not be observed",
                )

        if score_state.polarity == WorkerScorePolarity.HOT_ACQUIRE:
            return WorkerRuntimeResult(WorkerRuntimeStatus.OK)

        recovery = self.score_band.toggle_current_polarity(
            home_bucket_id=declaration.worker_group_id,
            worker_id=declaration.worker_id,
            observed_score=score_state.score,
            target_lane_rank=score_state.lane_rank,
        )
        if recovery.status == WorkerScoreTransitionStatus.TRANSITIONED:
            return WorkerRuntimeResult(WorkerRuntimeStatus.OK)
        if recovery.status == WorkerScoreTransitionStatus.INVALID:
            return WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "worker score recovery was rejected",
            )
        return WorkerRuntimeResult(
            WorkerRuntimeStatus.STALE,
            "worker score recovery lost its observed fence",
        )


class RedisWorkerDynamicAttributeRuntime(WorkerDynamicAttributeRuntime):
    """Redis worker dynamic-attribute owner backed by handler functions."""

    def __init__(
        self,
        catalog: WorkerResourceCatalog,
        update_handlers: _DynamicAttributeUpdateHandlers,
        query_handlers: _DynamicAttributeQueryHandlers | None = None,
    ) -> None:
        self.catalog = catalog
        self._update_handlers = update_handlers
        self._query_handlers = query_handlers or {}

    def update_worker_dynamic_attributes(
        self,
        *,
        worker_group_id: WorkerGroupId,
        worker_id: WorkerId,
        updates: Mapping[AttributeName, DynamicAttributePayload],
        observed_at_millis: int,
    ) -> Mapping[AttributeName, WorkerRuntimeResult]:
        if not updates:
            return {}

        descriptor = self.catalog.get_worker_descriptors(
            worker_group_id=worker_group_id,
            worker_ids=[worker_id],
        ).get(worker_id)
        if descriptor is None:
            return {
                attr_name: WorkerRuntimeResult(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "worker not found",
                )
                for attr_name in updates
            }

        results: dict[AttributeName, WorkerRuntimeResult] = {}
        for attr_name, payload in updates.items():
            if attr_name not in descriptor.dynamic_attribute_names:
                results[attr_name] = WorkerRuntimeResult(
                    WorkerRuntimeStatus.REJECTED,
                    "dynamic attribute is not allowed",
                )
                continue

            update_fn = self._update_handlers.get(attr_name)
            if update_fn is None:
                results[attr_name] = WorkerRuntimeResult(
                    WorkerRuntimeStatus.NOT_FOUND,
                    "dynamic attribute handler not found",
                )
                continue

            results[attr_name] = update_fn(
                worker_id,
                payload,
                observed_at_millis,
            )
        return results

    def get_worker_dynamic_attribute_values(
        self,
        *,
        worker_group_id: WorkerGroupId,
        attribute_name: AttributeName,
        worker_ids: Sequence[WorkerId],
    ) -> Mapping[WorkerId, DynamicAttributeReadResult]:
        query_fn = self._query_handlers.get(attribute_name)
        if query_fn is None:
            raise ValueError(f"missing dynamic attribute query handler: {attribute_name}")
        if not worker_ids:
            return {}

        rows = query_fn(worker_group_id, worker_ids)
        return {
            worker_id: row
            for worker_id in worker_ids
            if (row := rows.get(worker_id)) is not None
        }
