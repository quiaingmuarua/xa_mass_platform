# WorkerGroup Capability And Candidate Index Roadmap

Last updated: 2026-05-18

Status: proposed engine-internal convergence roadmap. This is a direction
document, not implemented baseline behavior.

This roadmap starts after WorkerContext retirement and Scheduling Kernel
Baseline v1. It does not complete WorkerContext removal; that milestone is
closed. Its goal is to replace all-worker candidate enumeration with group
capability indexed candidate sourcing while keeping the existing scheduling
kernel owners intact.

## Summary

This roadmap does not split worker management into a separate Maven module,
service, or broad control-plane component.

The goal is narrower and higher ROI: converge worker capability ownership and
the scheduling candidate source inside the current engine boundary.

Before adding new models, the first implementation step must concentrate
worker-related scheduling code into clearer engine packages and shorten
visibility. The project does not need a new module boundary, but it does need a
smaller package-level blast radius before the candidate source changes.

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
  Worker-level supported project/event fields, where they still exist, are
  migration inputs or read projections only.
- matching becomes two-stage:
  - stage 1 narrows candidates by group capability index
  - stage 2 applies scheduling policy, resource admission, and dispatch
- `targetWorkerId` uses direct lookup, checks declared capability in Stage 1,
  and still goes through Stage 2 runtime/resource admission.
- one adapter node can host multiple worker groups.
- mechanism and policy stay separated: candidate indexing is mechanism,
  matching/ranking/allocation are policy, and runtime admission remains the
  resource owner path.
- `WorkerLoadView`, leases, result convergence, and scheduling kernel ownership
  stay unchanged.

This roadmap is about capability and candidate-source convergence, not about
service extraction.

Current implementation baseline before this roadmap:

```text
Closed:
  -> WorkerContext model/storage/API/runtime/trace identity retirement
  -> Scheduling Kernel Baseline v1

Current:
  -> worker-level scheduling kernel
  -> load/rank/budget/refill/resource owners in mainline
  -> candidate source still starts from worker enumeration

Next:
  -> WorkerGroup capability truth
  -> WorkerCandidateIndex
  -> matching candidate source no longer scans all workers for event-code tasks
```

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

`WorkerGroup` must not become an account slot, occupancy model, resource lock,
or runtime lease owner. Those responsibilities stay in the existing runtime and
scheduling-resource owners.

### 2. Put Event Capability Only On WorkerGroup

First-version rule:

```text
eventBindings are registered on WorkerGroup only
Worker does not register eventBindings directly
Worker inherits group capability
```

If a single worker needs special capability, create a single-worker group rather
than adding worker-level event-binding overrides.

`EventBinding(project,eventCode)` is the only candidate-source capability truth
after WG-3. Worker-level `supportedProjects` and `supportedEventCodes`, where
they still exist, must be generated/read projections or short migration inputs;
matching must not read group capability and worker capability as two parallel
truths.

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
  -> group capability check
  -> declared candidate materialization
  -> Stage 2 runtime admission
```

`targetWorkerId` is a lookup shortcut, not a policy bypass. It must not fall
back to full worker-list scanning.

### 6. Keep Mechanism And Policy Separate

The first implementation can use simple and even inefficient policies. It must
not mix policy decisions into mechanism owners.

Rules:

```text
mechanism
  -> owns data movement, indexes, lookup, materialization, and runtime state

policy
  -> owns preference, ranking, allocation shape, and admission decisions

owner path
  -> owns the actual state mutation and repair/release lifecycle
```

For this roadmap:

```text
WorkerRegistrySnapshot / WorkerCandidateIndex
  -> mechanism: declared capability lookup and candidate-source narrowing

RuleBasedTaskWorkerMatchingStrategy / ranker / allocation policy
  -> policy: eligibility, preference, ranking, and allocation

WorkerDispatchResourcePolicy / WorkerDispatchResourceReleaser / runtime claim
  -> runtime/resource owner path: reservation, lock, claim, lease, release
```

Do not over-engineer strategy extensibility in the first version. A fixed,
simple policy is acceptable. What must remain extensible is the owner boundary:
replacing policy later must not require rewriting group capability storage,
candidate indexes, lease state, or result convergence.

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
not a dependency for WG-0 through WG-5.

## Execution Shape

This roadmap has two layers:

```text
Core line
  -> WG-0 through WG-5
  -> package concentration, WorkerGroup capability truth, candidate index,
     matching rewiring, capability-truth cleanup, trace proof

Future extensions
  -> first-class AdapterNode lifecycle, capability self-report,
     routing-tag indexes, worker control-plane features
