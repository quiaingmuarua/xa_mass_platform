# Worker Runtime Dispatch Eligibility Signal Convergence Roadmap

Status: slice complete, roadmap active. The current landed slice covers the
disconnect negative-signal path, selection-gate owner move, worker-control
eligibility owner move, clear-capable SDK runtime bridge cleanup, and the first
worker-runtime-owned positive recovery path for worker state/command sources.
Transport session presence bridge has been deleted; connected and heartbeat
observations stay transport-local.
Capacity-changing release/final/lock-release evidence now requests dispatch
wakeup through the worker-runtime availability hook. Freshness point-read
recovery, richer release/capacity recovery policy, and a future shared
delivery-target projection remain open.

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
as worker lifecycle state. The current mainline negative block source is a
confirmed current-session disconnect. Other transport failures such as missing
endpoint, final-hop no-endpoint, backpressure, invalid input, shutdown, mailbox
unavailability, and generic failures remain delivery outcomes/failure evidence
and are deferred from this roadmap.

Worker-runtime should own group-scoped dispatch eligibility through the existing
worker registry, candidate acquisition, dispatch gate, heartbeat freshness, and
reserve/admission path. This roadmap must converge those existing owners; it
must not introduce a parallel scheduling index that competes with
`WorkerRegistry` or `WorkerDispatchGateRuntime`.

This also supersedes the older tendency to model dispatchability as three
independent scheduling truths named `readiness`, `reachability`, and
`occupancy`. Those names may remain as evidence or diagnostics inside
worker-runtime, but the executable truth is one worker-runtime dispatch
eligibility decision.

The goal is not a perfect synchronized lifecycle state machine. It is to keep
the schedulable worker set best-effort close to fact while preserving one
scheduling owner.

Current landed slice:

- worker-runtime exposes a negative-only `WorkerDispatchBlockRuntime` port;
- memory and Redis worker registries store source-scoped block metadata and
  reject stale negative observations by `observedAtMillis`;
- adapter-local current-session disconnect emits `TRANSPORT_DISCONNECTED`
  through the negative-only sink when endpoint/session currentness confirms
  the loss;
- worker selection no longer rejects candidates by reading direct reachability
  projection; it consumes worker-runtime registry/gate
  dispatch eligibility;
- worker state/command dispatch eligibility policy is owned by
  `WorkerDispatchEligibilityRuntime` in worker-runtime; `WorkerControlService`
  submits evidence to that runtime instead of receiving the clear-capable gate;
- `EngineRuntimeBridge` / `RuntimeEventBusEngineBridge` no longer receive
  `WorkerDispatchGateRuntime`; `EngineRuntimeKernel.StartedRuntime` and
  `EngineRuntimeKernelConfig` no longer expose the clear-capable gate to
  optional shell bridge wiring;
- `DefaultWorkerDispatchAvailabilityPolicy` no longer clears the gate directly
  for positive `AVAILABLE` state reports; it requests
  `WorkerDispatchRecoveryRuntime`, and `WorkerManager` validates worker meta,
  recovery mode, slot presence, removing state, and active block source before
  clearing that source through `WorkerRegistry.recoverDispatchDisable(...)`;
- the former transport-to-worker-runtime push-presence event bridge has been
  removed. Transport connected/heartbeat observations stay transport-local and
  cannot wake the scheduler through a generic session-event projection. Embedded assigned
  delivery uses worker registration plus local transport binding for
  selected-worker mailbox lookup; split producer runtimes use explicit
  resolver injection;
- worker-runtime claim-close/final evidence and exclusive-lock release now
  request dispatch wakeup through `WorkerAvailabilityWakeupRuntime`; it does
  not clear block sources.
- `SelectedWorkerDeliveryTargetEvidence` no longer carries reachability,
  generation, or observation metadata; assigned delivery target evidence is
  selected worker, mailbox target, and expiry evidence.

Current executable slice is the negative-fast-close lane, selection-gate owner
move, worker-control dispatch eligibility owner move, and removal of
readiness/reachability/occupancy as separate scheduling truth models. SDK
runtime bridge clear-gate cleanup and first positive recovery validation have
landed; the transport-presence event bridge, registry-heartbeat writes, and
presence-to-dispatch-wakeup bridge have also been removed, and
capacity-changing release/final/lock-release evidence now reaches the
worker-runtime dispatch wakeup/recheck hook. Delivery target evidence has been
separated from reachability vocabulary. Freshness point-read recovery, future
shared delivery-target projection, and broader release/delete policy remain
roadmap work, not completed facts.
Delivery target ownership can remain on the current simple implementation for
this slice as long as assigned dispatch does not move target resolution into
transport endpoint/session reads.

Partial completion terms:

- `negative signal slice landed`: NDE-1A/NDE-2 negative block contract and
  producer bridge are implemented.
- `slice complete`: one or more slices landed, but mainline completion criteria
  at the end of this roadmap have not all been proven yet.
- `transport/worker-runtime slice complete`: NDE-0 through NDE-5 are satisfied
  for transport and worker-runtime.
- `mainline unblocked`: the negative block path, selection-gate owner move,
  worker-control eligibility owner move, and readiness/reachability/occupancy
  scheduling-truth convergence are implemented enough for dependent transport
  work; first state/command positive recovery validation has landed, but
  freshness/delete/release phases remain.
- `complete`: all roadmap completion criteria at the end of this roadmap are
  satisfied, residue has been scanned, and current facts can be moved to owning
  docs/archive.

## Boundary Decision

Use evidence push plus worker-runtime-owned eligibility recovery:

```text
transport current-session disconnected
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
  -> runs because worker-runtime accepted a high-value recheck request or a
     bounded maintenance scan selected the worker
  -> reads worker `RecoveryMode`
  -> if `RecoveryMode == FRESHNESS_EVIDENCE`, point-read freshness evidence
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

- worker group scoped dispatch eligibility through one worker-runtime path:
  `WorkerRegistry`, candidate acquisition, slot lifecycle, dispatch gates,
  heartbeat freshness, and reserve/admission;
- reserve / prewarm / hold leases and release;
- occupancy/capacity release projection from task completion or assignment
  release evidence;
- block sources and unblock/recheck policy;
- longer-term lost/removal policy as a worker-runtime follow-up, not a transport
  decision;
- candidate acquisition and final reserve validation.
- positive recovery from worker report/command/release/freshness evidence only
  when worker-runtime recheck is requested by an allowed high-value source or
  bounded maintenance, and the worker's `RecoveryMode` allows freshness
  evidence to be point-read during the recheck.

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

If Redis zsets are used, treat them as `WorkerRegistry` internal projections:

```text
group candidate / eligibility zset
  worker-runtime-maintained candidate index for workers that may compete in a
  worker group after registry/gate/admission projection

