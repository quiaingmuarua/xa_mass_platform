# Worker Runtime Module Extraction Roadmap

Status: draft. This roadmap is a module-boundary convergence plan. It must not
be read as proof that the module already exists.

This is the next convergence line after
[`WORKER_MATCH_UPGRADE_ROADMAP.md`](./WORKER_MATCH_UPGRADE_ROADMAP.md) and the
transport worker-match spine. The match roadmap keeps the two-stage match
strategy healthy; this roadmap makes the worker runtime owner explicit so later
strategy work does not keep accumulating inside `xa-mass-engine`.

## Summary

Worker has grown from a small engine helper into a mature runtime plane:

```text
Worker resource declaration
  AdapterNode / WorkerGroup / NodeGroupBinding / Worker

Worker runtime projection
  WorkerMeta / WorkerSlot / route buckets / dispatch gates / heartbeat evidence

Worker scheduling runtime
  bounded candidate acquisition / source guard / reserve / confirm / release
  / final occupancy / exclusive lease / warm hint revalidation

Worker report runtime
  capability reports / state reports / command-driven dispatch gate effects
```

Keeping all of this inside `xa-mass-engine` makes the engine look like both the
task scheduling strategy owner and the worker runtime owner. That boundary is
now too blurry.

Target direction:

```text
worker runtime owner
  owns worker runtime projections, indexes, candidate source, source guard,
  structured admission, occupancy, warm hints, and worker report effects

xa-mass-engine
  owns task lifecycle, assignment trigger, match strategy, rule/rank policy,
  allocation/refill, dispatch binding, result convergence, and terminal policy

transport
  owns protocol sessions, connection presence, delivery routes, and result
  transport; it feeds normalized evidence into worker/runtime owners
```

The goal is not to create a pass-through facade. The goal is to make the
existing owner split explicit and move worker runtime truth behind narrow,
stable contracts.

This roadmap is intentionally convergence-first:

```text
1. inventory current owner/caller truth
2. introduce narrow contracts inside the current module
3. split `WorkerManager` internals by owner
4. clean DTO/package dependencies
5. move contracts and implementations only after the owner boundaries are
   already proven
```

Large package moves and renames are expected later, but they are not first-slice
business-logic work. A slice that only moves files is acceptable only after the
source owner boundary is already proven and the old path is deleted in the same
change.

## Layer Rule

Do not collapse the three infra truth layers during the extraction.

```text
control-plane storage
  stable Worker / WorkerGroup / AdapterNode / NodeGroupBinding declarations

runtime state
  WorkerMeta / WorkerSlot / route buckets / heartbeat freshness / dispatch
  gates / reservations / active occupancy / warm hints

trace / audit stream
  high-volume report history, command history, assignment evidence, and
  operator analysis
```

Worker runtime may own the write path that receives worker registration and
report commands, but durable registration and capability declaration remain
control-plane truth. Runtime state owns the derived projection used by matching.

## Existing Runtime-Api Relationship

This roadmap must not duplicate the worker runtime contracts that already live
under `platform_infra/mass-runtime-api`.

Current code already has:

```text
platform_infra/mass-runtime-api
  com.xa.mass.runtime.worker.WorkerRegistry
  WorkerMeta / WorkerSlot / ReserveResult / EventKey / DispatchAvailabilitySource

platform_infra/mass-runtime-redis
  RedisWorkerRegistry

xa-mass-engine
  InMemoryWorkerRegistry
  WorkerManager assembly and worker owner residue
```

Extraction must converge these existing pieces instead of introducing a second
parallel runtime contract.

Preferred long-term shape:

```text
platform_infra/mass-runtime-api
  neutral worker runtime contracts and value types

platform_infra/mass-runtime-memory
  in-memory WorkerRegistry implementation

platform_infra/mass-runtime-redis
  Redis WorkerRegistry implementation

xa-mass-worker-runtime
  optional higher-level worker runtime owner module after dependency cleanup;
  may coordinate storage-api resource declarations, runtime-api slots, report
  projection, candidate source, and admission services
```

