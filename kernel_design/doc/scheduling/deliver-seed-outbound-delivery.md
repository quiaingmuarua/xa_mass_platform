# DeliverSeed Outbound Delivery

Status: active new-kernel boundary contract; Python executable spec and Local
Function Adapter implemented; production transport policy deferred.

Upstream contract: [Task Dispatch Pacer](task-dispatch-pacer.md).
Worker lease contract:
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
External process contract:
[Local Function Transport Adapter](../../examples/local_function_adapter/README.md).

## Purpose

The outbound owner starts after a `DeliverSeed` has been appended and ends when
transport accepts or rejects that already-assigned delivery:

```text
consume queued DeliverSeed from this endpointManagerId queue
  -> discard if nowMillis >= taskItemClaimUntilMillis
  -> resolve workerId in the endpoint-manager-local delivery registry
  -> submit already-assigned delivery
  -> append SeedResult to the kernel SeedResultRuntime
```

It does not select a Worker, match Task constraints, claim a TaskItem, mutate
Task score, classify result finality, or create another assignment identity.

## Inputs

```text
DeliverSeedRuntime
  consume_deliver_seeds(endpointManagerId, limit)
  atomically pops one bounded batch from one endpointManagerId partition
  Redis 6.0 implementation uses bounded LPOP commands in one transaction pipeline

external DeliverSeed consumer client
  stable bounded queue-consume protocol for one endpointManagerId

external SeedResult command client
  forwards append_seed_results to the kernel SeedResultRuntime
  carries no endpointManagerId or outcome-class argument; runtime derives the
  class queue from each SeedResult outcomeCode
```

Target `workerLeaseScore` is the fence confirmed by dispatch-time Worker-owner
retention and copied into `opaqueResultContext`; the current partial executable
spec still copies the allocation fence directly. External adapters and Redis
queue runtime treat the context as opaque. Result routing recovers declared
correlation and requests exact release from the Worker owner.

## Mainline

```text
consume_deliver_seeds(endpointManagerId, limit)
  -> discard each seed where nowMillis >= taskItemClaimUntilMillis
  -> resolve selected Worker in endpoint-manager-local state
  -> submit transport

Worker result appended to SeedResultRuntime
  -> runtime routes by SUCCESS / WORKER_FAILURE / ADAPTER_REJECTION
  -> ResultRoutingPacer bounded-consumes, decodes, groups, and delegates
  -> selected Task handler stores success truth and finalizes the TaskItem
  -> selected Worker handler requests exact release or recovery demotion

transport rejected / resolution failed / outbound process stopped
  -> do not immediately release or reschedule
  -> Worker lease and Item claim recover through their own time coordinates

seed claim cutoff already reached before submit
  -> discard seed and record bounded log / metric
  -> do not synthesize timeout result
  -> do not mutate Item score or eagerly release Worker lease
```

The exact relationship between allocation lease, dispatch disposition,
result-side release, and natural expiry is defined by the canonical Worker
lease protocol. The external outbound process begins after assignment-side
DeliverSeed acceptance and must not reinterpret or mutate that disposition.

`DeliverSeedConsumerClient` performs the independent queue read and
`SeedResultCommandClient` appends a mixed result batch; SeedResultRuntime routes
each member to its outcome-class queue. The
Local Function Adapter proves local resolution, handler execution, deterministic
payload encoding, and one result append per bounded drain. The DeliverSeed
queue runtime itself still does not call transport.

## DeliverSeed Is Evidence

DeliverSeed contains:

```text
workerId
opaqueDeliveryItem
opaqueResultContext
taskItemClaimUntilMillis
```

`opaqueDeliveryItem` is produced by the assignment-dispatch internal encoder.
The built-in policy serializes only `eventCode` and `payload`; it does not expose
message identity, Item score, retry budget, expiry, or Worker lease evidence to
the Worker handler. A payload reference or bounded business batch, when needed
by an application, is ordinary caller-defined data inside this one Item
payload. The endpoint manager may translate the opaque item before Worker
submit. The kernel does not merge multiple TaskItems into one delivery item.

`opaqueResultContext` is forwarded unchanged and contains the Task, Item,
Worker, WorkerGroup home-bucket coordinate, and Worker-lease correlation
required by later result routing. Item claim score remains internal to the
TaskItem score owner. Worker-facing transport adapters and Redis queue runtime
do not parse the context.

`taskItemClaimUntilMillis` is only a fast stale-seed cutoff. The queue may be
lost or replayed without becoming the correctness owner: Item claim expiry and
Worker lease expiry remain the liveness fallback.

`endpointManagerId` is copied from matching through `CandidateWorkerEntry` and
partitions the Deliver Queue key, but is not duplicated in DeliverSeed. The
outbound owner therefore consumes only its own manager queue and does not
reread `WorkerDescriptor` to choose a queue.
Transport-specific route, adapter, mailbox, connection, or session facts are
resolved inside that endpoint manager and never written back into scheduling
candidate truth.

## Deferred Policy

- Production transports choose their protocol-specific delivery-item
  conversion and endpoint-local retry behavior.
- Pending/ack reliability requires a named outbound invariant; it is not added
  to the best-effort queue by default.
- Result payload projection and Worker disposition remain downstream owner
  concerns, not adapter extensions.

## Guardrails

- Do not let outbound delivery choose or replace the selected Worker.
- Do not let transport acceptance become Item result finality.
- Do not mutate Task score because delivery succeeds or fails.
- Do not reconstruct or decode `workerLeaseScore`.
- Do not emit a timeout result for a seed discarded before Worker submit.
- Do not downgrade a real late Worker result to diagnostics-only handling.
- Do not move Worker release/retain decisions into outbound queue consumption
  or the external adapter; assignment-side accepted-Seed disposition is already
  complete before this boundary.
- Do not add immediate compensation loops that bypass bounded score expiry.
