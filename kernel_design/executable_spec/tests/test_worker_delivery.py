from __future__ import annotations

import unittest
from dataclasses import fields

from kernel_design.executable_spec import (
    WORKER_CONNECTION_CLOSE_EVENT_CODE,
    WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
    DeliveryCommand,
    DeliveryEndpoint,
    DeliveryReport,
    decode_delivery_command,
    decode_delivery_report,
    encode_delivery_command,
    encode_delivery_report,
)


class WorkerDeliveryProtocolTest(unittest.TestCase):
    def test_connection_control_event_codes_are_stable(self) -> None:
        self.assertEqual(
            "worker.connection.identify",
            WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
        )
        self.assertEqual(
            "worker.connection.close",
            WORKER_CONNECTION_CLOSE_EVENT_CODE,
        )

    def test_connection_control_uses_existing_result_and_command_wire(
        self,
    ) -> None:
        identity = DeliveryReport.create(
            src=DeliveryEndpoint.WORKER,
            source_id="opaque-worker-id",
            dst=DeliveryEndpoint.ADAPTER,
            message_type=WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
            outcome_code="200",
            payload="null",
            forward="",
        )
        self.assertEqual(identity, decode_delivery_report(
            encode_delivery_report(identity)
        ))

        close = DeliveryCommand.create(
            src=DeliveryEndpoint.ADAPTER,
            dst=DeliveryEndpoint.WORKER,
            message_type=WORKER_CONNECTION_CLOSE_EVENT_CODE,
            execute_before_millis=123_456,
            payload="null",
            forward="",
        )
        self.assertEqual(close, decode_delivery_command(
            encode_delivery_command(close)
        ))

    def test_public_dto_shapes_are_direction_specific(self) -> None:
        self.assertEqual(
            (
                "src",
                "dst",
                "message_type",
                "execute_before_millis",
                "payload",
                "forward",
            ),
            tuple(field.name for field in fields(DeliveryCommand)),
        )
        self.assertEqual(
            (
                "src",
                "source_id",
                "dst",
                "message_type",
                "outcome_code",
                "payload",
                "forward",
            ),
            tuple(field.name for field in fields(DeliveryReport)),
        )
        self.assertEqual(
            ("TASK", "SYSTEM", "KERNEL", "ADAPTER", "WORKER"),
            tuple(endpoint.value for endpoint in DeliveryEndpoint),
        )

    def test_command_codec_is_deterministic_and_strict(self) -> None:
        command = self.command()
        encoded = encode_delivery_command(command)

        self.assertEqual(
            '{"dst":"WORKER","executeBeforeMillis":123456,'
            '"forward":"context",'
            '"messageType":"telecom.phone.inspect",'
            '"payload":"{\\"phoneNumber\\":\\"+14155552671\\"}",'
            '"src":"TASK"}',
            encoded,
        )
        self.assertEqual(command, decode_delivery_command(encoded))
        self.assertEqual(command, decode_delivery_command(encoded.encode()))
        self.assertIsNone(
            decode_delivery_command(encoded[:-1] + ',"unknown":true}')
        )
        self.assertIsNone(
            decode_delivery_command(encoded.replace('"TASK"', '"UNKNOWN"'))
        )

    def test_result_codec_preserves_command_routing_fields(self) -> None:
        result = DeliveryReport.from_command(
            command=self.command(),
            src=DeliveryEndpoint.WORKER,
            source_id="worker-1",
            outcome_code="200",
            payload='{"isValid":true}',
        )
        encoded = encode_delivery_report(result)

        self.assertEqual(
            '{"dst":"TASK","forward":"context",'
            '"messageType":"telecom.phone.inspect",'
            '"outcomeCode":"200","payload":"{\\"isValid\\":true}",'
            '"sourceId":"worker-1","src":"WORKER"}',
            encoded,
        )
        self.assertEqual(result, decode_delivery_report(encoded))

    def test_result_codec_rejects_legacy_and_invalid_sources(self) -> None:
        encoded = encode_delivery_report(DeliveryReport.from_command(
            command=self.command(),
            src=DeliveryEndpoint.WORKER,
            source_id="worker-1",
            outcome_code="200",
            payload="null",
        ))
        self.assertIsNone(decode_delivery_report(
            encoded.replace('"sourceId":"worker-1",', "")
        ))
        self.assertIsNone(decode_delivery_report(
            encoded.replace('"src":"WORKER"', '"src":"WORKER","extra":true')
        ))
        self.assertIsNone(decode_delivery_report(
            encoded.replace(
                '"src":"WORKER"',
                '"src":"WORKER","messageId":"legacy"',
            )
        ))
        self.assertIsNone(decode_delivery_report(
            encoded.replace('"sourceId":"worker-1"', '"sourceId":1')
        ))
        self.assertIsNone(decode_delivery_report(
            encoded.replace('"sourceId":"worker-1"', '"sourceId":" "')
        ))

    def test_delivery_message_id_is_absent_and_bare_construction_is_closed(
        self,
    ) -> None:
        command = DeliveryCommand.create(
            src=DeliveryEndpoint.SYSTEM,
            dst=DeliveryEndpoint.ADAPTER,
            message_type="event",
            execute_before_millis=1,
            payload="{}",
            forward="",
        )
        self.assertFalse(hasattr(command, "message_id"))
        self.assertFalse(hasattr(
            DeliveryReport.from_command(
                command=command,
                src=DeliveryEndpoint.WORKER,
                source_id="worker-1",
                outcome_code="200",
                payload="null",
            ),
            "message_id",
        ))
        with self.assertRaises(TypeError):
            DeliveryCommand()
        with self.assertRaises(TypeError):
            DeliveryReport()
        self.assertIsNone(decode_delivery_command(
            self.command_json().replace(
                '"src":"TASK"}',
                '"src":"TASK","messageId":"legacy"}',
            )
        ))

    def command(self) -> DeliveryCommand:
        command = decode_delivery_command(self.command_json())
        assert command is not None
        return command

    @staticmethod
    def command_json() -> str:
        return (
            '{"dst":"WORKER","executeBeforeMillis":123456,'
            '"forward":"context",'
            '"messageType":"telecom.phone.inspect",'
            '"payload":"{\\"phoneNumber\\":\\"+14155552671\\"}",'
            '"src":"TASK"}'
        )


if __name__ == "__main__":
    unittest.main()
