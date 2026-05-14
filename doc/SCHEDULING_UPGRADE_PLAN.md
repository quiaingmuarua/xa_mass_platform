# Scheduling Upgrade Plan

**Scope:** engine scheduling capability upgrade across six phases.  
**Goal:** remove WorkerContext model complexity, establish foreground/background task scheduling
as first-class concepts, and build a load-aware worker matching and priority dispatch system.  
**Proof surface:** `EngineSchedulingCoreSuite`, `ServerSchedulingE2eSuite`,
and `xa-mass-trace` scenario analyzers at each phase.

---

## Background: Why This Sequence

The scheduling upgrade has two distinct layers:

**Layer 1 — Model cleanup (Phase 0)**  
`WorkerContext` was designed as a "credential/account slot" model but its actual usage
is: routing tags and attributes used only for matching, plus an `IDLE→RESERVED→OCCUPIED`
state machine that duplicates what `tryLockWorker` already provides. The null-context path
is first-class in matching code, and every test registers exactly one context per worker.
Removing it before adding scheduling capability reduces the surface the new code must reason about.

**Layer 2 — Scheduling upgrade (Phases 1–5)**  
Built on the simplified model. `WorkerLoadView` provides the pre-aggregated load snapshot
that makes load-aware matching feasible without N×M runtime queries. Candidate ranking,
priority lanes, capacity reservation, and cross-task fairness follow in order.

---

## Current State: What Is Thin or Broken

| Issue | Location |
|---|---|
| `WorkerContext` state machine duplicates `tryLockWorker`; null-context is first-class | `RuleBasedTaskWorkerMatchingStrategy`, `WorkerContext` |
| Matching iterates `(worker, context)` pairs with dual null/non-null path | `matchWorkers()` |
| No foreground/background concept: all tasks exclusively lock the worker | `TaskWorkerAssignListener`, `WorkerManager` |
| Worker account capabilities are statically pre-registered, not worker-reported | `WorkerContext` registration API |
| Account switch has no protocol: no dispatch instruction, no distinguishable failure code | Transport dispatch payload |
| `DispatchPriority.HIGH/NORMAL` declared, zero runtime effect (FIFO within lane) | `TaskAssignWorker.LaneState` |
| Candidate iteration is storage order; no scoring, no load awareness | `RuleBasedTaskWorkerMatchingStrategy` |
| Worker load is not a runtime first-class concept; any load query in hot path = N×M | `WorkerMatchContext` |
| `tryLockWorker` is binary; no capacity dimension for shared/background tasks | `WorkerManager` |
| `TaskScheduler` SPI is explicit dead code (javadoc says so) | `TaskScheduler`, `SimpleTaskScheduler` |
| Large BULK tasks can exhaust worker pool and starve INTERACTIVE tasks | `TaskWorkerAssignListener` |

---

## Phase 0 — WorkerContext Retirement + Foreground/Background Task Model

**Goal:** remove `WorkerContext` as a model, simplify the matching path to a single branch,
establish `foreground` (exclusive) and background (shared) as the first-class scheduling
dimension that replaces the `WorkerContextStatus` lifecycle.

### What changes in the Worker model

Account and routing capabilities move from `WorkerContext` to `Worker.attributes`.
Workers report capabilities at registration and refresh via heartbeat — the engine no
longer requires operator-managed context CRUD.

`Worker` gets one new field:

```
maxConcurrentWork: int   // default 1 (exclusive-only, backward compatible)
                         // >1 enables background/shared task scheduling
```

`workerContextAttributes` as a QLExpress rule variable is replaced by `workerAttributes`
(already present in `WorkerMatchContext`). Existing rules that reference
`workerContextAttributes['key']` update to `workerAttributes['key']`.

### What changes in the Task model

`ExecutionSpec` gets one new field:

```
foreground: boolean   // default true (exclusive lock, backward compatible)
                      // false = background/shared, worker may handle concurrently
```

Semantics:
- `foreground=true`: task requires exclusive use of the worker for its duration.
  Uses existing `tryLockWorker` mechanism. Worker with `maxConcurrentWork=1` supports only this.
