# Task Runtime Scheduling Lock Reduction Roadmap

Last updated: 2026-05-26

Status: active implementation roadmap.

## Summary

This roadmap focuses on the task-worker scheduling mainline:

```text
task item append
  -> runtime ready visibility
  -> task dispatch wakeup
  -> worker candidate match / reserve
  -> runtime claim
  -> transport dispatch
  -> result callback
  -> runtime result apply
  -> stable final result
  -> progress / refill / terminal convergence
```

The goal is not to add another scheduling abstraction. The goal is to reduce
lock scope and single-thread dependence only where the current task-worker
mainline already has clear runtime ownership.

First priority:

1. Make runtime data structures and atomic boundaries explicit.
2. Prove the mainline with memory and Redis behind the same contracts.
3. Shrink broad engine/runtime locks without changing scheduling truth.
4. Delete residue only after the replacement path is proven.

Later specialist topics, such as Redis stream wakeup, delayed-promotion Lua,
repair cleanup, and memory shard tuning, should not pull the mainline work off
course.

## Current Mainline Truth

### Owners

`TaskWorkRuntime` owns:

- ready work visibility
- delayed work visibility
- active leases
- retry scheduling
- lease expiry
- per-task runtime counters
- recent final work receipts

`TaskResultRuntime` owns:

- staged callbacks
- stable final visible rows
- task-local result sequence
- attempt/logical/progress barrier claims
- repair candidate indexes

Engine scheduling owns orchestration only:

- ready-task recovery
- assignment signal / dispatch pump
- worker candidate matching
- worker reserve / confirm / release
- `TaskWorkRuntime.claimReady(...)`
- dispatch binding
- result / expiry convergence

Engine scheduling must not become the queue, lease, or finality owner.

### Current Lock Reality

Current implementation has several different lock categories:

- `LocalTaskConcurrencyCoordinator` uses JVM-local per-task write/read locks,
  per-message locks, and a coalesced progress reconcile lock.
- `TaskAssignWorker` uses lane-local single-thread executors plus tracked task
  deduplication for assignment signals.
- `RuntimeReadyDispatchPump` uses one scheduled scanner and per-task in-flight
  dedupe while dispatching attempts on virtual threads.
- `InMemoryTaskWorkRuntime` and `InMemoryTaskResultRuntime` use broad
  method-level `synchronized`.
- `RedisTaskWorkRuntime` already uses Lua for enqueue, claim, result apply,
  lease expiry polling, and discard, but still has multi-command delayed /
  ready cleanup paths.
- `RedisTaskResultRuntime.commitVisibleFinal(...)` still has a JVM method lock
  even though final commit truth is Redis/Lua-owned.
- Worker dispatch availability is already registry-backed through
  `WorkerRegistry` slot state. `WorkerManager.isWorkerDispatchEnabled(...)`
  reads `WorkerRegistry.slotByWorkerId(...)`, and source-scoped dispatch gate
  mutations call `WorkerRegistry.disableDispatch(...)` /
  `WorkerRegistry.clearDispatchDisable(...)`.

This roadmap should reduce locks in the order that protects the mainline:

1. prove current semantics
2. identify real hot-path locks
3. shrink or remove only locks whose truth has moved into runtime atomic
   operations

### Current Implementation Snapshot

This section captures current code facts that implementation phases must not
rediscover or accidentally reverse.

| Area | Current fact | Roadmap consequence |
| --- | --- | --- |
| Shared contracts | `TaskWorkRuntimeContractTest` and `TaskResultRuntimeContractTest` are runtime-api contract bases with memory and Redis subclasses. | Extend these tests first; do not create backend-specific proof as the only correctness signal. |
| Redis final commit | `RedisTaskResultRuntime.commitVisibleFinal(...)` is Lua-owned and must not use a method-level JVM monitor. | Keep the source guard and concurrent Redis proof as the regression boundary; do not change result key shape outside `TRS-C2`. |
| Worker dispatch gate | Dispatch disabled sources are stored in `WorkerRegistry` slot state; `WorkerManager` delegates disable/clear/read to registry. | Treat registry gate as current truth and add guards against reintroducing a separate gate map. |
| Redis work keyspace | Current `RedisTaskWorkKeyspace` still exposes per-message work/lease/final receipt keys, task member/active sets, runtime stats, task-local delayed, and global ready/delayed/lease indexes. | `TRS-C2` must decide first-slice target keys before delayed/ready Lua work. |
| Redis result keyspace | Current `RedisTaskResultKeyspace` includes staged draft keys, task stage sets, visible rows/zset, pending barrier zsets, and barrier token keys. | Treat staging/barrier keys as current-shape repair/finality support, not automatic mainline target keys. |
| Redis worker registry | `RedisWorkerRegistry.updateSlot(...)` is synchronized because WATCH/MULTI is connection-scoped. | Do not fold worker-registry lock work into TRS; only consume its current contract. |
| Engine worker registry snapshot | `WorkerManager` still maintains `WorkerRegistrySnapshot` alongside registry-backed slot operations. | TRS must not expand snapshot truth; any snapshot cleanup belongs to worker-runtime convergence unless task scheduling directly depends on it. |

### Current Implementation Progress

This section is intentionally narrow. It records what has landed without
claiming later phases are complete.

