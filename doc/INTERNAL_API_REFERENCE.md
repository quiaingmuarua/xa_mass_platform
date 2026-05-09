# XA Mass Platform Internal API Reference

Last updated: 2026-05-08

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
[VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md).

## 1. Scope Notes

- This file is an HTTP/API dictionary. For platform and boundary truth, use
  [AGENT_BASELINE.md](./AGENT_BASELINE.md).
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
- framework-owned task-create ownership metadata is persisted in the reserved
  internal envelope `Task.sharedConfig._massSecurity`; HTTP read models expose
  supported ownership state through `data.security`, not the raw reserved
  envelope.
- task detail is now shell-oriented and does not implicitly return item payload
  snapshots. Public v1 does not expose item, attempt, or
  compatibility/projection-audit read routes.
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
- SDK submitter credentials use API-key/Bearer auth and do not participate in
  operator session login.

### 2.2 SDK Submitter Introspection

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

## 3. Metadata API

Base path: `/api/v1/meta`

### 3.1 List Projects

- Method: `GET`
- Path: `/api/v1/meta/projects`
- Status: `Implemented`

Returns the registered `ProjectMetadata` list.

### 3.2 Get Project

- Method: `GET`
- Path: `/api/v1/meta/projects/{projectCode}`
- Status: `Implemented`

Returns HTTP `404` when `projectCode` does not exist.

### 3.3 List Project Events

- Method: `GET`
- Path: `/api/v1/meta/projects/{projectCode}/events`
- Status: `Implemented`

Returns the full `EventDefinition` list for the project's declared
`eventCodes`.

### 3.4 List Events

- Method: `GET`
- Path: `/api/v1/meta/events`
- Status: `Implemented`

Notes:

- returns the registered `EventDefinition` list
- `taskModes=[]` means the event is direct runtime discovery/dispatch, not a
  task-backed event

### 3.5 Get Event

- Method: `GET`
- Path: `/api/v1/meta/events/{eventCode}`
- Status: `Implemented`

Returns HTTP `404` when `eventCode` does not exist.

### 3.6 List Event Capabilities

- Method: `GET`
- Path: `/api/v1/meta/event-capabilities`
- Status: `Implemented`

Notes:

- returns one row per registered global event
- `invocationModel=TASK_BACKED` means the event enters through the task shell
  create plus item ingest flow
- `invocationModel=DIRECT_RUNTIME` means the event is handled directly by the
  SDK runtime definition
- `ready=true` means either a direct runtime handler exists or at least one
  online worker declares the event

### 3.7 List Worker Capability Snapshots

- Method: `GET`
- Path: `/api/v1/meta/worker-capabilities`
- Status: `Implemented`

Notes:

- joins SDK worker capability declarations with current transport/session
  snapshots by `workerId`
- `supportedEventCodes` remains the flat runtime capability list used by
  matching
- `eventBindings` is the richer capability view derived from event metadata
- `adapterId` is the concrete runtime adapter identity
- `transportHint` is the coarse transport family
- `connections` and `hasActiveEndpoint` are reachability facts from the
  transport/session layer, not capability truth

## 4. Task API

Base path: `/api/v1/tasks`

Task API is now explicitly split into:

- shell lifecycle
- item ingest
- command routes

Public create no longer accepts `inputs` and no longer mixes create with
dispatch.

### 4.1 List Tasks

- Method: `GET`
- Path: `/api/v1/tasks`
- Status: `Implemented`

Query params:

- `keyword` optional
- `status` optional
- `offset` optional, default `0`
- `limit` optional, default `500`

Notes:

- operator callers require normal task-view authorization
- SDK credential callers may also use this route
- current SDK list behavior is ownership-scoped

### 4.2 Create Task Shell

- Method: `POST`
- Path: `/api/v1/tasks`
- Status: `Implemented`

Supported request fields:

- `userId`
- `project`
- `taskName`
- `eventCode`
- `mode`
- `payloadType`
- `sharedConfig`
- `batchSize`
- `maxRuntimeSeconds`
- `sourceType`
- `workloadClass`
- `sourceRef`

Not supported on this route:

- `inputs`
- `defaultMsgMaxRetryCount`
- retired fields such as `targetJsonList`, `targetType`, and `extraParams`

Contract rules:

- `project`, `userId`, and `taskName` are required after auth scoping is
  resolved
- unknown JSON fields are rejected
- when `eventCode` is present, the `project` and `eventCode` must exist in the
  metadata catalog and the project must declare support for that event
- `mode` defaults to `SINGLE_RUN`
- `payloadType` defaults to `JSON`
- omitted `sourceType` defaults to `STREAM`
- public create creates only the task shell and opens normal intake for later
  append/seal flow

Example request:

```json
{
  "userId": "agent",
  "project": "demoApp",
  "taskName": "sdk-crawler",
  "eventCode": "demo.dispatch",
  "mode": "SINGLE_RUN",
  "payloadType": "JSON",
  "sharedConfig": {
    "site": "example"
  },
  "batchSize": 1,
  "maxRuntimeSeconds": 60,
  "workloadClass": "INTERACTIVE"
}
```

Example response:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "taskId": "task-uuid",
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
- SDK credential callers may use this route under the same ownership-based
  task-view gate

### 4.4 Update Task Shell Metadata

- Method: `PATCH`
- Path: `/api/v1/tasks/{taskId}`
- Status: `Implemented`

Supported request fields:

- `userId`
- `project`
- `taskName`
- `sharedConfig`
- `batchSize`

Contract rules:

- metadata-only update path
- only `NEW` and `BLOCKED` tasks may be updated
- omitted fields keep the currently persisted values
- `inputs` and unknown fields are rejected with HTTP `400`

### 4.5 Delete Task

- Method: `DELETE`
- Path: `/api/v1/tasks/{taskId}`
- Status: `Implemented`

Contract rules:

- only `NEW` and `TERMINAL` tasks can be deleted

### 4.6 Append Task Items

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}/items`
- Status: `Implemented`

Request shape:

```json
{
  "items": [
    {
      "target": "target-001"
    },
    {
      "target": "target-002"
    }
  ],
  "defaultMsgMaxRetryCount": 3
}
```

Contract rules:

- `items` must be a non-empty list
- task must exist
- task intake must still be open
- request is subject to ingress safety limits
- payload conversion follows the task shell's declared payload semantics

Current server guardrails:

- max item count: `500`
- max single item serialized size: `64 KiB`
- max total serialized batch size: `1 MiB`

### 4.7 Seal Task Intake

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}:seal`
- Status: `Implemented`

Behavior:

- closes the task intake window
- once sealed, later append is rejected

### 4.8 Task Detail Boundaries

- `GET /api/v1/tasks/{taskId}` returns task shell, aggregate state, and security view
- public task API does not expose task-item snapshot, per-item detail, attempt
  audit, or projection-audit routes
- residue or projection diagnostics are not part of the public v1 task surface

### 4.9 Approve Task

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}:approve`
- Status: `Implemented`

Behavior:

- advances shell review state into runnable state when allowed

### 4.13 Reject Task

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}:reject`
- Status: `Implemented`

Behavior:

- review rejection path

### 4.14 Pause Task

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}:pause`
- Status: `Implemented`

### 4.15 Resume Task

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}:resume`
- Status: `Implemented`

### 4.16 Block Task

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}:block`
- Status: `Implemented`

Behavior:

- explicit runtime block endpoint
- unlike review reject, this is not limited to `NEW`

### 4.17 Terminate Task

- Method: `POST`
- Path: `/api/v1/tasks/{taskId}:terminate`
- Status: `Implemented`

Behavior:

- closes any non-terminal task to `TERMINAL`

### 4.18 Removed Task Route Shapes

The following historical task route shapes are no longer part of the active
public API:

- create-with-inputs on public create
- non-`POST` task command routes
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

Current meaning:

- returns transport endpoint snapshots, not a kernel worker truth source
- session diagnostics are operator-only read surfaces

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

Notes:

- joins worker state with current connection snapshots
- `eventBindings` remains the richer capability read model

### 5.7 List Worker Contexts

- Method: `GET`
- Path: `/api/v1/runtime/worker-contexts`
- Status: `Implemented`

### 5.8 List Rules

- Method: `GET`
- Path: `/api/v1/runtime/rules`
- Status: `Implemented`

### 5.9 Rule Metadata

- Method: `GET`
- Path: `/api/v1/runtime/rules/meta`
- Status: `Implemented`

## 6. External Worker API

Base path: `/worker-api/v1`

External polling workers remain the stable repo-external integration path for
non-Java runtimes. Realtime adapter validation may still reuse portions of the
same registration model, but polling remains the primary public data-plane
contract.

### 6.1 Register Worker

- Method: `POST`
- Path: `/worker-api/v1/workers`
- Status: `Implemented`

Request notes:

- `workerId` is required
- `eventBindings` is required and is the canonical capability declaration
- `transportHint` defaults to `polling`
- `adapterId` is optional for polling and required for realtime
- caller must authenticate with an SDK credential that includes `worker:poll`
  and binds the same `workerId`

### 6.2 Register Worker Context

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}/contexts`
- Status: `Implemented`

Request notes:

- `workerContextId` is required
- stateless workers may skip this API entirely

### 6.3 Worker Online

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:online`
- Status: `Implemented`

### 6.4 Worker Heartbeat

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:heartbeat`
- Status: `Implemented`

### 6.5 Worker Offline

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:offline`
- Status: `Implemented`

### 6.6 Poll Tasks

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

### 6.7 Submit Task Result

- Method: `POST`
- Path: `/worker-api/v1/workers/{workerId}:submit-result`
- Status: `Implemented`

Request notes:

- request maps onto `TaskResultReport`
- `taskId` and `messageId` are required
- `output` is the canonical logical callback payload

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

- request uses the legacy create-shaped debug DTO only on this internal route
- exactly one input item is required
- `mode`, when provided, must be `SINGLE_RUN`
- uses the same ingest guardrails as public append
- creates shell, appends the one item, seals, approves, and waits for a
  logically-final result

## 8. Console Surface

### 8.1 Backend-Served Control Console

- Method: `GET`
- Paths:
  - `/`
  - `/tasks`
  - `/resources/workers`
  - `/resources/worker-contexts`
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

## 9. Health and Docs

- `GET /actuator/health` - `Implemented`
- `GET /doc.html` - `Demo`

## 10. Response Shape Notes

The active JSON API surface uses one response family:

- success: `{"code":0,"msg":"ok","data":...}`
- error: `{"code":<http-ish code>,"msg":"<reason>","data":null}`

Implications:

- consumers should read payloads from `data`
- task, metadata, runtime, and worker APIs all follow the same
  `ApiResponse<T>` envelope
