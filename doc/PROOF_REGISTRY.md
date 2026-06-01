# Proof Registry

Last updated: 2026-05-28

Status: current project-level proof ledger.

This file is the single lookup table for critical proof ownership.

Use it to answer:

1. which lane is authoritative for an invariant
2. which integrated scenario is the representative proof
3. which trace analyzer or trace-observed scenario is the canonical
   observational proof
4. which distributed-edge lane also matters
5. where the next proof belongs when current coverage is insufficient

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

Proof value is not measured by line or branch coverage. A test belongs in this
registry only when it proves at least one of:

- lifecycle or result convergence
- a mainline scheduling, dispatch, retry, release, or recovery mechanism
- a core policy decision that changes eligibility, admission, routing, finality,
  or ownership
- a cross-boundary contract exposed through HTTP, SDK, transport, external
  worker behavior, or trace
- an integrated abnormal path involving concurrency, timing, process restart,
  transport churn, lease expiry, stale result, or distributed runtime state

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
5. if the risk is only implementation coverage or a happy-path duplicate, do
   not add a test; strengthen the existing proof or delete the low-value case

Mainline rule:

- if a class is not named here, it is not mainline proof by default
- support or smoke coverage may still exist outside this ledger, but it must not
  be treated as the reason to add another duplicate test in a mainline suite
- tests tagged `secondary-proof` are explicitly downgraded support coverage
  unless they are promoted into this ledger in a later change

When a representative E2E or black-box flow fails:

1. use the trace analyzer or trace-observed scenario listed here
2. then fall back to the primary deterministic proof class
3. do not rediscover the proof graph from scratch

## 2. Critical Invariants

| Invariant id | Invariant statement | Primary proof | Representative integrated proof | Trace proof | Distributed-edge proof | Status | Do not duplicate here |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `sched.worker-eligibility-routing` | eligible worker is selected; wrong worker is excluded under attributes, targets, capability, and lock state | `EngineSchedulingCoreSuite`: `TaskWorkerEligibilityTest`, `TaskSchedulingGateAndTargetingTest` | `ServerSchedulingE2eSuite`: `TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest` | `TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest` + analyzer `worker-attribute-routing-without-context` | add chaos only if disconnect or lease-expiry changes the routing outcome | `covered` | do not add another topic-local engine or server routing test before checking these classes |
| `sched.min-worker-gate` | `minRequiredWorkerCount` blocks half-dispatch and keeps the task out of partial start | `EngineSchedulingCoreSuite`: `TaskSchedulingGateAndTargetingTest` | `ServerSchedulingE2eSuite`: `TaskApiMinimumWorkerGateTraceObservedIntegrationTest` | `TaskApiMinimumWorkerGateTraceObservedIntegrationTest` + analyzer `assignment-min-worker-gate` | only add chaos if the risk is degraded presence or lease churn around the gate | `covered` | do not add another plain gate test before checking this engine/E2E/trace chain |
| `sched.retry-redispatch` | retry, lease-expiry, or Redis runtime restart recovery returns work to competition without double assignment and without stale takeover | `EngineSchedulingCoreSuite`: `TaskRedispatchCompetitionTest`, `TaskSchedulingContentionTest` | `ServerSchedulingE2eSuite`: `TaskApiRetryRedispatchTraceObservedIntegrationTest` as the representative host/runtime retry-scheduler proof on the sealed `SESSION` keep-open path; Redis backend profile coverage must reuse this invariant rather than opening a new proof id | `TaskApiRetryRedispatchTraceObservedIntegrationTest` + analyzer `assignment-retry-redispatch` for assignment retry; lease-expiry takeover uses analyzer `lease-expiry-redispatch` | `xa-mass-testing`: `SdkPollingLeaseExpiryRedispatchChaosRunner`, `SdkWebSocketLeaseExpiryRedispatchChaosRunner`, both bound to analyzer `lease-expiry-redispatch` through the chaos trace planner; Redis restart recovery belongs here when server/runtime restart is the failure mode | `covered` | do not add another local contention, happy-path redispatch, or Redis-restart-specific proof line before checking this engine/E2E/trace chain |
| `sched.background-sharing` | shared-capacity workers may keep inflight work while new work backfills through released slots | `EngineSchedulingCoreSuite`: `TaskSchedulingContentionTest`, `TaskResourceReleaseListenerTest` | `ServerSchedulingE2eSuite`: representative sharing scenario carried by real host/runtime wiring | `TaskApiBackgroundWorkerSharingTraceObservedIntegrationTest` + analyzer `background-worker-sharing` | add chaos only if disconnect or delayed result changes sharing behavior | `covered` | do not add projection-first sharing checks |
| `sched.cross-task-fairness` | one task must not monopolize a reusable worker when another ready task is eligible to compete | `EngineSchedulingCoreSuite`: `TaskSchedulingContentionTest`, `TaskDelayedAvailabilitySchedulingTest` | `ServerSchedulingE2eSuite`: `TaskApiMultiTaskAssignmentIntegrationTest` | `TaskApiCrossTaskWorkerFairnessTraceObservedIntegrationTest` + analyzer `cross-task-worker-fairness` | add soak/chaos only when pressure or fleet timing is the actual risk | `covered` | do not add another host-level fairness test without checking this pair first |
| `sched.late-worker-backfill` | queued ready work dispatches once a worker later becomes eligible or joins the fleet | `EngineSchedulingCoreSuite`: `TaskDelayedAvailabilitySchedulingTest` | `ServerSchedulingE2eSuite`: `TaskApiDelayedWorkerAvailabilityTraceObservedIntegrationTest` | `TaskApiDelayedWorkerAvailabilityTraceObservedIntegrationTest` + analyzer `late-worker-backfill` | `xa-mass-testing` polling soak late-worker profile | `covered` | do not add another delayed-availability unit test before checking this chain |
| `kernel.duplicate-callback-idempotence` | duplicate callback replay is accepted idempotently and does not reopen or corrupt terminal work | `EngineKernelConvergenceSuite`: `TaskResultRuntimeConvergenceTest`, `TaskResultConcurrencyConvergenceTest` | `ServerLifecycleResultConvergenceSuite`: `TaskApiCallbackReplayTraceObservedIntegrationTest` | `TaskApiCallbackReplayTraceObservedIntegrationTest` + analyzer `duplicate-callback-replay` | `xa-mass-testing`: `SdkWebSocketLateResultAfterLeaseExpiryChaosRunner` for reconnect + stale late replay, bound to analyzer `late-stale-result-replay` | `covered` | do not add another projection replay test before checking the runtime-first lifecycle/trace pair |
| `kernel.result-terminal-convergence` | final result closes aggregate and runtime truth consistently for success, all-failed, and mixed-result paths | `EngineKernelConvergenceSuite`: `TaskResultRuntimeConvergenceTest`, `TaskResultConcurrencyConvergenceTest`, and `TaskContractTerminalBehaviorTest` | `ServerLifecycleResultConvergenceSuite`: `TaskApiFailureResultIntegrationTest`, `TaskApiMixedResultsTraceObservedIntegrationTest`, `TaskApiAllMessagesFailedTraceObservedIntegrationTest` | `TaskApiAllMessagesFailedTraceObservedIntegrationTest` + analyzer `all-failed-terminal-convergence`; `TaskApiMixedResultsTraceObservedIntegrationTest` + analyzer `mixed-result-terminal-convergence`; single-success remains covered by analyzer `single-message-success` | `xa-mass-testing`: result-shape chaos runners are scheduled/manual support unless a distinct distributed-edge invariant is justified | `covered` | do not add another result-topic variant before checking this engine/lifecycle/trace chain |
| `kernel.resource-release-reuse` | normal terminal closure or retry-exhausted closure releases worker/resource ownership so later ready work can acquire it | `EngineKernelConvergenceSuite`: release and cleanup deterministic coverage | `ServerSchedulingE2eSuite`: `TaskApiSingleWorkerReuseTraceObservedIntegrationTest` | `TaskApiSingleWorkerReuseTraceObservedIntegrationTest` + analyzer `worker-resource-cleanup-without-context` | chaos only when disconnect or expiry interferes with cleanup | `covered` | do not add another reuse assertion that only reads projection or piggybacks on unrelated routing proof |
| `ext.worker-parity-public-contract` | external workers preserve the public scheduling/result contract; Java proof is SDK-first through scenario-launcher, while Node samples remain adapter fixtures | `ExternalWorkerParitySuite` with dedicated external-worker parity classes as the primary integrated proof surface | `ExternalWorkerPublicContractTraceObservedIntegrationTest`, `ExternalWorkerPollingApiIntegrationTest`, `NodePollingWorkerBlackBoxIntegrationTest`, `NodeWebSocketWorkerBlackBoxIntegrationTest`, `NodeSocketWorkerBlackBoxIntegrationTest`, and `JavaScenarioLauncherBlackBoxIntegrationTest` | `ExternalWorkerPublicContractTraceObservedIntegrationTest` + analyzer `external-worker-public-contract-success` | transport/disconnect edges live in websocket/socket chaos and black-box runners; Java socket is not a public SDK session | `covered` | do not add per-adapter duplicate parity happy-path tests before checking this unified parity suite and trace proof |

