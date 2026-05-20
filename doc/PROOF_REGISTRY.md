# Proof Registry

Last updated: 2026-05-20

Status: current project-level proof ledger.

This file is the single lookup table for critical proof ownership.

Use it to answer:

1. which lane is authoritative for an invariant
2. which integrated scenario is the representative proof
3. which trace analyzer or trace-observed scenario is the canonical
   observational proof
4. which distributed-edge lane also matters
5. which gaps are still intentional and where the next proof belongs

This file does not replace owner matrices. It sits above them and points to the
current authoritative proof surface.

Use with:

- [TESTING_INDEX.md](./TESTING_INDEX.md)
- [TESTING_BASELINE.md](./TESTING_BASELINE.md)
- [E2E_BASELINE.md](./E2E_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../xa-mass-engine/doc/baseline/SCHEDULING_CORRECTNESS_MATRIX.md](../xa-mass-engine/doc/baseline/SCHEDULING_CORRECTNESS_MATRIX.md)
- [../xa-mass-engine/doc/baseline/KERNEL_CONVERGENCE_MATRIX.md](../xa-mass-engine/doc/baseline/KERNEL_CONVERGENCE_MATRIX.md)
- [../xa-mass-trace/README.md](../xa-mass-trace/README.md)

## 0. Proof Rule

Current default is `dual-proof`.

For critical invariants:

- primary proof is one authoritative deterministic lane
- representative proof is one real-wiring integrated lane
- trace proof is one canonical observational path through `xa-mass-trace`

Do not treat all lanes as equal proof for the same invariant.

Current lane roles:

- `xa-mass-engine` scheduling/kernel suites:
  authoritative deterministic proof
- `xa-mass-server` E2E and parity suites:
  representative integrated proof
- `xa-mass-trace` analyzers and trace-observed scenarios:
  canonical observational proof
- `xa-mass-testing` chaos and soak:
  distributed-edge proof

## 1. How To Use This Ledger

When a new change needs proof:

1. find the nearest invariant id below
2. use the listed primary proof lane first
3. add representative or trace proof only if the listed status says the pair is
   missing or the changed risk really sits on host/runtime or distributed edge
4. do not add a new test in another lane when an authoritative proof class
   already exists for that invariant

When a representative E2E or black-box flow fails:

1. use the trace analyzer or trace-observed scenario listed here
2. then fall back to the primary deterministic proof class
3. do not rediscover the proof graph from scratch

## 2. Critical Invariants

