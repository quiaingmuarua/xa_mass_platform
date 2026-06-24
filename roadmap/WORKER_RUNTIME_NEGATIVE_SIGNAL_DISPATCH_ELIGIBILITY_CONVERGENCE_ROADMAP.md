# Worker Runtime Dispatch Eligibility Signal Convergence Roadmap

Status: proposed direction document.

## Summary

This roadmap replaces the earlier transport connection-evidence/read-model
direction. The core invariant is:

```text
Negative evidence may close dispatch eligibility immediately as best-effort protection.
Positive evidence may only request worker-runtime recheck.
Only worker-runtime may reopen dispatch eligibility after validating worker declaration,
slot, group membership, gates, holds, capacity, and recovery mode.
```

Transport should own worker network/session/heartbeat facts locally. It should
not push high-frequency `connected` or `heartbeat` events into worker-runtime
as worker lifecycle state. When transport has a clear negative observation such
as disconnect, missing endpoint, or final-hop no-endpoint, it may best-effort
notify worker-runtime to block that worker from new scheduling.

Worker-runtime should own group-scoped dispatch eligibility through the existing
worker registry, candidate acquisition, dispatch gate, heartbeat freshness, and
reserve/admission path. This roadmap must converge those existing owners; it
must not introduce a parallel scheduling index that competes with
`WorkerRegistry` or `WorkerDispatchGateRuntime`.

The goal is not a perfect synchronized lifecycle state machine. It is to keep
the schedulable worker set best-effort close to fact while preserving one
scheduling owner.

Current executable scope is `NDE-0` and `NDE-0A` only. Later slices must not
start until the external fast-close block port, positive evidence check owner,
and engine release evidence surface are explicit.
Delivery target ownership can remain on the current simple implementation for
this roadmap as long as assigned dispatch does not move target resolution into
transport endpoint/session reads.

Transport/worker-runtime slices may proceed before engine convergence, but the
full roadmap is not complete until `NDE-6` removes engine-owned positive gate
clear.

Partial completion terms:

- `mainline unblocked`: transport/worker-runtime block signal path and
  recovery-mode decisions are implemented enough for dependent transport work,
  but NDE-4 or NDE-6 still remain.
- `slice complete, roadmap active`: one or more slices landed, but delivery
  target writer, push-presence scheduling-mutation residue, or engine
  positive-clear migration is still open.
- `transport/worker-runtime slice complete`: NDE-0 through NDE-5 are satisfied
  for transport and worker-runtime, but roadmap remains active until NDE-6.
- `complete`: all completion criteria at the end of this roadmap are satisfied.

## Boundary Decision

Use evidence push plus worker-runtime-owned eligibility recovery:

```text
transport disconnected / selected-worker no endpoint / no active session
  -> best-effort block worker in worker-runtime

engine task completed / assignment released / worker permit released
  -> submit release or completion evidence to worker-runtime
  -> worker-runtime checks occupancy/reserve/admission state
  -> worker may become schedulable only after worker-runtime validation

operator / worker command / worker report
  -> submitted as evidence to worker-runtime
  -> worker-runtime decides whether to block or clear internal gate sources

worker-runtime registry/gate recheck
  -> validates declaration / slot / group membership / scheduling attributes
  -> validates whether this worker supports positive recovery by worker-owned
     evidence or transport freshness evidence
  -> reads freshness evidence only if attributes allow that mode
  -> validates admission/gates inside worker-runtime owners
  -> decides whether the worker may re-enter scheduling
```

Transport owns network facts. Worker-runtime owns scheduling eligibility.
Engine owns task attempt timeout, retry, reassignment, and result convergence.
Engine also produces task-completion or assignment-release evidence, but it must
not directly clear worker dispatch gates in the final target. Engine control and
engine delivery-failure integration are deferred in this roadmap until `NDE-6`;
they should not block the transport/worker-runtime slice.

This roadmap intentionally does not define every recheck, lost-worker, hold, or
cleanup policy. Those policies can become richer inside worker-runtime later.
The current convergence only fixes the owner boundary and the negative-fast /
positive-recheck rule.

## Roadmap Detail Boundary

Do not turn this roadmap into a full worker scheduling policy design. The current
goal is narrow:

- negative evidence may quickly remove a worker from new scheduling;
- positive evidence may only request worker-runtime recheck;
- the schedulable set is best-effort close to fact, not a perfectly synchronized
  global lifecycle state;
- richer recovery, fairness, threshold, lost-worker, and hold policies remain
  worker-runtime follow-up work.

## Owner Review

Transport owns:

- adapter-local session/channel truth;
- polling/push heartbeat and connection freshness facts;
- a narrow freshness read view that can answer only whether a worker currently
  has valid transport heartbeat evidence.
- final-hop no-endpoint evidence;
- best-effort negative signal emission when it observes a worker should not
  receive new assignments.

Transport does not own:

- worker dispatch eligibility registry/gate truth;
- re-admission to schedulable state;
- worker reachability state used by scheduling;
- producer-side delivery target resolution from transport endpoint/session
  reads;
- worker lifecycle cleanup.

Worker-runtime owns:

- worker group scoped dispatch eligibility through `WorkerRegistry`,
  candidate acquisition, slot lifecycle, dispatch gates, heartbeat freshness,
  and reserve/admission;
- reserve / prewarm / hold leases and release;
- occupancy/capacity release projection from task completion or assignment
  release evidence;
- block sources and unblock/recheck policy;
- longer-term lost/removal policy as a worker-runtime follow-up, not a transport
  decision;
- candidate acquisition and final reserve validation.
- positive gate recovery from worker report/command/freshness evidence only when
  the worker's recovery mode allows that evidence to participate in
  worker-runtime recheck.

Engine owns:

- task scheduling intent and assignment;
- attempt timeout/retry/reassignment;
- task completion and assignment-release evidence production;
- delivery failure interpretation for task lifecycle;
- optional future best-effort negative signal emission when a delivery failure
  proves a worker should temporarily leave new scheduling.

## Target Model

Worker-runtime should converge dispatch eligibility into the current
`WorkerRegistry + WorkerDispatchGateRuntime` owner path.

The terms below are conceptual states, not a requirement to add new top-level
queues or a new `WorkerSchedulingIndex`:

```text
schedulable
  represented by WorkerRegistry candidate acquisition plus slotLifecycleStatus
  == ACCEPTED, followed by reserve/admission validation

occupied / reserved permits
  represented by reserve/admission capacity accounting; ordinary active work
  or reservation does not remove a multi-capacity worker from candidate
  acquisition by itself

blocked
  represented by source-scoped dispatch disable / gate records and any
  registry-owned recheck metadata
```

The implementation may use Redis zsets, hashes, in-memory sets, or other
internal structures inside the registry implementation, but the production
truth must remain one worker-runtime eligibility owner. A diagnostic enum may
exist, but it must not become a second scheduling truth.

Explicit hold/prewarm and lost/cold cleanup can be layered into worker-runtime
policy later. They are not required for the first eligibility signal
convergence slice.

Block sources should be source-scoped and idempotent. External negative sources
are narrow:

```text
TRANSPORT_DISCONNECTED
TRANSPORT_NO_ENDPOINT
DELIVERY_NO_ENDPOINT
```

Worker-runtime internal or control-plane sources remain worker-runtime-owned:

```text
WORKER_DRAINING
OPERATOR_DISABLED
COMMAND_DRAIN
CAPACITY_FULL
PREWARM_REQUIRED
```

Clearing one source must not clear another. A worker becomes schedulable only
when worker-runtime validation says all blocking sources, hold leases, and
capacity constraints allow it.

Ordinary reservation, active lease, or `OCCUPIED` diagnostic state is not a
block source. Capacity remains a reserve/admission decision.

Task completion, assignment release, or worker-permit release is not a
negative-only signal. It is valid worker-runtime input evidence because it can
reduce occupancy or release capacity. It still must not directly clear unrelated
block sources. If completion/release evidence leaves the worker with capacity
and worker-runtime checks pass, worker-runtime may make that worker schedulable.

Positive recovery mode must be explicit. Suggested modes:

```text
WORKER_EVIDENCE
  worker has a worker-owned heartbeat/report path that may participate in
  worker-runtime recheck

TRANSPORT_FRESHNESS
  worker-runtime may read a narrow transport-owned freshness view that answers
  only whether this worker has valid transport heartbeat evidence
  this mode must be explicitly enabled per worker/intake type and is not the
  default recovery mode

MANUAL_OR_CONTROLLED
  worker does not support automatic positive recovery from heartbeat/freshness;
  external/manual/control owner must drive recovery explicitly
```

Default should be conservative: no automatic positive recovery unless the worker
or intake path is explicitly classified. Recovery mode is a worker-runtime
worker attribute/slot policy input; freshness evidence cannot choose or upgrade
the recovery mode.

`TRANSPORT_FRESHNESS` is allowed only when NDE-0A proves the worker/intake type
cannot yet rely on worker-owned heartbeat/report or has a deliberate reason to
use transport freshness. Each `TRANSPORT_FRESHNESS` use must have an exit
criterion or a documented reason why transport freshness remains necessary.

## Evidence And Block Contracts

There are two different cross-boundary shapes:

```text
release/completion evidence
  engine or assignment owner -> worker-runtime occupancy/admission owner
  may reduce occupied/reserved capacity
  cannot clear unrelated block sources

external negative block signal
  transport/adapters/delivery failure producer -> worker-runtime block owner
  may make the worker less schedulable
  cannot make the worker schedulable
```

This distinction is important: the roadmap is not saying every external event
is negative-only. It is saying external modules do not own the final
eligibility reopen decision.

## External Negative Signal Contract

The cross-boundary write should be narrow, idempotent, and negative-only.
Transport/adapters and delivery-failure producers should receive a dedicated
block port, not the symmetric
`WorkerDispatchGateRuntime` that can also clear dispatch disable sources.

Shape:

```java
interface WorkerDispatchBlockRuntime {

  boolean blockWorkerDispatch(
    String workerId,
    WorkerDispatchBlockSignal signal
  );

  boolean blockWorkerDispatch(
    String workerGroupId,
    String workerId,
    WorkerDispatchBlockSignal signal
  );
}
```

Suggested signal shape:

```java
record WorkerDispatchBlockSignal(
    WorkerDispatchBlockSource source, // external negative source, not raw DispatchAvailabilitySource
    String reason,
    long observedAtMillis,
    long suggestedRecheckAfterMillis
) {}
```

Worker-runtime must map external block signals into registry-owned block
metadata before mutating candidate/gate projections. Suggested internal shape
should stay minimal:

```java
record WorkerDispatchBlockRecord(
    WorkerDispatchBlockSource externalSource,
    DispatchAvailabilitySource internalGateSource,
    String reason,
    long observedAtMillis
) {}
```

Rules:

- stale signals with `observedAtMillis` older than the stored source record are
  ignored;
- `suggestedRecheckAfterMillis` is an input hint only; worker-runtime owns how
  and when to recheck;
- block metadata may live inside `WorkerRegistry` or an adjacent
  worker-runtime-owned store, but memory and Redis implementations must preserve
  the same per-source semantics.

`workerGroupId` is allowed when the caller naturally carries it, which is true
for assigned delivery today because the current delivery bucket is effectively
the worker group. Longer term this can become `deliveryBucketId` once the
bucket contract is explicit.

Worker-runtime still validates current worker group membership before updating
group-scoped registry/gate projections. The group/bucket value is a location
hint and consistency check, not an external authority that can redefine worker
membership. Transport must not reverse-query group membership just to emit a
negative signal; it may pass the group/bucket already present on the dispatch
or session context.

Exact names may change, but the external block contract must preserve these
rules:

- caller may only make the worker less schedulable;
- caller cannot move a worker into `schedulable`;
- caller cannot clear `WorkerDispatchBlockSource` or `DispatchAvailabilitySource`;
- duplicate or stale block signals are harmless;
- missing signals are allowed because this is best-effort acceleration, not
  task correctness truth;
- worker-runtime decides group membership, recheck timing, and final
  registry/gate movement.

Engine task completion or assignment-release integration must use a separate
worker-runtime release/evidence surface, not this block port and not
`WorkerDispatchGateRuntime.clearWorkerDispatchDisable`.

## Positive Recovery