- `foreground=false`: task shares the worker with other background tasks.
  Uses capacity counting (Phase 1 `WorkerLoadView`). Worker must declare `maxConcurrentWork > 1`.

### Account switching via dispatch payload

When a task's matching requires a specific account (resolved from `targetWorkerAttributes`
or `routingCode` matching), the dispatch payload carries an optional field:

```
dispatchInstruction.targetAccount: String   // null if no account switch needed
```

Worker receives the dispatch, attempts account switch if `targetAccount` is set.

If switch fails: worker returns a normal item result with a specific failure reason:

```
failureReason = "ACCOUNT_SWITCH_FAILED"
```

Engine processes this as a standard item failure through the existing result convergence
path. No new protocol, no new message type, no ack handshake. Retry policy applies as
normal; if the account is persistently unavailable, `maxRetryCount` exhaustion produces
`ALL_MESSAGES_FAILED` with `finalReason=RETRY_EXHAUSTED` — distinguishable in trace via
`failureReason=ACCOUNT_SWITCH_FAILED` on the individual item.

`ACCOUNT_SWITCH_FAILED` is added to the result reason vocabulary so `xa-mass-trace`
analyzers can distinguish account failures from execution failures.

### Deleted

| Artifact | Notes |
|---|---|
| `WorkerContext.java` | Entire model |
| `WorkerContextStatus.java` | Entire enum + state machine |
| `WorkerContextFixture`, `WorkerContextSnapshot` | Test/diagnostic support types |
| `workerContextsByWorkerIds()` storage API | Replaced by worker attribute lookup |
| `ExternalWorkerContextRegisterApiRequest` | Context registration endpoint |
| `/api/v1/workers/{workerId}/contexts` endpoint | Context CRUD API |
| All `workerContext*` variables in `WorkerMatchContext` | ~15 variables removed |
| Null-context iteration path in `matchWorkers()` | Single path remains |

### Modified

| File | Change |
|---|---|
| `Worker` | Add `maxConcurrentWork: int` (default 1) |
| `ExecutionSpec` | Add `foreground: boolean` (default true) |
| `WorkerMatchContext` | Remove all `workerContext*` fields; context map shrinks from ~35 to ~20 variables |
| `RuleBasedTaskWorkerMatchingStrategy` | Remove `List<WorkerContext>` per-worker iteration; single `Worker` path |
| `prefilterCandidate()` | Remove context-related checks (`isAllocatable`, routing tag check, context project check) |
| `TaskWorkerAssignListener` | `unlockWorkers()` logic unchanged; foreground flag drives lock vs count |
| Transport dispatch payload | Add optional `dispatchInstruction.targetAccount` field |
| Failure reason vocabulary | Add `ACCOUNT_SWITCH_FAILED` |
| `WorkerStorage` API | Remove context storage methods |
| `WorkerApiController` | Remove context endpoints |

### Out of scope

- No change to `tryLockWorker` mechanics (Phase 0 keeps lock semantics for foreground tasks).
- No WorkerLoadView yet (Phase 1).
- `maxConcurrentWork > 1` is declared but not yet enforced in dispatch (Phase 1 enforces it).
- Dynamic attribute refresh via heartbeat: transport heartbeat already delivers worker events;
  wiring attribute updates through that path is additive and can follow independently.

### Verification

- Compile: `./mvnw -DskipTests compile` — reactor-wide
- ArchUnit: no remaining references to deleted types
- Engine suite: `EngineSchedulingCoreSuite` green
- Server E2E: `ServerSchedulingE2eSuite` green — especially `TaskApiWorkerContextAttributeRoutingIntegrationTest`
  (rule updated from `workerContextAttributes['country']` to `workerAttributes['country']`)
- New unit test: `WorkerMatchContextSimplificationTest` — verify context map has no `workerContext*` keys

---

## Phase 1 — WorkerLoadView Foundation

**Goal:** establish a push-updated, pre-aggregated worker load snapshot service.
No live runtime queries during matching. Enforce `maxConcurrentWork` for background tasks.

### Why this must not be a hot-path query

