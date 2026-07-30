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
    ResourcesCommandClient,
    TaskApprovalResult,
    TaskApprovalStatus,
    TaskCloseResult,
    TaskCloseStatus,
    TaskCreationResult,
    TaskCreationStatus,
    TaskDescriptor,
    WorkerDeclaration,
    WorkerGroupDescriptor,
    WorkerRuntimeResult,
    WorkerRuntimeStatus,
)


class WorkerGroupRequest(BaseModel):
    attributes: dict[str, Any] = Field(default_factory=dict)
    event_codes: list[str] = Field(alias="eventCodes")
    item_allocation_fields: list[str] = Field(
        default_factory=list,
        alias="itemAllocationFields",
    )


class WorkerRequest(BaseModel):
    endpoint_manager_id: str = Field(alias="endpointManagerId")
    attributes: dict[str, Any] = Field(default_factory=dict)
    dynamic_attribute_names: list[str] = Field(
        default_factory=list,
        alias="dynamicAttributeNames",
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


def _worker_result_response(result: WorkerRuntimeResult) -> JSONResponse:
    status_code = {
        WorkerRuntimeStatus.OK: 200,
        WorkerRuntimeStatus.NOOP: 200,
        WorkerRuntimeStatus.NOT_FOUND: 404,
        WorkerRuntimeStatus.INVALID: 422,
        WorkerRuntimeStatus.REJECTED: 409,
        WorkerRuntimeStatus.STALE: 409,
        WorkerRuntimeStatus.CONFLICT: 409,
    }[result.status]
    return JSONResponse(_result_payload(result), status_code=status_code)


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
    resources_client: ResourcesCommandClient | None = None,
) -> FastAPI:
    if config_json is not None and (
        application is not None
        or resources_client is not None
    ):
        raise ValueError(
            "config_json and injected application boundaries are mutually exclusive"
        )
    injected = (
        application,
        resources_client,
    )
    if any(boundary is not None for boundary in injected) and not all(
        boundary is not None for boundary in injected
    ):
        raise ValueError(
            "application and resource boundaries must be injected together"
        )
    if application is None:
        config = KernelApplicationConfig.from_json(config_json)
        kernel_application = KernelApplication(config)
        resource_commands = ResourcesCommandClient(config)
    else:
        assert resources_client is not None
        kernel_application = application
        resource_commands = resources_client

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        kernel_application.start()
        try:
            yield
        finally:
            kernel_application.stop()

    app = FastAPI(title="Python Kernel Control API", lifespan=lifespan)
    app.state.kernel_application = kernel_application
    app.state.resources_command_client = resource_commands

    @app.exception_handler(ValueError)
    async def invalid_contract_value(
        _request: Request,
        error: ValueError,
    ) -> JSONResponse:
        return JSONResponse({"detail": str(error)}, status_code=422)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.put("/worker-groups/{worker_group_id}")
    def upsert_worker_group(
        worker_group_id: str,
        request: WorkerGroupRequest,
    ) -> JSONResponse:
        return _worker_result_response(
            resource_commands.upsert_worker_group(
                descriptor=WorkerGroupDescriptor(
                    worker_group_id=worker_group_id,
                    attributes=request.attributes,
                    event_codes=frozenset(request.event_codes),
                    item_allocation_fields=frozenset(
                        request.item_allocation_fields
                    ),
                )
            )
        )

    @app.put("/worker-groups/{worker_group_id}/workers/{worker_id}")
    def upsert_worker(
        worker_group_id: str,
        worker_id: str,
        request: WorkerRequest,
    ) -> JSONResponse:
        return _worker_result_response(
            resource_commands.upsert_worker(
                declaration=WorkerDeclaration(
                    worker_id=worker_id,
                    worker_group_id=worker_group_id,
                    endpoint_manager_id=request.endpoint_manager_id,
                    attributes=request.attributes,
                    dynamic_attribute_names=frozenset(
                        request.dynamic_attribute_names
                    ),
                )
            )
        )

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
