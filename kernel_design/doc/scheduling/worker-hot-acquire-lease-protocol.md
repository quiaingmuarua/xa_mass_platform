# Worker HOT_ACQUIRE Lease Protocol

Status: active target cross-pacer mechanism contract; allocation lease and
result-side exact release are implemented in the Python executable spec;
dispatch-time lease disposition is not yet implemented.

This is the canonical cross-pacer contract for Worker `HOT_ACQUIRE` leases.
The score encoding and transition primitives remain owned by
[Worker Score-Band Scheduling](worker-score-band-scheduling.md). Allocation,
TaskItem dispatch, outbound delivery, and result routing link here instead of
defining competing lease lifecycles.

## Purpose

A Worker lease protects one bounded scheduling continuation:

```text
due HOT Worker
  -> short allocation lease
  -> matched CandidateWorkerEntry
  -> dispatch-time lease disposition
     -> non-exclusive: release after accepted DeliverSeed
     -> exclusive: retain through the attempt deadline
  -> result-side exact release or natural expiry
```

The protocol prevents three failures:

```text
two allocation rounds concurrently admitting the same Worker observation
dispatch continuing from stale matched evidence after the Worker score changed
an old or duplicate result releasing a newer Worker lease
```

It is not a Worker lifecycle, transport-session, execution-result, or generic
capacity owner.

## Truth And Evidence

`WorkerScoreCore` owns the stored Worker score and every score mutation.
Scheduling pacers own only bounded orchestration and policy choices around its
narrow primitives.

```text
Worker score
  scheduling truth owned by WorkerScoreCore

observedScore / workerLeaseScore
  opaque exact-CAS fence for one score coordinate

CandidateWorkerEntry
  transient matched continuation carrying the opaque allocation fence

DeliverSeed
  already-assigned delivery evidence carrying opaque result correlation

SeedResult
  execution outcome evidence; not Worker score truth
```

No caller may decode, trim, reconstruct, or mint a Worker score. External
adapters must pass `opaqueResultContext` through unchanged and never invoke
Worker score operations.

## Which Scores May Be Leased

Only `HOT_ACQUIRE` participates in the assignment lease protocol:

```text
HOT_ACQUIRE due observation
  may be exact-CAS leased by allocation

HOT_ACQUIRE active clean lease
  may be exact-fence retained through a later cutpoint

RECOVERY_RECHECK
  may not pass either HOT lease operation
```

A Worker in `RECOVERY_RECHECK` must first pass owner-validated recovery and move
back to `HOT_ACQUIRE` before assignment.

Not every future positive score is an assignment lease. Manual hold, drain,
maintenance, cooldown, or another owner-approved same-polarity hold may also
use a future HOT coordinate. A score is an assignment lease only when a real
bounded assignment continuation holds its opaque full-score fence.

Lease and release preserve `HOT_ACQUIRE` polarity. A future time coordinate
makes the Worker temporarily unavailable to HOT acquisition; natural clock
advance makes it due again without another write. Exact release is the declared
exception that may lower the time coordinate while preserving polarity,
`laneRank`, and `dirty`.

## Allocation Lease

[Task-Worker Allocation Pacer](task-worker-allocation-pacer.md) owns the first
lease phase:

```text
acquire bounded due HOT observations
  -> batch independent exact observed-score CAS leases
  -> pass only lease-success Worker ids to matching
  -> publish matched Worker ids with their lease fences
```

Rules:

- The allocation lease is short and bounded. It covers Worker validation,
  matching, candidate publication, and dispatch handoff rather than handler
  execution.
- The candidate collection expiry must not be later than the Worker lease
  deadline. The built-in first policy uses the same deadline.
- Allocation leases every unchanged bounded observation before matching.
  Matching never receives unleased Worker ids.
- An unmatched Worker consumed one bounded matching opportunity. Allocation
  does not release it immediately; bounded lease expiry prevents immediate
  repeated matching pressure.
- Matcher failure, candidate-publication failure, or ambiguous publication does
  not trigger compensation release. The lease expires naturally.
- Only matched lease fences enter `CandidateWorkerEntry`. Raw due observations
  and lease deadlines do not cross the matcher interface.

The exact duration is policy. A first deployment may use a value such as ten
seconds, but the mechanism requires only that it be long enough for the bounded
handoff and short enough that abandoned continuations do not materially consume
Worker availability.

## Dispatch-Time Lease Disposition

[Task Item Dispatch Pacer](task-item-dispatch-pacer.md) owns the cutpoint that
turns matched candidate evidence into queued delivery evidence. It does not own
Worker score encoding or lease truth; it invokes Worker-owner primitives.

The dispatch sequence is:

```text
consume CandidateWorkerEntry
  -> claim one TaskItem
  -> classify exclusive versus non-exclusive disposition from Task/admission policy
  -> confirm the candidate lease is still active, clean, and exact
  -> create DeliverSeed with the confirmed lease fence in opaqueResultContext
  -> append one endpoint-manager-local DeliverSeed batch
  -> apply the disposition for that confirmed batch
```

