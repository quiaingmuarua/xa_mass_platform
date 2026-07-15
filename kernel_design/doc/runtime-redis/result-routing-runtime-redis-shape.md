# Result-Routing Runtime Redis Shape

Status: current Python executable-spec Redis shape.

## Key

```text
rr:{prefix}:seed-results
  Redis LIST
```

Each member is deterministic JSON:

```json
{
  "opaqueResultContext": "...",
  "outcomeCode": "200",
  "opaqueResultPayload": "... or null"
}
```

The runtime uses one batch `RPUSH` and a bounded transaction pipeline of
`LPOP` commands. FIFO is preserved for valid members. Corrupt members are
consumed and skipped.

There is no `endpointManagerId` partition, pending/ack list, retry list, result
classification, Task/Item lookup, Worker lookup, or cross-key Lua. The LIST is
best-effort evidence; Item claim and Worker lease expiry provide recovery after
loss or process failure.

## Owner Rules

```text
RedisSeedResultRuntime
  owns queue encoding and bounded append/consume only

ResultRoutingPacer
  owns opaque context decoding and owner-operation selection

TaskItemScoreBandCore / WorkerScoreCore
  own all score validation and mutation
```

Do not add result payload projection or durable result history to this key.
