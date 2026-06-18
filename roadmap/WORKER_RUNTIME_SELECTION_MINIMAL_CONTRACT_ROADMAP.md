# Worker Runtime Selection Minimal Contract Roadmap

Status: proposed direction document.

Related:

- `roadmap/WORKER_RUNTIME_MINIMAL_INTERFACE_CONVERGENCE_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_COMPOSITE_ELIGIBILITY_SET_ROADMAP.md`
- `roadmap/RUNTIME_WORKER_SELECTION_RESIDUE_CONVERGENCE_ROADMAP.md`
- `roadmap/WORKER_RUNTIME_SELECTION_MINIMAL_CONTRACT_INVENTORY.md`
- `xa-mass-engine/README.md`
- `xa-mass-worker-runtime/README.md`
- `xa-mass-worker-runtime/CONTRACTS.md`
- `doc/PROOF_REGISTRY.md`

## Purpose

Move runtime worker selection behind minimal worker-runtime capability
contracts without turning worker-runtime into a second scheduling brain.

WMI narrowed worker declaration and default worker-facing DTOs, but the current
engine hot path still receives and interprets worker metadata and live worker
evidence. The target of this roadmap is not another DTO slimming pass and not a
class relocation exercise. The target is a boundary change:

```text
Engine resolves task-side scheduling policy and worker-universe intent
  -> Engine orchestrates a few coarse worker-runtime atomic stages
  -> Worker-runtime executes worker-fact mechanisms without exposing facts
  -> Engine binds work to selected worker handles
  -> Transport delivers to the selected worker
```

The long-term optimized shape may collapse these stages into a single
`selectAndReserve` style call. The first implementation does not need to start
there. It may expose a small set of coarse atomic stages, such as candidate
acquisition, worker-side eligibility/ranking, reservation, and final
accounting, as long as the cross-module model remains minimal.

Engine should not materialize `WorkerSchedulingView`, read worker attributes,
join WorkerGroup capability, rank by worker load, or evaluate worker-metadata
rules. Worker-runtime may use those facts internally, cache them, index them,
and expose diagnostics to server/SDK through explicit inspection contracts.

## Current Code Observations

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

The first implementation should prefer a few coarse stages over one large
worker-runtime scheduler. A valid initial shape is:

```text
candidate acquisition:
  request = worker universe + target worker + optional bucket hints + limit
  result  = minimal candidate handles

worker-side eligibility/ranking:
  request = candidate handles + WorkerSelectionIntent
  result  = ordered candidate handles + bounded rejection summary

reservation:
  request = candidate handles + reservation mode + requested count
  result  = selected handles with reservation/selection token

accounting:
  request = selected handle + claim/final/release event
  result  = accepted/rejected bounded outcome
```

The optimized future shape may expose:

```text
WorkerSelectionRequest
  worker selection intent:
    project
    eventCode
    workerGroupIds
    routingCode or named route policy token
    targetWorkerId when explicitly requested
    optional engine-owned bucket hints or policy-owned bucket hint
  requestedWorkerCount
  optional prewarmCount
  resource mode / reservation mode
  task-side claim constraints that are not worker metadata
  optional opaque correlation/prewarm key only when a concrete mechanism needs it
```

Target worker-runtime output to engine, whether produced by one call or by
coarse stages:

```text
WorkerSelectionResult
  selected:
    workerId
    workerGroupId
    optional bucket hint or selectionToken
    reservationToken or selectionToken
    claimConstraint or claimToken
  prewarm:
    workerId
    workerGroupId
    optional bucket hint or selectionToken
  summary:
    requestedCount
    selectedCount
    rejectedCountByReason
    bounded diagnostic reason codes
```

The selected handle is the only object the engine may use to claim work, bind
dispatch, release selection state, and record final worker accounting. It must
not carry worker attributes, supported project/event lists, live load details,
reachability details, dispatch-gate details, or transport topology ids.

The selected handle may carry a typed `claimConstraint` or opaque `claimToken`
that is produced by worker-runtime and consumed by the engine binder only as a
claim bridge. It must not expose raw `supportedEventCodes` as worker metadata.
If `TaskWorkRuntime#claimReady(...)` still requires `WorkerClaimTarget` during
the first implementation, the binder may translate the selected-handle claim
constraint into the existing `WorkerClaimTarget` shape without inspecting or
deriving worker capability facts.

Worker-runtime must not understand task lifecycle semantics. Requests or
accounting commands may carry opaque ids only when a concrete mechanism needs
them, such as a selection correlation id, reservation token, claim token, or
prewarm key. Worker-runtime treats those values as uninterpreted identities for
idempotency, cache lookup, reservation correlation, or accounting. It must not
parse those ids as `taskId`, `messageId`, retry policy, result policy, or task
lifecycle truth.

