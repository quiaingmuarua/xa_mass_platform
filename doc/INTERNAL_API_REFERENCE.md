# XA Mass Platform Internal API Reference

Last updated: 2026-04-22

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
- Current reference scenario is a long-connection worker path with `Worker + WorkerContext + WebSocket gateway + mock clients`.
- Workers can be phone apps, crawlers, LLM agents, IM bots, or other long-lived executors.
- Stable payload boundaries are `Task.sharedConfig` and `TaskMsg.input/output`.
- `target` is only a conventional key inside `TaskMsg.input`; no dedicated target compatibility accessor remains.
- `Task.intakeStatus` is the active append-window lifecycle truth; `openEnded` is only the create/request projection.
- `TaskMsg.latestAttemptWorkerId`, `latestAttemptWorkerContextId`, and `latestAttemptBatchId` are latest-attempt projections of `TaskMsgAttempt`.
- Worker/gateway callbacks must resolve a unique active `TaskMsgAttempt`; the runtime no longer synthesizes legacy attempts for result write-back.
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
- `inputs`
- `routingCode`
- `batchSize`
- `defaultMsgMaxRetryCount`
- `openEnded`
- `maxRuntimeSeconds`

Contract rules:

- `inputs` must be a non-empty list of work-item payload maps
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
  "inputs": [
    {
      "target": "target-001"
    },
    {
      "target": "target-002"
    }
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
  "code": 0,
  "msg": "ok",
  "data": {
    "taskId": "task-uuid",
    "message": "Task created"
  }
}
```

### 2.2 Get Task

- Method: `GET`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Response notes:

- returns `task`
- returns `items` derived from persisted `TaskMsg.input`
- returns `stateValidation`
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
    "stateValidation": {
      "valid": true,
      "needsResolution": false,
      "violations": []
    }
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
- `inputs` and unknown fields are rejected with HTTP 400

### 2.4 Delete Task

- Method: `DELETE`
- Path: `/status/api/tasks/{taskId}`
- Status: `Implemented`

Contract rules:

- only `NEW` and `TERMINAL` tasks can be deleted
- returns `ApiResponse.error(...)` when the task is in a non-deletable state

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

### 2.12 Append Items To Open-Ended Task

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

### 2.13 Seal Open-Ended Task

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

### 5.2 Backend-Served Control Console

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

## 7. Worker Debug APIs

Base path: `/status/workers`

### 7.1 Message History

- Method: `GET`
- Path: `/status/workers/message-history`
- Status: `Partial`

Behavior:

- returns the current outbound/inbound debug message history for one worker

### 7.2 Send Debug Message

- Method: `POST`
- Path: `/status/workers/send-message`
- Status: `Partial`

Behavior:

- sends a debug/control payload to a worker over the task-messages session
- does not create or mutate `TaskMsg`

## 8. Health And Docs

- `GET /actuator/health` - `Implemented`
- `GET /doc.html` - `Demo`

## 9. Response Shape Notes

The active JSON API surface uses one response family:

- success: `{"code":0,"msg":"ok","data":...}`
- error: `{"code":<http-ish code>,"msg":"<reason>","data":null}`

Implication:

- consumers should read payloads from `data`
- task endpoints follow the same `ApiResponse<T>` envelope as the other JSON endpoints
