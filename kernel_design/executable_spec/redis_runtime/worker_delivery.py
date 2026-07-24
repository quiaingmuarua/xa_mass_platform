from __future__ import annotations

from collections.abc import Mapping as MappingABC
from typing import Any, Mapping, Sequence

from ..kernel.task_score_band import TimeMillis
from ..kernel.worker_delivery import (
    WorkerCommandAppendStatus,
    WorkerCommandConsumePage,
    WorkerCommandEnvelope,
    WorkerCommandRuntime,
    decode_worker_command_envelope,
    encode_worker_command_envelope,
)
from ..kernel.worker_runtime import EndpointManagerId
from ..kernel.worker_score import WorkerId


_CONSUME_WORKER_COMMAND_SCRIPT = """
local current = redis.call('HGET', KEYS[1], ARGV[1])
if not current then
    return {}
end
redis.call('HDEL', KEYS[1], ARGV[1])
return {ARGV[1], current}
"""

_CONSUME_SCANNED_WORKER_COMMANDS_SCRIPT = """
local results = {}
for index = 1, #ARGV, 2 do
    local worker_id = ARGV[index]
    local observed = ARGV[index + 1]
    local current = redis.call('HGET', KEYS[1], worker_id)
    if current and current == observed then
        redis.call('HDEL', KEYS[1], worker_id)
        table.insert(results, worker_id)
        table.insert(results, current)
    end
end
return results
"""


class RedisWorkerCommandRuntime(WorkerCommandRuntime):
    """Redis-backed sparse Adapter Worker-command mailbox runtime."""

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

    def append_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_commands_by_worker_id: Mapping[
            WorkerId,
            WorkerCommandEnvelope,
        ],
    ) -> Mapping[WorkerId, WorkerCommandAppendStatus]:
        self._validate_endpoint_manager_id(endpoint_manager_id)
        if not worker_commands_by_worker_id:
            return {}

        worker_ids = tuple(worker_commands_by_worker_id)
        self._validate_worker_ids(worker_ids)
        now_millis = self._current_time_millis()
        if any(
            command.execute_before_millis <= now_millis
            for command in worker_commands_by_worker_id.values()
        ):
            raise ValueError("Worker command deadline must be in the future")

        key = self._worker_command_key(endpoint_manager_id)
        encoded_by_worker_id = {
            worker_id: encode_worker_command_envelope(command)
            for worker_id, command in worker_commands_by_worker_id.items()
        }
        with self.redis.pipeline(transaction=False) as pipeline:
            for worker_id, encoded_command in encoded_by_worker_id.items():
                pipeline.hsetnx(key, worker_id, encoded_command)
            inserted = pipeline.execute()

        replaced = {
            worker_id: encoded_by_worker_id[worker_id]
            for worker_id, was_inserted in zip(worker_ids, inserted)
            if not was_inserted
        }
        if replaced:
            self.redis.hset(key, mapping=replaced)

        return {
            worker_id: (
                WorkerCommandAppendStatus.APPENDED
                if was_inserted
                else WorkerCommandAppendStatus.REPLACED
            )
            for worker_id, was_inserted in zip(worker_ids, inserted)
        }

    def consume_worker_command(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_id: WorkerId,
    ) -> WorkerCommandEnvelope | None:
        self._validate_endpoint_manager_id(endpoint_manager_id)
        self._validate_worker_ids((worker_id,))
        raw_result = self.redis.eval(
            _CONSUME_WORKER_COMMAND_SCRIPT,
            1,
            self._worker_command_key(endpoint_manager_id),
            worker_id,
        )
        values = self._decode_text_sequence(raw_result)
        if not values:
            return None
        command = decode_worker_command_envelope(values[1])
        if (
            command is None
            or command.execute_before_millis <= self._current_time_millis()
        ):
            return None
        return command

    def consume_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        cursor: str | None,
        scan_count: int,
    ) -> WorkerCommandConsumePage:
        self._validate_endpoint_manager_id(endpoint_manager_id)
        if (
            isinstance(scan_count, bool)
            or not isinstance(scan_count, int)
            or scan_count <= 0
        ):
            raise ValueError("scan count must be positive")
        if cursor is not None and (
            not isinstance(cursor, str) or not cursor.isdecimal()
        ):
            raise ValueError("cursor must be a non-negative Redis cursor")

        key = self._worker_command_key(endpoint_manager_id)
        next_cursor, scanned = self.redis.hscan(
            key,
            cursor=0 if cursor is None else int(cursor),
            count=scan_count,
        )
        next_cursor_value = None if int(next_cursor) == 0 else str(next_cursor)
        if not scanned:
            return WorkerCommandConsumePage({}, next_cursor_value)
        if not isinstance(scanned, MappingABC):
            raise RuntimeError("Redis HSCAN returned an invalid response")

        script_args: list[Any] = []
        for worker_id, raw_command in scanned.items():
            script_args.extend((worker_id, raw_command))
        raw_result = self.redis.eval(
            _CONSUME_SCANNED_WORKER_COMMANDS_SCRIPT,
            1,
            key,
            *script_args,
        )
        values = self._decode_text_sequence(raw_result)
        now_millis = self._current_time_millis()
        commands: dict[WorkerId, WorkerCommandEnvelope] = {}
        for index in range(0, len(values), 2):
            worker_id = values[index]
            command = decode_worker_command_envelope(values[index + 1])
            if (
                command is not None
                and command.execute_before_millis > now_millis
            ):
                commands[worker_id] = command
        return WorkerCommandConsumePage(commands, next_cursor_value)

    def _current_time_millis(self) -> TimeMillis:
        seconds, microseconds = self.redis.time()
        return int(seconds) * 1_000 + int(microseconds) // 1_000

    def _worker_command_key(
        self,
        endpoint_manager_id: EndpointManagerId,
    ) -> str:
        return (
            f"wd:{self.prefix}:endpoint-manager:"
            f"{endpoint_manager_id}:worker-commands"
        )

    @staticmethod
    def _validate_endpoint_manager_id(
        endpoint_manager_id: EndpointManagerId,
    ) -> None:
        if (
            not isinstance(endpoint_manager_id, str)
            or not endpoint_manager_id
        ):
            raise ValueError("endpoint manager id must be non-empty")

    @staticmethod
    def _validate_worker_ids(worker_ids: Sequence[WorkerId]) -> None:
        if any(
            not isinstance(worker_id, str) or not worker_id
            for worker_id in worker_ids
        ):
            raise ValueError("Worker ids must be non-empty")
        if len(set(worker_ids)) != len(worker_ids):
            raise ValueError("Worker ids must not contain duplicates")

    @staticmethod
    def _decode_text_sequence(raw_values: Any) -> tuple[str, ...]:
        if raw_values is None:
            return ()
        if isinstance(raw_values, (str, bytes)):
            raw_values = (raw_values,)
        values = tuple(
            value.decode("utf-8") if isinstance(value, bytes) else value
            for value in raw_values
        )
        if len(values) % 2 != 0 or any(
            not isinstance(value, str) for value in values
        ):
            raise RuntimeError(
                "Redis WorkerCommand script returned an invalid response"
            )
        return values
