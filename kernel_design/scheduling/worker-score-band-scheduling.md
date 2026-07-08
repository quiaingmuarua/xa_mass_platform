# Worker Score-Band Scheduling

Status: design reference for the new kernel workspace. This document is not
current Java implementation truth and is not an implementation roadmap.

Artifact role: mechanism design reference.

## Purpose

Worker score-band scheduling is the worker/resource acquisition clock.

Worker score answers one question:

```text
may this worker/resource enter worker candidate acquisition now?
```

It does not answer:

```text
is this worker currently connected?
does this worker have free capacity?
does this worker match a task demand?
is this worker finally selected?
why was this worker held, parked, disabled, or recovered?
```

Those remain worker-runtime validation, admission, policy, owner evidence, and
trace decisions.

Worker score intentionally does not copy task score lifecycle tags. A task is a
one-shot scheduling aggregate. A worker is a long-lived resource identity. The
worker score axis therefore expresses acquisition polarity, not lifecycle
progression.

## Owner Boundary

Worker-runtime owns:

```text
worker declaration validation
worker group membership interpretation
dispatch gate interpretation
reachability interpretation for scheduling
capacity / admission truth
score polarity and coordinate placement
verified reopen
negative dispatch blocking
manual hold / release policy
```

Transport owns final-hop delivery evidence:

```text
endpoint/session observation
heartbeat / keepalive freshness
adapter-local consumer availability
delivery mailbox evidence
disconnect / unavailable evidence
```

Transport evidence can be read by worker-runtime during validation. It must not
directly write worker score or promote a worker into hot admission. Positive
availability is a worker-runtime-verified fact.

## Score Model

Worker score is a signed acquisition coordinate:

```text
score = polarity * base
base = epochSecond * SLOT_FACTOR + laneRank * VERSION_FACTOR + version
```

The sign is the worker scheduling lane:

```text
score > 0
  HOT_ACQUIRE polarity
  only candidate source for assignment-dispatch worker hot acquire

score < 0
  RECOVERY_RECHECK polarity
  only candidate source for worker-runtime recovery-recheck validation

score == 0
  invalid / reserved
```

`abs(score)` is decoded the same way for both polarities:

```text
epochSecond = abs(score) / SLOT_FACTOR
slotRemainder = abs(score) % SLOT_FACTOR
laneRank = slotRemainder / VERSION_FACTOR
version = slotRemainder % VERSION_FACTOR
```

First-slice constants:

```text
VERSION_FACTOR = 100
LANE_RANK_FACTOR = 100
SLOT_FACTOR = LANE_RANK_FACTOR * VERSION_FACTOR
MAX_LANE_RANK = 99
MAX_VERSION = 99
MAX_EPOCH_SECOND = 9_999_999_999
PAUSE_EPOCH_SECOND = MAX_EPOCH_SECOND
MIN_BASE = 1
```

The zero coordinate is reserved because score `0` has no polarity. In normal
wall-clock use, `epochSecond` is positive. If a test or bootstrap path needs a
minimum score, use `MIN_BASE`, not `0`.

`epochSecond` means:

```text
HOT_ACQUIRE
  next time the worker may enter hot admission
  examples: capacity cooldown, admission hold, occupancy interval, drain,
  manual disable, maintenance hold

RECOVERY_RECHECK
  next time worker-runtime may run recovery validation
  examples: disconnect recheck, stale endpoint recovery, reconnect backoff,
  parked / recovery exhausted far-future hold
```

`laneRank` is lane-local:

```text
HOT_ACQUIRE
  priority / fairness / same-second tie-break / admission anti-spin hint

RECOVERY_RECHECK
  retry count / failed recheck count / remaining recovery budget
```

`version` is a worker-runtime score fence:

```text
version
  worker core scheduling metadata revision fence
  00..99 rolling coordinate
  prevents stale acquired rounds from writing after platform-defined
  scheduling-critical metadata changed
```

