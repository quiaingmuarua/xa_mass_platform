# Platform Scheduling Plane Policy Proof Hardening Roadmap

Status: partially implemented; PP-0 proof hygiene is complete, one PP-2
`batchSize` outcome proof was added, and successor policy product gate remains
closed.

Related records:

- `roadmap/PLATFORM_SCHEDULING_PLANE_ROADMAP.md`
- `roadmap/PLATFORM_SCHEDULING_PLANE_DECISION.md`
- `roadmap/PLATFORM_SCHEDULING_PLANE_STABILIZATION_AND_PROOF_ROADMAP.md`
- `xa-mass-engine/doc/baseline/SCHEDULING_CORRECTNESS_MATRIX.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_RUNTIME_SELECTION_BOUNDARY.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_BEHAVIOR_NEUTRAL_AUDIT.md`
- `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_TRACE_PROOF_GAPS.md`

## Purpose

This roadmap makes Scheduling Plane policy proof harder.

The current code has computed-default resolved views:

```text
Task shell / TaskExecutionSpec / shared config
  -> TaskDispatchIntent
  -> DefaultSchedulingPlaneResolver
     -> ResolvedTaskSchedulingPolicy
     -> ResolvedWorkerSchedulingPolicy
```

That does not mean a policy product is proven.

This roadmap proves whether current computed-default policy facts actually
carry runtime behavior. It must not add low-value field-copy tests, mock-only
happy paths, source-shape tests, or coverage-padding regressions. CI time should
buy proof of runtime truth, lifecycle behavior, competition, failure handling,
and explainable scheduling outcomes.

## Current Execution State

This roadmap has not been fully executed. The completed work is proof hygiene
plus one concrete task-side perturbation proof. Inventory, classification, and
source residue scans are not proof completion.

| Stage | Current state | What is actually proven |
| --- | --- | --- |
| PP-0 proof inventory and suite triage | complete | Low-value allocation/budget object-shape tests were deleted; mainline suite membership no longer depends on those tests. |
| PP-1 worker-side outcome proof | partial / mostly pre-existing | Existing scheduling tests cover worker group, target worker, target attributes, and routing outcomes, but this roadmap has not yet added a systematic policy perturbation matrix for every worker-side fact. |
| PP-2 task-side outcome proof | partial | `batchSize` gained a concrete runtime outcome proof. Existing tests cover `minRequiredWorkerCount`, `workloadClass`, lane, and retry-related behavior, but carrier-vs-consumer proof remains mixed for profile-backed fields. |
| PP-3 single path and bypass proof | inventory only | Binding entries are classified. They are not fully proven against policy bypass until entry-specific perturbation tests drive direct assignment, retry, runtime-ready pump, lease-expiry redispatch, worker-availability wakeup, and target dispatch through the same policy-sensitive outcome. |
| PP-4 explainability proof | inventory only | Existing assignment records, runtime counters, and trace fields appear sufficient for current proven outcomes, but no new trace-observed proof bundle was added. |
| PP-5 registry and suite hygiene | partial | Registry and matrices were aligned with proof hygiene. Full proof registry promotion should wait until PP-1 through PP-4 are genuinely complete. |
| PP-6 successor gate | closed | No catalog, binding, SDK policy config, policy id, or second policy variant was introduced. |

Completed changes:

- PP-0 inventory and suite triage are recorded in
  `xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_POLICY_PROOF_INVENTORY.md`.
- Field-copy/object-shape allocation and budget tests were deleted instead of
  moved to another suite.
- `EngineSchedulingCoreSuite` no longer depends on those weak tests.
- `TaskSchedulingContentionTest#batchSizeChangesDispatchWorkerCountWithoutLosingReadyWork`
  proves that changing `batchSize` changes runtime binding shape while
  ready/inflight counters stay consistent.

Not yet completed:

- Worker-side policy perturbation matrix across all consumed facts.
- Entry-specific bypass proof for every binding entry.
- Trace-observed proof bundle for policy outcomes.
- Any successor policy product work.

## Decisions For Next Implementation Slice

These decisions are fixed for the next implementation slice. They prevent the
next pass from becoming more documentation or low-value test coverage.

