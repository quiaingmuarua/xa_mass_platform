# Runtime Worker Selection Residue Convergence Roadmap

Status: superseded for transport route-owner and dispatch-handoff details.

Transport route-key and dispatch-handoff truth has moved to
`transport/TRANSPORT_BOUNDARY_BASELINE.md` and `doc/PROOF_REGISTRY.md`: current
SDK/starter routeKey minting is worker-group-level, transport treats routeKey
as opaque metadata, transport route evidence is not worker online/offline
truth, and distributed dispatch handoff uses delivery-command batches with selected-worker delivery constraints rather than node-targeted or worker-resource-resolving dispatch. Historical transport convergence records
are archived under `doc/archive/transport/2026-06-12_*TRANSPORT_*`. The
remaining worker-selection cleanup in this roadmap must be re-read through the
current transport baseline before implementation.

Predecessors:

- `roadmap/PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md`
- `xa-mass-engine/doc/roadmap/WORKER_MATCH_UPGRADE_ROADMAP.md`
- `doc/archive/transport/2026-06-10_WORKER_RUNTIME_BOUNDARY_CONVERGENCE_ROADMAP.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `xa-mass-worker-runtime/README.md`
- `xa-mass-worker-runtime/CONTRACTS.md`

## Purpose

This roadmap is a cleanup and proof-hardening pass for runtime worker
selection across engine, worker-runtime, and transport. The previous
engine-only framing was insufficient because worker reachability is
transport-derived evidence. If transport route-owner truth is not included in
the proof chain, scheduling can still drift into two live truths:

- transport presence / route owner truth,
- worker-runtime or engine-side reachability truth.

The target chain is:

```text
transport route-owner presence
  -> WorkerReachabilityView assembly
  -> worker-runtime scheduling evidence
  -> engine runtime worker selection
  -> dispatch binding
  -> transport route selection / delivery
  -> result / compensation
```

This roadmap does not introduce a new policy product, matching plugin,
transport facade, or `RuntimeWorkerSelectionService`. It converges existing
owners and adds guards/proofs so the current boundary cannot drift back.

## Core Ownership

Transport owns:

- route-owner presence keyed by opaque route key,
- transport node connection evidence,
- delivery queue ownership keyed by route key,
- adapter connection diagnostics.

Worker-runtime owns:

- worker declaration and WorkerGroup / AdapterNode / NodeGroupBinding facts,
- worker scheduling views and candidate source,
- worker reachability evidence contract consumed by scheduling,
- dispatch gates, slot/load/occupancy, command lifecycle, and admission facts.

Engine owns:

- task scheduling orchestration,
- worker policy resolution and selector construction,
- runtime worker selection order,
- rule evaluation, ranking, reservation/lock/admission calls,
- dispatch binding and failure compensation.

Transport does not decide whether a worker is schedulable. Engine and
worker-runtime do not own transport route-owner state. The previous idea of
deriving `WorkerReachabilityView` from transport route-owner lookup is
superseded; worker reachability/admission evidence belongs to worker-runtime or
an explicitly owned worker-system-event projection, not to transport session
lease evidence.

## Superseded Baseline

The following baseline was true before route-targeted transport convergence and
must not be used as current implementation truth:

- routeKey was briefly documented as worker-level instead of the current
  worker-group consumption route.
- transport presence lookup was treated as a possible
  `WorkerReachabilityView` assembly source.
- route selection was modeled as a workerId-to-route resolver after selection.
- worker-id presence inspection APIs were treated as compatibility/operator
  surfaces.

The remaining risk is that future worker selection code can accidentally
consume route-owner projections, scan-based route views, or diagnostic context
as scheduling truth.

## Non-Goals

- No persisted `SchedulingPolicyCatalog`.
- No `ProjectSchedulingBinding` implementation.
- No SDK/server worker policy configuration surface.
- No new worker-selection policy variant.
- No rule DSL feature expansion.
- No new same-module bridge/facade/wrapper that only forwards to existing
  owners.
- No compatibility fallback or parallel old/new match path.
- No event-code or item-payload worker selection semantics.
- No transport-owned scheduling decision.
- No engine-owned transport presence store.

## Hard Rules

1. Scheduling reachability must be consumed through worker-runtime owned
   evidence or an explicitly owned worker-system-event projection.
2. Production `WorkerReachabilityView` assembly must not derive worker
   schedulability from transport route-owner session leases.
3. Runtime worker selection must not call transport route-owner inspection
   projections such as `getLatestOwnerByWorker`, `isWorkerReachable`, or
   `findRouteOwners`.
4. Engine production code must not import transport route-owner store
   implementations.
5. Worker-runtime production code must not import transport route-owner store
   implementations.
6. Transport may resolve route ownership after a worker is selected, but it
   must not choose the worker.
7. Dispatch route failure must compensate through engine/runtime failure paths;
   transport must not reschedule work to a different worker.
8. Rule evaluation must consume `WorkerMatchContext#getRuleContext()`, never
   full diagnostic context.
