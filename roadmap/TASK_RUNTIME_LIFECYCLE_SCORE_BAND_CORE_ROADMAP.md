# Task Runtime Lifecycle Score-Band Core Roadmap

Status: implemented score-band core contract; broader TRLC command cutover remains separate.

This roadmap stabilizes the task-runtime lifecycle score-band contract before
the broader lifecycle-command cutover resumes. It is intentionally smaller than
`TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md`.

The goal is not to delete `TaskManager`, rewrite SDK/server command adapters,
or complete result/finality cutover. The goal is to make the score-band itself
unambiguous, testable, and shared by memory and Redis runtime implementations.

The larger lifecycle-command roadmap now consumes this score contract instead
of defining score semantics inside every slice.

## Execution Posture

This roadmap is now an implementation contract, not a discussion loop. Do not
expand it to settle broader task lifecycle behavior unless current code proves
the score-band mainline cannot land without that decision.

The first implementation gate is:

```text
SBL-0 inventory
  -> SBL-1 TaskScoreV1 band contract
  -> SBL-1A score writer cutpoint
```

Do not start memory/Redis behavior convergence until SBL-1A closes the raw
production score-writer path. Do not re-review this roadmap before coding
unless a stop condition is hit.

Only these score-band facts are mandatory for the first gate:

- score range owns dispatch visibility;
- `<tr>:task:score:{laneKey}` remains the lifecycle score index;
- only lifecycle score owner / task scheduling trigger writes live due score;
- terminal is retained negative score, not score absence;
- append, claim, retry promotion, result, progress, finality, and no-work skip
  do not refresh live task score;
- `RuntimeGate` and engine `TaskStatus` cannot override score-range visibility.

Everything else remains deliberately deferred to TRLC or later cleanup:

- `reject` / `block` business mapping;
- SDK/server command cutover;
- result/finality serving cutover;
- engine lifecycle residue deletion;
- cleanup/retention/discard score removal;
- Redis keyspace redesign outside the score index.

## Implementation Summary

Implemented on 2026-07-03:

- `TaskScoreV1` now exposes the target three-range contract:
  retained terminal negative scores, positive non-schedulable codes, and
  schedulable timestamp scores with `SCHEDULER_HOLD_FLOOR`.
- `TaskRuntimeScorePort` no longer exposes raw `setTaskScore(...)` /
  `removeTaskScore(...)`; production callers use named score transitions:
  `seedNonSchedulable`, `markDispatchDue`, `markSchedulerHold`, and
  `markTerminalRetained`.
- `TaskRuntimeServingLane` no longer refreshes task score from append or
  result/retry outcomes. The remaining transitional serving-lane scheduler
  entry writes score only through the named score transitions.
- Memory and Redis runtime discovery/claim no longer use `RuntimeGate.OPEN` as
  dispatch truth. Candidate acquire is score-range based; claim fences lane,
  epoch/fence, and observed due score.
- Redis Lua claim/retry/result paths no longer write `MAINT_ACTIVE` or refresh
  live task score. `closeIfDrained` retains a negative terminal score instead
  of removing score membership.
- Tests were updated so score discovery proves lifecycle visibility, not
  work-ready backlog. Backlog availability is resolved at claim.

Verification run:

- `./mvnw -pl xa-mass-task-runtime "-Dtest=TaskScoreV1ContractTest,TaskRuntimeArchitectureGuardTest" test`
- `./mvnw -pl platform_infra/mass-task-runtime-memory -am -Dtest=InMemoryTaskRuntimeContractTest "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `./mvnw -pl platform_infra/mass-task-runtime-redis -am "-Dtest=RedisScoreBandTaskRuntimeTest,RedisTaskRuntimeScoreBandKeyspaceProofTest,RedisTaskRuntimeScoreBandAdvanceCandidateTest,RedisTaskRuntimeOwnerReconnectTest,RedisTaskRuntimeNetworkPartitionTest,RedisTaskRuntimeArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `./mvnw -pl sdk/xa-mass-task-runtime-starter-sdk -am -Dtest=TaskRuntimeNonServingAppendToClaimProofTest "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `./mvnw -pl xa-mass-engine -am -Dtest=TaskResultConcurrencyConvergenceTest "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `./mvnw -pl xa-mass-engine -am "-Dtest=TaskRuntimeServingLaneTest,TaskRuntimeServingLaneOldPathClosureGuardTest,TaskRuntimeEngineCutoverPreparationTest,TaskRuntimeRecoveryPortTest,TaskIdleClosePolicyBehaviorTest,TaskResultRuntimeConvergenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