1. **Next proof axis: binding-entry bypass proof.**
   The next implementation slice is PP-3A. It must prove that binding entries
   do not bypass policy-sensitive worker selection before any broader
   worker-side perturbation matrix or policy product work.
2. **Entry proof harness boundary.**
   Tests may extend `TaskSchedulingTestHarness` to drive runtime-ready pump,
   wakeup, and lease-expiry entries with controlled policy perturbations. The
   extension must stay test-owned and must not introduce production bridge,
   facade, wrapper, or compatibility paths.
3. **Route attributes semantics: keep unproven.**
   `routeAttributes` is currently resolved but engine-local policy outcome is
   unproven. It remains explicitly `resolved-only/unproven`; do not implement
   route-attribute behavior or write placeholder tests in PP-3A.
4. **Profile-backed fields: classify, do not converge in PP-3A.**
   `batchPolicy`, `leaseProfile`, `backpressureClass`, and parts of retry
   behavior still flow through profile resolvers rather than a clean resolved
   policy consumer. PP-3A should prove binding-entry bypass around current
   policy-sensitive selection, not retarget these consumers. They remain mixed
   carrier/mechanism-profile proof until a separate consumer-convergence slice.
5. **Completion threshold.**
   A stage may be marked complete only when it adds or identifies a concrete
   outcome proof for that stage. Inventory-only work must stay partial.

## PP-3A Next Executable Slice: Binding-Entry Bypass Proof

Goal: prove that alternate entry points cannot bind work to a worker without
re-entering the same policy-sensitive runtime selection path.

This slice is intentionally narrower than the full roadmap. It must add real
runtime outcome tests, not more inventory. It may extend
`TaskSchedulingTestHarness` to expose test-only drivers for pump, wakeup, and
lease-expiry entries.

Scope:

- Extend `TaskSchedulingTestHarness` only as needed to drive current runtime
  entries:
  - direct assignment through `TaskWorkerAssignListener#onTaskAssign` as the
    control path, not as a PP-3A completion-count entry,
  - retry through `TaskAssignWorker` where deterministic,
  - worker-availability wakeup waiting-retry branch through
    `TaskDispatchWakeupBridge` -> `TaskAssignWorker#wakeWaitingRetries`,
  - runtime-ready pump scan or idle-admission wake branch through
    `RuntimeReadyDispatchPump`,
  - lease-expiry redispatch through runtime lease expiry surfaces,
  - target-worker dispatch as a policy-sensitive direct-path control.
- For each entry tested, use a policy-sensitive worker fact that already has a
  current consumer:
  - prefer `workerGroupIds` for pump and lease-expiry redispatch because it
    clearly changes candidate universe,
  - prefer `targetWorkerId` for retry/wakeup because it proves the entry does
    not drift to a backup worker,
  - use `routingCode` only when group/target cannot express the entry cleanly.
- Each test must hold runtime evidence equivalent except for the entry trigger
  being exercised. It must assert at least:
  - selected worker id or active lease worker id,
  - no assignment record or lock for the disallowed worker/group,
  - no disallowed worker `MSG_ASSIGN` record and no dispatch binding for the
    disallowed worker,
  - ready/inflight counters or task status,
  - assignment record or dispatch binding evidence explaining the outcome.
- Runtime-ready pump tests must be deterministic. Do not rely on unbounded
  sleeps. If the current pump API is timer-driven, extend the test harness with
  a bounded await or a test-only deterministic pump driver that exercises the
  same production owner path without adding production API surface.
- Lease-expiry redispatch tests must not rely on invalid mid-run policy
  mutation. Prefer a stable policy with changed runtime evidence after expiry,
  or explicitly prove that any task shell/shared-config update used by the test
  is a valid current truth mutation before treating it as policy perturbation.
- Do not use `routeAttributes` in PP-3A.
- Do not add source-shape tests as proof. Residue scans may remain as sanity
  checks after the runtime proof passes.

Acceptance:

- The direct assignment / target dispatch path is present only as a control.
  It does not count toward PP-3A completion.
- At least three non-direct binding entries have new or materially strengthened
  entry-specific runtime outcome tests:
  - retry or worker-availability waiting-retry wakeup,
  - runtime-ready pump scan or runtime-ready pump idle-admission wake,
  - lease-expiry redispatch.
- Each accepted test proves a policy-sensitive fact still controls binding
  after that entry is triggered.
