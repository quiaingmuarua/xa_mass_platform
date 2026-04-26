# XA Mass Platform Internal API Reference

Last updated: 2026-04-24

This document tracks the current active HTTP/API surface in the mainline runtime.

Status labels used below:

- `Implemented`: endpoint exists and is wired into the current runtime
- `Partial`: endpoint exists but is mainly diagnostic, placeholder, or thin passthrough
- `Console`: backend-served SPA shell or route handled by the control console

Scope:

- HTTP endpoint inventory
- current request contract
- response shape notes
- implementation status

Out of scope:

- startup commands
- end-to-end verification logs
- architecture history

For verified runtime behavior and recommended startup, use [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md).

## 1. Scope Notes

- This file is an HTTP/API dictionary. For platform, module, and boundary truth, use [AGENT_BASELINE.md](./AGENT_BASELINE.md).
- The current HTTP/API surface validates the kernel; it does not define the kernel.
- `/sdk/meta/**` is read-only metadata discovery, not a second task domain.
- SDK credential callers use the same `POST /status/api/tasks` route as console/operator callers; `/sdk/submitters/me` is credential introspection only.
- `EventDefinition.code` is the global event/capability identity; `project` remains scope metadata for task ownership and event eligibility.
- `Task.project` and `Task.user` are first-class core bindings even though API edge shapes still use `project` and `userId`.
- Stable payload boundaries are `Task.sharedConfig` and `TaskMsg.input/output`.
- `TaskMsg.output` is the canonical logical success payload for one work item; legacy `result` is only a summary/string compatibility field.
- `TaskMsgAttempt` keeps the concrete attempt-level callback snapshot, including per-attempt output/error details.
- `target` is only a conventional key inside `TaskMsg.input`.
- `Task.intakeStatus` is the active append-window lifecycle truth; `openEnded` is only the create/request projection.
- `TaskMsg.latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId` are latest-attempt projections of `TaskMsgAttempt`.
- Worker/WebSocket-adapter callbacks must resolve a unique active `TaskMsgAttempt`; the runtime no longer synthesizes legacy attempts for result write-back.
- The current WebSocket-adapter surface uses canonical root-level task-dispatch/task-result frames, while worker identity is established at handshake time; these adapter semantics are not API capability truth.
- `/status/api/workers` and `/sdk/meta/worker-capabilities` are the current joined worker capability read models: SDK registration remains capability truth, session/endpoint facts come from the transport layer, and the response joins them by `workerId`.

## 1.1 Event Control Plane Notes

- Stable event invocation contract: `EventRequest`, `EventResponse`, and `EventPrincipal`.
- Control-plane authorization is event-centric and currently intersects client and user allow-list scope.
- `EventDefinition.code` is the event/capability identity used by dispatch, catalog reads, and permission checks; `project` remains scope metadata only.
- Task-backed business events enter through the SDK event path and normalize to task creation; direct runtime events are handled inside the embedded SDK runtime rather than through adapter protocol frames.
- Built-in runtime control events are also registered into the SDK metadata catalog so metadata and dispatch stay aligned.
- Manual worker debug is task-backed. Use `POST /status/api/tasks` with `eventCode` plus `sharedConfig.targetWorkerId` when the task must target one worker.

## 1.2 External Worker Polling Notes

- Official third-party worker path is the polling HTTP surface under `/worker-api/*`.
- External worker capability must be declared through `eventBindings`; external worker registration does not define a second capability identity model.
- External worker transport is polling-only. Do not treat adapter-internal frames as the public protocol for non-Java workers.
- `/worker-api/*` is authenticated with SDK credentials, not operator user-mode headers.
- required worker credential rules:
  - permission must include `worker:poll`
  - credential attributes must bind `workerId`
  - registration requests are still constrained by credential `eventScopes` and `projectScopes`
- External workers receive `TaskDispatchItem` payloads, execute locally by `eventCode`, and submit `TaskResultReport`.
- External workers do not receive direct business/control messages outside normal task lifecycle dispatch.
- For a runnable local example, use [EXTERNAL_WORKER_QUICKSTART.md](./EXTERNAL_WORKER_QUICKSTART.md).

## 2. SDK Metadata API

### 2.1 List SDK Projects

- Method: `GET`
- Path: `/sdk/meta/projects`
- Status: `Implemented`

