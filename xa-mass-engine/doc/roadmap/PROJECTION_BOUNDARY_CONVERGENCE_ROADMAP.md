# Projection Boundary Convergence Roadmap

Status: active direction document. PBC-0 inventory has landed. PBC-1 is
covered by existing mainline proof guards. PBC-2 has landed.

This is the current `xa-mass-engine` convergence roadmap.

PBC-0 inventory record:
[`PROJECTION_BOUNDARY_CONVERGENCE_INVENTORY.md`](PROJECTION_BOUNDARY_CONVERGENCE_INVENTORY.md).

This roadmap removes compatibility task message/attempt projection from the
engine kernel boundary. It is intentionally separate from the completed
rule-boundary convergence record in
[`../../../doc/archive/xa-mass-engine/RULE_BOUNDARY_CONVERGENCE_ROADMAP.md`](../../../doc/archive/xa-mass-engine/RULE_BOUNDARY_CONVERGENCE_ROADMAP.md)
because projection cleanup affects runtime result convergence, console review
read models, tests, and storage read-model assembly.

Do not create a parallel storage-dependency convergence track for this work.
The next meaningful engine dependency reduction is the projection boundary
work described here. Broadly removing `mass-storage-api` from engine is not
the current goal because task shell storage and rule-definition reads remain
legitimate engine-facing contracts until separate owner work changes them.

The current code has already moved scheduling and terminal truth toward runtime
state. PBC-2 removed the default engine projection-audit scan, but engine still
writes `TaskDetailStore` message/attempt projection residue and server review
surfaces still read that read model.

## Current Code Observations

- `TaskCompatibilityProjectionStore` is an engine-internal owner over
  `TaskDetailStore` message/attempt projections.
- `TaskWorkProjectionState` converts engine residue enums to
  `mass-storage-api` projection enums.
- PBC-2 removed `TaskProjectionStateAuditor`; default engine diagnostics no
  longer scan compatibility projection residue.
- `TaskManager` constructs `TaskCompatibilityProjectionStore`.
- `TaskResultService` writes best-effort work and attempt projection residue
  after runtime result convergence.
- `TaskManager.addRuntimeIngressItems(...)` writes ingress-accepted
  projection residue through `TaskCompatibilityProjectionStore`.
- Server review surfaces still read `TaskDetailStore` projections, especially
  `InternalTaskReviewController`.
- `InternalTaskReviewController` uses projection rows for review summary,
  seed preview/export, and result preview/export. The required fields include
  message id, input/event code, payload ref, status/final reason, retry counts,
  latest attempt id/worker/batch, timing fields, error summary, output, and
  aggregate message stats.
- Server proof lanes still include projection-specific suites and support
  classes such as `ServerProjectionResidueSuite`,
  `ServerProjectionAuditSuite`, and `ProjectionSampleE2eTest`.
- Many engine tests still assert behavior through `TaskDetailStore`
  message/attempt projections instead of runtime/result/trace truth.
- `@CompatibilityProjectionOnly` already marks the current compatibility
  boundary on projection owners, methods, and test helpers. PBC guards should
  use this annotation as one input instead of inventing a wholly separate
  marker.
- `TaskWorkProjectionState` is not only a storage conversion holder today.
  Its inner enums are also used as engine runtime event field types by
  `TaskWorkLogicallyFinalEvent`, `TaskWorkAttemptClosedEvent`,
  `SimpleTaskDispatchBinder`, and `TraceEventLogger`.
- Engine production code still depends on `mass-storage-api` for three
  different reasons that must not be collapsed:
  - `TaskShellStore` / `TaskShellLifecycleQuery`: current task shell and
    lifecycle lookup contract; not a PBC removal target.
  - `TaskDetailStore` and `com.xa.mass.storage.api.projection.*`: projection
    residue; PBC removal target.
  - `RuleStorage` / `com.xa.mass.storage.rule.*`: rule-definition and
    matching-policy data; record as a follow-up rule-domain boundary question,
    not part of PBC implementation.
- Worker declaration storage has already moved to `xa-mass-worker-runtime`.
  Do not use PBC to reopen that boundary.

## Boundary Decision

Runtime truth belongs to runtime state and result records.

Task shell/control-plane truth belongs to task storage.