Exclusive versus non-exclusive is a Task/admission policy decision. It must not
be inferred by an adapter, handler result, transport outcome, arbitrary payload
field, or raw Worker score.

### Required Active-Fence Semantics

Dispatch needs a batched Worker-owner operation with this meaning:

```text
retain active HOT lease through requiredUntilMillis

storedScore != observedScore
  -> STALE

polarity != HOT_ACQUIRE
  -> rejected

dirty == 1
  -> STALE; discard the continuation and require fresh matching

active clean lease already covers requiredUntilMillis
  -> success without extending; return the current fence

active clean lease does not cover requiredUntilMillis
  -> exact-CAS extend; return the new fence
```

The caller cannot decide whether extension is necessary because the score is
opaque. The current executable `renew_active_hot_score_leases` primitive covers
exact-CAS extension only; implementation convergence must give the existing
owner surface the required retain-through semantics rather than add a parallel
wrapper path.

The returned fence, whether unchanged or extended, is the
`workerLeaseScore` written into `opaqueResultContext`.

### Non-Exclusive Assignment

Non-exclusive work does not hold the Worker score for the handler execution
duration:

```text
confirm active clean exact allocation fence
  -> append DeliverSeed batch
  -> when that batch is definitely accepted, exact-release its Worker fences
```

Rules:

- Release happens only after `DeliverSeedRuntime.append_deliver_seeds` returns
  definite success for that endpoint-manager batch.
- Seed construction is not acceptance. A queue exception or ambiguous command
  result must not release the Worker because the Seed may already be visible.
- Successfully appended batches may be released even when a later independent
  endpoint-manager batch fails.
- The same confirmed fence remains in `opaqueResultContext`. Result routing may
  attempt exact release again: an already released fence is stale and harmless,
  while a failed early release may still be completed by the result path.
- If no trustworthy result arrives, the original bounded lease remains the
  liveness fallback.

### Exclusive Assignment

Exclusive work retains the Worker through the attempt deadline:

```text
retain active clean exact allocation fence through attemptUntilMillis
  -> create DeliverSeed with the returned execution fence
  -> append DeliverSeed batch
  -> exact release on trusted result or natural expiry
```

Rules:

- Retain/extension must complete before the DeliverSeed becomes visible.
- A dirty, stale, expired, or non-HOT fence produces no DeliverSeed for that
  Worker/Item pair.
- If the Item was already claimed when retain fails, the Item claim recovers
  through its own deadline; dispatch does not invent rollback.
- If retain succeeds but queue append fails or is ambiguous, dispatch does not
  release. The execution lease expires naturally.
- Result correlation must carry the returned execution fence, never the older
  allocation fence.

## Attempt Deadline

Exclusive Worker retention, TaskItem claim, and stale-seed rejection share one
absolute attempt deadline:

```text
attemptUntilMillis
  = TaskItem claim deadline
  = required exclusive Worker lease deadline
  = DeliverSeed submit cutoff
```

This window includes assignment-side queueing and handler execution. It does
not need to include normal result-routing queue delay: once the handler has
produced its result, the Worker has finished the attempt. A deployment may add
only a bounded, named allowance for termination or clock uncertainty; it must
not extend every Worker lease conservatively without an invariant.

Non-exclusive work uses the same Item claim and DeliverSeed cutoff but does not
retain the Worker score through the attempt deadline.

## Result-Side Release

[Result-Routing Scheduling](result-routing-scheduling.md) attempts exact Worker
release for every successfully decoded result, independently of Item outcome:

```text
Item success
  -> request Item final-success movement
  -> exact-release Worker fence

Item failure
  -> request Item retry/final-failure movement
  -> exact-release Worker fence

Item transition STALE or NOOP
  -> still attempt exact Worker release
```

The exact fence makes repeated release safe:

```text
non-exclusive dispatch already released the lease
  -> result-side release is stale / no-op

duplicate or late result carries an old fence
  -> cannot release a newer Worker lease

dispatch early release failed but the old fence is still current
  -> result-side release may complete it
```

A non-`200` Item outcome means that attempt failed; it does not by itself prove
that the Worker is unavailable. Confirmed Worker unavailability belongs to the
Worker owner and the `RECOVERY_RECHECK` polarity path, not ordinary result-side
HOT release.

Missing Task/Worker correlation, malformed context, or an internal routing
failure does not authorize a guessed release. Natural expiry remains the
fallback.

## Failure Matrix

