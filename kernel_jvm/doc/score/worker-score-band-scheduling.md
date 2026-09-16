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

HOT selects ordinary allocation: time is a hold deadline, and a due coordinate
may be acquired again. An active soft hold may transfer before that deadline.
RECOVERY selects recovery validation: time is the next eligible recheck time,
not a promised execution deadline.

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
score = polarity * (timeSlot * 2 + mark)

timeSlot = abs(score) / 2
mark = abs(score) % 2
polarity = sign(score)
```

- HOT_ACQUIRE = +1; RECOVERY_RECHECK = -1; score zero is invalid.
- SLOT_MILLIS = 100; SLOT_FACTOR = 2 is the sole encoding factor.
- MAX_TIME_SLOT = PAUSE_TIME_SLOT = 99_999_999_999.
- MAX_TIME_MILLIS = PAUSE_TIME_MILLIS = 9_999_999_999_900.
- MIN_TIME_SLOT = 0; MIN_BASE = 1; mark is 0 or 1.
- The fixed cold slot is 1. Registration initializes score -2; cold parking
  preserves mark, producing -2 or -3.
- Decoded timeMillis is the start of the stored slot.
- The largest absolute coordinate is 199_999_999_999, exactly representable
  as a Redis double.

There is no rank, retry counter, attempt limit or exhausted state in Score.
The numeric layout is unchanged by the soft/sealed migration. There is no
compatibility decoder, format detection, automatic conversion, migration key
or automatic data deletion. Existing MAX,0 coordinates follow soft semantics.

For active HOT, mark=0 is a transferable soft hold and mark=1 is a sealed hold
that cannot transfer. Properties invalidation and execution commit both seal;
Kernel records no reason. A sealed future coordinate does not prove execution
or that a particular caller committed it. Once due, either mark may be acquired
again. Expiry needs no write or background reset.

## Polarity Lanes

### HOT_ACQUIRE

Only positive Scores supply ordinary assignment candidates.
A slot strictly before Redis current slot is due. Current and future slots
are held and cannot be acquired; an exact soft current-slot hold may transfer.
Transfer keeps or extends time and can seal. The maximum slot follows these
same rules; PAUSED is its observation name, not a separate transfer condition.

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

## Read-Only Observations

All observations are read-only; only successful transitions move the head.

| Operation | Range and order |
| --- | --- |
| observeDueHotScoreCandidates | Positive due head at or above the supplied HOT floor, ascending Score/member order |
| observeHotCandidatesBefore | Positive head strictly below the supplied cutoff, descending numeric order |
| observeRecoveryRecheckCandidates | Negative coordinates after cold slot and strictly before Redis current slot, descending numeric order |

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
Within one time slot, mark=0 precedes mark=1; mark is not a business priority.

Current slots, future slots, PAUSE and cold entries are excluded. Every round
starts at the head, with no cursor, offset continuation or cooldown.
Refill omits corrupt rows within its raw-row budget. Serviceability range reads
retain their existing strict integer conversion: a fractional Score throws
IllegalStateException. Neither path performs replacement reads or repairs data.
Observation or failed CAS alone does not guarantee progress through a corrupt
or invalid head.

Pacer discovers only Groups supplied by Main's current Task input; this is not
a global Worker discovery or reconciliation facility. Successful acquisition
or deferral moves members out of the head, including equal-score members.
Initial HOT acquisition happens before Matching reads eligibility.

## Candidate Validation

Matching supplies bounded identity evidence with an original Score fence and
deadline. Kernel validates Group/Binding, exact soft HOT transfer with seal=true and
round uniqueness before claiming an Item and publishing a Command.
Matching cannot renew, acquire or reconstruct Score. Properties and sealing
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

Pause advances time and seals atomically while preserving sign. General polarity toggle
uses exact CAS and preserves the entire absolute coordinate. Evidence correction
uses the fixed current-value time fence below. Release can lower time only under
its exact fence; cold parking has its own fixed target.

## Interface Rule

Caller inputs are bounded identities, opaque observed fences, millisecond
times, a relative delay and mechanical seal or polarity/refresh choices.
Raw ranges, encoded coordinates and mark values are not caller construction
capabilities. Decoded fields, encoding constants, the PAUSE sentinel and slot
alignment remain package-private to WorkerScoreEncoding. The public polarity
enum names mechanical intent without exposing numeric encoding.

All candidate observations return immutable workerId-to-opaque-Score Maps in
Redis iteration order. HOT Serviceability reads preserve descending score/member
order; RECOVERY reads preserve earliest-recheck order, including existing tie
ordering. Callers may retain, associate, exact-compare and return these fences;
they cannot decode or calculate coordinates. Deferral takes one delayMillis for
the whole Map. Numeric target errors retain each operation's existing handling.

Pacer supplies millisecond floors and cutoffs without aligning them. Only the
Owner checks representability and converts to slots. Raw and pre-aligned valid
inputs select identical ranges; no public encoding-normalization helper is added.

### Scheduling Observation And Controls

WorkerScoreCore owns observeSchedulingStates(group, workerIds), pauseScheduling
and resumeScheduling. State interpretation and PAUSE composition stay in the
Score Owner; Server maps the semantic results. No new state or lifecycle is introduced.

Observation accepts 1..100 unique IDs, makes one ZMSCORE read and decodes it before
sampling the local JVM clock once. WorkerSchedulingObservation combines that
readAtMillis with a complete immutable Map in request order. Classification is:
missing -> MISSING; either polarity at PAUSE -> PAUSED; RECOVERY at or below the
cold slot -> COLD; remaining RECOVERY -> RECOVERY; HOT at the current/future slot
-> HELD_HOT; earlier HOT -> HOT_SCORE_OVERDUE. Mark does not affect this view.
This is a bounded Score projection, not Binding, Matching or network evidence,
and HOT_SCORE_OVERDUE does not assert floor-aware scheduling eligibility.

Pause composes current-time advance and sealing to MAX,1, preserving
polarity, with one EVAL and no pre-read or TIME. MAX,0 also becomes MAX,1;
repeating MAX,1 is UNCHANGED. Resume reads once; missing is
MISSING and non-PAUSE is UNCHANGED. For PAUSE it samples local time and composes
ordinary exact release, retaining the ZMSCORE + TIME + EVAL order. A later deletion
or fence change is CONFLICT, never a second observation or retry. Resume retains
polarity and mark. Both controls return APPLIED, UNCHANGED, MISSING or CONFLICT;
Server maps these meanings to its existing ActionOutcome and business errors.

Corrupt Score handling remains path-specific: observation/resume decode failures
throw; pause retains current-time advance's original comparisons and result
mapping for corrupt coordinates, including an above-maximum coordinate returning
unchanged and fractional suffixes remaining unrepaired.
Server keeps validation, result completeness checks, wire names and error mapping.
It does not decode Score or sample a second observation clock.

## Java Composition and Fixed Atomic Operations

Package-private WorkerScoreEncoding centralizes arithmetic and validation.
Known targets use direct encoding; changing only time preserves sign and mark:

```text
replace time:     sign(score) * (targetSlot * 2 + abs(score) % 2)
toggle polarity:  -score
acquisition:      requestedSlot * 2
transfer:         max(observedSlot, requestedSlot) * 2 + (seal ? 1 : 0)
```

Public provider methods prepare parameters and combine private operations.
Known complete targets are calculated in Java. Lua retains atomic reads,
exact comparisons, execution-time checks and current-value changes.

| Fixed operation | Atomic responsibility |
| --- | --- |
| Initialize absent | NX with a Java-prepared cold Score |
| Exact replace | Read once, accept the original or its supplied exact counterpart, write target |
| Due exact replace | Check requested target validity, exact fence and execution-time due |
| Active exact replace | Check requested target validity, exact soft active HOT fence |
| Due relative deferral | Exact fence, due and Redis-time relative target |
| Current time advance and seal | Advance time and set mark=1 atomically, preserving sign |
| Current seal | Set mark=1 atomically without creating a member |
| Current polarity correction | Fixed time fence and optional past-time refresh |

Fixed scripts share exact read/compare, write-if-changed and batch-result
functions, plus a fixed Redis TIME-to-milliseconds helper. Each time-dependent
script samples TIME once. Acquisition passes one complete target per batch;
transfer passes one requested time base per batch and complete targets per
member. Both reject an expired requested time before checking exact fences.
Transfer has no PAUSE parameter or dedicated maximum-coordinate rejection.
They do not interpret events, business modes or rule expressions.
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

### Current Time Advance And Seal

The private pause composition reads the current member once, retains its sign,
advances to MAX_TIME_SLOT and sets mark=1 in the same write. MAX,0 must still
change; MAX,1 remains unchanged. Missing is STALE and never created. No public
arbitrary-time rewrite or second seal write is exposed. The existing corrupt
value comparisons and numeric result convention are retained.

### Release

releaseScoreHolds accepts only the exact signed observation and preserves its
polarity and mark. The release time must be valid and at least the Redis current
slot start sampled by this call. releaseSlot * 2 must be below abs(observed).
Same-slot mark=1 replacement may therefore be an accepted NOOP.

releaseObservedHotScoreHolds takes a valid positive HOT fence and additionally
accepts its exact negative counterpart. It writes the positive release target
atomically and maps accepted NOOP to TRANSITIONED. Any other current coordinate
is STALE. Result Routing does not construct the counterpart.

These operations have no additional manual-reset authorization state.

### Polarity Move

toggleCurrentPolarity(group, workerId, observedScore) validates the observation
and exact-replaces it with its negative. It preserves time and mark, including
PAUSE. It cannot repair a changed observation or choose evidence freshness.

### Exact Due Coordinate Deferral

deferObservedToRecovery(group, observedScores, delayMillis) accepts up to 100
workerId -> observedScore entries in one Group. An empty Map returns an empty
result. Java validates the common delay once; a nonpositive or out-of-range
delay yields INVALID for every member without Redis access. Invalid observations
remain individual INVALID results. Java supplies each valid observation's mark.
One fixed Lua reads TIME and calculates the target time base once for the batch:

```text
targetSlot = floor((redisNowMillis + delayMillis) / 100)
targetBase = targetSlot * 2

