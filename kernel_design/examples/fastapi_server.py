from __future__ import annotations

import argparse
import logging
from collections.abc import Mapping
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from kernel_design.executable_spec.assembly import (
    TaskType,
    DeliverSeed,
    DeliverSeedConsumerClient,
    KernelApplication,
    KernelApplicationConfig,
    ResourcesCommandClient,
    TaskApprovalResult,
    TaskApprovalStatus,
    TaskCreationResult,
    TaskCreationStatus,
    TaskDescriptor,
    TaskItem,
    TaskItemAppendResult,
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


class TaskItemRequest(BaseModel):
    message_id: str = Field(alias="messageId")
    event_code: str = Field(alias="eventCode")
    created_at_millis: int = Field(alias="createdAtMillis")
    payload: dict[str, Any]
    priority: int = 5
    expire_at_millis: int | None = Field(default=None, alias="expireAtMillis")
    allocation_rule: dict[str, Any] | None = Field(
        default=None,
        alias="allocationRule",
    )


class AppendTaskItemsRequest(BaseModel):
    items: list[TaskItemRequest]


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


def _result_map_payload(
    results: Mapping[str, TaskItemAppendResult],
) -> dict[str, dict[str, Any]]:
    return {
        message_id: _result_payload(result)
        for message_id, result in results.items()
    }


def _deliver_seed_payload(seed: DeliverSeed) -> dict[str, Any]:
    return {
        "workerId": seed.worker_id,
        "opaqueDeliveryItem": seed.opaque_delivery_item,
        "opaqueResultContext": seed.opaque_result_context,
        "taskItemClaimUntilMillis": seed.task_item_claim_until_millis,
    }


def create_app(
    *,
    config_json: str | None = None,
    application: KernelApplication | None = None,
    resources_client: ResourcesCommandClient | None = None,
    deliver_seed_consumer: DeliverSeedConsumerClient | None = None,
) -> FastAPI:
    if config_json is not None and (
        application is not None
        or resources_client is not None
        or deliver_seed_consumer is not None
    ):
        raise ValueError(
            "config_json and injected application boundaries are mutually exclusive"
        )
    injected = (
        application,
        resources_client,
        deliver_seed_consumer,
    )
    if any(boundary is not None for boundary in injected) and not all(
        boundary is not None for boundary in injected
    ):
        raise ValueError(
            "application, resources_client, and deliver_seed_consumer "
            "must be injected together"
        )
    if application is None:
        config = KernelApplicationConfig.from_json(config_json)
        kernel_application = KernelApplication(config)
        resource_commands = ResourcesCommandClient(config)
        deliver_seed_commands = DeliverSeedConsumerClient(config)
    else:
        assert resources_client is not None
        assert deliver_seed_consumer is not None
        kernel_application = application
        resource_commands = resources_client
        deliver_seed_commands = deliver_seed_consumer

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        kernel_application.start()
        try:
            yield
        finally:
            kernel_application.stop()

    app = FastAPI(title="Kernel Executable Spec", lifespan=lifespan)
    app.state.kernel_application = kernel_application
    app.state.resources_command_client = resource_commands
    app.state.deliver_seed_consumer_client = deliver_seed_commands

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
                )
            )
        )

    @app.post("/tasks/{task_id}/approve")
    def approve_task(task_id: str) -> JSONResponse:
        return _approval_response(kernel_application.approve_task(task_id=task_id))

    @app.post("/tasks/{task_id}/items")
    def append_task_items(
        task_id: str,
        request: AppendTaskItemsRequest,
    ) -> dict[str, dict[str, Any]]:
        items = tuple(
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
        )
        return _result_map_payload(
            kernel_application.append_task_items(task_id=task_id, items=items)
        )

    @app.post(
        "/endpoint-managers/{endpoint_manager_id}/deliver-seeds:consume"
    )
    def consume_deliver_seeds(
        endpoint_manager_id: str,
        limit: int,
    ) -> list[dict[str, Any]]:
        return [
            _deliver_seed_payload(seed)
            for seed in deliver_seed_commands.consume_deliver_seeds(
                endpoint_manager_id=endpoint_manager_id,
                limit=limit,
            )
        ]

    return app


def main() -> None:
    parser = argparse.ArgumentParser(description="Start the kernel FastAPI example.")
    parser.add_argument("--config", type=Path, help="optional kernel JSON config")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
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
        raise RuntimeError("uvicorn is required for the FastAPI example") from error
    uvicorn.run(
        create_app(config_json=config_json),
        host=args.host,
        port=args.port,
        log_level=args.log_level,
    )


if __name__ == "__main__":
    main()