recheck due zset
  worker-runtime-maintained bounded maintenance index for blocked or pending
  workers that should be reconsidered later
```

These zsets are implementation indexes, not public queues and not a second
scheduling truth. A zset hit is only a candidate acquisition optimization; final
dispatch eligibility still requires worker-runtime validation of slot,
attributes, `RecoveryMode`, block sources, hold state, capacity, and
reserve/admission.

`reachability`, `readiness`, and `occupancy` should be read as evidence or
diagnostic vocabulary feeding this path, not as three synchronized state models
that separate modules maintain.

Explicit hold/prewarm and lost/cold cleanup can be layered into worker-runtime
policy later. They are not required for the first eligibility signal
convergence slice.

Block sources should be source-scoped and idempotent. External negative sources
for the current mainline slice are intentionally narrow:

```text
TRANSPORT_DISCONNECTED
```

Missing endpoint, final-hop no-endpoint, mailbox unavailable, invalid input,
backpressure, shutdown, and generic failed outcomes remain delivery
outcomes/failure evidence in this roadmap. They can be evaluated later only if
their owner and scheduling impact are proven separately.

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

Recovery has two separate concepts:

```text
recheck request source
  why worker-runtime should spend work evaluating this worker now

RecoveryMode
  whether worker-runtime may point-read freshness evidence when it is already
  rechecking the worker; it is not an event subscription or notification path
```

Heartbeat, transport refresh, and session keepalive are transport or
worker-local evidence updates. They are not worker-runtime ingress events and
are not valid recheck request sources. Recheck is higher-cost work and should
be driven by high-value state-change inputs or bounded worker-runtime
maintenance.

Allowed high-value request sources include:

```text
ITEM_COMPLETED
ASSIGNMENT_RELEASED
CAPACITY_RELEASED
WORKER_STATE_CHANGED
WORKER_COMMAND_CHANGED
OPERATOR_CHANGED
WORKER_REGISTERED_OR_REDECLARED
PERIODIC_MAINTENANCE
```

Disallowed request sources include:

```text
WORKER_HEARTBEAT
TRANSPORT_REFRESH
SESSION_KEEPALIVE
CONNECTED
```

`RecoveryMode` must be explicit. It intentionally does not encode who reported
freshness. Worker-owned heartbeat/report evidence and transport fresh/not-fresh
evidence are both freshness evidence providers; the mode only says whether
worker-runtime may use freshness evidence during recheck.

```text
EXPLICIT_ONLY
  worker-runtime recheck cannot use heartbeat/freshness evidence to reopen
  dispatch eligibility; recovery requires explicit worker-runtime/control
  evidence such as command/operator/state/re-registration policy

FRESHNESS_EVIDENCE
  worker-runtime may point-read freshness evidence while handling an
  already-requested or maintenance-selected recheck
  freshness evidence may come from worker-owned report/heartbeat evidence or
  a narrow transport-owned fresh/not-fresh view
  this mode must be explicitly enabled per worker/intake type and is not the
  default mode
```

Default should be conservative: `EXPLICIT_ONLY` unless the worker or intake path
is explicitly classified. For this roadmap, `RecoveryMode` is stored in worker
attributes / scheduling attributes and travels with the worker fact. Test
workers may opt into `FRESHNESS_EVIDENCE` by declaring that attribute. The mode
is still interpreted only by worker-runtime; heartbeat/freshness evidence cannot
notify worker-runtime, trigger recheck, choose `RecoveryMode`, or upgrade the
recovery mode.

If `FRESHNESS_EVIDENCE` reads transport-owned freshness, that read must be a
narrow fresh/not-fresh point-read and must not expose session, endpoint,
adapter, route, lease, or connection details. Transport freshness remains
transport-owned evidence; it does not become worker-runtime state or a recheck
request.

First implementation mode should favor a stable owner seam over early
performance optimization:

```text
worker-runtime recheck request port
  input: workerId + reason/source + observedAtMillis
  owner: worker-runtime
  first implementation: may recheck synchronously in the caller path or immediate
  worker-runtime service path
  later optimization: may enqueue into a worker-runtime internal recheck due
  zset or maintenance index without changing callers
```

The first version does not need a Redis zset or background scanner to be valid.
If a zset is later introduced, it is an internal implementation detail behind
the same recheck request port. The caller must not know whether worker-runtime
handled the recheck synchronously, stored a due marker, or let bounded
maintenance pick it up later.

For this roadmap, the first allowed recheck request sources are:

```text
ITEM_COMPLETED
ASSIGNMENT_RELEASED
CAPACITY_RELEASED
WORKER_STATE_CHANGED
WORKER_COMMAND_CHANGED
OPERATOR_CHANGED
WORKER_REGISTERED_OR_REDECLARED
PERIODIC_MAINTENANCE
```

Release/capacity evidence is owned by worker-runtime admission/capacity owners.
Engine may submit item-completion or assignment-release evidence, but it must
not directly clear unrelated block sources. Heartbeat, transport refresh, and
session keepalive remain excluded from this request-source allowlist.

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
The current producer is the SDK/starter bridge for accepted current-session
disconnect observations. Any future delivery-failure producer must be
separately allowlisted before it can use this port. Producers must not receive
the symmetric `WorkerDispatchGateRuntime` that can also clear dispatch disable
sources.

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

Worker heartbeat/report refresh, transport refresh, and session keepalive do not
enter worker-runtime as events. They do not directly reopen scheduling and do
not request recheck. They only update their owning evidence stores. Reopening
requires worker-runtime recheck requested by an allowed high-value source or
selected by bounded maintenance, and it is allowed only for workers whose
`RecoveryMode` permits freshness evidence to be point-read.

Recovery order is intentionally coarse:

```text
read worker declaration / slot / group membership / scheduling attributes
  -> confirm recheck was requested by an allowed high-value source or bounded maintenance
  -> read worker RecoveryMode
  -> point-read freshness evidence only if RecoveryMode permits it
  -> apply completion/release evidence only to capacity/admission facts
  -> worker-runtime decides whether the worker may enter scheduling