`xa-mass-worker-runtime` is allowed only when it is an owner module, not a
wrapper around `WorkerManager` or a duplicate of `mass-runtime-api`.

Avoid `worker-management` as the module name for this extraction. This is not a
CRUD backend. Operator-facing management APIs may be built later on top of
runtime views and trace/query projections.

Expected dependency direction after extraction:

```text
xa-mass-server
  -> xa-mass-sdk
     -> xa-mass-engine
        -> worker runtime contracts
           -> platform_infra/mass-runtime-api
           -> xa-mass-base

transport/transport_runtime
  -> worker runtime contracts
  -> transport/transport_api

worker runtime implementation modules
  -> platform_infra/mass-runtime-api
  -> platform_infra/mass-storage-api
  -> xa-mass-base
```

The exact Maven module shape is a later decision, but dependency direction is
not optional: engine and transport may consume worker-runtime contracts;
worker-runtime contracts must not consume engine or transport adapter
implementations.

## Core Rule

Worker runtime may provide readable views to engine, but readable views are not
enough.

Stage-2 matching still needs structured atomic admission. Therefore the worker
runtime owner must provide both:

```text
read:
  candidate batches
  runtime-neutral scheduling views
  source evidence and diagnostics
  reachability evidence consumed from transport presence

write:
  reserve / confirm / release / final occupancy mutation
  source-scoped dispatch gate mutation
  warm hint mutation after source guard
  resource/report mutation entry points that update control-plane truth and
  derived runtime projection without exposing slot internals
```

Engine strategy may decide which candidates to try. Worker runtime owns whether
a worker can be atomically admitted now, and must return a structured result
that preserves the rejection owner and reason.

## Non-Goals

This roadmap explicitly does not do:

1. Do not move task lifecycle, assignment lanes, result convergence, task
   runtime claim, or dispatch binding into worker runtime.
2. Do not let worker runtime decide the best worker for a task.
3. Do not introduce a generic plugin marketplace or policy registry.
4. Do not make DB-backed worker CRUD runtime truth.
5. Do not preserve old and new worker paths as two live tracks.
6. Do not create same-module bridge/facade wrappers that only forward calls.
7. Do not introduce `WorkerSession`, `Device`, or `AccountSlot` engine models.
8. Do not make transport connection/session evidence scheduling truth.
9. Do not make warm-pool state, eventCode, or arbitrary attributes candidate
   truth.
10. Do not duplicate `WorkerRegistry` / `WorkerSlot` contracts outside
    `mass-runtime-api`.
11. Do not move QLExpress rule evaluation or rank policy into worker runtime.

## Target Ownership

### Worker Resource Write Path Owns

These are stable resource declarations. Their canonical layer is control-plane
storage, even if the extracted worker runtime module owns the service that
validates and writes them:

- `AdapterNodeRecord`
- `WorkerGroupRecord`
- `NodeGroupBindingRecord`
- `Worker` registration row
- worker registration normalization
- capability declaration validation
- resource mutation wakeups

### Worker Runtime Owns

- `WorkerMeta` projection from resource declaration plus runtime evidence
- `WorkerRegistry`
- `WorkerSlot`
- route bucket membership and bounded candidate sampling
- source guard for group/node/route evidence
- source-scoped dispatch gates
- heartbeat freshness and stale candidate rejection
- exclusive worker lease truth
- worker load and occupancy facts
- structured reserve / confirm / release / final occupancy mutation
- task-local warm candidate hint state, as hint only
- candidate source diagnostics
- state report projection
- command-driven worker dispatch gate effects

### Engine Owns

- task shell and lifecycle
- task item runtime enqueue/claim/lease/result convergence
- assignment signal and lane orchestration
- allocation budget
- current match strategy
- prefilter and QLExpress rule evaluation
- rank policy
- resource usage policy around task dispatch binding
- refill policy
- terminal policy
- assignment trace and task lifecycle trace

