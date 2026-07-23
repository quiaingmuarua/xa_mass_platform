# Worker Delivery Dispatch

Status: active new-kernel boundary contract; Python executable spec and Worker
Adapter polling protocol implemented; production Adapter policy deferred.

Upstream contract: [Task Dispatch Pacer](task-dispatch-pacer.md).
Worker lease contract:
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
Protocol example:
[Worker Adapter Server](../../examples/worker-adapter-server.md).

## Purpose

`DeliverSeedRuntime` is the Adapter-partitioned handoff between Task Dispatch
and Worker Delivery Dispatch:

```text
Task Dispatch
  -> snapshot selected Worker's endpointManagerId
  -> append WorkerId -> DeliverSeed to that Adapter's sparse HASH

Worker Delivery Dispatch
  -> consume only its configured Adapter bucket
  -> discard if nowMillis >= taskItemClaimUntilMillis
  -> submit the already-assigned delivery to seed.workerId
  -> append SeedResult to SeedResultRuntime

Result Routing
  -> converge TaskItem result truth and Worker score
  -> never reads endpointManagerId
```

Task Dispatch has already selected the Worker. `endpointManagerId` selects the
delivery bucket; it does not select, rank, or validate the Worker.

The mailbox invariant is:

```text
one endpointManagerId = one sparse DeliverSeed HASH
one (endpointManagerId, workerId) has at most one unconsumed DeliverSeed
```

If one physical execution runtime supports multiple independent concurrent
slots, it exposes multiple globally unique WorkerIds.

## Runtime Contract

```python
append_deliver_seeds(
    endpoint_manager_id,
    deliver_seeds_by_worker_id,
) -> workerId -> APPENDED | REPLACED

consume_deliver_seed(
    endpoint_manager_id,
    worker_id,
) -> DeliverSeed | None

consume_deliver_seeds(
    endpoint_manager_id,
    cursor,
    scan_count,
) -> DeliverSeedConsumePage
```

`APPENDED` means the Adapter-local Worker field was empty. `REPLACED` means an
existing mailbox residue was overwritten. Both outcomes mean the new Seed is
published.

The append Map key must equal `DeliverSeed.workerId`. Append rejects a Seed
whose claim deadline has already passed. A Task dispatch round validates that
one WorkerId does not produce multiple Seeds, even if inconsistent route
evidence would place them in different Adapter buckets.

Point consume atomically removes one Worker field. Cursor consume scans one
Adapter HASH and returns:

```python
DeliverSeedConsumePage(
    deliver_seeds=(...),
    next_cursor="..." | None,
)
```

`cursor=None` starts at Redis cursor `0`. Redis cursor `0` is returned as
`next_cursor=None`. `scan_count` is an HSCAN work hint, not an exact result
limit; an empty page may still carry a continuation cursor.

## Redis Shape

```text
ad:{prefix}:endpoint-manager:{endpointManagerId}:deliver-seeds
  HASH workerId -> DeliverSeed JSON
```

Append first pipelines native `HSETNX` commands against one Adapter HASH. It
then writes the fields that already existed with one native `HSET mapping`
operation, equivalent to a bounded HMSET. No Lua computes routing, validates a
Worker lease, or parses Seed data.

Replacement is safe because Task Dispatch may construct a Seed only after
exactly acquiring or renewing that Worker lease, and the Seed claim deadline is
the same deadline used for the Worker lease. While that lease is active, the
Worker cannot be acquired for another dispatch. A later valid Seed for the same
Worker therefore implies that the previous lease and previous Seed deadline
have already ended. If the old HASH field still exists, it is unconsumed
delivery residue, not a second valid assignment.

The mailbox runtime trusts this lease-backed caller contract. It does not add a
second Worker-score read, compare deadlines, or create an `OCCUPIED` state.
Observing `REPLACED` may be recorded as lightweight Adapter backlog/residue
evidence, but it is not a scheduling conflict and does not demote the Worker.

Point consume uses minimal single-key `HGET + HDEL` Lua. Cursor consume first
uses `HSCAN`, then passes each scanned raw field/value pair to a minimal
single-key compare-and-delete Lua primitive:

```text
delete field only when currentValue == scannedValue
```

This prevents a concurrent consumer followed by a new append from having the
new Seed deleted by an older scan page. Cross-Adapter operations are not atomic
and completed buckets are not rolled back.

Expired, malformed, or WorkerId-mismatched values are deleted during consume
and not returned. There is no field TTL, expiry index, cleanup scanner,
pending/ack state, or FIFO backlog. A crash after destructive consume is
recovered by TaskItem claim and Worker lease expiry.

## Route Snapshot

`WorkerDescriptor.endpointManagerId` is immutable declaration metadata in the
current slice. `WorkerCandidateMatcher` copies it into
`CandidateWorkerEntry.endpointManagerId` when assignment evidence is created.
PRECOMPUTED candidate cache JSON preserves that value, and TARGETED acquisition
copies it through the same matcher result.

`TaskItemDispatcher` groups constructed Seeds by this snapshot. `DeliverSeed`
itself contains only:

```text
workerId
opaqueDeliveryItem
opaqueResultContext
taskItemClaimUntilMillis
```

Neither `DeliverSeed` nor `ResultContext` carries Adapter information. The
bucket key is sufficient for Worker Delivery Dispatch, while Result Routing
remains Adapter-agnostic.

`endpointManagerId` is not part of the Worker allocation DSL context. Route
placement cannot be used as a Worker matching predicate.

## Worker Adapter Protocol

The Worker Adapter Server starts with one required `endpointManagerId`.

```text
POST /workers/{workerId}/commands:poll
  -> point consume from the configured Adapter bucket
  -> return TASK_SEED or 204

POST /workers/{workerId}/results
  -> accept Worker 200/1xxx
  -> append opaque SeedResult
```

The path WorkerId cannot consume another Adapter's bucket. A local batch
Adapter may instead cursor-scan its configured bucket and demultiplex returned
Seeds by `seed.workerId`.

The Adapter rechecks `taskItemClaimUntilMillis` before submit. A stale Seed is
dropped without synthesizing result evidence or mutating Item/Worker score.
`opaqueResultContext` is forwarded unchanged and must not be parsed by the
Adapter or Worker.

The Adapter-generated command id and message type are private wire protocol
coordinates. They are not Kernel assignment identity and do not enter
DeliverSeed or SeedResult.

## At-Least-Once Boundary

Mailbox consume is destructive. A process crash or lost response after consume
produces unknown delivery evidence. Item claim and Worker lease expiry make the
logical Item dispatchable again, so Worker execution remains at-least-once.

TaskItem score monotonicity, last-success storage, and exact Worker lease fences
make stale result evidence converge safely inside the Kernel. They do not
provide exactly-once external side effects.

## Migration Boundary

Worker migration is not implemented by ordinary upsert. A future controlled
migration command must wait for or hold a non-active Worker lease before
changing the immutable route declaration.

A Seed already written to an old Adapter bucket is not moved. If a later
dispatch uses a new route, the two Seeds are different attempts with different
Worker lease fences. Result Routing accepts either source without understanding
Adapter identity.

## Deferred Policy

- Controlled Worker route migration.
- Occupied-mailbox replacement or Worker serviceability penalties.
- Production authentication, session ownership, and Adapter authorization.
- Pending/ack reliability or mailbox expiry cleanup.
- Proactive unreachable-Worker recovery evidence.

## Guardrails

- Do not let Worker Delivery Dispatch choose or replace `seed.workerId`.
- Do not put endpointManagerId into DeliverSeed or ResultContext.
- Do not expose endpointManagerId to Worker allocation rules.
- Do not let an Adapter consume another Adapter's bucket.
- Do not parse or reconstruct Worker score fences in the Adapter.
- Do not emit timeout evidence for a Seed discarded before Worker submit.
- Do not move Worker release/recovery decisions into mailbox consumption.
- Do not reinterpret a replaced mailbox residue as a second active assignment.
- Do not add cross-bucket rollback, cleanup scanners, or compatibility keys.