The score version is not a metadata hash and not a lifecycle state. Platform
policy owns the critical scheduling signature definition. The full signature or
hash lives in worker-runtime metadata/evidence; score `version` is only the
low-order fence that changes when that platform-owned signature changes.

Scheduling-critical metadata may include worker group membership, approved
scheduling attributes, capacity profile, dispatch gate generation, admission
policy, or other platform-defined fields that affect worker selection or
admission. It must not include transport heartbeat, session id, latency sample,
connection id, trace fields, diagnostic details, or high-frequency load
counters.

Reason codes, reconnect source, operator notes, owner-reset policy, and
diagnostics stay in worker-runtime evidence, trace, or optional diagnostics.
Score laneRank and version may bound scheduling/recheck work and stale writes;
they must not become hidden reason owners.

## Polarity Lanes

### HOT_ACQUIRE

HOT_ACQUIRE is the only worker lane assignment-dispatch may use for worker candidate
acquisition.

```text
score = +base(epochSecond, laneRank, version)
```

Interpretation:

```text
epochSecond <= now
  due for worker hot acquisition

epochSecond > now
  temporarily unavailable for hot acquisition
```

Manual disable, drain, maintenance, capacity cooldown, and admission hold do not
mean network unavailable. They are same-polarity HOT_ACQUIRE rewrites with a later
`epochSecond`. A hard manual hold uses:

```text
+base(PAUSE_EPOCH_SECOND, laneRank, version)
```

Release of that hold preserves polarity:

```text
+base(PAUSE_EPOCH_SECOND, laneRank, version)
  -> +base(releaseEpochSecond, laneRank, version)
```

### RECOVERY_RECHECK

RECOVERY_RECHECK is the recovery validation lane. It is not a worker selection lane
and must not return a selected worker handle.

```text
score = -base(epochSecond, laneRank, version)
```

Typical inputs:

```text
confirmed disconnect
trusted unavailable / unreachable evidence
stale endpoint requiring recovery validation
recoverable dispatch gate block caused by reachability uncertainty
failed hot admission validation that requires recovery
```

RECOVERY_RECHECK means worker-runtime must validate owner facts before reopening hot
admission. A due RECOVERY_RECHECK candidate may:

```text
pass recovery validation
  -> flip polarity to HOT_ACQUIRE after declaration, gate, reachability, capacity, and
     policy validation

fail recovery validation with budget remaining
  -> stay RECOVERY_RECHECK with later epochSecond and updated laneRank

exhaust recovery / owner park
  -> stay RECOVERY_RECHECK with far-future epochSecond and owner evidence explaining
     why recovery is not currently due
```

### Parked

There is no PARKED band.

Parked is a RECOVERY_RECHECK far-future hold plus owner evidence:

```text
score = -base(PAUSE_EPOCH_SECOND, laneRank, version)
owner evidence = parked / owner-reset-required / recovery-exhausted / policy hold
```

This is intentionally analogous to a paused disconnected worker. It is outside
hot admission and outside routine recovery-recheck due ranges, but it does not create
a third scheduling lane. Worker id remains long-lived; score does not say the
worker was deleted or terminal.

Release of parked RECOVERY_RECHECK preserves polarity:

```text
-base(PAUSE_EPOCH_SECOND, laneRank, version)
  -> -base(releaseEpochSecond, laneRank, version)
```

It does not reopen HOT_ACQUIRE. HOT_ACQUIRE reopen requires a verified polarity flip after
worker-runtime validation. Because polarity flip must not lower `epochSecond`,
parked recovery is intentionally two-step:

```text
release parked RECOVERY_RECHECK hold
  -> due RECOVERY_RECHECK

recovery validation passes
  -> HOT_ACQUIRE
```

There is no direct parked-to-HOT_ACQUIRE shortcut in the score mechanism.

Parked release is not a generic hold release. If owner evidence marks the
worker as parked, owner-reset-required, or recovery-exhausted, release must
validate the owner reset authorization in the same owner transition boundary as
the score release. The score primitive proves the held coordinate is current; it
does not prove the operator or policy is allowed to unpark the worker.

