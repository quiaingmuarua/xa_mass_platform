# Task Runtime Score-Band Scheduling Roadmap

Status: proposed follow-up active-contract candidate.

Current cursor: SBS-0, score-band scheduling contract and current writer
inventory.

Artifact role: follow-up roadmap for the task score-band scheduling mechanism.
It records the score-band scheduling direction so it does not get lost while
the Redis backlog keyspace rewrite lands first.

Prerequisite:

- `TASK_RUNTIME_REDIS_BACKLOG_KEYSPACE_REWRITE_ROADMAP.md` is complete or has
  at least closed append/claim/result against the backlog / active-runtime
  keyspace.

This roadmap must not be used to justify adding score writes to append in the
backlog rewrite roadmap.

## Purpose

Introduce task-level score-band scheduling as the runtime-owned task
scheduling visibility mechanism:

```text
task lifecycle / scheduling owner writes task score
engine scheduling reads due task score candidates
claim resolves actual backlog availability
append only writes backlog
result/retry only mutates item runtime state unless it closes terminal state
```

The score answers:

```text
should this task be considered by scheduling now, later, or never
```

It does not answer:

```text
how many items are in backlog
which worker should execute the work
whether append is allowed
whether a result is accepted
```

## Boundary Decision

Task score-band scheduling is a task-level scheduling visibility mechanism.
It is not the backlog storage mechanism.

The split is:

```text
append/enqueue owner
  -> accepted backlog only

task score owner
  -> task scheduling visibility only

engine scheduling
  -> reads score candidates and orchestrates worker selection

task runtime claim/result
  -> backlog, active runtime, retry, finality
```

Append and score meet only later in the scheduling/claim round:

```text
score says task is eligible to try
claim says whether this task has claimable backlog now
```

An eligible score with empty backlog is not a correctness bug. It is a bounded
no-work scheduling attempt and must not be fixed by making append rewrite
score.

## Score Band Contract

The task score is a Redis ZSET score with range semantics.

Use explicit constants; do not infer band from event type or task status name.

Recommended first constants:

```text
TERMINAL_BAND
  score < 0
  meaning: terminal or otherwise stopped; not schedulable; no automatic recovery

NON_SCHEDULABLE_BAND
  0 <= score < TIME_SCORE_FLOOR
  meaning: live but not schedulable by the hot path

TIME_BAND
  score >= TIME_SCORE_FLOOR
  meaning: time-based scheduling visibility
```

Within `TIME_BAND`:

```text
TIME_SCORE_FLOOR <= score <= now
  task may be acquired as a scheduling candidate

score > now
  task is held until the timestamp becomes due
```

Pause is encoded as a far-future time-band score:

```text
pauseScore = SCHEDULER_HOLD_FLOOR
```

`SCHEDULER_HOLD_FLOOR` must be a very large safe epoch-millis value, for
example a date far in the future, without risking Redis double precision or
Java long overflow. It is still a time-band score, not a negative terminal
state and not a non-schedulable enum.

## Initial ZSET Shape

Keep lane keys stable unless a later implementation proves they are harmful:

```text
{ns}:task:score:{laneKey}
  type: ZSET
  member: taskId
  score: task scheduling visibility score
```

`laneKey` partitions task scheduling visibility. It may map to project or a
deterministic default in the first implementation. It is not worker selection,
not transport routing, and not item shard identity.

The score key is the only task scheduling visibility index for this roadmap.
Do not add `task:active:{laneKey}`, `task:dirty`, or a second ready-task
lifecycle index as score-band truth in the first slice.

## State Range Transitions

This roadmap defines range-level transitions, not every business command.
Business command vocabulary can be mapped later by the lifecycle command
roadmap.

Core transition rules:

```text
TERMINAL_BAND
  -> no transition back to live

NON_SCHEDULABLE_BAND
  -> TIME_BAND
  -> TERMINAL_BAND

TIME_BAND
  -> TIME_BAND
  -> TERMINAL_BAND
```

The important simplification:

- Once a task is in a schedulable time-band model, normal "do not dispatch for
  a while" is represented by a future timestamp.
- A live time-band task does not need a second "dispatchable vs intakeable"
  kernel state.
