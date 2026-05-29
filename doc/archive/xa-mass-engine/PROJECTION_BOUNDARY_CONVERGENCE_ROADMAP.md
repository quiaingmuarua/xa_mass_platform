# Projection Boundary Convergence Roadmap

Status: **completed**. All slices (PBC-0 through PBC-5) have landed. Archived
to `doc/archive/xa-mass-engine/`.

PBC-0 inventory landed and selected server/read-model-side assembly for PBC-3.
PBC-1 is covered by existing mainline proof guards. PBC-2 landed. PBC-3 server
review contract/controller/helper migration landed, and the server read-model
writer is now wired from append plus final runtime-result evidence, including
worker, batch, attempt, and timing fields. PBC-4 removed engine compatibility
projection writes and split engine-native lifecycle enums out of the old
projection-named state holder. PBC-5 production ownership guards now prevent
projection-write dependencies or unclassified storage imports from returning to
engine mainline code.

This was the `xa-mass-engine` projection boundary convergence roadmap.

PBC-0 inventory record:
[`PROJECTION_BOUNDARY_CONVERGENCE_INVENTORY.md`](PROJECTION_BOUNDARY_CONVERGENCE_INVENTORY.md).

PBC-0 decision: PBC-3 uses **Path A, server/read-model-side assembly**.
Trace/archive-derived review remains the later direction, but current trace
payloads do not yet cover the full review/export field set. In particular,
review still needs accepted input, event code, payload ref, max retry count,
full timing fields, output/error payload, and aggregate item stats. PBC-3 must
therefore move review projection assembly out of engine runtime and into a
server/read-model owner before PBC-4 removes engine compatibility writes.

This roadmap removes compatibility task message/attempt projection from the
engine kernel boundary. It is intentionally separate from the completed
rule-boundary convergence record in
[`RULE_BOUNDARY_CONVERGENCE_ROADMAP.md`](RULE_BOUNDARY_CONVERGENCE_ROADMAP.md)
because projection cleanup affects runtime result convergence, console review
read models, tests, and storage read-model assembly.

Do not create a parallel storage-dependency convergence track for this work.
The next meaningful engine dependency reduction is the projection boundary
work described here. Broadly removing `mass-storage-api` from engine is not
the current goal because task shell storage and rule-definition reads remain
legitimate engine-facing contracts until separate owner work changes them.

The current code has moved scheduling, terminal, and work-finality truth toward
runtime state. PBC-2 removed the default engine projection-audit scan, and
PBC-4 removed engine writes to `TaskDetailStore` message/attempt projection
residue. Server review surfaces now depend on a server `TaskReviewReadModel`
contract backed by a transitional `TaskDetailStore` implementation.

## Current Code Observations

- `TaskCompatibilityProjectionStore` has been deleted from engine production
  code.
- `TaskWorkProjectionState` has been deleted from engine production code.
  Runtime event records now use `TaskWorkLifecycleState`.
- PBC-2 removed `TaskProjectionStateAuditor`; default engine diagnostics no
  longer scan compatibility projection residue.
- `TaskManager` no longer constructs a compatibility projection store and no
  longer requires `TaskDetailStore` for projection writes.
- `TaskResultService` no longer writes best-effort work or attempt projection
  residue after runtime result convergence.
- `TaskManager.addRuntimeIngressItems(...)` admits work into runtime state
  without writing ingress-accepted projection residue.
- `InternalTaskReviewController` depends on `TaskReviewReadModel`, not
  `TaskDetailStore`.
- `InternalTaskReviewController` uses projection rows for review summary,
  seed preview/export, and result preview/export. The required fields include
  message id, input/event code, payload ref, status/final reason, retry counts,
  latest attempt id/worker/batch, timing fields, error summary, output, and
  aggregate message stats.
- Server proof lanes that used to include projection-specific suites and support
  classes have been renamed to review read-model ownership:
  `ServerReviewReadModelResidueSuite`, `ServerReviewReadModelAuditSuite`, and
  `ReviewReadModelSampleE2eTest`.
- The server read-model writer is attached after item append acceptance and
  final runtime-result visibility. Finality enriches the logical-final
  notification from SDK `getTaskWorkFinal(...)` so worker id, batch id, attempt
  id, retry/max-retry counts, result payload, and timing fields come from the
  runtime result row rather than from engine projection residue.
