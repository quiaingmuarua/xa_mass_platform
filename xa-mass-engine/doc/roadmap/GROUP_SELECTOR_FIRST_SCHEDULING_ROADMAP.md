# Group-Selector-First Scheduling Roadmap

Last updated: 2026-05-22

Status: implemented mainline convergence record, not a substitute for current
code verification.

Implementation status: GFS-0 through GFS-6 are complete in the current
mainline. Remaining references to old event-first terms are historical gap
markers, archive material, report-ceiling/catalog tests, or runtime/trace
evidence, not scheduling candidate-source truth.

This document defines the next scheduling-kernel convergence line:

```text
Task must not find workers through eventCode.
Task must select WorkerGroup explicitly, then narrow workers by approved
route, placement, attributes, reachability, dispatch gate, load, lock, and
policy.
```

The goal is to remove double truth between `eventCode` and `WorkerGroup` in
the scheduling hot path and reduce candidate-match cost for large worker
fleets.

## Summary

Before this convergence, scheduling already had a WorkerGroup-backed candidate
index, but the mainline candidate source still started from task event
identity:

```text
Task(project,eventCode)
  -> WorkerCandidateIndex
  -> groupIdsByEventKey(project,eventCode)
  -> groupId route bucket
  -> workerIds
```

That is better than worker-level event scans, but it still makes event identity
part of scheduling truth. At scale, this creates two competing meanings:

- `eventCode` as business/runtime handler semantics
- `WorkerGroup` as the platform scheduling capability cohort

The target shape is:

```text
Task(workerGroupSelector, routeAttributes, placement, workerAttributes)
  -> groupId buckets
  -> bounded worker candidates
  -> Stage-2 eligibility / ranking / admission
```

`eventCode` remains valid business metadata and runtime work evidence. It must
not be the primary worker candidate-source key.

## Core Rules

1. `WorkerGroup` is the scheduling candidate-source truth.
2. `eventCode` is business/intake/runtime payload semantic, not scheduling
   truth.
3. `AdapterNode` is placement / endpoint / transport evidence, not capability
   truth.
4. Worker attributes are route/filter/rank inputs only when explicitly approved.
5. No ordinary scheduling path may fall back to all-worker scans.
6. A task without `workerGroupId` / `workerGroupIds` is not schedulable by the
   kernel, including `targetWorkerId` debug/manual dispatch.
7. Catalog/intake may derive a worker group from event metadata, but the
   scheduling kernel must receive the resolved group selector.
8. `targetWorkerId` remains a debug/manual narrowing shortcut inside an
   explicit group selector. It must not bypass group, reachability, dispatch
   gate, load, lock, or rule checks.

## Non-Goals

This roadmap does not do:

1. No `WorkerSession` engine model.
2. No `Device` / `AccountSlot` owner.
3. No AdapterNode-first scheduling source.
4. No arbitrary worker attribute indexing.
5. No database CRUD owner for runtime worker indexes.
6. No complete routing-policy marketplace.
7. No long-lived compatibility path where both eventCode and group selector are
   scheduling truth.
8. No rewrite of task result convergence, lease expiry, or transport delivery
   semantics.

## Converged Implementation Gaps

These were the event-first seams targeted by this roadmap. They are retained
here as convergence history and guard targets; verify current behavior from code
and tests before treating any item as active.

- `WorkerCandidateIndex.workersFor(Task)` no longer derives candidate groups
  from `TaskSharedConfig.sdkEventCode(task)` or `task.getProject()`.
- `WorkerRegistrySnapshot.groupIdsByEventKey` remains a read cache for
  catalog/report-ceiling flows, not a scheduling hot-path source.
- `WorkerCapabilityAuthority` still composes WorkerGroup-approved event binding
  truth for report ceilings and catalog resolution, not candidate admission.
- `WorkerManager.findWorkerCandidates(task)` no longer branches on event-code
  or project-only narrowing.
- `WorkerManager.findWorkerCandidates(task)` no longer falls back to
  `workerStorage.getAllWorkers()` for ordinary scheduling.
- `RuleBasedTaskWorkerMatchingStrategy.prefilterCandidate(...)` no longer
  rejects candidates because task event support is missing.
- `SimpleTaskDispatchBinder.workerCandidateSource(...)` no longer emits
  `GROUP_INDEX` / `GROUP_PROJECT_INDEX` based on task event/project fields.
- `SCHEDULING_CORRECTNESS_MATRIX.md` now describes explicit WorkerGroup
  selector truth rather than event-derived candidate narrowing.

