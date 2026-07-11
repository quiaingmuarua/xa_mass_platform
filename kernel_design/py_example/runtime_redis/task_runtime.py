from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC
from typing import Any, Mapping, Sequence

from ..kernel.task_runtime import (
    TaskDescriptor,
    TaskDescriptorRegistrationResult,
    TaskDescriptorRegistrationStatus,
    TaskResourceCatalog,
)
from ..kernel.task_score_band import TaskId


_CREATE_TASK_DESCRIPTOR_SCRIPT = """
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call(
  'HSET',
  KEYS[1],
  'workerGroupId', ARGV[1],
  'allocationRuleJson', ARGV[2],
  'configJson', ARGV[3]
)
return 1
"""


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

    def register_task_descriptor(
        self,
        *,
        descriptor: TaskDescriptor,
    ) -> TaskDescriptorRegistrationResult:
        try:
            allocation_rule_json = self._encode_json(descriptor.allocation_rule)
            config_json = self._encode_json(descriptor.config)
        except (TypeError, ValueError):
            return TaskDescriptorRegistrationResult(
                TaskDescriptorRegistrationStatus.INVALID,
                "descriptor is not JSON serializable",
            )

        created = self.redis.eval(
            _CREATE_TASK_DESCRIPTOR_SCRIPT,
            1,
            self._task_key(descriptor.task_id),
            descriptor.worker_group_id,
            allocation_rule_json,
            config_json,
        )
        if int(created) == 0:
            return TaskDescriptorRegistrationResult(
                TaskDescriptorRegistrationStatus.CONFLICT,
                "task descriptor already exists",
            )
        return TaskDescriptorRegistrationResult(
            TaskDescriptorRegistrationStatus.REGISTERED
        )

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
        return f"tc:{self.prefix}:task:{task_id}"

    @staticmethod
    def _encode_json(payload: Mapping[str, object]) -> str:
        return json.dumps(
            dict(payload),
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )

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
        return TaskDescriptor(
            task_id=task_id,
            worker_group_id=worker_group_id,
            allocation_rule=dict(allocation_rule),
            config=dict(config),
        )
