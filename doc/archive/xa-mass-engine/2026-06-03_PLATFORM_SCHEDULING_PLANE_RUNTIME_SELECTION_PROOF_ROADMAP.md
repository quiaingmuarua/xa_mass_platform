# Platform Scheduling Plane Runtime Selection Proof Roadmap

Status: implemented with integration-proof model repaired.

Predecessors:

- `roadmap/PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md`
- `roadmap/PLATFORM_SCHEDULING_PLANE_DECISION.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_TRACE_PROOF_GAPS.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_PUBLIC_VOCABULARY_CHECKPOINT.md`

## Purpose

The Scheduling Plane now has engine-facing resolved views, but the next proof
step should not be a policy-product roadmap.

This roadmap targets proof of `RuntimeWorkerSelection` as the concrete worker-choice
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

## Owner Review

Current code says `RuntimeWorkerSelection` is not one class yet. It is an owner
boundary implemented by several engine and worker-runtime mechanisms. This
roadmap should first prove those owners and only then decide whether an
extracted runtime-selection facade is justified.

Owner classification:

| Current Owner / Symbol | Owns | Consumes | Must Not Own |
| --- | --- | --- | --- |
| `DefaultSchedulingPlaneResolver` | computed default scheduling resolution from current task shell, shared config, route, and target inputs | task shell and shared config | live worker reachability, slots, reserve, lock, admission, trace evidence |
| `ResolvedWorkerSchedulingPolicy` | static resolved worker-side input view | resolver output | runtime truth, diagnostic evidence, mutable admission state |
| `WorkerTaskSelectorFactory` | candidate-source selector construction from resolved worker policy | `ResolvedWorkerSchedulingPolicy` | policy resolution, runtime admission, ranking, reserve/lock mutation |
| `RuleBasedTaskWorkerMatchingStrategy` | current matching assembly and selection call order | dispatch intent, resolved worker policy, candidate source, rule context, ranker, admission/resource mechanisms | persisted policy truth, public policy config, trace truth |
| `TaskAssignWorker` / `TaskWorkerAssignListener` | assignment signal queue, retry, allocation, matching handoff, and dispatch binding coordination | task events, runtime-ready signals, matching strategy, dispatch binder | worker choice outside the runtime-selection order, policy definition, trace truth |
| `RuntimeReadyDispatchPump` / `TaskDispatchWakeupBridge` / `EngineRuntimeKernel` | redispatch entry wiring and wakeup fanout | runtime-ready task source, worker availability callbacks, assignment worker | direct worker binding, direct candidate selection, bypass of `TaskWorkerAssignListener#onTaskAssign` |
| `WorkerMatchContext#getRuleContext()` | approved rule-readable eligibility context | static candidate/task facts approved for rules | diagnostic-only context, live admission/reserve/lock evidence unless explicitly approved |
| full `WorkerMatchContext` snapshot | diagnostic match evidence | runtime and candidate facts | rule policy contract, selection truth |
| `WorkerCandidateRanker` / `WorkerCandidateRankPolicy` | read-only ordering of rule-passed candidates and the current default ranking weights | candidate evidence and current ranking inputs | reserve, lock, admission, release, live evidence ownership |
| `WorkerAdmissionRuntime` / `WorkerWarmHintRuntime` | live admission and warm-hint runtime evidence | runtime worker state | resolved policy definition, rule DSL ownership, trace truth |
| `WorkerDispatchResourcePolicy` | dispatch resource use, reserve/lock/release semantics | selected candidate and dispatch context | policy variant selection, candidate-source definition, trace truth |
| `AssignmentRecordService` / `TraceEventLogger` | assignment and trace evidence | selection result and rejection/diagnostic facts | worker-selection truth, replayable policy state |

Owner review findings:

- `ResolvedWorkerSchedulingPolicy` is an input owner, not a selector owner. It
  may narrow the candidate universe, but it must not answer which live worker
  is currently admissible.
- `RuntimeWorkerSelection` owns the worker-choice result, but the current
  implementation can remain distributed across matching, ranking, admission,
  resource policy, and trace mechanisms while proof is being built.
- Rule evaluation owns declarative eligibility only. Dispatch gates,
  reachability, lock checks, reserve, and admission remain mechanism concerns.