- Intake state is not part of the first score-band scheduling mechanism.

Examples:

```text
create but not yet scheduling-visible
  -> NON_SCHEDULABLE_BAND code

approve / make schedulable
  -> score = now

pause event
  -> score = SCHEDULER_HOLD_FLOOR

resume event
  -> score = now

scheduling round attempted and backlog may remain
  -> score = now or now + bounded recheck delay

cancel / terminal close
  -> negative retained terminal score
```

Reject/block semantics are not decided by this roadmap. A later command
mapping must choose whether a specific command is terminal, non-schedulable, or
future-hold. The score-band core only defines the allowed bands and transition
invariants.

## Score Writers

Only these owners may write task score:

1. Task lifecycle / scheduling command owner:
   - make schedulable;
   - pause;
   - resume;
   - block or hold if later mapped;
   - terminal close.
2. Task scheduling round owner:
   - after a task is selected for scheduling consideration, rewrite score to
     the next eligible timestamp or keep it visible according to the first
     scheduling policy;
   - this is the only live-task due refresh expected in the first score-band
     scheduling model.
3. Terminal convergence owner:
   - may write negative terminal retained score through the lifecycle owner
     boundary.

These paths must not write task score:

- append/enqueue;
- backlog size changes;
- ordinary result apply;
- retry requeue;
- worker selection;
- transport delivery;
- result ingress;
- server/read view projection;
- trace/audit materialization.

Result or retry may ask the lifecycle/terminal owner to close the task when
terminal convergence is proven. That is a terminal transition, not a live
task-score refresh.

## Scheduling Read Path

Engine scheduling consumes task score candidates only as task demand
visibility.

Target flow:

```text
score-band task scheduler
  -> acquire due task ids from task:score:{laneKey}
  -> engine resolves task scheduling policy and worker universe
  -> worker-runtime selects/reserves worker
  -> task runtime claim tries to pop backlog
  -> transport dispatch receives already assigned work
```

Due score candidate means:

```text
this task is allowed to try scheduling
```

It does not prove backlog exists. Claim is still the backlog truth.

Bounded no-work handling:

- If claim finds no backlog, do not mutate append owner state.
- The scheduling round owner may rewrite task score to a bounded later recheck
  time.
- The scheduler must advance through bounded candidate batches so empty tasks
  cannot starve later due tasks.

## Relationship To Redis Backlog Rewrite

The backlog rewrite roadmap gives claim a compact item source:

```text
task:{taskId}:backlog
task:{taskId}:rt
```

This score-band roadmap gives scheduler a task demand source:

```text
task:score:{laneKey}
```

The two mechanisms remain separate:

```text
backlog LIST length does not write score
score candidate does not imply backlog length
```

This separation is deliberate. It prevents accepted-item intake from becoming
task lifecycle/scheduling truth.

## Explicit Non-Goals

- No Redis backlog keyspace rewrite; that belongs to
  `TASK_RUNTIME_REDIS_BACKLOG_KEYSPACE_REWRITE_ROADMAP.md`.
- No new module.
- No public server/SDK API change.
- No durable result ledger.
- No worker-runtime score-band change.
- No transport routing or delivery ownership change.
- No `TaskManager` physical deletion in this roadmap.
- No full task lifecycle command surface redesign in this roadmap.
- No exact timer guarantee for pause/resume/recheck.
- No active lease repair redesign unless it becomes impossible to keep repair
  discoverable through existing runtime indexes.

## Forbidden Drift

- Do not make append update task score.
- Do not make result/retry update live task score.
- Do not infer score band from event names or legacy `TaskStatus` labels.
- Do not let `RuntimeGate`, `TaskStatus`, or read projections become the
  dispatch truth once score-band scheduling is serving.
- Do not create both `ready:tasks` and `task:score:{laneKey}` as equivalent
  production scheduling sources.
- Do not make `task:score:{laneKey}` responsible for active lease expiry
  precision.
- Do not add a second active-task registry unless active lease repair cannot be
  proven through the selected runtime path and a separate owner decision
  justifies it.

## SBS-0 Score Writer Inventory And Contract Freeze

Goal:

Identify every current or proposed task scheduling visibility writer before
introducing score-band scheduling.

Scope:

- `TaskWorkRuntime.readyTaskIds(limit)` usage in engine.
- Current `ready:tasks` Redis writes.
- Current `TaskStatus` filters in assignment/recovery.
- Lifecycle command paths that currently move tasks into or out of `READY`,
  `PAUSED`, `BLOCKED`, `TERMINAL`.
- Runtime result/lease expiry paths that may trigger redispatch.

Acceptance:

- Every current source that makes a task dispatchable is classified as:
  - backlog availability discovery;
  - lifecycle command;
  - scheduling round rewrite;
  - terminal convergence;
  - old engine status gate;
  - test-only fixture.
- First constants are named:
  - `TIME_SCORE_FLOOR`;
  - `SCHEDULER_HOLD_FLOOR`;
  - negative retained terminal score codes;
  - optional non-schedulable score codes.
- Implementation order explicitly names which old serving source is replaced
  first.

Verification:

```powershell
rg -n "readyTaskIds|READY|PAUSED|BLOCKED|TERMINAL|hasReadyWork|claimReady|RuntimeReadyDispatchPump|getRuntimeDispatchableTasks" `
  xa-mass-engine platform_infra/mass-runtime-api platform_infra/mass-runtime-memory platform_infra/mass-runtime-redis `
  --glob "*.java" --glob "!**/target/**"
```

## SBS-1 Score Constants And Band Classifier

Goal:

Introduce one task score-band classifier shared by memory/Redis tests and
eventual scheduler code.

Acceptance:

- Band classification is based on score range only.
- `pause` hold score is a safe future timestamp and is not terminal.
- Negative score is retained terminal / stopped, not paused.
- `TIME_SCORE_FLOOR` prevents positive non-time codes from entering due-time
  scheduling.
- Unit tests prove boundary values and fake-clock behavior.

Verification:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api -Dtest=*Task*Score* test
```

If no test class exists yet, SBS-1 must add one and the final proof command
must not use `-Dsurefire.failIfNoSpecifiedTests=false`.

## SBS-2 Redis Score Keyspace And Due Candidate Read

Goal:

Add the Redis task score ZSET and bounded due candidate read without changing
append/backlog semantics.

Target:

```text
{ns}:task:score:{laneKey}
  ZSET taskId -> score
```

Acceptance:

- Due acquire reads only `TIME_SCORE_FLOOR <= score <= now`.
- Future pause/hold scores are not returned before due.
- Negative terminal scores and non-schedulable positive codes are not returned
  by the hot path.
- Due acquire is bounded by lane and limit.
- No Redis `SCAN` is used.
- Append/enqueue tests prove append does not write task score.

Verification:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis -Dtest=*Task*Score*,RedisTaskWorkRuntimeTest test
```

## SBS-3 Scheduling Round Score Rewrite

Goal:

Make task scheduling rounds the only live-task due refresh writer.

Target flow:

```text
due task candidate acquired
  -> engine scheduling attempts worker selection + task runtime claim
  -> scheduling round owner rewrites task score:
       now, if immediately eligible for another bounded round
       now + delay, if bounded recheck/backoff is desired
       SCHEDULER_HOLD_FLOOR, if pause command occurred
       negative terminal, if terminal close occurred
```

Acceptance:

- A scheduling attempt rewrites score through one named owner method.
- Empty/no-work candidate handling does not call append, result, or storage to
  fix score.
- Bounded candidate batches can advance past empty or stale candidates.
- One large task cannot permanently starve other due score candidates in the
  same lane.
- Result/retry code paths do not write live due score.

Verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine,platform_infra/mass-runtime-redis -am -DskipTests test-compile
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=RuntimeReadyDispatchPumpTest,TaskRuntimeRecoveryPortTest,SimpleTaskDispatchBinderTest test
```

## SBS-4 Serving Dispatch Cutover

Goal:

Route the production task discovery path from ready-backlog discovery to
score-band due candidate discovery, while keeping claim as backlog truth.

Old path to close:

```text
RuntimeReadyDispatchPump
  -> TaskWorkRuntime.readyTaskIds(limit)
  -> storage Task status filtering
