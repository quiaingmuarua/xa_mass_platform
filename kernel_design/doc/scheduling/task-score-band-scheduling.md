# Task Score-Band Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

## Purpose

Task score is the ordered lifecycle and scheduling coordinate for one Task:

```text
PRE_REVIEW -> ADMISSION_VISIBLE -> RUNNING_VISIBLE -> TERMINAL
```

It tells the kernel which scheduling domain the Task occupies and where it is
ordered inside that domain. It does not tell the kernel why the Task should be
admitted, which Worker should run an Item, or how business results are stored.

## Encoding

Positive mutable scores use:

```text
score = tag * TAG_FACTOR + timeSlot * SUFFIX_FACTOR + suffix
```

Current tags:

```text
RUNNING_VISIBLE    = 1
ADMISSION_VISIBLE  = 2
PRE_REVIEW         = 3
TERMINAL           = any negative score
```

The lifecycle direction is numerically downward. Positive cross-band writes
may keep or lower the tag; terminal close changes the score negative. Terminal
score cannot return to a positive band.

External callers pass millisecond timestamps. The score core converts them to
the current 100 ms `timeSlot`; slot width and mint rules are private and may
change. A returned score is an opaque fence token, never an externally decoded
contract.

## Coordinates

### Band

```text
PRE_REVIEW
  metadata exists but approval has not admitted the Task

ADMISSION_VISIBLE
  approved and eligible for Task/System admission policy

RUNNING_VISIBLE
  admitted to periodic dispatch, explicitly paused, or privately idle-parked

TERMINAL
  permanently closed
```

### Time

For positive bands, `timeSlot` is the next time the lane may act. Ordinary
writes must increase it. A future coordinate is a hold/pause/recheck delay.
Only exact hold release may move time earlier.

### Suffix

Suffix is a bounded two-digit owner-local coordinate. Its meaning is per band:

```text
PRE_REVIEW
  review owner code, opaque to score core

ADMISSION_VISIBLE
  Task priority in 0..99; lower values run first inside one bounded due window

RUNNING_VISIBLE
  ordinary scheduling uses 0; the private idle-park boundary uses MAX_SUFFIX
```

RUNNING suffix has no retry, capacity, fairness, or pause meaning. The private
idle park uses the maximum theoretical suffix only so its raw score is the
upper boundary of the reserved time slot; Lua does not interpret that suffix.
TaskItem retry truth belongs to TaskItem score.

## Core Surface

Read operations:

```python
get_score_states(task_ids)
count_running_capacity_tasks()
acquire_band_task_candidates(band, before_time_millis, limit)
acquire_dispatch_work_tasks(limit)
```

Mutation operations:

```python
initialize_score(task_id, suffix, lease_duration_millis)
rewrite_score(task_id, expected_band, target_time_millis,
              target_band=None, target_suffix=None)
rewrite_same_band_time_millis(task_id, expected_band, target_time_millis)
park_observed_idle_task(task_id, observed_score)
try_release_idle_park(task_id)
close_score(task_id, terminal_score)
close_observed_score(task_id, observed_score, terminal_score)
release_observed_score_hold(task_id, observed_hold_score)
```

Callers never pass Redis ranges, tags, slots, score bases, or Lua arguments.

## Creation And Approval

Task creation is score-first:

```text
reject an existing descriptor without touching score
-> initialize a missing score with a short PRE_REVIEW lease
-> create the complete Task descriptor without overwriting an existing HASH
-> release only the exact creation hold minted by this call
```

Creation is create-only. An existing descriptor always returns `CONFLICT` and
is never compared, merged, or overwritten. A score-only interruption residue
may be completed only when the score is still `PRE_REVIEW` and the descriptor
key is absent:

```text
initialize missing score with a short PRE_REVIEW lease
  process stops before descriptor write
-> retry observes PRE_REVIEW score and absent descriptor
-> create descriptor with HSETNX semantics
-> preserve the existing score unchanged
```

Descriptor fields are installed through one Redis transaction of per-field
`HSETNX` operations. This keeps descriptor creation atomic without a cross-key
Lua script. Existing non-PRE_REVIEW scores and descriptor-only residue return
`CONFLICT`.

Approval validates metadata and requests:

```text
PRE_REVIEW -> ADMISSION_VISIBLE
target suffix = Task priority, where 0 is highest and 99 is lowest
target time = max(approvalNow, previousTime + oneSlot)
```

Approval is idempotent for ADMISSION/RUNNING and rejects terminal Tasks. The
new lane coordinate comes from approval evidence rather than Task creation age.

## Running Admission

`TaskRunningActivationPacer` is the only normal assignment mechanism that
changes ADMISSION to RUNNING:

```text
bounded time-major ADMISSION observation window
-> priority-order observed window members
-> TaskAdmissionPolicy
-> SystemAdmissionPolicy
-> rewrite_score(... RUNNING_VISIBLE, target_suffix=0)
-> same-band recheck every observed Task not transitioned
```

No Worker is reserved during admission. All Tasks enter RUNNING with suffix
zero. The default Task policy requires one due ACTIVE Item. The score owner
uses timeSlot to select bounded window membership, then priority suffix to order
that window. The default System policy only applies the soft RUNNING count
limit.

Observed Tasks that do not enter RUNNING retain their priority and move to a
future ADMISSION coordinate:

```text
priorityBucket = priority // 10
nextTime = roundNow + oneSlot + priorityBucket * 1000ms
```

This reason-independent rotation prevents rejected due heads from permanently
hiding later Tasks without lowering priority or promising global fairness.

## Running Dispatch And Idle Disposition

`TaskDispatchPacer` acquires due RUNNING suffix-zero Tasks and observes due
Items. When no claimable Item remains, it queries the complete ACTIVE Item band:

```text
ACTIVE exists
  -> ordinary same-band pacing, suffix remains 0

no ACTIVE and CLOSE_WHEN_IDLE
  -> exact observed-score terminal close

no ACTIVE and PARK_WHEN_IDLE
  -> exact observed-score move to the private idle park
```

The idle park is RUNNING at `MAX_TIME_SLOT - 1`, suffix `MAX_SUFFIX`. It is excluded
from due scans and from `count_running_capacity_tasks()`, but remains distinct
from the public pause coordinate at `MAX_TIME_SLOT`. A successful park receives
one bounded post-check; if a concurrent Task Call append created an ACTIVE
Item, the exact park is released. Neither path creates a cross-owner
transaction.

## Transition Rules

### Positive rewrite

`rewrite_score` reads the stored score, requires the expected band, requires a
larger target time, and allows only lifecycle-forward tag movement. It
preserves suffix unless the owner supplies a target suffix.

### Same-Band Time Rewrite

`rewrite_same_band_time_millis` derives the accepted score range internally.
It preserves stored suffix. It is appropriate for routine pacing where a newer
same-band score should win by monotonic time.

### Private Idle Park

`park_observed_idle_task` requires the complete observed ordinary RUNNING
suffix-zero score and derives the private park coordinate internally.
`try_release_idle_park` atomically reads the current score. It releases the
exact private park to a due RUNNING suffix-zero score using Redis time, returns
`NOOP` for a positive score below the park or above the RUNNING band, returns
`STALE` for a missing score, and returns `INVALID` for a terminal or RUNNING
pause score. The owner precomputes the idle-park and RUNNING-band maximum raw
boundaries with `MAX_SUFFIX`; Lua only compares those complete scores and does
not decode tags, time slots, or suffixes. Generic rewrites and generic hold
release cannot mint or release the private park.

### Terminal Close

`close_score` changes any positive band to a negative terminal score. Existing
terminal score is an idempotent no-op. The public `KernelApplication.close_task`
chooses the terminal score internally and returns only `TaskCloseResult`.

`close_observed_score` additionally requires complete observed-score equality.
Task Dispatch uses this narrower operation when idle evidence belongs
to one bounded round; manual close continues to use `close_score`.

Explicit close applies to both Worker allocation mechanisms and every positive
band. It does not
roll back Items, claims, DeliveryCommands, or late results. Those facts cannot
reopen Task score.

### Hold Release

`release_observed_score_hold` accepts only the exact observed positive score,
preserves band and suffix, and may move time to the current slot. A stale
opaque fence cannot release a newer hold.

## Scan Ranges

Band scans are separate and bounded:

```text
ADMISSION scan
  only ADMISSION_VISIBLE before the exclusive time horizon
  timeSlot order determines the bounded window members
  returned window members are priority suffix ascending
  equal priority is timeSlot then taskId ascending

RUNNING dispatch scan
  only RUNNING_VISIBLE due before current time

RUNNING count
  complete RUNNING_VISIBLE band, including future holds
```

Candidate warming uses its own disposable hint schedule. It may validate that
a hinted Task is RUNNING, but it never uses Task score as its cursor and never
mutates Task score.

## Redis And Lua Boundary

Redis ZSET is the score-axis truth. Python derives semantic target coordinates;
Lua is limited to one key and the smallest required atomic check:

```text
initialize if absent
exact score CAS
close positive score
range membership mint
```

No Task descriptor, Item payload, Worker state, candidate cache, or result key
is read or written by Task score Lua.

The Java Redis provider implements only the six caller-driven operations used
by Task create, approve, close and Call submission: state read, initialize,
generic rewrite, positive close, exact hold release and private idle-park
release. Pacer-only candidate, pacing, observed park and observed close
operations remain explicit JVM gaps; Python remains their production owner.

## Writer Matrix

| Owner | Allowed Task score write |
| --- | --- |
| Task creation | initialize PRE_REVIEW and exact release of creation hold |
| Task approval | PRE_REVIEW -> ADMISSION_VISIBLE, suffix = Task priority |
| Running activation | ADMISSION_VISIBLE -> RUNNING_VISIBLE, suffix 0; reason-independent same-band recheck for every observed non-transitioned Task |
| Task dispatch | RUNNING same-band pacing, exact idle park or exact idle close |
| Task Call submission | idempotent private-park release before and after bounded Item append; other valid nearer positive coordinates are no-ops |
| Explicit lifecycle command | close any positive band; exact hold release when authorized |
| Candidate warmer | none |
| Worker/runtime/transport/result routing | none |

## Failure And Concurrency

- Range rewrites lose when band/time no longer matches.
- Observed park and close lose when the full score fence changed. Idle release
  classifies and updates one current Task score atomically.
- Terminal close is irreversible and takes precedence over later scheduling
  rounds.
- A Task discovered before pause/close may finish its already-bounded Item and
  DeliveryCommand work; the later score rewrite cannot reopen terminal state.
- A parked Task remains RUNNING but stays outside the due dispatch range and
  does not consume RUNNING admission soft-limit capacity. Explicit close may
  still terminate it.

## Guardrails

- Do not expose score encoding or accept caller-minted score coordinates.
- Do not use Task score for Item retry, Worker lease, candidate cache, or
  result truth.
- Do not make candidate warming a Task score writer.
- Keep ordinary RUNNING scheduling suffix fixed at zero. The private idle-park
  boundary alone uses `MAX_SUFFIX` as a range sentinel.
- Do not classify only-due absence as Task emptiness; query the full ACTIVE Item
  band.
- Do not reopen terminal Tasks after append or late result evidence.
- Do not add cross-key Lua for lifecycle convenience.