for each member:
    storedScore == observedScore
    validate execution-time target
    storedSlot < redisNowSlot
    write -(targetBase + mark)
```

Relative addition precedes rounding. Missing/changed exact observations or
non-due current/future coordinates are STALE. Invalid coordinates, nonpositive
or out-of-range delays, cold RECOVERY inputs and targets above MAX_TIME_SLOT are
INVALID. Exact comparison precedes execution-time target and due checks, even
though the target base is calculated before the loop. Rejected members are unchanged.
The maximum target slot itself is legal; it is not a forbidden PAUSE band.

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

replace polarity; preserve mark
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
polarity, calculates -(coldSlot * 2 + observedMark) in Java and reuses exact
replacement. It neither counts failures nor decides to stop checking.

Its production caller is the excluded-Endpoint branch. HOT exclusions retain
the existing two-step sequence: exact polarity toggle, then exact cold park.
Observation ranges exclude PAUSE; an intervening PAUSE change fails the exact
fence at either write. Valid available evidence can later activate
the retained member. Registration independently initializes absent cold members.

### Soft And Sealed Holds

sealCurrentScoreHolds accepts 1..100 unique identities and uses one same-key
Lua without TIME. It preserves sign and time, sets mark=1, and returns NOOP
when already 1, STALE when missing, INVALID for illegal stored values.
It applies to due, held and RECOVERY coordinates alike.

acquireObservedHotScoreLeases accepts due HOT with either mark value and
writes requestedSlot * 2, prepared and passed once per batch in Java.
Redis execution time must still admit the requested future slot.

transferObservedHotScoreLeases(group, observedScores, targetTimeMillis, seal)
accepts only exact soft HOT in the current or future slot. Java prepares
max(observedSlot, requestedSlot) * 2 + (seal ? 1 : 0); Lua still validates the
requested slot itself against execution time. Transfer never shortens time.
With seal=false, an unchanged target returns NOOP only after Redis time and
exact checks. With seal=true, mark changes to 1 even when time stays unchanged,
producing a new fence. Only an actual change returns TRANSITIONED; concurrent
competitors using that old fence have at most one successful transfer.

Sealed HOT rejects transfer even with its latest exact fence. MAX,0 may return
NOOP for a soft target or transition to MAX,1 for a sealed target. MAX,1 rejects
transfer because it is sealed. Due HOT must use acquire, which accepts either
mark; negative RECOVERY cannot use either HOT operation. Request validity is
checked before exact comparison, including a would-be soft NOOP.

Both fixed entries process bounded same-key batches and sample Redis TIME
inside the write. No standalone unseal operation exists. Matching retains
the original acquisition fence and deadline; facts writes and sealing invalidation
have no cross-owner transaction or repair guarantee.

## Transition Matrix

| Input | Transition |
| --- | --- |
| Registration | Missing -> cold RECOVERY, mark=0 |
| Due HOT acquisition | Exact HOT -> future HOT, mark=0 |
| Soft active HOT transfer, seal=false | Exact HOT -> same/later HOT, mark=0; unchanged is NOOP |
| Soft active HOT transfer, seal=true | Exact HOT -> same/later HOT, mark=1 |
| Facts invalidation | Current legal coordinate -> same coordinate, mark=1 |
| Eligible Serviceability check | Exact due HOT/RECOVERY -> RECOVERY at Redis now + delay |
| Available evidence | Correct to HOT; refresh only an older past time; preserve mark |
| Unavailable evidence | Correct to RECOVERY; preserve time and mark |
| Excluded Endpoint | Exact RECOVERY -> cold, preserving mark |
| Pause | Current polarity -> MAX,1 atomically |
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
- Preserve mark, current/future time and PAUSE through evidence corrections.
- Do not infer network truth or resource deletion from Score alone.
- Keep same-key mutation bounded and preserve command budgets.
- Add neither attempt counters nor terminal Worker scheduling states.
- Tests use unique test_* scopes; cleanup uses only scoped SCAN plus UNLINK.

WorkerScoreEncodingTest and WorkerScoreRedisBoundaryTest cover encoding and
I/O ownership. RedisWorkerOwnerRuntimeIntegrationTest covers atomicity, Redis
time, field preservation, long-lived due reads and equal-score head progress.
It also proves shared observation time, pause/resume command budgets and exact
resume conflicts, soft NOOP validation, non-shortening transfer, sealed rejection
and maximum-slot boundaries. RedisWorkerMatchingCatalogIntegrationTest retains
a cached original fence while another caller transfers it, then proves the old
fence cannot commit execution or release the replacement. No allocator is installed.
The Owner test source lives in the Score Owner package, while the
Server redis-owner lane continues to execute it. Runtime proof fences use only
test-side Redis witnesses; no inspection capability is reopened in production.
Pacer and Runtime proofs cover policy, delivery, Binding and original fences.
