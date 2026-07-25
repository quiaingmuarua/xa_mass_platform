from __future__ import annotations

import json
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from kernel_design.executable_spec.assembly import (
    SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
    DeliverSeed,
    encode_deliver_seed,
)

try:
    from kernel_design.examples.polling_phone_worker import (
        PHONE_INSPECT_EVENT_CODE,
        PollingPhoneWorker,
        inspect_international_phone_number,
    )
except ImportError:  # pragma: no cover - missing example dependencies
    PHONE_INSPECT_EVENT_CODE = None  # type: ignore[assignment]
    PollingPhoneWorker = None  # type: ignore[assignment,misc]
    inspect_international_phone_number = None  # type: ignore[assignment]


@unittest.skipUnless(
    PollingPhoneWorker is not None,
    "polling phone Worker dependencies missing",
)
class InternationalPhoneInspectionTest(unittest.TestCase):
    def test_valid_international_numbers_resolve_regions(self) -> None:
        assert inspect_international_phone_number is not None

        self.assertEqual(
            {
                "countryCallingCode": 1,
                "e164": "+14155552671",
                "isPossible": True,
                "isValid": True,
                "regionCode": "US",
            },
            inspect_international_phone_number("+14155552671"),
        )
        self.assertEqual(
            {
                "countryCallingCode": 44,
                "e164": "+442083661177",
                "isPossible": True,
                "isValid": True,
                "regionCode": "GB",
            },
            inspect_international_phone_number("+442083661177"),
        )

    def test_invalid_or_non_international_numbers_are_normal_results(
        self,
    ) -> None:
        assert inspect_international_phone_number is not None

        self.assertEqual(
            {
                "countryCallingCode": 1,
                "e164": "+12001230101",
                "isPossible": True,
                "isValid": False,
                "regionCode": None,
            },
            inspect_international_phone_number("+12001230101"),
        )
        invalid_result = {
            "countryCallingCode": None,
            "e164": None,
            "isPossible": False,
            "isValid": False,
            "regionCode": None,
        }
        self.assertEqual(
            invalid_result,
            inspect_international_phone_number("4155552671"),
        )
        self.assertEqual(
            invalid_result,
            inspect_international_phone_number("not-a-phone-number"),
        )


