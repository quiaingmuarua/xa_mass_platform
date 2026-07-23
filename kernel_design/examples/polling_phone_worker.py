from __future__ import annotations

import argparse
import json
import logging
from collections.abc import Mapping
from dataclasses import dataclass
from time import sleep, time_ns
from typing import Any, Callable
from urllib.parse import quote

import httpx2
import phonenumbers
from phonenumbers.phonenumberutil import NumberParseException


PHONE_INSPECT_EVENT_CODE = "telecom.phone.inspect"


def inspect_international_phone_number(
    phone_number: str,
) -> Mapping[str, object]:
    """Inspect one international number without assuming a default region."""

    try:
        parsed = phonenumbers.parse(phone_number, None)
    except NumberParseException:
        return {
            "countryCallingCode": None,
            "e164": None,
            "isPossible": False,
            "isValid": False,
            "regionCode": None,
        }

    return {
        "countryCallingCode": parsed.country_code,
        "e164": phonenumbers.format_number(
            parsed,
            phonenumbers.PhoneNumberFormat.E164,
        ),
        "isPossible": phonenumbers.is_possible_number(parsed),
        "isValid": phonenumbers.is_valid_number(parsed),
        "regionCode": phonenumbers.region_code_for_number(parsed),
    }


@dataclass(frozen=True)
class _ExecutionResult:
    outcome_code: str
    opaque_result_payload: str | None = None


class PollingPhoneWorker:
    """Polling Worker that executes the international phone inspection tool."""

    def __init__(
        self,
        *,
        worker_id: str,
        adapter_client: Any,
        current_time_millis: Callable[[], int] | None = None,
    ) -> None:
        if not worker_id:
            raise ValueError("worker_id must be non-empty")
        self.worker_id = worker_id
        self.adapter_client = adapter_client
        self._current_time_millis = (
            current_time_millis or _system_time_millis
        )

    def poll_once(self) -> bool:
        response = self.adapter_client.post(
            f"/workers/{quote(self.worker_id, safe='')}/commands:poll"
        )
        if response.status_code == 204:
            return False
        response.raise_for_status()
        command = response.json()
        self._validate_command(command)
        if self._current_time_millis() >= command[
            "taskItemClaimUntilMillis"
        ]:
            return False

        result = self._execute_delivery_item(command["opaqueDeliveryItem"])
        result_request: dict[str, object] = {
            "commandId": command["commandId"],
            "messageType": "TASK_SEED_RESULT",
            "opaqueResultContext": command["opaqueResultContext"],
            "outcomeCode": result.outcome_code,
        }
        if result.opaque_result_payload is not None:
            result_request["opaqueResultPayload"] = (
                result.opaque_result_payload
            )

        result_response = self.adapter_client.post(
            f"/workers/{quote(self.worker_id, safe='')}/results",
            json=result_request,
        )
        result_response.raise_for_status()
        if result_response.status_code != 202:
            raise RuntimeError(
                "Worker Adapter did not accept the SeedResult"
            )
        return True

    @staticmethod
    def _validate_command(command: object) -> None:
        if not isinstance(command, Mapping):
            raise RuntimeError("Worker Adapter returned an invalid command")
        required_fields = {
            "commandId": str,
            "messageType": str,
            "opaqueDeliveryItem": str,
            "opaqueResultContext": str,
            "taskItemClaimUntilMillis": int,
        }
        if any(
            field_name not in command
            or isinstance(command[field_name], bool)
            or not isinstance(command[field_name], field_type)
            for field_name, field_type in required_fields.items()
        ):
            raise RuntimeError("Worker Adapter returned an invalid command")
        if command["messageType"] != "TASK_SEED":
            raise RuntimeError("Worker Adapter returned an invalid message type")

    @staticmethod
    def _execute_delivery_item(value: str) -> _ExecutionResult:
        try:
            delivery_item = json.loads(value)
        except (TypeError, ValueError):
            return _ExecutionResult("1400")
        if not isinstance(delivery_item, Mapping):
            return _ExecutionResult("1400")

        event_code = delivery_item.get("eventCode")
        if event_code != PHONE_INSPECT_EVENT_CODE:
            return _ExecutionResult("1404")
        payload = delivery_item.get("payload")
        if not isinstance(payload, Mapping):
            return _ExecutionResult("1400")
        phone_number = payload.get("phoneNumber")
        if not isinstance(phone_number, str):
            return _ExecutionResult("1400")

        try:
            inspection = inspect_international_phone_number(phone_number)
            opaque_payload = json.dumps(
                inspection,
                allow_nan=False,
                sort_keys=True,
                separators=(",", ":"),
            )
        except Exception:
            logging.exception("international phone inspection failed")
            return _ExecutionResult("1500")
        return _ExecutionResult("200", opaque_payload)


def _system_time_millis() -> int:
    return time_ns() // 1_000_000


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Start the polling international-phone Worker."
    )
    parser.add_argument("--worker-id", required=True)
    parser.add_argument(
        "--adapter-url",
        default="http://127.0.0.1:18081",
    )
    parser.add_argument(
        "--poll-interval-millis",
        type=int,
        default=500,
    )
    parser.add_argument(
        "--request-timeout-seconds",
        type=float,
        default=5.0,
    )
    parser.add_argument(
        "--log-level",
        choices=("debug", "info", "warning", "error"),
        default="info",
    )
    args = parser.parse_args()
    if args.poll_interval_millis <= 0:
        parser.error("--poll-interval-millis must be positive")
    if args.request_timeout_seconds <= 0:
        parser.error("--request-timeout-seconds must be positive")

    logging.basicConfig(level=args.log_level.upper())
    with httpx2.Client(
        base_url=args.adapter_url,
        timeout=args.request_timeout_seconds,
    ) as adapter_client:
        worker = PollingPhoneWorker(
            worker_id=args.worker_id,
            adapter_client=adapter_client,
        )
        try:
            while True:
                if not worker.poll_once():
                    sleep(args.poll_interval_millis / 1_000)
        except KeyboardInterrupt:
            logging.info("polling phone Worker stopped")


if __name__ == "__main__":
    main()
