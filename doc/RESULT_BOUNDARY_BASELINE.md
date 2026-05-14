# Result Boundary Baseline

Status: current result-kernel owner baseline.

Use this with:

- [../AGENTS.md](../AGENTS.md)
- [STATE_MACHINE_BASELINE.md](STATE_MACHINE_BASELINE.md)
- [INFRA_TRUTH_LAYERS.md](INFRA_TRUTH_LAYERS.md)
- [TRACE_CONTRACT.md](TRACE_CONTRACT.md)
- [../transport/TRANSPORT_BOUNDARY_BASELINE.md](../transport/TRANSPORT_BOUNDARY_BASELINE.md)

## 1. Result Kernel Mainline

The current result mainline is:

```text
callback / result inbox
  -> transport ingress normalization
  -> optional envelope identity gate
      -> engine result ingest port
      -> TaskResultService
      -> terminal / duplicate / late callback classification
      -> TaskResultRuntime.stageCallback(...)
      -> TaskWorkRuntime.applyResultWithContext(...)
      -> runtime outcome interpretation
      -> trace
      -> TaskResultRuntime.commitVisibleFinal(...) for stable-final rows
      -> runtime attempt-closed/logical-final/progress idempotency barriers
      -> projection residue write submitted best-effort
      -> synchronous result-side event publish
      -> task progress / terminal convergence trigger
```

This is the result-side counterpart to assignment. Assignment reserves and
leases work; result applies one callback against the active runtime lease and
then lets engine lifecycle policy converge the task aggregate.

## 2. Owner Layers

| Layer | Owner | Meaning | Must not own |
| --- | --- | --- | --- |
| worker result payload | `TaskResultReport` | worker-submitted task/message result body | retry, finality, terminal policy, transport routing |
| transport ingress metadata | `TransportResultEnvelope` | adapter id, route key, trace id, optional attempt/lease evidence | worker protocol replacement, lifecycle mutation |
| ingress channel / inbox | `TaskResultIngestChannel`, `RedisTaskResultIngestChannel` | bounded process/JVM ingress into engine result apply | durable result log, ack ledger, task state |
| engine ingest port | `TaskResultIngestFacade`, `TaskResultIngestPort` | narrow callable surface from transport into engine result handling | server API ownership, public result read API |
| runtime apply truth | `TaskWorkRuntime.applyResultWithContext(...)` | lease-valid apply, retry budget consumption, runtime apply status, counters, recent receipts | trace policy, projection storage, public result reads |
| runtime result read truth | `TaskResultRuntime` | staged callback repair anchors, stable-final visible result rows, task-local result sequence, attempt-closed/logical-final/progress barriers | work queue ownership, transport ack/redelivery, task lifecycle policy, projection/debug residue |
| engine result orchestration | `TaskResultService` | terminal/duplicate/late classification, runtime outcome interpretation, trace, projection submission, result-side events, convergence trigger | durable ledger storage, transport I/O |
| projection residue | `TaskDetailStore` message/attempt projection | bounded UI/debug/audit residue and review read view | callback acceptance truth, retry/finality truth, public result read truth |

## 3. Runtime Apply Truth

`TaskWorkRuntime.applyResultWithContext(...)` is the current runtime result
truth. It decides whether the callback can consume an active lease, whether the
runtime retry budget schedules another attempt, and whether the item is
runtime-final. Engine result code must interpret this outcome instead of
deciding retry/finality from projection rows, transport metadata, or worker
payload fields alone.

Callbacks without active runtime lease are not accepted by the engine apply
path. Duplicate and late callbacks may be accepted as no-ops only when runtime
recent-final receipt or task terminal state proves that the logical result has
already converged.

Stable-final public result rows are committed into `TaskResultRuntime`, not
`TaskDetailStore`. The runtime separates callback-attempt staging from visible
message-final rows:

- stage identity: `taskId + messageId + normalized identity digest`
- visible identity: `taskId + messageId`
- visible sequence: task-local monotonic `seq` allocated at first final commit

Active stable-final result commit must not reject solely because a task has
many visible rows. Explicit task discard is the only result-runtime cleanup
surface in the current kernel.

Visible final commit is atomic at the runtime boundary:

- duplicate visible commit resolves by `taskId + messageId`
- visible `seq` is allocated only when the first committed final row becomes
  readable
- Redis runtime commit is a single-script transition; there is no `PENDING`
  sentinel row that callers must interpret

## 4. Transport Ingress

`TaskResultReport` is the worker protocol payload. `TransportResultEnvelope`
adds transport-owned ingress metadata around that payload; it is not a second
worker result protocol.

Envelope identity checks may use `attemptId` and `leaseToken` to reject stale
transport callbacks before engine apply. A failed envelope identity check is an
ingress accepted-noop: the transport runtime handled the envelope and
intentionally did not call engine apply. The public `boolean` return from
`TaskResultIngestChannel` does not distinguish applied, enqueued, or
accepted-noop outcomes in the current API.

Redis result inboxes are bounded runtime ingress queues drained by the engine
process. They are not durable result logs and do not provide ack/redelivery
ledger semantics.

## 5. Projection Residue

Projection writes after result apply are best-effort projection residue.
They are submitted after runtime acceptance so UI/debug/audit readers can see a
bounded message and latest-attempt view. They are not the result commit point.

Projection residue must not decide:

- callback acceptance
- stale callback rejection
- retry scheduling
- finality
- task terminal convergence
- `/api/v1/tasks/{taskId}/results`
- SDK `TaskResultQueryOperations`

Public result reads and archive generation read committed stable-final rows
from `TaskResultRuntime`. Controllers must not read projection rows to build or
fill public result responses. Memory result runtime is volatile local/dev
truth; Redis result runtime is the cross-process runtime truth. Durability
follows the selected runtime implementation.

## 6. Repair And Barriers

Repair truth is:

```text
staged callback exists
+ TaskWorkRuntime recent final receipt / stable-final truth exists
+ visible final row missing
= repair candidate
```

The explicit repair state is not a separate durable row written after failure.
If visible commit fails after runtime apply, the staged draft remains the repair
anchor. Callback and repair paths both use runtime barriers keyed by
`taskId + messageId + finalSeq` before attempt-closed event publish,
logical-final event publish, and progress application.

Barrier protocol in the current kernel is:

- claim returns a token plus `claimedAt` / `expiresAt`
- stale claims may be stolen after TTL
- row barrier bits remain final truth:
  - `attemptClosedPublished`
  - `logicalFinalPublished`
  - `progressApplied`
- callback path and repair path both mark those bits through runtime-owned
  barrier mutation, not by inferring completion from side effects

Repair scan is bounded by runtime-owned indexes:

- staged callbacks missing visible final rows
- committed visible rows missing attempt-closed publish mark
- committed visible rows missing logical-final publish mark
- committed visible rows missing progress-apply mark

This does not promise crash-gap exactly-once delivery. The current guarantee is
recoverable at-least-once side effects with normal callback/repair race
suppression. Attempt-closed listeners, logical-final listeners, and progress-side
consumers must stay idempotent on `taskId + messageId + seq`.

Redis `TaskResultRuntime` v1 is designed for standalone or single-shard Redis.
Redis Cluster key-slot design for multi-key barrier and commit scripts is out of
scope for this baseline.

## 7. Non-Goals

This baseline does not implement:

- durable result ledger
- `outputRef` or blob-backed result storage
- server-owned transport result endpoints
- million-scale archive materialization beyond the current streaming contract

Those belong in a separate result ledger/public-results evolution after this
runtime-owned result truth is stable.
