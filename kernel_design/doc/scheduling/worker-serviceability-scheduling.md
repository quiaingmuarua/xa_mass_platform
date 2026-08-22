# Worker Serviceability Scheduling

Status: active optional Kernel policy. Transport evidence is stable; score
thresholds and retry policy remain tunable.

## Purpose

Worker Serviceability keeps old scheduling coordinates from remaining ordinary
HOT candidates forever. It consumes Adapter Route evidence; it does not mirror
network state and does not call a Worker:

```text
exact Route change or expired Worker delivery
  -> ADAPTER -> KERNEL DeliveryReport
  -> Serviceability Result Pacer
  -> explicit WorkerScore primitives

or pre-epoch HOT / due RECOVERY score
  -> Adapter-scoped snapshot request
  -> platform.adapter.worker-connections.snapshot
  -> the same Result Pacer
```

`CONNECTED` means the Adapter observed a verified active route. It does not
prove Worker idleness, current Binding, channel writability, or process
liveness. `worker-delivery.expired` means only that one TASK Command missed its
Adapter delivery deadline.

## HOT Eligibility Epoch

When Serviceability is configured, `KernelApplication` mints one immutable,
100ms-aligned `hotEligibilityFloorMillis` for that process instance. Restarting
the loops on the same Application does not change it; a new Kernel process has
a new epoch.

```text
0 .. floor       pre-epoch HOT; excluded from Assignment and probed
floor .. now     ordinary due HOT candidates
now .. future    lease or hold
PAUSE_TIME       pause hold
```

Assignment passes the floor to both broad and explicit HOT reads. When
Serviceability is absent it passes `None`, preserving the original `MIN_BASE`
range. New Worker initialization uses current time and therefore starts after
the active floor.

The floor is not an evidence timestamp or persistent generation. This cut
assumes one active Kernel scheduling application per Redis scope.

## Best-Effort Runtime

`WorkerServiceabilityRuntime` owns only two bounded handoffs:

```text
xa_mass:<scope>:worker:serviceability:adapter:<adapterId>:probe_requests
  HASH workerId -> "1"

xa_mass:<scope>:worker:serviceability:evidence_results
  LIST encoded DeliveryReport
```

The Adapter-partitioned HASH uses `HSETNX`, so repeated requests for one Worker
coalesce. Server destructively selects up to 100 fields only when an Adapter
Command response has remaining capacity. The LIST holds at most 10,000 ordinary
Reports and accepts only the available prefix of an append batch.

There is no dispatched state, deadline index, batch registry, ack, retry queue,
or durable claim. Loss leaves the old score eligible for a later scan.

## Dispatch Pacer

The Dispatch Pacer first reads one bounded page of due
`RUNNING_VISIBLE` Tasks through the same read-only acquisition surface used by
Task Dispatch. It re-reads their current score states and allocation
descriptors, removes Tasks that became paused, terminal, missing, or invalid,
and deduplicates `workerGroupId` in page order. With no surviving due Task it
does not read Worker score, Worker resources, or the Probe Runtime.

One round selects one Group from that current Task page. HOT and RECOVERY each
keep one process-local opaque score cursor for Groups still present in the
page; hints for disappeared Groups are deleted. For the selected Group it
reads:

- HOT scores in `[MIN_BASE, hotEligibilityFloor)`;
- RECOVERY scores in the owner's bounded recent negative window.

RECOVERY retry `laneRank=n` is due only after:

```text
(n + 1) * recoveryRetryIntervalMillis
```

The Pacer batch-loads current score states and Worker descriptors, groups
eligible Workers by `endpointManagerId`, and offers ids to the matching request
HASH. It does not lease Workers and normally does not write scores.

Each raw owner page is score-descending. Its last score becomes the next
exclusive upper bound before state filtering or request offer, so a fixed
ineligible head cannot pin later score coordinates. Equal-score entries beyond
the page limit may be skipped for that sweep. An empty HOT or RECOVERY page
independently resets that cursor and cools only that range for
`probeSweepRestartDelayMillis` (default 10 seconds); the one-second Application
loop keeps running and never sleeps for the cooldown. Cursor and cooldown are
fairness hints, not Redis checkpoints or in-flight Probe tracking. The Task
score page is never mutated or held by Serviceability. A Group outside the
bounded due-Task page is intentionally ignored until Task demand exposes it in
a later round.