## Closed Pre-Implementation Observations

Before this roadmap was implemented, code had score-band residue but not a
stable contract:

- `xa-mass-task-runtime/src/main/java/com/xa/mass/task/runtime/TaskScoreV1.java`
  defined `TIME_SCORE_FLOOR`, but also defined `PARKED_PAUSED` and
  `PARKED_BLOCKED` as negative scores.
- `RuntimeGate` had `OPEN`, `PAUSED`, `BLOCKED`, `DISCARDED`, and `TERMINAL`,
  which could become a second lifecycle truth beside score.
- Memory and Redis task-runtime paths inspected `RuntimeGate.OPEN` while
  discovering or claiming score candidates.
- `TaskRuntimeServingLane` mapped engine shell state / `RuntimeGate` directly
  to raw score writes and `removeTaskScore`, so the serving path could express
  lifecycle score truth outside a named score-writer
  cutpoint.
- `TaskRuntimeScorePort`, `ScoreCandidate`, `TaskRuntimeMetaV1`, and Redis
  score keys carried `laneKey`. SBL kept that shape. Lane partitioning
  is not the problem this roadmap solves.

Closed gap:

```text
target: score range decides task lifecycle scheduling state
previous: score plus RuntimeGate plus old status mapping could all influence state
```

This roadmap closes that gap only for the score-band core.

## Owner Decision

`xa-mass-task-runtime` owns task lifecycle score state and score transition
rules.

Physical implementations live in:

- `platform_infra/mass-task-runtime-memory`
- `platform_infra/mass-task-runtime-redis`

Engine may interpret external commands and scheduling policy, but it must not
own the lifecycle score. Worker-runtime owns worker capability, selection,
reservation, capacity, and locks. Transport owns assigned delivery only.

Forbidden in this score-band core:

- engine `Task` DTOs;
- engine `TaskStatus` as score truth;
- worker reservation or worker evidence DTOs;
- transport route/session/mailbox fields;
- SDK/server read models;
- Redis key names in public task-runtime contracts.

## Mainline Anchor

The core mechanism to prove is:

```text
task-runtime lifecycle score transition
  -> task-local meta/reason side facts
  -> <tr>:task:score:{laneKey}
  -> bounded score candidate acquire reads only dispatch-visible time scores
```

Append is not part of this mechanism:

```text
append item -> accepted backlog only
score transition -> lifecycle/scheduler visibility only
claim -> first point where backlog item and score candidate meet
```

Append must not enroll, rescore, dirty, wake, or publish task score. If a task
with backlog should become dispatch-visible, the task scheduling trigger /
lifecycle score owner must write the task score explicitly.

## Score Refresh Boundary

This roadmap separates score transition from dispatch refresh.

Score transition is any owner write to the task score. Dispatch refresh is the
narrow case that writes a live task back into the due time band so scheduling
can see it.

First-version dispatch refresh has exactly one source:

```text
task scheduling trigger
  -> lifecycle / scheduler-visibility owner calls the due-writer primitive
  -> due-writer primitive stores TaskScoreV1.dueAt(nowMillis)
```

Examples such as approve or resume may trigger scheduling through that owner
action, but they must not create a second refresh mechanism. The refresh is not
periodic and not tied to work/result events. Once a task score is in the due
band, it stays schedulable by range until an explicit owner transition moves it
elsewhere.