9. Rule-readable context must not contain live reachability, dispatch gate,
   lock, reserve, active lease, capacity, load, admission, route-owner, or
   warm-hint evidence unless a successor decision explicitly approves a named
   exception.
10. Diagnostic context may explain a runtime-selection decision, but it must
    not drive rule evaluation, candidate source, ranking policy selection,
    reserve, lock, or admission.
11. `ResolvedWorkerSchedulingPolicy` must not carry live runtime or transport
    evidence.
12. Candidate ranking must be read-only. It may order candidates; it must not
    mutate reserve, lock, release, admission, or transport route truth.
13. Source scans are guardrails, not behavior proof. Runtime-selection proof
    must use lifecycle, contention, targeting, transport, failure, or
    trace-observed tests.

Approved production order:

```text
resolve worker policy
  -> build selector
  -> acquire candidates
  -> prefilter live worker-runtime gates
  -> evaluate rule context
  -> rank
  -> reserve / lock / admission
  -> bind selected worker
  -> resolve transport route owner for selected worker
  -> dispatch or compensate
```

## RWS-C0 Cross-Layer Owner Inventory

Goal: make the current runtime-worker-selection owner chain explicit before
changing behavior.

Scope:

- Inventory every production caller of `WorkerReachabilityView`.
- Inventory every production caller of transport route-owner lookup and
  inspection methods.
- Classify each caller as:
  - transport route-owner truth,
  - reachability assembly seam,
  - operator/inspection compatibility projection,
  - dispatch route selection after worker binding,
  - residue to remove or guard.
- Inventory every key in `WorkerMatchContext#getRuleContext()`.
- Inventory every key present only in full `WorkerMatchContext#getContext()`.
- Classify each match-context key as:
  - rule-readable static task intent,
  - rule-readable worker capability/static attribute,
  - diagnostic-only resolved task policy evidence,
  - diagnostic-only runtime worker evidence,
  - diagnostic-only transport route-owner evidence,
  - diagnostic-only assignment/source statistic,
  - residue to remove.

Acceptance:

- The inventory states that transport route-owner lookup is allowed for
  dispatch handoff assembly only, after worker selection has already produced
  bindings.
- Worker-id route-owner projections are explicitly classified as
  operator/support surfaces, not scheduling truth.
- The rule-readable key set is asserted as an explicit contract.
- Diagnostic-only live runtime and transport keys are asserted as absent from
  rule context.
- No production behavior changes.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=WorkerMatchContextTest,EngineSchedulingCoreArchitectureGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
rg -n "getLatestOwnerByWorker\\(|isWorkerReachable\\(|findRouteOwners\\(|currentOwner\\(" \
  xa-mass-engine xa-mass-worker-runtime sdk transport --glob '!**/target/**'
```

## RWS-C1 Transport Route-Owner Boundary Guards

Goal: prevent transport route-owner projections from becoming scheduling truth.

Scope:

- Strengthen architecture guards so engine and worker-runtime production code
  cannot import transport route-owner store implementations.
- Guard runtime worker selection paths from calling worker-id route-owner
  projection APIs such as `getLatestOwnerByWorker`, `isWorkerReachable`, or
  `findRouteOwners`.
- Keep those APIs allowed only in explicitly classified operator/inspection or
  test/support surfaces.
- Guard route-owner lookup direction:
  - scheduling reachability does not read transport route-owner leases,
  - dispatch handoff assembly uses already selected bindings plus injected
    route-key resolver,
  - neither path scans all active route owners.

Acceptance:

- A source guard fails if runtime worker selection consumes worker-id
  route-owner projections or scan-based active route lists.
- A source guard fails if engine or worker-runtime production code imports
  transport route-owner store implementations.
- Embedded SDK assembly must not adapt transport route-owner leases into
  `WorkerReachabilityView`.
- Transport dispatch handoff assembly remains allowed to resolve route owners
  after worker bindings are already selected.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=EngineSchedulingCoreArchitectureGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
rg -n "findRouteOwners\\(|getLatestOwnerByWorker\\(|isWorkerReachable\\(" \
  xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java \
  --glob '!**/target/**'
```

