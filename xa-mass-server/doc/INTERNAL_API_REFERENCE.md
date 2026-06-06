# XA Mass Platform Internal API Reference

Last updated: 2026-05-12

Status: current global HTTP/API reference.

This document tracks the active HTTP surface in the mainline runtime after the
API v1 normalization and task shell / item ingest split.

Status labels used below:

- `Implemented`: endpoint exists and is wired into the current runtime
- `Partial`: endpoint exists but is mainly diagnostic or thin passthrough
- `Console`: backend-served SPA shell or route handled by the control console

Scope:

- HTTP endpoint inventory
- current request contract
- response shape notes
- implementation status

Out of scope:

- startup commands
- full E2E walkthrough detail
- architecture history

For verified startup and validation flows, use
[VERIFIED_RUNBOOK.md](../../xa-mass-testing/VERIFIED_RUNBOOK.md).

## 1. Scope Notes

- This file is an HTTP/API dictionary. For platform and boundary truth, use
  [AGENT_BASELINE.md](../../doc/AGENT_BASELINE.md).
- The HTTP surface validates the kernel; it does not define the kernel.
- Formal API prefixes are now versioned:
  - `/api/v1/**` for operator and SDK-facing control-plane APIs
  - `/worker-api/v1/**` for repo-external worker data-plane APIs
  - `/internal/v1/**` for debug and test-only surfaces
- Historical unversioned public routes are removed from the active runtime.
- `Task.sharedConfig` plus per-item runtime payload / `payloadRef` remain the
  generic payload boundaries.
- `target` is only a conventional key inside the per-item input payload.
- `eventCode` is the global event/capability identity.
- `Task.project` and `Task.user` remain first-class core bindings even though
  some API shapes use `project` and `userId`.
- current runtime is single-tenant, but API/model semantics are tenant-aware;
  projects resolve under the default tenant `default`
- framework-owned task-create ownership metadata is persisted in the reserved
  internal envelope `Task.sharedConfig._massSecurity`; HTTP read models expose
  supported ownership state through `data.security`, not the raw reserved
  envelope.
- task detail is now shell-oriented and does not implicitly return item payload
  snapshots. Public v1 keeps task item/result visibility behind an explicit
  review/export surface instead of mixing it into shell detail.
- public task create is now shell-only. Public ingest is explicit and happens
  after shell creation by `taskId`.
- request safety for item ingest is enforced at the server ingress layer with:
  - HTTP request size guard
  - batch item count limit
  - single item serialized size limit
  - total serialized batch size limit

## 2. Auth and Surface Partitioning

### 2.1 Auth API

Base path: `/api/v1/auth`

#### Get Current Operator Principal

- Method: `GET`
- Path: `/api/v1/auth/me`
- Status: `Implemented`

#### Logout Operator Principal

- Method: `POST`
- Path: `/api/v1/auth/logout`
- Status: `Implemented`

Notes:

- These routes are for control-console/operator auth state.
- submitter credentials use API-key/Bearer auth and do not participate in
  operator session login.

### 2.2 Submitter Credential Introspection

Base path: `/api/v1/submitters`

#### Get Current SDK Submitter

- Method: `GET`
- Path: `/api/v1/submitters/me`
- Status: `Implemented`

Headers:

- `X-Mass-Api-Key: <credential>` or `Authorization: Bearer <credential>`

Notes:

- resolves the current credential through `AuthProvider.authenticate(...)`
- returns the authenticated submitter view with `principalId`, `userId`,
  `projectScope`, `permissions`, `projectScopes`, `eventScopes`, and
  `attributes`
- does not expose raw credential material
- returns HTTP `401` when the credential is missing or invalid

## 3. Project API

Base path: `/api/v1/projects`

### 3.1 List Projects

- Method: `GET`
- Path: `/api/v1/projects`
- Status: `Implemented`

Returns the registered project list.

### 3.2 Get Project

- Method: `GET`
- Path: `/api/v1/projects/{projectCode}`
- Status: `Implemented`

Returns HTTP `404` when `projectCode` does not exist.

### 3.3 List Project Events

- Method: `GET`
- Path: `/api/v1/projects/{projectCode}/events`
- Status: `Implemented`

Returns the full `EventDefinition` list for the project's declared
`eventCodes`. Event definitions include descriptive metadata fields:
`priorityClass`, `responseMode`, and `targetScope`. These fields are catalog
metadata and do not change task scheduling, result finality, or transport
delivery behavior by themselves.

