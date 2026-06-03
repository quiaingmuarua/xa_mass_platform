# Platform Scheduling Plane Stabilization And Proof Roadmap

Status: proposed stabilization / proof roadmap.

Predecessors:

- `roadmap/PLATFORM_SCHEDULING_PLANE_ROADMAP.md`
- `roadmap/PLATFORM_SCHEDULING_PLANE_DECISION.md`
- `doc/archive/xa-mass-engine/2026-06-03_PLATFORM_SCHEDULING_PLANE_INVENTORY.md`

## Purpose

The Scheduling Plane concept has started landing in code, but it should not
move directly into another feature-expansion roadmap.

This roadmap stabilizes the current computed-default implementation, proves the
owner split, reviews old truth that the architecture upgrade may have
superseded, and closes guard gaps before any persisted policy catalog, project
binding, SDK configuration surface, or second policy variant is added.

## Current Baseline

Current implemented shape:

```text
Task shell / TaskExecutionSpec / shared config
  -> TaskDispatchIntent
  -> DefaultSchedulingPlaneResolver
     -> ResolvedTaskSchedulingPolicy
        -> assignment allocation / TaskAssignWorker / runtime scheduling gates
     -> ResolvedWorkerSchedulingPolicy
        -> worker candidate source / rule eligibility / ranking / reserve / admission
```

Current code has the value-contract direction in place:

- `TaskDispatchIntent` is a named task-level dispatch intent.
- `ResolvedTaskSchedulingPolicy` is a resolved task-side input view.
- `ResolvedWorkerSchedulingPolicy` is a resolved worker-side input view.
- `DefaultSchedulingPlaneResolver` computes current defaults from existing task
  fields, shared config, route keys, and runtime profile.
- Rule evaluation consumes `WorkerMatchContext#getRuleContext()`, while the
  full context remains diagnostic evidence.

Current code does not yet have these target owners:

- no persisted `SchedulingPolicyCatalog`
- no persisted `ProjectSchedulingBinding`
- no SDK/server scheduling policy configuration surface
- no second concrete task scheduling policy variant
- no second concrete worker scheduling policy variant
- no stateful fairness, quota, deadline, or policy runtime ledger

Interpretation: the new architecture is partially landed as engine-facing
contracts and behavior-neutral consumers. It is not yet landed as a
user-configurable policy system, and this roadmap intentionally does not try to
make it one.

## Strategy And Mechanism Separation

The Scheduling Plane direction is strategy/mechanism separation, not early
policy-product expansion.

Strategy owns the resolved scheduling intent and limits:

- task-side dispatch lane, priority, worker budget, and gate inputs,
- worker-side group, route, target, and rule-set inputs,
- caller/default selection once there is a proven caller and more than one
  policy cost.

Mechanism owns runtime execution:

- queue, lease, retry, expiry, and result convergence,
- candidate enumeration, ranking, reserve, lock, and admission,
- live worker reachability, load, and runtime state.

Initial support may remain one default strategy. That default still needs a
visible resolved view so strategy facts are not buried inside assignment,
matching, and runtime mechanisms. Extensibility comes from a clean owner
boundary and proof path for adding a second strategy later, not from building a
catalog, DSL, or plug-in framework before the cost exists.

## Truth Review

Architecture upgrades change truth ownership. Stabilization must review old
truth, not only add new names.

Every Scheduling Plane stabilization slice should classify changed facts as one
of:

- current runtime truth,
- current control-plane/storage truth,
- current public contract,
- current trace/audit evidence,
- derived resolved view,
- diagnostic-only residue,
- superseded truth to remove or archive.

If a field, test, doc, or guard still treats superseded truth as current truth,
the slice must either update it or record a concrete follow-up. This is part of
the proof work, not optional documentation cleanup.

## Hard Rules

