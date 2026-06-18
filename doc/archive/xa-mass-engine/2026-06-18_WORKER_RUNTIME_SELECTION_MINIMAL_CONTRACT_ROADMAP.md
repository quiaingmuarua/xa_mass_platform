# Worker Runtime Selection Minimal Contract Roadmap

Status: complete and archived on 2026-06-18.

Archive note: the worker-selection mainline has moved to
`WorkerSelectionRuntime` and selected-worker handles. Current truth is in
`xa-mass-engine/README.md`, `xa-mass-worker-runtime/README.md`, and
`xa-mass-worker-runtime/CONTRACTS.md`. This file is retained as historical
implementation context only; do not execute it as an active roadmap.

Follow-up external inspection/API surface cleanup remains separate.

Related:

- `roadmap/WORKER_RUNTIME_MINIMAL_INTERFACE_CONVERGENCE_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_COMPOSITE_ELIGIBILITY_SET_ROADMAP.md`
- `doc/archive/xa-mass-engine/2026-06-18_RUNTIME_WORKER_SELECTION_RESIDUE_CONVERGENCE_ROADMAP.md`
- `doc/archive/xa-mass-engine/2026-06-18_WORKER_RUNTIME_SELECTION_MINIMAL_CONTRACT_INVENTORY.md`
- `xa-mass-engine/README.md`
- `xa-mass-worker-runtime/README.md`
- `xa-mass-worker-runtime/CONTRACTS.md`
- `doc/PROOF_REGISTRY.md`

Supersession closure: this roadmap corrected the worker-selection owner split
described by older residue docs. The older residue roadmap was archived with
this record, and the current owner rule now lives in the active engine and
worker-runtime docs.

## Purpose

Move runtime worker selection behind minimal worker-runtime capability
contracts without turning worker-runtime into a second scheduling brain.

WMI narrowed worker declaration and default worker-facing DTOs, but the current
engine hot path still receives and interprets worker metadata and live worker
evidence. The target of this roadmap is not another DTO slimming pass and not a
class relocation exercise. The target is a boundary change:

```text
Engine resolves task-side scheduling policy and worker-universe intent
  -> Engine builds a minimal worker selection request
  -> Worker-runtime executes worker-fact mechanisms without exposing facts
  -> Engine binds work to selected worker handles
  -> Transport delivers to the selected worker
```

The preferred implementation shape is a coarse `selectAndReserve` style
worker-runtime call. Worker-runtime may keep acquisition, worker-side
eligibility/ranking, reservation, and accounting as internal stages, but the
default engine-facing contract should not expose candidate rows or worker
metadata. If an intermediate implementation exposes opaque pre-selection
handles, they are a transition mechanism only and must be guarded so engine can
only forward them to the next worker-runtime stage.

Engine should not materialize `WorkerSchedulingView`, read worker attributes,
join WorkerGroup capability, rank by worker load, or evaluate worker-metadata
rules. Worker-runtime may use those facts internally, cache them, index them,
and expose diagnostics to server/SDK through explicit inspection contracts.
The engine-facing selected worker shape is limited to the delivery identity it
actually needs: `workerId`, `workerGroupId`, and opaque
selection/claim/accounting tokens. Any other worker fact is a worker-runtime
implementation detail or a server/SDK diagnostic view, not an engine selection
input.

## Implementation Status

Current mainline outcome:

- `WorkerSelectionRuntime` is the engine-facing worker-runtime selection
  contract.
- `TaskWorkerAssignListener` builds `WorkerSelectionRequest` from resolved
  task-side worker policy intent and consumes `WorkerSelectionResult`.
- Worker-runtime owns candidate acquisition, worker-side predicate/ranking
  mechanics, reservation, exclusive lease acquisition, selected claim
  authorization, and selected-worker accounting.
- Engine allocation, binder, release, and final paths consume
  `SelectedWorkerHandle` or `SelectedWorkerEvidence`; they do not call
  `WorkerAdmissionRuntime` directly.
- `RuleBasedTaskWorkerMatchingStrategy`, `TaskWorkerMatchingStrategy`,
  `WorkerSchedulingCandidateEnumerator`, `WorkerSchedulingCandidate`,
  `WorkerSchedulingView`, `WorkerMatchContext`, and engine-side candidate
  ranker classes have been removed from production.
- Assignment diagnostics record selected worker handles and task-level
  selection outcome counts. They do not reconstruct worker scheduling views or
  candidate rows.

Out of scope for this completed mainline slice:

- SDK/server/frontend worker inspection read models may still expose worker
  metadata or scheduling views through explicit diagnostic surfaces.
- Further slimming of worker declaration/resource view DTOs remains with the
  worker-runtime minimal interface roadmap.

## Pre-Implementation Code Observations

Current facts from source:

- `xa-mass-engine` has a main dependency on `xa-mass-worker-runtime`.
  Worker selection is not currently mediated by `xa-mass-kernel-spi`.
- `EngineRuntimeKernelConfig` exposes `WorkerCandidateRuntime`,
  `WorkerSchedulingViewRuntime`, `WorkerAdmissionRuntime`,
  `WorkerWarmHintRuntime`, and `WorkerDispatchGateRuntime` to engine assembly.
- `RuleBasedTaskWorkerMatchingStrategy` calls
  `WorkerCandidateRuntime#findWorkerCandidateBatch(...)`, enumerates
  scheduling candidates, creates `WorkerMatchContext`, ranks candidates, and
  calls `WorkerAdmissionRuntime#reserveWorkerCapacity(...)`.
- `WorkerSchedulingCandidateEnumerator` joins `WorkerCandidateRow` with
  reachability, exclusive lease, WorkerGroup capability, and load from
  `WorkerSchedulingViewRuntime`.
- `WorkerSchedulingView` carries `workerId`, `workerGroupId`,
  `transportHint`, `agentVersion`, supported projects/events, worker
  attributes, reachability, dispatch enabled, lock state, and load.
- `SimpleTaskDispatchBinder` currently reads
  `WorkerSchedulingView.supportedEventCodes()` and passes that set into
  `WorkerClaimTarget`. The task-runtime claim contract therefore still depends
  on per-worker capability evidence reaching the binder.