### Transport Owns

- protocol connection/session evidence
- transport presence store and route ownership
- delivery route
- worker-facing dispatch payload
- result ingest transport
- worker event normalization before owner apply

Transport may call worker-runtime owner APIs. It must not mutate worker runtime
maps/indexes, engine scheduling state, or control-plane resource maps directly.

## Target Contracts

First extraction should converge to narrow contracts before moving files across
modules. Names below are target shapes, not mandatory final Java names.

These contracts must use runtime-neutral DTOs. They must not expose `Task`,
`TaskManager`, `RuleManager`, transport adapter sessions, or engine model
classes.

The signatures below are sketches. WRX-C0 must confirm the exact fields before
WRX-C1 lands any Java interface. In particular, admission must preserve the
current `WorkerRegistry.tryReserve(...)` facts:

```text
groupId
workerId
taskId
permits
nowMillis / clock source for heartbeat freshness checks
```

If a higher-level admission API hides `groupId`, that is a deliberate lookup
choice and must be documented in C0. It must not erase group-scoped slot truth.

```java
interface WorkerResourceRuntime {
    WorkerResourceResult registerAdapterNode(AdapterNodeDeclaration declaration);
    WorkerResourceResult declareWorkerGroup(WorkerGroupDeclaration declaration);
    WorkerResourceResult bindNodeGroup(NodeGroupBindingDeclaration declaration);
    WorkerResourceResult registerWorker(WorkerRegistrationDeclaration declaration);
    WorkerResourceResult updateWorkerAttributes(WorkerAttributeUpdate update);
}

interface WorkerCandidateRuntime {
    WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch(
        WorkerTaskSelector selector,
        int maxCandidateCount
    );
}

interface WorkerSchedulingViewRuntime {
    Optional<WorkerRuntimeSchedulingView> schedulingView(String workerId);
    WorkerRuntimeLoadSnapshot load(String workerId);
    WorkerReachabilityEvidence reachability(String workerId);
}

interface WorkerAdmissionRuntime {
    WorkerAdmissionResult reserve(WorkerAdmissionRequest request);
    WorkerAdmissionResult confirmReservation(WorkerAdmissionRequest request);
    void releaseReservation(WorkerAdmissionRequest request);
    void recordWorkFinal(WorkerAdmissionRequest request);
    WorkerAdmissionResult acquireExclusiveLease(String workerId);
    void releaseExclusiveLease(String workerId);
}

interface WorkerReportRuntime {
    WorkerCapabilityReportResult applyCapabilityReport(WorkerCapabilityReport report);
    WorkerStateProjectionResult applyStateReport(WorkerStateReport report);
    WorkerCommandStatusTransition acknowledgeCommand(WorkerCommandAcknowledgement ack);
}
```

`WorkerAdmissionResult` must be structured, not boolean. It must preserve at
least:

```text
status
workerId / workerGroupId
owner reason: SOURCE_GUARD | STAGE2_POLICY | RESERVE | DISPATCH_GATE | STALE
diagnostic reason
optional slot/load evidence
```

This is required so assignment diagnostics can identify whether rejection came
from source guard, Stage-2 policy, reserve, dispatch bind, or result
convergence.

## Runtime Flow

Target mainline:

```text
Task dispatch signal
  -> engine allocation plan
  -> engine builds WorkerTaskSelector from task/group selector
  -> worker runtime acquires bounded candidate batch
  -> engine prefilter / rule / rank
  -> worker runtime structured atomic reserve
  -> engine task runtime claim
  -> engine dispatch binding
  -> transport delivery
  -> result runtime convergence
  -> worker runtime release/final occupancy
```

Worker runtime must not see task result finality. Engine must not own worker
route bucket internals or slot mutation internals.

