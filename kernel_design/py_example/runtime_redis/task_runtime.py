from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC
from contextlib import suppress
from typing import Any, Mapping, Sequence

from ..constraint_dsl import ConstraintDsl
from ..kernel.task_runtime import (
    TaskCreationResult,
    TaskCreationStatus,
    TaskDescriptor,
    TaskResourceCatalog,
    TaskRuntime,
)
from ..kernel.task_score_band import (
    Score,
    Suffix,
    TaskId,
    TaskScoreBandCore,
    TaskScoreTransitionStatus,
)


def _task_descriptor_key(prefix: str, task_id: TaskId) -> str:
    return f"tc:{prefix}:task:{task_id}"


def _encode_json(payload: Mapping[str, object]) -> str:
    return json.dumps(
        dict(payload),
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


class RedisTaskRuntime(TaskRuntime):
    """Redis Task runtime owner."""

    DEFAULT_LEASE_DURATION_MILLIS = 3_000

    def __init__(
        self,
        redis_client: Any,
        score_band: TaskScoreBandCore,
        *,
        prefix: str = "default",
        lease_duration_millis: int = DEFAULT_LEASE_DURATION_MILLIS,
    ) -> None:
        if not prefix:
            raise ValueError("prefix must be non-empty")
        if lease_duration_millis <= 0:
            raise ValueError("lease_duration_millis must be positive")
        self.redis = redis_client
        self.score_band = score_band
        self.prefix = prefix
        self.lease_duration_millis = lease_duration_millis

    def create_task(
        self,
        *,
        descriptor: TaskDescriptor,
        suffix: Suffix,
    ) -> TaskCreationResult:
        try:
            ConstraintDsl.compile_match_rules(descriptor.allocation_rule)
            allocation_rule_json = _encode_json(descriptor.allocation_rule)
            config_json = _encode_json(descriptor.config)
        except (TypeError, ValueError):
            return TaskCreationResult(
                TaskCreationStatus.INVALID,
                "descriptor allocation rule is invalid or not JSON serializable",
            )

        initialization = self._initialize_task_score(
            task_id=descriptor.task_id,
            suffix=suffix,
        )
        if isinstance(initialization, TaskCreationResult):
            return initialization
        observed_lease_score = initialization

        try:
            self.redis.hset(
                _task_descriptor_key(self.prefix, descriptor.task_id),
                mapping={
                    "workerGroupId": descriptor.worker_group_id,
                    "allocationRuleJson": allocation_rule_json,
                    "configJson": config_json,
                },
            )
        except Exception:
            with suppress(Exception):
                self.score_band.release_observed_score_hold(
                    task_id=descriptor.task_id,
                    observed_hold_score=observed_lease_score,
                )
            raise

        release = self.score_band.release_observed_score_hold(
            task_id=descriptor.task_id,
            observed_hold_score=observed_lease_score,
        )
        if release.status == TaskScoreTransitionStatus.TRANSITIONED:
            return TaskCreationResult(TaskCreationStatus.CREATED)
        return TaskCreationResult(
            TaskCreationStatus.RETRYABLE,
            "task descriptor was written but score release was not accepted",
        )

    def _initialize_task_score(
        self,
        *,
        task_id: TaskId,
        suffix: Suffix,
    ) -> Score | TaskCreationResult:
        initialization = self.score_band.initialize_score(
            task_id=task_id,
            suffix=suffix,
            lease_duration_millis=self.lease_duration_millis,
        )
        if initialization.status == TaskScoreTransitionStatus.TRANSITIONED:
            if initialization.score is not None:
                return initialization.score
            return TaskCreationResult(
                TaskCreationStatus.RETRYABLE,
                "task score initialization returned no lease score",
            )
        if initialization.status == TaskScoreTransitionStatus.NOOP:
            return TaskCreationResult(
                TaskCreationStatus.CONFLICT,
                "task score is already initialized",
            )
        if initialization.status == TaskScoreTransitionStatus.INVALID:
            return TaskCreationResult(
                TaskCreationStatus.INVALID,
                "task score initialization was rejected",
            )
        return TaskCreationResult(
            TaskCreationStatus.RETRYABLE,
            "task score initialization could not be confirmed",
        )


class RedisTaskResourceCatalog(TaskResourceCatalog):
    """Redis HASH-backed Task allocation descriptor catalog."""

    _HASH_FIELDS = ("workerGroupId", "allocationRuleJson", "configJson")

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

    def load_task_allocation_descriptors(
        self,
        *,
        task_ids: Sequence[TaskId],
    ) -> Mapping[TaskId, TaskDescriptor | None]:
        unique_task_ids = list(dict.fromkeys(task_ids))
        if not unique_task_ids:
            return {}

        pipeline = self.redis.pipeline(transaction=False)
        for task_id in unique_task_ids:
            pipeline.hmget(self._task_key(task_id), list(self._HASH_FIELDS))
        raw_rows = pipeline.execute()

        return {
            task_id: self._decode_descriptor(task_id, raw_row)
            for task_id, raw_row in zip(unique_task_ids, raw_rows, strict=True)
        }

    def _task_key(self, task_id: TaskId) -> str:
        return _task_descriptor_key(self.prefix, task_id)

    @staticmethod
    def _decode_descriptor(
        task_id: TaskId,
        raw_row: Any,
    ) -> TaskDescriptor | None:
        try:
            worker_group_raw, allocation_rule_raw, config_raw = raw_row
            worker_group_id = (
                worker_group_raw.decode("utf-8")
                if isinstance(worker_group_raw, bytes)
                else worker_group_raw
            )
            allocation_rule = json.loads(allocation_rule_raw)
            config = json.loads(config_raw)
        except (TypeError, ValueError, UnicodeDecodeError):
            return None

        if (
            not isinstance(worker_group_id, str)
            or not worker_group_id
            or not isinstance(allocation_rule, MappingABC)
            or not isinstance(config, MappingABC)
        ):
            return None
        try:
            return TaskDescriptor(
                task_id=task_id,
                worker_group_id=worker_group_id,
                allocation_rule=dict(allocation_rule),
                config=dict(config),
            )
        except ValueError:
            return None
