# AdapterNode / NodeGroupBinding / Worker Registration Roadmap

Last updated: 2026-05-20

Status: implemented convergence baseline for AN-0 through AN-6. The deferred
owner-outcome listener remains a future extension, not part of this baseline.
Use code and verified runtime behavior as truth for current implementation
claims.

## Summary

This roadmap does not rewrite transport and does not introduce a
`WorkerSession`, `Device`, `DeviceSession`, or `AccountSlot` runtime model.

The goal is to converge worker registration structure while preserving the
current owner split:

```text
AdapterNode
  -> NodeGroupBinding
      -> WorkerGroup
          -> Worker
```

Meaning:

```text
AdapterNode
  -> worker registration endpoint, logical adapter deployment identity,
     callback scope, and node-level diagnostics

NodeGroupBinding
  -> adapter node hosts worker group relation truth

WorkerGroup
  -> capability cohort and eventBindings truth

Worker
  -> platform dispatchable execution identity
```

This roadmap was implemented convergence-first: relation ownership was added
before worker registration changed, and the old group-owned node relation was
removed only after worker membership by adapter node was canonical.

## Current Truth

### Transport truth

Transport is a multi-protocol worker data plane, not a WebSocket-first worker
system.

- `transport_api` owns transport-neutral contracts.
- `transport_runtime` owns shared runtime assembly, routing, delivery, result
  ingress, and presence.
- polling, WebSocket, and socket adapters are peer protocol adapters.
- `adapterId` is concrete adapter runtime identity.
- `transportHint` is only a coarse family hint.
- `routeKey`, `connectionId`, and `transportNodeId` are transport-owned route
  owner evidence.
- `WorkerPresenceStore` owns transport reachability and route-owner truth.

Transport must not own worker capability truth, worker load, task leases,
matching, ranking, or task result finality.

### Scheduling and registry truth

Current candidate source is group-first:

```text
Task(project,eventCode)
  -> WorkerCandidateIndex
  -> EventKey
  -> groupIds
  -> workerIds
  -> WorkerSchedulingView
  -> eligibility / rank / allocation / resource admission
```

`WorkerGroup` currently owns capability truth. `WorkerCapabilityAuthority`
composes an immutable `WorkerRegistrySnapshot` from worker registration rows
plus accepted capability reports.

`WorkerRegistrySnapshot`, `AdapterNodeRecord`, `NodeGroupBindingRecord`,
dispatch availability, worker load, and reachability are runtime read models.
They must not be persisted through direct DB CRUD paths. If operator history or
offline query needs these facts, emit trace/events and let the async event
pipeline materialize durable views.

### Resolved relation conflict

The old mainline contained node/group relation truth inside the capability
line:

- `WorkerGroupRecord.adapterNodeId`
- `WorkerRegistrySnapshot.groupIdsByAdapterNodeId(...)`
- worker-level compatibility projection paths that could derive group snapshot
  truth from worker rows.

Current code removes that node relation from `WorkerGroup` and
`WorkerRegistrySnapshot`. `NodeGroupBinding` now owns node/group relation
truth. `WorkerGroupCompatibilityProjection` is retired; WorkerGroup
declarations are the only capability truth for candidate-source indexes.

`WorkerRegistrySnapshot.groupIdsByAdapterNodeId(...)` was removed instead of
replaced because it had no production caller.

## Core Principles

### No WorkerSession

Do not introduce an engine-side `WorkerSession`.

Transport already owns connection-local and route-local evidence through
`connectionId`, `routeKey`, `transportNodeId`, route owners, and presence.
Duplicating that as an engine model would blur transport truth and scheduling
truth.

### Worker remains the dispatchable execution identity

`Worker` remains the smallest schedulable execution identity. `Device`,
`AccountSlot`, `AdapterNode`, and transport sessions must not replace it as the
scheduling subject.

### WorkerGroup remains the only capability truth

`WorkerGroup` owns:

- `eventBindings`
- project capability
- group-level defaults
- default concurrency/capacity declarations

`AdapterNode`, `NodeGroupBinding`, and raw capability reports must not own a
second event-capability model.

### AdapterNode is not transportNodeId

Keep these identities separate:

```text
adapterId
  -> adapter/protocol runtime identity

adapterNodeId
  -> worker registration endpoint / logical adapter deployment identity

transportNodeId
  -> actual transport consumer node / route-owner node in split runtime
```

