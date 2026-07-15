# Result-Routing Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Parent contract: [Task Item Score-Band Scheduling](task-item-score-band-scheduling.md).
Redis shape: [Seed Result Runtime Redis Shape](../runtime-redis/seed-result-runtime-redis-shape.md).

## Purpose

Result routing converts bounded `SeedResult` evidence into owner operations:

```text
SeedResultRuntime unified queue
  -> ResultRoutingPacer bounded consume
  -> decode opaqueResultContext inside result-routing
  -> success precedence or exact retry
  -> TaskItemScoreBandCore
  -> WorkerScoreCore exact release
```

It does not select Workers, claim Items, persist result payloads, query Task
state, refresh Task score, or reproduce score encoding rules.

## Contracts

```python
SeedResult(
    opaque_result_context: str,
    outcome_code: str,
    opaque_result_payload: str | None,
)

SeedResultRuntime.append_seed_results(results) -> int
SeedResultRuntime.consume_seed_results(limit) -> tuple[SeedResult, ...]
```

The queue is one logical FIFO and is not partitioned by `endpointManagerId`.
The runtime stores opaque strings only; it does not decode context or classify
outcomes.

The built-in result context contains:

```text
taskId
messageId
workerId
claimScore
workerLeaseScore
taskItemClaimUntilMillis
```

`claimScore` and `workerLeaseScore` remain opaque exact-CAS fences. Only the
result owner decodes the envelope, and it passes each score unchanged to its
declared score owner.

## Outcome Rule

```text
outcomeCode == "200"
  request FINAL_SUCCESS promotion

any other non-empty outcomeCode
  request same-band retry with the original claimScore
```

The first executable spec has no result payload projection and no separate
final-failure result classification. Retry preserves remaining budget with
`remainingBudgetDelta=0`. When ACTIVE budget is exhausted, the existing Item
dispatch acquire path promotes the Item to `FINAL_FAILED`.

Within one consumed batch, evidence is collapsed by `(taskId, messageId)`:

```text
any "200" exists
  retain success

otherwise
  retain the last failure evidence
```

This is batch-local precedence only. Cross-band final precedence remains owned
by `TaskItemScoreBandCore`, so a late success may promote a previously failed
Item without reopening Task scheduling.

## Routing Round

```text
1. consume at most config.batchLimit SeedResults
2. decode valid opaqueResultContext values; discard malformed contexts
3. collapse duplicate Item evidence using success precedence
4. batch FINAL_SUCCESS promotions by taskId
5. batch non-200 exact retries by taskId
6. per Task, set retry due to max(now, that Task's retained claimUntil) + retryDelayMillis
7. count only TRANSITIONED Item operations
8. load bounded TaskDescriptors for every successfully decoded result
9. group unique Worker lease fences by descriptor.workerGroupId
10. call WorkerScoreCore.release_score_holds with every original lease fence;
    duplicate Worker ids are split into bounded exact-release rounds
```

Worker release is attempted for all successfully decoded results, including
Item `STALE` and `NOOP` outcomes. Missing TaskDescriptor, stale Worker lease, or
release failure does not roll back Item movement; Worker lease expiry remains
the recovery fallback.

Result routing does not reread Item score before choosing an operation:

```text
success
  promote_item_outcomes(taskId, messageIds, FINAL_SUCCESS, now)

failure
  rewrite_observed_item_scores(
      taskId,
      {messageId: claimScore},
      retryDueMillis,
      remainingBudgetDelta=0,
  )
```

## Application Lifecycle

`ResultRoutingApplication` owns one independently paced background loop.
Startup order, shutdown order, partial-start rollback, and shared stop timeout
are defined by [Kernel Application Assembly](../kernel-application-assembly.md).
Built-in scheduling coordinates remain:

```text
intervalMillis = 100
batchLimit = 100
retryDelayMillis = 1000
```

Only `resultRouting.intervalMillis` is public JSON. Batch and retry policy stay
internal to result-routing assembly.

## Deferred Policy

- Batch limit, retry delay, and cadence may change without changing result
  classification or score-owner interfaces.
- Result payload projection requires a separate read/projection owner.
- Pending/ack or durable result history requires an invariant that bounded Item
  claim and Worker lease expiry cannot satisfy.

## Failure Semantics

```text
malformed result envelope
  consumed and discarded

Item exact retry is stale/no-op
  no score rewrite; Worker exact release is still attempted

missing TaskDescriptor
  skip Worker release; lease expiry recovers

process crash after queue pop
  Item claim and Worker lease expiry recover scheduling

result payload
  ignored after validation of the SeedResult envelope; no projection exists
```

`SeedResultRuntime` is deliberately best-effort. It has no pending/ack queue,
retry queue, cross-key transaction, or durable result ledger.

## Guardrails

- Do not partition the public result queue by endpoint manager.
- Do not let the adapter parse `opaqueResultContext` or mutate score.
- Do not add a result projection until a separate owner and caller require it.
- Do not inspect current Item score before invoking its owner operation.
- Do not duplicate same-tag exact fencing or cross-tag precedence.
- Do not refresh or close Task score because a result arrived.
- Do not make Worker release a precondition for accepted Item movement.
- Do not add pending/ack reliability to this best-effort evidence queue without
  a named invariant that score expiry cannot satisfy.
