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

from kernel_design.executable_spec.assembly import (
    SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
    SeedResult,
    WorkerCommandEnvelope,
    WorkerMessageType,
    decode_deliver_seed,
)


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
        delivery_client: Any,
        endpoint_manager_id: str = SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
        current_time_millis: Callable[[], int] | None = None,
    ) -> None:
        if not worker_id:
            raise ValueError("worker_id must be non-empty")
        if not endpoint_manager_id:
            raise ValueError("endpoint_manager_id must be non-empty")
        self.worker_id = worker_id
        self.endpoint_manager_id = endpoint_manager_id
        self.delivery_client = delivery_client
        self._current_time_millis = (
            current_time_millis or _system_time_millis
        )

    def poll_once(self) -> bool:
        response = self.delivery_client.post(
            self._worker_delivery_path("commands:poll")
        )
        if response.status_code == 204:
            return False
        response.raise_for_status()
        command = self._decode_command(response.json())
        if self._current_time_millis() >= command.execute_before_millis:
            return False

        seed = decode_deliver_seed(command.opaque_item)
        if seed is None or seed.worker_id != self.worker_id:
            raise RuntimeError(
                "Worker Delivery Gateway returned an invalid DeliverSeed"
            )
        result = self._execute_delivery_item(seed.opaque_delivery_item)
        seed_result = SeedResult(
            command_id=command.command_id,
            opaque_result_context=seed.opaque_result_context,
            outcome_code=result.outcome_code,
            opaque_result_payload=result.opaque_result_payload,
        )
        result_request: dict[str, object] = {
            "commandId": seed_result.command_id,
            "opaqueResultContext": seed_result.opaque_result_context,
            "outcomeCode": seed_result.outcome_code,
            "opaqueResultPayload": seed_result.opaque_result_payload,
        }

        result_response = self.delivery_client.post(
            self._worker_delivery_path("results"),
            json=result_request,
        )
        result_response.raise_for_status()
        if result_response.status_code != 202:
            raise RuntimeError(
                "Worker Delivery Gateway did not accept the SeedResult"
            )
        return True

    def _worker_delivery_path(self, action: str) -> str:
        endpoint_manager_id = quote(self.endpoint_manager_id, safe="")
        worker_id = quote(self.worker_id, safe="")
        return (
            "/worker-delivery/endpoint-managers/"
            f"{endpoint_manager_id}/workers/{worker_id}/{action}"
        )

    @staticmethod
    def _decode_command(command: object) -> WorkerCommandEnvelope:
        if not isinstance(command, Mapping):
            raise RuntimeError(
                "Worker Delivery Gateway returned an invalid command"
            )
        if set(command) != {
            "commandId",
            "executeBeforeMillis",
            "messageType",
            "opaqueItem",
        }:
            raise RuntimeError(
                "Worker Delivery Gateway returned an invalid command"
            )
        try:
            return WorkerCommandEnvelope(
                command_id=command["commandId"],
                message_type=WorkerMessageType(command["messageType"]),
                execute_before_millis=command["executeBeforeMillis"],
                opaque_item=command["opaqueItem"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise RuntimeError(
                "Worker Delivery Gateway returned an invalid message type"
            )

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
        "--server-url",
        default="http://127.0.0.1:18080",
    )
    parser.add_argument(
        "--endpoint-manager-id",
        default=SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
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
        base_url=args.server_url,
        timeout=args.request_timeout_seconds,
    ) as delivery_client:
        worker = PollingPhoneWorker(
            worker_id=args.worker_id,
            endpoint_manager_id=args.endpoint_manager_id,
            delivery_client=delivery_client,
        )
        try:
            while True:
                if not worker.poll_once():
                    sleep(args.poll_interval_millis / 1_000)
        except KeyboardInterrupt:
            logging.info("polling phone Worker stopped")


if __name__ == "__main__":
    main()
