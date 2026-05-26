# Production Scheduling Kernel Improvement Notes

Last updated: 2026-05-18

Status: future improvement notes, not implementation proof.

This document records the remaining high-value gaps after the scheduling
kernel convergence work. It is intentionally not an active phase plan. Use it
to decide future roadmap slices, then verify current behavior from code,
tests, and baseline docs before implementation.

## Current Position

The scheduling kernel now has a stable v1 mainline:

```text
assignment signal
  -> candidate source
  -> eligibility + ranking
  -> allocation / budget
  -> resource admission
  -> runtime claim + dispatch bind
  -> result-side release / retry / refill
```

Completed convergence that should be treated as current baseline:

- worker scheduling is worker-level, not `WorkerContext`-level
- candidate source is centralized behind `WorkerManager.findWorkerCandidates(...)`
  and backed by `WorkerCandidateIndex`
- ranking, allocation, budget, resource usage, release, and refill each have
  explicit owners
- worker occupancy, capacity, and dispatch gates are owned by `WorkerRegistry`
  slot state, not by storage locks or live queries inside matching loops
- event owner surfaces now route through owner-backed services and SDK
  contracts rather than event handlers or concrete owner internals
- server is a validation shell / SDK adapter; it must not redefine engine
  semantics

That is enough to call the current shape a scheduling kernel baseline. It is
not enough to call it a production-grade adaptive scheduler.

## Remaining Kernel Gaps

### 1. Time Execution Kernel

Current code has time-based task execution support, but the capability is not
generalized.

What already exists:

- `LeaseExpireWatchdog` polls expired task-work leases and calls
  `TaskRuntimeMaintenancePort.expireLeasedWork(...)`
- `LeaseExpireWatchdog` also polls max-runtime task expiry and terminates tasks
  with `MAX_RUNTIME_REACHED`

What is still missing:

- worker command `deadlineEpochMillis` is stored but not actively enforced by a
  scheduler
- command states such as `REQUESTED`, `DELIVERY_ACCEPTED`, and
  `EXECUTION_ACCEPTED` have no timeout policy owner
- task item stage evidence has timestamps, but no SLA/checkpoint timeout owner
- there is no common owner vocabulary for time-triggered lifecycle mutation
  outside the existing task lease watchdog

Future direction:

```text
time trigger scheduler
  -> task lease / max-runtime trigger
  -> worker command deadline trigger
  -> future stage SLA trigger
```

Boundary rules:

- time triggers may advance owner state; they must not become query/reporting
  jobs
- task lease expiry must continue through `TaskWorkRuntime` and
  `TaskResultService`
- worker command expiry must enter `WorkerCommandLifecycleOwner`
- stage SLA, if added, must enter the stage evidence owner and not task result
  runtime

Suggested first slice:

- add command deadline expiry only
- keep task lease behavior unchanged
- do not implement command delivery/retry in the same slice

### 2. Feedback-Driven Ranking

Current ranking is open-loop. `DefaultWorkerCandidateRanker` uses fixed
heuristics:

```text
score = load * 0.6 + affinity * 0.3 + availability * 0.1
```

This is useful as a deterministic default, but it does not learn from runtime
outcomes.

Missing scheduling feedback:

- worker + event success rate
- worker + event latency distribution
- retry/failure patterns
- recent degradation or recovery
- rolling confidence windows

Future direction:

```text
result / trace / runtime event
  -> side-channel performance accumulator
  -> WorkerPerformanceView snapshot
  -> WorkerCandidateRanker
```

Boundary rules:

- do not synchronously update ranking state inside result apply hot paths
- do not query result rows from matching
- ranking must read a bounded snapshot, not perform live analytics
- trace may feed offline or side-channel aggregation, but trace is not runtime
  truth

Suggested first slice:

- define `WorkerPerformanceView` as a read-only scheduling snapshot
- seed it with simple in-memory event success/latency counters from a
  side-channel listener
- keep the default ranker deterministic and make performance weighting explicit

### 3. Cross-Task Fairness

Current policies control how many workers one task may request, but they do not
coordinate fairness across tasks in the same lane.

What already exists:

- `TaskAssignWorker` separates lanes and applies dispatch priority inside each
  lane
- `AssignmentAllocationPolicy` owns one task's allocation decision
- `WorkerBudgetPolicy` caps one task's concurrent worker usage

What is still missing:

- cross-task arbitration inside one lane
- aging or quota for tasks waiting behind large tasks
- fairness proof that small tasks cannot starve behind sustained large-task
  competition

