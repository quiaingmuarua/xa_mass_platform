# Result-Routing Scheduling

Status: new-kernel mechanism note. This document defines result classification
and the handoff to Task Item runtime. It is not current implementation truth and
not an implementation roadmap.

Parent contract: [Task Item Score-Band Scheduling](task-item-score-band-scheduling.md).

## Purpose

Result routing converts result evidence into one bounded owner decision:

```text
incoming result evidence
  -> classify retryable / final-failed / final-success business outcome
  -> invoke one named Task Item runtime operation
  -> map Task Item transition status to accepted / stale / duplicate / unresolved
  -> result barrier / projection update
  -> owner-specific release or closure handoffs
```

It does not select Workers, claim Items, read or decode Item score, reproduce
same-tag/cross-tag transition rules, refresh Task score, or turn transport
delivery into finality truth.

## Inputs

```text
ResultEvidence
  taskId
  messageId
  workerId
  outcome
  resultPayload | resultPayloadRef | null
  claimScore
  observedAtMillis
```

`claimScore` is opaque evidence copied from `DeliverSeed`. Result routing passes
it unchanged to Task Item runtime and never reads or decodes tag, timeSlot, or
suffix. Transport may normalize protocol frames into `ResultEvidence`; it does
not decide retry or finality.

Policy inputs may include:

```text
retryable outcome classification
final-failure classification
late-success acceptance window
Task terminal / discard fence
Worker release or capacity disposition
```

## Owner Split

```text
Task Item score owner
  current Item score
  exact same-tag claim/retry stale fence
  ACTIVE < FINAL_FAILED < FINAL_SUCCESS promotion

result owner
  business outcome classification
  Task Item operation selection
  transition-result mapping to routing outcome
  late-success retention barrier
  result payload and read projection

Task score owner
  Task scheduling visibility and terminal coordinate

Worker owner
  release / retain / capacity decision after accepted result
```

Result policy chooses an Item owner operation. Task Item runtime alone validates
the current score and returns the transition result. Result routing does not
write Redis score directly, compare stored score, or construct target tags,
timeSlots, suffixes, or encoded scores.

## Routing Outcomes

```text
RESULT_RETRY_SCHEDULED
RESULT_FINAL_FAILED
RESULT_FINAL_SUCCESS
RESULT_DUPLICATE_NOOP
RESULT_STALE_NOOP
RESULT_IGNORED_TASK_CLOSED
RESULT_UNRESOLVED_REVIEW
```

Each outcome has one owner-visible effect. Avoid hidden multi-owner writes behind
a generic `handle_result` method.

## Mainline

```text
1. receive normalized ResultEvidence
2. validate taskId / messageId / workerId and result payload shape
3. consult the recent-result barrier and Task retention fence
4. classify retryable failure, final failure, or final success
5. invoke exactly one Task Item owner transition
6. map the returned transition status to accepted, stale, duplicate, or unresolved
7. record result/barrier/projection evidence only when routing policy permits
8. invoke Worker and Task owner handoffs when policy requires them
9. return ResultRoutingOutcome
```

No result path refreshes Task score as a generic side effect.

## Task Item Operations Consumed

Result routing depends on the Task Item contract without reimplementing it:

```text
retryable failure
  -> retry_observed_claim(taskId, messageId, claimScore, retryDueMillis)

final failure
  -> promote_item_outcome(taskId, messageId, FINAL_FAILED, outcomeAtMillis)

final success
  -> promote_item_outcome(taskId, messageId, FINAL_SUCCESS, outcomeAtMillis)
```

Task Item runtime owns exact same-tag claim fencing, retry-budget interpretation,
cross-tag outcome precedence, and all score mutation. It returns an explicit
transition status such as `TRANSITIONED`, `STALE`, `NOOP`, `NOT_FOUND`, or
`CORRUPT`. Result routing maps that status to `ResultRoutingOutcome`; it does not
derive the status by inspecting Item score.

The authoritative fence and outcome-precedence rules live only in
[Task Item Score-Band Scheduling](task-item-score-band-scheduling.md).

## Task Closure And Late Success

Task terminal score remains closed and does not re-enter scheduling because a
late Item success is accepted. Result attribution and aggregate projection may
still improve from failed to success while retained Item truth exists.

Therefore:

```text
Task close
  stops new Task / Item dispatch
  does not immediately delete Item score or result barrier truth

late success inside retention window
  requests FINAL_SUCCESS promotion from Task Item runtime
  may update result aggregate / terminal reason projection
  must not reopen Task scheduling

retention barrier expired
  physical Item/result cleanup may proceed
```

The retention owner, not Task score-band, decides physical deletion timing.

## Transition-Result Mapping

```text
Task Item returns TRANSITIONED
  map to RESULT_RETRY_SCHEDULED / RESULT_FINAL_FAILED / RESULT_FINAL_SUCCESS

Task Item returns STALE
  map to RESULT_STALE_NOOP

Task Item returns NOOP
  RESULT_DUPLICATE_NOOP

Task Item returns NOT_FOUND / CORRUPT
  map to RESULT_UNRESOLVED_REVIEW or the declared owner-specific rejection

result after retention cleanup
  RESULT_IGNORED_TASK_CLOSED or RESULT_UNRESOLVED_REVIEW
```

No-op outcomes may emit bounded trace/diagnostic evidence but do not mutate Task
or Worker scheduling truth.

## Owner Handoffs

After an Item transition succeeds:

```text
result owner
  persist result payload / barrier / projection

Worker owner
  release or retain admission/capacity according to policy

Task lifecycle owner
  observe aggregate/no-work evidence in a later bounded scheduling round
  close Task visibility only through declared Task-score transitions
```

These are owner handoffs. Result routing does not call Worker score or Task score
as an unrestricted mutation path.

## Executable-Spec Gap

The Python executable spec does not yet implement Task Item score, result
routing, DeliverSeed, or TaskItemDispatchPacer. The first implementation must prove:

```text
retryable classification invokes retry_observed_claim with unchanged claimScore
final classification invokes promote_item_outcome with the named outcome
Task Item TRANSITIONED / STALE / NOOP statuses map to declared routing outcomes
only accepted transitions update result barrier / projection
late success after Task close without Task reopen
bounded result barrier retention
```

Same-tag fencing and cross-tag precedence are proved by Task Item runtime tests,
not duplicated in result-routing tests.

## Guardrails

- Do not restore a current-work HASH, retry ZSET, claim-expiry queue, or Attempt
  aggregate beside Item score.
- Do not let result callers construct tag, timeSlot, suffix, or target score.
- Do not read Item score to pre-validate, predict, or reproduce Task Item runtime
  transition results.
- Do not duplicate same-tag fencing or cross-tag precedence in result-routing.
- Do not translate a retryable business result into a final operation merely to
  avoid a `STALE` response.
- Do not let late success reopen Task scheduling.
- Do not physically delete Item/result truth before the late-success retention
  barrier permits cleanup.
- Do not let transport decide retry, finality, or Worker replacement.
- Do not refresh Task score because a result arrived.
