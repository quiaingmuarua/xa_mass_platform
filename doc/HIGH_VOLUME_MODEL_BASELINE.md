# High-Volume Model Baseline

Last updated: 2026-04-27

Design-only reference for runtime compression work. It is not current runtime
truth. If this file conflicts with code or verified behavior, code wins.

Use with:

- [../AGENTS.md](../AGENTS.md)
- [./AGENT_BASELINE.md](./AGENT_BASELINE.md)
- [../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md)
- [./TESTING_BASELINE.md](./TESTING_BASELINE.md)

## 1. Current Status

Already true in current code:

- the first `TaskWorkRuntime` slice is landed
- `TaskManager` still writes `Task` plus persisted `TaskMsg` compatibility projections
- initial or appended work is also written into `TaskWorkRuntime`
- assignment claims ready work from runtime instead of scanning all `INIT` messages
- runtime owns active lease and expiry indexes
- task progress and terminal policy already read runtime counters instead of aggregate `TaskMsg` scans

Still too heavy on the hot path:

- `Task` still owns too much message-facing responsibility
- dispatch and result flow still write thick compatibility projections
- read models still assume one task can cheaply expose all messages
- full attempt history is still too expensive as default hot-path truth
- some persistence surfaces still expose full-message reads that are acceptable for audit and pagination only, not runtime readiness truth
- task orchestration is not fully separated from downstream detail-analysis needs yet

## 2. Frozen Design

Keep these decisions stable:

- `Task` shrinks toward a control-plane shell: lifecycle, ownership, shared config, ingest state, aggregate counters, terminal reason
- task strategy, worker matching, and start-gate decisions stay at the task or explicit task-slice level; do not reintroduce per-`TaskMsg` rule matching as a scaling fallback
- the default runnable unit is a queue-native envelope, not a full `TaskMsg` object graph
- convergence is counter-driven, not full-message-scan-driven
- attempt truth splits into active hot-path lease truth and optional off-path audit history
- `batch`, `stream`, and `file` source modes may differ at ingest, but converge after ingest into one runnable-unit shape
- observability stays in logs, traces, counters, indexed reads, and explicit export sinks
- idempotent result, retry, and timeout handling matter more than rich mid-flight projections
- engine-owned task-detail reads stay bounded; large-scale detail analysis belongs in structured trace, audit sinks, or downstream storage engines

Minimal target shape:

- task shell: `taskId`, `status`, `project`, `user`, `sharedConfig`, `sourceType`, `ingestStatus`, `intakeStatus`, aggregate counters, `terminalReason`, timestamps
- runnable envelope: `taskId`, `messageId`, `eventCode`, `payload` or `payloadRef`, `retryCount`, `leaseToken`, worker or routing hints, visibility/scheduling timestamp
- active lease truth: `taskId`, `messageId`, `leaseToken`, `workerId`, `workerContextId`, `leaseExpireAt`, `retryCount`
- trace or audit export event: task lifecycle, dispatch binding, attempt state transition, result acceptance or rejection, retry reset, expiry, terminal closure

## 3. Contracts To Preserve

Compression work must preserve these unless explicitly approved otherwise:

- `POST /status/api/tasks`
- append + seal semantics for open intake
- polling worker contract around `TaskDispatchItem`
- result submission contract around `TaskResultReport`
- task terminal immutability to late results

## 4. Acceptable Slices

Allowed migration slices:

1. shrink `Task` toward a control-plane shell
2. add or tighten explicit ingest for `batch`, `stream`, and `file`
3. move dispatch toward queue-native runtime state
4. move result convergence toward counters and output processing
5. downgrade full attempt history from default hot-path truth

Do not combine these into one broad rewrite.

## 5. Required Proof

Every high-volume slice must prove:

- `perf`: queue pressure and hot-path cost
- `concurrency`: lease expiry, duplicate result, retry, and inflight recovery
- `Boot-shell E2E`: create -> ingest -> dispatch -> result -> convergence

Minimum proof points:

- bounded ingest for large task sources
- no hot-path full-task message scan
- counter-driven convergence
- retry and timeout recovery without double-finalization
- structured trace or sink export remains sufficient for downstream task-detail reconstruction without adding new hot-path scans
