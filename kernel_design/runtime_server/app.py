from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from kernel_design.executable_spec.assembly import (
    KernelApplication,
    KernelApplicationConfig,
    TaskApprovalResult,
    TaskApprovalStatus,
    TaskCallSubmissionResult,
    TaskCallSubmissionStatus,
    TaskCloseResult,
    TaskCloseStatus,
    TaskCreationResult,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskIdleDisposition,
    WorkerAllocationMechanism,
)


class TaskRequest(BaseModel):
    task_id: str = Field(alias="taskId")
    worker_group_id: str = Field(alias="workerGroupId")
    worker_allocation_mechanism: WorkerAllocationMechanism = Field(
        alias="workerAllocationMechanism"
    )
    idle_disposition: TaskIdleDisposition = Field(alias="idleDisposition")
    allocation_rule: dict[str, Any] | None = Field(
        default=None,
        alias="allocationRule",
    )
    config: dict[str, str]


class TaskCallItemRequest(BaseModel):
    message_id: str = Field(alias="messageId")
    event_code: str = Field(alias="eventCode")
    created_at_millis: int = Field(alias="createdAtMillis", ge=0, strict=True)
    payload: dict[str, Any]
    priority: int = Field(default=5, ge=0, le=10, strict=True)
    expire_at_millis: int | None = Field(
        default=None,
        alias="expireAtMillis",
        ge=0,
        strict=True,
    )
    allocation_rule: dict[str, Any] | None = Field(
        default=None,
        alias="allocationRule",
    )


class TaskCallItemsSubmissionRequest(BaseModel):
    items: list[TaskCallItemRequest] = Field(
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


def _task_call_submission_response(
    result: TaskCallSubmissionResult,
) -> JSONResponse:
    payload = _result_payload(result)
    payload["itemResults"] = {
        message_id: {
            "status": item_result.status.value,
            **(
                {}
                if item_result.reason is None
                else {"reason": item_result.reason}
            ),
        }
        for message_id, item_result in result.item_results.items()
    }
    status_code = {
        TaskCallSubmissionStatus.SUBMITTED: 200,
        TaskCallSubmissionStatus.NOT_FOUND: 404,
        TaskCallSubmissionStatus.CLOSED: 409,
        TaskCallSubmissionStatus.STALE: 409,
        TaskCallSubmissionStatus.INVALID: 422,
        TaskCallSubmissionStatus.RETRYABLE: 503,
    }[result.status]
    return JSONResponse(payload, status_code=status_code)


def create_app(
    *,
    config: KernelApplicationConfig | None = None,
    application: KernelApplication | None = None,
) -> FastAPI:
    if config is not None and application is not None:
        raise ValueError(
            "config and injected application are mutually exclusive"
        )
    if application is None:
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
                    worker_allocation_mechanism=(
                        request.worker_allocation_mechanism
                    ),
                    idle_disposition=request.idle_disposition,
                    allocation_rule=request.allocation_rule,
                    config=request.config,
                )
            )
        )

    @app.post("/tasks/{task_id}/approve")
    def approve_task(task_id: str) -> JSONResponse:
        return _approval_response(kernel_application.approve_task(task_id=task_id))

    @app.post("/tasks/{task_id}/close")
    def close_task(task_id: str) -> JSONResponse:
        return _close_response(kernel_application.close_task(task_id=task_id))

    @app.post("/tasks/{task_id}:submit-call-items")
    def submit_task_call_items(
        task_id: str,
        request: TaskCallItemsSubmissionRequest,
    ) -> JSONResponse:
        return _task_call_submission_response(
            kernel_application.submit_task_call_items(
                task_id=task_id,
                items=tuple(
                    TaskItem(
                        message_id=item.message_id,
                        event_code=item.event_code,
                        created_at_millis=item.created_at_millis,
                        payload=item.payload,
                        priority=item.priority,
                        expire_at_millis=item.expire_at_millis,
                        allocation_rule=item.allocation_rule,
                    )
                    for item in request.items
                ),
            )
        )

    return app