Working primitive name for this roadmap: `markDispatchDue(taskId, laneKey,
epoch, nowMillis)`. The implementation name may change, but the shape must
stay the same: one owner-internal task-runtime score transition that writes a
due score for lifecycle/scheduler visibility. If implementation keeps a
generic `setTaskScore(...)` primitive, it must be owner-internal support for
that transition, not a public refresh surface for arbitrary callers.

Allowed first-version score transitions:

- create seed writes one initial non-schedulable score;
- task scheduling trigger writes a due score;
- pause writes `SCHEDULER_HOLD_FLOOR`;
- terminal / cancel writes a retained negative terminal score;
- later delayed-schedule commands may write a future due timestamp, but this
  roadmap only keeps the band available for that future use.

Forbidden first-version score writers:

- append;
- accepted backlog depth changes;
- claim success or empty-claim skip;
- worker result apply;
- retry/finality outcome handling;
- progress snapshot publication;
- read/view/storage projection;
- transport delivery outcome;
- worker-runtime selection/reservation evidence.

Result-side code may eventually ask the lifecycle owner to close a task as a
terminal transition. It must not use "work result arrived" as a generic score
refresh trigger, and it must not rewrite a live task back to `now` just because
work completed.

Anti-starvation and scheduling stability must come from acquire/claim behavior,
not opportunistic score rewrites:

- due candidate acquisition starts from the smallest score in
  `[TIME_SCORE_FLOOR, nowMillis]`;
- a due score that is not selected remains due for the next bounded acquire;
- candidate acquisition must be able to skip stale or empty candidates and
  continue within a bounded window;
- empty backlog or failed claim must not update task score in this roadmap;
- no additional fairness optimization is required in this roadmap.

## Score-Band Contract

The task score is a `long` whose range is the scheduling visibility state.
Event names and reason metadata explain why a score changed; they do not decide
whether a task is dispatch-visible.

`<tr>:task:score:{laneKey}` is the task-runtime score-band scheduling index,
not a full lifecycle audit store. Each lane-keyed score index uses the same
score bands: retained terminal scores, non-schedulable scores, scheduler
holds, future scores, and due scores. The hot path acquires only the due time
subrange; the other bands are retained score state but are never dispatch
candidates. SBL does not change lane partitioning.

Missing score membership means the task is absent from dispatch competition. In
the first version, missing score membership is allowed for never-seeded tasks
only. Retention/discard cleanup is not part of SBL because task completion
should first be represented by a retained negative terminal score. Missing
score is not service discovery, terminal discovery, corrupt-state diagnosis, or
full lifecycle audit truth. Terminal reason and diagnostic detail must live in
runtime meta/result/projection/trace, not only in the score value.

Target bands:

| Band | Range | Meaning | Hot-path acquire |
| --- | --- | --- | --- |
| Terminal band | `score < 0` | retained closed marker for lifecycle closed / terminal; no transition out | never |
| Non-schedulable band | `0 <= score < TIME_SCORE_FLOOR` | non-terminal but not dispatch-visible; explicit lifecycle/policy action may promote | never |
| Schedulable time band | `score >= TIME_SCORE_FLOOR` | timestamp semantics; due/future/hold are read by score value | only due subrange |

Time subranges:

| Subrange | Range | Meaning |
| --- | --- | --- |
| Due | `TIME_SCORE_FLOOR <= score <= nowMillis` | dispatch-visible candidate |
| Future | `nowMillis < score < SCHEDULER_HOLD_FLOOR` | delayed / retry / cooldown / not due yet |
| Scheduler hold | `score >= SCHEDULER_HOLD_FLOOR` | manual pause / long hold; non-terminal and resume-able, but not acquired |

Constant rules:

- `TIME_SCORE_FLOOR` is a fixed lower bound for real epoch-millis scores.
- `SCHEDULER_HOLD_FLOOR` is a fixed far-future epoch-millis floor, roughly
  "1000-year hold" class, chosen with checked arithmetic and kept far below
  `Long.MAX_VALUE`.