Response notes:

- returns the registered `ProjectMetadata` list
- each project includes `code`, `name`, `description`, `enabled`, and `eventCodes`
- `eventCodes` reference globally unique catalog event codes; they are not per-project-local names

### 2.2 Get SDK Project

- Method: `GET`
- Path: `/sdk/meta/projects/{projectCode}`
- Status: `Implemented`

Behavior:

- returns the registered `ProjectMetadata`
- returns HTTP 404 when `projectCode` does not exist in the catalog

### 2.3 List Events For One SDK Project

- Method: `GET`
- Path: `/sdk/meta/projects/{projectCode}/events`
- Status: `Implemented`

Behavior:

- returns the full `EventDefinition` list for the project's declared `eventCodes`
- returns HTTP 404 when `projectCode` does not exist in the catalog

### 2.4 List SDK Events

- Method: `GET`
- Path: `/sdk/meta/events`
- Status: `Implemented`

Response notes:

- returns the registered `EventDefinition` list
- each event includes `code`, `name`, `description`, `payloadTypes`, `taskModes`, and `enabled`
- `taskModes=[]` means the event is a direct runtime definition, not a task-create event

### 2.5 Get SDK Event

- Method: `GET`
- Path: `/sdk/meta/events/{eventCode}`
- Status: `Implemented`

Behavior:

- returns the registered `EventDefinition`
- `taskModes=[]` means the event is direct runtime discovery/dispatch only
- returns HTTP 404 when `eventCode` does not exist in the catalog

### 2.6 List SDK Event Capabilities

- Method: `GET`
- Path: `/sdk/meta/event-capabilities`
- Status: `Implemented`

Response notes:

- returns one row per registered global event
- `invocationModel=TASK_BACKED` means the event creates/dispatches task work items through `POST /status/api/tasks`
- `invocationModel=DIRECT_RUNTIME` means the event is handled directly by the SDK runtime definition
- `onlineWorkerIds` is derived from live workers declaring `supportedEventCodes`; `supportedProjects` is not treated as capability truth
- capability identity is the global `eventCode`; project membership only describes where that event may be invoked
- `ready=true` means either a direct runtime handler exists or at least one online worker currently declares the event

### 2.7 List Worker Capability Snapshots

- Method: `GET`
- Path: `/sdk/meta/worker-capabilities`
- Status: `Implemented`

Response notes:

- returns one row per registered worker
- joins SDK worker capability declarations with current transport session snapshots by `workerId`
- `supportedEventCodes` remains the flat runtime capability list used by matching
- `eventBindings` is the richer capability view derived from event metadata scope for each declared `supportedEventCode`
- `supportedProjects` remains a separate coarse worker scope hint and is not used as capability identity
- `adapterId` is the concrete runtime adapter identity (`polling`, `websocket`, `socket`, ...)
- `transportHint` is the coarse transport family (`polling`, `realtime`, ...)
- capability views expose both fields; runtime routing keys off `adapterId`, not `transportHint`
- `connections` and `hasActiveEndpoint` come from the transport/session layer and are reachability facts, not capability truth

## 2.6 SDK Submitter Introspection API

### 2.6.1 Get Current SDK Submitter

- Method: `GET`
- Path: `/sdk/submitters/me`
- Status: `Implemented`

Headers:

- `X-Mass-Api-Key: <credential>` or `Authorization: Bearer <credential>`

Contract notes:

- resolves the current credential through `AuthProvider.authenticate(...)`
- returns the authenticated submitter view with `principalId`, `userId`, `projectScope`, `permissions`, `projectScopes`, `eventScopes`, and `attributes`
- submitter credentials are API-key style credentials; multiple credentials may resolve to the same `userId` while keeping independent permissions and scopes
- does not expose raw credential material
- returns HTTP 401 when the credential is missing or invalid
- this endpoint is not a control-console login/session API and does not participate in operator RBAC

## 3. Task API

Base path: `/status/api/tasks`

### 3.1 Create Task

- Method: `POST`
- Path: `/status/api/tasks`
- Status: `Implemented`

Supported request fields:

- `userId`
- `project`
- `taskName`
- `eventCode`
- `mode`
- `payloadType`
- `sharedConfig`
- `inputs`
- `batchSize`
- `defaultMsgMaxRetryCount`
- `openEnded`
- `maxRuntimeSeconds`