1. No new persisted Scheduling Plane policy table.
2. No new public SDK or server policy configuration surface.
3. No new project/workload binding owner.
4. No second policy variant without a caller, cost, and proof surface.
5. No runtime-stateful fairness, quota, deadline, or rate policy.
6. No item payload access in Scheduling Plane resolution.
7. No `eventCode` worker selection semantics.
8. No rule-readable live worker evidence outside the approved rule context.
9. No trace field becomes policy truth.
10. No superseded truth remains live without owner classification.
11. No broad `scheduling` package or service that owns policy, matching,
    runtime state, and storage together.

## Boundary Clarification

The resolved views are inputs to execution owners, not peer execution owners.

```text
TaskDispatchIntent
  -> ResolvedTaskSchedulingPolicy
       consumed by:
       - assignment allocation
       - TaskAssignWorker
       - runtime scheduling gates

TaskDispatchIntent
  -> ResolvedWorkerSchedulingPolicy
       consumed by:
       - worker candidate source
       - rule eligibility
       - ranking
       - reserve
       - admission

Runtime worker selection
  owns:
  - live reachability
  - load and slots
  - reserve and locks
  - admission result
```

This roadmap should keep that consumption relationship visible in docs, tests,
and guards.

## Out Of Scope

- implementing `SchedulingPolicyCatalog`
- implementing `ProjectSchedulingBinding`
- adding database-backed policy truth
- adding SDK/server policy selection APIs
- adding policy authoring UI
- adding rule DSL features
- changing result convergence, transport delivery, or task lifecycle semantics
- extracting a new module only to wrap existing engine owners

## SPSP-0 Current-State Proof Refresh

Goal: establish exactly what landed and what remains target-only.

Scope:

- Verify all active Scheduling Plane references against current code.
- Update active docs that still describe landed concepts as target-only.
- Keep future-only concepts explicitly marked as target owner boundaries.
- Classify old truth that may have been superseded by the resolved views,
  including stale docs, tests, guard expectations, and trace vocabulary.
- Do not move archived inventory facts back into active docs unless they remain
  current implementation truth.

Acceptance:

- Active docs distinguish:
  - implemented value contracts,
  - implemented consumers,
  - target-only persisted/configurable policy owners.
- No active roadmap links point to the old inventory location.
- No active doc implies persisted catalog/binding already exists.
- Superseded truth is either removed, reclassified as diagnostic/derived, or
  recorded as a named residue item.
- `doc/AGENT_BASELINE.md`, `doc/TASK_LIFECYCLE_BASELINE.md`, and
  `xa-mass-engine/README.md` are consistent on the current Scheduling Plane
  shape.

Suggested residue checks:

```powershell
rg -n "PLATFORM_SCHEDULING_PLANE_INVENTORY" roadmap doc README.md AGENTS.md xa-mass-engine/README.md --glob '!**/target/**'
rg -n "current concept implemented through task fields/shared config/selectors rather than a single named object" doc xa-mass-engine roadmap --glob '!**/target/**'
rg -n "SchedulingPolicyCatalog|ProjectSchedulingBinding" doc roadmap xa-mass-engine --glob '!**/target/**'
```

## SPSP-1 Resolver And Consumer Proof

Goal: prove the computed-default resolver is behavior-neutral and is consumed
only as an input view.

Scope:

- Strengthen resolver tests for:
  - workload class and dispatch lane,
  - desired worker count and minimum start gate,
  - worker group selector,
  - route code and route attributes,
  - target worker and target attributes,
  - empty/default shared config.
- Strengthen consumer tests where current behavior depends on resolved policy:
  - assignment allocation,
  - `TaskAssignWorker`,
  - rule-backed matching,
  - candidate acquisition limits.
- Avoid introducing a new orchestration owner.

Acceptance:

- Resolver output matches existing behavior for all current inputs.
- Execution owners consume resolved views but still own execution decisions.
- No test has to mock a persisted catalog or project binding.
- No production engine dependency on a policy storage/control-plane module.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=DefaultSchedulingPlaneResolverTest,DefaultAssignmentAllocationPolicyTest,TaskAssignWorkerTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest" test
```

## SPSP-2 Rule Context Stability

Goal: prevent `WorkerMatchContext` from becoming the future policy contract
again.

Scope:

- Treat `WorkerMatchContext#getRuleContext()` as the only rule-readable map.
- Treat `WorkerMatchContext#getContext()` and context snapshots as diagnostic
  evidence only.
- Add or strengthen guards for rule-readable keys:
  - every rule-readable key has one owner classification,
  - no live admission, reserve, lock, slot, or runtime load evidence is
    rule-readable unless explicitly approved,
  - diagnostic aliases do not become policy inputs.
- Keep ranking and admission evidence outside declarative policy.

Acceptance:

- Rule evaluator production calls use `getRuleContext()`, not the full context.
- Tests prove unapproved live evidence is absent from rule context.
- Any remaining diagnostic-only keys are named and justified.
- No PSP-5 residue item remains that can silently change matching behavior
  after policy code exists.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=WorkerMatchContextTest,RuleConfigTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest" test
