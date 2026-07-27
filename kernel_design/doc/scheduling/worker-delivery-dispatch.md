# Worker Delivery Dispatch

Status: active new-kernel boundary contract; Python protocol/Redis oracle,
Java point Gateway and WebSocket Adapter, and phone-tool Worker implemented;
production authentication and multi-instance policy deferred.

Upstream contract: [Task Dispatch Pacer](task-dispatch-pacer.md).
Worker lease contract:
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
Executable HTTP host:
[JVM Runtime API Server](../../../server_jvm/README.md).

## Purpose

Worker Delivery Dispatch is the transport-neutral boundary after assignment:

```text
Task Dispatch
  -> DeliverSeed
  -> WorkerCommandEnvelope
  -> endpointManagerId-partitioned WorkerCommand mailbox

Worker Delivery Gateway
  -> point-consume one target Worker for request-driven polling
  -> cursor-consume one configured WebSocket Adapter mailbox
  -> accept Worker point results or Adapter result batches

Worker
  -> decode the command's opaque DeliverSeed
  -> execute the selected Item on the selected WorkerId
  -> return SeedResult with the original commandId

Result Routing
  -> consume SeedResult
  -> converge TaskItem result truth and Worker score
```

Task Dispatch has already selected the Worker. `endpointManagerId` selects one
Adapter mailbox bucket; it does not select, rank, or validate the Worker.

## Protocol Layers

The Kernel defines one assignment handoff, one outbound command envelope, and
one semantic result:

```python
DeliverSeed(
    worker_id,
    opaque_delivery_item,
    opaque_result_context,
)

WorkerCommandEnvelope(
    command_id,
    message_type=TASK_ITEM,
    execute_before_millis,
    opaque_item=encode_deliver_seed(seed),
)

SeedResult(
    command_id=command.command_id,
    opaque_result_context=seed.opaque_result_context,
    outcome_code,
    opaque_result_payload,
)
```

`DeliverSeed` is assignment handoff data. It remains opaque to Adapter session
and pump code. `WorkerDeliveryService` may strictly decode it only when it must
construct trusted pre-execution Adapter rejection evidence.
`WorkerCommandEnvelope` is the stable cross-Adapter and cross-language outbound
command DTO. `SeedResult` is the independent Result Routing evidence DTO.
These types are deliberately asymmetric because command mailboxes are
partitioned by Adapter route while result queues are partitioned by semantic
outcome class.

All codecs use deterministic compact JSON with camelCase fields and no Base64.
Decoders reject missing, extra, or incorrectly typed fields. `commandId` is a
canonical UUID generated after exact Item claim and copied into `SeedResult`.
It is only trace and command/result correlation data; it is not TaskItem
`messageId`, an idempotency key, a lease fence, or result truth.

`executeBeforeMillis` means the Worker must not begin execution at or after the
deadline. It does not require execution to finish before that time. The
deadline belongs only to the outbound Worker command because every Adapter must
enforce it before submit. `SeedResult` does not echo or reinterpret the
deadline, and `DeliverSeed` does not carry a second deadline.

The only current `WorkerMessageType` is `TASK_ITEM`. It identifies how a Worker
interprets the command's `opaqueItem`; Result Routing does not consume or
interpret Worker message types.

## Runtime Contract

```python
append_worker_commands(
    endpoint_manager_id,
    worker_commands_by_worker_id,
) -> workerId -> APPENDED | REPLACED

consume_worker_command(
    endpoint_manager_id,
    worker_id,
) -> WorkerCommandEnvelope | None

consume_worker_commands(
    endpoint_manager_id,
    cursor,
    scan_count,
) -> WorkerCommandConsumePage
```

The mailbox invariant is:

```text
one endpointManagerId = one sparse WorkerCommand HASH
one (endpointManagerId, workerId) has at most one unconsumed command
```

If one physical execution runtime supports multiple independent concurrent
slots, it exposes multiple globally unique WorkerIds.

Point consume atomically removes one Worker field. Cursor consume returns:

```python
WorkerCommandConsumePage(
    worker_commands_by_worker_id={...},
    next_cursor="..." | None,
)
```

