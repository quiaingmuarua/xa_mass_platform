# Server Task Worker API Runtime Boundary Inventory

Archive status: archived on 2026-06-05 with its parent roadmap after mainline
implementation. Current truth owners:
`xa-mass-server/doc/INTERNAL_API_REFERENCE.md`,
`xa-mass-server/doc/API_SURFACE_INVENTORY.md`,
`frontend/README.md`, `sdk/README.md`,
`sdk/xa-mass-java-sdk/README.md`, and current code/tests.
Do not use this archived inventory as proof of current behavior.

Status: implemented mainline with
[2026-06-05_SERVER_TASK_WORKER_API_RUNTIME_BOUNDARY_ROADMAP.md](./2026-06-05_SERVER_TASK_WORKER_API_RUNTIME_BOUNDARY_ROADMAP.md).

This inventory is intentionally current-code-first. TWA-0 owner review is
complete; the tables below are the current classification baseline and must
stay current as later slices land.

## Route Inventory

| Method | Route | Current Owner | Current Shape | Classification | Target |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/tasks` | `TaskApiController` | `ApiTaskListResult` of source-labeled `ApiTask` | source-labeled shell/current aggregate composite | keep bounded public read; source labels are current first-pass split |
| POST | `/api/v1/tasks` | `TaskApiController` | `ApiTaskCreateOutcome` with `ApiTaskShell` | task shell ingress | keep shell create; no empty runtime counters/timestamps |
| GET | `/api/v1/tasks/{taskId}` | `TaskApiController` | `ApiTaskGetResult` with source-labeled `ApiTask` | source-labeled task shell/current aggregate/security composite | keep current route; source labels are current first-pass split |
| PATCH | `/api/v1/tasks/{taskId}` | `TaskApiController` | `ApiTaskUpdateOutcome` | operator task shell command | keep bounded command |
| POST | `/api/v1/tasks/{taskId}/items` | `TaskApiController` | `ApiTaskAppendOutcome` | item ingest | keep public ingress |
| POST | `/api/v1/tasks/{taskId}/items:sync` | `TaskApiController` | `ApiTaskSyncAppendOutcome` | item ingest plus result wait | keep SDK ingress/result wait |
| POST | `/api/v1/tasks/{taskId}/commands` | `TaskApiController` | `ApiTaskCommandOutcome` | operator command | keep command intent |
| GET | `/api/v1/tasks/{taskId}/results` | `TaskApiController` | `ApiTaskResultWindow` | result-runtime | keep separate |
| GET | `/api/v1/tasks/{taskId}/results/archive` | `TaskApiController` | `ApiTaskResultArchive` | result-runtime archive | keep separate |
| GET | `/api/v1/tasks/{taskId}/results/archive/content` | `TaskApiController` | gzip ndjson stream | result-runtime archive | keep separate |
| GET | `/internal/v1/review/tasks/{taskId}` | `InternalTaskReviewController` | review/export response | review-materialization | keep server-local |
| GET | `/internal/v1/review/tasks/{taskId}/seed-export` | `InternalTaskReviewController` | export stream | review-materialization | keep server-local |
| GET | `/internal/v1/review/tasks/{taskId}/result-export` | `InternalTaskReviewController` | export stream | review-materialization | keep server-local |
| POST | `/worker-api/v1/worker-groups` | `ExternalWorkerApiController` | worker group declaration | worker registration ingress / WorkerGroup capability | candidate for registration observation write |
| POST | `/worker-api/v1/adapter-nodes` | `ExternalWorkerApiController` | adapter node declaration | worker registration ingress | candidate for registration observation write |
| POST | `/worker-api/v1/node-group-bindings` | `ExternalWorkerApiController` | binding declaration | worker registration ingress | candidate for registration observation write |
| POST | `/worker-api/v1/workers` | `ExternalWorkerApiController` | worker registration | worker registration ingress | candidate for registration observation write |
| POST | `/worker-api/v1/workers/{workerId}:online` | `ExternalWorkerApiController` | presence command | runtime current truth | no DB registration write unless observation decision says event ledger only |
| POST | `/worker-api/v1/workers/{workerId}:heartbeat` | `ExternalWorkerApiController` | heartbeat command | runtime current truth | no DB registration write |
| POST | `/worker-api/v1/workers/{workerId}:offline` | `ExternalWorkerApiController` | presence command | runtime current truth | no DB registration write |
| POST | `/worker-api/v1/workers/{workerId}:poll` | `ExternalWorkerApiController` | polling dispatch | runtime data-plane | no DB registration write |
| POST | `/worker-api/v1/workers/{workerId}:submit-result` | `ExternalWorkerApiController` | result submit | result/runtime data-plane | no registration DB truth |
| POST | `/worker-api/v1/workers/{workerId}:report-capability` | `ExternalWorkerApiController` | capability report | bounded current diagnostic evidence | not registration declaration truth unless TWA-1 decides observation-only ledger |
| POST | `/worker-api/v1/workers/{workerId}:report-state` | `ExternalWorkerApiController` | state report | runtime current diagnostic evidence | no declaration DB write |
| GET | `/api/v1/runtime/workers` | `WorkerApiController` | map rows with `fieldSources` | composite diagnostic | keep source-labeled; not base worker entity |
| GET | `/api/v1/runtime/workers/{workerId}/state` | `WorkerApiController` | worker state projection | runtime current diagnostic evidence | keep diagnostic |
| GET | `/api/v1/runtime/workers/states` | `WorkerApiController` | worker state projections | runtime current diagnostic evidence | keep diagnostic |
| POST | `/api/v1/runtime/workers/{workerId}/commands` | `WorkerApiController` | command request | operator command | keep command intent |
| GET | `/api/v1/runtime/workers/{workerId}/commands` | `WorkerApiController` | command list | runtime/command diagnostic | keep diagnostic |
| GET | `/api/v1/runtime/workers/commands/{commandId}` | `WorkerApiController` | command detail | runtime/command diagnostic | keep diagnostic |
| GET | `/api/v1/catalog/worker-capabilities` | `CatalogController` | worker capability snapshot | catalog + worker runtime/transport composite | classify as capability diagnostic |
| GET | `/api/v1/catalog/worker-group-capabilities` | `CatalogController` | WorkerGroup capability view | WorkerGroup capability truth | keep capability read |

## Field Classification Worklist

| Shape | Fields / Groups | Initial Classification | Target |
| --- | --- | --- | --- |
| `ApiTaskShell` | `taskId`, `taskName`, `tenantId`, `project`, `userId`, `contract`, `sourceRef`, sanitized `sharedConfig`, `execution` | control-plane shell + execution policy | create response shell object; no counters/timestamps/status |
| `ApiTask` | `taskId`, `taskName`, `tenantId`, `project`, `userId`, `contract`, `sourceRef`, sanitized `sharedConfig` | control-plane shell | keep with `fieldSources=controlPlaneShell` |
| `ApiTask` | `status`, `intakeStatus`, `terminalReason`, `holdReason` | runtime/current task lifecycle state | keep on list/detail as source-labeled current-state fields because current frontend filters and detail pages need them |
| `ApiTask` | `taskTargetNumber`, `taskEligibleNumber`, `taskSuccessNumber`, `taskNonSuccessNumber`, worker counters | runtime/current aggregate counters | keep on list/detail as source-labeled runtime counters; future route split may move them to runtime detail |
| `ApiTask` | `execution`, `executionSpec`, flat execution aliases | execution policy + compatibility | keep `execution`; mark `executionSpec` and flat aliases as compatibility |
| `ApiTask` | `id`, `tid`, flat count aliases, flat timestamp aliases | compatibility alias | keep only as current caller compatibility; do not add more aliases |
| `ApiTaskResultWindow` | `items`, `nextAfterSeq`, `hasMore`, archive flags | result-runtime | keep separate |
| review response | seed/result preview, exports | review-materialization | keep internal/server-local |
| worker runtime row | `workerId`, `workerGroupId`, `adapterNodeId`, `adapterId`, attributes, declared capacity | declaration-ish field group | source-label as declaration |
| worker runtime row | `transportReachability`, `transportOnline`, `connections`, `hasActiveEndpoint` | transport/session evidence | source-label as transport |
| worker runtime row | `locked`, state projections, command projections | runtime current diagnostic | source-label as runtime |
| worker runtime row | `eventBindings` | WorkerGroup capability | source-label as capability |
| worker runtime row | `supportedEventCodes`, `supportedProjects` | compatibility derived display/filter hints | do not treat as capability owner |

## Frontend Caller Inventory

| Caller | Route | Current Use | Target |
| --- | --- | --- | --- |
| `frontend/src/api/tasks.real.ts` | `/api/v1/tasks` | task list | preserves source-labeled task rows through typed response |
| `frontend/src/api/tasks.real.ts` | `/api/v1/tasks/{taskId}` | task detail | preserves source-labeled task detail through typed response |
| `frontend/src/api/tasks.real.ts` | `/internal/v1/review/tasks/{taskId}` | task review preview/export | keep separate |
| `frontend/src/api/workers.real.ts` | `/api/v1/runtime/workers` | worker list | keep as composite diagnostic and preserve `fieldSources` in type/adapter tests |
| task pages | task list/detail/review | mixed task API + review API | preserve separation in page model |
| worker pages | worker list/detail | composite worker row | display source-aware facts |
| dashboard/project pages | task and worker summaries | aggregate console reads | avoid turning diagnostics into public entity truth |

## Documentation Drift

| Document | Current Drift | Target |
| --- | --- | --- |
| `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` | previously said Task API was already explicitly split while `ApiTask` remained mixed | repaired during TWA-1A/TWA-2 to say route families are split while list/detail `ApiTask` remains source-labeled composite |

## Worker Registration DB Observation Decision

TWA-1B current decision:

- Use a server-owned append-only ledger table:
  `xa_worker_registration_observation`.
- Record only successful public registration ingress after the runtime owner
  accepts the operation:
  WorkerGroup declaration, AdapterNode registration, NodeGroupBinding, and
  Worker registration.
- Writes are best-effort and happen after successful runtime registration.
  Observation write failure is logged and must not flip the public registration
  response from success to failure.
- Store selected response facts as bounded JSON plus request hash, principal id
  and type, resource type/id, action, and occurred timestamp.
- Do not record failed runtime registration attempts in this slice.
- Retention/cleanup is deferred; the table is low-volume analysis/audit output
  at the current project stage.

Hard initial decision:

- DB registration observation rows must not be loaded into runtime on startup.
- DB registration observation rows must not drive matching, scheduling,
  transport routing, presence, heartbeat, or command delivery.
- DB registration observation rows must not implement or replace
  `WorkerDeclarationStore`; that owner remains in `xa-mass-worker-runtime`.
- DB registration observation rows live in
  `xa-mass-server/src/main/resources/db/schema/server-control-plane` for schema
  notes and `xa-mass-server/src/main/resources/db/migration/server-control-plane`
  for executable Flyway SQL, not in `platform_infra/mass-storage-jdbc`.

## Deferred Frontend Consumer Follow-Up

Server API split remains the main roadmap owner. Frontend source-aware page
presentation is deferred to
[`../frontend/WORKER_SOURCE_AWARE_PRESENTATION_FOLLOWUP.md`](../frontend/WORKER_SOURCE_AWARE_PRESENTATION_FOLLOWUP.md).
No backend route/response-shape contract changes are deferred there; frontend
real adapters, type files, and affected tests are updated in this roadmap.

If deferred, only source-aware page presentation can move to a frontend-local
follow-up. Frontend real adapters, type files, and affected tests must be
updated in the same slice as any backend route or response-shape change.

Known frontend follow-up candidates:

| File | Current Gap | Target |
| --- | --- | --- |
| `frontend/src/types/workers.ts` | `WorkerListItem` now models `fieldSources`; page presentation still does not surface source labels | optional UI presentation follow-up only |
| `frontend/src/api/workers.real.ts` | consumes `/api/v1/runtime/workers` as source-labeled `WorkerListResponse` | adapter contract preserved; optional page presentation follow-up |
| worker/dashboard/project pages | display mixed worker fields without owner source | handled by the named frontend follow-up |