## 2.1 Retained Mainline Support Cases

These classes intentionally stay in mainline server suites, but they are not
independent invariant rows. They are retained boundary/support proof under the
nearest mainline owner above and should not be cloned into new topic-local
tests.

| Class | Current reason to retain in mainline suites | Do not duplicate here |
| --- | --- | --- |
| `TaskApiLifecycleGuardsIntegrationTest` | lifecycle shell transition guards on the real host path | do not add another broad shell guard test when this class already covers the remaining host-side transition seams |
| `TaskApiBlockedRunningIntegrationTest` | blocked-running host/runtime lifecycle interaction on real dispatch callbacks | do not add another pause/block shell test unless it introduces a new kernel invariant |
| `TaskApiPauseCompletionIntegrationTest` | paused-running closure path on the real host/runtime wiring | do not add another callback-after-pause shell test without a new invariant |
| `TaskApiResumeAndCompleteIntegrationTest` | paused task resumes after late worker availability on the real host shell | do not add another resume-after-worker-connect shell test before checking this class |
| `TaskApiTerminateRunningIntegrationTest` | terminate-after-assignment shell boundary on the real callback path | do not add another generic terminate-running shell test before checking this class |
| `TaskApiWorkerWithoutContextIntegrationTest` | worker-without-context host boundary coverage remains part of routing/mainline viability | do not add another compatibility-free worker smoke unless registry proof ownership changes |

## 3. Coverage Notes

This registry does not maintain a central gap index. When proof is insufficient,
record the current evidence boundary in the owning baseline, suite map, or
owner README instead of accumulating closed-gap or target-state notes here.

## 4. Naming Rule

When adding a new high-value scenario:

- name the invariant or scenario explicitly
- link the class or suite from this file in the same change
- if the scenario claims trace coverage, name the analyzer id here as well
- if the scenario only supports another proof lane, say so here instead of
  presenting it as a new primary proof

This file is the only default ledger for current proof ownership.