```

Only the core line is implementation scope for the first wave. Future
extensions are direction markers; they are not acceptance criteria for the core
line.

Each phase must be independently shippable:

- behavior-preserving phases must compile and keep scheduling tests green
- model/index phases must add isolated unit tests before wiring hot paths
- behavior-changing phases must prove scheduling behavior and trace evidence
- no phase should require a later phase to restore correctness
- no phase should open SDK/server/transport/result changes unless explicitly
  owned by that phase

## Core Model

### AdapterNodeId In The Core Line

The core line only needs an opaque `adapterNodeId` to keep the
AdapterNode -> WorkerGroup -> Worker shape visible and support one adapter node
hosting multiple groups.

Do not implement AdapterNode lifecycle in WG-0 through WG-5.

Future directional model:

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

Core-line scope:

- WG-1 may store `adapterNodeId` as an opaque declared field on group/worker
  records.
- Do not implement AdapterNode lifecycle, adapter registry, or transport
  presence ownership in the core line.
- AdapterNode becomes a first-class model only in a future extension after the
  WorkerGroup capability index is stable.

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
- worker locks
- runtime queue depth
- dispatch binding state
- task assignment state
- online/offline source of truth

First-version scope:

- `groupId` is globally unique.
- group ownership is adapter-node scoped.
- `adapterNodeId` may remain an opaque declared field in the core line.
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

### Capability Truth Migration Rule

The implementation should choose one of these modes before WG-1 begins:

```text
strict mode
  -> worker registration binds groupId
  -> group eventBindings are the only event capability input
  -> worker-level event capability fields are removed or derived

migration mode
  -> existing worker capability input is converted into a generated group
  -> matching and indexes still read group truth only