| Phase | Status | Landed evidence |
| --- | --- | --- |
| `TRS-C1` | Partial | Shared runtime contract proof now covers concurrent claim uniqueness, multi-worker claim counters, same-lease result apply idempotence, lease-expiry polling uniqueness, same-message final commit uniqueness, and different-message final sequence uniqueness for memory and Redis subclasses. |
| `TRS-M1` | Implemented first slice | `RedisTaskResultRuntime.commitVisibleFinal(...)` no longer has a method-level JVM monitor; Redis tests include a reflection guard and cross-connection concurrent final commit proof. |
| `TRS-M2` | Implemented first slice | `WorkerCandidateSamplingPolicy` now has a shared random bounded implementation used by memory and Redis registry defaults. `WorkerManagerTest` proves bounded sampling happens before worker row materialization, `SimpleTaskDispatchBinderTest` covers concurrent assignment rounds for the same task, `TaskRedispatchCompetitionTest` covers result-release/refill on the mainline, and `TaskApiDelayedWorkerAvailabilityRedisRuntimeIntegrationTest` covers Redis-backed multi-round refill through the server/transport/runtime path. Deeper Redis concurrent engine duplicate-dispatch soak remains `TRS-D2` proof, not a `TRS-M2` blocker. |
| `TRS-M3` | Started | `EngineSchedulingCoreArchitectureGuardTest.taskWriteLockRemainsLifecycleAndProgressOnly` locks the current boundary: task write locks are allowed for lifecycle, intake, progress, and audit paths only; runtime claim must stay task-lock free. |
| `TRS-M4` | Implemented first slice | Redis delayed promotion and ready-head cleanup use small per-entry Lua transitions. `RedisTaskWorkRuntimeTest` covers competing runtime instances promoting one delayed item once and bounded cleanup of stale ready-head entries without counter drift. |
| `TRS-D1` | Started | Trace proof now accepts only explicit group-first candidate sources, stale worker-dispatch-gate docs point to `WorkerRegistry` slot disabled sources, and the superseded worker-resource-occupancy roadmap has been removed. |
| `TRS-D2` | Started | `TaskFlowLoadModelRunner` can run the same task append -> runtime enqueue -> runtime-ready pump -> match/reserve -> dispatch -> result -> release/refill -> terminal flow against memory or Redis runtime. The report now includes backend, stable-final result count, duplicate/stale/expired runtime counters, processing counter drift, result counter drift, first-dispatch lag, and claimed messages/sec. It also has opt-in lease-expiry/refill proof (`mass.load.expireFirstAttemptEveryNth`), duplicate-result callback proof (`mass.load.duplicateResultEveryNth`), and duplicate-wakeup proof (`mass.load.duplicateWakeupsOnApprove`) without changing the default smoke path. `run-perf-smokes.sh` can opt into backend and fault/wakeup proof, and existing perf smokes have been converged to explicit WorkerGroup selector setup. |

## Mainline Data Structures To Stabilize First

### Task Work Runtime View

The task work runtime should be reasoned about as these logical structures,
regardless of memory or Redis backend:

```text
workByTaskMessage
  key: (taskId, messageId)
  value: payloadRef/input metadata, retryCount, maxRetryCount, runtime flags

readyQueueByTask
  key: taskId
  value: ordered messageIds that may be claimed

readyTasks
  key: taskId
  value: task-level ready visibility / scheduling discovery evidence

delayedWork
  key: dueAt
  value: (taskId, messageId, delay reason)

activeLeaseByTaskMessage
  key: (taskId, messageId)
  value: workerId, batchId, leaseToken, leasedAt, expiresAt, retryCount

activeByTask
  key: taskId
  value: messageIds with active leases

activeByWorker
  key: workerId
  value: task/message lease references for release/read checks
  status: conditional until WorkerRegistry occupancy can own the required view

leaseExpiry
  key: expiresAt
  value: (taskId, messageId, leaseToken)

taskWorkStats
  key: taskId
  value: ready/delayed/active/final/retry counters

recentFinalReceipts
  key: (taskId, messageId)
  value: bounded duplicate/late callback recovery evidence
```

Mainline atomic mutations:

- enqueue
- claim ready work
- apply result / retry / final receipt
- expire active lease
- discard task runtime state

Reads may be stale when they are only used to discover candidates. Mutations
must re-validate under the runtime owner.

### Task Result Runtime View

The task result runtime should be reasoned about as:

```text
stagedCallbackByStageId
  key: stageId
  value: staged callback draft

visibleFinalByTaskMessage
  key: (taskId, messageId)
  value: stable final row

visibleFinalSeqByTask
  key: taskId
  value: ordered final rows by task-local sequence

taskResultSeq
  key: taskId
  value: next stable final sequence

barrierClaims
  key: (barrierType, taskId, messageId, finalSeq)
  value: claim token with TTL

repairIndexes
  key: due/visibility window
  value: final rows needing barrier progress
```

Mainline atomic mutations:

- commit visible final
- claim barrier
- mark barrier published/applied
- discard task result state

Repair scans are not the normal commit path.

## Redis Runtime Key Minimalism Review

This section is a review input, not an approved implementation phase.

The Redis runtime should be designed as runtime structures, not as a database
schema. The target is not merely changing per-message keys into hashes. The
target is to reduce the number of runtime structures to the few that directly
serve scheduling progress, lease safety, final result visibility, and bounded
recovery.

The default posture should be:

```text
Do not store an intermediate result unless a mainline operation needs it.
Do not create a reverse index unless avoiding it would require a hot-path scan.
Do not create repair indexes until the repair path is explicitly proven needed.
Prefer rebuilding non-critical diagnostics from trace/audit outside runtime.
```

### Minimal Target After This Roadmap

The table below is the candidate end-state key set for the mainline runtime
after this roadmap. It is intentionally smaller than the current keyspace.