The Map preserves the Redis HASH field used for Worker demultiplexing, so a
batch Adapter never parses `opaqueItem` to discover the target Worker.
`cursor=None` starts at Redis cursor `0`; a returned cursor of `0` becomes
`None`. `scanCount` is an HSCAN work hint, not an exact result count.

## Redis Shape

```text
wd:{prefix}:endpoint-manager:{endpointManagerId}:worker-commands
  HASH workerId -> WorkerCommandEnvelope JSON
```

Candidate cache remains under the `ad:` owner. Old DeliverSeed mailbox keys and
values are not read or migrated.

Append rejects an outer command whose `executeBeforeMillis` has passed. It
pipelines native `HSETNX`, then replaces existing residue with one native
`HSET mapping` operation. Redis does not parse `opaqueItem`, compute Adapter
routing, validate a Worker lease, or order attempts.

The normal path expects replacement to mean unconsumed residue: Task Dispatch
can construct a command only after exact Worker lease acquisition or renewal,
and one WorkerId cannot hold two current allocation leases. Replacement is
therefore safe for score truth and is not a second active assignment state.

The two Redis writes are deliberately not an attempt-order fence. A delayed
publisher can still overwrite a newer command after its own lease expires.
That bounded race may delay useful delivery, but the outer execution deadline,
Item claim, Worker lease fence, and Result Routing keep stale evidence from
changing current truth. `REPLACED` is lightweight residue evidence, not proof
of Worker failure or lease recency.

Point consume uses minimal single-key `HGET + HDEL` Lua. Cursor consume uses
HSCAN followed by a minimal compare-and-delete Lua operation:

```text
delete field only when currentValue == scannedValue
```

This prevents an old scan page from deleting a command appended after a
concurrent consume. Cross-Adapter operations are not atomic and completed
buckets are not rolled back.

Expired or malformed outer commands are deleted during consume and not
returned. The runtime intentionally does not decode the inner DeliverSeed.
There is no field TTL, expiry index, cleanup scanner, pending/ack state, or FIFO
backlog.

## Route Snapshot

`WorkerDescriptor.endpointManagerId` is immutable declaration metadata in this
slice. `WorkerCandidateMatcher` copies it into
`CandidateWorkerEntry.endpointManagerId`. PRECOMPUTED cache and TARGETED
acquisition preserve the same snapshot.

`TaskItemDispatcher` groups commands by this value. Neither `DeliverSeed`,
`WorkerCommandEnvelope`, `ResultContext`, nor `SeedResult` carries Adapter
identity. `endpointManagerId` is not available to the Worker allocation DSL.

## Adapter And Worker

The Java Runtime API Server exposes the Worker Delivery Gateway. Pure polling
Workers bind to:

```text
endpointManagerId = system-polling
```

This is a fixed logical route identity, not an independently deployed Adapter,
session owner, thread, or runtime truth.

Start Python scheduling and the Java external server:

```text
python -m kernel_design.runtime_server
./gradlew :server_jvm:bootRun
```

The Worker Delivery address is `127.0.0.1:18082`. Task/resource controllers
still call the Python server at `127.0.0.1:18080`; Worker Delivery directly
uses only the command-mailbox and result-queue Redis shapes.

Point polling is always Worker-specific:

```text
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}/commands:poll
  -> point consume WorkerCommandEnvelope
  -> recheck executeBeforeMillis
  -> return the same commandId, messageType, deadline, and opaqueItem
  -> return 204 when the field is empty or the command is expired

POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/
     workers/{workerId}/results
  -> accept SeedResult fields directly
  -> Java Gateway appends the classified SeedResult queue entry
  -> allow Worker-owned 200 or 1xxx evidence
```

The polling API accepts no cursor, scan count, Worker list, or fallback source.
It is request-driven and naturally applies Worker-side backpressure.

A long-lived Adapter uses separate role-specific endpoints:

```text
POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/commands:consume
  -> cursor-consume a sparse mailbox page

POST /api/v1/worker-delivery/endpoint-managers/{endpointManagerId}/results:append
  -> append one non-empty 200/1xxx/3xxx SeedResult batch
```

