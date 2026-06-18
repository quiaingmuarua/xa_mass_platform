# Task Shell Runtime Model Convergence Roadmap

Status: proposed direction document.

This roadmap converges task model ownership after the worker model cleanup
proved the same problem on the worker side: a broad base model becomes an
accidental runtime contract. The target is not to move `Task` wholesale into
engine. The target is to split task shell storage, engine lifecycle mutation,
runtime work/result truth, and public SDK/server read models into separate
owner surfaces.

Read with:

- [AGENT_BASELINE.md](../doc/AGENT_BASELINE.md)
- [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md)
- [TASK_SHELL_RUNTIME_MODEL_CONVERGENCE_INVENTORY.md](TASK_SHELL_RUNTIME_MODEL_CONVERGENCE_INVENTORY.md)
- [TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_ROADMAP.md](TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_ROADMAP.md)
- [WORKER_RUNTIME_MINIMAL_INTERFACE_CONVERGENCE_ROADMAP.md](WORKER_RUNTIME_MINIMAL_INTERFACE_CONVERGENCE_ROADMAP.md)

## Current Code Observations

- `com.xa.mass.base.model.Task` is still a mutable shared aggregate. It carries
  task shell identity, project/user, `contract`, `executionSpec`, `sharedConfig`,
  status, intake state, terminal reason, counters, worker assignment evidence,
  timestamps, and lifecycle mutation helpers.
- `TaskShellRuntimeStore` and `TaskShellRuntimeLifecycleQuery` in
  `xa-mass-kernel-spi` currently accept and return `Task`.
- `TaskShellStore` in `platform_infra/mass-storage-api` also accepts and
  returns `Task`.
- Engine production ports still expose the fat shape: `TaskCommandPort`,
  `TaskQueryPort`, `TaskStateRuntimePort`, `TaskRuntimeRecoveryPort`,
  `TaskAssignmentRuntimePort`, and `TaskDispatchWakeupPort` accept or return
  `Task` in at least one method.
- `TaskWorkRuntime` already owns runtime work truth: ready/delayed work, active
  leases, retry, expiry, backpressure, result apply, and runtime stats.
- `TaskResultRuntime` already owns result runtime truth: staged callbacks,
  visible final rows, sequential result windows, and publish/progress barriers.
- Engine lifecycle writes are already partly centralized in
  `TaskLifecycleService`, `TaskStateResolver`, and `TaskWorkerAssignListener`,
  while `TaskManager` is the composition root.
- Scheduling Plane values already exist (`TaskDispatchIntent`,
  `ResolvedTaskSchedulingPolicy`, `ResolvedWorkerSchedulingPolicy`,
  `SchedulingPlaneResolver`, and `TaskPolicyPresetResolver`), but the current
  resolver boundary still receives a whole `Task` and factories still read
  `sharedConfig`, `executionSpec`, and `minRequiredWorkerCount` from it.
- `TaskResultService` is runtime-first, but still rebuilds a
  message/attempt-shaped event view through `RuntimeWorkSummary`,
  `RuntimeAttemptView`, and `TaskWorkLifecycleState`.
- Worker convergence has already established the precedent: worker-runtime
  declaration stores use narrow records and guards reject `base.model.Worker`
  on runtime/declaration ports.

## Owner Review

Task shell belongs to the task control-plane/kernel shell boundary. It may be
persisted by storage adapters, but storage should not define a broad runtime
domain object.

`TaskShellRuntimeStore` is the engine/kernel runtime port. `TaskShellStore` is
the storage adapter surface. They can be implemented by the same adapter class,
but they must converge to the same narrow shell record in the same slice; they
must not become two independent task-shell truths.

Engine owns task lifecycle mutation, scheduling-policy consumption, assignment
state changes, progress reconciliation, result convergence, and terminal
policy. Engine may mutate shell records through owner services, but those
mutation rules must not live in a shared base model.

