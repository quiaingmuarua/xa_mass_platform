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
  ON_DEMAND  -> explicit IDs, indexed identities plus post-hold recheck, or ANY
  -> final exact Worker confirmation -> exact Item claim -> Command publication
  -> opaque ResultContext/WorkerLeaseReference -> exact result disposition
```

The Kernel observes only bounded due HOT scores and returns the exact opaque
observations to the Score Owner. A successful initial hold preserves rank,
writes a future coordinate and clears dirty. Concurrent observations do not
create concurrent leases: only the exact CAS winner holds the Worker. Optional
Serviceability eligibility filtering is owned by the Score operations.

[Allocation Policy](../../../kernel_pacer_jvm/doc/dispatch/task-worker-allocation-pacer.md)
holds the PRECOMPUTED pool before publishing Demand. Matching receives ordered
Task IDs, held Worker IDs and opaque scores, then appends accepted
entries through the Candidate Cache Owner. It removes only Cache-accepted IDs
from that Demand's available pool. It cannot decode, compare, renew or release
the held scores, and receives no endpoint or Item state.

ON_DEMAND keeps one selector: explicit IDs and ANY use Kernel mechanics directly;
property conditions pass unchanged to Matching for bounded identities and
post-hold membership recheck. It uses no PRECOMPUTED Demand or Candidate Cache. The
[Assignment Policy](../../../kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md)
owns deficits, priority, pairing and round uniqueness. Neither a cached miss nor
a stale candidate switches allocation mechanism.

Unmatched, unselected, Cache-rejected and Demand-rejected holds expire
naturally. Queue rejection is not a reason to add compensation release or a
pending-lease registry. Actual Properties changes request score-local dirty
invalidation after the facts write; Candidate entries remain until consumption
or expiry. Their original fence cannot pass final confirmation after invalidation.

## Confirmation Before Claim

Task Dispatch obtains endpoint-bearing candidates, then its exact assignment
closure confirms supplied clean, active, non-PAUSE HOT fences. One CAS requires
the entire original score, retains or extends its deadline, and sets dirty=1.
Even a hold that already covers the requested deadline must transition: there
is no successful NOOP. Dirty, expired, negative or stale observations cannot
proceed to Item claim. One initial fence can be consumed only once.

Only a TRANSITIONED confirmation with a returned score participates in the Item
claim batch. Only claimed Items become Commands. The returned execution fence,
not the initial held score, is encoded into ResultContext and carried opaquely
by delivery. Item claim or Command append failure does not compensate-release
the Worker: independent lease and claim expiry restore scheduling eligibility.

Policy may retain, associate, exact-compare and return raw Score evidence but
must not decode or calculate it. Primitive preconditions and status results
are maintained once in the
[Score primitive contract](worker-score-band-scheduling.md#score-primitives).

## Dirty Fence

Dirty=0 means the current initial hold's candidate eligibility is available;
dirty=1 means it has been invalidated or consumed. Dirty is not a Properties
version, network state, scheduling polarity or attribute write lock.

Server requests one bounded dirty operation per Group after APPLIED Worker or
Platform facts writes. The operation preserves sign, deadline and rank and does
not create missing members. An already confirmed execution fence is dirty=1,
so subsequent Properties invalidation is a NOOP and preserves result release.

Facts and Score commit independently. Confirmation may win between the facts
write and invalidation; already confirmed work continues. Invalidation failure
keeps the successful facts response and emits an aggregate diagnostic, with no
replay guarantee. An UNCHANGED retry does not repeat invalidation. Old held
scores and Cache entries remain bounded by their existing deadlines.

Due scans include dirty=1. Only a new exact initial HOT hold clears dirty;
PRECOMPUTED then matches again. ON_DEMAND uses the same confirmation fence but
asks Matching to recheck indexed identities after hold using the same property selector.
ANY and explicit IDs require no Matching call. Do not clear an active hold or fetch a newer score to
rescue a stale Candidate. Cache counts may temporarily include invalid entries.

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
| Confirmation | dirty, expired, negative, stale or missing candidate | Do not claim the Item; no fallback acquisition |
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

## Deployment

No Redis shape or data migration is required. Coordinate the Server/Pacer
restart so old and new assignment code do not run concurrently in one scope.
An old dirty=0 execution lease can lose its early-release fence if invalidated
after upgrade; existing lease expiry provides the accepted best-effort recovery.
Do not rewrite historical scores or add an upgrade repair process.