- Ranking is read-only. Any mutation of reserve, lock, release, or admission in
  ranking code is an owner violation.
- Ranking must be split into two concepts:
  - ranking input, such as live load, availability, and warm evidence, is
    runtime mechanism evidence owned by `RuntimeWorkerSelection`,
  - ranking policy, such as comparator or weight selection, is static strategy
    input and may only move into `ResolvedWorkerSchedulingPolicy` after RS-5
    proves a real variant and caller-visible cost.
- Assignment records and trace are evidence. They can explain the decision but
  cannot drive or replay it as truth.
- `RuntimeWorkerSelection` is not proven as sole owner until every production
  path that can bind work to a worker is enumerated and shown to flow through
  the RS-3 order.
- A future `RuntimeWorkerSelectionService` is only valid if RS-0 through RS-3
  prove a real boundary that is currently hidden by the call chain. A pass-
  through rename is not a valid outcome.

Owner review proof surfaces:

- residue scans for forbidden imports and revived call sites,
- perturbation tests that separate resolved static inputs from runtime evidence,
- order-contract tests for prefilter, rule evaluation, ranking, reserve, lock,
  admission, and release,
- bounded trace proof showing explainability without truth inversion.

## Strategy And Mechanism Separation

Strategy owns defaults and caller-visible constraints:

- which WorkerGroup universe a task may use,
- route and target narrowing inputs,
- future static ranking policy, such as comparator or weight selection, only
  after RS-5 proves a real variant and caller-visible cost,
- future policy variant selection only after there is a proven caller and cost.

Mechanism owns runtime execution:

- worker presence and reachability,
- slots, load, draining, reservation, and lock state,
- live ranking inputs, ranking execution, admission, dispatch resource usage,
  and release,
- trace/audit evidence for why a worker was accepted or rejected.

Initial support may remain one default strategy. The architecture should be
extensible because these owners are cleanly separated, not because a catalog,
DSL, or plug-in framework exists early.

## Convergence Discipline

This roadmap must be executed as convergence work, not as additive layering.

Do not start by adding an internal `RuntimeWorkerSelectionService`, bridge,
adapter, fallback, or compatibility path. A new name that forwards to the old
path does not prove ownership; it creates another place for truth to settle.

Execution order:

1. Inventory:
   - enumerate every production binding entry,
   - classify truth owners and consumers,
   - identify helper, bridge, fallback, compatibility, and duplicate-compute
     residue.
2. Converge:
   - move each production caller onto the single approved owner path,
   - replace raw task reads with resolved views where the fact has migrated,
   - keep behavior green after each slice,
   - do not run old and new paths in parallel as two live tracks.
3. Remove residue:
   - delete superseded helpers when no external contract requires them,
   - restrict unavoidable helpers to test-only or documented non-owner use,
   - keep source residue scans that detect old-path revival without treating
     them as runtime proof,
   - update docs so target, current truth, and residue are not mixed.

At roadmap start, `WorkerTaskSelectorFactory#fromTask` was an existing
convenience path to classify during RS-0. The implemented repair removed it
from production instead of preserving a compatibility bridge.

`TaskDispatchWakeupBridge` is pre-classified as an existing engine-owned wakeup
fanout, not worker-selection truth. RS-0 should still verify that it does not
bind work directly or select candidates, but its `Bridge` suffix alone is not
residue.

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
12. No production path binds work to a worker without flowing through the RS-3
    runtime-selection order.
13. No reserve, lock, or admission acquisition lacks a matching release on every
    failure exit before durable dispatch ownership is established.
14. No temporary internal bridge, adapter, fallback, compatibility alias, or
    parallel path is introduced to preserve a superseded owner.

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
- The current proof names the clean matching path, but it does not yet prove
  that every binding entry path flows into that path.
- Reserve/lock release is tested in some failure paths, but acquire/release
  pairing is not yet a named architecture invariant across all failure exits.
- Ranking policy and ranking evidence are not yet separated as explicit future
  strategy-vs-mechanism concepts.
- Convenience or helper paths that compute selection facts, such as the removed
  `WorkerTaskSelectorFactory#fromTask`, need residue classification so they do
  not become alternate production truth.
