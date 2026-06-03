# Platform Scheduling Plane Runtime Selection Boundary

Status: current runtime-selection owner inventory for
`doc/archive/xa-mass-engine/2026-06-03_PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_PROOF_ROADMAP.md`.

## Purpose

This document is the RS-0 boundary inventory for `RuntimeWorkerSelection`.

It records the current worker-choice owner shape and identifies proof targets
by inventorying:

- static resolved worker-policy inputs,
- runtime selection mechanisms,
- production entries that can cause work to bind to a worker,
- helper / convenience / compatibility-like residue candidates,
- trace and diagnostic evidence.

`RuntimeWorkerSelection` is a Scheduling Plane owner boundary. It is not yet a
single class, not a policy family, and not a new facade.

## Owner Classification

| Current Owner / Symbol | Owns | Consumes | Must Not Own | Classification |
| --- | --- | --- | --- | --- |
| `DefaultSchedulingPlaneResolver` | computed default scheduling resolution from current task shell, shared config, route, and target inputs | task shell and shared config | live worker reachability, slots, reserve, lock, admission, trace evidence | resolved input owner |
| `ResolvedWorkerSchedulingPolicy` | static worker-side scheduling input view: worker group, adapter node, route buckets, target worker, target attributes | resolver output | runtime truth, diagnostic evidence, mutable admission state | resolved input owner |
| `WorkerTaskSelectorFactory#fromPolicy` | candidate-source selector construction from resolved worker policy | `ResolvedWorkerSchedulingPolicy` | policy resolution, runtime admission, ranking, reserve/lock mutation | candidate-source adapter |
| `RuleBasedTaskWorkerMatchingStrategy` | matching assembly, prefilter, rule evaluation handoff, ranking handoff, reserve/lock/admission handoff | dispatch intent, resolved worker policy, candidate source, rule context, ranker, admission/resource mechanisms | persisted policy truth, public policy config, trace truth | runtime-selection mechanism |
| `TaskAssignWorker` | assignment signal queue, delayed retry, lane routing, and retry wakeup | task events and `TaskWorkerAssignListener` | concrete worker choice, policy definition, trace truth | binding entry coordinator |
| `TaskWorkerAssignListener` | allocation, matching handoff, dispatch binding call, surplus release, warm-hint write timing | matching strategy, dispatch binder, admission runtime, warm-hint runtime, allocation policy | direct candidate source, rule context definition, persisted policy truth | binding coordinator |
| `RuntimeReadyDispatchPump` | runtime-ready BATCH task redispatch entry | runtime dispatchable task source and `TaskWorkerAssignListener#onTaskAssign` | direct worker binding, direct candidate selection | approved binding entry fan-in |
| `TaskDispatchWakeupBridge` | worker-availability wakeup fanout | `TaskAssignWorker#wakeWaitingRetries` and runtime-ready pump wakeup | direct worker binding, direct candidate selection | approved wakeup fanout |
| `EngineRuntimeKernel` | default engine runtime assembly and lifecycle wiring | runtime ports, listener graph, matching strategy, assign worker, pumps/watchdogs | direct worker binding outside listener/binder path | assembly owner |
| `WorkerMatchContext#getRuleContext()` | approved rule-readable eligibility map | static task/candidate facts approved for rules | diagnostic-only context, live admission/reserve/lock evidence unless explicitly approved | rule-readable input |
| full `WorkerMatchContext` snapshot | diagnostic match evidence | runtime and candidate facts | rule policy contract, selection truth | diagnostic evidence |
| `WorkerCandidateRanker` / `WorkerCandidateRankPolicy` | read-only ordering and current default ranking weights | candidate evidence and static rank weights | reserve, lock, admission, release, live evidence ownership | ranking mechanism / static ranking policy |
| `WorkerAdmissionRuntime` / `WorkerWarmHintRuntime` | live admission, reservation, lock, load, active-worker count, warm hints | worker runtime state | resolved policy definition, rule DSL ownership, trace truth | runtime truth |
| `WorkerDispatchResourcePolicy` / `WorkerDispatchResourceReleaser` | dispatch resource usage and release semantics | selected candidate and task dispatch context | policy variant selection, candidate source definition, trace truth | runtime resource mechanism |
| `SimpleTaskDispatchBinder` | runtime work claim and dispatch binding materialization for already matched workers | matched workers, runtime ready work, admission runtime | matching, ranking, policy resolution | binding owner after runtime selection |
| `AssignmentRecordService` / `TraceEventLogger` | assignment and trace evidence | selection results, rejection facts, release facts | worker-selection truth, replayable policy state | evidence only |

