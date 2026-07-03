# Task Runtime Redis Backlog Keyspace Rewrite Roadmap

Status: active.

Current cursor: RBR-0, current Redis runtime keyspace inventory and proof
classification.

Artifact role: active-contract for the first Redis runtime rewrite slice. This
roadmap is intentionally narrower than the score-band task
scheduling direction. It changes the existing `TaskWorkRuntime` Redis backing
shape in place and does not create a new module.

## Purpose

Move the existing Redis `TaskWorkRuntime` from one-key-per-ready-item storage to
a compact backlog / active-runtime shape:

```text
append/enqueue
  -> task-local backlog LIST
  -> bounded backlog availability discovery index
  -> claim moves one frame into active runtime state
  -> result/retry/finality removes active state or requeues a frame
```

The purpose is to establish the large-backlog runtime structure first, without
also redesigning task lifecycle or task scheduling score semantics in the same
roadmap.

## Current Code Observations

Current serving owner and hot path:

```text
TaskManager.enqueueTaskWork(...)
  -> TaskWorkRuntime.enqueue(...)
RuntimeReadyDispatchPump / startup recovery
  -> TaskRuntimeRecoveryPort.getRuntimeDispatchableTasks(...)
  -> TaskWorkRuntime.readyTaskIds(limit)
SimpleTaskDispatchBinder
  -> TaskAssignmentRuntimePort.claimReady(...)
  -> TaskWorkRuntime.claimReady(...)
TaskResultService / lease expiry path
  -> TaskWorkRuntime.applyResultWithContext(...) / applyResult(...)
```

Current Redis implementation:

- `platform_infra/mass-runtime-api` owns `TaskWorkRuntime`.
- `platform_infra/mass-runtime-redis` owns `RedisTaskWorkRuntime` and
  `RedisTaskWorkKeyspace`.
- `enqueue` currently writes `task:{taskId}:work:{messageId}` per accepted
  item, pushes `messageId` into `task:{taskId}:ready`, and updates
  `ready:tasks`.
- `claimReady` currently reads per-item work hashes, writes per-item lease
  hashes, and maintains active membership.
- `applyResult` currently reads/removes the active lease and either deletes the
  per-item work hash, schedules retry, or writes a recent-final receipt.
- Current Redis also maintains `worker:{workerId}:active` as a task-runtime
  reverse index for `hasActiveLeaseForWorker(taskId, workerId)`. This is not
  target task-runtime truth. Worker-dimensional active state belongs to
  worker-runtime; task-runtime may answer the existing method from task-local
  active runtime state.

The old shape works functionally, but it allocates Redis objects proportional
to accepted backlog size. That is the wrong production shape for large
batch-style tasks.

## Owner Decision

This roadmap keeps the owner as the existing runtime contract:

```text
platform_infra/mass-runtime-api
  TaskWorkRuntime semantic contract

platform_infra/mass-runtime-redis
  Redis keyspace and atomic implementation

xa-mass-engine
  orchestration and current caller of TaskWorkRuntime
```

No `xa-mass-task-runtime` module is introduced in this roadmap.

The Java boundary remains stable:

```text
engine callers
  -> TaskWorkRuntime / TaskResultRuntime
  -> Redis implementation
```

This roadmap does not introduce a new engine-facing runtime API. It replaces
the Redis physical shape behind the current `TaskWorkRuntime` methods.

`ready:tasks` or its successor remains a runtime discovery index, but it is
not task lifecycle truth and not task score-band scheduling truth. In this
roadmap it means only:

```text
this task may have ready backlog frames worth trying to claim
```

It must not be documented or used as:

- task status;
- lifecycle gate;
- score-band scheduling state;
- policy admission;
- task terminal truth.

## Target Keyspace

Use the existing namespace owner class unless implementation proves a rename is
necessary:

```text
namespace = xa:mass:runtime:v1
```

### Global Discovery And Runtime Indexes

