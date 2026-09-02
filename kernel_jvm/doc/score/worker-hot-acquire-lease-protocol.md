# Worker HOT_ACQUIRE Lease Protocol

Status: active Java Kernel HOT lease mechanism contract.

Parent contract: [Worker Score-Band Scheduling](worker-score-band-scheduling.md).
Related pacers:
[Task Worker Allocation Pacer](../../../kernel_pacer_jvm/doc/dispatch/task-worker-allocation-pacer.md),
[Task Dispatch Pacer](../../../kernel_pacer_jvm/doc/dispatch/task-dispatch-pacer.md),
and
[Result-Routing Scheduling](../../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md).

## Purpose

One opaque Worker score fence protects the bounded continuation from Worker
allocation through dispatch and result disposition:

```text
due HOT observation
  -> bounded identity-only Match Demand
  -> exact bounded pool hold and dirty clear
  -> Worker Matching Rule/Properties evidence
  -> exact active-hold confirmation and Kernel selection
  -> CandidateWorkerEntry(workerLeaseScore)
  -> optional CandidateWorkerCache handoff
  -> cached candidate exact renewal
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

## HOT-Pool And On-Demand Acquisition Lease

```text
observe_due_hot_score_candidates(
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

`WorkerCandidateSelectionPolicy` calls the bounded observation and exact-fence
Owner operations directly. A Demand is offered before the pool hold so queue
rejection does not reserve Worker capacity. Once at least one Demand is
admitted, Kernel immediately exact-holds the observed pool. Worker Matching is
a separate facts/rule owner and returns only short-lived WorkerId evidence:

```text
Task-rule precomputation
  Kernel supplies and holds one bounded due HOT WorkerId set
  Worker Matching filters it against the Task Rule and canonical facts
  Kernel confirms the hold and applies priority/count/unique selection

Item-rule on-demand acquisition
  Kernel supplies and holds one bounded due HOT WorkerId set
  Worker Matching filters it independently for each Item Rule
  Kernel confirms the hold and selects at most requestedCount per request
```

Shared HOT and item-rule on-demand observation do not read Candidate Cache;
cached candidate renewal does.
Allocation cache publication confirms the selected Worker IDs through the
Score Owner at the expected hold slot, then appends only the still-active
subset. Each call is scoped to one explicit WorkerGroup and one score ZSET.
Policy selects only the bounded identities in Evidence whose exact clean hold
is still active. It then loads the minimal Kernel Worker descriptor to obtain
the current endpoint. Matching does not receive score, priority, count, cache
or endpoint data; the opaque hold score remains inside Kernel.

Empty or stale Evidence cannot consume a hold. Unmatched, unselected and
candidate-publication-failed Workers are not actively released; their short
holds expire naturally. Moving each admitted pool to a newer score position
also lets later due Workers enter subsequent bounded observations.

## Cached Candidate Renewal

The cached renewal path consumes bounded cache evidence and calls:

```text
renew_active_hot_score_leases(
    workerGroupId,
    observedScores,
    targetTimeMillis,
)
```

All accepted observations are submitted in one score-owner batch for the
call's explicit WorkerGroup. Candidate Cache identity is already scoped to the
Task and is not rematched against Properties during renewal.

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

Only a `TRANSITIONED` or exact `NOOP` result carrying a score may proceed to
minimal descriptor loading and Item claim. The returned fence, unchanged or
renewed, is written into `forward`.

Cached miss or rejected evidence never falls back to item-rule on-demand
acquisition. `TaskDispatchPolicy` chooses one path from the fixed
`TaskDescriptor.workerAllocationMechanism`: cached candidate renewal for
Task-owned rules or on-demand acquisition for Item-owned rules. Both paths
carry raw Score evidence opaquely by
usage: they may associate and return it to exact Owner operations but never
decode or calculate it. Neither path clears dirty or releases rejected
candidates.

## Dirty Fence

Dirty means only:

```text
an active Worker lease exists
and a score-owner caller has explicitly invalidated renewal of that fence
```

It is not scheduling-serviceability polarity, metadata version, global Worker
status, or an
attribute update lock. Non-lease owners may set dirty but never clear it.
Allocation may clear dirty only while acquiring a due Worker. Worker Matching
facts updates do not write Worker score and do not set dirty in this cut.
Active cached renewal rejects dirty and forces later allocation to obtain new
matching evidence and a new lease.

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
| Match admission | hold CAS lost | exclude Worker from later Evidence use |
| Allocation | unmatched or publication failure | retain short lease until expiry |
| Cached candidate renewal | dirty/recovery/expired/stale fence or missing descriptor | consume candidate, do not claim Item |
| Item dispatch | Item absent or claim lost | no release; leases expire |
| Task Dispatch | mailbox residue replaced | publish the new lease-backed Seed; optional bounded residue metric |
| Task Dispatch | append failed or result ambiguous | no compensation; claim and lease expiry recover |
| Worker Delivery Dispatch | expired or malformed Seed | drop; no synthetic result |
| Polling Worker | unsupported EventCode, handler failure, or execution failure after entry | emit a Worker-owned `3...` outcome |
| Long-lived Adapter | command expired before execution entry | may emit `WorkerDeliveryAdapterErrorCode.COMMAND_EXPIRED` through Adapter batch ingress |
| Worker Delivery Dispatch | response lost, process crash, or no result evidence | `UNKNOWN`; claim and lease expiry recover |
| Result | `200` or Worker failure | exact release |
| Result | Adapter rejection | exact release; no online inference |
| Result | malformed/missing evidence | no guessed mutation; expiry recovers lease eligibility only |

No branch adds a repair scanner, compensation queue, distributed lock, or
cross-owner transaction.

## Owner Matrix

| Owner | Responsibility | Refusal |
| --- | --- | --- |
| WorkerScoreCore | score encoding, scans, exact lease, dirty fence, release and polarity mechanics | no Task policy, transport or result subcode parsing |
| WorkerRuntime | minimal declaration validation, endpoint metadata and first score initialization | no Properties, matching, heartbeat or dispatch ownership |
| WorkerMatchingRuntime | persistent Rule/Properties interpretation and bounded identity-only Evidence | no HOT/Cache scheduling source, priority, uniqueness, Score, lease, endpoint or Candidate Cache access |
| WorkerCandidateSelectionPolicy | due HOT identity source, exact Match hold and confirmation, cached renewal, request priority/count/unique selection and terminal Candidate assembly | no Property/Constraint interpretation, Score decoding, construction or arithmetic |
| TaskWorkerAllocationPolicy | consume verified RUNNING Task evidence, read bounded Candidate counts, compute deficits, consume held Match evidence and publish the next held Demand pool | no Task discovery, Task-score write or result handling |
| TaskAssignmentDispatcher | exact Worker fence renewal, Item claim, ResultContext/Command construction and publication | no Item observation, expiry, pairing, limit, idle or pacing policy |
| TaskIdleSettlement | complete ACTIVE check, ordinary pacing, exact close/park and post-park repair | no Item selection, Worker acquisition or Command publication |
| TaskDispatchPolicy | bounded Task/Item observation, expiry/exhaustion, pairing, per-Task limit, ordinary pacing and idle-disposition choice | no Score decoding, construction or arithmetic |
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
- Do not let cached candidate renewal fall back to item-rule on-demand acquisition.
- Do not let `TaskDispatchPolicy` bypass `WorkerCandidateSelectionPolicy` to
  access Worker Score or Candidate Cache, or bypass `TaskAssignmentDispatcher`
  to claim Items and publish Commands. Direct bounded Owner calls inside the
  policy that owns that exact decision are allowed; Score remains opaque by
  usage.
- Do not release rejected dispatch candidates as compensation.
- Do not treat missing result as Adapter rejection.
- Do not let old result evidence mutate a newer Worker lease.
- Do not create separate Attempt, reservation, session epoch or lease registry.
- Do not release a Worker fence after DeliveryCommand append to simulate
  immediate slot reuse.
- Do not assign independent TaskItems or different Tasks concurrently to one
  WorkerId.
