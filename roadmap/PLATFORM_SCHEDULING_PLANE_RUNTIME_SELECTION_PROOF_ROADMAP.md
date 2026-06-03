# Platform Scheduling Plane Runtime Selection Proof Roadmap

Status: proposed proof roadmap.

Predecessors:

- `roadmap/PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md`
- `roadmap/PLATFORM_SCHEDULING_PLANE_DECISION.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_TRACE_PROOF_GAPS.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_PUBLIC_VOCABULARY_CHECKPOINT.md`

## Purpose

The Scheduling Plane now has engine-facing resolved views, but the next proof
step should not be a policy-product roadmap.

This roadmap proves `RuntimeWorkerSelection` as the concrete worker-choice
owner inside the Scheduling Plane. It keeps strategy and mechanism separate:
resolved worker policy defines the static worker universe and caller/default
constraints, while runtime worker selection owns live reachability, load,
ranking, reserve, lock, and admission.

The goal is to make the current default path defensible before adding any
persisted catalog, project/workload binding, SDK/server policy configuration,
or second worker policy variant.

## Current Baseline

Current implemented shape:

```text
Task shell / TaskExecutionSpec / shared config
  -> TaskDispatchIntent
  -> DefaultSchedulingPlaneResolver
     -> ResolvedWorkerSchedulingPolicy
        -> WorkerTaskSelector
        -> worker candidate source
        -> rule eligibility
        -> candidate ranking
        -> reserve / lock / admission
        -> assignment trace and diagnostics
```

Current code has a resolved worker-side input view:

- `ResolvedWorkerSchedulingPolicy` carries WorkerGroup selector inputs, adapter
  node constraint, route bucket keys, target worker, and target attributes.
- `WorkerTaskSelectorFactory#fromPolicy` converts the resolved worker view into
  candidate-source constraints.
- `RuleBasedTaskWorkerMatchingStrategy` consumes a single scheduling-plane
  resolution per dispatch matching pass.
- `WorkerMatchContext#getRuleContext()` is the rule-readable context; the full
  context snapshot remains diagnostic evidence.
- `WorkerCandidateRanker`, `WorkerAdmissionRuntime`,
  `WorkerDispatchResourcePolicy`, warm hints, reservation, lock, and assignment
  diagnostics are current runtime selection mechanisms.

Current code does not yet have a dedicated `RuntimeWorkerSelection` class or a
new module. That is acceptable for this roadmap. The proof target is the owner
boundary and call-order contract, not a wrapper service.

## Boundary Decision

`RuntimeWorkerSelection` is a first-class Scheduling Plane owner, but it is not
a policy family.

The boundary is:

```text
ResolvedWorkerSchedulingPolicy
  owns static / resolved inputs:
  - worker group selector
  - adapter node constraint
  - route bucket keys
  - target worker constraint
  - target attributes

RuntimeWorkerSelection
  owns live worker choice:
  - candidate availability
  - live reachability
  - load and slots
  - warm hints
  - rule eligibility outcome
  - ranking order
  - reserve / lock / release
  - admission result
  - assignment diagnostic evidence
```

The resolved view feeds the runtime selection mechanisms. It must not become a
container for live runtime evidence, and runtime selection must not become a
new policy definition surface.

## Strategy And Mechanism Separation

Strategy owns defaults and caller-visible constraints:

- which WorkerGroup universe a task may use,
- route and target narrowing inputs,
- future policy variant selection only after there is a proven caller and cost.

Mechanism owns runtime execution:

- worker presence and reachability,
- slots, load, draining, reservation, and lock state,
- ranking, admission, dispatch resource usage, and release,
- trace/audit evidence for why a worker was accepted or rejected.

Initial support may remain one default strategy. The architecture should be
extensible because these owners are cleanly separated, not because a catalog,
DSL, or plug-in framework exists early.

## Hard Rules

1. No new persisted `SchedulingPolicyCatalog`.
2. No new `ProjectSchedulingBinding` implementation.
3. No new SDK/server worker policy configuration surface.
4. No second worker policy variant without caller, cost, proof, and owner
   review.
5. No live reachability, slot, load, reserve, lock, admission, or warm-hint
   evidence in `ResolvedWorkerSchedulingPolicy`.
