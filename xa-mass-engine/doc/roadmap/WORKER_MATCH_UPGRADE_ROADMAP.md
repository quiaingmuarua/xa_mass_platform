# Worker Match Upgrade Roadmap

Status: active implementation; Slice 0A/0B, Slice 1 source-guard, Slice 2,
Slice 3 policy seam, Slice 4 measurement, and Slice 5 warm-hint first slices
are implemented.

This roadmap supersedes the former transport worker-match spine direction. The
current transport boundary lives in `transport/AGENTS.md` and
`transport/TRANSPORT_BOUNDARY_BASELINE.md`; this roadmap owns the engine-side
worker match strategy, policy, diagnostics, bounded candidate acquisition,
candidate-bucket cleanup, and follow-up tuning.

This roadmap supersedes the former `TASK_CANDIDATE_WARM_POOL_ROADMAP.md`.
Warm candidate reuse now lives under Slice 5 instead of driving the overall
match roadmap.

This roadmap upgrades the worker matching path without turning it into a
premature plugin framework. The current two-stage match remains the default
production strategy, but it should be documented and implemented as a strategy
owned by the match layer, not as inseparable runtime truth.

The goal is low future change cost: candidate-source, admission, ranking,
reservation, retry, and diagnostics should have clear boundaries so later
strategy changes do not leak into worker lifecycle, task runtime, or dispatch
truth owners.

## Summary

Current default flow:

```text
Task dispatch signal
  -> allocation plan / requested match count
  -> Stage-1 candidate source
       WorkerGroup selector
       adapter node / candidate bucket
       bounded random sample
  -> Stage-2 admission and preference
       scheduling candidate enumeration
       prefilter
       QLExpress rule evaluation
       rank
       reserve / optional exclusive lock
  -> allocation decision
  -> runtime claim / dispatch bind
```

Keep this flow as the first stable strategy:

```text
WorkerGroupFirstTwoStageMatch
  Stage-1: source candidates from current WorkerGroup-first indexes
  Stage-2: validate current state, apply rules/rank, then reserve
```

But treat the two-stage shape as a replaceable strategy boundary, not the
kernel itself. The kernel contracts are:

- worker candidate source is not eligibility truth
- Stage-2/current-state admission is not worker lifecycle truth
- reserve/occupancy remains in `WorkerRegistry`
- dispatch claim/finality remains in task runtime
- trace must say which owner rejected a candidate

## Mechanism And Policy Boundary

Mechanism is the part of matching that protects runtime correctness and owner
truth. It should be stable, testable, and shared by every strategy:

- WorkerGroup selector resolution before candidate acquisition
- candidate bucket membership and stale cleanup
- heartbeat freshness and source-guard validation
- reachability / dispatch gate / capacity admission
- `WorkerRegistry` reserve, confirm, release, and final occupancy mutation
- task-runtime claim, lease, result finality, retry visibility, and refill
- bounded match evidence shape and trace owner reasons

Policy is the part of matching that can evolve without changing those truth
owners:

- candidate bucket sampling distribution
- candidate priority hints such as warm entries
- prefilter ordering after hard source/admission gates
- QLExpress rule content and rule cost budget
- rank weights for load, affinity, task class, and future measured latency
- retry/backoff timing and wakeup priority
- diagnostic sampling depth

Hard boundary:

- policy may choose which bounded candidates to try first
- policy may not manufacture candidate truth, bypass source guard, bypass
  Stage-2 admission, or mutate reserve/runtime state directly
- mechanism may expose narrow policy seams, but it must not hide mutable
  business strategy inside registry cleanup, runtime claim, or dispatch bind

## Current Code Observations

- Candidate source enters through `WorkerManager.findWorkerCandidateBatch(...)`,
  then through
  `WorkerCandidateIndex` and `WorkerRegistry.acquireCandidates(...)`.
- Stage-1 sample size is currently `requestedMatchCount * 4`, clamped by
  JVM-configurable min/max defaults of 512 and 2048.
