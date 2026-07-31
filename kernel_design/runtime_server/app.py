from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from kernel_design.executable_spec.assembly import (
    TaskType,
    KernelApplication,
    KernelApplicationConfig,
    TaskApprovalResult,
    TaskApprovalStatus,
    TaskCloseResult,
    TaskCloseStatus,
    TaskCreationResult,
    TaskCreationStatus,
    TaskDescriptor,
)


class TaskRequest(BaseModel):
    task_id: str = Field(alias="taskId")
    worker_group_id: str = Field(alias="workerGroupId")
    task_type: TaskType = Field(alias="taskType")
    allocation_rule: dict[str, Any] | None = Field(
        default=None,
        alias="allocationRule",
    )
    config: dict[str, str]
    empty_close_at_millis: int | None = Field(
        default=None,
        alias="emptyCloseAtMillis",
        ge=0,
        strict=True,
    )


class TaskDispatchWakeRequest(BaseModel):
    task_ids: list[str] = Field(
        alias="taskIds",
        min_length=1,
        max_length=100,
    )


def _result_payload(result: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {"status": result.status.value}
    if result.reason is not None:
        payload["reason"] = result.reason
    return payload


def _creation_response(result: TaskCreationResult) -> JSONResponse:
    status_code = {
        TaskCreationStatus.CREATED: 201,
        TaskCreationStatus.CONFLICT: 409,
        TaskCreationStatus.INVALID: 422,
        TaskCreationStatus.RETRYABLE: 503,
    }[result.status]
    return JSONResponse(_result_payload(result), status_code=status_code)


def _approval_response(result: TaskApprovalResult) -> JSONResponse:
    status_code = {
        TaskApprovalStatus.APPROVED: 200,
        TaskApprovalStatus.ALREADY_APPROVED: 200,
        TaskApprovalStatus.NOT_FOUND: 404,
        TaskApprovalStatus.CONFLICT: 409,
        TaskApprovalStatus.INVALID: 422,
        TaskApprovalStatus.RETRYABLE: 503,
    }[result.status]
    return JSONResponse(_result_payload(result), status_code=status_code)


def _close_response(result: TaskCloseResult) -> JSONResponse:
    status_code = {
        TaskCloseStatus.CLOSED: 200,
        TaskCloseStatus.ALREADY_CLOSED: 200,
        TaskCloseStatus.NOT_FOUND: 404,
        TaskCloseStatus.INVALID: 422,
        TaskCloseStatus.RETRYABLE: 503,
    }[result.status]
    return JSONResponse(_result_payload(result), status_code=status_code)


def create_app(
    *,
    config_json: str | None = None,
    application: KernelApplication | None = None,
) -> FastAPI:
    if config_json is not None and application is not None:
        raise ValueError(
            "config_json and injected application are mutually exclusive"
        )
    if application is None:
        config = KernelApplicationConfig.from_json(config_json)
        kernel_application = KernelApplication(config)
    else:
        kernel_application = application

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        kernel_application.start()
        try:
            yield
        finally:
            kernel_application.stop()

    app = FastAPI(title="Python Kernel Task Control API", lifespan=lifespan)
    app.state.kernel_application = kernel_application

    @app.exception_handler(ValueError)
    async def invalid_contract_value(
        _request: Request,
        error: ValueError,
    ) -> JSONResponse:
        return JSONResponse({"detail": str(error)}, status_code=422)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/tasks")
    def create_task(request: TaskRequest) -> JSONResponse:
        return _creation_response(
            kernel_application.create_task(
                descriptor=TaskDescriptor(
                    task_id=request.task_id,
                    worker_group_id=request.worker_group_id,
                    task_type=request.task_type,
                    allocation_rule=request.allocation_rule,
                    config=request.config,
                    empty_close_at_millis=request.empty_close_at_millis,
                )
            )
        )

    @app.post("/tasks/{task_id}/approve")
    def approve_task(task_id: str) -> JSONResponse:
        return _approval_response(kernel_application.approve_task(task_id=task_id))

    @app.post("/tasks/{task_id}/close")
    def close_task(task_id: str) -> JSONResponse:
        return _close_response(kernel_application.close_task(task_id=task_id))

    @app.post("/tasks:dispatch-wake")
    def wake_task_dispatch(request: TaskDispatchWakeRequest) -> dict[str, Any]:
        accepted = kernel_application.wake_task_dispatch(
            task_ids=tuple(dict.fromkeys(request.task_ids)),
        )
        return {"status": "accepted", "acceptedTaskCount": accepted}

    return app