### 3.4 List Project Submitters

- Method: `GET`
- Path: `/api/v1/projects/{projectCode}/submitters`
- Status: `Implemented`

Returns the effective submitter list visible for the project scope.

Notes:

- includes both explicitly project-scoped submitters and wildcard/global
  submitters whose scopes still authorize the project
- this is a control-plane ownership view; it does not expose raw credentials

## 4. Catalog API

Base path: `/api/v1/catalog`

### 4.1 List Events

- Method: `GET`
- Path: `/api/v1/catalog/events`
- Status: `Implemented`

Notes:

- returns the registered `EventDefinition` list
- each event includes `priorityClass`, `responseMode`, and `targetScope`
  metadata with conservative defaults
- `taskModes=[]` means the event is direct runtime discovery/dispatch, not a
  task-backed event
- event metadata is descriptive only; it is not queue placement, result
  convergence, or worker command routing truth

### 4.2 Get Event

- Method: `GET`
- Path: `/api/v1/catalog/events/{eventCode}`
- Status: `Implemented`

Returns HTTP `404` when `eventCode` does not exist.

### 4.3 List Event Capabilities

- Method: `GET`
- Path: `/api/v1/catalog/event-capabilities`
- Status: `Implemented`

Notes:

- returns one row per registered global event
- each row includes `priorityClass`, `responseMode`, and `targetScope` from the
  registered event definition
- `invocationModel=TASK_BACKED` means the event enters through the task shell
  create plus item ingest flow
- `invocationModel=DIRECT_RUNTIME` means the event is handled directly by the
  SDK runtime definition
- `ready=true` means either a direct runtime handler exists or at least one
  online worker belongs to a WorkerGroup whose capability declaration binds the
  event

### 4.4 List Worker Capability Snapshots

- Method: `GET`
- Path: `/api/v1/catalog/worker-capabilities`
- Status: `Implemented`

Notes:

- joins WorkerGroup capability declarations, worker declarations, and current
  transport/session snapshots by `workerGroupId` / `workerId`
- `eventBindings` is projected from WorkerGroup capability truth
- `supportedEventCodes` remains a flat compatibility/read convenience derived
  from WorkerGroup capability declarations; do not treat it as a separate
  worker-row matching owner
- `adapterId` is the concrete runtime adapter identity
- `transportHint` is the coarse transport family
- `online` follows transport presence truth, not the worker model status field
- `connections` and `hasActiveEndpoint` are reachability facts from the
  transport/session layer, not capability truth
- each row includes `fieldSources`, a field-to-owner label map. Expected
  owners are `declaration`, `runtime`, `transport`,
  `declarationOrTransport`, and `workerGroupCapability`.

## 5. Task API

Base path: `/api/v1/tasks`

Task API route families are split into:

- shell lifecycle and shell create
- item ingest
- command routes
- result reads

Public create no longer accepts `inputs` and no longer mixes create with
dispatch.

Public task responses use the shared `ApiResponse<T>` envelope and server-owned
canonical task objects rather than anonymous controller maps. List/detail task
responses are still source-labeled composite rows: `ApiTask` carries shell,
current-state, execution, counter, timestamp, and compatibility fields, with
`fieldSources` identifying each top-level field owner. Do not treat the
list/detail `ApiTask` shape as a pure shell object.

The current task response objects are:

- `ApiTaskShell`: shell-only object returned by task create; it does not carry
  empty runtime counter or timestamp containers
- `ApiTask`: task shell, state, intake, execution, counters, and timestamps
- `ApiTaskExecution`: `profile`, `workloadClass`, `batchSize`,
  `maxRuntimeSeconds`, `defaultMaxRetryCount`
- `ApiTaskCounters`: target, eligible, success, non-success, and worker counters
- `ApiTaskTimestamps`: created, updated, started, and ended timestamps
- `ApiTaskCommandOutcome`: unified command result
- `ApiTaskResultWindow`: live ordered result read window
- `ApiTaskResultArchive`: terminal archive manifest

`fieldSources` labels use values such as `controlPlaneShell`,
`runtimeCurrent`, `executionPolicy`, `lifecycleTimestamp`, and
`compatibilityAlias`.

### 4.1 List Tasks

- Method: `GET`
- Path: `/api/v1/tasks`
- Status: `Implemented`

Query params:

- `keyword` optional
- `project` optional, exact project code filter
- `status` optional
- `offset` optional, default `0`
- `limit` optional, default `500`

Notes:

- operator callers require normal task-view authorization
- submitter credential callers may also use this route
- current SDK list behavior is ownership-scoped
- project filtering is shell-level ownership filtering, not item-level
  `eventCode` filtering
- response `data` is `ApiTaskListResult` with `items` and `total`
- each item is an `ApiTask`
- each item includes `fieldSources`; `status`, `intakeStatus`,
  `terminalReason`, and `holdReason` are current-state fields, not pure shell
  fields

### 4.2 Create Task Shell

- Method: `POST`
- Path: `/api/v1/tasks`
- Status: `Implemented`

Supported request fields:

- `userId`
- `project`
- `contract` (`SESSION` or `BATCH`) top-level field; current public preset
  input used to resolve task scheduling policy defaults
- `sharedConfig`
- `executionSpec`
- `sourceRef`

Not supported on this route:

- `inputs`
- retired fields such as `targetJsonList`, `targetType`, and `extraParams`

Contract rules:

- `project` and `userId` are required after auth scoping is resolved
- unknown JSON fields are rejected
- `taskName` is server-derived and persisted on the shell; callers must not
  provide it
- `eventCode` is not part of task shell truth and must not be provided on this
  route
- `contract` is a top-level field; providing it inside `executionSpec` is
  rejected with an error
- omitted `executionSpec` resolves to default task execution policy
- public create creates only the task shell and opens normal intake for later
  append/seal flow
- optional `sharedConfig.routeAttributes` may carry route-bucket hints; only
  engine-approved keys are used for Stage-1 candidate narrowing

Example request:

```json
{
  "userId": "agent",
  "project": "demoApp",
  "sharedConfig": {
    "site": "example",
    "routeAttributes": {
      "region": "us"
    }
  },
  "executionSpec": {
    "profile": "STANDARD",
    "workloadClass": "INTERACTIVE",
    "batchSize": 1,
    "maxRuntimeSeconds": 60
  }
}
```

Example response:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "taskId": "task-uuid",
    "task": {
      "taskId": "task-uuid",
      "id": "task-uuid",
      "tid": "task-uuid",
      "taskName": "demoApp-BATCH-task-uuid",
      "project": "demoApp",
      "userId": "agent",
      "contract": "BATCH",
      "execution": {
        "profile": "STANDARD",
        "workloadClass": "BULK",
        "batchSize": 1,
        "maxRuntimeSeconds": 60,
        "defaultMaxRetryCount": 0
      },
      "fieldSources": {
        "taskId": "controlPlaneShell",
        "taskName": "controlPlaneShell",
        "project": "controlPlaneShell",
        "userId": "controlPlaneShell",
        "execution": "executionPolicy"
      }
    },
    "message": "Task shell created"
  }
}
```

### 4.3 Get Task Detail

- Method: `GET`
- Path: `/api/v1/tasks/{taskId}`
- Status: `Implemented`

Response notes:

- returns `task`
- returns `security`
- does not return item payload snapshots by default
- submitter credential callers may use this route under the same ownership-based
  task-view gate
- response `data` is `ApiTaskGetResult`
- `data.task` is `ApiTask`
- `data.security` is the server-owned security/config view
- `data.task.fieldSources` labels shell/current-state/execution/counter/
  timestamp/compatibility owners

### 4.4 Update Task Shell

- Method: `PATCH`
- Path: `/api/v1/tasks/{taskId}`
- Status: `Implemented`

Supported request fields:

- `userId`
- `project`
- `sharedConfig`

Contract rules:

- shell-only update path
- only `NEW` and `BLOCKED` tasks may be updated
- omitted fields keep the currently persisted values
- `taskName` is server-derived and cannot be patched
- `inputs` and unknown fields are rejected with HTTP `400`

### 4.5 Delete Task

- Method: `DELETE`
- Path: `/api/v1/tasks/{taskId}`
- Status: `Removed from public task API`

Contract rules:

- physical task delete is not a public mainline capability
- public callers should use `POST /api/v1/tasks/{taskId}/commands` with
  `TERMINATE` when they need to close task lifecycle
- operator cleanup, if needed, belongs under an internal/operator-only surface

### 4.6 Append Task Items

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}/items`
- Status: `Implemented`

Request shape:

```json
{
  "eventCode": "demo.dispatch",
  "items": [
    {
      "target": "target-001"
    },
    {
      "target": "target-002"
    }
  ]
}
```

