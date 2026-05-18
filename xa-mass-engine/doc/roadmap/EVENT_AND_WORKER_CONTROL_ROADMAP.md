# Event And Worker Control Roadmap

Last updated: 2026-05-18

Status: active future roadmap after event-metadata baseline closure.

The event-metadata first wave is complete. Future work should now be organized
around concrete owners, not around a premature unified event runtime.

## Completed Baseline

Implemented first-wave baseline:

- event owner inventory
- `PriorityClass`, `ResponseMode`, and `TargetScope` metadata on descriptor
  models
- catalog/API metadata visibility
- guards that keep metadata out of runtime owner paths
- current presence-only system-event boundary

Current truth is recorded in
[`EVENT_OWNER_BOUNDARY.md`](../baseline/EVENT_OWNER_BOUNDARY.md).

## Direction

```text
concrete owner first
  -> stable model / lifecycle / proof
  -> only then consider shared carrier shape
```

Future capabilities must remain separate even if they later share metadata:

```text
TaskResultReport
  -> TaskResultService / TaskResultRuntime

worker command
  -> future command lifecycle owner

worker state report
  -> future bounded projection owner

worker capability self-report
  -> future capability-report owner

task item stage/progress
  -> future stage owner, not public final result
```

## Active Future Phases

### EWC-1: Queue Placement Policy

Goal: introduce a real queue-placement policy seam before any priority-driven
queue behavior.

Scope:

- policy input may include `PriorityClass`
- first default must preserve current behavior

Out of scope:

- no category-driven front/back queue rule
- no fairness, aging, deadline, quota, or budget expansion in the first seam

### EWC-2: Worker Command Lifecycle

Goal: add worker command request/status ownership without routing acknowledgments
through task-result convergence.

Owner shape:

```text
request
  -> WorkerCommandLifecycleOwner
  -> command delivery handoff
  -> ack/status ingest
  -> command read view
  -> trace evidence
```

Directional model:

- `commandId`
- `workerId`
- `commandType`
- requester/reason/deadline/idempotency key
- smallest status set that proves request, delivery, terminal result, and expiry

Package direction:

- shared value vocabulary in `xa-mass-base/com.xa.mass.command.core`
- lifecycle owner/store in `xa-mass-engine/com.xa.mass.engine.command`
- no owner/store implementation in the shared core package

Out of scope for the first behavior slice:

- no task-result writes
- no task lifecycle mutation
- no generic event endpoint
- no worker SDK shell until the owner exists

### EWC-3: Worker State Projection

Goal: accept worker/device state reports into a bounded owner projection.

Scope:

- validation and idempotency
- TTL/debounce
- bounded recent history
- approved derived scheduling evidence

Out of scope:

- no raw state facts in matching/ranking
- no unbounded durable audit for every high-frequency report by default

### EWC-4: Worker Capability Self-Report

Goal: let worker-reported capability facts refresh worker registration truth
without bypassing the WorkerGroup/candidate-index line.

Scope:

- capability report owner
- validation
- refresh path into `WorkerManager` / `WorkerRegistrySnapshot`
- trace proof

Out of scope:

- no direct capability mutation from `WorkerSystemEventChannel`
- no matching path reading raw report payloads

### EWC-5: Task Item Stage Semantics

Goal: support multi-stage task work without polluting public final results.

Rules:

- stage evidence may drive progress or next-stage work
- only approved final-result paths may commit public result rows

### EWC-6: Shared Runtime Envelope Review

Goal: evaluate a shared runtime carrier only after concrete owners exist.

Do this only if command/state/stage paths expose real duplicate carrier cost.
Do not use a shared envelope to erase lifecycle owner boundaries.

## Proof Rule

Each behavior phase must add owner-local proof plus canonical trace evidence:

- command path: request -> delivery -> ack/status, not task-result rows
- state path: report -> bounded projection -> derived evidence, not raw hot-path
  matching
- capability path: report -> registry refresh -> candidate-source proof

## Non-Goals

- no event microservice
- no `UnifiedEventService`
- no shared envelope before owner need is proven
- no task result / worker control / worker state multiplexing through one owner
- no queue behavior change directly from descriptor metadata

## Related Current Docs

- [EVENT_OWNER_BOUNDARY.md](../baseline/EVENT_OWNER_BOUNDARY.md)
- [SCHEDULING_KERNEL_BASELINE.md](../baseline/SCHEDULING_KERNEL_BASELINE.md)
- [../../../transport/TRANSPORT_BOUNDARY_BASELINE.md](../../../transport/TRANSPORT_BOUNDARY_BASELINE.md)
- [../../../doc/RESULT_BOUNDARY_BASELINE.md](../../../doc/RESULT_BOUNDARY_BASELINE.md)