- `WorkerClaimTarget` is in `mass-runtime-api` and carries `workerId`,
  `workerGroupId`, `batchId`, capacity, and `supportedEventCodes`. It is not
  owned by worker-runtime, but it is the current work-claim bridge that must be
  narrowed or fed by a worker-runtime selected-handle claim constraint.
- `WorkerClaimTarget#supportsEvent(...)` is not passive metadata. Memory
  task-runtime uses it while claiming ready work, and Redis task-runtime encodes
  `supportedEventCodes` into the atomic claim script. The selected-handle claim
  bridge therefore needs an explicit task-runtime contract, not just a binder
  DTO rename.
- `WorkerMatchContext` copies worker metadata and scheduling evidence into
  engine context and rule context. The rule context excludes some live evidence,
  but still exposes worker attributes and capability lists.
- `DefaultWorkerCandidateRanker` ranks in engine using load ratio, routing
  affinity, worker attributes, and scheduling-resource availability.
- `xa-mass-worker-runtime` main sources do not depend on `xa-mass-engine` or
  `xa-mass-kernel-spi`; selection intent contracts that worker-runtime must
  consume cannot live in engine packages.
- `SimpleTaskDispatchBinder` and `WorkerDispatchResourceReleaser` consume
  `WorkerSchedulingCandidate` and `WorkerSchedulingView` to confirm admission,
  release reservations/locks, and build dispatch evidence.
- `TaskWorkerAssignListener`, `SimpleTaskDispatchBinder`,
  `TaskResourceReleaseListener`, and `WorkerDispatchResourceReleaser` use
  `WorkerAdmissionRuntime` for active-worker counts, confirm, release,
  claim/final accounting, exclusive locks, and load reads. The migration is not
  complete if it only removes reserve-time calls.
- `AssignmentRecordService` and `TraceEventLogger` consume
  `WorkerSchedulingCandidate` / `WorkerSchedulingView` for diagnostics.
- `EngineSchedulingCoreArchitectureGuardTest` still contains guard language
  that protects the current `WorkerSchedulingView`-based model. Those guards
  must be migrated in the same slice that retargets the production mainline,
  not deferred until final residue cleanup.
- `xa-mass-worker-runtime/CONTRACTS.md` currently says worker-runtime does not
  own rule evaluation or worker ranking. This will become stale once
  worker-runtime owns worker-fact predicate/ranking mechanics behind minimal
  selection contracts, so it must be updated with the README in the same owner
  doc slice.
- `sdk/xa-mass-embedded-sdk` currently assembles one `WorkerManager` instance
  behind several narrow ports, but engine still consumes those ports
  separately.
- `sdk/xa-mass-embedded-sdk` also exposes the old custom matching seam through
  `EngineConfig#setMatchingStrategy(...)` and
  `MassEngineBuilder#matchingStrategy(...)`; that seam currently returns
  `WorkerSchedulingCandidate` and must not survive as an alternate metadata
  path.

## Owner Review

Engine owns task lifecycle, task-side scheduling policy resolution, allocation
budget, worker-universe intent resolution, work claim, dispatch binding, result
convergence, retry, and terminal policy.

WorkerGroup owns project/event capability truth. Worker-runtime may maintain
the runtime projection and indexes of WorkerGroup capability, WorkerGroup
membership, worker declaration, live worker evidence, candidate source,
admission, reservations, locks, warm-candidate cache, and final accounting.
Worker-runtime does not own task lifecycle policy, allocation policy, retry
policy, terminal policy, or public scheduling-policy catalog truth.

Runtime worker selection is a Scheduling Plane concern, but worker-runtime owns
the atomic worker-fact mechanisms used by that concern. Engine may decide how
many workers it wants, which worker universe it is asking for, and which
resolved policy intent applies. Worker-runtime executes the worker-side
mechanisms against its own facts. Engine must not inspect worker metadata and
implement those mechanisms itself.

Minimal exposure is the default rule. Before worker-runtime returns a selected
or prewarm handle, engine should not see worker identity except when it
explicitly requested a target worker and already owns that input. `workerId` and
`workerGroupId` become engine-visible only after worker-runtime has selected
the worker for dispatch or prewarm. This roadmap does not introduce a separate
worker-universe partition identity; any future split must be justified by a
concrete policy owner and must not become worker-runtime-owned partitioning.

Worker-runtime may evaluate worker-side predicates and ranking over
worker-runtime facts after engine has resolved task-side policy intent. This is
a boundary change from older wording that said worker-runtime does not rank or
evaluate match rules. WSM-0 must update owner docs to state the narrower rule:
engine owns task-side policy and rule intent; worker-runtime owns worker-fact
predicate/ranking mechanics behind minimal contracts.

Server and SDK inspection surfaces may expose worker metadata through explicit
read models. Those read models are diagnostic/product surfaces, not engine
scheduling inputs.

Transport owns delivery feasibility after a worker is selected. Transport must
not participate in choosing the worker.

## Boundary Decision

Replace engine-visible candidate rows and scheduling views with minimal
worker-runtime atomic selection contracts.

Add an explicit selection-intent translation boundary:

```text
Engine-owned TaskDispatchIntent / ResolvedWorkerSchedulingPolicy
  -> API-owned WorkerSelectionIntent
  -> worker-runtime atomic selection stages
```

`WorkerSelectionIntent` and selected-handle contracts must live in a package
that `xa-mass-worker-runtime` may depend on, such as `mass-runtime-api` or a
worker-runtime owned API package. Worker-runtime must not import
`xa-mass-engine` classes, engine rule classes, or engine scheduling DTOs.

The first implementation should prefer one coarse engine-facing operation over
engine-orchestrated candidate stages:

```text
select/reserve:
  request = WorkerSelectionRequest
  result  = WorkerSelectionResult

accounting/release/final:
  request = SelectedWorkerHandle or persisted selected-worker evidence
  result  = accepted/rejected bounded outcome
```

The default request shape is:

```text
WorkerSelectionRequest
  worker selection intent:
    project
    eventCode
    workerGroupIds
    routingCode or engine/Scheduling Plane-owned route policy token
    targetWorkerId when explicitly requested
  requestedWorkerCount
  optional prewarmCount
  resource mode / reservation mode
  task-side claim constraints that are not worker metadata
  optional opaque selectionCorrelationToken only when a concrete mechanism needs it
```