Engine may choose coarse worker-universe slicing, such as a bucket policy or
bucket hint, when that is part of task-side worker-universe intent. Worker-runtime
must not invent an additional selection-visible shard, bucket, or partition
policy. Engine must not know or decide the final worker-runtime source key,
cache key, storage key, or prewarm key. If worker-runtime implementations derive
private lookup/index keys, those keys are hidden storage mechanics derived from
the engine-provided universe/hint and canonical worker facts; they must not
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

First define and prove the minimal atomic stage contracts, then retarget the
engine mainline to those contracts, then remove the old worker view/candidate
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
  - worker-fact mechanism to move behind worker-runtime atomic stages,
  - task-side orchestration that remains in engine,
  - dispatch binding that should use selected handles,
  - diagnostic-only residue,
  - explicit out-of-scope worker-control path.
- Decide how `TaskWorkRuntime#claimReady(...)` should handle worker event
  capability without engine reading per-worker supported event lists.
- Decide the first selected-handle claim bridge:
  - preferred first slice: worker-runtime returns a typed
    `WorkerSelectionClaimConstraint` or opaque claim token, and engine binder
    only maps it to the current `WorkerClaimTarget` until the task-runtime
    claim API is narrowed;
  - explicitly rejected first slice: engine reads `supportedEventCodes` or
    WorkerGroup capability lists from a scheduling view.
- Decide the package owner for `WorkerSelectionIntent`,
  `SelectedWorkerHandle`, selection summary/reason codes, and claim
  constraints. The chosen package must not require `xa-mass-worker-runtime` to
  depend on `xa-mass-engine`.
- Decide the initial coarse stage split. The default target is:
  - acquire candidate handles,
  - evaluate/rank worker-side facts,
  - reserve selected handles,
  - release/claim/final accounting by selected handle.
- Record that a single `selectAndReserve(...)` call is an optional later
  optimization, not the required first contract.
- Decide whether route/routing affinity is represented as:
  - a policy-owned bucket hint,
  - a worker-runtime route policy token,
  - or explicit task-side routing code passed to worker-runtime for internal
     interpretation.
- Record that whichever representation is chosen, worker-runtime must consume it
  as engine-resolved worker-universe intent and must not add its own
  selection-visible shard/bucket policy.
- Inventory existing architecture guards and owner docs that protect the old
  `WorkerSchedulingView`/`WorkerMatchContext` model. Decide which guard changes
  must land with WSM-2 and which final residue guards can wait until WSM-6.
- Record the owner-doc delta for `xa-mass-worker-runtime/README.md`:
  worker-runtime still does not own task-side policy, but it will own
  worker-fact predicate/ranking mechanics behind minimal selection contracts.
- Record the same owner-doc delta for `xa-mass-worker-runtime/CONTRACTS.md`.

Acceptance:

- The inventory names every current production caller and target owner.
- The first implementation slice has no unresolved question about
  `supportedEventCodes` in `WorkerClaimTarget`.
- The first implementation slice has a chosen selected-handle claim-constraint
  shape and the binder translation rule is explicit.
- The first implementation slice has a chosen contract package for
  `WorkerSelectionIntent` and selected-handle contracts.
- The first implementation slice has a chosen stage boundary and names which
  stage owns each current worker-fact read.
- The first implementation slice has no unresolved question about routing
  affinity representation.
- The first implementation slice explicitly forbids worker-runtime-owned
  autonomous sharding/bucket selection in the worker-selection path.
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

## WSM-1 - Minimal Atomic Stage Contracts

Goal: introduce worker-runtime atomic stage contracts that can replace the
engine candidate/view/admission composition without creating a monolithic
worker-runtime scheduler.

Scope:

- Add worker-runtime contracts under a selection-owned package. Names are
  flexible, but the contract family should cover:
  - `WorkerSelectionIntent` or equivalent API-owned worker selection intent,
  - candidate acquisition request/result,
  - worker-side eligibility/rank request/result,
  - reservation request/result,
  - selected worker handle,
  - selected-handle claim constraint or claim token,
  - selection summary/rejection reason,
  - selected-handle accounting commands.
- The candidate handle may contain only:
  - `workerId`
  - `workerGroupId`
  - optional engine-visible bucket hint when needed by allocation, or opaque
    candidate token
- The selected handle may contain only:
  - `workerId`
  - `workerGroupId`
  - optional engine-visible bucket hint when needed by allocation, or opaque
    `selectionToken`
  - reservation/selection token if required
  - typed claim constraint or opaque claim token if required by
    `TaskWorkRuntime#claimReady(...)`
