# Worker HOT_ACQUIRE Lease Protocol

Status: active Java Kernel HOT lease mechanism contract.

This document owns the opaque Worker fence across allocation, assignment and
result disposition. [Worker Score](worker-score-band-scheduling.md) owns its
encoding, primitive validation and atomic transitions; this protocol adds no
reservation store, attempt lifecycle or lease registry.

## One Worker Slot

One WorkerId is one scheduler-visible execution slot with one Score. A physical
executor with parallel capacity exposes multiple logical WorkerIds. One lease
carries one TaskItem and one DeliveryCommand; business batching stays inside
that Item's payload. Never release a fence after publication to simulate early
slot reuse or assign independent Items behind the same Worker lease.

## Acquisition And Handoff

```text
due HOT observation -> exact initial hold and dirty clear
  PRECOMPUTED -> ordered Match Demand -> accepted Candidate Cache entry
  ON_DEMAND  -> normalized explicit Worker ID or ANY selection
  -> final exact Worker renewal -> exact Item claim -> Command publication
  -> opaque ResultContext/WorkerLeaseReference -> exact result disposition
```

The Kernel observes only bounded due HOT scores and returns the exact opaque
observations to the Score Owner. A successful initial hold preserves rank,
writes a future coordinate and clears dirty. Concurrent observations do not
create concurrent leases: only the exact CAS winner holds the Worker. Optional
Serviceability eligibility filtering is owned by the Score operations.

[Allocation Policy](../../../kernel_pacer_jvm/doc/dispatch/task-worker-allocation-pacer.md)
holds the PRECOMPUTED pool before publishing Demand. Matching receives ordered
Candidate addresses, held Worker IDs and opaque scores, then appends accepted
entries through the Candidate Cache Owner. It removes only Cache-accepted IDs
from that Demand's available pool. It cannot decode, compare, renew or release
the held scores, and receives no endpoint or Item state.

ON_DEMAND selection directly acquires normalized Worker IDs/ANY without a
Matching round trip or Candidate Cache. The
[Assignment Policy](../../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md)
owns deficits, priority, pairing and round uniqueness. Neither a cached miss nor
a stale candidate switches allocation mechanism.

Unmatched, unselected, Cache-rejected and Demand-rejected holds expire
naturally. Queue rejection is not a reason to add compensation release or a
pending-lease registry. Properties updates do not revoke Candidate entries;
expiry and exact renewal bound their use.

## Renewal Before Claim

Task Dispatch obtains endpoint-bearing candidates, then its exact assignment
closure renews the supplied clean, active HOT fences. An exact NOOP that covers
the requested deadline is valid; a transition returns the renewed fence. Dirty,
expired, negative or stale observations cannot proceed to Item claim.

Only a successful renewal result participates in the Item claim batch. Only
claimed Items become Commands. The returned Worker fence is encoded into
ResultContext and carried opaquely by delivery. Item claim or Command append
failure does not compensate-release the Worker: independent lease and claim
expiry restore scheduling eligibility.

Policy may retain, associate, exact-compare and return raw Score evidence but
must not decode or calculate it. Primitive preconditions and status results
are maintained once in the
[Score primitive contract](worker-score-band-scheduling.md#score-primitives).

## Dirty Fence

Dirty invalidates renewal of an active lease; it is not a metadata version,
network state, scheduling polarity or attribute write lock. A real continuation
must justify a dirty producer. Non-lease owners never clear dirty. Initial due
acquisition may clear it, while active renewal rejects it. Matching facts
updates do not write Score or mark dirty.

## Result Disposition

[Result Policy](../../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)
parses the returned context and publishes bounded semantic events. It does not
call WorkerScoreCore directly. The Worker execution event Owner unwraps the
opaque WorkerLeaseReference and applies the completed-HOT exact release.

The release accepts only the exact returned HOT lease or its exact
sign-flipped RECOVERY counterpart. The latter may be restored and released
atomically by the Score Owner. No newer or otherwise changed fence is released.
This mechanical counterpart rule is not an inference that the Worker is online.
Adapter evidence classification remains with the independent Serviceability
policy and its dedicated event port.

Worker success/failure and trusted Adapter pre-execution rejection submit their
own lease evidence. There is no cross-class winner registry. Conflicting
logical outcomes do not justify a guessed mutation; duplicate or late evidence
must still satisfy the exact Owner fence. Result disposition is independent of
TaskItem movement and cannot prove that all preceding Owner calls completed.

## Failure Boundaries

| Stage | Failure | Existing behavior |
| --- | --- | --- |
| Initial hold | CAS lost | Exclude the Worker from that held pool |
| Match handoff | rejection, no match or partial publication | Accepted entries remain; unaccepted holds expire |
| Renewal | dirty, expired, negative, stale or missing candidate | Do not claim the Item; no fallback acquisition |
| Claim/publication | claim lost, append failed or result ambiguous | No compensation; independent fences expire |
| Delivery | destructive consume or process/send loss | UNKNOWN is not trusted pre-execution rejection |
| Result | missing or malformed context | No guessed mutation; expiry restores eligibility only |
| Result | duplicate, late or conflicting fence | Only an exact applicable Owner transition can change Score |

The [delivery boundary](../../../doc/kernel/worker-delivery-dispatch.md) owns
network failure windows; the
[Result storage contract](../runtime-redis/task-result-runtime-redis-shape.md)
owns the independent storage/finality interruption windows. Lease expiry does
not replay evidence or repair a stored Result's separate finality transition.

## Serviceability Boundary

HOT and RECOVERY are Kernel scheduling eligibility, not physical connection
state. The [Serviceability Policy](../../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
interprets Adapter evidence and probe results through its dedicated time-fenced
operation. It must not be collapsed into Task result lease disposition.
Cadence, recovery ranking and cold parking belong to that policy; no generic
Session, Attempt or Worker reservation owner is introduced here.