```text
{ns}:tasks
  type: SET
  member: taskId
  purpose: bounded ownership registry for cleanup and diagnostics

{ns}:ready:tasks
  type: ZSET
  member: taskId
  score: first known backlog-ready time or next backlog visibility time
  purpose: ready-backlog discovery for readyTaskIds(limit)
  not: lifecycle score-band task state

{ns}:delayed:work
  type: ZSET
  member: encoded taskId + messageId
  score: nextVisibleAtMillis
  purpose: current delayed retry / delayed visibility discovery
  note: can remain in this first roadmap; score-band scheduling does not land here

{ns}:lease:expiry
  type: ZSET
  member: encoded taskId + messageId
  score: leaseExpireAtMillis
  purpose: existing lease expiry polling

{ns}:recent-final
  type: ZSET
  member: encoded taskId + messageId
  score: completedAtMillis
  purpose: bounded recent-final trimming

{ns}:stats
  type: HASH
  purpose: runtime-wide counters
```

### Task-Local Backlog And Runtime Keys

```text
{ns}:task:{taskId}:backlog
  type: LIST
  value: BacklogFrame
  purpose: accepted ready backlog, append-time storage
  replaces: task:{taskId}:ready as a list of message ids plus per-item work hashes

{ns}:task:{taskId}:rt
  type: HASH
  field: messageId
  value: ActiveRuntimeFrame
  purpose: sparse active lease/runtime state created only by claim
  replaces: task:{taskId}:lease:{messageId}

{ns}:task:{taskId}:delayed
  type: ZSET
  member: messageId
  score: nextVisibleAtMillis
  purpose: current task-local delayed retry / delayed visibility index
  note: allowed in this roadmap because it is item retry visibility, not task score

{ns}:task:{taskId}:delayed:frames
  type: HASH
  field: messageId
  value: BacklogFrame
  purpose: delayed retry frame payload when a retry should not immediately re-enter backlog
  note: implementation may reuse a compact equivalent if tests prove no per-ready-item key allocation

{ns}:task:{taskId}:recent-final
  type: SET
  member: messageId
  purpose: bounded recent-final ownership set

{ns}:task:{taskId}:recent-final:{messageId}
  type: HASH
  purpose: bounded recent-final receipt
  note: can stay for this roadmap; long-term result ledger is out of scope

{ns}:task:{taskId}:stats
  type: HASH
  purpose: task-local counters
```

### Retired By This Roadmap

```text
{ns}:task:{taskId}:work:{messageId}
  retired because ready backlog must not allocate one Redis key per item

{ns}:task:{taskId}:lease:{messageId}
  retired because active runtime state lives in task-local rt HASH

{ns}:worker:{workerId}:active
  retired because worker-dimensional active truth belongs to worker-runtime
```

`TaskWorkRuntime.hasActiveLeaseForWorker(taskId, workerId)` remains as a Java
API during this roadmap, but the Redis implementation must answer it from
`task:{taskId}:rt` by bounded task-local inspection. It must not maintain a
worker reverse index in task runtime.

### Frame Shapes

Backlog frame:

```text
messageId
eventCode
payloadJson or payloadRef
retryCount
maxRetryCount
shardKey
createdAtMillis
```

Active runtime frame:

```text
messageId
eventCode
payloadJson or payloadRef
retryCount
maxRetryCount
workerId
workerGroupId
batchId
selectionToken
scoreBandClaimScore
leaseToken
leaseExpireAtMillis
leasedAtMillis
createdAtMillis
```

These are Redis implementation frames, not new public DTOs. The public Java
surface remains the existing `TaskWorkEnvelope`, `ClaimedTaskWork`,
`ActiveLeaseRecord`, `RuntimeResultApplyContext`, and `TaskWorkResult`.

## Runtime Semantics To Preserve

The rewrite must preserve the current serving contract:

```text
readyTaskIds(limit)
  returns task ids with backlog visibility through a bounded runtime index

claimReady(taskId, workers, options)
  exclusively moves claimable backlog frames into active runtime state

applyResultWithContext(result)
  validates active runtime state, mutates result/retry/finality, and returns
  the pre-apply runtime context needed by engine

pollExpiredLeases(limit, now)
  discovers expired active runtime frames through a bounded expiry index

stats(taskId) / stats()
  report ready/inflight/delayed/success/failed/expired support counters
```