```

Freshness evidence is ignored for workers whose attributes do not allow the
matching `RecoveryMode`. Freshness events never enter worker-runtime and never
request the recheck themselves. The detailed timing, retry, lost-worker, and
cleanup policy is worker-runtime-owned and should not be over-specified in this
transport boundary roadmap.

This avoids the old ambiguity where `connected` could be mistaken for
business readiness or scheduling eligibility.

Before any future transport-backed freshness provider is allowed to participate
in recovery, the implementation must classify every supported worker intake
mode. WebSocket, socket, polling, embedded, and external SDK workers must either
have worker-owned heartbeat/report evidence or transport freshness evidence
that can be point-read during recheck, or be classified as `EXPLICIT_ONLY`.

Worker state report and command lifecycle evidence may still be positive
evidence. Their dispatch eligibility interpretation is now owned by
worker-runtime through `WorkerDispatchEligibilityRuntime`; richer recovery mode
classification remains follow-up policy.

## Current Code Observations

- Current transport session evidence still has transport-owned
  `TransportEndpointLeaseStore` / `TransportEndpointLeasePublisher`.
- The former push-presence bridge from transport session events into
  worker-runtime projection has been deleted. Transport connected/heartbeat
  observations now update only transport-owned endpoint/session evidence.
- Current-session disconnect is the only transport-origin scheduling signal
  retained by this roadmap; it enters worker-runtime as a narrow negative block.
- Embedded assigned dispatch resolves `selectedWorkerId -> adapterMailboxKey`
  from worker registration plus local transport binding. Split engine-producer
  runtimes require an explicit resolver injection until a future shared
  projection is justified.
- `SelectedWorkerDeliveryTargetEvidence` no longer carries reachability,
  generation, or observed-time metadata.
- Current `WorkerSelectionOwner` no longer rejects candidates by calling
  `WorkerSchedulingViewRuntime.getWorkerReachability(workerId) != ONLINE`.
  Direct reachability projection is guarded as diagnostic/evidence-only for
  selection; candidate rejection now flows through worker-runtime registry/gate
  dispatch eligibility, admission, capacity, locks, and matching.
- Current `WorkerSchedulingViewRuntime` no longer exposes
  `getWorkerReachability`; SDK/operator diagnostics read reachability through
  explicit diagnostic/freshness point-read providers.
- Current `WorkerRegistry` already owns candidate acquisition, slot lifecycle,
  heartbeat freshness, dispatch disable sources, reservation, and lifecycle
  cleanup in memory and Redis implementations.
- Current `WorkerDispatchGateRuntime` already exposes workerId-first
  `disableWorkerDispatch` / `clearWorkerDispatchDisable` methods, while the
  lower registry owner also supports group-scoped operations. This roadmap
  should preserve workerId-only calls for callers without group context and
  allow group/bucket context when the caller naturally has it.
- Current `DefaultWorkerDispatchAvailabilityPolicy` is owned by
  worker-runtime/control as a `WorkerDispatchEligibilityRuntime`.
  `WorkerControlService` submits worker state/command evidence to that runtime
  and does not receive the clear-capable `WorkerDispatchGateRuntime`. Broader
  positive recovery/delete semantics remain deferred scope.
- Current `WorkerAvailabilityWakeupRuntime` is the assembly hook for high-value
  worker-runtime availability changes. Worker-runtime admission/final evidence
  uses it for dispatch wakeup after reservation release, work-final accounting,
  and exclusive-lock release. These wakeups request recheck only; they do not
  clear unrelated block sources.
- Current `EngineRuntimeBridge` and `RuntimeEventBusEngineBridge` accept only
  `TaskEventListenerRegistrar` plus the dispatch wakeup callback; they do not
  accept `WorkerDispatchGateRuntime`.
- Current `MassEngine` no longer passes a clear-capable worker dispatch gate to
  optional shell bridge code, and it no longer wires engine dispatch wakeup from
  transport/session projection.
- Current `EngineRuntimeKernel.StartedRuntime` carries only event listeners and
  the dispatch wakeup callback; it no longer exposes
  `WorkerDispatchGateRuntime`.
- Current `EngineRuntimeKernelConfig` no longer exposes
  `getWorkerDispatchGateRuntime()`. The remaining
  `EngineConfig.getWorkerDispatchGateRuntime()` method is SDK assembly residue
  used to construct the worker-runtime-owned dispatch eligibility policy, not
  a kernel or optional shell bridge surface.
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
- Do not keep reachability projection as an independent selection-time
  scheduling gate. Reachability/freshness may feed worker-runtime recovery, but
  candidate rejection must come from registry/gate/admission projections.
- Do not pull engine control-policy migration into the first
  transport/worker-runtime slice. Track it as deferred conflict/follow-up.
- Do not expose `WorkerDispatchGateRuntime` as the external negative-signal
  contract; it is symmetric and can clear dispatch disables.
- Do not add a parallel `WorkerSchedulingIndex` unless inventory proves the
  existing registry/gate owners cannot be extended; if that happens, this
  roadmap must be revised before implementation.
- Do not delete the current `selectedWorkerId -> adapterMailboxKey` resolver
  path as part of this roadmap unless a same-slice replacement keeps target
  resolution before transport enqueue.
- Do not classify ordinary reservation, active lease, or `OCCUPIED` as a
  candidate-lifecycle hold. Capacity belongs to reserve/admission.
- Do not redesign task attempt timeout/retry in this roadmap.
- Do not redesign public Java SDK worker online/offline APIs in the first
  slice. Public API vocabulary can be handled by a follow-up once the runtime
  owner boundary is stable.
- Do not add a broad lifecycle state machine that requires multiple modules to
  keep `reachability`, `readiness`, and `occupancy` synchronized. These terms
  are worker-runtime evidence/diagnostic vocabulary, not parallel
  dispatchability truth.

## Do Not Start With

Do not start by deleting `TransportEndpointLeaseStore` or by reintroducing a
generic transport session-presence bridge.

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

- Inventory current references and preserve historical deletion proof for:
  - `WorkerPresenceIngress`
  - `WorkerPresenceSessionPublisher`
  - `WorkerSessionPresenceEvent`
  - `TransportEndpointLeaseStore`
  - `TransportEndpointLeasePublisher`
  - `InMemoryWorkerPresenceRuntime`
  - `WorkerReachabilityState`
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
  - removed residue: former `WorkerRuntimePresenceIngress.refreshSlotHeartbeat`;
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
  - adapter current-session disconnect;
  - mailbox/system/backpressure/final-hop failures that must not block workers
    in this roadmap.
- Decide final naming:
  - `WorkerDispatchEligibility`
  - `WorkerDispatchBlock`
  - source-scoped dispatch gate / registry extension names
  - or another worker-runtime-owned name that does not imply a parallel
    scheduling owner.
- Inventory the current delivery target resolver:
  - keep the existing simple selected-worker resolver path for this roadmap
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
- Classify `RecoveryMode` by worker intake mode:
  - `EXPLICIT_ONLY`;
  - `FRESHNESS_EVIDENCE`.
- Inventory how `RecoveryMode` is represented in worker attributes /
  scheduling attributes:
  - default is `EXPLICIT_ONLY`;
  - test workers and explicitly classified intake paths may set
    `FRESHNESS_EVIDENCE`;
  - group policy or slot metadata may later provide defaults, but must not
    override explicit worker attributes without a worker-runtime owner decision.
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
- Inventory explicitly names the former
  `WorkerRuntimePresenceIngress.refreshSlotHeartbeat` as removed transitional
  coupling, and separates registry heartbeat writers from reachability and
  delivery-target projection.
- Inventory does not assume all workers can use heartbeat/report or transport
  freshness as recovery evidence.
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
  `WorkerPresenceIngress`, `InMemoryWorkerPresenceRuntime`, or session-presence
  writes.
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
  stops using transport-derived reachability projection as an independent
  scheduling gate.

## NDE-0A Positive Evidence And Port Boundary Decision

Implementation state:

- External negative-only port: implemented as
  `WorkerDispatchBlockRuntime(blockWorkerDispatch(..., WorkerDispatchBlockSignal))`.
- External source vocabulary: implemented as `WorkerDispatchBlockSource`.
- Internal gate/storage mapping: worker-runtime maps external block sources to
  internal `DispatchAvailabilitySource` values; transport/adapters do not see
  or choose the internal enum.
- Block metadata storage: implemented as `WorkerDispatchBlockRecord` in
  memory/Redis worker registries, keyed by worker/group/source with stale
  `observedAtMillis` rejection.
- Integration owner: SDK/starter assembly bridges accepted current-session
  disconnect observations into the worker-runtime block port. Delivery outcomes
  are not block producers in the landed slice.
- Decided for this roadmap: `RecoveryMode` is stored in worker attributes /
  scheduling attributes and interpreted by worker-runtime.
- Decided for the first recovery slice: recheck requests enter worker-runtime
  through a narrow request port; first implementation may recheck synchronously;
  zset/maintenance indexing is an internal optimization behind the same port.
- Decided for the first recovery slice: allowed request sources are item
  completion, assignment release, capacity release, worker state or command
  change, operator change, worker registration/redeclaration, and bounded
  worker-runtime maintenance.
- Decided for the first recovery slice: release/capacity evidence is applied by
  worker-runtime admission/capacity owners and cannot clear unrelated block
  sources.
- Remaining open outside the next slice: replacement writer for delivery target
  evidence if push-presence is removed.

Scope:

- Finalize the transport/worker-runtime positive recovery mechanism before code
  only at naming and field-shape level:
  - declaration/slot/scheduling-attribute validation;
  - worker-owned heartbeat/report input;
  - transport freshness read input;
  - engine task-completion / assignment-release evidence input;
  - worker-runtime occupancy/capacity release owner;
  - worker attribute key / value shape for `RecoveryMode`;
  - freshness evidence provider shape, if `RecoveryMode == FRESHNESS_EVIDENCE`;
  - stable worker-runtime recheck request port name and record shape;
  - dispatch gate clear policy;
  - bounded maintenance scan owner, if a maintenance scan is added after the
    first synchronous implementation.
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
  - if not, retain the existing simple selected-worker resolver path;
  - name the writer that keeps `SelectedWorkerDeliveryTargetEvidence` current;
  - keep delivery target evidence out of freshness evidence providers;
  - if yes, keep target resolution before transport enqueue and do not replace
    it with transport endpoint/session reads.

Acceptance:

- NDE-3 recovery can proceed with the first-version model above; zset or
  scanner performance work is not a prerequisite.
- NDE-4 cleanup should not start until the delivery target writer is named and
  proven separate from scheduling eligibility and transport freshness.
- NDE-1A/NDE-2 may proceed on the implemented external transport/adapter block
  port because it is negative-only and does not expose clear/schedulable
  capability.
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
  SDK/starter assembly owns the bridge between accepted current-session
  disconnect observations and worker-runtime block ports.
- Block metadata shape and storage owner are named before NDE-1 starts.
- Source mapping is named before NDE-1 starts; current implementation keeps
  transport-origin block sources internal to worker-runtime registry/gate
  storage by mapping `WorkerDispatchBlockSource` to
  `DispatchAvailabilitySource`.
- Every supported worker intake mode has an explicit `RecoveryMode`:
  - `EXPLICIT_ONLY`; or
  - `FRESHNESS_EVIDENCE`.
- Heartbeat, transport refresh, session keepalive, and connected events are
  explicitly forbidden as recheck request sources.
- High-value recheck request sources are explicitly allowlisted, such as item
  completion, assignment release, capacity release, worker state/command change,
  operator change, worker registration/redeclaration, or bounded maintenance.
- Heartbeat/freshness evidence is documented and tested as evidence ingestion,
  not direct eligibility reopen and not a recheck trigger.
- Completion/release evidence is documented and tested as capacity or occupancy
  evidence, not direct eligibility reopen.
- Tests or inventory proof show recheck validates worker attributes/recovery
  mode before reading worker or transport freshness evidence.
- Tests or inventory proof show any transport-backed freshness provider is a
  point-read fresh/not-fresh evidence source only and cannot enumerate
  candidates, request recheck, or wake scheduling.
- Tests or inventory proof show `workerGroupId` on a transport freshness read is
  only a partition hint and cannot override worker-runtime group membership.
- The focused verification list is corrected to use real tests or explicitly
  mark new tests to create; no command may rely on
  `-Dsurefire.failIfNoSpecifiedTests=false` to hide missing proof.

## NDE-1A Worker-Runtime Negative Block And Selection Gate

Implementation state:

- Source-scoped negative block metadata is implemented in `WorkerRegistry` with
  memory and Redis storage.
- `WorkerDispatchBlockRecord` preserves source, reason, `observedAtMillis`, and
  recheck hint; stores reject stale observations for the same worker/group/source.
- `WorkerDispatchBlockRuntime` is implemented by `WorkerManager` and exposes no
  clear/schedulable method.
- Full positive recheck/recovery policy is not implemented in this slice and is
  blocked on NDE-3 owner decisions.

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
- Move worker selection away from direct reachability projection gating:
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
- Freshness evidence is ignored when worker attributes do not allow
  `RecoveryMode == FRESHNESS_EVIDENCE`.
- Duplicate block signals are idempotent.
- Clearing one block source does not clear other block sources.
- Task completion/release evidence can reduce occupancy/capacity, but it does
  not clear block sources.
- `WorkerSelectionOwner` no longer rejects candidates by reading a
  transport-derived `getWorkerReachability(...) == ONLINE` check as an
  independent scheduling gate.
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

Implementation state:

- Adapter-local current-session disconnect maps to `TRANSPORT_DISCONNECTED`
  through the assembly-provided negative sink.
- Transport runtime and concrete adapters still do not import worker-runtime or
  call dispatch gate/block APIs directly.

Scope:

- Producers call only the negative-only `WorkerDispatchBlockRuntime` or
  equivalent port, not `WorkerDispatchGateRuntime`.
- Starter assigned-dispatch translation does not emit worker dispatch blocks in
  this slice. Pre-transport target missing remains delivery failure
  compensation until a separate owner decision proves it is worker-unreachable
  eligibility evidence.
- Transport emits best-effort negative block signals only for clear current
  network-session loss:
  - adapter session disconnected when the adapter confirms current worker
    connection is gone;
- Connected and heartbeat do not emit positive schedulable signals.
- Delivery outcome to block-source mapping is not part of the current slice:
  - confirmed current-session disconnect may emit a negative signal;
  - pre-transport missing target, final-hop `NO_ENDPOINT`, and adapter-local no
    active session are deferred;
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
- Tests prove delivery failures and pre-transport target missing do not disable
  worker dispatch in this slice.

## NDE-3 Recovery And Heartbeat Validation

Status: first release/recheck-request slice landed. Freshness point-read
recovery and richer release/capacity recovery policy remain follow-up
worker-runtime policy, not part of the current mainline completion gate.

First implementation decisions:

- `RecoveryMode` is stored in worker attributes / scheduling attributes and
  defaults to `EXPLICIT_ONLY`.
- A narrow worker-runtime recheck request port receives high-value recheck
  requests. Exact Java names can be chosen during implementation, but the port
  must not expose gate-clear capability to callers.
- First-version recheck may be synchronous. A recheck-due zset, delayed queue,
  or maintenance scanner is optional internal optimization and must remain
  behind the same port.
- Allowed recheck request sources are item completion, assignment release,
  capacity release, worker state/command/operator change,
  worker registration/redeclaration, and bounded worker-runtime maintenance.
- Task completion / assignment release evidence is applied by worker-runtime
  occupancy/admission/capacity owners and cannot clear unrelated block sources.
- Task completion / assignment release / exclusive-lock release now requests
  dispatch wakeup through `WorkerAvailabilityWakeupRuntime` after the
  worker-runtime admission owner applies the capacity change.
- Transport freshness, if used, is only a point-read provider during an existing
  recheck and only for workers whose `RecoveryMode` is `FRESHNESS_EVIDENCE`.
- Worker heartbeat, transport refresh, session keepalive, and connected events
  are not recheck request sources.
- The previous transport heartbeat-to-registry write bridge has been
  removed. There is no remaining generic session-presence bridge; embedded
  delivery-target lookup uses worker registration plus local transport binding,
  and future transport-backed freshness must be exposed only through explicit
  point-read providers.

Scope:

- Worker-runtime recovery checks the configured freshness evidence source when
  a blocked worker is due for recheck.
- Recheck is requested only by worker-runtime bounded maintenance or high-value
  state-change events such as item completion, assignment release, capacity
  release, worker state/command change, operator change, or worker
  registration/redeclaration.
- Worker heartbeat, transport refresh, session keepalive, and connected events
  are local evidence updates only; they do not enter worker-runtime as events
  and must not request recheck.
- Worker-runtime applies task completion / assignment release evidence only to
  occupancy, reserve, and admission capacity state.
- First implementation may perform this recheck synchronously from the request
  source. Performance-oriented due indexes or maintenance scans can be added
  later without changing caller contracts.
- Worker-runtime validates worker declaration, slot, group membership,
  scheduling attributes, and `RecoveryMode` before reading freshness evidence.
- Worker heartbeat/report and transport freshness are both freshness evidence
  providers. `RecoveryMode` does not distinguish who reported heartbeat.
- If the freshness provider is transport-backed, worker-runtime may point-read
  it only through a narrow fresh/not-fresh view during an existing recheck.
- `EXPLICIT_ONLY` workers do not use heartbeat/freshness evidence for recovery.
- Explicit worker readiness/drain reports are modeled as block sources or gate
  sources, not a parallel readiness state machine.
- If a deployment needs transport-backed freshness for an intake mode, that
  mode must either use `FRESHNESS_EVIDENCE` plus a narrow transport-backed
  point-read provider, or stay `EXPLICIT_ONLY`.
- Detailed retry intervals, threshold tuning, and lost-worker cleanup are left
  to worker-runtime policy follow-up.

Acceptance:

- A fresh transport connection alone cannot make a worker schedulable.
- A fresh worker heartbeat/report or transport freshness update alone does not
  notify worker-runtime, cannot request recheck, and cannot make a worker
  schedulable.
- A worker heartbeat/report may be considered only when worker-runtime is
  already rechecking due to an allowed high-value request or bounded maintenance,
  and only when the worker's `RecoveryMode` allows freshness evidence to be
  point-read.
- Task completion or assignment-release evidence can make an `occupied` worker
  eligible for recheck, and can make the worker schedulable only after
  worker-runtime capacity/admission checks pass.
- Task completion or assignment-release evidence does not clear transport,
  operator, command-drain, or other unrelated block sources.
- A fresh transport heartbeat can participate in recovery only as point-read
  evidence during an already-requested or maintenance-selected recheck, only
  through the narrow transport freshness view, and only for `FRESHNESS_EVIDENCE`
  workers.
- A fresh heartbeat/report for `EXPLICIT_ONLY` workers does not participate in
  recovery.
- A fresh worker/transport heartbeat is ignored when worker declaration, slot,
  group membership, scheduling attributes, or `RecoveryMode` disallow freshness
  evidence.
- The former transport heartbeat write bridge has been removed; future freshness
  providers must not recreate transport/session event push into worker-runtime
  scheduling state.
- WebSocket/socket/embedded/polling/external SDK focused tests prove that
  recovery follows the configured `RecoveryMode` and never comes from transport
  connected/heartbeat writes, heartbeat notifications, or heartbeat-triggered
  recheck.

## NDE-4 Remove Push-Presence Scheduling-Mutation Residue

Status: push-presence cleanup landed. Transport connected/heartbeat no longer
refresh worker registry heartbeat, write worker-runtime scheduling freshness, or
wake the scheduler through a generic session-event projection. Embedded delivery-target
lookup is derived from worker registration plus local transport binding; a
future shared projection remains a separate deployment decision.

Scope:

- Keep the former push-presence capabilities removed from adapter bootstrap
  assembly.
- Do not delete `TransportEndpointLeaseStore`, `TransportEndpointLeasePublisher`,
  endpoint lease records, or Redis/in-memory endpoint lease stores as part of
  this roadmap.
- Retained endpoint lease evidence must stay transport-local. It may support
  transport diagnostics, currentness checks, or narrow freshness evidence, but it
  must not mutate worker-runtime scheduling state or drive producer-side target
  resolution.
- Preserve the embedded delivery target resolver and split-runtime explicit
  resolver requirement unless a future shared projection replaces them.
- Current allowed state: no transport session event may update worker-runtime
  scheduling or diagnostic reachability projection, and transport
  connected/heartbeat must not call `WorkerHeartbeatRuntime`, refresh registry
  heartbeat, request dispatch wakeup/recheck, or open eligibility.

Acceptance:

- No production code path uses transport connected/heartbeat to mutate
  worker-runtime scheduling state.
- No production code path reads endpoint lease to choose dispatch target.
- No worker-runtime schedulability path reads endpoint lease as reachability,
  readiness, lifecycle, or positive recovery truth.
- Dispatch producer still resolves the delivery target before transport
  enqueue; transport endpoint/session reads do not become the producer-side
  target resolver.
- `SelectedWorkerDeliveryTargetEvidence` still has a named resolver or
  projection source after push-presence residue is removed.
- The delivery target resolver/projection source is separate from scheduling eligibility and
  freshness evidence providers; it does not become a transport endpoint/session
  read in the assigned-dispatch producer.
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

## NDE-6 Clear-Capable Surface Cleanup And Validated Recovery

The worker state/command dispatch eligibility owner has moved out of engine
control and into worker-runtime/control for `WorkerControlService`:
engine-control code submits evidence to `WorkerDispatchEligibilityRuntime`
instead of receiving `WorkerDispatchGateRuntime`.

That owner move was not the same as full recovery semantics. NDE-6 now has one
landed bridge-cleanup slice and one landed first recovery-validation slice:

```text
NDE-6A clear-capable shell-surface cleanup
  landed: WorkerDispatchGateRuntime has been removed from SDK/starter runtime
  bridge and engine started-runtime output

