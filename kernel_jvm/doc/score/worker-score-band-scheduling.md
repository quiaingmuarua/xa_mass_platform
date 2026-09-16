# Worker Score-Band Scheduling

Status: active Java Kernel Worker Score Owner contract.

The [HOT Lease Protocol](worker-hot-acquire-lease-protocol.md) owns the fence
handoff across acquisition, Matching, confirmation, claim and Result release.
This document owns encoding, ranges and atomic Score operations.

## Purpose

Worker Score is a signed scheduling coordinate for one long-lived Worker
identity. One WorkerId represents one execution slot; physical concurrency is
represented by multiple logical WorkerIds. Score does not report a physical
connection, Matching eligibility, execution outcome or Worker lifecycle.

HOT selects ordinary allocation. RECOVERY selects recovery validation.
The time coordinate determines when the selected lane can next consider the
Worker. A due coordinate is eligibility, not a promised execution deadline.

## Owner Boundary

WorkerScoreCore owns encoding, exact fences and Score mutation.
WorkerResourceCatalog owns Group metadata and immutable Group/Endpoint Binding.
[Matching](../../../worker_matching_jvm/README.md) owns facts, Rules and inventory.
[Serviceability Policy](../../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
owns check timing, Endpoint exclusions and interpretation of network evidence.

Score remains opaque outside this Owner: callers can retain, associate,
exact-compare and return it, but cannot construct or decode coordinates.
RedisWorkerScoreCore retains the existing connection, Key and lifecycle.
No additional state Owner, Redis Key, background task or migration reader exists.

## Score Model

The complete encoding is:

```text
timeSlot = floor(timeMillis / 100)
score = polarity * (timeSlot * 2 + dirty)

timeSlot = abs(score) / 2
dirty = abs(score) % 2
polarity = sign(score)
```

- HOT_ACQUIRE = +1; RECOVERY_RECHECK = -1; score zero is invalid.
- SLOT_MILLIS = 100; SLOT_FACTOR = DIRTY_FACTOR = 2.
- MAX_TIME_SLOT = PAUSE_TIME_SLOT = 99_999_999_999.
- MAX_TIME_MILLIS = PAUSE_TIME_MILLIS = 9_999_999_999_900.
- MIN_TIME_SLOT = 0; MIN_BASE = 1; dirty is 0 or 1.
- The fixed cold slot is 1. Registration initializes score -2; cold parking
  preserves dirty, producing -2 or -3.
- Decoded timeMillis is the start of the stored slot.
- The largest absolute coordinate is 199_999_999_999, exactly representable
  as a Redis double.

There is no rank, retry counter, attempt limit or exhausted state in Score.
This encoding replaces the previous layout. Old coordinates and related
fences cannot be mixed with the new format. There is no compatibility decoder,
format detection, automatic conversion, migration key or automatic data deletion.

Dirty is the candidate invalidation/consumption fence: acquisition clears it,
facts invalidation sets it, and execution confirmation consumes a clean hold by
setting it. It is not a global version, connection fact or retry counter.

## Polarity Lanes

### HOT_ACQUIRE

Only positive Scores supply ordinary assignment candidates.
A slot strictly before Redis current slot is due. Current and future slots
are held and cannot be acquired; a clean current-slot hold can still confirm.
PAUSE is a maximum-time hold and cannot confirm.

### RECOVERY_RECHECK

A negative non-cold Score retains a resource waiting for validation.
Its time is the next eligible recheck time. Every successful exact deferral
writes another future time; there is no attempt-dependent backoff or exhaustion.
A valid available observation can restore HOT; missing or unavailable evidence
leaves RECOVERY available for a later eligible round.

### Parked

Cold parking is a fixed near-zero RECOVERY coordinate, not another band or
resource deletion. Registration starts cold; Serviceability parks excluded
Endpoints there without a Probe. Routine reads exclude cold members.

Valid available evidence may refresh the cold time and restore HOT.
PAUSE remains distinct: evidence can correct its polarity but preserves its
maximum-time hold. No cold-member scan, activation ACK or replay is installed.

## Acquire Queries

All observations are read-only; only successful transitions move the head.

| Operation | Range and order |
| --- | --- |
| observeDueHotScoreCandidates | Positive due head at or above the supplied HOT floor, ascending Score/member order |
| acquireHotCandidatesBefore | Positive head strictly below the supplied cutoff, descending numeric order |
| acquireRecoveryRecheckCandidates | Negative coordinates after cold slot and strictly before Redis current slot, descending numeric order |

Refill due-head observation uses one short Lua containing Redis TIME and
ZRANGE BYSCORE LIMIT 0 limit WITHSCORES. It accepts 1..100 raw rows and returns
an immutable ordered Map. Its lower bound is MIN_BASE when no floor is supplied,
otherwise max(MIN_BASE, floorSlot * 2). Its upper bound is (nowSlot - 1) * 2 + 1.

Serviceability HOT retains its exclusive cutoff and direct bounded range read.
RECOVERY retains one Redis TIME sample before its range read:

```text
firstSlot = coldSlot + 1
lastSlot = redisNowSlot - 1
ZREVRANGEBYSCORE key -(firstSlot * 2) -(lastSlot * 2 + 1)
  LIMIT 0 limit WITHSCORES
```

There is no age-based lower bound. Coordinates older than 24 hours remain
eligible. Reverse numeric order for negative scores selects the earliest time.
Within one time slot, clean precedes dirty; dirty is not a business priority.

Current slots, future slots, PAUSE and cold entries are excluded. Every round
starts at the head, with no cursor, offset continuation or cooldown.
Corrupt rows consume the raw-row budget and are omitted without replacement
reads. Observation or failed CAS alone does not guarantee progress through a
corrupt or invalid head.

Pacer discovers only Groups supplied by Main's current Task input; this is not
a global Worker discovery or reconciliation facility. Successful acquisition
or deferral moves members out of the head, including equal-score members.
Initial HOT acquisition happens before Matching reads eligibility.

## Candidate Validation

Matching supplies bounded identity evidence with an original Score fence and
deadline. Kernel validates Group/Binding, exact clean HOT confirmation and
round uniqueness before claiming an Item and publishing a Command.
Matching cannot renew, acquire or reconstruct Score. Properties and dirty
invalidation remain separate best-effort commits.

## Registration Members

initializeRegisteredScores uses one bounded ZADD NX Lua and returns actual new
IDs. It performs no TIME or pre-read. Existing members, including corrupt
coordinates, are unchanged. Cold membership is registration existence, not
network evidence.

sampleRegisteredWorkerIds accepts 1..1000 and exposes identities only.
Catalog sampling relies on this membership; deleting Score is not a complete
Worker deletion. No deletion or offline-resource reconciliation runs here.

## Transition Rules

Only the Score Owner performs writes. Semantic events stop at finite Worker
Mechanisms, which select the legal mechanical operation after Binding checks.
Task results release their correlated lease without inferring network polarity.

Same-polarity time advance preserves sign and dirty. General polarity toggle
uses exact CAS and preserves the entire absolute coordinate. Evidence correction
uses the fixed current-value time fence below. Release can lower time only under
its exact fence; cold parking has its own fixed target.

## Interface Rule

Caller inputs are bounded identities, opaque observed fences, millisecond
times, a relative delay and the mechanical polarity/refresh choice.
Raw ranges, encoded coordinates and dirty values are not caller construction
capabilities. WorkerScoreState exposes polarity, timeMillis and dirty, plus
the opaque original score; WorkerScoreDelayTarget carries observedScore and
delayMillis. Numeric target errors remain Owner INVALID results.

## Java Composition and Fixed Atomic Operations

Package-private WorkerScoreEncoding centralizes arithmetic and validation.
For absolute value a and sign p:

```text
replace time:      p * (targetSlot * 2 + a % 2)
replace dirty:     p * (a - a % 2 + targetDirty)
replace polarity:  targetPolarity * a
```

Public provider methods prepare parameters and combine private operations.
Known complete targets are calculated in Java. Lua retains atomic reads,
exact comparisons, execution-time checks and current-value changes.

| Fixed operation | Atomic responsibility |
| --- | --- |
| Initialize absent | NX with a Java-prepared cold Score |
| Exact replace | Read once, accept the original or its supplied exact counterpart, write target |
| Due exact replace | Check requested target validity, exact fence and execution-time due |
| Active exact replace | Check requested target validity, exact clean active non-PAUSE fence |
| Due relative deferral | Exact fence, due and Redis-time relative target |
| Current time advance | Change only an earlier coordinate, preserving sign and dirty |
| Current dirty | Set dirty atomically without creating a member |
| Current polarity correction | Fixed time fence and optional past-time refresh |

Fixed scripts share exact read/compare, write-if-changed and batch-result
functions. They do not interpret events, business modes or rule expressions.
Ordinary same-value replacement returns NOOP. Completed HOT release maps an
accepted NOOP to TRANSITIONED in Java. It accepts only the original HOT fence
or its exact negative, never arbitrary alternatives.

Existing rejection Score conventions remain, including raw Score echoes in
deferral rejection and unchanged polarity correction. Fractional echoes fail
integer parsing rather than silently truncating.

Write scripts process at most 100 same-Group members. Lease operations split
larger inputs; release and current-time advance retain their per-member pipeline.
Release samples TIME once before validating/preparing individual targets.
No preceding point reads, per-member TIME, retries or new keys are added.

## Score Primitives

### Current Same-Polarity Rewrite

rewriteCurrentScores(group, workerIds, targetTimeMillis) atomically reads
each current member and advances only when abs(current) < targetSlot * 2.
It preserves sign and dirty; missing is STALE, and it never creates.
Same-slot or later coordinates do not advance. Server pause uses this operation
with PAUSE_TIME_MILLIS.

### Release

releaseScoreHolds accepts only the exact signed observation and preserves its
polarity and dirty. The release time must be valid and at least the Redis current
slot start sampled by this call. releaseSlot * 2 must be below abs(observed).
Same-slot dirty=1 replacement may therefore be an accepted NOOP.

releaseObservedHotScoreHolds takes a valid positive HOT fence and additionally
accepts its exact negative counterpart. It writes the positive release target
atomically and maps accepted NOOP to TRANSITIONED. Any other current coordinate
is STALE. Result Routing does not construct the counterpart.

These operations have no additional manual-reset authorization state.

### Polarity Move

toggleCurrentPolarity(group, workerId, observedScore) validates the observation
and exact-replaces it with its negative. It preserves time and dirty, including
PAUSE. It cannot repair a changed observation or choose evidence freshness.

### Exact Due Coordinate Deferral

deferObservedToRecovery accepts up to 100
workerId -> WorkerScoreDelayTarget(observedScore, delayMillis) entries per Group.
Java validates observations and delays and supplies the preserved dirty.
One fixed Lua reads TIME once and performs:

```text
storedScore == observedScore
targetSlot = floor((redisNowMillis + delayMillis) / 100)
storedSlot < redisNowSlot
write -(targetSlot * 2 + dirty)
```

Relative addition precedes rounding. Missing/changed exact observations or
non-due current/future coordinates are STALE. Invalid coordinates, nonpositive
or out-of-range delays, cold RECOVERY inputs and targets reaching PAUSE are
INVALID. Exact comparison precedes execution-time target and due checks.
Rejected members are unchanged.

Pacer chooses the delay and whether to offer a Probe; Score Owner has no
attempt count or outcome interpretation. Only TRANSITIONED members are offered.
Offer failure or evidence loss retains the new eligibility time. A later
CONNECTED observation preserves a future time while restoring HOT polarity.

### Current Polarity Within a Time Fence

rewriteCurrentPolarityWithinTimeFence(group, suppliedTimes, targetPolarity,
refreshPastTime) atomically reads the current member. The fixed condition is:

```text
allow when storedSlot >= redisNowSlot OR storedSlot <= suppliedSlot

if refreshPastTime AND storedSlot < redisNowSlot AND storedSlot < suppliedSlot:
    replace time with suppliedSlot
otherwise:
    retain time

replace polarity; preserve dirty
```

The Worker Serviceability Mechanism chooses HOT/true for available evidence
and RECOVERY/false for unavailable evidence. The Score Owner receives no event
names. Current/future slots and PAUSE retain time; newer past coordinates reject
older evidence. Same polarity with unchanged time is NOOP.

Score time may originate from a lease, recheck or observation; it is not a
persistent network-evidence timestamp. Binding/source and evidence-age checks
belong upstream. Across batches, evidence remains best-effort arrival order,
without replay or a total event version.

### Cold Park

parkObservedRecoveryScore(group, workerId, observedScore) validates RECOVERY
polarity, calculates -(coldSlot * 2 + observedDirty) in Java and reuses exact
replacement. It neither counts failures nor decides to stop checking.

Its production caller is the excluded-Endpoint branch. HOT exclusions retain
the existing two-step sequence: exact polarity toggle, then exact cold park.
PAUSE is skipped by the policy. Valid available evidence can later activate
the retained member. Registration independently initializes absent cold members.

### Dirty Lease Fence

markCurrentLeasesDirty accepts 1..100 unique identities and uses one same-key
Lua without TIME. It preserves sign and time, sets dirty=1, and returns NOOP
when already dirty, STALE when missing, INVALID for illegal stored values.
It applies to due, held and RECOVERY coordinates alike.

acquireObservedHotScoreLeases accepts due HOT with either dirty value and
writes the Java-prepared target time with dirty=0. Redis execution time must
still admit the requested future slot.

confirmActiveHotScoreLeases accepts only exact clean HOT in the current or
future slot, excluding PAUSE. Java prepares max(observedSlot, requestedSlot)
with dirty=1; Lua still validates the requested slot itself against execution
time. Even without extending the deadline, confirmation consumes dirty and
returns a new execution fence. It cannot confirm twice.

Both fixed entries process bounded same-key batches and sample Redis TIME
inside the write. No standalone dirty-clear operation exists. Matching retains
the original acquisition fence and deadline; facts writes and dirty invalidation
have no cross-owner transaction or repair guarantee.

## Transition Matrix

| Input | Transition |
| --- | --- |
| Registration | Missing -> cold RECOVERY, dirty=0 |
| Due HOT acquisition | Exact HOT -> future HOT, dirty=0 |
| Clean active HOT confirmation | Exact HOT -> same/later HOT, dirty=1 |
| Facts invalidation | Current legal coordinate -> same coordinate, dirty=1 |
| Eligible Serviceability check | Exact due HOT/RECOVERY -> RECOVERY at Redis now + delay |
| Available evidence | Correct to HOT; refresh only an older past time; preserve dirty |
| Unavailable evidence | Correct to RECOVERY; preserve time and dirty |
| Excluded Endpoint | Exact RECOVERY -> cold, preserving dirty |
| Pause | Advance current polarity to PAUSE, preserving dirty |
| Ordinary release | Exact signed fence -> same polarity at release time |
| Completed HOT release | Exact HOT or exact negative counterpart -> HOT at release time |

## Cross-Owner Use

The [HOT Lease Protocol](worker-hot-acquire-lease-protocol.md) owns the opaque
fence handoff. [Serviceability Policy](../../../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)
owns bounded discovery and evidence interpretation. Server and Transport do
not select workers or mutate Redis Score outside these contracts.

## Atomicity Boundaries

Each exact transition or current-value rewrite is atomic on one Group Score
key. Binding validation, Matching facts, Score invalidation, Probe submission,
Item claim and Result storage remain separate Owner operations.
Score is a scheduling coordinate, not a broad resource write lock.

## Failure And Stale Handling

Changed fences reject stale attempts. Missing or mismatched Bindings do not
authorize Score repair. Lost Probe requests or evidence retain the next
eligible time, and a later normal Producer round may recheck it.
No automatic orphan deletion, cold scan or Worker-wide reconciliation runs.

There is no recheck deadline or maximum number of attempts. Main-selected
Groups, Producer scheduling, HOT-first ordering and the round budget determine
when eligible RECOVERY members are actually considered.

## Mechanism And Policy

Score Owner owns legal coordinates, bounded reads, exact fences and atomic
field changes. Pacer owns recheck delay, stale-HOT cutoff, Group input/order,
Probe budget, Endpoint exclusions and network-evidence interpretation.

The default recheck delay is 15 seconds, the HOT stale threshold is separately
60 seconds, and the Producer interval is 1 second. These are policy values,
not encoding constants or execution-time guarantees.

Future offline-resource cleanup belongs to a separate reconcile policy based
on network evidence. nextRecheckAt cannot measure offline age because each
check advances it. No cleanup policy, thread, evidence history or delete
operation is added in this change.

## Guardrails

- Keep Score opaque outside Owner operations.
- Preserve one execution slot per WorkerId and exact result association.
- Preserve dirty, current/future time and PAUSE through evidence corrections.
- Do not infer network truth or resource deletion from Score alone.
- Keep same-key mutation bounded and preserve command budgets.
- Add neither attempt counters nor terminal Worker scheduling states.
- Tests use unique test_* scopes; cleanup uses only scoped SCAN plus UNLINK.

WorkerScoreEncodingTest and WorkerScoreRedisBoundaryTest cover encoding and
I/O ownership. RedisWorkerOwnerRuntimeIntegrationTest covers atomicity, Redis
time, field preservation, long-lived due reads and equal-score head progress.
Pacer and Runtime proofs cover policy, delivery, Binding and original fences.