Contract rules:

- `items` must be a non-empty list
- append requires batch-level `eventCode` or per-item `eventCode`
- task must exist
- task intake must still be open
- request is subject to ingress safety limits
- payload is treated as opaque ingress data; the runtime does not infer payload
  schema from the task shell

Current server guardrails:

- max item count: `500`
- max single item serialized size: `64 KiB`
- max total serialized batch size: `1 MiB`

### 4.6.1 Sync Append One Item

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}/items:sync`
- Status: `Implemented`

Request shape:

```json
{
  "eventCode": "demo.dispatch",
  "item": {
    "target": "target-001"
  },
  "timeoutMs": 5000,
  "clientRequestId": "req-001"
}
```

Contract rules:

- appends exactly one item to an already-created task
- task must be in `READY` or `RUNNING`
- task intake must be `OPEN`
- append requires request-level `eventCode` or item-level `eventCode`
- resolved event/capability identity must collapse to exactly one eventCode
- request is subject to the same ingress safety limits as normal append
- server wait is handled as an async HTTP request lifecycle rather than blocking
  a servlet thread for the full worker round-trip
- server enforces in-flight protection at global, project, and task scope; when
  exceeded this route returns HTTP `429`
- timeout only ends the HTTP wait; the appended item continues running
- response includes `taskId`, `messageId`, `synced`, `timedOut`, `timeoutMs`,
  and when available the stable-final result payload fields
- this route reads final truth from runtime result state, not server review rows

### 4.7 Task Command Surface

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}/commands`
- Status: `Implemented`

Request shape:

```json
{
  "command": "SEAL",
  "reason": "optional",
  "options": {}
}
```

Supported commands:

- `APPROVE`
- `REJECT`
- `BLOCK`
- `PAUSE`
- `RESUME`
- `TERMINATE`
- `SEAL`

Response notes:

- returns unified command execution result
- includes post-command task state fields such as `status`, `intakeStatus`,
  `terminalReason`, and `holdReason`
- invalid command name returns `400`
- command rejected by current task state returns `409`
- response `data` is `ApiTaskCommandOutcome`

### 4.8 Task Detail Boundaries

- `GET /api/v1/tasks/{taskId}` returns task shell, aggregate state, and security view
- task shell detail remains separate from task review/export payload visibility
- residue or projection diagnostics are not part of the default task detail contract

### 4.9 Task Result Stream

- Method: `GET`
- Path: `/api/v1/tasks/{taskId}/results`
- Status: `Implemented`

Query parameters:

- `afterSeq` optional
- `limit` optional, bounded read window rather than paging contract

Response notes:

- returns committed stable-final rows from `TaskResultRuntime`, not
  server review materialization
- response fields include `mode`, `taskTerminal`, `archiveReady`, `items`,
  `nextAfterSeq`, `hasMore`, and optional `archiveUrl`
- callers own checkpointing through `afterSeq`
- this route does not expose paging or ack semantics
- response `data` is `ApiTaskResultWindow`

### 4.10 Task Result Archive Manifest

- Method: `GET`
- Path: `/api/v1/tasks/{taskId}/results/archive`
- Status: `Implemented`

Behavior:

- returns terminal-result archive manifest
- archive contract is fixed to `ndjson`
- content encoding is surfaced explicitly, currently `gzip`
- archive rows are streamed from `TaskResultRuntime` committed visible rows
- `byteSize` and `checksum` may be `null` when the runtime does not materialize
  archive metadata ahead of download
- response `data` is `ApiTaskResultArchive`

### 4.11 Task Result Archive Content

- Method: `GET`
- Path: `/api/v1/tasks/{taskId}/results/archive/content`
- Status: `Implemented`

Behavior:

- downloads the archive payload for terminal task results
- `Content-Type: application/x-ndjson`
- `Content-Encoding: gzip` when declared by the manifest
- response is streamed directly from the SDK/runtime writer; the controller does
  not buffer the full archive in memory before sending it
- controller must not read projection rows for public result responses

