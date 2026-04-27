# Task Runtime Profile Design

Last updated: 2026-04-27

Design-only reference for the next engine convergence slice. This file does not
describe current verified runtime truth yet.

Use with:

- [../../AGENTS.md](../../AGENTS.md)
- [../HIGH_VOLUME_MODEL_BASELINE.md](../HIGH_VOLUME_MODEL_BASELINE.md)
- [../TESTING_BASELINE.md](../TESTING_BASELINE.md)
- [../../xa-mass-engine/TASK_EXECUTION_FLOW.md](../../xa-mass-engine/TASK_EXECUTION_FLOW.md)

## 1. Problem

The engine needs to support two materially different workload shapes without
forking the kernel:

- low-latency interactive tasks such as chatbot or agent conversations
- high-throughput bulk tasks such as crawler or batch-processing platforms

Both should continue to use the same kernel truth:

- `Task` as the orchestration shell
- `TaskWorkRuntime` for ready work, lease, retry, and expiry truth
- `TaskMsgAttempt` as attempt audit truth

What must change is runtime policy selection, not the message model boundary.

## 2. Non-Goals

This design does not authorize:

- per-`TaskMsg` rule matching
- a new public API contract yet
- engine-owned full-message detail queries for analysis use cases
- separate kernels for interactive and bulk tasks

If different messages need materially different routing semantics, split them
into separate tasks or explicit task-owned slices before dispatch.

## 3. Design Rule

Task attributes are inputs. Resolved runtime profile is the engine truth.

Do not let hot-path scheduling repeatedly interpret arbitrary `sharedConfig`
keys. The engine should resolve a small, explicit `TaskRuntimeProfile` once and
then let assignment, lease, retry, queue lane, and backpressure logic consume
that profile.

## 4. Minimal Input Set

Current task inputs already available:

- `Task.sourceType`
- `Task.batchSize`
- `Task.minRequiredWorkerCount`
- `Task.intakeStatus`
- `Task.maxRuntimeSeconds`
- `Task.sharedConfig`

Recommended near-term workload input:

- `Task.sharedConfig.executionClass`

Allowed values:

- `INTERACTIVE`
- `BULK`

Near-term rule:

- use a fixed, engine-owned key such as `sharedConfig.executionClass`
- do not let callers invent additional scheduler-driving keys without updating
  the resolver and this file

Longer-term direction:

- if workload class becomes a stable platform contract, promote it from
  `sharedConfig` into an explicit task-level field

## 5. Resolved Profile

Minimal internal profile:

- `executionClass`: `INTERACTIVE | BULK`
- `dispatchLane`: `INTERACTIVE | BULK`
- `dispatchPriority`: `HIGH | NORMAL`
- `batchPolicy`: `SMALL | LARGE`
- `leaseProfile`: `SHORT | NORMAL | LONG`
- `backpressureClass`: `INTERACTIVE | BULK`

Notes:

- this is an engine-internal normalized profile, not yet a public request model
- different fields may temporarily map to the same value during the first slice
- keep the profile intentionally small; add fields only when a real policy
  consumer needs them

## 6. Resolver Rules

Recommended precedence:

1. explicit `sharedConfig.executionClass`
2. engine-owned fallback heuristics from stable task fields
3. final default to `BULK`

Recommended fallback heuristics:

- `STREAM` tasks may default toward `INTERACTIVE` only when combined with small
  `batchSize` and low-latency expectations
- `FILE` tasks default to `BULK`
- large `batchSize` biases toward `BULK`

Guardrail:

- heuristics may choose a default, but they must not replace explicit
  `executionClass`
- assignment and retry code should consume the resolved profile only, not
  re-run heuristics

## 7. Policy Mapping

Recommended first mapping:

### INTERACTIVE

- `dispatchLane = INTERACTIVE`
- `dispatchPriority = HIGH`
- `batchPolicy = SMALL`
- `leaseProfile = SHORT`
- `backpressureClass = INTERACTIVE`

Intended behavior:

- small dispatch rounds
- lower queueing latency
- faster retry and expiry recovery
- isolation from bulk backlog

### BULK

- `dispatchLane = BULK`
- `dispatchPriority = NORMAL`
- `batchPolicy = LARGE`
- `leaseProfile = NORMAL` or `LONG`
- `backpressureClass = BULK`

Intended behavior:

- larger dispatch rounds
- higher throughput
- stronger backlog shaping
- lower sensitivity to single-item latency

## 8. Engine Impact

The first runtime-profile slice should affect:

- assignment lane selection
- claim batch sizing
- lease duration defaults or selection
- retry scheduling and backpressure class
- structured trace fields for dispatch and result paths

It should not require:

- per-message matching
- richer task-detail query APIs
- separate attempt models per workload class

## 9. Trace Requirements

When a runtime profile is introduced, structured trace should emit:

- resolved `executionClass`
- resolved `dispatchLane`
- resolved `leaseProfile`
- resolved `batchPolicy`

Reason:

- downstream analysis should explain why an interactive task or bulk task was
  dispatched differently without forcing the engine to keep heavy projections

## 10. Acceptance Direction

The profile slice should add acceptance coverage for both workload classes.

Minimum lanes:

- `perf`
  - interactive queueing latency under bulk background pressure
  - bulk dispatch throughput under sustained ingest
- `concurrency`
  - retry, expiry, and duplicate result behavior for both classes
  - no cross-lane starvation or incorrect lease sharing
- `Boot-shell E2E`
  - one interactive flow
  - one bulk flow

## 11. Migration Advice

Keep migration small:

1. add resolver and internal profile
2. emit profile in trace
3. split assignment or queue policy by resolved profile
4. add dual-workload acceptance coverage

Do not combine this with broader API redesign, SDK redesign, or full storage
engine replacement in the same change.