## Acquire Queries

Hot worker acquisition:

```text
acquire_hot_acquire_candidates(homeBucketId, limit)
  -> list[(workerId, observedScore)]
```

Score range:

```text
MIN_BASE <= score <= base(nowEpochSecond, MAX_LANE_RANK, MAX_VERSION)
```

Only positive due scores are returned. Assignment-dispatch may use these
candidates only after worker-runtime validation and admission.

Recovery recheck acquisition:

```text
acquire_recovery_recheck_candidates(homeBucketId, limit)
  -> list[(workerId, observedScore)]
```

Due RECOVERY_RECHECK has negative score with due absolute coordinate:

```text
-base(nowEpochSecond, MAX_LANE_RANK, MAX_VERSION) <= score <= -MIN_BASE
```

Because Redis sorted-set order is numeric ascending, a plain ascending scan
would see the largest absolute due coordinate first. The intended first-slice
ordering is oldest/smallest absolute due coordinate first, so Redis should use a
reverse scan over the negative range:

```text
ZREVRANGEBYSCORE key -MIN_BASE -base(nowEpochSecond, MAX_LANE_RANK, MAX_VERSION) LIMIT 0 limit
```

Within the same `epochSecond`, reverse scan returns lower laneRank first because
RECOVERY_RECHECK scores are negative. Within the same `epochSecond` and laneRank,
it returns lower version first. First-slice policy should treat lower
RECOVERY_RECHECK laneRank as more urgent / closer to exhaustion. Version is only a
stale fence and must not be used as a priority signal. If a later policy wants
higher retry budget to run first, it must encode the laneRank in reverse order
instead of changing the scan primitive.

First version should prefer demand-driven or owner-controlled recovery recheck. Do
not add a periodic worker-wide recovery-recheck scanner until a later design proves
the liveness invariant and cost. Without demand or an owner-controlled recheck
round, RECOVERY_RECHECK has no wall-clock guarantee to become HOT_ACQUIRE or parked exactly
at its due second.

`observedScore` is an opaque stale fence for the worker-runtime admission or
recheck round. It is the complete signed score, including polarity,
epochSecond, laneRank, and version. Do not trim the sign, version, or any lower
coordinate. It is not a public lifecycle DTO and should not be decoded,
constructed, or interpreted outside worker-runtime score/admission logic.
Callers may only keep it and pass it back to the score owner.

## Candidate Validation

Acquired workers are candidates only. After acquire, worker-runtime validates:

```text
worker declaration exists
worker belongs to homeBucketId / workerGroupId
worker group capability and task demand are compatible
approved scheduling metadata is current enough
score version still matches the current platform-owned scheduling signature
dispatch gate permits scheduling
reachability evidence is acceptable by policy
capacity/admission is available
selection policy still wants this worker
```

Only after validation and admission may worker-runtime return a selected worker
handle to assignment-dispatch.

## Transition Rules

Worker score transitions are polarity-aware, not lifecycle-tag transitions.

Default rewrite rule:

```text
same-polarity rewrite
  preserve sign
  rewrite abs(score) coordinate
  normally require targetEpochSecond >= currentEpochSecond
  preserve version unless worker-runtime refreshes platform scheduling signature
```

Most changes are same-polarity rewrites:

```text
capacity cooldown
admission hold
manual disable / drain
maintenance hold
same-lane retry / recheck
anti-spin backoff
parked / recovery exhausted far-future hold
```

Release rule:

```text
release lowers epochSecond only with exact observedScore match
release preserves polarity
release is not a reopen and not a sign flip
```

Polarity flip rule:

```text
HOT_ACQUIRE -> RECOVERY_RECHECK
  strong negative availability transition
  examples: confirmed disconnect, trusted unavailable, owner-validated
  unreachable

RECOVERY_RECHECK -> HOT_ACQUIRE
  verified reopen transition
  requires declaration, membership, gate, reachability, capacity/admission, and
  policy validation
```