Task message/attempt projection is a read model, not engine kernel truth.

Implications:

- scheduling, result convergence, terminal policy, and runtime validation must
  not depend on `TaskDetailStore` message/attempt projection reads
- engine production code should not import
  `com.xa.mass.storage.api.projection.*`
- console review/read-model needs should be assembled outside engine kernel or
  explicitly marked as external read-model assembly
- the PBC-3 read-model replacement path must be decided during PBC-0, not
  discovered while implementing PBC-3
- scan-heavy projection auditing should not be part of default engine
  diagnostics
- projection cleanup should not be hidden under rule-boundary work
- `TaskShellStore` may remain an engine dependency while it is the task
  shell/control-plane contract
- `RuleStorage` and storage rule DTO usage should be inventoried but not
  changed in PBC unless the change is required to decouple projection residue
- engine-native work/attempt lifecycle event types must not keep projection
  naming after projection persistence is removed
- server projection proof lanes must move with the server/read-model decision
  before engine projection writes are removed

## Non-Goals

1. No rewrite of task shell persistence.
2. No removal of `TaskShellStore` as engine control-plane storage.
3. No console product redesign beyond moving it away from engine-owned
   compatibility projection truth.
4. No change to result correctness semantics except removing projection as a
   source of runtime truth.
5. No compatibility alias for removed projection paths after in-repo callers
   move.
6. No broad attempt to remove every `mass-storage-api` dependency from
   `xa-mass-engine`.
7. No rule-domain migration. `RuleStorage`, `RuleDefinition`, `RuleType`, and
   evaluator registry ownership should be handled by a later rule-domain
   roadmap if PBC inventory confirms a real boundary problem.
8. No worker declaration movement. That boundary is owned by
   `xa-mass-worker-runtime`.

## Slice PBC-0: Inventory Projection Truth Usage

Goal: identify every projection read/write and classify whether it is runtime
truth, console read model, test residue, or an unrelated engine dependency.

Scope:

1. List production and test callers of:
   - `TaskCompatibilityProjectionStore`
   - `TaskWorkProjectionState`
   - `TaskProjectionStateAuditor`
   - `TaskDetailStore.TaskMessageProjection`
   - `TaskDetailStore.TaskMessageAttemptProjection`
   - `com.xa.mass.storage.api.projection.*`
2. Classify every `xa-mass-engine` production dependency on `mass-storage-api`
   into exactly one bucket:
   - keep for now: `TaskShellStore` / `TaskShellLifecycleQuery`
   - PBC target: `TaskDetailStore` projection read/write residue
   - rule follow-up: `RuleStorage` / `com.xa.mass.storage.rule.*`
   - unexpected dependency requiring owner review before implementation
3. Classify each projection caller as:
   - runtime correctness
   - result convergence residue write
   - console/review read model
   - explicit offline audit
   - test-only assertion helper
4. Identify any path where projection data affects scheduling, terminal
   policy, result convergence, or dispatch eligibility.
5. Identify server/API callers that need a replacement read model before
   projection writes can be removed.
6. Decide the PBC-3 replacement path before implementation starts:
   - path A: server/read-model-side writer keeps a `TaskDetailStore`-like
     review projection without engine runtime writes
   - path B: server review reads from trace/archive/runtime-derived read model
     and stops depending on `TaskDetailStore` projection rows
   - any third path requires owner review before PBC-1 begins
7. Build a field coverage matrix for `InternalTaskReviewController`:
   - current response/export field
   - current `TaskDetailStore` source field
   - proposed replacement source
   - whether existing `ExecutionEventType` coverage is sufficient
   - required new trace/read-model event or assembler, if any
8. Record that rule-domain storage dependencies are not being changed by this
   roadmap unless they directly block projection removal.
9. Specifically classify `TaskWorkProjectionState` inner enum consumers:
   - storage conversion
   - projection audit
   - runtime event payload
   - trace/logging payload
   - test-only helper
10. Classify server projection proof assets:
    - `ServerProjectionResidueSuite`
    - `ServerProjectionAuditSuite`
    - `ProjectionSampleE2eTest`
    - every E2E class extending `ProjectionSampleE2eTest`
11. Produce a small inventory doc beside this roadmap.

Acceptance:

1. Every current projection caller has one classification.
2. Any runtime-correctness dependency on projection data is explicitly named.
3. Console/read-model callers are separated from engine runtime callers.
4. Every current engine production dependency on `mass-storage-api` is
   classified as keep-for-now, PBC target, rule follow-up, or unexpected.
5. Every `TaskWorkProjectionState` enum consumer is classified before PBC-4
   attempts to delete projection conversion logic.
6. PBC-3 has one selected read-model replacement path with explicit tradeoffs.
7. `InternalTaskReviewController` field coverage is mapped against
   trace/runtime/read-model sources before PBC-3 implementation starts.
8. Server projection proof assets have an owner decision: migrate, rename,
   keep as explicit projection-read-model proof, or delete.
9. No behavior changes in this slice.

## Slice PBC-1: Replace Runtime Assertions With Runtime Truth

Goal: stop proving engine correctness through compatibility projection rows.

Scope:

1. Update engine tests that assert scheduling/result correctness through
   `TaskDetailStore` message/attempt projections.
2. Prefer assertions against:
   - `TaskWorkRuntime`
   - `TaskResultRuntime`
   - task shell state
   - trace/audit evidence
   - dispatch/result service outcomes
3. Keep dedicated read-model tests only where the read model itself is the
   subject under test.
4. Do not rewrite task shell or rule-storage tests in this slice unless they
   currently rely on projection rows as proof of engine runtime correctness.

Acceptance:

1. Engine runtime correctness tests do not require `TaskDetailStore`
   message/attempt projection reads.
2. Projection-specific assertions are isolated to projection/read-model tests.
3. Scheduling and result convergence coverage remains behaviorally equivalent.

## Slice PBC-2: Remove Projection Audit From Engine Diagnostics

Goal: remove scan-heavy projection auditing from the default engine kernel.

Scope:

1. Delete `TaskProjectionStateAuditor` if it only audits compatibility
   projection residue.
2. If an offline audit is still useful, move it to a read-model/admin
   diagnostic surface outside engine kernel.
3. Keep `TaskStateValidator` focused on runtime task state.
4. Do not replace projection audit with another scan-heavy engine diagnostic.

Acceptance:

1. Default engine diagnostics do not scan `TaskDetailStore` message/attempt
   projections.
2. `TaskManager.auditTaskProjectionState(...)` is removed or moved out of the
   engine kernel boundary.
3. Runtime state validation remains covered by tests.

## Slice PBC-3: Move Or Replace Console Review Read Model

Goal: keep console review useful without making engine own projection truth.

Scope:

1. Replace server review reads from `TaskDetailStore` message/attempt
   projections with the read-model path selected in PBC-0.
2. If PBC-0 selected server/read-model-side projection assembly, make the
   writer/assembler server-side or read-model-side, not engine-side runtime
   logic.
3. If PBC-0 selected trace/archive/runtime-derived review, implement the
   required reader or assembler before removing the old controller dependency.
4. Do not start this slice until the `InternalTaskReviewController` field
   coverage matrix has no unresolved required field.
5. Update `InternalTaskReviewController` and related tests to the chosen read
   model.
6. Migrate or rename server projection proof lanes according to the PBC-0
   owner decision:
   - `ServerProjectionResidueSuite`
   - `ServerProjectionAuditSuite`
   - `ProjectionSampleE2eTest`
   - E2E classes extending `ProjectionSampleE2eTest`
7. Keep the replacement read model deterministic enough for local console demo
   and review surfaces; do not make external network calls part of this proof.

Acceptance:

1. Console review no longer requires engine runtime code to write projection
   residue.
2. Server read-model ownership is documented outside engine kernel ownership.
3. Review/export tests remain deterministic and do not rely on hidden engine
   compatibility writes.
4. Server projection suites/support classes either target the new read model
   explicitly or have been renamed/deleted so they no longer imply engine-owned
   projection truth.
5. Existing trace event coverage gaps found in PBC-0 are closed or documented
   as explicit follow-up blockers before PBC-4 starts.

## Slice PBC-4: Remove Engine Projection Writes

Goal: stop engine runtime paths from writing compatibility projection rows.

Scope:

1. Remove `TaskCompatibilityProjectionStore` from `TaskManager` assembly.
2. Remove ingress projection writes from runtime item append.
3. Remove best-effort work/attempt projection writes from `TaskResultService`.
4. Split engine-native work/attempt lifecycle enums out of
   `TaskWorkProjectionState` before deleting storage projection conversion
   logic. Runtime event records such as `TaskWorkLogicallyFinalEvent` and
   `TaskWorkAttemptClosedEvent` must depend on engine-native lifecycle types,
   not projection-named containers.
5. Delete `TaskWorkProjectionState` conversions to storage projection enums
   once no engine runtime path writes projection rows and no runtime event
   payload depends on `TaskWorkProjectionState`.
6. Remove `TaskDetailStore` constructor requirements that exist only for
   compatibility projection writes.

Acceptance:

1. Engine production code no longer imports
   `com.xa.mass.storage.api.projection.*`.
2. Engine runtime paths do not write `TaskDetailStore` message/attempt
   projections.
3. Any remaining `TaskDetailStore` dependency is outside engine kernel runtime
   logic and is documented as read-model assembly or explicit audit.
4. Result convergence, retry, expiry, and terminal policy tests still pass.
5. Remaining engine `mass-storage-api` imports are limited to explicitly kept
   task shell/control-plane or rule-domain follow-up contracts.
6. Runtime work/finality event records no longer use
   `TaskWorkProjectionState` as their field type.

## Slice PBC-5: Guard And Proof

Goal: prevent projection residue from returning to engine runtime truth.

Scope:

1. Add or update architecture guards:
   - engine runtime packages must not import storage projection packages
   - scheduling/result/terminal code must not read `TaskDetailStore`
     message/attempt projections
   - projection read-model assembly, if any remains, is outside engine kernel
   - remaining engine `mass-storage-api` imports must be on the allowlist from
     PBC-0 classification
   - engine kernel/runtime mainline must not depend on classes or methods
     annotated `@CompatibilityProjectionOnly`, except inside explicitly
     named projection/read-model lanes that survived PBC-0 classification
2. Prefer extending `EngineSchedulingCoreArchitectureGuardTest` for these
   guards because it is the current engine architecture guard carrier. Create
   a separate `EngineStorageDependencyGuardTest` only if PBC-0 inventory shows
   the allowlist logic would make the existing guard materially harder to
   maintain.
3. Add proof that console review/read-model still works through its new owner.
4. Add proof that runtime correctness tests pass without projection reads.

Acceptance:

1. Guard fails if engine production runtime code imports
   `com.xa.mass.storage.api.projection.*`.
2. Guard fails if scheduling/result/terminal code reads message/attempt
   projection rows.
3. Console review/read-model proof passes through the new owner.
4. Engine scheduling/result suites pass without projection assertion helpers.
5. Guard or inventory check fails when a new unclassified engine
   `mass-storage-api` dependency appears.
6. Guard fails if a new engine kernel/runtime path consumes
   `@CompatibilityProjectionOnly` code outside an explicitly allowed
   projection/read-model lane.

## Implementation Order

Recommended order:

```text
PBC-0 -> PBC-1 -> PBC-2 -> PBC-3 -> PBC-4 -> PBC-5
```

Do not start by deleting projection writes. First move tests and console reads
off projection truth, then remove engine writes.

PBC-4 is blocked until PBC-3 has landed. Deleting engine projection writes
before the server review replacement is in place will silently break
`/internal/v1/review/tasks/**` seed/result preview and export behavior.

PBC is the engine convergence mainline. After PBC lands, reassess whether
remaining engine `mass-storage-api` dependencies are real owner-boundary
problems:

- `TaskShellStore` should stay until a task-shell owner boundary changes.
- `RuleStorage` / storage rule DTOs should move only through a rule-domain
  roadmap, not as cleanup fallout from projection work.
- `TaskDetailStore` should no longer be required by engine kernel runtime code.

## Verification Candidates

Initial commands to keep in the roadmap proof set:

```powershell
mvn -pl xa-mass-engine -am '-Dtest=TaskResultRuntimeConvergenceTest,SimpleTaskDispatchBinderTest,EngineSchedulingCoreArchitectureGuardTest,EngineProofOwnershipGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-server -am '-Dtest=TaskApiControllerTest,ControlConsoleRoutingIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The exact test list should be corrected in PBC-0 after the current projection
caller inventory is complete.
