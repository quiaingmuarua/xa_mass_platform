# DeliverSeed Outbound Delivery

Status: current Python executable-spec boundary. The Redis queue, external
clients, and Local Function Adapter are implemented.

Upstream contract: [Task Item Dispatch Pacer](task-item-dispatch-pacer.md).
External process contract:
[Local Function Transport Adapter](../../examples/local_function_adapter/README.md).

## Purpose

The outbound owner starts after a `DeliverSeed` has been appended and ends when
transport accepts or rejects that already-assigned delivery:

```text
consume queued DeliverSeed from this endpointManagerId queue
  -> discard if nowMillis >= taskItemClaimUntilMillis
  -> resolve workerId in endpoint-manager-local reachability truth
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
  carries no endpointManagerId because results enter one logical queue
```

`workerLeaseScore` is copied from allocation evidence into
`opaqueResultContext`. External adapters and Redis queue runtime treat the
context as opaque. Result routing recovers declared correlation and coordinates
the accepted-result release/retention handoff to the Worker owner.

## Mainline

```text
consume_deliver_seeds(endpointManagerId, limit)
  -> discard each seed where nowMillis >= taskItemClaimUntilMillis
  -> resolve selected Worker in endpoint-manager-local state
  -> submit transport

Worker result appended to SeedResultRuntime
  -> ResultRoutingPacer bounded-consumes the unified queue
  -> Result-Routing Scheduling applies TaskItem outcome
  -> Worker owner receives release / retain / capacity handoff

transport rejected / resolution failed / outbound process stopped
  -> do not immediately release or reschedule
  -> Worker lease and Item claim recover through their own time coordinates

seed claim cutoff already reached before submit
  -> discard seed and record bounded log / metric
  -> do not synthesize timeout result
  -> do not mutate Item score or eagerly release Worker lease
```

The exact relationship between Worker lease duration, result arrival, and
result-side release belongs to Result-Routing Scheduling and the Worker-owner
handoff. It must not be pushed into the external adapter or backward into
`TaskItemDispatchPacer`.

`DeliverSeedConsumerClient` performs the independent queue read and
`SeedResultCommandClient` appends outcomes to the unified result queue. The
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
message identity, Item scheduling fields, expiry, or score evidence to the
Worker handler. A payload reference, when needed by an application, is ordinary
caller-defined data inside `payload`. The endpoint manager may translate the
opaque item before Worker submit.

`opaqueResultContext` is forwarded unchanged and contains the Task,
Item, Worker, claim-score, and Worker-lease correlation required by later
result routing. Worker-facing transport adapters and Redis queue runtime do not
parse it.

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

## Guardrails

- Do not let outbound delivery choose or replace the selected Worker.
- Do not let transport acceptance become Item result finality.
- Do not mutate Task score because delivery succeeds or fails.
- Do not reconstruct or decode `claimScore` or `workerLeaseScore`.
- Do not emit a timeout result for a seed discarded before Worker submit.
- Do not downgrade a real late Worker result to diagnostics-only handling.
- Do not move Worker release/retain decisions into seed generation or the
  external adapter.
- Do not add immediate compensation loops that bypass bounded score expiry.
