# XA Mass Platform Internal API Reference

Last updated: 2026-04-29

Status: current global HTTP/API reference.

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
- control-console mock auth still uses request headers, but those headers now resolve to built-in operator `PrincipalContext` definitions instead of a separate permission truth model.
- unified control-plane authorization now flows through `AuthorizationPolicy` from `xa-mass-sdk-api` / `xa-mass-sdk`; `xa-mass-server` is the HTTP host adapter that resolves principals and forwards authorization requests.
- `EventDefinition.code` is the global event/capability identity; `project` remains scope metadata for task ownership and event eligibility.
- `Task.project` and `Task.user` are first-class core bindings even though API edge shapes still use `project` and `userId`.
- Stable payload boundaries are `Task.sharedConfig` and `TaskMsg.input/output`.
- framework-owned task-create ownership metadata is currently stored in `Task.sharedConfig._massSecurity` with `createdByPrincipalId` and `createdByPrincipalType`.
- `Task.workloadClass` is an explicit task-level create field; current values are `INTERACTIVE` and `BULK`, and omission defaults to `BULK`.
- `TaskMsg.output` is the canonical logical success payload for one work item; `result` remains a summary/string read-model field.
- `TaskMsgAttempt` keeps the concrete attempt-level callback snapshot, including per-attempt output/error details.
- `target` is only a conventional key inside `TaskMsg.input`.
- `Task.intakeStatus` is the active append-window lifecycle truth; `openEnded` is only the create/request projection.
- `TaskMsg.latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId` are latest-attempt projections of `TaskMsgAttempt`.
- `/status/api/workers` and `/sdk/meta/worker-capabilities` are the current joined worker capability read models: SDK registration remains capability truth, session/endpoint facts come from the transport layer, and the response joins them by `workerId`.

## 1.1 Event Control Plane Notes

- Stable event invocation contract: `EventRequest`, `EventResponse`, and `PrincipalContext`.
- Control-plane authorization resolves one unified `PrincipalContext` and then applies direct permissions plus `projectScopes` / `eventScopes`.
- `AuthorizationPolicy` is the current shared authorization entrypoint for operator routes, SDK submitter task create, and external worker HTTP access.
- SDK task-read ownership checks use the shared `TaskOwnershipStamp` / `AuthorizationReasonCode` contract and are adapted by the server host layer rather than hidden inside controller-local string rules.
- `EventDefinition.code` is the event/capability identity used by dispatch, catalog reads, and permission checks; `project` remains scope metadata only.
- Task-backed business events enter through the SDK event path and normalize to task creation; direct runtime events are handled inside the embedded SDK runtime rather than through adapter protocol frames.
- Built-in runtime control events are also registered into the SDK metadata catalog so metadata and dispatch stay aligned.
- Manual worker debug is task-backed. Use `POST /status/api/tasks` with `eventCode` plus `sharedConfig.targetWorkerId` when the task must target one worker.

## 1.2 External Worker Notes

- Public repo-external worker data-plane contract is the polling HTTP surface under `/worker-api/*`.
- Shared repo-external control-plane registration also begins at `/worker-api/workers/register`, including realtime adapter onboarding.
- External worker capability must be declared through `eventBindings`; external worker registration does not define a second capability identity model.
- Realtime adapter frame shapes remain adapter-local compatibility seams. Do not treat them as the stable public non-Java worker protocol.
- `/worker-api/*` is authenticated with SDK credentials, not operator user-mode headers.
- `/worker-api/*` authorization is evaluated through the shared `AuthorizationPolicy`; the HTTP layer only adapts headers and request fields into that contract.
- required worker credential rules:
  - permission must include `worker:poll`
  - credential attributes must bind `workerId`
  - registration requests are still constrained by credential `eventScopes` and `projectScopes`
- External workers receive `TaskDispatchItem` payloads, execute locally by `eventCode`, and submit `TaskResultReport`.
- Realtime workers establish online presence through transport connection; polling workers do it through the worker API.
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
- adapter labels and old compatibility names (`websocket`, `ws`, `push`, `pull`, `queue`, ...) are not `transportHint` aliases
- capability views expose both fields; runtime routing keys off `adapterId`, not `transportHint`
- `connections` and `hasActiveEndpoint` come from the transport/session layer and are reachability facts, not capability truth
- worker inventory may still display `supportedProjects` as a coarse scope hint, but the server no longer exposes an operator write path for mutating that hint through the control console API

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

### 3.1 List Tasks

- Method: `GET`
- Path: `/status/api/tasks`
- Status: `Implemented`

Behavior notes:

- operator callers still require `task:view`
- SDK credential callers may also use this route
- current SDK task-list behavior is ownership-scoped: only tasks whose `sharedConfig._massSecurity` matches the credential principal are returned

### 3.2 Create Task

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
- `sourceType`
- `workloadClass`
- `sourceRef`

Contract rules:

- `project` and `userId` are required
- `inputs` must be a non-empty list
- unsupported `project` values are rejected
- unknown JSON fields are rejected
- retired fields such as `targetJsonList`, `targetType`, and `extraParams` are not supported
- `workloadClass` controls engine runtime optimization intent only; it is not inferred from `sharedConfig`
- current supported workload classes are `INTERACTIVE` and `BULK`
- omitted `workloadClass` defaults to `BULK`
- when `eventCode` is present, create uses the SDK mode/payload-aware path
- when `eventCode` is present, `project` and `eventCode` must exist in the SDK metadata catalog and the project must declare support for that event
- when `eventCode` is present, runtime worker capability is matched by worker-declared `supportedEventCodes`
- `supportedProjects` remains only a coarse worker grouping/filter hint; it is not the runtime event-capability source of truth
- SDK credential callers use this same route with `X-Mass-Api-Key` or `Authorization: Bearer ...`
- when an SDK credential is present, `AuthProvider.authenticate(...)` resolves a `PrincipalContext`
- SDK credentials must include `task:create` to create tasks
- when an SDK credential has `projectScopes`, the request `project` must be allowed by that scope; `projectScope` remains a single-project compatibility projection
- when an SDK credential has `eventScopes`, the request `eventCode` must be allowed by that scope
- when an SDK submitter has `projectScope`, the request `project` may be omitted or must match that scope; mismatches return HTTP 403
- when an SDK submitter has `userId`, the request `userId` may be omitted or must match that scope; mismatches return HTTP 403
- when an SDK submitter has no scoped `userId`, user resolution order is request `userId`, then submitter `principalId`
- invalid or missing SDK credentials return HTTP 401
- successful task create writes framework-owned ownership metadata to `sharedConfig._massSecurity`
- `sharedConfig._massSecurity` currently contains `createdByPrincipalId` and `createdByPrincipalType`
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
    "workloadClass": "INTERACTIVE",
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

### 3.3 Get Task

- Method: `GET`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Response notes:

- returns `task`
- returns a bounded `items` snapshot derived from persisted `TaskMsg.input`
- optional `limit` controls the snapshot size; default `100`, hard-capped at `500`
- `itemsTotal` reports the total task-message count and `itemsTruncated` reports
  whether the bounded snapshot omitted items
- returns `stateValidation`
- `task.project` is serialized as the canonical project code
- `task.user` is serialized as the current business-user binding object
- `task.sharedConfig` may include framework-owned `_massSecurity` ownership metadata in addition to caller-provided keys
- task detail also exposes a derived `data.security` view so callers do not need to parse `_massSecurity` directly
- SDK credential callers may also use this route; current read gate is ownership-based and requires the credential principal to match `sharedConfig._massSecurity`
- SDK credential owner mismatches return HTTP 403 with an explicit reason
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

### 3.4 Update Task Metadata

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

### 3.5 Delete Task

- Method: `DELETE`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Contract rules:

- only `NEW` and `TERMINAL` tasks can be deleted
- returns `ApiResponse.error(...)` when the task is in a non-deletable state

### 3.6 Audit Task

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

### 3.7 Pause Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/pause`
- Status: `Implemented`

Behavior:

- `READY` or `RUNNING` -> `PAUSED`

### 3.8 Resume Task

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

### 3.9 Terminate Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/terminate`
- Status: `Implemented`

Behavior:

- any non-`TERMINAL` task may be closed to `TERMINAL`

### 3.10 Status Routing Helper

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

### 3.11 Runtime Block Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/block`
- Status: `Implemented`

Behavior:

- explicit runtime block endpoint
- allowed from `READY` and `RUNNING`
- moves the task to `BLOCKED`
- unlike audit reject, this is not limited to `NEW`

### 3.12 List Task Messages

- Method: `GET`
- Path: `/status/api/tasks/{taskId}/messages`
- Status: `Implemented`

Query params:

- optional `limit`: default `100`, hard-capped at `500`
- no `page`, `offset`, or cursor contract is exposed; this endpoint is a
  bounded compatibility/debug snapshot, not a high-volume detail API

Response shape:

- primary per-item payload truth is `messages[*].input` and `messages[*].output`
- `target` is only a conventional key inside `messages[*].input`
- raw top-level target projections are not part of the message read model
- `total` reports the task-message count; `truncated=true` means the bounded
  snapshot omitted messages