`stats` are not a UI-only convenience in the current engine. They feed task
progress and terminal convergence support paths, so this roadmap must not
replace them with zeros or loosely sampled diagnostics. Exact counter repair
does not need to become a new heavy mechanism, but the current counter meaning
must stay coherent across append, claim, retry, result, expiry, and discard.

Recent-final receipts remain bounded runtime duplicate/late callback evidence.
They are kept in this roadmap, but they are not a durable result ledger.

## Atomic Boundaries

Redis mutations that cross multiple runtime keys must remain atomic through Lua
or an equivalent Redis atomic boundary.

Required atomic boundaries:

```text
enqueue
  backlog frame append + ready discovery index + stats/backpressure

claimReady
  backlog pop + rt write + lease expiry index + counters

applyResultWithContext / applyResult
  rt validation/removal + retry/final mutation + recent-final + counters

promote delayed retry
  delayed index removal + delayed frame load/removal + backlog append +
  ready discovery index + counters

pollExpiredLeases
  bounded expiry discovery and stale-entry handling

discardTask
  task-local runtime key deletion + global index cleanup
```

This is not a request for stronger business consistency than today. It is the
minimum Redis runtime truth boundary needed to avoid half-moved backlog,
half-active lease, or half-retried item state.

## Cutover And Redis Data Compatibility

This roadmap does not provide online migration for existing Redis runtime data.
The acceptable cutover model is:

```text
clean runtime Redis namespace
or
explicit runtime Redis reset before starting the new implementation
```

If a production deployment later needs old-key live migration, that is a
separate migration roadmap. Do not pollute this keyspace rewrite with dual
read/write compatibility.

## Explicit Non-Goals

- No new module.
- No `xa-mass-task-runtime` package/module resurrection.
- No task lifecycle command rewrite.
- No `task:score:{laneKey}` lifecycle/scheduling ZSET.
- No score-band task scheduling semantics.
- No `TaskManager` deletion.
- No worker-runtime API redesign.
- No transport result ingress redesign.
- No server view/API cleanup.
- No durable result ledger.
- No caller idempotency-key guarantee beyond the current runtime contract.
- No claim that Redis node loss is zero-loss unless the runtime profile and
  Redis durability settings prove it.
- No online migration from old Redis runtime data.

## Allowed Scope

- Change `RedisTaskWorkKeyspace` key names and helpers.
- Change `RedisTaskWorkRuntime` Lua scripts and Java mapping.
- Update Redis runtime tests and shared runtime contract tests where the old
  physical key shape is asserted.
- Update `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md` after
  code lands.
- Add focused architecture or source guards only after the new keyspace is
  serving.

## Forbidden Drift

- Do not introduce a second production runtime next to `TaskWorkRuntime`.
- Do not keep both old per-item `work:{messageId}` and new backlog frames as
  writable truth.
- Do not add a compatibility fallback that makes old and new key families both
  serving truth.
- Do not make append rewrite lifecycle score, task status, or task policy.
- Do not make `ready:tasks` a lifecycle state or scheduling score-band.
- Do not introduce Redis `SCAN` into hot paths.
- Do not use server review rows, storage task rows, or trace materialization to
  reconstruct runtime queue truth.
- Do not maintain worker-dimensional active lease truth inside task runtime.
  `worker:{workerId}:active` is not a target key.
- Do not add worker-runtime, transport, or task lifecycle concepts into Redis
  backlog frames beyond the fields already needed by current runtime claim and
  result paths.

## RBR-0 Current Keyspace And Test Inventory

Goal:

Inventory current Redis keys, scripts, tests, and callers before changing
runtime shape.

Scope:

- `RedisTaskWorkKeyspace`
- `RedisTaskWorkRuntime`
- current Lua scripts and their key/argument contracts
- Redis runtime tests under `platform_infra/mass-runtime-redis`
- shared `TaskWorkRuntime` contract tests
- engine callers of `readyTaskIds`, `claimReady`, `applyResultWithContext`,
  `pollExpiredLeases`, `discardTask`

Acceptance:

- Current old keys are classified as:
  - keep unchanged;
  - rewrite into backlog / rt shape;
  - keep temporarily as delayed/recent-final support;
  - retire as wrong-owner reverse index;
  - test-only assertion to update.
- No production caller outside `TaskWorkRuntime` is found reading Redis task
  work keys directly.