- PP-3A is complete only when the three non-direct entry categories above are
  covered. Full PP-3 remains partial unless every listed binding entry is
  covered.
- No production compatibility bridge or duplicate policy path is added.
- `routeAttributes` remains `resolved-only/unproven`.

Suggested verification:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskRedispatchCompetitionTest,TaskDelayedAvailabilitySchedulingTest,TaskAssignWorkerTest,RuntimeReadyDispatchPumpTest" test
rg -n "WorkerTaskSelectorFactory\\.fromTask\\(|bindDispatches\\(" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
```

The `rg` command is residue sanity only. It should be used to inspect new direct
`bindDispatches` callers or removed selector bridges, not as proof that runtime
behavior is correct.

## Current Code Facts

Current implemented policy-facing value contracts:

- `ResolvedTaskSchedulingPolicy` carries:
  - `workloadClass`
  - `dispatchLane`
  - `dispatchPriority`
  - `batchPolicy`
  - `leaseProfile`
  - `backpressureClass`
  - `batchSize`
  - `defaultMaxRetryCount`
  - `minRequiredWorkerCount`
- `ResolvedWorkerSchedulingPolicy` carries:
  - `workerGroupIds`
  - `adapterNodeId`
  - `routingCode`
  - `routeAttributes`
  - `routeBucketKeys`
  - `targetWorkerId`
  - `targetWorkerAttributes`
- `TaskDispatchIntent` carries current task-side dispatch target constraints.
- `SchedulingPolicyCatalog` and `ProjectSchedulingBinding` remain target-only
  boundaries. They are not implemented policy products.

Current consumers:

- `DefaultAssignmentAllocationPolicy` consumes resolved task policy for
  `batchSize` and `minRequiredWorkerCount`.
- `DefaultWorkerBudgetPolicy` consumes resolved task policy for
  `workloadClass`.
- `TaskAssignWorker` consumes resolved task policy for dispatch lane and retry
  scheduling path.
- `RuleBasedTaskWorkerMatchingStrategy` consumes one scheduling resolution per
  match pass and uses the resolved worker policy to create candidate-source
  constraints.
- `WorkerDispatchResourcePolicy` and ranking/admission remain runtime
  mechanisms, not policy truth.

Current proof state:

- Worker-group selector proof is the strongest current policy-input proof
  because it changes real worker selection, assignment, lock, and runtime task
  state.
- Target worker, target attributes, routing, contention, min-worker gate, and
  release behavior have related integrated scheduling coverage. They still need
  entry-specific bypass proof before the roadmap can claim PP-3 completion.
- Resolver tests and simple policy/factory tests are support regressions only.
  They must not be listed as mainline policy proof.
- Source scans are residue sanity only. They must not be described as runtime
  behavior proof.

## Proof Bar

A policy fact is proven only when all are true:

1. **Truth owner is named.**
   The writable source is task shell, shared config, worker group capability, or
   another current owner. A resolved view is not writable truth.
2. **Consumer is named.**
   The runtime owner that consumes the fact is identified.
3. **Perturbation changes outcome.**
   With equivalent runtime evidence, changing only that policy fact changes at
   least one runtime-visible result:
   - candidate pool
   - selected worker
   - assignment result
   - worker lock/capacity/load
   - ready/inflight/final counters
   - task status
   - retry/redispatch behavior
   - dispatch binding count
4. **Bypass is excluded.**
   No migrated production consumer can silently read the old raw field or use a
   compatibility helper path to avoid the resolved view.
5. **Evidence explains the outcome.**
   Assignment records and/or trace evidence can explain why the outcome changed
   without becoming policy truth.

The following are not policy proof:

- field-copy assertions between records, factories, selectors, DTOs, or getters
- unit tests that only assert a resolver populated the same values it read
- mock-only happy paths that never exercise runtime truth
- source-shape tests that require a method call to appear in one file
- broad architecture scans presented as behavior proof
- tests that duplicate an already stronger integrated scenario without a new
  risk

## Do Not Start With

Do not start by adding more resolver, factory, DTO, or adapter tests.

Do not start by implementing `SchedulingPolicyCatalog`,
`ProjectSchedulingBinding`, policy ids, SDK policy config, or a second policy
variant.

Do not start by adding a generic `PolicyProofTest` that only checks object
shape. First classify facts and existing tests, then strengthen or delete tests
based on the proof bar above.

## Hard Rules

1. No field-copy unit test may be counted as policy proof.
2. No source scan or architecture guard may be counted as runtime behavior
   proof.
3. No test enters `EngineSchedulingCoreSuite` unless it proves runtime truth,
   lifecycle, contention, targeting, redispatch, failure compensation, or
   explainability.
4. Support regressions must stay outside mainline proof unless they cover a
   concrete failure path not covered by an integrated test.
5. Delete low-value tests instead of keeping them for coverage.
6. Do not introduce `SchedulingPolicyCatalog`, `ProjectSchedulingBinding`, SDK
   policy config, or policy ids in this roadmap.
7. Do not introduce a second policy variant unless the successor gate in this
   roadmap is satisfied.
8. Do not introduce compatibility bridges such as `fromTask` helpers after
   converging callers to resolved views.
9. Do not let item payload, `eventCode`, trace rows, or runtime live evidence
   become policy truth.
10. Do not claim a resolved field is proven when current code only carries it
    and no runtime consumer uses it.
11. If a policy fact cannot be proven because no consumer exists, record it as
    unproven/resolved-only rather than writing a fake test.

## PP-0 Proof Inventory And Test Triage

Goal: classify the proof surface first, then clean suite membership and delete
low-value tests before adding new proof.

Scope:

- Inventory every current policy-facing fact:
  - `ResolvedTaskSchedulingPolicy` fields,
  - `ResolvedWorkerSchedulingPolicy` fields,
  - `TaskDispatchIntent` fields that constrain policy-like behavior,
  - foreground/background dispatch resource semantics if they are used as
    scheduling inputs outside the resolved task policy.
- For each fact, classify:
  - writable truth owner,
  - resolved view,
  - runtime consumer,
  - current proof class,
  - outcome asserted,
  - evidence owner,
  - bypass/residue risk.
- Classify existing tests into:
  - hard proof,
  - support regression,
  - delete candidate,
  - misplaced suite membership.
- Triage known current `EngineSchedulingCoreSuite` weak-member candidates first:
  - `DefaultAssignmentAllocationPolicyTest`,
  - `DefaultWorkerBudgetPolicyTest`,
  - `WorkerMatchContextTest`.
- Delete tests that only prove field movement, object shape, or a mock-only
  happy path unless the inventory names a concrete support-regression risk that
  cannot be covered by an integrated proof.
- Remove low-value or support-only tests from `EngineSchedulingCoreSuite` during
  PP-0 triage. Do not wait for PP-5 to withdraw weak suite membership.
- Classify `WorkerMatchContextTest` separately from allocation/budget tests:
  it may remain as boundary/support proof only if it protects the approved
  rule-readable context surface rather than simple object-field movement.

Acceptance:

- A new or updated inventory table exists under `xa-mass-engine/doc/baseline/`
  with every current policy fact classified.
- Every selected mainline proof test has an outcome-based proof reason.
- Every support regression has a short reason explaining why an integrated test
  cannot cover that risk.
- `EngineSchedulingCoreSuite` membership is updated in the same PP-0 slice that
  classifies the tests; weak members are not left for PP-5 cleanup.
- `DefaultAssignmentAllocationPolicyTest` and `DefaultWorkerBudgetPolicyTest`
  are removed from `EngineSchedulingCoreSuite` unless each selected test has a
  specific runtime-outcome proof reason.
- `WorkerMatchContextTest` is either kept with an explicit rule-context boundary
  proof reason or removed from mainline proof.
- Every delete candidate has either been deleted or has a named follow-up.
- No `WorkerTaskSelectorFactoryTest`-style field-copy test remains as policy
  proof.

Suggested checks:

```powershell
rg -n "ResolvedTaskSchedulingPolicy|ResolvedWorkerSchedulingPolicy|TaskDispatchIntent|TaskRuntimeProfile|WorkerDispatchResourcePolicy|DefaultAssignmentAllocationPolicy|DefaultWorkerBudgetPolicy" xa-mass-engine/src/main/java xa-mass-engine/src/test/java --glob '!**/target/**'
rg -n "assertEquals\\([^\\n]*\\.workerGroupIds\\(\\)|assertEquals\\([^\\n]*\\.targetWorkerId\\(\\)|assertEquals\\([^\\n]*\\.batchSize\\(\\)|assertEquals\\([^\\n]*\\.workloadClass\\(\\)" xa-mass-engine/src/test/java --glob '!**/target/**'
```

## PP-1 Worker-Side Policy Outcome Proof

Goal: prove current worker-side resolved facts by changing runtime selection
outcomes, not by checking selector fields.

Scope:

- For each worker-side fact with a current consumer, provide or identify one
  outcome-based proof:
  - `workerGroupIds`: same workers and runtime evidence, only selected group
    changes; selected group binds, non-selected group has no assignment or lock.
  - `targetWorkerId`: target worker can bind only inside the selected group;
    backup eligible workers do not bind while target is unavailable or locked.
  - `targetWorkerAttributes`: changing required attributes changes the selected
    worker or rejection reason under otherwise equivalent runtime evidence.
  - `routingCode`: route perturbation changes candidate universe or rejection
    outcome without scanning all workers.
  - `routeBucketKeys`: prove only if bucket-key perturbation changes runtime
    assignment outcome; candidate-source or index mechanics alone are support
    regression.
  - `routeAttributes`: keep `resolved-only/unproven` until a separate owner
    decision chooses to implement/verify route-attribute scheduling behavior.
  - `adapterNodeId`: prove only if there is a real current consumer that changes
    candidate outcome; otherwise classify as resolved-only/unproven.
- Prefer strengthening existing integrated tests such as
  `TaskSchedulingGateAndTargetingTest`, `TaskWorkerEligibilityTest`, and
  `TaskSchedulingContentionTest` over adding new classes.
- Do not add `WorkerTaskSelectorFactory` field-copy tests.

Acceptance:

- Each current worker-side policy fact is either:
  - proven by a runtime outcome test, or
  - explicitly classified as resolved-only/unproven because no current consumer
    changes behavior.
- A resolved-only/unproven classification is a valid deliverable for fields
  with no runtime consumer. Do not add placeholder tests for those fields.
- The proof observes at least assignment records plus one runtime truth surface:
  active lease, worker lock/load, ready/inflight counters, or task status.
- No proof uses source-shape checks as the primary assertion.
- Residue scans only check that removed bypasses have not returned.

Suggested verification:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskSchedulingGateAndTargetingTest,TaskWorkerEligibilityTest,TaskSchedulingContentionTest" test
rg -n "WorkerTaskSelectorFactory\\.fromTask\\(" xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java --glob '!**/target/**'
```

