# Runtime Boundary Baseline

Status: current engine runtime boundary baseline after the task-runtime
serving-lane cutover.

This file freezes the engine-facing task-runtime boundary so memory and Redis
task-runtime adapters can share one behavioral contract without turning engine
storage, server review materialization, transport, or starter assembly into a
second task item runtime truth.

Use with:

- [README.md](../../README.md)
- [STORAGE_BASELINE.md](./STORAGE_BASELINE.md)
- [../../../doc/TASK_LIFECYCLE_BASELINE.md](../../../doc/TASK_LIFECYCLE_BASELINE.md)
- [../../../doc/INFRA_TRUTH_LAYERS.md](../../../doc/INFRA_TRUTH_LAYERS.md)
- [../../../platform_infra/README.md](../../../platform_infra/README.md)

## Scope

This baseline covers the engine-facing task-runtime boundary:

- what `xa-mass-task-runtime` owns;
- what engine shell orchestration consumes;
- what storage and server review materialization do not own;
- what runtime switching does and does not promise;
- what recovery paths may infer from runtime truth.

It does not redesign transport, public API shape, worker selection, or future
trace/audit sinks.

## Runtime Truth

`xa-mass-task-runtime` is the runtime truth for:

- accepted backlog;
- scheduler discovery of task-level runnable candidates;
- exclusive claim ownership;
- active lease identity, expiry, and repair candidates;
- retry/finality outcome;
- final-result rows and bounded result reads;
- progress snapshots used by engine state/terminal policy;
- runtime discard/cleanup.

Engine hot paths must treat these runtime semantics as authoritative:

- append writes accepted backlog frames through `TaskRuntimeWorkPort`;
- scheduler discovery reads task-runtime score candidates through
  `TaskRuntimeScorePort`, not storage or review rows;
- claim moves backlog work to active runtime state through `TaskRuntimeWorkPort`;
- result ingest applies worker/timeout/dispatch-failure facts through
  `TaskRuntimeConvergencePort`;
- active lease timeout and repair candidates come from
  `TaskRuntimeConvergencePort` plus task-local reads;
- public point final-result reads come from `TaskRuntimeReadPort`, while any
  result window remains a separate read-model surface;
- terminal/delete cleanup uses `TaskRuntimeConvergencePort`;
- task progress and terminal convergence consume `TaskRuntimeProgressSnapshot`.

## Engine Role

Engine remains the shell and orchestration owner:

- task shell lifecycle, intake, control commands, and terminal aggregate policy;
- scheduling policy resolution and worker selection orchestration;
- dispatch binding and trace emission;
- consumption of task-runtime outcomes into task progress and terminal
  convergence.

`TaskRuntimeServingLane` is the engine-side serving boundary to the
task-runtime ports. `TaskManager` must not reintroduce old direct
`TaskWorkRuntime` / `TaskResultRuntime` stores or a parallel result helper path.

## Storage And Review Non-Truth

`TaskShellStore` owns control-plane shell truth only:

- `Task` shell state;
- rule definition truth.

Worker declaration truth is owned by `xa-mass-worker-runtime`.

Server review/export materialization is owned by `xa-mass-server` and may lag
runtime. It may support:

- bounded UI/debug/export reads;
- focused tests and operator review flows;
- server-local item and attempt summaries.

It must not redefine:

- ready backlog truth;
- lease truth;
- retry visibility truth;
- startup recovery truth;
- result commit truth;
- task progress correctness in place of task-runtime progress snapshots.

## Cutover Semantics

Runtime implementation choice belongs to startup assembly, not to engine
callers. Engine depends on `TaskRuntimeServingLane`; starter wiring chooses a
task-runtime memory or Redis backend through `sdk/xa-mass-task-runtime-starter-sdk`.

Current truth:

- code paths are implementation-agnostic once a serving lane is installed;
- runtime implementation is selected before `TaskManager` serving-lane use;
- runtime implementation must not be replaced after the serving lane is
  configured except through a full starter/runtime restart;
- memory -> Redis is an explicit cutover, not an online hot-switch contract.

Do not describe runtime selection as seamless state migration unless backlog,
delay, lease, and final-result transfer semantics are explicitly implemented
and verified.

## Recovery Rules

Startup or replay recovery must trust task-runtime truth first:

- dispatch recovery reads task-runtime scheduler discovery;
- storage task status alone must not imply dispatchable runtime work;
- runtime task ids missing from storage are filtered as residue, not promoted
  into synthetic storage truth.

Recovery must not rely on:

- scanning server review rows to reconstruct queue truth;
- inferring ready work from `TaskStatus.READY` alone;
- replaying review history into runtime on every startup.

## Forbidden Drift

Do not add these regressions:

- a second engine runtime facade beside `TaskRuntimeServingLane`;
- old `TaskWorkRuntime` / `TaskResultRuntime` stores, DTOs, or constructors;
- review-row-driven recovery or finality correctness;
- storage or review scans in hot paths to reconstruct ready queues;
- starter or transport code that mutates task-runtime truth outside the
  task-runtime ports;
- docs that imply runtime implementation switching is hot or automatic.

## Current Residue

Current bounded residue that remains acceptable:

- server-local review rows for operator/UI/export materialization;
- bounded debug reads exposed by shell-facing query services;
- manual chaos/diagnostic snapshot proof semantics that still need to be
  retargeted to the serving-lane vocabulary.

These are current implementation facts, not permission to make review rows,
transport queues, or storage rows task-runtime truth.
