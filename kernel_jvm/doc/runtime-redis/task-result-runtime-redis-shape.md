# Task Result Runtime Redis Shape

Status: active Java Kernel Task Result Redis shape and production append/consume
contract.

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

## TaskItem Result Projection

Ingress LISTs are transient evidence. TaskItem result projection is owned by
`TaskRuntime`:

```text
xa_mass:<scope>:task:<taskId>:results
  HASH messageId -> encoded TaskItemResult(code, opaqueResultPayload)
```

Exact code `200` is success. Success encodes the complete Result before one
multi-field `HSET`, so it may replace an earlier terminal failed value or an
earlier success payload. Terminal failed storage encodes
`{"code":"failed","opaqueResultPayload":"TaskItem ended without a successful result"}`
and applies `HSETNX` to each bounded Message ID in one owner-local, single-key
Lua operation. Every observed Result therefore has a non-empty payload. A
later SUCCESS replaces failed, while a later failed write cannot replace any
observed Result.

Point reads use one bounded `HMGET`; scan reads use bounded
`HSCAN COUNT 1000` pages. Each present field is decoded without another Redis
lookup and classified by its own code; an absent field is not observed.
Malformed or legacy raw values fail closed as corrupt Owner data instead of
being guessed as success or failure.

The SUCCESS Result policy stores success then promotes the TaskItem to
`FINAL_SUCCESS`. Ordinary retryable FAILURE evidence does not store an Item
result or rewrite the Item coordinate. Task Dispatch stores failed only when
the existing retry budget is exhausted or Item TTL has elapsed, and only then
promotes the same IDs to `FINAL_FAILED`. A failed-result write exception leaves
the Item score unchanged for a later idempotent Dispatch round.

## Owner Rules

```text
Server Worker Delivery ingress
  owns producer validation, endpoint error-code validation and lane mapping

RedisTaskResultRuntime
  owns selected-lane encoding, append and bounded consume

ResultConvergenceApplication
  owns fixed SUCCESS/FAILURE lane consumption and shared-capacity asynchronous
  Batch dispatch; both Task lanes may execute concurrent Batches. FIFO fixes
  consume order, not policy completion order; duplicate SUCCESS payloads use
  the last actual Redis field write

TaskResultBatchPolicy
  owns ResultContext decode, bounded grouping, last-wins collapse and
  SUCCESS/FAILURE semantic event publication

TaskItemResultEvents
  owns successful TaskItem Result meaning; it stores payload then promotes
  finality

WorkerExecutionResultEvents
  owns Worker execution success/failure event meaning and is the only Result
  layer allowed to unwrap WorkerLeaseReference into the score-owner fence

TaskRuntime
  owns the Task-scoped self-describing Result HASH

TaskDispatchPolicy
  owns exhaustion/TTL classification and orders failed-result storage before
  FINAL_FAILED promotion

TaskItemScoreBandCore / WorkerScoreCore
  own score validation and mutation
```

The old `worker-failure` and `adapter-rejection` LISTs are not read or migrated.
They were transient best-effort evidence and have no compatibility alias. The
self-describing Result shape is also a clean cut: scopes containing legacy raw
HASH values or the retired `results:success` SET must be cleared or recreated
before use. Runtime code does not read, migrate or delete either legacy shape.