## RWS-C2 Route-Owner Dispatch Behavior Proof

Goal: prove that transport route-owner truth is used for dispatch handoff only,
not for worker scheduling reachability.

Scope:

- Add or tighten focused tests for:
  - SDK/starter default route key is derived from `workerGroupId`,
  - dispatch handoff uses the resolved opaque `routeKey`,
  - missing route owner triggers engine-owned compensation after binding,
  - offline transport node triggers engine-owned compensation after binding,
  - stale/offline evidence from an old connection cannot revoke a newer owner,
  - worker-id inspection projection cannot make transport dispatch possible when
    resolved route owner is absent.
- Prefer existing transport runtime and embedded SDK tests over broad E2E.

Acceptance:

- Transport route-owner lookup is not needed to prove schedulability.
- Worker-id projection APIs are not needed for dispatch handoff.
- Route-owner takeover semantics are covered by tests.
- Memory and Redis route-owner stores both satisfy the route-owner contract where
  relevant.

Suggested proof:

```bash
./mvnw -q -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk -am \
  -Dtest=MassApplicationDistributedTransportTest,RedisTransportRouteOwnerStoreTest,MassApplicationDistributedTransportTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## RWS-C3 Rule And Diagnostic Context Hardening

Goal: prevent diagnostic context from becoming rule policy input.

Scope:

- Strengthen `EngineSchedulingCoreArchitectureGuardTest` so production rule
  evaluation cannot call evaluator methods with `getContext()`.
- Guard common bypass shapes:
  - direct `ruleEvaluator.evaluate(..., matchContext.getContext())`,
  - helper methods that receive full context and then evaluate rules,
  - evaluator overloads added to accept `WorkerMatchContext` directly.
- Keep `getContext()` allowed for logs, trace, and assignment diagnostics.
- Treat transport route-owner and reachability evidence as diagnostic-only in
  match context unless already reduced to approved hard-gate outcomes.

Acceptance:

- A source guard fails if rule evaluation consumes full diagnostic context.
- Existing assignment diagnostics can still include full context snapshots.
- `RuleBasedTaskWorkerMatchingStrategy` still evaluates only rule context.
- Transport-derived evidence remains absent from rule-readable context.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=EngineSchedulingCoreArchitectureGuardTest,RuleBasedTaskWorkerMatchingStrategyTest,WorkerMatchContextTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
rg -n "evaluate\\([^;]*getContext\\(|getContext\\(\\).*evaluate|evaluate\\([^;]*WorkerMatchContext" \
  xa-mass-engine/src/main/java xa-mass-engine/src/test/java --glob '!**/target/**'
```

## RWS-M1 Runtime Selection Order And Compensation Proof

Goal: make the match call order and failure compensation auditable without
extracting a pass-through service.

Scope:

- Add focused tests around the current order:
  - reachability / dispatch gate / existing lock reject before rule evaluation,
  - rule failure rejects before reserve,
  - reserve failure does not try exclusive lock,
  - exclusive-lock failure releases reservation,
  - accepted non-exclusive path reserves without lock,
  - accepted exclusive path reserves then locks before binding.
- Add transport-facing compensation proof:
  - worker is selected and reserved,
  - dispatch route owner is missing or offline,
  - dispatch submitter records route failure,
  - worker reservation/resource is released or made retry-safe,
  - transport does not choose an alternate worker.
- Prefer existing `RuleBasedTaskWorkerMatchingStrategyTest`,
  `MassApplicationDistributedTransportTest`, and scheduling matrix tests over new
  broad suites.
- Do not split the production strategy unless a small extraction removes
  repeated failure compensation code without hiding owner boundaries.

Acceptance:

- Tests prove each failure exit either never acquired runtime resource or
  releases the resource it acquired.
- Tests prove ranking is read-only and happens before reserve/lock mutation.
- Tests prove route-owner failure after binding is compensation, not
  transport-side rescheduling.
- No new wrapper or facade is introduced.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine,transport/transport_runtime -am \
  -Dtest=RuleBasedTaskWorkerMatchingStrategyTest,TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest,MassApplicationDistributedTransportTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## RWS-M2 Resolved Worker Policy Perturbation Proof