```
matchWorkers() called once per assignment cycle.
For each task: iterate M worker candidates.
If load = live runtime query: N tasks × M workers = N×M queries per second.
At scale this saturates the runtime layer.
```

The pattern is identical to `WorkerReachabilityView`: transport events push state into an
engine-internal snapshot; matching reads snapshot at O(1) per worker.

### New types

```java
// xa-mass-engine
interface WorkerLoadView {
    int getActiveCount(String workerId);       // active leases (foreground or background)
    int getReservedCount(String workerId);     // optimistic reservations (Phase 4)
    double getLoadRatio(String workerId);      // (active + reserved) / maxConcurrentWork
    boolean isAtCapacity(String workerId);     // (active + reserved) >= maxConcurrentWork

    void recordWorkClaimed(String workerId, String taskId, boolean foreground);
    void recordWorkFinal(String workerId, String taskId);
}

// default impl
class InMemoryWorkerLoadView implements WorkerLoadView {
    // ConcurrentHashMap<workerId, AtomicInteger> activeCounts
    // ConcurrentHashMap<workerId, Integer> declaredCapacity  (from Worker.maxConcurrentWork)
    // fully thread-safe; stale by at most one assignment cycle — acceptable
}
```

### Foreground vs background in load tracking

- `foreground=true` task claimed: marks worker as at-capacity (activeCount = maxConcurrentWork)
  — effectively the same as a full lock; `tryLockWorker` remains the serialization guard
- `foreground=false` task claimed: increments activeCount by 1;
  worker remains dispatchable until `activeCount >= maxConcurrentWork`

`isAtCapacity(workerId)` replaces the role of `isLocked(workerId)` as the primary
dispatch eligibility check in `prefilterCandidate`. `tryLockWorker` is retained for
foreground serialization within a single assignment cycle (prevents double-dispatch).

### Modified

| File | Change |
|---|---|
| `WorkerManager` | Inject `WorkerLoadView`; expose `isWorkerAtCapacity(workerId)` |
| `WorkerMatchContext` | Add `activeLeaseCount`, `loadRatio`, `isAtCapacity` to context map |
| `prefilterCandidate()` | Add `isAtCapacity` check after reachability check |
| `TaskDispatchBinder` impl | Call `workerLoadView.recordWorkClaimed(workerId, taskId, foreground)` after claim |
| `TaskResultService` / result convergence | Call `workerLoadView.recordWorkFinal(workerId, taskId)` on work terminal |
| `EngineConfig` / `MassEngineBuilder` | Wire `InMemoryWorkerLoadView` as default; injectable |

### Out of scope

- Capacity reservation (Phase 4).
- No change to `tryLockWorker` role.
- Redis-backed `WorkerLoadView` for multi-JVM (future; same interface, different impl).

### Verification

- Unit: `InMemoryWorkerLoadViewTest` — concurrent claim/final; foreground capacity semantics; thread-safety
- Unit: `WorkerMatchContextLoadTest` — `isAtCapacity` variable present and correct
- Engine suite: `EngineSchedulingCoreSuite` green with zero behavioral change
- New integration: `TaskApiBackgroundTaskSharingIntegrationTest` — two background tasks dispatched to
  same worker (maxConcurrentWork=2); both complete without blocking each other

---

## Phase 2 — DispatchPriority Within Lanes

**Goal:** make `TaskRuntimeProfile.DispatchPriority.HIGH/NORMAL` have real runtime effect
inside each lane's queue. Currently declared in the type system but FIFO in practice.

### What is wrong now

`TaskAssignWorker.LaneState` uses `LinkedBlockingQueue<TaskAssignmentSignal>` — plain FIFO.
An `INTERACTIVE/HIGH` task submitted after a wave of `INTERACTIVE/NORMAL` tasks waits behind
all of them. `DispatchPriority` exists as types but does nothing.

### Changes

| File | Change |
|---|---|
| `TaskAssignmentSignal` (record) | Add `int priorityOrdinal` (from `DispatchPriority.ordinal()`, lower = higher priority) |
| `TaskAssignWorker.LaneState` | Replace `LinkedBlockingQueue` with `PriorityBlockingQueue` ordered by `priorityOrdinal ASC` |
| `TaskAssignWorker.submit()` | Resolve `TaskRuntimeProfile` at submit time; stamp signal with priority ordinal |

