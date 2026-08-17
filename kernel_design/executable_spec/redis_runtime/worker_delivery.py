from __future__ import annotations

from typing import Any, Mapping, Sequence

from ..kernel.task_score_band import TimeMillis
from ..kernel.worker_delivery import (
    WorkerCommandAppendStatus,
    WorkerCommandOfferStatus,
    DeliveryCommand,
    WorkerCommandRuntime,
    decode_delivery_command,
    encode_delivery_command,
)
from ..kernel.worker_runtime import EndpointManagerId
from ..kernel.worker_score import WorkerId


_OFFER_WORKER_COMMANDS_SCRIPT = """
local results = {}
for index = 1, #ARGV, 2 do
    local worker_id = ARGV[index]
    local encoded_command = ARGV[index + 1]
    table.insert(
        results,
        redis.call('HSETNX', KEYS[1], worker_id, encoded_command)
    )
end
return results
"""


_CONSUME_WORKER_COMMAND_SCRIPT = """
local current = redis.call('HGET', KEYS[1], ARGV[1])
if not current then
    return {}
end
redis.call('HDEL', KEYS[1], ARGV[1])
return {ARGV[1], current}
"""

_CONSUME_OBSERVED_WORKER_COMMANDS_SCRIPT = """
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
            DeliveryCommand,
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
            worker_id: encode_delivery_command(command)
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

    def offer_worker_commands(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_commands_by_worker_id: Mapping[
            WorkerId,
            DeliveryCommand,
        ],
    ) -> Mapping[WorkerId, WorkerCommandOfferStatus]:
        self._validate_endpoint_manager_id(endpoint_manager_id)
        if not worker_commands_by_worker_id:
            return {}

        worker_ids = tuple(worker_commands_by_worker_id)
        self._validate_worker_ids(worker_ids)
        now_millis = self._current_time_millis()
        if any(
            not isinstance(command, DeliveryCommand)
            or command.execute_before_millis <= now_millis
            for command in worker_commands_by_worker_id.values()
        ):
            raise ValueError(
                "Worker commands must be DeliveryCommand values with "
                "future deadlines"
            )

        arguments = [
            value
            for worker_id, command in worker_commands_by_worker_id.items()
            for value in (worker_id, encode_delivery_command(command))
        ]
        raw_results = self.redis.eval(
            _OFFER_WORKER_COMMANDS_SCRIPT,
            1,
            self._worker_command_key(endpoint_manager_id),
            *arguments,
        )
        results = tuple(raw_results or ())
        if len(results) != len(worker_ids):
            raise RuntimeError(
                "Redis Worker command offer returned an invalid response"
            )
        try:
            inserted = tuple(int(value) for value in results)
        except (TypeError, ValueError) as error:
            raise RuntimeError(
                "Redis Worker command offer returned an invalid response"
            ) from error
        if any(value not in (0, 1) for value in inserted):
            raise RuntimeError(
                "Redis Worker command offer returned an invalid response"
            )
        return {
            worker_id: (
                WorkerCommandOfferStatus.OFFERED
                if was_inserted
                else WorkerCommandOfferStatus.OCCUPIED
            )
            for worker_id, was_inserted in zip(worker_ids, inserted)
        }

    def consume_worker_command(
        self,
        *,
        endpoint_manager_id: EndpointManagerId,
        worker_id: WorkerId,
    ) -> DeliveryCommand | None:
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
        command = decode_delivery_command(values[1])
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
        limit: int,
    ) -> Mapping[WorkerId, DeliveryCommand]:
        self._validate_endpoint_manager_id(endpoint_manager_id)
        if (
            isinstance(limit, bool)
            or not isinstance(limit, int)
            or limit <= 0
        ):
            raise ValueError("consume limit must be positive")

        key = self._worker_command_key(endpoint_manager_id)
        observed = self.redis.hrandfield(
            key,
            count=limit,
            withvalues=True,
        )
        script_args = list(observed or ())
        if not script_args:
            return {}
        if len(script_args) % 2 != 0:
            raise RuntimeError("Redis HRANDFIELD returned an invalid response")
        raw_result = self.redis.eval(
            _CONSUME_OBSERVED_WORKER_COMMANDS_SCRIPT,
            1,
            key,
            *script_args,
        )
        values = self._decode_text_sequence(raw_result)
        now_millis = self._current_time_millis()
        commands: dict[WorkerId, DeliveryCommand] = {}
        for index in range(0, len(values), 2):
            worker_id = values[index]
            command = decode_delivery_command(values[index + 1])
            if (
                command is not None
                and command.execute_before_millis > now_millis
            ):
                commands[worker_id] = command
        return commands

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
                "Redis DeliveryCommand script returned an invalid response"
            )
        return values