- SDK credential callers may also use this route; current read gate is ownership-based and requires the credential principal to match `sharedConfig._massSecurity`

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "total": 2,
    "limit": 100,
    "truncated": false,
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

### 3.13 Append Items To Open-Ended Task

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

### 3.14 Seal Open-Ended Task

- Method: `PUT`
- Path: `/status/api/tasks/{taskId}/seal`
- Status: `Implemented`

Behavior:

- closes the append window for an open-ended task
- once sealed, normal terminal convergence resumes when all engine runtime work items are final

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
    "inputQueueSize": 0,
    "outputQueueSize": 0
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
    "deliveryDiagnostics": {
      "available": true,
      "queuedItems": 0,
      "queueCount": 0,
      "waitingPollers": 0,
      "maxQueuedItems": 100000,
      "oldestQueuedAgeMillis": 0,
      "enqueuedItems": 0,
      "drainedItems": 0,
      "backpressureRejectedItems": 0,
      "invalidItems": 0,
      "unavailableItems": 0,
      "shutdownClearedItems": 0,
      "directSentItems": 0,
      "directOfflineItems": 0,
      "directFailedItems": 0,
      "directInvalidItems": 0,
      "directUnavailableItems": 0,
      "queueByAdapter": {
        "polling": {
          "queuedItems": 0,
          "queueCount": 0,
          "waitingPollers": 0,
          "oldestQueuedAgeMillis": 0,
          "backpressureRejectedItems": 0
        }
      },
      "directByAdapter": {
        "websocket": {
          "sentItems": 0,
          "offlineItems": 0,
          "failedItems": 0,
          "invalidItems": 0,
          "unavailableItems": 0
        }
      }
    },
    "runtimeExecutors": {
      "transport": {
        "available": true,
        "submittedTasks": 0,
        "completedTasks": 0,
        "rejectedTasks": 0,
        "activeTasks": 0,
        "pendingTasks": 0,
        "maxPendingTasks": 10000
      },
      "event": {
        "available": false,
        "submittedTasks": 0,
        "completedTasks": 0,
        "rejectedTasks": 0,
        "activeTasks": 0,
        "pendingTasks": 0,
        "maxPendingTasks": 0
      }
    }
  }
}
```

Notes:

- queue metrics are compatibility/observability data only; they are not the
  transport runtime routing truth
- queue diagnostics are operator-only read surfaces; they are not a repo-external worker or SDK contract
- `inputQueueSize` / `outputQueueSize` are the only supported root queue size
  fields on this control-plane surface.
- `transporterAvailable` may be `false`, with queue sizes reported as `-1`,
  when the embedded runtime is assembled through adapter-native paths without a
  shared message transporter
- `deliveryDiagnostics` is the runtime delivery-store diagnostic surface. It reports
  shared dispatch backlog and polling waiters, not engine-owned task lifecycle
  or retry state. `oldestQueuedAgeMillis` is for backlog age monitoring only.
  The cumulative counters are process-local diagnostics for accepted, drained,
  rejected, invalid, unavailable, shutdown-cleared, and direct-send delivery
  outcomes. `queueByAdapter` breaks queue-focused store diagnostics down by
  concrete `adapterId`, and `directByAdapter` breaks direct-send counters down
  by concrete `adapterId` for realtime adapter troubleshooting.
- `deliveryDiagnostics` is a combined diagnostics envelope, not proof that direct-send
  outcomes are queue-owned. `queueByAdapter` remains queue-path only, while
  `directByAdapter` remains direct-send only.
- `runtimeExecutors` reports admission and execution counters for runtime-owned
  transport and optional bounded event-handler executors. `maxPendingTasks`
  reflects SDK runtime config, not a fixed platform constant.

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
- session diagnostics are operator-only read surfaces and should not be used as a second worker-control API

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
- requires authenticated operator context; it exists to populate backend-served console forms, not as a public metadata surface

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

## 7. Legacy Control-Surface Notes

- `/api/message/send` has been removed from the server API surface
- raw transport-envelope injection is not an accepted operator or SDK contract; task-backed dispatch must enter through `POST /status/api/tasks`, and repo-external worker data-plane traffic must stay on `/worker-api/*`

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

External polling workers remain the stable public integration path for non-Java runtimes such as Node, Python, or Go. The same registration API is also used by current realtime adapter validation paths when the embedded runtime assembles them.

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
- `transportHint` must use canonical family values such as `polling` or `realtime`; adapter names like `websocket` are not accepted as aliases
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
