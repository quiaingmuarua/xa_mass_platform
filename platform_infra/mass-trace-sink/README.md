# mass-trace-sink

Asynchronous JSONL execution-event sink for XA Mass Platform.

Records lifecycle events (task transitions, message dispatch, lease expiry, worker lifecycle) as newline-delimited JSON files that can be queried with DuckDB or any NDJSON-aware tool.

---

## Schema — `xa.mass.execution-event.v1`

```json
{
  "schema": "xa.mass.execution-event.v1",
  "eventId": "evt-xxx",
  "eventType": "TASK_STATUS_CHANGED",
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
    "totalMessages": 10,
    "successCount": 10,
    "failedCount": 0
  }
}
```

### Field groups

| Group | Purpose |
|---|---|
| `schema` + `eventId` | Versioned schema identity and idempotency key |
| `category` + `severity` | Fast filter axes without string-prefix matching |
| `traceId` / `spanId` / `parentSpanId` | Reserved for future OpenTelemetry integration |
| `node` | Multi-node deployment: which server/engine/adapter produced the event |
| `identity` | Unified ID bag — task, message, attempt, worker, lease |
| `transition` | State machine: `src → dst` + optional `reason` |
| `outcome` | Execution result: success flag, error code, free-text detail |
| `attrs` | Event-type-specific free-form attributes |

### Event types and default severity

| eventType | category | default severity |
|---|---|---|
| `TASK_STATUS_CHANGED` | TASK | INFO |
| `MSG_STATUS_CHANGED` | MSG | INFO |
| `MSG_DISPATCH_SENT` | DISPATCH | INFO |
| `MSG_RETRY_SCHEDULED` | MSG | WARN |
| `LEASE_EXPIRED` | LEASE | WARN |
| `WORKER_ONLINE` | WORKER | INFO |
| `WORKER_OFFLINE` | WORKER | WARN |

---

## Builder usage

```java
ExecutionEvent event = ExecutionEvent.builder()
    .eventType(ExecutionEventType.TASK_STATUS_CHANGED)
    .node("server-1", "engine-1", null)
    .identity(b -> b.taskId("t-abc"))
    .transition("READY", "RUNNING", null)
    .attrs(Map.of("batchSize", 1))
    .build();

sink.emit(event);
```

The builder auto-generates `eventId` (UUID), `ts` / `tsIso` (current instant), derives `category` and `severity` from `eventType`, and sets `schema` automatically.

---

## Configuration

```yaml
mass:
  trace:
    sink:
      enabled: true          # false → NoopExecutionEventSink (default)
      output-dir: trace-events
      queue-capacity: 4096
      rotate-after-lines: 100000
```

---

## DuckDB queries

```sql
-- Transition distribution for TASK_STATUS_CHANGED
SELECT
    transition.src,
    transition.dst,
    transition.reason,
    count(*) AS cnt
FROM read_ndjson('trace-events/events-*.jsonl')
WHERE eventType = 'TASK_STATUS_CHANGED'
GROUP BY transition.src, transition.dst, transition.reason
ORDER BY cnt DESC;
```

```sql
-- WARN and ERROR events by node
SELECT
    node.engineNodeId,
    eventType,
    count(*) AS cnt
FROM read_ndjson('trace-events/events-*.jsonl')
WHERE severity IN ('WARN', 'ERROR')
GROUP BY node.engineNodeId, eventType
ORDER BY cnt DESC;
```

```sql
-- Detect unexpected state transitions
SELECT src, dst, count(*) AS cnt
FROM (
    SELECT
        transition.src AS src,
        transition.dst AS dst
    FROM read_ndjson('trace-events/events-*.jsonl')
    WHERE eventType = 'TASK_STATUS_CHANGED'
)
WHERE (src || '->' || dst) NOT IN (
    'NEW->READY', 'READY->RUNNING', 'RUNNING->TERMINAL',
    'READY->PAUSED', 'PAUSED->READY', 'NEW->BLOCKED', 'BLOCKED->READY'
)
GROUP BY src, dst
ORDER BY cnt DESC;
```