### 4.12 Task Item Stage Evidence

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}/evidence`
- Status: `Implemented`

Behavior:

- reports bounded task item stage evidence through the SDK
  `TaskStageEvidenceOperations` owner-backed surface
- writes stage evidence only; it does not enter `/results` and does not create
  stable-final task result rows
- path variables own `taskId`, `messageId`, and `stageName`
- response `data` is the SDK `TaskStageEvidenceSnapshot`

### 4.13 Task Item Stage Projection Reads

- Method: `GET`
- Paths:
  - `/api/v1/tasks/{taskId}/items/{messageId}/stages`
  - `/api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}`
- Status: `Implemented`

Behavior:

- reads bounded stage projections through the SDK `TaskStageEvidenceOperations`
  surface
- list response uses `{items,total}` in the shared `ApiResponse` envelope
- projection reads are stage evidence read models, not task result rows

### 4.14 Internal Review Preview

- Method: `GET`
- Path: `/internal/v1/review/tasks/{taskId}`
- Status: `Implemented`

Behavior:

- returns bounded seed/result preview rows for console or debug review only
- this is not part of the public task API contract

### 4.15 Internal Review Seed Export

- Method: `GET`
- Path: `/internal/v1/review/tasks/{taskId}/seed-export`
- Status: `Implemented`

### 4.16 Internal Review Result Export

- Method: `GET`
- Path: `/internal/v1/review/tasks/{taskId}/result-export`
- Status: `Implemented`

### 4.17 Removed Task Route Shapes

The following historical task route shapes are no longer part of the active
public API:

- create-with-inputs on public create
- `:approve`, `:reject`, `:pause`, `:resume`, `:block`, `:terminate`, `:seal`
- public `/review`, `/review/seed-export`, `/review/result-export`
- public `DELETE /api/v1/tasks/{taskId}`
- `/messages` as the main item list route

## 5. Runtime Diagnostics API

Base path: `/api/v1/runtime`

These routes are control-plane/operator diagnostics. They do not redefine
kernel truth.

### 5.1 Queue Detail

- Method: `GET`
- Path: `/api/v1/runtime/queues`
- Status: `Implemented`

Response notes:

- returns queue/detail diagnostics from the SDK internal transport debug handle
- operator diagnostic surface only

### 5.2 Queue Metrics

- Method: `GET`
- Path: `/api/v1/runtime/queues/metrics`
- Status: `Partial`

Current behavior:

- endpoint exists
- currently returns reserved metrics data rather than a stable throughput
  contract

### 5.3 List Sessions

- Method: `GET`
- Path: `/api/v1/runtime/sessions`
- Status: `Implemented`
- Query:
  - `limit` optional response window, default `200`, maximum `500`

Current meaning:

- returns transport endpoint snapshots, not a kernel worker truth source
- session diagnostics are operator-only read surfaces
- the response window bounds payload size; the current diagnostics owner still
  reads the live session list before the server applies the window

### 5.4 Session Stats

- Method: `GET`
- Path: `/api/v1/runtime/sessions:stats`
- Status: `Implemented`

Current meaning:

- `activeConnections` counts addressable transport endpoints
- `workerCount` counts distinct workers represented in the endpoint snapshot set
- `activeConnectionsByAdapter` breaks active endpoints down by concrete
  `adapterId`

### 5.5 Global Project Config

- Method: `GET`
- Path: `/api/v1/runtime/config/projects`
- Status: `Implemented`

Behavior:

- returns configured project codes from `GlobalConfig`
- intended for backend-served console/runtime configuration reads

### 5.6 List Workers

- Method: `GET`
- Path: `/api/v1/runtime/workers`
- Status: `Implemented`
- Query:
  - `limit` optional response window, default `200`, maximum `500`

Notes:

- `status` is the control-plane worker model status
- `transportReachability` and `transportOnline` are transport-owned reachability
  facts read through the SDK/transport presence view
- joins worker state with current connection snapshots
- `eventBindings` remains the richer capability read model
- each row includes `fieldSources`, a field-to-owner label map. Expected
  owners are `declaration`, `runtime`, `transport`,
  `declarationOrTransport`, and review materialization evidence.
- this is an operator/console diagnostic response, not public SDK worker
  browsing. The response window bounds payload size; deeper owner-side paging is
  still a diagnostics-interface concern.

### 5.7 Worker Capability And State Reports

- Paths:
  - `POST /api/v1/runtime/workers/{workerId}/capability-reports`
  - `POST /api/v1/runtime/workers/{workerId}/state-reports`
- Status: `Removed`

Behavior:

- worker capability and state self-reporting belongs to the external worker
  data-plane:
  - `POST /worker-api/v1/workers/{workerId}:report-capability`
  - `POST /worker-api/v1/workers/{workerId}:report-state`
- runtime worker routes are diagnostics and operator command surfaces, not
  worker self-report ingress.

### 5.8 Worker State Projection Reads

- Methods:
  - `GET /api/v1/runtime/workers/{workerId}/state`
  - `GET /api/v1/runtime/workers/states`
- Status: `Implemented`

Behavior:

- reads bounded worker state projections through SDK `WorkerControlOperations`
- list response uses `{items,total,limit}` in the shared `ApiResponse` envelope
- `GET /api/v1/runtime/workers/states` accepts optional `limit`, default `200`,
  maximum `500`
- this is a read model and not scheduling truth

### 5.9 Worker Command Control And Reads

- Methods:
  - `POST /api/v1/runtime/workers/{workerId}/commands`
  - `GET /api/v1/runtime/workers/{workerId}/commands`
  - `GET /api/v1/runtime/workers/commands/{commandId}`
- Status: `Implemented`

Behavior:

- submits operator worker commands through SDK `WorkerControlOperations`
- command acknowledgements belong to the external worker data-plane:
  `POST /worker-api/v1/workers/{workerId}/commands/{commandId}:ack`
- command status does not enter task result runtime
- list response uses `{items,total,limit}` in the shared `ApiResponse` envelope
- `GET /api/v1/runtime/workers/{workerId}/commands` accepts optional `limit`,
  default `200`, maximum `500`
- path `workerId` is the target identity for command submit/list

Removed duplicate path:

- `POST /api/v1/runtime/workers/{workerId}/commands/{commandId}/ack`

### 5.10 Worker Context Runtime View

- Path: `/api/v1/runtime/worker-contexts`
- Status: `Removed`

Notes:

- `WorkerContext` is no longer a server/runtime CRUD or diagnostic resource
- worker mainline visibility belongs to `/api/v1/runtime/workers` plus
  transport/session diagnostics
- scheduling proof should use worker attributes, event bindings, transport
  presence, runtime load/resource traces, and canonical assignment trace rows

### 5.11 List Rules

- Method: `GET`
- Path: `/api/v1/admin/rules`
- Status: `Implemented`

### 5.12 Rule Catalog

- Method: `GET`
- Path: `/api/v1/admin/rules/meta`
- Status: `Implemented`

## 6. External Worker API

Base path: `/worker-api/v1`

External polling workers remain the stable repo-external integration path for
non-Java runtimes. Realtime adapter validation may still reuse portions of the
same registration model, but polling remains the primary public data-plane
contract.

### 6.1 Declare Worker Group

- Method: `POST`
- Path: `/worker-api/v1/worker-groups`
- Status: `Implemented`

Request notes:

- `groupId` is required
- `eventBindings` is required and becomes WorkerGroup capability truth
- `defaultAttributes` and `defaultMaxConcurrentWork` are optional group
  defaults
- caller must authenticate with a worker credential that includes `worker:poll`
  and is allowed for every declared event/project binding

### 6.2 Register Worker

- Method: `POST`
- Path: `/worker-api/v1/workers`
- Status: `Implemented`

Request notes:

- `workerId` is required
- `workerGroupId` is required
- `eventBindings` is optional compatibility input; WorkerGroup declaration is
  the capability owner
- `transportHint` defaults to `polling`
- `adapterId` is optional for polling and required for realtime
- caller must authenticate with a worker credential that includes `worker:poll`
  and binds the same `workerId`

### 6.3 Register Worker Context

- Path: `/worker-api/v1/workers/{workerId}/contexts`
- Status: `Removed`

Notes:

- external workers declare group capability through
  `/worker-api/v1/worker-groups`
- worker registration binds execution identity to a `workerGroupId` plus worker
  attributes and transport presence
- account/device inventory belongs to worker-management/system-event ownership,
  not to engine/server WorkerContext CRUD

### 6.4 Worker Online

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:online`
- Status: `Implemented`