Default polarity flip may preserve `epochSecond` unless policy explicitly
chooses a later one. It must not lower `epochSecond`; release is the only
ordinary way to lower `epochSecond`. Polarity flip must not implicitly inherit
laneRank across lanes. HOT_ACQUIRE laneRank and RECOVERY_RECHECK laneRank have different meanings,
so the target laneRank must be minted explicitly by policy.
Polarity flip normally preserves score version. A version change belongs to the
worker scheduling-signature refresh boundary, not to raw availability evidence.

Raw network-ok, heartbeat, reconnect, keepalive, or latency evidence cannot flip
RECOVERY_RECHECK to HOT_ACQUIRE by itself. They are validation inputs only.

## Score Primitives

Worker-score primitives are intentionally small.

### Observed Rewrite

Admission/recheck rounds that acquired a worker must rewrite through exact
`observedScore`:

```text
rewrite_observed_worker_score(
  homeBucketId,
  workerId,
  observedScore,
  targetPolarity,
  targetEpochSecond,
  targetLaneRank?,
  targetVersion?
)
```

Rules:

```text
storedScore must equal observedScore
observedScore must decode to HOT_ACQUIRE or RECOVERY_RECHECK
targetPolarity must be HOT_ACQUIRE or RECOVERY_RECHECK
targetEpochSecond must be valid
targetEpochSecond must not be less than observed epochSecond
if targetPolarity == observed polarity:
  targetLaneRank defaults to observed laneRank
if targetPolarity != observed polarity:
  targetLaneRank must be supplied by policy
targetVersion defaults to observed version
targetVersion may differ only when worker-runtime is also refreshing the
platform-owned scheduling signature in the same owner transition boundary
write signed score(targetPolarity, targetEpochSecond, targetLaneRank, targetVersion)
```

The exact comparison includes the sign. This prevents stale cross-polarity
writes, such as an old RECOVERY_RECHECK round overwriting a newer HOT_ACQUIRE score that
happens to share the same epochSecond, laneRank, and version. It also prevents an
old acquired round from writing after a scheduling-critical metadata refresh
changed score version.

Typical uses:

```text
capacity full -> HOT_ACQUIRE with future epochSecond
admission hold -> HOT_ACQUIRE with future epochSecond
manual disable / drain observed as HOT_ACQUIRE -> HOT_ACQUIRE(PAUSE_EPOCH_SECOND)
manual disable / drain observed as RECOVERY_RECHECK -> RECOVERY_RECHECK(PAUSE_EPOCH_SECOND)
disconnect with recovery window -> RECOVERY_RECHECK
recovery-recheck failed with budget -> RECOVERY_RECHECK with later epoch/laneRank
recovery-recheck exhausted / parked -> RECOVERY_RECHECK(PAUSE_EPOCH_SECOND) + owner evidence
verified recovery -> HOT_ACQUIRE
```

### Same-Polarity Hold

Owner commands that hold the current worker state without changing availability
polarity use a current-read same-polarity hold:

```text
hold_current_worker_polarity(
  homeBucketId,
  workerId,
  targetEpochSecond,
  targetLaneRank?,
  targetVersion?
)
```

Rules:

```text
storedScore must exist and be signed non-zero
targetEpochSecond >= stored epochSecond
target polarity = stored polarity
targetLaneRank defaults to stored laneRank
targetVersion defaults to stored version
write same-polarity score
```

Use this for manual disable, drain, maintenance, owner hold, capacity hold, or
parked/recovery-exhausted far-future hold. It must not flip RECOVERY_RECHECK to HOT_ACQUIRE
or HOT_ACQUIRE to RECOVERY_RECHECK.

### Release

Release / enable is the only ordinary operation allowed to lower `epochSecond`.
It must use exact observed-score protection:

```text
release_worker_hold(
  homeBucketId,
  workerId,
  observedScore,
  releaseEpochSecond
)
```

Rules:

```text
storedScore must equal observedScore
observedScore must decode to HOT_ACQUIRE or RECOVERY_RECHECK
releaseEpochSecond <= observed epochSecond
targetLaneRank = observed laneRank
targetVersion = observed version
write observed polarity with releaseEpochSecond, targetLaneRank, and targetVersion
```

Release does not reopen a worker:

```text
+base(PAUSE_EPOCH_SECOND, laneRank, version)
  -> +base(releaseEpochSecond, laneRank, version)

-base(PAUSE_EPOCH_SECOND, laneRank, version)
  -> -base(releaseEpochSecond, laneRank, version)
```

If the released score is RECOVERY_RECHECK, the worker still has to pass recovery
validation before returning to HOT_ACQUIRE acquisition.

If owner evidence says the RECOVERY_RECHECK far-future hold is parked,
owner-reset-required, or recovery-exhausted, release also requires owner reset
authorization. That authorization is not encoded in the score; it belongs to
worker-runtime owner evidence and must be checked before writing the release.

### Polarity Flip

Polarity flip is an owner-validated availability transition, not release:

```text
flip_worker_polarity(
  homeBucketId,
  workerId,
  expectedScore,
  targetPolarity,
  targetEpochSecond,
  targetLaneRank,
  targetVersion?
)
```

Rules:

```text
storedScore must equal expectedScore
targetPolarity must differ from stored polarity
targetEpochSecond >= stored epochSecond
targetLaneRank is policy-owned
targetVersion defaults to stored version
write signed score(targetPolarity, targetEpochSecond, targetLaneRank, targetVersion)
```

Use HOT_ACQUIRE -> RECOVERY_RECHECK for strong negative availability evidence. Use
RECOVERY_RECHECK -> HOT_ACQUIRE only after worker-runtime verified reopen. Do not use release
for sign flips. A parked RECOVERY_RECHECK score at `PAUSE_EPOCH_SECOND` must be
released to due RECOVERY_RECHECK before it can pass recovery validation and flip to
HOT_ACQUIRE; polarity flip is not allowed to lower the held epoch.

Polarity flip is the strongest score transition and always uses an exact
expected-score fence. A current-read blind flip is not allowed.

### Scheduling Signature Refresh

Worker scheduling metadata can change without any network event. Worker-runtime
owns a platform-defined scheduling signature over critical worker fields. The
signature definition is built into platform policy, not supplied by external
events or arbitrary business callers.

Typical signature inputs:

```text
worker group membership
approved scheduling attributes
capacity profile id
dispatch gate generation
admission policy id
placement / home-bucket fields that affect candidate validity
```

Excluded inputs:

```text
heartbeat
session id
connection id
latency sample
trace / diagnostic fields
high-frequency load counters
display-only worker fields
```

When worker-runtime detects that the platform-owned scheduling signature changed:

```text
stored signature hash changes in worker metadata/evidence
score version is refreshed as a short rolling fence
polarity is preserved
laneRank is preserved unless the owner policy explicitly changes lane-local budget
epochSecond must not be lowered by the refresh
```

The exact score-version bump rule is intentionally left open for the executable
spec slice. It may use a rolling increment, but it must preserve score-axis
safety:

```text
do not set score version to hash % N directly
do not let version rollover recreate an old observedScore under the same
epochSecond/laneRank/polarity coordinate
do not let metadata refresh become an unbounded high-frequency score writer
```

This refresh exists only to make old acquired rounds stale after critical
scheduling metadata changes. It does not replace admission validation, metadata
truth, trace, or audit history.

## Transition Matrix

