# Worker Fault Matrix Infra Fault Decision

Last updated: 2026-06-02

Archived on 2026-06-05 after the current infra-fault proof restrictions were
moved into `xa-mass-testing/README.md` and `doc/TESTING_INDEX.md`.

Current truth owners:

- `xa-mass-testing/README.md` for worker-fault matrix proof scope.
- `doc/TESTING_INDEX.md` for cross-module verification selection.
- `doc/PROOF_REGISTRY.md` for proof routing and coverage claims.

This document is historical context only. Do not use it as proof of current
implementation behavior; verify against current code, tests, testing README,
and the testing index.

Status: archived deferred xa-mass-testing decision.

The implemented worker-fault matrix roadmap left the active reading path on
2026-06-02. Its implementation record is archived at
`../doc/archive/xa-mass-testing/2026-06-02_WORKER_FAULT_MATRIX_ROADMAP.md`.

## Current Facts

- `WorkerFaultScenarioIndex` is the current Java ledger for worker-fault matrix
  rows.
- Current matrix proof covers PR chaos, perf smoke, SDK transport load, polling
  soak, WebSocket churn, Redis runtime owner restart/reconnect, proof-registry
  closure, and trace overflow incomplete-proof semantics.
- `polling-redis-restart-recovery` proves Redis-backed runtime owner
  restart/reconnect during active leased work. It does not prove Redis process
  kill, network partition, Redis failover, or clock-skew behavior.
- The current trace analyzer path refuses pass results when a known
  `droppedCount` makes absence-based proof unsafe.
- Current owner docs and runbooks are `xa-mass-testing/README.md`,
  `xa-mass-testing/VERIFIED_RUNBOOK.md`, `doc/TESTING_INDEX.md`,
  `doc/PROOF_REGISTRY.md`, and `doc/TRACE_CONTRACT.md`.

## Decision Needed

Redis process kill, Redis partition/failover, lease-clock skew, and multi-node
presence flap are not ready to implement as worker-fault matrix rows.

Reasons:

- Redis process kill, partition, and failover need a deterministic local or CI
  harness that owns Redis process/container lifecycle and can distinguish
  runtime-owner restart/reconnect from Redis server failure.
- Lease-clock skew needs an explicit runtime clock seam. Runtime and presence
  code still use current-time calls in several owner paths, so a test-only skew
  hook would be fake proof.
- Multi-node presence flap needs a split-node harness that composes transport
  presence, node-targeted handoff, engine retry/compensation, and task
  convergence. Single-runner worker churn is not the same proof.

## Allowed Outcomes

- Create a new infra-fault roadmap with deterministic Redis partition/failover
  harness rules and explicit clock-seam ownership.
- Narrow these rows to owner-local runtime/transport tests and manual drills,
  without claiming worker-fault matrix proof.
- Defer until split-runtime and infra-drill fixtures are first-class test
  assets.

## Hard Rules

- Do not mutate `TaskWorkRuntime`, `TaskResultRuntime`, or `WorkerRegistry`
  through test-only `fault.*` hooks.
- Do not fake capacity, clock, Redis failure, or split-node behavior through
  capability attributes or worker-pack local state.
- Do not list Redis partition/failover, process kill, lease-clock skew, or
  multi-node presence flap as covered distributed-edge proof until the owner
  harness exists and the proof registry names the real runner/analyzer.

## Verification Before Reopening

```powershell
rg -n "polling-redis-restart-recovery|sdk-transport-load-websocket-churn|TRACE_INCOMPLETE" xa-mass-testing xa-mass-trace doc
rg -n "Redis process kill|partition/failover|lease-clock skew|multi-node presence" doc xa-mass-testing roadmap -g "*.md"
.\mvnw.cmd -pl xa-mass-testing,xa-mass-trace -am "-Dtest=WorkerFaultScenarioIndexTest,ProofRegistryClosureGuardTest,TraceOperatorServiceIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```
