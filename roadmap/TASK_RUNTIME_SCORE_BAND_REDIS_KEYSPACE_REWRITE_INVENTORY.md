# Task Runtime Score-Band Redis Keyspace Rewrite Inventory

Status: completed current-code inventory for
`TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_ROADMAP.md`. SBRK-0D
pre-mechanism cleanup has fresh compile/guard proof: old command-bucket DTO
source files are deleted from core main, and remaining old DTO names are guard
strings only. Existing score-band Redis code and grouped-port delegation have
advanced past SBRK-1/SBRK-3/SBRK-4/SBRK-5 focused proof:
`RedisTaskRuntime` delegates to the score-band implementation, Redis mutation
gaps are closed through owner-local Lua scripts, and ordered final-window reads
are separated from the core `TaskRuntimePortSet`. Current proof also closes the
stale `ScoreCandidate` fence and active-only visibility gaps: claim validates
lane/gate/epoch/fence/observed-score before moving backlog, and empty-backlog
active tasks stay visible through `TaskScoreV1.MAINT_ACTIVE` for repair/close
without entering dispatch discovery. SBRK-6 guard/proof-registry proof, server
trace proof, and production-source residue scans are closed.

Rows in this file are current worktree facts, not broad completion claims. If a
row mentions Redis grouped implementation movement, treat it as focused
SBRK-1/SBRK-3/SBRK-4/SBRK-5 proof unless the row explicitly names a separate
future server/SDK read-model cleanup.

This file is the SBRK-0A closure matrix. It tracks old runtime API surfaces by
interface family so SBRK-0B can narrow callers before score-band Redis becomes
serving truth.

## Old Runtime Surface Closure Matrix