- The selected handle must not carry task lifecycle fields such as `taskId`,
  `messageId`, retry state, or result policy. If a runtime mechanism needs a
  stable correlation value, pass an opaque selection/prewarm/accounting key in
  the request or accounting command and keep it uninterpreted by worker-runtime.
- Requests may carry `WorkerSelectionIntent`, requested counts, reservation
  mode, and optional prewarm count. They must not carry worker metadata.
- Worker-runtime may use private lookup/index keys internally for performance,
  but those keys must not be part of the cross-module contract and must not
  create a new worker-universe slice beyond the engine-provided intent/hints.
- Implement the coarse atomic stages in worker-runtime by reusing current
  owners:
  - candidate source
  - slot lifecycle eligibility
  - reachability
  - dispatch gate
  - load/admission
  - WorkerGroup capability projection / worker attributes
  - worker-side ranking mechanics
  - reserve/lock
- Engine may orchestrate the coarse stages in the first implementation, but it
  must pass handles and minimal intent between stages. Engine must not reserve
  after inspecting worker facts.
- Worker-runtime main sources must not import `com.xa.mass.engine.*` or engine
  rule/evaluator classes. If rule-like worker-side predicates remain, they must
  be represented as API-owned policy tokens or resolved predicate intent that
  worker-runtime can consume without an engine dependency.
- The selected-handle claim bridge must not expose raw
  `supportedEventCodes`, WorkerGroup capability lists, worker attributes, or
  load details. It is either a typed minimal claim constraint or an opaque
  token that only worker-runtime/task-runtime claim code can interpret.
- Do not add a single all-purpose request that embeds rule context, worker
  facts, trace snapshots, or task lifecycle policy.

Acceptance:

- `WorkerSelectionIntent` and selected-handle contracts compile without a
  worker-runtime dependency on `xa-mass-engine`.
- Worker-runtime tests prove that each stage returns only handles, bounded
  outcomes, reason codes, counts, or diagnostic ids.
- Worker-runtime tests prove that worker attributes, WorkerGroup capability
  projections, reachability, load, lock, dispatch gate, and admission are
  evaluated without exposing those facts to engine.
- No engine production caller has been switched yet unless WSM-2 lands in the
  same commit/slice.
- If WSM-1 and WSM-2 do not land together, the new contract must not become a
  second production path.
- Contract guards fail if selected handles expose `supportedEventCodes`,
  worker attributes, capability lists, load, reachability, dispatch-gate state,
  transport ids, or engine DTO imports.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-worker-runtime -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-worker-runtime `
  "-Dtest=WorkerSelectionAtomicRuntimeTest,WorkerSelectionContractGuardTest" test
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
  - build minimal worker-universe intent,
  - acquire candidate handles from worker-runtime,
  - ask worker-runtime to evaluate/rank worker-side facts,
  - ask worker-runtime to reserve selected handles,
  - pass selected handles to allocation and binding.
- Replace `TaskWorkerMatchingStrategy` or narrow it so custom extensions do
  not receive or return worker metadata. A retained extension may choose
  policy intent or stage strategy, but must not receive worker fact views.
- Retarget `sdk/xa-mass-embedded-sdk` assembly surfaces that currently expose
  the old matching seam, including `EngineConfig#setMatchingStrategy(...)` and
  `MassEngineBuilder#matchingStrategy(...)`. They must be removed or replaced
  with an approved selected-handle/stage-policy extension that cannot return
  `WorkerSchedulingCandidate`.
- Change `AssignmentAllocationPolicy` and `AssignmentAllocationDecision` to
  carry selected handles, counts, and budget decisions rather than
  `WorkerSchedulingCandidate`.
- Change `SimpleTaskDispatchBinder` to bind `SelectedWorkerHandle`.
- Change `SimpleTaskDispatchBinder` to build current work-claim input from the
  selected-handle claim constraint, not from `WorkerSchedulingView` or
  per-worker event capability lists.
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
  - `WorkerAdmissionRuntime#tryAcquireWorkerExclusiveLease(...)`
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
- Engine selection/assignment packages no longer import worker-runtime
  candidate, evidence, or admission packages except the approved selection
  contract.
- Engine no longer constructs `WorkerMatchContext` or
  `WorkerSchedulingView` in the production assignment mainline.
- Engine no longer ranks by worker load, worker attributes, supported projects,
  or supported event codes.
- `SimpleTaskDispatchBinder` does not read `supportedEventCodes` from a
  scheduling view. If current `WorkerClaimTarget` remains, it is populated
  only from the selected-handle claim constraint.
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
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,WorkerSelectionAtomicRuntimeTest" `
  test
