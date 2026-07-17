# Seed Result Runtime Redis Shape

Status: active new-kernel Redis shape; Python executable spec implemented.

## Keys

```text
rr:{prefix}:seed-results:success
rr:{prefix}:seed-results:worker-failure
rr:{prefix}:seed-results:adapter-rejection
```

Each key is a Redis LIST containing deterministic SeedResult JSON:

```json
{
  "opaqueResultContext": "...",
  "outcomeCode": "200",
  "opaqueResultPayload": "... or null"
}
```

`append_seed_results` classifies only the public `outcomeCode`, groups a mixed
batch by `SeedResultOutcomeClass`, and uses a non-transaction pipeline to
`RPUSH` the non-empty groups. Cross-class append is best-effort and not atomic.
The runtime never decodes `opaqueResultContext`.

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
RedisSeedResultRuntime
  owns class queue encoding, partitioning, and bounded append/consume

ResultRoutingPacer
  owns context decoding and class-local owner-operation composition

TaskRuntime
  owns Task-scoped last-success result payload truth

TaskItemScoreBandCore / WorkerScoreCore
  own all score validation and mutation
```
