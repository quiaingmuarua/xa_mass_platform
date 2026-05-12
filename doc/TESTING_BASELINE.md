# Testing Baseline

Last updated: 2026-05-12

Status: current global testing baseline.

System-level map of the testing lanes.

Use [TESTING_INDEX.md](./TESTING_INDEX.md) as the default entry for current CI
truth, current asset map, and change-type minimum verification. This file keeps
only the cross-module lane model and placement rules.

Use with:

- [./TESTING_INDEX.md](./TESTING_INDEX.md)
- [./VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- [./E2E_BASELINE.md](./E2E_BASELINE.md)
- [./TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- [../xa-mass-server/README.md](../xa-mass-server/README.md)

## 1. Core Rule

- test decisions are organized around the current mainline:
  `project -> submitter / worker capability -> task shell -> item append -> engine runtime -> transport delivery -> result ingest -> convergence`
- core proof is priority-ordered, not flat:
  1. `Scheduling Correctness`
  2. `Kernel Convergence`
  3. `Platform Viability / Boot-shell E2E`
  4. `Chaos / Perf / Distributed-readiness`
- core proof is split intentionally:
  - scheduling correctness proves worker selection, contention, redispatch, and contract-aware convergence under real business scenarios
  - local engine/transport tests protect deterministic kernel and boundary invariants
  - E2E / black-box / chaos protect real wiring, parity, and distributed edge behavior
- `project` is a mainline business boundary, not only a metadata/resource surface
- `transport` is an explicit validation boundary, not an engine implementation detail
- perf and chaos are part of the project-level test estate, but current CI gate
  truth belongs to [TESTING_INDEX.md](./TESTING_INDEX.md)
- projection-first proof style is downgraded; compatibility projection is bounded residue, not the primary execution proof surface
- engine PR mainline suites are now runtime-first:
  `EngineSchedulingCoreSuite` no longer carries projection-heavy residue classes directly; compatibility residue and audit live in explicit secondary suites
- `EngineSchedulingCoreArchitectureGuardTest` keeps projection-first helpers out
  of the scheduling-core mainline test set

## 2. Lane Map

| Lane | Owner | Weight / placement |
| --- | --- | --- |
| `Scheduling Correctness` | `xa-mass-engine` first, `xa-mass-server` representative E2E | highest-priority proof for matching, contention, redispatch, gating, and contract-aware convergence |
| `Kernel Convergence` | `xa-mass-engine` | lifecycle, retry, expiry, finality, release, and convergence invariants |
| `Platform Viability / Boot-shell E2E` | `xa-mass-server` | representative real-host proof that HTTP, SDK, transport, and workers are wired correctly |
| `cross-language black-box` | `xa-mass-server` | adapter/language parity proof for external workers across Java / Node and multiple adapters |
| `transport boundary` | `transport/*`, `xa-mass-testing`, `xa-mass-server` | adapter routing, result ingress, and transport/engine decoupling proof |
| `perf / chaos / distributed-readiness` | `xa-mass-testing` | scale, recovery, disconnect, replay, and degraded-condition proof around the scheduling mainline |
| `mainline boundary` | `xa-mass-server` | `project / submitter / worker / workerContext` boundary proof on real host surfaces |
| `local invariant / module` | owning module tests | support coverage only when it adds kernel or boundary debugging value |

## 3. Command Ownership

- current minimum verification and CI truth: [TESTING_INDEX.md](./TESTING_INDEX.md)
- startup, smoke, and focused regression commands: [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md)
- engine race/refill/release coverage: [../xa-mass-engine/README.md](../xa-mass-engine/README.md)
- perf, SDK harness, and chaos: [../xa-mass-testing/README.md](../xa-mass-testing/README.md)
- Boot-shell E2E suite map: [../xa-mass-server/README.md](../xa-mass-server/README.md)
- external worker sample lane: `./scripts/run-external-worker-samples.sh`

## 4. Lane Intent

- `Scheduling Correctness` proves the platform's core business value:
  the right workers are selected, excluded, re-selected, and converged under
  contention, gating, retry, and contract differences
- current engine-first scheduling matrix includes explicit tests for:
  `TaskContractSchedulingBehaviorTest`, `TaskSchedulingContentionTest`,
  `TaskWorkerEligibilityTest`,
  `TaskWorkerContextContentionTest`, `TaskRedispatchCompetitionTest`, and
  `TaskSchedulingGateAndTargetingTest`
- that matrix now includes active degraded-presence competition:
  a worker can lose transport reachability while holding a lease, and later
  READY tasks must exclude it and choose an eligible backup without projection reads
- reachability also participates in gate decisions:
  `minRequiredWorkerCount` is evaluated against currently eligible workers, so
  a dropped worker keeps the task READY without half-dispatching work
- target-worker routing is covered under contention:
  a task with a fixed target worker must not drift to an idle backup worker while
  the target is locked, and it must dispatch to the target after release
- retry-exhausted batch expiry is covered as a competition scenario:
  final convergence must release the worker/context so a waiting READY task can
  acquire the resource
- delayed availability is covered in engine-first form:
  READY work remains queued when no worker/context is eligible, then dispatches
  when an eligible worker registers or a blocked context becomes allocatable
- schedulable membership is covered under contention:
  a waiting task paused before resource release must not acquire the released
  worker until it is resumed
- `Kernel Convergence` verifies lifecycle and convergence invariants that are
  easier to prove deterministically under concurrency than through the host shell
- `Platform Viability / Boot-shell E2E` proves the host shell exposes the
  mainline correctly; it does not replace the scheduling matrix
- `cross-language black-box` proves external worker compatibility and scheduling
  parity across process and language boundaries
- cross-language parity is suite-owned by `ExternalWorkerParitySuite`; the
  runner script invokes the suite instead of maintaining a parallel class list
- `transport boundary` verifies routing, result ingress, and decoupling so
  transport does not redefine kernel semantics
- `perf / chaos / distributed-readiness` proves degraded-condition resilience
  around the real scheduling path; it does not replace ordinary feature acceptance
- current PR chaos smokes in `xa-mass-testing` are runtime/aggregate/trace-first:
  polling all-failed, mixed-result, retry-exhausted, polling lease-expiry
  redispatch, websocket lease-expiry redispatch, and websocket stale late-result
  runners assert `TaskWorkRuntime` counters, active lease drain, final receipts,
  task terminal reason, and `ExecutionEvent` transitions before any
  compatibility report payload

For change-type specific minimum verification, use
[TESTING_INDEX.md](./TESTING_INDEX.md).

## 5. Fast Path

Identify the dominant boundary first:

- `xa-mass-engine` first for scheduling correctness, lifecycle, retry, expiry,
  release, and convergence
- `xa-mass-server` for representative Boot-shell E2E and host-boundary proof
- `transport/*` plus `xa-mass-testing` for transport runtime, routing, perf, and chaos

Read the owner README after [TESTING_INDEX.md](./TESTING_INDEX.md) confirms the
minimum verification set.

## 6. Projection-First Tests

- keep local kernel tests strong; do not weaken lifecycle or convergence coverage
- rewrite tests that prove runtime/result correctness by immediately reading compatibility projection
- keep compatibility projection assertions only when proving bounded residue, overlay, or explicit no-op behavior
- keep compatibility residue ownership explicit:
  `EngineProjectionResidueSuite` and `EngineProjectionAuditSuite` are valid supporting lanes, but they do not define the scheduling-core gate
- keep server compatibility residue ownership explicit:
  `ServerProjectionResidueSuite` and `ServerProjectionAuditSuite` are valid
  supporting lanes; `ServerSchedulingE2eSuite` and
  `ServerLifecycleResultConvergenceSuite` stay runtime/aggregate-first
- `ServerMainlineE2eArchitectureGuardTest` keeps projection-first helpers and
  implicit `var` declarations out of the mainline server E2E suites
- when the real risk is disconnect, replay, late result, takeover, or host/runtime wiring, prefer Boot-shell E2E, cross-language black-box, or chaos over adding more projection-first local tests
- chaos/perf reports may read bounded compatibility residue for diagnostics,
  but the runner's pass/fail proof must stay runtime/aggregate/trace-first

## 7. Documentation Rule

- this file answers cross-module testing questions only
- detailed perf, concurrency, chaos, and suite maps belong in owner READMEs
- `doc/` should not accumulate module-local testing playbooks
- [TESTING_INDEX.md](./TESTING_INDEX.md) is the only default entry for current
  CI truth, current suite map, and minimum verification rules
- [E2E_BASELINE.md](./E2E_BASELINE.md) and
  [VERIFIED_RUNBOOK.md](./VERIFIED_RUNBOOK.md) stay project-level because they
  define release-scope semantics and verified runtime behavior