## Target Scheduling Contract

### Task Selector

Task scheduling input should be explicit:

```text
workerGroupId        optional single group selector
workerGroupIds       optional ordered group selector list
adapterNodeId        optional hard placement filter
routeAttributes      approved route bucket selector
workerAttributes     approved worker attribute filter
targetWorkerId       manual/debug fixed-worker selector
eventCode            business/runtime semantic only
```

`workerGroupId` and `workerGroupIds` are mutually compatible views of the same
selector. Internally the kernel should normalize them into an ordered, de-duped
`WorkerGroupSelector`.

### Selector Semantics

```text
targetWorkerId
  -> direct worker lookup
  -> require workerGroupId(s)
  -> verify worker belongs to one selected group
  -> verify adapterNodeId placement when provided
  -> Stage-2 eligibility

workerGroupId(s)
  -> groupId buckets
  -> optional adapterNode placement filter
  -> route bucket acquisition
  -> approved worker attribute filter
  -> Stage-2 eligibility

missing selector
  -> no candidates
```

No eventCode fallback and no project-only fallback should remain in the
scheduling kernel after this roadmap. `targetWorkerId` is not an exception to
the group selector requirement; it only prevents scanning inside an already
selected cohort.

### EventCode To Group Selector Resolution

This roadmap must choose the resolver direction before GFS-2 implementation.
The selected direction is:

```text
catalog/intake wrapper resolves eventCode -> workerGroupId(s)
kernel receives workerGroupId(s)
kernel does not resolve eventCode
```

The resolver owner is outside `WorkerCandidateIndex`. The first implementation
belongs in SDK/event-dispatch or task-intake assembly where task
shell/sharedConfig is produced. WorkerGroup event bindings are a valid catalog
source for the resolver, but the resolved selector must be materialized into
task shared config before the task enters assignment.

Rejected direction:

```text
every task caller must manually set workerGroupId
```

That would create broad caller churn and would make SDK event dispatch fragile.
SDK/catalog surfaces should preserve event-based ergonomics by resolving the
group selector before scheduling, while direct low-level task APIs may require
explicit `workerGroupId(s)` for worker-backed tasks.

### EventCode Placement

`eventCode` remains useful for:

- SDK event dispatch and handler selection
- task item payload/runtime evidence
- result and trace diagnosis
- catalog/intake validation
- WorkerGroup declaration readability
- capability-report ceiling checks during migration

`eventCode` must not be used for:

- selecting group ids in `WorkerCandidateIndex`
- admitting worker candidates
- deriving fallback project support
- producing the main `workerCandidateSource`

## Target Index Shape

The memory runtime and Redis runtime should share the same conceptual shape.
In-memory implementations may use immutable maps and sets, but the structure
should remain hash/index oriented.

Primary records:

```text
worker:{workerId} -> Worker
group:{groupId} -> WorkerGroupRecord
adapterNode:{adapterNodeId} -> AdapterNodeRecord
nodeGroupBinding:{adapterNodeId}:{groupId} -> NodeGroupBindingRecord
```

Primary candidate indexes:

```text
group:{groupId}:workers -> ordered worker ids
group:{groupId}:route:{routeBucketKey}:workers -> bounded worker ids
group:{groupId}:node:{adapterNodeId}:workers -> worker ids
group:{groupId}:node:{adapterNodeId}:route:{routeBucketKey}:workers -> bounded worker ids
```

Approved secondary filters:

```text
group:{groupId}:attr:{key}:{value}:workers -> worker ids
group:{groupId}:node:{adapterNodeId}:attr:{key}:{value}:workers -> worker ids
worker:{workerId}:indexMemberships -> reverse cleanup evidence
```

Do not index all `Worker.attributes`. Only policy-approved keys may become
secondary indexes. The first slice should continue with the existing approved
route attributes:

```text
business
tenant
region
pool
```

## Runtime Behavior

### Candidate Source

The desired hot path is:

```text
WorkerCandidateIndex.workersFor(task)
  -> WorkerGroupSelector.from(task.sharedConfig)
  -> for each selected group
       -> optional adapterNodeId filter
       -> WorkerRouteBucketOwner.acquireForTask(groupId, adapterNodeId, task, limit)
       -> optional approved attribute filter
  -> bounded worker rows
```

`WorkerRouteBucketOwner` remains a bounded candidate-source helper. It must not
evaluate reachability, dispatch gates, load, lock, or task result state.

### Stage-2 Eligibility

Stage-2 stays unchanged in principle:

```text
WorkerSchedulingCandidateEnumerator
  -> WorkerSchedulingView
  -> dispatchEnabled
  -> WorkerReachabilityView
  -> worker lock
  -> target worker / attributes
  -> route/rule checks
  -> rank
  -> capacity reservation
  -> optional exclusive lock
```

The event-support prefilter must be removed or converted into a selector
consistency guard. Once the kernel receives explicit group selectors,
`schedulingView.supportsEvent(eventCode)` is not a candidate-source truth.

### Dispatch Evidence

Trace should prove the new spine:

```text
taskWorkerGroupSelector
workerGroupId
adapterNodeId
routeBucketKey
workerCandidateSource = GROUP_SELECTOR | GROUP_SELECTOR_WITH_NODE | TARGET_WORKER
eventCode
```

`eventBindingKey` may remain as business evidence while event bindings exist,
but it must not imply candidate-source ownership.

## Phase Plan

### GFS-0: Inventory And Guard Preparation

Goal: identify current event-first candidate-source reads without behavior
change.

Scope:

1. Inventory all uses of `TaskSharedConfig.sdkEventCode(task)` in scheduling
   source, prefilter, trace, and tests.
2. Inventory all uses of `groupIdsByEventKey` and `workerSupportsEventKey`.
3. Inventory project-only candidate fallback.
4. Inventory `workerStorage.getAllWorkers()` fallback from ordinary
   scheduling.
5. Inventory `workerCandidateSource` values in trace analyzers and tests.
6. Add a real `EngineSchedulingCoreArchitectureGuardTest` source guard that
   fails if the final GFS hot path reintroduces `sdkEventCode` into
   `WorkerCandidateIndex`. If this guard is staged before the code change, mark
   it against the new group-selector method rather than as a TODO comment.

Acceptance:

1. The event-first candidate-source blast radius is documented in this file or
   a follow-up implementation note.
2. No behavior change.
3. Guard coverage exists as executable source-scan proof, not only a prose TODO.
4. No new compatibility abstractions.

### GFS-1: Task Group Selector Contract

Goal: make task-level group selector explicit.

Scope:

1. Add `TaskSharedConfig.WORKER_GROUP_ID`.
2. Add `TaskSharedConfig.WORKER_GROUP_IDS`.
3. Add helper methods that normalize to an ordered group selector list.
4. Keep `targetWorkerId` readable, but require explicit `workerGroupId(s)` for
   it to produce scheduling candidates.
5. SDK/server task creation may pass group selector through shared config.
6. Implement the minimum SDK/catalog resolver that maps event dispatch metadata
   to `workerGroupId(s)` before task assignment. This must be usable before
   event-code candidate fallback is removed.
7. Do not yet remove event-code candidate lookup.

Acceptance:

1. A task can carry one worker group selector.
2. A task can carry multiple worker group selectors in deterministic order.
3. Empty or blank group selectors normalize to empty.
4. `targetWorkerId` remains readable independently, but is not schedulable
   without an explicit group selector.
5. SDK/event-dispatch assembly produces task shared config containing resolved
   `workerGroupId(s)` for event-backed worker tasks, even though old candidate
   lookup still works during this slice.
6. No existing task result/runtime payload behavior changes.

### GFS-2: Candidate Index Group-Selector Path

Goal: implement explicit group-selector candidate acquisition before removing
event fallback.

Scope:

1. Add `WorkerCandidateIndex.workersForGroups(task, groupIds, limit)`.
2. Route selected group ids through `WorkerRouteBucketOwner`.
3. Add `WorkerRouteBucketOwner.acquireForTask(groupId, adapterNodeId, task,
   limit)` or an equivalent node-scoped acquisition path. Placement filtering
   must live at the bucket/candidate-source boundary, not after unbounded
   group enumeration.
4. Support optional `adapterNodeId` placement filter.
5. Update `WorkerCandidateIndex.workerForWorkerId(...)` so target-worker lookup
   requires `workerGroupId(s)` and verifies that the target worker belongs to
   one selected group.
6. Preserve route bucket narrowing from `Task.sharedConfig.routeAttributes`.
7. Keep event-code path temporarily only to support old tests during the
   slice, but do not extend it.

Acceptance:

1. `workerGroupId=group-a` returns only workers in `group-a`.
2. `workerGroupIds=[group-a,group-b]` returns bounded candidates in selector
   order.
3. `workerGroupId + adapterNodeId` returns only workers under that
   `(adapterNodeId, groupId)` relation.
