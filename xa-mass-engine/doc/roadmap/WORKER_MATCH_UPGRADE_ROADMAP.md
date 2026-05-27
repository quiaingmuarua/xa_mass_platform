# Worker Match Upgrade Roadmap

Status: design roadmap; no implementation yet.

This roadmap continues from
[`TRANSPORT_WORKER_MATCH_SPINE_ROADMAP.md`](../../../transport/TRANSPORT_WORKER_MATCH_SPINE_ROADMAP.md)
after the TW-4 spine work. Transport spine owns transport registration,
fixed-worker routing, worker relation evidence, and dispatch handoff seams; this
roadmap owns the engine-side worker match strategy, policy, diagnostics, and
follow-up tuning.

Transport spine keeps the transport boundary and TW-1C scaling intent. This
roadmap owns the engine-side follow-through for bounded candidate acquisition,
route-bucket cleanup, and match diagnostics that consume those spine contracts.

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
       adapter node / route bucket
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

## Current Code Observations

- Candidate source enters through `WorkerManager.findWorkerCandidates(...)` and
  `WorkerCandidateIndex`, then calls `WorkerRegistry.acquireCandidates(...)`.
- Stage-1 sample size is currently `requestedMatchCount * 4`, clamped by
  JVM-configurable min/max defaults of 512 and 2048.
- Route buckets use approved attribute powersets. With four standard route
  attributes, a fully attributed worker writes to the default bucket plus 15
  attribute buckets.
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
- route bucket
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
- route-bucket membership truth
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

It must not become:

- a second source of eligibility truth
- a cached rule outcome owner
- a replacement for `WorkerRegistry` reserve state
- a task result finality owner

## Slice 0: Registry Hygiene

Goal: make candidate-source cleanup reliable before adding more match strategy
features.

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
6. Replace broad route-bucket removal scans with worker-to-bucket membership.
   `removeFromBuckets` must remove only the bucket keys indexed for the worker
   being removed.
7. Add or identify an engine-side dispatch wakeup bridge that wires worker
   availability callbacks to both relevant dispatch recovery paths:
   lane/task-signal redispatch through `requestTaskDispatch(...)` for tasks
   already waiting in `TaskAssignWorker` retry/backoff, and
   runtime-driven batch admission through
   `RuntimeReadyDispatchPump.wakeIdleAdmissions()`. Current wiring that only
   wakes the runtime-ready pump is incomplete for lane retry latency.
   Worker/transport owners must call only the bridge callback, not the pump
   directly.
8. Fix or explicitly accept the READY-state dedupe window in `TaskAssignWorker`.

Acceptance:

1. In-memory and Redis registries keep `cleanupExpiredHeartbeats(nowMillis, limit)`
   as a current-time API and agree on internal heartbeat-deadline semantics.
2. Both in-memory and Redis `tryReserve(...)` return the same stale-heartbeat
   rejection for a slot whose heartbeat deadline has passed, whether or not
   cleanup has already removed the candidate from route buckets.
3. Worker unregister / group move / route-attribute update removes only known
   bucket memberships from the worker-to-bucket reverse index.
4. Worker registration, online, capability, and AVAILABLE-state transitions call
   the engine wakeup bridge, and the bridge can wake both lane-driven redispatch
   and runtime-ready batch admission where each path is configured.
5. READY tasks do not silently lose a meaningful redispatch signal unless the
   accepted behavior is documented and traced.

## Slice 1: Match Boundary Cleanup

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
5. Separate trace reasons for:
   - no candidate source
   - source-guard rejection
   - prefilter rejection
   - rule failure
   - rank/reserve failure
   - dispatch bind failure
6. Add a compact match evidence model or diagnostic record that captures source
   counts, source-guard result, prefilter result, rule summary, rank score,
   reserve result, and final decision for an assignment attempt.
7. Keep source guards in architecture tests so future hints cannot fall back to
   event/project/all-worker scans.

Acceptance:

1. Existing behavior is unchanged for ordinary tasks.
2. Tests prove candidate source starts from WorkerGroup selector.
3. Tests prove `targetWorkerId` does not fall back to backup workers.
4. Trace/diagnostics identify the owner that rejected a candidate.
5. Match evidence is structured enough for tests to assert source, prefilter,
   rule, rank, reserve, and dispatch-bind outcomes without scraping log text.
6. Architecture guards prevent match strategy code from bypassing centralized
   candidate source or reserve owners.

## Slice 2: Candidate Source Policy Review

Goal: tune Stage-1 policy without changing Stage-2 truth.

Scope:

1. Review route bucket powerset growth and standard approved route attributes.
2. Add worker-to-bucket diagnostics: bucket count, worker membership count,
   stale member cleanup count.
3. Define and test multi-group and multi-route sample distribution under bounded
   sample limits. The first policy can be round-robin or proportional, but it
   must be explicit.
4. Review sample min/max defaults for interactive and bulk tasks.
5. Remove `WorkerManager.DEFAULT_STAGE_ONE_CANDIDATE_LIMIT` from the hot-path
   narrative if the no-limit `findWorkerCandidates(task)` overload remains
   unused by matching. If a non-hot diagnostic caller still needs a default,
   rename it to make clear it is not the production Stage-1 sample policy.
6. Add source-count trace fields:
   - selected group count
   - selected route key count
   - source bucket size if cheap
   - sampled candidate count

Acceptance:

1. Multi-group / multi-route tasks have a tested distribution policy. A test
   with at least two selected groups or route keys proves one selected bucket
   cannot consume the whole sample budget while another selected bucket still
   has candidates.
2. Candidate sample limits are explained by workload class and dispatch budget.
3. Stage-1 remains bounded and stale-tolerant.
4. Stage-2 still rejects stale or currently invalid candidates.

## Slice 3: Stage-2 Policy Review

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

## Slice 4: Dispatch Signal And Throughput Review

Goal: understand and reduce matching throughput bottlenecks without moving
truth into the wrong owner.

This is a diagnostic / measurement slice first. Its deliverable is a short
measurement note at `doc/measurements/MATCH_THROUGHPUT_NOTE.md`, before any
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

## Slice 5: Warm Candidate Hints

Goal: add a task-local warm candidate hint after the match boundary and registry
cleanup work are stable.

Warm pool is a Stage-2 hit-rate optimization, not a new matching truth and not
a Stage-1 lookup optimization.

Warm entry shape for first slice:

```text
TaskCandidateWarmEntry
  taskId
  workerId
  observedGroupId
  observedAdapterNodeId
  observedRouteBucketKey
  observedAt
```

Scope:

1. Add `TaskCandidateWarmPool` as bounded task-local hint state.
2. Use per-task and global entry caps.
3. Use TTL and source-guard pruning only; do not add dependency-key
   invalidation in the first slice.
4. Do not add worker version fields.
5. Disable warm sampling for `targetWorkerId` tasks in the first slice.
6. Rehydrate warm ids through current WorkerGroup-first source guard before
   Stage-2 sees them.
7. Route-bucket source guard is explicit: fetch current worker meta and
   recompute the current route bucket keys with the same routing policy used by
   `WorkerRegistry` registration; reject a warm entry when
   `observedRouteBucketKey` is no longer in that current set. TTL alone is not
   enough to prove route-bucket validity.
8. Cold-fill through the normal candidate source after warm rehydration.
9. Dedupe warm and cold candidates before Stage-2.
10. Insert only unreserved candidates, or reserved-but-not-dispatched candidates
   after successful release.
11. Keep dependency-key invalidation as a later optional slice.

Acceptance:

1. Warm candidates never bypass Stage-2.
2. Warm entries from a stale WorkerGroup / adapter node / route bucket are
   rejected by source guard before Stage-2.
3. `targetWorkerId` direct lookup is not suppressed by warm entries.
4. Empty, stale, or dropped warm state degrades to normal cold candidate source.
5. Assignment diagnostics show warm/cold counts and source-guard rejections.
6. Warm pool does not hold reservations, locks, leases, or dispatch truth.
7. Runtime dispatch pump and lane-driven assignment can touch warm state safely.

## Testing Plan

Engine tests:

- in-memory and Redis heartbeat cleanup share expiration semantics
- stale heartbeat cleanup removes stale candidates from route buckets and
  prevents new reserve
- worker bucket removal uses known worker-to-bucket memberships
- candidate source starts from WorkerGroup selector
- target worker direct lookup remains group scoped and has no backup fallback
- multi-group / multi-route sampling remains fair enough under bounded limits
- prefilter, rule, rank, reserve, and dispatch bind rejections have distinct
  diagnostics
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
- matching must not call route-bucket internals directly when a source owner API
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

- complete Slice 0 before warm hints
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
Slice 0: Registry Hygiene
Slice 1: Match Boundary Cleanup
Slice 2: Candidate Source Policy Review
Slice 3: Stage-2 Policy Review
Slice 4: Dispatch Signal And Throughput Review
Slice 5: Warm Candidate Hints
```

Do not start by building a strategy plugin framework. The first goal is a
cleaner mainline where the current two-stage strategy is obvious, testable, and
not tangled with lifecycle or runtime truth.
