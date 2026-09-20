# Worker Score Band Scheduling

Status: active Java Kernel Worker Score contract.

## Owner Boundary

`WorkerScoreCore` owns coordinates, state interpretation, bounded reads and
atomic transitions on each Group's Worker ZSET. `RedisWorkerScoreCore` owns the
connection, Key and lifecycle; package-private `WorkerScoreEncoding` owns all
encoding arithmetic. Storage remains `xa_mass:<scope>:worker:score:<group>`, a
ZSET of Worker IDs with one Score each. Callers retain and return opaque scores and supply millisecond
operation intents. They do not decode, construct or interpret coordinates.

Pacer owns candidate recycling, supply budgets, recheck delay and network policy.
Matching owns qualification and local Pool TTL. Neither owns another Score store.
The [HOT protocol](worker-hot-acquire-lease-protocol.md) specifies the fence handoff.

## Score Model

```text
timeSlot = floor(timeMillis / 100)
MAX_TIME_SLOT = 99_999_999_999
MARK_BASE = MAX_TIME_SLOT + 1
score = polarity * (mark * MARK_BASE + timeSlot)

timeSlot = abs(score) % MARK_BASE
mark = floor(abs(score) / MARK_BASE)
```

Coordinates require an integer, nonzero Score, timeSlot in 0..MAX_TIME_SLOT,
mark in 0..1, and positive HOT or negative RECOVERY polarity. Cold is slot 1;
registration initializes absent members at -1. Both marks occupy independent
contiguous ranges. MAX_TIME_SLOT remains the PAUSED observation coordinate.

For HOT, mark=0 is the ordinary lane; mark=1 denotes a candidate generation.
Candidate admission changes only the mark and never creates a future Worker
lease. A mark=1 coordinate is not written into the future by candidateization,
recycling or Properties invalidation. Execution acquisition, pause and relative
Recovery deferral write mark=0. A past polarity change clears mark while advancing
generation; current/future polarity correction and exact release preserve mark.
The decoder validates coordinates without making clock-dependent state claims.

RECOVERY is a long-lived resource state. Its future time is nextRecheckAt, an
eligibility boundary, not a deadline or offline-age measurement. There is no
rank, attempt cap, automatic deletion or terminal Worker scheduling state.

## Read-Only Observations

All ranges return immutable insertion-ordered `Map<String, Long>` values.
No offset, durable cursor, supplementary scan or scan cooldown is retained.

| Read | Range and order | Raw cost |
| --- | --- | --- |
| observeDueHotScoreCandidates | HOT mark=0, from optional floor, strictly before Redis current slot; ascending time/member | One read-only TIME + ZRANGE Lua, limit 1..100 |
| observeHotCandidateScoresBefore | HOT mark=1, optional floor through exclusive cutoff, strictly due; ascending time/member | One read-only TIME + ZRANGE Lua, limit 1..100 |
| observeHotCandidatesBefore | Both HOT marks below exclusive cutoff, descending logical time | Two bounded ranges, at most 2 * limit temporary rows |
| observeRecoveryRecheckCandidates | Both RECOVERY marks after cold, strictly before sampled Redis current slot; earliest time first | Existing external TIME, two bounded ranges, at most 2 * limit temporary rows |

Serviceability merges the two mark ranges by logical time, then by mark in the
previous logical ordering, then by reverse Redis member order for equal scores.
It truncates to the final raw row budget before decoding. Refill and recycling
skip malformed raw rows without fetching replacements. Serviceability preserves
its strict conversion behavior: an illegal fractional Score throws. The paths do
not have a unified corruption policy. Fully corrupt heads need not make progress.
The Recovery range has no 24-hour lower bound and never includes cold or PAUSE.

## Scheduling Observation And Controls

`observeSchedulingStates` accepts 1..100 unique IDs, reads with one ZMSCORE,
decodes, then samples one local readAtMillis for the complete batch. Its immutable
Map preserves request order and includes MISSING. Classification order is:
PAUSED first; RECOVERY polarity then COLD versus RECOVERY; HOT current/future
slots as HELD_HOT and past slots as HOT_SCORE_OVERDUE. These names do not establish
Binding, qualification, connectivity or authorization to claim an Item.

`pauseScheduling` performs one atomic read/write, preserving polarity and writing
MAX_TIME_SLOT with mark=0. It does not create missing members or sample TIME.
`resumeScheduling` point-reads once; non-paused values are UNCHANGED. A paused
value is released with its original exact fence, without re-read or retry. Initial
absence is MISSING; deletion/change after the read is CONFLICT. Actual resume
retains ZMSCORE + TIME + EVAL. Server maps semantic outcomes to the existing HTTP
fields and errors; it has no mirrored decoder.

