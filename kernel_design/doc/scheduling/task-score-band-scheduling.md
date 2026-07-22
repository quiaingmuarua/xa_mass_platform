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
  admitted to periodic dispatch and empty recheck

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
  consecutive confirmed-empty recheck count
  0 means ordinary TaskItem dispatch lane
  1..99 means empty recheck lane
```

RUNNING suffix is not TaskItem retry budget. TaskItem retry truth belongs to
TaskItem score. It is also not Worker capacity, fairness, or pause state.

## Core Surface

Read operations:

```python
get_score_states(task_ids)
count_running_visible_tasks()
acquire_band_task_candidates(band, before_time_millis, limit)
acquire_dispatch_work_tasks(limit)
```

Mutation operations:

```python
initialize_score(task_id, suffix, lease_duration_millis)
rewrite_score(task_id, expected_band, target_time_millis,
              target_band=None, target_suffix=None)
rewrite_same_band_time_millis(task_id, expected_band, target_time_millis)
rewrite_observed_same_band_suffix(task_id, observed_score,
                                  target_time_millis, suffix_delta)
close_score(task_id, terminal_score)
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

## Running Dispatch And Empty Recheck

`TaskDispatchPacer` acquires due RUNNING Tasks and decodes the suffix:

```text
suffix = 0
  -> ordinary Item observation, Worker acquisition, Item claim, DeliverSeed

suffix > 0
  -> no Worker acquisition or Item claim
  -> complete ACTIVE-band existence query
```

Any ACTIVE Item resets a positive suffix to zero using exact observed-score
CAS. Confirmed emptiness increments suffix and applies linear delay:

```text
nextSuffix = currentSuffix + 1
nextTime = now + nextSuffix * emptyRecheckInterval
```

At the configured maximum:

```text
now >= emptyCloseAtMillis -> terminal close
now < emptyCloseAtMillis  -> remain RUNNING and continue bounded checks
```

This close rule is shared by both TaskTypes. The threshold is consulted only
after complete ACTIVE-band emptiness and the maximum count are established; it
is not a hard deadline.

Ordinary suffix-zero dispatch pacing uses same-band absolute-time rewrite and
preserves suffix zero.

## Transition Rules

### Positive rewrite

`rewrite_score` reads the stored score, requires the expected band, requires a
larger target time, and allows only lifecycle-forward tag movement. It
preserves suffix unless the owner supplies a target suffix.

### Same-Band Time Rewrite

`rewrite_same_band_time_millis` derives the accepted score range internally.
It preserves stored suffix. It is appropriate for routine pacing where a newer
same-band score should win by monotonic time.

### Observed Suffix Rewrite

`rewrite_observed_same_band_suffix` requires:

```text
storedScore == observedScore
suffixDelta != 0
targetTimeSlot > observedTimeSlot
0 <= observedSuffix + suffixDelta <= 99
```

Positive delta records another empty observation. Negative delta resets a
count when ACTIVE Item evidence appears. Exact CAS prevents stale rounds from
overwriting pause, close, or newer recheck evidence.

### Terminal Close

`close_score` changes any positive band to a negative terminal score. Existing
terminal score is an idempotent no-op. The public `KernelApplication.close_task`
chooses the terminal score internally and returns only `TaskCloseResult`.

Explicit close applies to both Task types and every positive band. It does not
roll back Items, claims, DeliverSeeds, or late results. Those facts cannot
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

## Writer Matrix

| Owner | Allowed Task score write |
| --- | --- |
| Task creation | initialize PRE_REVIEW and exact release of creation hold |
| Task approval | PRE_REVIEW -> ADMISSION_VISIBLE, suffix = Task priority |
| Running activation | ADMISSION_VISIBLE -> RUNNING_VISIBLE, suffix 0; reason-independent same-band recheck for every observed non-transitioned Task |
| Task dispatch | RUNNING same-band pacing, exact empty-count increment/reset, shared threshold-based empty close |
| Explicit lifecycle command | close any positive band; exact hold release when authorized |
| Candidate warmer | none |
| Worker/runtime/transport/result routing | none |

## Failure And Concurrency

- Range rewrites lose when band/time no longer matches.
- Observed suffix rewrites lose when the full score fence changed.
- Terminal close is irreversible and takes precedence over later scheduling
  rounds.
- A Task discovered before pause/close may finish its already-bounded Item and
  DeliverSeed work; the later score rewrite cannot reopen terminal state.
- Before `emptyCloseAtMillis`, an empty Task remains RUNNING and continues to
  consume the current soft-limit count. External close evidence may terminate
  either TaskType earlier.

## Guardrails

- Do not expose score encoding or accept caller-minted score coordinates.
- Do not use Task score for Item retry, Worker lease, candidate cache, or
  result truth.
- Do not make candidate warming a Task score writer.
- Do not decrement RUNNING suffix as a remaining budget.
- Do not classify only-due absence as Task emptiness; query the full ACTIVE Item
  band.
- Do not reopen terminal Tasks after append or late result evidence.
- Do not add cross-key Lua for lifecycle convenience.
