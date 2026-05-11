# mass-trace-sink

Asynchronous JSONL trace sink for XA Mass Platform.

This module owns the canonical trace event model and the default JSONL sink
implementation. It does not define a second logging vocabulary beside engine
logs. `ExecutionEventType` is the stable event-name registry for the platform.

Use with:

- [../../doc/TRACE_CONTRACT.md](../../doc/TRACE_CONTRACT.md)
- [../../doc/INFRA_TRUTH_LAYERS.md](../../doc/INFRA_TRUTH_LAYERS.md)

---

## Mainline

Current mainline decisions:

- trace is a new platform feature; older MDC lifecycle logs are not the
  long-term contract
- `ExecutionEvent` is the only canonical trace payload shape
- `ExecutionEventType` is the only canonical trace event-name vocabulary
- JSONL sink is an implementation detail of the canonical model, not a second
  event design

No compatibility layer is required for superseded trace names such as
`*_CHANGED` when `*_TRANSITION` is the new mainline.

---

## Schema - `xa.mass.execution-event.v1`

```json
{
  "schema": "xa.mass.execution-event.v1",
  "eventId": "evt-xxx",
  "eventType": "TASK_STATUS_TRANSITION",
  "category": "TASK",
  "severity": "INFO",
  "ts": 1710000000000,
  "tsIso": "2026-05-05T21:00:00.123Z",
  "traceId": "trace-xxx",
  "spanId": null,
  "parentSpanId": null,
  "node": {
    "serverNodeId": "local-server-1",
    "engineNodeId": "local-engine-1",
    "adapterNodeId": null
  },
  "identity": {
    "taskId": "t-xxx",
    "messageId": null,
    "attemptId": null,
    "workerId": null,
    "workerContextId": null,
    "endpointId": null,
    "routeKey": null,
    "leaseToken": null
  },
  "transition": {
    "src": "RUNNING",
    "dst": "TERMINAL",
    "reason": "ALL_MESSAGES_SUCCEEDED"
  },
  "outcome": {
    "success": true,
    "errorCode": null,
    "detail": null
  },
  "attrs": {
    "trigger": "RESOLVE_TASK_STATE",
    "source": "TaskManager",
    "reason": "all messages converged",
    "terminalReason": "ALL_MESSAGES_SUCCEEDED"
  }
}
```

### Field groups

| Group | Purpose |
| --- | --- |
| `schema` + `eventId` | versioned schema identity and event id |
| `eventType` | canonical platform event name |
| `category` + `severity` | fast filter axes |
| `traceId` / `spanId` / `parentSpanId` | distributed-trace linkage |
| `node` | producer node context |
| `identity` | task / message / attempt / worker / lease identity bag |
| `transition` | lifecycle transition semantics |
| `outcome` | decision or result outcome |
| `attrs` | standardized event-specific supplemental fields |

---

## Stable Event Types

The current stable event registry is the `ExecutionEventType` enum.

Current event types:

- `TASK_STATUS_TRANSITION`
- `TASK_TERMINAL_CLOSED`
- `TASK_PROGRESS_SNAPSHOT`
- `TASK_WORK_STATUS_TRANSITION`
- `TASK_WORK_ATTEMPT_STATUS_TRANSITION`
- `TASK_WORK_ATTEMPT_CLOSED`
- `TASK_WORK_LOGICALLY_FINAL`
- `TASK_WORK_RETRY_RESET`
- `WORKER_CONTEXT_STATUS_TRANSITION`
- `WORKER_LOCK_ACQUIRED`
- `WORKER_LOCK_RELEASED`
- `WORKER_MATCH_ACCEPTED`
- `WORKER_MATCH_REJECTED`
- `DISPATCH_REQUESTED`
- `DISPATCH_SKIPPED`
- `ASSIGNMENT_SUMMARY`
- `TASK_STATE_VALIDATION_SUMMARY`
- `DISPATCH_BINDING_SUMMARY`
- `ASSIGNMENT_QUEUE_SNAPSHOT`
- `ASSIGNMENT_RETRY_SCHEDULED`
- `CALLBACK_ACCEPTED`
- `CALLBACK_IGNORED_DUPLICATE`
- `CALLBACK_IGNORED_LATE`
- `CALLBACK_REJECTED_NO_ACTIVE_LEASE`
- `CALLBACK_REJECTED_NO_ACTIVE_ATTEMPT`
- `RESOURCE_RELEASED`
- `RESOURCE_RELEASE_FAILED`
- `LEASE_EXPIRED`
- `WORKER_ONLINE`
- `WORKER_OFFLINE`

---

## Builder usage

```java
ExecutionEvent event = ExecutionEvent.builder()
    .eventType(ExecutionEventType.TASK_STATUS_TRANSITION)
    .node("server-1", "engine-1", null)
    .identity(b -> b.taskId("t-abc"))
    .transition("READY", "RUNNING", null)
    .attrs(Map.of(
            "trigger", "ASSIGNMENT_SUCCESS",
            "source", "TaskManager",
            "reason", "first work item leased"))
    .build();

sink.emit(event);
```

The builder auto-generates `eventId`, `ts`, `tsIso`, `category`, `severity`,
and an empty `identity` block when the caller does not set one.

---

## Configuration

```yaml
mass:
  trace:
    sink:
      enabled: true
      output-dir: trace-events
      queue-capacity: 4096
      rotate-after-lines: 100000
      overflow-policy: DROP
      shutdown-drain-timeout-ms: 5000
```

When disabled, the platform uses `NoopExecutionEventSink`.

Local-debug example:

```yaml
mass:
  trace:
    sink:
      enabled: true
      output-dir: trace-events
      overflow-policy: FALLBACK_SYNC
```

For local issue diagnosis, `FALLBACK_SYNC` is acceptable because the goal is to
preserve trace evidence, not to protect a production hot path.

---

## Overflow Policies

| Policy | Behavior | Recommended use |
| --- | --- | --- |
| `DROP` | event is discarded when the queue is full; `droppedCount` increments | production default |
| `FALLBACK_SYNC` | caller thread writes directly to the file | debug / low-throughput only |

---

## DuckDB queries

```sql
SELECT
    epoch_ms(ts) AS time,
    eventType,
    identity.taskId,
    identity.messageId,
    identity.attemptId,
    transition.src,
    transition.dst,
    attrs.reason
FROM read_ndjson('trace-events/events-*.jsonl')
WHERE identity.taskId = 't-xxx'
ORDER BY ts;
```

A ready-to-run local smoke query also lives at:

- [duckdb/trace_local_smoke.sql](./duckdb/trace_local_smoke.sql)

```sql
SELECT
    transition.src,
    transition.dst,
    count(*) AS cnt
FROM read_ndjson('trace-events/events-*.jsonl')
WHERE eventType = 'TASK_STATUS_TRANSITION'
GROUP BY transition.src, transition.dst
ORDER BY cnt DESC;
```

```sql
SELECT
    node.engineNodeId,
    eventType,
    count(*) AS cnt
FROM read_ndjson('trace-events/events-*.jsonl')
WHERE severity IN ('WARN', 'ERROR')
GROUP BY node.engineNodeId, eventType
ORDER BY cnt DESC;
```