### 6.5 Worker Heartbeat

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:heartbeat`
- Status: `Implemented`

### 6.6 Worker Offline

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:offline`
- Status: `Implemented`

### 6.7 Poll Tasks

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:poll`
- Status: `Implemented`

Request body:

- optional `maxMessages`, default `1`
- optional `timeoutMs`, range `0..30000`

Response notes:

- returns `TaskDispatchItem[]` in `data.items`
- `eventCode` is the worker handler identity
- `input` is the per-item logical payload
- `sharedConfig` is the task-level shared payload

### 6.8 Submit Task Result

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:submit-result`
- Status: `Implemented`

Request notes:

- request maps onto `TaskResultReport`
- `taskId` and `messageId` are required
- `output` is the canonical logical callback payload

### 6.9 Report Worker Capability

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:report-capability`
- Status: `Implemented`

Request notes:

- caller must authenticate with a worker credential that includes `worker:poll`
  and binds the same `workerId`
- `capabilityVersion` is optional; when omitted the server synthesizes one from
  current wall-clock time
- `availableEventCodes` must stay within the worker credential event scope
- the report is a bounded capability snapshot, not an incremental patch

### 6.10 Report Worker State

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:report-state`
- Status: `Implemented`

Request notes:

- caller must authenticate with a worker credential that includes `worker:poll`
  and binds the same `workerId`
