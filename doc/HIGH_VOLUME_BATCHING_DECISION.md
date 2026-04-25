# High-Volume Batching Decision

Last updated: 2026-04-25

This document records the current decision for `batchSize` and `batchId` in the
high-volume model.

Use it when the task is about:

- deciding whether `batchId` belongs in the hot path
- deciding what batching semantics must remain in the compressed model
- preventing `batchSize` and `batchId` from being treated as the same thing

Use with:

- [./HIGH_VOLUME_MODEL_BASELINE.md](./HIGH_VOLUME_MODEL_BASELINE.md)
- [./HIGH_VOLUME_SCHEMA_PLAN.md](./HIGH_VOLUME_SCHEMA_PLAN.md)
- [./HIGH_VOLUME_MIGRATION_MAP.md](./HIGH_VOLUME_MIGRATION_MAP.md)
- [./INTERNAL_API_REFERENCE.md](./INTERNAL_API_REFERENCE.md)

## 1. Decision Summary

- `batchSize` stays in the model
- `batchId` is downgraded from default hot-path significance
- the default high-volume mainline does not require `batchId` for correctness

Short version:

- `batchSize` is strategy
- `batchId` is optional correlation unless batch itself becomes a first-class execution unit

## 2. Why `batchSize` Stays

`batchSize` has clear scheduling value.

It currently means:

- per-worker cap for one dispatch round
- a way to limit how much work one matched worker receives at once
- a simple mechanism for refill and load spreading

That value remains useful in a high-volume queue-driven model.

Keep it because it helps express:

- fairness
- burst control
- worker-side capacity matching
- dispatch refill behavior

## 3. Why `batchId` Is Downgraded

`batchId` does not currently carry kernel-critical meaning.

In the current code it is mainly a label attached during one dispatch round:

- one dispatch slot gets a generated batch label
- that label is copied onto assigned messages and attempts
- the label is forwarded in `TaskDispatchItem`

That is useful for correlation, but it is not currently the source of truth
for:

- task convergence
- result idempotency
- retry sequencing
- lease ownership
- terminal policy

So in the compressed model it should not remain a required hot-path field just
because it exists today.

## 4. What `batchId` Is Good For

`batchId` still has value in these scenarios.

### 4.1 Correlating One Dispatch Group

Useful when you want to know:

- which items were handed to the same worker in the same dispatch round
- which local dispatch group produced an error burst
- which group was retried or timed out together

### 4.2 Batch-Oriented Worker Or Provider Integrations

Useful when the downstream executor really consumes a group as one batch:

- one provider call handles N items together
- one crawler or agent worker wants to log or checkpoint one pulled group
- one adapter reports metrics at the group level

### 4.3 Trace And Perf Analysis

Useful for:

- refill diagnostics
- dispatch spread analysis
- queue pressure analysis
- worker-side batch behavior inspection

## 5. What `batchId` Should Not Be Used For

Do not make `batchId` the source of truth for:

- message identity
- result acceptance
- retry eligibility
- active lease ownership
- task completion
- worker capability identity

Those concerns should stay on:

- `taskId + messageId`
- `leaseToken` or equivalent active-attempt token
- task-level counters and terminal policy

## 6. Default High-Volume Rule

In the default high-volume mainline:

- `batchId` is optional
- omission of `batchId` must not break correctness
- adapters may attach it when useful
- traces may include it when available

Working rule:

- if the platform can remain correct without it, it does not belong in the required hot-path schema

## 7. When `batchId` May Be Promoted Again

`batchId` may become a stronger field only if one of these becomes an explicit
platform feature:

- batch-level ack semantics
- batch-level failure handling or rollback
- batch-level QoS, timeout, or cost policy
- batch-level worker contracts
- batch-level API or UI operations

If that happens:

- document the new semantics explicitly
- define batch identity as a first-class runtime concept
- update state-machine, trace, API, and verification docs together

## 8. Migration Guidance

During compression work:

- keep `batchSize`
- treat `batchId` as compatibility or observability data
- do not block queue-envelope compression on preserving `batchId` everywhere
- if a specific adapter benefits from it, keep it adapter-local or optional

## 9. Test Guidance

Required checks:

- changing or omitting `batchId` must not break dispatch correctness
- changing or omitting `batchId` must not break result acceptance
- `batchSize` behavior must still be covered by refill and batching tests

Nice-to-have checks:

- when `batchId` is emitted, trace and debug views remain coherent
