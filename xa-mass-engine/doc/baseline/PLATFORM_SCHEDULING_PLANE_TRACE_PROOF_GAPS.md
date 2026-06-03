# Platform Scheduling Plane Trace Proof Gaps

Status: current evidence checkpoint for Scheduling Plane stabilization.

This note records the existing trace and diagnostic evidence for Scheduling
Plane proof work. It also bounds the gaps that must not become open-ended trace
enrichment.

## Existing Evidence

Current trace and diagnostics already explain the main scheduling and matching
outcomes without introducing writable policy truth:

- `TraceEventLogger#assignmentSummary(...)` emits task-side scheduling evidence:
  `workloadClass`, `foreground`, `dispatchLane`, `dispatchPriority`,
  `batchPolicy`, `leaseProfile`, `backpressureClass`, worker budget, requested
  match count, matched count, dispatched count, and budget-limited state.
- `TraceEventLogger#workerMatchAccepted(...)` and
  `TraceEventLogger#workerMatchRejected(...)` emit worker-side selection
  evidence: candidate rank, score, WorkerGroup id, adapter node id, event
  binding key, worker candidate source, worker scheduling resource id, routing
  tags, static scheduling attributes, route match result, and load snapshot.
- `SimpleTaskDispatchBinder` carries dispatch binding evidence on attempt
  transitions: WorkerGroup id, adapter node id, event binding key, and worker
  candidate source.
- `AssignmentRecordService` records per-candidate rule evaluation details,
  evaluation counts, full diagnostic context snapshots, task snapshots, worker
  snapshots, and worker scheduling snapshots.
- `doc/TRACE_CONTRACT.md` names the stable assignment-oriented fields consumed
  by trace analyzers. These fields are read-side evidence only; they do not
  own runtime correctness, lease acceptance, retry budgeting, dispatch
  ownership, or terminal convergence.

## Bounded Gaps

The following gaps are bounded residue, not approval to add broad observability:

| Gap | Current evidence | Owner | Rule |
| --- | --- | --- | --- |
| Resolved value-object names are not trace fields. There is no trace column named `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, or `ResolvedWorkerSchedulingPolicy`. | Assignment summary and worker match fields expose the resolved facts that currently matter: workload class/profile fields, worker group, adapter node, route match evidence, target narrowing result, and candidate source. | `xa-mass-engine`: `DefaultSchedulingPlaneResolver`, `TraceEventLogger`, assignment diagnostics. | Add explicit resolved-view trace only when an analyzer cannot explain a real scheduling decision from the existing fields. |
| Route bucket keys are not emitted as a first-class field. | Trace emits route attributes indirectly through worker scheduling evidence and `workerSchedulingMatchesRoutingCode`; dispatch binding records selected WorkerGroup/node evidence. | `xa-mass-engine`: `WorkerRoutingPolicy`, `TraceEventLogger`. | Add a bounded `routeBucketKeys` diagnostic only if route-bucket proof cannot be expressed through current worker scheduling evidence. |
| Rule pass/fail detail is diagnostic record evidence, not canonical trace JSONL evidence. | `AssignmentRecordService` stores `RuleEvaluationDetail` and the context snapshot for each worker assignment attempt. Worker match trace rows carry accepted/rejected outcome and reason. | `xa-mass-engine`: matching strategy and assignment diagnostics. | Do not promote full rule details into trace unless a trace analyzer needs rule-level proof that cannot be checked from assignment records or existing outcome rows. |
| Public policy selector evidence is absent because no public policy selector exists. | Current trace covers computed defaults and execution evidence. | Future successor decision. | Do not add placeholder policy id, catalog id, or binding id fields before a successor decision proves caller, cost, storage owner, and runtime consumer. |

## Proof Boundary

Trace may prove that a Scheduling Plane decision happened and which inputs were
observed. It must not become the source of scheduling truth. Current source
truth remains task shell/execution spec/shared config, worker runtime evidence,
and engine-resolved views consumed by assignment, matching, and dispatch
mechanisms.