- Candidate buckets may be derived from approved route-attribute powersets.
  With four standard route attributes, a fully attributed worker writes to the
  default candidate bucket plus 15 attribute-derived buckets.
- Stage-2 prefilter short-circuits dispatch availability, reachability,
  existing lock, target worker id, target attributes, and routing code before
  QLExpress.
- Ranking is currently fixed-weight: load dominant, then affinity, then
  scheduling-resource availability.
- `TaskAssignWorker` processes each signal lane with one thread, while
  runtime-driven batch dispatch can call assignment through the runtime dispatch
  pump. Match-side state must still be concurrency safe.
- `WorkerMeta` has no identity / attribute / dispatch / reachability version
  fields. Do not build first-slice designs around nonexistent version facts.

## Non-Goals

- Do not introduce a generic match plugin framework now.
- Do not make event code, arbitrary worker attributes, or warm-pool state a new
  candidate-source truth.
- Do not move reserve, occupancy, or exclusive-lock truth out of
  `WorkerRegistry`.
- Do not move runtime claim, result finality, or retry visibility into matching.
- Do not bypass current Stage-2 validation for any candidate source.
- Do not add background per-task match coroutines.
- Do not add worker version fields just to make a cache design look cleaner.

## Match Contracts

### Candidate Source

Candidate source answers:

```text
Which workers are worth considering for this task right now?
```

It may use:

- resolved WorkerGroup selector
- target-worker shortcut
- adapter node
- candidate bucket
- bounded random sample
- future source hints, such as warm candidates, only after source guard

It must not own:

- rule evaluation
- reachability truth
- dispatch availability truth
- ranking
- reserve / lock
- dispatch claim

### Current Admission

Admission answers:

```text
Can this candidate be used for this task attempt now?
```

It may use:

- current worker slot
- reachability
- dispatch gate
- target worker id / attributes
- routing code
- rule engine
- resource availability

It must not own:

- long-lived worker lifecycle truth
- candidate-bucket membership truth
- task result finality
- retry policy

### Preference

Preference answers:

```text
Which admitted candidates should be reserved first?
```

It may use load, affinity, availability, task class, and future measured
latency. It must remain replaceable without changing candidate source or
reserve semantics.

### Reservation

Reservation answers:

```text
Can this worker accept this unit of dispatch capacity now?
```

This remains a `WorkerRegistry` responsibility. Matching can request reserve,
but it does not own occupancy counters.

### Match Evidence

Match evidence answers:

```text
Why did this candidate enter, pass, fail, rank, reserve, or dispatch?
```

This is a compact proof surface for business matching policy. It should be more
structured than free-form logs, but it does not need a generic plugin
framework.

It may include:

- selected group / route source counts
- candidate source and source-guard outcome
- prefilter outcome
- rule evaluation summary and latency
- rank score and rank policy name
- reserve / lock result
- allocation and dispatch-bind result

Evidence must be bounded:

- always record aggregate counters and owner-level rejection reasons
- record at most a small top-N / sampled candidate detail set per assignment
  attempt
- do not emit full per-candidate evidence for large worker pools by default
- full candidate detail, if ever needed, must be an explicit debug/test mode
  with a hard cap

It must not become:

- a second source of eligibility truth
- a cached rule outcome owner
- a replacement for `WorkerRegistry` reserve state
- a task result finality owner

## Slice 0A: Registry Candidate Hygiene

Status: implemented first slice.

Goal: make candidate-source cleanup reliable before adding more match strategy
features.

Landed in first slice:

- in-memory and Redis registry reserve both reject expired heartbeat slots with
  `STALE_HEARTBEAT` before cleanup runs
- in-memory heartbeat cleanup now uses
  `lastHeartbeatMillis + heartbeatFreshnessMillis` and marks expired slots
  removing, matching Redis lifecycle semantics
- candidate-bucket removal uses per-worker bucket membership instead of scanning
  all candidate buckets for every worker removal
