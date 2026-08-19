# Worker Serviceability Scheduling

Status: active optional Kernel policy with a stable Server and Adapter evidence
bridge; policy thresholds and score interpretation remain tunable.

## Purpose

Worker serviceability turns Adapter Route evidence into Worker score
transitions. Exact `CONNECTED` and `DISCONNECTED` Route changes provide the
fast path. Periodic snapshots compensate for dropped evidence, process restart,
and long-lived drift. This is convergence over scheduling coordinates, not a
live connection mirror and not Worker RPC:

```text
exact Adapter Route change
  -> ADAPTER -> KERNEL DeliveryReport
  -> WorkerScoreCore serviceability transition

or stale HOT / due RECOVERY score
  -> Adapter-scoped probe request
  -> platform.adapter.worker-connections.snapshot
  -> ADAPTER -> KERNEL DeliveryReport
  -> WorkerScoreCore serviceability transition
```

The Adapter is the probe target. Workers receive no Command and execute no
Handler. `CONNECTED` is evidence that the Adapter observed a verified active
route; it does not prove idleness, current Binding, writability, or process
liveness.

## Stable Mechanism And Current Policy

The stable mechanism is deliberately smaller than the current policy:

| Stable mechanism | Current executable policy |
| --- | --- |
| Adapter emits exact, timestamped Route observations without reading score | `CONNECTED` is interpreted as serviceable and `DISCONNECTED` as unserviceable |
| Server routes `dst=KERNEL` Reports into one bounded evidence handoff without parsing their event payload | Evidence older than 30 seconds or in the future is dropped |
| Kernel resolves global workerId to WorkerGroup and only the Score owner may apply a transition | HOT is rechecked after five minutes and RECOVERY after one minute |
| Periodic Adapter snapshots compensate for missing exact observations | Discovery budgets are 80 HOT and 20 RECOVERY candidates per round |
| Score writes remain atomic and reject observations behind the current scheduling coordinate | Recovery attempts and cold parking use the current retry policy |

The right column is not a Transport contract. Debounce, evidence weighting,
retry cadence, recovery limits, cold handling, or a future heartbeat can change
inside Kernel policy without changing the Adapter event, Server routing, or
Worker Delivery DTO.

The current timestamp comparison is a lightweight stale check, not an
independent evidence generation. Events in the same 100ms score slot may
coalesce, Adapter and Kernel clocks may drift, and a cold-coordinate rewrite
does not preserve a monotonic evidence version. The current cut also has no
Binding-generation fence: Server authenticates the producing Adapter path, but
Kernel does not yet prove that Adapter still owns each reported Worker at apply
time. These are explicit policy and Fleet-convergence follow-ups, not hidden
guarantees of this mechanism.

## Runtime Owner

`WorkerServiceabilityRuntime` owns two best-effort handoffs:

```text
ws:{prefix}:adapter:{adapterId}:probe-requests
  HASH workerId -> "1"

ws:{prefix}:adapter-evidence-results
  LIST encoded DeliveryReport
```

The request HASH is Adapter-partitioned so Server consumption needs no global
scan or repartitioning. `HSETNX` coalesces repeated requests for one Worker.
Server destructively selects up to 100 random fields only when the Adapter's
ordinary Command response still has capacity. There is no
requested/dispatched state, deadline index, batch registry, ack, retry counter,
or durable delivery claim.

One result LIST item is one ordinary DeliveryReport. A Route-change Report
carries one Worker; a periodic snapshot Report may carry up to 100. The list is
bounded to 10,000 entries and accepts only the available prefix of an append
batch. Destructive consumption and process failure may lose evidence. The old
score then remains discoverable by a later periodic round.

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

`WorkerServiceabilityResultPacer` consumes up to ten Reports per round. It
accepts two strict forms:

```text
active Route change
  src=ADAPTER
  dst=KERNEL
  messageType=platform.adapter.worker-connection.changed
  outcomeCode=200
  forward=worker-serviceability-evidence:v1
  payload={"workerId":"...","state":"CONNECTED|DISCONNECTED",
           "observedAtMillis":...}

periodic snapshot
src=ADAPTER
dst=KERNEL
messageType=platform.adapter.worker-connections.snapshot
outcomeCode=200
forward=worker-serviceability:v1:<checkStartedAtMillis>
payload={"stateByWorkerId":{"worker-id":"CONNECTED|DISCONNECTED|UNKNOWN"}}
```

Both forms are rejected when their observation time is in the future or older
than `evidenceMaxAgeMillis` (default `30000`). Within a round, the newest
observation for each Worker is retained. The Pacer resolves explicit Worker ids
to their immutable WorkerGroup through
`WorkerResourceCatalog.get_worker_group_ids`, groups checks by score bucket,
and invokes `WorkerScoreCore.apply_worker_serviceability_checks`. Missing
owners and malformed Reports are dropped. There is no result retry queue or
second projection of current Route state. A `CONNECTED` change applies a HOT
check directly; it does not enqueue another probe.

## Server And Adapter Bridge

The existing Adapter HTTP boundary carries both evidence forms without a
dedicated route, Process, queue, or thread:

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

Server turns one destructively consumed periodic request set into:

```text
src=KERNEL
dst=ADAPTER
messageType=platform.adapter.worker-connections.snapshot
payload={"workerIds":[...]}
forward=worker-serviceability:v1:<checkStartedAtMillis>
```

The Adapter permits KERNEL for the immutable snapshot event and also emits
single-Worker `platform.adapter.worker-connection.changed` Reports when its
Route Registry makes an exact availability transition. Both use the ordinary
`DeliveryReportProcess`, so several logical Reports may share one HTTP append
batch. Server validates only the path Adapter identity and destination before
appending the encoded Reports; it does not parse event or payload semantics.
Neither Server nor Transport reads or writes Worker score. Sustained
higher-priority Commands may starve a periodic probe; later discovery still
converges it.

## Score Transition

The Score owner floors the snapshot check start or Route `observedAtMillis` to
its internal 100ms slot and performs one atomic Redis script per Worker. It
applies evidence only when the stored score time slot is strictly older:

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

Missing, current/newer, or future observation coordinates return
`STALE`/`INVALID`
and are not overwritten. The Pacer never decodes, constructs, or receives an
opaque score. A cold-parked Worker is intentionally outside periodic discovery,
but a later exact `CONNECTED` Report still uses this same Result-to-Score owner
transition and may restore HOT.

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
- Do not turn Adapter Route evidence into a Server connection-state mirror.
- Do not let Server or Transport invoke score transitions directly.
- Do not promote current thresholds, retry counts, or polarity mappings into
  Transport or Server contracts.