6. No rule-readable live admission or reserve evidence outside the approved
   `WorkerMatchContext#getRuleContext()` boundary.
7. No candidate ranking code mutates reserve, lock, release, or admission
   truth.
8. No policy resolver imports or calls `WorkerAdmissionRuntime`,
   `WorkerWarmHintRuntime`, or dispatch resource mutation APIs.
9. No item payload or `eventCode` worker-selection semantics.
10. No trace or assignment diagnostic record becomes worker-selection truth.
11. No broad `RuntimeWorkerSelectionService` facade unless it introduces a real
    owner boundary, protocol seam, lifecycle split, or external caller surface.

## Out Of Scope

- implementing a policy catalog
- implementing project/workload binding
- adding public policy APIs
- adding worker policy authoring UI
- adding rule DSL features
- adding stateful fairness or quota policy
- changing task lifecycle, result convergence, or transport delivery semantics
- extracting a module only to wrap existing engine mechanisms

## Known Proof Gaps

- `RuntimeWorkerSelection` is named in architecture docs, but current code is
  still implemented through several engine and worker-runtime mechanisms.
- Resolved worker policy consumption is proven for candidate-source narrowing,
  but runtime evidence perturbation still needs stronger direct proof.
- Ranking, reserve, lock, and admission order is partly encoded in tests and
  call sites, but the owner boundary is not yet guarded as a first-class
  Scheduling Plane invariant.
- Trace explains several scheduling facts, but trace coverage should stay a
  bounded proof surface rather than an invitation to add broad observability.
- Future policy-product work could start too early if the roadmap does not keep
  catalog/binding gates closed until runtime selection proof is complete.

## RS-0 Runtime Selection Inventory

Goal: classify every current worker-choice fact by owner before changing code.

Scope:

- Inventory production symbols and call sites for:
  - `ResolvedWorkerSchedulingPolicy`,
  - `WorkerTaskSelectorFactory`,
  - `RuleBasedTaskWorkerMatchingStrategy`,
  - `WorkerMatchContext`,
  - `WorkerCandidateRanker`,
  - `WorkerAdmissionRuntime`,
  - `WorkerWarmHintRuntime`,
  - `WorkerDispatchResourcePolicy`,
  - assignment records and trace diagnostics.
- Classify each fact as one of:
  - resolved worker policy input,
  - candidate-source constraint,
  - rule-readable eligibility input,
  - diagnostic-only evidence,
  - ranking evidence,
  - admission truth,
  - reserve / lock / release truth,
  - trace/audit evidence.
- Record any owner ambiguity as named residue before implementation starts.

Acceptance:

- A current-state inventory exists under the owning engine baseline docs or as
  a sibling roadmap inventory.
- The inventory distinguishes static resolved inputs from live runtime evidence.
- No field is classified as both policy truth and runtime truth.
- `RuntimeWorkerSelection` is described as an owner boundary, not as an already
  implemented facade.

Suggested checks:

```powershell
rg -n "ResolvedWorkerSchedulingPolicy|WorkerTaskSelectorFactory|RuleBasedTaskWorkerMatchingStrategy|WorkerMatchContext|WorkerCandidateRanker|WorkerAdmissionRuntime|WorkerWarmHintRuntime|WorkerDispatchResourcePolicy" xa-mass-engine xa-mass-worker-runtime --glob '!**/target/**'
rg -n "RuntimeWorkerSelection" README.md AGENTS.md doc xa-mass-engine roadmap --glob '!**/target/**'
```

## RS-1 Resolved Policy Purity Guards

Goal: prevent live runtime evidence from leaking into resolved worker policy.

Scope:

- Add architecture guards proving:
  - `ResolvedWorkerSchedulingPolicy` does not expose live evidence fields,
  - policy resolver classes do not import admission or resource mutation APIs,
  - candidate selector construction consumes the resolved worker policy rather
    than raw task fields,
  - production matching paths do not bypass the resolved view after migration.
- Keep guard scope source-detectable and focused on migrated facts.
- Do not add reflective or brittle checks for unrelated implementation details.

Acceptance:

- A production consumer cannot silently reintroduce raw-task worker selection
  for migrated fields.
- No policy resolver or resolved view depends on `WorkerAdmissionRuntime`,
  `WorkerWarmHintRuntime`, reserve, lock, or release mutation APIs.
