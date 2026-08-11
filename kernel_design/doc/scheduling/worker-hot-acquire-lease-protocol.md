# Worker HOT_ACQUIRE Lease Protocol

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Parent contract: [Worker Score-Band Scheduling](worker-score-band-scheduling.md).
Related pacers:
[Task Worker Allocation Pacer](task-worker-allocation-pacer.md),
[Task Dispatch Pacer](task-dispatch-pacer.md), and
[Result-Routing Scheduling](result-routing-scheduling.md).

## Purpose

One opaque Worker score fence protects the bounded continuation from Worker
allocation through dispatch and result disposition:

```text
due HOT observation
  -> exact allocation lease and dirty clear
  -> Worker matcher validation
  -> CandidateWorkerEntry(workerLeaseScore)
  -> optional CandidateWorkerCache handoff
  -> PRECOMPUTED acquisition exact validate/renew and rematch
  -> ResultContext(workerLeaseScore)
  -> result exact release or RECOVERY_RECHECK demotion
```

There is no separate reservation store, attempt lifecycle, lease token model,
or transport-owned Worker state.

`WorkerId` means one scheduler-visible execution slot. A successful allocation
lease prevents that slot from receiving another independent TaskItem until
trusted result disposition or lease expiry. A physical executor with parallel
capacity exposes multiple logical WorkerIds; the kernel does not create several
active assignments behind one Worker score.

One Worker lease carries one TaskItem and one WorkerCommand. A Worker that exposes
a business batch operation receives the batch as a bounded collection inside
that TaskItem payload. The kernel does not merge multiple TaskItems, create
multiple Item claim fences behind one Worker lease, or release the slot early.

## HOT-Pool And TARGETED Acquisition Lease

```text
acquire_hot_acquire_candidates(workerGroupId, limit)
  -> workerId -> opaque observedScore

observe_due_hot_scores(workerGroupId, workerIds)
  -> due HOT workerId -> opaque observedScore

acquire_observed_hot_score_leases(
    workerGroupId,
    observedScores,
    targetTimeMillis,
)
  -> independent exact-CAS results
```

Only due positive HOT scores may be leased. Successful acquisition preserves
laneRank, writes a future time coordinate, and clears dirty. Concurrent rounds
may observe the same Worker, but only one exact observed-score CAS succeeds.

`WorkerCandidateAcquirer` exposes separate source semantics:

```text
acquire_hot_pool_candidates
  bounded HOT scan for Task-level cache warming

TARGETED with a complete Item-owned rule
  bounded Worker ids from workerId $eq/$equal/$in
  complete pre-match over snapshots and explicit index.* projections
  observe only the pre-matched Workers' due HOT scores
  exact lease at most requestedCount Workers per request
  complete post-lease rematch
```

Neither HOT-pool nor TARGETED acquisition reads or writes candidate cache; the
allocation Pacer owns publication. Each call is scoped to one explicit
WorkerGroup and one score ZSET. Every accepted Worker still passes exact lease
and full allocation-rule matching; explicit index fields are point-loaded only
for that bounded Worker-id set.

Pre-match failures do not receive a lease. Post-lease mismatches and candidate
publication failures are not actively released; their short leases expire
naturally, preventing immediate hot-loop rematching from the same evidence.

## PRECOMPUTED Acquisition Validation

The `PRECOMPUTED` path consumes bounded cache evidence and calls:

```text
renew_active_hot_score_leases(
    workerGroupId,
    observedScores,
    targetTimeMillis,
)
```

All accepted observations are submitted in one score-owner batch for the
call's explicit WorkerGroup; matching remains request-local so cached evidence
cannot silently change its CandidateId meaning.

Rules:

```text
storedScore != observedScore -> STALE
polarity != HOT_ACQUIRE      -> rejected
dirty == 1                   -> STALE
observed lease expired       -> STALE
target is not future         -> INVALID
lease covers target          -> exact NOOP + observedScore
lease needs extension        -> exact CAS + renewedScore
```

Only a `TRANSITIONED` or exact `NOOP` result carrying a score is rematched
against the current request. A successful rematch may proceed to Item claim.
The returned fence, unchanged or renewed, is written into
`forward`.

