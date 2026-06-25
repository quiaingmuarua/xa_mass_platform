# Scheduling Kernel Baseline

Last updated: 2026-05-18

Status: current engine scheduling baseline.

This document is the current owner map for scheduling. It replaces the older
split guardrail, worker-view, and policy-interaction notes with one current
read path.

## Core Rule

Scheduling is split into:

- mechanisms: queueing, locking, claiming, binding, releasing, retry wakeup,
  task mutation, and trace publication
- policies: eligibility, ranking, allocation shape, budget, resource usage,
  refill, and future fairness/capacity choices

A future strategy change should mostly change a policy owner, not rewrite the
runtime mechanisms that execute lifecycle mutations.

```text
assignment signal
  -> candidate source
  -> eligibility + ranking
  -> allocation / budget
  -> resource admission
  -> runtime claim + dispatch bind
  -> result-side release / retry / refill
```

## Current Mainline

| Stage | Current owner | Must not own |
| --- | --- | --- |
| assignment admission | `TaskAssignWorker` | eligibility, ranking, task budget |
| assignment orchestration | `TaskWorkerAssignListener` | inline budget formulas, result finality |
| allocation shape | `AssignmentAllocationPolicy` | task mutation, claim/bind |
| worker budget | `WorkerBudgetPolicy` | eligibility, result interpretation |
| candidate source | `WorkerManager.findWorkerCandidateBatch(...)` backed by `WorkerCandidateIndex` | rule evaluation, acquisition |
| worker scheduling read model | `WorkerSchedulingCandidate` / `WorkerSchedulingView` / `WorkerMatchContext` | device/account lifecycle ownership |
| eligibility and preference | prefilter + QLExpress rules + `WorkerCandidateRanker` | runtime claim, terminal policy |
| current acquisition path | `RuleBasedTaskWorkerMatchingStrategy` | allocation formulas, result convergence |
| dispatch binding | `SimpleTaskDispatchBinder` | candidate preference, min-start gate |
| resource usage | `WorkerDispatchResourcePolicy` | ranking, terminal policy |
| cleanup | `WorkerDispatchResourceReleaser` | refill formulas |
| refill | `AssignmentRefillPolicy` | release mechanics |
| runtime/result truth | `TaskWorkRuntime` / `TaskResultRuntime` | projection-driven dispatch truth |

The current matching strategy still combines rule execution, ranking, capacity
reservation, and optional exclusive-lock acquisition. That is the current
acquisition mechanism, not a promise that matching is the final long-term owner.
Split it only when there is a real lifecycle boundary to protect; do not add a
pass-through wrapper.

## Worker Scheduling Surface

WorkerContext is retired from scheduling truth. The hot path is worker-level:

```text
WorkerSchedulingCandidateEnumerator
  -> WorkerSchedulingCandidate
  -> WorkerSchedulingView
  -> WorkerMatchContext
```

Current worker scheduling evidence includes:

- worker identity, group identity, and group-indexed candidate-source evidence
- dispatch gate and worker-runtime registry eligibility; reachability
  diagnostics must not be read as a separate selection gate
- dispatch-enabled and worker lock state
- routing tags and scheduling attributes from worker-level facts
- load/reservation facts from `WorkerRegistry` / `WorkerSlot`
- declared capacity and task-level active worker count

Current matching and proof must use worker-level fields such as:

- `workerSchedulingAttributes`
- `workerSchedulingRoutingTags`
- `workerSchedulingMatchesRoutingCode`
- `workerActiveLeaseCount`
- `workerReservedCount`
- `workerDeclaredCapacity`
- `workerEstimatedLoadRatio`

Retired scheduling proof must not return:

- `WorkerContext` identity
- `workerContext*` rule fields
- context lifecycle state transitions
- context-first matched-resource handoff types

`ExecutionSpec.foreground` is the current public/read preset input for worker
resource mode. Resolved `WorkerResourceMode.EXCLUSIVE` keeps the long-lived
exclusive worker-lock path, while `WorkerResourceMode.CAPACITY` uses declared
worker capacity without the long-lived lock. `WorkerDispatchResourcePolicy`
consumes the resolved resource-mode decision; listeners and binders consume
that policy instead of deriving their own foreground rules.

## Policy Precedence

The current policy order is:

1. manual terminal/cancel prevents reopening
2. pause/block stops new dispatch without erasing in-flight attempts
3. callback write-back resolves the active attempt before logical finality
4. retry decision happens after attempt close and before logical finality
5. resource release happens after attempt close or terminal notification
6. refill may re-enter assignment only after release and eligibility checks
7. terminal policy evaluates after stable reconciliation
8. open-intake tasks do not auto-close until sealed

Risky pairs that require explicit tests when changed:

- retry + release
- retry + refill
- ranking + acquisition
- min-worker gate + delayed availability
- shared capacity + release
- worker budget + refill
- routing rule + worker scheduling attributes

## Hard Boundaries

- `TaskWorkRuntime` owns ready/delayed/lease/retry/counter truth.
- `TaskResultRuntime` owns stable-final result truth.
- compatibility projection is residue, not scheduling truth.
- trace proves decisions but does not own them.
- server DTOs and SDK snapshots do not define kernel semantics.
- no per-message hot-path fallback matching.
- no hot-path full-worker scans when indexed or push-updated views exist.
- no direct runtime queries inside ranking loops when a read view can carry the
  fact.
- worker management/device/account lifecycle stays outside the scheduling
  kernel; scheduling consumes bounded read evidence only.

## Proof Surface

Primary proof remains engine-local and runtime-first:

- `EngineSchedulingCoreSuite`
- `SCHEDULING_CORRECTNESS_MATRIX.md`
- `EngineSchedulingCoreArchitectureGuardTest`
- runtime counters, leases, locks, reservations, assignment records, and
  canonical scheduling trace

Representative external proof:

- server trace-observed wiring for worker attribute routing, background worker
  sharing, and group-capability routing
- `xa-mass-trace` analyzers for capacity reservation, fairness, cleanup, and
  scheduling evidence

## Review Checklist

For any scheduling change, answer:

1. Which mechanism owner changed?
2. Which policy owner changed?
3. Could the same behavior be expressed by a policy change instead?
4. Does trace prove the decision without becoming the owner?
5. Did runtime/result/projection truth boundaries stay intact?
6. Would a future fairness or capacity policy require rewriting this mechanism?

If the last answer is yes, the seam is still weak.