## Java Composition And Fixed Atomic Operations

Public combinations prepare Java inputs and call private Redis primitives. No
Store layer, generic Patch, operation list, event-mode Lua or dynamic script is
used. Shared fixed functions read an exact fence once, compare, write if changed
and encode batch results; time-dependent scripts share the Redis clock helper.

| Fixed operation | Atomic rule |
| --- | --- |
| Initialize absent | NX of Java-prepared cold Score; return actual new members |
| Exact replace | Accept exact original, or the one supplied negative counterpart for completed release |
| Candidateize | Exact due HOT mark=0; write Java-prepared candidate fence with unchanged time |
| Recycle candidate | Exact due HOT mark=1; write ordinary HOT at Redis current slot |
| Observed execution acquisition | Validate requested future time, exact HOT and due; write Java-prepared deadline, mark=0 |
| Current execution acquisition | Validate requested future time, read current HOT and due; write deadline, mark=0 |
| Relative deferral | Exact and due; compute one Redis-relative target per batch, mark=0 |
| Pause | Preserve polarity; write MAX time, mark=0 |
| Advance past times | For legal past values outside cold RECOVERY, write Redis current time; clear HOT mark, preserve RECOVERY mark and polarity |
| Current polarity correction | Apply fixed evidence-time fence and optional minimum activation time |

Writes handle at most 100 same-Group members per Lua. Existing lease splitting,
per-member release pipelines and TIME sampling positions remain. Candidateize,
recycle and Properties each sample TIME once per batch; pause samples none.
There are no preceding point reads or conflict retries. Serviceability's two
mark ranges explicitly increase bounded read work; other paths retain their
existing command shapes.

## Score Primitives

### Candidate Generation And Execution

Let N be Redis execution time's slot. Candidateize and both execution acquisitions
require T < N; equality is not due. Candidateize also requires an exact HOT mark=0
observation and writes HOT 1/T. Recycling exact HOT mark=1 writes HOT 0/N. Pacer
selects the age threshold; Owner only enforces its mechanical input and due check.

`acquireObservedHotScoreLeases` accepts either mark with the exact positive fence.
`acquireCurrentHotScoreLeases` takes identities only and reads each current value
atomically. Both write HOT 0/requestedSlot and require requestedSlot > N, checked
before member comparison. Current/future HOT, RECOVERY and missing members are
ineligible. Corrupt current values return INVALID without repair. A strict failure
cannot be downgraded to current acquisition. Only TRANSITIONED with the returned
execution fence permits Item claim. No lease transfer or sealing API remains.

### Properties Time Invalidation

`advancePastScoreTimesToNow` accepts 1..100 unique IDs. For legal HOT with T < N,
it atomically writes HOT mark=0 at N. Past non-cold RECOVERY advances to N while
preserving its mark and polarity. Current/future values and cold RECOVERY are
NOOP; missing members are STALE and never created. A property change invalidates
the past candidate fence and returns HOT to the ordinary lane. After the current
slot passes, normal Refill can observe it without waiting for candidate recycling.
Supply deficits, Group roots and round budgets still govern qualification.

Clearing HOT mark also advances generation in the same atomic write, so later
candidateization cannot recreate the old Pool fence. An assignment that already
won retains its future execution deadline and result association. Same-slot
invalidation is NOOP; no candidate can be created or acquired in that slot. Facts
and Score remain independent commits, without a property-version transaction or
repair guarantee.

Cold RECOVERY is excluded from time advancement so initial Properties publication
cannot fence out a pending CONNECTED event before activation. The exception
preserves both marks and uses the existing cold coordinate classification; it
adds no initialization flag. Non-cold RECOVERY still advances and may reject an
older CONNECTED event once its new coordinate is past. Such a Worker needs newer
network evidence; a Group absent from Main's Task roots has no periodic Probe to
supply it. Proofs must still establish initial HOT activation rather than infer
it from network connection alone.

### Release And Polarity Move

`releaseScoreHolds` accepts only the exact signed observation and preserves its
polarity/mark. It samples Redis time before preparing targets, and the requested
release time cannot precede that current slot start. Release cannot move to a
later slot; the previous same-slot mark=1 accepted-NOOP boundary remains.
`releaseObservedHotScoreHolds` additionally accepts the exact negative of the
original HOT fence and writes HOT at release time. Java maps its accepted NOOP
to TRANSITIONED. Other changes reject the old fence. `toggleCurrentPolarity`
reuses exact replacement, flips polarity, clears mark and retains time. Java
prepares the complete target without TIME; a zero target is INVALID. This exact
operation serves the excluded-Endpoint toggle/park composition, not network
generation refresh.

