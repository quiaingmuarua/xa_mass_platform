# Scheduling Upgrade Plan

**Scope:** engine scheduling capability upgrade across nine steps.  
**Aligned with:** `xa-mass-engine/SCHEDULING_UPGRADE_ROADMAP.md` (read that document first
for non-negotiable execution rules, owner boundary definitions, and mechanism/policy
separation rules).  
**Proof surface:** `EngineSchedulingCoreSuite`, `ServerSchedulingE2eSuite`,
and `xa-mass-trace` scenario analyzers at each step.

---

## Current Status

| Step | Name | Status |
|---|---|---|
| 0 | Roadmap and guardrails | Done — engine roadmap committed |
| 1 | Retire inert `TaskScheduler` SPI | **Done 2026-05-15** |
| 1b | Extract `AssignmentAllocationPolicy` | **Done 2026-05-15** — allocation formula and dispatch decision gate extracted from `TaskWorkerAssignListener` into `DefaultAssignmentAllocationPolicy` |
| 2 | Worker scheduling view convergence | Next |
| 3 | WorkerContext public/storage cleanup | Blocked on Step 2 |
| 4 | WorkerLoadView foundation | Proposed |
| 5 | Dispatch priority within lanes | Proposed |
| 6 | WorkerCandidateRanker | Proposed |
| 7 | Capacity reservation and foreground/background | Proposed — open model questions must be answered first |
| 8 | Cross-task worker budget and fairness | Proposed |
| 9 | Worker management / system event boundary | Proposed |

---

## What Was Just Done (main 2026-05-15)

### Step 1: TaskScheduler SPI Retired

`TaskScheduler` and `SimpleTaskScheduler` deleted. No replacement. The inert no-op scheduling
path that bypassed lane queues and contention guards is removed. Assignment enters through
`TaskAssignWorker` and `TaskWorkerAssignListener` only.

### Step 1b: AssignmentAllocationPolicy Extracted

Allocation logic extracted from `TaskWorkerAssignListener.onTaskAssign()` into a new
policy seam. The policy now owns:

- `desiredDispatchWorkerCount` — `ceil(readyWorkCount / batchSize)`
- `requiredStartWorkerCount` — minimum worker gate for READY→RUNNING transition
- `requestedMatchCount` — upper bound passed to matching strategy
- `dispatchCandidateLimit` — post-match trim limit
- Dispatch decision outcomes: `NO_MATCH`, `BELOW_MIN_START_GATE`, `TASK_STATUS_CHANGED`,
  `NO_DISPATCH_CANDIDATES`, `DISPATCH`

`TaskWorkerAssignListener` is now the orchestration mechanism that consumes the policy plan
and decision. It must not re-own allocation formulas.

Future steps (budget, foreground/background capacity) extend
`DefaultAssignmentAllocationPolicy` or replace it with a richer implementation —
not by moving logic back into the listener.

---

## Step 2: Worker Scheduling View Convergence (Next)

**Goal:** move engine matching hot path to read worker-level scheduling attributes.
Stop treating `WorkerContext` as a schedulable engine resource.

**Critical rule:** `WorkerContext` storage, HTTP endpoints, and test fixtures are NOT
deleted in this step. Physical deletion is Step 3, after this step is proven by E2E.

### Target shape

Engine matching consumes a flattened worker-level attribute map:

```
workerId
supportedProjects, supportedEventCodes
routingTags (merged from worker-level or context-level source, presented as worker attribute)
workerAttributes
reachability (from WorkerReachabilityView, already present)
```

`workerContextAttributes` as a QLExpress variable is replaced by `workerAttributes`.
Routing rules that currently read `workerContextAttributes['country']` switch to
`workerAttributes['country']`.

The current per-worker `List<WorkerContext>` iteration in `matchWorkers()` — including
the null-context fallback path — is replaced by a single-worker path reading the
flattened attribute map.

### Changes

| File | Change |
|---|---|
| `WorkerMatchContext` | Replace `workerContext*` variables with flattened `workerAttributes` from worker-level; remove null-context dual path |
| `RuleBasedTaskWorkerMatchingStrategy` | Remove `getWorkerContextsByWorkerIds()` call and per-context iteration; single `Worker` path |
| `prefilterCandidate()` | Remove context-specific checks (`isAllocatable`, context routing tag check, context project check) |
| Worker attribute population | Routing tags and context attributes merged into `Worker.attributes` at registration/heartbeat time |

### Out of scope

- Do not delete `WorkerContext` types, storage, or API.
- Do not add `foreground`, `background`, or `maxConcurrentWork`.
- Do not add `WorkerLoadView`.
- Do not add account switching or execution hints.

### Open questions to answer before this step

From engine roadmap Section 18:
- Where do routing tags come from when `WorkerContext` is no longer the matching source?
  (Worker registration, heartbeat, or a system event that populates worker attributes?)
