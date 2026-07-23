# Worker Delivery Dispatch

Status: active new-kernel boundary contract; Python executable spec and Worker
Adapter polling protocol implemented; production Adapter policy deferred.

Upstream contract: [Task Dispatch Pacer](task-dispatch-pacer.md).
Worker lease contract:
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
Protocol example:
[Worker Adapter Server](../../examples/worker-adapter-server.md).

## Purpose

`DeliverSeedRuntime` is the Worker-addressed handoff between Task Dispatch and
Worker Delivery Dispatch:

```text
Task dispatch
  -> append one DeliverSeed to the selected WorkerId mailbox
  -> Worker Delivery Dispatch consumes by WorkerId
  -> discard if nowMillis >= taskItemClaimUntilMillis
  -> submit the already-assigned delivery
  -> append SeedResult to SeedResultRuntime
```

It does not select a Worker, match Task constraints, claim a TaskItem, mutate
Task score, classify result finality, or create another assignment identity.

The owner boundary starts after Task Dispatch handles the mailbox append result
and ends after a valid `SeedResult` is accepted by `SeedResultRuntime`. It owns
mailbox consume, deadline validation, Worker Adapter command/result conversion,
and result ingress only.

The core mailbox invariant is:

```text
one WorkerId = one logical execution slot
one WorkerId has at most one unconsumed DeliverSeed
```

If one physical execution runtime supports multiple independent concurrent
slots, it exposes multiple globally unique WorkerIds.

## Runtime Contract

```text
append_deliver_seeds(workerIdToDeliverSeed)
  -> workerId -> APPENDED | OCCUPIED

consume_deliver_seeds(workerIds)
  -> workerId -> DeliverSeed
```

Append status means:

- `APPENDED`: the Worker mailbox was empty.
- `OCCUPIED`: a value already exists for that WorkerId; append never reads,
  compares, or overwrites it.

The append Map key must equal `DeliverSeed.workerId`; Map shape makes duplicate
WorkerIds impossible. Append rejects a Seed whose claim deadline has already
passed. Consume is bounded by the explicit WorkerId input; there is no
independent `limit` and no endpoint-manager partition coordinate.

The Redis shape is fixed for this executable spec:

```text
shard = CRC32(UTF-8(workerId)) % 64

ad:{prefix}:deliver-seeds:{00..63}
  HASH workerId -> DeliverSeed JSON
```

Append computes each shard in `RedisDeliverSeedRuntime` and pipelines native
`HSETNX` commands. Every Worker field is independent; batches spanning shards
are best-effort and are not atomic. Lua is not used for append.

Consume uses a minimal single-key `HGET + HDEL` Lua primitive for each requested
shard, so two consumers racing for one WorkerId cannot both receive the same
Seed. An expired, malformed, or WorkerId-mismatched stored value is deleted and
not returned.

There is no field TTL, expiry index, cleanup scanner, pending/ack state, or FIFO
backlog. Even an expired or malformed field remains occupied until a consumer
requests and removes it. A process crash after destructive consume is recovered
by TaskItem claim and Worker lease expiry, not by a second DeliverSeed
reliability owner.

## Mainline

```text
polling Worker
  -> POST /workers/{workerId}/commands:poll
  -> Worker Adapter consumes one WorkerId mailbox
  -> Adapter rechecks claim deadline
  -> Adapter returns TASK_SEED command envelope
  -> Worker executes opaqueDeliveryItem
  -> Worker POSTs TASK_SEED_RESULT with 200 or 1xxx
  -> Adapter appends opaque SeedResult

Worker result appended to SeedResultRuntime
  -> runtime routes by SUCCESS / WORKER_FAILURE / ADAPTER_REJECTION
  -> ResultRoutingPacer delegates Task and Worker evidence
```

The Worker Adapter must request only WorkerIds it is authorized to serve. The
current protocol example has no authentication slice, so the path WorkerId is
accepted as the requested mailbox address. An unpolled Worker produces no
immediate rejection evidence: its mailbox remains until a consumer removes it.
Item claim and Worker lease expiry still restore scheduling liveness, but later
append attempts observe `OCCUPIED` and do not displace that delivery evidence.

Task dispatch counts only `APPENDED` results as published. `OCCUPIED` is not a
program error and does not trigger compensation or cross-Worker rollback.

