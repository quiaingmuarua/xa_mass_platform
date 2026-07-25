"""Worker Delivery HTTP routes hosted by the Kernel Runtime Server."""

from __future__ import annotations

from time import time_ns
from uuid import UUID

from fastapi import APIRouter, HTTPException, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from kernel_design.executable_spec.assembly import (
    SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
    SeedResult,
    SeedResultCommandClient,
    SeedResultOutcomeClass,
    WorkerCommandConsumerClient,
    WorkerCommandEnvelope,
    classify_seed_result_outcome_code,
)


class _WorkerCommandConsumeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    cursor: str | None = None
    scan_count: int = Field(alias="scanCount", gt=0, strict=True)

    @field_validator("cursor")
    @classmethod
    def validate_cursor(cls, cursor: str | None) -> str | None:
        if cursor is not None and not cursor.isdecimal():
            raise ValueError("cursor must be a non-negative Redis cursor")
        return cursor


class _SeedResultRequest(BaseModel):
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

    def to_seed_result(self) -> SeedResult:
        return SeedResult(
            command_id=self.command_id,
            opaque_result_context=self.opaque_result_context,
            outcome_code=self.outcome_code,
            opaque_result_payload=self.opaque_result_payload,
        )


class _SeedResultBatchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    results: list[_SeedResultRequest] = Field(min_length=1)


def create_worker_delivery_router(
    *,
    worker_command_consumer: WorkerCommandConsumerClient,
    seed_result_commands: SeedResultCommandClient,
) -> APIRouter:
    router = APIRouter(
        prefix="/worker-delivery/endpoint-managers/{endpoint_manager_id}",
        tags=["worker-delivery"],
    )

    @router.post("/workers/{worker_id}/commands:poll")
    def poll_worker_command(
        endpoint_manager_id: str,
        worker_id: str,
    ) -> Response:
        command = worker_command_consumer.consume_worker_command(
            endpoint_manager_id=endpoint_manager_id,
            worker_id=worker_id,
        )
        if command is None or _is_expired(command):
            return Response(status_code=204)
        return JSONResponse(_worker_command_payload(command))

    @router.post("/commands:consume")
    def consume_worker_commands(
        endpoint_manager_id: str,
        request: _WorkerCommandConsumeRequest,
    ) -> dict[str, object]:
        _require_long_lived_adapter(endpoint_manager_id)
        page = worker_command_consumer.consume_worker_commands(
            endpoint_manager_id=endpoint_manager_id,
            cursor=request.cursor,
            scan_count=request.scan_count,
        )
        commands = {
            worker_id: _worker_command_payload(command)
            for worker_id, command in page.worker_commands_by_worker_id.items()
            if not _is_expired(command)
        }
        return {
            "workerCommandsByWorkerId": commands,
            "nextCursor": page.next_cursor,
        }

    @router.post("/workers/{worker_id}/results", status_code=202)
    def submit_worker_result(
        endpoint_manager_id: str,
        worker_id: str,
        request: _SeedResultRequest,
    ) -> dict[str, bool]:
        del endpoint_manager_id, worker_id
        result = request.to_seed_result()
        if classify_seed_result_outcome_code(result.outcome_code) not in {
            SeedResultOutcomeClass.SUCCESS,
            SeedResultOutcomeClass.WORKER_FAILURE,
        }:
            raise ValueError("Worker result outcome code must be 200 or 1xxx")
        _append_results_or_raise(
            seed_result_commands=seed_result_commands,
            results=(result,),
        )
        return {"accepted": True}

    @router.post("/results:append", status_code=202)
    def append_adapter_results(
        endpoint_manager_id: str,
        request: _SeedResultBatchRequest,
    ) -> dict[str, int]:
        _require_long_lived_adapter(endpoint_manager_id)
        results = tuple(result.to_seed_result() for result in request.results)
        _append_results_or_raise(
            seed_result_commands=seed_result_commands,
            results=results,
        )
        return {"acceptedCount": len(results)}

    return router


def _worker_command_payload(
    command: WorkerCommandEnvelope,
) -> dict[str, object]:
    return {
        "commandId": command.command_id,
        "executeBeforeMillis": command.execute_before_millis,
        "messageType": command.message_type.value,
        "opaqueItem": command.opaque_item,
    }


def _is_expired(command: WorkerCommandEnvelope) -> bool:
    return _current_time_millis() >= command.execute_before_millis


def _append_results_or_raise(
    *,
    seed_result_commands: SeedResultCommandClient,
    results: tuple[SeedResult, ...],
) -> None:
    accepted_count = seed_result_commands.append_seed_results(results=results)
    if accepted_count != len(results):
        raise HTTPException(
            status_code=503,
            detail="SeedResult batch was not fully accepted",
        )


def _current_time_millis() -> int:
    return time_ns() // 1_000_000


def _require_long_lived_adapter(endpoint_manager_id: str) -> None:
    if endpoint_manager_id == SYSTEM_POLLING_ENDPOINT_MANAGER_ID:
        raise ValueError(
            "system-polling supports only point Worker access"
        )