PRECOMPUTED miss or rejected evidence never falls back to TARGETED acquisition.
`TaskItemDispatcher` never calls Worker score directly. It chooses one path
from `TaskDescriptor.taskType`: PRECOMPUTED for Task-owned rules or TARGETED
for Item-owned rules. Neither the Dispatcher nor PRECOMPUTED acquisition
decodes scores, clears dirty, or releases rejected candidates.

## Dirty Fence

Dirty means only:

```text
an active Worker lease exists
and metadata used by its match may have changed
```

It is not scheduling-serviceability polarity, metadata version, global Worker
status, or an
attribute update lock. Non-lease owners may set dirty but never clear it.
Allocation may clear dirty only while acquiring a due Worker before fresh
matching. Active dispatch validation rejects dirty and forces a later fresh
allocation/match.

The score owner exposes a reconciliation primitive:

```text
existing score
  -> preserve timeSlot and laneRank
  -> converge to HOT_ACQUIRE
  -> set dirty=1
```

The primitive can invalidate old candidate evidence without releasing future
holds or introducing a session epoch. It is not called by Worker resource
upsert and has no production caller in this slice. A future explicit lifecycle
operation must validate recovery evidence before using it.

## Result Disposition

The opaque Worker fence returns through `ResultContext` together with its
`workerGroupId` home-bucket coordinate. Result routing must not reread Task
metadata to recover the Worker score bucket:

```text
200 / Worker failure
  -> this attempt crossed the Worker execution boundary
  -> exact release preserving positive polarity

Adapter rejection
  -> a trusted Adapter confirmed this attempt did not enter Worker execution
  -> exact CAS from HOT_ACQUIRE to RECOVERY_RECHECK
```

A Worker Delivery Dispatch producer emits evidence only; it never mutates
score. The polling Worker endpoint accepts only `200` and Worker-owned `3...`.
The long-lived Adapter batch endpoint accepts trusted
pre-execution rejection evidence; authentication of that role remains
deferred. Result routing invokes WorkerScoreCore and treats Worker disposition
independently from Item movement.

Every result submits its own exact lease evidence without cross-class winner
aggregation. Stale evidence cannot release or demote a newer lease. Conflicting
classes for one exact lease violate the one-logical-outcome protocol; the score
owner accepts at most one applicable disposition. A duplicated copy of the same
transport result is allowed to reach routing, but after the first applicable
exact transition the old Worker fence is stale and cannot change newer truth.

## Serviceability Reconciliation And Recovery Demotion

```text
reconcile_worker_hot_acquire(workerGroupId, workerId)
```

- Missing score returns `STALE`; WorkerRuntime owns initialization.
- Positive clean becomes positive dirty.
- Negative becomes positive dirty.
- Positive dirty is `NOOP`.
- timeSlot and laneRank are preserved; future holds remain future.

This mechanism does not make resource upsert a reconnect or activation API.
Current production upsert initializes a missing score and otherwise preserves
the existing score exactly.

```text
demote_observed_worker_leases_to_recovery(workerGroupId, observedScores)
```

- Accepts only clean positive opaque lease scores.
- Uses independent exact-score CAS.
- Writes `-abs(observedScore)` and preserves the complete absolute coordinate.
- `STALE` does not affect Item outcome.

Polarity is the kernel-owned classification of whether a Worker may participate
in normal TaskItem scheduling:

```text
HOT_ACQUIRE
  scheduling-available
  may enter ordinary allocation when due and after all runtime checks

RECOVERY_RECHECK
  scheduling-unavailable
  excluded from ordinary allocation; may enter only recovery validation
```

This is not a physical connection or socket state. Adapter, Worker, and endpoint
manager observations are evidence. Execution evidence, pre-execution rejection,
and future explicit recovery probes make the kernel classification converge
when scheduling or recovery work requires it. With no demand, bounded
classification lag is allowed.

## Failure Matrix

