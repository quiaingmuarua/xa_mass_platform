# WorkerGroup Capability And Candidate Index Roadmap

Last updated: 2026-05-18

Status: proposed engine-internal convergence roadmap. This is a direction
document, not implemented baseline behavior.

## Summary

This roadmap does not split worker management into a separate Maven module,
service, or broad control-plane component.

The goal is narrower and higher ROI: converge worker capability ownership and
the scheduling candidate source inside the current engine boundary.

Target model:

```text
AdapterNode
  -> WorkerGroup
      -> Worker
```

Meaning:

```text
AdapterNode
  -> adapter / transport host identity and presence-evidence source

WorkerGroup
  -> declared capability and deployment cohort

Worker
  -> concrete runtime execution identity
```

Core direction:

- `eventCode` / `eventBindings` are normalized as group capability.
- `WorkerGroup` owns declared capability.
- `Worker` binds to a group and does not declare event capability directly.
- matching becomes two-stage:
  - stage 1 narrows candidates by group capability index
  - stage 2 applies scheduling policy, resource admission, and dispatch
- `targetWorkerId` uses direct lookup and still checks capability and resource
  policy.
- one adapter node can host multiple worker groups.
- `WorkerLoadView`, leases, result convergence, and scheduling kernel ownership
  stay unchanged.

This roadmap is about capability and candidate-source convergence, not about
service extraction.

## Goals

### 1. Fix The Three-Layer Worker Access Model

```text
AdapterNode:
  adapter / transport host identity and presence-evidence source

WorkerGroup:
  declared capability and deployment cohort
  owns eventBindings, plugin/deployment metadata, default attributes, default
  declared capacity

Worker:
  concrete worker instance
  owns workerId, groupId, runtime identity, last-seen evidence, individual
  attributes
```

`WorkerGroup` may include plugin metadata, but it is not limited to one plugin.
It is a declared capability and scheduling-label unit. A future plugin may
publish multiple groups, and an adapter node may host multiple groups.

### 2. Put Event Capability Only On WorkerGroup

First-version rule:

```text
eventBindings are registered on WorkerGroup only
Worker does not register eventBindings directly
Worker inherits group capability
```

If a single worker needs special capability, create a single-worker group rather
than adding worker-level event-binding overrides.

### 3. Build A Bounded In-Memory Candidate Index

Avoid:

```text
task -> scan all workers -> filter
```

Use:

```text
task(project,eventCode)
  -> groupIdsByEventKey
  -> workerIdsByGroupId
  -> WorkerSchedulingCandidate
```

This is an index, not an in-memory SQL engine. The first version should keep the
shape small and explicit.

### 4. Make Matching Two-Stage

The target matching shape is:

```text
Stage 1: candidate-source narrowing
  task(project,eventCode)
    -> EventKey
    -> WorkerGroup ids
    -> worker ids
    -> materialized WorkerSchedulingCandidate list

Stage 2: scheduling policy and resource admission
  candidates
    -> prefilter
    -> rule evaluation
    -> ranking
    -> allocation
    -> reservation / resource admission
    -> dispatch binding
```

Stage 1 is a source/index problem. It should be deterministic, cheap, and based
on declared group capability plus direct worker lookup.

Stage 2 is the scheduling kernel. It keeps policy, rule evaluation, ranking,
allocation, capacity admission, runtime claim, lease, and dispatch ownership.

Stage 1 must not become a second matching strategy. It only decides which
workers are worth evaluating.

### 5. Keep Target Worker Direct Lookup

If a task specifies `targetWorkerId`:

```text
targetWorkerId
  -> workersById
  -> groupId
  -> capability check
  -> reachability / capacity / resource policy check
```

`targetWorkerId` is a lookup shortcut, not a policy bypass. It must not fall
back to full worker-list scanning.

## Non-Goals

This roadmap does not:

- create a separate worker-management Maven module
- create a worker-management microservice
- implement `WorkerCommand` lifecycle
- implement `WorkerStateReport` projection
- implement unified event-envelope runtime behavior
- create a SQL-like in-memory query engine
- move `WorkerLoadView` into WorkerGroup or worker registration
- make group ownership include lease, reservation, active load, attempt
  lifecycle, or result convergence
- implement complex multi-dimensional routing-tag indexes
- implement an operator console
- reintroduce WorkerContext under a new name
- add pass-through bridge/facade layers that only rename current calls

`UNIFIED_EVENT_ENVELOPE_ROADMAP.md` remains a separate future north-star. It is
not a dependency for WG-0 through WG-3.

## Core Model

### AdapterNodeRecord

Directional model:

```java
record AdapterNodeRecord(
        String adapterNodeId,
        String adapterType,
        String nodeVersion,
        boolean online,
        long lastSeenAt
) {}
```

Responsibilities:

- adapter / transport host identity
- connection or host-level presence evidence
- one adapter node may host multiple worker groups

AdapterNode does not own:

- event capability truth
- task scheduling policy
- result finality
- worker runtime load

### WorkerGroupRecord

Directional model:

```java
record WorkerGroupRecord(
        String groupId,
        String adapterNodeId,
        Set<EventBinding> eventBindings,
        Map<String, String> groupAttributes,
        int defaultMaxConcurrentWork,
        GroupDispatchMode dispatchMode,
        boolean enabled,
        long updatedAt
) {}
```

Responsibilities:

- `eventBindings` capability truth
- plugin or adapter metadata such as `pluginName` / `pluginVersion`
- group-level routing tags and scheduling attributes
- default declared capacity
- group administrative dispatch gate, such as enabled/disabled/draining

WorkerGroup does not own:

- leases
- reservations
- active runtime load
- attempt lifecycle
- result convergence

First-version scope:

- `groupId` is globally unique.
- group ownership is adapter-node scoped.
- global group templates are out of scope until a real need appears.

### WorkerRecord

Directional model:

```java
record WorkerRecord(
        String workerId,
        String groupId,
        String adapterNodeId,
        WorkerRuntimeInfo runtimeInfo,
        Map<String, String> attributes,
        DispatchAvailability dispatchAvailability,
        long lastSeenAt
) {}
```

Responsibilities:

- concrete worker identity
- group binding
- adapter-node binding
- worker-level attributes
- last-seen / reported runtime evidence
- administrative dispatch gate if needed

Worker does not own event capability. Matching capability comes from its group.

Reachability truth should be consumed through `WorkerReachabilityView` at
materialization time. If a worker record carries reachability-like fields, they
are projection/cache evidence, not the source of scheduling reachability truth.

`DispatchAvailability` is an administrative gate only. It must not contain
observed lease count, reserved count, active load, or busy/occupied runtime
state.

### EventBinding

First-version model:

```java
record EventBinding(
        String project,
        String eventCode,
        Set<String> routingTags,
        Map<String, String> attributes
) {}
```

Minimum identity:

```text
project
eventCode
```

Normalization rules:

- `project` is trimmed, non-empty, and case-sensitive.
- `eventCode` is trimmed, non-empty, and case-sensitive.
- duplicate `(project,eventCode)` bindings in one group normalize to one
  binding.
- capability report/update replaces the group's full binding set in the first
  version.
- `routingTags` and binding attributes are retained but not indexed in the
  first version.

## In-Memory Snapshot

Use an immutable snapshot model for the first implementation:

```java
final class WorkerRegistrySnapshot {
    Map<String, AdapterNodeRecord> adapterNodesById;
    Map<String, WorkerGroupRecord> groupsById;
    Map<String, WorkerRecord> workersById;

    Map<String, Set<String>> groupIdsByAdapterNodeId;
    Map<String, Set<String>> workerIdsByGroupId;
    Map<String, String> groupIdByWorkerId;
    Map<EventKey, Set<String>> groupIdsByEventKey;
}
```

Outer holder:

```java
AtomicReference<WorkerRegistrySnapshot> snapshotRef;
```

Reads use the current immutable snapshot. Writes rebuild affected indexes and
publish with an atomic swap.

### EventKey

```java
record EventKey(
        String project,
        String eventCode
) {}
```

## Index Rules

Required first-version indexes:

```text
workerId -> WorkerRecord
groupId -> WorkerGroupRecord
adapterNodeId -> AdapterNodeRecord

adapterNodeId -> groupIds
groupId -> workerIds
workerId -> groupId
(project,eventCode) -> groupIds
```

Optional later index:

```text
(project,eventCode,routingTag) -> groupIds
```

Do not add the routing-tag index until measured need exists.

Snapshot tests must cover:

- group registration
- group binding update
- group deletion or disable
- worker registration
- worker movement between groups
- worker deletion
- adapter node with multiple groups
- removed event binding no longer matching

## Candidate Source

Introduce or converge toward a narrow indexed source:

```java
interface WorkerCandidateIndex {
    List<WorkerSchedulingCandidate> candidatesFor(Task task);

    Optional<WorkerSchedulingCandidate> candidateForWorkerId(Task task, String workerId);
}
```

`WorkerCandidateIndex` narrows the search space. It does not decide final
eligibility or allocation.

It may own:

- event-key lookup
- group-to-worker lookup
- target worker direct lookup
- basic group/worker existence checks
- materializing declared worker/group facts into scheduling candidates

It must not own:

- QLExpress rule evaluation
- ranking
- allocation
- reservation
- resource locks
- min-worker gate
- fairness
- runtime lease or result behavior

### Normal Path

```text
Task(project,eventCode)
  -> EventKey(project,eventCode)
  -> groupIdsByEventKey
  -> groupsById
  -> workerIdsByGroupId
  -> workersById
  -> materialize WorkerSchedulingView
  -> WorkerSchedulingCandidate
```

### Target Worker Path

```text
targetWorkerId
  -> workersById
  -> groupIdByWorkerId
  -> groupsById
  -> capability check
  -> materialize WorkerSchedulingView
```

The target path must still check:

- worker exists
- group exists
- group supports task project and event code
- worker is reachable
- worker/group dispatch is enabled
- capacity is available
- resource policy allows dispatch

`targetWorkerId` must never trigger a full worker-list scan.

## WorkerSchedulingView Materialization

`WorkerSchedulingView` should combine declared facts with runtime facts:

```text
WorkerRecord
WorkerGroupRecord
WorkerReachabilityView
WorkerLoadView
```

Ownership remains split:

```text
WorkerGroup / WorkerRecord
  -> declared facts

WorkerReachabilityView
  -> transport/reachability facts

WorkerLoadView
  -> observed runtime load / reservation / active lease
```

Directional materialization:

```java
WorkerSchedulingView materializeView(
        WorkerRecord worker,
        WorkerGroupRecord group,
        WorkerReachabilityView reachabilityView,
        WorkerLoadView loadView
)
```

Do not merge `WorkerLoadView` into `WorkerGroupRecord`.

Capacity rule for the first version:

- group `defaultMaxConcurrentWork` is declared capacity.
- per-worker capacity override is out of scope.
- current available capacity is derived by scheduling/resource policy from
  declared capacity plus `WorkerLoadView`.

## Matching Flow

Matching is intentionally two-stage.

### Stage 1: Candidate Source Narrowing

Stage 1 changes the source of candidates from full worker enumeration to an
indexed source:

```text
WorkerCandidateIndex
  -> EventKey(project,eventCode)
  -> groupIdsByEventKey
  -> workerIdsByGroupId
  -> materialized WorkerSchedulingCandidate list
```

For targeted tasks:

```text
WorkerCandidateIndex
  -> targetWorkerId
  -> workersById
  -> groupIdByWorkerId
  -> group capability check
  -> materialized WorkerSchedulingCandidate
```

Stage 1 may reject obvious non-candidates:

- missing worker
- missing group
- group disabled
- missing `(project,eventCode)` binding
- target worker whose group does not support the task capability

Stage 1 must not own:

- QLExpress rule evaluation
- ranking
- allocation
- reservations
- worker locks
- resource admission
- lease state
- retry or result behavior

### Stage 2: Scheduling Policy And Resource Admission

Keep the existing scheduling kernel mainline for Stage 2:

```text
WorkerCandidateIndex
  -> RuleBasedTaskWorkerMatchingStrategy
  -> prefilter
  -> rule evaluation
  -> ranker
  -> allocation policy
  -> reservation
  -> dispatch binder
```

Stage 2 owns:

- prefilter and rule evaluation
- ranking and preference
- allocation shape
- declared-capacity versus observed-load interpretation
- reservation and resource policy
- runtime claim / lease / dispatch binding

The candidate lookup implementation changes from:

```text
WorkerSchedulingCandidateEnumerator
  -> all workers
```

to:

```text
WorkerCandidateIndex
  -> eventKey indexed groups
  -> group workers
```

This must preserve existing prefilter, rule, ranker, allocation, resource
policy, runtime claim, lease, result, and terminal behavior.

The important boundary is:

```text
WorkerGroup index decides who can be considered.
Scheduling policy decides who should be used.
Runtime/resource owners decide who can be admitted now.
```

## Phase Plan

### Phase WG-0: Baseline And Inventory

Goal: no behavior change. Make the current worker lookup path explicit.

Scope:

- inventory current `WorkerManager`, `WorkerSchedulingCandidateEnumerator`, and
  matching worker-query paths
- mark all places that still scan all workers
- mark uses of `supportedProjects`, `supportedEventCodes`, attributes,
  routing tags, and `eventBindings`
- document that group `eventBindings` will become capability truth
- update README/roadmap naming away from the old split framing

Out of scope:

- no new model
- no matching behavior change
- no module split

Acceptance:

- inventory is clear
- no behavior change
- next phase can add model/index without touching matching

### Phase WG-1: Introduce WorkerGroup Model And Snapshot Index

Goal: add thin WorkerGroup and indexes without wiring matching.

Scope:

- add `WorkerGroupRecord`
- add `EventBinding`
- add `AdapterNodeRecord` only if needed for the index boundary
- add `WorkerRegistrySnapshot`
- add `groupIdsByEventKey`, `workerIdsByGroupId`, `groupIdByWorkerId`,
  and `groupIdsByAdapterNodeId`
- allow worker registration to bind `groupId`
- support adapter node to multiple group relation

Out of scope:

- no matching path change
- no `WorkerLoadView` change
- no result/runtime change
- no external adapter registration
- no worker command/state report implementation

Acceptance:

- group can register event bindings
- worker can bind group
- snapshot index unit tests pass
- snapshot update/delete/re-register does not leak old indexes
- read path remains snapshot-based

### Phase WG-2: Introduce WorkerCandidateIndex Without Rewiring Matching

Goal: prove indexed candidate lookup independently.

Scope:

- add `WorkerCandidateIndex`
- implement normal `EventKey(project,eventCode)` lookup
- implement `targetWorkerId` direct lookup
- materialize `WorkerSchedulingCandidate` from worker/group plus existing
  reachability/load views
- test capability mismatch and adapter-node multi-group behavior

Out of scope:

- do not replace `RuleBasedTaskWorkerMatchingStrategy` input yet
- do not change scheduling matrix behavior
- do not add routing-tag index

Acceptance:

- eventCode A returns only group A workers
- eventCode B returns only group B workers
- one adapter node can host multiple groups
- `targetWorkerId` direct lookup succeeds without full enumeration
- target worker capability mismatch is rejected

### Phase WG-3: Switch Matching To Indexed Candidate Source

Goal: matching no longer scans all workers for event-code tasks.

Scope:

- wire `RuleBasedTaskWorkerMatchingStrategy` or its candidate enumerator to
  consume `WorkerCandidateIndex`
- normal task candidate source uses event-key group index
- target worker source uses direct lookup
- keep existing prefilter/rule/ranker/resource policy

Out of scope:

- no scheduling policy redesign
- no allocation redesign
- no runtime lease/result change

Acceptance:

- normal task does not scan full worker list
- event-code mismatch group workers do not enter candidate list
- target worker path does not enumerate all workers
- target worker path still checks capability and resource policy
- existing scheduling matrix remains green

### Phase WG-4: Normalize Event Capability Truth

Goal: group event bindings become scheduling capability truth.

Scope:

- define `EventKey(project,eventCode)` as capability key
- read group event bindings as the default matching capability
- keep `supportedProjects` and `supportedEventCodes` as compatibility/read
  projections only
- generate compatibility projection from group bindings where needed
- update docs for eventCode naming and binding normalization

Out of scope:

- no worker-level event-binding override
- no routing-tag index
- no unified event-envelope runtime

Acceptance:

- default matching does not depend on worker-level supported event codes
- group event bindings and compatibility projection remain consistent
- tests cover projection consistency

### Phase WG-5: AdapterNode Multi-Group Registration

Goal: make adapter-node-to-many-groups an explicit supported shape.

Scope:

- finalize `AdapterNodeRecord`
- support adapter node registration if not already present
- bind group registration to adapter node
- bind worker registration to adapter node and group
- test multiple groups on one adapter node with different event bindings

Out of scope:

- adapter node does not own scheduling policy
- adapter node does not own event capability
- no transport adapter service split

Acceptance:

- adapter node can host multiple groups
- different groups under one node can register different event bindings
- task eventCode matches the correct group
- adapter-node offline affects reachability, not group capability truth

### Phase WG-6: Worker Capability Self-Report

Goal: let worker/adapter report group capability into the same model.

Directional request:

```text
WorkerCapabilityReport:
  groupId
  adapterNodeId
  eventBindings
  attributes
  defaultMaxConcurrentWork
```

Scope:

- add SDK/reporting method only when an actual caller exists
- accept capability report into `WorkerGroupRecord` and indexes
- record trace when capability report is accepted
- ensure full binding replacement updates indexes

Out of scope:

- no `WorkerCommand`
- no `WorkerStateReport`
- no unified event envelope runtime
- no command ack lifecycle

Acceptance:

- worker/adapter can report group capability
- report updates index
- old binding deletion stops matching
- later task scheduling uses the updated group binding

### Phase WG-7: Thin Adapter Registration

Goal: allow external adapter registration without making adapter own scheduling
policy.

Scope:

- add a thin transport adapter registry only if real callers need it
- register adapter node identity and metadata
- record adapter lifecycle start/stop evidence
- expose adapter-node-to-group relation for diagnostics

Rules:

```text
adapter owns:
  connection
  frame decode/encode
  delivery
  result ingress normalization
  presence evidence

adapter does not own:
  worker capability policy
  scheduling semantics
  result finality
  WorkerLoadView
```

Acceptance:

- adapter can register node
- adapter can host multiple groups
- adapter does not directly write scheduling policy
- source guard prevents transport from owning worker capability decisions

## Trace And Test Plan

Trace fields to add when behavior changes:

```text
adapterNodeId
workerGroupId
eventCode
eventBindingMatched
workerCandidateSource = GROUP_INDEX | TARGET_WORKER
```

Scenario tests:

```text
group-event-routing
  task eventCode -> group -> worker

adapter-node-multiple-groups
  one adapter node, multiple groups, different eventBindings

target-worker-direct-lookup
  targetWorkerId does not scan all workers

capability-report-updates-index
  report capability -> index update -> task match

capability-mismatch-rejected
  targetWorkerId exists but group does not support eventCode
```