| Current method | Current callers | Old keys forced | Target grouped method | SBRK-0B action | SBRK-4 serving cutover action | SBRK-5 delete/guard action | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `TaskRuntimeAppendPort#appendBatch(AppendBatchCommand)` | old callers migrated; old port/DTO deleted from core main | `ids`, `ready`, `tasks`, `dirty` | `TaskRuntimeWorkPort#appendBacklog(taskId, items, maxAppendBatchSize)` | Serving append callers use `appendBacklog` with `AppendItemInput`. | Newly accepted Redis work writes only `<tr>:task:{taskId}:backlog`; append does not score-enroll. | Keep guards preventing old append port/DTO re-entry. | Closed for core/starter/engine/Redis serving. |
| `TaskRuntimeSchedulerPort#discoverEligibleTasks(SchedulerDiscoveryCommand)` | old callers migrated; old port/DTO deleted from core main | `tasks`, `eligibility`, `ready`, `dirty` | `TaskRuntimeScorePort#discoverSchedulable(laneKey, maxScore, limit)` | Serving scheduler callers use score discovery; no global task scan target API. | Discovery reads `<tr>:task:score:{laneKey}` plus task-local `meta`; no `SMEMBERS <tr>:tasks`. | Keep guards preventing old scheduler port/DTO and dirty/global-scan re-entry. | Closed for core/starter/engine/Redis serving. |
| `TaskRuntimeSchedulerPort#markTaskDirty(String)` | old method deleted from target ports | `dirty` | No target core method. Later wakeup must be separate best-effort signal if needed. | Removed from serving caller surface. | Score owner/state transition, not dirty hints, controls scheduling visibility. | Guard that `markTaskDirty` cannot reappear in target runtime ports. | Closed for production surface. |
| `TaskRuntimeClaimPort#claimReady(ClaimReadyCommand)` | old command DTO deleted; engine assignment keeps only direct vocabulary method | `ready`, `active`, `worker:{workerId}:active`, `dirty` | `TaskRuntimeWorkPort#claimBacklog(ScoreCandidate, reservations, maxItems, leaseMillis, nowMillis)` | Engine-facing `claimReady` uses direct parameters and delegates to `claimBacklog`. | Claim atomically validates candidate lane/gate/epoch/fence/observed-score, rejects non-dispatch-band candidates, and moves `backlog -> rt`; no worker reverse index; candidate comes from score discovery or score-owner point-read. | Keep guards preventing old claim DTO/port re-entry. | Closed for core/starter/engine/Redis serving. |
| `TaskRuntimeResultPort#getResultCorrelation(taskId, messageId)` | old result port deleted; read surface owns point correlation | `active` and indirectly old result port read/write coupling | `TaskRuntimeReadPort#resultCorrelation(taskId, messageId)` | Serving-lane correlation reads use read surface. | Correlation reads task-local `rt`, independent of result apply port. | Keep old result-port guard; facade vocabulary can be renamed separately if useful. | Closed for core Redis/runtime surface. |
| `TaskRuntimeResultPort#applyResult(ResultApplyCommand)` | old result port/DTO deleted from core main | `active`, `delayed`, `ready`, `final:*`, `dirty`, `worker:{workerId}:active` | `TaskRuntimeConvergencePort#applyResult(RuntimeResultFact)` plus runtime-owned retry/finality policy in meta/rt | Serving result apply uses convergence fact API. | Result apply validates `rt` and mutates `rt`, `retry:*`, `result`; retry/finality policy comes from runtime-owned state, not engine snapshots. | Keep old result command guard. | Closed for core/starter/engine/Redis serving. |
| `TaskRuntimeRepairPort#pollExpiredActiveLeases(PollActiveLeaseRepairCommand)` | old repair port/DTO deleted from core main | `tasks`, `active` | `TaskRuntimeConvergencePort#scanExpiredLeases(laneKey, nowMillis, taskLimit, itemLimit)` + `applyResult(LEASE_TIMEOUT)` | Serving repair uses convergence surface. | Lease scan discovers score-visible expired active work through non-negative task score bands, including `MAINT_ACTIVE`; timeout mutation happens through result apply so engine/review events are not bypassed. No `task:active:{laneKey}`. | Keep old repair command guard. | Closed for core/starter/engine/Redis serving. |
| `TaskRuntimeRepairPort#getActiveWorkForWorker(ActiveWorkQuery)` | old worker-active reverse lookup deleted from target core runtime surface | `worker:{workerId}:active` or global scan | No target core method. Use `TaskRuntimeReadPort#activeWorkForTask(taskId, limit)` for task-local point reads. | Serving worker-active hint moved to task-local read plus worker filter. | No worker reverse index in core task-runtime. | Guard against `worker:*:active` key builders. | Closed for core runtime surface. |
| formerly `TaskRuntimeReadPort#readFinalResults(FinalResultReadRequest)`, now `TaskRuntimeResultWindowReadModel#readFinalResults(FinalResultReadRequest)` | server/engine read paths through serving lane; old contract tests; Redis/memory retention tests; starter proof tests | `final:order`, `final:seq`, ordered projection pressure | `TaskRuntimeReadPort#finalResult(taskId, messageId)` for core point read; `TaskRuntimeResultWindowReadModel` only as non-core view/read-model surface | Core read-port exposure is closed; keep ordered window off `TaskRuntimeReadPort` and out of score-band runtime proof. | Core score-band runtime stores `<tr>:task:{taskId}:result` by message id; ordered windows must be side projection or removed and cannot force Redis runtime truth. | Guard against `TaskRuntimeResultWindowReadPort`, `final:order`, and `final:seq` in core runtime truth. | Re-owned outside the core port set: `TaskRuntimePortSet` no longer extends ordered result-window reads; serving lane gets a separate read model. |
| formerly `TaskRuntimeProgressPort#progressSnapshot(taskId)` | deleted old interface; memory/runtime implementations expose the method through `TaskRuntimeReadPort` | old ready/delayed/active/final counters | `TaskRuntimeReadPort#progressSnapshot(taskId)` | Fold caller use into read port; no separate progress-only core port remains. | Progress reads task-local score-band keys only. | Guard against `TaskRuntimeProgressPort` reappearing as a target runtime port. | Core exposure closed: `TaskRuntimeProgressPort` was deleted; progress lives on `TaskRuntimeReadPort`. |
| `TaskRuntimeDiscardPort#discardTaskRuntime` / `discardTaskWork` | old discard port/command DTOs deleted from target core runtime surface | old ready/delayed/active/final/eligibility/tasks/dirty cleanup | `TaskRuntimeConvergencePort#discardRuntime` / `discardWork` | Serving discard uses convergence surface. | Redis discard scripts clean score-band task-local work/runtime truth and score membership without old key fallback. | Keep guards preventing old discard port/command DTO and old key cleanup re-entry. | Closed for core/starter/engine/Redis serving; deeper hard-discard fencing is future policy, not V0 score-band keyspace residue. |

## SBRK-0B Closure Evidence And SBRK-0D Proof Status

- Old command-bucket port interfaces are deleted from `xa-mass-task-runtime`
  main source: `TaskRuntimeAppendPort`, `TaskRuntimeSchedulerPort`,
  `TaskRuntimeClaimPort`, `TaskRuntimeResultPort`, `TaskRuntimeRepairPort`,
  `TaskRuntimeProgressPort`, and `TaskRuntimeDiscardPort`.