```

Do not keep a long-lived dual truth where matching sometimes reads
WorkerGroup.eventBindings and sometimes reads worker-level supported events.
This project is not yet constrained by external compatibility, so strict mode
is preferred when the diff remains manageable.

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
- opaque adapterNodeId with multiple groups
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

The target path must still check Stage 1 declared/model facts:

- worker exists
- group exists
- group supports task project and event code
- worker/group dispatch is enabled

`targetWorkerId` must never trigger a full worker-list scan.

Reachability, load/capacity, resource policy, reservation, and worker lock are
Stage 2 runtime-admission checks. `WorkerCandidateIndex` may attach facts needed
by Stage 2, but it must not interpret `WorkerLoadView` or resource policy into
an admission decision.

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

Stage 1 may over-include candidates. Stage 2 must preserve correctness.

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

### Phase WG-0: Package Concentration, Visibility, And Inventory

Goal: no behavior change. Concentrate worker-related scheduling code inside the
engine package structure, shorten visibility, and make the current worker
lookup path explicit.

Scope:

- collect worker-related scheduling/candidate/capability code into coherent
  engine-owned packages before introducing new WorkerGroup models
- reduce `public` visibility to package-private where callers are same-package
  implementation details
- keep only real cross-package/cross-module owner surfaces public
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
- no public SDK/server API change
- no WorkerGroup/AdapterNode implementation yet

Acceptance:

- worker-related scheduling internals are package-concentrated enough that
  later WG-1/WG-2 changes have a small caller surface
- same-package implementation details are no longer unnecessarily public
- cross-package public surfaces are intentional owner boundaries, not accidental
  convenience visibility
- inventory lists every full-worker enumeration path in engine scheduling
- inventory lists every worker capability truth source:
  `supportedProjects`, `supportedEventCodes`, attributes, routing tags, and
  `eventBindings`
- inventory lists every `targetWorkerId` path and whether it uses direct lookup
  or enumeration
- inventory identifies which classes will call `WorkerCandidateIndex` in WG-3
- inventory records which types must remain public owner boundaries and which
  can be package-private implementation details
- no behavior change
- next phase can add model/index without touching matching

Suggested inventory scan:

```powershell
rg "findWorkerCandidates|getAllWorkers|listWorkers|supportedEventCodes|supportedProjects|eventBindings|targetWorkerId" xa-mass-engine/src/main -n
```

Current WG-0 inventory:

| Surface | Current owner/path | Current behavior | WG replacement direction |
|---|---|---|---|
| assignment worker candidate count | `TaskWorkerAssignListener -> WorkerManager.findWorkerCandidates(task).size()` | allocation planning uses the same worker candidate source count that matching later consumes | WG-3 should get candidate count from indexed candidate source without duplicating lookup logic |
| matching candidate source | `RuleBasedTaskWorkerMatchingStrategy -> WorkerManager.findWorkerCandidates(task)` | strategy receives worker rows, then `WorkerSchedulingCandidateEnumerator` materializes one worker-level scheduling candidate per worker | WG-3 should replace this source with `WorkerCandidateIndex` while keeping Stage 2 prefilter/rule/ranker/resource behavior |
| target worker lookup | `WorkerManager.findWorkerCandidates(task)` | `targetWorkerId` is checked first and returns direct `getWorker(targetWorkerId)` singleton or empty list | WG-2/WG-3 keep direct lookup, then apply group capability gate before Stage 2 |
| sdk event candidate narrowing | `WorkerManager.findWorkerCandidates(task)` | `sdkEventCode` uses `WorkerStorage.getWorkersBySupportedEventCode(eventCode)` | WG-3 replaces worker supported-event lookup with `(project,eventCode) -> groupIds -> workerIds` |
| non-event project candidate narrowing | `WorkerManager.findWorkerCandidates(task)` | project-only tasks use `WorkerStorage.getWorkersBySupportedProject(project)` | WG-3 either uses group event/project capability or a deliberate project-level group binding path |
| fallback candidate source | `WorkerManager.findWorkerCandidates(task)` | tasks without target, event code, or project fall back to `WorkerStorage.getAllWorkers()` | WG-3 must make any remaining full-scan fallback explicit and guarded |
| worker scheduling view capability fields | `WorkerSchedulingView` | reads `Worker.supportedProjects` and `Worker.supportedEventCodes` into the Stage 2 read model | WG-4 removes or derives these from group bindings so they do not remain a second matching truth |
| rule context capability fields | `WorkerMatchContext` | exposes `supportedProjects`, `supportedEventCodes`, `supportsProject`, and `supportsEvent` from `WorkerSchedulingView` | WG-4 updates rule context to read group-derived scheduling facts only |
| dispatch runtime claim capability evidence | `SimpleTaskDispatchBinder.supportedEventCodes(...)` | passes worker supported event codes into runtime claim target metadata | WG-4 must either derive this from group binding or prove it is read-only claim metadata, not matching truth |
| diagnostics capability snapshot | `AssignmentRecordService -> WorkerSchedulingSnapshot` | snapshots worker scheduling supported projects/event codes for diagnostics | WG-4 should update diagnostics to snapshot group-derived capability evidence |
| worker read API / SDK projection | `WorkerSnapshot`, `WorkerRegistration`, server catalog/worker APIs | `eventBindings` exists, while supported project/event fields remain compatibility/read surfaces | WG-1/WG-4 decide strict mode versus generated-group migration and prevent these projections from driving matching |

Public surface inventory:

| Type | Current visibility | WG-0 decision |
|---|---|---|
| `WorkerSchedulingCandidate` | public | remains public inside engine module because assignment, binder, resource, trace, and diagnostics cross packages consume it |
| `WorkerSchedulingView` | public | remains public inside engine module because rule context, trace, diagnostics, and tests consume it |
| `WorkerMatchContext` | public | remains public for rule/ranker tests and current strategy package consumption |
| `WorkerCandidateRanker` | public | remains public policy seam |
| `WorkerSchedulingCandidateEnumerator` | package-private | strategy-package implementation detail; future WG-3 should replace its source path with `WorkerCandidateIndex` |
| `WorkerSelector` / `DefaultWorkerSelector` | removed | unused parallel worker-level selection path; keeping it would confuse candidate-source ownership |

### Phase WG-1: Introduce WorkerGroup Model And Snapshot Index

Goal: add thin WorkerGroup and indexes without wiring matching.

Entry gate:

- WG-0 package concentration and visibility tightening are complete enough that
  WorkerGroup/index code can be added behind a small package-level surface.

Scope:

- add `WorkerGroupRecord`
- add `EventBinding`
- define `EventKey(project,eventCode)` as the index truth
- keep `adapterNodeId` as an opaque declared field; do not add AdapterNode
  lifecycle
- add `WorkerRegistrySnapshot`
- add `groupIdsByEventKey`, `workerIdsByGroupId`, `groupIdByWorkerId`,
  and `groupIdsByAdapterNodeId`
- allow worker registration to bind `groupId`
- support adapter node to multiple group relation
- either remove worker-level event capability input or convert it into generated
  group bindings at registration time
- keep `supportedProjects` and `supportedEventCodes`, if still needed, as
  compatibility/read projections only

Out of scope:

- no matching path change
- no `WorkerLoadView` change
- no result/runtime change
- no external adapter registration
- no first-class AdapterNode lifecycle
- no worker command/state report implementation

Acceptance:

- group can register event bindings
- worker can bind group
- EventBinding/EventKey is the only new index truth
- matching/index code does not read worker-level event capability as a second
  truth
- compatibility projections do not become a second capability truth
- snapshot index unit tests pass
- snapshot update/delete/re-register does not leak old indexes
- read path remains snapshot-based

### Phase WG-2: Introduce WorkerCandidateIndex Without Rewiring Matching

Goal: prove indexed candidate lookup independently.

Scope:

- add `WorkerCandidateIndex`
- implement normal `EventKey(project,eventCode)` lookup
- implement `targetWorkerId` direct lookup
- materialize Stage-1 `WorkerSchedulingCandidate` from declared worker/group
  facts
- leave reachability, load/capacity, reservation, and resource policy to Stage 2
- test capability mismatch and adapter-node multi-group behavior

Out of scope:

- do not replace `RuleBasedTaskWorkerMatchingStrategy` input yet
- do not change scheduling matrix behavior
- do not interpret `WorkerLoadView`
- do not call resource policy
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
- target worker path still checks declared capability before Stage 2 admission
- existing scheduling matrix remains green

### Phase WG-4: Capability Truth Cleanup

Goal: remove or strictly derive any remaining worker-level event capability
truth after matching reads the group index.

Scope:

- remove direct matching dependency on worker-level `supportedProjects` and
  `supportedEventCodes`
- generate compatibility/read projections from group event bindings where still
  needed
- update fixtures, README files, and scheduling docs to declare
  `WorkerGroup.eventBindings` as candidate-source truth
- add source guards against reintroducing worker-level event capability as a
  matching truth

Out of scope:

- no worker-level event-binding override
- no routing-tag index
- no public worker-management control plane
- no unified event-envelope runtime

Acceptance:

- matching and candidate-index code read event capability only from group
  bindings
- worker-level supported project/event fields are absent from the scheduling
  decision path, or are demonstrably derived/read-only projections
- tests cover projection consistency if projections remain
- source guards prevent dual capability truth from returning

### Phase WG-5: Trace And E2E Proof

Goal: prove group-indexed scheduling through the canonical trace and a small
server wiring test without expanding server into the scheduling matrix owner.

Scope:

- add or update trace scenarios for group capability routing
- add or update a target-worker capability-gate proof
- add a representative server trace-observed test for group-indexed routing
- keep EngineSchedulingCoreSuite as the scheduling matrix owner

Out of scope:

- no operator console
- no scan-heavy runtime observability
- no projection-first proof

Acceptance:

- canonical trace proves event-code routing through worker group capability
- target worker with mismatched group capability is rejected or skipped with a
  clear trace reason
- mismatched group workers never appear as accepted matches for that task
- server proof uses real wiring but does not duplicate the engine scheduling
  matrix

## Future Extensions

Future extensions require separate approval. They must not be bundled into
WG-0 through WG-5.

### Future WG-F1: First-Class AdapterNode Lifecycle

Goal: make adapter node identity and lifecycle first-class only after the group
capability index is stable.

Possible scope:

- finalize `AdapterNodeRecord`
- support adapter node registration if not already present
- bind group registration to adapter node
- bind worker registration to adapter node and group
- test multiple groups on one adapter node with different event bindings

Out of scope:

- adapter node does not own scheduling policy
- adapter node does not own event capability
- no transport adapter service split

Acceptance direction:

- adapter node can host multiple groups
- different groups under one node can register different event bindings
- task eventCode matches the correct group
- adapter-node offline affects reachability, not group capability truth

### Future WG-F2: Worker Capability Self-Report

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

Possible scope:

- add SDK/reporting method only when an actual caller exists
- accept capability report into `WorkerGroupRecord` and indexes
- record trace when capability report is accepted
- ensure full binding replacement updates indexes

Out of scope:

- no `WorkerCommand`
- no `WorkerStateReport`
- no unified event envelope runtime
- no command ack lifecycle

Acceptance direction:

- worker/adapter can report group capability
- report updates index
- old binding deletion stops matching
- later task scheduling uses the updated group binding

### Future WG-F3: Routing-Tag Index

Goal: add routing-tag indexes only after measured need exists.

Possible scope:

- add `(project,eventCode,routingTag) -> groupIds`
- keep routing-tag correctness in Stage 2 until the index is added
- prove the new index reduces candidate volume without changing final match

Out of scope:

- no generic multi-dimensional query engine
- no SQL-like in-memory filtering system

### Future WG-F4: Thin Adapter Registration

Goal: allow external adapter registration without making adapter own scheduling
policy, only when real adapter callers need it.

Possible scope:

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

Acceptance direction:

- adapter can register node
- adapter can host multiple groups
- adapter does not directly write scheduling policy
- source guard prevents transport from owning worker capability decisions

## Trace And Test Plan

Trace fields to add when WG-3 changes matching behavior:

```text
workerGroupId
eventBindingKey = project:eventCode
workerCandidateSource = GROUP_INDEX | TARGET_WORKER
candidateLookupResult
```

`adapterNodeId` can be added when AdapterNode becomes a first-class lifecycle
model. In the core line it may remain an opaque field for diagnostics only.

Core scenario tests:

```text
group-capability-routing
  task eventCode -> group -> worker