| Key | Type | Owner | Purpose | Keep? | Notes |
| --- | --- | --- | --- | --- | --- |
| `rt:{ns}:task:{taskId}:work` | HASH | `TaskWorkRuntime` | `messageId -> work payload/retry metadata` for not-yet-final work | Yes | Main payload truth before final. Replaces per-message work keys and task member set. |
| `rt:{ns}:task:{taskId}:ready` | LIST | `TaskWorkRuntime` | ready message order for one task | Yes | Main claim source. Stale members are validated during claim. |
| `rt:{ns}:ready-tasks` | ZSET | `TaskWorkRuntime` | discover tasks that may have ready work | Yes | Stale-tolerant scheduling discovery. Claim re-validates. |
| `rt:{ns}:task:{taskId}:leases` | HASH | `TaskWorkRuntime` | `messageId -> active lease payload` | Yes | Active lease truth. Replaces per-message lease keys and task active set for mainline. |
| `rt:{ns}:lease-expiry` | ZSET | `TaskWorkRuntime` | discover expired leases by due time | Yes | Global due index is required to avoid scanning task leases. |
| `rt:{ns}:worker:{workerId}:active` | SET | `TaskWorkRuntime` / worker occupancy integration | active `taskId/messageId` refs for one worker | Conditional | Keep only while worker disconnect / expiry paths require this runtime view. Worker dispatch gate truth is already registry-backed; this key must not become a second gate truth. |
| `rt:{ns}:task:{taskId}:stats` | HASH | `TaskWorkRuntime` | task counters for progress/terminal policy | Yes | Mainline progress should not scan work/lease hashes. |
| `rt:{ns}:task:{taskId}:final` | HASH | `TaskResultRuntime` | `messageId -> stable final row` | Yes | Stable final visibility by message. Replaces per-message visible row keys. |
| `rt:{ns}:task:{taskId}:final-seq` | ZSET | `TaskResultRuntime` | result window order, member `messageId`, score `seq` | Yes | Needed for ordered result windows. |
| `rt:{ns}:task:{taskId}:seq` | STRING | `TaskResultRuntime` | task-local final sequence counter | Yes | Required for stable result ordering. |

### Keys Not In The Mainline Target

These current structures should not be part of the first target unless a review
proves the exact operation that needs them:

| Current structure | Current reason | Owner review |
| --- | --- | --- |
| `task registry set` | broad cleanup / diagnostics | Remove from mainline. Runtime operations should start from taskId or due indexes. |
| `task members set` | cleanup for per-message keys | Remove if work/lease/final rows are task-local hashes. |
| `task active set` | task active lease listing | Remove from mainline if `task:{taskId}:leases` hash is active truth. |
| `task-local delayed zset` | task-local delayed visibility | Remove from first target unless a hot-path operation truly needs task-local delayed lookup. |
| `runtime stats hash` | global metrics | Defer. Metrics should not drive scheduling truth. |
| `recent-final global zset` | bounded duplicate/late callback receipt trim | Defer. Prefer TTL or a small task-local receipt only if duplicate handling needs it. |
| `task recent-final set/hash` | duplicate/late callback recovery | Defer. Consider TTL receipt keyed by task/message or folded into final row. |
| `stage draft keys` | result callback staging/repair | Defer to result repair roadmap. Mainline can commit final without treating staging as runtime truth. |
| `task stage sets` | staged callback cleanup | Defer. Cleanup structure, not scheduling truth. |
| `task/message stage sets` | staged callback cleanup by message | Defer. Cleanup structure, not scheduling truth. |
| three pending barrier zsets | repair scans per barrier type | Defer. Repair visibility, not first-slice mainline. |
| three barrier token keys | idempotent publish/progress claims | Conditional. Keep only if barrier proof requires Redis-owned idempotence in the mainline slice. |

### Consequences Of The Minimal Target

This target accepts a few tradeoffs:

- `ready-tasks` may contain stale task ids; `claimReady(...)` validates against
  `ready` and `work`.
- `ready` may contain stale message ids; claim skips entries that no longer
  exist in `work` or already have a lease.
- delayed/retry can start as a single global due index. Task-local delayed
  visibility is not a first-slice requirement.
- global runtime metrics can be derived from trace or sampled diagnostics until
  a concrete operator requirement exists.
- repair/stage indexes are not required for the dispatch mainline and should be
  designed separately.

### Review Questions

Before implementation, answer these from current code and tests:

1. Can `TaskResultRuntime` mainline commit final directly without staging keys
   in the first lock-reduction slice?
2. Can duplicate/late callback classification use stable final rows plus active
   lease state instead of separate recent-final receipt keys?
3. Can `task:{taskId}:leases` replace `task:{taskId}:active` for all task-level
   active lease reads?
4. Which worker disconnect/release paths still require
   `worker:{workerId}:active`, and can WorkerRegistry occupancy eventually own
   that view?
5. Is task-local delayed lookup required by a mainline operation, or only by
   diagnostics/cleanup?
6. Which counters are needed by terminal policy versus metrics only?

### Phase Ordering Decision

This roadmap must follow the same execution rhythm as the worker-runtime
convergence work:

```text
Converge current truth
  -> modify one atomic boundary at a time
  -> delete residue only after the replacement path is proven
```

The phase names intentionally encode that rhythm:

- `TRS-C*` phases converge facts, contracts, and proof boundaries. They should
  not change runtime behavior.
- `TRS-M*` phases modify one mechanism boundary at a time. Each phase must land
  with its own verification and green CI.
- `TRS-D*` phases delete residue after the replacement path is proven. They
  must not leave two live runtime truth tracks.

Redis key convergence must not block the first narrow lock-removal slice:

- `TRS-M1` may run before Redis task-work key convergence because
  `RedisTaskResultRuntime.commitVisibleFinal(...)` already delegates final
  correctness to a Lua script.
- `TRS-M4` must not run before the Redis task-work key shape decision in
  `TRS-C2`. Delayed promotion and ready cleanup Lua depend directly on
  work/ready/lease key layout; writing new Lua against a shape that will be
  replaced creates avoidable review and rewrite cost.

The intended order is:

```text
TRS-C0 inventory / lock budget
TRS-C1 shared runtime proof baseline
TRS-C2 runtime structure and key-shape decision
TRS-M1 Redis final-commit JVM lock removal
TRS-M2 claim / dispatch mainline proof
TRS-M3 engine scheduling lock-scope reduction
TRS-M4 Redis delayed / ready atomicity
TRS-D1 residue removal
TRS-D2 runtime selection and performance proof
```

