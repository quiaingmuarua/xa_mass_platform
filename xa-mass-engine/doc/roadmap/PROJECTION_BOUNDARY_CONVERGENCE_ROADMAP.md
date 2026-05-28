# Projection Boundary Convergence Roadmap

Status: active direction document. No implementation slices have landed from
this roadmap yet.

This roadmap removes compatibility task message/attempt projection from the
engine kernel boundary. It is intentionally separate from
`RULE_BOUNDARY_CONVERGENCE_ROADMAP.md` because projection cleanup affects
runtime result convergence, console review read models, tests, and storage
read-model assembly.

The current code has already moved scheduling and terminal truth toward runtime
state, but engine still writes and sometimes scans `TaskDetailStore`
message/attempt projection residue.

## Current Code Observations

- `TaskCompatibilityProjectionStore` is an engine-internal owner over
  `TaskDetailStore` message/attempt projections.
- `TaskWorkProjectionState` converts engine residue enums to
  `mass-storage-api` projection enums.
- `TaskProjectionStateAuditor` scans compatibility projection residue and
  reports violations as projection-audit state validation.
- `TaskManager` constructs `TaskCompatibilityProjectionStore` and
  `TaskProjectionStateAuditor`.
- `TaskResultService` writes best-effort work and attempt projection residue
  after runtime result convergence.
- `TaskManager.appendRuntimeIngressItems(...)` writes ingress-accepted
  projection residue through `TaskCompatibilityProjectionStore`.
- Server review surfaces still read `TaskDetailStore` projections, especially
  `InternalTaskReviewController`.
- Many engine tests still assert behavior through `TaskDetailStore`
  message/attempt projections instead of runtime/result/trace truth.

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
- scan-heavy projection auditing should not be part of default engine
  diagnostics
- projection cleanup should not be hidden under rule-boundary work

## Non-Goals

1. No rewrite of task shell persistence.
2. No removal of `TaskStorage` as engine control-plane storage.
3. No console product redesign beyond moving it away from engine-owned
   compatibility projection truth.
4. No change to result correctness semantics except removing projection as a
   source of runtime truth.
5. No compatibility alias for removed projection paths after in-repo callers
   move.

## Slice PBC-0: Inventory Projection Truth Usage

Goal: identify every projection read/write and classify whether it is runtime
truth, console read model, or test residue.

Scope:

1. List production and test callers of:
   - `TaskCompatibilityProjectionStore`
   - `TaskWorkProjectionState`
   - `TaskProjectionStateAuditor`
   - `TaskDetailStore.TaskMessageProjection`
   - `TaskDetailStore.TaskMessageAttemptProjection`
   - `com.xa.mass.storage.api.projection.*`
2. Classify each caller as:
   - runtime correctness
   - result convergence residue write
   - console/review read model
   - explicit offline audit
   - test-only assertion helper
3. Identify any path where projection data affects scheduling, terminal
   policy, result convergence, or dispatch eligibility.
4. Identify server/API callers that need a replacement read model before
   projection writes can be removed.
5. Produce a small inventory doc beside this roadmap.

Acceptance:

1. Every current projection caller has one classification.
2. Any runtime-correctness dependency on projection data is explicitly named.
3. Console/read-model callers are separated from engine runtime callers.
4. No behavior changes in this slice.

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
   projections with a read model assembled outside engine kernel, or explicitly
   keep them as server/read-model storage concerns.
2. If `TaskDetailStore` remains the server read-model backing store, make the
   writer/assembler server-side or read-model-side, not engine-side runtime
   logic.
3. Update `InternalTaskReviewController` and related tests to the chosen read
   model.

Acceptance:

1. Console review no longer requires engine runtime code to write projection
   residue.
2. Server read-model ownership is documented outside engine kernel ownership.
3. Review/export tests remain deterministic and do not rely on hidden engine
   compatibility writes.

## Slice PBC-4: Remove Engine Projection Writes

Goal: stop engine runtime paths from writing compatibility projection rows.

Scope:

1. Remove `TaskCompatibilityProjectionStore` from `TaskManager` assembly.
2. Remove ingress projection writes from runtime item append.
3. Remove best-effort work/attempt projection writes from `TaskResultService`.
4. Delete `TaskWorkProjectionState` conversions to storage projection enums
   once no engine runtime path writes projection rows.
5. Remove `TaskDetailStore` constructor requirements that exist only for
   compatibility projection writes.

Acceptance:

1. Engine production code no longer imports
   `com.xa.mass.storage.api.projection.*`.
2. Engine runtime paths do not write `TaskDetailStore` message/attempt
   projections.
3. Any remaining `TaskDetailStore` dependency is justified by control-plane
   task detail storage, not runtime projection residue.
4. Result convergence, retry, expiry, and terminal policy tests still pass.

## Slice PBC-5: Guard And Proof

Goal: prevent projection residue from returning to engine runtime truth.

Scope:

1. Add or update architecture guards:
   - engine runtime packages must not import storage projection packages
   - scheduling/result/terminal code must not read `TaskDetailStore`
     message/attempt projections
   - projection read-model assembly, if any remains, is outside engine kernel
2. Add proof that console review/read-model still works through its new owner.
3. Add proof that runtime correctness tests pass without projection reads.

Acceptance:

1. Guard fails if engine production runtime code imports
   `com.xa.mass.storage.api.projection.*`.
2. Guard fails if scheduling/result/terminal code reads message/attempt
   projection rows.
3. Console review/read-model proof passes through the new owner.
4. Engine scheduling/result suites pass without projection assertion helpers.

## Implementation Order

Recommended order:

```text
PBC-0 -> PBC-1 -> PBC-2 -> PBC-3 -> PBC-4 -> PBC-5
```

Do not start by deleting projection writes. First move tests and console reads
off projection truth, then remove engine writes.

## Verification Candidates

Initial commands to keep in the roadmap proof set:

```powershell
mvn -pl xa-mass-engine -am '-Dtest=TaskStateValidatorBoundaryTest,TaskResultRuntimeConvergenceTest,SimpleTaskDispatchBinderTest,EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-server -am '-Dtest=TaskApiControllerTest,ControlConsoleRoutingIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The exact test list should be corrected in PBC-0 after the current projection
caller inventory is complete.
