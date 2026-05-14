# Scheduling Upgrade Plan

**Scope:** engine-layer scheduling capability upgrade across five phases.  
**Goal:** make task-worker matching and worker invocation priority strategy a first-class,
load-aware, verifiable capability — not a binary eligibility filter on storage-order iteration.  
**Proof surface:** each phase is verified via `EngineSchedulingCoreSuite`,
`ServerSchedulingE2eSuite`, and `xa-mass-trace` scenario analyzers.  
**Prerequisite:** `xa-mass-trace` scenario analysis capability is already in place.

---

## Context: What Exists and What Is Thin

### What is solid

| Component | Status |
|---|---|
| `RuleBasedTaskWorkerMatchingStrategy` prefilter | Binary eligibility: reachability, lock, project/event/routingCode |
| QLExpress rule evaluation per `(worker, workerContext)` | Pluggable, trace-recorded |
| `TaskAssignWorker` lane separation | INTERACTIVE / BULK each have own `LinkedBlockingQueue` + executor |
| `WorkerReachabilityView` pattern | Push-updated snapshot, read-only at match time — proven pattern |
| `TaskWorkerMatchingStrategy` SPI | Clean seam for strategy replacement |
| Assignment trace coverage | `workerMatchAccepted`, `workerMatchRejected`, `assignmentSummary` all emit canonical events |

### What is thin or declared but unimplemented

| Gap | Location | Impact |
|---|---|---|
| `DispatchPriority.HIGH/NORMAL` declared, zero runtime effect | `TaskRuntimeProfile`, `TaskAssignWorker` | Lane-internal ordering is pure FIFO regardless of profile |
| Candidate iteration is storage-order; no scoring or ranking | `RuleBasedTaskWorkerMatchingStrategy.matchWorkers()` | First eligible worker always wins; no load-awareness |
| Worker load is not a runtime first-class concept | `WorkerMatchContext` | Rules cannot reference `currentLoad`, `availableCapacity` |
| Load query in hot path would be N×M without pre-aggregation | — | Prerequisite: load must be a push-updated snapshot, not a live query |
| `tryLockWorker` is binary; no capacity dimension | `WorkerManager` | Cannot express "worker can handle 3 concurrent tasks" |
| `TaskScheduler` SPI is explicit dead code | `TaskScheduler`, `SimpleTaskScheduler` | Misleads readers; javadoc says "not called by mainline dispatch loop" |
| Cross-task worker budget: large BULK tasks can exhaust worker pool | `TaskWorkerAssignListener` | INTERACTIVE tasks may starve under concurrent BULK load |

---

## Phase 0 — WorkerLoadView Foundation

**Goal:** establish a push-updated, pre-aggregated worker load snapshot service that can be
read at O(1) per worker during matching — no live runtime queries in the hot path.

This phase produces no behavioral change. It is solely the infrastructure that makes
Phases 2 and 3 feasible without a performance cliff.

### Rationale

`WorkerReachabilityView` is the proven pattern: transport events push online/offline state
into an engine-internal snapshot; matching reads the snapshot without touching transport.
Worker load needs the same pattern: runtime claim/final events push load deltas into an
engine-internal `WorkerLoadView`; matching reads the snapshot.

Without this, any load-aware matching would require per-worker runtime queries inside
`matchWorkers()`, producing N×M queries per assignment cycle under concurrent task load.

### New types

```
xa-mass-engine:
  WorkerLoadView (interface)
    int getActiveLeaseCount(String workerId)
    int getReservedCount(String workerId)
    double getEstimatedLoadRatio(String workerId)   // leases / declared capacity, 0.0–1.0+
    void recordWorkClaimed(String workerId)
    void recordWorkFinal(String workerId)

  InMemoryWorkerLoadView (default impl)
    ConcurrentHashMap<workerId, AtomicInteger activeLeaseCount>
    ConcurrentHashMap<workerId, Integer declaredCapacity>   // from worker.maxConcurrentWork
    push-updated; stale by at most one assignment cycle — acceptable for scheduling
```

### Modified

