# Worker Serviceability Scheduling

Status: active optional Java Kernel policy. Result and Dispatch Pacers are
production Java mechanisms.
Transport evidence is stable; score thresholds and retry policy remain
tunable.

## Purpose

Worker Serviceability keeps old scheduling coordinates from remaining ordinary
HOT candidates forever. It consumes Adapter Route evidence; it does not mirror
network state and does not call a Worker:

```text
exact Route change or expired Worker delivery
  -> ADAPTER -> KERNEL DeliveryReport
  -> Result Convergence ADAPTER_EVIDENCE lane
  -> Worker Serviceability Result Policy
  -> WorkerServiceabilityEvents
  -> Worker resource and score-owner primitives

or pre-epoch or stale ordinary HOT / due RECOVERY score
  -> Adapter-scoped snapshot request
  -> platform.adapter.worker-connections.snapshot
  -> the same Adapter Evidence policy
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

Serviceability also computes one call-local, slot-aligned HOT Probe cutoff:

```text
max(hotEligibilityFloorMillis, now - probeRetryIntervalMillis)
```

It may observe ordinary due HOT coordinates older than that cutoff as
loss-compensation candidates. This range deliberately overlaps Assignment:
the exact observed-score hold wins only when the Worker has remained unchanged;
a concurrent lease makes the hold stale. Fresh ordinary HOT, future leases and
PAUSE remain outside the Probe range.

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
Reports and admits each append batch atomically with respect to capacity. A
full LIST produces Server backpressure, so the Adapter may retry the unchanged
batch through its existing bounded process-local queue.

There is no dispatched state, deadline index, batch registry, ack, retry queue,
durable claim, global pending counter, or backlog watermark. Dispatch advances
the exact Worker check coordinate before offering a request. If the HASH offer
or later Report is lost, the retained RECOVERY coordinate becomes eligible
through the ordinary retry scan. If Route evidence is lost before an ordinary
HOT coordinate changes polarity, the unchanged coordinate eventually enters
the stale-HOT compensation range.

## Resource Producer

`DispatchMainScheduler` receives one bounded descending score map, removes the
INITIAL subset identified by the Task Score Owner, and loads Descriptors once
for the NORMAL complement. It does not issue a Task Score point recheck. The
Main Scheduler derives unique WorkerGroup IDs in first-occurrence Task order
and supplies that complete root input to the optional Worker Serviceability
Producer. With no surviving due Task, the Producer is not invoked and therefore
does not read Worker state or offer Probe requests.

One Serviceability round receives distinct WorkerGroups from the Main Scheduler
and visits each Group in that order. The Policy cannot discover or add Groups.
There is no process-local Group rotation cursor. HOT and RECOVERY each keep one
process-local scalar exclusive score cursor for every Group in the bounded Task batch. A
Group absent from the next Task batch is not scanned and its hint is discarded;
if demand exposes it later, scanning restarts from the full range.

The whole batch shares a budget of 100 successfully held Probe attempts. For
each Group, the policy first reads at most the remaining budget from
`[MIN_BASE, hotProbeCutoff)`, where the cutoff is the later of the process floor
and the stale-HOT threshold above. RECOVERY is read only when that Group's raw
HOT page is empty or its HOT range is in empty-range cooldown. A non-empty HOT
page suppresses RECOVERY only for that Group; unused budget continues to later
Groups. A successful exact Score hold consumes budget even when the subsequent
HASH offer returns `ALREADY_REQUESTED` or `CAPACITY`, because that Score change
is not rolled back. Processing stops when the budget is exhausted or every
Group has been visited. The two ranges retain independent cursors and
cooldowns; the former 80/20 split no longer exists.

RECOVERY retry `laneRank=n` is due only after:

```text
(n + 1) * probeRetryIntervalMillis
```

The initial HOT Probe writes rank 0 and is not counted as a Recovery Probe.
A due rank below `maxRecoveryAttempts` is advanced before the next request; a
due rank already at the maximum is exact-cold-parked without another request.

The policy directly asks the Score and Resource Owners for bounded pages,
current semantic states, and canonical Worker descriptors. It exact-cold-parks
excluded endpoints through Score Owner operations. Before any request is
offered, it asks that Owner to atomically hold exact HOT observations as
`RECOVERY(redisNow, rank=0)` or advance exact RECOVERY observations to
`RECOVERY(redisNow, rank+1)`. Only successfully transitioned Workers are
grouped by `endpointManagerId` and offered through the bounded
`WorkerServiceabilityRuntime.offerProbeRequests` Owner operation. An
`ALREADY_REQUESTED` or `CAPACITY` result does not roll back the Score hold;
later Recovery scanning supplies best-effort convergence.

Each raw owner page is score-descending. Its last score becomes the next
exclusive upper bound before state filtering or request offer, so a fixed
ineligible head cannot pin later score coordinates. Equal-score entries beyond
the page limit may be skipped for that sweep. An empty HOT or RECOVERY page
independently resets that cursor and cools only that range for
`probeSweepRestartDelayMillis` (default 10 seconds); the Dispatch Main Scheduler
keeps running and does not block for the cooldown. Cursor and
cooldown are bounded scan hints, not fairness guarantees, Redis checkpoints or
in-flight Probe tracking. The Task
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

The fixed Java production Adapter Evidence batch policy accepts three strict
`ADAPTER -> KERNEL` forms:

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
timestamps use the later Report. The policy publishes only three bounded
semantic facts: connected, route unavailable, and probe unavailable.
Across consumed rounds there is no retained evidence timestamp or strict
monotonic fence: Reports apply in arrival order and an older late Report may
temporarily reverse a newer observation. A later connection transition or
Probe supplies fresh evidence and drives eventual convergence. This mechanism
does not claim an uninterrupted monotonic state history.
`DeliveryReport`, JSON, forward values and Adapter Event Names stop at that
policy. `WorkerServiceabilityEvents` resolves global workerId to WorkerGroup in
bounded catalog reads and owns current-score interpretation. Missing owners,
scores, or malformed Reports are dropped. There is no retry or retained
current-state projection.

## Score Convergence

`DefaultWorkerServiceabilityEvents` resolves WorkerGroup ownership and calls
one bounded Score-owner serviceability Evidence operation. The Adapter Evidence
policy cannot read a Worker score or select a concrete score mutation. The
finite event interface is not a generic EventBus.

The target is fixed by the semantic event:

```text
CONNECTED                                      -> HOT
DISCONNECTED / delivery expired / Probe miss  -> RECOVERY
```

For each Worker, Evidence is accepted when its 100ms slot is at least the
stored non-future slot, or when the stored Score is a future lease/hold. The
same slot is accepted. Valid Evidence always preserves `laneRank` and dirty.
Unavailable Evidence changes only the Score sign. Connected Evidence also
advances an older non-future coordinate to its Evidence slot, so a reconnect
observed after Server startup crosses that process's HOT eligibility floor
without depending on a separate Probe round. A future lease or PAUSE keeps its
exact time coordinate and is never shortened by Evidence.

Retry rank, next-check time, and cold parking are Dispatch concerns performed
before a Probe is offered or when a due Recovery observation is exhausted.
The Result path does not calculate the process floor or advance Recovery retry
state; it uses the accepted connection timestamp as the fresh HOT coordinate.
A newer non-future Score rejects older Evidence as `STALE`.

## Server And Adapter Boundary

Server routes result ownership only by destination:

```text
TASK   -> Task Result Runtime
SYSTEM -> Direct Call correlation
KERNEL -> Worker Serviceability Runtime
```

It does not parse Serviceability event payloads. Adapter uses its fixed Command
and Report Dispatchers; see the [Adapter Owner](../../../transport/netty-adapter/README.md)
for lane admission, retransmission and shutdown.

When a TASK Command expires before delivery, Adapter independently offers a
23002 TASK Report and a `platform.adapter.worker-delivery.expired` KERNEL Report
to their respective lanes. These admissions are not atomic and either may be
lost. Task Result Routing handles correlated execution evidence and lease
release; only Serviceability interprets the KERNEL Report as availability
evidence. Queue pressure does not create a cross-lane admission guarantee or
close a Worker Channel merely because delivery expired.

## Lifecycle And Guardrails

When Serviceability is absent, no floor, Adapter Evidence lane, or
Serviceability Dispatch lane exists. The Server bridge owner may still be
assembled but has no production Evidence consumer. Production mints the floor
once in Java and shares it only with Serviceability Dispatch and Assignment,
and uses:

```text
start: Java Result Convergence
    -> Java Dispatch Convergence

stop: Java Dispatch Convergence
   -> Java Result Convergence
```

Serviceability Dispatch and Assignment use the same floor. Adapter Evidence
does not receive or rewrite it. This assembly has no duplicate consumers or
Probe Request producers.

- Do not generalize the Runtime into an event bus.
- Do not send Adapter Route probes to Workers.
- Do not infer network state in Task Result Routing.
- Do not let Server or Transport write score.
- Do not treat best-effort evidence as a scheduling fence.
- Binding generation, Polling wake, heartbeat, and multi-Kernel epoch
  coordination remain later Fleet-convergence slices.