Target worker-runtime output to engine:

```text
WorkerSelectionResult
  selected:
    workerId
    workerGroupId
    reservationToken or selectionToken
    claimConstraint or claimToken
  prewarm:
    workerId
    workerGroupId
    prewarmToken when needed
  summary:
    requestedCount
    selectedCount
    rejectedCountByReason
    bounded diagnostic reason codes
```

The selected handle is the only worker-selection object the engine may use to
claim work, bind dispatch, release selection state, and record final worker
accounting. It must not carry worker attributes, supported project/event lists,
live load details, reachability details, dispatch-gate details, internal
worker-runtime source keys, or transport topology ids. Prewarm handles are
selected by worker-runtime too; they may carry the same minimal identity fields
as selected handles, but no worker facts.

If implementation pressure requires a temporary multi-stage contract, the only
legal pre-selection object is an opaque handle that exposes no `workerId`, no
`workerGroupId`, and no worker facts. Engine may not sort, filter, persist,
compare, branch on, or log those handles except as opaque tokens passed to the
next worker-runtime stage. That transition must not survive as a second
production selection path once the coarse selected-worker stage is available.

The selected handle may carry a typed `claimConstraint` or opaque `claimToken`
that is produced by worker-runtime and consumed by the engine binder only as a
claim bridge. It must not expose raw `supportedEventCodes` as worker metadata.
If `TaskWorkRuntime#claimReady(...)` still requires `WorkerClaimTarget` during
the first implementation, the binder may translate the selected-handle claim
constraint into the existing `WorkerClaimTarget` shape without inspecting or
deriving worker capability facts.

The claim bridge has to be one of two explicit shapes:

- worker-runtime selection proves event compatibility before reservation, and
  `TaskWorkRuntime#claimReady(...)` no longer receives per-worker event lists;
- a minimal task-runtime `ClaimConstraint` lives in an API package shared by
  task-runtime and worker-runtime, while engine only forwards it and guards
  prevent engine code from reading capability contents.

It is not acceptable for engine to rebuild `supportedEventCodes` from a worker
view or WorkerGroup capability projection.

Worker-runtime must not understand task lifecycle semantics. Requests or
accounting commands may carry opaque ids only when a concrete mechanism needs
them, such as a selection correlation id, reservation token, claim token, or
prewarm token. Worker-runtime treats those values as uninterpreted identities for
idempotency, cache lookup, reservation correlation, or accounting. It must not
parse those ids as `taskId`, `messageId`, retry policy, result policy, or task
lifecycle truth.

If allocation needs the current active selected-worker count for a task, the
count must come from a selection/accounting contract that accepts an opaque
selection-scope key or returns the count as part of the selection summary.
Engine must not keep using the old admission port as a side channel for active
worker counts, load, locks, or reservation evidence.

For this roadmap, `workerGroupId` is the only selection-visible
worker-universe identity. Engine must not pass a separate selection partition,
shard id, source key, cache key, storage key, or worker-runtime prewarm cache
key to worker-runtime.
Worker-runtime must not invent an additional selection-visible shard, bucket,
or partition policy. If worker-runtime implementations derive private
lookup/index keys, those keys are hidden storage mechanics derived from the
engine-provided worker universe and canonical worker facts; they must not
change the worker universe, ranking domain, or assignment correctness.

Worker-runtime may internally use WorkerGroup capability projections, worker
attributes, reachability, load, dispatch gates, locks, and warm hints. It must
return only handles, bounded reason codes, counts, and optional diagnostic ids
to engine.

## Non-Goals

- No new public scheduling-policy catalog.
- No `ProjectSchedulingBinding` implementation.
- No public SDK/server policy configuration surface.
- No transport-owned worker selection.
- No engine-side scan of worker metadata as a temporary optimization.
- No wholesale move of `RuleBasedTaskWorkerMatchingStrategy` into
  worker-runtime.
- No worker-runtime ownership of task lifecycle policy, allocation budget,
  retry, terminal convergence, or public scheduling-policy catalog truth.
- No worker-runtime-owned autonomous sharding, bucket selection, or partition
  policy in the worker-selection path.
- No worker-runtime main dependency on `xa-mass-engine`, engine rule packages,
  or engine scheduling DTOs.
- No compatibility wrapper that leaves old `WorkerSchedulingView` and new
  selection handles as two production paths.
- No rule DSL expansion.
- No server/frontend worker inspection cleanup. Inspection views remain a
  separate surface unless this roadmap touches them directly.
- No worker-control owner migration. `WorkerControlRuntime` and dispatch-gate
  mutation policy may need a later roadmap, but this roadmap only removes
  worker metadata and worker evidence from the selection hot path.

## Do Not Start With

Do not start by deleting `WorkerSchedulingView`, `WorkerSchedulingCandidate`,
or `WorkerMatchContext`.

Do not start by moving `RuleBasedTaskWorkerMatchingStrategy` wholesale into
worker-runtime. That only relocates the fat owner.

Do not start by switching the engine mainline before the selected-handle claim
constraint and architecture-guard migration plan are defined. The binder cannot
lose task-runtime claim safety while the worker metadata view is being removed.

First define and prove the minimal selected-worker contract, then retarget the
engine mainline to that contract, then remove the old worker view/candidate
path. Deleting the models first will either break assignment/binding or
encourage a new wrapper that hides the same data flow.

## WSM-0 - Inventory And Contract Decision

Goal: freeze the current caller map and close the unresolved claim/diagnostic
questions before implementation.

Scope:

- Keep `WORKER_RUNTIME_SELECTION_MINIMAL_CONTRACT_INVENTORY.md` current.
- Inventory all production engine imports of:
  - `WorkerCandidateRuntime`
  - `WorkerSchedulingViewRuntime`
  - `WorkerAdmissionRuntime`
  - `WorkerCandidateRow`
  - `WorkerSchedulingView`
  - `WorkerSchedulingCandidate`
  - `WorkerMatchContext`
  - `DefaultWorkerCandidateRanker`
