# Worker Result Runtime Redis Shape

Status: active new-kernel Redis shape; Java ingress append and Python
executable-spec consume/routing implemented.

## Keys

```text
xa_mass:<scope>:result:routing:success
xa_mass:<scope>:result:routing:worker-failure
xa_mass:<scope>:result:routing:adapter-rejection
```

Each key is a Redis LIST containing deterministic `DeliveryReport` JSON:

```json
{
  "dst": "TASK",
  "forward": "...",
  "messageType": "extension.worker.telecom.phone.inspect",
  "outcomeCode": "200",
  "payload": "{\"isValid\":true}",
  "sourceId": "worker-1",
  "src": "WORKER"
}
```

Ingress classifies only `outcomeCode`, groups a mixed batch by
`DeliveryReportOutcomeClass`, and `RPUSH`es non-empty groups. Cross-class append
is best-effort and not atomic.

The runtime does not decode `forward` or interpret `messageType`. DeliveryReport
has no outer message or correlation ID; TaskItem identity remains inside the
opaque Result Context until Result Routing.
`consume_worker_results(outcomeClass, limit)` performs bounded `LPOP` against
one class queue. FIFO is preserved within each class; corrupt members are
consumed and skipped.

There is no endpoint-manager, Task, WorkerGroup, producer-based partition,
pending/ack, retry, repair-scan, or cross-key Lua state. `src/sourceId` remain
part of the encoded Report evidence.

## Successful Result Truth

Ingress LISTs are transient evidence. Task success payload truth is owned by
`TaskRuntime`:

```text
xa_mass:<scope>:task:<taskId>:results
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
