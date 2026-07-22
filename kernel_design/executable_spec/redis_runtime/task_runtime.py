from __future__ import annotations

import json
from collections.abc import Mapping as MappingABC
from contextlib import suppress
from dataclasses import replace
from typing import Any, Mapping, Sequence

from ..constraint_dsl import ConstraintEvaluator
from ..kernel.task_runtime import (
    TaskType,
    MessageId,
    TaskCreationResult,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendResult,
    TaskItemAppendStatus,
    TaskResourceCatalog,
    TaskRuntime,
)
from ..kernel.task_item_score_band import (
    TaskItemScoreBandCore,
    TaskItemScoreTransitionStatus,
)
from ..kernel.task_score_band import (
    Score,
    Suffix,
    TaskId,
    TaskScoreBand,
    TaskScoreBandCore,
    TaskScoreTransitionStatus,
)


def _task_descriptor_key(prefix: str, task_id: TaskId) -> str:
    return f"tc:{prefix}:task:{task_id}"


def _task_items_key(prefix: str, task_id: TaskId) -> str:
    return f"tr:{prefix}:task:{task_id}:items"


def _task_item_results_key(prefix: str, task_id: TaskId) -> str:
    return f"tr:{prefix}:task:{task_id}:results"


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
    DEFAULT_ITEM_TTL_MILLIS = 365 * 24 * 60 * 60 * 1_000
    ITEM_PRIORITY_STEP_MILLIS = 100
    MAX_ITEM_PRIORITY = 10

    def __init__(
        self,
        redis_client: Any,
        score_band: TaskScoreBandCore,
        item_score_band: TaskItemScoreBandCore,
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
        self.item_score_band = item_score_band
        self.prefix = prefix
        self.lease_duration_millis = lease_duration_millis

    def create_task(
        self,
        *,
        descriptor: TaskDescriptor,
        suffix: Suffix,
    ) -> TaskCreationResult:
        if descriptor.empty_close_at_millis is None:
            return TaskCreationResult(
                TaskCreationStatus.INVALID,
                "descriptor empty_close_at_millis must be resolved",
            )
        try:
            if descriptor.allocation_rule is not None:
                ConstraintEvaluator.compile_match_rules(descriptor.allocation_rule)
            allocation_rule_json = (
                _encode_json(descriptor.allocation_rule)
                if descriptor.allocation_rule is not None
                else "null"
            )
            config_json = _encode_json(descriptor.config)
        except (TypeError, ValueError):
            return TaskCreationResult(
                TaskCreationStatus.INVALID,
                "descriptor allocation rule is invalid or not JSON serializable",
            )

        descriptor_fields = {
            "workerGroupId": descriptor.worker_group_id,
            "taskType": descriptor.task_type.value,
            "allocationRuleJson": allocation_rule_json,
            "configJson": config_json,
            "emptyCloseAtMillis": str(descriptor.empty_close_at_millis),
        }
        initialization = self._start_or_complete_task_creation(
            task_id=descriptor.task_id,
            suffix=suffix,
            descriptor_fields=descriptor_fields,
        )
        if isinstance(initialization, TaskCreationResult):
            return initialization
        observed_lease_score = initialization

        try:
            descriptor_created = self._write_descriptor_if_absent(
                task_id=descriptor.task_id,
                descriptor_fields=descriptor_fields,
            )
        except Exception:
            with suppress(Exception):
                self.score_band.release_observed_score_hold(
                    task_id=descriptor.task_id,
                    observed_hold_score=observed_lease_score,
                )
            raise
        if not descriptor_created:
            with suppress(Exception):
                self.score_band.release_observed_score_hold(
                    task_id=descriptor.task_id,
                    observed_hold_score=observed_lease_score,
                )
            return TaskCreationResult(
                TaskCreationStatus.CONFLICT,
                "task descriptor already exists",
            )

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

    def _start_or_complete_task_creation(
        self,
        *,
        task_id: TaskId,
        suffix: Suffix,
        descriptor_fields: Mapping[str, object],
    ) -> Score | TaskCreationResult:
        descriptor_key = _task_descriptor_key(self.prefix, task_id)
        if self.redis.exists(descriptor_key):
            return TaskCreationResult(
                TaskCreationStatus.CONFLICT,
                "task descriptor already exists",
            )

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
            state = self.score_band.get_score_states(task_ids=(task_id,)).get(
                task_id
            )
            if (
                state is not None
                and state.band is TaskScoreBand.PRE_REVIEW
                and not self.redis.exists(descriptor_key)
                and self._write_descriptor_if_absent(
                    task_id=task_id,
                    descriptor_fields=descriptor_fields,
                )
            ):
                return TaskCreationResult(TaskCreationStatus.CREATED)
            return TaskCreationResult(
                TaskCreationStatus.CONFLICT,
                "task score is already initialized outside an incomplete creation",
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

    def _write_descriptor_if_absent(
        self,
        *,
        task_id: TaskId,
        descriptor_fields: Mapping[str, object],
    ) -> bool:
        key = _task_descriptor_key(self.prefix, task_id)
        if self.redis.exists(key):
            return False
        with self.redis.pipeline(transaction=True) as pipe:
            for field, value in descriptor_fields.items():
                pipe.hsetnx(key, field, value)
            results = pipe.execute()
        return bool(results) and all(int(result) == 1 for result in results)

    def append_items(
        self,
        *,
        task_id: TaskId,
        items: Sequence[TaskItem],
    ) -> Mapping[MessageId, TaskItemAppendResult]:
        if not items:
            return {}

        ordered_items = {item.message_id: item for item in items}
        max_retry_times = self._load_max_retry_times(task_id)
        if max_retry_times is None:
            return self._item_results(
                ordered_items,
                TaskItemAppendStatus.NOT_FOUND,
            )

        records: dict[MessageId, str] = {}
        due_millis_by_message_id: dict[MessageId, int] = {}
        results: dict[MessageId, TaskItemAppendResult] = {}
        append_time_millis = self._current_time_millis()
        for message_id, item in ordered_items.items():
            try:
                if item.allocation_rule is not None:
                    ConstraintEvaluator.compile_match_rules(item.allocation_rule)
                normalized = self._materialize_item_defaults(item)
                if (
                    normalized.expire_at_millis is None
                    or append_time_millis >= normalized.expire_at_millis
                ):
                    raise ValueError("TaskItem is already expired")
                records[message_id] = self._encode_task_item(normalized)
                due_millis_by_message_id[message_id] = (
                    self._initial_due_millis(normalized)
                )
            except (TypeError, ValueError):
                results[message_id] = TaskItemAppendResult(
                    TaskItemAppendStatus.INVALID,
                    "TaskItem is invalid or not JSON serializable",
                )

        if not records:
            return results

        try:
            self.redis.hset(self._items_key(task_id), mapping=records)
        except Exception:
            results.update(
                self._item_results(records, TaskItemAppendStatus.RETRYABLE)
            )
            return results

        try:
            score_results = self.item_score_band.initialize_item_scores(
                task_id=task_id,
                initial_due_millis_by_message_id=due_millis_by_message_id,
                max_retry_times=max_retry_times,
            )
        except Exception:
            results.update(
                self._item_results(records, TaskItemAppendStatus.RETRYABLE)
            )
            return results

        for message_id, score_result in score_results.items():
            if score_result.status in {
                TaskItemScoreTransitionStatus.TRANSITIONED,
                TaskItemScoreTransitionStatus.NOOP,
            }:
                status = TaskItemAppendStatus.APPENDED
            elif score_result.status is TaskItemScoreTransitionStatus.INVALID:
                status = TaskItemAppendStatus.INVALID
            else:
                status = TaskItemAppendStatus.RETRYABLE
            results[message_id] = TaskItemAppendResult(status)
        return results

    def load_task_items(
        self,
        *,
        task_id: TaskId,
        message_ids: Sequence[MessageId],
    ) -> Mapping[MessageId, TaskItem | None]:
        unique_message_ids = tuple(dict.fromkeys(message_ids))
        if not unique_message_ids:
            return {}

        raw_items = self.redis.hmget(
            self._items_key(task_id),
            unique_message_ids,
        )
        return {
            message_id: (
                None
                if raw_item is None
                else self._decode_task_item(message_id, raw_item)
            )
            for message_id, raw_item in zip(
                unique_message_ids,
                raw_items,
                strict=True,
            )
        }

    def store_task_item_success_results(
        self,
        *,
        task_id: TaskId,
        results: Mapping[MessageId, str],
    ) -> None:
        if not task_id:
            raise ValueError("task id must be non-empty")
        if not results:
            return
        if any(
            not message_id
            or not isinstance(payload, str)
            or not payload
            for message_id, payload in results.items()
        ):
            raise ValueError("success results require non-empty ids and payloads")
        self.redis.hset(
            _task_item_results_key(self.prefix, task_id),
            mapping=dict(results),
        )

    def load_task_item_success_results(
        self,
        *,
        task_id: TaskId,
        message_ids: Sequence[MessageId],
    ) -> Mapping[MessageId, str | None]:
        if not task_id:
            raise ValueError("task id must be non-empty")
        unique_message_ids = tuple(dict.fromkeys(message_ids))
        if not unique_message_ids:
            return {}
        raw_results = self.redis.hmget(
            _task_item_results_key(self.prefix, task_id),
            unique_message_ids,
        )
        return {
            message_id: (
                raw_result.decode("utf-8")
                if isinstance(raw_result, bytes)
                else raw_result
            )
            for message_id, raw_result in zip(
                unique_message_ids,
                raw_results,
                strict=True,
            )
        }

    def _items_key(self, task_id: TaskId) -> str:
        return _task_items_key(self.prefix, task_id)

    def _load_max_retry_times(self, task_id: TaskId) -> int | None:
        raw_config = self.redis.hget(
            _task_descriptor_key(self.prefix, task_id),
            "configJson",
        )
        if raw_config is None:
            return None
        config = json.loads(raw_config)
        value = config["maxRetryTimes"]
        if not isinstance(value, str) or not value.isascii() or not value.isdecimal():
            raise ValueError("Task maxRetryTimes must be decimal text")
        return int(value)

    def _materialize_item_defaults(self, item: TaskItem) -> TaskItem:
        if item.expire_at_millis is not None:
            return item
        return replace(
            item,
            expire_at_millis=(
                item.created_at_millis + self.DEFAULT_ITEM_TTL_MILLIS
            ),
        )

    def _initial_due_millis(self, item: TaskItem) -> int:
        return max(
            0,
            item.created_at_millis
            - item.priority * self.ITEM_PRIORITY_STEP_MILLIS,
        )

    def _current_time_millis(self) -> TimeMillis:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    @staticmethod
    def _encode_task_item(item: TaskItem) -> str:
        return _encode_json(
            {
                "eventCode": item.event_code,
                "payload": item.payload,
                "priority": item.priority,
                "createdAtMillis": item.created_at_millis,
                "expireAtMillis": item.expire_at_millis,
                "allocationRule": item.allocation_rule,
            }
        )

    @staticmethod
    def _decode_task_item(message_id: MessageId, raw_item: Any) -> TaskItem | None:
        try:
            item = json.loads(raw_item)
            return TaskItem(
                message_id=message_id,
                event_code=item["eventCode"],
                payload=item["payload"],
                priority=item["priority"],
                created_at_millis=item["createdAtMillis"],
                expire_at_millis=item["expireAtMillis"],
                allocation_rule=item.get("allocationRule"),
            )
        except (KeyError, TypeError, ValueError, UnicodeDecodeError):
            return None

    @staticmethod
    def _item_results(
        message_ids: Sequence[MessageId] | Mapping[MessageId, object],
        status: TaskItemAppendStatus,
    ) -> dict[MessageId, TaskItemAppendResult]:
        return {
            message_id: TaskItemAppendResult(status)
            for message_id in message_ids
        }


class RedisTaskResourceCatalog(TaskResourceCatalog):
    """Redis HASH-backed Task allocation descriptor catalog."""

    _HASH_FIELDS = (
        "workerGroupId",
        "taskType",
        "allocationRuleJson",
        "configJson",
        "emptyCloseAtMillis",
    )

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
            (
                worker_group_raw,
                task_type_raw,
                allocation_rule_raw,
                config_raw,
                empty_close_at_millis_raw,
            ) = raw_row
            worker_group_id = (
                worker_group_raw.decode("utf-8")
                if isinstance(worker_group_raw, bytes)
                else worker_group_raw
            )
            task_type_value = (
                task_type_raw.decode("utf-8")
                if isinstance(task_type_raw, bytes)
                else task_type_raw
            )
            task_type = TaskType(task_type_value)
            allocation_rule = json.loads(allocation_rule_raw)
            config = json.loads(config_raw)
            empty_close_at_millis_value = (
                empty_close_at_millis_raw.decode("utf-8")
                if isinstance(empty_close_at_millis_raw, bytes)
                else empty_close_at_millis_raw
            )
            if (
                not isinstance(empty_close_at_millis_value, str)
                or not empty_close_at_millis_value.isascii()
                or not empty_close_at_millis_value.isdecimal()
            ):
                return None
            empty_close_at_millis = int(empty_close_at_millis_value)
        except (TypeError, ValueError, UnicodeDecodeError):
            return None

        if (
            not isinstance(worker_group_id, str)
            or not worker_group_id
            or (
                allocation_rule is not None
                and not isinstance(allocation_rule, MappingABC)
            )
            or not isinstance(config, MappingABC)
        ):
            return None
        try:
            return TaskDescriptor(
                task_id=task_id,
                worker_group_id=worker_group_id,
                task_type=task_type,
                allocation_rule=(
                    dict(allocation_rule)
                    if allocation_rule is not None
                    else None
                ),
                config=dict(config),
                empty_close_at_millis=empty_close_at_millis,
            )
        except ValueError:
            return None