opaque-adapter-node-multiple-groups
  one adapterNodeId, multiple groups, different eventBindings

target-worker-direct-lookup
  targetWorkerId does not scan all workers

target-worker-group-capability-gate
  targetWorkerId exists but group does not support eventCode
```

`target-worker-direct-lookup` is better proven by a focused unit/spy test than
by trace alone. Trace can prove who was evaluated or accepted, but absence from
trace is not sufficient proof that a worker was never scanned.

Future scenario tests:

```text
capability-report-updates-index
  report capability -> index update -> task match

adapter-node-lifecycle-routing
  first-class AdapterNode lifecycle affects reachability evidence but not group
  capability truth
```

Representative engine verification after matching rewiring:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=EngineSchedulingCoreArchitectureGuardTest,EngineSchedulingCoreSuite,RuleBasedTaskWorkerMatchingStrategyTest,TaskWorkerAssignListenerTest,TaskResourceReleaseListenerTest" test
```

## Architecture Guards

Add targeted guards only when a path is retired. Avoid broad regex guards for
future ideas.

Useful guards:

- new worker scheduling/candidate internals should stay inside the concentrated
  engine package unless they are a real owner boundary
- package-private visibility should be preferred for same-package implementation
  details
- scheduling candidate hot path must not call a full worker list when task has
  `eventCode`
