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
in this module. A type belongs here only when the inventory records the owning
Controller method and route role.

## Current First Slice

- `TaskCreateRequest`
- `TaskExecutionSpec`
- `TaskItemBatch`
- `TaskItemSyncRequest`
- `TaskCommandRequest`
- `TaskCommand`
- `TaskContract`
- `TaskSharedConfigKeys`

The extraction inventory is
[../../doc/archive/sdk/2026-06-02_SDK_MODULE_LAYOUT_AND_PUBLIC_CONTRACT_INVENTORY.md](../../doc/archive/sdk/2026-06-02_SDK_MODULE_LAYOUT_AND_PUBLIC_CONTRACT_INVENTORY.md).