- `pause` is an event with no caller-provided pause duration. Runtime maps it
  to `SCHEDULER_HOLD_FLOOR`.
- Do not compute pause as unchecked `now + hugeDuration`.
- Positive non-schedulable scores are enum-like codes. They are not timestamps.
- Negative scores are terminal/non-transitionable. They are not parked paused
  or blocked states.
- Removing a score is not part of the score-band lifecycle transition model.
  `removeTaskScore` must not be used by command, result, finality, acquire, or
  repair code to express terminal state or to fix dispatch visibility. If a
  later retention/discard owner needs score cleanup, it must be a separate
  cleanup surface outside SBL and outside the hot path.

## Transition Rules

The current score range decides dispatch visibility and core score transition
invariants. Command-specific business legality belongs to the later
lifecycle-command state machine, but it cannot override due/non-due dispatch
truth.

```text
terminal band
  -> no transition out

non-schedulable band
  -> non-schedulable band
  -> schedulable time band
  -> terminal band

schedulable time band
  -> schedulable time band
  -> terminal band
```

First-version trigger mapping:

| Trigger | Target score | Notes |
| --- | --- | --- |
| create / unapproved seed | positive non-schedulable code | not dispatch-visible |
| task scheduling trigger | `dueAt(nowMillis)` | approve/resume may use this owner action; this is the only live dispatch refresh |
| pause | `SCHEDULER_HOLD_FLOOR` | manual pause without pause-until parameter |
| delayed schedule command | `dueAt(futureMillis)` below hold floor | future-use band only; not result/append driven in this roadmap |
| cancel / terminal close | negative terminal code while retained | cannot reopen |
| append | no score write | backlog owner only |

Notes:

- If a later business command needs to move a due task back to a positive
  non-schedulable code, it needs a separate owner decision. Do not smuggle that
  into the first score-band contract.
- `reject` and `block` business semantics are not decided by SBL. TRLC must map
  them to positive non-schedulable or negative terminal transitions before
  cutting those commands over.
- Runtime reason metadata may record `CREATED`, `MANUAL_PAUSE`,
  `REVIEW_REJECTED`, `CANCELLED`, or similar values, but acquire and transition
  legality must read the score band first.

## Redis Shape Covered Here

This roadmap only stabilizes the lifecycle score index:

```text
<tr>:task:score:{laneKey}
  type: ZSET
  member: taskId
  score: TaskScoreV1.score
```

`laneKey` remains a partition/context value in this roadmap. It must not alter
the score-band ranges, score writer rules, or append/result/claim refresh
boundaries.

Acquisition must use a bounded due-time range:

```text
ZRANGE <tr>:task:score:{laneKey}
  TIME_SCORE_FLOOR nowMillis
  BYSCORE LIMIT 0 N
```

Candidate order starts at the smallest due score greater than or equal to
`TIME_SCORE_FLOOR`. This is the first-version fairness rule: a task does not
need frequent score refresh to stay eligible.

It must not scan:

- terminal band;
- positive non-schedulable enum band;
- scheduler hold scores above `nowMillis`;
- task metadata hashes to decide dispatch visibility;
- a parallel `ready`, `active`, `dirty`, or status-derived task index.

Task-local metadata can store reason, epoch, and projection facts, but it is
side data for validation/projection. It is not the acquire index.

This roadmap does not redesign backlog, active lease, retry, final-result, or
progress keys.

## Non-Goals

- No SDK/server command adapter cutover.
- No `TaskManager` or `TaskLifecycleService` physical deletion.
- No worker selection, worker reservation, worker capacity, or worker lock
  redesign.
- No transport delivery/result-ingress change.
- No read-view or frontend/API cleanup.
- No full Redis keyspace rewrite beyond score-band lifecycle reads/writes.
- No new task ready set, active set, dirty set, or append-driven score update.
- No caller idempotency or message-id uniqueness guarantee for append.

## Slices

