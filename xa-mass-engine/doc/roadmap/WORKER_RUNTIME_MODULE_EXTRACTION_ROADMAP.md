# Worker Runtime Module Extraction Roadmap

Status: draft. This roadmap is a module-boundary convergence plan. It must not
be read as proof that the module already exists.

## Summary

Worker has grown from a small engine helper into an independent runtime plane:

```text
AdapterNode / WorkerGroup / NodeGroupBinding / Worker
  -> capability and state reports
  -> reachability and dispatch gates
  -> route buckets and bounded candidate sampling
  -> WorkerSlot reserve / confirm / release / final occupancy
  -> candidate source and warm candidate hints
```

Keeping all of this inside `xa-mass-engine` makes the engine look like both the
task scheduling strategy owner and the worker runtime owner. That boundary is
now too blurry.

Target direction:

```text
xa-mass-worker-runtime
  owns worker runtime truth, worker indexes, worker state projection,
  candidate source views, and atomic worker admission

xa-mass-engine
  owns task lifecycle, assignment trigger, matching strategy, rule/rank policy,
  allocation/refill, dispatch binding, and result convergence
```

The goal is not to create a pass-through facade. The goal is to make the
existing owner split explicit and move worker runtime truth behind narrow,
stable contracts.

## Naming

Preferred module name:

```text
xa-mass-worker-runtime
```

Avoid `worker-management` as the module name for the first extraction. This is
not a CRUD / DB management module. It is high-frequency runtime state and
admission truth. Operator-facing management APIs may be built later on top of
runtime views and trace/query projections.

## Core Rule

Worker runtime may provide readable views to engine, but readable views are not
enough.

Stage-2 matching still needs an atomic admission operation. Therefore the worker
runtime module must provide both:

```text
read:
  candidate batches
  WorkerSchedulingView-like snapshots
  relation/capability/reachability evidence

write:
  reserve / confirm / release / final occupancy mutation
  source-scoped dispatch gate mutation
  worker registration and report mutation
```

Engine strategy may decide which candidates to try, but worker runtime must own
whether a worker can be atomically admitted now.

## Non-Goals

This roadmap explicitly does not do:

1. Do not move task lifecycle, assignment lanes, result convergence, or dispatch
   binding into worker runtime.
2. Do not let worker runtime decide the best worker for a task.
3. Do not introduce a generic plugin marketplace or policy registry.
4. Do not add DB-backed worker CRUD as runtime truth.
5. Do not preserve old and new worker paths as two live tracks.
6. Do not create same-module bridge/facade wrappers that only forward calls.
7. Do not introduce `WorkerSession`, `Device`, or `AccountSlot` engine models.
8. Do not make transport connection/session evidence scheduling truth.
9. Do not make warm-pool state, eventCode, or arbitrary attributes candidate
   truth.

## Target Ownership

### Worker Runtime Owns

- `AdapterNodeRecord`
- `WorkerGroupRecord`
- `NodeGroupBindingRecord`
- worker registration row / meta normalization
- `WorkerRegistry`
- `WorkerSlot`
- route bucket membership and candidate sampling
- source guard for group/node/route evidence
- reachability evidence view
- source-scoped dispatch gates
- exclusive worker lease truth
- capability report projection
- state report projection
- command-driven worker dispatch gate effects
- task-local warm candidate hint state, as hint only
- candidate source diagnostics

### Engine Owns

- task shell and lifecycle
- task item runtime enqueue/claim/lease/result convergence
- assignment signal and lane orchestration
- allocation budget
- current match strategy
- prefilter and QLExpress rule evaluation
- rank policy
- resource usage policy
- runtime claim and dispatch binding
- refill policy
- terminal policy
- assignment trace and task lifecycle trace

### Transport Owns

- protocol connection/session evidence
- delivery route
- worker-facing dispatch payload
- result ingest transport
- worker event normalization before owner apply

Transport must call worker-runtime owner APIs. It must not mutate worker
runtime indexes or engine scheduling state directly.

## Target Contracts

First extraction should converge to these contracts before moving files across
modules.

