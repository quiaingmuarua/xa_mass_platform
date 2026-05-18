# Current Gaps Index

Last updated: 2026-05-18

Status: current gap index.

Known active-mainline gaps only. Resolved items do not stay in this file. This
index does not authorize compatibility layers, broad redesign, or speculative
target-state work.

## Runtime Gaps

| Gap | Handling | Owner |
| --- | --- | --- |
| Redis storage is a fail-fast placeholder | Use memory or focused H2 verification paths | [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| Engine `DATABASE` factory methods remain fail-fast | `xa-mass-server` owns the focused JDBC path behind `mass.storage.mode=jdbc-h2` or `mass.storage.mode=jdbc-postgres` | [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| Redis-backed EventBus is not verified runtime behavior | Do not depend on it for acceptance or mainline behavior | [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| **`RETRY_BUDGET_EXHAUSTED` terminal reason has no triggering policy** | `AllWorkFinalTaskTerminalPolicy` returns only `ALL_MESSAGES_SUCCEEDED`, `ALL_MESSAGES_FAILED`, and `MIXED_MESSAGE_RESULTS`. The `TaskTerminalPolicy` extension seam exists but no `RetryBudgetTaskTerminalPolicy` is implemented. Do not write tests expecting this reason to be emitted automatically. Per-message retry exhaustion (`maxRetryCount`) ends with `ALL_MESSAGES_FAILED` + message `finalReason=RETRY_EXHAUSTED`; that path is verified by `SdkPollingMessageRetryExhaustedChaosRunner`. | [xa-mass-engine/policy/AllWorkFinalTaskTerminalPolicy.java](../xa-mass-engine/src/main/java/com/xa/mass/engine/policy/AllWorkFinalTaskTerminalPolicy.java) |
| **Worker command lifecycle is owner-backed but not fully executable** | Request/ack/read surfaces exist, and `WorkerCommandDeliveryCoordinator` proves the owner seam. A real transport delivery implementation, automatic request-to-delivery handoff, command retry, and deadline expiry enforcement are not complete. Do not assume worker commands can actively control workers until this gap is closed. | [EVENT_OWNER_BOUNDARY.md](../xa-mass-engine/doc/baseline/EVENT_OWNER_BOUNDARY.md), [PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md](../xa-mass-engine/doc/roadmap/PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md) |

## Coverage Gaps

| Gap | Treatment | Owner |
| --- | --- | --- |
| HTTP cancel from `RUNNING` | Add Boot-shell E2E before changing semantics | [xa-mass-server/README.md](../xa-mass-server/README.md) |
| HTTP cancel from `READY` | Add Boot-shell E2E before changing semantics | [xa-mass-server/README.md](../xa-mass-server/README.md) |
| Worker disconnect during in-flight execution | Cover deterministic surrogate first; use chaos for degraded/recovery behavior | [xa-mass-server/README.md](../xa-mass-server/README.md), [TESTING_BASELINE.md](./TESTING_BASELINE.md) |
| Stronger real-runtime `EXPIRED` message coverage | Prefer real lease/expiry path over timestamp backdating | [xa-mass-server/README.md](../xa-mass-server/README.md), [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| Broader `batchSize > 1` multi-worker coverage | Verify assignment/refill plus Boot-shell E2E; `SdkPollingMixedResultsChaosRunner` covers single-worker multi-message flow but not concurrent multi-worker assignment | [xa-mass-server/README.md](../xa-mass-server/README.md), [TESTING_BASELINE.md](./TESTING_BASELINE.md) |

## Rules

- Gaps are not default implementation tasks; confirm scope with current code.
- A failing or missing gap test is not a reason to add a compatibility layer.
- If a gap is resolved, remove it from this index and update the owner doc in the same change.
- Production scheduling improvement notes live in
  [PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md](../xa-mass-engine/doc/roadmap/PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md).
  That document is future direction, not current behavior.