@unittest.skipUnless(
    PollingPhoneWorker is not None,
    "polling phone Worker dependencies missing",
)
class PollingPhoneWorkerTest(unittest.TestCase):
    NOW_MILLIS = 100_000

    def setUp(self) -> None:
        assert PollingPhoneWorker is not None
        self.client = Mock()
        self.worker = PollingPhoneWorker(
            worker_id="worker-1",
            delivery_client=self.client,
            current_time_millis=lambda: self.NOW_MILLIS,
        )

    def test_empty_poll_is_a_bounded_noop(self) -> None:
        response = Mock(status_code=204)
        self.client.post.return_value = response

        self.assertFalse(self.worker.poll_once())
        self.client.post.assert_called_once_with(
            "/worker-delivery/endpoint-managers/"
            f"{SYSTEM_POLLING_ENDPOINT_MANAGER_ID}"
            "/workers/worker-1/commands:poll"
        )

    def test_phone_command_executes_and_submits_success(self) -> None:
        poll_response = self._command_response(
            self._delivery_item(
                PHONE_INSPECT_EVENT_CODE,
                {"phoneNumber": "+14155552671"},
            )
        )
        result_response = Mock(status_code=202)
        self.client.post.side_effect = (poll_response, result_response)

        self.assertTrue(self.worker.poll_once())

        result_request = self.client.post.call_args_list[1].kwargs["json"]
        self.assertEqual(
            "a5e9e10d-f78b-469e-93ab-864b49c189c1",
            result_request["commandId"],
        )
        self.assertEqual(
            "opaque-context",
            result_request["opaqueResultContext"],
        )
        self.assertEqual("200", result_request["outcomeCode"])
        self.assertEqual(
            {
                "countryCallingCode": 1,
                "e164": "+14155552671",
                "isPossible": True,
                "isValid": True,
                "regionCode": "US",
            },
            json.loads(result_request["opaqueResultPayload"]),
        )

    def test_invalid_payload_and_unknown_event_return_worker_failures(
        self,
    ) -> None:
        cases = (
            ("{bad-json", "1400"),
            ('{"eventCode":"telecom.phone.inspect","payload":{}}', "1400"),
            (
                self._delivery_item(
                    PHONE_INSPECT_EVENT_CODE,
                    {"phoneNumber": 14155552671},
                ),
                "1400",
            ),
            (self._delivery_item("unknown.event", {}), "1404"),
        )

        for delivery_item, expected_outcome in cases:
            with self.subTest(outcome=expected_outcome):
                self.client.reset_mock()
                self.client.post.side_effect = (
                    self._command_response(delivery_item),
                    Mock(status_code=202),
                )

                self.assertTrue(self.worker.poll_once())

                result_request = self.client.post.call_args_list[1].kwargs[
                    "json"
                ]
                self.assertEqual(
                    expected_outcome,
                    result_request["outcomeCode"],
                )
                self.assertIsNone(result_request["opaqueResultPayload"])

    def test_unexpected_tool_failure_returns_1500(self) -> None:
        self.client.post.side_effect = (
            self._command_response(
                self._delivery_item(
                    PHONE_INSPECT_EVENT_CODE,
                    {"phoneNumber": "+14155552671"},
                )
            ),
            Mock(status_code=202),
        )

        with patch(
            "kernel_design.examples.polling_phone_worker."
            "inspect_international_phone_number",
            side_effect=RuntimeError("tool failed"),
        ), patch(
            "kernel_design.examples.polling_phone_worker.logging.exception"
        ):
            self.assertTrue(self.worker.poll_once())

        result_request = self.client.post.call_args_list[1].kwargs["json"]
        self.assertEqual("1500", result_request["outcomeCode"])
        self.assertIsNone(result_request["opaqueResultPayload"])

    def test_expired_command_is_not_executed_or_reported(self) -> None:
        self.client.post.return_value = self._command_response(
            self._delivery_item(
                PHONE_INSPECT_EVENT_CODE,
                {"phoneNumber": "+14155552671"},
            ),
            execute_before_millis=self.NOW_MILLIS,
        )

        self.assertFalse(self.worker.poll_once())
        self.assertEqual(1, self.client.post.call_count)

    def test_poll_and_result_http_failures_propagate(self) -> None:
        failed_poll = Mock(status_code=503)
        failed_poll.raise_for_status.side_effect = RuntimeError("poll failed")
        self.client.post.return_value = failed_poll
        with self.assertRaisesRegex(RuntimeError, "poll failed"):
            self.worker.poll_once()

        self.client.reset_mock()
        failed_result = Mock(status_code=503)
        failed_result.raise_for_status.side_effect = RuntimeError(
            "result failed"
        )
        self.client.post.side_effect = (
            self._command_response(
                self._delivery_item(
                    PHONE_INSPECT_EVENT_CODE,
                    {"phoneNumber": "+14155552671"},
                )
            ),
            failed_result,
        )
        with self.assertRaisesRegex(RuntimeError, "result failed"):
            self.worker.poll_once()

    @staticmethod
    def _delivery_item(event_code: str, payload: object) -> str:
        return json.dumps(
            {"eventCode": event_code, "payload": payload},
            sort_keys=True,
            separators=(",", ":"),
        )

    @staticmethod
    def _command_response(
        delivery_item: str,
        *,
        execute_before_millis: int = 105_000,
    ) -> Mock:
        response = Mock(status_code=200)
        response.json.return_value = {
            "commandId": "a5e9e10d-f78b-469e-93ab-864b49c189c1",
            "executeBeforeMillis": execute_before_millis,
            "messageType": "TASK_ITEM",
            "opaqueItem": encode_deliver_seed(
                DeliverSeed(
                    "worker-1",
                    delivery_item,
                    "opaque-context",
                )
            ),
        }
        return response


class PollingPhoneWorkerBoundaryGuardTest(unittest.TestCase):
    def test_worker_depends_only_on_delivery_protocol_and_tool_library(
        self,
    ) -> None:
        source_path = (
            Path(__file__).parents[2] / "examples" / "polling_phone_worker.py"
        )
        source = source_path.read_text(encoding="utf-8")

        self.assertIn(
            "from kernel_design.executable_spec.assembly import",
            source,
        )
        for forbidden in (
            "executable_spec.redis_runtime",
            "redis_runtime",
            "KernelApplication",
            "WorkerScoreCore",
            "Pacer",
        ):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