## PP-2 Task-Side Policy Outcome Proof

Goal: prove current task-side resolved facts by changing allocation, lane,
budget, backpressure, retry, or lifecycle outcomes.

Scope:

- For each task-side resolved fact with a current consumer, provide or identify
  outcome-based proof:
  - `batchSize`: same ready work and workers, different batch size changes claim
    or dispatch binding count, while ready/inflight counters stay consistent.
  - `minRequiredWorkerCount`: changing the gate changes READY/RUNNING and lock
    release behavior; no half-dispatch.
  - `workloadClass`: changing class changes worker budget, ready-backpressure,
    retry cadence, or dispatch lane outcome. A budget-only unit test is support
    regression unless paired with runtime outcome.
  - `dispatchLane` / `dispatchPriority`: prove only through lane scheduling
    progress or ordering behavior. If proof requires thread timing, keep it
    deterministic and bounded.
  - `batchPolicy`, `leaseProfile`, `backpressureClass`,
    `defaultMaxRetryCount`: prove only where current runtime owners consume
    them. If they are only carried in the value object, mark them unproven
    instead of adding object-shape tests.
- Reuse existing high-value tests where possible:
  - `TaskSchedulingGateAndTargetingTest` for min-worker gate,
  - `TaskSchedulingContentionTest` for budget/contention/resource behavior,
  - `TaskKernelLifecycleTest` for ready-backpressure and intake/runtime truth,
  - `TaskRedispatchCompetitionTest` for retry/lease outcomes,
  - `TaskAssignWorkerTest` only when lane behavior is deterministic and cannot
    be expressed through a broader integrated scheduling test.

