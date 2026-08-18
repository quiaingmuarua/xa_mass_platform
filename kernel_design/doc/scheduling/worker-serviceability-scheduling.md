# Worker Serviceability Scheduling

Status: active optional Kernel policy with Server and Adapter bridge.

## Purpose

Worker serviceability periodically asks the Worker-owning Adapter for a bounded
route snapshot and turns the returned observation into a Worker score
transition. Its periodic Dispatch path primarily prevents an offline Worker
from remaining indefinitely inside the HOT schedulable range. It is a
convergence policy over scheduling coordinates, not a live connection mirror
and not Worker RPC:

```text
stale HOT or due RECOVERY score
  -> Adapter-scoped probe request
  -> platform.adapter.worker-connections.snapshot
  -> ADAPTER -> KERNEL DeliveryReport
  -> WorkerScoreCore serviceability transition
```

The Adapter is the probe target. Workers receive no Command and execute no
Handler. `CONNECTED` is evidence that the Adapter observed a verified active
route; it does not prove idleness, current Binding, writability, or process
liveness.

## Runtime Owner

`WorkerServiceabilityRuntime` owns two best-effort handoffs:

```text
ws:{prefix}:adapter:{adapterId}:probe-requests
  HASH workerId -> "1"

ws:{prefix}:probe-results
  LIST encoded DeliveryReport
```

The request HASH is Adapter-partitioned so Server consumption needs no global
scan or repartitioning. `HSETNX` coalesces repeated requests for one Worker.
Server destructively selects up to 100 random fields only when the Adapter's
ordinary Command response still has capacity. There is no
requested/dispatched state, deadline index, batch registry, ack, retry counter,
or durable delivery claim.

One result LIST item is one ordinary DeliveryReport and may carry up to 100
Worker states in its payload. The list is bounded to 10,000 entries and accepts
only the available prefix of an append batch. Destructive consumption and
process failure may lose a result. That loss is safe: the Worker score remains
old and a later discovery round can request another observation.

The exact backend contract is documented in
[Worker Serviceability Runtime Redis Shape](../runtime-redis/worker-serviceability-runtime-redis-shape.md).

## Dispatch Pacer

`WorkerServiceabilityDispatchPacer` rotates through the explicitly configured
WorkerGroup ids, exactly one Group per round. It never discovers Groups through
Redis.

For that Group it:

1. reads bounded oldest due HOT and RECOVERY candidates;
2. batch-loads their decoded score states through `WorkerScoreCore`;
3. keeps HOT coordinates older than the HOT horizon and RECOVERY coordinates
   older than the retry interval;
4. batch-loads Worker descriptors;
5. skips missing descriptors and `system-polling`;
6. groups Worker ids by the descriptor's `endpointManagerId` and offers them to
   the matching request HASH.

The default round budget is 80 HOT plus 20 RECOVERY candidates. Recovery
discovery is restricted to the Score owner's recent negative window; it never
scans the full negative axis or automatically rediscovers cold-parked Workers.
A request already in the HASH is a successful coalescence, not a second item.
The Pacer does not lease Workers or write scores.

## Result Pacer

`WorkerServiceabilityResultPacer` consumes up to ten Reports per round. A valid
Report is strictly:

```text
src=ADAPTER
dst=KERNEL
messageType=platform.adapter.worker-connections.snapshot
outcomeCode=200
forward=worker-serviceability:v1:<checkStartedAtMillis>
payload={"stateByWorkerId":{"worker-id":"CONNECTED|DISCONNECTED|UNKNOWN"}}
```

It resolves each explicit Worker id to its immutable WorkerGroup through
`WorkerResourceCatalog.get_worker_group_ids`, groups checks by score bucket,
and invokes `WorkerScoreCore.apply_worker_serviceability_checks`. Missing
owners and malformed Reports are dropped. There is no result retry queue or
second projection of current route state.

## Server And Adapter Bridge

The existing Adapter HTTP boundary carries the observation without a dedicated
route, Process, queue, or thread:

```text
commands:consume
  -> Adapter Direct FIFO
  -> shared Worker Command HASH
  -> with remaining capacity, one Serviceability Adapter Command

results:append
  -> dst=TASK   selects Worker Result owner
  -> dst=SYSTEM selects Direct Call owner
  -> dst=KERNEL selects Worker Serviceability result handoff
```

Server turns one destructively consumed request set into:

```text
src=KERNEL
dst=ADAPTER
messageType=platform.adapter.worker-connections.snapshot
payload={"workerIds":[...]}
forward=worker-serviceability:v1:<checkStartedAtMillis>
```

The Adapter permits KERNEL only for that immutable local event. Its ordinary
`DeliveryReport.fromCommand` path returns `ADAPTER -> KERNEL`; Server validates
the path Adapter identity and appends the encoded Report. Neither layer reads
or writes Worker score. Sustained higher-priority Commands may starve a probe;
the stale score remains discoverable by a later Dispatch round.

## Score Transition

The Score owner floors `checkStartedAtMillis` to its internal slot and performs
one atomic Redis script per Worker. It applies a check only when the stored
score time is older than the check:

```text
serviceable=true
  -> HOT_ACQUIRE(check slot, laneRank=0, dirty preserved)

HOT + serviceable=false
  -> RECOVERY_RECHECK(check slot, retryCount=0, dirty preserved)

RECOVERY + serviceable=false
  -> RECOVERY_RECHECK(check slot, retryCount+1, dirty preserved)

retryCount reaches maxRecoveryAttempts
  -> RECOVERY_RECHECK(owner-internal near-zero cold slot, dirty preserved)
```

Missing, current/newer, or future-check coordinates return `STALE`/`INVALID`
and are not overwritten. The Pacer never decodes, constructs, or receives an
opaque score. A cold-parked Worker is intentionally outside periodic discovery;
future online evidence may request the same Adapter check, but must not promote
the Worker directly or bypass this Result-to-Score transition.

## Lifecycle And Current Cut

When the optional `workerServiceability` configuration is absent, no Runtime or
thread is created. When enabled, Result and Dispatch each own one non-daemon
loop. Startup is:

```text
Result Routing -> Serviceability Result -> Serviceability Dispatch
               -> Assignment Dispatch
```

Shutdown is the reverse order. Enabling this optional policy activates both
Kernel Pacer loops and the existing Server/Adapter bridge; omitting it creates
neither Runtime nor thread.

## Guardrails

- Do not generalize the Runtime into an event bus or arbitrary payload queue.
- Do not send this probe to Workers.
- Do not add in-flight truth merely to prevent duplicate best-effort checks.
- Do not make a missing Report an immediate negative score write.
- Do not treat Adapter route state as Worker lifecycle or scheduling truth.
- Do not let Server or Transport invoke score transitions directly.