| Stage | Evidence | Action |
| --- | --- | --- |
| Allocation | lease CAS lost | exclude Worker |
| Allocation | unmatched or publication failure | retain short lease until expiry |
| PRECOMPUTED acquisition | dirty/recovery/expired/stale fence or rematch failure | consume candidate, do not claim Item |
| Item dispatch | Item absent or claim lost | no release; leases expire |
| Task Dispatch | mailbox residue replaced | publish the new lease-backed Seed; optional bounded residue metric |
| Task Dispatch | append failed or result ambiguous | no compensation; claim and lease expiry recover |
| Worker Delivery Dispatch | expired or malformed Seed | drop; no synthetic result |
| Polling Worker | unsupported EventCode, handler failure, or execution failure after entry | emit a Worker-owned `3...` outcome |
| Long-lived Adapter | command expired before execution entry | may emit `WorkerDeliveryAdapterErrorCode.COMMAND_EXPIRED` through Adapter batch ingress |
| Worker Delivery Dispatch | response lost, process crash, or no result evidence | `UNKNOWN`; claim and lease expiry recover |
| Result | `200` or Worker failure | exact release |
| Result | Adapter rejection | exact RECOVERY_RECHECK demotion |
| Result | malformed/missing evidence | no guessed mutation; expiry recovers |

No branch adds a repair scanner, compensation queue, distributed lock, or
cross-owner transaction.

## Owner Matrix

| Owner | Responsibility | Refusal |
| --- | --- | --- |
| WorkerScoreCore | score encoding, scans, exact lease, dirty fence, release and polarity mechanics | no Task policy, transport or result subcode parsing |
| WorkerRuntime | declaration validation, first score initialization and trusted reconnect reconciliation | no heartbeat or dispatch ownership |
| WorkerCandidateAcquirer HOT pool | bounded due-HOT scan, exact lease and full match for precomputation | no cache read/write |
| WorkerCandidateAcquirer TARGETED | request-local WorkerId source, pre-match, bounded exact lease and post-lease rematch | no index discovery, cache read/write or fallback |
| WorkerCandidateAcquirer PRECOMPUTED | cache consume, exact active-fence validation/renewal and rematch | no HOT scan or fallback |
| TaskWorkerAllocationPacer | retain Task-owned rule Tasks, acquire HOT-pool candidates and publish cache evidence | no direct Worker-score or result handling |
| TaskItemDispatcher | resolve PRECOMPUTED/TARGETED from immutable TaskType, preserve binding, claim Item and build WorkerCommand | no Task-score, mailbox, cache or Worker-score access |
| TaskDispatchPacer | bounded Task round, suffix routing, mailbox publication and Task-score pacing | no candidate acquisition, Item claim or Worker-score access |
| Worker Delivery Dispatch | mailbox consume, deadline check, command forwarding and WorkerResult append | no Worker selection or score parsing/mutation |
| Future trusted Adapter | direct pre-execution rejection evidence | no inferred rejection from missing response or mailbox age |
| ResultRoutingPacer | bounded consume, context decode, owner-key grouping and handler delegation | no direct Task/Worker owner dependency, Worker selection or exact subcode policy |
| Result-routing handlers | owner-local Task finality and Worker disposition policy | no queue ownership, score decoding or cross-owner truth |

## Deferred Policy

Recovery probe cadence and ranking are deferred. TaskItem coalescing is not a
deferred kernel policy; business batching is expressed inside one TaskItem
payload.

## Guardrails

- Do not lease negative `RECOVERY_RECHECK` scores through HOT primitives.
- Do not expose score encoding, dirty bit, sign or timeSlot to callers.
- Do not let active renewal clear dirty.
- Do not let PRECOMPUTED acquisition fall back to TARGETED acquisition.
- Do not let TaskItemDispatcher or TaskDispatchPacer call WorkerScoreCore
  directly.
- Do not release rejected dispatch candidates as compensation.
- Do not treat missing result as Adapter rejection.
- Do not let old result evidence mutate a newer Worker lease.
- Do not create separate Attempt, reservation, session epoch or lease registry.
- Do not release a Worker fence after WorkerCommand append to simulate
  immediate slot reuse.
- Do not assign independent TaskItems or different Tasks concurrently to one
  WorkerId.
