# Scheduling Correctness Matrix

Status: current engine scheduling correctness map.

This file is the engine-local entry for scheduling correctness tests. It maps
the owner invariant to the primary proof surface, current test lane, and known
gap. It is not a roadmap for new scheduling behavior.

## Core Rule

Scheduling correctness has priority over extensibility and performance.

```text
correctness > extensibility > performance
```

Strategy may be simple or inefficient, but it must not bypass runtime truth,
lease ownership, reservation ownership, reachability ownership, or result
convergence.

## Proof Surfaces

Primary scheduling correctness proof must stay engine-local and runtime-first:

- `TaskWorkRuntime` ready / inflight / delayed / final counters
- active leases and lease tokens
- task aggregate status and terminal reason
- worker lock / capacity / resource state
- assignment records and canonical scheduling trace evidence

Do not use compatibility projection as the pass/fail truth for scheduling
correctness. Retired engine support suites must not return as scheduling proof;
scan-heavy projection audit is no longer part of the engine kernel diagnostic
surface.

## Test Lanes

| Lane | Owner | Intent |
| --- | --- | --- |
| `EngineSchedulingCoreSuite` | `xa-mass-engine` | Primary PR gate for deterministic scheduling correctness and owner-boundary guards |
| `xa-mass-testing` soak/chaos | `xa-mass-testing` | Runtime pressure and distributed edge proof; not a substitute for deterministic scheduling matrix coverage |
| `xa-mass-server` E2E | `xa-mass-server` | Host/API wiring proof; not the full scheduling matrix |

## Physical Test Layout

Logical ownership is defined by this matrix and by suite membership first.
Physical package moves are allowed only when they do not require production
visibility expansion or same-module wrapper seams.

- Root-package scheduling tests may stay in `com.xa.mass.engine` when they need
  package-private kernel access such as manager internals, runtime ports, or
  lifecycle helpers.
- Owner-local tests should live under the owner package when they can do so
  without changing production visibility, for example `worker`, `strategy`,
  `listener`, `resource`, `assignment`, `runtime`, and `guard`.
- Boundary and residue guards belong under `com.xa.mass.engine.guard` unless
  they are part of the scheduling-core suite guard itself. They prevent proof
  surface drift; they are not runtime-behavior proof.
- Do not introduce a test-only bridge, facade, or public accessor just to make a
  directory move possible.

## Invariant Matrix