- shared contract and implementation tests cover stale heartbeat reserve and
  route-attribute bucket movement

Scope:

1. Align `WorkerRegistry.cleanupExpiredHeartbeats(...)` semantics across
   in-memory and Redis registries.
2. Keep the public argument as `nowMillis`, meaning current wall-clock time.
   Each registry computes heartbeat deadline internally from stored heartbeat
   state and freshness policy.
3. Make in-memory heartbeat freshness behavior match Redis behavior:
   `heartbeatDeadlineMillis = lastHeartbeatMillis + heartbeatFreshnessMillis`.
4. Make `tryReserve(...)` heartbeat admission consistent across registries.
   Redis already rejects stale slots with `STALE_HEARTBEAT`; in-memory must
   reject the same stale slot even when cleanup has not run yet.
5. Add tests proving stale heartbeat cleanup removes candidates from route
   buckets and prevents new reserve.
6. Replace broad candidate-bucket removal scans with worker-to-bucket membership.
   `removeFromBuckets` must remove only the bucket keys indexed for the worker
   being removed.

Acceptance:

1. In-memory and Redis registries keep `cleanupExpiredHeartbeats(nowMillis, limit)`
   as a current-time API and agree on internal heartbeat-deadline semantics.
2. Both in-memory and Redis `tryReserve(...)` return the same stale-heartbeat
   rejection for a slot whose heartbeat deadline has passed, whether or not
   cleanup has already removed the candidate from candidate buckets.
3. Worker unregister / group move / route-attribute update removes only known
   bucket memberships from the worker-to-bucket reverse index.
4. Cleanup remains bounded and stale-tolerant; stale bucket members may be
   rejected lazily by reserve, but cleanup does not scan every candidate bucket on
   every worker removal.

## Slice 0B: Dispatch Wakeup And Dedupe Semantics

Status: implemented first slice.

Goal: shorten no-match / min-worker / capacity wait time when worker
availability changes, without giving worker owners task-scheduling authority.

Landed in first slice:

- `TaskDispatchWakeupBridge` owns worker-availability wakeup fanout
- worker-control / worker-manager availability callbacks are wired to the
  bridge instead of directly to the runtime-ready pump
- the bridge wakes runtime-ready batch admission and bounded
  `TaskAssignWorker` lane retries
- `TaskAssignWorker` now owns a waiting-retry index and can requeue only tasks
  already known to be waiting retry/backoff
- READY dedupe remains an explicit traced skip in this slice; it is not
  converted into blind requeue because a successful in-flight assignment could
  otherwise be duplicated

Scope:

1. Add or identify an engine-side dispatch wakeup bridge, for example
   `TaskDispatchWakeupBridge`.
2. Worker, transport, capability, and state owners call only the bridge with a
   reason such as `WORKER_REGISTERED`, `WORKER_ONLINE`,
   `CAPABILITY_CHANGED`, or `STATE_AVAILABLE`.
3. The bridge may fan out only to bounded engine-owned wakeup ports:
   - runtime-driven batch admission via `RuntimeReadyDispatchPump.wakeIdleAdmissions()`
   - lane-driven assignment retry via a `TaskAssignWorker`-owned bounded wake
     method
4. The lane wake method must not scan task storage or all READY tasks. It may
   only requeue tasks already tracked by `TaskAssignWorker` as waiting retry,
   deferred requeue, or deduped meaningful redispatch. `TaskAssignWorker` owns
   the waiting-task index because it owns lane retry/backoff state.
5. If the first implementation cannot safely wake lane retries, document and
   trace the temporary pump-only behavior; do not pretend `requestTaskDispatch`
   can be called without a task source.
6. Fix or explicitly accept the READY-state dedupe window in `TaskAssignWorker`.

Acceptance:

1. Worker registration, online, capability, and AVAILABLE-state transitions call
   the engine wakeup bridge, not the pump or assign worker directly.
