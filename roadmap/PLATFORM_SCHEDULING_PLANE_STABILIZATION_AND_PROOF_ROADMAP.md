# Platform Scheduling Plane Stabilization And Proof Roadmap

Status: active stabilization / proof roadmap.

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

## Known Proof Gaps

The first Scheduling Plane implementation introduced resolved views and moved
some consumers onto them. This roadmap strengthens the proof before the
boundary can be treated as a feature-expansion base.

Known gaps:

- `behavior-neutral` is currently supported by updated tests, not by a
  before/after characterization or golden diff.
- Worker-side selector construction consumes `ResolvedWorkerSchedulingPolicy`;
  architecture guards must prevent migrated consumers from bypassing resolved
  views and reading raw task fields directly.
- `TaskDispatchIntent` and `WorkerTaskSelector` construction must stay behind
  the single Scheduling Plane resolution boundary in production dispatch paths.
- `workloadClass` is the clearest current strategy fact and must be used as the
  first duplicate-truth / bypass-guard sample.
- `ProjectSchedulingBinding` remains a target name, but the future binding
  subject is not yet decided: project may be governance/quota scope while
  workload remains the actual scheduling axis.

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
- `AGENTS.md`, `doc/AGENT_BASELINE.md`, `doc/TASK_LIFECYCLE_BASELINE.md`,
  and `xa-mass-engine/README.md` are consistent on the current Scheduling
  Plane shape.

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
- Add behavior-neutral proof that is independent of the implementation commit:
  - either freeze pre-Scheduling Plane matching behavior as a characterization
    / golden baseline and diff current behavior against it,
  - or audit the Scheduling Plane landing commit assertion-by-assertion for
    accept/reject result, rule count, rejection owner, and rejection reason
    changes.
- Strengthen consumer tests where current behavior depends on resolved policy:
  - assignment allocation,
  - `TaskAssignWorker`,
  - rule-backed matching,
  - candidate acquisition limits.
- Add perturbation proof for migrated facts:
  - perturb a resolved task scheduling field and prove task-side consumer
    behavior changes,
  - perturb a resolved worker scheduling field and prove worker-side consumer
    behavior changes,
  - prove the same consumer is not silently reading the old raw task field.
- Keep readonly duplicate resolution collapsed in the matching path so a
  dispatch pass computes `TaskDispatchIntent` / `SchedulingPlaneResolution`
  once and threads the resolved inputs down to selector construction and
  candidate acquisition.
- Avoid introducing a new orchestration owner.

Acceptance:

- Resolver output matches existing behavior for all current inputs, proven by
  characterization/golden diff or explicit landing-commit assertion audit.
- Execution owners consume resolved views but still own execution decisions.
- Migrated facts have at least one perturbation test showing that the resolved
  view, not the old raw field read, drives the consumer decision.
- A dispatch pass does not recompute the same `TaskDispatchIntent` through
  parallel helper paths.
- No test has to mock a persisted catalog or project binding.
- No production engine dependency on a policy storage/control-plane module.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=DefaultSchedulingPlaneResolverTest,DefaultAssignmentAllocationPolicyTest,TaskAssignWorkerTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest" test
rg -n "TaskDispatchIntent\\.fromTask\\(|ResolvedTaskSchedulingPolicy\\.from\\(|ResolvedWorkerSchedulingPolicy\\.from\\(|WorkerTaskSelectorFactory\\.fromTask\\(" xa-mass-engine/src/main/java --glob '!**/target/**'
rg -n "getExecutionSpec\\(\\)\\.getWorkloadClass\\(|getWorkloadClass\\(" xa-mass-engine/src/main/java --glob '!**/target/**'
```

Behavior-neutral assertion audit:

- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_BEHAVIOR_NEUTRAL_AUDIT.md`

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
- `EngineSchedulingCoreArchitectureGuardTest` is the primary proof for the rule
  context boundary and covers variable passing / helper extraction / evaluator
  overloads, not only one literal inline call shape.
- Any remaining diagnostic-only keys are named and justified.
- No diagnostic-only key in `WorkerMatchContext#getContext()` that is absent
  from `WorkerMatchContext#getRuleContext()` can silently alter rule evaluation
  outcomes.