- `TaskRuntimeArchitectureGuardTest` guards those old port source files remain
  absent and checks the target public port set:
  `TaskRuntimeWorkPort`, `TaskRuntimeScorePort`,
  `TaskRuntimeConvergencePort`, and `TaskRuntimeReadPort`. Ordered result
  windows are separated into `TaskRuntimeResultWindowReadModel`.
- Old command DTOs such as `AppendBatchCommand`, `ClaimReadyCommand`,
  `ResultApplyCommand`, `PollActiveLeaseRepairCommand`, active work query
  records, and discard command records are deleted from `xa-mass-task-runtime`
  main source. Remaining mentions are guard strings only.
- SBRK-0B compile/guard proof was recorded for the cross-module test-compile
  gate plus task-runtime, memory-runtime, starter, Redis, and engine focused
  suites. This validates old exposure pre-convergence only; it is not
  SBRK-3/SBRK-4 score-band Redis proof or serving cutover.
- Current SBRK-0D proof status: closed. Fresh proof passed after old command DTO
  deletion and test/fixture migration:
  `mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine,xa-mass-engine-starter -am -DskipTests test-compile`;
  `mvn -pl xa-mass-task-runtime '-Dtest=TaskRuntimeArchitectureGuardTest,TaskRuntimeContractShapeTest' test`;
  `mvn -pl platform_infra/mass-task-runtime-redis -Dtest=RedisTaskRuntimeArchitectureGuardTest test`;
  `mvn -pl sdk/xa-mass-task-runtime-starter-sdk '-Dtest=TaskRuntimeStarterArchitectureGuardTest' test`;
  `mvn -pl xa-mass-engine '-Dtest=TaskRuntimeServingLaneOldPathClosureGuardTest,TaskRuntimeEngineCutoverPreparationTest,TaskRuntimeRecoveryPortTest,TaskResultConcurrencyConvergenceTest' test`;
  `mvn -pl platform_infra/mass-task-runtime-memory -am test`.

## Current Grouped Surface

| Target family | Current symbol | Status | Notes |
| --- | --- | --- | --- |
| Work | `TaskRuntimeWorkPort#appendBacklog`, `claimBacklog` | Focused serving proof closed | `RedisTaskRuntime` delegates to `RedisScoreBandTaskRuntime`; append writes `backlog`; claim validates dispatch-band `ScoreCandidate` lane/gate/epoch/fence/observed-score and atomically moves `backlog -> rt` through Lua. |
| Score | `TaskRuntimeScorePort#putRuntimeMeta`, `setTaskScore`, `removeTaskScore`, `scoreCandidate`, `discoverSchedulable` | Focused serving proof closed | Redis score discovery and point-read read lane `task:score` plus task-local `meta`; dispatch discovery reads only due-time band. `MAINT_ACTIVE` keeps active-only tasks visible for maintenance without dispatch empty scans. |
| Convergence | `TaskRuntimeConvergencePort#promoteDueRetries`, `scanExpiredLeases`, `applyResult(RuntimeResultFact)`, `closeIfDrained`, `discardRuntime`, `discardWork` | Focused serving proof closed | Redis convergence uses `rt`, `retry:*`, and `result`; claim/retry/result/close/discard mutation boundaries are Lua-owned. Expired lease scan is discovery-only over non-negative score bands; timeout mutation enters through `applyResult(LEASE_TIMEOUT)`. |
| Read | `TaskRuntimeReadPort#finalResult`, `resultCorrelation`, `progressSnapshot`, `activeWorkForTask` | Core exposure closed for ordered windows | `resultCorrelation` was added to move point reads off `TaskRuntimeResultPort`; ordered `readFinalResults` is no longer part of the core read port. |
| Non-core read-model surface | `TaskRuntimeResultWindowReadModel#readFinalResults` | Re-owned outside core port set | This exists only to preserve current read-window callers. It is not part of the four core runtime ports and must not be used as score-band Redis proof. |

## Mixed Worktree Reconciliation Items

