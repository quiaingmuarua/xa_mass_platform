# Testing Proof System Hardening Correctness Gap Map

Status: current TPS-3 gap map for `TESTING_PROOF_SYSTEM_HARDENING_ROADMAP.md`.

Last updated: 2026-06-09

## Scope

This map reviews `scheduling-policy-correctness` and
`lifecycle-result-correctness` placement for the proof hardening roadmap. It is
not a request to add more server E2E permutations.

Authoritative inputs:

- `doc/PROOF_REGISTRY.md`
- `xa-mass-engine/doc/baseline/SCHEDULING_CORRECTNESS_MATRIX.md`
- `xa-mass-engine/doc/baseline/KERNEL_CONVERGENCE_MATRIX.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_POLICY_PROOF_INVENTORY.md`

## Owner Decision

Policy and lifecycle correctness remain engine-first.

- `xa-mass-engine` owns deterministic proof for worker selection, candidate
  exclusion, readiness, occupancy, capacity, locks, retry/wakeup/lease-expiry
  re-entry, lifecycle transition, finality, duplicate/stale result behavior,
  and resource release.
- `xa-mass-server` E2E is representative real-wiring proof only. Add it only
  when the remaining risk is host/API/auth/transport/Spring wiring.
- `xa-mass-testing` chaos/perf/soak is scoped operational evidence only. Add it
  only when distributed timing, reconnect, runtime backend degradation, or
  pressure is the actual risk.
- Trace analyzers are canonical observation proof, not policy truth.

## Current Gap Classification

| Risk | Current primary proof | Representative/edge proof | Classification | Next action |
| --- | --- | --- | --- | --- |
| Wrong worker selected or eligible worker excluded incorrectly | `EngineSchedulingCoreSuite`: `TaskWorkerEligibilityTest`, `TaskSchedulingGateAndTargetingTest` | server routing trace-observed scenarios | covered | no new test unless selector owner changes |
| Candidate bucket crosses WorkerGroup/event/capability boundary | `TaskSchedulingGateAndTargetingTest`, `WorkerCandidateIndexTest`, `WorkerSchedulingCandidateEnumeratorTest` support regressions | trace assignment/routing analyzers | covered | strengthen engine deterministic proof first if a new selector branch lands |
| Readiness/dispatch gate/occupancy/capacity dimensions collapse | `WorkerStateReportSchedulingIntegrationTest`, `TaskWorkerEligibilityTest`, `TaskSchedulingContentionTest` | reuse routing/retry representative rows | covered-engine-current-slice | do not add direct dispatch-gate-only support tests as proof |
| Retry/wakeup/lease-expiry side-door binds without policy re-entry | `TaskSchedulingBindingEntryBypassTest`, `TaskRedispatchCompetitionTest` | retry trace-observed E2E and selected chaos rows | covered | add engine proof before new host E2E if a new binding entry appears |
| Minimum-worker gate half-dispatches | `TaskSchedulingGateAndTargetingTest` | `TaskApiMinimumWorkerGateTraceObservedIntegrationTest` | covered | no duplicate gate test |
| Cross-task fairness/background sharing/resource reuse | `TaskSchedulingContentionTest`, `TaskResourceReleaseListenerTest`, `DefaultAssignmentRefillPolicyTest` | representative server sharing/reuse scenarios; soak only for pressure | covered | add engine deterministic proof for new resource policy semantics |
| Delayed availability/backfill | `TaskDelayedAvailabilitySchedulingTest`, `TaskWorkerEligibilityTest` | server delayed-worker E2E and soak pressure | covered | no server matrix expansion |
| Duplicate callback idempotence | `EngineKernelConvergenceSuite`: `TaskResultRuntimeConvergenceTest`, `TaskResultConcurrencyConvergenceTest` | callback replay E2E, late stale chaos | covered | no projection replay test |
| Result terminal convergence | `TaskResultRuntimeConvergenceTest`, `TaskResultConcurrencyConvergenceTest`, `TaskIdleClosePolicyBehaviorTest` | lifecycle/result E2E and trace analyzers | covered | add engine proof first for new terminal reason behavior |
| Resource release after terminal or retry exhaustion | scheduling/kernel convergence tests | server single-worker reuse E2E | covered | no duplicate projection or host-only proof |

## Placement Rule For Future Gaps

When a new "can it be wrong?" concern appears:

1. Add or update a row in the nearest engine matrix.
2. Add deterministic engine proof if the risk is policy, scheduling, lifecycle,
   lease, finality, or resource-release logic.
3. Add representative server E2E only if the deterministic proof cannot cover
   host/API/auth/transport/Spring wiring risk.
4. Add trace analyzer proof only when the invariant needs canonical
   observation.
5. Add chaos/perf/soak only when the risk is distributed timing, reconnect,
   runtime backend degradation, or load/pressure.

Current TPS-3 result: no immediate engine test gap is identified from the
current proof registry and engine matrices. The hardening work should therefore
focus on proof-summary honesty, runner-native capability/no-bypass evidence,
and scoped resilience contracts rather than adding new server E2E cases.
