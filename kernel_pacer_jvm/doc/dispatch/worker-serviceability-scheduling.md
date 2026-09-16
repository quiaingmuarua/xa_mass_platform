# Worker Serviceability Scheduling

Status: active Java Kernel network-evidence policy with optional periodic probes. Result and Dispatch Pacers are
production Java mechanisms.
Transport evidence is stable; score thresholds and recheck timing remain
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
millisecond `hotEligibilityFloorMillis` for that process instance. Only the
Score Owner converts it to the current encoding's slot boundary. Restarting
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

Serviceability computes one call-local HOT Probe cutoff in milliseconds:

```text
max(hotEligibilityFloorMillis, max(0, now - hotProbeStaleAfterMillis))
```

The Owner alone aligns the cutoff. The observation is an immutable ordered Map
of IDs to opaque fences; Policy does not decode it or make a second point read.
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

An unused candidate that is repeatedly refilled does not have an unchanged
coordinate: each new lease advances its time. It can remain outside the old-HOT
probe range without any actual Adapter delivery to generate fresh evidence.
Refill alone therefore does not guarantee fleet-wide unavailability after an
outage. Task-fault records these scheduling states as diagnostics and proves
actual work recovery after reconnect. Runtime Boundary separately loses the first
disconnect evidence and requires a real TASK delivery expiry to supply new
evidence under DEFAULT, where no periodic probe can mask that path. Existing
past-slot STALE checks and exact completed-HOT counterpart release still apply.

## Resource Producer

`DispatchMainScheduler` receives one bounded descending score map, removes the
INITIAL subset identified by the Task Score Owner, and loads Descriptors once
for the NORMAL complement. It does not issue a Task Score point recheck. The
Main Scheduler derives unique WorkerGroup IDs in first-occurrence Task order
and supplies that complete root input to the optional Worker Serviceability
Producer. With no surviving due Task, the Producer is not invoked and therefore
does not read Worker state or offer Probe requests.

An eligible Producer that receives no NORMAL input remains ready for the next
Task source observation already required by the other fixed Producers. Waiting
does not create a poll, wake Main, or retain previous Group identities. This
prevents the one-second deadline from repeatedly missing Tasks while their
current 100ms pacing slot is outside the due range. After a non-empty round or
source failure, the ordinary interval applies again.

One Serviceability round receives distinct WorkerGroups from the Main Scheduler
and visits each Group in that order. The Policy cannot discover or add Groups.
There is no process-local Group rotation cursor or per-Group scan state. Every
round reads the current bounded range head for the supplied Groups; a Group
outside the current Task input is not scanned.

The whole batch shares a budget of 100 successfully held Probe attempts. For
each Group, the policy first reads at most the remaining budget from
`[MIN_BASE, hotProbeCutoff)`, where the cutoff is the later of the process floor
and the stale-HOT threshold above. RECOVERY is read only when that Group's raw
HOT result is empty. A non-empty HOT result suppresses RECOVERY only for that
Group; unused budget continues to later Groups. A successful exact Score hold
consumes budget even when the subsequent HASH offer returns `ALREADY_REQUESTED`
or `CAPACITY`, because that Score change is not rolled back. Processing stops
when the budget is exhausted or every Group has been visited.

**RECOVERY timeSlot is the next eligible recheck time for a long-lived resource.**
The Owner reads every due coordinate after the fixed cold slot, without a
24-hour lookback cutoff. Current-slot, future, PAUSE and cold coordinates remain
outside that range. There is no attempt count, attempt limit or exhaustion.

| Observed state | Exact transition before offer | Request |
| --- | --- | --- |
| eligible HOT | RECOVERY(next time=Redis now+delay) | Probe |
| due RECOVERY | RECOVERY(next time=Redis now+delay) | Probe |

The Producer interval remains 1 second. recheckDelayMillis defaults to 15 seconds;
hotProbeStaleAfterMillis independently remains 60 seconds for the HOT cutoff.
The Runtime Boundary preset retains separate 10ms values for both checks.
Pacer supplies the fixed delay. Score Owner encodes floor((Redis now+delay)/100)
inside the exact batch Lua and preserves dirty. Only storedSlot < redisNowSlot
is due, so rounding down cannot permit an early check.

**15 seconds is eligibility delay, not a promised Probe time or periodic schedule.**
Main's Group input, Producer scheduling, HOT-first ordering and the 100-successful-
hold budget determine actual progress. A delayed round starts the next delay
from its Redis execution time. An eligible Recovery member is not cold-parked
because of its age or how often it has been checked.

The policy reads `observeHotCandidatesBefore`, falling back to
`observeRecoveryRecheckCandidates` only for an empty HOT result, then loads
canonical Worker descriptors for Binding checks. It does not point-read Score
states. The selected observation entry supplies the lane; Pacer passes the
original opaque fences to `deferObservedToRecovery(group, observedScores, delayMillis)`
with one shared delay. The final exact CAS rejects deletion, time/dirty/polarity
changes and intervening PAUSE. A non-empty HOT result never falls through to
RECOVERY because its Bindings or writes failed. Only `TRANSITIONED`
Workers are grouped by `endpointManagerId` and offered through
`WorkerServiceabilityRuntime.offerProbeRequests`. A failed or lost offer or
Report leaves the next recheck time intact. No rollback, renewal or request
registry is added.