- `stateVersion` is optional; when omitted the server synthesizes one from
  current wall-clock time
- external worker public contract currently accepts only:
  `AVAILABLE`, `DEGRADED`, `DRAINING`, `OFFLINE`
- the report is a bounded state snapshot, not an incremental patch
- worker state projection remains separate from transport presence truth
- current scheduling integration recognizes `DRAINING` as a dispatch gate:
  future assignments stop, while already in-flight work continues normally
- dispatch re-enable stays explicit in current mainline: failed or expired
  `DRAIN` command outcomes do not reopen dispatch; a later
  `report-state(AVAILABLE)` is required

### 6.11 Acknowledge Worker Command

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}/commands/{commandId}:ack`
- Status: `Implemented`

Request notes:

- caller must authenticate with a worker credential that includes `worker:poll`
  and binds the same `workerId`
- the command referenced by `commandId` must belong to the same `workerId`
- request maps onto the owner-backed worker command acknowledgement surface
- in current mainline, acknowledging a `DRAIN` command to an accepted delivery or
  execution state disables future dispatches to that worker without interrupting
  already in-flight work
- later `FAILED` or `EXPIRED` command outcomes do not re-enable dispatch on
  their own; recovery remains an explicit worker state report via
  `report-state(AVAILABLE)`

## 7. Internal Debug API

Base path: `/internal/v1/debug`

These routes are not part of the formal public task API.

### 7.1 Sync Task Invocation

- Method: `POST`
- Path: `/internal/v1/debug/task-invocations:sync`
- Status: `Implemented`

Purpose:

- debug/test-only single-item sync invocation path
- not a replacement for public task create or public item ingest

Contract rules:

- operator authentication is required; SDK submitter credentials are rejected
  for this route
- request uses an internal debug-only invocation DTO on this route
- exactly one item is required
- `mode`, when provided, must be `SINGLE_RUN`
- uses the same ingest guardrails as public append
- creates shell, appends the one item, seals, approves, and waits for a
  logically-final result
- sync waiting is message-scoped (`taskId + messageId`), not encoded through
  task-level `sharedConfig`

## 8. Console Surface

### 8.1 Backend-Served Control Console

- Method: `GET`
- Paths:
  - `/`
  - `/tasks`
  - `/resources/workers`
  - `/resources/rules`
  - `/resources/configs`
  - `/runtime/diagnostics`
  - `/system/users`
  - `/system/roles`
  - `/system/audit`
- Status: `Console`

Behavior:

- returns the SPA shell from the built `frontend/dist`
- browser-side routing handles page view after shell load
- WorkerContext console routes are removed; worker resource diagnostics live
  under `/resources/workers` and runtime diagnostic views

## 9. Health and Docs

- `GET /actuator/health` - `Implemented`
- `GET /doc.html` - `Demo`
- `GET /v3/api-docs` - `Implemented`

Doc handoff:

- `/doc.html` is the Knife4j browser UI for exploring the active API.
- `/v3/api-docs` exports the OpenAPI JSON document for generated handoff docs
  and client review.
- Task API request and response objects carry OpenAPI schema annotations in
  the server code; Markdown remains the narrative contract, while Knife4j is
  the exportable field-level contract.

## 10. Response Shape Notes

The active JSON API surface uses one response family:

- success: `{"code":0,"msg":"ok","data":...}`
- error: `{"code":<http-ish code>,"msg":"<reason>","data":null}`

Implications:

- consumers should read payloads from `data`
- task, catalog, runtime, and worker APIs all follow the same
  `ApiResponse<T>` envelope
