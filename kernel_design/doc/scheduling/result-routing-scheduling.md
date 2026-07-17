# Result-Routing Scheduling

Status: active new-kernel mechanism contract; Python executable spec
implemented; policy coverage partial.

Parent contracts:
[Task Item Score-Band Scheduling](task-item-score-band-scheduling.md) and
[Worker HOT_ACQUIRE Lease Protocol](worker-hot-acquire-lease-protocol.md).
Redis shape: [Seed Result Runtime Redis Shape](../runtime-redis/seed-result-runtime-redis-shape.md).

## Purpose

Result routing converts bounded `SeedResult` evidence into independent Item and
Worker owner operations:

```text
SeedResultRuntime
  -> decode ResultContext and classify outcomeCode
  -> TaskItemScoreBandCore final-success or exact retry
  -> WorkerScoreCore exact release or exact RECOVERY_RECHECK demotion
```

It does not select Workers, claim Items, persist result payloads, refresh Task
score, parse score internals, or own Worker scheduling-serviceability truth.

## Protocol

```python
SeedResult(
    opaque_result_context: str,
    outcome_code: str,
    opaque_result_payload: str | None,
)
```

The unified queue is not partitioned by endpoint manager. The result context
carries `taskId`, `messageId`, `workerId`, opaque `claimScore`, opaque
`workerLeaseScore`, and `taskItemClaimUntilMillis`.

Outcome classification is exact:

```text
"200"  -> SUCCESS
"1xxx" -> WORKER_FAILURE
"3xxx" -> ADAPTER_REJECTION
```

`1xxx` and `3xxx` are exactly four ASCII digits. Other values are invalid or
reserved and authorize no Item or Worker mutation. Result routing understands
only the class, never the business or adapter subcode.

## Routing Round

```text
1. consume at most batchLimit SeedResults
2. decode valid ResultContext values and classify outcomeCode
3. discard malformed context or invalid outcome protocol
4. collapse Item evidence by (taskId, messageId)
5. apply FINAL_SUCCESS for retained 200 evidence
6. apply exact same-band retry for retained 1xxx / 3xxx evidence
7. independently classify each correlated Worker lease disposition
8. load bounded TaskDescriptors to resolve WorkerGroup ownership
9. exact-release 200 / 1xxx Worker leases
10. exact-CAS 3xxx Worker leases to RECOVERY_RECHECK
```

Within one batch, any `200` wins for the same Item; otherwise the last valid
failure evidence is retained. Cross-band Item precedence remains owned by
`TaskItemScoreBandCore`.

Retry uses the original claim fence:

```text
retryDueMillis
  = max(nowMillis, taskItemClaimUntilMillis) + retryDelayMillis

remainingBudgetDelta = 0
```

The budget was consumed at claim time and is not restored. Result arrival does
not make the Item immediately retryable.

## Worker Disposition

Worker disposition is independent from Item transition success:

```text
200 / 1xxx
  -> this attempt crossed the Worker execution boundary
  -> release_score_holds(original workerLeaseScore, now)

3xxx
  -> Adapter confirmed the Item did not enter Worker execution
  -> demote_observed_worker_leases_to_recovery(original workerLeaseScore)
```

For the same `(workerGroupId, workerId, workerLeaseScore)`, Worker execution
evidence (`200` or `1xxx`) wins over `3xxx`, because it proves that this attempt
crossed the Worker execution boundary. It does not prove persistent physical
connectivity. Duplicate dispositions are collapsed before owner calls.

Worker `STALE`, missing TaskDescriptor, or owner-operation failure does not
roll back Item movement. The opaque fence prevents an old result from releasing
or demoting a newer Worker lease.

## Failure Semantics

```text
malformed context or invalid outcomeCode
  -> consume and discard; no Item or Worker mutation

Item transition STALE / NOOP
  -> Worker disposition is still attempted

missing TaskDescriptor
  -> skip Worker disposition; lease expiry recovers

Adapter process dies without SeedResult
  -> UNKNOWN; Item claim and Worker lease expiry recover

process crash after SeedResult pop
  -> Item claim and Worker lease expiry recover
```

`SeedResultRuntime` is deliberately best-effort. It has no pending/ack queue,
repair scanner, cross-key transaction, or durable result ledger.

## Application And Deferred Policy

`ResultRoutingApplication` owns one independently paced loop. Lifecycle order
is defined by [Kernel Application Assembly](../kernel-application-assembly.md).
Built-in coordinates are `intervalMillis=100`, `batchLimit=100`, and
`retryDelayMillis=1000`; only the interval is public JSON.

Deferred policy is limited to cadence/batch tuning and a future separate result
projection owner. Reliable pending/ack requires a new named invariant and does
not belong in the current score-expiry mechanism.

## Guardrails

- Do not let Adapter mutate Worker score directly.
- Do not parse exact `1xxx` or `3xxx` subcodes in result routing.
- Do not treat missing SeedResult as adapter rejection.
- Do not make Worker disposition a precondition for Item movement.
- Do not inspect current Item score before invoking its score owner.
- Do not refresh or close Task score because a result arrived.
- Do not partition the SeedResult queue by endpoint manager.
