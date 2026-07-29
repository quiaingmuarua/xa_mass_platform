# Worker Result Runtime Redis Shape

Status: active new-kernel Redis shape; Java ingress append and Python
executable-spec consume/routing implemented.

## Keys

```text
rr:{prefix}:worker-results:success
rr:{prefix}:worker-results:worker-failure
rr:{prefix}:worker-results:adapter-rejection
```

Each key is a Redis LIST containing deterministic `WorkerResult` JSON:

```json
{
  "dst": "TASK",
  "forward": "...",
  "messageId": "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
  "messageType": "telecom.phone.inspect",
  "outcomeCode": "200",
  "payload": "{\"isValid\":true}"
}
```

Ingress classifies only `outcomeCode`, groups a mixed batch by
`WorkerResultOutcomeClass`, and `RPUSH`es non-empty groups. Cross-class append
is best-effort and not atomic.

The runtime does not decode `forward`, interpret `messageType`, or use
`messageId` for partitioning, deduplication, precedence, or score fencing.
`consume_worker_results(outcomeClass, limit)` performs bounded `LPOP` against
one class queue. FIFO is preserved within each class; corrupt members are
consumed and skipped.

There is no endpoint-manager, Task, WorkerGroup, source, pending/ack, retry,
repair-scan, or cross-key Lua state.

## Successful Result Truth

Ingress LISTs are transient evidence. Task success payload truth is owned by
`TaskRuntime`:

```text
tr:{prefix}:task:{taskId}:results
  HASH messageId -> payload
```

Result Routing decodes `forward` into the Task/Item/Worker owner coordinates.
A valid `200` stores the result payload and promotes the Item. Failure payloads
are not stored in v0.

## Owner Rules

```text
Java Worker Delivery ingress
  owns public result validation and bounded append

RedisWorkerResultRuntime
  owns outcome queue encoding, append, and bounded consume

ResultRoutingPacer
  owns forward-context decode and evidence grouping

TaskRuntime
  owns Task-scoped last-success payload truth

TaskItemScoreBandCore / WorkerScoreCore
  own score validation and mutation
```