Transport `connected` and `heartbeat` should not directly reopen scheduling.
Engine control policy should not directly reopen scheduling in the final target,
but moving that policy is deferred from the first transport/worker-runtime
slice.

Engine task completion, assignment completion, or worker permit release is
different from engine-owned gate clear. Engine may submit completion/release
evidence because engine owns task attempt lifecycle, but worker-runtime owns the
occupancy/admission projection that decides whether the worker becomes
schedulable again. Completion/release evidence can only remove occupied/reserved
capacity; it cannot clear unrelated block sources.

Worker heartbeat/report refresh and transport freshness evidence also do not
directly reopen scheduling. They only provide freshness evidence. Reopening
requires worker-runtime recheck and is allowed only for workers whose positive
recovery mode permits that evidence source.

Recovery order is intentionally coarse:

```text
read worker declaration / slot / group membership / scheduling attributes
  -> determine recovery mode
  -> read allowed positive evidence only if the mode permits it
  -> apply completion/release evidence only to capacity/admission facts
  -> worker-runtime decides whether the worker may enter scheduling
```

Freshness evidence is ignored for workers whose attributes do not allow the
matching positive recovery mode. The detailed timing, retry, lost-worker, and
cleanup policy is worker-runtime-owned and should not be over-specified in this
transport boundary roadmap.

This avoids the old ambiguity where `connected` could be mistaken for
business readiness or scheduling eligibility.

Before any transport session-presence path is removed, the implementation must
classify every supported worker intake mode. WebSocket, socket, polling,
embedded, and external SDK workers must either have worker-owned
heartbeat/report evidence, explicitly allow narrow transport freshness as
positive evidence, or be classified as manual/controlled with no automatic
positive recovery.

Worker state report and command lifecycle evidence may still be positive
evidence, but their projection into `clearDispatchDisable` should eventually be
owned by worker-runtime. This engine-control migration is a follow-up decision,
not the first transport/worker-runtime slice.

## Current Code Observations

- Current transport session evidence still has transport-owned
  `TransportEndpointLeaseStore` / `TransportEndpointLeasePublisher`.
- Current push-presence bridge still has `WorkerPresenceIngress` /
  `WorkerPresenceSessionPublisher` residue that can mutate worker-runtime
  presence from transport connected/heartbeat/disconnected events.
- Current `WorkerRuntimePresenceIngress` receives transport connected,
  heartbeat, and disconnected events and mutates worker-runtime presence
  directly.
- Current `InMemoryWorkerPresenceRuntime` models active sessions and projects
  older `ONLINE / STALE / OFFLINE / UNKNOWN` reachability vocabulary.
- Current `WorkerPresenceRuntime` also implements `WorkerDeliveryTargetView`;
  `resolveDeliveryTarget(selectedWorkerId)` is still the dispatch producer's
  source for `selectedWorkerId -> adapterMailboxKey`.
- Current `WorkerSelectionOwner` still rejects candidates by calling
  `WorkerSchedulingViewRuntime.getWorkerReachability(workerId) != ONLINE`.
  This is a direct scheduling gate from the older reachability projection and
  must be removed or made diagnostic-only before the roadmap can claim transport
  heartbeat no longer reopens or closes scheduling.
- Current `WorkerRegistry` already owns candidate acquisition, slot lifecycle,
  heartbeat freshness, dispatch disable sources, reservation, and lifecycle
  cleanup in memory and Redis implementations.
- Current `WorkerDispatchGateRuntime` already exposes workerId-first
  `disableWorkerDispatch` / `clearWorkerDispatchDisable` methods, while the
  lower registry owner also supports group-scoped operations. This roadmap
  should preserve workerId-only calls for callers without group context and
  allow group/bucket context when the caller naturally has it.
- Current engine `DefaultWorkerDispatchAvailabilityPolicy` directly clears
  `WORKER_STATE` dispatch disable on `AVAILABLE`; that conflicts with the
  final target invariant unless the positive gate-clear decision moves into
  worker-runtime. This is tracked as deferred scope.
- Current `DispatchAvailabilitySource` is an internal gate source enum with
  `WORKER_STATE / WORKER_COMMAND / NODE_GROUP_BINDING`; external transport
  negative sources should not be added to it and exposed through the symmetric
  gate API without a separate negative-only port.
- Current worker-runtime docs discuss reachability/readiness/occupancy as
  separate conceptual dimensions. This roadmap narrows the executable target:
  runtime scheduling should center on worker-runtime registry/gate dispatch
  eligibility, while transport keeps network state local.

## Non-Goals

- Do not build a transport connection evidence read model as the scheduling
  truth.
- Do not make worker-runtime maintain high-frequency transport heartbeat
  state as a first-class scheduling state.
- Do not make transport heartbeat write worker-runtime registry heartbeat as a
  target mechanism.
- Do not let transport or adapters move a worker into `schedulable`.
- Do not keep `WorkerReachabilityView` as an independent selection-time
  scheduling gate. Reachability/freshness may feed worker-runtime recovery, but
  candidate rejection must come from registry/gate/admission projections.
- Do not pull engine control-policy migration into the first
  transport/worker-runtime slice. Track it as deferred conflict/follow-up.
- Do not expose `WorkerDispatchGateRuntime` as the external negative-signal
  contract; it is symmetric and can clear dispatch disables.
- Do not add a parallel `WorkerSchedulingIndex` unless inventory proves the
  existing registry/gate owners cannot be extended; if that happens, this
  roadmap must be revised before implementation.
- Do not delete `WorkerDeliveryTargetView` or the
  current `selectedWorkerId -> adapterMailboxKey` resolver as part of this
  roadmap unless a same-slice replacement keeps target resolution before
  transport enqueue.
- Do not classify ordinary reservation, active lease, or `OCCUPIED` as a
  candidate-lifecycle hold. Capacity belongs to reserve/admission.
- Do not redesign task attempt timeout/retry in this roadmap.
- Do not redesign public Java SDK worker online/offline APIs in the first
  slice. Public API vocabulary can be handled by a follow-up once the runtime
  owner boundary is stable.
- Do not add a broad lifecycle state machine that requires multiple modules to
  keep `reachability`, `readiness`, and `occupancy` synchronized.

## Do Not Start With

Do not start by deleting `WorkerPresenceIngress` or
`TransportEndpointLeaseStore`.

