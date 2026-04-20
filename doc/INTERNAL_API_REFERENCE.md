# XA Mass Platform Internal API Reference

Last updated: 2026-04-20

This document tracks the current active HTTP/API surface in the mainline runtime.

Status labels used below:

- `Implemented`: endpoint exists and is wired into the current runtime
- `Partial`: endpoint exists but is mainly diagnostic, placeholder, or thin passthrough
- `Demo`: endpoint exists for status/demo pages rather than as a stable SDK surface

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
- Current reference scenario is a long-connection worker path with `Worker + WorkerContext + WebSocket gateway + mock clients`.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- Stable payload boundaries are `Task.sharedConfig` and `TaskMsg.input/output`.
- `TaskMsg.getTarget()` remains only as a backwards-compat accessor over `input["target"]`.
- `Task.intakeStatus` is the active append-window lifecycle truth; `openEnded` remains a compatibility request/response projection.
- `TaskMsg.workerId`, `workerContextId`, and `batchId` are compatibility projections of the latest `TaskMsgAttempt`.
- `Worker` and `WorkerContext` are current reference adapters, not the permanent platform boundary.

## 2. Task API

Base path: `/status/api/tasks`

### 2.1 Create Task

- Method: `POST`
- Path: `/status/api/tasks`
- Status: `Implemented`

Supported request fields:

- `userId`
- `project`
- `taskName`
- `sharedConfig`
- `targetList`
- `routingCode`
- `batchSize`
- `defaultMsgMaxRetryCount`
- `openEnded`
- `maxRuntimeSeconds`

Contract rules:

- `targetList` must be a non-empty list
- unsupported `project` values are rejected
- unknown JSON fields are rejected
- retired fields such as `targetJsonList`, `targetType`, and `extraParams` are not supported
- `defaultMsgMaxRetryCount` defaults to `3`
- `openEnded` defaults to `false`
- `maxRuntimeSeconds` defaults to `0` and disables runtime-limit termination

Example request:

```json
{
  "userId": "agent",
  "project": "demoApp",
  "taskName": "smoke-lifecycle",
  "sharedConfig": {
    "textContent": "hello"
  },
  "targetList": [
    "target-001",
    "target-002"
  ],
  "routingCode": "us",
  "batchSize": 1,
  "defaultMsgMaxRetryCount": 3,
  "openEnded": false,
  "maxRuntimeSeconds": 0
}
```

Example response:

```json
{
  "success": true,
  "message": "Task created",
  "taskId": "task-uuid"
}
```

### 2.2 Get Task

- Method: `GET`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Response notes:

- returns `task`
- returns `items` derived from persisted `TaskMsg.input`
- returns `compatTargetList` only as a backwards-compat projection of `input["target"]`
- returns `stateValidation`
- returns HTTP 404 with no body when the task does not exist

Example response shape:

```json
{
  "success": true,
  "task": {
    "tid": "task-uuid",
    "taskName": "smoke-lifecycle",
    "project": "demoApp",
    "status": "NEW",
    "taskRoutingCode": "us",
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
  "compatTargetList": [
    "target-001",
    "target-002"
  ],
  "stateValidation": {
    "valid": true,
    "needsResolution": false,
    "violations": []
  }
}
```

### 2.3 Update Task Metadata

- Method: `PUT`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Supported request fields:

- `userId`
- `project`
- `taskName`
- `sharedConfig`
- `routingCode`
- `batchSize`

Contract rules:

- metadata-only update path
- only `NEW` and `BLOCKED` tasks may be updated; returns HTTP 400 otherwise
- `targetList` and unknown fields are rejected with HTTP 400

### 2.4 Delete Task

- Method: `DELETE`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Contract rules:

- only `NEW` and `TERMINAL` tasks can be deleted
- returns HTTP 400 with `{"success": false, ...}` if the task is in a non-deletable state

### 2.5 Audit Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/audit`
- Status: `Implemented`

Query params:

- `approved`: required, `true` or `false`
- `comment`: optional

Behavior:

- `approved=true`: `NEW` or `BLOCKED` -> `READY`
- `approved=false`: `NEW` -> `BLOCKED`
- returns HTTP 400 with `{"success": false, ...}` if the transition is not allowed from the current state

Example success response:

```json
{
  "success": true,
  "message": "Task approved",
  "newStatus": "READY"
}
```

### 2.6 Pause Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/pause`
- Status: `Implemented`

Behavior:

- `READY` or `RUNNING` -> `PAUSED`

### 2.7 Resume Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/resume`
- Status: `Implemented`

Behavior:

- normally `PAUSED` -> `READY`
- if the task already completed while paused, the response reports closure to `TERMINAL`

Example success response:

```json
{
  "success": true,
  "message": "Task resumed",
  "newStatus": "READY",
  "terminalReason": ""
}
```

Alternate success response when it already completed:

```json
{
  "success": true,
  "message": "Task already completed while paused and was closed to TERMINAL",
  "newStatus": "TERMINAL",
  "terminalReason": "ALL_MESSAGES_SUCCEEDED"
}
```

