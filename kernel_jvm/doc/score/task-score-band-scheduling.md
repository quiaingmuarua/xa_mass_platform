# Task Score-Band Scheduling

Status: current Java production Task Score Owner contract.

## Lifecycle

Task score has two positive lifecycle bands and one terminal region:

```text
PRE_REVIEW
  -> approve
RUNNING_VISIBLE / INITIAL
  -> due ACTIVE Item observed
RUNNING_VISIBLE / NORMAL
  -> explicit close or idle close
TERMINAL
```

`INITIAL` is not a band. It is one fixed time slot inside RUNNING used
only for the first start-condition check.

## Encoding

Positive scores use:

```text
score = tag * TAG_FACTOR + timeSlot * SUFFIX_FACTOR + suffix
```

Current tags and fixed coordinates are:

```text
RUNNING_VISIBLE = 1
PRE_REVIEW      = 2
TERMINAL        = any negative score

INITIAL_TIME_SLOT      = 100
INITIAL_TIME_MILLIS    = 10_000
NORMAL_TIME_SLOT_MIN   = 101
NORMAL_TIME_MIN_MILLIS = 10_100
```

Score encoding, Redis range construction, and complete-score comparison stay
inside `TaskScoreBandCore`. Pacer code receives opaque score observations.

## INITIAL Coordinate

Approve writes:

```text
timeSlot = INITIAL_TIME_SLOT
suffix   = MAX_SUFFIX - priority
```

Priority remains `0..99`; smaller values are higher priority. INITIAL reads
the exact slot in descending score order, so priority `0` with suffix `99` is
more likely to be observed before priority `99` with suffix `0`. Tasks with the
same priority have the same score and no FIFO or relative-order promise.

The fixed slot is a phase coordinate, not a Unix timestamp, deadline, approval
time, or process-start time. The suffix is only INITIAL-local priority. Both
remain stable across Kernel restarts.

## NORMAL Coordinate

An INITIAL Task is promoted only when it has a due ACTIVE Item. Promotion uses
Redis TIME and writes:

```text
band   = RUNNING_VISIBLE
time   = max(redisNowAligned, NORMAL_TIME_MIN_MILLIS)
suffix = 0
```

Scheduling reads one descending range from the latest due RUNNING score down
to zero. It returns only the ordered `taskId -> opaque score` map; it does not
decode a `TaskScoreState`. A separate Score Owner operation filters the exact
INITIAL subset. The Pacer obtains NORMAL identities by removing that subset and
never compares, decodes, calculates, or logs the scores.

The scan accepts `0..100` and never mutates Score. Malformed non-integral
members are skipped without a compensating second read, so a page may contain
fewer than the requested limit. With valid Owner data, due NORMAL scores appear
newest first, followed by the fixed INITIAL slot ordered by priority suffix.

## Owner Surface

Read operations:

```python
get_score_states(task_ids)
preview_score_states(limit)
count_running_tasks()
acquire_scheduling_tasks(limit)
filter_initial_task_scores(observed_task_scores)
```

Lifecycle operations:

```python
initialize_score(task_id, suffix, lease_duration_millis)
start_observed_pre_review_task(
    task_id,
    observed_pre_review_score,
    priority,
)
promote_observed_initial_tasks(observed_initial_scores_by_task_id)
close_score(task_id, terminal_score)
close_observed_score(task_id, observed_score, terminal_score)
```

RUNNING pacing operations:

```python
rewrite_same_band_time_millis(task_id, expected_band, target_time_millis)
park_observed_idle_task(task_id, observed_score)
try_release_idle_park(task_id)
release_observed_score_hold(task_id, observed_hold_score)
```

There is no generic cross-band rewrite or arbitrary band scan. Lifecycle
transitions use narrow owner operations with exact observed-score fences.
RUNNING pacing and idle park reject INITIAL coordinates, so only the exact
initialization operation can enter NORMAL.

## Create And Approve

Task creation remains score-first:

```text
initialize a short PRE_REVIEW lease
-> create-only Descriptor fields
-> exact release of the creation lease
```

Approve first performs a read-only soft-limit precheck:

```text
count_running_tasks()
-> count >= 100: return the existing retryable approval result
-> count < 100: continue
```