Current repair/barrier index writes may remain inside the existing final-commit
Lua only as current-shape behavior. They are not approval that barrier/pending
keys belong in the first mainline key target.

## Mechanism And Policy Boundary

Mechanism:

- runtime atomic transitions
- task/work/result indexes
- wakeup coalescing
- candidate discovery bounds
- per-task or per-message lock scope
- Redis Lua / single-command atomicity
- memory CAS / per-task lock atomicity

Policy:

- assignment allocation count
- worker candidate ranking
- idle polling backoff
- refill decision
- retry delay
- wakeup priority

Policy may choose when to attempt dispatch. It must not own queue, lease,
reservation, or finality truth.

## Hard Rules

1. Do not introduce `TaskRuntimeRegistry`, `TaskScheduleBridge`, or another
   wrapper that only forwards to existing runtime owners.
2. Keep `TaskWorkRuntime` as queue / delayed / lease / retry / expiry owner.
3. Keep `TaskResultRuntime` as stable-final result / barrier / repair owner.
4. Memory and Redis implementations must share the same runtime contract tests.
5. Redis scheduling correctness must come from Redis data structures plus Lua or
   Redis atomic primitives, not JVM locks around Redis calls.
6. Streams or pub/sub may wake schedulers, but they must not be the only source
   of queue, lease, or finality truth.
7. Cleanup may be lazy and bounded. Correctness must not depend on immediate
   cleanup.
8. Critical scheduling progress must not require one global Java thread.
9. Every lock reduction must have a concurrency proof.
10. Do not remove an engine lock until the runtime mutation it protected is
    proven atomic.
11. Do not let diagnostics, trace, or compatibility projection drive runtime
    state.
12. Do not optimize delayed/repair/stream specialty paths before the task-worker
    mainline is proven.
13. Keep Lua small. Lua scripts may protect indivisible multi-key state
    transitions, but must not become hidden scheduling policy or large business
    workflows.
14. Redis task-work key convergence is not required before `TRS-M1`, but it
    must be decided before `TRS-M4` delayed/ready Lua work starts.
15. Worker dispatch gate truth is registry-backed through `WorkerRegistry`.
    Do not reintroduce an independent synchronized dispatch gate map in task
    runtime lock-reduction work.

## Owner Review Position

The lock-reduction direction is accepted with two explicit boundaries:

1. The platform may tolerate stale discovery, duplicate wakeups, and delayed
   cleanup.
2. The platform must not tolerate duplicate claims, duplicate stable finals,
   terminal-after-claim races, or persistent worker/task occupancy drift.

This means the target is not "eventual consistency everywhere". The target is:

```text
stale-tolerant reads around the edge
strong atomic mutation at runtime ownership boundaries
```

Stale-tolerant areas:

- ready task discovery
- wakeup hints
- poll backoff state
- diagnostic snapshots
- bounded cleanup discovery
- candidate indexes before stage-2 validation

Strong atomic areas:

- `TaskWorkRuntime.claimReady(...)`
- `TaskWorkRuntime.applyResultWithContext(...)`
- lease expiry to retry/final transition
- `TaskResultRuntime.commitVisibleFinal(...)`
- barrier claim/mark mutation
- task terminal transition
- worker reserve / confirm / release / final occupancy mutation

These are the boundaries that must drive implementation review. A change that
removes a lock but weakens one of the strong atomic areas is not a lock
reduction; it is a correctness regression.

## Lock Boundary

This roadmap is not "no locks". It is "no broad global lock on the scheduling
mainline".

Acceptable locks:

- owner-internal locks around a small mutable structure
- per-task locks for task-local lifecycle or runtime mutation
- per-message locks for result/lease convergence
- shard locks when they bound unrelated task interference
- short Redis connection guards only when a connection-scoped primitive cannot
  safely be shared, with a follow-up plan to replace it with Lua or separate
  connections where appropriate

Unacceptable locks:

- one global lock around task acquisition, matching, claim, dispatch, and result
  convergence
- locks that start before task discovery and end after dispatch/result side
  effects
- Java locks used to make Redis correctness pass only inside one JVM
- locks that serialize unrelated tasks without a documented invariant
- compatibility projection or diagnostics locks on the runtime hot path

Rule of thumb:

```text
Lock the smallest owner state transition.
Do not lock the whole scheduling story.
```

If a simpler internal lock keeps one owner clear and local, it is acceptable.
If a lock turns the platform into a single scheduling lane, it is a design
problem.

## Redis Lua Boundary

Lua is allowed where Redis needs a small atomic mutation across multiple keys.

Good Lua use:

- idempotent enqueue writes work payload, ready queue, ready task index, and
  counters together
- claim moves one or more ready message ids into active lease state
- result apply removes an active lease, writes retry/final receipt evidence, and
  updates counters
- visible final commit checks duplicate, increments task-local sequence, writes
  final row, and writes barrier indexes
- delayed promotion moves due entries from delayed index to ready queue with
  bounded batch size

Bad Lua use:

- embedding worker ranking or assignment policy
- embedding retry strategy decisions beyond already-resolved retry metadata
- scanning large namespaces
- implementing long control flow that is hard to test from Java contract tests
- hiding correctness problems by moving broad Java logic into a script

Rule of thumb:

```text
Java owner / policy decides what transition should happen.
Lua applies the minimal Redis key mutation atomically.
Contract tests describe the transition semantics.
```

If a script becomes difficult to explain as a small state transition, split the
owner logic in Java first instead of expanding Lua.

## Phase TRS-C0: Inventory And Lock Budget

Goal: make current lock and atomic boundaries explicit before changing behavior.

