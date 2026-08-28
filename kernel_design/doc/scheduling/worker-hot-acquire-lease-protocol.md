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
  -> result exact release
```

There is no separate reservation store, attempt lifecycle, lease token model,
or transport-owned Worker state.

`WorkerId` means one scheduler-visible execution slot. A successful allocation
lease prevents that slot from receiving another independent TaskItem until
trusted result disposition or lease expiry. A physical executor with parallel
capacity exposes multiple logical WorkerIds; the kernel does not create several
active assignments behind one Worker score.

One Worker lease carries one TaskItem and one DeliveryCommand. A Worker that exposes
a business batch operation receives the batch as a bounded collection inside
that TaskItem payload. The kernel does not merge multiple TaskItems, create
multiple Item claim fences behind one Worker lease, or release the slot early.

## HOT-Pool And DIRECT Acquisition Lease

```text
acquire_hot_acquire_candidates(
    workerGroupId,
    hotEligibilityFloorMillis?,
    limit
)
  -> workerId -> opaque observedScore

observe_due_hot_scores(
    workerGroupId,
    workerIds,
    hotEligibilityFloorMillis?
)
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
When Worker Serviceability is enabled, both reads exclude scores below that
Kernel process's HOT eligibility floor. With no Serviceability configuration,
the optional floor is absent and the original full positive range remains.

`WorkerCandidateMechanism` exposes separate bounded observation and exact-fence
semantics while `WorkerCandidateSelectionPolicy` owns matching and selection:

```text
acquire_hot_pool_candidates
  bounded HOT scan for Task-level cache warming

DIRECT with an Item-owned rule
  empty rule: one bounded due-HOT WorkerGroup score query
  non-empty rule: bounded Worker ids from workerId $eq/$equal/$in
  non-empty rule without a Worker-id source fails closed
  complete point pre-match over canonical Worker descriptors
  use Group-query scores or observe only pre-matched explicit Workers
  exact lease at most requestedCount Workers per request
  complete post-lease rematch
```

HOT-pool and DIRECT observation do not read Candidate Cache; PRECOMPUTED does.
Allocation cache publication also goes through the Mechanism. Each call is
scoped to one explicit WorkerGroup and one score ZSET. Policy first matches and
selects bounded observations, then Mechanism exact-leases only those Workers,
reloads canonical descriptors, and returns opaque lease references for the
required post-lease rematch.

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

PRECOMPUTED miss or rejected evidence never falls back to DIRECT acquisition.
`TaskDispatchPolicy` chooses one path from
`TaskDescriptor.workerAllocationMechanism`: PRECOMPUTED for Task-owned rules or
DIRECT for Item-owned rules. It receives only opaque candidate/lease
references; neither Policy path decodes scores, clears dirty, or releases
rejected candidates.

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

## Result Disposition

The opaque Worker fence returns through `ResultContext` together with its
`workerGroupId` home-bucket coordinate. Result routing must not reread Task
metadata to recover the Worker score bucket:

```text
200 / Worker failure
  -> this attempt crossed the Worker execution boundary
  -> exact release preserving positive polarity

Adapter rejection
  -> delivery definitively ended before Worker execution entry
  -> exact release preserving positive polarity
```

For Adapter rejection, release proves only that this exact lease no longer
needs to be held. It does not prove the Worker is connected or serviceable;
Adapter Route evidence and the Serviceability Pacer own that classification.

A Worker Delivery Dispatch producer emits evidence only; it never mutates
score. The polling Worker endpoint accepts only `200` and Worker-owned `3...`.
The long-lived Adapter batch endpoint accepts trusted
pre-execution rejection evidence; authentication of that role remains
deferred. Result routing invokes WorkerScoreCore and treats Worker disposition
independently from Item movement.

Every result submits its own exact lease evidence without cross-class winner
aggregation. Stale evidence cannot release a newer lease. Conflicting
classes for one exact lease violate the one-logical-outcome protocol; the score
owner accepts at most one applicable disposition. A duplicated copy of the same
transport result is allowed to reach routing, but after the first applicable
exact transition the old Worker fence is stale and cannot change newer truth.