Scheduling policy input is not a runtime hot-path task model. `TaskContract`,
`TaskExecutionSpec`, and `sharedConfig` may remain persisted shell inputs, but
the Scheduling Plane should parse them at the boundary into
`TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, and
`ResolvedWorkerSchedulingPolicy`; assignment, dispatch, retry, and matching
mechanisms should consume those resolved values rather than re-processing a
whole `Task`.

Runtime owns runtime facts. `TaskWorkRuntime` and `TaskResultRuntime` are the
owners for work queue/lease/retry/result truth. Runtime callers should exchange
runtime values such as `TaskWorkEnvelope`, `TaskWorkResult`,
`TaskWorkStats`, `TaskResultRuntimeRow`, and result windows, not a whole
`Task`.

SDK and server own external contract/read-model surfaces. They should expose
intent-shaped request models and snapshots such as `TaskShellSnapshot`,
`TaskDetailSnapshot`, `TaskSummarySnapshot`, and `ApiTask*` records instead of
freezing a mutable base aggregate as a public API.

## Boundary Decision

Use four distinct surfaces:

```text
task shell/control-plane record
  narrow stored task shell fields only
  id, tenant, project, user, contract, execution options, status,
  intake, terminal reason, bounded aggregate counters, timestamps

scheduling boundary values
  TaskDispatchIntent, ResolvedTaskSchedulingPolicy,
  ResolvedWorkerSchedulingPolicy, policy/preset resolution outputs

engine lifecycle/mutation owner
  command methods, lifecycle services, state resolver, assignment owner,
  terminal policy, progress reconciliation

runtime work/result truth
  TaskWorkRuntime, TaskResultRuntime, runtime envelopes/results/stats/windows

public SDK/server read models
  SDK task snapshots and server ApiTask records
