# High-Volume Model Baseline

Last updated: 2026-04-27

This document freezes the target production model for moving XA Mass Platform
from an object-heavy validation runtime toward a high-volume queue-driven
runtime.

Use it when the task is about any of these:

- compressing the current `Task / TaskMsg / TaskMsgAttempt` hot path
- designing Redis-first queueing for large task inputs
- importing large files into runnable work without materializing a giant in-memory task graph
- deciding which orchestration semantics stay on the default path vs move to optional audit

Use with:

- [../AGENTS.md](../AGENTS.md)
- [./AGENT_BASELINE.md](./AGENT_BASELINE.md)
- [./HIGH_VOLUME_MIGRATION_MAP.md](./HIGH_VOLUME_MIGRATION_MAP.md)
- [./HIGH_VOLUME_SCHEMA_PLAN.md](./HIGH_VOLUME_SCHEMA_PLAN.md)
- [./TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](./TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md)
- [./STATE_MACHINE_BASELINE.md](./STATE_MACHINE_BASELINE.md)
- [./TESTING_BASELINE.md](./TESTING_BASELINE.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

Important status note:

- this file describes the approved target direction for compression work
- the first engine `TaskWorkRuntime` slice now implements ready/claim/active-lease/result outcome state in memory
- `TaskMsg` and `TaskMsgAttempt` are still compatibility projection/audit models in this slice
- when this file conflicts with current runtime reality, current code still wins until the migration lands

## 1. Why This Exists

Current code proves the platform kernel, but the default hot path is still too
object-heavy for high-volume workloads:

- `Task` still behaves too much like a message-owning aggregate instead of a control-plane shell
- assignment still centers on task-level object flow instead of queue-level work units
- `TaskMsg` still mixes logical-message truth, dispatch projections, and read-model convenience
- `TaskMsgAttempt` is modeled as full execution audit on the default path
- current storage and assignment paths assume cheap access to one task's full message set

That shape is acceptable for validation shells and medium-scale integration
tests. It is not the right mainline shape for millions of runnable work items.

## 2. Target Outcome

The platform should keep its kernel but compress the default hot path.

Target statement:

- keep `Task` as the control-plane shell for lifecycle, configuration, and convergence
- move high-volume work-item flow onto queue-native message envelopes
- make ingest explicit for `batch`, `stream`, and `file` task sources
- treat Redis-style hot queues and indexes as the mainline execution substrate for large workloads
- keep heavy attempt-level audit as an optional or downgraded layer rather than the default requirement for every message

Short version:

`preserve kernel, compress hot path`

## 3. Non-Goals

This compression does not mean:

- deleting the `Task` concept
- redefining the platform into a transport-specific worker system
- removing task-level convergence, retry, or terminal reasoning entirely
- requiring exactly-once delivery before high-volume support is useful
- keeping the current heavy audit semantics on the default path at any cost

## 4. Main Model Shift

The main shift is from:

`Task owns many in-process TaskMsg objects and the engine scans them`

to:

`Task controls one ingest + queue + result-convergence workflow`

Practical implications:

- the default runnable unit becomes a queue envelope, not a full `TaskMsg` object graph
- task status is driven by counters and queue/result events, not full-message scans
- file-backed tasks are ingested incrementally, not fully materialized up front
- result convergence is asynchronous and counter-driven

## 5. Control Plane vs Data Plane

### 5.1 Task Is Control Plane

In the high-volume model, `Task` is the stable control-plane shell.

Default `Task` responsibilities:

- task identity
- project and user ownership
- task-level shared configuration
- task source metadata
- task ingest state
- aggregate counters
- lifecycle and terminal reason
- top-level control operations such as pause, resume, cancel, and seal

`Task` should not be the default in-memory owner of all runnable messages for a
large workload.

### 5.2 Message Flow Is Data Plane

The hot data plane should be queue-native.

Default data-plane responsibilities:

- represent one consumable work item
- hold minimal routing and retry state
- carry or reference the payload
- support lease, retry, and result write-back
- support high-throughput queueing without full aggregate hydration

## 6. Target Task Shape

The target default `Task` shape should converge toward these fields:

- `taskId`
- `status`
- `project`
- `user`
- `sharedConfig`
- `sourceType`
- `ingestStatus`
- `intakeStatus`
- `taskTargetNumber`
- `enqueuedCount`
- `inflightCount`
- `successCount`
- `failedCount`
- `expiredCount`
- `terminalReason`
- `createTime`
- `updateTime`

Notes:

- exact naming may differ during migration
- aggregate counters remain valuable and should stay explicit
- `Task` should remain small enough to update frequently without dragging large message payloads along

## 7. Target Source Modes

The platform should support three source modes through one ingest mainline:

1. `batch`
   - one API call submits a bounded input list
   - appropriate for small to medium immediate workloads
2. `stream`
   - clients append work items incrementally after task creation
   - appropriate for low-latency producer-driven flows
3. `file`
   - clients submit a file or file reference
   - the platform creates the task shell first and then ingests the file asynchronously in chunks

Working rule:

- source modes differ at ingress only
- once ingested, they should become the same queue-native runnable unit
- `file` tasks are source-reference shells at create time; inline initial inputs are rejected so large files cannot be accidentally materialized as in-memory `TaskMsg` lists
- `batch` create and stream/file append are bounded ingest batches; callers must chunk large producers through the ingest path

## 8. Target Runnable Unit

The default runnable unit should be a compressed queue envelope.

Suggested envelope fields:

- `taskId`
- `messageId`
- `eventCode`
- `payload` or `payloadRef`
- `retryCount`
- `leaseToken`
- `workerHint`
- `workerContextHint`
- `shardKey`
- `nextVisibleAt`

Working rules:

- keep the envelope small
- avoid embedding large read-model or audit state in the ready queue
- use `payloadRef` when message payloads are too large for efficient hot-queue storage
- do not treat the queue envelope as the long-term source of full historical truth

## 9. Redis-First Hot Path

For the high-volume mainline, Redis-style hot data structures are the right default fit.

Target runtime shape:

- ready queue: runnable message envelopes
- inflight index: active lease ownership and timeout recovery
- delayed retry bucket: retry backoff scheduling
- output queue: worker result envelopes
- task counters: hot aggregate status for convergence

The queue path should not require hydrating all messages for a task before work
can begin.

## 10. Result Path

Results should not be modeled as synchronous mutation of a giant task-owned
message set.

Target result path:

1. worker or transport submits a result envelope
2. result enters output queue
3. result aggregator updates counters and any hot message state
4. task terminal policy evaluates aggregate convergence

Working rule:

- task completion should be derived from incremental counters and sealed-intake rules, not from full scans of every message in steady-state hot paths

## 11. Attempt Audit Compression

The current `TaskMsgAttempt` model is useful, but it is too heavy as a default
requirement for every message in a high-volume path.

Target rule:

- keep active lease ownership on the hot path
- downgrade full attempt history to optional audit, sampled audit, failure-only audit, or short-retention audit

This means:

- the platform still supports debugging and postmortem analysis
- the default path no longer assumes every message deserves a permanently retained full attempt timeline

## 12. Lifecycle Compression Rule

The message hot path should use the smallest stable state set that still
supports correctness.

Target default message state intent:

- ready to dispatch
- leased/inflight
- done
- failed
- delayed retry

Richer phases such as adapter-level ack or executor-started transitions may
still exist, but they should not automatically become default persistent
high-volume state unless they are required for correctness.

## 13. Migration Rules

The migration must be staged. Do not attempt one big rewrite.

Stage order:

1. freeze target model and documentation
2. compress `Task` into a control-plane shell
3. add explicit ingest pipeline for `batch`, `stream`, and `file`
4. replace task-owned assignment flow with queue-envelope-driven dispatch
5. move result convergence onto output queue plus counters
6. downgrade heavy attempt audit from default path
7. remove superseded object-heavy hot-path seams

Current landed slice:

- `TaskManager` still creates the `Task` shell and persisted `TaskMsg` projections
- each initial or appended `INIT` `TaskMsg` is also written to `TaskWorkRuntime`
- assignment now claims ready work from runtime instead of scanning all messages for `INIT`
- runtime owns active lease tokens and expiry indexes
- result handling applies a runtime result outcome before updating the compatibility projection
- task progress and terminal policy now evaluate `TaskWorkRuntime` counters instead of `TaskMsg` aggregate scans
- task terminal/delete paths discard runtime work for that task

Working rule:

- do not leave a compatibility seam as a second effective mainline

## 14. Verification Requirements

Each migration slice must prove these lanes:

- `perf`: queue pressure, ingest throughput, and hot-key/path cost
- `concurrency`: lease expiry, retry, duplicate result, and inflight recovery
- `Boot-shell E2E`: real task create -> ingest -> dispatch -> result -> convergence path

Minimum high-volume proof points:

- bounded-memory ingest of large file-backed tasks
- queue-driven dispatch without full task-message scan
- result convergence by counters
- retry and timeout recovery without double-finalization

## 15. Current Design Pressure

When choosing between a heavier semantic and a lighter scalable mainline, bias toward:

- smaller hot-path state
- fewer default persistent transitions
- queue-native runnable units
- incremental counters over repeated full scans
- optional audit over mandatory heavy audit

The repo should feel like a real high-volume scheduling platform, not an
in-memory orchestration demo with Redis added underneath it.
