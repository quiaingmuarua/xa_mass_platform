# Worker Runtime Bounded Candidate Acquisition Roadmap

Status: proposed successor roadmap.

Parent:

- `doc/archive/core/2026-06-11_WORKER_RUNTIME_ADMISSION_AND_REDIS_SHAPE_CONVERGENCE_ROADMAP.md`

## Purpose

This roadmap owns the successor decision that the admission/Redis-shape roadmap
intentionally did not fold into a Redis-internal cleanup:

- bounded mainline candidate acquisition for Redis-backed `WorkerRegistry`,
- bounded or paged node-group worker maintenance operations.

The parent roadmap kept current complete-set semantics because changing either
path changes `WorkerRegistry` / `WorkerCandidateSamplingPolicy` semantics, not
just Redis physical shape.

## Current Facts

- `WorkerRegistry#acquireCandidates(...)` returns a bounded list, but the Redis
  implementation currently materializes the full candidate bucket before calling
  `WorkerCandidateSamplingPolicy`.
- `WorkerCandidateSamplingPolicy#sample(...)` currently receives the complete
  candidate list for the selected group/bucket. Redis-side sampling would change
  that policy contract.
- `WorkerRegistry#workerIdsByAdapterNodeGroup(...)` currently returns the full
  current worker set for one adapter node / worker group.
- Node-group dispatch gate default methods iterate that full set to apply or
  clear a gate.
- These are runtime SPI semantics. They must not be changed by hiding a Redis
  optimization behind the old method names.

## Boundary Decision

Current complete-set semantics stay valid until this roadmap changes the SPI.

Do not change `acquireCandidates(...)` to Redis-side random sampling while
`WorkerCandidateSamplingPolicy` still claims to receive the complete candidate
list.

Do not add a node-membership Redis key family only to make
`workerIdsByAdapterNodeGroup(...)` cheaper. First decide whether the runtime SPI
should expose paged maintenance, bounded batches, or a different node-group gate
owner.

## Non-Goals

- No public worker API, SDK, worker-pack, or transport protocol change.
- No worker-side final admission or pending-offer lease model.
- No change to `WorkerRegistry` reserve/confirm/release semantics.
- No Redis Cluster/hash-tag deployment decision.
- No hidden compatibility bridge that keeps old and new SPI semantics live.

## BCA-0: Caller And Cost Inventory

Goal:

Classify current call sites and quantify what must remain complete-set versus
what can become bounded/pageable.

Scope:

- Inventory production callers of:
  - `WorkerRegistry#acquireCandidates(...)`,
  - `WorkerRegistry#workerIdsByAdapterNodeGroup(...)`,
  - `disableDispatchForAdapterNodeGroup(...)`,
  - `clearDispatchDisableForAdapterNodeGroup(...)`.
- Separate scheduling hot path from control/maintenance paths.
- Record current ordering assumptions, duplicate handling, and sampling policy
  expectations.

Acceptance:

- Inventory states whether each caller requires complete-set semantics.
- Inventory states whether each caller can tolerate unordered, random, paged, or
  eventually complete processing.
- No code behavior changes.

## BCA-1: Candidate Acquisition Contract Decision

Goal:

Decide the contract before changing Redis reads.

Allowed outcomes:

- Keep `WorkerCandidateSamplingPolicy` as complete-list policy and add a new
  lower-level bounded Redis candidate fetch contract.
- Narrow `WorkerCandidateSamplingPolicy` so it explicitly accepts an
  implementation-provided bounded subset.
- Add a separate `WorkerCandidateAcquisitionPolicy` that owns bucket read shape
  and leaves ranking/sampling policy above it.

Acceptance:

- The selected contract states whether the policy sees complete candidate sets or
  bounded subsets.
- Memory and Redis implementations can both satisfy the same contract.
- Tests prove behavior through candidate outcome, not field-copy assertions.

## BCA-2: Redis Candidate Read Implementation

Goal:

Implement the selected contract in Redis without changing admission truth.

Scope:

- Replace full bucket `SMEMBERS` on the scheduling path only after BCA-1.
- Preserve Stage-2 reserve validation.
- Preserve or explicitly redefine ordering/randomness semantics.
- Keep stale candidates correctness-neutral.

Acceptance:

- Redis candidate acquisition no longer requires full bucket materialization when
  the selected contract says it should be bounded.
- Memory and Redis contract tests agree.
- Existing worker selection proof still excludes disallowed workers.

## BCA-3: Node-Group Maintenance Pagination

Goal:

Avoid unbounded node-group maintenance reads without introducing a duplicate
worker membership truth.

Allowed outcomes:

- Add paged/batched maintenance SPI for node-group dispatch gate changes.
- Keep complete-set API but move callers to a bounded loop owned by
  worker-runtime.
- Retain current complete-set API only if expected cardinality is bounded by a
  documented control-plane constraint.

Acceptance:

- Node-group gate changes can be applied without one Redis call materializing
  every node/group worker at large scale, unless the retained complete-set
  cardinality bound is explicitly accepted.
- No new Redis key family is added without a reviewed owner, lifecycle,
  cardinality formula, and cleanup path.
- Existing node-group gate behavior remains correct.

## Verification Candidates

```powershell
rg -n "acquireCandidates\\(|workerIdsByAdapterNodeGroup\\(|disableDispatchForAdapterNodeGroup\\(|clearDispatchDisableForAdapterNodeGroup\\(" platform_infra xa-mass-worker-runtime xa-mass-engine --glob '!**/target/**'
.\mvnw.cmd -pl platform_infra/mass-runtime-memory "-Dtest=InMemoryWorkerRegistryTest" test
.\mvnw.cmd -pl platform_infra/mass-runtime-redis "-Dtest=RedisWorkerRegistryTest" test
.\mvnw.cmd -pl xa-mass-engine "-Dtest=EngineSchedulingCoreArchitectureGuardTest#upperRuntimeCallersUseWorkerRegistrySemanticOperations" test
```

## Completion Criteria

This roadmap is complete when:

1. Candidate acquisition has an explicit complete-set or bounded-subset
   contract.
2. Redis candidate reads match that contract.
3. Node-group maintenance has an explicit complete-set or paged/batched
   contract.
4. No duplicate Redis worker membership truth is introduced without owner
   review.
5. Memory and Redis `WorkerRegistry` behavior remain aligned by contract tests.