Suggested proof:

```powershell
mvn -pl xa-mass-engine "-Dtest=WorkerMatchContextTest,RuleConfigTest,QLExpressRuleEvaluatorTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest" test
rg -n "evaluate\\([^,]+,\\s*[^)]*getContext\\(|getContext\\(\\).*rule|rule.*getContext\\(" xa-mass-engine/src/main/java xa-mass-engine/src/test/java --glob '!**/target/**'
```

The `rg` check is only a sanity scan. The architecture guard must carry the
actual boundary proof.

## SPSP-3 Policy Truth Duplicate Guard

Goal: turn the PSP-4 "no duplicate policy truth" rule into an auditable proof
surface.

Scope:

This stage adds automated guard coverage for writable duplicate truth and
records readonly resolution sites as named residue if they were not fixed in
SPSP-1. SPSP-1 owns fixing duplicated resolution in the matching path; SPSP-3
owns preventing that duplication from becoming an untracked boundary leak.

- Add architecture guard coverage for source-detectable duplicate truth:
  - no production `SchedulingPolicyCatalog` implementation,
  - no production `ProjectSchedulingBinding` implementation,
  - no engine production dependency on storage/control-plane policy modules,
  - no writable scheduling policy state in SDK/server config,
  - no runtime owner stores the same policy fact already represented by task
    shell, shared config, or resolved views.
- Use `workloadClass` as the first concrete policy-truth sample:
  - writable truth remains the task shell execution spec,
  - resolved task scheduling view carries it to task-side consumers,
  - assignment allocation, worker budget, lane selection, and retry policy must
    not each rediscover it through separate raw task reads once migrated.
- Track readonly duplicate resolution separately from writable duplicate truth.
  Recomputing the same intent in parallel helper paths is not a second writable
  source of truth, but it is still a Scheduling Plane boundary failure.
- Maintain a policy-truth ownership table in the active owner doc if code
  introduces or renames any Scheduling Plane field.
- Document non-automatable review checks explicitly instead of pretending the
  guard can prove them all.

Acceptance:

- Architecture guard fails when a second writable owner is introduced for a
  current policy fact.
- Architecture guard or targeted tests fail when a migrated fact such as
  `workloadClass` is consumed by bypassing the resolved task scheduling view.
- Readonly duplicate resolution sites are either removed or recorded as named
  residue with owner and follow-up.
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
rg -n "getExecutionSpec\\(\\)\\.getWorkloadClass\\(|getWorkloadClass\\(" xa-mass-engine/src/main/java --glob '!**/target/**'
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
- Any identified trace gaps are recorded as a bounded list with a named owner;
  no unbounded trace enrichment is added.
- Proof registry entries point to one primary proof and avoid duplicate
  happy-path tests.

Suggested proof surfaces:

```powershell
mvn -pl xa-mass-engine "-Dtest=EngineSchedulingCoreSuite,TaskSchedulingGateAndTargetingTest,TaskContractSchedulingBehaviorTest,TaskSchedulingContentionTest" test
rg -n "dispatchIntent|taskSchedulingPolicy|workerSchedulingPolicy|candidateSource|rule.*detail|admission" xa-mass-engine xa-mass-trace doc roadmap --glob '!**/target/**'
```

Evidence checkpoint:

- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_TRACE_PROOF_GAPS.md`
  records current trace evidence and bounded trace gaps. It is not approval for
  open-ended trace enrichment.

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

Evidence checkpoint:

- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_PUBLIC_VOCABULARY_CHECKPOINT.md`
  classifies the current ambiguous vocabulary and preserves the no-new-public-
  fields boundary.

## SPSP-6 Successor Decision Gate

Goal: prevent the next roadmap from starting before the proof gap is closed.

The next feature roadmap may add policy configuration only if this roadmap
produces all of the following evidence:

- at least two concrete policy variants with different caller-visible cost,
- the caller that selects or configures them,
- the owner that stores the selection,
- the runtime owner that consumes the resolved view,
- the future binding subject decision:
  - whether `ProjectSchedulingBinding` means project-governance binding,
    project-quota scope, workload scheduling binding, or a two-level
    project/workload binding,
  - why that subject matches the proven scheduling axis instead of preserving a
    misleading target name,
