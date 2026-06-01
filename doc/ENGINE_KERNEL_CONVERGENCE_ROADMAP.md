# Engine Kernel Convergence Roadmap

Status: proposed.

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
- `xa-mass-engine/pom.xml` keeps `mass-storage-memory` only in test scope.
- `xa-mass-engine/src/main` still has a broad public surface. Many types under
  listener, watchdog, strategy, command, stage, and util packages are public
  because SDK assembly currently imports concrete engine internals directly.
- `xa-mass-sdk/src/main/java/com/xa/mass/starter/MassEngine.java` assembles
  engine internals directly: `TaskAssignWorker`, `TaskWorkerAssignListener`,
  `SimpleTaskDispatchBinder`, watchdogs, matching strategy, and trace logger.
- `TaskManager` is still the engine composition root and implements many
  narrow ports. Its size alone is not the problem; the problem is which
  methods remain visible as cross-module caller surfaces.
- `TaskCommandPort` still exposes CRUD-shaped methods such as
  `updateTask(Task)` and `deleteTask(String)`. SDK currently uses
  `getTask(...) -> mutate Task -> updateTask(...)` for task definition
  updates.
- `TaskResultService` is the largest active engine class and owns result
  ingestion, lease expiry, visible result commits, retry handling, repair pump,
  trace emission, and some projection-era wording in one implementation.
- `WorkerCommandLifecycleOwner` lives in engine while worker capability
  authority and worker state projection owners live in `xa-mass-worker-runtime`.
  This may be correct only if worker command lifecycle is treated as engine
  dispatch-control truth; otherwise it should converge toward worker runtime.
- Engine tests still directly use storage implementation fixtures such as
  `InMemoryTaskShellStore`, including a storage implementation test under the
  engine test tree.
- Server review materialization is server-owned. Current dev wiring creates
  `InProcessTaskReviewReportQueue` and
  `QueueBackedTaskReviewReadModelWriter`. It records accepted items, work-final
  reports, and attempt-closed reports by default.

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

Do not create pass-through wrappers merely to reduce imports. Create a new
surface only when it protects an owner boundary, lifecycle split, or external
caller contract.

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
6. Inventory `WorkerCommandLifecycleOwner` callers and decide whether its state
   is worker-runtime lifecycle truth or engine dispatch-control truth.
7. Inventory review materialization triggers:
   `recordItemsAccepted`, `recordAttemptClosed`, and `recordWorkFinal`.

Acceptance:

1. Every non-test cross-module engine import has an owner classification.
2. Every `TaskCommandPort` method has a target classification.
3. Review materialization triggers are classified as default, diagnostic, or
   removable.
4. No code behavior changes in this slice.

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

1. SDK/server no longer import unclassified engine implementation classes.
2. Engine still starts through the existing SDK/server boot path.
3. Scheduling and server E2E representative tests pass.
4. No new same-module pass-through wrappers exist without an owner-boundary
   reason in the inventory.

## Slice EKC-2: Task Command Intent Boundary

Goal: remove CRUD-shaped aggregate update from normal SDK/API task definition
flows.

Scope:

1. Introduce an intent-shaped task definition update request or command method
   owned by engine task command surface.
2. Retarget SDK/API task update paths from
   `getTask -> mutate Task -> updateTask(Task)` to the new intent command.
3. Keep `updateTask(Task)` only where EKC-0 proves engine-internal owner
   handoff requires aggregate persistence.
4. Guard against SDK/server production code calling generic
   `TaskCommandService.updateTask(Task)` for task definition updates.

Acceptance:

1. External task definition update no longer uses generic aggregate overwrite.
2. Engine-internal assignment/lifecycle/result paths still persist task
   aggregate changes correctly.
3. Existing task command/API tests pass.

## Slice EKC-3: Result Convergence Owner Split

Goal: split `TaskResultService` by real result-owner responsibilities without
changing runtime behavior.

Scope:

1. Extract result convergence/apply logic into an owner class with a narrow
   dependency surface.
2. Extract repair-pump scheduling and drain/shutdown behavior from the
   convergence owner.
3. Replace projection-era wording in result code with runtime/review-neutral
   terms where behavior is now runtime-first.
4. Keep trace emission semantics unchanged unless a current trace contract says
   otherwise.
5. Add focused tests around lease expiry retry, stale replay, visible commit,
   and repair-pump shutdown if existing coverage is not enough.

Acceptance:

1. Runtime late replay, lease expiry, retry, and result convergence tests pass.
2. No result path depends on server review materialization.
3. `TaskResultService` no longer owns both convergence logic and repair-pump
   lifecycle directly.
4. Trace contract output remains compatible.

## Slice EKC-4: Worker Command Lifecycle Ownership Decision

Goal: decide and converge worker command lifecycle ownership.

Scope:

1. Use EKC-0 inventory to decide whether `WorkerCommandLifecycleOwner` belongs
   in engine or `xa-mass-worker-runtime`.
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
rg -n "import com\.xa\.mass\.engine\.(listener|watchdog|strategy|util)" xa-mass-sdk/src/main xa-mass-server/src/main transport platform_infra
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
mvn -pl xa-mass-engine,platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc -am test
```