### SBL-0: Inventory The Current Score Readers And Writers

Goal: know every current code path that reads or writes task score/gate.
This includes score writes hidden inside claim, retry promotion, result apply,
maintenance, and tests.

Scope:

- `TaskScoreV1`
- `RuntimeGate`
- `TaskRuntimeMetaV1`
- `TaskRuntimeScorePort`
- `TaskRuntimeServingLane.updateSchedulerEligibility(...)` and any other
  serving caller that directly maps shell state / `RuntimeGate` to raw score
  writes
- existing `TaskRuntimeScorePort#removeTaskScore` and Redis `ZREM` callers as
  old API / implementation residue outside the target lifecycle transition
  path
- memory task-runtime score discovery/claim
- memory task-runtime result/retry paths that write `MAINT_ACTIVE` or
  `TIME_SCORE_FLOOR`
- Redis task-runtime score discovery/claim/scripts
- Redis `CLAIM_BACKLOG`, `PROMOTE_DUE_RETRIES`, and `APPLY_RESULT` score
  writes
- tests that assert old `RuntimeGate.OPEN`, `PARKED_PAUSED`,
  `PARKED_BLOCKED`, or `MAINT_ACTIVE`

Acceptance:

- current readers/writers are listed in a small section inside this roadmap or
  a paired inventory only if the list becomes too large;
- each entry is classified as owner score transition, allowed dispatch
  refresh, old hidden refresh writer, implementation adapter, old gate
  residue, test residue, or unrelated diagnostic;
- no code behavior changes are required in this slice.

Verification:

- source inventory review only.

### SBL-1: Replace `TaskScoreV1` With The Target Band Contract

Goal: make the score value object express exactly the target ranges.

Scope:

- replace negative paused/blocked helpers;
- add predicates for terminal, non-schedulable, due, future, scheduler hold,
  and schedulable time band;
- add factory methods for non-schedulable code, due time, future time,
  scheduler hold, and terminal code;
- add overflow/clamp validation for far-future hold and due timestamps;
- define missing score membership as never seeded / absent from dispatch
  competition, not as terminal lifecycle proof.

Acceptance:

- `score < 0` is terminal-only;
- `0 <= score < TIME_SCORE_FLOOR` is non-schedulable-only;
- `TIME_SCORE_FLOOR <= score <= now` is due dispatch-visible;
- `now < score < SCHEDULER_HOLD_FLOOR` is future;
- `score >= SCHEDULER_HOLD_FLOOR` is scheduler hold;
- `pause` can be represented without a caller-supplied timestamp;
- absent score is not due and is not interpreted as a terminal marker by the
  score contract;
- no score predicate references `RuntimeGate`, `TaskStatus`, worker evidence,
  transport evidence, or read-view DTOs.

Verification:

- focused `TaskScoreV1` contract tests.

### SBL-1A: Establish The Score Writer Cutpoint

Goal: prevent memory and Redis implementation work from continuing around raw
score writers.

This slice does not remove `laneKey`. It closes the earlier owner problem:
production lifecycle code must not express score truth by directly choosing
arbitrary `TaskScoreV1` values or by using `removeTaskScore` as terminal state.

Scope:

- introduce or identify owner-internal score writer cutpoint operations:
  `seedNonSchedulable`, `markDispatchDue`, `markSchedulerHold`, and
  `markTerminalRetained` are working names, not required final method names;
- keep `laneKey` as partition/context input or owner-resolved context for those
  score writes;
- route transitional serving callers, including `TaskRuntimeServingLane`, to
  the score writer cutpoint instead of raw `setTaskScore/removeTaskScore`
  lifecycle mapping;
- make retained terminal write a negative terminal score instead of removing
  score membership;
- classify `setTaskScore(...)` as implementation/test support or owner-internal
  primitive only, not a production lifecycle command surface;
- remove `removeTaskScore(...)` from production lifecycle / dispatch-visibility
  semantics. Retention cleanup remains out of scope.

Acceptance:

- no production lifecycle caller can express terminal by `removeTaskScore` /
  Redis `ZREM`;
- no production lifecycle caller can directly map engine `TaskStatus`,
  `RuntimeGate`, worker evidence, transport evidence, or read-view data to raw
  arbitrary score writes;
- the only production live due writer is the task scheduling trigger /
  lifecycle score owner;
- pause/hold and retained terminal use named score transitions, not old parked
  negative paused/blocked helpers;
- `laneKey`, if present, remains a partition/context parameter and does not
  change writer authority;
- SBL-2 and SBL-3 are not allowed to depend on raw writer behavior that SBL-1A
  has not closed.

Verification:

- focused source/architecture guard for production callers of raw
  `setTaskScore/removeTaskScore`;
- owner proof that terminal writes retained negative score and not score
  absence.

### SBL-2: Align Memory Runtime Score Semantics

Goal: make in-memory runtime obey the score contract without old gate truth.

Scope:

- score discovery returns only due time-band candidates;
- due candidate discovery starts from the smallest score in the due band;
- score discovery proves lifecycle visibility only, not backlog availability;
- positive non-schedulable scores never dispatch;
- scheduler hold never dispatches before it becomes due, which should be
  practically never under the configured hold floor;
- terminal scores never dispatch or reopen;
- append without score does not create a score candidate;
- result/work completion does not refresh score.

Acceptance:

- memory implementation can point-read score metadata, but dispatch candidate
  selection is score-range first;
- candidate acquire, point-read candidate, and claim validation do not use
  `RuntimeGate.OPEN` as dispatch truth;
- any remaining `RuntimeGate` use is projection/residue and cannot override
  score-range visibility by making a non-due score dispatchable or a due score
  non-dispatchable;
- tests prove append/backlog and score visibility are separate;
- tests prove result/progress/finality paths do not rewrite live task score as
  a scheduling refresh;
- tests prove retry promotion moves item state only and does not refresh task
  score;
- tests prove owner-authorized terminal close writes a retained negative score;
- tests prove no-work claim handling does not rewrite task score;
- tests prove multiple empty/stale due candidates do not prevent bounded
  progress to a later due candidate;
- old `MAINT_ACTIVE`, `PARKED_PAUSED`, and `PARKED_BLOCKED` expectations are
  removed or rewritten as new score-band contract proof.

Verification:

- memory runtime score-band contract tests.

### SBL-3: Align Redis Runtime Score Semantics

Goal: make Redis score reads/writes obey the same contract as memory.

Scope:

- Redis candidate scan uses `[TIME_SCORE_FLOOR, nowMillis]`;
- Redis candidate scan starts from the smallest due score;
- Redis claim validates the observed candidate score is still in the due
  time-band for the same task/epoch/fence before moving backlog into active
  runtime state;
- Redis claim resolves backlog availability and may return no work without
  rewriting task score;
- Redis candidate acquire, point-read candidate, scripts, and claim validation
  do not use `RuntimeGate.OPEN` as dispatch truth;
- Redis implementation does not add a parallel ready/active/dirty task index
  for lifecycle score discovery;
- Redis claim, retry promotion, result, and maintenance scripts no longer
  refresh live task score outside the explicit task scheduling trigger;
- Redis `ZREM` / `removeTaskScore` production score lifecycle path is removed
  from the SBL target.

Acceptance:

- memory and Redis tests share the same score-band contract expectations;
- stale candidate after pause, terminal transition, or score transition fails
  before claim;
- positive non-schedulable and scheduler-hold scores remain invisible to normal
  dispatch acquire;
- append writes backlog only and leaves score unchanged;
- result/progress/finality paths do not write a due score for live tasks;
- retry promotion moves retry item state only and does not refresh task score;
- owner-authorized terminal close may write a retained negative terminal score;
- cleanup/discard score removal is not implemented, proven, or required by
  SBL;
- empty-claim or no-work skip does not rewrite task score;
- multiple empty/stale due candidates do not prevent bounded progress to a
  later due candidate;