Acceptance:

- Each task-side policy fact is classified as hard-proven, support-regression,
  or unproven/resolved-only.
- At least the consumed fields `batchSize`, `minRequiredWorkerCount`, and
  `workloadClass` have outcome-based proof or a written blocker explaining why
  current code cannot produce a distinct runtime outcome.
- Tests assert runtime truth, not only policy object contents:
  ready/inflight counters, active leases, worker load/lock, task status, retry
  delay, assignment result, or dispatch binding count.
- Low-level policy tests remain outside mainline proof unless they cover a
  concrete failure path.

Suggested verification:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskKernelLifecycleTest,TaskRedispatchCompetitionTest,TaskAssignWorkerTest" test
```

## PP-3 Single Path And Bypass Proof

Goal: prove policy facts cannot be bypassed through an alternate binding path.

Scope:

- Inventory every production entry that can make work bind to a worker:
  - direct task assignment,
  - retry redispatch,
  - runtime-ready pump,
  - lease-expiry redispatch,
  - worker-availability wakeup,
  - target-worker dispatch.
- For each entry, prove it flows through:
  - scheduling eligibility,
  - candidate-source constraints,
  - runtime worker selection,
  - allocation,
  - dispatch binding.
- Entry proof only proves owner/path convergence. It does not repeat every
  policy-fact perturbation from PP-1 and PP-2 for every entry.
- Verify migrated policy facts are not read from raw task fields by consumers
  after the resolved view is established.
- Treat source scans as residue sanity; the proof must be the integrated entry
  behavior.

Acceptance:

- Binding-entry inventory is current and names the owner of each entry.
- No production path binds work directly to a worker without the same selection
  and allocation order.
- No compatibility bridge or helper reintroduces duplicate resolution.
- If a bypass cannot be ruled out by integrated proof, it is a blocker for
  claiming policy proof.

Suggested verification:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskSchedulingGateAndTargetingTest,TaskSchedulingContentionTest,TaskDelayedAvailabilitySchedulingTest,TaskRedispatchCompetitionTest,TaskRuntimeRecoveryPortTest" test
rg -n "bindDispatches|onTaskAssign|wakeWaitingRetries|expireLeasedWork|readyTaskIds|WorkerTaskSelectorFactory\\.fromTask\\(|TaskDispatchIntent\\.fromTask\\(" xa-mass-engine/src/main/java --glob '!**/target/**'
```