## Scheduling Serviceability

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

This is not a physical connection or socket state. Adapter Route changes and
periodic Adapter snapshots are evidence interpreted by the independent Worker
Serviceability Result Policy in the Adapter Evidence lane. Task results only release their correlated lease;
they do not change polarity. With no demand or fresh evidence, bounded
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
| Result | Adapter rejection | exact release; no online inference |
| Result | malformed/missing evidence | no guessed mutation; expiry recovers |

No branch adds a repair scanner, compensation queue, distributed lock, or
cross-owner transaction.

## Owner Matrix

| Owner | Responsibility | Refusal |
| --- | --- | --- |
| WorkerScoreCore | score encoding, scans, exact lease, dirty fence, release and polarity mechanics | no Task policy, transport or result subcode parsing |
| WorkerRuntime | declaration validation, first score initialization and trusted reconnect reconciliation | no heartbeat or dispatch ownership |
| WorkerCandidateMechanism | bounded HOT/point observations, cached opaque lease correlation, exact lease/renew and canonical descriptor reload | no priority, deficit, rule decision or fallback |
| WorkerCandidateSelectionPolicy | request priority, bounded candidate choice, complete pre/post match and PRECOMPUTED/DIRECT strategy | no Score or lease internals |
| TaskWorkerAllocationPolicy | consume verified RUNNING Task evidence, read bounded Candidate counts, compute deficits and request Candidate publication | no Task discovery, Task-score write or result handling |
| TaskExecutionMechanism | Item observation/finality, Worker fence verification, Item claim, Command publication and Task pacing/idle transitions | no expiry, pairing, limit or idle-disposition policy |
| TaskDispatchPolicy | bounded Task batch, expiry/exhaustion, pairing, per-Task limit and idle-disposition choice | no Score, Candidate Cache, Command Runtime or ResultContext access |
| Worker Delivery Dispatch | mailbox consume, deadline check, command forwarding and DeliveryReport append | no Worker selection or score parsing/mutation |
| Long-lived Adapter | direct pre-execution rejection evidence | no inferred rejection from missing response or mailbox age |
| ResultConvergenceApplication | weighted-fair bounded lane consume over ten shared Batch slots; Task lanes may execute concurrently and Adapter Evidence remains single-flight | no Redis ownership, dynamic lanes, Worker selection or exact subcode policy |
| TaskResultBatchPolicy | context decode, bounded owner-key grouping and TaskItem/Worker execution event publication | no queue ownership, score decoding, mechanical owner calls or cross-owner truth |
| WorkerExecutionResultEvents | successful/failed Task execution semantics and batched score-owner fence application | no DeliveryReport, lane, JSON or endpoint-code interpretation |

## Deferred Policy

Recovery probe cadence and ranking are deferred. TaskItem coalescing is not a
deferred kernel policy; business batching is expressed inside one TaskItem
payload.

## Guardrails

- Do not lease negative `RECOVERY_RECHECK` scores through HOT primitives.
- Do not expose score encoding, dirty bit, sign or timeSlot to callers.
- Do not let active renewal clear dirty.
- Do not let PRECOMPUTED acquisition fall back to DIRECT acquisition.
- Do not let a Dispatch Policy call WorkerScoreCore, TaskScoreBandCore,
  TaskItemScoreBandCore or WorkerCommandRuntime directly. A bounded
  CandidateWorkerCache count read may remain in Allocation Policy because it
  neither exposes a Score nor composes owners.
- Do not release rejected dispatch candidates as compensation.
- Do not treat missing result as Adapter rejection.
- Do not let old result evidence mutate a newer Worker lease.
- Do not create separate Attempt, reservation, session epoch or lease registry.
- Do not release a Worker fence after DeliveryCommand append to simulate
  immediate slot reuse.
- Do not assign independent TaskItems or different Tasks concurrently to one
  WorkerId.
