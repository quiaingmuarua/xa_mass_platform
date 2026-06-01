# Engine Kernel Convergence Roadmap

Status: active. EKC-0 inventory is captured in
[`ENGINE_KERNEL_CONVERGENCE_INVENTORY.md`](./ENGINE_KERNEL_CONVERGENCE_INVENTORY.md).
EKC-1A has moved default runtime scheduling assembly out of SDK `MassEngine`
and into engine-owned `EngineRuntimeKernel`. EKC-1B removed SDK main references
to engine listener/watchdog/util implementation packages. EKC-1C moved
`TraceEventLogger` out of the generic `util` package and into the engine root
as an explicit lifecycle-trace contract and added an SDK guard requiring every
SDK main import from `com.xa.mass.engine` to be classified. EKC-2 added
`TaskDefinitionPatch` plus `TaskCommandService.patchTaskDefinition(...)`,
retargeted SDK task-definition updates away from mutable aggregate overwrite,
and added an SDK/source guard against `.updateTask(...)` usage outside approved
engine-internal paths. EKC-3A split the result repair-pump scheduling lifecycle
into `TaskResultRepairPump`; EKC-3B renamed projection-era runtime attempt/view
helpers in `TaskResultService`; EKC-3C moved visible-final commit and staged
callback cleanup into `TaskResultVisibleFinalCommitter`. Result convergence,
repair business handling, and progress application remain visible in the
current result/task owners. EKC-4 moved worker command lifecycle truth and
command value/port contracts to `xa-mass-worker-runtime.command`; engine keeps
worker-control entry, delivery coordination, trace emission, and dispatch-gate
side effects. EKC-5 added server-owned review materialization policy with
`TERMINAL` as the default mode and `reviewMaterializationMode` as a task
`sharedConfig` override interpreted only by server review materialization.
EKC-6 moved memory task-shell index tests back to `mass-storage-memory`,
introduced engine-owned test fixtures for task shell, worker declaration, and
rule definition ports, and removed the engine test dependency on
`mass-storage-memory`.

This roadmap continues after
[`ENGINE_STORAGE_API_DETACHMENT_ROADMAP.md`](./ENGINE_STORAGE_API_DETACHMENT_ROADMAP.md).
ESD removed the engine production dependency on storage APIs. The next
convergence target is smaller: keep the engine runtime kernel clear while
shrinking public surfaces, moving callers to intent-shaped ports, and removing
remaining owner ambiguity around result convergence, worker command lifecycle,
and review materialization triggers.

This roadmap is not a broad package cleanup. It is an owner-boundary
convergence plan.

## Current Code Facts

- `xa-mass-engine` production no longer imports `com.xa.mass.storage.*`.
- `xa-mass-engine/pom.xml` has no `mass-storage-memory` dependency. Engine
  tests use engine-owned fixtures for ordinary runtime proof and keep only
  `mass-storage-api` in test scope for rule definition contracts.