- How do workers that currently register contexts update their routing attributes at runtime?

### Verification

- Unit: `WorkerMatchContextConvergenceTest` — no `workerContext*` variables in context map
- Unit: `RuleBasedTaskWorkerMatchingStrategyTest` — single-path matching, no null-context branch
- Server E2E: `TaskApiWorkerContextAttributeRoutingIntegrationTest` green with rule updated to
  `workerAttributes['country'] == routingCode`
- Server E2E: `TaskApiWorkerWithoutContextIntegrationTest` green
- Trace: new scenario `worker-scheduling-view-routing`

---

## Step 3: WorkerContext Public/Storage Cleanup

**Goal:** physically retire all `WorkerContext` artifacts after Step 2 is proven.

Blocked on Step 2 passing full E2E and trace verification.

### Deletions

| Artifact | Notes |
|---|---|
| `WorkerContext.java` | Model |
| `WorkerContextStatus.java` | Enum + state machine |
| `WorkerContextFixture`, `WorkerContextSnapshot` | Test/diagnostic types |
| Context storage APIs (`getWorkerContextsByWorkerIds`, etc.) | `WorkerStorage` |
| `ExternalWorkerContextRegisterApiRequest` | Request DTO |
| Worker context registration endpoint | `/api/v1/workers/{workerId}/contexts` |
| Context CRUD endpoints | Server API |
| `workerContextId` from assignment trace | After trace analyzers are updated |

### Risk

This is a public API / server / storage cleanup. If the diff crosses too many modules,
break it into sub-steps: model + engine first, server API second, storage third.

### Verification

- Reactor compile
- ArchUnit: no engine mainline references to `WorkerContext`
- `EngineSchedulingCoreSuite`, `ServerSchedulingE2eSuite`
- Trace analyzers updated to accept worker-level matching evidence

---

## Step 4: WorkerLoadView Foundation

**Goal:** push-updated worker load snapshot. O(1) read per worker during matching.
No live runtime queries in the hot path.

### Why pre-aggregation is required

```
matchWorkers() iterates M candidates per assignment cycle.
N concurrent tasks × M workers = N×M runtime queries per second without pre-aggregation.
```

Pattern: identical to `WorkerReachabilityView`. Runtime claim/release events push
deltas; matching reads a pre-computed in-memory snapshot.

### Proposed API (include taskId from the start — required by Step 8)

```java
interface WorkerLoadView {
    int getActiveCount(String workerId);
    int getReservedCount(String workerId);
    int getActiveWorkerCountForTask(String taskId);
    double getLoadRatio(String workerId);
    boolean isAtCapacity(String workerId);

    void recordWorkClaimed(String taskId, String workerId, boolean foreground, int capacityUnits);
    void recordWorkReleased(String taskId, String workerId, String reason);
}
```

### Release coverage rule

`recordWorkReleased` must be called on all active attempt close paths:
successful final result, failed final result, retry reset, lease expiry,
dispatch handoff failure compensation, manual terminal/cancel cleanup.
Missing one release path creates a permanent load count leak.

### Changes

| File | Change |
|---|---|
| New: `WorkerLoadView` interface | Engine layer, alongside `WorkerReachabilityView` |
| New: `InMemoryWorkerLoadView` | Thread-safe in-process default |
| `WorkerManager` | Inject `WorkerLoadView`; expose `isWorkerAtCapacity(workerId)` |
| `WorkerMatchContext` | Add `activeLeaseCount`, `loadRatio`, `isAtCapacity` to context map |
| `prefilterCandidate()` | Add `isAtCapacity` as an early filter |
| `TaskDispatchBinder` impl | Call `recordWorkClaimed` after successful claim |
| Result convergence paths | Call `recordWorkReleased` on all attempt close paths |
| `EngineConfig` / `MassEngineBuilder` | Wire `InMemoryWorkerLoadView` as default; injectable |

### Out of scope

- No candidate ranking yet.
- No capacity reservation yet.
- No foreground/background behavior (Step 7).
- No Redis/distributed load view.

### Verification

- Unit: concurrent claim/release accounting; thread-safety
- Unit: all release paths covered
- Engine suite unchanged behaviorally

---

## Step 5: Dispatch Priority Within Lanes

**Goal:** make `TaskRuntimeProfile.DispatchPriority.HIGH/NORMAL` affect signal order
within each lane. Currently declared but FIFO in practice.

### Important design constraint

Do not replace the bounded `LinkedBlockingQueue` with an unbounded `PriorityBlockingQueue`.
Queue capacity must be preserved. Options: bounded priority queue wrapper, or stamp
signals with a `(priorityOrdinal, sequenceNumber)` pair and maintain FIFO within
same priority.

### Changes

