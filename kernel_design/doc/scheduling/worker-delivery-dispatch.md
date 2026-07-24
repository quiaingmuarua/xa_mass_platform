# Worker Delivery Dispatch

Status: active new-kernel boundary contract; Python executable spec, Worker
Adapter polling protocol, and phone-tool Worker implemented; production
Adapter policy deferred.

Upstream contract: [Task Dispatch Pacer](task-dispatch-pacer.md).
Worker lease contract:
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
Protocol example:
[Worker Adapter Server](../../examples/worker-adapter-server.md).
Worker example:
[Polling Phone Worker](../../examples/polling_phone_worker.py).

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
existing mailbox field was overwritten. Both outcomes mean the caller's Seed
is visible when the append returns. `REPLACED` is a best-effort mailbox
visibility outcome, not a Worker lease comparison or a second assignment
state.

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
then writes fields that already existed with one native `HSET mapping`
operation, equivalent to a bounded HMSET. No Lua computes routing, validates a
Worker lease, orders attempts, or parses Seed data.

The normal scheduling path expects an existing field to be unconsumed residue:
Task Dispatch can construct a Seed only after exactly acquiring or renewing
the Worker lease, and one WorkerId cannot hold two current allocation leases.
The mailbox therefore does not model `OCCUPIED` as a second active assignment
state and does not read Worker score before replacement.

The two Redis writes are deliberately not an attempt-order fence. A delayed
publisher can pass its deadline check, pause, and later overwrite a Seed from a
newer lease after its own lease has expired. In that bounded race, Redis shows
the last mailbox write rather than the lease-newest attempt. The stale Seed
cannot change TaskItem or Worker truth: pre-submit deadline filtering, result
fences, and claim/lease expiry preserve correctness, although delivery may wait
for expiry and retry. `REPLACED` is therefore useful lightweight residue or
backlog evidence, but it is neither proof of a scheduling conflict nor a reason
to demote the Worker.

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

The current executable Worker Adapter performs one point consume for each
Worker HTTP poll. A production low-frequency polling profile may instead run
one bounded cursor-consume loop per Adapter, place consumed commands in a
bounded process-local `workerId -> command` buffer, and serve Worker polls from
that buffer. This avoids one Redis round trip per short Worker poll without
changing the Kernel contracts. It remains deferred policy: destructive
prefetch followed by Adapter failure is still `UNKNOWN`, so claim and Worker
lease expiry remain the fallback. Push/WebSocket delivery is a separate
Adapter transport profile over the same DeliverSeed and SeedResult contracts.

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
- Worker serviceability penalties based on bounded mailbox residue evidence.
- Production polling buffer bounds, cursor cadence, authentication, session
  ownership, and Adapter authorization.
- Push/WebSocket transport policy.
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
- Do not claim that a mailbox replacement is ordered by Worker lease recency.
- Do not add cross-bucket rollback, cleanup scanners, or compatibility keys.