```

## WSM-3 - Worker-Side Predicate And Ranking Mechanics

Goal: remove worker metadata rule evaluation and ranking from engine while
keeping policy ownership in engine/Scheduling Plane and worker-fact mechanisms
inside worker-runtime.

Scope:

- Move current worker capability projection, attribute matching, routing
  affinity execution, reachability, dispatch-gate, load, lock, warm-hint, and
  ranking mechanics behind worker-runtime atomic stages.
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
  "-Dtest=WorkerMatchContextTest,EngineSchedulingCoreArchitectureGuardTest,WorkerSelectionAtomicRuntimeTest" `
  test
```

## WSM-4 - Prewarm And Cache Boundary

Goal: support optional prewarm without giving engine a metadata cache.

Scope:

- Add optional `prewarmCount` to `WorkerSelectionRequest` only if a concrete
  caller needs it. If the initial implementation keeps separate atomic stages,
  prewarm should be an optional candidate-acquisition hint, not a separate
  engine metadata cache.
- Worker-runtime owns warm candidate cache and invalidation.
- Engine may receive prewarm handles containing only `workerId`,
  `workerGroupId`, and an optional engine-visible bucket hint or opaque token.
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
  - worker selection atomic stage contracts,
  - selected handle/result contracts
  - worker-control contracts that are explicitly out of this roadmap.
- Update any remaining owner docs and proof registry entries not already
  updated in WSM-2.

Acceptance:

- Architecture guard fails if engine assignment/strategy/model/resource
  packages import worker candidate/evidence/admission packages outside the
  approved selection handle API.
- Architecture guard fails if engine rule context contains worker metadata or
  live worker evidence keys.
- Architecture guard fails if engine ranking code reads worker load,
  attributes, capability lists, reachability, locks, dispatch gate, or
  reservation evidence.
- Source scan has no production hits for retired hot-path types except
  explicitly named diagnostic/archive/test surfaces.

Suggested verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime,xa-mass-testing -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-engine,xa-mass-worker-runtime,xa-mass-testing `
  "-Dtest=EngineSchedulingCoreArchitectureGuardTest,EngineSchedulingCoreSuite,WorkerSelectionAtomicRuntimeTest" `
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

- Engine default assignment mainline calls minimal worker-runtime selection
  atomic stage contracts, or an equivalent optimized `selectAndReserve` call
  whose request/result remains equally minimal.
- Engine does not consume worker candidate rows, worker scheduling views,
  worker metadata maps, capability lists, live load snapshots, reachability
  snapshots, dispatch-gate evidence, reservation evidence, or worker locks as
  selection inputs.
- Worker-runtime owns candidate acquisition mechanics, worker-side predicate
  execution, ranking mechanics, reservation, lock, warm cache, and final
  accounting, while engine remains the task-side policy and orchestration owner.
- Engine receives only selected worker handles containing `workerId`,
  `workerGroupId`, an approved optional bucket hint or selection token, and an
  approved claim-constraint token/value that does not expose worker metadata.
  Optional selection/prewarm/accounting ids exist only when needed by a concrete
  mechanism; they are opaque correlation values, not task lifecycle fields.
- Engine work-claim/binder code does not read `supportedEventCodes`,
  WorkerGroup capability lists, worker attributes, or worker load from a
  worker scheduling view.
- `xa-mass-worker-runtime` main sources do not depend on `xa-mass-engine`
  packages, engine rule packages, or engine scheduling DTOs.
- Engine remains owner of task-side scheduling policy resolution, allocation
  budget, work claim, dispatch binding, result convergence, retry, and
  terminal policy.
- Server/SDK inspection surfaces may expose worker metadata, but engine
  scheduling packages cannot import those views.
- Existing target-worker, WorkerGroup, reachability, dispatch gate, capacity,
  contention, result/final, and transport selected-worker delivery tests pass.
- Guards prevent reintroduction of engine-side worker metadata matching or
  ranking.

## Verification Candidates

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
  "-Dtest=WorkerSelectionAtomicRuntimeTest,WorkerSelectionContractGuardTest" test

.\mvnw.cmd -pl xa-mass-engine -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-engine `
  "-Dtest=TaskWorkerEligibilityTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,WorkerMatchContextTest,EngineSchedulingCoreArchitectureGuardTest" test

.\mvnw.cmd -pl xa-mass-testing -am `
  -DskipTests test-compile

.\mvnw.cmd -pl xa-mass-testing `
  "-Dtest=EngineSchedulingCoreSuite" test
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