- Engine mainline result/convergence tests use runtime/result/task aggregate
  proof. Explicit compatibility projection tests remain secondary proof lanes
  and test-only helpers.
- `@CompatibilityProjectionOnly` remains on explicit test-only compatibility
  projection helpers. Engine production code is guarded from consuming it.
- `TaskWorkLifecycleState` owns engine-native work/attempt lifecycle enums used
  by `TaskWorkLogicallyFinalEvent`, `TaskWorkAttemptClosedEvent`,
  `SimpleTaskDispatchBinder`, `TraceEventLogger`, and SDK tests.
- Engine production code still depends on `mass-storage-api` for three
  different reasons that must not be collapsed:
  - `TaskShellStore` / `TaskShellLifecycleQuery`: current task shell and
    lifecycle lookup contract; not a PBC removal target.
  - `TaskDetailStore` and `com.xa.mass.storage.api.projection.*`: projection
    residue; removed from engine production by PBC-4.
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
- the PBC-3 read-model replacement path has been decided by PBC-0: use
  server/read-model-side assembly first, not a trace-only replacement
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

Status: landed. Inventory produced in
`PROJECTION_BOUNDARY_CONVERGENCE_INVENTORY.md`. Path A selected for PBC-3.

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
6. Record the PBC-3 replacement path selected by the inventory:
   - selected path A: server/read-model-side writer keeps a
     `TaskDetailStore`-like review projection without engine runtime writes
   - deferred path B: server review reads from trace/archive/runtime-derived
     read model once trace/archive can cover the complete review/export field
     set
   - any third path requires owner review before PBC-3 implementation resumes
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
 10. Classify server projection proof assets. The PBC-0 inventory captured the
     pre-migration names; current PBC-3 names are
     `ServerReviewReadModelResidueSuite`, `ServerReviewReadModelAuditSuite`,
     and `ReviewReadModelSampleE2eTest`.
11. Produce a small inventory doc beside this roadmap.

Acceptance:

1. Every current projection caller has one classification.
2. Any runtime-correctness dependency on projection data is explicitly named.
3. Console/read-model callers are separated from engine runtime callers.
4. Every current engine production dependency on `mass-storage-api` is
   classified as keep-for-now, PBC target, rule follow-up, or unexpected.
5. Every `TaskWorkProjectionState` enum consumer is classified before PBC-4
   attempts to delete projection conversion logic.
6. PBC-3 has one selected read-model replacement path with explicit tradeoffs:
   server/read-model-side assembly now, trace/archive-derived review later.
7. `InternalTaskReviewController` field coverage is mapped against
   trace/runtime/read-model sources before PBC-3 implementation starts.
8. Server projection proof assets have an owner decision: migrate, rename,
   keep as explicit projection-read-model proof, or delete.
9. No behavior changes in this slice.

## Slice PBC-1: Replace Runtime Assertions With Runtime Truth

Status: landed. Covered by existing mainline proof guards; engine runtime
correctness tests no longer require projection reads.

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

Status: landed. `TaskProjectionStateAuditor` deleted; default engine
diagnostics no longer scan compatibility projection residue.

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

Status: landed. `InternalTaskReviewController` depends on `TaskReviewReadModel`.
Server read-model writer wired from ingress acceptance and runtime-result
finality evidence.

Goal: keep console review useful without making engine own projection truth.

Scope:

1. Replace direct server review reads from `TaskDetailStore` message/attempt
   projections with a server-owned review read-model contract.
2. Implement the selected Path A writer/assembler server-side or
   read-model-side, not engine-side runtime logic. `TaskDetailStore` may remain
   an implementation detail behind that server read-model during this slice,
   but `InternalTaskReviewController` must not depend on it directly.
3. Treat trace/archive-derived review as a later follow-up unless PBC-3 first
   adds enough trace/archive data to cover the full field matrix.
4. Do not start this slice until the `InternalTaskReviewController` field
   coverage matrix has no unresolved required field.
5. Update `InternalTaskReviewController` and related tests to the chosen read
   model.
6. Migrate or rename server projection proof lanes according to the PBC-0
   owner decision:
   - `ServerReviewReadModelResidueSuite`
   - `ServerReviewReadModelAuditSuite`
   - `ReviewReadModelSampleE2eTest`
   - E2E classes extending `ReviewReadModelSampleE2eTest`
7. Keep the replacement read model deterministic enough for local console demo
   and review surfaces; do not make external network calls part of this proof.