- `xa-mass-engine/src/main` still has a broad public surface. Many types under
  listener, watchdog, strategy, command, and stage packages are public
  because SDK assembly currently imports concrete engine internals directly.
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java` assembles
  engine internals directly: `TaskAssignWorker`, `TaskWorkerAssignListener`,
  `SimpleTaskDispatchBinder`, watchdogs, matching strategy, and trace logger.
- Server production source already has no engine implementation package
  imports. EKC-1 remediation is primarily SDK assembly cleanup while keeping
  server guards in place.
- `TaskManager` is still the engine composition root and implements many
  narrow ports. Its size alone is not the problem; large internal
  orchestrators are acceptable when owner boundaries, mainline flow, and
  scheduling entry points stay visible. The problem is which methods remain
  visible as cross-module caller surfaces.
- `TaskCommandPort` still exposes generic `updateTask(Task)` for
  engine-internal owner handoff, but SDK task-definition updates now use
  `patchTaskDefinition(String, TaskDefinitionPatch)`.
- `TaskResultService` is the largest active engine class and owns result
  ingestion, lease expiry, visible result commits, retry handling, repair pump,
  trace emission, and some projection-era wording in one implementation.
- `WorkerCommandLifecycleOwner` lives in engine while worker capability
  authority and worker state projection owners live in `xa-mass-worker-runtime`.
  Fixed by EKC-4: command lifecycle truth now lives in worker runtime, while
  engine keeps dispatch-control side effects and delivery coordination.
- Engine tests no longer use storage-memory implementation fixtures for
  ordinary runtime proof. Storage implementation behavior such as
  `InMemoryTaskShellStore` deadline/index maintenance lives in
  `mass-storage-memory` tests.
- Server review materialization is server-owned. Current dev wiring creates
  `InProcessTaskReviewReportQueue` and
  `QueueBackedTaskReviewReadModelWriter`. It records accepted items and
  work-final reports by default. Attempt-closed reports are diagnostic-only
  and require either a server default mode or task `sharedConfig` override of
  `DIAGNOSTIC`.

## Owner Review

1. **Engine is the runtime decision kernel, not the assembly API.**
   SDK/server may assemble engine components, but engine implementation classes
   should not become long-lived SDK-facing API by accident.

2. **Task command APIs should express intent, not expose aggregate overwrite.**
   External callers should say "patch task definition", "append items",
   "seal", or "terminate"; they should not read a mutable `Task`, change
   fields, and pass the whole aggregate back through a generic update method.

3. **Result convergence is the next high-value owner split.**
   The result path is runtime-critical and larger than a simple cleanup. Split
   by lifecycle owner responsibilities, not by arbitrary line count.

4. **Worker command lifecycle needs an explicit owner decision.**
   If it is worker lifecycle/control truth, it belongs with worker runtime. If
   it is engine dispatch-control truth, document why and keep only the
   dispatch-gate effects in engine.

5. **Review materialization is optional server read-model work.**
   It must not drive engine result truth. In production, most intermediate
   state materialization is low value compared with logs/trace. It should be
   configurable by task and environment, with terminal-focused behavior as the
   default.

## Boundary Decisions

### Engine Public Surface

`xa-mass-engine` should expose only stable runtime-kernel ports and explicit
assembly contracts. Concrete listener/watchdog/strategy classes may remain
public temporarily for assembly, but the target is that SDK/server code does
not depend on engine implementation packages unless the type is an approved
assembly contract.

Do not create pass-through wrappers merely to reduce imports or make a large
class look smaller. Create a new surface only when it protects an owner
boundary, lifecycle split, or external caller contract.

### Task Command Boundary

`TaskCommandService` remains the preferred cross-module write surface, but its
contract should narrow from CRUD-shaped aggregate writes to intent-shaped task
commands.

Generic `updateTask(Task)` is allowed for engine-internal owner handoff such as
assignment, lifecycle, and result convergence. It should not remain the normal
SDK/API path for changing task definitions.

### Result Convergence Boundary

`TaskResultService` should converge into explicit result-owner pieces:

```text
TaskResultIngest/Expiry entry
  -> TaskResultConvergenceOwner
  -> TaskResultCommit/Barrier helpers
  -> TaskResultRepairPump
  -> TaskProgress application through the existing task runtime port
```

The split must keep runtime correctness unchanged. No slice may move public
result truth to review materialization or trace.

### Worker Command Boundary

Worker command lifecycle must be classified before moving code:

- worker lifecycle/control truth -> `xa-mass-worker-runtime`
- engine dispatch-control truth -> engine, but with a documented dispatch-gate
  policy boundary

The command delivery port may remain an assembly seam. Command status truth
should not be split across two modules.

### Review Materialization Boundary

Review materialization remains server-owned and best-effort.

Review materialization is not an event warehouse. By default it stores durable
business-review facts, not every runtime transition. High-cardinality runtime
process evidence belongs to trace/logs unless a task, project/profile, or
operator action explicitly opts into diagnostic materialization.

This is a deliberate trade-off. Persisting every intermediate state can look
complete, but it adds write pressure, index and retention cost, query noise,
model drift risk, and long-term compatibility burden. For ordinary production
tasks, start/accepted evidence and terminal result/reason are usually the
valuable review facts; detailed process evidence is mainly useful during tests,
incident investigation, and targeted analysis.

Default production-oriented behavior should be:

```text
accepted item summary + terminal work materialization
```

Intermediate attempt/state materialization such as attempt-closed, assigned,
running, retry-delayed, or lease-expired retry evidence should be opt-in for
diagnostic/test tasks or environments.

The policy should be resolved outside engine, for example:

```text
server default config
  -> task sharedConfig override
  -> TaskReviewMaterializationMode
