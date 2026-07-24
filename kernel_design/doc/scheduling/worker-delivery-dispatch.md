# Worker Delivery Dispatch

Status: active new-kernel boundary contract; Python protocol, Redis mailbox,
polling Adapter, and phone-tool Worker implemented; production Adapter policy
deferred.

Upstream contract: [Task Dispatch Pacer](task-dispatch-pacer.md).
Worker lease contract:
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
Protocol example:
[Worker Adapter Server](../../examples/worker-adapter-server.md).

## Purpose

Worker Delivery Dispatch is the transport-neutral boundary after assignment:

```text
Task Dispatch
  -> DeliverSeed
  -> WorkerCommandEnvelope
  -> endpointManagerId-partitioned WorkerCommand mailbox

Worker Adapter
  -> consume its mailbox
  -> forward WorkerCommandEnvelope unchanged
  -> accept one semantic SeedResult

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

`DeliverSeed` is assignment handoff data. It remains opaque to the Adapter.
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

The Worker Adapter starts with one configured `endpointManagerId`:

```text
POST /workers/{workerId}/commands:poll
  -> point consume WorkerCommandEnvelope
  -> recheck executeBeforeMillis
  -> return the same commandId, messageType, deadline, and opaqueItem

POST /workers/{workerId}/results
  -> accept SeedResult fields directly
  -> append through SeedResultCommandClient
  -> allow Worker-owned 200 or 1xxx evidence
```

The Adapter does not generate `commandId`, define `messageType`, decode
`DeliverSeed`, or parse `opaqueResultContext`. A Java WebSocket Adapter and the
Python polling Adapter can therefore implement the same command and SeedResult
contracts without creating transport-specific variants.

The Worker decodes `DeliverSeed`, verifies `seed.workerId` matches its identity,
executes `opaqueDeliveryItem`, and copies `opaqueResultContext` into
`SeedResult`. It copies only the original `commandId`; `messageType` and
`executeBeforeMillis` are command-side coordinates.

`3xxx` remains reserved for trusted Adapter rejection evidence. The current
polling Worker cannot submit it, and this slice adds no Adapter rejection API.

A production polling Adapter may cursor-consume commands into a bounded
process-local `workerId -> command` buffer. Push or WebSocket delivery is
another transport profile over the same command envelope. Buffer size, session
ownership, and push policy do not change Kernel contracts.

## At-Least-Once Boundary

Mailbox consume is destructive. Adapter failure or response loss after consume
creates unknown delivery evidence. Item claim and Worker lease expiry make the
logical Item dispatchable again, so Worker execution remains at-least-once.

TaskItem score monotonicity, last-success storage, and exact Worker lease
fences converge stale result evidence inside the Kernel. They do not make
external Worker side effects exactly-once.

## Deferred Policy

- Java WebSocket Adapter implementation.
- Authentication, Worker session identity, and Adapter authorization.
- Controlled Worker route migration.
- Production polling buffer bounds and cursor cadence.
- Trusted Adapter rejection ingress.
- Pending/ack reliability or mailbox expiry cleanup.
- Proactive unreachable-Worker recovery evidence.

## Guardrails

- Do not let Worker Delivery Dispatch choose or replace `seed.workerId`.
- Do not put `endpointManagerId` into protocol envelopes or ResultContext.
- Do not let an Adapter consume another Adapter's bucket.
- Do not let Adapters generate command identity or message types.
- Do not wrap SeedResult in WorkerCommandEnvelope or add a generic result
  message dispatcher; a future Worker evidence family needs its own semantic
  DTO and owner runtime.
- Do not parse or reconstruct Worker score fences in an Adapter.
- Do not emit timeout evidence for a command discarded before Worker submit.
- Do not move Worker release/recovery decisions into mailbox consumption.
- Do not claim mailbox replacement is ordered by Worker lease recency.
- Do not add cross-bucket rollback, cleanup scanners, or compatibility keys.