| File | Change |
|---|---|
| `WorkerManager` | Inject `WorkerLoadView`; expose `getWorkerLoad(workerId)` |
| `WorkerMatchContext` | Add `currentActiveLeaseCount`, `estimatedLoadRatio` to context map so QLExpress rules can reference them immediately |
| `TaskResultService` / `TaskResultIngestFacade` | Call `workerLoadView.recordWorkFinal(workerId)` on work terminal |
| `TaskDispatchBinder` impl | Call `workerLoadView.recordWorkClaimed(workerId)` after successful claim |
| `EngineConfig` / `MassEngineBuilder` | Wire `InMemoryWorkerLoadView` as default; accept injection |

### Out of scope

- No change to matching algorithm yet.
- No capacity reservation yet.
- No QLExpress rule changes; just makes variables available.

### Verification

- Unit: `WorkerLoadViewTest` — claim/final increments/decrements, thread-safety under concurrent updates
- Integration: full `EngineSchedulingCoreSuite` must stay green with zero behavioral change
- Trace: no new scenario yet; existing `assignment-success-binding` trace output unchanged

---

## Phase 1 — Implement DispatchPriority Within Lanes

**Goal:** make `TaskRuntimeProfile.DispatchPriority.HIGH` and `NORMAL` have real runtime
effect inside each lane's queue. INTERACTIVE/HIGH tasks must not wait behind INTERACTIVE/NORMAL
tasks already queued in the same lane.

### What is wrong now

`TaskAssignWorker` uses `LinkedBlockingQueue<TaskAssignmentSignal>` per lane — plain FIFO.
`TaskRuntimeProfile.DispatchPriority` is declared in the type system but has zero runtime
effect. An INTERACTIVE task submitted after a wave of INTERACTIVE/NORMAL tasks will wait
behind all of them regardless of its declared priority.

### Changes

| File | Change |
|---|---|
| `TaskAssignmentSignal` (record) | Add `int priorityOrdinal` field resolved from `TaskRuntimeProfile.DispatchPriority.ordinal()` at submit time |
| `TaskAssignWorker.LaneState` | Replace `LinkedBlockingQueue` with `PriorityBlockingQueue` ordered by `priorityOrdinal ASC` (lower ordinal = higher priority = dequeued first) |
| `TaskAssignWorker.submit()` | Resolve `TaskRuntimeProfile` at submit time; stamp signal with priority ordinal |
| `TaskRuntimeProfileResolver` | No change to logic; priority resolution already correct for INTERACTIVE→HIGH, BULK→NORMAL |

### Invariants

- Lane separation (INTERACTIVE vs BULK) is unchanged; priority ordering is strictly within-lane.
- Deduplication via `trackedTaskIds` is unchanged; deferred requeue logic is unchanged.
- `DispatchLane` remains the primary separation; `DispatchPriority` is a secondary within-lane ordering.

### Out of scope

- Cross-lane preemption (BULK tasks never preempt INTERACTIVE lane work).
- Task-level user-defined priority beyond `workloadClass` mapping.

### Verification

- Unit: `TaskAssignWorkerPriorityTest` — submit HIGH and NORMAL signals to same lane; verify HIGH drains first
- Engine suite: `EngineSchedulingCoreSuite` unchanged
- Trace: new scenario `priority-lane-ordering` — two INTERACTIVE tasks, one marked HIGH priority;
  verify `ASSIGNMENT_QUEUE_SNAPSHOT` events show HIGH processed before NORMAL

---

## Phase 2 — WorkerCandidateRanker: Load-Aware Scoring

**Goal:** after prefilter passes, rank eligible candidates by a composite score before
lock acquisition. The first K ranked candidates acquire locks and become dispatch targets.
First-available-in-storage-order is replaced by best-score-first.

### What is wrong now

`RuleBasedTaskWorkerMatchingStrategy.matchWorkers()` iterates candidates in storage order.
Two workers that both pass all rules are treated identically; the first one encountered wins
every time. Under steady state the same "first" workers are always chosen, leaving lower-index
workers perpetually overloaded and higher-index workers idle.

### New types

```
xa-mass-engine:
  WorkerCandidateRanker (interface)
    List<WorkerMatchContext> rank(List<WorkerMatchContext> candidates, Task task)
    // returns same list in preference order; must not mutate input
```

### Default implementation: `DefaultWorkerCandidateRanker`

Composite score (lower = better):

