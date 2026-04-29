# Task Runtime Profile Design

Last updated: 2026-04-29

Status: design/refactor reference, not current runtime truth.

Current truth for implemented behavior lives in code, the engine README, and
the global lifecycle/trace baselines. This file only records the remaining
design direction so workload-profile work does not sprawl into unrelated API or
storage redesign.

## Problem

The engine must handle two materially different workload shapes with one kernel:

- `INTERACTIVE`: low queueing latency and quicker retry/expiry recovery
- `BULK`: high throughput and stronger backlog shaping

The shared kernel remains:

- `Task` as orchestration shell
- `TaskWorkRuntime` as ready/lease/retry/expiry owner
- `TaskMsgAttempt` as attempt audit truth

## Current Implemented Slice

Already on the mainline:

- `Task.workloadClass` is the explicit workload input
- current supported values are `INTERACTIVE` and `BULK`
- `TaskRuntimeProfileResolver` resolves an engine-internal
  `TaskRuntimeProfile`
- assignment entry is lane-aware through `INTERACTIVE` and `BULK` routing
- claim, retry-delay, backpressure, and trace now consume resolved profile
  semantics instead of reinterpreting free-form `sharedConfig`

Current mapping:

- `INTERACTIVE`
  - high priority
  - small batch policy
  - short lease profile
  - interactive backpressure class
- `BULK`
  - normal priority
  - large batch policy
  - normal lease profile
  - bulk backpressure class

## Remaining Work

Still intentionally out of the first slice:

- fair scheduling across lanes
- precise lane quotas and admission budgets
- multi-executor throughput tuning inside one runtime
- richer lease-duration tuning per workload
- stronger perf gates in `xa-mass-testing`

Those should build on the resolved profile that already exists. They should not
reintroduce ad hoc scheduler semantics on `Task.sharedConfig`.

## Hard Non-Goals

This design does not authorize:

- per-`TaskMsg` rule matching
- engine-owned full-message analytics queries
- separate interactive and bulk kernels
- broad public API redesign in the same refactor slice

If messages need materially different routing semantics, split them into
separate tasks or explicit task-owned slices before dispatch.

## Acceptance Direction

Keep workload-profile validation aligned with the repo's core acceptance lanes:

- `perf`
  - interactive queueing latency under bulk background pressure
  - bulk dispatch continuity under sustained backlog
- `concurrency`
  - retry, expiry, callback replay, and release correctness for both workload
    classes
- `Boot-shell E2E`
  - one interactive task flow
  - one bulk task flow

## Read Next

- [`README.md`](./README.md)
- [`../doc/HIGH_VOLUME_MODEL_BASELINE.md`](../doc/HIGH_VOLUME_MODEL_BASELINE.md)
- [`../doc/TESTING_BASELINE.md`](../doc/TESTING_BASELINE.md)