## Phase Plan

### WRX-C0: Inventory Current Worker Runtime Truth

Goal: document current owner/caller reality without behavior change.

Deliverable:

```text
xa-mass-engine/doc/roadmap/WORKER_RUNTIME_MODULE_EXTRACTION_INVENTORY.md
```

Keep the inventory outside this roadmap so the roadmap remains the phase plan.
The inventory may be updated by implementation slices and should include the
current call graph table, method owner classification, dependency blockers, and
rename/move candidates.

Scope:

1. Inventory `WorkerManager` public methods by owner category:
   - resource write path
   - group/node binding
   - candidate source
   - warm hints
   - report projection
   - reachability/gate
   - reserve/lease/occupancy
   - diagnostics/query
2. Inventory direct callers from engine, SDK, server, and transport.
3. Mark each call as:
   - engine strategy read
   - engine lifecycle/release write
   - worker resource mutation
   - worker runtime admission mutation
   - transport normalized evidence entry
   - SDK/server public shell entry
   - compatibility residue
4. Classify each target owner by truth layer: control-plane storage, runtime
   state, trace/audit, or temporary residue.
5. Identify methods that cannot move until a runtime-neutral DTO exists.

Acceptance:

1. No behavior change.
2. `WORKER_RUNTIME_MODULE_EXTRACTION_INVENTORY.md` has a current call graph
   table.
3. Every `WorkerManager` public method has one target owner category and one
   truth-layer classification.
4. Existing `mass-runtime-api` worker contracts are listed so no duplicate
   contracts are introduced.
5. `WorkerLookupStore` has an explicit disposition: keep as storage-edge lookup
   seam, absorb into a worker-runtime read contract, or delete in WRX-D1.
6. No new wrappers are introduced.

### WRX-C1: Define Worker Runtime Contracts In Engine

Goal: introduce narrow contracts while implementation still lives in engine.

Scope:

1. Define internal contracts for candidate source, admission, resource writes,
   reports, and scheduling views.
2. Use structured result DTOs for admission and resource/report mutations.
3. Preserve group-scoped admission facts and multi-permit capacity semantics in
   the contract; do not reduce `ReserveResult`-style outcomes to boolean.
4. Decide whether admission callers provide `groupId` directly or whether the
   runtime resolves it from `workerId`, and document the chosen lookup path.
5. Make `WorkerManager` implement those contracts directly.
6. Update engine strategy to depend on the narrow candidate/admission contracts
   where practical.
7. Keep SDK/server/transport call sites stable unless the call site already
   leaks a worker-runtime owner boundary.
8. Resolve the `WorkerLookupStore` overlap identified in C0; do not leave two
   indistinguishable lookup seams.

Acceptance:

1. `RuleBasedTaskWorkerMatchingStrategy` no longer needs the full
   `WorkerManager` surface.
2. Source guard and warm hint still flow through the candidate-source owner.
3. Reserve/release/final still flow through worker admission owner.
4. Admission callers still receive structured reserve failure reasons.
5. Admission signatures keep `groupId`, `permits`, and clock/`nowMillis`
   semantics either explicitly or through a documented runtime-owned lookup.
6. No pass-through bridge class is added.

### WRX-C1.5: Runtime-Neutral DTO Hygiene

Goal: remove engine-only DTO dependencies before module movement.

Scope:

1. Define runtime-neutral equivalents for:
   - task selector input
   - candidate batch
   - candidate row
   - source evidence/result
   - scheduling view
   - load snapshot
   - reachability evidence
   - admission request/result
2. Keep QLExpress context and `WorkerMatchContext` in engine.
3. Keep `Task.sharedConfig` parsing in engine or SDK/intake, not worker runtime.
4. Ensure target contracts do not reference `Task`, `WorkerSchedulingView`,
   `WorkerMatchContext`, `RuleManager`, or transport adapter classes.
5. Promote movable value types that are currently nested inside owner classes
   into top-level classes before module movement.