| File | Change |
|---|---|
| `TaskAssignmentSignal` | Add `priorityOrdinal` + monotonic `sequenceNumber` for same-priority FIFO |
| `TaskAssignWorker.LaneState` | Replace or wrap queue with bounded priority-aware semantics |
| `TaskAssignWorker.submit()` | Stamp signal with resolved priority ordinal at submit time |

### Out of scope

- No cross-lane preemption.
- No user-defined priority beyond existing `workloadClass` mapping.

### Verification

- Unit: HIGH drains before NORMAL in the same lane
- Unit: same-priority FIFO order preserved
- Unit: queue capacity still applies
- Engine suite green
- Trace: new scenario `priority-lane-ordering`

---

## Step 6: WorkerCandidateRanker

**Goal:** rank rule-passed candidates by composite score before lock/reservation.
Replace storage-order-biased selection with load-aware preference ordering.

### Pipeline

```
storage candidates
  → cheap prefilter
  → build WorkerMatchContext
  → QLExpress eligibility rules
  → collect passed candidates
  → ranker.rank(...)        ← new
  → lock/reserve in ranked order
  → return matched workers
```

Ranker placement after QLExpress: rules remain the correctness gate; ranker
adds quality above the eligibility threshold without contaminating eligibility.

### SPI

```java
interface WorkerCandidateRanker {
    // returns same candidates in preference order; must not mutate input
    List<WorkerMatchContext> rank(List<WorkerMatchContext> candidates, Task task);
}
```

### Default scoring (lower = better candidate)

```
score = loadWeight    × loadRatio
      + affinityWeight × (1 - affinityScore)   // routing tag match quality
      + availabilityWeight × availabilityPenalty
```

Weights via engine config first; system properties as fallback only.

### Changes

| File | Change |
|---|---|
| New: `WorkerCandidateRanker` interface | Engine strategy layer |
| New: `DefaultWorkerCandidateRanker` | Composite load + affinity scoring |
| `RuleBasedTaskWorkerMatchingStrategy` | Inject ranker; call `ranker.rank()` after rule evaluation, before lock acquisition |
| `EngineConfig` / `MassEngineBuilder` | Wire default ranker; injectable |

### Out of scope

- No external ranker plugin API.
- No worker performance-history scoring.
- No capacity reservation (Step 7).

### Verification

- Unit: lower-load equivalent worker selected first
- Unit: affinity tie-break correct
- Server E2E: routing integration tests green
- Trace: new scenario `load-aware-worker-selection`

---

## Step 7: Capacity Reservation and Foreground/Background

**Goal:** prevent over-commitment across concurrent assignment waves.
Define exclusive (foreground) vs shared (background) worker usage.

### Open questions — must be answered before this step begins

From engine roadmap Section 18:
1. Is `foreground/background` a persisted task field, a runtime profile derivation, or both?
2. What is the exact unit of worker capacity: worker, active lease, active attempt,
   dispatched message, or assignment binding?
3. How should `tryLockWorker` (intra-cycle exclusive lock) interact with the new
   capacity reservation (cross-cycle capacity guard)? They operate at different scopes
   and are complementary, but the interaction must be explicit.

### Proposed model (pending open question resolution)

```
ExecutionSpec.foreground: boolean        // default true (exclusive, backward compat)
Worker.maxConcurrentWork: int            // default 1 (exclusive-only, backward compat)
```

Semantics:
- `foreground=true`: reserves full worker capacity; `tryLockWorker` handles intra-cycle serialization
- `foreground=false`: reserves one capacity unit; worker remains dispatchable until at max capacity

### Proposed WorkerLoadView additions

```java
boolean tryReserveCapacity(String taskId, String workerId, boolean foreground, int capacityUnits);
void confirmReservation(String taskId, String workerId, int capacityUnits);
void releaseReservation(String taskId, String workerId, String reason);
```

### Failure integration

Release must cover all dispatch failure and task-status failure paths.
`tryLockWorker` is not removed — it still prevents double-selection within
a single `matchWorkers()` call. Reservation is the cross-cycle capacity guard.

### Verification

- Unit: concurrent reservations respect capacity ceiling
- Unit: all failure paths release reservations
- Engine: `TaskSchedulingContentionTest`, `TaskRedispatchCompetitionTest` extended
- Trace: new scenario `capacity-reservation-under-concurrency`

---

## Step 8: Cross-Task Worker Budget and Fairness

**Goal:** prevent large BULK tasks from starving concurrent INTERACTIVE tasks.

### Owner rule

Budget applies inside `AssignmentAllocationPolicy.plan()`, not in `TaskWorkerAssignListener`.
`DefaultAssignmentAllocationPolicy` is extended or replaced.

### Proposed model

```
ExecutionSpec.maxConcurrentWorkers: int   // 0 = use policy default
```

