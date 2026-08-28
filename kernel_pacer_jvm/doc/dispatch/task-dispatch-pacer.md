# Task Dispatch Policy

Status: active Java Kernel Task Dispatch policy; policy coverage is explicit
below.

## Purpose

`TaskDispatchPolicy` owns one bounded decision round over the NORMAL complement
of the coordinator's due `RUNNING_VISIBLE` Task scan.
Every dispatch-visible NORMAL RUNNING Task uses suffix `0`; INITIAL uses an
Owner-private priority suffix and never enters this policy. Task score no
longer encodes an idle recheck lane.

```text
Task has ACTIVE Item
  -> ordinary dispatch pacing

Task has no ACTIVE Item and CLOSE_WHEN_IDLE
  -> exact terminal close

Task has no ACTIVE Item and PARK_WHEN_IDLE
  -> exact private idle park
```

The private park removes an idle reusable Task from periodic dispatch scans but
remains inside the RUNNING lifecycle count. It is not a retry counter, queue
state, pause, or hard lock against concurrent Item submission.

## Contract

```python
TaskDispatchConfig(
    per_task_dispatch_limit,
    item_claim_lease_duration_millis,
)
```

Policy and Mechanism boundary:

```text
TaskDispatchPolicy
  expiry/exhaustion decisions
  PRECOMPUTED or DIRECT Candidate strategy
  Item/Worker pairing and per-Task limit
  CLOSE_WHEN_IDLE or PARK_WHEN_IDLE branch

WorkerCandidateMechanism
  bounded observations and Candidate Cache
  exact Worker lease/renew and canonical descriptor reload

TaskExecutionMechanism
  Item observation/finality and exact claim
  Worker fence verification
  ResultContext and DeliveryCommand construction/publication
  Task pacing, ACTIVE recheck, close and idle park repair
```

The Policy has no mechanical Owner dependency. Score, Candidate Cache,
canonical resource reload, Command publication and exact transition details
remain behind the two finite Mechanisms.

## Round Flow

One round computes its dispatch time and Item claim deadline once:

1. Consume the immutable verified RUNNING observation batch.
2. Ask `TaskExecutionMechanism` to observe record-backed due ACTIVE Items.
3. Policy identifies zero-budget and expired Items; Mechanism exact-promotes
   the selected observations to `FINAL_FAILED`.
4. Policy matches/selects Workers and pairs them with Items. Mechanisms
   exact-validate Worker leases, exact-claim only Worker-backed Items, construct
   DeliveryCommands, and publish endpoint-grouped sparse maps.
5. In `finally`, Policy reports the semantic dispatch outcome to
   `TaskExecutionMechanism`, which advances ordinary Task pacing or performs
   complete ACTIVE recheck, exact close/park, and post-park repair.

Observation precedes Worker acquisition. Worker acquisition precedes Item
claim. Item claim precedes command identity generation and mailbox publication.
Expiry classification therefore cannot acquire a Worker, consume retry budget,
or emit a Worker command. An already-claimed attempt is not revoked when its
Item later expires.

## ACTIVE Item Truth

`has_active_items(taskIds)` checks the complete Task-local ACTIVE score band:

```text
due ACTIVE Item             -> true
future claim/retry Item     -> true
ACTIVE Item with budget 0   -> true
FINAL Item                  -> false
missing Item score          -> false
```

The query does not load Item payload. A Task is idle only when no ACTIVE score
exists, not merely when no Item is currently due or claimable.

## Idle Transitions

The Mechanism uses the exact opaque Task reference from the shared
observation:

```text
ACTIVE exists
  -> keep RUNNING suffix 0
  -> ordinary same-band time pacing

no ACTIVE and CLOSE_WHEN_IDLE
  -> exact terminal close

no ACTIVE and PARK_WHEN_IDLE
  -> exact move to the Kernel-private RUNNING idle park
```

