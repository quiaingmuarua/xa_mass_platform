# Runtime Boundary Baseline

Status: current engine runtime boundary baseline.

This file freezes the engine-side runtime boundary so memory and Redis can
share one behavioral contract without turning storage, review materialization,
or starter assembly into a second runtime truth.

Use with:

- [README.md](../../README.md)
- [STORAGE_BASELINE.md](./STORAGE_BASELINE.md)
- [../../../doc/INFRA_TRUTH_LAYERS.md](../../../doc/INFRA_TRUTH_LAYERS.md)
- [../../../platform_infra/README.md](../../../platform_infra/README.md)

## Scope

This baseline only covers the engine-facing runtime boundary:

- what `TaskWorkRuntime` owns
- what storage and server review materialization do not own
- what runtime switching does and does not promise
- what recovery paths may infer from runtime truth

It does not redesign transport, public API shape, or future trace/audit sinks.

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
- batch/bulk redispatch should prefer periodic recovery from
  `readyTaskIds(limit)` over engine-local task-delay ownership
- `claimReady(...)` is the exclusive runtime claim path
- `applyResult(...)` and `applyResultWithContext(...)` are the runtime result
  convergence paths
- `pollExpiredLeases(...)` reports runtime expiry truth
- `getRecentFinalReceipt(...)` is the bounded duplicate/late callback recovery
  read after queue and lease ownership have already been released
- `discardTask(...)` removes runtime residue without redefining storage truth
- engine -> transport handoff carries runtime-native dispatch bindings built
  from claimed runtime work plus active attempt ownership
- transport must not need persisted review input fields to reconstruct the
  worker payload
- callback duplicate, late, and no-active-lease trace emission must use bounded
  runtime state first
- when queue work and active lease have already been removed, accepted
  duplicate/late callback handling must rely on bounded runtime final receipts
- task termination / cancellation must drain runtime active leases only; review
  rows must not be scanned just to stamp terminal status

## Storage And Review Non-Truth

`TaskShellStore` owns control-plane shell truth only:

- `Task` shell state
- rule definition truth

Worker declaration truth is owned by `xa-mass-worker-runtime`.

Server review/export materialization is owned by `xa-mass-server` and may lag
runtime. It may support:

- bounded UI/debug/export reads
- focused tests and operator review flows
- server-local item and attempt summaries

It must not redefine:

- ready queue truth
- lease truth
- retry visibility truth
- startup recovery truth
- result commit truth
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
- starter-owned bulk dispatch pump also reads `TaskWorkRuntime.readyTaskIds(limit)`
  and routes only batch-contract tasks into direct assignment/matching
- storage task status alone must not imply dispatchable runtime work
- runtime task ids missing from storage are filtered as residue, not promoted
  into synthetic storage truth

Recovery must not rely on:

- scanning server review rows to reconstruct queue truth
- inferring ready work from `TaskStatus.READY` alone
- replaying review history into runtime on every startup

## Forbidden Drift

Do not add these regressions:

- a second engine runtime facade beside `TaskWorkRuntime`
- review-row-driven recovery or finality correctness
- storage or review scans in hot paths to reconstruct ready queues
- starter or transport code that mutates runtime truth outside the contract
- docs that imply runtime implementation switching is hot or automatic

## Current Residue

Current bounded residue that remains acceptable:

- recent final receipts in runtime for duplicate/late callback classification
- staged callback/result repair anchors in `TaskResultRuntime`
- server-local review rows for operator/UI/export materialization
- bounded debug reads exposed by shell-facing query services

These are current implementation facts, not permission to make review rows
runtime truth.