Also do not start by creating a new scheduling index beside `WorkerRegistry`.
Do not start by wiring transport or adapters to the current
`WorkerDispatchGateRuntime`; it is not a negative-only external port. Engine
control migration is deferred and should not drive this slice.

First inventory the existing registry/gate/heartbeat/delivery-target path and
decide the exact convergence surface. Then introduce the worker-runtime
block/release contracts and prove candidate acquisition/reserve behavior
through the existing registry owner. Move transport and delivery-failure
producers to the negative signal contract only after the positive
recovery mode, evidence owner, and external block port are proven. Delete old
push-presence scheduling-mutation residue only after no production path depends
on it. Keep endpoint lease evidence transport-local unless a separate transport
cleanup proves it has no diagnostics, freshness, or currentness value.

## NDE-0 Inventory And Naming Decision

Scope:

- Inventory current production and test references to:
  - `WorkerPresenceIngress`
  - `WorkerPresenceSessionPublisher`
  - `WorkerSessionPresenceEvent`
  - `TransportEndpointLeaseStore`
  - `TransportEndpointLeasePublisher`
  - `WorkerPresenceRuntime`
  - `WorkerReachabilityState`
  - `WorkerDeliveryTargetView`
  - `SelectedWorkerDeliveryTargetEvidence`
  - `TransportEndpointLeaseStore`
  - `TransportEndpointLeasePublisher`
  - endpoint lease readers/writers and diagnostics
  - `WorkerRegistry`
  - `WorkerDispatchGateRuntime`
  - `DispatchAvailabilitySource`
  - worker heartbeat refresh paths
  - worker reserve/release/dispatch gate paths
  - engine task completion / assignment release / worker permit release paths
  - `WorkerSelectionOwner` reachability checks
- Inventory current registry-heartbeat writers separately from reachability
  projection, then classify which ones are transitional:
  - `WorkerRuntimePresenceIngress.refreshSlotHeartbeat`;
  - explicit worker heartbeat/report APIs;
  - test-only heartbeat refresh fixtures.
- Inventory whether transport can provide a narrow freshness view:
  - input: `workerId`, `nowMillis`, and optional `workerGroupId` partition hint;
  - output: fresh/not fresh, optionally observed/expires timestamps;
  - point-read only; no list, scan, watch, subscribe, wakeup, or candidate
    enumeration surface;
  - forbidden output: adapter id, mailbox key, session token, connection id,
    route key, endpoint lease, session handle.
  - `workerGroupId` must not be a correctness key or group membership source;
    worker-runtime validates group membership before reading freshness.
- Inventory concrete negative-signal producer seams:
  - pre-transport delivery-target missing or not deliverable in
    `TaskDispatchRoutingSubmitter`;
  - adapter final-hop `NO_ENDPOINT`;
  - adapter-local current-session disconnect / no active session;
  - mailbox/system/backpressure failures that must not block workers.
- Decide final naming:
  - `WorkerDispatchEligibility`
  - `WorkerDispatchBlock`
  - source-scoped dispatch gate / registry extension names
  - or another worker-runtime-owned name that does not imply a parallel
    scheduling owner.
- Inventory the current delivery target resolver:
  - keep the existing simple `WorkerDeliveryTargetView` path for this roadmap
    unless a replacement is needed by the current slice;
  - name the writer that updates `SelectedWorkerDeliveryTargetEvidence`;
  - separate delivery target evidence from scheduling eligibility and transport
    freshness;
  - do not let transport endpoint/session evidence become producer-side
    target resolution in the assigned-dispatch hot path.
- Prove positive recovery evidence by worker intake mode:
  - polling worker;
  - WebSocket worker;
  - socket worker;
  - embedded/default worker;
  - external Java SDK worker.
- Classify positive recovery mode by worker intake mode:
  - `WORKER_EVIDENCE`;
  - `TRANSPORT_FRESHNESS`;
  - `MANUAL_OR_CONTROLLED`.
- Inventory where positive recovery mode should be stored or derived:
  - worker declaration;
  - worker slot metadata;
  - group policy / scheduling attributes;
  - explicit control state.
- Classify current hold-like mechanisms:
  - ordinary reservation / active lease / occupied permits;
  - exclusive lease / lock;
  - prewarm hold;
  - explicit operator or command hold.
- Classify task completion / assignment release / worker permit release:
  - which owner emits the evidence;
  - which worker-runtime owner applies the capacity or occupancy update;
  - which block sources remain unaffected by release evidence.
- Inventory whether selection-time reachability can be removed from
  `WorkerSelectionOwner` and replaced by registry/gate/admission projections.

Acceptance:

- Inventory separates runtime truth, test proof, public API residue, and
  documentation residue.
- Final name does not use `occupancy` for the whole model unless it only means
  reserve/active/capacity.
- Roadmap is updated if inventory finds a required positive heartbeat/report
  path that is not covered by worker-runtime recovery.
- Inventory explicitly names `WorkerRuntimePresenceIngress.refreshSlotHeartbeat`
  as current transitional coupling where applicable, and separates it from
  reachability projection.
- Inventory does not assume all workers can use heartbeat/report or transport
  freshness to reopen eligibility.
- Inventory states whether transport freshness can be read through a narrow
  view without exposing session/endpoint/adapter details.
- Inventory states that transport freshness is point-read only and cannot
  provide worker lists, wakeups, route owners, sessions, or adapter mailboxes.
- Inventory classifies pre-transport delivery-target failures and separates
  worker-unreachable evidence from resolver/configuration/integration failures.
- Inventory states which worker declaration/slot attributes are checked before
  any freshness view is read.
- Roadmap names the retained or replacement owner for
  the pre-transport delivery target resolver if this roadmap changes it.
- Roadmap names the delivery target evidence writer before any slice removes
  `WorkerPresenceIngress`, `WorkerPresenceRuntime`, or session-presence writes.
- Roadmap states that `WorkerRegistry + WorkerDispatchGateRuntime` are the
  first extension targets; adding a parallel scheduling index is blocked unless
  a later owner decision explicitly replaces registry ownership.
- Inventory proves whether any transport/adapter call sites currently attempt to
  reopen dispatch eligibility and names the worker-runtime owner that must
  absorb or reject that behavior.
- Inventory classifies ordinary reservation/active work as capacity/admission,
  not as candidate-lifecycle hold.