- No production caller outside `TaskWorkRuntime` depends on the physical
  `worker:{workerId}:active` Redis key.
- All current Redis atomic scripts are classified by target mutation:
  enqueue, claim, apply result, promote delayed, poll expired, discard.
- Existing Redis data compatibility is explicitly classified as out of scope;
  clean namespace/reset is the cutover assumption.
- The first implementation slice can name the old key write it closes.

Verification:

```powershell
rg -n "taskReadyQueue|taskWorkHash|taskLeaseHash|taskActiveSet|readyTasksZset|delayedWorkZset|leaseExpiryZset" `
  platform_infra/mass-runtime-redis platform_infra/mass-runtime-api xa-mass-engine `
  --glob "*.java" --glob "!**/target/**"
```

## RBR-1 Backlog Append Rewrite

Goal:

Change Redis append/enqueue so accepted ready items are stored as frames in a
task-local backlog LIST, not as per-item Redis work hashes.

Old path to close:

```text
enqueue
  -> HSET task:{taskId}:work:{messageId}
  -> RPUSH task:{taskId}:ready messageId
```

Target path:

```text
enqueue
  -> RPUSH task:{taskId}:backlog BacklogFrame
  -> update ready-backlog discovery index
```

Acceptance:

- `enqueue` of ready work does not create
  `task:{taskId}:work:{messageId}`.
- `readyTaskIds(limit)` can still discover the task through a bounded runtime
  index.
- `TaskWorkRuntime.stats(taskId)` and global stats remain meaningful enough
  for current engine progress paths.
- Backpressure behavior remains bounded and does not require scanning backlog
  frames.
- Enqueue remains one atomic Redis mutation for backlog frame write, ready
  discovery index update, and counters/backpressure.
- Existing in-repo callers of `TaskWorkRuntime.enqueue` compile unchanged.

Verification:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -Dtest=RedisTaskWorkRuntimeTest test
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -Dtest=RedisTaskWorkRuntimeContractTest test
```

## RBR-2 Claim Moves Backlog Frame To Active Runtime

Goal:

Change Redis claim so it atomically moves backlog frames into sparse active
runtime state.

Old path to close:

```text
claimReady
  -> LPOP task:{taskId}:ready messageId
  -> HGET task:{taskId}:work:{messageId}
  -> HSET task:{taskId}:lease:{messageId}
```

Target path:

```text
claimReady
  -> LPOP task:{taskId}:backlog BacklogFrame
  -> HSET task:{taskId}:rt messageId ActiveRuntimeFrame
```

Acceptance:

- Claimed payload is reconstructed from the popped frame.
- `getActiveLease`, `activeLeases`, and `applyResultWithContext` can read the
  active runtime frame without a per-item lease hash.
- `hasActiveLeaseForWorker(taskId, workerId)` is implemented from
  task-local `rt` state and does not write or read `worker:{workerId}:active`.
- Claim is atomic with respect to backlog pop and active runtime write.
- If no backlog frame is available, claim returns empty without mutating task
  lifecycle or score state.
- `readyTaskIds(limit)` removes or repairs stale backlog discovery entries in a
  bounded way.
- Claim does not fall back to `task:{taskId}:work:{messageId}` to reconstruct
  payload or retry metadata.

Verification:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -Dtest=RedisTaskWorkRuntimeTest test
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=TaskRuntimeRecoveryPortTest,SimpleTaskDispatchBinderTest test
```

## RBR-3 Result, Retry, And Recent-Final Rewrite

Goal:

Change Redis result apply so active runtime frames are the only accepted apply
truth, and retry requeues frames without recreating append-time per-item work
hashes.

Old path to close:

```text
applyResult
  -> HGET/HDEL task:{taskId}:lease:{messageId}
  -> HGET/HDEL or HSET task:{taskId}:work:{messageId}
```

Target path:

```text
applyResult
  -> HGET/HDEL task:{taskId}:rt messageId
  -> success/final: recent-final receipt
  -> retry: backlog frame or delayed retry frame
```

Acceptance:

- Success/final removes the active runtime frame.
- Retryable failure removes the active runtime frame and requeues the same
  logical `messageId` as a backlog or delayed frame.