- old Redis tests that asserted `MAINT_ACTIVE`, negative parked paused, or
  negative parked blocked are rewritten to assert the new score-band contract
  instead of preserving old score truth.

Verification:

- Redis score-band contract tests with Redis available;
- code-level guard or focused test proving forbidden old score bands are not
  used by Redis score discovery.

### SBL-4: Freeze Owner Boundaries Around The Score API

Goal: prevent the score-band core from becoming another fat cross-module API.

Scope:

- review public task-runtime score interfaces after SBL-1A through SBL-3;
- keep parameters primitive/stable/owner-owned;
- keep `laneKey` as dispatch lane / score partition context where current
  callers need it;
- ensure `laneKey` does not change the score-band ranges, writer ownership, or
  append/result/claim refresh rules;
- verify raw `setTaskScore` / `removeTaskScore` are not exposed by the public
  score port;
- reject `Task`, `TaskStatus`, worker reservation evidence, transport fields,
  SDK snapshots, and read-view DTOs in score-band APIs.

Acceptance:

- task-runtime score public surface exposes score transition/candidate
  mechanics only;
- `laneKey`, if present, is a partition/context field only and not a second
  lifecycle or dispatch-refresh owner;
- arbitrary raw score writes are not treated as legitimate production
  dispatch-refresh sources;
- worker selection and transport delivery facts do not appear in task-runtime
  score contracts;
- `RuntimeGate` is either removed from score truth or explicitly classified as
  non-authoritative residue with a closure target.

Verification:

- architecture guard or source guard for forbidden imports/types in
  `xa-mass-task-runtime` score contracts.

### SBL-5: Make TRLC Consume This Contract

Goal: prevent the larger lifecycle-command roadmap from redefining score-band
semantics.

Scope:

- update `TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md` to
  depend on this roadmap for score-band constants, predicates, and transition
  legality;
- update `TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_INVENTORY.md` so
  score semantics are referenced from this roadmap instead of copied as a
  second fact source;
- remove duplicate or conflicting score-band definitions from TRLC where they
  are no longer the owner decision;
- keep command cutover, SDK/server adapters, read-view migration, and engine
  residue deletion outside this roadmap.

Acceptance:

- TRLC names this roadmap as the score-band prerequisite;
- TRLC inventory no longer claims score-band slices are implemented unless
  this roadmap's score writer/read proof is actually complete;
- TRLC does not claim lifecycle-command cutover can proceed while score-band
  contract tests fail;
- TRLC remains responsible for mainline command cutover only after this
  score-band core is stable.

Verification:

- documentation review plus focused test references.

## Completion Criteria

This roadmap is complete only when:

- `TaskScoreV1` exposes the target three-range contract and hold-floor rules;
- raw `setTaskScore/removeTaskScore` production lifecycle writers are removed
  from the public score port and replaced by named score transitions;
- memory and Redis runtime implementations pass shared score-band contract
  tests;
- dispatch candidate acquisition reads only due scores from
  `<tr>:task:score:{laneKey}`;
- dispatch candidate acquisition starts from the smallest due score at or above
  `TIME_SCORE_FLOOR`;
- only explicit task scheduling trigger writes a live task to a due score;
- append/backlog writes do not mutate task score;
- result/work/progress/finality paths do not mutate live task score as a
  refresh;
- empty-claim/no-work skips do not mutate task score;
- retained terminal scores cannot reopen;
- score absence is never dispatch-visible and is treated only as never seeded /
  absent from dispatch competition, not terminal discovery;
- `TaskRuntimeScorePort#removeTaskScore` is not part of the target score
  lifecycle / hot-path surface;
- candidate acquire, point-read candidate, and claim validation cannot use
  `RuntimeGate.OPEN` as dispatch truth;
- scheduler hold is represented without a caller pause duration;
- `RuntimeGate` and engine `TaskStatus` cannot decide dispatch visibility;
- task-runtime score APIs contain no engine fat DTO, worker-runtime evidence,
  transport evidence, SDK snapshot, or read-view DTO;