Acceptance:

1. New runtime-neutral DTOs can compile without `xa-mass-engine`.
2. Engine adapts runtime DTOs into `WorkerMatchContext` locally.
3. `WorkerCandidateBatch`, `WorkerCandidateRow`, and other moved DTOs are
   top-level types before M1.
4. No rule/rank policy moves into runtime DTOs.

### WRX-C2: Split WorkerManager Internals By Owner

Goal: reduce `WorkerManager` from god object to assembly owner.

Scope:

1. Extract package-private owners inside `com.xa.mass.engine.worker`:
   - worker resource owner
   - adapter node / binding owner
   - worker report owner
   - candidate source owner
   - worker admission owner
2. Owners may share current storage contracts, `WorkerRegistry`, and current
   in-memory maps while still inside engine.
3. `WorkerManager` becomes assembly and compatibility surface only during the
   convergence window.
4. Do not move modules yet.
5. Keep extracted owners dependency-clean enough to move later.
6. Keep broad rename-only churn out of this slice unless the rename is required
   to make owner semantics visible for the next move.

Acceptance:

1. Candidate source logic is no longer mixed with report projection code.
2. Registration/binding mutation is no longer mixed with reserve/final mutation.
3. Movable owners do not depend on `TaskManager`, `RuleManager`, engine
   listeners, transport adapter sessions, or SDK models.
4. Engine strategy tests pass without broad changes.
5. Architecture guard prevents strategy from reading worker registry internals.

### WRX-M1: Move Worker Runtime Value Types And Contracts

Goal: create the module boundary without moving heavy behavior first.

Scope:

1. Reuse or extend `platform_infra/mass-runtime-api` for low-level worker
   runtime contracts and value types.
2. Add `xa-mass-worker-runtime` only if the slice needs a higher-level owner
   module above `mass-runtime-api`; otherwise defer the new module until M3.
3. Move or expose runtime-neutral value types and contracts:
   - candidate batch
   - source evidence/result
   - runtime selector
   - scheduling view/load/reachability DTOs
   - admission request/result
   - report result DTOs
4. Keep implementation in engine for this phase.
5. Engine depends on worker-runtime contracts, not implementation details.
6. Move contracts/value types only after C1.5 has made them top-level and
   runtime-neutral.

Acceptance:

1. No new module duplicates `WorkerRegistry`, `WorkerSlot`, or `WorkerMeta`.
2. New contracts have no dependency on `xa-mass-engine`.
3. `xa-mass-engine` compiles against the new contracts.
4. No behavior change.
5. No duplicate runtime truth exists in the new module.
6. Package moves do not introduce temporary compatibility aliases for old nested
   type names.

### WRX-M2: Move WorkerRegistry Implementation Runtime

Goal: move worker slot/index/admission truth out of engine.

Scope:

1. Move `InMemoryWorkerRegistry` out of engine into the runtime implementation
   module selected by M1.
2. Keep Redis implementation under `platform_infra/mass-runtime-redis`.
3. Keep memory and Redis implementations sharing the same contract tests.
4. Keep engine as caller of admission and candidate APIs.
5. Preserve current Redis/memory runtime switch behavior.

Acceptance:

1. Memory and Redis worker registry tests run from shared contract tests.
2. Engine no longer imports worker slot/index implementation classes.
3. Engine still owns match policy and rank strategy.
4. Reserve correctness tests still pass under memory and Redis.
5. No global worker scan is introduced as a migration shortcut.

### WRX-M3: Move Resource / Report / Binding Owners

Goal: move remaining worker owner services without moving stable truth to the
wrong layer.

Scope:

1. Move adapter node, worker group, node-group binding, worker registration,
   capability report, state report, and command gate-effect owners behind
   worker-runtime contracts.
2. Resource owners may write through `mass-storage-api`; their stable truth
   remains control-plane storage.