### Invariants

- Lane separation (INTERACTIVE vs BULK) unchanged; priority ordering is strictly within-lane.
- Deduplication (`trackedTaskIds`) and deferred requeue logic unchanged.
- `DispatchLane` = primary separation; `DispatchPriority` = secondary within-lane ordering.

### Out of scope

- Cross-lane preemption (BULK never preempts INTERACTIVE).
- User-defined task priority beyond `workloadClass` mapping.

### Verification

- Unit: `TaskAssignWorkerPriorityTest` — submit HIGH and NORMAL signals to same lane; verify HIGH drains first
- Engine suite: `EngineSchedulingCoreSuite` green
- Trace: new scenario `priority-lane-ordering`

---

## Phase 3 — WorkerCandidateRanker: Load-Aware Scoring

**Goal:** after eligibility prefilter, rank candidates by composite score before lock
acquisition. Replaces first-in-storage-order selection with best-score-first.

### What is wrong now

`matchWorkers()` iterates candidates in storage order. Two workers that both pass prefilter
are treated identically; the first encountered wins every time. Under steady state the same
workers are always selected first, creating systematic load imbalance.

### New SPI

```java
interface WorkerCandidateRanker {
    // Returns same candidates in preference order. Must not mutate input.
    List<WorkerMatchContext> rank(List<WorkerMatchContext> candidates, Task task);
}
```

### Default implementation: `DefaultWorkerCandidateRanker`

Composite score (lower = better candidate):

```
score = 0.6 × loadRatio             // from WorkerLoadView via WorkerMatchContext
      + 0.3 × (1 - affinityScore)   // routingTag exact-match=1.0, partial=0.5, none=0.0
      + 0.1 × availabilityPenalty   // isAtCapacity=∞ (filtered), else 0
```

Weights configurable via system properties. Rules remain the eligibility gate;
ranker adds quality above the eligibility threshold.

### Modified

| File | Change |
|---|---|
| `RuleBasedTaskWorkerMatchingStrategy` | Build `WorkerMatchContext` for all prefilter-passed candidates → call `ranker.rank()` → iterate ranked list for lock acquisition |
| `EngineConfig` / `MassEngineBuilder` | Wire `DefaultWorkerCandidateRanker` as default; injectable |

### Lock acquisition after ranking

```
for candidate in ranker.rank(prefilterPassed, task):
    if matched.size() >= maxWorkerCount: break
    if tryLockWorker(candidate.workerId):
        matched.add(candidate)
    // lock conflict = concurrent assignment won the race; continue to next candidate
```

### Out of scope

- External scoring plugins.
- Worker performance history (future: feed completion latency into score).

### Verification

- Unit: `DefaultWorkerCandidateRankerTest` — high-load worker ranks below low-load; affinity tie-break
- Unit: `RuleBasedTaskWorkerMatchingStrategyTest` — load-differentiated pool; lower-load worker selected
- Server E2E: `TaskApiWorkerContextAttributeRoutingIntegrationTest` green (routing behavior preserved)
- Trace: new scenario `load-aware-worker-selection`

---

## Phase 4 — Optimistic Capacity Reservation

**Goal:** prevent the same worker from being over-committed across concurrent assignment
waves. Bridge the window between "match committed" and "runtime claim confirmed".

### What is wrong now

`tryLockWorker` serializes dispatch within one assignment cycle but is released after
binding. Between two concurrent assignment cycles, both can see the same worker as
low-load and select it before either claim confirms. `WorkerLoadView.activeCount` (Phase 1)
tracks confirmed leases but not in-flight reservations.

### Changes

```java
// WorkerLoadView additions
boolean tryReserveCapacity(String workerId);   // atomic: if (active + reserved) < max → reserved++; return true
void confirmReservation(String workerId);       // claim confirmed: reserved--, active++
void releaseReservation(String workerId);       // dispatch failed: reserved--
```