They may be correlated by configuration, but they are not aliases.

### Attributes are evidence first

`deviceId`, `accountId`, `phoneId`, `devicePool`, `route`, `region`, and
similar data should enter as worker attributes or report attributes first.

They may support diagnostics, trace, filtering, routing rules, or future
ranking policy. They must not silently create device owners, account-slot
lifecycle, or implicit locks.

## Target Models

### AdapterNodeRecord

```java
record AdapterNodeRecord(
    String adapterNodeId,
    String adapterType,
    String adapterVersion,
    String endpointId,
    boolean enabled,
    boolean online,
    Instant registeredAt,
    Instant lastSeenAt,
    Map<String, String> attributes
) {}
```

Owns:

- adapter node identity
- adapter runtime metadata
- node-level enable/disable
- node-level online/offline evidence
- diagnostics
- callback/listener scope, when that later exists

Must not own:

- `WorkerGroup.eventBindings`
- worker capability truth
- worker load or reservation
- worker lease
- task result finality
- device lifecycle

### WorkerGroupRecord

Target shape:

```java
record WorkerGroupRecord(
    String groupId,
    Set<EventBinding> eventBindings,
    Set<String> projectCodes,
    Map<String, String> defaultAttributes,
    int defaultMaxConcurrentWork,
    boolean enabled,
    long capabilityVersion
) {}
```

Current code does not carry `adapterNodeId` on `WorkerGroupRecord`.

### NodeGroupBindingRecord

```java
record NodeGroupBindingRecord(
    String adapterNodeId,
    String groupId,
    String pluginVersion,
    String deploymentVersion,
    boolean enabled,
    boolean draining,
    Instant registeredAt,
    Instant updatedAt,
    Map<String, String> attributes
) {}
```

Owns:

- `adapterNodeId hosts groupId` deployment fact
- node-local group enabled/disabled state
- node-local draining state
- plugin/deployment metadata
- node-local metadata

Must not own:

- event bindings
- effective capability truth
- worker active load
- task lease
- result finality

`NodeGroupBindingRecord` must not carry `eventBindings`. Keeping event
capability only on `WorkerGroup` prevents three competing truths:

```text
WorkerGroup.eventBindings
NodeGroupBinding.eventBindings
WorkerCapabilityReport.availableEventCodes
```

### WorkerRecord

Target shape:

```java
record WorkerRecord(
    String workerId,
    String adapterNodeId,
    String adapterId,
    String groupId,
    Map<String, String> attributes,
    int maxConcurrentWork,
    boolean enabled,
    Instant registeredAt,
    Instant updatedAt
) {}
```

Owns:

- worker execution identity
- adapter node membership
- adapter/protocol runtime identity
- group membership
- worker-level attributes
- declared max concurrent work
- worker-level enabled flag

`adapterNodeId` and `adapterId` are not interchangeable. `adapterNodeId`
identifies the registration endpoint / logical adapter deployment node.
`adapterId` remains the concrete adapter or protocol runtime identity such as
polling, WebSocket, or socket adapter.

## Indexes

Target index set, with phase ownership:

```text
adapterNodeId -> AdapterNodeRecord
groupId -> WorkerGroupRecord
workerId -> WorkerRecord
adapterNodeId -> groupIds                 // AN-2 binding index
groupId -> adapterNodeIds                 // AN-2 binding index
groupId -> workerIds                      // existing / AN-3 canonicalized
adapterNodeId -> workerIds                // AN-3 populated worker membership
(adapterNodeId, groupId) -> workerIds     // AN-3 populated worker membership
(projectCode,eventCode) -> groupIds
projectCode -> groupIds
```

AN-2 did not expose canonical worker-membership indexes. After AN-3,
`adapterNodeId -> workerIds` and `(adapterNodeId, groupId) -> workerIds` are
populated from worker registration truth.

Scheduling must remain group-first:

```text
Task(project,eventCode)
  -> WorkerCandidateIndex
  -> groupIds by EventKey(project,eventCode)
  -> workerIds by groupId
  -> WorkerSchedulingView
  -> eligibility / rank / allocation / resource admission
```

Do not turn candidate source into:

```text
Task -> AdapterNode -> Workers
```

`AdapterNode` is not capability truth.

Storage must not expose worker supported-project or supported-event lookup as a
parallel candidate source. `WorkerStorage` keeps worker rows and group lookup;
capability narrowing belongs to `WorkerRegistrySnapshot` / `WorkerCandidateIndex`.