Contract rules:

- `project` and `userId` are required
- `inputs` must be a non-empty list
- unsupported `project` values are rejected
- unknown JSON fields are rejected
- retired fields such as `targetJsonList`, `targetType`, and `extraParams` are not supported
- when `eventCode` is present, create uses the SDK mode/payload-aware path
- when `eventCode` is present, `project` and `eventCode` must exist in the SDK metadata catalog and the project must declare support for that event
- when `eventCode` is present, runtime worker capability is matched by worker-declared `supportedEventCodes`
- `supportedProjects` remains only a coarse worker grouping/filter hint; it is not the runtime event-capability source of truth
- SDK credential callers use this same route with `X-Mass-Api-Key` or `Authorization: Bearer ...`
- when an SDK credential is present, `AuthProvider.authenticate(...)` resolves a `TaskSubmitterContext`
- SDK credentials must include `task:create` to create tasks
- when an SDK credential has `projectScopes`, the request `project` must be allowed by that scope; `projectScope` remains a single-project compatibility projection
- when an SDK credential has `eventScopes`, the request `eventCode` must be allowed by that scope
- when an SDK submitter has `projectScope`, the request `project` may be omitted or must match that scope; mismatches return HTTP 403
- when an SDK submitter has `userId`, the request `userId` may be omitted or must match that scope; mismatches return HTTP 403
- when an SDK submitter has no scoped `userId`, user resolution order is request `userId`, then submitter `principalId`
- invalid or missing SDK credentials return HTTP 401
- `mode` defaults to `SINGLE_RUN`, or `STREAMING` when `openEnded=true` and `mode` is omitted
- `payloadType` defaults to `JSON`
- `defaultMsgMaxRetryCount` defaults to `3`
- `openEnded` defaults to `false`
- `maxRuntimeSeconds` defaults to `0` and disables runtime-limit termination
- the public task API has no dedicated routing-code field; keep task-level payload or hints inside `sharedConfig` only when a concrete runtime contract requires them

Example request:

```json
{
  "userId": "agent",
  "project": "demoApp",
  "taskName": "smoke-lifecycle",
  "sharedConfig": {
    "textContent": "hello"
  },
  "inputs": [
    {
      "target": "target-001"
    },
    {
      "target": "target-002"
    }
  ],
  "batchSize": 1,
  "defaultMsgMaxRetryCount": 3,
  "openEnded": false,
  "maxRuntimeSeconds": 0
}
```

SDK-style example:

```json
{
  "userId": "agent",
  "project": "demoApp",
  "taskName": "sdk-crawler",
  "eventCode": "demo.dispatch",
  "mode": "STREAMING",
  "payloadType": "JSON",
  "sharedConfig": {
    "site": "example"
  },
  "inputs": [
    {
      "url": "https://example.test"
    }
  ],
  "batchSize": 1,
  "defaultMsgMaxRetryCount": 2,
  "maxRuntimeSeconds": 60
}
```

Example response:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "taskId": "task-uuid",
    "message": "Task created"
  }
}
```

### 3.2 Get Task

- Method: `GET`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Response notes:

- returns `task`
- returns `items` derived from persisted `TaskMsg.input`
- returns `stateValidation`
- `task.project` is serialized as the canonical project code
- `task.user` is serialized as the current business-user binding object
- returns HTTP 404 with `ApiResponse.error(404, ...)` when the task does not exist

Example response shape:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "task": {
      "tid": "task-uuid",
      "taskName": "smoke-lifecycle",
      "project": "demoApp",
      "user": {
        "userId": "agent"
      },
      "status": "NEW",
      "sharedConfig": {
        "textContent": "hello"
      },
      "intakeStatus": "SEALED",
      "openEnded": false,
      "batchSize": 1,
      "maxRuntimeSeconds": 0
    },
    "items": [
      {
        "target": "target-001"
      },
      {
        "target": "target-002"
      }
    ],
    "stateValidation": {
      "valid": true,
      "needsResolution": false,
      "violations": []
    }
  }
}
```

### 3.3 Update Task Metadata

- Method: `PUT`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Supported request fields:

- `userId`
- `project`
- `taskName`
- `sharedConfig`
- `batchSize`

