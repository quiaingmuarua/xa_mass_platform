from __future__ import annotations

import unittest
from dataclasses import fields
from uuid import NAMESPACE_DNS, uuid5

from kernel_design.executable_spec import (
    WorkerCommand,
    WorkerConnectionBind,
    WorkerMessageEndpoint,
    WorkerResult,
    decode_worker_connection_bind,
    decode_worker_command,
    decode_worker_result,
    encode_worker_command,
    encode_worker_connection_bind,
    encode_worker_result,
)


class WorkerDeliveryProtocolTest(unittest.TestCase):
    MESSAGE_ID = str(uuid5(NAMESPACE_DNS, "worker-delivery-message"))

    def test_connection_bind_is_minimal_and_strict(self) -> None:
        bind = WorkerConnectionBind(worker_id=self.MESSAGE_ID)
        encoded = encode_worker_connection_bind(bind)

        self.assertEqual(
            '{"workerId":"' + self.MESSAGE_ID + '"}',
            encoded,
        )
        self.assertEqual(bind, decode_worker_connection_bind(encoded))
        self.assertIsNone(
            decode_worker_connection_bind(
                '{"workerId":"' + self.MESSAGE_ID + '","extra":true}'
            )
        )
        self.assertIsNone(
            decode_worker_connection_bind('{"workerId":"not-a-uuid"}')
        )

    def test_public_dto_shapes_are_direction_specific(self) -> None:
        self.assertEqual(
            (
                "message_id",
                "src",
                "dst",
                "message_type",
                "execute_before_millis",
                "payload",
                "forward",
            ),
            tuple(field.name for field in fields(WorkerCommand)),
        )
        self.assertEqual(
            (
                "message_id",
                "dst",
                "message_type",
                "outcome_code",
                "payload",
                "forward",
            ),
            tuple(field.name for field in fields(WorkerResult)),
        )
        self.assertEqual(
            ("TASK", "SYSTEM", "ADAPTER", "WORKER"),
            tuple(endpoint.value for endpoint in WorkerMessageEndpoint),
        )

    def test_command_codec_is_deterministic_and_strict(self) -> None:
        command = self.command()
        encoded = encode_worker_command(command)

        self.assertEqual(
            '{"dst":"WORKER","executeBeforeMillis":123456,'
            '"forward":"context","messageId":"' + self.MESSAGE_ID + '",'
            '"messageType":"telecom.phone.inspect",'
            '"payload":"{\\"phoneNumber\\":\\"+14155552671\\"}",'
            '"src":"TASK"}',
            encoded,
        )
        self.assertEqual(command, decode_worker_command(encoded))
        self.assertEqual(command, decode_worker_command(encoded.encode()))
        self.assertIsNone(
            decode_worker_command(encoded[:-1] + ',"unknown":true}')
        )
        self.assertIsNone(
            decode_worker_command(encoded.replace('"TASK"', '"UNKNOWN"'))
        )

    def test_result_codec_preserves_correlation_fields(self) -> None:
        result = WorkerResult(
            message_id=self.MESSAGE_ID,
            dst=WorkerMessageEndpoint.TASK,
            message_type="telecom.phone.inspect",
            outcome_code="200",
            payload='{"isValid":true}',
            forward="context",
        )
        encoded = encode_worker_result(result)

        self.assertEqual(
            '{"dst":"TASK","forward":"context","messageId":"'
            + self.MESSAGE_ID
            + '","messageType":"telecom.phone.inspect",'
            '"outcomeCode":"200","payload":"{\\"isValid\\":true}"}',
            encoded,
        )
        self.assertEqual(result, decode_worker_result(encoded))

    def test_direction_and_uuid_validation_are_strict(self) -> None:
        with self.assertRaises(ValueError):
            WorkerCommand(
                message_id=self.MESSAGE_ID.upper(),
                src=WorkerMessageEndpoint.TASK,
                dst=WorkerMessageEndpoint.WORKER,
                message_type="event",
                execute_before_millis=1,
                payload="{}",
                forward="context",
            )
        with self.assertRaises(ValueError):
            WorkerCommand(
                message_id=self.MESSAGE_ID,
                src=WorkerMessageEndpoint.TASK,
                dst=WorkerMessageEndpoint.TASK,
                message_type="event",
                execute_before_millis=1,
                payload="{}",
                forward="context",
            )
        with self.assertRaises(ValueError):
            WorkerResult(
                message_id=self.MESSAGE_ID,
                dst=WorkerMessageEndpoint.WORKER,
                message_type="event",
                outcome_code="200",
                payload="null",
                forward="context",
            )

    def command(self) -> WorkerCommand:
        return WorkerCommand(
            message_id=self.MESSAGE_ID,
            src=WorkerMessageEndpoint.TASK,
            dst=WorkerMessageEndpoint.WORKER,
            message_type="telecom.phone.inspect",
            execute_before_millis=123_456,
            payload='{"phoneNumber":"+14155552671"}',
            forward="context",
        )


if __name__ == "__main__":
    unittest.main()