- a proof that current computed defaults are insufficient,
- a rollback or migration plan for existing task behavior.

Without that evidence, the successor should remain a cleanup/proof roadmap, not
a policy product roadmap.

Current gate result: not satisfied. Current code and docs prove computed-default
resolved views and selected consumers, but they do not prove two concrete policy
variants with caller-visible cost, a persisted selection owner, or a settled
binding subject. A successor roadmap may continue cleanup/proof work; it must
not introduce policy product configuration from this roadmap alone.

## Verification Matrix

| Area | Minimum proof |
| --- | --- |
| Behavior neutrality | `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_BEHAVIOR_NEUTRAL_AUDIT.md` or a future characterization/golden diff |
| Resolver behavior | `DefaultSchedulingPlaneResolverTest` |
| Resolved-view perturbation | targeted consumer perturbation tests for migrated facts |
| Single resolution boundary | source guard for duplicate `TaskDispatchIntent` / resolution computation |
| Task-side consumers | `DefaultAssignmentAllocationPolicyTest`, `TaskAssignWorkerTest` |
| Worker-side consumers | `RuleBasedTaskWorkerMatchingStrategyTest`, `WorkerMatchContextTest` |
| Rule boundary | `WorkerMatchContextTest`, `RuleConfigTest`, `QLExpressRuleEvaluatorTest` |
| Architecture guard | `EngineSchedulingCoreArchitectureGuardTest` |
| Scheduling E2E behavior | `EngineSchedulingCoreSuite`, task scheduling behavior tests |
| Trace proof boundary | `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_TRACE_PROOF_GAPS.md` plus `doc/TRACE_CONTRACT.md` analyzer fields |
| Public vocabulary | `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_PUBLIC_VOCABULARY_CHECKPOINT.md` |
| Successor gate | SPSP-6 current gate result remains not satisfied |
| Docs and residue | `rg` checks plus active doc review |

## Risks

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| Stabilization becomes feature expansion | Adds policy truth before proof exists | Hard rule: no catalog, binding, SDK config, or second variant |
| Resolved views become execution owners | Blurs input vs runtime ownership | Keep consumer relationship explicit in docs and tests |
| Resolved views become decoration | Consumers can keep reading raw task fields while tests still pass | perturbation proof plus bypass guards for migrated facts |
| Readonly resolution duplication spreads | Strategy facts remain scattered even without writable duplicate truth | resolve once per dispatch pass and thread inputs through |
| Trace becomes source of truth | Reverses runtime ownership | Treat trace as derived evidence only |
| Rule context re-expands | Reintroduces catch-all matching policy | Guard rule-readable keys and live evidence |
| Duplicate policy truth survives | Storage, SDK config, resolved views, and runtime state can diverge | Add architecture guard plus review checklist |
| Binding target name misleads implementation | `ProjectSchedulingBinding` can preserve a project-first name even if workload is the proven scheduling axis | require binding subject decision before successor feature work |
| Docs overclaim implementation | Agents start coding target state as current truth | SPSP-0 active-doc alignment before new feature work |

## Exit Criteria

This roadmap is complete when:

1. Active docs describe the implemented Scheduling Plane contracts accurately.
2. Archived inventory links are not used as active proof.
3. Resolver and consumer tests prove computed-default behavior by differential
   or audited characterization evidence, not only by updated tests passing.
4. Perturbation tests prove migrated facts are carried by resolved views.
5. Matching and selector construction do not recompute dispatch intent through
   parallel helper paths.
6. Rule context guards prevent unclassified live evidence from being
   rule-readable.
7. Duplicate policy truth has an automated guard and a review checklist.
8. `workloadClass` has an explicit truth/consumer proof and no migrated
   consumer bypasses the resolved task scheduling view.
9. Trace/E2E proof explains scheduling and matching outcomes without becoming
   truth.
10. Any successor feature roadmap starts from a written decision, not from
   implicit pressure to keep expanding the abstraction.