```

Target path:

```text
RuntimeReadyDispatchPump
  -> task score-band due candidates
  -> engine scheduling policy / worker-runtime selection
  -> TaskWorkRuntime.claimReady(taskId, ...)
```

Acceptance:

- Production dispatch discovery does not infer schedulability from
  `TaskStatus.READY` or backlog discovery alone.
- `TaskWorkRuntime.readyTaskIds(limit)` is either demoted to backlog support /
  migration residue or no longer used as the production scheduling source.
- Claim still validates actual backlog availability.
- Empty candidate proof shows scheduler does not get stuck and does not
  mutate append owner state.
- Existing task execution E2E still passes through the new discovery path.

Verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=RuntimeReadyDispatchPumpTest,TaskRuntimeRecoveryPortTest,TaskKernelLifecycleTest,TaskManagerLifecycleTest test
.\mvnw.cmd -q -pl xa-mass-server -am -DskipTests package
```

## SBS-5 Lifecycle Command Mapping

Goal:

Map the first minimal task lifecycle commands to score transitions without
turning this roadmap into a full task manager deletion.

First command mapping:

```text
make schedulable / approve
  -> TIME_BAND score = now

pause event
  -> TIME_BAND score = SCHEDULER_HOLD_FLOOR

resume event
  -> TIME_BAND score = now

terminal close / cancel
  -> TERMINAL_BAND retained negative score
```

Acceptance:

- Command mapping writes score through task lifecycle/scheduling owner only.
- Append remains backlog-only.
- Intake state is not introduced as a kernel scheduling gate in this roadmap.
- Existing external command APIs remain stable unless a separate command
  roadmap approves shape changes.
- Read/projection code may observe the score result but must not drive it.

Verification:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=TaskManagerLifecycleTest,TaskKernelLifecycleTest,EngineSchedulingCoreArchitectureGuardTest test
```

## SBS-6 Docs, Guards, And Old Source Demotion

Goal:

Update current owner docs only after the serving path is cut over and add
guards against the specific regressions that caused prior drift.

Acceptance:

- `doc/TASK_LIFECYCLE_BASELINE.md` and
  `xa-mass-engine/doc/baseline/RUNTIME_BOUNDARY_BASELINE.md` describe
  implemented score-band scheduling only after SBS-4/SBS-5 land.
- `platform_infra/mass-runtime-redis/REDIS_RUNTIME_BASELINE.md` distinguishes
  backlog keyspace from task score scheduling keyspace.
- Guard or focused source test proves append does not call score write.
- Guard or focused source test proves result/retry does not refresh live task
  due score except via terminal owner handoff.
- Guard or focused source test proves production scheduling reads score-band
  due candidates after cutover.
- Any remaining `readyTaskIds` production use is classified and has a closure
  target.

Verification:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-redis,xa-mass-engine -am -DskipTests test-compile
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=EngineSchedulingCoreArchitectureGuardTest test
.\mvnw.cmd clean -DskipTests test-compile
```

## Completion Criteria

This roadmap is complete only when:

- `task:score:{laneKey}` is the production task scheduling visibility source.
- Score band semantics are explicit constants and proven by tests.
- Append/backlog writes do not update task score.
- Result/retry paths do not refresh live task score.
- Scheduling rounds are the only live-task due refresh writer.
- Engine scheduling consumes due score candidates and still claims backlog
  through `TaskWorkRuntime`.
- Empty/no-work score candidates are bounded and cannot starve other tasks.
- Terminal close writes retained negative score rather than relying on score
  absence as lifecycle truth.
- Current owner docs describe the implemented state and old `readyTaskIds`
  scheduling source is removed, demoted, or explicitly marked as non-serving
  residue.

## Stop Triggers

Stop and re-coordinate if implementation requires:

- append to write score to make dispatch work;
- result/retry to refresh live score outside terminal handoff;
- a second task scheduling visibility index beside score-band;
- server/read-model projection to decide schedulability;
- transport or worker-runtime to write task score;
- a new module before the in-place runtime path is proven;
- exact active-lease timeout semantics from task score;
- broad deletion of `TaskManager` or task lifecycle APIs before score-band
  serving cutover is proven.