NDE-6B validated positive recovery
  landed first pass: positive state/command evidence enters
  WorkerDispatchRecoveryRuntime and validates worker meta, RecoveryMode, slot
  presence, removing state, and active block source before clearing only that
  source. Full freshness point-read and release/capacity recovery remain open.
```

Current `DefaultWorkerDispatchAvailabilityPolicy` is no longer a direct positive
clear owner. `AVAILABLE` state reports request recovery through
`WorkerDispatchRecoveryRuntime`; `WorkerManager` owns the synchronous first-pass
validation and delegates the actual source-scoped clear to
`WorkerRegistry.recoverDispatchDisable(...)`.

High-frequency heartbeat/freshness evidence is not a recheck request source.
Richer cleanup and tuning remain worker-runtime follow-up policy and do not
block the negative-fast-close slice.

### NDE-6A Clear-Capable Shell-Surface Cleanup

Status: landed.

Scope:

- remove `WorkerDispatchGateRuntime` from `EngineRuntimeBridge.start(...)`
  overloads and from `RuntimeEventBusEngineBridge`;
- remove `WorkerDispatchGateRuntime` from
  `EngineRuntimeKernel.StartedRuntime` and from the `MassEngine` bridge call;
- remove `WorkerDispatchGateRuntime` from `EngineRuntimeKernelConfig` if it is
  only used to populate `StartedRuntime`; do not leave an unused clear-capable
  config method after the bridge surface is narrowed;
- if the optional shell bridge needs runtime context, pass only
  `TaskEventListenerRegistrar`, `dispatchWakeupCallback`, or another explicitly
  narrow shell surface that cannot clear worker dispatch gates;
- add or update SDK/engine architecture guards so
  `EngineRuntimeBridge`, `RuntimeEventBusEngineBridge`, `MassEngine`, and
  `EngineRuntimeKernel.StartedRuntime` cannot import or mention
  `WorkerDispatchGateRuntime` or `clearWorkerDispatchDisable`; if
  `EngineRuntimeKernelConfig` still mentions the gate, the guard must require a
  documented worker-runtime assembly use rather than allowing optional shell
  bridge exposure;
- keep `WorkerDispatchGateRuntime` internal to worker-runtime/control and
  worker-runtime assembly surfaces that are not optional shell bridges.

Acceptance:

- SDK runtime bridge implementations cannot receive the clear-capable dispatch
  gate.
- `MassEngine.start(...)` does not pass `WorkerDispatchGateRuntime` to
  shell-side bridge code.
- `EngineRuntimeKernel.StartedRuntime` no longer exposes
  `WorkerDispatchGateRuntime`.
- `EngineRuntimeKernelConfig` no longer exposes `WorkerDispatchGateRuntime`
  unless implementation proves a non-shell worker-runtime assembly need and
  documents that exception in this roadmap.
- `RuntimeEventBusEngineBridge` remains event-bus shell wiring only.
- Focused guards fail if SDK/starter runtime bridge classes import
  `WorkerDispatchGateRuntime` or call `clearWorkerDispatchDisable`.

### NDE-6B Validated Positive Recovery

Status: first validation slice landed; broader recovery policy remains active.

Scope:

- keep worker state/command dispatch eligibility interpretation in
  worker-runtime/control;
- engine submits worker report/command evidence to worker-runtime or an
  integration-owned evidence ingress;
- engine task completion / assignment release may submit release evidence to
  worker-runtime occupancy/admission owners, but not direct gate-clear commands;
- task completion / assignment release / capacity release may request
  worker-runtime recheck because they are high-value capacity-changing events;
- replace direct `AVAILABLE` positive clear with a worker-runtime recovery
  request; first-pass validation must at least check worker meta,
  `RecoveryMode`, current slot existence, removing state, and the active block
  source before clearing only that source;
- keep hold, capacity/admission, release evidence, and freshness point-read
  validation visible as follow-up worker-runtime policy unless the current
  slice implements them explicitly;
- decide in a later slice whether any engine delivery failure is strong enough
  to prove worker unreachable and emit a best-effort negative worker block
  signal;
- if a future delivery-failure producer emits negative signals, route it through
  the same negative-only block port and a separately proven producer
  classification. This is not part of the current disconnect-first slice.

Acceptance:

- `DefaultWorkerDispatchAvailabilityPolicy` or its successor remains
  worker-runtime/control owned.
- Positive state/command evidence cannot reopen eligibility except through
  worker-runtime recovery validation.
- `DefaultWorkerDispatchAvailabilityPolicy` must not call
  `clearWorkerDispatchDisable(...)` directly for `AVAILABLE`.
- `WorkerDispatchRecoveryRuntime` or its successor is the only default positive
  recovery entry from worker state/command evidence.
- The first synchronous recovery path rejects missing or removing worker slots,
  validates recovery mode for non-controlled sources, and clears only the
  matching active block source.
- Engine control paths do not receive `WorkerDispatchGateRuntime` and do not
  call `clearWorkerDispatchDisable`.
- Engine task-completion or assignment-release paths can release
  worker-runtime capacity only through the worker-runtime release/evidence
  surface; they do not clear unrelated block sources.
- Engine does not import transport session/endpoint/freshness facts for worker
  scheduling or recovery.
- Focused engine/worker-runtime tests prove `AVAILABLE` state reports or command
  lifecycle evidence cannot reopen eligibility except through worker-runtime
  positive recovery.
- Focused worker-runtime tests prove source-scoped recovery keeps unrelated
  block sources active and rejects recovery for removing workers.
- Focused engine/worker-runtime tests prove task completion can make an
  `occupied` worker eligible for worker-runtime recheck, and schedulable only
  when worker-runtime capacity/admission, block sources, hold state, and
  recovery rules allow it.
- Capacity/admission, hold, release evidence, and freshness point-read recovery
  remain follow-up policy unless the current implementation slice proves them.

## Suggested Implementation Order

1. Complete NDE-0 inventory and naming decision.
2. Converge worker-runtime negative block and selection gating inside
   `WorkerRegistry + WorkerDispatchGateRuntime` in memory and Redis.
3. Wire confirmed current-session disconnect to the negative block API. Keep
   pre-transport target missing, final-hop no-endpoint, and other delivery
   failures as delivery outcomes/failure evidence until a separate owner
   decision proves they should affect dispatch eligibility.
4. Move worker-control state/command dispatch eligibility interpretation into
   worker-runtime and keep engine control as the evidence ingress/read surface.
5. Remove reachability from selection-facing scheduling view and prove
   readiness/reachability/occupancy remain evidence/diagnostic vocabulary, not
   separate scheduling truth models.
6. Remove `WorkerDispatchGateRuntime` from the SDK/starter runtime bridge,
   `EngineRuntimeKernel.StartedRuntime`, and any now-unused
   `EngineRuntimeKernelConfig` clear-gate accessor; keep optional shell bridge
   wiring event/wakeup-only.
7. Preserve the current delivery target resolver, or replace it in the same
   slice if this roadmap changes it.
8. Add the worker-runtime recovery request port and first synchronous recovery
   path for allowlisted state/command sources. Keep any due zset, scanner,
   freshness point-read, release evidence, or capacity/admission recovery as
   later internal policy behind the same owner.
9. Apply task completion / assignment-release evidence through worker-runtime
   occupancy/admission/capacity owners; do not clear unrelated block sources.
10. Update docs and guards in the same slice that moves eligibility ownership.
11. Follow-up only when needed: prove `RecoveryMode` classification,
   remove push-presence scheduling-mutation residue, add worker-runtime
   due-index performance optimization, or handle hold/lost cleanup.

## Verification Candidates

NDE-0 / NDE-0A proof:

```bash
rg -n "WorkerRuntimePresenceIngress|refreshSlotHeartbeat|SelectedWorkerDeliveryTargetEvidence|TaskDispatchRoutingSubmitter|WorkerDispatchGateRuntime|DispatchAvailabilitySource|WorkerSelectionOwner|getWorkerReachability|release.*Worker|complete.*Worker" sdk xa-mass-worker-runtime xa-mass-engine platform_infra transport --glob "*.java" --glob "!**/target/**"
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