| Stage | Evidence | Lease action |
| --- | --- | --- |
| Allocation | lease CAS lost | exclude Worker; no release |
| Allocation | unmatched Worker | keep short lease until expiry |
| Allocation | matcher/publication failure or ambiguity | keep short lease until expiry |
| Dispatch | candidate has no claimable Item | consume candidate; short lease expires |
| Dispatch | active-fence retain is stale, dirty, expired, or wrong polarity | do not emit Seed; deadlines recover |
| Dispatch | non-exclusive Seed batch definitely accepted | exact-release confirmed fence |
| Dispatch | exclusive Seed batch definitely accepted | keep execution lease until result/expiry |
| Dispatch | queue append failed or is ambiguous | do not compensate; lease expires |
| Outbound | stale Seed dropped, transport rejected, or no result | adapter does not mutate score; lease expires |
| Result routing | valid success or failure result | exact-release correlated fence |
| Result routing | duplicate/late stale fence | no overwrite of current score |
| Result routing | malformed/missing correlation | skip guessed release; lease expires |

No failure branch adds a repair scanner, compensation queue, distributed lock,
or cross-owner transaction.

## Dirty Fence Interaction

`dirty` protects the continuation between matching and dispatch disposition:

```text
allocation exact lease
  -> matching records Worker evidence
  -> relevant scheduling dependency changes
  -> non-lease owner may mark the current lease dirty
  -> dispatch retain returns STALE
  -> old candidate is discarded; later allocation revalidates
```

Dirty does not interrupt work after the DeliverSeed cutpoint:

- Non-exclusive work has already released its Worker lease.
- Exclusive work may remain held until result or timeout, but metadata changes
  do not cancel the running handler through the score bit.
- The next assignment validates current metadata again.

Non-lease owners may set dirty but never clear it. Fresh HOT allocation may
clear dirty as part of exact leasing before current matcher validation. Active
dispatch retention must reject dirty rather than clear it.

## Capacity Boundary

Non-exclusive does not mean unlimited concurrency. It means only that the
Worker score lease is not the execution-duration capacity primitive.

```text
non-exclusive concurrent capacity
  -> Worker attributes / bounded dynamic attributes / admission policy /
     worker-local backpressure

exclusive capacity = one scheduler-visible identity at a time
  -> retain the Worker score through attemptUntilMillis
```

If one Worker needs a hard `maxConcurrency = N` invariant, model explicit
WorkerSlot identities or a named capacity-token owner. Do not pretend that one
binary Worker score lease expresses N slots, and do not keep every
non-exclusive lease open merely to approximate running-count truth.

## Owner Matrix

| Owner | Responsibility | Refusal |
| --- | --- | --- |
| WorkerScoreCore / worker-runtime | score encoding, bounded HOT query, exact lease/retain/release, dirty/stale validation | no Task policy, Item claim, queue write, transport, or result classification |
| TaskWorkerAllocationPacer | short lease duration, bounded lease batch, match and candidate publication | no unmatched compensation release, execution lease, or result handling |
| TaskItemDispatchPacer / admission policy | exclusive classification, active-fence retain request, DeliverSeed cutpoint, non-exclusive early release request | no score decoding, Worker discovery, adapter resolution, or result finality |
| DeliverSeedRuntime | accepted queue append/consume | no lease meaning or score mutation |
| External adapter | selected-Worker delivery and SeedResult evidence | no score parsing, retain, release, or timeout compensation |
| ResultRoutingPacer | decoded-result exact release request independent of Item outcome | no Worker selection, lease renewal, or guessed release |

## Executable-Spec Status

Implemented now:

```text
bounded due HOT observation
batch exact-CAS allocation lease
lease-success-only matching
unmatched natural expiry
CandidateWorkerEntry workerLeaseScore propagation
WorkerScoreCore active renewal primitive
DeliverSeed opaque result-context propagation
result-side exact release for all decoded outcomes
lease expiry as liveness fallback
```

Not implemented yet:

```text
Task/admission exclusive disposition available to TaskItem dispatch
dispatch-time active clean exact-fence retain
retain-through NOOP when the current lease already covers the required deadline
non-exclusive exact release after confirmed DeliverSeed append
exclusive attempt-deadline retention
propagation of the retained execution fence instead of the allocation fence
focused dispatch proof for both disposition branches
```

Until those gaps close, the current Python `TaskItemDispatchPacer` still copies
the allocation fence into `opaqueResultContext`, and result routing or natural
expiry is the only implemented release path. This status must not be presented
as the target lease protocol.

## Guardrails

- Do not lease `RECOVERY_RECHECK` through HOT primitives.
- Do not let matcher, candidate runtime, DeliverSeed runtime, Redis codecs, or
  external adapters decode Worker scores.
- Do not publish an exclusive DeliverSeed before the active clean exact fence
  is retained through its attempt deadline.
- Do not release a non-exclusive Worker before its DeliverSeed batch is
  definitely accepted.
- Do not release unmatched leases or ambiguous writes as compensation.
- Do not let an old result release a newer Worker lease; every release uses the
  complete correlated fence.
- Do not let active renewal clear dirty.
- Do not derive exclusive policy from arbitrary Item payload or transport
  behavior.
- Do not create a second lease registry, assignment lifecycle, ack queue,
  timeout scanner, or distributed transaction around this protocol.
- Do not describe lease expiry as a scheduled write. Time makes the stored
  coordinate due again.
