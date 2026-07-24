from __future__ import annotations

import argparse
import logging
from pathlib import Path
from time import time_ns
from uuid import UUID

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from kernel_design.executable_spec.assembly import (
    KernelApplicationConfig,
    SeedResult,
    SeedResultCommandClient,
    SeedResultOutcomeClass,
    WorkerCommandConsumerClient,
    classify_seed_result_outcome_code,
)


class _WorkerResultRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    command_id: str = Field(alias="commandId", min_length=1)
    opaque_result_context: str = Field(
        alias="opaqueResultContext",
        min_length=1,
    )
    outcome_code: str = Field(alias="outcomeCode", min_length=1)
    opaque_result_payload: str | None = Field(
        default=None,
        alias="opaqueResultPayload",
    )

    @field_validator("command_id")
    @classmethod
    def validate_command_id(cls, command_id: str) -> str:
        try:
            parsed = UUID(command_id)
        except ValueError as error:
            raise ValueError("commandId must be a canonical UUID") from error
        if str(parsed) != command_id:
            raise ValueError("commandId must be a canonical UUID")
        return command_id


def create_app(
    *,
    endpoint_manager_id: str,
    config_json: str | None = None,
    worker_command_consumer: WorkerCommandConsumerClient | None = None,
    seed_result_commands: SeedResultCommandClient | None = None,
) -> FastAPI:
    if not isinstance(endpoint_manager_id, str) or not endpoint_manager_id:
        raise ValueError("endpoint_manager_id must be non-empty")
    if config_json is not None and (
        worker_command_consumer is not None or seed_result_commands is not None
    ):
        raise ValueError(
            "config_json and injected transport boundaries are mutually exclusive"
        )
    if (worker_command_consumer is None) != (seed_result_commands is None):
        raise ValueError(
            "worker command and result clients must be injected together"
        )
    if worker_command_consumer is None:
        config = KernelApplicationConfig.from_json(config_json)
        worker_command_consumer = WorkerCommandConsumerClient(config)
        seed_result_commands = SeedResultCommandClient(config)

    assert seed_result_commands is not None
    command_consumer = worker_command_consumer
    result_commands = seed_result_commands

    app = FastAPI(title="Worker Adapter API")
    app.state.endpoint_manager_id = endpoint_manager_id
    app.state.worker_command_consumer_client = command_consumer
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
        command = command_consumer.consume_worker_command(
            endpoint_manager_id=endpoint_manager_id,
            worker_id=worker_id,
        )
        if (
            command is None
            or _current_time_millis() >= command.execute_before_millis
        ):
            return Response(status_code=204)
        return JSONResponse(
            {
                "commandId": command.command_id,
                "executeBeforeMillis": command.execute_before_millis,
                "messageType": command.message_type.value,
                "opaqueItem": command.opaque_item,
            }
        )

    @app.post("/workers/{worker_id}/results", status_code=202)
    def submit_worker_result(
        worker_id: str,
        request: _WorkerResultRequest,
    ) -> dict[str, bool]:
        del worker_id
        result = SeedResult(
            command_id=request.command_id,
            opaque_result_context=request.opaque_result_context,
            outcome_code=request.outcome_code,
            opaque_result_payload=request.opaque_result_payload,
        )
        if classify_seed_result_outcome_code(result.outcome_code) not in {
            SeedResultOutcomeClass.SUCCESS,
            SeedResultOutcomeClass.WORKER_FAILURE,
        }:
            raise ValueError("Worker result outcome code must be 200 or 1xxx")
        accepted = result_commands.append_seed_results(
            results=(
                result,
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
    parser.add_argument("--endpoint-manager-id", required=True)
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
        create_app(
            endpoint_manager_id=args.endpoint_manager_id,
            config_json=config_json,
        ),
        host=args.host,
        port=args.port,
        log_level=args.log_level,
    )


if __name__ == "__main__":
    main()