Scope:

1. Inventory task-worker mainline calls:
   - `TaskLifecycleService.appendTaskItemsWithReceipt(...)`
   - `TaskManager.addRuntimeIngressItems(...)`
   - `TaskAssignWorker.submit(...)`
   - `TaskWorkerAssignListener.onTaskAssign(...)`
   - `RuleBasedTaskWorkerMatchingStrategy.matchWorkers(...)`
   - `SimpleTaskDispatchBinder.bindDispatches(...)`
   - `TaskWorkRuntime.claimReady(...)`
   - `TaskResultService.ingestTaskResult(...)`
   - `TaskResultRuntime.commitVisibleFinal(...)`
2. For each call, classify:
   - pure read
   - stale-tolerant discovery
   - finality-sensitive mutation
   - cleanup / repair mutation
   - compatibility projection residue
3. For each lock, record:
   - owner
   - protected invariant
   - scope
   - whether it is JVM-local only
   - whether it is on the dispatch hot path
4. Record Redis key shapes currently used by:
   - `RedisTaskWorkRuntime`
   - `RedisTaskResultRuntime`
5. Classify current Redis keys as:
   - mainline truth
   - stale-tolerant discovery index
   - cleanup/repair index
   - diagnostic/metrics only
   - candidate for convergence
6. Record memory structures currently used by:
   - `InMemoryTaskWorkRuntime`
   - `InMemoryTaskResultRuntime`

Exit:

- one mainline call graph exists
- one lock budget table exists
- one runtime data structure table exists
- one Redis key classification table exists
- WorkerRegistry-backed dispatch gate reads are recorded as existing current
  truth, not as a future WSR dependency
- no behavior changes yet

## Phase TRS-C1: Shared Runtime Proof Baseline

Goal: prove the task-worker mainline before reducing locks.

Scope:

1. Extend `TaskWorkRuntimeContractTest` with concurrent proof:
   - concurrent enqueue for the same `taskId/messageId` is idempotent
   - concurrent claim cannot lease one message twice
   - concurrent claim across many workers preserves ready/active counters
   - concurrent apply result with the same lease is idempotent or stale-safe
   - concurrent lease expiry polling does not duplicate expiry handling
2. Extend `TaskResultRuntimeContractTest` with concurrent proof:
   - concurrent final commit for the same message produces one visible row
   - concurrent final commit for different messages preserves task-local sequence
   - concurrent barrier claim allows one owner at a time
   - expired barrier claims can be reclaimed
3. Add Redis cross-client tests for critical cases, not only shared-instance
   tests.
4. Add engine-level proof for:
   - many items appended to one task
   - many workers matched
   - bounded claim count
   - no duplicate message dispatch
   - result convergence releases worker resources and may trigger refill

Exit:

- memory and Redis pass the same contract suite
- Redis has cross-client proof where JVM locks cannot hide bugs
- engine mainline proof observes append -> claim -> result -> refill
- proof failures expose duplicate claim, duplicate final, counter drift, stale
  result, or wakeup lag instead of reporting only generic timeout/failure

## Phase TRS-C2: Runtime Structure And Key-Shape Decision

Goal: align memory and Redis runtime structures before optimizing locks.

Scope:

1. Name the logical runtime structures listed in this document in code comments
   or test helper terminology.
2. Ensure memory and Redis expose equivalent behavior for:
   - ready queue visibility
   - active lease lookup
   - worker active lookup only if TRS still owns that view after the current
     WorkerRegistry occupancy/gate contract is reviewed
   - task stats
   - recent final receipt lookup
3. Define stale-tolerant read boundaries:
   - `readyTaskIds(...)` may return stale task ids
   - `hasReadyWork(...)` may be used as evidence only
   - `claimReady(...)` must re-validate and is the actual claim truth
4. Define finality-sensitive mutation boundaries:
   - `applyResultWithContext(...)`
   - `commitVisibleFinal(...)`
   - barrier claim/mark methods
5. Remove or demote tests that assert implementation-specific row layout rather
   than runtime contract semantics.
6. Confirm Redis key convergence is postponed until after `TRS-M1`, and document
   why the existing key shape does not block `commitVisibleFinal(...)`
   lock removal.
7. Complete the Redis task-work key-shape decision before `TRS-M4` starts.
   Delayed promotion / ready cleanup Lua must not be written against a key
   shape that is expected to be replaced.
8. Define stats counter invariants that every Redis atomic mutation must
   preserve:
   - for active, non-discarded tasks:
     `ready + delayed + inflight + success + failed + expired == total`
   - discarded tasks may clear runtime rows and counters together
   - final counters are monotonic
   - `claim` moves ready to inflight without changing total
   - `result apply` moves inflight to final or delayed
   - cleanup never decrements counters below zero

Exit:

- runtime structures are clear without introducing a new owner class
- stale reads and atomic mutations are explicitly separated
- tests stop depending on backend-specific storage shape unless the test is
  backend-specific
- Redis key shape convergence remains an explicit decision, not an accidental
  side effect of lock work
- stats counter invariant tests are defined before any delayed/ready Lua rewrite

## Phase TRS-M1: Redis Result Commit Lock Removal

Goal: make Redis stable final commit rely on Redis atomicity, not a Java method
monitor.

Current target:

- `RedisTaskResultRuntime.commitVisibleFinal(...)`

Scope:

1. Add concurrent final commit tests before removing the lock.
2. Remove method-level `synchronized` from Redis result commit.
3. Keep the Lua script as the only final commit owner:
   - duplicate detection
   - task-local sequence
   - visible row write
   - visible sequence index write
   - current-shape repair/barrier index writes only if the existing script
     already owns them
4. Keep memory runtime synchronization until the memory lock-striping phase.
5. Do not change Redis result key shape in this phase; key convergence is a
   `TRS-C2` decision.
