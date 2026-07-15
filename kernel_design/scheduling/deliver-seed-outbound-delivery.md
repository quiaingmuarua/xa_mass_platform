# DeliverSeed Outbound Delivery

Status: new-kernel boundary note. This document separates queued DeliverSeed
consumption from assignment-side seed generation. Its concrete Python interface
is not frozen yet.

Upstream contract: [Task Item Dispatch Pacer](task-item-dispatch-pacer.md).

## Purpose

The outbound owner starts after a `DeliverSeed` has been appended and ends when
transport accepts or rejects that already-assigned delivery:

```text
consume queued DeliverSeed from this endpointManagerId queue
  -> validate or renew opaque Worker lease evidence
  -> resolve final-hop transport evidence for selectedWorkerId
  -> submit transport delivery
  -> apply Task-owned exclusive/non-exclusive Worker disposition
```

It does not select a Worker, match Task constraints, claim a TaskItem, mutate
Task score, classify result finality, or create another assignment identity.

## Inputs

```text
DeliverSeedQueue
  bounded queued seed consumption for one endpointManagerId partition

WorkerScoreCore
  exact opaque Worker-score validation/renew/release primitives

resolved Task Worker-occupancy policy
  EXCLUSIVE or NON_EXCLUSIVE
  exact carrier remains to be frozen

transport ingress
  accepts or rejects delivery for the already selected Worker
```

`workerLeaseScore` is copied from allocation evidence through DeliverSeed. The
outbound owner may return a renewed opaque score, but it never decodes Worker
score coordinates.

## Mainline

```text
consume DeliverSeed
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
```

The exact relationship between exclusive Worker lease duration, result arrival,
and result-side release belongs to the later result/outbound interface design.
It must not be pushed backward into `TaskItemDispatchPacer`.

## DeliverSeed Is Evidence

DeliverSeed contains:

```text
taskId
selectedWorkerId
workerGroupId
endpointManagerId
TaskItem
claimScore
workerLeaseScore
```

The two scores are opaque fences, not duplicated owner truth. The queue may be
lost or replayed without becoming the correctness owner: Item claim expiry and
Worker lease expiry remain the liveness fallback.

`endpointManagerId` is copied from matching through `CandidateWorkerEntry` and
partitions the Deliver Queue. The outbound owner therefore consumes only its
own manager queue and does not reread `WorkerDescriptor` to choose a queue.
Transport-specific route, adapter, mailbox, connection, or session facts are
resolved inside that endpoint manager and never written back into scheduling
candidate truth.

## Guardrails

- Do not let outbound delivery choose or replace the selected Worker.
- Do not let transport acceptance become Item result finality.
- Do not mutate Task score because delivery succeeds or fails.
- Do not reconstruct or decode `claimScore` or `workerLeaseScore`.
- Do not move Worker release/retain decisions into seed generation.
- Do not add immediate compensation loops that bypass bounded score expiry.