8. Wire the server/read-model writer from explicit ingress and lifecycle
   evidence:
   - item append acceptance for message id, input, event code, payload ref, max
     retry count, and create time
   - dispatch/attempt evidence for worker id, batch id, attempt id, assignment,
     start/update timing, and running state
   - finality/result evidence for final status, final reason, retry count,
     error summary, output, completion time, and aggregate stats
9. If current engine events do not expose a required review field, add a
   bounded event/read-model input or document the gap as a blocker before
   PBC-4. Do not silently drop review/export fields to make the migration pass.

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
6. `InternalTaskReviewController` depends on the server review read-model
   contract, not `TaskDetailStore`.
7. The migrated server projection proof lane proves accepted input,
   worker/batch/attempt identity, timing, final output/error, retry state, and
   aggregate stats through the new owner.

## Slice PBC-4: Remove Engine Projection Writes

Goal: stop engine runtime paths from writing compatibility projection rows.

Status: landed. Engine production no longer owns compatibility projection
assembly or writes.

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

1. Landed: engine production code no longer imports
   `com.xa.mass.storage.api.projection.*`.
2. Landed: engine runtime paths do not write `TaskDetailStore` message/attempt
   projections.
3. Landed: `TaskDetailStore` is no longer required by `TaskManager` or
   `TaskResultService`.
4. Verified: result convergence, retry, expiry, and terminal-policy focused
   tests compile and run through the runtime/result proof lane.
5. Landed: remaining engine `mass-storage-api` imports are limited to explicitly kept
   task shell/control-plane or rule-domain follow-up contracts.
6. Landed: runtime work/finality event records no longer use
   `TaskWorkProjectionState` as their field type.

## Slice PBC-5: Guard And Proof

Goal: prevent projection residue from returning to engine runtime truth.

Status: landed for the current PBC scope. `EngineProofOwnershipGuardTest` now
guards engine production against `TaskDetailStore` projection rows, storage
projection enum packages, `TaskCompatibilityProjectionStore`,
`TaskWorkProjectionState`, projection read/write method names,
`@CompatibilityProjectionOnly`, and unclassified storage imports outside the
PBC allowlist.

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

1. Landed: guard fails if engine production runtime code imports
   `com.xa.mass.storage.api.projection.*`.
2. Landed: guard fails if scheduling/result/terminal code reads message/attempt
   projection rows.
3. Verified: console review/read-model proof passes through the new owner.
4. Verified: engine scheduling/result suites pass without projection assertion
   helpers in mainline proof lanes.
5. Landed: guard fails when a new unclassified engine
   `mass-storage-api` dependency appears.
6. Landed: guard fails if a new engine kernel/runtime path consumes
   `@CompatibilityProjectionOnly` code outside an explicitly allowed
   projection/read-model lane.

## Implementation Order

Recommended order:

```text
PBC-0 -> PBC-1 -> PBC-2 -> PBC-3 -> PBC-4 -> PBC-5
```

Do not start by deleting projection writes. First move tests and console reads
off projection truth, then remove engine writes.

PBC-4 must start only after the PBC-3 server proof lane passes. Deleting engine
projection writes before the server review replacement is in place will
silently break `/internal/v1/review/tasks/**` seed/result preview and export
behavior.

PBC is the engine convergence mainline. After PBC lands, reassess whether
remaining engine `mass-storage-api` dependencies are real owner-boundary
problems:

- `TaskShellStore` should stay until a task-shell owner boundary changes.
- `RuleStorage` / storage rule DTOs should move only through a rule-domain
  roadmap, not as cleanup fallout from projection work.
- `TaskDetailStore` should no longer be required by engine kernel runtime code.

## Surviving Test Residue

`TaskCompatibilityProjectionAccess` (engine test-only, `@CompatibilityProjectionOnly`)
provides bounded compatibility projection overlay and audit views for secondary
proof lanes. It is guarded by `EngineProofOwnershipGuardTest` +
`EngineProjectionResidueSuite` + `@Tag("secondary-proof")` so it cannot leak
into mainline proof or production.

Retirement trigger: delete `TaskCompatibilityProjectionAccess` and its
consuming test classes when the transitional `TaskDetailStoreTaskReviewReadModel`
is replaced by a trace/archive-derived read model that no longer needs
`TaskDetailStore` projection rows. At that point, `TaskDetailStore`
message/attempt projection types can also be removed from `mass-storage-api`.

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