### 2.8 Terminate Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/terminate`
- Status: `Implemented`

Behavior:

- any non-`TERMINAL` task may be closed to `TERMINAL`

### 2.9 Status Routing Helper

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

### 2.10 Runtime Block Task

- Method: `POST`
- Path: `/status/api/tasks/{taskId}/block`
- Status: `Implemented`

Behavior:

- explicit runtime block endpoint
- allowed from `READY` and `RUNNING`
- moves the task to `BLOCKED`
- unlike audit reject, this is not limited to `NEW`

### 2.11 List Task Messages

- Method: `GET`
- Path: `/status/api/tasks/{taskId}/messages`
- Status: `Implemented`

Query params:

- `page`: default `1`
- `size`: default `20`, hard-capped at `500`

Response shape:

- primary per-item payload truth is `messages[*].input` and `messages[*].output`
- `messages[*].compatTarget` is the explicit backwards-compat projection of `input["target"]`
- raw `target` is no longer part of the intended API read model

```json
{
  "success": true,
  "total": 2,
  "page": 1,
  "size": 20,
  "messages": [
    {
      "msgId": "msg-1",
      "taskId": "task-uuid",
      "status": "SUCCESS",
      "workerId": "worker-a",
      "workerContextId": "worker-context-a",
      "batchId": "batch-1",
      "retryCount": 0,
      "maxRetryCount": 3,
      "finalReason": "BUSINESS_SUCCESS",
      "result": "ok",
      "errorMessage": null,
      "errorCode": null,
      "input": {
        "target": "target-001"
      },
      "output": {},
      "compatTarget": "target-001"
    }
  ]
}
```

### 2.11 Append Items To Open-Ended Task

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
- task must have `intakeStatus=OPEN` (`openEnded=true` remains the compatibility create flag)
- task must still be active

Example response:

```json
{
  "success": true,
  "message": "Items appended",
  "added": 2
}
```

### 2.12 Seal Open-Ended Task

- Method: `PUT`
- Path: `/status/api/tasks/{taskId}/seal`
- Status: `Implemented`

Behavior:

- closes the append window for an open-ended task
- once sealed, normal terminal convergence resumes when all persisted messages are final

Example response:

```json
{
  "success": true,
  "message": "Task sealed",
  "status": "RUNNING"
}
```

## 3. Queue APIs

Base path: `/api/queue`

### 3.1 Queue Status

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

### 3.2 Queue Detail

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

### 3.3 Queue Metrics

- Method: `GET`
- Path: `/api/queue/metrics`
- Status: `Partial`

Current behavior:

- endpoint exists
- currently returns static zero values rather than real throughput metrics

## 4. Session APIs

Base path: `/api/session`

### 4.1 List Sessions

- Method: `GET`
- Path: `/api/session/list`
- Status: `Implemented`

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
          "channelId": "abc123"
        }
      ]
    }
  ]
}
```

### 4.2 Session Stats

- Method: `GET`
- Path: `/api/session/stats`
- Status: `Implemented`

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

## 5. Config APIs

### 5.1 Global Project List

- Method: `GET`
- Path: `/api/config/projects`
- Status: `Implemented`

Behavior:

- returns the configured project code list from `GlobalConfig`

### 5.2 Config Page

- Method: `GET`
- Path: `/config`
- Status: `Demo`

Behavior:

- returns the config HTML page

## 6. Message API

### 6.1 Send Message

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

## 7. Status And Demo Pages

Base path: `/status`

### 7.1 Summary Pages

- `GET /status` - `Demo`
- `GET /status/tasks` - `Demo`
- `GET /status/workers` - `Demo`
- `GET /status/rules` - `Demo`

Behavior:

- return Thymeleaf status/demo pages for runtime inspection

### 7.2 Worker Project Helpers

- `GET /status/workers/allProjects` - `Demo`
- `POST /status/workers/updateSupportedProjects` - `Demo`

Current purpose:

- support status/demo-page editing of mock worker project bindings
- should not be treated as a stable SDK contract

## 8. Health And Docs

- `GET /actuator/health` - `Implemented`
- `GET /doc.html` - `Demo`

## 9. Response Shape Notes

The active API surface uses two response styles:

**Task API (`/status/api/tasks/**`)** — flat `Map<String,Object>`:

- all success responses: `{"success": true, <data fields at top level>}`
- all error responses: `{"success": false, "message": "<reason>"}`
- HTTP 404 on task-not-found from `GET /status/api/tasks/{taskId}` returns no body
- HTTP 400 for validation failures and out-of-state transitions
- `ApiResponse` wrapper is not used on this path

**Other APIs (queue, session, config, message passthrough)** — use `ApiResponse<T>` or `{"code", "msg", "data"}` shapes as documented per-endpoint above.

Implication:

- consumers should treat task endpoints and other diagnostic endpoints as separate response families
- do not assume `code/msg/data` wrapping on task endpoints
