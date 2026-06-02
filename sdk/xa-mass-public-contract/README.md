# xa-mass-public-contract

Status: current public HTTP wire-contract owner.

`xa-mass-public-contract` owns narrow DTOs and constants that are directly
exposed by server Controller request/response contracts and needed by external
SDK callers.

## Role

- Controller-exposed task request DTOs under `com.xa.mass.contract.task`
- public task command and contract enums used by those request DTOs
- public `Task.sharedConfig` key constants used by task routing helpers

## Boundary

This module must stay dependency-clean. It must not depend on Spring, server,
engine, transport, `xa-mass-base`, embedded SDK modules, external SDK modules,
or integrations.

Do not put control-plane internals, review materialization models, diagnostic
models, bootstrap fixtures, transport frames, or embedded runtime assembly types
in this module. A type belongs here only when this README records the owning
Controller method and route role.

## Current First Slice

| Type | Controller method | Route role |
| --- | --- | --- |
| `TaskCreateRequest` | `TaskApiController.createTask` | request body for `POST /api/v1/tasks` |
| `TaskExecutionSpec` | `TaskApiController.createTask` | nested execution policy field in `TaskCreateRequest` |
| `TaskItemBatch` | `TaskApiController.appendTaskItems` | request body for `POST /api/v1/tasks/{taskId}/items` |
| `TaskItemSyncRequest` | `TaskApiController.appendTaskItemSync` | request body for `POST /api/v1/tasks/{taskId}/items:sync` |
| `TaskCommandRequest` | `TaskApiController.executeTaskCommand` | request body for `POST /api/v1/tasks/{taskId}/commands` |
| `TaskCommand` | `TaskApiController.executeTaskCommand` | public command value inside `TaskCommandRequest` |
| `TaskContract` | `TaskApiController.createTask` | public task contract value inside `TaskExecutionSpec` |
| `TaskSharedConfigKeys` | `TaskApiController.createTask` | public shared-config keys consumed from `TaskCreateRequest.sharedConfig` |