NDE-6A / NDE-6B proof:

```bash
mvn -pl xa-mass-engine,xa-mass-worker-runtime,sdk/xa-mass-embedded-sdk -am test -Dtest='WorkerControlServiceTest,TaskWorkerEligibilityTest,EngineSchedulingCoreArchitectureGuardTest,MassEngineAssemblyBoundaryTest,WorkerManagerTest,WorkerSelectionContractGuardTest'
mvn -pl platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis,xa-mass-worker-runtime,sdk/xa-mass-embedded-sdk -am test -Dtest='InMemoryWorkerRegistryTest,RedisWorkerRegistryTest,WorkerManagerTest'
rg -n "WorkerDispatchGateRuntime|clearWorkerDispatchDisable" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/EngineRuntimeBridge.java sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/RuntimeEventBusEngineBridge.java sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassEngine.java xa-mass-engine/src/main/java/com/xa/mass/engine/EngineRuntimeKernel.java xa-mass-engine/src/main/java/com/xa/mass/engine/EngineRuntimeKernelConfig.java
rg -n "clearWorkerDispatchDisable" xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/control/DefaultWorkerDispatchAvailabilityPolicy.java xa-mass-engine/src/main/java/com/xa/mass/engine/control/WorkerControlService.java
```

NDE-4A transport heartbeat-write, wakeup, and push-presence deletion
proof:

