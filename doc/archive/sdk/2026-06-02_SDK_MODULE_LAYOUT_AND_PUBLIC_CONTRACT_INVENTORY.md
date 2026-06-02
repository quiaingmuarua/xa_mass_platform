# SDK Module Layout And Public Contract Inventory

Status: implemented inventory for
`doc/archive/sdk/2026-06-02_SDK_MODULE_LAYOUT_AND_PUBLIC_CONTRACT_ROADMAP.md`.

This inventory records SDK module layout facts and the first implemented
Controller-exposed public-contract candidates. It is intentionally narrow:
`xa-mass-public-contract` may only own server Controller wire DTOs/constants
that are also needed by external SDK callers.

## Module Layout Facts

| Current Module Path | Current Artifact | Current Role | Notes |
| --- | --- | --- | --- |
| `sdk/xa-mass-java-sdk` | `xa-mass-java-sdk` | external Java HTTP SDK for task producers and external workers | moved from the previous integration path; artifact ID unchanged |
| `sdk/xa-mass-embedded-sdk-api` | `xa-mass-embedded-sdk-api` | embedded/runtime-facing SDK API; may depend on platform internals | renamed from the previous embedded SDK API artifact; Java packages unchanged |
| `sdk/xa-mass-embedded-sdk` | `xa-mass-embedded-sdk` | embedded JVM runtime composition SDK | renamed from the previous embedded SDK artifact; Java packages unchanged |
| `sdk/xa-mass-public-contract` | `xa-mass-public-contract` | pure Controller wire contract module | new dependency-clean public contract owner |
| `integrations/xa-mass-scenario-launcher` | `xa-mass-scenario-launcher` | external SDK adopter/proof | keep under `integrations/` |
| `integrations/xa-mass-worker-pack` | `xa-mass-worker-pack` | real worker capability pack and SDK adopter | keep under `integrations/` |

## Boundary Decisions

- `xa-mass-public-contract` owns only Controller-exposed wire DTOs/constants.
- A candidate must name the owning `*Controller` method and route role.
- Nested wire DTOs required by a candidate must be listed with the same
  Controller method.
- `ApiResponse<T>` is not part of the first public-contract slice. The external
  SDK currently decodes the envelope by field names, and SCL-1 should keep that
  behavior unless a later inventory update explicitly promotes the envelope.
- SDK convenience builders may remain in `com.xa.mass.client.*` even when they
  build public-contract wire DTOs.

## First SCL-1 Candidate Set

The first slice should target task-scoped external SDK invocation because it is
already covered by Java external SDK tests and avoids expanding worker/control
surfaces.

