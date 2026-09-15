# Worker HOT_ACQUIRE Lease Protocol

Status: active Java Kernel HOT lease mechanism contract.

This document owns the opaque Worker fence across initial hold, assignment and
result disposition. [Worker Score](worker-score-band-scheduling.md) owns its
encoding, primitive validation and atomic transitions; this protocol adds no
reservation store, attempt lifecycle or lease registry.

## One Worker Slot

One WorkerId is one scheduler-visible execution slot with one Score. A physical
executor with parallel capacity exposes multiple logical WorkerIds. One lease
carries one TaskItem and one DeliveryCommand; business batching stays inside
that Item's payload. Never release a fence after publication to simulate early
slot reuse or assign independent Items behind the same Worker lease.

## Core Mechanism Change: Lease Before Matching

**Pacer acquires the 1-second candidate lease before Matching reads eligibility.**
It supplies only the successful fences from a closed Group observation batch.
Matching qualifies those IDs and retains their original fences and deadlines;
processing and inventory waiting share the same second. Unmatched candidates also
hold that short lease until expiry. Kernel supply authority, Score encoding and
exact execution confirmation remain. There is no Matching acquisition callback,
5-second inventory lease or inventory renewal operation.

## Acquisition And Handoff

```text
NORMAL bindings -> local Group deficits
  -> Pacer read-only due HOT head, including dirty=0 and dirty=1
  -> one Kernel exact acquisition, 1 second, dirty=0 -> supplied successful fences
  -> Matching supplied-ID current projection and acceptance plan -> shared inventory
  -> local take -> exact Worker confirmation, execution fence, dirty=1
  -> exact Item claim -> Command -> ResultContext -> exact result disposition
```

The Score Owner preserves rank and clears dirty when exact-acquiring a due HOT
observation. Both observed dirty values are legal; the entire score must still
match. Concurrent callers using the same observation have at most one winner.
An occupied, paused, negative, missing or changed score cannot be acquired.
Pacer computes the target as its current time plus 1 second immediately before
that Group's acquisition call. Only TRANSITIONED results with new fences are
offered. Matching cannot reset the deadline, renew the lease or acquire a substitute.
It filters expired offers before projection and again before stock insertion.

**Core scheduling change: refill always observes the due head from the optional
HOT floor, in ascending Score/member order, without a within-Group offset.** One
read-only Lua uses Redis TIME and ZRANGE BYSCORE LIMIT 0 limit WITHSCORES. Acquisition
moves successful candidates out of the due range before Matching, including those
that will not match. Expiry retains their newer Score, so older unacquired Workers
remain ahead. Observation alone does not advance the head. Group rotation remains
Pacer policy. Serviceability independently reads bounded heads and advances next-check times.

Limit is 1..100 raw rows. The immutable result omits corrupt scores without fetching
replacements, repairing data or changing its order. A fully corrupt head returns
empty; no cross-round bypass of those rows is promised. Concurrent Score changes
remain subject to exact acquisition, without a same-round rescan or stable snapshot.

Acquisition and confirmation each check Redis TIME inside the same bounded Lua
as exact comparison and write, once per at most 100 identities on a Group key.
No preceding time confirmation read is used. Existing 100ms semantics apply:
acquisition requires slot < nowSlot, while active confirmation allows equality.
Targets must be later than nowSlot. The operations return individual results;
Properties and other Owners remain independent commits.

TaskItems only consume successfully acquired inventory. Counts and take read no
Worker Score. No match or projection failure leaves the already acquired hold to
expire. Ambiguous acquisition, failed insertion and unused inventory also leave holds to
expire; there is no periodic renewal, compensation release or restart adoption.

## Confirmation Before Claim

**Core evidence boundary change:** a Score in the current 100ms slot, as well as
a future slot, accepts validated network evidence to correct its polarity.
This matches the current-slot lease confirmation boundary. Evidence preserves
the time coordinate, rank and dirty; it cannot release a lease or undo PAUSE.
A disconnect committed before confirmation makes the original HOT fence stale
unless subsequent available evidence changes the polarity again. If confirmation
wins first, a later disconnect preserves its execution fence's magnitude and
dirty bit; the existing exact result disposition still applies.

This is best-effort observation, not strict network ordering. An older valid
report may change polarity within the current slot, including a RECOVERY check
coordinate. Once the stored slot is in the past, its existing evidence freshness
check still applies. No evidence replay or broader probe scan is introduced.

Task Dispatch obtains endpoint-bearing candidates, then its exact assignment
closure confirms supplied clean, active, non-PAUSE HOT fences. One CAS requires
the entire original score, retains or extends its deadline, and sets dirty=1.
Even a hold that already covers the requested deadline must transition: there
is no successful NOOP. Dirty, expired, negative or stale observations cannot
proceed to Item claim. One inventory fence can be consumed only once.

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
scores remain bounded by their existing deadlines.

Dirty invalidation marks every existing valid score, including due and recovery
coordinates; it is not restricted to active leases. Release preserves dirty and
expiry does not rewrite it. Due scans therefore include dirty=1. Only a new exact
initial HOT acquisition clears dirty; confirmation consumes clean eligibility.

Pacer acquisition clears dirty before Matching reads current membership. After
acquisition, a successful dirty invalidation rejects that candidate at confirmation,
even if it follows the projection read; admission performs no second acquisition
that could clear it. Facts and dirty are still independent: a projection-to-facts
race or failed invalidation is not repaired by a version or transaction. Default
identity selectors need no facts. Never fetch a newer score
to rescue a stale candidate or clear an active execution hold. There is no
per-Task Candidate Cache to invalidate or repair.

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
| First acquisition | CAS lost | Do not supply the Worker to Matching |
| Qualification | no match or projection failure | No new stock; acquired leases expire naturally |
| First acquisition | response loss or insertion failure | Only returned new fences enter stock; committed holds expire |
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

No HTTP, Binding, Redis key or Score encoding migration is required. Restart the
existing Server/Pacer process; local inventory is discarded and previous holds
expire. No data cleanup, historical rewrite or upgrade repair process is needed.
