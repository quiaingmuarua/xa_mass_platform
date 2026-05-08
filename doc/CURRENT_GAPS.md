# Current Gaps Index

Last updated: 2026-05-08 (rev 2)

Status: current gap index.

Known active-mainline gaps. This index does not authorize compatibility layers,
broad redesign, or speculative target-state work.

## Runtime Gaps

| Gap | Handling | Owner |
| --- | --- | --- |
| `SimpleTaskScheduler.scheduleTasks()` is still a stub | Do not treat it as verified scheduling behavior | [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| `mass-runtime-redis` has keyspace/index baseline only; queue/lease operations are not implemented or verified | Keep the active runtime mainline on `mass-runtime-memory`; inject custom runtimes explicitly only when they are real | [platform_infra/mass-runtime-redis/README.md](../platform_infra/mass-runtime-redis/README.md) |
| Redis storage is a fail-fast placeholder | Use memory or focused H2 verification paths | [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| Engine `DATABASE` factory methods remain fail-fast | `xa-mass-server` owns the focused JDBC path behind `mass.storage.mode=jdbc-h2` or `mass.storage.mode=jdbc-postgres` | [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| Redis-backed EventBus is not verified runtime behavior | Do not depend on it for acceptance or mainline behavior | [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| **`RETRY_BUDGET_EXHAUSTED` terminal reason has no triggering policy** | `AllWorkFinalTaskTerminalPolicy` returns only `ALL_MESSAGES_SUCCEEDED`, `ALL_MESSAGES_FAILED`, and `MIXED_MESSAGE_RESULTS`. The `TaskTerminalPolicy` extension seam exists but no `RetryBudgetTaskTerminalPolicy` is implemented. Do not write tests expecting this reason to be emitted automatically. Per-message retry exhaustion (`maxRetryCount`) ends with `ALL_MESSAGES_FAILED` + message `finalReason=RETRY_EXHAUSTED` — that path IS verified by `SdkPollingMessageRetryExhaustedChaosRunner`. | [xa-mass-engine/policy/AllWorkFinalTaskTerminalPolicy.java](../xa-mass-engine/src/main/java/com/xa/mass/engine/policy/AllWorkFinalTaskTerminalPolicy.java) |

## Coverage Gaps

| Gap | Treatment | Owner |
| --- | --- | --- |
| HTTP cancel from `RUNNING` | Add Boot-shell E2E before changing semantics | [xa-mass-server/README.md](../xa-mass-server/README.md) |
| HTTP cancel from `READY` | Add Boot-shell E2E before changing semantics | [xa-mass-server/README.md](../xa-mass-server/README.md) |
| Worker disconnect during in-flight execution | Cover deterministic surrogate first; use chaos for degraded/recovery behavior | [xa-mass-server/README.md](../xa-mass-server/README.md), [TESTING_BASELINE.md](./TESTING_BASELINE.md) |
| Stronger real-runtime `EXPIRED` message coverage | Prefer real lease/expiry path over timestamp backdating | [xa-mass-server/README.md](../xa-mass-server/README.md), [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| `ALL_MESSAGES_FAILED` and `MIXED_MESSAGE_RESULTS` terminal convergence coverage | **Partially closed** by `SdkPollingAllMessagesFailedChaosRunner`, `SdkPollingMixedResultsChaosRunner`, and `SdkPollingMessageRetryExhaustedChaosRunner` in `xa-mass-testing` (all wired to CI `chaos-smokes` job); Boot-shell E2E coverage of these paths is still missing | [xa-mass-server/README.md](../xa-mass-server/README.md), [xa-mass-testing/README.md](../xa-mass-testing/README.md) |
| Broader `batchSize > 1` multi-worker coverage | Verify assignment/refill plus Boot-shell E2E; `SdkPollingMixedResultsChaosRunner` covers single-worker multi-message flow but not concurrent multi-worker assignment | [xa-mass-server/README.md](../xa-mass-server/README.md), [TESTING_BASELINE.md](./TESTING_BASELINE.md) |
| Resume short-circuit where a paused task is already complete underneath | Cover lifecycle semantics through Boot-shell E2E | [xa-mass-server/README.md](../xa-mass-server/README.md), [E2E_BASELINE.md](./E2E_BASELINE.md) |

## Rules

- Gaps are not default implementation tasks; confirm scope with current code.
- A failing or missing gap test is not a reason to add a compatibility layer.
- If a gap is closed, update this index and the owner doc in the same change.