```

Suggested modes:

| Mode | Meaning |
| --- | --- |
| `OFF` | no server review row materialization for the task |
| `TERMINAL` | accepted item summary and terminal work rows only |
| `DIAGNOSTIC` | accepted, attempt/intermediate, and terminal rows |

`TERMINAL` should be the default unless local/dev/test profile explicitly
chooses `DIAGNOSTIC`.

## Non-Goals

1. Do not rewrite the scheduling algorithm in this roadmap.
2. Do not split `TaskManager` merely because it is large.
3. Do not move server review materialization into engine.
4. Do not make review rows the public result source.
5. Do not add compatibility aliases for old engine internals.
6. Do not move worker command lifecycle until ownership is decided from current
   callers and invariants.
7. Do not remove storage-memory test fixtures before replacing the engine test
   fixture strategy.
8. Do not make task-level review materialization policy part of transport
   protocol.

## Slice EKC-0: Inventory And Public Surface Classification

Goal: produce the current caller and owner map before moving code.

Scope:

1. Create `ENGINE_KERNEL_CONVERGENCE_INVENTORY.md`.
2. Classify all cross-module imports from `com.xa.mass.engine.*` in SDK,
   server, transport, and infra tests.
3. Separate allowed public runtime ports from accidental implementation imports:
   listener, watchdog, strategy, util, concrete service, and model classes.
4. Classify all `TaskCommandPort` methods by caller:
   external command, engine-internal mutation, test-only helper, or residue.
5. Inventory `TaskResultService` method groups: ingest, expiry, commit, repair,
   trace, progress application, and helper projections.
6. Inventory `WorkerCommandLifecycleOwner` callers and propose whether its
   state is worker-runtime lifecycle truth or engine dispatch-control truth.
7. Inventory review materialization triggers:
   `recordItemsAccepted`, `recordAttemptClosed`, and `recordWorkFinal`.
8. Verify whether task-level review materialization policy should use an
   existing top-level `sharedConfig` key, a nested SDK/server metadata key, or
   a dedicated server-side task policy field.

Acceptance:

1. Every non-test cross-module engine import has an owner classification.
2. Every `TaskCommandPort` method has a target classification.
3. Review materialization triggers are classified as default, diagnostic, or
   removable.
4. Worker command lifecycle ownership is proposed from evidence, not executed.
5. Task-level review materialization policy placement is decided or explicitly
   deferred to EKC-5.
6. No code behavior changes in this slice.

## Slice EKC-1: Engine Assembly Surface Convergence

Goal: stop SDK assembly from depending on arbitrary engine implementation
classes.

Scope:

1. Based on EKC-0, define the minimal approved engine assembly surface.
2. Retarget SDK `MassEngine` / `EngineConfig` usage away from internal
   listener/watchdog/strategy implementation imports where a real assembly
   boundary exists.
3. Keep implementation classes package-private where they are no longer used
   outside engine.
4. Add or update architecture guards so new cross-module imports from engine
   implementation packages require explicit allowlist classification.

Acceptance:

1. SDK production no longer imports unclassified engine implementation classes.
   Server production remains free of engine implementation imports.
2. Engine still starts through the existing SDK/server boot path.
3. Scheduling and server E2E representative tests pass.
4. No new same-module pass-through wrappers exist without an owner-boundary
   reason in the inventory.

## Slice EKC-2: Task Command Intent Boundary

Goal: remove CRUD-shaped aggregate update from normal SDK/API task definition
flows.

Status: implemented for the SDK task-definition path. `TaskDefinitionPatch`
is the intent-shaped cross-module command; generic `updateTask(Task)` remains
available only for engine-internal aggregate persistence. SDK main is guarded
against `.updateTask(...)` call-pattern residue.

Scope:

1. Introduce an intent-shaped task definition update request or command method
   owned by engine task command surface.
2. Retarget SDK/API task update paths from
   `getTask -> mutate Task -> updateTask(Task)` to the new intent command.
3. Keep `updateTask(Task)` only where EKC-0 proves engine-internal owner
   handoff requires aggregate persistence.
4. Add a source guard that fails if SDK/server production code calls generic
   `.updateTask(...)` with a mutable `Task` aggregate outside explicitly
   allowlisted engine-internal assembly paths. The guard must scan call
   patterns and paths, not only the `TaskCommandService` class name.

Acceptance:

1. External task definition update no longer uses generic aggregate overwrite.
2. Engine-internal assignment/lifecycle/result paths still persist task
   aggregate changes correctly.
3. Existing task command/API tests pass.

## Slice EKC-3: Result Convergence Owner Split

Goal: split `TaskResultService` by real result-owner responsibilities without
changing runtime behavior.

Status: implemented through EKC-3C. EKC-3A extracted repair-pump scheduling and shutdown into
`TaskResultRepairPump`. This is a real lifecycle split: the new class owns the
scheduled executor, interval/batch configuration, exception isolation, and
shutdown. EKC-3B removed `AttemptProjectionView`/active projection wording from
the hot result service in favor of runtime-view names. EKC-3C moved
visible-final commit and staged callback cleanup into
`TaskResultVisibleFinalCommitter`, keeping runtime result truth in
`TaskResultRuntime`. During EKC-3C, a repair-pump race was fixed so a BUSY
progress-apply barrier no longer hides synchronous task-state convergence; the
progress update is idempotent and still derived from runtime truth. Repair
business handling and broader result progress application remain with the
current result/task owners until a clearer convergence owner boundary is proven
separately.

Scope:

1. Extract result convergence/apply logic into an owner class with a narrow
   dependency surface.
2. Extract repair-pump scheduling and drain/shutdown behavior from the
   convergence owner.
3. Replace projection-era wording in result code with runtime/review-neutral
   terms where behavior is now runtime-first.
4. Keep trace emission semantics unchanged unless a current trace contract says
   otherwise.
5. Verify existing result convergence coverage still exercises lease expiry
   retry, stale replay, visible commit, and repair-pump shutdown after the
   split. Add tests only if the split exposes a previously invisible coverage
   gap.

Acceptance:

1. Runtime late replay, lease expiry, retry, and result convergence tests pass.
2. No result path depends on server review materialization.
3. `TaskResultService` no longer owns both convergence logic and repair-pump
   lifecycle directly.
4. Trace contract output remains compatible.

## Slice EKC-4: Worker Command Lifecycle Ownership Decision

Goal: decide and converge worker command lifecycle ownership.

Status: implemented. EKC-4 classified worker command records/status as
worker-scoped lifecycle truth and moved `WorkerCommandLifecycleOwner` plus
command request, acknowledgement, record, status, lifecycle-result, and
delivery-port value contracts to `com.xa.mass.worker.runtime.command`. Engine
retains `WorkerControlService`, `WorkerCommandDeliveryCoordinator`,
`WorkerCommandRequestEventHandler`, and `WorkerCommandMaintenanceWatchdog`
because they own engine entry, trace/delivery handoff, and dispatch-gate
side effects rather than command state truth.

Scope:

1. Use EKC-0 inventory to confirm whether `WorkerCommandLifecycleOwner`
   belongs in engine or `xa-mass-worker-runtime`, then execute the move-or-keep
   decision.
2. If it remains in engine, document that worker commands are dispatch-control
   truth and keep worker-runtime integration limited to resource/state inputs.
3. If it moves to worker-runtime, move command records/status/result contracts
   and leave engine with only dispatch-gate policy application.
4. Update SDK/server imports and architecture guards.

Acceptance:

1. There is one owner of worker command lifecycle state.
2. Worker state/capability/report runtime boundaries remain intact.
3. Worker command request, acknowledgement, retry, and expiry tests pass.

## Slice EKC-5: Review Materialization Policy

Goal: make server review materialization configurable by task and environment,
without changing engine truth.

Status: implemented. `TaskReviewMaterializationPolicy` and
`TaskReviewMaterializationMode` live in the server review package. Server
configuration property `mass.review.materialization.mode` defaults to
`TERMINAL`. The task-level override key is the server-owned top-level
`sharedConfig` key `reviewMaterializationMode`; it is intentionally not a base
model constant or transport protocol field. `QueueBackedTaskReviewReadModelWriter`
evaluates policy before queue submission, keeps accepted-item and terminal
work events in `TERMINAL`, skips attempt-closed events unless mode is
`DIAGNOSTIC`, and logs skipped materialization with queue stats. Existing
review tests either assert terminal-only behavior or opt into diagnostic mode.

Scope:

1. Add a server-owned `TaskReviewMaterializationPolicy` or equivalent resolver.
2. Resolve policy from server default configuration and task-level
   `sharedConfig` override.
3. Default production-oriented mode to `TERMINAL`.
4. Keep dev/test profile free to choose `DIAGNOSTIC` where review-row E2E proof
   still intentionally asserts attempt/intermediate materialization.
5. Retarget `taskReviewReadModelAttemptClosedListener` so attempt-closed review
   writes happen only when policy mode is `DIAGNOSTIC`.
6. Keep accepted item and terminal work materialization under `TERMINAL`.
7. Add queue stats/log evidence for skipped diagnostic events so operators can
   distinguish "disabled by policy" from "queue failed".
8. Document the product trade-off: DB review rows are for durable
   business-review facts by default; logs/trace carry high-cardinality process
   evidence unless diagnostic materialization is explicitly enabled.

Acceptance:

1. Engine has no dependency on review materialization policy.
2. Default server runtime does not materialize attempt/intermediate review rows
   unless task/profile policy enables diagnostic mode.
3. Existing review-read-model tests either opt into `DIAGNOSTIC` or assert only
   accepted/terminal rows.
4. Public result APIs still read `TaskResultRuntime`, not review rows.
5. Docs and guards make it clear that review materialization must not become a
   default DB event warehouse for every runtime transition.

## Slice EKC-6: Engine Test Fixture Cleanup

Goal: remove infra-storage implementation ownership from engine test lanes
where possible.

Status: implemented for current engine test scope. Engine test lanes now use
engine-local fixtures `InMemoryTaskShellRuntimeStore`,
`InMemoryWorkerDeclarationRuntimeStore`, and `InMemoryRuleDefinitionStore`.
`InMemoryTaskShellStore` implementation/index tests moved to
`mass-storage-memory`. `xa-mass-engine/pom.xml` no longer declares
`mass-storage-memory`; it keeps `mass-storage-api` only in test scope for the
rule definition test port. `EngineProofOwnershipGuardTest` now fails if
`mass-storage-memory` is reintroduced as a generic engine test fixture.

Scope:

1. Move storage implementation contract tests out of `xa-mass-engine` and into
   the owning storage module.
2. Replace direct storage-memory usage in engine tests with a kernel-spi test
   fixture where storage behavior is not the subject.
3. Keep storage-memory test scope only when the test explicitly verifies
   engine integration with the memory storage adapter.
4. Update engine proof guards to distinguish allowed test fixture usage from
   accidental production dependency drift.

Acceptance:

1. Engine tests no longer own storage implementation behavior.
2. Engine production dependency graph remains storage-free.
3. Engine and storage-module test suites pass.

## Suggested Order

```text
EKC-0 -> EKC-1 -> EKC-2 -> EKC-3 -> EKC-4 -> EKC-5 -> EKC-6
```

EKC-5 may run after EKC-0 if review materialization policy becomes urgent, but
it must remain server-owned and must not use engine cleanup as a reason to move
review concepts into engine.

## Do Not Start With

Do not start by deleting public modifiers, moving `TaskResultService`, or
turning off review materialization globally.

First classify callers and owners. Then narrow external surfaces. Then split
result convergence and worker command lifecycle. Review materialization policy
must be task/profile configurable, not a hard global removal.

## Verification Candidates

After EKC-1:

```powershell
mvn -pl xa-mass-engine,xa-mass-sdk,xa-mass-server -am -DskipTests compile
```

```powershell
rg -n "com\.xa\.mass\.engine\.(listener|watchdog|strategy|util)" xa-mass-sdk/src/main xa-mass-server/src/main transport platform_infra
```

After EKC-2:

```powershell
mvn -pl xa-mass-engine,xa-mass-sdk,xa-mass-server -am test
```

```powershell
rg -n "updateTask\(task\)|updateTask\(.*Task" xa-mass-sdk/src/main xa-mass-server/src/main
```

After EKC-3:

```powershell
mvn -pl xa-mass-engine,platform_infra/mass-runtime-redis,xa-mass-server -am test
```

After EKC-4:

```powershell
mvn -pl xa-mass-engine,xa-mass-worker-runtime,xa-mass-sdk,xa-mass-server -am test
```

```powershell
rg -n "WorkerCommandLifecycleOwner" xa-mass-engine/src/main
```

Expected result after EKC-4: if worker command lifecycle moved to
`xa-mass-worker-runtime`, engine production has no owner implementation
reference. If the owner remains in engine, remaining references are documented
as dispatch-control truth in the inventory and README/baseline update.

After EKC-5:

```powershell
mvn -pl xa-mass-server -am "-Dtest=*Review*,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

```powershell
rg -n "recordAttemptClosed" xa-mass-server/src/main
```

Expected result after EKC-5: attempt-closed review writes are gated by the
server-owned review materialization policy.

After EKC-6:

```powershell
mvn -pl xa-mass-engine,platform_infra/mass-storage-memory -am clean test
```

```powershell
rg -n "import com\.xa\.mass\.storage\.memory|mass-storage-memory" xa-mass-engine/src/test xa-mass-engine/pom.xml
```