- Classify each use as:
  - worker-fact mechanism to move behind worker-runtime selection contracts,
  - task-side orchestration that remains in engine,
  - dispatch binding that should use selected handles,
  - diagnostic-only residue,
  - explicit out-of-scope worker-control path.
- Record the first selected-handle claim bridge:
  - worker-runtime selection proves event compatibility before returning a
    selected handle;
  - if the current `TaskWorkRuntime#claimReady(...)` bridge still requires
    claim-time evidence, the selected handle carries a typed opaque
    claim-authorization/constraint value that engine only forwards;
  - engine must not read, rebuild, derive, log, or branch on worker event
    capability contents.
- Explicitly rejected first slice: engine reads `supportedEventCodes`,
  WorkerGroup capability lists, worker attributes, load, reachability, or
  dispatch-gate facts from any worker view to build the claim input.
- Use a worker-runtime-owned selection API package for `WorkerSelectionIntent`,
  `WorkerSelectionRequest`, `SelectedWorkerHandle`, selection summary/reason
  codes, and selected-worker accounting commands. Only claim bridge shapes that
  must be consumed by `mass-runtime-api` should live in `mass-runtime-api`.
  Worker-runtime must not depend on `xa-mass-engine`.
- Decide the final selected-handle identity fields. For this roadmap the shape
  is `workerId` and `workerGroupId` only, plus opaque tokens/claim constraints
  when required. A separate worker-universe partition identity is out of scope
  for the first contract.
- Set the initial engine-facing stage split to:
  - `selectAndReserve(WorkerSelectionRequest)` returning selected handles,
    optional prewarm handles, bounded counts, and reason codes;
  - selected-worker accounting/release/final commands by selected handle or
    persisted selected-worker evidence.
- If a temporary multi-stage contract is used internally or during a
  convergence slice, pre-selection handles must be opaque and engine may only
  forward them. They are not the preferred final engine-facing shape.
- Replace all old admission side-channel reads/writes used by engine:
  - active selected-worker count for allocation,
  - reserve/confirm/release,
  - claim/final accounting,
  - exclusive lock release,
  - load/reservation evidence.
- Represent route/routing affinity as engine-resolved worker-universe intent
  passed to worker-runtime for internal interpretation. It may be a routing code
  or a Scheduling Plane-owned route policy token, but it is not a
  worker-runtime-owned shard/bucket/source key and must not change the resolved
  worker universe.
- Inventory existing architecture guards and owner docs that protect the old
  `WorkerSchedulingView`/`WorkerMatchContext` model. Decide which guard changes
  must land with WSM-2 and which final residue guards can wait until WSM-6.
- Mark the worker-selection ownership section of the residue roadmap as
  historical or residual-only, because this roadmap replaces the older rule
  that engine owns worker-fact ranking and admission calls.
- Record the owner-doc delta for `xa-mass-worker-runtime/README.md`:
  worker-runtime still does not own task-side policy, but it will own
  worker-fact predicate/ranking mechanics behind minimal selection contracts.
- Record the same owner-doc delta for `xa-mass-worker-runtime/CONTRACTS.md`.

Acceptance:

- The inventory names every current production caller and target owner.
- The first implementation slice uses the selected-handle claim bridge above
  and does not expose worker capability lists or `supportedEventCodes` to engine.
- Any temporary `WorkerClaimTarget` bridge is fed only by selected-handle claim
  authorization/constraint values, not by engine-side worker views.
- The first implementation slice uses the worker-runtime selection API package
  for `WorkerSelectionIntent`, `WorkerSelectionRequest`, selected handles,
  summaries, and accounting commands, except for claim bridge values that must
  be shared with `mass-runtime-api`.
- The first implementation slice uses the selected-handle identity shape:
  `workerId`, `workerGroupId`, and opaque tokens only.
- The first implementation slice uses `selectAndReserve(...)` or an equally
  coarse selected-worker stage as the default engine-facing contract. Any
  pre-selection handles are transitional, opaque, and forward-only.
- The first implementation slice has a replacement path for every engine call
  to `WorkerAdmissionRuntime`, not only reserve-time calls.
- Routing affinity is represented only as engine-resolved worker-universe
  intent, not as a worker-runtime-owned selection-visible bucket/source key.
- The first implementation slice explicitly forbids worker-runtime-owned
  autonomous sharding/bucket selection in the worker-selection path.
- Old owner docs/roadmaps no longer present the superseded engine-owns-ranking
  rule as current truth.
- The WSM-2 guard/doc migration list is known before production retargeting
  starts.
- `xa-mass-worker-runtime/README.md` and
  `xa-mass-worker-runtime/CONTRACTS.md` have a planned wording change that does
  not conflict with the new worker-fact predicate/ranking owner split.
- No behavior changes.

Suggested verification:

```powershell
rg -n "WorkerSchedulingView|WorkerSchedulingCandidate|WorkerMatchContext|WorkerCandidateRuntime|WorkerSchedulingViewRuntime|WorkerAdmissionRuntime|DefaultWorkerCandidateRanker" `
  xa-mass-engine/src/main/java `
  --glob '!**/target/**'
```

## WSM-1 - Minimal Selected-Worker Contracts

Goal: introduce worker-runtime selected-worker contracts that replace the
engine candidate/view/admission composition without exposing worker facts to
engine.

Scope:

- Add worker-runtime contracts under a selection-owned package. Names are
  flexible, but the contract family should cover:
  - `WorkerSelectionIntent` or equivalent worker-universe intent,
  - `WorkerSelectionRequest` or equivalent selected-worker request,
  - `WorkerSelectionResult` or equivalent selected-worker outcome,
  - selected worker handle,
  - selected-handle claim authorization/constraint or claim token,
  - selection summary/rejection reason,
  - selected-handle accounting/release/final commands.
- The default engine-facing selection contract is coarse:
  `selectAndReserve(request) -> result`. Worker-runtime may internally use
  acquisition, eligibility, ranking, reservation, locks, warm hints, and
  accounting stages, but those internal stages are not engine facts.