- Trace explains several scheduling facts, but trace coverage should stay a
  bounded proof surface rather than an invitation to add broad observability.
- Future policy-product work could start too early if the roadmap does not keep
  catalog/binding gates closed until runtime selection proof is complete.

## RS-0 Runtime Selection Inventory

Goal: classify every current worker-choice fact by owner before changing code.

Output:

- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md`

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
- Inventory every production entry that can cause work to bind to a worker,
  including:
  - direct session task-ready and dispatch-signal listeners,
  - assignment retry and delayed requeue inside `TaskAssignWorker`,
  - runtime-ready pump redispatch,
  - startup/runtime recovery redispatch,
  - worker-availability wakeup via `TaskDispatchWakeupBridge`,
  - lease-expiry or attempt-close paths that make work dispatchable again,
  - target-worker dispatch paths.
- For each binding entry, prove whether it flows through:
  - `TaskAssignWorker#submit`,
  - `TaskWorkerAssignListener#onTaskAssign`,
  - `TaskWorkerMatchingStrategy#matchWorkers`,
  - `TaskDispatchBinder#bindDispatches`.
- Inventory helper, convenience, and compatibility-like paths that can compute
  or imply worker-selection facts, including `WorkerTaskSelectorFactory#fromTask`.
- Treat `TaskDispatchWakeupBridge` as an approved current wakeup fanout unless
  inventory finds direct worker binding or candidate selection.
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

- The current-state inventory exists at
  `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md`.
- The inventory distinguishes static resolved inputs from live runtime evidence.
- The inventory distinguishes binding entries from worker-selection mechanisms.
- Every production worker-binding entry is listed with its flow into the RS-3
  order or recorded as a blocker.
- Every helper, convenience, bridge, fallback, or compatibility-like path is
  classified as the single owner path, test-only helper, removable residue, or
  blocker.
- No field is classified as both policy truth and runtime truth.
- `RuntimeWorkerSelection` is described as an owner boundary, not as an already
  implemented facade.
- The inventory preserves or updates the owner review table when code reality
  disagrees with this roadmap draft.

Suggested checks:

```powershell
rg -n "ResolvedWorkerSchedulingPolicy|WorkerTaskSelectorFactory|RuleBasedTaskWorkerMatchingStrategy|WorkerMatchContext|WorkerCandidateRanker|WorkerAdmissionRuntime|WorkerWarmHintRuntime|WorkerDispatchResourcePolicy|AssignmentRecordService" xa-mass-engine xa-mass-worker-runtime --glob '!**/target/**'
rg -n "TaskAssignWorker|TaskWorkerAssignListener|TaskDispatchWakeupBridge|RuntimeReadyDispatchPump|LeaseExpireWatchdog|TaskDispatchBinder|bindDispatches|submit\\(|onTaskAssign|expireLeasedWork" xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "fromTask\\(|Compatibility|Legacy|Fallback|Facade|Deprecated|compatibility|legacy|fallback|deprecated" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "RuntimeWorkerSelection" README.md AGENTS.md doc xa-mass-engine roadmap --glob '!**/target/**'
```

## RS-1 Resolved Policy Purity Guards

Goal: prevent live runtime evidence from leaking into resolved worker policy.

Scope:

- Add architecture guards proving:
  - `ResolvedWorkerSchedulingPolicy` does not expose live evidence fields,
  - any future ranking policy field is static strategy input and does not carry
    live load, warm, reserve, lock, admission, or reachability evidence,
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
- `ResolvedWorkerSchedulingPolicy` may later carry static ranking policy only
  after RS-5; it must never carry ranking evidence.
- `WorkerTaskSelectorFactory#fromTask` is removed rather than retained as a
  production bridge. Residue scans should remain empty.
- Integrated scheduling tests cover migrated worker-policy facts by changing
  actual candidate and binding outcomes.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskSchedulingGateAndTargetingTest" test
rg -n "WorkerTaskSelectorFactory\\.fromTask\\(" xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "WorkerAdmissionRuntime|WorkerWarmHintRuntime|WorkerDispatchResourcePolicy" xa-mass-engine/src/main/java/com/xa/mass/engine/runtime/scheduling xa-mass-engine/src/main/java/com/xa/mass/engine/strategy/DefaultSchedulingPlaneResolver.java --glob '!**/target/**'
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
- Add the symmetric static-input perturbation proof:
  - worker group selector changes while runtime evidence stays constant, and
    the candidate pool changes through `ResolvedWorkerSchedulingPolicy`.
- Keep each test explicit about what remains static policy input and what is
  runtime evidence.
- Do not encode trace fields as runtime truth.

Acceptance:

- Runtime perturbation proof covers each listed runtime-evidence scenario:
  reachability, capacity/admission, draining or dispatch-disabled state,
  lock/reservation conflict, load/warm-hint ranking, and target worker not
  currently admissible.
- Static-input perturbation proof covers worker group selector changes with
  constant runtime evidence.
- Target narrowing does not bypass admission.
- Ranking order is read-only and admission/reserve is still owned by runtime
  mechanisms.
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
  -> prefilter dispatch gate / reachability / lock
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
- Prove every production worker-binding entry inventoried in RS-0 flows into
  this order before `TaskDispatchBinder#bindDispatches`.
- Add guards that prevent:
  - ranking before eligibility,
  - admission mutation inside rule evaluation,
  - lock/reserve mutation inside ranking,
  - assignment binding before admission succeeds,
  - direct calls to `TaskDispatchBinder#bindDispatches` outside the approved
    assignment listener path,
  - reserve, lock, or admission acquire without a matching release on every
    failure exit before durable dispatch ownership is established,
  - trace-driven selection decisions.
- Keep this as a behavior contract, not a request for a new orchestration
  facade.

Acceptance:

- The order contract is documented in the engine baseline or roadmap proof
  section.
- Every production binding entry listed in RS-0 reaches binding only after the
  RS-3 order.
- Tests cover reserve/lock release on failed assignment or rejection paths.
- Guards or focused tests prove acquire/release pairing on every failure exit
  after reserve/lock/admission acquisition.
- Architecture guards prevent obvious admission/ranking/rule boundary regressions.
- No new service exists only to rename the existing call chain.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=RuleBasedTaskWorkerMatchingStrategyTest,TaskWorkerAssignListenerTest,TaskSchedulingContentionTest,EngineSchedulingCoreArchitectureGuardTest" test
rg -n "rank\\(|reserve|lock|admission|release" xa-mass-engine/src/main/java/com/xa/mass/engine xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "bindDispatches\\(|new SimpleTaskDispatchBinder|TaskDispatchBinder" xa-mass-engine/src/main/java --glob '!**/target/**'
```

## RS-4 Residue, Trace, And Diagnostic Proof

Goal: remove residue after convergence and prove runtime selection is
explainable without making trace the source of truth.

Scope:

- Remove or guard production helpers, bridges, aliases, duplicate-compute paths,
  stale docs, and tests that preserve superseded worker-selection vocabulary.
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

- No production bridge, fallback, compatibility helper, or duplicate-compute
  path remains as a second worker-selection truth.
- Any helper that remains is explicitly test-only, non-owner, or the single
  approved production path.
- Trace/assignment evidence is classified as evidence, not worker-selection
  truth.
- Any trace gap is recorded as a bounded list with a named owner.
- No broad trace enrichment is added without a concrete proof case.
- No runtime decision reads back from trace or assignment diagnostics.

Suggested proof:

```powershell
rg -n "workerGroup|targetWorker|route|admission|reject|release|rank|candidate" xa-mass-engine/src/main/java/com/xa/mass/engine xa-mass-trace --glob '!**/target/**'
rg -n "fromTask\\(|Compatibility|Legacy|Fallback|Facade|Deprecated|compatibility|legacy|fallback|deprecated" xa-mass-engine/src/main/java xa-mass-engine/src/test/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
rg -n "TraceEventLogger|AssignmentRecordService|assignment diagnostic|match" xa-mass-engine/src/test xa-mass-testing --glob '!**/target/**'
```

## RS-5 Successor Gate

Goal: decide whether policy-product work is justified after runtime selection
is proven.

Do not start a successor policy roadmap unless all of these are true:

- `RuntimeWorkerSelection` owner boundary is proven by guards and tests.
- Every production path that can bind work to a worker is inventoried and shown
  to flow through the RS-3 order.
- Resolved worker policy is still pure static input.
- Runtime evidence perturbation proof exists.
- Selection order contract is documented and guarded.
- Reserve/lock/admission acquire-release pairing is proven across failure exits.
- Ranking policy is separated from ranking evidence:
  - comparator or weight selection is static strategy input,
  - live load, availability, reserve, lock, warm hints, and admission remain
    runtime mechanism evidence.
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
| Runtime fact inventory | Current code and owner classification | `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md` |
| Binding entry completeness | Integrated scheduling and redispatch tests | `mvn -pl xa-mass-engine "-Dtest=TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskDelayedAvailabilitySchedulingTest,TaskRedispatchCompetitionTest" test` |
| Resolved policy purity | Integrated group selector behavior; resolver construction tests are support regression only | `mvn -pl xa-mass-engine "-Dtest=TaskSchedulingGateAndTargetingTest" test` |
| Rule context boundary | `WorkerMatchContext` and rule evaluator tests | `mvn -pl xa-mass-engine "-Dtest=WorkerMatchContextTest,RuleConfigTest,QLExpressRuleEvaluatorTest,EngineSchedulingCoreArchitectureGuardTest" test` |
| Runtime perturbation | Matching, ranking, contention, targeting tests | `mvn -pl xa-mass-engine "-Dtest=RuleBasedTaskWorkerMatchingStrategyTest,DefaultWorkerCandidateRankerTest,TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest" test` |
| Selection order and release pairing | Integrated scheduling plus binder failure regression | `mvn -pl xa-mass-engine "-Dtest=TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest,SimpleTaskDispatchBinderTest" test` |
| Residue cleanup | Source scan sanity only | `rg -n "WorkerTaskSelectorFactory\\.fromTask\\(" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'` |
| Trace evidence | Trace proof gaps and focused trace tests | `rg -n "TraceEventLogger|AssignmentRecordService|admission|release|candidate" xa-mass-engine xa-mass-trace xa-mass-testing --glob '!**/target/**'` |

