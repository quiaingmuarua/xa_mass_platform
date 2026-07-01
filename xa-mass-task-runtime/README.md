# xa-mass-task-runtime

Status: semantic task runtime owner module.

This module owns task item/result convergence contracts. It defines public
ports, commands, outcomes, and contract-test surfaces for accepted append,
task-level scheduler discovery, claim, result finality, active-lease repair,
progress snapshots, discard, and bounded final-result reads.

This module must not own physical storage, Redis keys, Lua scripts, memory-map
shape, Spring beans, process threads, engine shell policy, worker-runtime
selection, transport delivery, SDK facade behavior, or server HTTP routes.

Physical implementations live behind these ports in infra modules such as
`platform_infra/mass-task-runtime-memory` and
`platform_infra/mass-task-runtime-redis`. Starter/thread lifecycle belongs to
`sdk/xa-mass-task-runtime-starter-sdk`.

## First Boundary

Append writes accepted ready backlog truth. Scheduler discovery is task-level
and weakly consistent. Claim is the first item ownership transition and must be
atomic in physical implementations.

Production cutover cannot stop at active lease creation. Any serving path that
creates active leases must also converge result/retry/finality and active-lease
repair through this owner, or delegate old result/repair callers to this owner.

## Public Ports

| Port | Runtime owner action | Old path targeted for closure |
| --- | --- | --- |
| `TaskRuntimeAppendPort` | accept or reject a batch, then write accepted ready backlog truth | `TaskCommandPort.appendTaskItems*`, deleted append-side runtime enqueue path |
| `TaskRuntimeSchedulerPort` | accept narrow task eligibility snapshots and discover task-level runnable candidates without per-item lane entries | runtime-ready recovery and dispatch-wakeup reads |
| `TaskRuntimeClaimPort` | consume worker reservation evidence and atomically move ready work to active lease | `TaskAssignmentRuntimePort.claimReady` |
| `TaskRuntimeResultPort` | apply worker/dispatch/timeout result facts and emit message-finality outcomes | `TaskResultIngestPort`, dispatch-failure compensation, lease expiry finality |
| `TaskRuntimeRepairPort` | discover active leases for bounded timeout repair and owner-local release evidence | `TaskLeaseMaintenancePort.pollExpiredLeases`, `getActiveLeases`, active-work worker queries |
| `TaskRuntimeProgressPort` | expose narrow aggregate counts for engine progress and terminal policy without leaking old stats DTOs | `TaskStateRuntimePort.getTaskWorkStats` |
| `TaskRuntimeReadPort` | read bounded final result rows retained by task runtime | deleted old result-runtime window reads |
| `TaskRuntimeDiscardPort` | discard ready, active, and retained final-result runtime state after shell terminal/delete policy | shell delete/cancel/terminate runtime cleanup |

## State Rules

- First-version append is `ALL_ACCEPTED` or `REJECTED_BEFORE_RUNTIME`; caller
  API idempotency and duplicate-message filtering are deferred.
- Append does not synchronously rewrite scheduler lane score. Scheduler
  discovery and owner-local recovery keep accepted backlog discoverable.
- Worker reservation evidence must exist before claim can create an active
  lease; task-runtime must not create unbound active leases.
- `ClaimLeasePolicy.maxItems` is the total item claim limit. Reservations are
  selected-worker/admission evidence and may be reused round-robin for a larger
  batch claim, with a distinct lease token per claimed item.
- `eventCode`, `payloadRef`, and claim `batchId` are optional carrier fields
  preserved for worker handler invocation, payload lookup, and engine dispatch
  binding. They are not worker-selection, transport-routing, or scheduling
  facts.
- Message finality is task-runtime owned. Task terminal convergence remains an
  engine/shell aggregate policy that consumes task-runtime outcome facts and
  progress snapshots.
- Lease timeout timing is best-effort, but active lease discoverability is not
  optional. Active-by-task lease snapshots exist for engine terminal/resource
  release and old port closure; they are not server view APIs.
- Final result rows are bounded runtime read state, not durable public history.