- Pre-selection candidate handles are not required for the target contract. If
  a temporary staged implementation exposes them, they may contain only an
  opaque candidate token, bounded batch/cursor metadata, and diagnostic ids that
  cannot be used for worker selection. They must not expose `workerId`,
  `workerGroupId`, worker attributes, capability lists, load, reachability,
  lock, dispatch-gate, or reservation facts.
- The selected handle may contain only:
  - `workerId`
  - `workerGroupId`
  - reservation/selection token if required
  - typed claim constraint or opaque claim token if required by
    `TaskWorkRuntime#claimReady(...)`
- The selected handle must not carry task lifecycle fields such as `taskId`,
  `messageId`, retry state, or result policy. If a runtime mechanism needs a
  stable correlation value, pass an opaque selection/prewarm/accounting key in
  the request or accounting command and keep it uninterpreted by worker-runtime.
- Requests may carry `WorkerSelectionIntent`, requested counts, reservation
  mode, and optional prewarm count. They must not carry worker metadata,
  worker-runtime source keys, storage keys, or transport topology ids.
- Stage result contracts may return counts, bounded reason codes, and opaque
  diagnostic ids. They must not return raw worker fact snapshots to engine.
- Worker-runtime may use private lookup/index keys internally for performance,
  but those keys must not be part of the cross-module contract and must not
  create a new worker-universe slice beyond the engine-provided intent.
- Implement the selected-worker contract in worker-runtime by reusing current
  owners:
  - candidate source
  - slot lifecycle eligibility
  - reachability
  - dispatch gate
  - load/admission
  - WorkerGroup capability projection / worker attributes
  - worker-side ranking mechanics
  - reserve/lock
- Engine may orchestrate counts and task-side policy intent, but it should not
  orchestrate candidate acquisition or ranking. If an intermediate staged API is
  unavoidable, engine must pass opaque handles and minimal intent between
  worker-runtime stages only. Engine must not filter, reorder, persist, compare,
  log, or branch on pre-selection handles except by passing them to the next
  approved worker-runtime stage.
- Worker-runtime main sources must not import `com.xa.mass.engine.*` or engine
  rule/evaluator classes. If rule-like worker-side predicates remain, they must
  be represented as API-owned policy tokens or resolved predicate intent that
  worker-runtime can consume without an engine dependency.
- The selected-handle claim bridge must not expose raw
  `supportedEventCodes`, WorkerGroup capability lists, worker attributes, or
  load details. It is either:
  - no per-worker event constraint because worker-runtime selection already
    proved claim compatibility, or
  - a typed minimal task-runtime `ClaimConstraint` / opaque token that engine
    only forwards and cannot inspect for worker facts.
- Do not add a single all-purpose request that embeds rule context, worker
  facts, trace snapshots, task lifecycle policy, or transport topology.

Acceptance:

- `WorkerSelectionIntent` and selected-handle contracts compile without a
  worker-runtime dependency on `xa-mass-engine`.
- Worker-runtime tests prove the selected-worker contract returns only selected
  handles, prewarm handles, bounded outcomes, reason codes, counts, or
  diagnostic ids.
- If pre-selection handles exist, worker-runtime tests prove they are opaque and
  do not expose `workerId`, `workerGroupId`, worker facts, or runtime evidence.
- Worker-runtime tests prove that worker attributes, WorkerGroup capability
  projections, reachability, load, lock, dispatch gate, and admission are
  evaluated without exposing those facts to engine.
- WSM-1 and WSM-2 landed together in the current mainline; the selection
  contract is not a second production path.
- Contract guards fail if selected handles expose `supportedEventCodes`,
  worker attributes, capability lists, load, reachability, dispatch-gate state,
  transport ids, or engine DTO imports.
- Contract guards fail if any pre-selection handle exposes worker identity or
  worker facts.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerSelectionAtomicRuntimeTest,WorkerSelectionContractGuardTest,WorkerSelectionRankingMechanicsTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## WSM-2 - Retarget Engine Assignment To Selection Handles

Goal: make the engine assignment mainline orchestrate worker-runtime atomic
stages and consume selected handles instead of candidate rows, scheduling
views, and admission ports.

Scope:

- Replace the default `RuleBasedTaskWorkerMatchingStrategy` mainline with an
  engine-owned orchestration flow:
  - resolve task scheduling policy,
  - compute requested worker count/allocation budget,
  - build minimal worker-universe intent/request,
  - ask worker-runtime to select and reserve selected handles,
  - pass selected handles to allocation and binding.
- Engine may decide counts and task-side policy intent. Engine must not filter,
  rank, reorder, bucket, or persist worker-runtime candidates. The first
  engine-visible worker identity produced by the selection flow is the selected
  or prewarm handle.
- If WSM-1 introduced temporary pre-selection handles, WSM-2 must keep them
  forward-only and must not let `TaskWorkerAssignListener`,
  `RuleBasedTaskWorkerMatchingStrategy`, allocation, binder, trace, or
  diagnostics branch on them.
- Replace `TaskWorkerMatchingStrategy` or narrow it so custom extensions do
  not receive or return worker metadata. A retained extension may choose
  task-side selection policy intent, but must not receive worker fact views or
  worker-runtime pre-selection handles.
- Retarget `sdk/xa-mass-embedded-sdk` assembly surfaces that currently expose
  the old matching seam, including `EngineConfig#setMatchingStrategy(...)` and
  `MassEngineBuilder#matchingStrategy(...)`. They must be removed or replaced
  with an approved selected-handle/stage-policy extension that cannot return
  `WorkerSchedulingCandidate`.
- Migrate SDK test fixtures that currently inject old matching strategies or
  construct `WorkerSchedulingCandidate` / `WorkerSchedulingView` through the
  SDK assembly seam. Tests must prove the new assembly surface, not preserve the
  old seam as compatibility.
- Change `AssignmentAllocationPolicy` and `AssignmentAllocationDecision` to
  carry selected handles, counts, and budget decisions rather than
  `WorkerSchedulingCandidate`.
- Change `SimpleTaskDispatchBinder` to bind `SelectedWorkerHandle`.
- Change `SimpleTaskDispatchBinder` to build current work-claim input from the
  selected-handle claim constraint, not from `WorkerSchedulingView` or
  per-worker event capability lists.