## PP-4 Explainability Proof

Goal: make policy outcomes explainable without turning trace into policy truth.

Scope:

- For every hard-proven policy fact, verify that at least one evidence surface
  can explain the changed outcome:
  - assignment records,
  - worker match accepted/rejected trace,
  - dispatch binding summary,
  - task status/runtime counters,
  - bounded trace analyzer fields where needed.
- Do not add broad trace enrichment.
- If an outcome cannot be explained from current evidence, record a bounded
  trace proof gap with owner and exact missing field.

Acceptance:

- Hard policy proofs state their evidence surface.
- `PLATFORM_SCHEDULING_PLANE_TRACE_PROOF_GAPS.md` is updated only for bounded
  gaps.
- No trace field is promoted to writable policy truth.
- No full rule context or live runtime evidence is added to resolved policy.

Suggested verification:

```powershell
rg -n "TraceEventLogger|AssignmentRecordService|WORKER_MATCH|DISPATCH_BINDING_SUMMARY|ASSIGNMENT_SUMMARY|workerGroup|targetWorker|workloadClass|batchSize|minRequiredWorker" xa-mass-engine xa-mass-trace --glob '!**/target/**'
```

## PP-5 Proof Registry And Suite Hygiene

Goal: make the proof model visible so future agents do not recreate low-value
tests.

Scope:

- Update `doc/PROOF_REGISTRY.md` only when a policy invariant is hard-proven.
- Update `doc/TESTING_INDEX.md` or `SCHEDULING_CORRECTNESS_MATRIX.md` if suite
  ownership changes.
- Verify PP-0 already removed mainline suite membership from tests that only
  support construction or mapper regression.
- Tag or move support tests only when the repo's existing proof model requires
  it; do not create another suite just to preserve weak tests.
- Document commands that separate:
  - primary proof,
  - support regression,
  - residue sanity.

Acceptance:

- `EngineSchedulingCoreSuite` contains only hard proof or essential boundary
  guard tests.