```java
interface WorkerBudgetPolicy {
    int resolveMaxConcurrentWorkers(Task task, int desiredCount);
    // INTERACTIVE: small default (e.g. 5)
    // BULK:        larger bounded default (e.g. 20)
    // explicit ExecutionSpec.maxConcurrentWorkers overrides
}
```

`DefaultAssignmentAllocationPolicy.plan()` reads current active worker count for the task
from `WorkerLoadView.getActiveWorkerCountForTask(taskId)` and applies the budget ceiling.

### Trace attrs added to assignment summary

- `workerBudget`
- `currentTaskWorkerCount`
- `budgetLimited`

### Verification

- Unit: budget caps large BULK desired worker count correctly
- Unit: INTERACTIVE dispatches while BULK is running
- Engine: mixed workload contention test
- Trace: new scenario `cross-task-worker-fairness`

---

## Step 9: Worker Management / System Event Boundary

**Goal:** move worker/device/account management toward a system-event-driven owner.
Engine scheduling depends only on readable views, not on context CRUD or slot lifecycle.

### Direction

Engine emits or consumes bounded system events but does not own device/account state.

Planned boundaries:
- `WorkerSchedulingViewStore`: engine read-side view for scheduling attributes
- Engine may publish worker-related lifecycle observations via a bounded event seam
- Worker management layer owns device/account registration, refresh, and scheduling attribute population

### Account execution hint

When scheduling attributes imply a target account, dispatch payload carries an optional hint:

```
dispatchInstruction.targetAccount: String   // null if no account switch needed
```

Account switch failure converges through normal result handling with a distinct reason:

```
failureReason = "ACCOUNT_SWITCH_FAILED"
```

This is a transport/result contract addition. `ACCOUNT_SWITCH_FAILED` is added to the
result reason vocabulary so `xa-mass-trace` analyzers can distinguish account failures
from execution failures.

No new protocol, no ack handshake. If switch fails, worker returns a standard item
failure result. Retry policy applies normally. Persistent account failures surface
via `RETRY_EXHAUSTED` with `failureReason=ACCOUNT_SWITCH_FAILED` visible in trace.

### Verification

- Contract tests: dispatch payload compatibility with `targetAccount` field
- Result convergence: `ACCOUNT_SWITCH_FAILED` flows through barrier protocol correctly
- Trace: new scenario `account-switch-failure`

---

## Dependency Graph

```
Step 1  Retire TaskScheduler SPI                     ← DONE
Step 1b AssignmentAllocationPolicy extracted          ← DONE
    │
    └── Step 2  Worker scheduling view convergence   ← NEXT
            │
            └── Step 3  WorkerContext cleanup        (blocked on Step 2)
                    │
                    └── Step 4  WorkerLoadView       (can start alongside Step 3)
                            │
                            ├── Step 5  DispatchPriority  (independent of load view)
                            ├── Step 6  CandidateRanker   (requires Step 4 load data)
                            │       │
                            │       └── Step 7  Capacity reservation   (requires Step 4 + open questions answered)
                            │               │
                            │               └── Step 8  Cross-task budget  (requires Step 4 + Step 7)
                            │
                            └── Step 9  Worker management boundary   (independent; long-range)
```

**Minimum path to load-aware dispatch:** Steps 2 → 3 → 4 → 6.  
**Full production-grade scheduling:** all steps in order.

---

## Trace Scenario Roadmap

| Scenario | First step | Validates |
|---|---|---|
| `worker-scheduling-view-routing` | Step 2/3 | Worker-level attributes preserve current routing behavior |
| `priority-lane-ordering` | Step 5 | HIGH processed before NORMAL in same lane |
| `load-aware-worker-selection` | Step 6 | Lower-load equivalent worker selected first |
| `capacity-reservation-under-concurrency` | Step 7 | No over-commitment across concurrent waves |
| `cross-task-worker-fairness` | Step 8 | BULK does not starve INTERACTIVE |
| `account-switch-failure` | Step 9 | Switch failure converges through result handling and trace |

---

## Non-Negotiable Execution Rules

Copied from engine roadmap — must hold at every step:

- Each step must be small enough to merge and verify independently.
- Do not combine model retirement, new capacity semantics, public API changes,
  and trace analyzer changes in one step.
- Do not introduce parallel old and new live paths.
- Do not reassign allocation formulas back to `TaskWorkerAssignListener`.
  `AssignmentAllocationPolicy` owns desired/requested worker counts, minimum
  start gates, and dispatch candidate limits.
- A policy returns a plan, decision, score, or reason. It must not mutate task
  state, claim runtime work, unlock workers, publish events, or decide terminal
  result semantics.
- Do not expose a policy through SDK/server configuration until there is a real
  external use case.
- Proof surface at every step: focused unit tests + `EngineSchedulingCoreSuite`
  + `ServerSchedulingE2eSuite` when wiring is touched + canonical trace scenario
  when scheduling decisions change.