6. Add a source guard that Redis result commit does not regain method-level
   `synchronized`. The guard should be an ArchUnit or equivalent reflection
   test that asserts `RedisTaskResultRuntime.commitVisibleFinal(...)` has no
   `synchronized` modifier, paired with the concurrent Redis contract test.

Exit:

- same-message concurrent commit produces one committed row
- duplicate commits return duplicate/stale-safe outcomes
- different-message concurrent commit produces unique task-local sequence values
- Redis final commit has no JVM monitor
- CI can pass without depending on a later phase

## Phase TRS-M2: Claim Path And Mainline Dispatch Proof

Goal: prove the worker match -> reserve -> claim -> dispatch boundary under
concurrency.

Scope:

1. Add proof that a large eligible worker pool with bounded match count does
   not repeatedly select only the same first workers when random/bounded
   sampling is enabled.
2. Add proof that claim-ready work is bounded by:
   - allocation policy
   - worker reserved capacity
   - runtime ready count
   - per-worker claim capacity
3. Add proof that duplicate assignment attempts for the same task do not
   duplicate message dispatch.
4. Add proof that a no-match attempt does not consume ready work.
5. Add proof that result final / lease expiry releases worker occupancy and
   a later refill attempt can claim remaining ready work.

Exit:

- mainline scheduling works under concurrent assignment attempts
- runtime claim is the dispatch truth
- worker reservation remains a pre-claim admission guard, not finality truth
- critical duplicate-dispatch proofs use Redis cross-client setup, not only
  shared-instance memory runtime tests
- CI can pass without depending on a later phase

## Phase TRS-M3: Engine Scheduling Lock Scope Reduction

Goal: shrink engine-side locks only where runtime ownership already protects
the mutation.

Current lock classification:

| Lock path | Current owner | Classification | Roadmap stance |
| --- | --- | --- | --- |
| `withTaskLock(...)` / `withTaskWriteLock(...)` | `LocalTaskConcurrencyCoordinator` per task | lifecycle/intake/progress/audit write serialization | Keep for approve/reject/block/pause/resume/cancel/terminate, append/seal, progress resolution, validation, and projection audit. |
| `claimReady(...)` | `TaskWorkRuntime` | runtime claim atomic boundary | Must not take task write/read locks; runtime implementation owns uniqueness and counters. |
| `withTaskWorkReadLock(...)` | task read lock plus per-message lock | result/expiry/dispatch-failure convergence guard | Keep as a conservative first-slice guard until a separate review proves late-result vs lifecycle races are safe without the task read lock. It must not become a task write lock. |
| `reconcileTaskProgress(...)` | coalesced per-task progress coordinator | aggregate progress/terminal convergence | Keep as write-locked because it mutates the task aggregate and can publish terminal events. |
| dispatch gate read/write | `WorkerRegistry` slot disabled sources | worker runtime admission truth | Keep outside task runtime locks; do not reintroduce an independent gate map. |

Scope:

1. Review `TaskManager.withTaskLock(...)` usage and classify each call as:
   - task shell/state mutation that still needs task write lock
   - runtime mutation that should not require task write lock
   - diagnostic/audit path that can stay locked but is not hot path
2. Keep task write lock for:
   - lifecycle status transition
   - intake-window mutation
   - terminal transition
   - state/progress reconcile
3. Do not require task write lock merely to:
   - discover ready work
   - claim runtime work
   - read active lease evidence
   - commit runtime final result
4. Review `TaskAssignWorker` lane model:
   - keep per-task dedupe
   - avoid one lane becoming the only progress path for bulk runtime tasks
   - keep runtime ready pump as recovery / bulk path
5. Review `RuntimeReadyDispatchPump`:
   - keep in-flight task dedupe
   - ensure duplicate wakeups are harmless
   - avoid relying on one scanner for correctness
6. Identify dispatch gate reads that must remain registry-backed, especially
   `WorkerSchedulingCandidateEnumerator -> WorkerManager.isWorkerDispatchEnabled(...)`.
   Add a guard or source scan that prevents reintroducing an independent
   dispatch gate map outside `WorkerRegistry`.
7. Add trace evidence for lock/wakeup decisions only if it does not add hot-path
   scans.

Exit:

- task lifecycle locks remain correctness locks
- runtime claim/result paths are not serialized by unnecessary task write locks
- assignment retry / pump / wakeup paths are coalesced but not truth owners
- WorkerRegistry-owned dispatch gate truth stays a registry concern, not a new
  task-runtime map
- CI can pass without depending on a later phase

## Phase TRS-M4: Redis Work Delayed / Ready Cleanup Atomicity

Goal: make delayed promotion and ready-head cleanup safe under concurrent
Redis clients.

This is intentionally after `TRS-C2` and `TRS-M2`. It is important, but it
should not block the task-worker mainline proof.

Current targets:

- `RedisTaskWorkRuntime.promoteDueDelayedLocked(...)`
- `RedisTaskWorkRuntime.promoteDueDelayedForTaskLocked(...)`
- `RedisTaskWorkRuntime.promoteDelayedMember(...)`
- `RedisTaskWorkRuntime.ensureReadyQueueVisible(...)`

Scope:

1. Replace delayed promotion with Lua:
   - each Lua call handles one delayed entry atomically
   - Java caller owns bounded batch iteration and stopping criteria
   - read one due member from delayed index
   - validate work still exists
   - validate no active lease exists
   - remove delayed indexes for that entry
   - push ready message once
   - update ready/delayed counters once
   - update readyTasks once
2. Replace ready-head cleanup with Lua:
   - each Lua call handles one bounded cleanup step
   - Java caller owns bounded loop and stopping criteria
   - inspect ready list head
   - remove stale head entries
   - preserve first valid ready message
   - remove readyTasks entry if queue is empty
   - update counters without underflow
