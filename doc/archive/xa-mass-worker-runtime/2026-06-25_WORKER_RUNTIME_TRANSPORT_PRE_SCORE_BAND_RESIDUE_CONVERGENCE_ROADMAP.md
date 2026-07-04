# Worker Runtime / Transport Pre-Score-Band Residue Convergence Roadmap

Status: complete and archived; WTP-0 through WTP-5 have landed, residue was
scanned, and current facts were moved into owner docs and proof registry.

## Purpose

Prepare `xa-mass-worker-runtime` and transport integration for the
Score-Band Resource Slot Scheduling direction without starting the score-band
registry implementation yet.

The immediate problem is not that score-band lacks a roadmap. The problem is
that current worker-runtime and transport integration still carry older helper
models that would become hidden second scheduling paths if score-band work
starts now:

- `WorkerPresenceRuntime`, read-view interfaces, and
  `InMemoryWorkerPresenceRuntime` have been removed from the mainline.
- `WorkerPresenceIngress`, `WorkerSessionPresenceEvent`, and
  `WorkerRuntimePresenceIngress` have been deleted. Transport session events
  stay transport-local; only confirmed current-session disconnect may cross as
  scheduling-relevant negative evidence.
- `TaskCandidateWarmPool` and `WorkerWarmHintRuntime` have been deleted so
  task-local warm-candidate hints cannot compete with the supply-side slot
  leasing direction.
- `WorkerRuntimeStateRecord` and `WorkerReadinessState` have been deleted.
  `WorkerOccupancyState` remains only as a narrow load snapshot helper.
  The older runtime-history boundary track was removed from active roadmap
  scope because it preserved stale runtime-history and storage-shape wording
  around this area instead of reducing the pre score-band surface.
- `AdapterNodeRecord` / `NodeGroupBindingRecord` preserve an older
  adapter-node topology model. That model needs explicit classification before
  score-band work starts, because it is not the same thing as a resource slot,
  demand lane, or adapter mailbox.
- `WorkerCandidateBucketPolicy` and
  `WorkerTaskSelector#candidateBucketKeys` are current bounded-candidate
  machinery. They need classification so they are not mistaken for the future
  score-band lane/index model.
- `WorkerCandidateBatch` and `findWorkerCandidateBatch(...)` have been deleted;
  candidate acquisition now returns candidate rows directly.

This roadmap is a cleanup prerequisite. It should leave current assignment and
transport delivery behavior working, but remove unnecessary old seams before
the score-band registry is introduced.

## References

- [Score-Band Resource Slot Scheduling Blueprint](../../../blueprint/score-band/score-band-resource-slot-scheduling-blueprint.md)
- [Score-Band Worker Runtime Redis Shape](../../../blueprint/score-band/score-band-worker-runtime-redis-shape.md)
- [Worker Runtime Dispatch Eligibility Signal Convergence Roadmap](2026-06-26_WORKER_RUNTIME_NEGATIVE_SIGNAL_DISPATCH_ELIGIBILITY_CONVERGENCE_ROADMAP.md)
- [Worker Runtime Bounded Candidate Acquisition Roadmap](../../../roadmap/WORKER_RUNTIME_BOUNDED_CANDIDATE_ACQUISITION_ROADMAP.md)
- [Worker Runtime / Transport Pre-Score-Band Residue Inventory](./2026-06-25_WORKER_RUNTIME_TRANSPORT_PRE_SCORE_BAND_RESIDUE_INVENTORY.md)
- [Worker Runtime README](../../../xa-mass-worker-runtime/README.md)
- [Worker Runtime Contracts](../../../xa-mass-worker-runtime/CONTRACTS.md)
- [Transport Boundary Baseline](../../../transport/TRANSPORT_BOUNDARY_BASELINE.md)

## Blueprint Alignment

This cleanup follows the score-band blueprint, but it does not implement the
blueprint yet.

The blueprint target says:

```text
task = demand
resource slot = supply

engine exposes task demand lanes and attempt lifecycle needs
worker-runtime/resource-runtime maintains schedulable resource slots
matcher leases eligible slots from supply and binds them to demand
```

Relevant blueprint sections:

- [Core mechanism](../../../blueprint/score-band/score-band-resource-slot-scheduling-blueprint.md#core-mechanism):
  score-band resource availability is supply-owned.
- [Demand-Guided Sparse Acquire](../../../blueprint/score-band/score-band-resource-slot-scheduling-blueprint.md#demand-guided-sparse-acquire):
  task demand compiles into an acquire plan; tasks must not query workers,
  choose bucket keys, or create placement tag specs at runtime.
- [Transport To Worker-Runtime](../../../blueprint/score-band/score-band-resource-slot-scheduling-blueprint.md#transport-to-worker-runtime):
  heartbeat, transport refresh, session keepalive, and connected are
  transport-local freshness events, not worker-runtime scheduling reopen
  events.
- [Worker Runtime Redis Shape](../../../blueprint/score-band/score-band-worker-runtime-redis-shape.md):
  worker scheduling state should split into eligibility index, scheduling
  metadata, and lease/hold state; transport/session facts stay out of
  scheduling metadata.

Therefore this roadmap cleans the pre-score-band residue as follows:

| Current mechanism | Classification | Why it can be cleaned before score-band | Target disposition |
| --- | --- | --- | --- |
| `WorkerPresenceRuntime extends WorkerReachabilityView, WorkerDeliveryTargetView` | mixed projection / residue | It collapses transport session writes, reachability diagnostics, and delivery-target lookup into one contract. The blueprint separates transport-local freshness from worker-runtime schedulability and post-selection delivery. Current code is already moving away from the composite type; this roadmap must finish the cleanup rather than recreate it. | Delete the composite contract. Do not replace it with a generic presence ingress; delivery-target lookup and negative disconnect evidence get separate surfaces. |
| `InMemoryWorkerPresenceRuntime` backing presence, reachability, and delivery target | composite implementation residue | It exposed session mutation, reachability, and delivery target in one public class. After transport stopped writing session presence, it had no production writer and would preserve the old model through tests. | Deleted with `WorkerPresenceChange`; embedded delivery target fallback now resolves worker registration plus local transport binding. |
| `WorkerPresenceIngress` / `WorkerSessionPresenceEvent` / `WorkerRuntimePresenceIngress` | transport-to-worker-runtime event bridge residue | Connected and heartbeat prove transport freshness only. A generic event bridge encourages worker-runtime to treat session events as lifecycle input and preserves positive reopen drift. | Delete. Transport writes endpoint/session evidence locally. Confirmed current-session disconnect may emit narrow negative block evidence without a presence event DTO. |
| `WorkerDispatchBlockRuntime` / `WorkerDispatchBlockSignal` / `WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED` | allowed negative-evidence ingress | This is the one current transport-to-worker-runtime scheduling effect allowed by the blueprint: accepted current-session loss can close dispatch eligibility. It is not a freshness/reopen path. | Keep narrow. Inventory callers and prove connected/heartbeat cannot use it; only accepted current-session disconnect may emit `TRANSPORT_DISCONNECTED`. |
| `WorkerReachabilityView` / `WorkerManager#getWorkerReachability` | diagnostic read model residue | Reachability is not score-band eligibility and must not become a second scheduling gate. The interface is being removed, but `Function<String, WorkerReachabilityState>` and direct getters can still preserve the old behavior if left unclassified. | Delete from worker-runtime mainline unless a current product/API requirement is explicitly accepted. Future network reachability diagnostics should read transport-owned evidence through a targeted diagnostic surface, not worker-runtime selection adjacency. |
| Delivery-target resolver / `SelectedWorkerDeliveryTargetEvidence` | still-required post-selection evidence | Assigned dispatch still needs selected worker to opaque `adapterMailboxKey` lookup. This is after worker selection and not a scheduling input. The interface may disappear, but the resolver surface still exists. | Keep only the minimal point lookup. `workerId` and `adapterMailboxKey` are the default kept facts; `generation`, `observedAtEpochMillis`, and `expiresAtEpochMillis` are delete candidates unless current code proves stale-target rejection needs them. Forbid list/stats/scheduling use. |
| `WorkerRuntimeStateRecord` + readiness/occupancy derivations | composite read model residue | It composes reachability, heartbeat freshness, dispatch gate, admission, and load into one observation. This is dangerous during score-band inversion because it can be mistaken for slot eligibility truth. | Default delete `WorkerRuntimeStateRecord` and `WorkerReadinessState`. Keep `WorkerOccupancyState` only if it remains a narrow `WorkerLoadSnapshot` helper; otherwise delete. Public/API exposure is a breakage list, not a retention reason. |
| `AdapterNodeRecord` / `NodeGroupBindingRecord` | topology/control-plane residue | Adapter node/group binding is not the same thing as worker slot supply, demand lane, or adapter mailbox. It may still be useful as admin/control-plane inventory. | Classify as keep/narrow/delete/defer before score-band; do not use as score-band placement truth. |
| `WorkerCandidateBucketPolicy` / `candidateBucketKeys` | current bounded-candidate partition mechanism | BCA still uses candidate buckets for sparse candidate acquisition. They are not the final score-band lane model. | Keep until score-band replacement is designed; document as current migration mechanism, not future lane truth. |
| `TaskCandidateWarmPool` | task-local worker candidate cache residue | It is a task-side worker hint. The blueprint forbids new task-side worker indexes and moves future warming toward lane/resource preallocation. | Delete, not rename. |
| `WorkerWarmHintRuntime` | mutation surface for warm-pool residue | It only exists to feed task-local warm candidates. | Delete with warm pool. |
| `WorkerCandidateBatch` / `findWorkerCandidateBatch(...)` | warm-pool-era wrapper / delete candidate | Selection currently needs candidate rows, not warm/cold source diagnostics. If the wrapper exists only to carry `candidates()`, it is not a useful owner boundary. Keeping the method name after returning rows would preserve a false batch model. | Prefer deleting the wrapper and replacing the method with `findWorkerCandidates(...)` returning `List<WorkerCandidateRow>`. Retain a batch only if inventory proves non-warm-pool metadata is still required. |
| `WorkerCandidateIndex` / `WorkerCandidateRuntime` | current migration mechanism | These remain the current bounded candidate path until the score-band matcher roadmap replaces internals with eligible slot acquire. | Do not delete in this roadmap. |

In short:

```text
clean now:
  composite presence contract
  transport-to-worker-runtime presence event bridge
  transport freshness as worker-runtime scheduling input
  composite runtime-state as selection truth, and unused runtime-state DTOs
  task-local warm candidate cache
  warm/cold candidate diagnostics
  candidate batch wrapper if it only carries rows

keep narrow for now:
  current-session disconnect -> WorkerDispatchBlockRuntime negative evidence
  post-selection delivery target resolver
  current bounded candidate acquisition
  candidate bucket partitioning as current migration mechanism
  worker-runtime dispatch block/recovery owner

classify before changing score-band:
  adapter-node / node-group topology
  runtime state/readiness/occupancy diagnostic surfaces
  selected-worker delivery-target evidence fields

defer:
  score-band registry
  eligible slot acquire
  demand-lane preallocation
  multi-resource claims
```

## Deletion Bias For This Roadmap

This roadmap assumes the scheduling model is being materially reversed toward
score-band supply-side slots. Older worker-runtime helper records and DTOs are
not protected by default merely because they exist.

Default rule:

```text
no real production/API consumer -> delete
only test/doc/guard/archive consumer -> delete or rewrite proof
single narrow internal consumer -> pass the needed fields, not the whole record
public SDK/server/operator API consumer -> classify breakage, then delete/narrow/keep by explicit owner decision
```

For this roadmap, a "real consumer" means one of:

- a production main-source caller that needs the whole shape for a current
  runtime decision;
- a public SDK/server/operator API that intentionally exposes the shape as part
  of current behavior and whose retention is explicitly accepted by owner
  decision;
- a documented owner contract with active proof that would fail if the shape is
  removed.

Tests, stale roadmaps, archived docs, architecture guards protecting old
vocabulary, and compatibility examples do not count as real consumers. They
should be rewritten or deleted with the model.

If a no-consumer or low-coupling record becomes useful later, the score-band
implementation should add it back under the new owner with a narrow caller and
proof. It should not be retained now as speculative diagnostic surface.

## Current Code Observations

- `WorkerPresenceRuntime`, `WorkerReachabilityView`, and
  `WorkerDeliveryTargetView` no longer exist as production contracts.
- `WorkerPresenceIngress`, `WorkerSessionPresenceEvent`,
  `WorkerPresenceEventType`, `NoopWorkerPresenceIngress`, and
  `WorkerRuntimePresenceIngress` have been deleted from main and test sources.
- SDK/assembly configuration for a custom worker presence ingress has been
  removed. `TransportAdapterBootstrapContext` exposes no worker-runtime
  presence ingress.
- `InMemoryWorkerPresenceRuntime` and `WorkerPresenceChange` have been deleted.
  Transport connected/heartbeat/disconnect no longer have any worker-runtime
  session-presence projection target.
- Embedded assigned dispatch no longer depends on session-presence projection
  as the default delivery target writer. When no explicit resolver is
  configured, `MassApplication` derives `SelectedWorkerDeliveryTargetEvidence`
  from worker registration plus local `TransportBinding` metadata. Split
  `ENGINE_PRODUCER` runtime still requires an explicit resolver because it has
  no local adapter binding registry.
- `WorkerDispatchBlockRuntime`, `WorkerDispatchBlockSignal`, and
  `WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED` are the current narrow
  negative-evidence path from transport session loss into worker-runtime
  dispatch eligibility. They are allowed only for accepted current-session
  disconnect, not connected, heartbeat, session refresh, or positive recovery.
- Delivery-target resolution is still required for assigned dispatch:
  delivery integration resolves an already-selected worker to an opaque
  `adapterMailboxKey` through `SelectedWorkerDeliveryTargetEvidence`.
- `SelectedWorkerDeliveryTargetEvidence` carries `workerId`,
  `adapterMailboxKey`, and `expiresAtEpochMillis`. `generation` and
  `observedAtEpochMillis` were removed because current dispatch only needs
  worker identity, mailbox, and deadline check.
- `WorkerReachabilityState` remains observable through
  `WorkerManager#getWorkerReachability` and SDK config helpers. It is a
  retained targeted diagnostic read only. It is not a selection/admission or
  positive recovery input.
- `WorkerRuntimeStateRecord` and `WorkerReadinessState` have been deleted.
  `WorkerOccupancyState` remains through `WorkerLoadSnapshot` only as a narrow
  load helper.
- `AdapterNodeRecord` / `NodeGroupBindingRecord` are exposed through
  `WorkerManager`, `WorkerResourceQueryRuntime`, `WorkerNodeBindingRuntime`,
  embedded SDK snapshots, SDK runtime-control operations, and public worker
  API routes such as `/worker-api/v1/adapter-nodes` and
  `/worker-api/v1/node-group-bindings`. They look like older adapter
  topology/control surface, not score-band supply truth. They are retained
  conservatively as topology/admin inventory.
- `TaskCandidateWarmPool` and `WorkerWarmHintRuntime` have been deleted.
- `WorkerCandidateBatch` has been deleted and
  `findWorkerCandidateBatch(...)` has been replaced by row-returning
  `findWorkerCandidates(...)`.
- `WorkerCandidateBucketPolicy`, `WorkerTaskSelector#candidateBucketKeys`, and
  `ResolvedWorkerSchedulingPolicy#candidateBucketKeys` are current
  bounded-candidate partitioning mechanics. They are not the same as future
  demand lanes or score-band resource-slot indexes.
- Current BCA candidate acquisition remains bounded without task-local
  warm-candidate hints.

## Owner Review

Worker-runtime owns:

- worker declaration to runtime scheduling projection;
- dispatch eligibility and block/recovery policy;
- selected-worker read contracts consumed after assignment;
- candidate acquisition while the current selection mechanism remains active.
- current worker read models for operator/SDK diagnostics, as long as they do
  not feed selection as composite truth.

Transport owns:

- adapter-local session/channel truth;
- endpoint/session/freshness evidence;
- assigned-delivery queueing and final-hop best-effort delivery;
- carrying explicit worker reports or current-session disconnect evidence to
  worker-runtime through narrow integration contracts.

Transport must not own:

- worker schedulability;
- worker candidate warm pools;
- score-band state;
- positive recovery or reopen decisions.

Task-local warm candidates are a task-side acceleration hint. They are not
resource-slot supply truth. Under score-band direction they should be removed,
not renamed into a new slot or lane concept. Future warm capacity belongs to
lane/resource preallocation, not per-task worker candidate caches.

Adapter-node / node-group binding is not transport final-hop truth and not
score-band resource-slot truth. If retained, it should be treated as
control-plane topology inventory around adapter deployments and group
availability, not as a worker-selection index.

Candidate bucket policy is current bounded-candidate partitioning. It can
remain as a migration mechanism, but it must not be described as the target
demand lane or score-band index.

## Boundary Decision

Transport session evidence, worker-runtime negative dispatch block, reachability
diagnostics, and delivery-target lookup must be separate contracts.

Target split:

```text
Transport endpoint/session evidence
  transport-local freshness/currentness only:
    connected
    heartbeat
    disconnected

Worker dispatch block sink
  negative signal only:
    confirmed current-session disconnect -> TRANSPORT_DISCONNECTED

WorkerReachabilityState point read
  delete from worker-runtime mainline unless explicitly kept as a product/API
  diagnostic by owner decision

Selected-worker delivery target resolver
  post-selection point lookup:
    selectedWorkerId -> adapterMailboxKey evidence
```

No production contract should deliver generic transport session events into
worker-runtime. There should be no replacement for `WorkerPresenceIngress`;
connected / heartbeat / keepalive are transport-local evidence. If an in-memory
implementation temporarily backs delivery-target lookup and diagnostics,
assembly must expose only narrow typed fields or functions, not a composite
presence interface or broad runtime object.

Runtime state/readiness/occupancy records must be treated as read models:

```text
WorkerRuntimeStateRecord
WorkerReadinessState
WorkerOccupancyState
```

They are deletion-first. A current SDK/server/operator consumer is not by
itself a retention reason; it is a breakage surface to classify. Retention
requires an explicit owner decision and must remain diagnostic-only. Score-band
slot state requires its own owner decision.

Adapter node/group binding must be treated as topology/control-plane inventory:

```text
AdapterNodeRecord
NodeGroupBindingRecord
```

It must not be reused as score-band resource supply, demand lane, or adapter
mailbox truth without a separate owner decision.

Warm candidate hints should be deleted:

```text
TaskCandidateWarmPool
WorkerWarmHintRuntime
warmCandidateCount
WorkerCandidateBatch warm/cold counters
```

The successor is not another warm-pool abstraction. The successor direction is
score-band supply and later demand-lane preallocation. `WorkerCandidateBatch`
should also be deleted if, after warm-pool removal, it carries only candidate
rows.

## Non-Goals

- Do not implement score-band registry in this roadmap.
- Do not replace `WorkerSelectionRuntime` internals with eligible slot acquire.
- Do not introduce supply-push worker-to-task matching.
- Do not introduce a generic resource abstraction.
- Do not change transport dispatch carrier or adapter mailbox delivery.
- Do not remove the selected-worker delivery-target resolver while assigned
  dispatch still needs post-assignment worker-to-mailbox lookup.
- Do not preserve `TaskCandidateWarmPool` through aliases, wrappers, or renamed
  compatibility paths.
- Do not add stats/list/count read models to compensate for removed warm-pool
  diagnostics.
- Do not convert `AdapterNodeRecord` / `NodeGroupBindingRecord` into
  score-band slot, lane, or mailbox concepts by rename.
- Do not delete current candidate-bucket partitioning until a score-band
  acquire roadmap replaces the production caller path.
- Do not treat `WorkerRuntimeStateRecord`, readiness, or occupancy as the
  future eligibility index.
- Do not retain no-consumer or low-coupling broad records "just in case".
  Future score-band work should add back narrowly owned records only when a
  real caller and proof exist.

## WTP-0: Inventory And Baseline Classification

Goal:

Classify all production and test callers before deleting or splitting the
contracts.

Scope:

- Inventory callers of:
  - `WorkerPresenceRuntime`
  - `InMemoryWorkerPresenceRuntime`
  - `WorkerPresenceIngress`
  - `WorkerSessionPresenceEvent`
  - `WorkerPresenceEventType`
  - `NoopWorkerPresenceIngress`
  - `WorkerRuntimePresenceIngress`
  - `TransportConfig.customWorkerPresenceIngress`
  - `MassApplicationBuilder.TransportBuilder#workerPresenceIngress`
  - `TransportRuntimeComposition#resolveWorkerPresenceIngress`
  - `WorkerDispatchBlockRuntime`
  - `WorkerDispatchBlockSignal`
  - `WorkerDispatchBlockSource`
  - `WorkerReachabilityView`
  - `WorkerDeliveryTargetView`
  - `WorkerManager#getWorkerReachability`
  - `EngineConfig#setWorkerDeliveryTargetResolver`
  - `SelectedWorkerDeliveryTargetEvidence` fields
  - `WorkerRuntimeStateRecord`
  - `WorkerReadinessState`
  - `WorkerOccupancyState`
  - `AdapterNodeRecord`
  - `NodeGroupBindingRecord`
  - `WorkerNodeBindingRuntime`
  - `WorkerWarmHintRuntime`
  - `TaskCandidateWarmPool`
  - `WorkerCandidateBatch`
  - `WorkerCandidateRuntime#findWorkerCandidateBatch`
  - `WorkerCandidateBatch#warmCandidateCount`
  - `WorkerCandidateBatch#coldCandidateCount`
  - `WorkerCandidateBatch#warmSourceGuardRejectedCount`
  - `WorkerCandidateBatch#duplicateCandidateCount`
  - `WorkerCandidateBucketPolicy`
  - `WorkerTaskSelector#candidateBucketKeys`
  - `ResolvedWorkerSchedulingPolicy#candidateBucketKeys`
- Separate production, test, architecture guard, documentation, and archived
  roadmap references.
- Classify the allowed negative-evidence path separately from freshness
  residue: `TRANSPORT_DISCONNECTED` is kept only as current-session loss input
  to worker-runtime dispatch eligibility.
- Classify whether each record/interface has a real consumer under the
  roadmap-level deletion-bias rule. Tests, docs, guards, and archived roadmaps
  are not enough to retain a production model.
- Verify whether selection packages still read reachability directly.
- Verify whether any production path depends on warm-candidate behavior for
  correctness rather than performance.
- Verify whether `WorkerCandidateBatch` carries any non-warm-pool metadata
  required by selection. If not, target deletion and let candidate acquisition
  return candidate rows directly.
- Verify whether adapter-node / node-group APIs are production scheduling
  inputs, admin/control-plane inventory, test fixtures, or stale public SDK
  surface.
  This inventory must include `MassSdkApplication`,
  `WorkerRegistryOperations`, `MassRuntimeControl`,
  `ExternalWorkerApiController`, server API docs, SDK quickstart/docs, and
  server integration tests for `/worker-api/v1/adapter-nodes` and
  `/worker-api/v1/node-group-bindings`.
- Verify whether candidate bucket keys are only current BCA partitioning
  mechanics or are being described as future score-band demand lanes.

Acceptance:

- Inventory states each symbol's current owner, fact classification, caller
  class, and target disposition:
  `keep`, `narrow`, `delete`, or `defer`.
- Diagnostics retention is not a default disposition. If a diagnostic surface
  is kept, WTP-0 must record the explicit owner decision and breakage cost.
- Any symbol with no real consumer is marked `delete`.
- Any public/API/operator consumer is treated as a breakage surface. It does
  not justify retention unless WTP-0 records an explicit owner decision to keep
  that surface.
- Any low-coupling symbol with one or two internal callers that only need a few
  fields is marked `narrow`, with the exact replacement fields or method shape.
- No behavior change.
- The next slice can be implemented without discovering a hidden production
  caller that requires compatibility aliases.
- The inventory explicitly says which symbols are user-facing SDK/API
  surfaces and which are internal runtime support, so cleanup does not
  preserve stale public paths accidentally.
- The inventory explicitly proves that the only transport-origin scheduling
  effect retained by this roadmap is accepted current-session disconnect into
  `WorkerDispatchBlockRuntime`.

Suggested inventory file:

```text
roadmap/WORKER_RUNTIME_TRANSPORT_PRE_SCORE_BAND_RESIDUE_INVENTORY.md
```

## WTP-1: Delete Session Presence Event Bridge

Goal:

Remove the transport-to-worker-runtime session presence event bridge while
preserving embedded delivery-target lookup through starter-owned binding
resolution and negative-disconnect behavior.

Scope:

- Finish removing `WorkerPresenceRuntime` / `WorkerReachabilityView` /
  `WorkerDeliveryTargetView` interface residue from main sources, tests,
  docs, and guards.
- Delete `WorkerPresenceIngress`, `NoopWorkerPresenceIngress`,
  `WorkerSessionPresenceEvent`, `WorkerPresenceEventType`, and
  `WorkerRuntimePresenceIngress`.
- Remove SDK/assembly configuration for custom worker presence ingress:
  `TransportConfig.customWorkerPresenceIngress`,
  `MassApplicationBuilder.TransportBuilder#workerPresenceIngress(...)`,
  `TransportRuntimeComposition#resolveWorkerPresenceIngress()`, and the
  `workerPresenceIngress` field/constructor argument on
  `TransportAdapterBootstrapContext`.
- `AdapterSessionEvidencePublisher` should publish only transport-owned
  endpoint/session evidence. It must not construct worker-runtime presence
  events or depend on a worker-runtime presence ingress.
- Keep `WorkerDispatchBlockRuntime`, `WorkerDispatchBlockSignal`, and
  `WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED` as an explicit
  negative-evidence ingress, not as presence-read residue. Classify the port in
  WTP-0 and prove its caller path is limited to accepted current-session loss.
- Connected / heartbeat / keepalive stay transport-local and must not call
  worker-runtime, refresh registry heartbeat, request dispatch wakeup, recover
  dispatch gates, or reopen schedulability.
- Disconnected is the only current transport-to-worker-runtime path, and only
  when adapter-local currentness confirms current-session loss. It must emit a
  narrow `TRANSPORT_DISCONNECTED` block signal directly or through a
  negative-only assembly sink, not through a generic presence event.
- Wire reachability diagnostics and delivery-target resolver separately in
  `EngineConfig` / `MassApplication` assembly. Embedded default delivery target
  resolution must derive from worker registration plus local `TransportBinding`
  metadata; split `ENGINE_PRODUCER` runtime must inject an explicit resolver.

Acceptance:

- No production main source references `WorkerPresenceRuntime`,
  `WorkerReachabilityView`, or `WorkerDeliveryTargetView` unless WTP-0
  explicitly reclassifies one as a still-current contract.
- No production main or test source references `WorkerPresenceIngress`,
  `NoopWorkerPresenceIngress`, `WorkerSessionPresenceEvent`,
  `WorkerPresenceEventType`, or `WorkerRuntimePresenceIngress`.
- `TransportConfig`, `MassApplicationBuilder`, `TransportRuntimeComposition`,
  `TransportAdapterBootstrapContext`, and `AdapterSessionEvidencePublisher`
  expose no worker presence ingress configuration or constructor dependency.
- Connected, heartbeat, refresh, and stale/replaced disconnect cannot call
  block, recovery, recheck, heartbeat refresh, or any worker-runtime scheduling
  reopen path.
- Accepted current-session disconnect still emits `TRANSPORT_DISCONNECTED`
  block evidence through a negative-only path.
- `EngineConfig` no longer uses one composite field as the public/default owner
  of presence, reachability, and delivery target.
- `InMemoryWorkerPresenceRuntime` and `WorkerPresenceChange` are deleted, not
  retained as hidden session-presence projection.
- Embedded assigned-dispatch proof shows delivery target lookup still works
  without worker-runtime session-presence writes.
- Existing embedded distributed transport tests still prove selected-worker
  delivery target lookup works.
- Stale or replaced-session disconnect does not emit `TRANSPORT_DISCONNECTED`.

## WTP-2: Narrow Delivery Target And Reachability Residue

Goal:

Make delivery target and reachability explicit point-read surfaces, not
presence-owned scheduling truth.

Scope:

- Keep selected-worker delivery-target resolution as a point lookup for
  assigned dispatch only. Current code may use
  `Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>>`; a named
  interface is optional and should only be reintroduced if it improves the
  owner boundary.
- Ensure delivery-target resolution exposes no worker lists, mailbox lists,
  active counts, session handles, endpoint leases, or scheduling views.
- Classify each `SelectedWorkerDeliveryTargetEvidence` field:
  `workerId` and `adapterMailboxKey` are dispatch-required; `generation`,
  `observedAtEpochMillis`, and `expiresAtEpochMillis` are delete candidates
  unless current dispatch code proves they are used for stale-target rejection.
- Delete or isolate `WorkerReachabilityState` point reads from worker-runtime
  mainline. A retained reachability read requires a named current product/API
  owner and must not sit on the selection or recovery path.
- Ensure selection hot path does not call `getWorkerReachability(...)` or
  consume reachability-derived read models.
- Move or delete documentation that describes reachability as dispatchability
  truth.
- Decide whether `WorkerManager`'s permissive reachability fallback is still a
  useful embedded/test default or should be narrowed to test support.

Acceptance:

- `xa-mass-worker-runtime` selection package does not import or call
  reachability diagnostics.
- Engine mainline does not read reachability diagnostics for worker selection.
- Delivery-target resolver remains post-selection only: its caller already has
  a `selectedWorkerId`.
- `SelectedWorkerDeliveryTargetEvidence` is reduced to dispatch-required facts
  unless stale-target rejection is proven in current code.
- `WorkerReachabilityState` / `getWorkerReachability` is deleted from
  worker-runtime mainline, or a named owner decision records the current
  product/API consumer and keeps it as a targeted diagnostic only.
- Architecture guard fails if delivery target view becomes a worker list,
  mailbox list, stats view, or scheduling input.
- Architecture guard fails if `SelectedWorkerDeliveryTargetEvidence` fields
  are used by worker selection or scheduling eligibility.

## WTP-3: Classify Runtime State, Topology, And Candidate Bucket Residue

Goal:

Prevent diagnostic state, adapter topology, and current candidate-bucket
partitioning from becoming accidental score-band truth.

Scope:

- Classify `WorkerRuntimeStateRecord`, `WorkerReadinessState`, and
  `WorkerOccupancyState` with deletion as the default. Delete
  `WorkerRuntimeStateRecord` and `WorkerReadinessState` unless an explicit
  owner decision accepts a current product/API breakage cost to keep them.
  Keep `WorkerOccupancyState` only if it remains a narrow
  `WorkerLoadSnapshot` helper; otherwise delete it too.
- Do not keep `WorkerRuntimeStateRecord` or `WorkerReadinessState` as
  diagnostic surfaces only because they are cheap to leave in place. They need
  a real consumer; otherwise deletion is the target.
- Verify no selection/admission/dispatch hot path consumes
  `WorkerRuntimeStateRecord` as input.
- Classify `AdapterNodeRecord`, `NodeGroupBindingRecord`, and
  `WorkerNodeBindingRuntime` as topology/control-plane/admin inventory unless
  WTP-0 proves all API/SDK consumers are stale. Do not delete this surface in
  the same slice as warm-pool deletion; it has a different caller set and proof
  surface.
- Include the SDK/server/API disposition in the same classification:
  `MassSdkApplication`, `WorkerRegistryOperations`, `MassRuntimeControl`,
  `ExternalWorkerApiController`, `xa-mass-server` API docs, SDK quickstart
  docs, and external-worker registration E2E tests must either keep the surface
  as explicit topology inventory or be listed for deletion in a separate API
  owner slice.
- If adapter-node/group binding stays, document that it is not worker slot
  supply, not demand-lane routing, and not assigned-delivery mailbox truth.
- Classify `WorkerCandidateBucketPolicy`,
  `WorkerTaskSelector#candidateBucketKeys`, and
  `ResolvedWorkerSchedulingPolicy#candidateBucketKeys` as current
  bounded-candidate partitioning. They may remain until a score-band acquire
  roadmap replaces them, but docs must not present them as final demand lanes.
- Remove or quarantine any proof counters/list APIs whose only purpose is to
  preserve old warm/cold candidate behavior.

Acceptance:

- Owner docs delete `WorkerRuntimeStateRecord` / `WorkerReadinessState` from
  current contracts, unless WTP-0 records an explicit owner decision to keep a
  current product/API surface and lists the breakage cost. If retained, the
  surface is diagnostics only and cannot feed selection/admission.
- Adapter-node / node-group binding is either explicitly retained as
  control-plane inventory or marked for deletion with caller list, SDK/server
  API disposition, and proof.
- Candidate bucket policy is documented as current BCA partitioning, not
  score-band lane/index truth.
- No new abstraction is introduced in this slice. The output is owner
  classification plus any small doc/guard corrections needed to prevent
  misread implementation.

## WTP-4: Delete Task-Local Warm Candidate Path

Goal:

Remove task-local warm-candidate hinting and its row-batch diagnostics before
score-band work starts.

Scope:

- Delete `TaskCandidateWarmPool`.
- Delete `WorkerWarmHintRuntime`.
- Remove `WorkerManager.recordWarmCandidate(...)` and
  `WorkerManager.warmCandidateCount(...)`.
- Remove `WorkerManager implements WorkerWarmHintRuntime`.
- Remove `EngineConfig#getWorkerWarmHintRuntime()` and any SDK/starter
  assembly exposure of warm-hint mutation.
- Remove warm-candidate sampling from `WorkerCandidateSourceOwner`; it should
  request the bounded candidate source directly.
- Prefer deleting `WorkerCandidateBatch` and changing
  `WorkerCandidateRuntime#findWorkerCandidateBatch(...)` to
  `findWorkerCandidates(...)` returning `List<WorkerCandidateRow>` or an
  equivalently narrow row collection. Retain a batch type only if WTP-0 proves
  non-warm-pool metadata is still required.
- Update `WorkerSelectionOwner`, `WorkerCandidateSourceOwner`, `WorkerManager`,
  and focused tests to use the row-returning acquisition method. Do not keep a
  method named `findWorkerCandidateBatch(...)` when no batch carrier remains.
- Update selection-focused tests and helpers, including
  `WorkerSelectionAtomicRuntimeTest`, `WorkerSelectionRankingMechanicsTest`,
  and `WorkerSelectionTestSupport`, so they mock/provide candidate rows rather
  than `WorkerCandidateBatch` or warm/cold diagnostic metadata.
- If a batch type is retained temporarily, remove all warm/cold/source-guard
  diagnostic counters from it.
- Delete or rewrite tests that preserve warm-pool behavior, including
  `TaskCandidateWarmPoolTest` and warm-candidate assertions in
  `WorkerManagerTest`.
- Remove architecture guards that protect `TaskCandidateWarmPool` as a current
  mechanism; replace them with guards that forbid task-local warm candidate
  caches from returning.
- Update `xa-mass-worker-runtime/README.md`,
  `xa-mass-worker-runtime/CONTRACTS.md`, engine scheduling docs, and
  architecture guards in the same slice. Do not leave docs/tests protecting
  the deleted warm-pool mechanism.

Acceptance:

- Main sources contain no `TaskCandidateWarmPool`, `WorkerWarmHintRuntime`,
  `recordWarmCandidate`, or `warmCandidateCount`.
- `EngineConfig` no longer exposes `getWorkerWarmHintRuntime()`.
- `WorkerCandidateBatch` is deleted when it carries no non-warm metadata. If
  retained by explicit WTP-0 decision, it carries no warm/cold/source-guard
  diagnostic counters and has a later deletion owner.
- If `WorkerCandidateBatch` is deleted, no production or test source references
  `findWorkerCandidateBatch(...)`. If a batch carrier is retained by explicit
  WTP-0 decision, the method name and metadata fields are documented as
  current, non-warm-pool facts with a later score-band replacement owner.
- `WorkerSelectionOwner` and selection tests prove selection consumes
  candidate rows only; warm/cold/source-guard batch metadata is not preserved
  as a hidden selection input.
- Candidate acquisition remains bounded through the BCA contract.
- No correctness test depends on warm-candidate preference.
- Engine and worker-runtime architecture guards no longer require or protect
  `TaskCandidateWarmPool` / `WorkerWarmHintRuntime`; they fail if task-local
  warm candidate caches or mutation ports return.
- Documentation points future warming to demand-lane preallocation / score-band
  roadmap work, not task-local worker candidate caches.

## WTP-5: Owner Docs, Guards, And Proof Registry

Goal:

Make the cleanup durable and prevent old vocabulary from re-entering the
mainline before score-band starts.

Scope:

- Update:
  - `xa-mass-worker-runtime/README.md`
  - `xa-mass-worker-runtime/CONTRACTS.md`
  - `transport/TRANSPORT_BOUNDARY_BASELINE.md` if presence or transport
    evidence wording changes
  - `doc/PROOF_REGISTRY.md`
  - active engine scheduling docs that still describe warm-pool as current
    mainline
- Update architecture guards:
  - forbid composite presence contract extending read views;
  - forbid `WorkerPresenceIngress` / `WorkerSessionPresenceEvent` /
    `WorkerRuntimePresenceIngress` or SDK worker-presence-ingress config from
    returning;
  - forbid selection hot path importing reachability diagnostics;
  - forbid runtime-state/readiness/occupancy read models from becoming
    selection or admission inputs, or remove them entirely when WTP-3 deletes
    the unused surfaces;
  - forbid adapter-node / node-group topology from being used as
    score-band slot, lane, or mailbox truth;
  - forbid task-local warm candidate pool classes or mutation ports.
  - forbid `findWorkerCandidateBatch(...)` after the batch wrapper is deleted.
- Archive or mark superseded any roadmap sections that still treat warm pool as
  a current mechanism.

Acceptance:

- Owner docs describe:
  - transport freshness as transport-local evidence;
  - current-session disconnect as the only retained transport-origin negative
    scheduling evidence path;
  - delivery target lookup as post-selection only;
  - runtime-state/readiness/occupancy as deleted surfaces or diagnostics/read
    models only;
  - adapter-node / node-group binding as classified topology or removed
    surface, with SDK/server API disposition, not score-band truth;
  - candidate buckets as current bounded-candidate partitioning only;
  - warm-pool mechanism as removed.
- Proof registry points to focused tests for presence-event bridge deletion,
  delivery target lookup, negative disconnect, and bounded candidate
  acquisition.
- Guard tests fail on reintroducing composite presence, session-presence event
  bridge, or warm-pool symbols.

## Suggested Implementation Order

1. WTP-0 inventory.
2. WTP-1 session-presence event bridge deletion.
3. WTP-4 task-local warm candidate deletion.
4. WTP-2 delivery-target / reachability residue cleanup.
5. WTP-3 runtime-state / topology / candidate-bucket classification.
6. WTP-5 docs and guards.

WTP-0 should be thin and current-code grounded. It must prove the warm-pool
path is not a correctness requirement and identify the delivery-target writer
that will survive presence-event bridge deletion, but it should not become a
broad topology redesign gate. WTP-1 and WTP-4 can proceed independently after
WTP-0: WTP-1 removes transport session events from worker-runtime, and WTP-4
removes task-local worker warming.

The removed runtime-history boundary track is not a dependency for this
roadmap. Do not reintroduce it to justify retaining `WorkerRuntimeStateRecord`
or old runtime-history storage wording.

## Do Not Start With

- Do not start from only `WorkerPresenceRuntime` and `TaskCandidateWarmPool`
  because they were named as examples. WTP-0 must inventory runtime-state,
  topology, delivery-target evidence, candidate-bucket, and warm-pool residue
  together before choosing delete/narrow/defer actions.
- Do not start by creating diagnostic replacements for records that have no
  real consumer. Delete first; add back later only under a new score-band owner
  if a caller proves the need.
- Do not start by adding score-band classes.
- Do not start by renaming `TaskCandidateWarmPool`.
- Do not replace warm pool with another task-local candidate cache.
- Do not make `WorkerPresenceRuntime` smaller in name only while keeping
  reachability and delivery target on the same public contract.
- Do not replace `WorkerPresenceIngress` with another generic session-presence
  event bridge. Transport connected / heartbeat / keepalive stay transport
  local; only confirmed current-session disconnect may cross as negative
  dispatch evidence.
- Do not delete the selected-worker delivery-target resolver until assigned
  delivery no longer needs selected-worker to mailbox resolution.
- Do not add stats, lists, or diagnostics loops to prove the cleanup.
- Do not let adapter-node / node-group topology classification block
  task-local warm candidate deletion; it has a different public/API proof
  surface.

## Verification Candidates

Commands must be corrected after WTP-0 if test names change.

```powershell
rg -n "WorkerPresenceRuntime|InMemoryWorkerPresenceRuntime|WorkerPresenceIngress|NoopWorkerPresenceIngress|WorkerSessionPresenceEvent|WorkerPresenceEventType|WorkerRuntimePresenceIngress|customWorkerPresenceIngress|workerPresenceIngress|WorkerDispatchBlockRuntime|WorkerDispatchBlockSignal|WorkerDispatchBlockSource|WorkerDeliveryTargetView|WorkerReachabilityView|WorkerManager#getWorkerReachability|getWorkerReachability|SelectedWorkerDeliveryTargetEvidence|WorkerRuntimeStateRecord|WorkerReadinessState|WorkerOccupancyState|AdapterNodeRecord|NodeGroupBindingRecord|WorkerNodeBindingRuntime|WorkerCandidateBucketPolicy|candidateBucketKeys|TaskCandidateWarmPool|WorkerWarmHintRuntime|warmCandidate|warmCandidateCount|coldCandidateCount|warmSourceGuardRejectedCount|duplicateCandidateCount|findWorkerCandidateBatch" xa-mass-worker-runtime sdk/xa-mass-embedded-sdk transport xa-mass-engine platform_infra roadmap doc --glob "!**/target/**" --glob "!**/archive/**"

rg -n "WorkerPresenceIngress|NoopWorkerPresenceIngress|WorkerSessionPresenceEvent|WorkerPresenceEventType|WorkerRuntimePresenceIngress|customWorkerPresenceIngress|workerPresenceIngress" transport/transport_api/src/main transport/transport_runtime/src/main transport/polling-adapter/src/main transport/socket-adapter/src/main transport/websocket-adapter/src/main sdk/xa-mass-embedded-sdk/src/main --glob "*.java" --glob "!**/target/**"

rg -n "TaskCandidateWarmPool|WorkerWarmHintRuntime|recordWarmCandidate|warmCandidateCount|WorkerCandidateBatch|findWorkerCandidateBatch|coldCandidateCount|warmSourceGuardRejectedCount|duplicateCandidateCount" xa-mass-worker-runtime/src/main sdk/xa-mass-embedded-sdk/src/main xa-mass-engine/src/main platform_infra --glob "*.java" --glob "!**/target/**"

.\mvnw.cmd -q -pl xa-mass-worker-runtime,sdk/xa-mass-embedded-sdk,transport/transport_runtime,xa-mass-engine -am -DskipTests compile

.\mvnw.cmd -q -pl xa-mass-worker-runtime -am "-Dtest=WorkerManagerTest,WorkerCandidateIndexTest,WorkerAdmissionOwnerTest,WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest" test

.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=MassApplicationDistributedTransportTest,TaskDispatchRoutingSubmitterTest,MassSdkTest" test

.\mvnw.cmd -q -pl transport/transport_runtime -am "-Dtest=TransportConvergenceArchitectureGuardTest" test

.\mvnw.cmd -q -pl xa-mass-engine -am "-Dtest=EngineSchedulingCoreArchitectureGuardTest" test
```

For final completion proof, mandatory focused tests must run without
`-Dsurefire.failIfNoSpecifiedTests=false`. If a named test is deleted as part
of the cleanup, replace it with the new focused proof instead of masking the
absence.

## Completion Criteria

This roadmap is complete when:

1. No production composite presence contract exposes both mutation and
   reachability/delivery-target reads.
2. `WorkerPresenceIngress`, `NoopWorkerPresenceIngress`,
   `WorkerSessionPresenceEvent`, `WorkerPresenceEventType`,
   `WorkerRuntimePresenceIngress`, and SDK worker-presence-ingress config are
   removed from main and test sources.
3. Transport connected / heartbeat / keepalive remains transport-local
   freshness and cannot notify worker-runtime or reopen worker schedulability.
4. Current-session disconnect remains the only retained transport-origin
   negative scheduling evidence path and enters through
   `WorkerDispatchBlockRuntime` / `TRANSPORT_DISCONNECTED`.
5. Selected-worker delivery-target resolution is available only as
   post-selection point lookup for assigned delivery.
6. Selection hot path does not depend on reachability diagnostics.
7. `SelectedWorkerDeliveryTargetEvidence` is reduced to dispatch-required
   facts. `generation`, `observedAtEpochMillis`, and `expiresAtEpochMillis`
   are deleted unless stale-target rejection is proven in current code.
8. `WorkerRuntimeStateRecord` and `WorkerReadinessState` are deleted unless an
   explicit owner decision records a current product/API reason to keep them as
   diagnostics. `WorkerOccupancyState` remains only as a narrow
   `WorkerLoadSnapshot` helper if retained.
9. `AdapterNodeRecord` / `NodeGroupBindingRecord` are either retained as
   explicit control-plane/admin topology or removed from current runtime
   surfaces; they are not score-band supply, demand lane, or mailbox truth.
10. `WorkerCandidateBucketPolicy` and `candidateBucketKeys` are documented as
   current BCA partitioning and have a clear replacement/defer decision for
   score-band work.
11. `TaskCandidateWarmPool`, `WorkerWarmHintRuntime`, warm-candidate mutation,
   warm/cold batch counters, `findWorkerCandidateBatch(...)`, and row-only
   `WorkerCandidateBatch` residue are removed from main sources.
12. Bounded candidate acquisition still works without task-local warm hints.
13. Owner docs, proof registry, and guards no longer preserve warm-pool,
   composite-presence, topology-as-slot, or runtime-state-as-eligibility
   vocabulary as current truth.
14. The repo is ready for the first score-band registry roadmap without hidden
   old scheduling acceleration paths.
15. No no-consumer broad record is retained solely as speculative diagnostic
   surface; deleted models can be reintroduced later only by a new owner
   decision and focused proof.

## Deferred Decisions

- Whether a future shared/split deployment needs a persistent delivery-target
  projection store beyond the current embedded point lookup.
- Whether any reachability diagnostic deserves a future public SDK/server
  surface after worker-runtime mainline deletion. Default: delete from this
  roadmap's worker-runtime path.
- Whether `generation` / `observedAtEpochMillis` / `expiresAtEpochMillis` on
  `SelectedWorkerDeliveryTargetEvidence` are dispatch-required stale-target
  facts. Default: delete unless proven by current code.
- Whether adapter-node / node-group topology should remain as an admin
  inventory surface, move behind server/control-plane APIs, or be deleted
  before score-band.
- Whether current candidate bucket partitioning is replaced directly by
  score-band sparse acquire or kept as a temporary implementation detail under
  the first score-band registry slice.
- Whether demand-lane preallocation later replaces any useful behavior removed
  with `TaskCandidateWarmPool`.
- How the first score-band registry will bridge from current
  `WorkerRegistry`/`WorkerSlot` without preserving the old candidate path as a
  second truth.