Both reads keep descending numeric score order. RECOVERY's negative scores
therefore return the earliest eligible time first, starting at the current
recheck floor. Each round uses `LIMIT 0 limit`, with no exclusive continuation
score. Successful holds move members out of the due range; cold parking removes
them from routine discovery. Subsequent reads can reach remaining equal-score
members without skipping ties. Read-only observations, invalid Bindings and
stale CAS outcomes do not themselves guarantee progress or authorize a repair
scan. There is no retained range, cursor, empty-range cooldown or extra wakeup.
An empty read is retried on the next normal Producer round. The Task score batch
is never mutated or held by Serviceability.

Removing the former Score-state point read saves one ZMSCORE per non-empty
candidate Group. HOT observation remains one range command; RECOVERY remains
one TIME followed by one range command. Deferral remains one EVAL, with one
internal TIME and one common target time base. Serviceability range reads keep
their existing exception for fractional Scores; Refill instead omits corrupt
rows within its raw-row budget. Neither path fetches replacement rows.

Runtime Boundary establishes its connected RECOVERY fixture with an exact Owner
toggle before approving the Task that exposes the Group. It must not overwrite
an in-flight probe hold with an earlier Score. Its existing 15-second bound
covers normal Producer scheduling and Adapter/Result handoff, with no scan
cooldown. Focused Pacer tests prove next-round discovery and fixed eligibility delay;
Redis Owner tests prove time boundaries, equal-score head progress and exact CAS.

Worker Score now stores only polarity, time and dirty. Old Score layouts and
related fences cannot be resumed with this encoding. No compatibility reader,
migration tool or data cleanup is included; proofs use fresh test scopes.
This change does not add a reconciler or janitor. Future offline cleanup requires
separate network evidence; nextRecheckAt advances on checks and cannot measure
offline age.

`probeExcludedEndpointManagerIds` is the finite exception. It defaults to
`["system-polling"]`, accepts zero to 100 unique ids, and replaces the former
hard-coded Polling branch. An excluded HOT score is exact-toggled to RECOVERY;
an excluded RECOVERY score is used as observed. HOT cold parking uses the new
fence returned by the successful toggle; a failed toggle stops the sequence.
The exact negative score is then cold-parked at slot 1 with dirty preserved.
PAUSE is excluded by observation ranges, and a later PAUSE change fails the
exact fence at either write.
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
replay or cold-member scan promises activation. Excluded Endpoints use the same
cold coordinate. Network events do not initialize
missing Scores, release leases, clear dirty or undo PAUSE.

## Score Convergence

`DefaultWorkerServiceabilityEvents` checks Binding Endpoint and Group, then calls
`rewriteCurrentPolarityWithinTimeFence` with the supplied evidence times, target
polarity and explicit `refreshPastTime` flag. The Network Evidence
policy cannot read a Worker score or select a concrete score mutation. The
finite event interface is not a generic EventBus.

The target is fixed by the semantic event:

```text
CONNECTED or valid Polling observation         -> HOT, refreshPastTime=true
DISCONNECTED / delivery expired / Probe miss  -> RECOVERY, refreshPastTime=false
```

**Core evidence boundary change:** a stored Score in the current 100ms slot
accepts validated Evidence just as a future coordinate does. This aligns with
active lease confirmation, which still permits that current slot. Only past
stored coordinates require Evidence from the same or a later slot. The rule
applies to HOT and RECOVERY, including a current-slot probe coordinate.
Valid Evidence always preserves dirty.
Unavailable Evidence changes only the Score sign. Available Evidence also
advances an older past-slot coordinate to its Evidence slot, so a reconnect
observed after Server startup crosses that process's HOT eligibility floor
without depending on a separate Probe round. A current/future coordinate or PAUSE keeps its
exact time coordinate and is never shortened by Evidence. This includes a future
Serviceability recheck: CONNECTED can restore HOT polarity while assignment still
waits for that retained coordinate to become due. Score alone does not distinguish
that wait from an execution lease, and evidence never releases either early.

This accepts delayed observations within the current slot and does not establish
strict network event ordering. Score time also represents leases and probe
checks; it is not an independent network timestamp. Source/Binding validation,
the default 30-second evidence age limit, lane capacity and probe scheduling
are unchanged. No extra read, queue, replay or compensation scan is introduced.

Next-check time and excluded-Endpoint cold parking are Dispatch concerns.
The Result path does not calculate the process floor or schedule rechecks; it refreshes a past coordinate from the accepted connection timestamp
and preserves a current/future coordinate.
A newer past-slot Score still rejects older Evidence as `STALE`; reports that
arrive after a lease's slot can therefore remain unapplied under best-effort
semantics.
Repeated acquisition of unused Matching stock may keep advancing that HOT
coordinate after a rejected report, keeping it outside the stale-HOT Probe
range. Stock that is never consumed receives no Command and therefore need not
produce delivery-expiry Evidence. The current-slot repair does not establish
eventual unavailability for this combination.

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