- Retry frame reconstruction uses the active runtime frame. It must not require
  the retired `task:{taskId}:work:{messageId}` key.
- Duplicate/late result handling remains runtime-first through active runtime
  frames plus bounded recent-final receipts.
- `applyResultWithContext` still returns the context needed by engine result
  handling without additional storage/review reads.
- No server review row is required for callback acceptance.

Verification:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -Dtest=RedisTaskWorkRuntimeTest,RedisRuntimeTraceIntegrationTest test
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest test
```

## RBR-4 Delayed Retry, Lease Expiry, And Discard Closure

Goal:

Close the remaining old per-item key assumptions around delayed visibility,
lease expiry, and task discard.

Acceptance:

- Delayed retry visibility can promote due frames into backlog without
  creating per-ready-item keys.
- Delayed retry frame payload comes from `task:{taskId}:delayed:frames` or an
  equivalent compact task-local frame store, not from retired per-item work
  hashes.
- `pollExpiredLeases(limit, now)` returns active runtime frames from the
  existing bounded lease-expiry index or a documented replacement.
- `discardTask(taskId)` deletes backlog, active runtime, delayed retry,
  recent-final, stats, and discovery entries without namespace scan.
- No old per-item work/lease key is needed for precise discard.
- `discardTask(taskId)` does not need to clean task-runtime
  `worker:{workerId}:active` keys because that key family is not written in the
  target shape.

Verification:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -Dtest=RedisTaskWorkRuntimeTest test
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=TaskKernelLifecycleTest,TaskIdleClosePolicyBehaviorTest test
```

## RBR-5 Baseline, Guard, And Current-Doc Update

Goal:

Make the implemented Redis keyspace the current baseline and prevent old
per-item ready key writes from returning.

Acceptance:

- `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md` describes the
  implemented backlog / active runtime shape.
- Focused source/keyspace tests prove ready append does not create old
  `work:{messageId}` keys.
- Guard or focused test proves production Redis runtime does not write both old
  per-item work hashes and new backlog frames for ready append.
- Guard or focused test proves production Redis task runtime does not write
  `worker:{workerId}:active`.
- Guard or focused test proves delayed retry promotion does not read retired
  per-item work hashes as the delayed frame source.
- `TaskWorkRuntime` JavaDoc is revised only where the implemented semantics
  changed. Do not expand the public API surface.
- Score-band task scheduling remains explicitly out of scope and points to
  `TASK_RUNTIME_SCORE_BAND_SCHEDULING_ROADMAP.md`.

Verification:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-redis -am -DskipTests test-compile
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -Dtest=RedisTaskWorkRuntimeTest,RedisTaskWorkRuntimeContractTest test
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=EngineSchedulingCoreArchitectureGuardTest test
.\mvnw.cmd clean -DskipTests test-compile
```

## Completion Criteria

This roadmap is complete only when:

- Redis ready append stores backlog frames without per-ready-item Redis keys.
- Claim creates sparse active runtime state proportional to active leases, not
  accepted backlog size.
- Result/retry/finality converges through active runtime frames and requeued
  backlog/delayed frames.
- `readyTaskIds -> claimReady -> applyResult` remains the current serving
  engine path.
- Old Redis per-item ready work hashes are no longer written by production
  Redis append.
- Retry and delayed promotion no longer depend on old per-item work hashes.
- Redis task runtime no longer writes `worker:{workerId}:active`; worker
  reverse active truth is not maintained by task runtime.
- Runtime stats remain coherent enough for current engine progress and
  terminal convergence paths.
- The current Redis baseline doc and focused tests describe implemented
  behavior, not target prose.
- No task lifecycle score-band mechanism is introduced by this roadmap.

## Stop Triggers

Stop and re-coordinate if implementation requires any of these:

- changing public server or SDK task APIs;
- creating a new task-runtime module;
- making task lifecycle status live in Redis backlog keys;
- introducing `task:score:{laneKey}` to make this roadmap pass;
- replacing worker-runtime selection or transport dispatch semantics;
- accepting a namespace scan or storage/review scan as runtime recovery.
- requiring live migration from old Redis runtime keyspace instead of clean
  namespace/reset cutover.