```
score = w1 * loadRatio           // from WorkerLoadView via WorkerMatchContext
      + w2 * (1 - affinityScore) // routingTag exact-match > partial-match > none
      + w3 * contextPenalty      // AVAILABLE=0, USABLE=1, other=10
```

Default weights: `w1=0.6, w2=0.3, w3=0.1` — configurable via system properties.

Rules still gate eligibility; ranker only orders the eligible set. This is additive — the
rule engine remains the correctness guard; the ranker adds quality.

### Modified

| File | Change |
|---|---|
| `RuleBasedTaskWorkerMatchingStrategy` | Insert ranker step: build all `WorkerMatchContext` objects for prefilter-passed candidates → call `ranker.rank()` → iterate ranked list for lock acquisition |
| `WorkerMatchContext` | `currentActiveLeaseCount` and `estimatedLoadRatio` already available after Phase 0 |
| `TaskWorkerMatchingStrategy` | No change to SPI |
| `EngineConfig` / `MassEngineBuilder` | Wire `DefaultWorkerCandidateRanker` as default; accept injection |

### Lock acquisition after ranking

```
for candidate in ranker.rank(prefilterPassed, task):
    if matched.size() >= maxWorkerCount: break
    if tryLockWorker(candidate.workerId):
        matched.add(candidate)
        traceEventLogger.workerMatchAccepted(...)
    else:
        traceEventLogger.workerMatchRejected(..., "lock conflict after ranking")
```

Lock conflict on a ranked candidate is not a ranker failure — it means concurrent assignment
won the race. Continue to next ranked candidate.

### Out of scope

- No change to rule evaluation logic.
- No capacity reservation yet (Phase 3).
- No external scoring plugins yet; composite score is internal.

### Verification

- Unit: `DefaultWorkerCandidateRankerTest` — high-load worker ranks below low-load worker;
  routing affinity breaks ties correctly
- Unit: `RuleBasedTaskWorkerMatchingStrategyTest` — extend with load-differentiated worker pool;
  verify lower-load worker is chosen when rules are equivalent
- Engine suite: `EngineSchedulingCoreSuite`, specifically `TaskWorkerEligibilityTest`,
  `TaskWorkerContextContentionTest`
- Server E2E: `TaskApiWorkerContextAttributeRoutingIntegrationTest` must stay green
- Trace: new scenario `load-aware-worker-selection` — two identical-rule workers, one at 80% load,
  one at 10% load; verify trace shows lower-load worker accepted on repeated assignments

---

## Phase 3 — Optimistic Capacity Reservation

**Goal:** replace binary dispatch-cycle locking with capacity-aware reservation so that the
load view reflects committed dispatch intent immediately — before the runtime claim confirms.
This prevents the same worker from being over-committed across concurrent assignment waves.

### What is wrong now

`tryLockWorker()` is a per-cycle binary lock: a worker is either locked or not.
It serializes dispatch within one assignment attempt but does not express capacity.
`WorkerLoadView` (from Phase 0) tracks actual lease counts but there is a window between
"match committed, lock released" and "runtime claim confirmed" during which the load view
shows stale data. Under high concurrency, two concurrent assignment cycles may both see the
same worker as low-load and both select it before either claim confirms.

### Changes

```
WorkerLoadView additions:
  boolean tryReserveCapacity(String workerId)
    // atomically: if (activeLeaseCount + reservedCount) < maxConcurrent → increment reservedCount; return true
    // else return false — worker is at capacity
  void confirmReservation(String workerId)
    // reservation → active lease: reservedCount--, activeLeaseCount++
  void releaseReservation(String workerId)
    // dispatch failed: reservedCount--
```

| File | Change |
|---|---|
| `InMemoryWorkerLoadView` | Implement `tryReserveCapacity`, `confirmReservation`, `releaseReservation` with per-worker `AtomicInteger` for reservedCount |
| `RuleBasedTaskWorkerMatchingStrategy` | Replace `tryLockWorker()` with `workerLoadView.tryReserveCapacity(workerId)`; keep `tryLockWorker` for serialization only |
| `TaskWorkerAssignListener.onTaskAssign()` | On dispatch failure path: call `releaseReservation` for all candidates in `dispatchCandidates` |
| `TaskDispatchBinder` impl | On successful work claim: call `confirmReservation(workerId)` |
| `Worker` model (or `WorkerContext`) | Add `maxConcurrentWork` field (default: 1 for backward compat) so capacity limit is worker-declared |