| Invariant | Owner boundary | Primary tests | Proof surface | Current status |
| --- | --- | --- | --- | --- |
| A task that is not schedulable must not dispatch even if ready work exists | task lifecycle + assignment signal admission | `TaskSchedulingContentionTest`, `TaskContractSchedulingBehaviorTest` | task status, runtime ready/inflight, no active lease | Covered |
| Worker eligibility excludes unreachable, locked, capacity-exhausted, routing-mismatch, and target-attribute mismatch candidates | worker scheduling view + rule/rank/admission path | `TaskWorkerEligibilityTest`, `TaskSchedulingGateAndTargetingTest`, `TaskSchedulingContentionTest` | assignment records, active leases, worker lock/load | Covered |
| Candidate narrowing must use explicit WorkerGroup selector truth and must not fall back to event/project/all-worker scans | worker registry snapshot + `WorkerCandidateIndex` + candidate enumerator | `TaskSchedulingGateAndTargetingTest`; `WorkerCandidateIndexTest` and `WorkerSchedulingCandidateEnumeratorTest` are support regressions | selected worker lease/binding, non-selected group has no assignment or lock, residue scan for removed fallback paths | Covered |
| `targetWorkerId` is a group-scoped direct lookup shortcut, not a policy bypass | candidate index + Stage-2 admission | `TaskSchedulingGateAndTargetingTest`, `WorkerCandidateIndexTest`, `WorkerSchedulingCandidateEnumeratorTest` | no backup candidate, required group selector, lock/resource state | Covered |
| Multiple tasks competing for one worker must not double assign or lose ready work | worker lock/capacity + runtime ready truth | `TaskSchedulingContentionTest` | active leases, assignment conflict records, ready/inflight counts | Covered |
| Stateless/background workers can share capacity only up to declared capacity | worker load view + resource policy | `TaskSchedulingContentionTest` | active lease count, quota rejection, task status | Covered |
| Partial assignment leaves remaining work ready for later refill | runtime ready truth + allocation/binder | `TaskSchedulingContentionTest`, `TaskSchedulingGateAndTargetingTest` | ready/inflight counts, active leases, later successful assignment | Covered |
| Released worker/resource can be used by waiting ready work | resource releaser + refill policy + assignment signal | `TaskSchedulingContentionTest`, `TaskRedispatchCompetitionTest`, `TaskResourceReleaseListenerTest`, `DefaultAssignmentRefillPolicyTest` | worker lock/load release, later assignment, terminal source task | Covered |
| A new eligible worker can move waiting READY work into dispatch | worker registry + assignment trigger | `TaskDelayedAvailabilitySchedulingTest`, `TaskWorkerEligibilityTest` | READY before worker, RUNNING after worker, active lease | Covered deterministic; soak covers pressure only |
| Offline/unreachable workers must be excluded from new work | reachability view + candidate admission | `TaskWorkerEligibilityTest` | rejection record, backup worker lease, original active lease unchanged | Covered |
| An active lease is not finalized by reachability loss; lease/runtime decides expiry or result acceptance | `TaskWorkRuntime` lease truth + result application | `TaskWorkerEligibilityTest`, `TaskRedispatchCompetitionTest` | active lease remains, expiry/reset path, stale lease rejection | Partially covered engine-local; real disconnect/reconnect remains chaos/E2E lane |
| Lease expiry re-enters retryable work into competition exactly once | runtime expiry + retry policy + assignment | `TaskRedispatchCompetitionTest` | ready/inflight/final counts, retry count, same message id | Covered |
| Stale late result after redispatch must not overwrite active/final work | result application + lease token validation | `TaskRedispatchCompetitionTest` | `STALE_LEASE`, active lease token unchanged, final count unchanged | Covered |
| Retry-exhausted expiry finalizes and releases resources for waiting work | runtime retry policy + terminal convergence + resource release | `TaskRedispatchCompetitionTest` | terminal reason, expired/final counters, worker unlock, later assignment | Covered |
| Minimum-worker gate must avoid half-dispatch and release skipped candidates | allocation policy + assignment listener cleanup | `TaskSchedulingGateAndTargetingTest`, `TaskWorkerEligibilityTest` | READY state, zero active leases, worker unlocked, later dispatch when enough eligible workers exist | Covered |
| Result finality must release worker lock/capacity/resource | result convergence + resource releaser | `TaskSchedulingContentionTest`, `TaskRedispatchCompetitionTest`, `TaskResourceReleaseListenerTest` | task terminal, worker unlock/load release, waiting task dispatch | Covered |
| Scheduling-core tests must stay runtime/aggregate/trace-first | suite guard | `EngineSchedulingCoreArchitectureGuardTest` | selected suite source scan as proof-surface drift prevention only | Covered support guard |

## Coverage Placement Notes

These notes describe where current proof belongs before adding another
deterministic engine-matrix test.

- Reconnect-before-expiry is mainly covered by `xa-mass-testing` chaos; add an
  engine-local deterministic test only if the reachability/lease owner boundary
  changes.
- New-worker-online automatic refill is covered as explicit re-assignment in
  engine tests and as pressure behavior in soak; if assignment trigger semantics
  change, add a deterministic test around the trigger owner rather than relying
  on soak.
- Multi-group event-code routing is covered by worker candidate/index tests and
  trace scenario coverage; keep group capability proof out of projection
  helpers.
- Priority/fairness strategy is not part of current correctness proof beyond
  preserving reservation, lease, and admission invariants.

## Add-Test Rule

When adding a scheduling test:

1. Pick an invariant in this matrix or add a new one.
2. State the proof surface in the test class or method name.
3. Prefer `TaskSchedulingTestHarness` for deterministic engine scenarios.
4. Keep projection reads out of `EngineSchedulingCoreSuite`.
5. Use `xa-mass-testing` soak/chaos only for runtime pressure or distributed
   edge behavior that is not deterministic inside the engine boundary.