```java
interface WorkerRegistrationRuntime {
    void registerAdapterNode(...);
    void declareWorkerGroup(...);
    void bindNodeGroup(...);
    void registerWorker(...);
    void updateWorkerAttributes(...);
}

interface WorkerCandidateRuntime {
    WorkerCandidateBatch acquireCandidates(TaskWorkerSelector selector, int max);
    SourceGuardResult sourceGuard(WorkerSourceEvidence evidence);
}

interface WorkerSchedulingViewRuntime {
    Optional<WorkerSchedulingView> schedulingView(String workerId);
    WorkerLoadSnapshot load(String workerId);
    WorkerReachabilityState reachability(String workerId);
}

interface WorkerAdmissionRuntime {
    boolean tryReserve(String workerId, String taskId);
    boolean confirmReservation(String workerId, String taskId);
    void releaseReservation(String workerId, String taskId);
    void recordWorkFinal(String workerId, String taskId);
    boolean tryAcquireExclusiveLease(String workerId);
    void releaseExclusiveLease(String workerId);
}

interface WorkerReportRuntime {
    WorkerCapabilityReportResult applyCapabilityReport(...);
    WorkerStateProjectionResult applyStateReport(...);
    WorkerCommandStatusTransition acknowledgeCommand(...);
}
```

These names are target shapes, not mandatory final Java names. The important
part is the owner boundary.

## Runtime Flow

Target mainline:

```text
Task dispatch signal
  -> engine allocation plan
  -> engine builds TaskWorkerSelector
  -> worker runtime acquires bounded candidate batch
  -> engine prefilter / rule / rank
  -> worker runtime atomic reserve
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

Scope:

1. Inventory `WorkerManager` methods by owner category:
   - registration
   - group/node binding
   - candidate source
   - warm hints
   - report projection
   - reachability/gate
   - reserve/lease/occupancy
   - diagnostics/query
2. Inventory direct callers from engine, SDK, server, and transport.
3. Mark which calls are engine strategy reads, worker runtime writes, or
   transport mutation entry points.
4. Identify methods that are compatibility residue only.

Acceptance:

1. No behavior change.
2. Roadmap has a current call graph table.
3. Every `WorkerManager` public method has one target owner category.
4. No new wrappers are introduced.

### WRX-C1: Define Worker Runtime Contracts In Engine

Goal: introduce narrow contracts while implementation still lives in engine.

Scope:

1. Define internal contracts for candidate source, admission, registration,
   reports, and scheduling views.
2. Make `WorkerManager` implement those contracts directly.
3. Update engine strategy to depend on the narrow candidate/admission contracts
   where practical.
4. Keep SDK/server/transport call sites stable unless the call site already
   leaks a worker-runtime owner boundary.

Acceptance:

1. `RuleBasedTaskWorkerMatchingStrategy` no longer needs the full
   `WorkerManager` surface.
2. Source guard and warm hint still flow through the candidate-source owner.
3. Reserve/release/final still flow through worker admission owner.
4. No pass-through bridge class is added.

### WRX-C2: Split WorkerManager Internals By Owner

Goal: reduce `WorkerManager` from god object to assembly owner.

Scope:

1. Extract package-private owners inside `com.xa.mass.engine.worker`:
   - worker registration owner
   - adapter node / binding owner
   - worker report owner
   - candidate source owner
   - worker admission owner
2. Owners may share `WorkerRegistry` and current in-memory control maps.
3. `WorkerManager` becomes assembly and compatibility surface only during the
   convergence window.
4. Do not move modules yet.

Acceptance:

1. Candidate source logic is no longer mixed with report projection code.
2. Registration/binding mutation is no longer mixed with reserve/final mutation.
3. Engine strategy tests pass without broad changes.
4. Architecture guard prevents strategy from reading worker registry internals.

### WRX-M1: Move Worker Runtime Value Types And Contracts

Goal: create the module boundary without moving heavy behavior first.

Scope:

1. Add `xa-mass-worker-runtime`.
2. Move or expose runtime-neutral value types and contracts:
   - candidate batch
   - source evidence/result
   - runtime selector
   - admission contract
   - report contract
3. Keep implementation in engine for this phase.
4. Engine depends on worker-runtime contracts, not implementation details.

Acceptance:

1. New module has no dependency on `xa-mass-engine`.
2. `xa-mass-engine` compiles against the new contracts.
3. No behavior change.
4. No duplicate runtime truth exists in the new module.

### WRX-M2: Move WorkerRegistry Implementation Runtime

Goal: move worker slot/index/admission truth out of engine.

Scope:

1. Move `WorkerRegistry` implementation owners to worker-runtime module when
   their dependencies are clean.
2. Keep memory and Redis implementations sharing the same contract tests.
3. Keep engine as caller of admission and candidate APIs.
4. Preserve current Redis/memory runtime switch behavior.

Acceptance:

1. Memory and Redis worker registry tests run from shared contract tests.
2. Engine no longer imports worker slot/index implementation classes.
3. Engine still owns match policy and rank strategy.
4. Reserve correctness tests still pass under memory and Redis.

### WRX-M3: Move Registration / Report / Binding Owners

Goal: move remaining worker runtime mutation owners.

Scope:

1. Move adapter node, worker group, node-group binding, worker registration,
   capability report, state report, and command gate effects to worker-runtime.
2. Keep SDK/server/transport entry points calling stable runtime contracts.
3. Keep task runtime and dispatch binding in engine.

Acceptance:

1. Transport no longer imports engine worker internals for worker mutation.
2. SDK/server worker registry APIs call worker-runtime contracts.
3. WorkerGroup remains capability truth.
4. AdapterNode remains endpoint/runtime-node truth, not scheduling truth.

### WRX-M4: Engine Strategy Consumes Worker Runtime Views

Goal: engine becomes strategy consumer, not worker runtime owner.

Scope:

1. `RuleBasedTaskWorkerMatchingStrategy` consumes:
   - candidate batch
   - scheduling view
   - admission result
2. Rule/rank policy remains in engine.
3. Assignment diagnostics can include worker-runtime evidence, but must not
   mutate worker-runtime truth.

Acceptance:

1. Engine strategy has no direct access to `WorkerRegistry` internals.
2. Engine strategy cannot mutate worker registration/report/binding state.
3. Worker runtime cannot evaluate QLExpress rules.
4. Existing assignment correctness tests pass.

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

Scope:

1. Run memory and Redis worker-runtime contract tests through the same suite.
2. Run engine assignment/match proof against both runtime backends where
   supported.
3. Add black-box transport worker proof that registration/report/dispatch/result
   crosses module boundary cleanly.
4. Track metrics:
   - duplicate candidate
   - stale candidate rejection
   - reserve conflict
   - source guard rejection
   - warm/cold candidate count

Acceptance:

1. Memory and Redis worker-runtime proofs are shared.
2. Multi-JVM / Redis proof does not require engine-local worker state.
3. Failure diagnostics identify whether rejection came from source guard,
   Stage-2 policy, reserve, dispatch bind, or result convergence.

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

## Test Strategy

Contract tests:

- worker registration lifecycle
- node/group binding lifecycle
- capability report ceiling validation
- state report to dispatch gate
- candidate source bounded acquisition
- source guard stale rejection
- reserve / confirm / release / final occupancy
- exclusive worker lease
- warm hint revalidation

Engine integration tests:

- Task -> WorkerGroup selector -> candidate batch -> Stage-2 -> reserve
- no worker / late worker join wakeup
- node-group drain exclusion
- multi-task worker contention
- targetWorkerId group-scoped lookup

Transport black-box tests:

- polling worker registration/report/result
- websocket worker registration/report/result
- command ack and DRAIN gate behavior
- adapter-node scoped registration with group binding

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

### Risk 2: Worker Runtime Starts Owning Strategy

Mitigation:

- worker runtime returns bounded candidate batches and admission results
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
- worker runtime receives normalized registration/report/presence events
- no `WorkerSession` engine/runtime model in this extraction

### Risk 5: Redis Runtime Becomes Over-Lua

Mitigation:

- Lua only protects small multi-key atomic mutations
- selection, ranking, and policy stay in Java
- stale and duplicate candidates are allowed before Stage-2 admission

## Recommended First Slice

Start with:

```text
WRX-C0 + WRX-C1
```

Do not create the new module first.

First prove the current worker surface can be consumed through narrow contracts.
Only then move value types and implementations. This keeps each phase
independently testable and avoids a long-lived old/new split.