### Relative Recovery Deferral And Cold Park

`deferObservedToRecovery(group, observations, delayMillis)` accepts at most 100
members. Empty input is empty. Invalid common delay yields per-member INVALID
without Redis; invalid observations are processed individually. Lua computes:

```text
targetSlot = floor((redisNowMillis + delayMillis) / 100)
targetScore = -targetSlot
```

Addition precedes rounding. Exact comparison precedes execution-time target
validity and due checks, even though target preparation happens once per batch.
Missing/changed or current/future observations are STALE; illegal coordinates,
cold RECOVERY input and out-of-range targets are INVALID. MAX itself is legal.
Successful deferral clears candidate mark. Only then may Pacer submit a Probe;
failed offers, ALREADY_REQUESTED, CAPACITY or lost evidence never roll time back.

`parkObservedRecoveryScore` constructs the cold target in Java, preserving mark,
and uses exact replacement. Excluded Endpoints use it without a Probe; HOT first
exact-toggles polarity, then parks using the returned fence. Neither operation
means deletion. Valid network evidence may reactivate cold membership.

### Current Polarity Within A Time Fence

`rewriteCurrentPolarityWithinTimeFence` receives supplied times, target polarity
and minimumTimeMillis (zero disables the startup activation condition), never an
event name. Let T be storedSlot, E be suppliedSlot, N be Redis currentSlot and F be
minimumSlot. One atomic read per member applies these rules:

```text
T >= N: correct polarity; preserve time and mark
T < N and E < T: STALE, no write
T < N and T < F: require E >= F and N >= F, otherwise STALE

for an admitted past polarity change or below-F activation:
    newSlot = max(T + 1, min(E, N))
    write target polarity, mark=0, newSlot
otherwise: NOOP
```

CONNECTED supplies the Runtime's once-sampled startup floor; DISCONNECTED supplies
zero. The activation exception also applies to same-polarity HOT below floor after
restart. Evidence before floor leaves that lower coordinate unchanged. Normal
same-polarity evidence, including repeated Polling, never clears candidate mark or
advances time. Current/future execution, recheck and PAUSE coordinates retain their
full absolute value and therefore preserve the original execution counterpart.

The new past generation is strictly later than T and no later than N. A legitimate
same-slot evidence time remains admissible and advances one slot; it cannot recreate
an old Pool fence on requalification. A later evidence slot is preferred to processing
time, so delayed consumption does not unnecessarily raise the evidence fence. A
coordinate written at N must cross the next slot before candidateization or execution.
Old Pool entries need no repair or rollback. Normal Refill can resupply a due restored
HOT without waiting for 60-second candidate recycling.

The existing batch uses one EVAL and one script-local TIME with no pre-read or retry.
Malformed-score reply differences remain; refreshing a fractional stored coordinate
must not silently repair it into an integer. Upstream Binding/source/age checks remain.
These are best-effort evidence timestamps, not a total network event version or replay log.

## Failure And Atomicity Boundaries

Each mutation is atomic on one Group key. Binding, Matching facts, invalidation,
Probe offers, Item claim and Result writes remain separate. Existing INVALID,
STALE, NOOP, TRANSITIONED, score echoes and corruption exceptions remain except
for the explicitly changed candidate/execution semantics. Fractional raw echoes
in deferral or unchanged polarity correction still fail integer parsing.

Pacer uses 15-second Recovery eligibility and a separate 60-second HOT stale
threshold with a 1-second Producer interval. Candidate age is separately 60 seconds;
Pool admission has its own 60-second TTL. None is an execution-time guarantee.
Main supplies all scanned Groups; no global discovery or resource cleanup is added.

## Cutover And Proof

The high-mark layout is incompatible with the old low-bit encoding. Deploy into
a new scope; do not mix processes or reinterpret old data. This change adds no
migration, compatibility decoder, version key or automatic cleanup. Test fixtures
use fresh test_* scopes and only scoped SCAN + UNLINK cleanup.

Encoding and structural tests keep arithmetic/I/O in the Owner. Redis Owner
proof covers exact competition, current-slot exclusions, evidence ordering,
Properties versus assignment, pause/resume, long Recovery and four equal-score
heads progressing 100/100/50. Matching proof covers TTL, replacement, shared Pool
fences and single-winner execution. Runtime and system lanes retain their named
Binding, delivery, result and convergence claims. No allocator is introduced.