- Existing resolved policy tests still cover worker group, route, adapter node,
  target worker, and target attributes.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=DefaultSchedulingPlaneResolverTest,WorkerTaskSelectorFactoryTest,EngineSchedulingCoreArchitectureGuardTest" test
rg -n "WorkerTaskSelectorFactory\\.fromTask\\(|getWorkerGroupIds\\(|getTargetWorkerId\\(|getAdapterNodeId\\(" xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "WorkerAdmissionRuntime|WorkerWarmHintRuntime|WorkerDispatchResourcePolicy" xa-mass-engine/src/main/java/com/xa/mass/engine/runtime/scheduling xa-mass-engine/src/main/java/com/xa/mass/engine/strategy --glob '!**/target/**'
```

## RS-2 Runtime Evidence Perturbation Proof

Goal: prove runtime selection decisions are driven by runtime evidence after the
resolved worker universe has been computed.

Scope:

- Add focused perturbation tests where the resolved worker policy is unchanged,
  but runtime evidence changes the outcome:
  - reachable worker becomes unreachable,
  - capacity or admission becomes full,
  - worker enters draining or dispatch-disabled state,
  - lock/reservation conflict prevents assignment,
  - rankable load/warm-hint evidence changes selected worker order,
  - target worker exists but is not currently admissible.
- Keep each test explicit about what remains static policy input and what is
  runtime evidence.
- Do not encode trace fields as runtime truth.

Acceptance:

- At least one test proves a resolved worker policy can remain stable while
  runtime selection outcome changes.
- At least one test proves target narrowing does not bypass admission.
- At least one test proves ranking order is read-only and admission/reserve is
  still owned by runtime mechanisms.
- Perturbation tests do not require a catalog, binding, or public policy config.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=RuleBasedTaskWorkerMatchingStrategyTest,DefaultWorkerCandidateRankerTest,TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest,EngineSchedulingCoreArchitectureGuardTest" test
```

## RS-3 Selection Order Contract

Goal: make the worker-choice order explicit and guarded.

Target order:

```text
resolve worker policy
  -> build candidate-source selector
  -> acquire candidate universe
  -> build match context
  -> evaluate approved rule context
  -> rank rule-passed candidates
  -> reserve / lock / admission
  -> bind assignment
  -> release or record rejection on failure
  -> emit trace / diagnostics
```

Scope:

- Prove the current order from code and tests.
- Add guards that prevent:
  - ranking before eligibility,
  - admission mutation inside rule evaluation,
  - lock/reserve mutation inside ranking,
  - assignment binding before admission succeeds,
  - trace-driven selection decisions.
- Keep this as a behavior contract, not a request for a new orchestration
  facade.

Acceptance:

- The order contract is documented in the engine baseline or roadmap proof
  section.
