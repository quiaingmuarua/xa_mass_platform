# Current Gaps Index

Last updated: 2026-05-28

Status: current gap index.

Known active-mainline gaps only. Resolved items do not stay in this file. This
index does not authorize compatibility layers, broad redesign, or speculative
target-state work.

## Runtime Gaps

| Gap | Handling | Owner |
| --- | --- | --- |
| Redis runtime is opt-in, not the default acceptance mainline | Use Redis runtime tests only when the change touches Redis runtime behavior. Default server/shell acceptance still uses the in-memory runtime path unless the scenario explicitly selects Redis. | [mass-runtime-redis/README.md](../platform_infra/mass-runtime-redis/README.md), [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| Redis-backed EventBus is not verified runtime behavior | Do not depend on it for acceptance or mainline behavior | [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) |
| **`RETRY_BUDGET_EXHAUSTED` terminal reason has no triggering policy** | `AllWorkFinalTaskTerminalPolicy` returns only `ALL_MESSAGES_SUCCEEDED`, `ALL_MESSAGES_FAILED`, and `MIXED_MESSAGE_RESULTS`. The `TaskTerminalPolicy` extension seam exists but no `RetryBudgetTaskTerminalPolicy` is implemented. Do not write tests expecting this reason to be emitted automatically. Per-message retry exhaustion (`maxRetryCount`) ends with `ALL_MESSAGES_FAILED` + message `finalReason=RETRY_EXHAUSTED`; that path is verified by `SdkPollingMessageRetryExhaustedChaosRunner`. | [xa-mass-engine/policy/AllWorkFinalTaskTerminalPolicy.java](../xa-mass-engine/src/main/java/com/xa/mass/engine/policy/AllWorkFinalTaskTerminalPolicy.java) |
| **Worker command policy is first-slice only** | Worker command request/read, polling pull, realtime push handoff, ack/status ingress, bounded delivery retry, and deadline expiry exist. The remaining gap is policy depth: the accepted command catalog is still small (`DRAIN`, `PING`), current `DRAIN` recovery expects disconnect/re-register, and there is no explicit `RESUME` command-gate reopen path. | [EVENT_OWNER_BOUNDARY.md](../xa-mass-engine/doc/baseline/EVENT_OWNER_BOUNDARY.md), [PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md](../xa-mass-engine/doc/roadmap/PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md) |

## Coverage Gaps

| Gap | Treatment | Owner |
| --- | --- | --- |
| Worker disconnect during in-flight execution | Existing chaos covers polling/websocket lease-expiry takeover and stale late replay. Add deterministic Boot-shell coverage only when the change touches host/runtime disconnect semantics rather than generic lease expiry. | [xa-mass-testing/README.md](../xa-mass-testing/README.md), [TESTING_BASELINE.md](./TESTING_BASELINE.md) |
| Broader `batchSize > 1` multi-worker Boot-shell coverage | Engine/perf lanes cover scheduling contention and batch sizing, and server E2E covers single-worker multi-round dispatch plus two-worker running terminate. Add a representative concurrent multi-worker Boot-shell proof only when changing multi-worker allocation/refill semantics. | [PROOF_REGISTRY.md](./PROOF_REGISTRY.md), [TESTING_INDEX.md](./TESTING_INDEX.md) |

## Rules

- Gaps are not default implementation tasks; confirm scope with current code.
- A failing or missing gap test is not a reason to add a compatibility layer.
- If a gap is resolved, remove it from this index and update the owner doc in the same change.
- Production scheduling improvement notes live in
  [PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md](../xa-mass-engine/doc/roadmap/PRODUCTION_SCHEDULING_KERNEL_IMPROVEMENTS.md).
  That document is future direction, not current behavior.