4. node placement filtering is bounded before worker row materialization; it
   must not acquire the full group then filter in memory.
5. route attributes narrow within selected groups.
6. target-worker lookup rejects a target that is outside the selected group.
7. target-worker lookup without group selector returns no candidates.
8. no all-worker fallback is used for group-selector tasks.

### GFS-3: Make Group Selector The Mainline

Goal: switch ordinary scheduling to explicit group selectors.

Scope:

1. Update `WorkerManager.findWorkerCandidates(task)` to prefer group selector.
2. If no selector, return no candidates, including when `targetWorkerId` is
   present.
3. Remove project-only candidate fallback from the kernel.
4. Remove eventCode candidate fallback from the kernel.
5. Remove the `workerStorage.getAllWorkers()` fallback from ordinary
   scheduling.
6. Update assignment trace source naming.

Acceptance:

1. Task with only `eventCode` and no group selector does not match a worker.
2. Task with group selector matches even when eventCode is absent or unrelated.
3. Task with project only does not scan or match workers.
4. `workerCandidateSource` no longer emits `GROUP_INDEX` or
   `GROUP_PROJECT_INDEX` for new mainline tests.
5. Ordinary scheduling has no all-worker fallback branch.
6. Target-worker debug dispatch requires group selector and rejects mismatched
   group.
7. engine scheduling tests prove group selector as the candidate source.

### GFS-4: EventCode Demotion And Double-Truth Removal

Goal: remove eventCode from scheduling truth.

Scope:

1. Remove `WorkerCandidateIndex` dependency on `EventKey`.
2. Remove `WorkerRegistrySnapshot.groupIdsByEventKey` from hot-path ownership.
3. Remove `workerSupportsEventKey` from candidate admission.
4. Convert event binding tests to catalog/report-ceiling tests, not scheduling
   candidate tests.
5. Remove `GROUP_INDEX` and `GROUP_PROJECT_INDEX` branches from
   `SimpleTaskDispatchBinder.workerCandidateSource(...)`.
6. Update scheduling correctness docs.

Acceptance:

1. `WorkerCandidateIndex` no longer reads `TaskSharedConfig.sdkEventCode`.
2. `WorkerManager.findWorkerCandidates` no longer branches on eventCode.
3. `RuleBasedTaskWorkerMatchingStrategy` does not reject candidates because
   task eventCode is unsupported.
4. Capability report still cannot expand beyond WorkerGroup-approved event
   bindings while event bindings remain in the model.
5. `SimpleTaskDispatchBinder` cannot emit `GROUP_INDEX` or
   `GROUP_PROJECT_INDEX`.
6. Architecture guard fails if eventCode re-enters candidate-source lookup.

### GFS-5: External Contract Alignment

Goal: ensure external worker and task APIs produce the new selector shape.

Scope:

1. Update SDK task creation examples to include `workerGroupId` when scheduling
   a worker-backed task.
2. Update external worker quickstart and black-box samples.
3. Align all public examples and black-box tests with the resolver implemented
   in GFS-1.
4. Keep eventCode in payload/result/trace for diagnosis.

Acceptance:

1. External worker public-contract proof shows:

```text
task -> workerGroupId -> worker -> adapterNodeId -> transport -> result
```

2. No public example relies on eventCode-only worker matching.
3. Realtime and polling workers use the same selector contract.
4. Trace analyzer checks `workerGroupId` and `workerCandidateSource` rather
   than event-derived group evidence.
5. SDK event dispatch remains ergonomic through the already-implemented
   resolver: callers may specify eventCode, but the produced task carries
   resolved `workerGroupId(s)` before scheduling.

### GFS-6: Index Runtime Hardening

Status: implemented for the in-memory mainline.

Goal: prepare the structure for large group sizes and Redis runtime parity.

Scope:

1. Keep candidate acquisition bounded by group and route bucket.
2. Add explicit approved attribute indexes only if a real filter needs them.
3. Define reverse membership cleanup expectations for worker update/delete.
4. Make memory and Redis runtime concepts align:

```text
hash rows
set/zset candidate indexes
bounded candidate acquisition
reverse cleanup evidence
```

Acceptance:

1. Ordinary task scheduling does not enumerate all workers. Guard coverage
   rejects `WorkerManager.findWorkerCandidates(...)` all-worker fallback.
2. Large group tests prove bounded acquisition through
   `WorkerRouteBucketOwner`.
3. Attribute filters use approved keys only through `WorkerRoutingPolicy`.
4. Worker update/delete tests prove old route bucket membership is removed by
   snapshot republish.