2. The bridge can wake runtime-ready batch admission where configured.
3. Lane retry wakeup is bounded to tasks already known to `TaskAssignWorker`,
   or the roadmap explicitly records pump-only behavior as a temporary
   limitation with trace evidence.
4. READY tasks do not silently lose a meaningful redispatch signal unless the
   accepted behavior is documented and traced.
5. No wakeup path scans all tasks or lets worker/transport owners call
   `requestTaskDispatch(Task)` without an engine-owned task source.

## Slice 1: Match Boundary Cleanup

Status: source-guard first slice implemented.

Goal: keep the current two-stage strategy but make its boundaries explicit in
code, tests, and trace.

Scope:

1. Name the current strategy in docs and tests as
   `WorkerGroupFirstTwoStageMatch`.
2. Keep implementation concrete. Do not create a generic strategy registry.
   No class rename is required in this slice; `WorkerGroupFirstTwoStageMatch`
   may be a behavior label in docs, tests, and trace while
   `RuleBasedTaskWorkerMatchingStrategy` remains the implementation.
3. Ensure all hot-path candidate acquisition still enters through
   `WorkerManager` / `WorkerCandidateIndex`.
4. Document `targetWorkerId` as a group-scoped direct lookup shortcut, not a
   policy bypass. This engine-side rule should reference the fixed-worker path
   from the transport spine roadmap: worker id resolves to current worker
   relation evidence, then still passes WorkerGroup capability and current
   admission gates.
5. Introduce a source-guard owner API before warm hints. Preferred first-slice
   owner is `WorkerCandidateIndex`, backed by current `WorkerRegistry` slot/meta
   reads. The API should validate:
   - worker still belongs to one selected WorkerGroup
   - optional adapter-node relation still matches
   - current routing policy still maps worker to the observed candidate bucket
   - source-guard rejection is distinguishable from Stage-2 admission rejection
   Matching strategy code must call this API; it must not inspect candidate-bucket
   storage directly.
6. Separate trace reasons for:
   - no candidate source
   - source-guard rejection
   - prefilter rejection
   - rule failure
   - rank/reserve failure
   - dispatch bind failure
7. Add a compact match evidence model or diagnostic record that captures
   aggregate source counts, bounded source-guard detail, prefilter result, rule
   summary, rank score, reserve result, and final decision for an assignment
   attempt.
8. Keep source guards in architecture tests so future hints cannot fall back to
   event/project/all-worker scans.

Acceptance:

1. Existing behavior is unchanged for ordinary tasks.
2. Tests prove candidate source starts from WorkerGroup selector.
3. Tests prove `targetWorkerId` does not fall back to backup workers.
4. Trace/diagnostics identify the owner that rejected a candidate.
5. Match evidence is structured enough for tests to assert source, prefilter,
   rule, rank, reserve, and dispatch-bind outcomes without scraping log text,
   while remaining bounded for large worker pools.
6. Source-guard API rejects stale group/node/route evidence before Stage-2, and
   tests prove matching does not read candidate-bucket internals directly.
7. Architecture guards prevent match strategy code from bypassing centralized
   candidate source or reserve owners.

Landed first slice:

1. `WorkerCandidateIndex` exposes `sourceGuard(...)` as the source-guard owner
   API.
2. Source guard validates current worker slot group, optional adapter node, and
   current candidate-bucket membership before returning a worker candidate.
3. `targetWorkerId` lookup uses the same source guard instead of bypassing
   group/route relation checks.
4. Architecture guards keep matching strategy code out of direct
   `WorkerRegistry` bucket/slot reads.

## Slice 2: Candidate Source Policy Review

Status: first-slice fair source budget implemented.

Goal: tune Stage-1 policy without changing Stage-2 truth.

Scope:

1. Review candidate bucket powerset growth and standard approved route attributes.
2. Add worker-to-bucket diagnostics: bucket count, worker membership count,
   stale member cleanup count.
