# Scheduling Kernel Guardrails

Last updated: 2026-05-16

Status: current engine scheduling guardrails.

This file is intentionally short. It is not a long-range roadmap and it is not
the detailed implementation baseline for every scheduling type. Its job is to
keep the scheduling kernel stable while policy quality evolves.

Use with:

- [README.md](../../README.md)
- [POLICY_INTERACTION_BASELINE.md](./2026-04-20_POLICY_INTERACTION_BASELINE.md)
- [WORKER_SCHEDULING_VIEW_BASELINE.md](./2026-05-15_WORKER_SCHEDULING_VIEW_BASELINE.md)
- [SCHEDULING_UPGRADE_ROADMAP.md](./2026-05-15_SCHEDULING_UPGRADE_ROADMAP.md)
- [../../../xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md](../../../xa-mass-engine/doc/baseline/HIGH_VOLUME_MODEL_BASELINE.md)

## 1. Purpose

Scheduling refactors do not have to solve peak performance immediately.

They do have to preserve these three properties:

- the scheduling mainline stays readable end to end
- strategy decisions stay replaceable without rewriting runtime mechanisms
- future policy upgrades do not force result, runtime, or transport owner drift

The engine should therefore converge toward:

```text
assignment signal admission
  -> worker candidate source
  -> eligibility match
  -> candidate ranking
  -> allocation / budget decision
  -> runtime claim and dispatch bind
  -> result-side release / retry / redispatch
```

The default policy may stay simple, conservative, and even somewhat inefficient
for a while. What must not happen is policy logic leaking back into the
mechanisms that execute lifecycle mutations.

## 2. Fixed Scheduling Mainlines

These mainlines are the current north-star baseline for engine scheduling work.
They describe owner boundaries, not package layout. Future refactors may rename
or split classes, but they must preserve the same answers unless the owning
baseline is updated in the same change.

### 2.1 Assignment Orchestration

```text
TaskWorkerAssignListener
  -> read task/runtime facts
  -> ask AssignmentAllocationPolicy
  -> ask matching/acquisition path
  -> ask SimpleTaskDispatchBinder
  -> update task + emit assignment event
```

`TaskWorkerAssignListener` is the cross-aggregate orchestration mechanism. It
does not own worker budget formulas, candidate ranking, resource cleanup
primitives, runtime claim semantics, or result convergence.

### 2.2 Allocation And Budget

```text
AssignmentAllocationPolicy
  -> desired dispatch worker count
  -> minimum start gate
  -> requested match count
  -> dispatch candidate limit
  -> budget-exhausted decision
```

`WorkerBudgetPolicy` is the budget sub-policy consumed by allocation. Fixed
defaults are acceptable. Budget formulas must not move into the listener or
binder.

### 2.3 Eligibility, Ranking, And Current Acquisition

```text
WorkerSchedulingCandidateEnumerator
  -> WorkerSchedulingView
  -> WorkerMatchContext
  -> prefilter + QLExpress rules
  -> WorkerCandidateRanker
  -> capacity reservation + optional worker lock
```

Rules remain the correctness gate. `WorkerCandidateRanker` only orders
rule-passed candidates. Current code still performs reservation and optional
exclusive worker-lock acquisition inside `RuleBasedTaskWorkerMatchingStrategy`;
that is a known transitional shape, not proof that acquisition is pure matching
policy. A future split into a dedicated acquisition owner is allowed when it
improves a real lifecycle boundary, but do not add a pass-through wrapper that
only renames the same work.

### 2.4 Dispatch Binding

```text
SimpleTaskDispatchBinder
  -> claim TaskWorkRuntime
  -> create attempt/dispatch binding
  -> submit dispatch batch
  -> compensate submit failure
```

The binder does not decide worker preference, requested worker count, minimum
start gates, or terminal/result policy.

### 2.5 Resource Release And Refill

```text
TaskResourceReleaseListener
  -> finalize observed worker load
  -> release exclusive resource via WorkerDispatchResourceReleaser
  -> ask AssignmentRefillPolicy
  -> request dispatch if policy allows
```

`WorkerDispatchResourcePolicy` owns exclusive-lock usage. `WorkerDispatchResourceReleaser`
owns reservation/lock cleanup mechanics. `AssignmentRefillPolicy` owns refill
decision formulas. Do not duplicate those decisions in listener or binder
paths.

### 2.6 Runtime And Result Truth

```text
TaskWorkRuntime
  -> ready/delayed/lease/retry/counter truth

TaskResultRuntime
  -> stable-final public result rows
  -> result sequence
  -> result-side repair/barrier truth
```

Projection residue, trace output, SDK snapshots, and server DTOs must not
reverse-drive runtime or result ownership.

### 2.7 Worker Management Boundary

Engine scheduling consumes worker scheduling views, reachability, load, and
resource acquisition facts. It does not own device/account lifecycle management.
`WorkerContext` is no longer a scheduling truth in the engine hot path. Runtime,
transport, projection, SDK/API, server payloads, and canonical trace identity
are worker-level.

## 3. Kernel Rule

Treat scheduling as two kinds of code:

- mechanisms:
  perform queueing, locking, claiming, binding, releasing, retry wakeup, task
  mutation, and trace publication
- policies:
  decide among valid options such as eligibility preference, worker count,
  budget caps, fairness, and future capacity semantics

The rule is:

> a future strategy change should mostly change a policy owner, not the
> scheduling mainline mechanism owners