- Inventory classifies task completion/release as capacity evidence, not as a
  direct gate-clear or block-clear command.
- Inventory explicitly names the migration point where `WorkerSelectionOwner`
  stops using transport-derived `WorkerReachabilityView` as an independent
  scheduling gate.

## NDE-0A Positive Evidence And Port Boundary Decision

Scope:

- Decide the transport/worker-runtime positive recovery mechanism before any
  implementation slice:
  - declaration/slot/scheduling-attribute validation;
  - worker-owned heartbeat/report input;
  - transport freshness read input;
  - engine task-completion / assignment-release evidence input;
  - worker-runtime occupancy/capacity release owner;
  - positive recovery mode source;
  - dispatch gate clear policy;
  - high-level recheck trigger owner.
- Decide the external negative-only port:
  - introduce `WorkerDispatchBlockRuntime` or equivalent;
  - own the port and source-to-gate mapping in `xa-mass-worker-runtime`;
  - inject negative signal handling through SDK/starter assembly or an
    equivalent integration owner;
  - do not add `xa-mass-worker-runtime` as a compile dependency to
    `transport/*` modules or concrete adapters;
  - keep `WorkerDispatchGateRuntime` internal to worker-runtime recovery and
    control projection;
  - keep `WorkerDispatchBlockSource` separate from internal
    `DispatchAvailabilitySource` unless a mapping owner is explicitly named.
- Decide block metadata storage:
  - per worker/group/source metadata shape;
  - stale observedAt rejection;
  - memory and Redis storage owner.
- Decide source mapping:
  - external `WorkerDispatchBlockSource` values;
  - worker-runtime-owned mapping to internal gate source or metadata source;
  - whether existing `DispatchAvailabilitySource` is reused internally or left
    unchanged beside new block metadata;
  - transport/adapters never choose or see internal gate source.
- Decide release/evidence mapping:
  - engine task completion and assignment release do not use the external block
    port;
  - release evidence updates only occupancy/capacity/reservation state;
  - release evidence does not clear unrelated block sources;
  - worker-runtime owns the final schedulable decision after release evidence.
- Decide whether delivery target resolver ownership is changing in this
  roadmap:
  - if not, retain the existing simple `WorkerDeliveryTargetView` path;
  - name the writer that keeps `SelectedWorkerDeliveryTargetEvidence` current;
  - keep delivery target evidence out of `TRANSPORT_FRESHNESS`;
  - if yes, keep target resolution before transport enqueue and do not replace
    it with transport endpoint/session reads.

Acceptance:

- Later slices are blocked until this section names the positive recovery owner,
  release evidence owner, external transport/adapter block port, and whether
  delivery target resolver ownership is changing in this roadmap.
- NDE-4 is blocked until the delivery target writer is named and proven separate
  from scheduling eligibility and transport freshness.
- Endpoint lease removal is not part of this roadmap. If endpoint lease is kept,
  inventory must classify it as transport-local evidence/currentness/diagnostics
  and prove it is not a worker-runtime schedulability writer or producer-side
  dispatch target resolver.
- No external module is planned to receive a contract that can both block and
  clear dispatch gates.
- Transport/adapters receive only a negative-only sink or integration callback;
  they do not import `WorkerDispatchGateRuntime`, `WorkerRegistry`, or any
  clear-capable worker-runtime port.
- Transport/adapters do not add a compile dependency on `xa-mass-worker-runtime`;
  SDK/starter assembly owns the bridge between transport outcomes and
  worker-runtime block ports.
- Block metadata shape and storage owner are named before NDE-1 starts.
- Source mapping is named before NDE-1 starts; adding transport-specific values
  directly to `DispatchAvailabilitySource` is forbidden unless a worker-runtime
  owner decision explicitly keeps that enum internal.
- Every supported worker intake mode has either:
  - a proven worker-runtime heartbeat/report path; or
  - an explicit `TRANSPORT_FRESHNESS` statement that worker-runtime may read a
    narrow transport freshness view for that worker/intake mode; or
  - an explicit `MANUAL_OR_CONTROLLED` statement that heartbeat/report does not
    automatically reopen dispatch eligibility.
- Heartbeat/freshness evidence is documented and tested as evidence ingestion,
  not direct eligibility reopen.
- Completion/release evidence is documented and tested as capacity or occupancy
  evidence, not direct eligibility reopen.
- Tests or inventory proof show recheck validates worker attributes/recovery
  mode before reading worker or transport freshness evidence.
- Tests or inventory proof show `TRANSPORT_FRESHNESS` is a point-read evidence
  source only and cannot enumerate candidates or wake scheduling.
- Tests or inventory proof show `workerGroupId` on a transport freshness read is
  only a partition hint and cannot override worker-runtime group membership.
- The focused verification list is corrected to use real tests or explicitly
  mark new tests to create; no command may rely on
  `-Dsurefire.failIfNoSpecifiedTests=false` to hide missing proof.

## NDE-1A Worker-Runtime Negative Block And Selection Gate

Scope:

- Converge existing `WorkerRegistry` and worker-runtime-internal gate APIs for
  source-scoped negative worker dispatch block.
- Store source-scoped block metadata for negative signals:
  - reason;
  - observedAtMillis;
  - enough worker-runtime-owned metadata to reject stale signals and drive
    recheck.
- Group-scoped truth stays inside `WorkerRegistry` candidate, lifecycle,
  dispatch disable, and reserve semantics.
- WorkerId-first gate APIs remain available for callers without group context.
  Group/bucket-aware negative block calls are allowed when the caller naturally
  has `workerGroupId` / current `deliveryBucketId`; registry still validates
  membership internally.
- Recheck for blocked workers is owned by worker-runtime and may be periodic,
  demand-driven, or bounded opportunistic cleanup.
- Move worker selection away from direct `WorkerReachabilityView` gating:
  candidate exclusion must come from registry/gate/admission projections, not a
  transport-derived `ONLINE` read.

Acceptance:

- A worker in `blocked` is not returned by schedulable candidate acquisition.
- Ordinary reservation, active lease, and `OCCUPIED` diagnostic state do not
  remove a worker from candidate acquisition when capacity remains.