### Relationship with `tryLockWorker`

`tryLockWorker` remains as a coarse serialization guard within a single assignment cycle —
it prevents the same worker from being locked twice in the same `matchWorkers()` call.
`tryReserveCapacity` is a cross-cycle capacity guard — it prevents over-commitment across
concurrent assignment waves. Both are needed; they operate at different scopes.

### Out of scope

- Capacity across JVM instances (requires Redis-backed `WorkerLoadView` — future phase).
- Persistent capacity state across restarts.

### Verification

- Unit: `WorkerLoadViewCapacityTest` — concurrent `tryReserveCapacity` calls; verify capacity limit is respected
- Engine suite: `TaskSchedulingContentionTest`, `TaskRedispatchCompetitionTest` extended with multi-wave concurrent scenarios
- Trace: new scenario `capacity-reservation-under-concurrency` — N concurrent tasks, worker pool with declared capacity 2; verify no worker is over-committed in trace output

---

## Phase 4 — TaskScheduler SPI: Retire Dead Code

**Goal:** eliminate the ambiguity introduced by a documented-but-dead SPI.

### What is wrong now

`TaskScheduler` interface declares `scheduleTask`, `scheduleTasks`, `cancelTask`, `pauseTask`,
`resumeTask`. Its own javadoc says:

> "These methods are advisory hooks — the mainline dispatch loop in `TaskAssignWorker` does
> not call them directly. They are reserved for future integration with external scheduling
> systems."

`SimpleTaskScheduler` is a pure no-op that only logs. The SPI has been wired via `EngineConfig`
and `MassEngineBuilder` but has zero effect on any dispatch behavior. Readers encountering
this interface have no way to know it is inert without reading the javadoc carefully.

### Decision: retire

There is no concrete use case for an external scheduler integration (Quartz, Spring Scheduler)
in the current mainline. Dispatch is event-driven via `TaskAssignWorker` lanes; external
timer-based scheduling would bypass the lane model and the contention guards.

If an external scheduling integration is needed in the future, the correct seam is a new
event source that submits tasks to `TaskAssignWorker.submit()` — not a separate parallel
dispatch path.

### Changes

| Action | Files |
|---|---|
| Delete | `TaskScheduler.java` |
| Delete | `SimpleTaskScheduler.java` |
| Remove wiring | `MassEngineBuilder` — remove `scheduler()` method and field |
| Remove wiring | Any `EngineConfig` reference |

### Verification

- Compile: reactor-wide `./mvnw -DskipTests compile`
- ArchUnit guard: no remaining references to deleted types
- Engine suite: full `EngineSchedulingCoreSuite` — behavioral baseline unchanged

---

## Phase 5 — Cross-Task Worker Fairness and Budget

**Goal:** prevent large BULK tasks from exhausting the worker pool and starving concurrently
running INTERACTIVE tasks. Introduce a per-task worker budget as a first-class execution
spec concept.

### What is wrong now

`getDesiredDispatchWorkerCount(task, readyWorkCount)` in `TaskWorkerAssignListener` is
task-local: `ceil(readyWorkCount / batchSize)`. A BULK task with 10,000 ready items and
batchSize=1 will request 10,000 workers, effectively claiming the entire available pool and
blocking any concurrent INTERACTIVE task from getting workers until the BULK task's
assignment cycle completes.

### New concept: `WorkerBudget`

```
ExecutionSpec additions:
  int maxConcurrentWorkers    // 0 = unlimited (default for INTERACTIVE SESSION tasks)
  // for BULK tasks: defaults to min(desiredWorkerCount, BULK_DEFAULT_MAX_CONCURRENT)

WorkerBudgetPolicy (engine-internal):
  int resolveMaxConcurrentWorkers(Task task, int desiredCount)
    // INTERACTIVE: min(desired, interactiveMaxConcurrent) — default 5
    // BULK:        min(desired, bulkMaxConcurrent)        — default 20 (configurable)
    // task.executionSpec.maxConcurrentWorkers overrides if set
```

### Changes