## Gate Semantics

Eligibility may compose these gates:

- `WorkerGroup.enabled`
- `NodeGroupBinding.enabled`
- `NodeGroupBinding.draining`
- `Worker.enabled`
- `WorkerReachabilityView`
- `WorkerRegistry` / `WorkerSlot.disabledSources`
- `WorkerRegistry` / `WorkerSlot`

Recommended meaning:

1. `WorkerGroup.disabled` removes the whole group from new candidate source.
2. `NodeGroupBinding.disabled/draining` blocks new work only for that
   adapter-node/group pair.
3. `Worker.disabled` or dispatch-disabled blocks one worker.
4. `WorkerReachabilityView.offline` blocks new dispatch but does not alter
   active leases.
5. `WorkerRegistry` reservation / exclusive lease failure remains resource
   admission.

`NodeGroupBinding.draining` is not capability deletion. It only says one node
is not taking new work for one group.

Raw binding state should not be read directly by matching. Convert node-local
availability through a policy/read-model path into `WorkerRegistry`
dispatch-disabled sources, then let scheduling continue to consume stable
availability truth.

## Worker Reports

### Capability report

Target path:

```text
WorkerCapabilityReport
  -> WorkerCapabilityAuthority
  -> validate worker exists
  -> validate reported event codes stay within WorkerGroup approved eventBindings
  -> update report-owned slice
  -> compose effective WorkerGroup / Worker capability view
  -> publish WorkerRegistrySnapshot
  -> WorkerCandidateIndex refresh
```

Not allowed:

- report directly mutates `WorkerGroup.eventBindings`
- report directly mutates `NodeGroupBinding`
- report directly mutates matching/ranking
- report directly affects active leases

Capability mutation affects future candidate source only. Active work remains
owned by `TaskWorkRuntime` and `TaskResultRuntime`.

### State report

Current accepted shape should remain:

```text
WorkerStateReport
  -> WorkerStateProjectionOwner
  -> WorkerDispatchAvailabilityPolicy
  -> WorkerRegistry / WorkerSlot.disabledSources
```

Not allowed:

- raw state enters matching/ranking
- raw state mutates `WorkerReachabilityView`
- raw state mutates `WorkerRegistry`

### Command acknowledgement

Current accepted shape should remain:

```text
WorkerCommandAcknowledgement
  -> WorkerCommandLifecycleOwner
  -> WorkerDispatchAvailabilityPolicy when command outcome affects dispatch
  -> WorkerRegistry / WorkerSlot.disabledSources
```

Not allowed:

- command acknowledgement enters `TaskResultRuntime`
- command acknowledgement mutates `TaskWorkRuntime`
- command acknowledgement directly changes scheduling decisions

## Phase Plan

### AN-0: Inventory and current-truth extraction

Goal: describe current worker registration and node/group relation truth
without changing behavior.

Scope:

- inventory `adapterId` usage
- inventory `transportHint` / `onlineStrategy` usage
- inventory `workerGroupId` usage
- inventory removed `WorkerGroupRecord.adapterNodeId`
- inventory removed `WorkerRegistrySnapshot.groupIdsByAdapterNodeId(...)`
- verify `WorkerGroupCompatibilityProjection` remains absent
- inventory worker registration through SDK/server
- inventory `WorkerCapabilityAuthority` registration-row dependencies
- inventory `WorkerCandidateIndex` group-first candidate path

Acceptance:

- current worker registration path is documented
- current node/group relation conflict is documented
- current compatibility projection seam is documented
- current capability authority inputs are documented
- no behavior changes

### AN-1: AdapterNode baseline

Goal: add adapter node identity without changing scheduling.

Scope:

- add `AdapterNodeRecord`
- add engine-owned adapter-node registry state
- support register/update/delete/list/get
- expose adapter-node diagnostics in narrow read paths if useful
- do not connect to candidate source

Acceptance:

- adapter node can be registered and queried
- adapter node does not change worker candidates
- source guard proves `AdapterNode` does not own event bindings

### AN-2: NodeGroupBinding baseline and relation migration start

Goal: create a dedicated owner for adapter-node/group relation and start
retiring the existing group-owned node relation.

Scope:

- add `NodeGroupBindingRecord`
- support bind/unbind/enable/disable/draining flag
- add indexes:
  - `adapterNodeId -> groupIds`
  - `groupId -> adapterNodeIds`
- forbid `eventBindings` on the binding
- keep `WorkerGroupCompatibilityProjection` retired
- prevent reintroducing group-owned node relation truth

Acceptance:

- one adapter node can bind multiple groups
- one group can bind multiple adapter nodes
- binding does not change `WorkerGroup` capability truth
- guard proves `NodeGroupBinding` does not own event bindings
- guard prevents AdapterNode/NodeGroupBinding from becoming capability truth

### AN-3: Worker registration requires adapterNodeId + groupId

Goal: move worker registration to explicit node/group membership.

Compatibility decision:

- AN-3 no longer keeps an adapterId-derived compatibility registration path.
- New registration callers must send `adapterNodeId` and `groupId`.
- AdapterNode and NodeGroupBinding must be registered explicitly before worker
  registration.
- Existing in-repo callers are migrated to the explicit sequence instead of
  preserving a fallback.

Scope:

- require `workerId`, `adapterNodeId`, and `groupId` in worker registration
- validate adapter node exists
- validate worker group exists
- validate `NodeGroupBinding(adapterNodeId, groupId)` exists
- refresh `WorkerRegistrySnapshot` after upsert
- keep candidate source group-first

This is a registry-truth change, not a light request-field addition, because
`WorkerCapabilityAuthority` still composes from registration rows.

Acceptance:

- missing adapter node or group registration fails
- unknown adapter node fails
- unknown group fails
- unbound node/group pair fails
- task candidate source still follows `EventKey -> groupIds -> workerIds`
- trace and diagnostics include `adapterNodeId`, `workerGroupId`, and
  event-binding evidence
- `adapterNodeId -> workerIds` and `(adapterNodeId, groupId) -> workerIds` are
  populated from worker registration truth

### AN-4: Retire WorkerGroup.adapterNodeId from mainline truth

Goal: remove the current node relation from capability truth.

Scope:

- remove `WorkerRegistrySnapshot.groupIdsByAdapterNodeId(...)` unless a
  temporary migration read is explicitly needed
- remove or demote `WorkerGroupRecord.adapterNodeId`
- keep `WorkerGroupCompatibilityProjection` absent
- update tests to prove group capability and node relation are separate
- update docs to describe the new canonical relation owner

Acceptance:

- mainline code no longer treats `WorkerGroupRecord.adapterNodeId` as canonical
- node/group relation queries come from `NodeGroupBinding`
- no production code depends on `WorkerRegistrySnapshot.groupIdsByAdapterNodeId(...)`
- capability truth remains only on `WorkerGroup`

### AN-5: Node-local drain and availability

Goal: let node-local drain affect only future dispatch.

Prerequisite: AN-3 must be complete enough that worker membership by
`adapterNodeId + groupId` is canonical. Without worker-level `adapterNodeId`,
node-local drain can only be approximated through the old group relation and
must not be implemented.

Scope:

- `NodeGroupBinding.draining=true` disables new dispatch for workers in that
  adapter-node/group pair
- other nodes hosting the same group remain eligible
- convert binding availability through the existing dispatch availability
  policy/read-model path
- do not let matching read raw binding state directly

Acceptance:

- `node-a/group-x` draining excludes only those workers
- `node-b/group-x` remains eligible
- active leases are unaffected
- group event bindings remain unchanged
- clearing drain makes the workers eligible again

### AN-6: Worker attribute normalization

Goal: keep device/account/route data as attributes unless a real owner is
introduced later.

Recommended attributes:

- `deviceId`
- `accountId`
- `phoneId`
- `devicePool`
- `region`
- `country`
- `routingTags`
- `agentVersion`

Acceptance:

- attributes are visible in snapshot/trace/diagnostics
- attributes can be used by routing or ranking policy
- same `deviceId` across multiple workers does not imply mutual exclusion
- no device/account owner or lock is introduced

### Deferred: owner outcome listener

Listener/callback work is intentionally deferred.

Reason:

- it spans capability, state, command, task-stage, and node-group owners
- it can easily become a broad event bus
- it is not required to converge registration truth

Only revisit this after AN-0 through AN-4 have a single relation owner.

If introduced later, listener behavior must be:

- after-owner-apply
- async
- bounded
- best-effort
- failure-isolated
- non-rollback

## Architecture Guards

