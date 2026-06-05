# Server Task Worker API Runtime Boundary Inventory

Status: initial inventory scaffold for
[SERVER_TASK_WORKER_API_RUNTIME_BOUNDARY_ROADMAP.md](./SERVER_TASK_WORKER_API_RUNTIME_BOUNDARY_ROADMAP.md).

This inventory is intentionally current-code-first. Update it during TWA-0
before implementing route or DTO changes.

## Route Inventory

| Method | Route | Current Owner | Current Shape | Classification | Target |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/tasks` | `TaskApiController` | `ApiTaskListResult` of `ApiTask` | mixed task shell/current aggregate | classify fields; narrow or source-label |
| POST | `/api/v1/tasks` | `TaskApiController` | `ApiTaskCreateOutcome` | task shell ingress | keep shell create |
| GET | `/api/v1/tasks/{taskId}` | `TaskApiController` | `ApiTaskGetResult` with `ApiTask` | mixed task shell/current aggregate/security | classify fields; narrow or source-label |
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
| `ApiTask` | `taskId`, `taskName`, `tenantId`, `project`, `userId`, `contract`, `sourceRef`, sanitized `sharedConfig` | control-plane shell | keep shell section |
| `ApiTask` | `status`, `intakeStatus`, `terminalReason`, `holdReason` | task lifecycle/current state | decide shell/current-state section split |
| `ApiTask` | `taskTargetNumber`, `taskEligibleNumber`, `taskSuccessNumber`, `taskNonSuccessNumber`, worker counters | runtime/current aggregate counters | source-label or move to runtime view |
| `ApiTask` | `execution`, `executionSpec`, flat execution aliases | compatibility + execution policy view | reduce alias growth |
| `ApiTask` | `id`, `tid`, flat count aliases, flat timestamp aliases | compatibility alias | keep only if caller evidence requires |
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
| `frontend/src/api/tasks.real.ts` | `/api/v1/tasks` | task list | update after task split/source labels |
| `frontend/src/api/tasks.real.ts` | `/api/v1/tasks/{taskId}` | task detail | update after task split/source labels |
| `frontend/src/api/tasks.real.ts` | `/internal/v1/review/tasks/{taskId}` | task review preview/export | keep separate |
| `frontend/src/api/workers.real.ts` | `/api/v1/runtime/workers` | worker list | keep as composite diagnostic or retarget after worker split |
| task pages | task list/detail/review | mixed task API + review API | preserve separation in page model |
| worker pages | worker list/detail | composite worker row | display source-aware facts |
| dashboard/project pages | task and worker summaries | aggregate console reads | avoid turning diagnostics into public entity truth |

## Worker Registration DB Observation Decision Queue

Open decisions for TWA-1:

- current table only, ledger table only, or both
- required versus best-effort DB write in dev/test/prod
- whether WorkerGroup/AdapterNode/Binding declarations are recorded before
  worker rows, and whether failed runtime registration records anything
- payload storage policy: full bounded JSON, selected fields, request hash, or
  mixed
- retention/cleanup policy, if any

Hard initial decision:

- DB registration observation rows must not be loaded into runtime on startup.
- DB registration observation rows must not drive matching, scheduling,
  transport routing, presence, heartbeat, or command delivery.