- worker model must not declare `eventBindings` directly
- matching code must not read worker-level supported project/event fields as a
  second capability truth after WG-4
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

- target lookup checks only direct lookup, group membership, and declared
  capability/gate facts
- Stage 2 still checks reachability, dispatch admission, load/capacity,
  reservation, and resource policy
- target lookup is direct source optimization only

### Risk 5: Policy Leaks Into Candidate Index

Mitigation:

- `WorkerCandidateIndex` narrows candidate source only
- Stage 1 may over-include candidates
- Stage 2 owns rule evaluation, ranking, runtime admission, and resource policy
- fixed/simple first-version policy is acceptable, but owner boundaries must
  remain replaceable

### Risk 6: Over-Indexing

Mitigation:

- first version only indexes `(project,eventCode) -> groupIds` and
  `groupId -> workerIds`
- routing-tag indexes wait for measured bottlenecks

### Risk 7: Plugin Metadata Becomes The Group Identity

Mitigation:

- WorkerGroup is a capability declaration unit
- plugin metadata is optional descriptive data
- do not introduce global plugin/group templates until a concrete need appears

## Recommended First Slice

Start with WG-0 only:

- replace old split documentation with WorkerGroup capability index direction
- concentrate worker scheduling/candidate/capability internals into coherent
  engine packages
- reduce accidental `public` visibility on same-package implementation details
- inventory current full-worker-scan paths and event/capability fields
- decide strict mode versus migration mode for event capability truth
- do not add WorkerGroup models yet
- do not change matching behavior

Second slice:

- add the smallest WG-1 model/index subset
- add `WorkerGroupRecord` / `EventBinding`
- add immutable `WorkerRegistrySnapshot`
- build basic indexes:
  - `groupId -> workerIds`
  - `workerId -> groupId`
  - `adapterNodeId -> groupIds`
  - `(project,eventCode) -> groupIds`
- add unit tests for index update/delete/re-register
- do not change matching mainline yet

Third slice:

- add `WorkerCandidateIndex`
- prove event-key and target-worker lookup independently

Fourth slice:

- wire matching to the indexed source
- run scheduling matrix and trace verification

Fifth slice:

- remove or strictly derive worker-level capability projections
- add source guards against dual capability truth

Sixth slice:

- add trace-observed group capability proof
- add representative server wiring proof

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