3. Define and test multi-group and multi-route sample distribution under bounded
   sample limits. First-slice policy: round-robin bucket budget across selected
   `(groupId, candidateBucketKey)` sources, capped by the assignment candidate
   budget. Empty/stale buckets are skipped and remaining budget may be
   redistributed to later buckets in the same bounded pass.
4. Review sample min/max defaults for interactive and bulk tasks.
5. Keep the removed list-only `findWorkerCandidates(task)` overload out of the
   hot-path narrative; diagnostics should consume explicit batch/row evidence.
6. Add source-count trace fields:
   - selected group count
   - selected route key count
   - source bucket size if cheap
   - sampled candidate count

Acceptance:

1. Multi-group / multi-route tasks have a tested round-robin bucket-budget
   policy. A test
   with at least two selected groups or route keys proves one selected bucket
   cannot consume the whole sample budget while another selected bucket still
   has candidates.
2. Candidate sample limits are explained by workload class and dispatch budget.
3. Stage-1 remains bounded and stale-tolerant.
4. Stage-2 still rejects stale or currently invalid candidates.

Landed first slice:

1. `WorkerCandidateIndex` builds bounded `(groupId, candidateBucketKey)` source
   buckets from the selected worker groups and approved task route attributes.
2. Stage-1 candidate acquisition allocates remaining candidate budget fairly
   across remaining source buckets instead of allowing the first large group to
   consume the whole sample.
3. Source guard still validates every sampled worker before Stage-2.
4. The former no-limit list helper has been removed; candidate diagnostics use
   explicit batch row evidence instead of a hidden default sample.

## Slice 3: Stage-2 Policy Review

Status: rule-evaluation diagnostic and rank-policy first slices implemented.

Goal: keep Stage-2 as current-state admission, while making policy choices
visible and adjustable.

Scope:

1. Keep prefilter before QLExpress.
2. Record rule-evaluation count and rule-evaluation latency per assignment
   attempt.
3. Review fixed rank weights for interactive and bulk workloads.
4. Introduce a concrete rank policy object for the current default ranker. Do
   not add a generic plugin registry. Workload-specific weights should come
   from static engine config or `TaskRuntimeProfile`-derived policy, not task
   sharedConfig or worker reports.
5. Keep rank after rule pass and before reserve.
6. Ensure reserve failure reasons remain distinct from rule failure reasons.

Acceptance:

1. Prefilter-only tasks do not pay unnecessary rule or warm-pool overhead.
2. Rule-heavy tasks expose rule evaluation cost in diagnostics.
3. Rank weights can be reviewed or changed without touching candidate source,
   reserve, or dispatch code.
4. Interactive and bulk rank behavior can diverge later without a broad rewrite.
5. Rank policy configuration owner is explicit and does not depend on mutable
   worker reports or per-task business payload.

Landed first slice:

1. `AssignmentRecord` captures per-attempt `ruleEvaluationCount` and
   `ruleEvaluationTotalTimeMs`.
2. `AssignmentRecordService` derives these fields from bounded rule evaluation
   details in the matching hot path.
3. `WorkerCandidateRankPolicy` owns the current default rank weights as a
   concrete value object consumed by `DefaultWorkerCandidateRanker`.
4. Existing matching behavior and default rank order are unchanged.

## Slice 4: Dispatch Signal And Throughput Review

Status: assignment-lane measurement first slice implemented.

Goal: understand and reduce matching throughput bottlenecks without moving
truth into the wrong owner.

This is a diagnostic / measurement slice first. Its deliverable is a short
measurement note at `xa-mass-engine/doc/measurements/MATCH_THROUGHPUT_NOTE.md`, before any
throughput-changing implementation PR. If the measurement note is deliberately
kept inside this file instead, add it under `## Slice 4 Measurement Record` and
link to the commit or run artifact that produced the numbers.

Scope:

1. Measure assignment time inside `TaskAssignWorker` lanes.
2. Measure runtime dispatch pump concurrency and assignment contention.
3. Review retry delay behavior for min-worker gate, no-match, and capacity
   contention.
4. Verify worker availability wakeups shorten wait time for READY tasks.
5. Decide whether lane workers need controlled parallelism or whether runtime
   pump coverage is sufficient for batch workloads.

Acceptance:

1. A measurement note records lane queue depth, assignment duration, retry
   count, runtime pump concurrency, and the measured bottleneck decision.
2. Min-worker gate waiting is observable and not only a fixed-delay mystery.
3. READY/RUNNING redispatch coalescing behavior is documented and tested.
4. Any future parallelism preserves reserve correctness through
   `WorkerRegistry`.

Landed first slice:

1. `TaskAssignWorker` records assignment duration on `ASSIGNMENT_QUEUE_SNAPSHOT`
   for processed attempts and no-match retry scheduling.
2. The measurement note lives at
   [`doc/measurements/MATCH_THROUGHPUT_NOTE.md`](../measurements/MATCH_THROUGHPUT_NOTE.md).
3. No lane parallelism or dispatch concurrency behavior changed in this slice.

## Slice 5: Warm Candidate Hints

Goal: add a task-local warm candidate hint after the match boundary and registry
cleanup work are stable.

Status: first slice implemented. `TaskCandidateWarmPool` is bounded task-local
hint state owned by `xa-mass-worker-runtime`. `WorkerManager` rehydrates warm entries through
`WorkerCandidateIndex.sourceGuard(...)`, dedupes warm and cold candidates, and
falls back to the normal cold candidate source. The assignment listener records
warm hints only for workers that produced bound dispatch work; matching strategy
code does not write warm hints. `targetWorkerId` tasks skip warm sampling.
Assignment context records warm, cold, and warm source-guard rejection counts as
diagnostics only.

Warm pool is a pre-Stage-2 candidate priority hint. It can prefer candidates
that recently passed this task's source/admission path, but it is not a new
matching truth, not candidate-bucket membership truth, and not an eligibility /
admission cache.

Warm entry shape for first slice:

```text
TaskCandidateWarmEntry
  taskId
  workerId
  observedGroupId
  observedAdapterNodeId
  observedCandidateBucketKey
  observedAt
```

Scope:

1. Add `TaskCandidateWarmPool` as bounded task-local hint state.
2. Use per-task and global entry caps.
3. Use TTL and source-guard pruning only; do not add dependency-key
   invalidation in the first slice.
4. Do not add worker version fields.
5. Disable warm sampling for `targetWorkerId` tasks in the first slice.
6. Rehydrate warm ids through the Slice-1 source-guard owner API before Stage-2
   sees them. Matching strategy code must not read candidate-bucket internals
   directly.
7. Route-bucket source guard is explicit inside that owner API: fetch current
   worker meta/slot, verify selected WorkerGroup and adapter-node relation,
   recompute current candidate bucket keys with the same routing policy used by
   `WorkerRegistry` registration, and reject a warm entry when
   `observedCandidateBucketKey` is no longer in that current set. TTL alone is not
   enough to prove candidate-bucket validity.
8. Cold-fill through the normal candidate source after warm rehydration.
9. Dedupe warm and cold candidates before Stage-2.
10. Insert warm entries only after dispatch binding proves the worker actually
   received work for the task. A Stage-2 pass, reserve, lock, or later release
   is not enough by itself to create a warm hint.
11. Keep dependency-key invalidation as a later optional slice.

Acceptance:

1. Warm candidates never bypass Stage-2.
2. Warm entries from a stale WorkerGroup / adapter node / candidate bucket are
   rejected by source guard before Stage-2.
3. `targetWorkerId` direct lookup is not suppressed by warm entries.
4. Empty, stale, or dropped warm state degrades to normal cold candidate source.
5. Assignment diagnostics show warm/cold counts and source-guard rejections.
6. Warm pool does not hold reservations, locks, leases, or dispatch truth.
7. Runtime dispatch pump and lane-driven assignment can touch warm state safely.
8. Source guard is exposed through a match/source owner API; matching code does
   not directly inspect candidate-bucket storage.