3. Keep bounded batch size.
4. Do not scan whole namespaces on scheduling hot path.

Exit:

- concurrent delayed promotion cannot duplicate a message in ready queue
- counters remain stable under concurrent promotion / claim
- readyTaskIds remains bounded and does not perform unbounded cleanup
- Lua remains a small per-entry state transition, not a hidden batch scheduler
- CI can pass without depending on a later phase

## Phase TRS-D1: Residue Removal

Goal: remove old key dependencies, implementation-shape tests, and stale docs
after the proven path is live.

Scope:

1. Delete or demote tests that assert obsolete Redis key layout instead of
   runtime semantics.
2. Remove stale docs that describe WSR dispatch gate migration as incomplete.
3. Remove old task-work keys only after `TRS-C2` target keys and `TRS-M4`
   atomicity proof are in place.
4. Remove duplicate repair/diagnostic hot-path structures unless a bounded
   repair proof names them as required.
5. Keep trace/audit as the place for historical or operator analytics; do not
   keep runtime keys solely to make historical queries easier.

Exit:

- no two live runtime truth tracks remain for the same queue/lease/final fact
- docs no longer describe old dependency states as current truth
- cleanup residue does not drive scheduling correctness
- CI can pass without depending on a later phase

## Phase TRS-D2: Runtime Selection And Performance Proof

Goal: prove high-performance scheduling behavior with memory and Redis runtime
behind the same engine flow.

Scope:

1. Run the same scheduling E2E with memory and Redis runtime:
   - many tasks
   - many workers
   - delayed retry
   - lease expiry
   - duplicate result callbacks
   - worker disconnect/reconnect
2. Add a Redis runtime throughput smoke:
   - concurrent enqueue
   - concurrent ready discovery
   - concurrent claim
   - concurrent result apply
   - concurrent final commit
3. Track:
   - claimed messages per second
   - duplicate dispatch / duplicate claim count
   - stale result count
   - delayed promotion duplication
   - counter drift
   - scheduler wakeup lag
   - Redis command/script latency
4. Add source guards for:
   - `RedisTaskResultRuntime.commitVisibleFinal(...)` has no method-level
     `synchronized`
   - dispatch gate reads remain registry-backed

Exit:

- Redis runtime proves correctness under real concurrent clients
- memory runtime remains a correct embedded implementation
- task-flow load proof uses explicit WorkerGroup selector truth and starts
  `RuntimeReadyDispatchPump`, so BATCH refill is proven through runtime-ready
  recovery instead of direct dispatch events
- no task scheduling critical path is protected by a global JVM lock
- proof output identifies whether failures are duplicate claim, stale result,
  counter drift, wakeup lag, or lock regression

## Phase Verification Matrix

Each phase must be independently mergeable. A phase may add stronger tests than
listed here, but it must not rely on a later phase to make its own behavior
correct.

| Phase | Required verification | Proof intent |
| --- | --- | --- |
| `TRS-C0` | `git diff --check` plus source-scan evidence in the roadmap/doc update | Inventory only; no runtime behavior change. |
| `TRS-C1` | `./mvnw -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am test` | Shared contract proof covers memory and Redis, including Redis tests that cannot be masked by one JVM monitor. |
| `TRS-C2` | Runtime contract tests plus backend-specific Redis key-shape tests only where key layout itself is the subject | Contract semantics stay backend-neutral while Redis key convergence decisions are explicit. |
| `TRS-M1` | Redis result contract tests, Redis cross-client final commit test, and source guard for `commitVisibleFinal(...)` modifier | Stable final commit correctness comes from Redis/Lua, not a Java method monitor. |
| `TRS-M2` | Engine scheduling proof tests plus runtime contract tests | Reserve/claim/dispatch/result/refill does not duplicate work under concurrent assignment attempts. |
| `TRS-M3` | Engine lifecycle/scheduling tests plus source guard for registry-backed dispatch gate reads | Engine lock reduction does not weaken lifecycle locks or reintroduce an independent gate map. |
| `TRS-M4` | Redis work runtime tests with concurrent promotion/claim/result and counter invariant checks | Delayed/ready cleanup is atomic and bounded under concurrent Redis clients. |
| `TRS-D1` | Full touched-module test set plus source scans for removed old keys/docs | Residue removal does not leave two live runtime truth tracks. |
| `TRS-D2` | Memory and Redis scheduling E2E/proof scripts with reported metrics | Runtime selection is proven by real concurrent flow, not happy-path startup. |

Recommended local verification command for doc-only roadmap edits:

```bash
git diff --check
```

Recommended first code-change verification command:

```bash
./mvnw -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am test
```

If a phase touches engine scheduling or worker assignment, include the owning
engine tests with `-am` rather than relying on runtime module tests alone.

## Later Specialist Topic: Wakeup Hints Without Truth Drift

Goal: reduce scheduling latency without moving truth into events.

Scope:

1. Define wakeup hint events for:
   - ready enqueue
   - delayed/retry due
   - result final may refill task
   - worker capacity/gate change may refill task
2. Redis runtime may write durable wakeup hints after atomic state mutation.
3. Pub/sub may be added as a fast local hint.
4. Scheduler must always re-read runtime truth:
   - `readyTaskIds(...)`
   - `stats(taskId)`
   - `claimReady(...)`
5. Polling recovery remains a bounded safety net.

Exit:

- lost wakeup only delays scheduling
- duplicate wakeup does not duplicate claim
- stream/pubsub does not become queue truth

## Later Specialist Topic: Memory Runtime Lock Striping

Goal: make memory runtime mirror the same logical owner model without one broad
monitor around unrelated tasks.

Scope:

1. Replace broad method-level synchronization in memory work runtime with
   task-keyed or shard-keyed coordination.
2. Keep finality-sensitive mutations atomic per task/message:
   - claim
   - result apply
   - lease expiry
   - delayed promotion
