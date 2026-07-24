from __future__ import annotations

import unittest
from dataclasses import fields
from uuid import NAMESPACE_DNS, uuid5

from kernel_design.executable_spec import (
    DeliverSeed,
    SeedResult,
    WorkerCommandEnvelope,
    WorkerMessageType,
    decode_deliver_seed,
    decode_seed_result,
    decode_worker_command_envelope,
    encode_deliver_seed,
    encode_seed_result,
    encode_worker_command_envelope,
)


class WorkerDeliveryProtocolTest(unittest.TestCase):
    COMMAND_ID = str(uuid5(NAMESPACE_DNS, "worker-delivery-command"))

    def test_public_dto_shapes_are_transport_neutral(self) -> None:
        self.assertEqual(
            (
                "worker_id",
                "opaque_delivery_item",
                "opaque_result_context",
            ),
            tuple(field.name for field in fields(DeliverSeed)),
        )
        self.assertEqual(
            (
                "command_id",
                "message_type",
                "execute_before_millis",
                "opaque_item",
            ),
            tuple(field.name for field in fields(WorkerCommandEnvelope)),
        )
        self.assertEqual(
            (
                "command_id",
                "opaque_result_context",
                "outcome_code",
                "opaque_result_payload",
            ),
            tuple(field.name for field in fields(SeedResult)),
        )
        self.assertEqual(
            (WorkerMessageType.TASK_ITEM,),
            tuple(WorkerMessageType),
        )

    def test_deliver_seed_codec_is_deterministic_and_strict(self) -> None:
        seed = DeliverSeed("worker-1", "delivery", "context")
        encoded = encode_deliver_seed(seed)

        self.assertEqual(
            '{"opaqueDeliveryItem":"delivery",'
            '"opaqueResultContext":"context","workerId":"worker-1"}',
            encoded,
        )
        self.assertEqual(seed, decode_deliver_seed(encoded))
        self.assertIsNone(
            decode_deliver_seed(
                '{"opaqueDeliveryItem":"delivery",'
                '"opaqueResultContext":"context","unknown":1,'
                '"workerId":"worker-1"}'
            )
        )

    def test_command_codec_round_trips_canonical_uuid_and_deadline(self) -> None:
        command = WorkerCommandEnvelope(
            command_id=self.COMMAND_ID,
            message_type=WorkerMessageType.TASK_ITEM,
            execute_before_millis=123_456,
            opaque_item="opaque-command-item",
        )
        encoded = encode_worker_command_envelope(command)

        self.assertEqual(
            '{"commandId":"' + self.COMMAND_ID + '",'
            '"executeBeforeMillis":123456,'
            '"messageType":"TASK_ITEM",'
            '"opaqueItem":"opaque-command-item"}',
            encoded,
        )
        self.assertEqual(command, decode_worker_command_envelope(encoded))
        self.assertEqual(command, decode_worker_command_envelope(encoded.encode()))

    def test_result_codec_preserves_command_correlation(self) -> None:
        seed_result = SeedResult(
            self.COMMAND_ID,
            "context",
            "200",
            "null",
        )
        encoded = encode_seed_result(seed_result)

        self.assertEqual(
            '{"commandId":"' + self.COMMAND_ID + '",'
            '"opaqueResultContext":"context",'
            '"opaqueResultPayload":"null","outcomeCode":"200"}',
            encoded,
        )
        self.assertEqual(seed_result, decode_seed_result(encoded))

    def test_invalid_outer_envelopes_are_rejected(self) -> None:
        valid = (
            '{"commandId":"' + self.COMMAND_ID + '",'
            '"executeBeforeMillis":123456,'
            '"messageType":"TASK_ITEM","opaqueItem":"item"}'
        )
        invalid_values = (
            "{bad-json",
            valid.replace(self.COMMAND_ID, self.COMMAND_ID.upper()),
            valid.replace('"TASK_ITEM"', '"UNKNOWN"'),
            valid.replace("123456", "0"),
            valid[:-1] + ',"unknown":true}',
        )

        for value in invalid_values:
            with self.subTest(value=value):
                self.assertIsNone(decode_worker_command_envelope(value))

    def test_dto_construction_rejects_noncanonical_uuid(self) -> None:
        with self.assertRaises(ValueError):
            WorkerCommandEnvelope(
                command_id=self.COMMAND_ID.upper(),
                message_type=WorkerMessageType.TASK_ITEM,
                execute_before_millis=1,
                opaque_item="item",
            )
        with self.assertRaises(ValueError):
            WorkerCommandEnvelope(
                command_id="not-a-uuid",
                message_type=WorkerMessageType.TASK_ITEM,
                execute_before_millis=1,
                opaque_item="item",
            )


if __name__ == "__main__":
    unittest.main()