- Change allocation planning to read active selected-worker count from the
  approved selection/accounting contract, or from the selection summary, not
  from `WorkerAdmissionRuntime#getActiveWorkerCountForTask(...)`.
- Change release/final paths to release by selected handle or persisted
  `(taskId, workerGroupId, workerId, selectionToken)` evidence.
- `taskId` may remain engine-persisted lifecycle evidence for release/final
  recovery, but worker-runtime selection/accounting calls should receive only
  the selected handle plus opaque reservation/selection/accounting tokens unless
  a later task-runtime contract explicitly owns a different boundary.
- Update `EngineSchedulingCoreArchitectureGuardTest` in the same slice:
  guards that currently protect `WorkerSchedulingView`/assignment snapshot
  evidence must become selected-handle/worker-selection contract guards before
  production code is retargeted.
- Update `xa-mass-engine/README.md` and `xa-mass-worker-runtime/README.md` in
  the same slice so owner docs describe the new split:
  engine owns task-side policy/orchestration; worker-runtime owns worker-fact
  predicate/ranking mechanisms.
- Update `xa-mass-worker-runtime/CONTRACTS.md` in the same slice so it no
  longer states that worker-runtime cannot own worker-fact predicate/ranking
  mechanics. It must preserve that worker-runtime does not own task-side policy
  or lifecycle decisions.
- Remove engine calls to:
  - `WorkerCandidateRuntime#findWorkerCandidateBatch(...)`
  - `WorkerSchedulingViewRuntime#getWorkerReachability(...)`
  - `WorkerSchedulingViewRuntime#getWorkerLoad(...)`
  - `WorkerSchedulingViewRuntime#workerGroupReadView(...)`
  - `WorkerAdmissionRuntime#reserveWorkerCapacity(...)`
  - `WorkerAdmissionRuntime#confirmWorkerReservation(...)`
  - `WorkerAdmissionRuntime#releaseWorkerReservation(...)`
  - `WorkerAdmissionRuntime#recordWorkClaimed(...)`
  - `WorkerAdmissionRuntime#recordWorkFinal(...)`
  - `WorkerAdmissionRuntime#tryAcquireWorkerExclusiveLease(...)`
  - `WorkerAdmissionRuntime#releaseWorkerExclusiveLease(...)` from the
    assignment/resource-release hot path
  - `WorkerAdmissionRuntime#getWorkerLoad(...)`
  - `WorkerAdmissionRuntime#getActiveWorkerCountForTask(...)`
- Keep task lifecycle, claim, dispatch binding, result convergence, and
  terminal policy in engine.
- Do not move task-side allocation formulas, min-start gate behavior, retry
  policy, result policy, or terminal convergence into worker-runtime.

Acceptance:

- Engine assignment/matching code consumes only the approved worker-runtime
  selection stage contract(s). `EngineRuntimeKernelConfig` may still expose
  out-of-scope worker-control, wakeup, or dispatch-gate ports until their own
  owner roadmap removes them.
- `TaskWorkerMatchingStrategy`, `EngineConfig#setMatchingStrategy(...)`, and
  `MassEngineBuilder#matchingStrategy(...)` no longer preserve a production
  path that returns or injects `WorkerSchedulingCandidate`.
- SDK main/test assembly no longer imports the old matching seam as a live
  extension path. Any remaining old model hits are explicitly diagnostic or
  deleted with the retired tests.
- Engine selection/assignment packages no longer import worker-runtime
  candidate, evidence, or admission packages except the approved selection
  contract.
- Engine selection/assignment packages cannot read, compare, filter, sort, log,
  or persist pre-selection handles. If such handles exist, engine may only pass
  them to the next worker-runtime selection stage.
- Engine no longer constructs `WorkerMatchContext` or
  `WorkerSchedulingView` in the production assignment mainline.
- Engine no longer ranks by worker load, worker attributes, supported projects,
  or supported event codes.
- `SimpleTaskDispatchBinder` does not read `supportedEventCodes` from a
  scheduling view. If current `WorkerClaimTarget` remains, it is populated
  only from the selected-handle claim constraint or from a task-runtime claim
  API that no longer needs per-worker event lists.
- Allocation, binder, release, and final paths no longer call
  `WorkerAdmissionRuntime` directly for active counts, reserve/confirm/release,
  claim/final accounting, exclusive locks, or load evidence.
- Guard tests fail if engine assignment/strategy/model/resource packages
  import old worker candidate/evidence/admission packages outside the approved
  selection contract.
- Owner docs no longer state that worker-runtime cannot own worker-side
  predicate/ranking mechanics; they preserve the rule that worker-runtime does
  not own task-side policy or task lifecycle semantics.
- Existing scheduling behavior remains functionally covered by focused tests:
  target worker, group selection, dispatch-disabled worker, unreachable worker,
  capacity contention, lock contention, and assignment/refill paths.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,WorkerSelectionAtomicRuntimeTest,WorkerSelectionContractGuardTest,WorkerSelectionRankingMechanicsTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am `
  -DskipTests test-compile

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk `
  "-Dtest=MassEngineStartRecoveryTest,MassEngineAssemblyBoundaryTest" `
  test
```

## WSM-3 - Worker-Side Predicate And Ranking Mechanics

Goal: remove worker metadata rule evaluation and ranking from engine while
keeping policy ownership in engine/Scheduling Plane and worker-fact mechanisms
inside worker-runtime.

Scope:

- Move current worker capability projection, attribute matching, routing
  affinity execution, reachability, dispatch-gate, load, lock, warm-hint, and
  ranking mechanics behind worker-runtime selection contracts.
- If rules remain, split them:
  - engine may resolve task-side policy/rule intent,
  - worker-runtime evaluates worker-side predicates over worker facts,
  - the cross-module model is a policy id/token or resolved intent, not a
    worker fact map.
- Remove worker metadata from engine `RuleDefinition` usage or route it only
  through worker-runtime worker-side predicate execution.
- Do not move engine `RuleDefinition`, `TaskDispatchIntent`, or
  `ResolvedWorkerSchedulingPolicy` classes into worker-runtime. Translate them
  into the API-owned `WorkerSelectionIntent` or policy token before crossing
  the module boundary.
