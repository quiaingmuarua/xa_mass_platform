# Runtime Boundary Baseline

Status: current engine runtime boundary baseline.

This file freezes the engine-side runtime boundary so memory and Redis can
share one behavioral contract without turning storage, projection, or starter
assembly into a second runtime truth.

Use with:

- [README.md](./README.md)
- [STORAGE_BASELINE.md](./STORAGE_BASELINE.md)
- [../doc/INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [../platform_infra/README.md](../platform_infra/README.md)

## Scope

This baseline only covers the engine-facing runtime boundary:

- what `TaskWorkRuntime` owns
- what storage and compatibility projection do not own
- what runtime switching does and does not promise
- what recovery paths may infer from runtime truth

It does not redesign transport, public API shape, or future trace-sink
implementation.

## Runtime Truth

`TaskWorkRuntime` is the only runtime truth for:

- ready queue membership
- delayed visibility / retry re-entry timing
- exclusive claim ownership
- active lease identity and expiry
- runtime backpressure and queue-admission rejection
- per-task runtime counters used by engine progress convergence

Engine hot paths must treat these runtime semantics as authoritative:

- `enqueue(...)` is the runtime-owned admission point for logical work
- `readyTaskIds(limit)` is the startup and redispatch recovery surface
- `claimReady(...)` is the exclusive runtime claim path
- `applyResult(...)` is the only runtime result convergence path
- `pollExpiredLeases(...)` reports runtime expiry truth
- `discardTask(...)` removes runtime residue without redefining storage truth
- engine -> transport handoff now carries runtime-native dispatch bindings built
  from claimed runtime work plus active attempt ownership; transport must not
  need persisted `TaskMsg.input` to reconstruct the worker payload
- assignment-side compatibility `TaskMsg` sync must happen after runtime claim
  and must not require `TaskDetailStore.getTaskMessage(...)` as a dispatch gate;
  preserving or repairing the projection is residue, not the condition that
  makes a claimed work item dispatchable
- compatibility `TaskMsgAttempt` writes are best-effort residue only; runtime
  dispatch ownership, retry truth, and callback acceptance must remain correct
  when those writes lag or are absent
- runtime lease repair on callback, expiry, or dispatch-submit compensation may
  rebuild a bounded in-memory `TaskMsg` compatibility view, but it must not
  require persisting intermediate `ASSIGNED` or transient failure states before
  runtime result convergence can finish
- when `TaskMsg.latestAttemptId` is missing during runtime result convergence,
  engine may reuse the latest bounded compatibility attempt id only as a local
  fallback to close the right audit row; runtime acceptance still comes from the
  active lease, not from an active-attempt projection read
- callback duplicate, late, and no-active-lease trace emission must use bounded
  runtime-synchronized `TaskMsg` projection fields first; trace must not force
  a hot-path latest-attempt projection lookup
- task termination / cancellation must drain runtime active leases only; queued
  or merely projected `TaskMsg` rows must not be scanned just to stamp terminal
  status into compatibility residue

## Storage And Projection Non-Truth

`TaskStorage` owns control-plane shell truth only:

- `Task` shell state
- worker / worker-context registration truth
- rule definition truth

`TaskDetailStore` remains bounded compatibility residue only. It may support:

- projection repair
- latest-attempt compatibility fallback for audit-row closure during transition
- bounded shell/debug reads
- focused tests and audit helpers
- engine code should depend on this seam directly through the smallest needed
  runtime or service ports; do not reintroduce pass-through projection bridges
  that add no ownership boundary
- when assignment wiring already depends on `TaskAssignmentRuntimePort`, prefer
  the engine owner implementing that seam directly over a second adapter class
  that only forwards to `TaskManager`
- shell/query/result facades may keep their external seam types, but
  package-local pass-through `TaskManager*Port` wrappers should not exist when
  same-module services can call the engine owner directly
- the same rule applies to maintenance/recovery seams: keep the seam when
  watchdog or startup wiring needs it, but let the engine owner implement it
  directly instead of preserving a forwarding adapter class

It must not redefine:

- ready queue truth
- lease truth
- retry visibility truth
- startup recovery truth
- task progress correctness in place of runtime counters

## Cutover Semantics

Runtime implementation choice belongs to startup assembly, not to engine
callers. Engine depends on `TaskWorkRuntime`; starter wiring chooses memory or
Redis.

Current truth:

- code paths are implementation-agnostic once a `TaskWorkRuntime` is injected
- runtime implementation is selected before `TaskManager` assembly
- runtime implementation must not be replaced after `TaskManager` is configured
- memory -> Redis is an explicit cutover, not an online hot-switch contract

Do not describe runtime selection as seamless state migration unless backlog,
delay, and lease transfer semantics are explicitly implemented and verified.

## Recovery Rules

Startup or replay recovery must trust runtime truth first:

- dispatch recovery reads `TaskWorkRuntime.readyTaskIds(limit)`
- storage task status alone must not imply dispatchable runtime work
- runtime task ids missing from storage are filtered as residue, not promoted
  into synthetic storage truth

Recovery must not rely on:

- scanning full `TaskMsg` projections to reconstruct queue truth
- inferring ready work from `TaskStatus.READY` alone
- replaying projection history into runtime on every startup

## Forbidden Drift

Do not add these regressions:

- a second engine runtime facade beside `TaskWorkRuntime`
- projection-driven recovery or finality correctness
- storage scans in hot paths to reconstruct ready queues
- starter or transport code that mutates runtime truth outside the contract
- docs that imply runtime implementation switching is hot or automatic

## Current Residue

Current bounded residue that remains acceptable:

- `TaskMsg` and `TaskMsgAttempt` compatibility projection
- active-attempt projection repair when runtime lease exists but projection is
  missing
- bounded compatibility `TaskMsg` recovery from runtime lease truth when result
  ingest arrives after projection loss
- read-time compatibility overlay that projects terminal task closure onto a
  non-final `TaskMsg` view without rewriting every queued message row
- bounded debug reads exposed by shell-facing query services

These are current compatibility facts, not target runtime truth.