| Candidate | Current Owner | Controller Method | Route Role | Nested Wire Closure | Target |
| --- | --- | --- | --- | --- | --- |
| `TaskShellCreateApiRequest` | previous server-local `com.xa.mass.api.model.task` | `TaskApiController.createTask` | request body for `POST /api/v1/tasks` | `TaskExecutionSpec` public-contract equivalent | implemented as `com.xa.mass.contract.task.TaskCreateRequest` |
| `TaskExecutionOptions` equivalent | previous embedded SDK API `com.xa.mass.sdk.model` | `TaskApiController.createTask` through `TaskCreateRequest.executionSpec` | nested request body field | none expected | implemented as `com.xa.mass.contract.task.TaskExecutionSpec`; server converts to embedded `TaskExecutionOptions` internally |
| `TaskApiContracts.ApiTaskCreateOutcome` | `xa-mass-server` `com.xa.mass.api.model.task` | `TaskApiController.createTask` | response body data for `ApiResponse<ApiTaskCreateOutcome>` | `ApiTask` plus its nested wire DTOs if included | candidate; may be deferred if first slice starts request-only |
| `TaskItemBatchIngestApiRequest` | previous server-local `com.xa.mass.api.model.task` | `TaskApiController.appendTaskItems` | request body for `POST /api/v1/tasks/{taskId}/items` | none; `items` is opaque `List<Object>` | implemented as `com.xa.mass.contract.task.TaskItemBatch` |
| `TaskApiContracts.ApiTaskAppendOutcome` | `xa-mass-server` `com.xa.mass.api.model.task` | `TaskApiController.appendTaskItems` | response body data for `ApiResponse<ApiTaskAppendOutcome>` | none | move or recreate under `com.xa.mass.contract.task` |
| `TaskItemSyncIngestApiRequest` | previous server-local `com.xa.mass.api.model.task` | `TaskApiController.appendTaskItemSync` | request body for `POST /api/v1/tasks/{taskId}/items:sync` | none; `item` is opaque `Object` | implemented as `com.xa.mass.contract.task.TaskItemSyncRequest` |
| `TaskApiContracts.ApiTaskSyncAppendOutcome` | `xa-mass-server` `com.xa.mass.api.model.task` | `TaskApiController.appendTaskItemSync` | response body data for `ApiResponse<ApiTaskSyncAppendOutcome>` | none | move or recreate under `com.xa.mass.contract.task` |
| `TaskCommandApiRequest` | previous server-local `com.xa.mass.api.model.task` | `TaskApiController.executeTaskCommand` | request body for `POST /api/v1/tasks/{taskId}/commands` | public command enum/string decision required | implemented as `com.xa.mass.contract.task.TaskCommandRequest` plus `TaskCommand` |
| `TaskApiContracts.ApiTaskCommandOutcome` | `xa-mass-server` `com.xa.mass.api.model.task` | `TaskApiController.executeTaskCommand` | response body data for `ApiResponse<ApiTaskCommandOutcome>` | none | move or recreate under `com.xa.mass.contract.task` |
| task routing shared-config keys | previous external SDK local `TaskSharedConfigKeys`; server consumes via `Task.sharedConfig` route body | `TaskApiController.createTask` through `TaskCreateRequest.sharedConfig` | public constant names inside request body map | none | implemented as `com.xa.mass.contract.task.TaskSharedConfigKeys` |

## Explicit Non-Candidates For SCL-1

| Surface | Reason |
| --- | --- |
| `ApiResponse<T>` | first slice keeps envelope decoding SDK-local/manual; moving it would widen all route contracts |
| `TaskApiContracts.ApiTask`, `ApiTaskExecution`, `ApiTaskCounters`, `ApiTaskTimestamps` | response nested closure for create/list/detail; include only if SCL-1 chooses response migration instead of request-first migration |
| `TaskApiContracts.ApiTaskResultWindow`, `ApiTaskResultItem`, `ApiTaskResultArchive` | public task result routes, but not needed for the first create/append/command contract extraction |
| `ExternalWorkerApiController` registration/polling DTOs | public external worker routes, but moving them in SCL-1 would mix task contract extraction with worker control surface extraction |
| review materialization events and read-model writer DTOs | not Controller request/response bodies for the external SDK contract |
| server bootstrap/mock data loader DTOs | test/bootstrap support, not production Controller wire contract |
| transport frame models such as `TaskDispatchItem` and `TaskResultReport` | transport implementation contract, not `xa-mass-public-contract` first-cut scope |
| embedded SDK starter/config/builder APIs | embedded runtime composition, not external Controller wire contract |

## SCL-1 Recommendation

Implemented request-first:

1. create `sdk/xa-mass-public-contract`;
2. recreate `TaskShellCreateApiRequest` public wire shape as
   `TaskCreateRequest` with public `TaskExecutionSpec`;
3. recreate `TaskItemBatchIngestApiRequest` as `TaskItemBatch`;
4. recreate `TaskItemSyncIngestApiRequest` as `TaskItemSyncRequest`;
5. recreate `TaskCommandApiRequest` as `TaskCommandRequest`;
6. move task routing shared-config key constants into
   `TaskSharedConfigKeys`.

This proves the contract-owner split without forcing all task response DTOs or
the `ApiResponse<T>` envelope into the first slice.
