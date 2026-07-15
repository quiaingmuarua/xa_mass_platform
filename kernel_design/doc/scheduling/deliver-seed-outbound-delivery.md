# DeliverSeed Outbound Delivery

Status: new-kernel boundary note. This document separates queued DeliverSeed
consumption from assignment-side seed generation. The queue consume primitive
is implemented; transport-side outbound orchestration is not.

Upstream contract: [Task Item Dispatch Pacer](task-item-dispatch-pacer.md).

## Purpose

The outbound owner starts after a `DeliverSeed` has been appended and ends when
transport accepts or rejects that already-assigned delivery:

```text
consume queued DeliverSeed from this endpointManagerId queue
  -> discard if nowMillis >= taskItemClaimUntilMillis
  -> validate or renew opaque Worker lease evidence
  -> resolve final-hop transport evidence for workerId
  -> submit transport delivery
  -> apply Task-owned exclusive/non-exclusive Worker disposition
```

It does not select a Worker, match Task constraints, claim a TaskItem, mutate
Task score, classify result finality, or create another assignment identity.

## Inputs

```text
DeliverSeedRuntime
  consume_deliver_seeds(endpointManagerId, limit)
  atomically pops one bounded batch from one endpointManagerId partition
  Redis 6.0 implementation uses bounded LPOP commands in one transaction pipeline

WorkerScoreCore
  exact opaque Worker-score validation/renew/release primitives

resolved Task Worker-occupancy policy
  EXCLUSIVE or NON_EXCLUSIVE
  exact carrier remains to be frozen

transport ingress
  accepts or rejects delivery for the already selected Worker
```

`workerLeaseScore` is copied from allocation evidence into
`opaqueResultContext`. The platform outbound coordinator may recover that
declared field for exact lease continuation and still forward the original
context unchanged. It never decodes Worker score coordinates; Redis queue and
Worker-facing transport adapters treat the context as opaque.

## Mainline

```text
consume_deliver_seeds(endpointManagerId, limit)
  -> discard each seed where nowMillis >= taskItemClaimUntilMillis
  -> exact Worker lease continuation succeeds
  -> resolve selected Worker delivery target
  -> submit transport

transport accepted + NON_EXCLUSIVE
  -> release exact current Worker lease

transport accepted + EXCLUSIVE
  -> retain or renew Worker lease
  -> carry the opaque current Worker lease score into result-side disposition

transport rejected / resolution failed / outbound process stopped
  -> do not immediately release or reschedule
  -> Worker lease and Item claim recover through their own time coordinates

seed claim cutoff already reached before submit
  -> discard seed and record bounded log / metric
  -> do not synthesize timeout result
  -> do not mutate Item score or eagerly release Worker lease
```

The exact relationship between exclusive Worker lease duration, result arrival,
and result-side release belongs to the later result/outbound interface design.
It must not be pushed backward into `TaskItemDispatchPacer`.

The Redis executable spec implements the bounded LIST pop only. A later
transport-side owner must call it and perform the remaining validation,
resolution, submission, and Worker disposition steps. The queue runtime itself
does not call transport.

## DeliverSeed Is Evidence

DeliverSeed contains:

```text
workerId
opaqueDeliveryItem
opaqueResultContext
taskItemClaimUntilMillis
```

`opaqueDeliveryItem` may be translated by the endpoint manager before Worker
submit. `opaqueResultContext` is forwarded unchanged and contains the Task,
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
- Do not move Worker release/retain decisions into seed generation.
- Do not add immediate compensation loops that bypass bounded score expiry.