Goal: prove worker-side static policy inputs affect candidate universe, while
runtime and transport evidence remain outside policy resolution.

Scope:

- Add perturbation tests for `ResolvedWorkerSchedulingPolicy` consumers:
  - worker group selector narrows candidate source,
  - adapter node constraint narrows candidate source,
  - candidate bucket keys narrow candidate source,
  - target worker narrows candidate source but does not bypass Stage-2 gates,
  - target attributes are enforced as Stage-2 constraints.
- Add guard coverage that resolver and selector construction do not import or
  call live runtime evidence owners such as admission, warm hints, lock, load,
  reachability runtime APIs, or transport presence APIs.

Acceptance:

- Perturbing resolved worker policy changes candidate universe or rejection
  reason predictably.
- Perturbing diagnostic-only live evidence does not change rule evaluation
  outcome through rule context.
- `targetWorkerId` remains group-scoped and cannot bypass reachability,
  dispatch gate, load, lock, rule, reserve, admission, or route-owner dispatch
  checks.

Suggested proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=DefaultSchedulingPlaneResolverTest,TaskSchedulingGateAndTargetingTest,WorkerMatchContextTest,EngineSchedulingCoreArchitectureGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## RWS-D1 Residue Removal

Goal: remove or guard old paths after the cross-layer boundary is proven.

Scope:

- Ensure `WorkerTaskSelectorFactory#fromTask` remains absent from production.
- Ensure `TaskDispatchIntent#fromTask` is only used inside
  `DefaultSchedulingPlaneResolver`.
- Remove stale docs or test names that describe full `WorkerMatchContext` as
  the policy contract.
- Remove or explicitly classify active docs that describe worker-id presence
  projections as scheduling truth.
- Update `PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md`,
  `transport/TRANSPORT_BOUNDARY_BASELINE.md`, or worker-runtime contracts only
  with current facts proven by previous slices.
- If all slices complete, archive this roadmap and move durable facts into the
  owning baselines / READMEs.

Acceptance:

- No production call site computes worker selector or dispatch intent outside
  the resolver boundary.
- No production runtime-selection path uses worker-id presence projection as
  scheduling truth.
- No active doc treats diagnostic context, trace, assignment records, or
  worker-id presence projections as scheduling truth.
- No active doc implies a new runtime-selection service exists.
- `git diff --check` and focused tests pass.

Suggested residue scans:

```bash
rg -n "WorkerTaskSelectorFactory\\.fromTask\\(" \
  xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "TaskDispatchIntent\\.fromTask\\(" \
  xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "getPresence\\(|isWorkerOnline\\(|findOwners\\(|listActivePresences\\(" \
  xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java \
  --glob '!**/target/**'
rg -n "RuntimeWorkerSelectionService|SchedulingPolicyCatalog|ProjectSchedulingBinding" \
  xa-mass-engine/src/main/java xa-mass-server/src/main/java sdk \
  --glob '!**/target/**'
```

## Verification Bundle

Minimum completion proof:

```bash
./mvnw -q -pl xa-mass-engine -am \
  -Dtest=WorkerMatchContextTest,RuleBasedTaskWorkerMatchingStrategyTest,DefaultSchedulingPlaneResolverTest,TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,EngineSchedulingCoreArchitectureGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q -pl transport/transport_runtime,sdk/xa-mass-embedded-sdk -am \
  -Dtest=MassApplicationDistributedTransportTest,RedisTransportRouteOwnerStoreTest,MassApplicationDistributedTransportTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

## Exit Criteria

This roadmap is complete when:

1. Transport route-owner presence is the only production transport truth that
   can influence scheduling reachability.
2. Worker reachability consumed by scheduling is derived through the approved
   `WorkerReachabilityView` assembly seam.
3. Worker-id presence projections remain operator/compatibility/support
   surfaces and cannot re-enter runtime worker selection.
4. Rule-readable context has an explicit key contract.
5. Diagnostic-only runtime and transport evidence cannot become rule input
   unnoticed.
6. Runtime selection order and failure compensation are proven.
7. Dispatch route failure after worker binding compensates instead of
   rescheduling inside transport.
8. Resolved worker policy perturbation proves static worker-universe ownership.
9. Target-worker selection remains a narrowed candidate source, not a bypass.
10. Active docs and guards reflect the cross-layer boundary without inventing a
    new service or policy product.