Contract rules:

- metadata-only update path
- only `NEW` and `BLOCKED` tasks may be updated; returns HTTP 400 otherwise
- omitted fields keep the currently persisted metadata/binding values
- `inputs` and unknown fields are rejected with HTTP 400

### 3.4 Delete Task

- Method: `DELETE`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Contract rules:

- only `NEW` and `TERMINAL` tasks can be deleted
- returns `ApiResponse.error(...)` when the task is in a non-deletable state

### 3.5 Audit Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/audit`
- Status: `Implemented`

Query params:

- `approved`: required, `true` or `false`
- `comment`: optional

Behavior:

- `approved=true`: `NEW` or `BLOCKED` -> `READY`
- `approved=false`: `NEW` -> `BLOCKED`
- returns `ApiResponse.error(...)` if the transition is not allowed from the current state

Example success response:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "message": "Task approved",
    "newStatus": "READY"
  }
}
```

### 3.6 Pause Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/pause`
- Status: `Implemented`

Behavior:

- `READY` or `RUNNING` -> `PAUSED`

### 3.7 Resume Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/resume`
- Status: `Implemented`

Behavior:

- normally `PAUSED` -> `READY`
- if the task already completed while paused, the response reports closure to `TERMINAL`

Example success response:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "message": "Task resumed",
    "newStatus": "READY",
    "terminalReason": ""
  }
}
```

Alternate success response when it already completed:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "message": "Task already completed while paused and was closed to TERMINAL",
    "newStatus": "TERMINAL",
    "terminalReason": "ALL_MESSAGES_SUCCEEDED"
  }
}
```

### 3.8 Terminate Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/terminate`
- Status: `Implemented`

Behavior:

- any non-`TERMINAL` task may be closed to `TERMINAL`

### 3.9 Status Routing Helper

- Method: `PUT`
- Path: `/status/api/tasks/{taskId}/status`
- Status: `Implemented`

Query param:

- `status`: one of `READY`, `BLOCKED`, `PAUSED`, `TERMINAL`

Behavior:

- `READY` routes to approve or resume depending on current state
- `BLOCKED` routes by current state:
  - `NEW -> BLOCKED` uses review rejection
  - `READY/RUNNING -> BLOCKED` uses runtime blocking
- `PAUSED` routes to pause
- `TERMINAL` routes to cancel
- no direct route exists for `RUNNING` through this helper

### 3.10 Runtime Block Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/block`
- Status: `Implemented`

Behavior:

- explicit runtime block endpoint
- allowed from `READY` and `RUNNING`
- moves the task to `BLOCKED`
- unlike audit reject, this is not limited to `NEW`

### 3.11 List Task Messages

- Method: `GET`
- Path: `/status/api/tasks/{taskId}/messages`
- Status: `Implemented`

Query params:

- `page`: default `1`
- `size`: default `20`, hard-capped at `500`

Response shape:

- primary per-item payload truth is `messages[*].input` and `messages[*].output`
- `target` is only a conventional key inside `messages[*].input`
- raw top-level target projections are not part of the message read model

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "total": 2,
    "page": 1,
    "size": 20,
    "messages": [
      {
        "messageId": "msg-1",
        "taskId": "task-uuid",
        "status": "SUCCESS",
        "latestAttemptWorkerId": "worker-a",
        "latestAttemptWorkerContextId": "worker-context-a",
        "latestAttemptBatchId": "batch-1",
        "retryCount": 0,
        "maxRetryCount": 3,
        "finalReason": "BUSINESS_SUCCESS",
        "result": "ok",
        "errorMessage": null,
        "errorCode": null,
        "input": {
          "target": "target-001"
        },
        "output": {}
      }
    ]
  }
}
```

### 3.12 Append Items To Open-Ended Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/items`
- Status: `Implemented`

Request shape:

```json
{
  "inputs": [
    {
      "target": "target-003"
    },
    {
      "target": "target-004",
      "priority": "high"
    }
  ]
}
```

Contract rules:

- `inputs` must be a non-empty list
- task must exist
- task must have `intakeStatus=OPEN`
- task must still be active

