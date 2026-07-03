# xa-mass-task-runtime

Status: semantic task runtime owner module.

This module owns task-runtime lifecycle and item/result convergence contracts.
It defines public ports, runtime-owned values, receiver-consumed outcomes, and
contract-test surfaces for score-band lifecycle state, accepted backlog append,
task score visibility, claim, result finality, active-lease repair, progress
snapshots, discard, and task-local final-result reads.

This module must not own physical storage, Redis keys, Lua scripts, memory-map
shape, Spring beans, process threads, engine shell policy, worker-runtime
selection, transport delivery, SDK facade behavior, or server HTTP routes.

Physical implementations live behind these ports in infra modules such as
`platform_infra/mass-task-runtime-memory` and
`platform_infra/mass-task-runtime-redis`. Starter/thread lifecycle belongs to
`sdk/xa-mass-task-runtime-starter-sdk`.

## First Boundary

Append writes accepted backlog truth. Scheduler discovery is task-level
and weakly consistent. Claim is the first item ownership transition and must be
atomic in physical implementations.

Production cutover cannot stop at active lease creation. Any serving path that
creates active leases must also converge result/retry/finality and active-lease
repair through this owner, or delegate old result/repair callers to this owner.

## Public Ports

| Port | Runtime owner action | Old path targeted for closure |
| --- | --- | --- |
| `TaskRuntimeWorkPort` | append caller-owned backlog items and atomically claim backlog into active runtime state | old append/claim command buckets, caller-built runtime frames, and ready/active vocabulary |
| `TaskRuntimeScorePort` | own task-local runtime meta and task score visibility, including score candidate discovery/point-read | old scheduler discovery, dirty hints, eligibility snapshots, and caller-built score fields |
| `TaskRuntimeConvergencePort` | own result apply, retry promotion, lease repair, close, and discard convergence | old result, repair, and discard command buckets |
| `TaskRuntimeReadPort` | expose task-local point reads for final result, result correlation, progress, and active work by task | old mixed result read/write port, worker reverse active reads, and progress-only port |
| `TaskRuntimeResultWindowReadModel` | non-core ordered final-result read window while server/engine view callers are converged | old ordered final-result projection; not core score-band runtime truth |

## State Rules

- First-version append is `ALL_ACCEPTED` or `REJECTED_BEFORE_RUNTIME`; caller
  API idempotency and duplicate-message filtering are deferred.
- Append input is `AppendItemInput` only: caller-owned message identity,
  handler/event carrier, payload JSON, and payload reference. Runtime-owned
  backlog frame fields are encoded inside physical implementations.
- Append does not synchronously rewrite scheduler lane score. Scheduler
  discovery and owner-local recovery keep accepted backlog discoverable.
- Dispatch discovery reads only dispatch-visible task scores. Active-only tasks
  may remain maintenance-visible for repair/close, but they must not be returned
  as dispatch candidates.
- Claim must fence the score fact it consumes: lane, runtime gate, epoch,
  fence token, and observed score must still match before physical runtimes move
  backlog into active runtime state.
- Worker reservation evidence must exist before claim can create an active
  lease; task-runtime must not create unbound active leases.
- `ClaimLeasePolicy.maxItems` is the total item claim limit. Reservations are
  selected-worker/admission evidence and may be reused round-robin for a larger
  batch claim, with a distinct lease token per claimed item.
- `eventCode`, `payloadRef`, and claim `batchId` are optional carrier fields
  preserved for worker handler invocation, payload lookup, and engine dispatch
  binding. They are not worker-selection, transport-routing, or scheduling
  facts.
- Message finality and runtime lifecycle score are task-runtime owned. Engine
  may consume task-runtime outcome facts and progress snapshots for trace,
  resource release, and read projection, but it must not maintain a second
  lifecycle truth.
- Retry promotion and expired-lease scan return bounded lists; close returns a
  primitive decision; discard mutations are `void`. Diagnostic mutation counts
  are not core runtime API.
- Lease timeout timing is best-effort, but active lease discoverability is not
  optional. Active-by-task lease snapshots exist for engine terminal/resource
  release and old port closure; they are not server view APIs.
- Final result rows are bounded runtime read state, not durable public history.
