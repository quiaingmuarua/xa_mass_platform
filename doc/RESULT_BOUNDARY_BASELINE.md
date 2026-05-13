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
      -> TaskWorkRuntime.applyResultWithContext(...)
      -> runtime outcome interpretation
      -> trace
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
| engine result orchestration | `TaskResultService` | terminal/duplicate/late classification, runtime outcome interpretation, trace, projection submission, result-side events, convergence trigger | durable ledger storage, transport I/O |
| compatibility residue | `TaskDetailStore` message/attempt projection | bounded UI/debug/audit residue and compatibility read view | callback acceptance truth, retry/finality truth, million-scale public result truth |

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

Projection writes after result apply are best-effort compatibility residue.
They are submitted after runtime acceptance so UI/debug/audit readers can see a
bounded message and latest-attempt view. They are not the result commit point.

Projection residue must not decide:

- callback acceptance
- stale callback rejection
- retry scheduling
- finality
- task terminal convergence

Current public result reads that depend on message/attempt projection are a
current limitation. They are not million-scale result truth and should not be
treated as a durable result ledger.

## 6. Non-Goals

This baseline does not implement:

- public `/results` large-task reads
- durable result ledger
- result archive materialization
- result sequence truth
- `outputRef` or blob-backed result storage
- server-owned transport result endpoints

Those belong in a separate result ledger/public-results design after this owner
baseline is stable.