## Risks

- Runtime evidence leaks into resolved worker policy because it is convenient
  for a future policy variant.
- A side-door binding entry assigns work to a worker without the RS-3 selection
  order.
- Reserve, lock, or admission is acquired and then leaked on a binding failure
  path.
- Rule context expands into a hidden runtime admission API.
- Ranking starts mutating reserve, lock, or admission state.
- Ranking policy gets trapped inside mechanism code, so the first real worker
  policy variant has to break the boundary instead of using static resolved
  policy input.
- Trace or assignment diagnostics are treated as replayable selection truth.
- A broad `RuntimeWorkerSelectionService` hides current owners without changing
  the real boundary.
- A temporary internal bridge or compatibility helper survives convergence and
  becomes a second production truth.
- Catalog/binding work starts before runtime selection is proven.

## Exit Criteria

1. Runtime worker-selection facts are inventoried and owner-classified.
2. Every production worker-binding entry is inventoried and proven to flow
   through the RS-3 selection order.
3. `ResolvedWorkerSchedulingPolicy` remains a static input view with guards
   against live runtime evidence.
4. Rule-readable context remains narrower than diagnostic context.
5. Runtime evidence perturbation tests prove worker choice can change while the
   resolved worker policy remains unchanged.
6. Static worker group selector perturbation proves resolved policy can change
   the candidate pool while runtime evidence stays constant.
7. Target worker narrowing does not bypass admission, reserve, lock, or release
   rules.
8. Reserve, lock, and admission acquisition has matching release proof for every
   failure exit before durable dispatch ownership.
9. Ranking policy is separated from ranking evidence.
10. Ranking, rule evaluation, and admission mutations have separate proof
   surfaces.
11. Selection order is documented and guarded without adding a pass-through
   facade.
12. No internal bridge, fallback, compatibility helper, or duplicate-compute
   path remains as second production truth.
13. Trace explains current worker selection decisions without becoming truth.
14. No catalog, binding, SDK/server policy config, or second worker policy
   variant is added by this roadmap.
15. A successor policy-product roadmap remains blocked until RS-5 evidence
    exists.
