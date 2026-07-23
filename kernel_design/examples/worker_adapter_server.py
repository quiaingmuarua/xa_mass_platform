from __future__ import annotations

import argparse
import logging
from pathlib import Path
from time import time_ns
from typing import Literal
from uuid import UUID, uuid4

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from kernel_design.executable_spec.assembly import (
    DeliverSeedConsumerClient,
    KernelApplicationConfig,
    SeedResult,
    SeedResultCommandClient,
    SeedResultOutcomeClass,
    classify_seed_result_outcome_code,
)


TASK_SEED_MESSAGE_TYPE = "TASK_SEED"


class _TaskSeedResultRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    command_id: UUID = Field(alias="commandId")
    message_type: Literal["TASK_SEED_RESULT"] = Field(alias="messageType")
    opaque_result_context: str = Field(alias="opaqueResultContext", min_length=1)
    outcome_code: str = Field(alias="outcomeCode")
    opaque_result_payload: str | None = Field(
        default=None,
        alias="opaqueResultPayload",
        min_length=1,
    )

    @field_validator("outcome_code")
    @classmethod
    def validate_worker_outcome_code(cls, outcome_code: str) -> str:
        outcome_class = classify_seed_result_outcome_code(outcome_code)
        if outcome_class not in {
            SeedResultOutcomeClass.SUCCESS,
            SeedResultOutcomeClass.WORKER_FAILURE,
        }:
            raise ValueError("Worker outcome code must be 200 or 1xxx")
        return outcome_code

    @model_validator(mode="after")
    def validate_success_payload(self) -> _TaskSeedResultRequest:
        if self.outcome_code == "200" and self.opaque_result_payload is None:
            raise ValueError("successful result must carry an opaque payload")
        return self


def create_app(
    *,
    config_json: str | None = None,
    deliver_seed_consumer: DeliverSeedConsumerClient | None = None,
    seed_result_commands: SeedResultCommandClient | None = None,
) -> FastAPI:
    if config_json is not None and (
        deliver_seed_consumer is not None or seed_result_commands is not None
    ):
        raise ValueError(
            "config_json and injected transport boundaries are mutually exclusive"
        )
    if (deliver_seed_consumer is None) != (seed_result_commands is None):
        raise ValueError(
            "deliver_seed_consumer and seed_result_commands must be injected together"
        )
    if deliver_seed_consumer is None:
        config = KernelApplicationConfig.from_json(config_json)
        deliver_seed_consumer = DeliverSeedConsumerClient(config)
        seed_result_commands = SeedResultCommandClient(config)

    assert seed_result_commands is not None
    deliver_commands = deliver_seed_consumer
    result_commands = seed_result_commands

    app = FastAPI(title="Worker Adapter API")
    app.state.deliver_seed_consumer_client = deliver_commands
    app.state.seed_result_command_client = result_commands

    @app.exception_handler(ValueError)
    async def invalid_contract_value(
        _request: Request,
        error: ValueError,
    ) -> JSONResponse:
        return JSONResponse({"detail": str(error)}, status_code=422)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/workers/{worker_id}/commands:poll")
    def poll_worker_command(worker_id: str) -> Response:
        seeds_by_worker_id = deliver_commands.consume_deliver_seeds(
            worker_ids=(worker_id,),
        )
        seed = seeds_by_worker_id.get(worker_id)
        if (
            seed is None
            or _current_time_millis() >= seed.task_item_claim_until_millis
        ):
            return Response(status_code=204)
        return JSONResponse(
            {
                "commandId": str(uuid4()),
                "messageType": TASK_SEED_MESSAGE_TYPE,
                "opaqueDeliveryItem": seed.opaque_delivery_item,
                "opaqueResultContext": seed.opaque_result_context,
                "taskItemClaimUntilMillis": seed.task_item_claim_until_millis,
            }
        )

    @app.post("/workers/{worker_id}/results", status_code=202)
    def submit_worker_result(
        worker_id: str,
        request: _TaskSeedResultRequest,
    ) -> dict[str, bool]:
        del worker_id
        accepted = result_commands.append_seed_results(
            results=(
                SeedResult(
                    opaque_result_context=request.opaque_result_context,
                    outcome_code=request.outcome_code,
                    opaque_result_payload=request.opaque_result_payload,
                ),
            )
        )
        if accepted != 1:
            raise HTTPException(
                status_code=503,
                detail="SeedResult was not accepted",
            )
        return {"accepted": True}

    return app


def _current_time_millis() -> int:
    return time_ns() // 1_000_000


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Start the Worker Adapter Server."
    )
    parser.add_argument("--config", type=Path, help="optional kernel JSON config")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument(
        "--log-level",
        choices=("debug", "info", "warning", "error"),
        default="info",
    )
    args = parser.parse_args()
    if args.port <= 0:
        parser.error("--port must be positive")

    config_json = args.config.read_text(encoding="utf-8") if args.config else None
    logging.basicConfig(level=args.log_level.upper())
    try:
        import uvicorn
    except ImportError as error:
        raise RuntimeError(
            "uvicorn is required for the Worker Adapter Server"
        ) from error
    uvicorn.run(
        create_app(config_json=config_json),
        host=args.host,
        port=args.port,
        log_level=args.log_level,
    )


if __name__ == "__main__":
    main()