`probeExcludedEndpointManagerIds` is the finite exception. It defaults to
`["system-polling"]`, accepts zero to 100 unique ids, and replaces the former
hard-coded Polling branch. An excluded HOT score is exact-toggled to RECOVERY;
an excluded RECOVERY score is used as observed. The exact negative score is
then cold-parked with `laneRank=maxRecoveryAttempts`. PAUSE remains unchanged.
No probe request is written. A future Polling wake/evidence owner must restore
such Workers.

## Evidence Forms

The Result Pacer accepts three strict `ADAPTER -> KERNEL` forms:

```text
platform.adapter.worker-connection.changed
  payload={"workerId":"...","state":"CONNECTED|DISCONNECTED",
           "observedAtMillis":...}
  forward=worker-serviceability-evidence:v1

platform.adapter.worker-delivery.expired
  payload={"workerId":"...","observedAtMillis":...}
  forward=worker-serviceability-evidence:v1

platform.adapter.worker-connections.snapshot
  payload={"stateByWorkerId":{"worker-id":"CONNECTED|DISCONNECTED|UNKNOWN"}}
  forward=worker-serviceability:v1:<checkStartedAtMillis>
```

Evidence in the future or older than `evidenceMaxAgeMillis` (default 30s) is
dropped. Within one consumed round, the latest timestamp wins per Worker; equal
timestamps use the later Report. Global workerId is resolved to WorkerGroup in
bounded catalog reads. Missing owners, scores, or malformed Reports are
dropped. There is no retry or retained current-state projection.

## Score Convergence

The Pacer composes existing Score owner operations; there is no business-shaped
`applyServiceability` operation.

Route `CONNECTED` or snapshot `CONNECTED`:

```text
PAUSE      -> no-op
RECOVERY   -> exact toggle to HOT
HOT        -> retain polarity
then monotonic rewrite to hotEligibilityFloor, laneRank=0
```

The final HOT time is `max(originalTime, floor)`. A lease or hold newer than the
floor is never lowered.

Route `DISCONNECTED` or `worker-delivery.expired`:

```text
PAUSE      -> no-op
HOT        -> exact toggle to RECOVERY
RECOVERY   -> no-op
```

Toggle preserves time and dirty and resets laneRank to zero. A stale exact CAS
is not retried.

Snapshot failure (`DISCONNECTED` or `UNKNOWN`) advances retry policy:

```text
HOT        -> exact toggle, then rewrite to check time at laneRank=0
RECOVERY n -> rewrite to check time at laneRank=n+1
n+1 >= max -> exact cold park at laneRank=max
```

`observedAtMillis` is used only for age validation and within-round ordering;
it is not a score version. Exact Score CAS and monotonic same-polarity rewrite
remain the write fences.

## Server And Adapter Boundary

Server routes result ownership only by destination:

```text
TASK   -> Worker Result Runtime
SYSTEM -> Direct Call correlation
KERNEL -> Worker Serviceability Runtime
```

It does not parse Serviceability event payloads. Adapter uses the existing
Command/Report Processes and adds no queue, thread, or HTTP path.

When a TASK Command expires before delivery, Adapter atomically offers one
23002 TASK Report and one `platform.adapter.worker-delivery.expired` KERNEL
Report to the same Report queue. Task Result Routing performs TaskItem
disposition and exact lease release only; Serviceability alone interprets the
second Report. Queue pressure drops both without closing a Worker Channel.

## Lifecycle And Guardrails

When Serviceability is absent, no floor, Runtime, or Pacer thread exists. When
enabled, Result and Dispatch each own one non-daemon loop:

```text
start: Result Routing -> Serviceability Result -> Serviceability Dispatch
      -> Assignment Dispatch
stop: reverse order
```

- Do not generalize the Runtime into an event bus.
- Do not send Adapter Route probes to Workers.
- Do not infer network state in Task Result Routing.
- Do not let Server or Transport write score.
- Do not treat best-effort evidence as a scheduling fence.
- Binding generation, Polling wake, heartbeat, and multi-Kernel epoch
  coordination remain later Fleet-convergence slices.