- Tests cover reserve/lock release on failed assignment or rejection paths.
- Architecture guards prevent obvious admission/ranking/rule boundary regressions.
- No new service exists only to rename the existing call chain.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=RuleBasedTaskWorkerMatchingStrategyTest,TaskWorkerAssignListenerTest,TaskSchedulingContentionTest,EngineSchedulingCoreArchitectureGuardTest" test
rg -n "rank\\(|reserve|lock|admission|release" xa-mass-engine/src/main/java/com/xa/mass/engine xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
```

## RS-4 Trace And Diagnostic Proof

Goal: prove runtime selection is explainable without making trace the source of
truth.

Scope:

- Use the existing trace proof gap baseline as the starting point.
- Verify that accepted and rejected selection paths expose enough evidence for:
  - resolved worker universe,
  - route or target narrowing,
  - rule rejection,
  - ranking decision where currently available,
  - admission or resource rejection,
  - assignment release / failure reason.
- Record missing evidence as bounded gaps with owners.
- Add trace fields only when an existing analyzer or proof case cannot explain
  a real runtime selection outcome from current evidence.

Acceptance:

- Trace/assignment evidence is classified as evidence, not worker-selection
  truth.
- Any trace gap is recorded as a bounded list with a named owner.
- No broad trace enrichment is added without a concrete proof case.
- No runtime decision reads back from trace or assignment diagnostics.

Suggested proof:

```powershell
rg -n "workerGroup|targetWorker|route|admission|reject|release|rank|candidate" xa-mass-engine/src/main/java/com/xa/mass/engine xa-mass-trace --glob '!**/target/**'
rg -n "TraceEventLogger|AssignmentRecordService|assignment diagnostic|match" xa-mass-engine/src/test xa-mass-testing --glob '!**/target/**'
```

## RS-5 Successor Gate

Goal: decide whether policy-product work is justified after runtime selection
is proven.

Do not start a successor policy roadmap unless all of these are true:

- `RuntimeWorkerSelection` owner boundary is proven by guards and tests.
- Resolved worker policy is still pure static input.
- Runtime evidence perturbation proof exists.
- Selection order contract is documented and guarded.
- Trace explains current decisions without becoming truth.
- At least two concrete worker policy variants exist as real caller needs, with
  different caller-visible cost or behavior.
- The future binding subject is decided: project as governance/quota scope,
  workload as scheduling axis, or a different explicit split.
- Storage owner, SDK/server surface, runtime consumer, migration path, and
  proof commands are named before any catalog/binding implementation begins.

Output:

- either keep policy-product work closed and record remaining proof residue,
- or draft a separate `SchedulingPolicyCatalog` / binding roadmap with concrete
  callers, variants, costs, and proof surfaces.

## Verification Matrix

| Proof Surface | Primary Evidence | Commands |
| --- | --- | --- |
| Runtime fact inventory | Current code and owner classification | `rg -n "ResolvedWorkerSchedulingPolicy|WorkerAdmissionRuntime|WorkerDispatchResourcePolicy" xa-mass-engine xa-mass-worker-runtime --glob '!**/target/**'` |
| Resolved policy purity | Resolver, selector, architecture guard tests | `mvn -pl xa-mass-engine "-Dtest=DefaultSchedulingPlaneResolverTest,WorkerTaskSelectorFactoryTest,EngineSchedulingCoreArchitectureGuardTest" test` |
| Rule context boundary | `WorkerMatchContext` and rule evaluator tests | `mvn -pl xa-mass-engine "-Dtest=WorkerMatchContextTest,RuleConfigTest,QLExpressRuleEvaluatorTest,EngineSchedulingCoreArchitectureGuardTest" test` |
| Runtime perturbation | Matching, ranking, contention, targeting tests | `mvn -pl xa-mass-engine "-Dtest=RuleBasedTaskWorkerMatchingStrategyTest,DefaultWorkerCandidateRankerTest,TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest" test` |
| Selection order | Matching, assignment, release, guard tests | `mvn -pl xa-mass-engine "-Dtest=RuleBasedTaskWorkerMatchingStrategyTest,TaskWorkerAssignListenerTest,TaskSchedulingContentionTest,EngineSchedulingCoreArchitectureGuardTest" test` |
| Trace evidence | Trace proof gaps and focused trace tests | `rg -n "TraceEventLogger|AssignmentRecordService|admission|release|candidate" xa-mass-engine xa-mass-trace xa-mass-testing --glob '!**/target/**'` |

## Risks

- Runtime evidence leaks into resolved worker policy because it is convenient
  for a future policy variant.
- Rule context expands into a hidden runtime admission API.
- Ranking starts mutating reserve, lock, or admission state.
- Trace or assignment diagnostics are treated as replayable selection truth.
- A broad `RuntimeWorkerSelectionService` hides current owners without changing
  the real boundary.
- Catalog/binding work starts before runtime selection is proven.

## Exit Criteria

1. Runtime worker-selection facts are inventoried and owner-classified.
2. `ResolvedWorkerSchedulingPolicy` remains a static input view with guards
   against live runtime evidence.
3. Rule-readable context remains narrower than diagnostic context.
4. Runtime evidence perturbation tests prove worker choice can change while the
   resolved worker policy remains unchanged.
5. Target worker narrowing does not bypass admission, reserve, lock, or release
   rules.
6. Ranking, rule evaluation, and admission mutations have separate proof
   surfaces.
7. Selection order is documented and guarded without adding a pass-through
   facade.
8. Trace explains current worker selection decisions without becoming truth.
9. No catalog, binding, SDK/server policy config, or second worker policy
   variant is added by this roadmap.
10. A successor policy-product roadmap remains blocked until RS-5 evidence
    exists.