## DeliverSeed Is Evidence

DeliverSeed contains:

```text
workerId
opaqueDeliveryItem
opaqueResultContext
taskItemClaimUntilMillis
```

`workerId` is both the selected logical execution slot and the mailbox address.
`workerGroupId` remains inside opaque result correlation because it identifies
the Worker score/catalog home bucket; it is not a delivery address.

`endpointManagerId` is not copied into `CandidateWorkerEntry`, `DeliverSeed`,
mailbox keys, or consumer calls. It remains Worker declaration metadata in this
first slice but Task Dispatch and Worker Delivery Dispatch do not read it.

`opaqueDeliveryItem` is produced by the assignment-dispatch internal encoder.
The built-in policy serializes only `eventCode` and `payload`; it does not expose
message identity, Item score, retry budget, expiry, or Worker lease evidence to
the Worker handler. The Worker Adapter translates this opaque item into its
private command before submit. The kernel does not merge multiple TaskItems
into one delivery item.

`opaqueResultContext` is forwarded unchanged. It contains Task, Item, Worker,
WorkerGroup home-bucket, and Worker-lease correlation for result routing.
The Worker Adapter, Worker, and mailbox runtime must not parse it.

`taskItemClaimUntilMillis` is a fast pre-submit stale cutoff. A stale Seed is
dropped without synthesizing timeout result evidence or mutating Item/Worker
score.

## Worker Adapter Protocol

`DeliverSeed` and `SeedResult` are the complete kernel contracts across the
Worker Delivery Dispatch boundary. The Worker Adapter wraps a consumed Seed in
`TASK_SEED` with an Adapter-generated command id. A Worker replies with
`TASK_SEED_RESULT` and echoes that command id. These fields are wire correlation
only; they are not persisted, validated as score fences, or promoted into
kernel assignment identity.

Polling Workers may report only `200` or `1xxx`. They cannot forge Adapter-owned
`3xxx` rejection evidence. A lost HTTP response, expired Seed, or missing
result remains unknown and falls back to Item claim and Worker lease expiry.

The current HTTP slice has no login, session, or authorization protocol.
`workerId` in the path is therefore a direct mailbox coordinate, not verified
Worker identity. A future cohesive Worker-facing API may establish a trusted
session before poll/result, but that session must remain private to Worker
Delivery Dispatch and must not enter DeliverSeed, SeedResult, or scheduling
truth.

Worker Adapter route, connection, session, or pull-channel facts remain outside
scheduling truth. Worker migration between Adapter processes is therefore a
Worker Delivery Dispatch concern: whichever process currently owns the Worker
may consume the same Worker-addressed mailbox.

## At-Least-Once Boundary

Missing result evidence allows Item claim expiry to make work dispatchable
again. The same logical TaskItem may therefore reach Worker execution more than
once. A transport-private command id may deduplicate retries of one physical
command, but a later kernel dispatch is a new attempt.

TaskItem score monotonicity, last-success storage, and exact Worker lease fences
make stale result evidence converge safely inside the kernel. They do not
provide exactly-once external side effects. Business operations that require
idempotency own an application key or Worker-specific execution policy inside
the opaque payload.

## Deferred Policy

- Kernel-assigned WorkerId and Adapter-local to global identity mapping are a
  separate identity slice.
- Production Worker Adapters choose their private command protocol and
  endpoint-local retry behavior.
- Pending/ack reliability and mailbox expiry cleanup require separate named
  invariants; neither is inferred from this single-slot handoff.
- Proactive unreachable-Worker classification belongs to future session or
  recovery evidence, not mailbox expiry.

## Guardrails

- Do not let Worker Delivery Dispatch choose or replace `seed.workerId`.
- Do not route DeliverSeed by WorkerGroup, endpoint manager, Adapter, session,
  or connection.
- Do not let Worker Adapter command acceptance become Item result finality.
- Do not parse or reconstruct `workerLeaseScore`.
- Do not emit rejection evidence for an unrequested Worker mailbox.
- Do not emit a timeout result for a Seed discarded before Worker submit.
- Do not move Worker release/retain decisions into mailbox consumption.
- Do not compare or replace an occupied mailbox during append.
- Do not add cross-shard rollback, cleanup scanners, or compatibility LISTs.