| Invariant id | Invariant statement | Primary proof | Representative integrated proof | Trace proof | Distributed-edge proof | Status | Do not duplicate here |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `sched.worker-eligibility-routing` | eligible worker is selected; wrong worker is excluded under attributes, targets, capability, and lock state | `EngineSchedulingCoreSuite`: `TaskWorkerEligibilityTest`, `TaskSchedulingGateAndTargetingTest` | `ServerSchedulingE2eSuite`: `TaskApiWorkerAttributeRoutingIntegrationTest` | `TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest` + analyzer `worker-attribute-routing-without-context` | add chaos only if disconnect or lease-expiry changes the routing outcome | `covered` | do not add another topic-local engine or server routing test before checking these classes |
| `sched.min-worker-gate` | `minRequiredWorkerCount` blocks half-dispatch and keeps the task out of partial start | `EngineSchedulingCoreSuite`: `TaskSchedulingGateAndTargetingTest` | `ServerSchedulingE2eSuite`: `TaskApiMinimumWorkerGateIntegrationTest` | `TaskApiMinimumWorkerGateTraceObservedIntegrationTest` + analyzer `assignment-min-worker-gate` | only add chaos if the risk is degraded presence or lease churn around the gate | `covered` | do not add another plain gate test before checking this engine/E2E/trace chain |
| `sched.retry-redispatch` | retry or lease-expiry returns work to competition without double assignment and without stale takeover | `EngineSchedulingCoreSuite`: `TaskRedispatchCompetitionTest`, `TaskSchedulingContentionTest` | `ServerSchedulingE2eSuite`: `TaskApiRetryRedispatchTraceObservedIntegrationTest` as the representative host/runtime retry-scheduler proof on the sealed `SESSION` keep-open path | `TaskApiRetryRedispatchTraceObservedIntegrationTest` + analyzer `assignment-retry-redispatch` for assignment retry; lease-expiry takeover uses analyzer `lease-expiry-redispatch` | `xa-mass-testing`: `SdkPollingLeaseExpiryRedispatchChaosRunner`, `SdkWebSocketLeaseExpiryRedispatchChaosRunner`, both bound to analyzer `lease-expiry-redispatch` through the chaos trace planner | `covered` | do not add another local contention or happy-path redispatch test before checking this engine/E2E/trace chain |
| `sched.background-sharing` | shared-capacity workers may keep inflight work while new work backfills through released slots | `EngineSchedulingCoreSuite`: `TaskSchedulingContentionTest`, `TaskResourceReleaseListenerTest` | `ServerSchedulingE2eSuite`: representative sharing scenario carried by real host/runtime wiring | `TaskApiBackgroundWorkerSharingTraceObservedIntegrationTest` + analyzer `background-worker-sharing` | add chaos only if disconnect or delayed result changes sharing behavior | `covered` | do not add projection-first sharing checks |
| `sched.cross-task-fairness` | one task must not monopolize a reusable worker when another ready task is eligible to compete | `EngineSchedulingCoreSuite`: `TaskSchedulingContentionTest`, `TaskDelayedAvailabilitySchedulingTest` | `ServerSchedulingE2eSuite`: `TaskApiMultiTaskAssignmentIntegrationTest` | `TaskApiCrossTaskWorkerFairnessTraceObservedIntegrationTest` + analyzer `cross-task-worker-fairness` | add soak/chaos only when pressure or fleet timing is the actual risk | `covered` | do not add another host-level fairness test without checking this pair first |
| `sched.late-worker-backfill` | queued ready work dispatches once a worker later becomes eligible or joins the fleet | `EngineSchedulingCoreSuite`: `TaskDelayedAvailabilitySchedulingTest` | `ServerSchedulingE2eSuite`: `TaskApiDelayedWorkerAvailabilityIntegrationTest` | `TaskApiDelayedWorkerAvailabilityTraceObservedIntegrationTest` + analyzer `late-worker-backfill` | `xa-mass-testing` polling soak late-worker profile | `covered` | do not add another delayed-availability unit test before checking this chain |
| `kernel.duplicate-callback-idempotence` | duplicate callback replay is accepted idempotently and does not reopen or corrupt terminal work | `EngineKernelConvergenceSuite`: `TaskManagerLifecycleTest` | `ServerLifecycleResultConvergenceSuite`: `TaskApiCallbackReplayTraceObservedIntegrationTest` | `TaskApiCallbackReplayTraceObservedIntegrationTest` + analyzer `duplicate-callback-replay` | `xa-mass-testing`: `SdkWebSocketLateResultAfterLeaseExpiryChaosRunner` for reconnect + stale late replay, bound to analyzer `late-stale-result-replay` | `covered` | do not add another projection replay test before checking the runtime-first lifecycle/trace pair |
| `kernel.result-terminal-convergence` | final result closes aggregate and runtime truth consistently for success, all-failed, and mixed-result paths | `EngineKernelConvergenceSuite`: `TaskManagerLifecycleTest` and result/lifecycle deterministic coverage | `ServerLifecycleResultConvergenceSuite`: `TaskApiFailureResultIntegrationTest`, `TaskApiMixedResultsIntegrationTest`, `TaskApiAllMessagesFailedIntegrationTest` | `TaskApiAllMessagesFailedTraceObservedIntegrationTest` + analyzer `all-failed-terminal-convergence`; `TaskApiMixedResultsTraceObservedIntegrationTest` + analyzer `mixed-result-terminal-convergence`; single-success remains covered by analyzer `single-message-success` | `xa-mass-testing`: `SdkPollingAllMessagesFailedChaosRunner` -> `all-failed-terminal-convergence`, `SdkPollingMixedResultsChaosRunner` -> `mixed-result-terminal-convergence`; `SdkPollingMessageRetryExhaustedChaosRunner` stays representative-only until a distinct mechanism invariant is justified | `covered` | do not add another result-topic variant before checking this engine/lifecycle/trace chain |
| `kernel.resource-release-reuse` | normal terminal closure or retry-exhausted closure releases worker/resource ownership so later ready work can acquire it | `EngineKernelConvergenceSuite`: release and cleanup deterministic coverage | `ServerSchedulingE2eSuite`: `TaskApiSingleWorkerReuseIntegrationTest` and `TaskApiTerminateReuseIntegrationTest` | `TaskApiSingleWorkerReuseTraceObservedIntegrationTest` + analyzer `worker-resource-cleanup-without-context` | chaos only when disconnect or expiry interferes with cleanup | `covered` | do not add another reuse assertion that only reads projection or piggybacks on unrelated routing proof |
| `ext.worker-parity-public-contract` | Java and Node external workers stay behaviorally aligned across polling, websocket, and socket adapters | `ExternalWorkerParitySuite` with dedicated external-worker parity classes as the primary integrated proof surface | `ExternalWorkerPublicContractTraceObservedIntegrationTest` plus focused worker-control/public-contract API tests such as `ExternalWorkerPollingApiIntegrationTest` | `ExternalWorkerPublicContractTraceObservedIntegrationTest` + analyzer `external-worker-public-contract-success` | transport/disconnect edges live in websocket/socket chaos and black-box runners | `covered` | do not add per-adapter duplicate parity happy-path tests before checking this unified parity suite and trace proof |

## 3. Known Gaps

Visible gaps should stay explicit so new agents add proof in the right lane.

### 3.1 Current Explicit Gaps

- there is no current top-priority gap in external worker public-contract proof
  ownership; parity now has a canonical trace-observed scenario and analyzer
  chain

## 4. Naming Rule

When adding a new high-value scenario:

- name the invariant or scenario explicitly
- link the class or suite from this file in the same change
- if the scenario claims trace coverage, name the analyzer id here as well
- if the scenario only supports another proof lane, say so here instead of
  presenting it as a new primary proof

This file is the only default ledger for current proof ownership.