9. Matching strategy code does not write warm hints; dispatch/assignment code
   records them only after bound work exists.

## Testing Plan

Engine tests:

- in-memory and Redis heartbeat cleanup share expiration semantics
- stale heartbeat cleanup removes stale candidates from candidate buckets and
  prevents new reserve
- worker bucket removal uses known worker-to-bucket memberships
- candidate source starts from WorkerGroup selector
- target worker direct lookup remains group scoped and has no backup fallback
- multi-group / multi-route sampling remains fair enough under bounded limits
- prefilter, rule, rank, reserve, and dispatch bind rejections have distinct
  diagnostics
- match evidence uses aggregate counters plus bounded candidate details
- warm candidates are source-guarded, deduped, revalidated, and bounded

Baseline docs:

- update `SCHEDULING_KERNEL_BASELINE.md` when match owner boundaries change
- update `SCHEDULING_CORRECTNESS_MATRIX.md` when a slice adds or changes a
  regression gate
- keep this roadmap as future direction; do not use it as proof of current
  implementation behavior after slices land

Concurrency tests:

- concurrent assignment attempts do not corrupt match-side hint state
- runtime dispatch pump and lane-driven assignment can overlap safely
- reserve correctness remains in `WorkerRegistry`
- warm pool put/sample/clear remains bounded under worker updates

Architecture guards:

- matching must consume centralized candidate source
- matching must not call candidate-bucket internals directly when a source owner API
  exists
- warm pool must not call rule evaluation or reserve
- maintenance must not evaluate task-specific rules
- dispatch bind and result finality must not move into match strategy code

Trace/proof:

- assignment summary includes source, prefilter, rule, rank, reserve, and
  dispatch-bind counts where available
- match evidence records enough structured fields for tests and UI diagnostics
  to distinguish source, admission, preference, reserve, and dispatch outcomes
- warm/cold candidate counts are optional and clearly marked as hints
- no trace labels a hint as eligibility truth

## Risks

### Risk 1: Strategy Boundaries Become Premature Abstraction

Mitigation:

- keep one concrete default strategy
- improve names, tests, and owner boundaries before adding interfaces
- add abstractions only when a second real strategy needs them

### Risk 2: Candidate Hints Become Scheduling Truth

Mitigation:

- require source guard and Stage-2 revalidation
- keep reserve in `WorkerRegistry`
- trace hint source separately from eligibility decision

### Risk 3: Registry Staleness Makes Match Diagnostics Noisy

Mitigation:

- complete Slice 0A and the source-guard part of Slice 1 before warm hints
- distinguish source-guard rejection from Stage-2 rejection
- keep stale candidate indexes allowed but observable

### Risk 4: Tuning Optimizes The Wrong Cost Center

Mitigation:

- measure rule-evaluation latency and candidate reuse
- avoid warm-pool overhead for small or prefilter-only tasks
- review rank and sample policy with workload class data
- require Slice 4 measurement notes before throughput-changing PRs

### Risk 5: Throughput Fixes Break Correctness

Mitigation:

- add measurements before adding lane parallelism
- keep reserve atomic in `WorkerRegistry`
- preserve task runtime claim/finality owners

## Recommended Implementation Order

```text
Slice 0A: Registry Candidate Hygiene
Slice 0B: Dispatch Wakeup And Dedupe Semantics
Slice 1: Match Boundary Cleanup
Slice 2: Candidate Source Policy Review
Slice 3: Stage-2 Policy Review
Slice 4: Dispatch Signal And Throughput Review
Slice 5: Warm Candidate Hints
```

Do not start by building a strategy plugin framework. The first goal is a
cleaner mainline where the current two-stage strategy is obvious, testable, and
not tangled with lifecycle or runtime truth.