Representative engine verification after matching rewiring:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=EngineSchedulingCoreArchitectureGuardTest,EngineSchedulingCoreSuite,RuleBasedTaskWorkerMatchingStrategyTest,TaskWorkerAssignListenerTest,TaskResourceReleaseListenerTest" test
```

## Architecture Guards

Add targeted guards only when a path is retired. Avoid broad regex guards for
future ideas.

Useful guards:

- scheduling candidate hot path must not call a full worker list when task has
  `eventCode`
- worker model must not declare `eventBindings` directly
- WorkerGroup must not depend on `WorkerLoadView`
- `WorkerLoadView` must not depend on WorkerGroup storage
- transport adapter must not call scheduling policy
- `targetWorkerId` path must use direct lookup
- group must not own lease, reservation, active load, or result convergence

Prefer behavioral tests or spies for "does not enumerate all workers" where a
source regex would be too broad.

## Risks

### Risk 1: WorkerGroup Becomes WorkerContext 2.0

Mitigation:

- WorkerGroup owns declared capability only
- WorkerGroup does not own runtime lifecycle
- WorkerGroup does not own lease/reservation/occupied state
- account/device inventory does not become scheduling truth without an explicit
  derived scheduling fact

### Risk 2: Group And Worker Capability Become Dual Truth

Mitigation:

- event bindings live only on WorkerGroup
- worker does not register event bindings
- supported projects/event codes are projection only
- special worker capability uses a single-worker group

### Risk 3: Index Staleness

Mitigation:

- immutable `WorkerRegistrySnapshot`
- writes rebuild indexes then atomic swap
- tests cover update, deletion, move, re-register, and binding replacement

### Risk 4: Target Worker Bypasses Policy

Mitigation:

- target lookup still checks group capability, reachability, dispatch
  availability, load/capacity, and resource policy
- target lookup is direct source optimization only

### Risk 5: Over-Indexing

Mitigation:

- first version only indexes `(project,eventCode) -> groupIds` and
  `groupId -> workerIds`
- routing-tag indexes wait for measured bottlenecks

### Risk 6: Plugin Metadata Becomes The Group Identity

Mitigation:

- WorkerGroup is a capability declaration unit
- plugin metadata is optional descriptive data
- do not introduce global plugin/group templates until a concrete need appears

## Recommended First Slice

Start with WG-0 and the smallest WG-1 model/index subset:

- replace old split documentation with WorkerGroup capability index direction
- add `WorkerGroupRecord` / `EventBinding`
- add immutable `WorkerRegistrySnapshot`
- build basic indexes:
  - `groupId -> workerIds`
  - `workerId -> groupId`
  - `adapterNodeId -> groupIds`
  - `(project,eventCode) -> groupIds`
- add unit tests for index update/delete/re-register
- do not change matching mainline yet

Second slice:

- add `WorkerCandidateIndex`
- prove event-key and target-worker lookup independently

Third slice:

- wire matching to the indexed source
- run scheduling matrix and trace verification

## Final Target

```text
AdapterNode
  owns adapter/transport host identity and presence evidence

WorkerGroup
  owns declared capability:
    eventBindings
    group attributes
    plugin metadata
    default declared capacity
    administrative enabled/draining gate

Worker
  owns runtime execution identity:
    workerId
    groupId
    adapterNodeId
    individual attributes
    last-seen evidence

WorkerCandidateIndex
  owns indexed candidate source:
    eventKey -> groupIds -> workerIds
    targetWorkerId -> worker -> group
    stage-1 candidate narrowing only

WorkerLoadView
  owns observed runtime load:
    active leases
    reservations
    resource usage

Engine Scheduling
  owns stage-2 matching:
    prefilter
    rule evaluation
    ranking
    allocation
    reservation
    dispatch
```

One-line target:

```text
Use a thin WorkerGroup plus a fixed in-memory index to move capability truth
from individual workers to groups, let matching jump from eventCode to candidate
groups in stage 1, keep policy/resource admission in stage 2, avoid full worker
scans, and keep WorkerGroup out of runtime resource ownership.
```