- Retire `DefaultWorkerCandidateRanker` from engine production code.
- Retire `WorkerSchedulingCandidateEnumerator` from engine production code.
- Migrate or delete tests that exist only to preserve the retired engine worker
  view/ranker model, including `WorkerMatchContextTest`,
  `WorkerSchedulingCandidateEnumeratorTest`,
  `DefaultWorkerCandidateRankerTest`, and old
  `RuleBasedTaskWorkerMatchingStrategyTest` fixture paths. Replacement proof
  belongs in worker-runtime selection/ranking tests and engine guard tests.
- Keep diagnostic summaries as bounded reason codes rather than full worker
  metadata maps.
- Do not give worker-runtime the authority to reinterpret item payload or
  choose WorkerGroup capability. It executes worker-side mechanisms inside the
  engine-resolved worker universe.

Acceptance:

- Engine rule context does not include:
  - worker attributes
  - supported projects
  - supported event codes
  - reachability
  - load
  - active lease count
  - reserved count
  - declared capacity
  - lock state
  - dispatch gate state
- Engine production code does not import `DefaultWorkerCandidateRanker` or
  `WorkerSchedulingCandidateEnumerator`.
- Worker-runtime tests prove equivalent worker-side predicate/ranking outcomes
  for the current supported scenarios.
- Engine tests prove task-side policy ownership is unchanged: allocation budget,
  min-start gate, claim/bind, retry, and terminal behavior remain engine-owned.
- Trace and assignment diagnostics show bounded reason codes and selected
  handle identity, not full worker metadata.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime `
  "-Dtest=EngineSchedulingCoreArchitectureGuardTest,WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest" `
  test
```

## WSM-4 - Prewarm And Cache Boundary

Goal: support optional prewarm without giving engine a metadata cache.

Scope:

- Add optional `prewarmCount` to `WorkerSelectionRequest` only if a concrete
  caller needs it. If an intermediate implementation keeps separate staged
  worker-selection APIs, prewarm should be an optional selection hint, not a
  separate engine metadata cache.
- Worker-runtime owns warm candidate cache and invalidation.
- Engine may receive prewarm handles containing only `workerId`,
  `workerGroupId`, and an opaque prewarm/selection token.
- Engine must not store worker metadata, load snapshots, capability lists, or
  reachability snapshots as a prewarm cache.
- Worker-runtime validates stale prewarm handles before they become selected.

Acceptance:

- Prewarm handles cannot bypass reachability, dispatch gate, capacity, lock, or
  reservation checks.
- Engine prewarm storage, if any, contains no worker metadata.
- Worker-runtime owns prewarm invalidation and final eligibility validation.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime,xa-mass-engine -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerSelectionPrewarmTest,WorkerSelectionAtomicRuntimeTest" test

.\mvnw.cmd -pl xa-mass-engine `
  "-Dtest=TaskSchedulingContentionTest" test
```

## WSM-5 - Diagnostic And Server Inspection Separation

Goal: keep rich worker metadata available where it belongs without letting it
return to engine scheduling.

Scope:

- Define or confirm explicit server/SDK diagnostic read models for worker
  metadata, capability, reachability, load, and dispatch eligibility.
- Keep those views outside engine assignment packages.
- Replace engine assignment diagnostics that require full `WorkerSchedulingView`
  with:
  - selected worker handle,
  - reason code,
  - counts,
  - optional opaque diagnostic id produced by worker-runtime.
- Ensure trace/archive evidence is diagnostic and cannot drive selection.

Acceptance:

- Server/SDK inspection tests still expose intended worker metadata.
- Engine assignment diagnostics compile without `WorkerSchedulingView`.
- Guards allow rich worker metadata only in explicit inspection/diagnostic
  packages, not in engine scheduling packages.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-server,sdk/xa-mass-embedded-sdk,xa-mass-engine -am `
  "-Dtest=WorkerApiControllerTest,CatalogControllerTest,EngineSchedulingCoreArchitectureGuardTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## WSM-6 - Remove Old Hot-Path Residue And Add Guards

Goal: prevent the old engine-visible worker view model from returning.

Scope:

- Remove or reclassify as diagnostic-only:
  - `WorkerSchedulingView`
  - `WorkerSchedulingCandidate`
  - `WorkerMatchContext`
  - `WorkerSchedulingCandidateEnumerator`
  - `DefaultWorkerCandidateRanker`
  - `WorkerCandidateRanker`
  - `WorkerCandidateRankPolicy`
- Remove engine production imports of:
  - `com.xa.mass.worker.runtime.candidate.*`
  - `com.xa.mass.worker.runtime.evidence.*`
  - `com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime`
  - `WorkerAdmissionTarget`
- Keep only approved imports for:
  - worker selection selected-worker contracts,
  - selected handle/result contracts
  - worker-control contracts that are explicitly out of this roadmap.
- Guard the new stage contracts:
  - pre-selection handles are opaque and do not expose worker
    identity or facts;
  - selected/prewarm handles expose only `workerId`, `workerGroupId`, and
    opaque tokens;
  - claim constraints are either task-runtime constraints or opaque tokens, not
    worker capability lists.
- Update any remaining owner docs and proof registry entries not already
  updated in WSM-2.
- Update or archive superseded owner language in the residue roadmap so it
  cannot be read as current proof that engine owns worker-fact ranking or
  admission calls.

Acceptance:

- Architecture guard fails if engine assignment/strategy/model/resource
  packages import worker candidate/evidence/admission packages outside the
  approved selection handle API.
- Architecture guard fails if engine rule context contains worker metadata or
  live worker evidence keys.
- Architecture guard fails if engine ranking code reads worker load,
  attributes, capability lists, reachability, locks, dispatch gate, or
  reservation evidence.
- Architecture guard fails if any pre-selection handle exposes
  `workerId`, `workerGroupId`, worker facts, or runtime evidence.
- Architecture guard fails if selected/prewarm handles expose worker attributes,
  capability lists, load, reachability, dispatch gate, locks, transport ids, or
  internal worker-runtime source keys.
- Architecture guard fails if engine code rebuilds `WorkerClaimTarget` from
  `WorkerSchedulingView`, WorkerGroup capability projections, or worker
  metadata.
