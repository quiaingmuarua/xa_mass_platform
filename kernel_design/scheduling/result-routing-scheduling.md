# Result-Routing Scheduling

Status: new-kernel mechanism note. This document describes the target
result-routing scheduling plane for the clean kernel core. It is not current
implementation truth and not an implementation roadmap.

## Purpose

Result-routing scheduling decides where an incoming result goes next:

```text
incoming result evidence
  -> current work hash compare
  -> accepted finality
  -> retry scheduling
  -> duplicate / stale no-op
  -> discard / reject
  -> unresolved manual lane
```

It answers:

```text
does this result close work, retry work, do nothing, or require unresolved
handling?
```

It does not answer:

```text
which worker should run new work?
which task should enter a scheduling round?
how transport sessions are managed?
what a read model should display?
```

Result routing is a scheduling plane because retry, finality, and ignored
results decide whether work leaves runtime, re-enters scheduling, or is
explicitly not acted on.

## Inputs

Result routing consumes normalized result evidence:

```text
ResultEvidence
  taskId
  workItemId
  workerId
  claimExpiresAtMillis?  # only when the deliver seed carried a time bound
  resultStatus
  resultPayload or outputRef
  errorCode
  observedAt
  transportCorrelation
```

It validates the evidence against owner truth:

```text
current work hash row
selected worker identity
optional claim_expires_at evidence
retry budget
finality policy
recent final barrier
task terminal / discard fence
```

Transport may normalize protocol frames into `ResultEvidence`, but transport
does not decide finality, retry, or duplicate semantics.

## Routing Outcomes

The target plane produces one explicit outcome:

```text
RESULT_ACCEPTED_FINAL
RESULT_RETRY_SCHEDULED
RESULT_DUPLICATE_FINAL_NOOP
RESULT_STALE_CLAIM_NOOP
RESULT_REJECTED_NO_CURRENT_CLAIM
RESULT_REJECTED_HASH_MISMATCH
RESULT_IGNORED_TASK_CLOSED
RESULT_UNRESOLVED_REVIEW
```

Each outcome has one owner-visible effect. Avoid side effects hidden behind
generic "handle result" calls.

## Mainline

```text
1. receive normalized result evidence
2. validate task/work ids
3. load current work hash row from the work-item owner
4. check recent-final barrier for duplicates
5. compare selected worker and optional claim_expires_at with the current hash
6. classify result against finality and retry policy
7. atomically remove/update the current work hash row
8. write final receipt, schedule retry frame, or record no-op
9. emit score rewrite requests only through owner-specific handoffs
10. return routing outcome
```

Step 8 is intentionally narrow. A result does not generically refresh task
score. It may:

```text
schedule retry visibility through the work-item/retry owner
close task visibility through lifecycle/finality owner
release worker capacity through worker owner
```

Those are owner handoffs, not direct result-to-score writes.

## Finality Boundary

Finality means the logical work item has reached an owner-approved terminal
state:

```text
success final
failure final
cancelled final
expired final
discarded final
```

Finality is not the same as:

```text
transport ack
worker process completed locally
review row written
trace event emitted
result payload stored
```

Result routing owns finality classification and the recent-final barrier. Read
models, trace, or review surfaces consume this as evidence after acceptance.

## Retry Boundary

Retry is scheduled only when all required facts hold:

```text
current work hash still exists
current selected worker matches result.workerId
claim_expires_at matches if the work is time-bounded
result is retryable by policy
retry budget remains
task/work fences permit retry
retry visibility time is computed
```

Retry output:

```text
RetryFrame
  taskId
  workItemId
  retryCount
  visibleAt
  payload or payloadRef
  reason
```

The retry frame belongs to the work-item/retry owner. Task score-band may later
be notified that retry visibility exists, but result routing must not rewrite a
live task score just because a result arrived.

## Duplicate And Stale Handling

Duplicate and stale results are normal distributed-system inputs.

Rules:

- duplicate final result after accepted finality returns a no-op outcome;
- stale result after the work hash was moved/reclaimed returns stale no-op or
  rejected-hash-mismatch;
- result with no current claim is rejected unless a recent-final barrier proves
  the logical work already converged;
- time-bounded work may reject a result when `now > claim_expires_at` or when
  the seed's `claim_expires_at` no longer matches the current hash;
- non-time-bounded work is single-claim until result/cancel/manual
  intervention in the first kernel core;
- late result after task closed cannot reopen task/work scheduling;
- no-op outcomes may emit trace/diagnostic evidence but must not mutate active
  runtime truth.

## Owner Handoffs

Result routing may hand off evidence to these owners:

```text
work-item / retry owner
  schedule retry frame
  remove or rewrite current work hash row
  write recent-final barrier

task score / lifecycle owner
  close task visibility if aggregate finality says the task is done
  never generic refresh on result arrival

worker score / capacity owner
  release capacity or classify stale worker evidence

trace / diagnostics owner
  observe routing outcome after the owner mutation is accepted
```

Result routing must not call read-view materialization to decide acceptance.

## Interaction With The Four Scheduling Planes

```text
task-score-band-scheduling
  may later acquire the task again if retry/work visibility says there is work

worker-score-band-scheduling
  may reopen or cool down worker capacity after result release evidence

assignment-dispatch-scheduling
  created the deliver seed whose evidence this result may carry back

result-routing-scheduling
  accepts, retries, ignores, or rejects the result
```

The result path is not a shortcut back into assignment. It can only create
retry/finality evidence that later scheduling planes consume through their own
owners.

## Python Kernel First Cut

The first Python kernel can expose:

```text
route_result(result: ResultEvidence) -> ResultRoutingOutcome
```

Minimal collaborators:

```text
work_hash.get_current(task_id, work_item_id)
work_hash.compare_and_apply(...)
retry_store.schedule(...)
final_store.record(...)
worker_runtime.record_release_evidence(...)
worker_runtime.release_admission(...)
task_score.close_or_notify(...)
```

Result routing must not call worker score directly. It emits release evidence
for the worker-runtime / assignment owner; that owner decides whether capacity
or admission score should be released.

Keep it synchronous and in-memory first. Do not introduce queues or background
repair loops before the routing outcomes are deterministic.

## Guardrails

- Do not accept results from read models or review rows.
- Do not let transport decide retry/finality.
- Do not let result arrival directly refresh live task score.
- Do not use absence of current claim as terminal proof unless recent-final
  barrier confirms prior convergence.
- Do not let duplicate result mutate final state twice.
- Do not let stale result overwrite a newer current work hash row.
- Do not make result routing select workers.
- Do not make trace emission the commit point.