rg -n "evaluate\\([^,]+,\\s*[^)]*getContext\\(|getContext\\(\\).*rule|rule.*getContext\\(" xa-mass-engine/src/main/java xa-mass-engine/src/test/java --glob '!**/target/**'
```

## SPSP-3 Policy Truth Duplicate Guard

Goal: turn the PSP-4 "no duplicate policy truth" rule into an auditable proof
surface.

Scope:

- Add architecture guard coverage for source-detectable duplicate truth:
  - no production `SchedulingPolicyCatalog` implementation,
  - no production `ProjectSchedulingBinding` implementation,
  - no engine production dependency on storage/control-plane policy modules,
  - no writable scheduling policy state in SDK/server config,
  - no runtime owner stores the same policy fact already represented by task
    shell, shared config, or resolved views.
- Maintain a policy-truth ownership table in the active owner doc if code
  introduces or renames any Scheduling Plane field.
- Document non-automatable review checks explicitly instead of pretending the
  guard can prove them all.

Acceptance:

- Architecture guard fails when a second writable owner is introduced for a
  current policy fact.
- Source searches show no catalog/binding production implementation.
- Any intentionally duplicated read model is labeled as derived evidence, not
  truth.
- PSP-4's duplicate-truth acceptance is covered by guard plus review checklist,
  not by self-discipline alone.

Suggested guard searches:

```powershell
rg -n "class .*SchedulingPolicyCatalog|interface .*SchedulingPolicyCatalog|record .*SchedulingPolicyCatalog|class .*ProjectSchedulingBinding|interface .*ProjectSchedulingBinding|record .*ProjectSchedulingBinding" xa-mass-engine xa-mass-server sdk platform_infra --glob '!**/target/**'
rg -n "SchedulingPolicyCatalog|ProjectSchedulingBinding" xa-mass-engine/src/main/java xa-mass-server/src/main/java sdk --glob '!**/target/**'
rg -n "policy.*storage|storage.*policy|scheduling.*catalog|project.*binding" xa-mass-engine/src/main/java xa-mass-server/src/main/java platform_infra --glob '!**/target/**'
```

## SPSP-4 Trace And E2E Proof Bundle

Goal: make Scheduling Plane behavior observable as evidence without making
trace the source of truth.

Scope:

- Identify existing trace fields that prove:
  - dispatch intent,
  - task scheduling resolved view,
  - worker scheduling resolved view,
  - candidate source and narrowing reason,
  - rule pass/fail details,
  - runtime admission rejection.
- Add bounded diagnostic evidence only if current trace cannot explain a
  Scheduling Plane decision.
- Keep trace fields derived from runtime decisions.

Acceptance:

- A scheduling E2E proof can explain why work entered or did not enter
  dispatch competition.
- A matching proof can explain why a candidate was accepted, rejected, or
  admitted.
- Trace does not introduce any new writable policy field.
- Proof registry entries point to one primary proof and avoid duplicate
  happy-path tests.

Suggested proof surfaces:

```powershell
mvn -pl xa-mass-engine "-Dtest=EngineSchedulingCoreSuite,TaskSchedulingGateAndTargetingTest,TaskContractSchedulingBehaviorTest,TaskSchedulingContentionTest" test
rg -n "dispatchIntent|taskSchedulingPolicy|workerSchedulingPolicy|candidateSource|rule.*detail|admission" xa-mass-engine xa-mass-trace doc roadmap --glob '!**/target/**'
```

## SPSP-5 Public Vocabulary Checkpoint

Goal: decide whether any current Scheduling Plane vocabulary should become
public, remain internal, or be retired before a future policy feature roadmap.

Scope:

- Review only current ambiguous vocabulary:
  - `profile`
  - `foreground`
  - `maxRuntimeSeconds`
  - `targetWorkerId`
  - `adapterNodeId`
  - route attributes
  - target attributes
- Classify each as:
  - current public contract,
  - internal resolved-view input,
  - diagnostic evidence,
  - future policy candidate,
  - deprecated residue.
- Do not add new public fields in this roadmap.

Acceptance:

- Public SDK and server docs do not expose target-only policy terms as current
  caller behavior.
- Engine docs do not hide current public scheduling inputs.
- Any future public vocabulary needs a successor decision with caller and proof.

## SPSP-6 Successor Decision Gate

Goal: prevent the next roadmap from starting before the proof gap is closed.

The next feature roadmap may add policy configuration only if this roadmap
produces all of the following evidence:

- at least two concrete policy variants with different caller-visible cost,
- the caller that selects or configures them,
- the owner that stores the selection,
- the runtime owner that consumes the resolved view,
- a proof that current computed defaults are insufficient,
- a rollback or migration plan for existing task behavior.

Without that evidence, the successor should remain a cleanup/proof roadmap, not
a policy product roadmap.

## Verification Matrix

| Area | Minimum proof |
| --- | --- |
| Resolver behavior | `DefaultSchedulingPlaneResolverTest` |
| Task-side consumers | `DefaultAssignmentAllocationPolicyTest`, `TaskAssignWorkerTest` |
| Worker-side consumers | `RuleBasedTaskWorkerMatchingStrategyTest`, `WorkerMatchContextTest` |
| Rule boundary | `WorkerMatchContextTest`, `RuleConfigTest`, `QLExpressRuleEvaluatorTest` |
| Architecture guard | `EngineSchedulingCoreArchitectureGuardTest` |
| Scheduling E2E behavior | `EngineSchedulingCoreSuite`, task scheduling behavior tests |
| Docs and residue | `rg` checks plus active doc review |

## Risks

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| Stabilization becomes feature expansion | Adds policy truth before proof exists | Hard rule: no catalog, binding, SDK config, or second variant |
| Resolved views become execution owners | Blurs input vs runtime ownership | Keep consumer relationship explicit in docs and tests |
| Trace becomes source of truth | Reverses runtime ownership | Treat trace as derived evidence only |
| Rule context re-expands | Reintroduces catch-all matching policy | Guard rule-readable keys and live evidence |
| Duplicate policy truth survives | Storage, SDK config, resolved views, and runtime state can diverge | Add architecture guard plus review checklist |
| Docs overclaim implementation | Agents start coding target state as current truth | SPSP-0 active-doc alignment before new feature work |

## Exit Criteria

This roadmap is complete when:

1. Active docs describe the implemented Scheduling Plane contracts accurately.
2. Archived inventory links are not used as active proof.
3. Resolver and consumer tests prove computed-default behavior.
4. Rule context guards prevent unclassified live evidence from being
   rule-readable.
5. Duplicate policy truth has an automated guard and a review checklist.
6. Trace/E2E proof explains scheduling and matching outcomes without becoming
   truth.
7. Any successor feature roadmap starts from a written decision, not from
   implicit pressure to keep expanding the abstraction.