```bash
mvn -pl sdk/xa-mass-embedded-sdk,transport/transport_runtime -am test -Dtest='MassApplicationDistributedTransportTest,MassEngineAssemblyBoundaryTest,TransportConvergenceArchitectureGuardTest'
mvn -pl transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter test -Dtest='PollingSessionEvidenceDriverTest,SocketSessionManagerTest,WebSocketSessionRegistryTest,WebSocketSessionEvidenceRefresherTest'
rg -n "WorkerHeartbeatRuntime|refreshWorkerHeartbeat|refreshSlotHeartbeat" sdk/xa-mass-embedded-sdk/src/main/java transport --glob "*.java" --glob "!**/target/**"
rg -n "setDispatchWakeupCallback|dispatchWakeupCallback|notifyDispatchWakeup" xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/presence
rg -n "interface\\s+WorkerPresenceRuntime|import\\s+.*\\.WorkerPresenceRuntime;|setWorkerPresenceRuntime\\(" sdk/xa-mass-embedded-sdk/src/main/java xa-mass-worker-runtime/src/main/java --glob "*.java"
rg -n "getWorkerPresenceRuntime\\(\\)\\.setDispatchWakeupCallback" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassEngine.java
```

NDE-3 first release/recheck-request proof:

```bash
mvn -pl xa-mass-worker-runtime -am test -Dtest='WorkerManagerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Residue checks after completion:

```bash
rg -n "WorkerPresenceIngress|WorkerPresenceSessionPublisher|WorkerSessionPresenceEvent" transport sdk xa-mass-worker-runtime --glob "*.java" --glob "*.md" --glob "!**/target/**"
rg -n "connected.*schedulable|heartbeat.*schedulable|make.*schedulable|open.*eligibility" transport sdk xa-mass-worker-runtime --glob "*.java" --glob "!**/target/**"
rg -n "refreshWorkerHeartbeat.*clear|refreshSlotHeartbeat.*schedulable|heartbeat.*clearDispatchDisable" sdk xa-mass-worker-runtime transport --glob "*.java" --glob "!**/target/**"
rg -n "class WorkerSchedulingIndex|interface WorkerSchedulingIndex|new WorkerSchedulingIndex" xa-mass-worker-runtime platform_infra --glob "*.java" --glob "!**/target/**"
rg -n "clearWorkerDispatchDisable\\(" transport sdk --glob "*.java" --glob "!**/target/**"
rg -n "clearWorkerDispatchDisable\\(" xa-mass-engine/src/main/java --glob "*.java" --glob "!**/target/**"
rg -n "WorkerDispatchGateRuntime|clearWorkerDispatchDisable" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/EngineRuntimeBridge.java sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/RuntimeEventBusEngineBridge.java sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassEngine.java xa-mass-engine/src/main/java/com/xa/mass/engine/EngineRuntimeKernel.java xa-mass-engine/src/main/java/com/xa/mass/engine/EngineRuntimeKernelConfig.java
rg -n "WorkerDispatchGateRuntime" xa-mass-engine/src/main/java/com/xa/mass/engine/control --glob "*.java" --glob "!**/target/**"
rg -n "getWorkerReachability\\(|WorkerReachabilityState\\.ONLINE|worker transport unreachable" xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/selection --glob "*.java"
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
- transport/adapters can only best-effort block worker dispatch eligibility
  through explicitly allowlisted negative sources;
