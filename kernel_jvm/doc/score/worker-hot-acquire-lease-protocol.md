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
NORMAL Task descriptors -> local Group deficits
  -> Pacer read-only due HOT head, including mark=0 and mark=1
  -> one Kernel exact acquisition, 1 second, soft mark=0 -> supplied successful fences
  -> Matching supplied-ID current projection and acceptance plan -> shared inventory
  -> local take -> Worker transferObservedHotScoreLeases(seal=true), execution fence, mark=1
  -> exact Item claim -> Command -> ResultContext -> exact result disposition
```

The Score Owner establishes a soft hold when exact-acquiring a due HOT
observation. Both observed mark values are legal; the entire score must still
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

Acquisition and transfer each check Redis TIME inside the same bounded Lua
as admission and write, once per at most 100 identities on a Group key.
No preceding time confirmation read is used. Existing 100ms semantics apply:
acquisition requires slot < nowSlot, while active transfer allows equality.
The requested target slot must be later than nowSlot, even if the observed
lease already has a later deadline. Java prepares requestedSlot * 2 once per
batch. The two transfer scripts share active soft HOT validation and target
calculation: max(currentScore, requestedBase) + seal. Observed-score transfer
additionally requires exact equality; current-state transfer receives IDs only.
It rejects an expired request before reading the member. No pre-transfer read is
introduced, and a multi-chunk invocation adds no transaction or rollback.
Transfer checks soft/active state without a PAUSE parameter or special rejection.
Fixed entries share exact comparison and writing. The operations return
individual results; Properties and other Owners remain independent commits.

TaskItems only consume successfully acquired inventory. Counts and take read no
Worker Score. No match or projection failure leaves the already acquired hold to
expire. Ambiguous acquisition, failed insertion and unused inventory also leave holds to
expire; there is no periodic renewal, compensation release or restart adoption.

## Confirmation Before Claim

**Core evidence boundary change:** a Score in the current 100ms slot, as well as
a future slot, accepts validated network evidence to correct its polarity.
This matches the current-slot lease confirmation boundary. Evidence preserves
the time coordinate and mark; it cannot release a lease or undo PAUSE.
A disconnect committed before confirmation makes the original HOT fence stale
unless subsequent available evidence changes the polarity again. If confirmation
wins first, a later disconnect preserves its execution fence's magnitude and
mark bit; the existing exact result disposition still applies.

This is best-effort observation, not strict network ordering. An older valid
report may change polarity within the current slot, including a RECOVERY check
coordinate. Once the stored slot is in the past, its existing evidence freshness
check still applies. No evidence replay or broader probe scan is introduced.

Task Dispatch obtains endpoint-bearing candidates, then its exact assignment
closure selects one of the two transfer operations with seal=true. Matching's consumption result
contains workerId and an explicit expectedScore, without an inventory deadline.
All current production Pool Rules return their original nonzero score: one CAS
requires that entire score, retains or extends its deadline, and sets mark=1.
Even a hold that already covers the requested deadline must transition: there
is no successful NOOP. Sealed, expired, negative or stale observations cannot
proceed to Item claim. One inventory fence can be consumed only once.

An explicit expectedScore=0 is an identity hint consumed by Pacer. Pacer partitions
the batch: nonzero fences go to transferObservedHotScoreLeases; hint IDs go to
transferCurrentHotScoreLeases, without a score argument. Exact transfer rejects
zero as invalid. Current-state Lua must find a valid, active soft HOT member
before sealing; it can use a newer soft hold than an earlier
qualification observed. This is instantaneous eligibility, not a guarantee that
old qualification evidence remains valid. Missing/ineligible members return STALE;
corrupt members return INVALID without echoing an undecodable score or repairing it.
Two hint transfers, or a hint and a strict transfer, have at most one winner with
seal=true. A strict failure never retries as a hint. The production Rule registry
does not emit hints in this slice; Runtime Boundary owns the test-only end-to-end
witness. Kernel never interprets zero as a wildcard or mode selector.
Each nonempty partition uses its own bounded call. If the second call fails after
the first commits, the first holds remain; no Items are claimed by that failed
dispatch, no compensation or retry occurs, and the holds expire normally. The
two calls and multi-chunk operations do not constitute one transaction.

Only a TRANSITIONED confirmation with a returned score participates in the Item
claim batch. Only claimed Items become Commands. The returned execution fence,
not the initial held score, is encoded into ResultContext and carried opaquely
by delivery. Item claim or Command append failure does not compensate-release
the Worker: independent lease and claim expiry restore scheduling eligibility.

Policy may retain, associate, exact-compare and return raw Score evidence but
must not decode or calculate it. Primitive preconditions and status results
are maintained once in the
[Score primitive contract](worker-score-band-scheduling.md#score-primitives).

## Soft Transfer And Sealing

For current/future HOT, mark=0 means a speculative soft hold that another
caller may transfer through an observed-score or current-state operation; mark=1 means a sealed hold that cannot
transfer. Kernel stores no seal reason. A future sealed hold can be an execution
commit, a Properties invalidation or pause: observing it does not prove execution
or authorize a caller to claim an Item.

Both transfer operations with seal=false retain soft status and use the
later of the current and requested deadlines. If time is unchanged, Redis still
checks active and request-time conditions, plus exact equality for the observed-score
operation, before returning NOOP; no new
fence or successful transfer is implied. seal=true changes mark to 1 even at an
unchanged deadline. Actual transfer invalidates the previous fence, including a
copy still resident in Matching stock. That old copy cannot later commit execution
or release the new hold. Matching need not repair or remove it synchronously.
The interface is available for future callers; this change adds no allocator,
cache scan, preemption policy or scheduling loop.

Pause atomically writes MAX,1 while preserving polarity. MAX,0 follows ordinary
soft rules: soft transfer is NOOP; sealing transfer changes it to MAX,1. MAX,1
rejects transfer because sealed. PAUSED remains an observation name only. Explicit
exact release/resume may shorten time; transfer may never shorten it.

Server requests sealCurrentScoreHolds once per bounded Group after APPLIED Worker or
Platform facts writes. The operation preserves sign and deadline and does
not create missing members. An already confirmed execution fence is mark=1,
so subsequent Properties invalidation is a NOOP and preserves result release.

Facts and Score commit independently. Confirmation may win between the facts
write and invalidation; already confirmed work continues. Invalidation failure
keeps the successful facts response and emits an aggregate diagnostic, with no
replay guarantee. An UNCHANGED retry does not repeat invalidation. Old held
scores remain bounded by their existing deadlines.

Seal invalidation marks every existing valid score, including due and recovery
coordinates; it is not restricted to active leases. Release preserves mark and
expiry does not rewrite it. Due scans therefore include both marks. New exact
initial HOT acquisition establishes soft mark=0 regardless of the expired mark.

Pacer establishes the soft hold before Matching reads current membership. After
acquisition, successful sealing rejects that candidate at execution transfer,
even if it follows the projection read; admission performs no second acquisition
that could clear it. Facts and sealing are still independent: a projection-to-facts
race or failed invalidation is not repaired by a version or transaction. Mark is
not a Properties version, connection fact or write lock. Default
identity selectors need no facts. Never fetch a newer score or retry through current-state transfer
to rescue a strict stale candidate or clear an active execution hold. There is no
per-Task Candidate Cache to invalidate or repair.

## Result Disposition

[Result Policy](../../../kernel_pacer_jvm/doc/result/result-routing-scheduling.md)
parses the returned context and publishes bounded semantic events. It does not
call WorkerScoreCore directly. The Worker execution event Owner unwraps the
opaque WorkerLeaseReference and applies `releaseObservedHotScoreHolds` for success,
or the signed-exact `releaseScoreHolds` for failure. Java prepares release targets
and the optional exact counterpart; both use the same exact replacement primitive.

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
| Execution transfer | sealed, expired, negative, stale or missing candidate | Do not claim the Item; no fallback acquisition |
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
Recheck timing and excluded-Endpoint cold parking belong to that policy; no generic
Session, Attempt or Worker reservation owner is introduced here.

## Deployment

HTTP, Binding, Redis keys and the numeric Score layout are unchanged. The low bit
now expresses soft/sealed semantics. This change supplies no compatibility
reader, migration or automatic cleanup. Old MAX,0 has no extra pause protection;
new pause always writes MAX,1. Local inventory is discarded on process restart.

## Caller Observation Boundary

Candidate Maps and transition results carry opaque Score fences. Decoded time,
mark, polarity values and slot arithmetic stay inside the Score Owner. Pacer
supplies raw millisecond floors/deadlines; Owner alignment preserves the existing
ranges and execution-time lease checks. Matching retains the original supplied
Score and deadline without interpreting either as a new coordinate.

Operator pause/resume and the six-state scheduling observation also belong to
WorkerScoreCore. Their Server surface maps semantic results only; it cannot use
HELD_HOT or PAUSED as evidence that a particular caller successfully transferred
a fence. CONNECTED preserves current/future time and mark: restored soft HOT can
transfer; restored sealed HOT cannot.