3. Runtime owners update `WorkerMeta` / `WorkerSlot` projection and source
   indexes after accepted resource mutations.
4. Keep SDK/server/transport entry points calling stable runtime/resource
   contracts.
5. Keep task runtime and dispatch binding in engine.

Acceptance:

1. Transport no longer imports engine worker internals for worker mutation.
2. SDK/server worker registry APIs call worker runtime/resource contracts.
3. WorkerGroup remains capability declaration truth, not a transport or match
   strategy artifact.
4. AdapterNode remains endpoint/runtime-node declaration truth, not a ranking
   policy.
5. Control-plane storage remains the canonical stable resource layer.

### WRX-M4: Engine Strategy Consumes Worker Runtime Views

Goal: engine becomes strategy consumer, not worker runtime owner.

Scope:

1. `RuleBasedTaskWorkerMatchingStrategy` consumes:
   - candidate batch
   - runtime scheduling view
   - structured admission result
2. Rule/rank policy remains in engine.
3. Assignment diagnostics can include worker-runtime evidence, but must not
   mutate worker-runtime truth.
4. Engine adapts runtime scheduling views into rule-evaluation context locally.
5. Warm hint writes cross from engine to worker runtime only after successful
   dispatch binding evidence exists. The runtime owns hint storage and
   revalidation; engine owns the timing decision that a candidate was actually
   useful for assignment.

Acceptance:

1. Engine strategy has no direct access to `WorkerRegistry` internals.
2. Engine strategy cannot mutate worker registration/report/binding state.
3. Worker runtime cannot evaluate QLExpress rules.
4. Existing assignment correctness tests pass.
5. Assignment diagnostics preserve candidate source, source guard, Stage-2,
   reserve, and dispatch-bind rejection ownership.
6. Warm hint put does not require worker runtime to depend on `Task`; the
   boundary uses task id, selector/source evidence, and worker evidence.

### WRX-D1: Delete Engine Worker Runtime Residue

Goal: remove old paths after module move is proven.

Scope:

1. Delete engine-local duplicate worker runtime classes.
2. Delete compatibility methods that only forward to moved owners.
3. Remove stale roadmap/baseline references to engine-owned worker runtime.
4. Update architecture guards.

Acceptance:

1. No two live worker runtime truth paths.
2. No engine-local worker registry implementation residue.
3. No server/transport code imports engine worker internals.
4. Docs say current behavior, not target behavior.

### WRX-D2: Distributed Proof And Runtime Selection

Goal: prove the extracted module works as runtime infrastructure.

Redis and multi-JVM proof are environment-dependent. The default CI unit lane
may run memory/runtime contract proofs only. Redis-backed and multi-JVM proofs
must be explicitly classified as one of:

```text
unit/integration with externally provided Redis
compose profile proof
manual/local verified runbook proof
```

Do not hide Redis requirements behind "where supported"; mark the required
profile, skip condition, and evidence artifact.

Scope:

1. Run memory and Redis worker-runtime contract tests through the same suite.
2. Run engine assignment/match proof against both runtime backends where
   supported by the selected CI/profile environment.
3. Add black-box transport worker proof that registration/report/dispatch/result
   crosses module boundary cleanly.
4. Track metrics:
   - duplicate candidate
   - stale candidate rejection
   - reserve conflict
   - source guard rejection
   - warm/cold candidate count
   - dispatch gate source rejection

Acceptance:

1. Memory and Redis worker-runtime proofs are shared.
2. Multi-JVM / Redis proof does not require engine-local worker state.
3. Failure diagnostics identify whether rejection came from source guard,
   Stage-2 policy, reserve, dispatch bind, or result convergence.
4. Runtime selection does not change SDK/server public worker APIs.
5. The roadmap or inventory names the CI/profile/runbook command for Redis and
   multi-JVM proof, including skip behavior when Redis is unavailable.

## Architecture Guards

Add or update guards as phases land:

1. Engine strategy must not import worker registry implementation classes.
2. Worker runtime module must not import `TaskManager`, `TaskWorkRuntime`, or
   `TaskResultRuntime`.
3. Worker runtime must not evaluate QLExpress rules.
4. Transport must not mutate worker runtime maps/indexes directly.
5. Candidate source must not scan all workers.
6. Candidate source must start from resolved WorkerGroup selector.
7. Worker runtime may expose source evidence, but warm hint state must not be
   eligibility truth.
8. Engine must call worker admission API for reserve/release/final instead of
   mutating slots directly.
9. No module may define a second `WorkerRegistry` / `WorkerSlot` contract while
   `mass-runtime-api` owns the current one.
10. Worker runtime contracts must not expose engine `Task`, `WorkerMatchContext`,
    or QLExpress rule types.
11. Transport presence classes must not become worker scheduling models.
12. Control-plane worker storage must not expose scheduling shortcut APIs such
    as all-worker candidate scans.

## Test Strategy

Contract tests:

- `WorkerLookupStore` disposition or replacement seam, if it still exists after
  C1
- worker resource declaration lifecycle
- runtime projection after resource mutation
- node/group binding lifecycle
- capability report ceiling validation
- state report to dispatch gate
- candidate source bounded acquisition
- source guard stale rejection
- reserve / confirm / release / final occupancy
- exclusive worker lease
- warm hint revalidation
- structured admission failure reason mapping

Engine integration tests:

- Task -> WorkerGroup selector -> candidate batch -> Stage-2 -> reserve
- no worker / late worker join wakeup
- node-group drain exclusion
- multi-task worker contention
- targetWorkerId group-scoped lookup
- admission failure diagnostics preserved through assignment records

Transport black-box tests:

- polling worker registration/report/result
- websocket worker registration/report/result
- command ack and DRAIN gate behavior
- adapter-node scoped registration with group binding
- transport presence evidence consumed without becoming scheduling truth

Distributed proof:

- memory runtime remains deterministic unit/integration proof
- Redis runtime validates multi-client reserve and stale cleanup
- compose proof may validate multi-JVM behavior, but CLI/HTTP proof is preferred
  over browser automation

## Risks

### Risk 1: Module Split Creates Pass-Through Noise

Mitigation:

- define owner contracts first
- do not add facade/bridge classes that only forward
- delete old paths after each move
- reject a new module if it only re-exports `WorkerManager`

### Risk 2: Worker Runtime Starts Owning Strategy

Mitigation:

- worker runtime returns bounded candidate batches and structured admission
  results
- engine keeps prefilter/rule/rank/allocation policy
- architecture guard blocks rule evaluation in worker runtime

### Risk 3: Engine Keeps Hidden Worker Runtime State

Mitigation:

- move registry/index/admission owners together
- guard engine imports
- run memory/Redis shared contract tests

### Risk 4: Transport Recreates Worker Session Truth

Mitigation:

- transport connection/session evidence stays transport-local
- worker runtime receives normalized resource/report/presence evidence through
  owner APIs
- no `WorkerSession` engine/runtime model in this extraction

### Risk 5: Redis Runtime Becomes Over-Lua

Mitigation:

- Lua only protects small multi-key atomic mutations
- selection, ranking, and policy stay in Java
- stale and duplicate candidates are allowed before Stage-2 admission

### Risk 6: Control-Plane Resource Truth Is Mistaken For Runtime Truth

Mitigation:

- resource declarations write through storage contracts
- runtime projections are derived and may be rebuilt
- candidate source and reserve must use runtime projection, not DB-style scans

## Recommended First Slice

Start with:

```text
WRX-C0 + WRX-C1
```

Do not create the new module first.

First prove the current worker surface can be consumed through narrow,
structured contracts. Then do DTO hygiene. Only then move value types and
implementations. This keeps each phase independently testable and avoids a
long-lived old/new split.