`park_observed_idle_task` and `close_observed_score` lose if
pause, explicit close, submission idle-park release, or a newer scheduling round has
changed the observed score. The post-park ACTIVE recheck repairs the common
park/append interleaving without creating a cross-owner transaction. The park
coordinate is `MAX_TIME_SLOT - 1`, suffix `MAX_SUFFIX`; only the score owner can mint
or release it. The recheck calls `try_release_idle_park`; it does not retain or
pass the private park coordinate. Explicit close remains available for either
disposition.

Ordinary `TaskRuntime.append_items` is a pure data write and does not release a
private Task park. Managed Task Call callers use
`TaskCallItemSubmission`, whose fixed composition is:

```text
try_release_idle_park
-> append at most 100 Items
-> try_release_idle_park
```

The first call gates append and releases an existing park. The second repairs a
park installed between the first call and append. Submission does not read the
Task descriptor, Task score state, or ACTIVE band. PRE_REVIEW, RUNNING INITIAL,
and ordinary nearer positive scores are accepted as score no-ops; terminal,
missing, and RUNNING pause coordinates fail before append. Released
Tasks re-enter the ordinary due scan; there is no urgent set or second Task
selection path.

## Ordinary Dispatch

The immutable Worker allocation mechanism selects the internal Worker path:

```text
PRECOMPUTED_TASK_RULE
  -> one TaskId-correlated PRECOMPUTED request
  -> Task allocationRule

DIRECT_ITEM_RULE
  -> one messageId-correlated DIRECT request per Item
  -> TaskItem allocationRule
```

Neither path falls back to the other. CandidateId-to-messageId binding is
preserved through exact Item claim; Workers and Items are not flattened and
re-zipped.

Each successful assignment produces one `DeliveryCommand`:

```text
messageId             canonical UUID generated after exact Item claim
src                   TASK
dst                   WORKER
messageType           TaskItem.eventCode
executeBeforeMillis   the same Item claim deadline used by this round
payload               deterministic TaskItem payload JSON
forward               encoded ResultContext
```

`workerId` is the mailbox field and transport route; it is not duplicated in
the command. `endpointManagerId` selects the mailbox bucket and is not part of
the command.

Task Dispatch ends when each Adapter mailbox append result is handled. It does
not consume the mailbox, call a Worker, decode a Worker result, or append a
`DeliveryReport`; those operations belong to Worker Delivery Dispatch.

## Failure And Concurrency

- Missing Task, descriptor, Item, Worker, or claim success is a bounded no-op.
- A stale park/release or close CAS cannot overwrite pause, explicit close, or a newer
  Task score.
- A pause or close after discovery does not retract evidence already published
  by the bounded round.
- Unused or failed Worker leases expire naturally; this policy does not release
  or demote them.
- One WorkerId may appear only once in the entire round. `APPENDED` and
  `REPLACED` both count as publication; replacement remains best-effort mailbox
  behavior bounded by command deadline and score-owner fences.
- Cross-Adapter mailbox writes are not atomic. A runtime failure does not roll
  back Adapter buckets whose append already completed.
- Task Call submission and idle lifecycle span independent Task, Task score,
  and TaskItem owners. Exact fences plus pre/post checks narrow races; they do
  not create a cross-key transaction.
- Explicit terminal close has precedence. Existing Items, claims, commands, or
  late results cannot reopen Task score.

## Guardrails

- Keep NORMAL RUNNING scheduling at suffix zero. INITIAL priority suffixes are
  private to the Task Score Owner, and the private idle park uses `MAX_SUFFIX`
  only as its raw range boundary.
- Do not classify future ACTIVE Items as idle.
- Do not make ordinary Item append a hidden scheduling command.
- Do not add a wake inbox, urgent set, priority queue, or second Task scan.
- Do not infer idle disposition from Worker allocation mechanism.
- Do not access CandidateWorker cache or Worker score directly.
- Do not add PRECOMPUTED-miss DIRECT fallback.
- Do not call Worker Delivery Dispatch or Result Routing directly.
- Group only by the endpointManagerId snapshot in `CandidateWorkerEntry`; do
  not read live Adapter, connection, or session state.
