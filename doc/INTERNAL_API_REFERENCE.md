# XA Mass Platform Internal API Reference

Last updated: 2026-04-23

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

## 1. Platform Contract Notes

- The project is a general distributed task scheduling platform. Its core abstraction is: assign work items to online workers, collect execution results, and converge task state.
- The current HTTP/API surface validates the kernel; it is not the kernel definition itself.
- Current reference scenario still includes a long-connection adapter path with `Worker + WorkerContext + WebSocket gateway + mock clients`, but pull-style workers now also run through the transport-neutral SDK/runtime seam.
- `xa-mass-sdk` now assembles worker delivery through a transport runtime registry/factory seam, so HTTP/API surfaces should not assume WebSocket is the only runtime transport.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- `Task.project` and `Task.user` are first-class business bindings in the core task aggregate. Frontend/API edge shapes still use `project` and `userId` strings for create/update.
- Stable payload boundaries are `Task.sharedConfig` and `TaskMsg.input/output`.
- `TaskMsg.output` is the canonical logical success payload for one work item; legacy `result` is only a summary/string compatibility field.
- `TaskMsgAttempt` keeps the concrete attempt-level callback snapshot, including per-attempt output/error details.
- `target` is only a conventional key inside `TaskMsg.input`; no dedicated target compatibility accessor remains.
- `Task.intakeStatus` is the active append-window lifecycle truth; `openEnded` is only the create/request projection.
- `TaskMsg.latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId` are latest-attempt projections of `TaskMsgAttempt`.
- Worker/gateway callbacks must resolve a unique active `TaskMsgAttempt`; the runtime no longer synthesizes legacy attempts for result write-back.
- `Worker` and `WorkerContext` are current reference adapters, not the permanent platform boundary.

## 1.1 SDK Metadata Catalog

- SDK v1 adds a code-registered project/event metadata catalog for platform self-description.
- Metadata is exposed through read-only `/sdk/meta/**` endpoints.
- Catalog entries are not the runtime scheduling truth yet; they are a public capability directory for SDK and UI callers.
- Current metadata covers representative task-event scenarios for crawler, SMS, and chatbot flows.

## 1.2 SDK Task Contract Notes

- SDK v1 keeps `/status/api/tasks/**` as the existing mainline contract and adds a parallel `/sdk/tasks/**` surface.
- `/sdk/tasks` is the SDK-oriented create/read/append/seal entry and currently still maps into the existing engine task DTOs.
- `MassTaskRequest.mode=SINGLE_RUN` maps to `openEnded=false`; `STREAMING` maps to `openEnded=true`.
- `MassTaskRequest` payloads are normalized into `TaskMsg.input` as:
  - text: `{"type":"text","text":"..."}`
  - json: `{"type":"json","data":{...}}`
- SDK task metadata is persisted in `Task.sharedConfig._sdk` with `eventCode`, `payloadType`, and `taskMode`.
- `/sdk/tasks` create does not auto-approve tasks; the current lifecycle still starts at `NEW`.

## 2. SDK API

### 2.1 List SDK Projects

- Method: `GET`
- Path: `/sdk/meta/projects`
- Status: `Implemented`

Response notes:

- returns the registered `ProjectMetadata` list
- each project includes `code`, `name`, `description`, `enabled`, and `eventCodes`

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

- returns the full `EventMetadata` list for the project's declared `eventCodes`
- returns HTTP 404 when `projectCode` does not exist in the catalog

### 2.4 List SDK Events

- Method: `GET`
- Path: `/sdk/meta/events`
- Status: `Implemented`

Response notes:

- returns the registered `EventMetadata` list
- each event includes `code`, `name`, `description`, `payloadTypes`, `taskModes`, and `enabled`

### 2.5 Get SDK Event

- Method: `GET`
- Path: `/sdk/meta/events/{eventCode}`
- Status: `Implemented`

Behavior:

- returns the registered `EventMetadata`
- returns HTTP 404 when `eventCode` does not exist in the catalog

### 2.6 Create SDK Task

- Method: `POST`
- Path: `/sdk/tasks`
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
- `maxRuntimeSeconds`

Contract rules:

- `project`, `taskName`, and `eventCode` are required
- `mode` defaults to `SINGLE_RUN`
- `payloadType` defaults to `JSON`
- `inputs` must be a non-empty list
- request `project` must exist in the SDK catalog
- request `eventCode` must exist in the SDK catalog
- the chosen project must explicitly declare support for the chosen event
- `X-Mass-Api-Key` is reserved for future submitter resolution; current mainline allows requests without it
- create returns only `taskId` and does not auto-transition the task out of `NEW`
- SDK task creation forwards `sharedConfig` as supplied; SDK event metadata does not inject routing hints into the task payload.

Example request:

```json
{
  "project": "demoApp",
  "taskName": "sdk-crawler",
  "eventCode": "crawler.fetch-page",
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
    "taskId": "task-sdk-001",
    "message": "SDK task created"
  }
}
```

### 2.7 Get SDK Task

- Method: `GET`
- Path: `/sdk/tasks/{taskId}`
- Status: `Implemented`

Response notes:

- returns `task`
- returns `items` derived from persisted `TaskMsg.input`
- returns `stateValidation`
- returns HTTP 404 when the task does not exist

### 2.8 Append Items To SDK Streaming Task

- Method: `POST`
- Path: `/sdk/tasks/{taskId}/items`
- Status: `Implemented`

Contract rules:

- `inputs` must be a non-empty list
- the task must exist
- the task must still have open intake
- when the task carries `_sdk.payloadType`, appended items must match that payload type
- when no SDK payload metadata exists, string inputs map to text and object inputs map to json

Example request:

```json
{
  "inputs": [
    "hello",
    "world"
  ]
}
```

### 2.9 Seal SDK Streaming Task

- Method: `PUT`
- Path: `/sdk/tasks/{taskId}/seal`
- Status: `Implemented`

Behavior:

- seals the append window for an open-ended SDK task
- returns HTTP 400 when the task is not open-ended

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
- `sharedConfig`
- `inputs`
- `batchSize`
- `defaultMsgMaxRetryCount`
- `openEnded`
- `maxRuntimeSeconds`

Contract rules:

- `project` and `userId` are required
- `inputs` must be a non-empty list of work-item payload maps
- unsupported `project` values are rejected
- unknown JSON fields are rejected
- retired fields such as `targetJsonList`, `targetType`, and `extraParams` are not supported
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
        "msgId": "msg-1",
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
    "transporterAvailable": true
  }
}
```

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
          "role": "task",
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

## 8. Worker Debug APIs

Base path: `/status/workers`

### 8.1 Message History

- Method: `GET`
- Path: `/status/workers/message-history`
- Status: `Partial`

Behavior:

- returns the current outbound/inbound debug message history for one worker

### 8.2 Send Debug Message

- Method: `POST`
- Path: `/status/workers/send-message`
- Status: `Partial`

Behavior:

- sends a debug/control payload to a worker over the task-messages session
- does not create or mutate `TaskMsg`

## 9. Health And Docs

- `GET /actuator/health` - `Implemented`
- `GET /doc.html` - `Demo`

## 10. Response Shape Notes

The active JSON API surface uses one response family:

- success: `{"code":0,"msg":"ok","data":...}`
- error: `{"code":<http-ish code>,"msg":"<reason>","data":null}`

Implication:

- consumers should read payloads from `data`
- task endpoints follow the same `ApiResponse<T>` envelope as the other JSON endpoints
