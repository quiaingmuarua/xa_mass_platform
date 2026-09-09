# Worker Serviceability Scheduling

Status: active Java Kernel network-evidence policy with optional periodic probes. Result and Dispatch Pacers are
production Java mechanisms.
Transport evidence is stable; score thresholds and retry policy remain
tunable.

## Purpose

Worker Serviceability keeps old scheduling coordinates from remaining ordinary
HOT candidates forever and activates cold registered members from valid network
observations. Every preset consumes evidence; only configured presets probe. It
consumes Adapter Route evidence and Server Polling observations; it does not mirror
network state and does not call a Worker:

```text
exact Route change or expired Worker delivery
  -> ADAPTER -> KERNEL DeliveryReport
  -> Result Convergence NETWORK_EVIDENCE lane
  -> Worker Serviceability Result Policy
  -> WorkerServiceabilityEvents
  -> Worker resource and score-owner primitives

or pre-epoch or stale ordinary HOT / due RECOVERY score
  -> Adapter-scoped snapshot request
  -> platform.adapter.worker-connections.snapshot
  -> the same Network Evidence policy
```

`CONNECTED` means the Adapter observed a verified active route. It does not
prove Worker idleness, current Binding, channel writability, or process
liveness. `worker-delivery.expired` means only that one TASK Command missed its
Adapter delivery deadline.

## HOT Eligibility Epoch

When periodic Serviceability is configured, `KernelPacerRuntime` mints one immutable,
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
periodic Serviceability is absent it passes `null`, preserving the original `MIN_BASE`
range. New Workers start at the fixed negative cold coordinate outside all
these scan ranges. A later valid network observation refreshes the time for HOT
activation; registration does not consult current time.

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
full LIST produces Server backpressure. The Adapter's KERNEL submission policy
drops the rejected batch; internal Polling publication also drops on capacity or
failure. Neither path adds an evidence replay guarantee.

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
No probe request is written. A later valid Polling observation can restore HOT
availability for a Polling Worker; other excluded endpoints require fresh valid
network evidence.

## Evidence Forms

The fixed Java production Network Evidence batch policy accepts three strict
`ADAPTER -> KERNEL` forms:

```text
platform.adapter.worker-connection.changed
  payload={"workerId":"...","state":"CONNECTED|DISCONNECTED",
           "observedAtMillis":...}
  forward=worker-serviceability-evidence:v1

platform.adapter.worker-delivery.expired
  payload={"workerId":"...","observedAtMillis":...}
  forward=worker-serviceability-evidence:v1

platform.adapter.command.succeeded
  payload={"stateByWorkerId":{"worker-id":"CONNECTED|DISCONNECTED|UNKNOWN"}}
  forward=worker-serviceability:v1:<checkStartedAtMillis>
```

The snapshot request remains `platform.adapter.worker-connections.snapshot`.
Its generic Adapter success event is recognized only with the dedicated probe
forward and strict snapshot schema. Failed results and Command-name echoes
produce no observation. No request registry is added; diagnostics never gate
snapshot, connection, expiry or polling evidence.

Evidence in the future or older than `evidenceMaxAgeMillis` (default 30s) is
dropped. Within one consumed round, the latest timestamp wins per Worker; equal
timestamps use the later Report. The policy publishes only three bounded
semantic facts: available, route unavailable, and probe unavailable. Each carries
the producing Endpoint and observation timestamp.
Across consumed rounds there is no retained evidence timestamp or strict
monotonic fence: Reports apply in arrival order and an older late Report may
temporarily reverse a newer observation. A later connection transition or
Probe supplies fresh evidence and drives eventual convergence. This mechanism
does not claim an uninterrupted monotonic state history.
`DeliveryReport`, JSON, forward values and Adapter Event Names stop at that
policy. `WorkerServiceabilityEvents` reads each bounded Binding batch once,
checks the producing Endpoint against the stored Binding and obtains Group. It
then delegates current-score interpretation to Score Owner. Missing Bindings,
scores, or malformed Reports are dropped. There is no retry or retained
current-state projection.

## Polling Observation And Cold Activation

After validating Binding and before consuming a Command, every valid Polling
request attempts one internal append, including empty polls:

```text
SERVER (sourceId=system-polling) -> KERNEL
platform.server.worker-poll.observed
payload={"workerId":"...","observedAtMillis":<Server time>}
forward=worker-serviceability-evidence:v1
```

This shares the existing evidence LIST and consumption lane; it adds no queue,
thread or Server dedup cache. Public Adapter ingress rejects SERVER-source
observations. A full queue or append exception drops the observation while
normal Command consumption continues. The next valid poll supplies new input.
The per-poll publication cost is intentional in this cut.

Every initial registration, including Polling, is cold. Lost first connection
or poll evidence leaves it cold until fresh valid evidence arrives. No ACK,
replay or cold-member scan promises activation. Existing recovery exhaustion
continues to use its cold parking coordinate. Network events do not initialize
missing Scores, release leases, clear dirty or undo PAUSE.

## Score Convergence

`DefaultWorkerServiceabilityEvents` checks Binding Endpoint and Group, then calls
one bounded Score-owner serviceability Evidence operation. The Network Evidence
policy cannot read a Worker score or select a concrete score mutation. The
finite event interface is not a generic EventBus.

The target is fixed by the semantic event:

```text
CONNECTED or valid Polling observation          -> HOT
DISCONNECTED / delivery expired / Probe miss  -> RECOVERY
```

For each Worker, Evidence is accepted when its 100ms slot is at least the
stored non-future slot, or when the stored Score is a future lease/hold. The
same slot is accepted. Valid Evidence always preserves `laneRank` and dirty.
Unavailable Evidence changes only the Score sign. Available Evidence also
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
TASK   -> Task Evidence Runtime
SERVER -> Direct Call correlation
SYSTEM -> admitted Adapter Properties observations to Matching; no Kernel calls
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

All presets install the Network Evidence lane. DEFAULT has no HOT floor or
periodic Serviceability Dispatch lane. Production mints the floor
once in Java and shares it only with Serviceability Dispatch and Assignment,
and uses:

```text
start: Java Result Convergence
    -> Java Dispatch Convergence

stop: Java Dispatch Convergence
   -> Java Result Convergence
```

Serviceability Dispatch and Assignment use the same floor. Network Evidence
does not receive or rewrite it. This assembly has no duplicate consumers or
Probe Request producers.

- Do not generalize the Runtime into an event bus.
- Do not send Adapter Route probes to Workers.
- Do not infer network state in Task Result Routing.
- Do not let Server or Transport write score.
- Do not treat best-effort evidence as a scheduling fence.
- Binding migration/generation, heartbeat, and multi-Kernel epoch
  coordination remain later Fleet-convergence slices.