3. Keep result runtime sequence/barrier mutation atomic per task.
4. Do not optimize memory by changing public runtime semantics.

Suggested shape:

```text
WorkRuntimeShard[N]
  ConcurrentHashMap task state
  per-task queue/lease mutation lock
  global due indexes guarded by small index lock or concurrent priority queue

ResultRuntimeShard[N]
  per-task seq and visible rows
  barrier maps
  staged callback indexes
```

Exit:

- unrelated tasks do not block behind one runtime-wide monitor
- same task/message invariants remain protected
- memory and Redis still pass the same contract tests

## Later Specialist Topic: Repair And Cleanup Specialist Pass

Goal: keep repair paths bounded without confusing them with normal scheduling.

Scope:

1. Script Redis staged callback cleanup:
   - delete staged draft
   - remove task stage index
   - remove task/message stage index
   - remove global stage zset entry
2. Script fully-converged barrier cleanup.
3. Keep repair candidate scans bounded by ZSET windows.
4. Do not let repair scans become the normal commit path.

Exit:

- repair cleanup is idempotent
- concurrent repair and final commit do not leave permanent index residue
- scan cost is bounded and documented

## Non-Goals

1. Do not redesign public task APIs.
2. Do not move scheduling truth into projection storage.
3. Do not make Redis Stream the only queue truth.
4. Do not require Redis Cluster support in the first lock-reduction slice.
5. Do not remove polling recovery just because wakeup hints exist.
6. Do not optimize memory runtime at the cost of Redis contract semantics.
7. Do not introduce global distributed locks.
8. Do not rework worker-slot registry in this roadmap except where the
   task-worker mainline consumes its existing reserve/confirm/release contract.
9. Do not make delayed/repair specialist optimizations part of the first
   mainline implementation slice.

## Risk Matrix

| Risk | Likely cause | Required proof |
| --- | --- | --- |
| duplicate ready messages | concurrent delayed promotion / stale ready cleanup | cross-client Redis promotion test |
| duplicate active leases | concurrent claim | shared contract + Redis cross-client test |
| counter drift | multi-command cleanup / promotion | counter invariant test after concurrency |
| hidden JVM lock dependency | same runtime instance only tests | Redis tests using independent clients |
| assignment single-lane bottleneck | every wakeup routed through one lane | mainline proof with runtime ready pump and duplicate wakeups |
| task write lock overreach | runtime mutation still wrapped by lifecycle lock | lock budget proof and targeted engine tests |
| dispatch gate truth drifts | independent gate map reintroduced outside `WorkerRegistry` | source guard plus scheduling candidate tests |
| wakeup truth drift | stream/pubsub treated as queue truth | lost/duplicate wakeup tests |
| repair scan overload | unbounded result/stage scans | bounded scan tests and metrics |
| memory/Redis divergence | implementation-specific behavior | shared contract suite |

## First Slice Status And Next Slice

Completed first-slice work:

1. `TRS-C0` inventory and lock/key classification are captured in this
   roadmap.
2. `TRS-C1` mainline concurrency tests cover:
   - concurrent claim uniqueness
   - concurrent final commit uniqueness
   - duplicate assignment attempts do not duplicate dispatch
3. `RedisTaskResultRuntime.commitVisibleFinal(...)` no longer has a
   method-level JVM monitor.
4. Redis result commit has a reflection guard plus concurrent cross-connection
   tests.
5. Dispatch gate reads remain registry-backed through existing architecture
   guard coverage.
6. Memory and Redis runtime contract tests are the verification baseline.
7. Redis delayed promotion and ready-head cleanup now use small bounded Lua
   transitions instead of Java multi-command mutation loops.

Next implementation slice:

1. Continue `TRS-M3` only with explicit race proof for any result-ingest
   task-read-lock reduction.
2. Do not remove lifecycle task write locks for approve/reject/pause/resume,
   intake append/seal, terminal transition, or progress reconciliation.
3. Start `TRS-D1` residue removal only after verifying no tests still assert
   obsolete Redis implementation shape as scheduling truth.
4. Keep deeper Redis concurrent duplicate-dispatch and wakeup-lag proof in
   `TRS-D2` unless `TRS-M3` changes the claim/dispatch atomic boundary.

Do not expand Lua into scheduling policy or batch orchestration. The landed
`TRS-M4` slice keeps Lua limited to one delayed member promotion or one bounded
ready-head cleanup step while Java owns iteration and stopping conditions.

## Review Questions Before Implementation

1. Which `TaskManager.withTaskLock(...)` calls are still lifecycle correctness
   locks, and which are runtime hot-path leftovers?
2. Should `TaskAssignWorker` remain lane-single-threaded for session/interactive
   work while bulk work is primarily runtime-pump driven?
3. What exact concurrency proof should be considered sufficient before removing
   a Redis JVM method lock?
4. Which memory runtime locks should stay until Redis runtime structure is
   already proven?
5. What metrics should be emitted by proof tests so CI failures explain whether
   the issue is duplicate claim, stale result, wakeup lag, or counter drift?

## Final Target

```text
append work item
  -> TaskWorkRuntime enqueue Lua / per-task memory atomic section
  -> wakeup hint emitted after truth mutation
  -> scheduler reads bounded ready task ids
  -> worker candidate/admission policy
  -> TaskWorkRuntime claim Lua / per-task memory atomic section
  -> dispatch binding
  -> transport dispatch
  -> result callback
  -> TaskWorkRuntime applyResultWithContext atomic mutation
  -> TaskResultRuntime stable-final commit atomic mutation
  -> bounded progress / refill / terminal convergence
  -> bounded repair / trace / projection side effects
```

Scheduling correctness comes from runtime-owned atomic transitions. Wakeups,
streams, polling, traces, and projections help the system move and explain
itself; they do not own task queue or finality truth.