```

`base.model.Task` is a convergence source, not the target contract. The end
state should look closer to the worker model after WMI: narrow task shell
records for storage/control-plane, narrow runtime values for hot paths, and
separate public snapshots for SDK/server callers.

## Do Not Start With

Do not start by moving `Task` into `xa-mass-engine` or deleting
`base.model.Task`. That would preserve the fat model shape under a new owner or
break storage/SDK/server callers before replacement contracts exist.

Start by classifying callers and introducing a narrow shell record, then move
ports and callers, then remove fat-model residue.

## Non-Goals

- Do not add compatibility aliases or dual live task models after in-repo
  callers have moved.
- Do not move runtime queue, lease, retry, callback, result, or trace history
  into task shell storage.
- Do not introduce a new broad `EngineTask` aggregate that simply wraps every
  old `Task` field.
- Do not change public server HTTP response contracts unless the slice
  explicitly targets API snapshot assembly.
- Do not make trace, review, or archive read models feed scheduling, dispatch,
  result convergence, or lifecycle decisions.
- Do not use this roadmap to redesign scheduling policy products or worker
  selection.

## TMC-0 Inventory And Classification

Goal: freeze the current caller and field inventory before changing contracts.

Scope:

- Maintain `TASK_SHELL_RUNTIME_MODEL_CONVERGENCE_INVENTORY.md`.
- Classify every `Task` field as shell record, engine mutation state, runtime
  derived evidence, public read model, diagnostic residue, or removal
  candidate.
- Maintain a field-level disposition table that names target owner, writer,
  reader family, and migration slice for every current `Task` field and direct
  lifecycle helper.
- Maintain an engine port inventory for `TaskCommandPort`, `TaskQueryPort`,
  `TaskStateRuntimePort`, `TaskRuntimeRecoveryPort`,
  `TaskAssignmentRuntimePort`, and `TaskDispatchWakeupPort`.
- Separate production callers from tests and examples.
- Classify SDK/server caller surfaces as public snapshots, starter/internal
  fat returns, or server assembly/storage wiring.
- Decide the disposition for `minRequiredWorkerCount`,
  `peakAssignedWorkerCount`, `taskTargetNumber`, `taskEligibleNumber`,
  `taskSuccessNumber`, and `taskNonSuccessNumber`.

Acceptance:

- Inventory names all production ports that accept or return `Task`.
- Inventory has a target owner for each current `Task` field.
- Inventory has a target shape for each engine port: task id only where
  possible, shell record only where shell state is genuinely needed, and
  resolved scheduling/runtime values for hot-path scheduling and assignment.
- Inventory distinguishes `TaskShellRuntimeStore` as the kernel runtime port
  from `TaskShellStore` as the storage adapter surface, and states why TMC-2
  must retarget both together.
- Inventory distinguishes SDK public snapshots from starter/internal fat
  returns and server assembly compile surfaces.
- Inventory identifies test-only callers that may be rewritten with fixtures
  after production callers move.
- No code behavior changes are required in this slice.

## TMC-1 Introduce Narrow Task Shell Record

Goal: create the target shell/control-plane payload before moving ports.

Scope:

- Add a narrow task shell record in the kernel shell boundary, for example
  `TaskShellRecord` or an equivalent name chosen during implementation.
- Keep runtime work/result fields out of the record.
- Keep engine lifecycle methods out of the record.
- Add mapping helpers only at real owner boundaries: storage adapters,
  engine shell mutation, and SDK/server snapshot assembly.
- Decide whether existing task enums remain in `xa-mass-base` for now or move
  into the kernel shell boundary in a later slice.

Acceptance:

- The new shell record has no lifecycle mutation methods such as
  `transitionTo(...)` or `sealIntake()`.
- The new shell record has no runtime queue/lease/result fields.
- Unit tests cover normalization/copy behavior for maps and execution options
  if the record owns any defensive copying.
- Existing code still compiles with `Task` until port retargeting begins.

## TMC-2 Retarget Shell Storage And Kernel SPI

Goal: remove fat `Task` from shell storage contracts.

Scope:

- Change `TaskShellRuntimeStore` and `TaskShellRuntimeLifecycleQuery`, the
  engine/kernel runtime shell ports, to use the narrow shell record or a
  narrower bounded dispatch reference where the query is only a recovery
  signal.
- Change `TaskShellStore`, the storage adapter surface, and storage
  implementations to use the same narrow shell record in the same slice.
- If one adapter class implements both runtime SPI and storage API, keep a
  single mapping path and do not leave runtime SPI on one shape and storage API
  on another.
- Update memory/JDBC storage contract tests.
- Preserve bounded shell list/status/project/max-runtime queries only if they
  remain shell/control-plane reads.
- Keep `TaskWorkRuntime` and `TaskResultRuntime` unchanged except for caller
  mapping fallout.

Acceptance:

- No production storage or kernel shell port accepts or returns
  `com.xa.mass.base.model.Task`.
- `TaskShellRuntimeStore` and `TaskShellStore` expose the same shell-record
  truth; there is no old/new dual shell contract.
- Storage boundary guards reject runtime/history-shaped fields and reject
  `base.model.Task` on task shell store contracts.
- Storage contract tests pass for memory/JDBC task shell stores.
- Runtime contract tests for `TaskWorkRuntime` and `TaskResultRuntime` remain
  unchanged or only receive fixture updates.

## TMC-3 Move Engine Lifecycle Mutation Off The Base Model

Goal: make engine services the lifecycle mutation owner instead of shared model
methods.

Scope:

- Retarget `TaskManager`, `TaskLifecycleService`, `TaskStateResolver`,
  `TaskStateValidator`, and `TaskWorkerAssignListener` to the shell record and
  engine-owned mutation helpers.
- Retarget `TaskCommandPort`, `TaskStateRuntimePort`, and
  `TaskAssignmentRuntimePort` methods that currently accept whole `Task`
  objects for lifecycle/state/assignment writes.
- Remove or isolate direct dependence on `Task#transitionTo(...)`,
  `Task#sealIntake()`, and counter setters from production engine paths.
- Keep status transitions under task locks.
- Keep runtime stats as the source for progress and terminal convergence.
- Treat assignment writes separately: READY->RUNNING and
  `peakAssignedWorkerCount` are assignment owner writes, not generic shell
  mutation.

Acceptance:

- `ModelMutationGuardTest` or a replacement guard names the only allowed
  lifecycle mutation owners.
- Engine lifecycle tests still prove create, approve, reject, block, pause,
  resume, cancel, append, seal, progress, and terminal convergence.
- `TaskWorkRuntime.stats(taskId)` remains the progress/terminal evidence
  source.
- No new public wrapper layer is introduced just to forward to the same owner.

## TMC-4 Slim Runtime Hot-Path Task Context

Goal: stop passing a whole task shell where hot-path code needs only ids,
policy, runtime envelopes, or event context.

Scope:

- Review `TaskAssignWorker`, `TaskWorkerAssignListener`,
  `SimpleTaskDispatchBinder`, `TaskDispatchRequestService`, `TaskResultService`,
  and `TraceEventLogger` call paths.
- Replace whole-shell arguments with `taskId`, resolved scheduling policy,
  dispatch intent, runtime work, or result context where the full shell is not
  needed.
- Retarget `SchedulingPlaneResolver#resolve(Task)`,
  `TaskPolicyPresetResolver`, `TaskDispatchIntent#fromTask`,
  `ResolvedTaskSchedulingPolicy#from(Task, ...)`, and
  `TaskRuntimeProfileResolver#resolve(Task)` so raw shell fields are parsed at
  a boundary and hot paths consume resolved values.
- Retarget `TaskRuntimeRecoveryPort` and `TaskDispatchWakeupPort` so recovery
  and wakeup paths pass task ids, bounded dispatch signals, shell records, or
  resolved policy values instead of whole `Task` objects.
- Rename or isolate `RuntimeWorkSummary`, `RuntimeAttemptView`, and
  `TaskWorkLifecycleState` as result event/read-model vocabulary if they remain.
- Preserve result runtime repair, idempotent visible-final commit, and
  attempt/logical-final publish barriers.

Acceptance:

- Runtime work/result hot paths do not depend on a fat task shell for data that
  already exists in runtime values.
- Scheduling/assignment/dispatch hot paths do not re-read
  `contract`, `executionSpec`, `sharedConfig`, or `minRequiredWorkerCount` from
  a whole `Task` after the scheduling boundary has produced
  `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, and
  `ResolvedWorkerSchedulingPolicy`.
- Result runtime convergence tests still pass, including duplicate callback,
  visible-final repair, publish barrier repair, and progress barrier behavior.
- Trace/event vocabulary is documented as derived event/read-model vocabulary,
  not runtime truth.

## TMC-5 Retarget SDK, Server, And Test Surfaces

Goal: remove `base.model.Task` from embedded/public caller surfaces.

Scope:

- Keep `MassSdkApplication` public task reads on SDK snapshots; this surface is
  not the primary fat-model leak, but its internal storage conversion must move
  when shell storage moves.
- Change embedded engine/starter task command and query results that still
  return `Task`, including `MassEngine#createTaskShell` and engine command
  services, to snapshots, shell records, or intent-shaped outcomes according to
  caller ownership.
- Keep server HTTP `ApiTask*` contracts stable unless an explicit API change is
  reviewed.
- Update server assembly/storage wiring such as `XaMassServerApplication` with
  the shell storage contract; include startup/context proof when Spring wiring
  changes.
- Retarget server and SDK tests that construct `Task` as a fixture.
- Replace direct storage task mutation in tests with shell-record fixtures or
  engine task commands where the test is proving engine behavior.

Acceptance:

- SDK/starter main sources do not import `com.xa.mass.base.model.Task`.
- Public task create/query APIs return task snapshots or API DTOs, not mutable
  base/engine aggregates.
- Server assembly compiles against the new shell storage contract, and server
  HTTP task DTO contracts are unchanged unless explicitly reviewed.
- A guard mirrors the worker precedent by failing if public SDK/starter task
  surfaces expose `base.model.Task`.
- Server API contract tests still pass or are intentionally updated to the
  snapshot shape.

## TMC-6 Remove Fat Base Task Residue

Goal: retire the old fat model once all production callers have moved.

