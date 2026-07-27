# Seed Result Runtime Redis Shape

Status: active new-kernel Redis shape; Java ingress append and Python
executable-spec consume/routing implemented.

## Keys

```text
rr:{prefix}:seed-results:success
rr:{prefix}:seed-results:worker-failure
rr:{prefix}:seed-results:adapter-rejection
```

Each key is a Redis LIST containing deterministic SeedResult JSON:

```json
{
  "commandId": "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
  "opaqueResultContext": "...",
  "outcomeCode": "200",
  "opaqueResultPayload": "... or null"
}
```

Ingress classifies only the public `outcomeCode`, groups a mixed batch by
`SeedResultOutcomeClass`, and `RPUSH`es the non-empty groups. The Java Gateway
owns the public ingress operation; the Python runtime retains equivalent
executable-spec/test-support behavior. Cross-class append is best-effort and
not atomic. Neither ingress implementation decodes `opaqueResultContext`.
`commandId` is stored unchanged for trace correlation and is not used for
partitioning, deduplication, result precedence, or score fencing.

`consume_seed_results(outcomeClass, limit)` performs bounded `LPOP` operations
against exactly one class queue. FIFO is preserved within each class. Corrupt
members are consumed and skipped.

There is no endpoint-manager, exact subcode, Task, WorkerGroup, or evidence-
source partition. There is also no pending/ack list, retry list, repair scan, or
cross-key Lua.

## Successful Result Truth

The ingress LISTs are best-effort evidence. Successful result truth is stored
separately by `TaskRuntime`:

```text
tr:{prefix}:task:{taskId}:results
  HASH messageId -> opaqueResultPayload
```

The HASH is last-success: a later accepted `200` overwrites the prior payload
for the same Task-scoped `messageId`. Failure payloads are not stored in v0.

## Owner Rules

```text
Java Worker Delivery Redis ingress
  owns public class queue encoding, partitioning, and bounded append

RedisSeedResultRuntime
  owns Python executable-spec append proof and production bounded consume

ResultRoutingPacer
  owns bounded class consumption, context decoding, owner-key grouping,
  and delegation to injected handlers

ResultRoutingBuiltinPolicies / replacement handlers
  own class-local Task and Worker owner-operation policy

TaskRuntime
  owns Task-scoped last-success result payload truth

TaskItemScoreBandCore / WorkerScoreCore
  own all score validation and mutation
```
