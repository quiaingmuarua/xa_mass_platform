# High-Volume Model Baseline

Last updated: 2026-04-27

This is a design-only reference for high-volume runtime compression work.
It is not current runtime truth.

Use with:

- [../AGENTS.md](../AGENTS.md)
- [./AGENT_BASELINE.md](./AGENT_BASELINE.md)
- [../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./TESTING_BASELINE.md](./TESTING_BASELINE.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

Current status:

- the first `TaskWorkRuntime` slice is already landed
- `TaskMsg` and `TaskMsgAttempt` still exist as compatibility projection and audit-heavy models
- current code still wins whenever this document and runtime disagree

## 1. Problem

The current runtime is still too heavy for large workloads because:

- `Task` still owns too much message-facing responsibility
- dispatch and result flow still write thick compatibility projections
- read models still assume one task can cheaply expose all messages
- full attempt history is too expensive as default hot-path truth

## 2. Frozen Decisions

Keep these design decisions stable:

1. `Task` becomes a smaller control-plane shell:
   - lifecycle
   - ownership
   - shared config
   - ingest state
   - aggregate counters
   - terminal reason

2. The default runnable unit becomes a queue-native envelope, not a full `TaskMsg` object graph.

3. Result convergence is counter-driven:
   - no steady-state full-message scans
   - no whole-task scans just to answer progress

4. Attempt truth splits into:
   - active lease truth on the hot path
   - optional audit history off the hot path

5. Source modes stay `batch`, `stream`, and `file`, but they converge after ingest into the same runnable-unit shape.

6. Observability stays in logs, traces, counters, and indexed reads, not rebuilt aggregate scans.

7. Idempotent result, retry, and timeout handling matter more than rich mid-flight state.

## 3. Minimal Target Shape

Target task shell:

- `taskId`
- `status`
- `project`
- `user`
- `sharedConfig`
- `sourceType`
- `ingestStatus`
- `intakeStatus`
- aggregate counters
- `terminalReason`
- timestamps

Target runnable envelope:

- `taskId`
- `messageId`
- `eventCode`
- `payload` or `payloadRef`
- `retryCount`
- `leaseToken`
- worker or routing hints
- visibility / scheduling timestamp

Target active lease truth:

- `taskId`
- `messageId`
- `leaseToken`
- `workerId`
- `workerContextId`
- `leaseExpireAt`
- `retryCount`

## 4. External Contracts To Preserve

Compression work must preserve these unless explicitly changed:

- `POST /status/api/tasks`
- append + seal semantics for open intake
- polling worker contract around `TaskDispatchItem`
- result submission contract around `TaskResultReport`
- task terminal immutability to late results

## 5. Current Acceptable Cut Lines

Acceptable migration slices:

1. shrink `Task` toward a control-plane shell
2. add or tighten explicit ingest for `batch`, `stream`, and `file`
3. move dispatch toward queue-native runtime state
4. move result convergence toward counters and output processing
5. downgrade full attempt history from default hot-path truth

Do not combine these into one big rewrite.

## 6. Already Landed

Current landed runtime slice:

- `TaskManager` still creates `Task` plus persisted `TaskMsg` compatibility projections
- initial or appended work is also written into `TaskWorkRuntime`
- assignment claims ready work from runtime instead of scanning all `INIT` messages
- runtime owns active lease and expiry indexes
- task progress and terminal policy already read runtime counters instead of aggregate `TaskMsg` scans

## 7. Verification Gates

Every high-volume slice must prove:

- `perf`: queue pressure and hot-path cost
- `concurrency`: lease expiry, duplicate result, retry, and inflight recovery
- `Boot-shell E2E`: create -> ingest -> dispatch -> result -> convergence

Minimum proof points:

- bounded ingest for large task sources
- no hot-path full-task message scan
- counter-driven convergence
- retry and timeout recovery without double-finalization