Example response:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "message": "Items appended",
    "added": 2
  }
}
```

### 3.13 Seal Open-Ended Task

- Method: `PUT`
- Path: `/status/api/tasks/{taskId}/seal`
- Status: `Implemented`

Behavior:

- closes the append window for an open-ended task
- once sealed, normal terminal convergence resumes when all persisted messages are final

Example response:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "message": "Task sealed",
    "status": "RUNNING"
  }
}
```

## 4. Queue APIs

Base path: `/api/queue`

### 4.1 Queue Status

- Method: `GET`
- Path: `/api/queue/status`
- Status: `Implemented`

Response shape:

```json
{
  "success": true,
  "data": {
    "inputQueue": 0,
    "outputQueue": 0
  }
}
```

### 4.2 Queue Detail

- Method: `GET`
- Path: `/api/queue/detail`
- Status: `Implemented`

Response shape:

```json
{
  "success": true,
  "data": {
    "inputQueueSize": 0,
    "outputQueueSize": 0,
    "transporterAvailable": true,
    "deliveryQueue": {
      "available": true,
      "queuedItems": 0,
      "queueCount": 0,
      "waitingPollers": 0,
      "maxQueuedItems": 100000
    }
  }
}
```

Notes:

- queue metrics are compatibility/observability data only; they are not the
  transport runtime routing truth
- `transporterAvailable` may be `false`, with queue sizes reported as `-1`,
  when the embedded runtime is assembled through adapter-native paths without a
  shared message transporter
- `deliveryQueue` is the runtime delivery-store diagnostic surface. It reports
  shared dispatch backlog and polling waiters, not engine-owned task lifecycle
  or retry state.

### 4.3 Queue Metrics

- Method: `GET`
- Path: `/api/queue/metrics`
- Status: `Partial`

Current behavior:

- endpoint exists
- currently returns static zero values rather than real throughput metrics

## 5. Session APIs

Base path: `/api/session`

### 5.1 List Sessions

- Method: `GET`
- Path: `/api/session/list`
- Status: `Implemented`

Current meaning:

- this endpoint returns transport endpoint snapshots, not a kernel-level worker truth source
- current mainline data is backed by the WebSocket adapter, but the response shape is transport-neutral

Response shape:

```json
{
  "success": true,
  "data": [
    {
      "workerId": "dev123",
      "connections": [
        {
          "active": true,
          "endpointId": "abc123",
          "transport": "websocket"
        }
      ]
    }
  ]
}
```

### 5.2 Session Stats

- Method: `GET`
- Path: `/api/session/stats`
- Status: `Implemented`

Current meaning:

- `activeConnections` counts currently addressable transport endpoints
- `workerCount` counts distinct workers represented in the endpoint snapshot set
- current WebSocket adapter uses a single endpoint per worker; session inspection no longer exposes any role/lane field

Response shape:

```json
{
  "success": true,
  "data": {
    "activeConnections": 2,
    "workerCount": 1
  }
}
```

## 6. Config APIs

### 6.1 Global Project List

- Method: `GET`
- Path: `/api/config/projects`
- Status: `Implemented`

Behavior:

- returns the configured project code list from `GlobalConfig`

### 6.2 Backend-Served Control Console

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
- browser-side routing handles the page view after the shell loads
- `/status`, `/status/tasks`, `/status/workers`, `/status/rules`, and `/config` are redirect aliases only and are not the primary console entrypoints
- worker-context read models now include first-class `project` when the context is bound to a specific project/account domain

## 7. Message API

### 7.1 Send Message

- Method: `POST`
- Path: `/api/message/send`
- Status: `Partial`

Current behavior:

- thin passthrough into the current output transporter
- accepts an arbitrary JSON body and serializes it as raw output envelope payload
- primarily useful for diagnostics or manual transport probing, not as a stable platform SDK contract