The count covers INITIAL, NORMAL due scores, future holds, pause, and idle
park. It is a policy observation rather than a reservation or capacity fence.
Only terminal close removes a Task from that count.

Approval then calls `start_observed_pre_review_task`. Its same-key Lua requires
the complete stored score to equal the observed PRE_REVIEW score and writes the
fixed RUNNING INITIAL slot with the Owner-derived priority suffix. It does not
count other Tasks. Concurrent approvals that observed a count below 100 may
all succeed, so RUNNING may
temporarily exceed the soft limit. This is accepted drift, not a scheduling
safety violation.

## Initialization

Task Initialization receives only the Score Owner-filtered INITIAL map:

```text
TaskInitializationPolicy.initialize(initial taskId -> opaque score)
-> one bounded hasDueActiveItems(all task ids)
-> keep due ids with their original opaque score
-> one promoteObservedInitialTasks(exact scores of ready subset)
-> not ready: keep the fixed INITIAL slot and priority suffix
```

The batch promotion chooses one NORMAL coordinate from Redis TIME before its
single batch Lua. That Lua only compares exact observations against the fixed
INITIAL range and writes the supplied target score; it does not construct or
decode Score fields. Each Task still has an independent exact-score result.
There is no Task admission SPI, System admission policy, capacity reservation,
or priority recheck. A Task without a due Item is rediscovered on a later
joint scheduling scan.

## Dispatch And Idle Lifecycle

Only NORMAL observations enter ordinary scheduling. When Task dispatch finds
no claimable Item, it checks the complete ACTIVE Item band:

```text
ACTIVE exists
  -> normal same-band pacing

no ACTIVE + CLOSE_WHEN_IDLE
  -> exact observed-score terminal close

no ACTIVE + PARK_WHEN_IDLE
  -> exact move to the private idle park
  -> one ACTIVE post-check
  -> exact park release if append raced with park
```

Idle park remains RUNNING at `MAX_TIME_SLOT - 1` with the maximum theoretical
suffix. Pause remains RUNNING at `MAX_TIME_SLOT`. Both stay outside due scans
but both consume a RUNNING seat until explicit close.

Task Call submission remains:

```text
tryReleaseIdlePark
-> appendItems
-> tryReleaseIdlePark
```

INITIAL lies below the private park coordinate, so both release calls are
owner no-ops for an INITIAL Task. Its new Item is discovered by Initialization.

## Runtime Preview

`preview_score_states(1..100)` performs one descending ZSET read. It is a
bounded operational window, not pagination or full inventory. Server projects
an INITIAL score as `running-initial` and does not expose its fixed slot
as a timestamp. NORMAL RUNNING keeps the existing `running_visible` view.

## Failure And Concurrency

- Exact INITIAL start or promotion loses with `STALE` after pause, close, a
  newer transition, or any score drift.
- A soft-limit rejection leaves PRE_REVIEW unchanged and is safe to retry;
  concurrent approval or close may make the observed count immediately stale.
- Initialization observations may become stale before execution; exact CAS
  discards them without repair writes.
- Terminal scores never reopen.
- Score and Descriptor/Item owners remain independent; no cross-key Lua is
  introduced.
- Existing ADMISSION data is not decoded or migrated. Deployment must clear
  old Task score data before enabling this contract.

## Writer Matrix

| Owner | Allowed Task score write |
| --- | --- |
| Task creation | initialize and exact-release PRE_REVIEW lease |
| Task approval | RUNNING soft-limit read, then exact PRE_REVIEW to INITIAL |
| Task initialization | exact INITIAL to NORMAL |
| Task dispatch | NORMAL pacing, exact idle park, or exact idle close |
| Task Call submission | idempotent private-park release before/after append |
| Explicit lifecycle command | close any positive score |
| Allocation, serviceability, result, transport | none |

## Guardrails

- Do not restore an ADMISSION band or generic cross-band rewrite.
- Do not interpret the fixed INITIAL slot as wall-clock evidence or its suffix
  as a general RUNNING lane.
- Do not admit INITIAL Tasks into allocation, dispatch, or serviceability.
- Do not treat the RUNNING soft-limit read as a reservation or hard invariant.
- Do not move TaskItem retry or Worker lease truth into Task score.