| Item | Current fact | Required SBRK-0C decision |
| --- | --- | --- |
| `RedisScoreBandTaskRuntime` | Present under the Redis implementation and reachable from `RedisTaskRuntime` grouped methods. | SBRK-1/SBRK-3/SBRK-4 focused proof accepted; keep old-key guards and continue SBRK-5 cleanup. |
| `RedisTaskRuntime` grouped-port delegation | `RedisTaskRuntime` is now a thin grouped-port wrapper. Old compatibility methods and old Redis key builders were removed. | Treat as the serving Redis cutover path for newly accepted runtime work; do not add old-key fallback. |
| Redis score-band tests | Focused tests cover keyspace, wrapper behavior, owner reconnect, network partition recovery, retry, lease repair, close, and discard through grouped APIs. | Keep tests free of old `ResultApplyCommand`, old ordered-window proof paths, worker-active reverse reads, and old key builders. |
| Starter/engine grouped surface | `TaskRuntimePortSet` and `TaskRuntimeServingLane` expose the narrowed grouped ports plus the separate non-core result-window read model. | Focused engine/starter proof closed; non-core read-window deletion is future server/SDK read-model cleanup, not a score-band Redis runtime blocker. |

## SBRK-0D Pre-Mechanism Cleanup Matrix

This matrix decides what may remain before Redis mechanism implementation
continues. It is about old surface pressure, not old Redis key deletion.

| Residue | Current fact | SBRK-0D status target | Next allowed action |
| --- | --- | --- | --- |
| Old command-bucket port source files | Deleted from `xa-mass-task-runtime/src/main`; guarded by `TaskRuntimeArchitectureGuardTest`. | `removed from production surface` | Keep guard. Do not recreate old ports as aliases. |
| Old command-bucket DTO source files | Deleted from `xa-mass-task-runtime/src/main`; old DTO names remain only in guard strings. | `removed from production surface` | Keep `TaskRuntimeArchitectureGuardTest`, Redis guard, starter guard, and engine old-path guard. Do not recreate as aliases. |
| Old command-style methods in memory/runtime tests | Core and memory contract tests use grouped methods; engine test wrapper old compatibility methods were removed. | `removed from production surface` | Any future helper must be test-local and must not reintroduce deleted core DTOs. |
| `TaskRuntimeResultWindowReadModel#readFinalResults` | Separate from core `TaskRuntimeReadPort` and `TaskRuntimePortSet`; still exists for current read-window callers. | `non-core read-model` | Keep out of score-band proof; delete later if server/SDK window callers are removed. |
| Engine-facing `claimReady` vocabulary | Engine assignment still uses the name, but no longer accepts `ClaimReadyCommand` or fabricates `ScoreCandidate`. | `implementation-local only` | Rename only if it reduces confusion; not required before SBRK-1 because old command bucket is closed. |
| `RedisScoreBandTaskRuntime` grouped delegation | Reachable through `RedisTaskRuntime` grouped methods. | `focused serving proof closed` | Keep Lua mutation boundaries and old-key guards; do not add old-key fallback. |
| Redis score-band keyspace tests | Tests cover keyspace, wrapper behavior, owner reconnect, network partition recovery, retry, lease repair, close, and discard. | `focused proof closed` | Keep assertions free of forbidden keys and old command DTO vocabulary. |
| Old Redis serving key builders | Removed or bypassed in current candidate path; Redis guard scans main sources for old-key and old-API vocabulary. | `SBRK-5 guard/cleanup` | Keep guard; delete stale docs/tests that still describe old Redis key builders as current serving truth. |
| Ordered result-window pressure | Core read port no longer owns it, but read-window callers still exist. | `temporary non-core read-model` | Must not force `final:order` / `final:seq`; later deletion belongs to server/SDK read-model cleanup. |
| Worker-active reverse lookup | Removed from target core runtime surface; task-local active reads remain. | `removed from production surface` unless diagnostics re-own later | Guard against `worker:*:active` as core task-runtime truth. |
| Dirty hint / global scan pressure | Target API no longer accepts `markTaskDirty` or global task discovery. | `removed from production surface` | Guard against dirty/global-scan re-entry before SBRK-1 implementation. |

## SBRK-1 Candidate Gap Matrix

These rows are code-grounded gaps that existed in the earlier
`RedisScoreBandTaskRuntime` advance candidate. Current status records whether
SBRK-1 closed the mutation boundary or left SBRK-5 residue.