Future direction:

```text
assignment signal admission
  -> task competition / fairness policy
  -> assignment orchestration
```

Boundary rules:

- do not hide fairness inside `TaskWorkerAssignListener`
- do not make worker budget a global fairness policy; it is task-local
- queue order, task priority, aging, and quota should have one explicit owner

Suggested first slice:

- introduce a task competition decision owner without changing behavior
- add trace/test evidence for same-lane large-task vs small-task competition
- then add the first bounded fairness rule, such as aging or deficit quota

### 4. Worker Command Execution Path

Current worker command code has correct lifecycle ownership shape, but the
execution path is not complete.

What already exists:

- `WorkerCommandLifecycleOwner`
- `WorkerCommandDeliveryCoordinator`
- `WorkerCommandDeliveryPort`
- SDK/server owner-backed command request, acknowledgement, and read surfaces

What is still missing:

- real delivery port implementation
- automatic request-to-delivery handoff
- worker-side acknowledgement ingress through runtime/transport
- delivery retry or unavailable policy
- command deadline enforcement

Future direction:

```text
command request
  -> WorkerCommandLifecycleOwner
  -> WorkerCommandDeliveryCoordinator
  -> command delivery port
  -> worker ack/status ingress
  -> WorkerCommandLifecycleOwner
```

Boundary rules:

- command status must not enter task result runtime
- command delivery must not reuse task-work dispatch as a hidden command path
- acknowledgement ingress shape is decided by the command lifecycle owner, not
  by transport convenience

Suggested first slice:

- implement command deadline expiry before delivery retry
- then add one minimal delivery implementation and ack path

### 5. Worker Registry Contention

`WorkerRegistry` is now the worker occupancy owner. The old `WorkerLoadView`
global-monitor issue is retired from production wiring; future contention work
must target registry implementations directly.

Current bottleneck:

- per-worker reserve / confirm / release / final mutation
- Redis registry mutation that still relies on connection-scoped
  `WATCH` / `MULTI` protection
- candidate bucket cleanup under large group/route membership

These are mechanism-level concerns. Do not reintroduce a separate load view,
storage lock set, or dispatch gate map to solve them.

Future direction:

```text
WorkerRegistry
  -> per-worker slot CAS / small Redis Lua mutation
  -> bounded candidate sampling
  -> task-level active worker indexes
```

Boundary rules:

- no behavior change
- no live runtime queries in matching
- preserve task-level active worker count semantics
- keep `WorkerRegistry` as the only worker occupancy/admission truth

Suggested first slice:

- replace Redis registry `WATCH` / `MULTI` mutation with small Lua only where
  multiple keys must update atomically
- add high-contention tests proving independent workers mutate concurrently,
  same-worker capacity remains correct, and stale bucket candidates are rejected

## Suggested Priority

| Priority | Work | Reason |
| --- | --- | --- |
| P0 | Time execution kernel baseline | shared prerequisite for command expiry, stage SLA, and active timeout behavior |
| P0.5 | `WorkerRegistry` mutation contention | keeps worker admission scalable without adding a second occupancy truth |
| P1 | Worker command delivery and acknowledgement | turns command lifecycle from owner skeleton into usable control path |
| P1 | Cross-task fairness owner | prevents same-lane starvation and protects production scheduling correctness |
| P2 | Feedback-driven ranking | improves dispatch quality after core timeout/fairness mechanics are stable |

## Final Non-Feature Closeout

Before starting new scheduling functionality, finish these low-risk closeout
items:

- keep completed roadmap documents in `doc/archive/xa-mass-engine/`; do not
  leave completed phase plans in module roots
- keep `SCHEDULING_KERNEL_BASELINE.md` as the concise current truth and avoid
  reintroducing parallel baseline narratives
- keep `EVENT_OWNER_BOUNDARY.md` current if owner-backed command/stage/capability
  surfaces change
- keep server/SDK docs clear that server adapts SDK contracts and does not
  own kernel semantics
- add narrow architecture guards only when a removed path has a realistic risk
  of returning; avoid broad grep bans that block legitimate tests or docs
- verify any future roadmap claim against current code before treating it as
  implemented behavior

## Not Planned As Kernel Work

The following may be useful product or operator features, but they should not
drive the scheduling kernel mainline:

- scan-heavy dashboards inside matching or result hot paths
- CRUD-style device/account management inside engine
- trace-query behavior feeding synchronous scheduling decisions
- server DTOs becoming engine decision input
- compatibility adapters for removed internal paths