For foreground tasks: `tryReserveCapacity` sets `reserved = maxConcurrentWork` (full capacity claim).  
For background tasks: `tryReserveCapacity` increments by 1.

| File | Change |
|---|---|
| `InMemoryWorkerLoadView` | Add per-worker `AtomicInteger reservedCount`; implement three reservation methods |
| `RuleBasedTaskWorkerMatchingStrategy` | Replace `tryLockWorker` as capacity guard with `workerLoadView.tryReserveCapacity(workerId)`; `tryLockWorker` retained for intra-cycle serialization only |
| `TaskWorkerAssignListener` | On dispatch failure: call `releaseReservation` for all dispatch candidates |
| `TaskDispatchBinder` impl | On successful claim: call `confirmReservation(workerId)` |

### Invariants

`tryLockWorker` is NOT removed. It still prevents the same worker from being selected
twice within a single `matchWorkers()` call. `tryReserveCapacity` is the cross-cycle guard.
They operate at different scopes and are complementary.

### Verification

- Unit: `WorkerLoadViewReservationTest` — concurrent `tryReserveCapacity`; capacity limit respected under contention
- Engine suite: `TaskSchedulingContentionTest`, `TaskRedispatchCompetitionTest` extended with multi-wave concurrent scenarios
- Trace: new scenario `capacity-reservation-under-concurrency`

---

## Phase 5 — TaskScheduler SPI Retirement

**Goal:** remove documented dead code that misleads readers about dispatch architecture.

### What is wrong now

`TaskScheduler` interface declares `scheduleTask`, `scheduleTasks`, `cancelTask`, `pauseTask`,
`resumeTask`. Its own javadoc states:

> "These methods are advisory hooks — the mainline dispatch loop in `TaskAssignWorker` does
> not call them directly. They are reserved for future integration with external scheduling systems."

`SimpleTaskScheduler` logs and returns. Wired via `MassEngineBuilder` but zero effect.

If external scheduler integration is ever needed, the correct seam is a new event source
that calls `TaskAssignWorker.submit()` — not a parallel dispatch path bypassing lane queues
and contention guards.

### Changes

| Action | Files |
|---|---|
| Delete | `TaskScheduler.java`, `SimpleTaskScheduler.java` |
| Remove wiring | `MassEngineBuilder.scheduler()` method and field |

### Verification

- Compile: reactor-wide `./mvnw -DskipTests compile`
- ArchUnit: no remaining references to deleted types
- Engine suite: full `EngineSchedulingCoreSuite` — behavioral baseline unchanged

---

## Phase 6 — Cross-Task Worker Fairness and Budget

**Goal:** prevent large BULK tasks from exhausting the worker pool and starving concurrent
INTERACTIVE tasks. Introduce per-task worker budget as a first-class `ExecutionSpec` concept.

### What is wrong now

`getDesiredDispatchWorkerCount(task, readyWorkCount) = ceil(readyWork / batchSize)` is
task-local. A BULK task with 10,000 ready items and batchSize=1 requests 10,000 workers,
claiming the entire available pool before any INTERACTIVE task gets scheduled.

`foreground` (Phase 0) establishes the exclusive/shared distinction but does not cap how
many workers a single task can claim.

### New concept: `WorkerBudgetPolicy`

```java
// ExecutionSpec addition
int maxConcurrentWorkers   // 0 = use policy default

// engine-internal
class WorkerBudgetPolicy {
    int resolveMaxConcurrentWorkers(Task task, int desiredCount);
    // INTERACTIVE: min(desired, interactiveMaxConcurrent)   default: 5
    // BULK:        min(desired, bulkMaxConcurrent)          default: 20
    // task.executionSpec.maxConcurrentWorkers overrides policy default if > 0
}
```

### Cross-task worker count tracking

`WorkerLoadView.recordWorkClaimed(workerId, taskId, foreground)` already takes `taskId`.
Add:

```java
int getActiveWorkerCountForTask(String taskId);
```

`TaskWorkerAssignListener.getDesiredDispatchWorkerCount()` caps the request to:
`min(desired, budget - currentTaskWorkerCount)`.