If a proposed change requires editing allocation formulas, binder behavior,
result finality, and task convergence together, the owner boundary is probably
wrong.

## 4. Current Mechanism Owners

These are the current scheduling mechanisms. They may consume policy output, but
they must not quietly become policy owners.

| Mechanism | Owns | Must not own |
| --- | --- | --- |
| `TaskAssignWorker` | assignment signal queueing, lane isolation, dedup, defer/retry, queue pressure handling | worker eligibility, ranking formula, task-level budget |
| `TaskWorkerAssignListener` | assignment orchestration, invoking matching, consuming allocation decision, releasing skipped reservations/locks, task status transition, assignment trace, assignment event publication | inline allocation formulas, ranking logic, runtime result semantics |
| `RuleBasedTaskWorkerMatchingStrategy` | current worker eligibility/ranking/acquisition path: candidate enumeration, prefilter execution, rule evaluation execution, reservation and optional lock attempt in ranked order, match diagnostics | task terminal policy, task progress, runtime claim/bind, allocation/budget formulas |
| `SimpleTaskDispatchBinder` | runtime claim, attempt creation, dispatch binding, submit-failure compensation around claim outcomes | candidate ranking, worker budget, min-start gate |
| `TaskResultService` plus release listeners | attempt close, retry reset, final-result release trigger, redispatch trigger after result-side outcomes | worker ranking, allocation formulas, assignment lane policy |

## 5. Current Policy Owners

These are real policy seams. A fixed default implementation is acceptable. A
hidden formula inside a mechanism is not.

| Policy | Current owner | Decision examples |
| --- | --- | --- |
| assignment signal priority | runtime profile resolver consumed by `TaskAssignWorker` | which lane, which priority, how interactive differs from bulk |
| worker eligibility | prefilter plus matching rules over `WorkerSchedulingView` | transport reachability, dispatch-enabled state, event/project capability, routing, target-worker fit |
| candidate preference order | `WorkerCandidateRanker` | load-first, affinity-first, fairness-first, stickiness |
| task-level allocation shape | `AssignmentAllocationPolicy` | desired worker count, requested match count, dispatch candidate limit, minimum start gate |
| worker budget | `WorkerBudgetPolicy` consumed by allocation | cap large BULK waves, preserve INTERACTIVE headroom |
| dispatch resource usage | `WorkerDispatchResourcePolicy` | exclusive vs shared worker use from task execution semantics |
| refill | `AssignmentRefillPolicy` | whether released capacity should request another assignment attempt |

## 6. Handoff Rules

Keep these handoffs explicit:

1. `TaskAssignWorker` admits a task into competition.
2. matching receives a task and a requested match count.
3. matching returns rule-passed, ranked scheduling candidates.
4. allocation decides how many matched candidates may enter dispatch now.
5. binder claims ready runtime work and creates dispatch bindings.
6. result-side lifecycle closes attempts and reopens redispatch through runtime
   truth, not through ad hoc scheduler backdoors.

Meaning:

- matching answers `who is eligible`
- ranking answers `who is preferred first`
- allocation answers `who should be consumed this round`
- acquisition answers `which candidate resources are reserved/locked now`
- binder answers `which work items were actually claimed and bound`

Do not collapse those answers into one opaque method again.

## 7. Hard Boundaries

Scheduling changes must preserve these boundaries:

- do not move runtime truth out of `TaskWorkRuntime`
- do not move stable-final result truth out of `TaskResultRuntime`
- do not let compatibility projection become dispatch, retry, or fairness truth
- do not let trace requirements define policy ownership
- do not let server DTOs or SDK snapshots define scheduling kernel semantics
- do not reintroduce per-message hot-path rule matching as a scaling fallback
- do not make `TaskManager` the default place for new scheduling formulas when a
  narrower policy owner already exists
- do not reintroduce WorkerContext storage, state mutation, or rule variables as
  scheduling truth

## 8. Performance Guardrail

This file is not a promise that every scheduling phase must improve throughput
immediately.

It is a guardrail against structural regressions that make future performance
work harder:

- no hot-path full-task or full-worker scans just to choose dispatch candidates
- no runtime round-trips inside ranking loops when a push-updated view can carry
  the signal
- no strategy code hidden inside bind/result/release mechanisms
- no policy change that requires rewriting transport or result convergence to
  land

The kernel should be performance-ready before it is performance-maximized.

## 9. Review Checklist

Before merging a scheduling change, answer these questions explicitly:

1. Which mechanism owner changed?
2. Which policy owner changed?
3. Could the same behavior change have been expressed by replacing a policy
   decision instead of editing a mechanism?
4. Does canonical trace prove the decision without becoming the owner of it?
5. Did the change keep runtime/result/projection truth boundaries intact?
6. If a future fairness or capacity policy changes, will this code need to be
   rewritten again?

If the last answer is `yes`, the seam is still too weak.

## 10. Working Rule For The Next Refactors

Near-term scheduling work should prefer:

- strengthening existing policy seams
- clarifying mechanism ownership
- making tests prove policy-vs-mechanism separation

It should avoid:

- large semantic renames with no owner improvement
- public model expansion before kernel semantics are decided
- deleting `WorkerContext` everywhere before binding and trace no longer rely on
  it
- introducing a new umbrella scheduler abstraction that hides the current
  explicit mainline

The target is a stable scheduling kernel, not an abstract scheduling framework.
