# Worker HOT_ACQUIRE Lease Protocol

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Parent contract: [Worker Score-Band Scheduling](worker-score-band-scheduling.md).
Related pacers:
[Task Worker Allocation Pacer](task-worker-allocation-pacer.md),
[TaskItem Dispatch Pacer](task-item-dispatch-pacer.md), and
[Result-Routing Scheduling](result-routing-scheduling.md).

## Purpose

One opaque Worker score fence protects the bounded continuation from Worker
allocation through dispatch and result disposition:

```text
due HOT observation
  -> exact allocation lease and dirty clear
  -> Worker matcher validation
  -> CandidateWorkerEntry(workerLeaseScore)
  -> dispatch exact validate/renew
  -> ResultContext(workerLeaseScore)
  -> result exact release or RECOVERY_RECHECK demotion
```

There is no separate reservation store, attempt lifecycle, lease token model,
or transport-owned Worker state.

## Allocation Lease

```text
acquire_hot_acquire_candidates(workerGroupId, limit)
  -> workerId -> opaque observedScore

acquire_observed_hot_score_leases(
    workerGroupId,
    observedScores,
    targetTimeMillis,
)
  -> independent exact-CAS results
```

Only due positive HOT scores may be leased. Successful acquisition preserves
laneRank, writes a future time coordinate, and clears dirty before the current
matcher reads Worker metadata. Concurrent rounds may scan the same Worker, but
only one exact observed-score CAS succeeds.

Unmatched Workers, matcher failures, and candidate publication failures are not
actively released. Their short leases expire naturally, preventing immediate
hot-loop rematching from the same evidence.

## Dispatch Validation

Before claiming a TaskItem, dispatch calls:

```text
renew_active_hot_score_leases(
    workerGroupId,
    observedScores,
    taskItemClaimUntilMillis,
)
```

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
Item claim. The returned fence, unchanged or renewed, is written into
`opaqueResultContext`.

Dispatch never decodes scores, clears dirty, or releases rejected candidates.

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

Connect/reconnect uses the same fence deliberately:

```text
existing score
  -> preserve timeSlot and laneRank
  -> converge to HOT_ACQUIRE
  -> set dirty=1
```

This invalidates candidate evidence created before reconnect without releasing
future holds or introducing a session epoch.

## Result Disposition

The opaque Worker fence returns through `ResultContext`:

```text
200 / 1xxx
  -> this attempt crossed the Worker execution boundary
  -> exact release preserving positive polarity

3xxx
  -> Adapter confirmed execution was not entered
  -> exact CAS from HOT_ACQUIRE to RECOVERY_RECHECK
```

The Adapter emits evidence only; it never mutates score. Result routing invokes
WorkerScoreCore and treats Worker disposition independently from Item movement.

For one exact lease, Worker execution evidence wins over adapter rejection.
Stale evidence cannot release or demote a newer lease.

## Serviceability Reconciliation And Recovery Demotion

```text
reconcile_worker_hot_acquire(workerGroupId, workerId)
```

- Missing score returns `STALE`; WorkerRuntime owns initialization.
- Positive clean becomes positive dirty.
- Negative becomes positive dirty.
- Positive dirty is `NOOP`.
- timeSlot and laneRank are preserved; future holds remain future.

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
manager observations are evidence. Reconnect upsert, execution evidence,
pre-execution rejection, and future recovery probes make the kernel
classification converge when scheduling or recovery work requires it. With no
demand, bounded classification lag is allowed.

## Failure Matrix

| Stage | Evidence | Action |
| --- | --- | --- |
| Allocation | lease CAS lost | exclude Worker |
| Allocation | unmatched or publication failure | retain short lease until expiry |
| Dispatch | dirty/recovery/expired/stale fence | consume candidate, do not claim Item |
| Dispatch | Item absent or claim lost | no release; leases expire |
| Dispatch | queue append failed or ambiguous | no compensation |
| Adapter | expired or malformed seed | drop; no synthetic result |
| Adapter | Worker/handler unavailable | emit `3xxx` |
| Adapter | Worker execution failure | emit `1xxx` |
| Result | `200/1xxx` | exact release |
| Result | `3xxx` | exact RECOVERY_RECHECK demotion |
| Result | malformed/missing evidence | no guessed mutation; expiry recovers |

No branch adds a repair scanner, compensation queue, distributed lock, or
cross-owner transaction.

## Owner Matrix

| Owner | Responsibility | Refusal |
| --- | --- | --- |
| WorkerScoreCore | score encoding, scans, exact lease, dirty fence, release and polarity mechanics | no Task policy, transport or result subcode parsing |
| WorkerRuntime | declaration validation, first score initialization and trusted reconnect reconciliation | no heartbeat or dispatch ownership |
| TaskWorkerAllocationPacer | bounded lease, match and candidate publication | no result handling or compensation release |
| TaskItemDispatchPacer | active fence validation/renewal before Item claim | no Worker discovery, rematch or release |
| External Adapter | local final-hop observation and execution evidence | no score parsing or mutation |
| ResultRoutingPacer | outcome class routing to Item and Worker owners | no Worker selection or exact subcode policy |

## Deferred Policy

Recovery probe cadence/ranking and explicit capacity/concurrency mechanisms are
deferred. They must reuse the signed Worker score owner rather than create a
second scheduling-serviceability or lease truth.

## Guardrails

- Do not lease negative `RECOVERY_RECHECK` scores through HOT primitives.
- Do not expose score encoding, dirty bit, sign or timeSlot to callers.
- Do not let active renewal clear dirty.
- Do not release rejected dispatch candidates as compensation.
- Do not treat missing result as `3xxx`.
- Do not let old result evidence mutate a newer Worker lease.
- Do not create separate Attempt, reservation, session epoch or lease registry.