- A worker moves back to `schedulable` only through worker-runtime validation.
- Heartbeat freshness alone never clears block sources or moves a worker back to
  `schedulable`.
- Worker declaration/slot/group membership/scheduling attributes are validated
  before any freshness source participates in recovery.
- Freshness evidence is ignored when worker attributes do not allow the matching
  positive recovery mode.
- Duplicate block signals are idempotent.
- Clearing one block source does not clear other block sources.
- Task completion/release evidence can reduce occupancy/capacity, but it does
  not clear block sources.
- `WorkerSelectionOwner` no longer rejects candidates by reading a
  transport-derived `WorkerReachabilityView` / `getWorkerReachability(...) ==
  ONLINE` check as an independent scheduling gate.
- `WorkerSchedulingViewRuntime.getWorkerReachability` is removed from the
  selection path or explicitly retained as diagnostic/non-gating residue with a
  follow-up removal target.
- Focused memory and Redis registry tests prove schedulable, blocked, capacity
  release, and recheck behavior.
- Focused memory and Redis tests prove per-source block metadata, stale
  `observedAtMillis` rejection, and source-preserving recheck behavior.
- No new production `WorkerSchedulingIndex` exists beside
  `WorkerRegistry`/`WorkerDispatchGateRuntime`.
- External modules cannot access an API that clears dispatch disable sources.

## NDE-1B Deferred Worker-Runtime Policy Follow-Up

Scope:

- Keep these as worker-runtime-owned follow-up policy, not as current transport
  boundary requirements:
  - exclusive/prewarm/explicit hold with bounded lease;
  - release/expire hold;
  - move long-term blocked workers to lost/cold path.
- Keep ordinary reservation, active lease, and occupied permits in
  reserve/admission capacity accounting, not candidate-lifecycle hold.
- Keep lost/cold cleanup worker-runtime-owned; transport can provide negative
  evidence but cannot delete workers or decide long-term lifecycle cleanup.

Acceptance:

- This follow-up is not required for the first transport/worker-runtime
  eligibility signal slice.
- If implemented, these policies remain inside worker-runtime and do not create
  a second transport-driven eligibility owner.

## NDE-2 Negative Signal Producers

Scope:

- Producers call only the negative-only `WorkerDispatchBlockRuntime` or
  equivalent port, not `WorkerDispatchGateRuntime`.
- Starter assigned-dispatch translation may emit a negative signal for
  pre-transport delivery target failure only after classification proves the
  missing target is worker-unreachable evidence, not resolver/configuration
  failure.
- Transport emits best-effort negative block signals for clear network/final-hop
  failures:
  - adapter session disconnected when the adapter confirms current worker
    connection is gone;
  - final-hop no endpoint;
  - adapter-local no active session for selected worker.
- Connected and heartbeat do not emit positive schedulable signals.
- Delivery outcome to block-source mapping is allowlisted:
  - `NO_ENDPOINT` for the selected worker may emit a negative signal;
  - confirmed adapter-local no active session may emit a negative signal;
  - confirmed current-session disconnect may emit a negative signal;
  - `BACKPRESSURE`, `INVALID`, `SHUTDOWN`, mailbox/system `UNAVAILABLE`, and
    generic `FAILED` do not block worker dispatch by default.

Acceptance:

- Transport/adapters cannot call any API that makes a worker schedulable.
- Transport/adapters can call only the negative block API and cannot access
  `clearWorkerDispatchDisable`.
- Negative signal failure is logged/observable but does not fail the original
  transport delivery path.
- Delivery correctness still relies on engine attempt timeout/retry, not on
  reliable negative-signal delivery.
- Stale session token / replaced channel disconnect does not emit a worker
  dispatch block.
- Only adapter-confirmed current session loss may emit `TRANSPORT_DISCONNECTED`.
- Tests prove endpoint lease release or adapter-local session removal must match
  the current worker session before a disconnect becomes negative worker
  eligibility evidence.
- Tests prove non-worker-specific delivery failures do not disable worker
  dispatch.
- Tests prove pre-transport target missing is classified before it becomes a
  worker block signal.

## NDE-3 Recovery And Heartbeat Validation

Scope:

- Worker-runtime recovery checks the configured freshness evidence source when
  a blocked worker is due for recheck.
- Worker-runtime applies task completion / assignment release evidence only to
  occupancy, reserve, and admission capacity state.
- Worker-runtime validates worker declaration, slot, group membership,
  scheduling attributes, and recovery mode before reading that freshness source.
- Worker heartbeat/report is positive recovery evidence only for
  workers/intake modes classified as `WORKER_EVIDENCE`.
- Transport heartbeat remains transport-owned. Worker-runtime may consume it
  only through a narrow fresh/not-fresh view for workers/intake modes classified
  as `TRANSPORT_FRESHNESS`.
- `MANUAL_OR_CONTROLLED` workers do not reopen from heartbeat/freshness
  evidence.
- Explicit worker readiness/drain reports are modeled as block sources or gate
  sources, not a parallel readiness state machine.
- If current session-presence ingress is the only freshness source for an intake
  mode, that mode must move to `TRANSPORT_FRESHNESS` narrow-read semantics or
  stay manual/controlled before the old write bridge is removed.
- Detailed retry intervals, threshold tuning, and lost-worker cleanup are left
  to worker-runtime policy follow-up.

Acceptance:

- A fresh transport connection alone cannot make a worker schedulable.
- A fresh worker heartbeat/report plus clear block sources can make a worker
  schedulable only when the worker's positive recovery mode allows
  worker-owned evidence.
- Task completion or assignment-release evidence can make an `occupied` worker
  schedulable only after worker-runtime capacity/admission checks pass.
- Task completion or assignment-release evidence does not clear transport,
  operator, command-drain, or other unrelated block sources.
- A fresh transport heartbeat can participate in recovery only through the
  narrow transport freshness view and only for `TRANSPORT_FRESHNESS` workers.
- A fresh heartbeat/report for `MANUAL_OR_CONTROLLED` workers does not reopen
  eligibility.
- A fresh worker/transport heartbeat is ignored when worker declaration, slot,
  group membership, scheduling attributes, or recovery mode disallow automatic
  recovery.
- The old presence-heartbeat write bridge is transitional and should be removed
  once the mode-specific evidence path exists.