| Owner path | Current behavior | SBRK-1 status | Remaining action |
| --- | --- | --- | --- |
| ClaimOwner `claimBacklog` | Lua atomically validates dispatch-band candidate lane/gate/epoch/fence/observed-score, moves bounded backlog frames into `rt`, and returns claimed work. If backlog drains while active remains, it moves score to `MAINT_ACTIVE`. | Closed. | Keep stale-candidate and maintenance-band rejection tests plus script source under old-key/API guard. |
| RetryOwner `promoteDueRetries` | Lua atomically moves due `retry:score` / `retry:item` entries back to backlog and removes retry residue. | Closed. | Keep proof that `retry:score`, `retry:item`, and backlog do not drift after promotion. |
| ResultOwner `applyResult` | Lua atomically validates active lease correlation and moves `rt` to `result`, retry keys, or stale/duplicate outcome. | Closed. | Keep stale/duplicate/retry/finality tests. |
| LeaseOwner `scanExpiredLeases` | Bounded scan uses non-negative lane score members, including `MAINT_ACTIVE`; expired mutation delegates to the same atomic result-apply boundary. | Closed for V0. | Keep owner reconnect and lease-repair proof; scan must not delete active state or schedule retry directly; do not add `task:active:{laneKey}` in V0. |
| CloseOwner `closeIfDrained` | Lua checks backlog, retry score, retry item, and `rt` before removing score membership. | Closed. | Keep close-with-retry-item-residue proof. |
| DiscardOwner `discardRuntime` / `discardWork` | Lua atomically counts and deletes owned local keys; `discardRuntime` also removes score membership. | Closed for V0 cleanup. | Broader hard-discard fencing beyond current epoch checks remains a future high-risk enhancement, not a blocker for the current score-band cutover proof. |
| Non-core result window | `readFinalResults` reads unordered result hash values behind `TaskRuntimeResultWindowReadModel`. | Closed for SBRK-5. | Re-owned outside core port set; later deletion belongs to server/SDK read-model cleanup, not score-band runtime proof. |

## SBRK-0B Immediate Closure Targets

- Keep serving-lane result correlation reads on
  `TaskRuntimeReadPort#resultCorrelation`.
- Keep serving-lane result apply on
  `TaskRuntimeConvergencePort#applyResult(RuntimeResultFact)` with
  retry/finality/retention policy stored in runtime-owned meta for V0.
- Do not reintroduce `TaskRuntimeResultPort#applyResult(ResultApplyCommand)` as
  a core port. If a same-signature method still exists in memory/runtime tests,
  keep it explicitly marked as old implementation/test residue; engine serving
  code must not import or construct it.
- Stop exposing worker-active reverse lookup to serving callers; use
  task-local `activeWorkForTask` and filter by worker id where a task id is
  already known.
- Do not claim score-band serving cutover while any runtime path still keeps
  old fallbacks for `appendBatch`, `claimReady`,
  `applyResult(ResultApplyCommand)`, core ordered final windows, or worker
  reverse active reads. If those fallbacks were deleted early, treat the state
  as SBRK-0C candidate evidence, not as cutover proof.
- `TaskRuntimePortSet` has stopped extending old append/scheduler/claim/
  repair/progress/discard/result ports.
- `TaskAssignmentRuntimePort#claimReady` no longer accepts
  `ClaimReadyCommand`; engine assignment keeps the method name as vocabulary but
  passes direct `taskId`, worker reservation evidence, and claim lease policy.
- `TaskRuntimeServingLane#claimReady` no longer fabricates `ScoreCandidate`;
  it reads the task candidate through `TaskRuntimeScorePort#scoreCandidate` and
  only then delegates to `TaskRuntimeWorkPort#claimBacklog`.
- Engine-starter serving-lane proof code uses the same direct claim parameter
  surface; `ClaimReadyCommand` is now old task-runtime/memory/test residue, not
  assembly vocabulary.
- Concrete starter port-set compatibility methods for old append/scheduler/
  claim/result/repair/worker-active/discard APIs have been deleted.
- Ordered final-window reading has been moved out of `TaskRuntimeReadPort` and
  out of `TaskRuntimePortSet` into `TaskRuntimeResultWindowReadModel`; this is a
  non-core view surface, not a score-band runtime commitment.
- `TaskRuntimeProgressPort` has been deleted; progress snapshots are owned by
  `TaskRuntimeReadPort`.

## Open Decisions Before SBRK-4

- Whether V0 result policy in `TaskRuntimeMetaV1` is enough for serving cutover,
  or whether SBRK-4 needs to freeze per-attempt policy inside `rt` before
  result apply.
- `TaskRuntimeResultWindowReadPort` was deleted and replaced by
  `TaskRuntimeResultWindowReadModel` outside the core port set. Later server/SDK
  result-window deletion remains a separate read-model cleanup, not this
  score-band runtime proof.
- Whether engine-facing `claimReady` is renamed, adapted internally, or kept as
  an engine port while task-runtime core uses `claimBacklog`.
- When `TaskRuntimeResultWindowReadModel#readFinalResults(FinalResultReadRequest)`
  can be deleted from runtime assembly after server/SDK window callers are removed.
