# mass-trace-sink

Status: MVP implementation of the platform trace/audit layer.

## What this module owns

- `ExecutionEventSink` — the only contract other modules depend on for trace emission
- `ExecutionEvent` — the immutable event envelope (src/dst state-transition model, schema version `v`)
- `ExecutionEventType` — the canonical set of traceable event types
- `JsonlExecutionEventSink` — async JSONL rotating file sink (MVP implementation)
- `NoOpExecutionEventSink` — silent no-op default for environments without a configured sink
- `JsonlSinkConfig` — configuration value object

## What this module does NOT own

- Business domain data or workflow truth (that lives in upstream business systems)
- Control-plane storage (task definitions, worker registration — that is `mass-storage-*`)
- Runtime queue/lease/counter state (that is `mass-runtime-*`)
- Transport-specific protocol logic (that is `transport/*`)

## Event model: src/dst state transitions

Every event records **where the system was before** (`src`) and **where it went** (`dst`). This is distinct from a single-point event that records only the current state.

```json
{
  "v": 1,
  "ts": "2026-05-05T21:00:00.123Z",
  "eventType": "TASK_STATUS_CHANGED",
  "src": "READY",
  "dst": "RUNNING",
  "reason": null,
  "taskId": "t-xxx",
  "messageId": null,
  "workerId": null,
  "adapterId": null,
  "retryCount": null,
  "extra": {}
}
```

Rules:
- `v` is always `1` for this implementation
- `ts` is ISO-8601 UTC
- `src` and `dst` hold the status enum name (e.g. `TaskStatus`, `TaskMsgStatus`) before and after the transition
- `reason` is only populated for terminal-state transitions (task TERMINAL, message FAILED/EXPIRED, worker OFFLINE)
- For non-transition events (e.g. `MSG_DISPATCH_SENT`), `src` and `dst` are null
- `extra` is a free-form map for additional context; defaults to empty object

## Event types

| Type | src/dst | Notes |
|---|---|---|
| `TASK_STATUS_CHANGED` | TaskStatus names | `reason` on terminal |
| `MSG_STATUS_CHANGED` | TaskMsgStatus names | `reason` on FAILED/EXPIRED |
| `MSG_DISPATCH_SENT` | null/null | `workerId` + `adapterId` required |
| `MSG_RETRY_SCHEDULED` | FAILED-ish / pending | `retryCount` required |
| `LEASE_EXPIRED` | null/null | `workerId` + `messageId` |
| `WORKER_ONLINE` | null/null | `workerId` + `adapterId` |
| `WORKER_OFFLINE` | null/null | `workerId` + `adapterId` + `reason` |

## Configuring JsonlExecutionEventSink

```java
JsonlSinkConfig config = JsonlSinkConfig.defaults(Path.of("/var/log/xa-trace"));
// or with explicit settings:
JsonlSinkConfig config = new JsonlSinkConfig(
    Path.of("/var/log/xa-trace"),
    64L * 1024 * 1024,  // rotate at 64 MB
    8192                // queue capacity
);

JsonlExecutionEventSink sink = new JsonlExecutionEventSink(config);
```

Files written:
- `events-current.jsonl` — active file being written
- `events-{yyyyMMdd'T'HHmmss}.jsonl` — rotated files

The sink must be closed on application shutdown to drain queued events:

```java
sink.close(); // or use try-with-resources
```

## Non-blocking guarantee

`emit()` is fire-and-forget. It places events onto a bounded internal queue and returns immediately. A single background daemon thread (`trace-sink-writer`) drains the queue and writes to disk. If the queue is full, the event is dropped and a rate-limited warn is logged. The calling thread is never blocked.

## Querying with DuckDB

```sql
SELECT src, dst, reason, count(*)
FROM read_ndjson('events-*.jsonl')
WHERE eventType = 'TASK_STATUS_CHANGED'
GROUP BY src, dst, reason
ORDER BY count(*) DESC;
```

```sql
-- Find non-terminal messages and their dispatch latency
SELECT messageId, taskId, workerId
FROM read_ndjson('events-*.jsonl')
WHERE eventType = 'MSG_DISPATCH_SENT';
```