- the current allowlisted producer is confirmed current-session disconnect via
  the negative-only block port; no producer can access gate clear;
- connected/heartbeat do not directly reopen worker scheduling;
- heartbeat, transport refresh, session keepalive, and connected events remain
  local evidence updates; they do not notify worker-runtime or request
  worker-runtime recheck;
- worker-runtime recheck request sources are explicitly allowlisted and limited
  to high-value state-change events or bounded maintenance;
- high-value recheck request callers use a narrow worker-runtime recheck request
  port and cannot call gate-clear capability directly;
- first implementation may handle recheck synchronously, and any future recheck
  due zset, delayed queue, or maintenance scanner remains worker-runtime
  internal optimization behind the same port;
- scheduler acquisition reads existing worker-runtime registry/gate eligibility
  projections;
- `WorkerSelectionOwner` no longer treats transport-derived reachability
  projection as an independent scheduling gate;
- the selection-facing `WorkerSchedulingViewRuntime` does not expose
  `getWorkerReachability`;
- engine control submits worker state/command evidence to
  `WorkerDispatchEligibilityRuntime` and does not receive
  `WorkerDispatchGateRuntime`;
- optional SDK/starter runtime bridge surfaces, including `EngineRuntimeBridge`,
  `RuntimeEventBusEngineBridge`, `MassEngine` bridge wiring, and
  `EngineRuntimeKernel.StartedRuntime`, do not expose
  `WorkerDispatchGateRuntime` or any clear-capable worker dispatch gate;