- WebSocket/socket/embedded/polling/external SDK focused tests prove that
  recovery follows the configured mode and never comes from transport
  connected/heartbeat writes into worker-runtime.

## NDE-4 Remove Push-Presence Scheduling-Mutation Residue

Scope:

- After NDE-0A through NDE-3 pass, remove or retire:
  - `WorkerPresenceIngress`
  - `WorkerPresenceSessionPublisher`
  - transport-owned `WorkerSessionPresenceEvent`
- Remove push-presence capabilities from adapter bootstrap assembly.
- Do not delete `TransportEndpointLeaseStore`, `TransportEndpointLeasePublisher`,
  endpoint lease records, or Redis/in-memory endpoint lease stores as part of
  this roadmap.
- Retained endpoint lease evidence must stay transport-local. It may support
  transport diagnostics, currentness checks, or narrow freshness evidence, but it
  must not mutate worker-runtime scheduling state or drive producer-side target
  resolution.
- Preserve the current delivery target resolver, or replace it in the same
  slice, before deleting `WorkerPresenceRuntime`.
- Preserve or replace the delivery target evidence writer before deleting
  session-presence writes that currently update delivery targets.
- If no replacement writer proof exists, do not delete the
  `WorkerRuntimePresenceIngress` path that currently keeps
  `SelectedWorkerDeliveryTargetEvidence` current.

Acceptance:

- No production code path uses transport connected/heartbeat to mutate
  worker-runtime scheduling state.
- No production code path reads endpoint lease to choose dispatch target.
- No worker-runtime schedulability path reads endpoint lease as reachability,
  readiness, lifecycle, or positive recovery truth.
- Dispatch producer still resolves the delivery target before transport
  enqueue; transport endpoint/session reads do not become the producer-side
  target resolver.
- `SelectedWorkerDeliveryTargetEvidence` still has a named writer after
  push-presence residue is removed.
- The delivery target writer is separate from scheduling eligibility and
  `TRANSPORT_FRESHNESS`; it does not become a transport endpoint/session read in
  the assigned-dispatch producer.
- Architecture guards forbid transport/adapters from opening schedulability.
- Architecture guards forbid engine from reading transport session/endpoint
  state as scheduling truth.
- If endpoint lease remains, focused tests or guards prove stale/replaced
  endpoint lease release does not produce a negative worker block signal.

## NDE-5 Docs, Guards, And Proof Registry

Scope:

- Update:
  - `transport/AGENTS.md`
  - `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  - `xa-mass-worker-runtime/README.md`
  - `xa-mass-worker-runtime/CONTRACTS.md`
  - `roadmap/WORKER_STATE_SYNC_ELIGIBILITY_DISCUSSION.md`
  - `doc/PROOF_REGISTRY.md` if proof ownership changes.

Acceptance:

- Docs state the invariant:
  ```text
  Negative evidence may close dispatch eligibility immediately as best-effort protection.
  Positive evidence may only request worker-runtime recheck.
  Only worker-runtime may reopen dispatch eligibility after validating worker declaration,
  slot, group membership, gates, holds, capacity, and recovery mode.
  ```
- Docs no longer describe transport endpoint lease or transport presence as
  worker reachability truth.
- Guards cover forbidden positive scheduling writes from transport/adapters.
- Guards cover forbidden engine reads of transport network/session state.

## NDE-6 Engine Positive-Clear Migration

Engine currently has control-policy paths that can clear worker dispatch gates.
That conflicts with the final invariant, but it is not part of the first
transport/worker-runtime slice.

Scope:

- move worker state/command positive gate-clear policy out of engine and into
  worker-runtime;
- engine submits worker report/command evidence to worker-runtime or an
  integration-owned evidence ingress;
- engine task completion / assignment release may submit release evidence to
  worker-runtime occupancy/admission owners, but not direct gate-clear commands;
- engine no longer receives or calls `WorkerDispatchGateRuntime` for positive
  clear decisions;
- decide whether engine delivery failures that prove worker unreachable should
  emit best-effort negative worker block signals;
- if engine delivery failures emit negative signals, route them through the same
  negative-only block port and allowlisted producer classification as transport.

Acceptance:

- `DefaultWorkerDispatchAvailabilityPolicy` or its successor no longer clears
  dispatch gates from engine-owned policy.
- Engine control paths do not call `clearWorkerDispatchDisable`.
- Engine task-completion or assignment-release paths can release
  worker-runtime capacity only through the worker-runtime release/evidence
  surface; they do not clear unrelated block sources.
- Engine does not import transport session/endpoint/freshness facts for worker
  scheduling or recovery.
- Focused engine/worker-runtime tests prove `AVAILABLE` state reports or command
  lifecycle evidence cannot reopen eligibility except through worker-runtime
  positive recovery.
- Focused engine/worker-runtime tests prove task completion can make an
  `occupied` worker schedulable only when worker-runtime capacity/admission,
  block sources, hold state, and recovery rules allow it.
- NDE-6 does not block `NDE-0A` and the transport/worker-runtime negative signal
  path, but full roadmap completion requires it.

## Suggested Implementation Order

1. Complete NDE-0 inventory and naming decision.
2. Complete NDE-0A transport/worker-runtime positive evidence, engine release
   evidence, external port, and delivery-target-change decisions.
3. Converge worker-runtime negative block and selection gating inside
   `WorkerRegistry + WorkerDispatchGateRuntime` in memory and Redis.
4. Prove positive recovery mode classification:
   `WORKER_EVIDENCE`, `TRANSPORT_FRESHNESS`, or `MANUAL_OR_CONTROLLED`.
5. Wire transport and delivery failure producers to the negative block API with
   natural group/bucket context when present and allowlisted failure mapping.
6. Preserve the current delivery target resolver, or replace it in the same
   slice if this roadmap changes it.
7. Remove push-presence scheduling-mutation residue while keeping endpoint lease
   transport-local if it still has evidence/currentness/diagnostics value.
8. Complete NDE-6 engine positive-clear migration.
9. Update docs and guards in the same slice that deletes old residue or moves
   engine positive-clear ownership.
10. Handle NDE-1B hold/lost cleanup as a worker-runtime policy follow-up only if
    implementation pressure proves it is needed.

## Verification Candidates

NDE-0 / NDE-0A proof:

```bash
rg -n "WorkerRuntimePresenceIngress|refreshSlotHeartbeat|WorkerDeliveryTargetView|SelectedWorkerDeliveryTargetEvidence|TaskDispatchRoutingSubmitter|WorkerDispatchGateRuntime|DispatchAvailabilitySource|WorkerSelectionOwner|getWorkerReachability|WorkerReachabilityView|release.*Worker|complete.*Worker" sdk xa-mass-worker-runtime xa-mass-engine platform_infra transport --glob "*.java" --glob "!**/target/**"
```

NDE-0 / NDE-0A acceptance is an inventory and decision proof, not a runtime test
proof. The inventory must explicitly cover delivery target writer, freshness
view shape, external block port placement, release evidence placement, source
mapping, selection reachability migration, and concrete producer seams.

Compile after implementation slices begin:

```bash
mvn -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis,xa-mass-worker-runtime,transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
```

Later implementation tests to create or update:

```text
InMemoryWorkerRegistryTest
RedisWorkerRegistryTest
WorkerCandidateIndexTest
WorkerManagerTest
WorkerDispatchEligibilityRuntimeTest or an inventory-selected equivalent
WebSocketSessionRegistryTest
SocketSessionManagerTest
PollingSessionEvidenceDriverTest
TransportConvergenceArchitectureGuardTest
```

Do not place non-existent placeholder tests in runnable Maven commands. The
exact test list must be corrected after NDE-0/NDE-0A inventory. Verification
commands must not use `-Dsurefire.failIfNoSpecifiedTests=false` to hide missing
tests.

Additional NDE-6 proof when engine migration starts:

```bash
mvn -pl xa-mass-engine,xa-mass-worker-runtime,sdk/xa-mass-embedded-sdk -am test -Dtest='WorkerControlServiceTest,TaskWorkerEligibilityTest,EngineSchedulingCoreArchitectureGuardTest,MassEngineAssemblyBoundaryTest'
```

Residue checks after completion:

```bash
rg -n "WorkerPresenceIngress|WorkerPresenceSessionPublisher|WorkerSessionPresenceEvent" transport sdk xa-mass-worker-runtime --glob "*.java" --glob "*.md" --glob "!**/target/**"
rg -n "connected.*schedulable|heartbeat.*schedulable|make.*schedulable|open.*eligibility" transport sdk xa-mass-worker-runtime --glob "*.java" --glob "!**/target/**"
rg -n "refreshWorkerHeartbeat.*clear|refreshSlotHeartbeat.*schedulable|heartbeat.*clearDispatchDisable" sdk xa-mass-worker-runtime transport --glob "*.java" --glob "!**/target/**"
rg -n "class WorkerSchedulingIndex|interface WorkerSchedulingIndex|new WorkerSchedulingIndex" xa-mass-worker-runtime platform_infra --glob "*.java" --glob "!**/target/**"
rg -n "clearWorkerDispatchDisable\\(" transport sdk --glob "*.java" --glob "!**/target/**"
rg -n "clearWorkerDispatchDisable\\(|WorkerDispatchGateRuntime" xa-mass-engine --glob "*.java" --glob "!**/target/**"
rg -n "getWorkerReachability\\(|WorkerReachabilityView|WorkerReachabilityState\\.ONLINE" xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence --glob "*.java"
rg -n "currentEndpointLease|TransportEndpointLease" xa-mass-worker-runtime xa-mass-engine --glob "*.java" --glob "!**/target/**"
rg -n "currentEndpointLease|TransportEndpointLease" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskDispatchRoutingSubmitter.java
```

If a `WorkerTransportFreshnessView` or equivalent is introduced, add a focused
shape guard for that contract: it must not expose `sessionToken`,
`connectionId`, `adapterMailboxKey`, endpoint lease fields, `routeKey`, or
session handles. Do not use a broad worker-runtime scan for these names because
the pre-transport delivery target resolver may still legitimately carry
`adapterMailboxKey`.

## Roadmap Completion Criteria

This roadmap is complete only when:

- the core invariant is true in code and docs:
  - negative evidence may close dispatch eligibility immediately as best-effort
    protection;
  - positive evidence may only request worker-runtime recheck;
  - only worker-runtime may reopen dispatch eligibility after validating worker
    declaration, slot, group membership, gates, holds, capacity, and recovery
    mode;
- NDE-6 has removed engine-owned positive gate-clear policy;
- transport/adapters can only best-effort block worker dispatch eligibility;
- transport/adapters and delivery-failure producers use a negative-only block
  port and cannot access gate clear;
- engine task completion / assignment release uses a worker-runtime
  release/evidence surface, not a clear-gate API;
- connected/heartbeat do not directly reopen worker scheduling;
- scheduler acquisition reads existing worker-runtime registry/gate eligibility
  projections;
- `WorkerSelectionOwner` no longer treats transport-derived
  `WorkerReachabilityView` as an independent scheduling gate;
- ordinary reservation/active work/`OCCUPIED` does not remove a multi-capacity
  worker from candidate acquisition by itself;
- task completion/release evidence only updates occupancy/capacity and cannot
  clear unrelated block sources;
- positive recovery mode is explicit per worker/intake path;
- worker declaration/slot/group membership/scheduling attributes are validated
  before any freshness source is read for recovery;
- heartbeat/freshness is evidence ingestion, not direct eligibility reopen;
- only `WORKER_EVIDENCE` workers can use worker-owned heartbeat/report as a
  positive recovery input;
- only `TRANSPORT_FRESHNESS` workers can use the narrow transport freshness view
  as a positive recovery input;
- worker-runtime does not receive transport heartbeat writes as target behavior;
- the transport freshness view does not expose session/endpoint/adapter details;
- `MANUAL_OR_CONTROLLED` workers do not automatically recover from
  heartbeat/freshness evidence;
- assigned dispatch keeps a pre-transport delivery target resolution step, and
  it is not replaced by transport endpoint/session reads;
- push-presence scheduling-mutation residue is removed;
- endpoint lease, if retained, is transport-local evidence/currentness and is not
  worker-runtime schedulability truth or producer-side target resolution;
- docs and guards protect the negative-signal owner boundary.
- hold/lost/cleanup policy remains worker-runtime-owned follow-up and is not
  required for this roadmap's first convergence slice.