| File | Change |
|---|---|
| `TaskWorkerAssignListener.getDesiredDispatchWorkerCount()` | Apply `WorkerBudgetPolicy.resolveMaxConcurrentWorkers()` as a ceiling |
| `WorkerLoadView` | Add `getActiveWorkerCountForTask(String taskId)` — tracks how many workers are currently assigned to each task |
| `RuleBasedTaskWorkerMatchingStrategy` | Pass `currentTaskWorkerCount` to caller so `TaskWorkerAssignListener` can cap the request to `budget - currentCount` |
| `ExecutionSpec` | Add optional `maxConcurrentWorkers` field (default 0 = use policy default) |
| `MassEngineBuilder` | Expose `WorkerBudgetPolicy` injection point |

### Per-task worker count tracking

`WorkerLoadView.recordWorkClaimed(workerId, taskId)` and `recordWorkFinal(workerId, taskId)`
(extend Phase 0 signatures to include taskId). This allows both per-worker load tracking
(Phase 2, 3) and per-task worker count tracking (Phase 5) from the same service.

### Out of scope

- Dynamic budget adjustment at runtime (future).
- Priority-weighted worker pool partitioning.
- Per-project or per-operator budget quotas.

### Verification

- Unit: `WorkerBudgetPolicyTest` — BULK task with large ready count is capped; INTERACTIVE task
  gets through concurrently
- Engine suite: `TaskWorkerContextContentionTest` extended with mixed INTERACTIVE/BULK concurrent scenario
- Chaos probe: new `worker-starvation` probe — concurrent INTERACTIVE + BULK tasks; INTERACTIVE must
  complete within P95 latency bound regardless of BULK queue depth
- Trace: new scenario `cross-task-worker-fairness` — verify INTERACTIVE task dispatch is not blocked
  by large concurrent BULK task in trace assignment timeline

---

## Dependency Graph

```
Phase 0 (WorkerLoadView)
    ↓
Phase 1 (DispatchPriority)     ← independent of Phase 0; can run in parallel
    ↓
Phase 2 (CandidateRanker)      ← requires Phase 0 (load data in WorkerMatchContext)
    ↓
Phase 3 (CapacityReservation)  ← requires Phase 0 (extends WorkerLoadView API)
    ↓
Phase 4 (Retire TaskScheduler) ← independent; can run at any point after Phase 0
Phase 5 (Cross-Task Budget)    ← requires Phase 0 and Phase 3 (extends WorkerLoadView tracking)
```

Minimum viable path to load-aware dispatch: **Phase 0 → Phase 2**.  
Full production-grade scheduling: all five phases.

---

## Trace Scenario Additions Per Phase

| Phase | New `xa-mass-trace` Scenario | Validates |
|---|---|---|
| Phase 1 | `priority-lane-ordering` | HIGH-priority task dequeued before NORMAL in same lane |
| Phase 2 | `load-aware-worker-selection` | Lower-load worker selected over higher-load equivalent |
| Phase 3 | `capacity-reservation-under-concurrency` | No worker over-committed across concurrent waves |
| Phase 5 | `cross-task-worker-fairness` | INTERACTIVE task not starved by concurrent BULK task |

Each scenario follows the existing `analyze --scenario <name>` contract in `TraceOperatorService`.

---

## What This Does Not Change

- `TaskWorkerMatchingStrategy` SPI contract — still `matchWorkers(Task, int)`.
- QLExpress rule evaluation — still the eligibility gate; ranker adds quality above eligibility.
- `TaskAssignWorker` lane model — INTERACTIVE and BULK lanes unchanged; Phase 1 only adds within-lane ordering.
- Result convergence barrier protocol — untouched.
- `TaskLifecycleService` state machine — untouched.
- Transport delivery layer — untouched.

---

## Minimum Verification Per Phase

Each phase must pass before the next begins:

```bash
# Engine core
./mvnw -pl xa-mass-engine -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=EngineSchedulingCoreSuite test

# Server E2E scheduling
./mvnw -pl xa-mass-server -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ServerSchedulingE2eSuite test

# Trace scenario for the phase (after xa-mass-trace scenario is added)
./mvnw -pl xa-mass-trace -am -Dexec.classpathScope=compile -Dmaven.test.skip=true \
  compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.xa.mass.trace.cli.XaMassTraceCli \
  -Dexec.args="analyze --path <trace-path> --scenario <new-scenario> --task-id <task-id>"
```