Add targeted guards as phases land:

1. no engine `WorkerSession` model
2. `AdapterNode` must not own `eventBindings`
3. `NodeGroupBinding` must not own `eventBindings`
4. `WorkerCandidateIndex` must not use adapter node as primary capability key
5. scheduling must not read transport `connectionId`, `routeKey`, or session
   evidence as lifecycle truth
6. worker `deviceId` attributes must not create implicit device owner or lock
7. transport adapters must not mutate `WorkerRegistrySnapshot`
8. listeners, when added, must not mutate `TaskWorkRuntime`,
   `TaskResultRuntime`, or `WorkerRegistry`
9. `WorkerGroupRecord.adapterNodeId` and
   `WorkerRegistrySnapshot.groupIdsByAdapterNodeId(...)` must not be
   reintroduced

## Proof Plan

Authoritative deterministic proof:

- adapter-node registry tests
- node-group binding registry tests
- `WorkerCapabilityAuthority` tests
- `WorkerRegistrySnapshot` tests
- `WorkerCandidateIndex` tests
- architecture guards

Representative integrated proof:

- external worker registration through node/group binding
- group-first scheduling still selects the right workers
- node-local drain excludes only the intended node/group workers

Trace scenarios:

- `adapter-node-multi-group-routing`
- `group-multi-node-routing`
- `node-group-draining-exclusion`
- `worker-registration-requires-node-group-binding`
- `device-id-as-attribute-only`

Soak profile, later:

```text
adapter-node-registration-soak
  nodes=4
  groups=8
  workers=64
  periodic node-group drain/enable
  capability reports
  tasks across eventCodes
```

Invariant examples:

- submitted tasks converge according to task policy
- active leases at end are zero
- no dispatch to drained node/group
- no all-worker fallback for event-code tasks
- trace drops are zero

## Risks

### AdapterNode becomes capability truth

Mitigation:

- keep `eventBindings` on `WorkerGroup`
- source guard blocks event bindings in `AdapterNode`

### NodeGroupBinding becomes second capability truth

Mitigation:

- forbid `eventBindings` on the binding
- candidate source remains group-first

### Group-owned node relation reappears as parallel truth

Mitigation:

- `WorkerGroupRecord` has no `adapterNodeId`
- `WorkerRegistrySnapshot` has no group-by-adapter-node index
- node/group relation queries come from `WorkerManager` binding indexes

### AdapterNode is confused with transportNodeId

Mitigation:

- document and test identity boundaries
- transport route owners remain transport-owned evidence

### deviceId becomes hidden resource lock

Mitigation:

- keep device/account values as attributes
- require a future explicit owner before exclusivity semantics

### listener becomes a mutation backdoor

Mitigation:

- defer listener work
- when added, make it after-owner-apply and non-rollback
- source guard blocks core owner mutation from listener code

## Implemented Slice Order

First slice, completed:

```text
AN-0 + AN-1 + AN-2
```

That means:

1. inventory starting truth
2. add `AdapterNodeRecord`
3. add `NodeGroupBindingRecord`
4. add binding indexes only
5. start migration framing for group-owned node relation
6. no scheduling behavior change

Second slice, completed:

```text
AN-3 + AN-4
```

Move worker registration to `adapterNodeId + groupId` and retire
group-owned node relation as mainline truth.

Third slice, completed:

```text
AN-5
```

Implement node-local drain through dispatch availability.

Fourth slice, completed:

```text
AN-6
```

Normalize device/account/route attributes.

Listener/callback work remains deferred until relation ownership is stable.

## Final Target

```text
AdapterNode
  -> registration endpoint / logical adapter deployment identity / callback scope

NodeGroupBinding
  -> adapter node hosts worker group relation truth

WorkerGroup
  -> capability truth only

Worker
  -> dispatchable execution identity

Worker attributes
  -> device/account/route evidence

WorkerReachabilityView
  -> transport-derived reachability truth

WorkerRegistry / WorkerSlot.disabledSources
  -> dispatch gate truth

WorkerCandidateIndex
  -> group capability to worker candidate source
```

The desired outcome is not a bigger registration subsystem. The desired
outcome is a smaller truth graph:

```text
capability belongs to WorkerGroup
node/group hosting belongs to NodeGroupBinding
runtime reachability belongs to transport presence
dispatchability belongs to dispatch availability
execution identity belongs to Worker
```