| Current polarity | Validated outcome | Target score | Rule |
| --- | --- | --- | --- |
| HOT_ACQUIRE | candidate remains usable and no delay is needed | no rewrite, or HOT_ACQUIRE(nextEpoch, laneRank, version) | same polarity |
| HOT_ACQUIRE | capacity full / contention / claim interval | HOT_ACQUIRE(nextEpoch, laneRank, version) | nextEpoch > currentEpoch |
| HOT_ACQUIRE | manual disable / drain / maintenance hold | HOT_ACQUIRE(PAUSE_EPOCH_SECOND, laneRank, version) | same polarity hold |
| HOT_ACQUIRE | confirmed disconnect / trusted unavailable | RECOVERY_RECHECK(epoch, laneRank, version) or RECOVERY_RECHECK(nextEpoch, laneRank, version) | owner-validated sign flip |
| RECOVERY_RECHECK | recovery validation passes | HOT_ACQUIRE(nextHotEpoch, hotLaneRank, version) | owner-validated sign flip |
| RECOVERY_RECHECK | recovery validation fails and budget remains | RECOVERY_RECHECK(nextRecheckEpoch, laneRank', version) | same polarity |
| RECOVERY_RECHECK | recovery exhausted / parked / owner hold | RECOVERY_RECHECK(PAUSE_EPOCH_SECOND, laneRank, version) | same polarity hold + owner evidence |
| RECOVERY_RECHECK | manual disable / drain / maintenance hold | RECOVERY_RECHECK(PAUSE_EPOCH_SECOND, laneRank, version) | same polarity hold |

There is no PARKED row because PARKED is not a polarity or band. It is owner
evidence attached to a RECOVERY_RECHECK far-future hold.

## Assignment-Dispatch Protocol

Worker score-band participates in assignment-dispatch like this:

```text
task score acquires due task candidate
assignment-dispatch resolves worker demand
worker-runtime acquire_hot_acquire_candidates(homeBucketId, limit)
worker-runtime validates candidates
worker-runtime admits one or more selected workers
task-runtime claims work item
transport receives already-selected worker dispatch
worker-runtime rewrites worker scores only through owner/admission rules
```

Worker score-band does not:

```text
read task backlog
claim task work
select transport route
inspect adapter sessions directly
write task score
create per-task worker candidate sets
```

RECOVERY_RECHECK acquisition is a worker-runtime recovery validation path, not an
assignment-dispatch worker selection path.

## Input Write Taxonomy

| Input kind | May write worker score? | Required path |
| --- | --- | --- |
| hot acquire admission round | yes | observed-score rewrite |
| recovery-recheck validation round | yes | observed-score rewrite |
| capacity full / contention | yes | same-polarity HOT_ACQUIRE rewrite |
| manual disable / drain / maintenance | yes | same-polarity hold |
| manual enable / release | yes | exact observed-score same-polarity release |
| platform scheduling metadata signature changed | yes | worker-runtime signature refresh; preserves polarity; refreshes score version fence |
| confirmed disconnect / trusted unavailable | yes | owner-validated HOT_ACQUIRE -> RECOVERY_RECHECK polarity flip |
| verified owner reopen | yes | owner-validated RECOVERY_RECHECK -> HOT_ACQUIRE polarity flip |
| parked / recovery exhausted | yes | RECOVERY_RECHECK far-future hold + owner evidence |
| transport heartbeat / keepalive | no | evidence only |
| raw connected / session refresh | no | evidence only |
| result arrival / task finality | no | capacity/admission may update; no generic score refresh |
| read projection / trace | no | diagnostics only |

## Atomicity Boundaries

Use atomic write/CAS where stale intermediate state would allow wrong admission:

```text
observed admission rewrite:
  score CAS with complete signed observedScore

capacity admission:
  capacity mutation and score rewrite if the rewrite protects that admission

manual disable / drain / maintenance:
  owner gate fact and same-polarity hold score write

trusted reachability block:
  owner block fact and HOT_ACQUIRE -> RECOVERY_RECHECK polarity flip

verified reopen:
  validated owner facts and RECOVERY_RECHECK -> HOT_ACQUIRE polarity flip

pause release / enable:
  exact observed-score release preserving polarity
```

Do not add a broad distributed lock around worker scheduling by default. The
runtime transition itself carries the concurrency boundary.

## Failure And Stale Handling

Score is an index, so stale candidates are normal.

Rules:

```text
score due but worker declaration missing
  reject candidate; clean opportunistically only when the index is a confirmed
  orphan, otherwise write RECOVERY_RECHECK hold by owner policy

score due but worker disabled/draining
  stale candidate or owner mismatch; rewrite same polarity to far-future hold
  if the owner hold fact is current

HOT_ACQUIRE score due but reachability/readiness validation fails strongly
  flip to RECOVERY_RECHECK by owner policy

RECOVERY_RECHECK score due but recovery validation fails
  rewrite RECOVERY_RECHECK with next recheck epoch, or RECOVERY_RECHECK far-future hold if
  exhausted

score due but capacity is full
  rewrite HOT_ACQUIRE with future epoch or reject according to admission policy

stale observed-score rewrite
  return STALE / no-op; do not overwrite newer score

raw positive transport evidence
  never flips RECOVERY_RECHECK to HOT_ACQUIRE directly
```

Stale handling must be bounded. Do not scan all workers to repair score.
Score absence is not normal unavailability. It may be used only for absent
resources or confirmed orphan cleanup, not as the ordinary way to hold, park,
disable, or disconnect a long-lived worker id.

## Policy Seams

Mechanism owns:

```text
signed score encoding
positive hot acquire range
negative recovery-recheck acquire range
observed-score stale fence
score version fence for platform-owned scheduling metadata refresh
same-polarity release
owner-validated polarity flip boundary
home bucket score key
no transport-driven positive refresh
```

Policy owns:

```text
initial HOT_ACQUIRE score
candidate ranking / laneRank meaning
platform scheduling signature policy
score version refresh rule
cooldown duration
admission hold interval
manual hold / enable rule
RECOVERY_RECHECK retry cadence
RECOVERY_RECHECK initial retry budget
parked / owner-reset evidence and release rule
negative evidence mapping
capacity contention delay
verified reopen policy
```

## First-Version Non-Goals

Do not include these in the first worker-score version:

```text
lifecycle-like worker tags
PARKED band
independent FUTURE_BAND
independent MANUAL_DISABLED_BAND
worker hold hash
WorkerHoldState
transition Redis stream
per-task worker candidate keys
placement-tag score fanout
transport/session evidence in worker metadata
HOT_ACQUIRE laneRank as failure retry truth
score laneRank as reason / reconnect source truth
background worker-wide repair scan
slot registry redesign
```

## Guardrails

- Do not use score absence as worker lifecycle proof.
- Do not let transport heartbeat, session keepalive, or raw connected events
  write HOT_ACQUIRE.
- Do not add worker lifecycle tags. Sign is acquisition polarity; abs(score) is
  the time/laneRank coordinate.
- Do not add a PARKED band. Parked is RECOVERY_RECHECK far-future hold plus owner
  evidence.
- Do not add `MANUAL_DISABLED_BAND`; manual disable is same-polarity hold.
- Do not make score replace capacity/admission validation.
- Do not return candidates without a complete signed observed-score fence to
  worker-runtime admission.
- Do not trim `observedScore` to epoch/laneRank/version. It must remain the full
  signed score including polarity, epochSecond, laneRank, and version; callers
  must not construct or decode it.
- Do not set score version directly from a hash modulo. Store the full
  scheduling signature/hash in worker-runtime metadata or evidence and use score
  version only as a short stale fence.
- Do not let heartbeat, session refresh, trace, diagnostics, or display-only
  metadata bump score version.
- Do not use broad range-mint for acquired admission rewrites.
- Do not create per-task worker candidate keys.
- Do not fan out score across placement-tag buckets in the first slice.
- Do not store transport/session evidence in worker scheduling metadata.
- Do not let read projections or trace materialization drive worker score.
- Do not force task lifecycle score semantics onto worker-runtime polarity.