- TRLC consumes this roadmap instead of redefining score semantics.

Completion does not require:

- production SDK command cutover;
- physical deletion of engine lifecycle code;
- result/finality cutover;
- server/frontend read-view cleanup.

## Verification

Implemented owner proof:

- `TaskScoreV1ContractTest`
- `TaskRuntimeArchitectureGuardTest`
- `InMemoryTaskRuntimeContractTest`
- `RedisScoreBandTaskRuntimeTest`
- `RedisTaskRuntimeArchitectureGuardTest`
- `RedisTaskRuntimeScoreBandKeyspaceProofTest`
- `RedisTaskRuntimeScoreBandAdvanceCandidateTest`
- `RedisTaskRuntimeOwnerReconnectTest`
- `RedisTaskRuntimeNetworkPartitionTest`
- `TaskRuntimeNonServingAppendToClaimProofTest`
- `TaskRuntimeServingLaneTest`
- `TaskRuntimeEngineCutoverPreparationTest`
- `TaskRuntimeRecoveryPortTest`

Support proof run:

- `TaskResultConcurrencyConvergenceTest`
- `TaskIdleClosePolicyBehaviorTest`
- `TaskResultRuntimeConvergenceTest`
- `TaskRuntimeServingLaneOldPathClosureGuardTest`

Anti-proof question:

```text
Would the test still pass if RuntimeGate.OPEN or engine TaskStatus decided
dispatch eligibility?
```

If yes, it is not score-band owner proof.

## Stop Conditions

Stop and re-review before coding if implementation needs any of these:

- task-runtime score API accepts `Task`, `TaskStatus`, `WorkerReservation*`,
  transport route/session fields, or SDK/read-view DTOs;
- append needs to update score to make the first proof pass;
- result/work/progress handling needs to update live task score to make the
  first proof pass;
- claim/retry/result/maintenance hidden writers need to keep writing due or
  maintenance score outside the explicit task scheduling trigger;
- SBL-2 or SBL-3 needs raw production `setTaskScore/removeTaskScore` lifecycle
  behavior that SBL-1A did not close;
- empty-claim/no-work skip needs to update task score to make acquire usable;
- callers need missing score membership to prove terminal lifecycle state;
- implementation needs to keep `removeTaskScore` as a score lifecycle,
  dispatch-visibility, or hot-path API;
- pause needs a caller-provided pause-until time;
- candidate acquire reads task metadata/status instead of score range;
- a second task-ready/active/dirty index is introduced for score discovery;
- worker selection/reservation is pulled into task-runtime;
- TRLC command cutover is required before score-band contract tests can pass.

The absence of a decision for deferred items such as `reject`, `block`,
server/SDK command cutover, result finality, or engine residue deletion is not
a stop condition for SBL. Those belong to later roadmaps unless they are needed
to make the score-band contract tests pass.

## Relationship To Other Roadmaps

- `TASK_RUNTIME_SCORE_BAND_LIFECYCLE_DIRECT_COMMAND_ROADMAP.md` should consume
  this roadmap before further lifecycle-command implementation.
- `TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_ROADMAP.md` remains the Redis
  keyspace/runtime-shape roadmap; this file only fixes lifecycle score-band
  semantics.
- `ENGINE_TASK_LIFECYCLE_RESIDUE_DELETION_ROADMAP.md` remains the later cleanup
  path for old engine lifecycle code after serving cutover is proven.
- `TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md` remains read/storage
  de-scoping only and must not define lifecycle score truth.

## Do Not Start With

- deleting `TaskManager`;
- wiring SDK/server commands;
- adding a new module;
- making `TaskReadViewPort` or server snapshots;
- implementing result ingress or finality;
- adding a maintenance scanner to compensate for unclear score semantics;
- polishing old `RuntimeGate` naming without changing score ownership;
- keeping negative paused/blocked scores and documenting them as acceptable.