5. Node-scoped route bucket tests prove adapter-node placement does not acquire
   the full group before filtering.
6. Redis runtime plan can map each memory index to concrete key structures.

## Testing Plan

Primary lane:

- `EngineSchedulingCoreSuite`
- `EngineSchedulingCoreArchitectureGuardTest`
- `WorkerCandidateIndexTest`
- `WorkerRouteBucketOwnerTest`
- `WorkerManagerTest`
- `RuleBasedTaskWorkerMatchingStrategyTest`
- `SimpleTaskDispatchBinderTest`

Server/transport proof:

- external worker public contract trace-observed test
- polling worker black-box group-selector dispatch
- websocket/socket black-box group-selector dispatch when the public contract
  changes

Required scenario tests:

1. `group-selector-single-group-dispatch`
2. `group-selector-multi-group-bounded-dispatch`
3. `group-selector-node-placement-filter`
4. `group-selector-route-attribute-bucket`
5. `event-code-only-task-does-not-match`
6. `target-worker-still-requires-valid-group-when-selector-present`
7. `worker-capability-report-cannot-expand-group-ceiling`
8. `no-all-workers-fallback-for-business-task`

Testing rule:

- Do not add duplicate happy-path tests just to cover each helper.
- Prefer tests that prove candidate-source truth, no fallback, and trace
  evidence.
- Delete or rewrite event-first tests instead of keeping them beside
  group-selector tests.

## Architecture Guards

Add or update targeted guards:

1. `WorkerCandidateIndex` must not call `TaskSharedConfig.sdkEventCode`.
2. `WorkerCandidateIndex` must not call `groupIdsByEventKey`.
3. `WorkerManager.findWorkerCandidates` must not branch on task eventCode.
4. Ordinary candidate source must not call `workerStorage.getAllWorkers()`.
5. `AdapterNode` and `NodeGroupBinding` must not own event bindings.
6. `WorkerRouteBucketOwner` must not read reachability, load, lock, or task
   result state.
7. `eventCode` may appear in trace/runtime payload code, but not in
   candidate-source lookup.
8. `workerCandidateSource` accepted values should be constrained to the new
   mainline vocabulary after GFS-3.

## Risks And Mitigations

### Risk 1: AdapterNode Becomes Capability Truth

Mitigation:

AdapterNode can narrow placement only after group selector resolution. It
cannot be the primary candidate source.

### Risk 2: Group Selector Becomes A Hidden EventCode Alias

Mitigation:

Catalog/intake may resolve eventCode to group selector before scheduling, but
the kernel receives and proves `workerGroupId`. Do not let the kernel perform
eventCode-to-group resolution.

### Risk 3: Missing Selector Breaks Existing Flows

Mitigation:

Do not preserve an event fallback. Instead, update in-repo task creation,
examples, tests, and SDK wrappers in the same convergence line. This project
has no compatibility obligation for superseded internal paths.

### Risk 4: Attribute Filters Recreate Scan Pressure

Mitigation:

Only approved attributes become indexes. Everything else remains diagnostic
metadata or Stage-2 rule input after bounded group acquisition.

### Risk 5: Tests Keep Both Narratives Alive

Mitigation:

Retire event-first scheduling tests when group-selector proof replaces them.
Keep event binding tests only where they prove catalog/report-ceiling behavior.

## Implementation Sequence Used

This convergence used the following order to avoid keeping old and new
scheduling truth alive as parallel tracks:

1. GFS-1: add `TaskSharedConfig` group selector helpers.
2. GFS-2: add group-selector candidate acquisition path and tests.
3. Update one engine scheduling scenario and one server trace-observed scenario
   to set `workerGroupId`.

Then it converged the hot path:

1. GFS-3: switch `WorkerManager.findWorkerCandidates` to group selector only.
2. Rewrite tests and traces.
3. GFS-4: delete event-first hot-path residue and guards.

## Final Target

```text
Task(sharedConfig.workerGroupIds, routeAttributes, placement, attributes)
  -> WorkerCandidateIndex group selector
  -> WorkerRouteBucketOwner bounded acquisition
  -> optional adapter-node placement filter
  -> optional approved attribute filter
  -> WorkerSchedulingView
  -> reachability / dispatch gate / load / lock / rules
  -> runtime claim
  -> transport route
  -> result convergence
```

One sentence:

Use `WorkerGroup` as the only scheduling candidate-source truth; keep
`eventCode` as business/runtime metadata; use AdapterNode and attributes only
to narrow an already selected group.