Response shape:

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "success": true,
    "msg": "message queued"
  }
}
```

## 8. External Worker Transport API

External polling workers remain the mainline integration path for non-Java runtimes such as Node, Python, or Go, but the shared registration API also supports realtime adapters when the embedded runtime assembles them.

For a full local dev walkthrough, including demo credentials and a runnable Node worker, use [EXTERNAL_WORKER_QUICKSTART.md](./EXTERNAL_WORKER_QUICKSTART.md).

### 8.1 Register External Worker

- Method: `POST`
- Path: `/worker-api/workers/register`
- Status: `Implemented`

Request notes:

- `workerId` is required
- `eventBindings` is required and is the canonical capability declaration
- `transportHint` is optional; when omitted it defaults to `polling`
- `adapterId` is optional for polling and required for realtime
- `transportHint` remains a coarse family; runtime routing resolves the concrete adapter through `adapterId`
- when `transportHint=realtime`, `adapterId` must be explicit even if the runtime currently assembles only one realtime adapter
- caller must authenticate with an SDK credential that includes `worker:poll` and `attributes.workerId == workerId`

Example:

```json
{
  "workerId": "node-worker-1",
  "adapterId": "polling",
  "workerGroupId": "node-runtime",
  "attributes": {
    "lang": "node"
  },
  "eventBindings": [
    {
      "eventCode": "crawler.fetch-page",
      "projectCodes": ["crawlerApp"]
    }
  ]
}
```

### 8.2 Register External Worker Context

- Method: `POST`
- Path: `/worker-api/worker-contexts/register`
- Status: `Implemented`

Request notes:

- `workerContextId` and `workerId` are required
- `project`, `routingTags`, and `attributes` map directly onto `WorkerContextRegistration`
- stateless workers may skip this API entirely
- caller must authenticate with an SDK credential bound to the same `workerId`

### 8.3 Mark External Worker Online

- Method: `POST`
- Path: `/worker-api/workers/{workerId}/online`
- Status: `Implemented`

Behavior:

- maps to the same runtime online transition used by pull workers
- affects transport/session reachability only; it does not bypass engine scheduling
- caller must authenticate with an SDK credential bound to the path `workerId`

### 8.4 External Worker Heartbeat

- Method: `POST`
- Path: `/worker-api/workers/{workerId}/heartbeat`
- Status: `Implemented`

Behavior:

- refreshes worker liveness through the runtime system-event channel
- caller must authenticate with an SDK credential bound to the path `workerId`

### 8.5 Poll Tasks

- Method: `POST`
- Path: `/worker-api/workers/{workerId}/poll`
- Status: `Implemented`

Request body:

- optional `maxMessages`
- defaults to `1` when omitted
- rejects `maxMessages <= 0`

Response notes:

- `data.items` is a `TaskDispatchItem[]`
- `eventCode` is the worker handler identity
- `input` is the per-item logical payload
- worker transports must not leak SDK-internal wrapper shapes such as `{type,data}` into `input`
- `sharedConfig` is the task-level shared payload
- caller must authenticate with an SDK credential bound to the path `workerId`

### 8.6 Submit Task Result

- Method: `POST`
- Path: `/worker-api/workers/{workerId}/results`
- Status: `Implemented`

Request notes:

- request body maps directly onto `TaskResultReport`
- `taskId` and `messageId` are required
- `output` is the canonical success/failure payload written back to `TaskMsg.output`
- caller must authenticate with an SDK credential bound to the path `workerId`

### 8.7 Mark External Worker Offline

- Method: `POST`
- Path: `/worker-api/workers/{workerId}/offline`
- Status: `Implemented`

Behavior:

- marks the worker offline through the runtime system-event channel
- caller must authenticate with an SDK credential bound to the path `workerId`

## 9. Worker Debug Surface

Manual worker debug no longer has a dedicated `/status/workers/*` API.

Use the normal task create API:

- Method: `POST`
- Path: `/status/api/tasks`
- Status: `Implemented`

Request notes:

- set `project`, `taskName`, and `eventCode`
- send the debug payload through `inputs`
- fix the target worker with `sharedConfig.targetWorkerId`
- worker capability and permission checks still resolve by global `eventCode`

Behavior:

- debug actions flow through normal engine scheduling, assignment, dispatch, result ingest, and terminal convergence
- there is no separate worker message-history read model
- the worker detail UI may still present a debug-focused form, but it submits a task instead of a direct worker message

## 10. Health And Docs

- `GET /actuator/health` - `Implemented`
- `GET /doc.html` - `Demo`

## 11. Response Shape Notes

The active JSON API surface uses one response family:

- success: `{"code":0,"msg":"ok","data":...}`
- error: `{"code":<http-ish code>,"msg":"<reason>","data":null}`

Implication:

- consumers should read payloads from `data`
- task endpoints follow the same `ApiResponse<T>` envelope as the other JSON endpoints