- `EngineRuntimeKernelConfig` does not retain a clear-capable gate accessor
  unless it has a documented non-shell worker-runtime assembly use;
- `RecoveryMode` is explicit per worker/intake path;
- first implementation stores `RecoveryMode` in worker attributes / scheduling
  attributes with default `EXPLICIT_ONLY`;
- worker declaration, slot, group membership, scheduling attributes, block
  sources, holds, capacity, and `RecoveryMode` are validated before any worker
  can reopen dispatch eligibility;
- `AVAILABLE` state reports, command lifecycle updates, and release/completion
  evidence request or enter worker-runtime recovery validation instead of
  directly bypassing recovery checks through a positive gate clear;
- heartbeat/freshness is local evidence update or point-read evidence, not
  direct eligibility reopen, worker-runtime notification, or recheck trigger;
- worker-runtime does not receive transport heartbeat writes as target
  scheduling behavior;
- if transport freshness is retained, the freshness view is narrow and does not
  expose session, endpoint, adapter mailbox, route, or connection details;
- `EXPLICIT_ONLY` workers do not automatically recover from heartbeat/freshness
  evidence;
- `FRESHNESS_EVIDENCE` workers may use freshness evidence during recheck without
  distinguishing whether the evidence came from worker report or transport
  freshness;
- ordinary reservation/active work/`OCCUPIED` does not remove a multi-capacity
  worker from candidate acquisition by itself;
- task completion/release evidence only updates occupancy/capacity/reservation
  state through worker-runtime, may request worker-runtime recheck, and cannot
  clear unrelated block sources;
- readiness, reachability, and occupancy are documented and guarded as
  worker-runtime evidence/diagnostic vocabulary, not separate scheduling truth
  models;
- assigned dispatch keeps a pre-transport delivery target resolution step, and
  it is not replaced by transport endpoint/session reads;
- push-presence scheduling-mutation residue is removed; any future
  delivery-target or freshness evidence writer must be named and must not own
  schedulability;
- endpoint lease, if retained, is transport-local evidence/currentness and is not
  worker-runtime schedulability truth or producer-side target resolution;
- docs and guards protect the negative-signal owner boundary.
- hold/lost/cleanup policy remains worker-runtime-owned follow-up only after the
  required recovery/release/push-presence boundary is explicit and guarded.