The built-in `system-polling` identity is rejected from both Adapter batch
operations. Cursor scanning is not a polling Worker capability.

The cursor request and response are:

```json
{"cursor": null, "scanCount": 100}
```

```json
{
  "workerCommandsByWorkerId": {
    "worker-1": {
      "commandId": "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
      "executeBeforeMillis": 1234567890,
      "messageType": "TASK_ITEM",
      "opaqueItem": "..."
    }
  },
  "nextCursor": "7"
}
```

Point and cursor consumers mechanically compete for the same Worker field, but
an endpoint-manager identity configured for the Java WebSocket Adapter is
reserved from HTTP point and batch access. `system-polling` remains point-only.

The Java Gateway does not generate `commandId` or define `messageType`.
WebSocket session and pump code do not decode `DeliverSeed` or parse
`opaqueResultContext`; only `WorkerDeliveryService` decodes the inner seed to
construct trusted `3001` when a consumed command has no current target
session. Java may read/delete WorkerCommand fields and append SeedResult queue
entries; it must not append commands, consume results, or access score/Pacer
state.

The Worker decodes `DeliverSeed`, verifies `seed.workerId` matches its identity,
executes `opaqueDeliveryItem`, and copies `opaqueResultContext` into
`SeedResult`. It copies only the original `commandId`; `messageType` and
`executeBeforeMillis` are command-side coordinates.

`3xxx` remains reserved for trusted Adapter rejection evidence. A polling
Worker cannot submit it; the Adapter batch endpoint is its protocol ingress.
Authentication of that Adapter role is deferred.

The Java WebSocket Adapter exposes:

```text
GET /api/v1/worker-delivery/websocket/workers/{workerId}
```

One JVM instance owns one configured non-`system-polling` endpoint-manager
mailbox. A Worker first performs its existing upsert, then connects. One
WorkerId has one current session; a newer connection replaces the old session
without allowing the old close callback to remove the new generation.

The Adapter cursor-consumes one bounded page, rechecks the command deadline,
and forwards the exact WorkerCommand JSON. Exact Worker result JSON is accepted
only for `200/1xxx`, buffered in bounded process memory, and batch-appended to
the existing result queues. There is no command or result ACK. Redis failure
retains one pending batch for retry; JVM failure may lose buffered results.

No current session is direct evidence that the command did not enter the
Worker and generates `3001`. Expiry, disconnect, missing result, or a failure
after send was attempted remains unknown and cannot generate `3xxx`.

## At-Least-Once Boundary

Mailbox consume is destructive. Adapter failure or response loss after consume
creates unknown delivery evidence. Item claim and Worker lease expiry make the
logical Item dispatchable again, so Worker execution remains at-least-once.

TaskItem score monotonicity, last-success storage, and exact Worker lease
fences converge stale result evidence inside the Kernel. They do not make
external Worker side effects exactly-once.

## Deferred Policy

- Authentication, Worker session identity, and Adapter authorization.
- Same-endpoint multi-instance ownership and automatic failover.
- Controlled Worker route migration.
- Pending/ack reliability or mailbox expiry cleanup.
- Proactive unreachable-Worker recovery evidence.

## Guardrails

- Do not let Worker Delivery Dispatch choose or replace `seed.workerId`.
- Do not put `endpointManagerId` into protocol envelopes or ResultContext.
- Do not let an Adapter consume another Adapter's bucket.
- Do not expose cursor scanning through the polling Worker API.
- Do not allow the `system-polling` identity to use Adapter batch operations.
- Do not let Adapters generate command identity or message types.
- Do not wrap SeedResult in WorkerCommandEnvelope or add a generic result
  message dispatcher; a future Worker evidence family needs its own semantic
  DTO and owner runtime.
- Do not parse or reconstruct Worker score fences in an Adapter.
- Do not emit timeout evidence for a command discarded before Worker submit.
- Do not move Worker release/recovery decisions into mailbox consumption.
- Do not claim mailbox replacement is ordered by Worker lease recency.
- Do not add cross-bucket rollback, cleanup scanners, or compatibility keys.