### Foreground task budget

A foreground task with `maxConcurrentWorkers=1` is the natural default — one foreground
task occupies one worker. INTERACTIVE foreground tasks with small batches stay within their
budget by default. Budget policy primarily constrains BULK tasks.

### Modified

| File | Change |
|---|---|
| `ExecutionSpec` | Add `maxConcurrentWorkers: int` (default 0 = use policy) |
| `TaskWorkerAssignListener` | Apply `WorkerBudgetPolicy` ceiling in `getDesiredDispatchWorkerCount()` |
| `WorkerLoadView` | Add `getActiveWorkerCountForTask(String taskId)` |
| `InMemoryWorkerLoadView` | Track per-task active worker count alongside per-worker counts |
| `MassEngineBuilder` | Expose `WorkerBudgetPolicy` injection |

### Verification

- Unit: `WorkerBudgetPolicyTest` — BULK task with large ready count is capped; INTERACTIVE task dispatched concurrently
- Engine suite: `TaskWorkerContextContentionTest` extended with mixed INTERACTIVE/BULK concurrent scenario
- Chaos probe: new `worker-starvation` probe — INTERACTIVE task must complete within latency bound regardless of concurrent BULK queue depth
- Trace: new scenario `cross-task-worker-fairness`

---

## Phase Dependency Graph

```
Phase 0  WorkerContext Retirement + foreground/background model
    │
    ├── Phase 1  WorkerLoadView Foundation         (requires Phase 0 foreground flag)
    │       │
    │       ├── Phase 2  DispatchPriority          (independent of Phase 1; can run in parallel)
    │       │
    │       ├── Phase 3  CandidateRanker           (requires Phase 1 load data)
    │       │       │
    │       │       └── Phase 4  CapacityReservation  (requires Phase 1 WorkerLoadView API)
    │       │
    │       └── Phase 6  Cross-Task Budget         (requires Phase 1 task-level tracking)
    │
    └── Phase 5  Retire TaskScheduler SPI          (independent; any point after Phase 0)
```

**Minimum viable path to load-aware dispatch:** Phase 0 → Phase 1 → Phase 3.  
**Full production-grade scheduling:** all six phases in order.

---

## Trace Scenario Additions Per Phase

| Phase | New Scenario | Validates |
|---|---|---|
| Phase 0 | `account-switch-failure` | `ACCOUNT_SWITCH_FAILED` result reason flows through convergence correctly |
| Phase 0 | `background-task-worker-sharing` | Two background tasks on same worker; both complete |
| Phase 2 | `priority-lane-ordering` | HIGH-priority task dequeued before NORMAL in same lane |
| Phase 3 | `load-aware-worker-selection` | Lower-load worker selected over equivalent higher-load worker |
| Phase 4 | `capacity-reservation-under-concurrency` | No worker over-committed across concurrent assignment waves |
| Phase 6 | `cross-task-worker-fairness` | INTERACTIVE task not starved by concurrent BULK task |

---

## What This Does Not Change

- `TaskWorkerMatchingStrategy` SPI contract — `matchWorkers(Task, int)` signature unchanged
- QLExpress rule evaluation — eligibility gate unchanged; ranker adds quality above it
- Result convergence barrier protocol — untouched
- `TaskLifecycleService` state machine — untouched
- Transport delivery layer — only additive: `dispatchInstruction.targetAccount` field added
- `TaskAssignWorker` lane model — INTERACTIVE/BULK separation unchanged

---

## Minimum Verification Per Phase

```bash
# Engine core
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=EngineSchedulingCoreSuite test

# Server E2E scheduling
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ServerSchedulingE2eSuite test

# Focused regression (routing + matching correctness)
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=TaskApiWorkerContextAttributeRoutingIntegrationTest,\
TaskApiWorkerWithoutContextIntegrationTest,\
TaskApiMinimumWorkerGateIntegrationTest test

# Trace scenario verification
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile \
  -Dmaven.test.skip=true compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli \
  -Dexec.args="analyze --path <trace-path> --scenario <new-scenario> --task-id <task-id>"
```