- Source scan has no production hits for retired hot-path types except
  explicitly named diagnostic/archive/test surfaces.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime,xa-mass-testing -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime,xa-mass-testing `
  "-Dtest=EngineSchedulingCoreArchitectureGuardTest,EngineSchedulingCoreSuite,WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest,WorkerSelectionContractGuardTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test

rg -n "WorkerSchedulingView|WorkerSchedulingCandidate|WorkerMatchContext|DefaultWorkerCandidateRanker|WorkerSchedulingCandidateEnumerator" `
  xa-mass-engine/src/main/java `
  --glob '!**/target/**'

rg -n "workerAttributes|supportedProjects|supportedEventCodes|estimatedLoadRatio|activeLeaseCount|reservedCount|declaredCapacity" `
  xa-mass-engine/src/main/java/com/xa/mass/engine `
  --glob '!**/target/**'
```

## Roadmap Completion Criteria

This roadmap is complete only when all of the following are true:

- Engine default assignment mainline calls a minimal worker-runtime selected
  worker contract such as `selectAndReserve(...)`. Any exposed pre-selection
  handle is transitional, opaque, forward-only, and guarded.
- If pre-selection handles exist, engine cannot read worker identity, worker
  facts, or runtime evidence from them and cannot sort, filter, compare, log, or
  persist them.
- Engine does not consume worker candidate rows, worker scheduling views,
  worker metadata maps, capability lists, live load snapshots, reachability
  snapshots, dispatch-gate evidence, reservation evidence, or worker locks as
  selection inputs.
- Worker-runtime owns candidate acquisition mechanics, worker-side predicate
  execution, ranking mechanics, reservation, lock, warm cache, and final
  accounting, while engine remains the task-side policy and orchestration owner.
- Engine receives only selected worker handles containing `workerId`,
  `workerGroupId`, selection/reservation/prewarm tokens, and an approved claim-constraint
  token/value that does not expose worker metadata.
  Optional selection/prewarm/accounting ids exist only when needed by a concrete
  mechanism; they are opaque correlation values, not task lifecycle fields.
- Engine work-claim/binder code does not read `supportedEventCodes`,
  WorkerGroup capability lists, worker attributes, or worker load from a
  worker scheduling view.
- Engine allocation, binder, release, and final paths do not call
  `WorkerAdmissionRuntime` directly for active counts, reserve/confirm/release,
  claim/final accounting, load, or exclusive-lock evidence. Those operations go
  through selected-handle/accounting contracts or remain inside worker-runtime
  selection stages.
- `xa-mass-worker-runtime` main sources do not depend on `xa-mass-engine`
  packages, engine rule packages, or engine scheduling DTOs.
- Engine remains owner of task-side scheduling policy resolution, allocation
  budget, work claim, dispatch binding, result convergence, retry, and
  terminal policy.
- Server/SDK inspection surfaces may expose worker metadata, but engine
  scheduling packages cannot import those views.
- Superseded roadmap/doc language no longer states as current truth that engine
  owns worker-fact ranking or direct admission calls.
- Existing target-worker, WorkerGroup, reachability, dispatch gate, capacity,
  contention, result/final, and transport selected-worker delivery tests pass.
- Guards prevent reintroduction of engine-side worker metadata matching or
  ranking.

## Verification Candidates

Implemented-slice verification recorded on 2026-06-18:

```powershell
.\mvnw.cmd -pl xa-mass-engine -am -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-engine -am `
  "-Dtest=TaskDelayedAvailabilitySchedulingTest,TaskSchedulingBindingEntryBypassTest,TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest,TaskWorkerEligibilityTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl xa-mass-engine -am `
  -Dtest=EngineSchedulingCoreArchitectureGuardTest `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl xa-mass-engine -am `
  "-Dtest=EngineSchedulingCoreSuite,EngineSchedulingCoreArchitectureGuardTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl xa-mass-worker-runtime -am `
  "-Dtest=WorkerSelectionAtomicRuntimeTest,WorkerSelectionContractGuardTest,WorkerSelectionRankingMechanicsTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am -DskipTests test-compile

.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk -am `
  "-Dtest=MassEngineAssemblyBoundaryTest,MassEngineStartRecoveryTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Compile-only commands are compile evidence only. The focused scheduling tests,
`EngineSchedulingCoreSuite`, architecture guard, worker-runtime selection owner
tests, SDK assembly boundary test, and SDK start-recovery test above are the
current proof for the implemented mainline slice.

Initial inventory/contract proof:

```powershell
rg -n "WorkerSchedulingView|WorkerSchedulingCandidate|WorkerMatchContext|WorkerCandidateRuntime|WorkerSchedulingViewRuntime|WorkerAdmissionRuntime|DefaultWorkerCandidateRanker" `
  xa-mass-engine/src/main/java `
  --glob '!**/target/**'
```

Focused implementation proof:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerSelectionAtomicRuntimeTest,WorkerSelectionContractGuardTest,WorkerSelectionRankingMechanicsTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

.\mvnw.cmd -pl xa-mass-engine -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-engine `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,EngineSchedulingCoreArchitectureGuardTest,EngineSchedulingCoreSuite" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Broad compile after each behavior slice:

```powershell
.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime,sdk/xa-mass-embedded-sdk,xa-mass-server,xa-mass-testing -am -DskipTests test-compile
```

Residue scans:

```powershell
rg -n "WorkerSchedulingView|WorkerSchedulingCandidate|WorkerMatchContext|DefaultWorkerCandidateRanker|WorkerSchedulingCandidateEnumerator" `
  xa-mass-engine/src/main/java `
  --glob '!**/target/**'

rg -n "workerAttributes|supportedProjects|supportedEventCodes|estimatedLoadRatio|activeLeaseCount|reservedCount|declaredCapacity" `
  xa-mass-engine/src/main/java/com/xa/mass/engine `
  --glob '!**/target/**'

rg -n "com\\.xa\\.mass\\.worker\\.runtime\\.(candidate|evidence|admission)" `
  xa-mass-engine/src/main/java/com/xa/mass/engine `
  --glob '!**/target/**'
```

Remaining hits must be either removed, moved to worker-runtime, or explicitly
classified as diagnostic/test-only with an architecture guard.