Scope:

- Delete `com.xa.mass.base.model.Task` when no production caller remains, or
  mark it as test/legacy residue only if deletion is blocked by a named test
  migration.
- Remove lifecycle mutation methods from shared model packages.
- Remove stale docs and examples that present `Task` as the runtime/kernel
  object.
- Add guards that reject new production imports of `base.model.Task`.
- Update `TASK_LIFECYCLE_BASELINE.md`, engine README, storage docs, SDK docs,
  and this roadmap/inventory from target-state to evidence-state wording.

Acceptance:

- `rg "com\\.xa\\.mass\\.base\\.model\\.Task"` has no production main-source
  hits, except an explicit allowlist if one is still justified.
- Architecture guards fail on new production `base.model.Task` imports.
- Roadmap residue scan finds no stale active docs describing `Task` as the
  runtime model.
- Roadmap completion criteria below are satisfied before archiving.

## Suggested Implementation Order

1. Finish TMC-0 field/caller classification.
2. Implement TMC-1 shell record with no port retargeting.
3. Retarget kernel SPI and storage contracts in TMC-2.
4. Move engine mutation in TMC-3.
5. Slim hot paths in TMC-4 only after shell mutation is stable.
6. Retarget SDK/server/test surfaces in TMC-5.
7. Remove `base.model.Task` residue and add broad guards in TMC-6.

## Verification Candidates

Correct exact commands during implementation if module names or suite names
change.

```bash
mvn -pl xa-mass-base,xa-mass-kernel-spi,platform_infra/mass-storage-api,platform_infra/mass-storage-memory,platform_infra/mass-storage-jdbc,xa-mass-engine -am test -Dtest=ModelMutationGuardTest,EngineProofOwnershipGuardTest,EngineKernelConvergenceArchitectureGuardTest,TaskKernelLifecycleTest,TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest
```

```bash
mvn -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am test -Dtest=TaskWorkRuntimeContractTest,TaskResultRuntimeContractTest
```

```bash
mvn -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=MassSdkTest,MassEngineAssemblyBoundaryTest,TaskApiControllerTest,TaskApiListControllerTest
```

Residue checks:

```bash
rg -n "com\\.xa\\.mass\\.base\\.model\\.Task|new Task\\(|Task#transitionTo|TaskShellStore.*Task|TaskShellRuntimeStore.*Task" xa-mass-base xa-mass-kernel-spi platform_infra xa-mass-engine xa-mass-server sdk
```

## Roadmap Completion Criteria

- No production shell storage or kernel shell SPI accepts or returns
  `base.model.Task`.
- No production SDK/starter public task surface exposes `base.model.Task`.
- Engine lifecycle mutation is owned by engine services/helpers and protected
  by guards.
- Runtime work/result truth remains in `TaskWorkRuntime` and
  `TaskResultRuntime`; no shell record absorbs queue, lease, retry, callback,
  result, or history truth.
- Result convergence, repair barriers, and terminal progress proofs still pass.
- Stale docs and tests that preserve `Task` as the mainline runtime model are
  removed or retargeted.
- A residue scan is run before this roadmap is marked complete or archived.

## Open Decisions

- Exact target name and package for the shell record:
  `TaskShellRecord`, `TaskShellState`, or another kernel-shell name.
- Whether `TaskStatus`, `TaskIntakeStatus`, `TaskTerminalReason`,
  `TaskContract`, and `TaskExecutionSpec` stay in `xa-mass-base` during the
  first implementation pass or move with the shell record later.
- Whether `taskTargetNumber` and `taskEligibleNumber` remain stored shell
  counters or become derived from task shell plus runtime ingest evidence.
- Whether `minRequiredWorkerCount` is still live scheduling policy input or
  historical residue.
- Whether `peakAssignedWorkerCount` remains a stored shell read-evidence field
  or moves to assignment diagnostics/trace.
- The exact narrow input type for `SchedulingPlaneResolver` after it no longer
  accepts `Task`: shell record, `TaskSchedulingInput`, or another boundary
  value.