## Binding Entry Inventory

Every current production entry that can make work bind to a worker must flow
through the runtime-selection order before `TaskDispatchBinder#bindDispatches`.

| Entry | Source | Current Flow | Binding Path | Classification |
| --- | --- | --- | --- | --- |
| SESSION task ready | `EngineRuntimeKernel` task-ready listener | `assignWorker.submit(task)` -> lane queue -> `TaskWorkerAssignListener#onTaskAssign` | `matchWorkers` -> `bindDispatches` | approved binding entry |
| SESSION dispatch signal | `EngineRuntimeKernel` task-dispatch listener | `assignWorker.submit(task)` -> lane queue -> `TaskWorkerAssignListener#onTaskAssign` | `matchWorkers` -> `bindDispatches` | approved binding entry |
| assignment retry / delayed requeue | `TaskAssignWorker` | retry scheduler -> lane queue -> `TaskWorkerAssignListener#onTaskAssign` | `matchWorkers` -> `bindDispatches` | approved binding entry |
| startup/runtime recovery redispatch | `EngineRuntimeKernel#recoverRuntimeReadyTasks` | `assignWorker.submit(task)` -> lane queue -> `TaskWorkerAssignListener#onTaskAssign` | `matchWorkers` -> `bindDispatches` | approved binding entry |
| BATCH runtime-ready pump | `RuntimeReadyDispatchPump` | runtime scan -> `workerAssignListener::onTaskAssign` | `matchWorkers` -> `bindDispatches` | approved binding entry |
| worker availability wakeup | `TaskDispatchWakeupBridge` | wakes waiting assignment retries and runtime-ready pump admissions | indirect entry only; no direct binding | approved wakeup fanout |
| lease-expiry readying | `LeaseExpireWatchdog` / `TaskLeaseMaintenancePort#expireLeasedWork` | expires leased work and releases worker; later dispatchable work is picked by task signal or runtime-ready pump | no direct binding | release/readiness entry |
| attempt-close resource release | `TaskResourceReleaseListener` | releases resource, then asks `TaskDispatchWakeupPort` for possible refill | no direct binding in release listener | release/readiness entry |
| target-worker task | `ResolvedWorkerSchedulingPolicy#targetWorkerId` | resolver narrows selector -> candidate source -> prefilter/rules/rank/reserve/lock/admission | `matchWorkers` -> `bindDispatches` | static narrowing input, not direct binding |

## Runtime Selection Order

Current production worker binding is approved only through this order:

```text
resolve worker policy
  -> build candidate-source selector
  -> acquire candidate universe
  -> prefilter dispatch gate / reachability / lock
  -> build match context
  -> evaluate approved rule context
  -> rank rule-passed candidates
  -> reserve / lock / admission
  -> bind assignment
  -> release or record rejection on failure
  -> emit trace / diagnostics
```

`TaskDispatchWakeupBridge`, `RuntimeReadyDispatchPump`, and
`EngineRuntimeKernel` are entry and assembly owners. They do not own concrete
worker choice.

## Strategy And Mechanism Split

Static strategy inputs:

- worker group selector,
- adapter node constraint,
- route bucket keys,
- target worker constraint,
- target attributes,
- future ranking policy such as comparator/weight selection only after RS-5.

Runtime mechanism evidence:

- worker reachability,
- worker load and slots,
- draining / dispatch-disabled state,
- warm hints,
- reserve / lock / admission result,
- ranking execution,
- release result.

Static ranking policy and runtime ranking evidence are separate. Current
`WorkerCandidateRankPolicy` is a default static weight object. Live load,
availability, warm evidence, reserve, lock, and admission remain runtime
mechanism evidence.

## Residue Candidates

| Candidate | Current Evidence | Classification | Required Action |
| --- | --- | --- | --- |
| `WorkerTaskSelectorFactory#fromTask` | removed from production; source residue scan should remain empty | retired residue | keep removed; use `fromPolicy` from `ResolvedWorkerSchedulingPolicy` |
| `TaskDispatchIntent#fromTask` | helper used by `DefaultSchedulingPlaneResolver` to build the single dispatch intent | approved resolver-internal helper | keep inside resolver boundary; do not call from matching/listener production paths |
| compatibility / legacy projection comments | unrelated projection compatibility text exists in engine/runtime docs and code comments | not worker-selection truth | ignore unless it computes worker-selection facts |
| `TaskDispatchWakeupBridge` | engine-owned wakeup fanout, no direct binding or selection | approved current owner | keep classified; integration proof owns runtime behavior |

## Trace And Diagnostics

Trace and assignment records explain runtime selection. They do not drive it.

Current evidence owners:

- `AssignmentRecordService` records worker-level and message-level assignment
  attempts.
- `TraceEventLogger` emits match rejection/acceptance, lock acquisition/release,
  dispatch binding summary, assignment summary, and resource release evidence.

Trace gaps must be recorded as bounded proof gaps with owners. Trace fields
must not become policy truth, runtime truth, or replayable worker-selection
state.

## Verification

Primary proof is integration-first and must exercise runtime truth, lifecycle,
contention, targeting, or failure paths. Source scans are residue sanity checks
only. Field-copy unit tests are support regressions, not scheduling proof.

Primary commands:

```powershell
mvn -pl xa-mass-engine -Dtest=EngineSchedulingCoreSuite test
mvn -pl xa-mass-engine "-Dtest=TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskDelayedAvailabilitySchedulingTest,TaskRedispatchCompetitionTest" test
```

Support regression commands:

```powershell
mvn -pl xa-mass-engine "-Dtest=SimpleTaskDispatchBinderTest,DefaultSchedulingPlaneResolverTest" test
```

`SimpleTaskDispatchBinderTest` covers dispatch submit failure compensation as a
low-level failure-path regression. `DefaultSchedulingPlaneResolverTest` may
detect resolver construction drift, but runtime-selection proof must come from
the primary integrated scheduling commands above.

Residue sanity commands:

```powershell
rg -n "ResolvedWorkerSchedulingPolicy|WorkerTaskSelectorFactory|RuleBasedTaskWorkerMatchingStrategy|WorkerMatchContext|WorkerCandidateRanker|WorkerAdmissionRuntime|WorkerWarmHintRuntime|WorkerDispatchResourcePolicy|AssignmentRecordService" xa-mass-engine xa-mass-worker-runtime --glob '!**/target/**'
rg -n "TaskAssignWorker|TaskWorkerAssignListener|TaskDispatchWakeupBridge|RuntimeReadyDispatchPump|LeaseExpireWatchdog|TaskDispatchBinder|bindDispatches|submit\(|onTaskAssign|expireLeasedWork" xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "WorkerTaskSelectorFactory\.fromTask\(" xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "TaskDispatchIntent\.fromTask\(" xa-mass-engine/src/main/java --glob '!**/target/**'
```

Expected residue interpretation:

- `WorkerTaskSelectorFactory.fromTask` should have zero production call sites
  after RS-1 convergence.
- `TaskDispatchIntent.fromTask` is expected only inside
  `DefaultSchedulingPlaneResolver`.
- `TaskDispatchWakeupBridge` is expected as a wakeup fanout only.
