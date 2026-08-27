# Task Result Runtime Redis Shape

Status: active Kernel Redis shape; Java production append/consume and Python
executable-spec Oracle implemented.

## Keys

```text
xa_mass:<scope>:result:routing:success
xa_mass:<scope>:result:routing:failure
```

Each key is a Redis LIST of deterministic `DeliveryReport` JSON. The Task
Result Runtime receives an explicit `TaskResultClass` from its bounded caller:

```text
appendTaskResults(resultClass, reports)
consumeTaskResults(resultClass, limit)
```

The Runtime validates the class, bounded input and Report encoding only. It
does not read `outcomeCode`, decode `forward`, interpret `messageType`, verify
the producer, or derive the lane. Server Worker Delivery ingress owns producer
and endpoint-code validation before append.

Append performs one `RPUSH` to the selected lane. Consume uses bounded Redis 7
`LPOP key count`. FIFO is preserved within a lane; corrupt members are consumed
and skipped. The two lane operations are independent and no cross-lane atomic
append is promised.

There is no endpoint-manager, Task, WorkerGroup or producer partition,
pending/ack state, retry, replay, repair scan or cross-key Lua. `src`,
`sourceId`, raw `outcomeCode` and `forward` remain encoded evidence fields.

## Successful Result Truth

Ingress LISTs are transient evidence. Task success payload truth is owned by
`TaskRuntime`:

```text
xa_mass:<scope>:task:<taskId>:results
  HASH messageId -> payload
```

Only the SUCCESS strategy stores payload and promotes the TaskItem. FAILURE
does not store payload or rewrite the Item retry coordinate.

## Owner Rules

```text
Server Worker Delivery ingress
  owns producer validation, endpoint error-code validation and lane mapping

RedisTaskResultRuntime
  owns selected-lane encoding, append and bounded consume

ResultRoutingPacer
  owns ResultContext decode, bounded grouping and fixed SUCCESS/FAILURE policy

TaskRuntime
  owns Task-scoped last-success payload truth

TaskItemScoreBandCore / WorkerScoreCore
  own score validation and mutation
```

The old `worker-failure` and `adapter-rejection` LISTs are not read or migrated.
They were transient best-effort evidence and have no compatibility alias.
