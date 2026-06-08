# Runtime Worker Selection Residue Convergence Roadmap

Status: proposed convergence roadmap.

Predecessors:

- `roadmap/PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md`
- `xa-mass-engine/doc/roadmap/WORKER_MATCH_UPGRADE_ROADMAP.md`
- `doc/archive/xa-mass-engine/2026-06-03_PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_PROOF_ROADMAP.md`

## Purpose

This roadmap is a cleanup and proof-hardening pass for the worker-side half of
Scheduling Plane. It does not introduce a new policy product, matching plugin,
or `RuntimeWorkerSelectionService`.

The current implementation already has the correct high-level shape:

```text
TaskDispatchIntent
  -> ResolvedWorkerSchedulingPolicy
  -> WorkerTaskSelector
  -> candidate source
  -> rule-readable eligibility
  -> rank
  -> reserve / lock / admission
  -> dispatch binding
```

The remaining work is to make the boundary harder to misuse:

- `ResolvedWorkerSchedulingPolicy` owns static worker-universe inputs only.
- `WorkerMatchContext#getRuleContext()` owns declarative rule-readable input.
- full `WorkerMatchContext` owns diagnostic evidence only.
- runtime worker selection owns live reachability, dispatch gate, load,
  reserve, lock, admission, ranking execution, and release.

## Non-Goals

- No persisted `SchedulingPolicyCatalog`.
- No `ProjectSchedulingBinding` implementation.
- No SDK/server worker policy configuration surface.
- No new policy variant.
- No rule DSL feature expansion.
- No new `RuntimeWorkerSelectionService` or same-module facade unless a later
  slice proves a real owner boundary that cannot be expressed in the current
  call chain.
- No compatibility bridge, fallback, or parallel old/new match path.
- No event-code or item-payload worker selection semantics.

## Current Baseline

Current active owner documents already define the target boundary:

- `PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md` classifies current
  owner symbols, binding entries, selection order, residue candidates, and
  verification commands.
- `WORKER_MATCH_UPGRADE_ROADMAP.md` owns the engine-side match strategy,
  bounded candidate acquisition, warm hints, diagnostics, and tuning.
- `PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md` owns the
  broader Scheduling Plane stabilization gate.

This roadmap should not duplicate those documents. It narrows implementation
work to residues that can make the current boundary drift.

## Hard Rules

1. Rule evaluation must consume `WorkerMatchContext#getRuleContext()`, never
   the full diagnostic context.
2. Rule-readable context must not contain live reachability, dispatch gate,
   lock, reserve, active lease, capacity, load, admission, or warm-hint evidence
   unless a successor decision explicitly approves a named exception.
3. Diagnostic context may explain a runtime-selection decision, but it must not
   drive rule evaluation, candidate source, ranking policy selection, reserve,
   lock, or admission.
4. `ResolvedWorkerSchedulingPolicy` must not carry live runtime evidence.
5. Candidate ranking must be read-only. It may order candidates; it must not
   mutate reserve, lock, release, or admission truth.
6. Every production path that binds work to a worker must flow through the
   approved order:

```text
resolve worker policy
  -> build selector
  -> acquire candidates
  -> prefilter live hard gates
  -> evaluate rule context
  -> rank
  -> reserve / lock / admission
  -> bind
  -> release or record rejection on failure
```

7. Source scans are guardrails, not behavior proof. Runtime-selection proof
   must use lifecycle, contention, targeting, failure, or trace-observed tests.

## RWS-C0 Rule And Diagnostic Context Inventory

Goal: make current `WorkerMatchContext` ownership explicit before changing code.

Scope:

- Inventory every key in `WorkerMatchContext#getRuleContext()`.
- Inventory every key present only in full `WorkerMatchContext#getContext()`.
- Classify each key as:
  - rule-readable static task intent,
  - rule-readable worker capability/static attribute,
  - diagnostic-only resolved task policy evidence,
  - diagnostic-only runtime worker evidence,
  - diagnostic-only assignment/source statistic,
  - residue to remove.
- Keep this inventory in `WorkerMatchContextTest` or an engine-local baseline
  table if it is too large for one test.

Acceptance:

- The rule-readable key set is asserted as an explicit contract.
- Diagnostic-only keys are asserted as absent from rule context.
- Live runtime evidence keys are named and tested as diagnostic-only.
- No production behavior changes.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=WorkerMatchContextTest,EngineSchedulingCoreArchitectureGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## RWS-C1 Rule Boundary Guard Hardening

Goal: prevent full diagnostic context from becoming rule policy input.

Scope:

- Strengthen `EngineSchedulingCoreArchitectureGuardTest` so production rule
  evaluation cannot call evaluator methods with `getContext()`.
- Guard common bypass shapes:
  - direct `ruleEvaluator.evaluate(..., matchContext.getContext())`,
  - helper methods that receive full context and then evaluate rules,
  - evaluator overloads added to accept `WorkerMatchContext` directly.
- Keep `getContext()` allowed for logs, trace, and assignment diagnostics.

Acceptance:

- A source guard fails if rule evaluation consumes full diagnostic context.
- Existing assignment diagnostics can still include full context snapshots.
- `RuleBasedTaskWorkerMatchingStrategy` still evaluates only rule context.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=EngineSchedulingCoreArchitectureGuardTest,RuleBasedTaskWorkerMatchingStrategyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
rg -n "evaluate\\([^;]*getContext\\(|getContext\\(\\).*evaluate|evaluate\\([^;]*WorkerMatchContext" \
  xa-mass-engine/src/main/java xa-mass-engine/src/test/java --glob '!**/target/**'
```

## RWS-C2 Runtime Selection Order Proof

Goal: make the match call order and failure compensation auditable without
extracting a pass-through service.

Scope:

- Add focused tests around the current order:
  - dispatch gate / reachability / existing lock reject before rule evaluation,
  - rule failure rejects before reserve,
  - reserve failure does not try exclusive lock,
  - exclusive-lock failure releases reservation,
  - accepted non-exclusive path reserves without lock,
  - accepted exclusive path reserves then locks before binding.
- Prefer existing `RuleBasedTaskWorkerMatchingStrategyTest` and scheduling
  matrix tests over new broad suites.
- Do not split the production strategy unless a small extraction removes
  repeated failure compensation code without hiding owner boundaries.

Acceptance:

- Tests prove each failure exit either never acquired runtime resource or
  releases the resource it acquired.
- Tests prove ranking is read-only and happens before reserve/lock mutation.
- No new wrapper or facade is introduced.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=RuleBasedTaskWorkerMatchingStrategyTest,TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## RWS-C3 Resolved Worker Policy Perturbation Proof

Goal: prove worker-side static policy inputs affect candidate universe, while
runtime evidence remains outside policy resolution.

Scope:

- Add perturbation tests for `ResolvedWorkerSchedulingPolicy` consumers:
  - worker group selector narrows candidate source,
  - adapter node constraint narrows candidate source,
  - candidate bucket keys narrow candidate source,
  - target worker narrows candidate source but does not bypass Stage-2 gates,
  - target attributes are enforced as Stage-2 constraints.
- Add guard coverage that resolver and selector construction do not import or
  call live runtime evidence owners such as admission, warm hints, lock, load,
  or reachability runtime APIs.

Acceptance:

- Perturbing resolved worker policy changes candidate universe or rejection
  reason predictably.
- Perturbing diagnostic-only live evidence does not change rule evaluation
  outcome through rule context.
- `targetWorkerId` remains group-scoped and cannot bypass reachability, dispatch
  gate, load, lock, rule, reserve, or admission checks.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=DefaultSchedulingPlaneResolverTest,TaskSchedulingGateAndTargetingTest,WorkerMatchContextTest,EngineSchedulingCoreArchitectureGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## RWS-D1 Residue Removal

Goal: remove or guard old paths after the boundary is proven.

Scope:

- Ensure `WorkerTaskSelectorFactory#fromTask` remains absent from production.
- Ensure `TaskDispatchIntent#fromTask` is only used inside
  `DefaultSchedulingPlaneResolver`.
- Remove stale docs or test names that describe full `WorkerMatchContext` as the
  policy contract.
- Update `PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md` only with
  current facts proven by the previous slices.
- If all slices complete, archive this roadmap and move durable facts into the
  engine baseline / README.

Acceptance:

- No production call site computes worker selector or dispatch intent outside
  the resolver boundary.
- No active doc treats diagnostic context, trace, or assignment records as
  scheduling truth.
- No active doc implies a new runtime-selection service exists.
- `git diff --check` and focused engine tests pass.

Suggested residue scans:

```bash
rg -n "WorkerTaskSelectorFactory\\.fromTask\\(" \
  xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "TaskDispatchIntent\\.fromTask\\(" \
  xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "getContext\\(\\).*rule|rule.*getContext\\(|evaluate\\([^;]*getContext\\(" \
  xa-mass-engine/src/main/java xa-mass-engine/src/test/java --glob '!**/target/**'
rg -n "RuntimeWorkerSelectionService|SchedulingPolicyCatalog|ProjectSchedulingBinding" \
  xa-mass-engine/src/main/java xa-mass-server/src/main/java sdk --glob '!**/target/**'
```

## Verification Bundle

Minimum completion proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=WorkerMatchContextTest,RuleBasedTaskWorkerMatchingStrategyTest,DefaultSchedulingPlaneResolverTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,EngineSchedulingCoreArchitectureGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q -pl xa-mass-engine -am test
git diff --check
```

## Exit Criteria

This roadmap is complete when:

1. Rule-readable context has an explicit key contract.
2. Diagnostic-only runtime evidence cannot become rule input unnoticed.
3. Runtime selection order and failure compensation are proven.
4. Resolved worker policy perturbation proves static worker-universe ownership.
5. Live runtime evidence remains outside resolver and selector construction.
6. Target-worker selection remains a narrowed candidate source, not a bypass.
7. Active docs and guards reflect the current boundary without inventing a new
   service or policy product.