- Support regressions are not described as policy proof.
- `doc/PROOF_REGISTRY.md` has no policy row until the proof bar is actually
  met.
- Verification commands do not include deleted or downgraded field-copy tests.

Suggested verification:

```powershell
mvn -pl xa-mass-engine -Dtest=EngineSchedulingCoreSuite test
rg -n "WorkerTaskSelectorFactoryTest|field-copy|support regression|policy proof|ResolvedTaskSchedulingPolicy|ResolvedWorkerSchedulingPolicy" doc xa-mass-engine/doc xa-mass-engine/src/test/java --glob '!**/target/**'
```

## PP-6 Successor Policy Product Gate

Goal: block feature expansion until current computed-default policy proof is
hard enough.

This roadmap does not implement policy products. A successor roadmap may start
`SchedulingPolicyCatalog`, `ProjectSchedulingBinding`, SDK policy config, or a
second concrete policy variant only when all are true:

- PP-0 through PP-5 are complete.
- At least two current computed-default policy facts have hard outcome proof on
  both task-side and worker-side paths.
- All binding entries are proven to converge through the same selection and
  binding path.
- Current public vocabulary is classified as caller-facing, internal, or
  target-only.
- The future binding subject is decided:
  - project governance/quota scope,
  - workload scheduling axis,
  - or an explicit split.
- There are at least two concrete policy variants with different
  caller-visible cost.
- The caller that selects or configures the variant exists or is specified.
- The owner that stores the selection exists or is specified.
- Rollback/migration impact on existing tasks is specified.

If these conditions are not satisfied, the next roadmap must remain proof,
cleanup, or residue work, not policy product implementation.

## Verification Matrix

| Area | Primary proof | Support only | Not proof |
| --- | --- | --- | --- |
| Worker-side policy facts | integrated scheduling outcome tests | candidate index/enumerator regressions | selector field-copy tests |
| Task-side policy facts | runtime counters, locks, status, retry, lane, dispatch count | resolver/allocation construction tests | resolved record getter assertions |
| Binding entries | integrated redispatch/wakeup/assignment behavior | source residue scans | grep-only path claims |
| Explainability | assignment records and bounded trace evidence | trace gap inventory | trace as policy truth |
| Suite hygiene | `EngineSchedulingCoreSuite` hard proof membership | support regression commands | coverage-padding tests |
| Policy product gate | concrete variants, caller, owner, cost, proof | target direction docs | names alone |

## Risks

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| Tests optimize for coverage | CI gets slower while proof stays weak | delete/downgrade low-value tests in PP-0 |
| Resolved views become decoration | Consumers can bypass policy facts | perturbation plus bypass proof |
| Current carried fields are overclaimed | A field exists but has no runtime consumer | classify unproven/resolved-only |
| Source guards are mistaken for proof | Agents stop at grep instead of runtime behavior | proof bar separates residue from behavior |
| Policy product starts too early | Catalog/binding becomes speculative architecture | PP-6 successor gate |
| Trace becomes truth | Observability reverses ownership | PP-4 bounded explainability only |
| Task-side proof stays weak | Worker proof creates false confidence | PP-2 requires task-side outcome proof |

## Exit Criteria

This roadmap is complete when:

1. Every current policy-facing fact is classified by truth owner, consumer,
   proof class, evidence surface, and bypass risk.
2. Low-value field-copy, object-shape, and mock-only happy-path tests are
   deleted or kept out of mainline proof with a support-regression reason.
3. Worker-side consumed policy facts have outcome-based proof or explicit
   unproven classification.
4. Task-side consumed policy facts have outcome-based proof or explicit blocker.
5. All binding entries are inventoried and entry-specific tests prove they
   converge through the same runtime selection and binding path before policy
   proof is claimed.
6. Assignment/trace evidence can explain every hard-proven policy outcome.
7. `EngineSchedulingCoreSuite`, `SCHEDULING_CORRECTNESS_MATRIX.md`, and
   `doc/PROOF_REGISTRY.md` agree on which tests are primary proof.
8. Source scans are documented only as residue sanity checks.
9. No catalog, binding, SDK policy config, policy id, or second policy variant
   has been introduced by this roadmap.
10. The PP-6 successor gate is either satisfied with evidence or explicitly
    remains closed.
