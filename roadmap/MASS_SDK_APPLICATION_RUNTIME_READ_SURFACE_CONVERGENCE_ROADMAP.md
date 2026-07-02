# Mass SDK Application Runtime Read Surface Convergence Roadmap

Status: superseded on 2026-07-02 by
`TASK_RUNTIME_API_BOUNDARY_CONVERGENCE_ROADMAP.md` TRAPI-5.

Supersession note: the external SDK/server read surface converged on
SDK-owned `TaskReadOperations`, not a separate runtime-read-only SDK surface.
Current facts and guard evidence live in TRAPI-5 and
`sdk/xa-mass-embedded-sdk/README.md`. Do not execute this roadmap as a
parallel track.

This roadmap converges the read-only task-runtime views exposed by
`MassSdkApplication` so embedded SDK callers read the selected task-runtime
backend through a read-only runtime access path instead of through the current
engine-starter method chain.

The goal is not to move lifecycle or mutation ownership into the SDK. The goal
is to remove pure result/progress/diagnostic reads from the engine API surface
and from `TaskRuntimeServingLane` caller indirection while keeping
`xa-mass-task-runtime` as the runtime truth owner.

Read with:

- [TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md)
- [ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md](ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md)
- [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md)
- [INFRA_TRUTH_LAYERS.md](../doc/INFRA_TRUTH_LAYERS.md)
- [sdk/xa-mass-embedded-sdk/README.md](../sdk/xa-mass-embedded-sdk/README.md)

## Current Code Observations

- `MassSdkApplication#readTaskResults` and
  `MassSdkApplication#getTaskWorkFinal` delegate to `MassApplication`.
- `MassSdkApplication#getTaskResultArchiveManifest` and
  `MassSdkApplication#writeTaskResultArchiveContent` also read final result
  data through `MassApplication`.
- `DefaultTaskDiagnosticOperations#getTaskWorkStats` and
  `DefaultTaskDiagnosticOperations#getActiveLeases` delegate to
  `MassApplication`.
- `MassApplication` forwards these read calls to `MassEngine`.
- `MassEngine` forwards them to `EngineConfig`.
- `EngineConfig` reads through `TaskRuntimeServingLane`, then maps
  `FinalResultRow`, `FinalResultWindow`, `TaskRuntimeProgressSnapshot`, and
  `ActiveLeaseRepairCandidate` into SDK snapshot DTOs.
- `TaskRuntimePortSet` currently exposes append, scheduler, claim, result,
  repair, progress, read, and discard ports through one `runtime()` handle.
  That is too wide for an embedded SDK read surface.
- `doc/TASK_LIFECYCLE_BASELINE.md` already defines
  `TaskRuntimeReadPort` as the runtime-owned public result read truth.
- `doc/INFRA_TRUTH_LAYERS.md` already places runtime result reads in
  runtime state, not in server review rows or JDBC materialized views.

## Owner Review

`xa-mass-task-runtime` owns accepted work, final result rows, progress counters,
active work evidence, idempotent result convergence, and the memory or Redis
backend implementation selected by `TaskRuntimeStarter`.

`sdk/xa-mass-embedded-sdk` owns public embedded SDK snapshots, archive streaming
format, caller ergonomics, and validation of public SDK query arguments.

`xa-mass-engine` and `xa-mass-engine-starter` own lifecycle orchestration,
assignment, result ingest, runtime loops, and engine assembly. They should not
own SDK snapshot projection for pure final-result or runtime-diagnostic reads.

`TaskRuntimeServingLane` may remain the engine-facing mutation/lifecycle lane
for append, dispatch discovery, claim, result ingest, lease repair, discard,
and terminal convergence. It should not be the SDK public read facade.

Embedded SDK direct read means reading the selected memory or Redis task-runtime
backend through a read-only runtime contract from the same started runtime
handle. It does not mean parsing Redis keys or memory internals directly from
SDK code.

## Surface Inventory

| Surface | Current Path | Classification | Target |
| --- | --- | --- | --- |
| `MassSdkApplication#readTaskResults` | SDK -> `MassApplication` -> `MassEngine` -> `EngineConfig` -> `TaskRuntimeServingLane` | runtime final read | SDK reads `TaskRuntimeReadPort` and maps SDK snapshot locally |
| `MassSdkApplication#getTaskWorkFinal` | same chain | runtime final read | SDK reads `TaskRuntimeReadPort#getFinalResultByMessageId` and maps SDK snapshot locally |
| `MassSdkApplication#getTaskResultArchiveManifest` | task shell detail plus engine-starter result count | mixed shell state plus runtime count | shell readiness remains a task query; result count comes from SDK runtime read access |
| `MassSdkApplication#writeTaskResultArchiveContent` | SDK loops through `MassApplication#readTaskResults` | runtime final read plus SDK archive format | SDK streams windows from runtime read access and owns archive DTO mapping |
| `DefaultTaskDiagnosticOperations#getTaskWorkStats` | SDK diagnostics -> `MassApplication` -> engine-starter | runtime progress diagnostics | SDK diagnostics reads `TaskRuntimeProgressPort` through read-only access |
| `DefaultTaskDiagnosticOperations#getActiveLeases` | SDK diagnostics -> `MassApplication` -> engine-starter | runtime active-work diagnostics | SDK diagnostics reads a read-only runtime diagnostics view; keep diagnostic, not lifecycle truth |
| `DefaultTaskDiagnosticOperations#validateTaskState` | SDK diagnostics -> engine task query | engine lifecycle query | out of this roadmap |
| `DefaultTaskDiagnosticOperations#resolveTaskState` | SDK diagnostics -> engine task query | engine lifecycle query | out of this roadmap |
| `TaskQueryOperations` task detail/list/state/access reads | SDK -> `MassApplication` task shell/query path | task shell/control-plane view | out of this roadmap unless a later task-shell owner roadmap starts |

## Target Shape

The SDK should have one package-private runtime read access point whose fields
are read-only and selected from the already-started runtime backend:

- result read: `TaskRuntimeReadPort`
- progress read: `TaskRuntimeProgressPort`
- active-work diagnostics read: a narrowed diagnostics/read view, not the full
  mutation-capable `TaskRuntimeRepairPort` unless no narrower contract exists
  in the first slice
- backend kind: `TaskRuntimeBackendKind` for diagnostics only

The access point may live in `xa-mass-task-runtime-starter-sdk` or be exposed
by the starter assembly, but `sdk/xa-mass-embedded-sdk` must not receive
`TaskRuntimePortSet` as a mutation-capable object.

SDK snapshot mapping should move out of `EngineConfig` into package-private
SDK code, for example:

- `FinalResultWindow` -> `TaskResultWindowSnapshot`
- `FinalResultRow` -> `TaskResultItemSnapshot`
- `FinalResultRow` -> `TaskWorkFinalSnapshot`
- `TaskRuntimeProgressSnapshot` -> `TaskWorkStatsSnapshot`
- active-work diagnostic row -> `TaskActiveLeaseSnapshot`

## Non-Goals

- Do not change append, claim, result ingest, lease repair, discard, terminal,
  or scheduler behavior.
- Do not remove or redesign `TaskRuntimeServingLane` for engine mutation and
  lifecycle paths.
- Do not expose `TaskRuntimePortSet` or mutation ports to embedded SDK query
  code.
- Do not make SDK parse Redis key names, Redis value structs, memory maps, or
  backend-private runtime records.
- Do not reintroduce `TaskShellStore` or storage shell query exposure.
- Do not change public SDK method signatures in `TaskResultQueryOperations` or
  `TaskDiagnosticOperations`.
- Do not change server HTTP auth, server review rows, durable result archival,
  or result retention policy.

## Executable Slices

### MSRR-0: Inventory The Read Surfaces

Goal: separate pure runtime reads from engine lifecycle queries and task shell
queries before implementation starts.

Scope:

- Inventory `MassSdkApplication`, `DefaultTaskDiagnosticOperations`,
  `MassApplication`, `MassEngine`, and `EngineConfig` methods that read result,
  progress, active lease, task shell, or engine lifecycle state.
- Classify each method as runtime result truth, runtime diagnostics, engine
  lifecycle query, task shell/control-plane view, compatibility residue, or
  public SDK contract.
- Record which methods must keep public SDK signatures unchanged.

Acceptance:

- A short inventory table exists in this roadmap or a sibling inventory.
- Runtime result reads and runtime diagnostics have an explicit target access
  path.
- Task shell reads and engine lifecycle validation/resolution are explicitly
  out of the current slice.

Verification:

- Source search proves every current read pass-through is classified:
  `readTaskResults`, `getVisibleTaskResultByMessageId`,
  `countVisibleTaskResults`, `getTaskWorkStats`, and `getActiveLeases`.

### MSRR-1: Introduce Narrow Runtime Read Access

Goal: provide embedded SDK code a read-only view of the selected runtime backend
without exposing mutation ports.

Scope:

- Add or expose a read-only access contract for the started task runtime.
- Ensure the access uses the same memory or Redis runtime selected by
  `TaskRuntimeStarter` / engine-starter assembly.
- Do not let SDK query code receive `TaskRuntimePortSet` or full
  `TaskRuntimeHandle#runtime()`.

Acceptance:

- SDK query code can obtain `TaskRuntimeReadPort` and progress/diagnostic read
  capability without calling engine-starter result read methods.
- Mutation ports such as append, claim, result apply, scheduler, and discard
  are not visible to SDK read implementations.
- Backend kind is available only as diagnostics or proof evidence; SDK behavior
  does not branch on Redis key internals.

Verification:

- Compile `sdk/xa-mass-embedded-sdk` with `-am`.
- Add or update a source-level guard that blocks SDK main code from importing
  task-runtime mutation ports.

### MSRR-2: Move Final Result Reads Into Embedded SDK

Goal: make public SDK final-result methods read runtime rows directly and map
SDK snapshots locally.

Scope:

- Retarget `MassSdkApplication#readTaskResults`.
- Retarget `MassSdkApplication#getTaskWorkFinal`.
- Retarget result archive streaming to read runtime windows directly.
- Keep archive content format and public snapshot DTOs unchanged.
- Remove now-unused final-result read pass-through methods from
  `MassApplication`, `MassEngine`, and `EngineConfig` when no production caller
  remains.

Acceptance:

- `MassSdkApplication` result reads no longer call `MassApplication` result
  read/count methods.
- `EngineConfig` no longer maps `FinalResultRow` or `FinalResultWindow` into
  SDK result snapshots for public SDK reads.
- Public SDK return types and behavior remain unchanged for memory and Redis
  runtime backends.

Verification:

- Focused SDK tests for `readTaskResults`, `getTaskWorkFinal`, and archive
  streaming.
- Existing memory-local and Redis task-runtime result E2E tests still pass.

### MSRR-3: Move Runtime Diagnostics Reads Into Embedded SDK

Goal: make runtime diagnostic SDK methods read runtime-owned diagnostic data
without routing through engine-starter public methods.

Scope:

- Retarget `DefaultTaskDiagnosticOperations#getTaskWorkStats` to runtime
  progress read access.
- Retarget `DefaultTaskDiagnosticOperations#getActiveLeases` to a read-only
  active-work diagnostics access.
- Keep `validateTaskState` and `resolveTaskState` on the engine lifecycle
  query path.

Acceptance:

- `TaskWorkStatsSnapshot` mapping lives in embedded SDK code.
- Active lease snapshots are documented as diagnostics, not lifecycle truth.
- Engine-starter no longer exports SDK diagnostic snapshot DTO projection for
  these pure runtime diagnostic reads.

Verification:

- Focused diagnostic tests cover empty, active, delayed, final, and active-work
  evidence states where existing fixtures allow it.
- Source search shows no remaining `getTaskWorkStats` or `getActiveLeases`
  public pass-through from `MassApplication` through `MassEngine` into
  `EngineConfig`, unless an inventory row explicitly keeps a non-SDK caller.

### MSRR-4: Close Pass-Through Residue And Add Guards

Goal: prevent the old engine-starter read facade from returning under a
different name.

Scope:

- Delete unused read pass-through methods from `MassApplication`, `MassEngine`,
  and `EngineConfig`.
- Remove SDK snapshot imports from `xa-mass-engine-starter` that only existed
  for public SDK result/progress/active-lease reads.
- Add stable guards against:
  - SDK query code importing task-runtime mutation ports.
  - SDK query code importing backend-private Redis or memory implementation
    classes.
  - engine-starter main code importing SDK result/diagnostic snapshot DTOs for
    runtime read projection.
  - re-adding `readTaskResults`, `getVisibleTaskResultByMessageId`,
    `countVisibleTaskResults`, `getTaskWorkStats`, or `getActiveLeases` as
    engine-starter public read pass-throughs after their callers are removed.

Acceptance:

- The only public read DTO mapping for result/progress/active-work SDK views is
  in embedded SDK code.
- Engine-starter retains mutation/lifecycle runtime assembly only.
- Negative guards fail on the old pass-through shape.

Verification:

- Source-level guard test or existing architecture guard suite.
- `mvn -pl sdk/xa-mass-embedded-sdk -am -DskipTests compile`
- Focused SDK result/diagnostic test suite.

## Suggested Implementation Order

1. Land MSRR-0 inventory before changing code.
2. Add narrow read access in MSRR-1 and prove it compiles without exposing
   mutation ports.
3. Move final result reads first, because they are the public runtime-read
   truth and have the clearest source-of-truth boundary.
4. Move runtime diagnostics reads second, because active lease visibility is
   diagnostic and should not drive lifecycle behavior.
5. Delete pass-through residue only after all production callers have moved.
6. Add guards after the target read path is stable enough to freeze.

## Do Not Start With

- Do not start by deleting `TaskRuntimeServingLane`; it still owns engine
  mutation and lifecycle integration.
- Do not start by exposing `TaskRuntimePortSet` to SDK code; that makes the
  read cleanup a mutation surface leak.
- Do not start by adding a `TaskRuntimeReadBridge` that only forwards to
  `EngineConfig`; that preserves the same owner problem with a new name.
- Do not start by reading Redis keys from SDK code; that duplicates runtime
  backend ownership and will drift from memory runtime behavior.
- Do not mix this with `TaskShellStore`, task shell storage reads, server review
  rows, result archive durability, or append/claim/result mutation interfaces.

## Roadmap Completion Criteria

This roadmap is complete only when all are true:

- `MassSdkApplication` and `DefaultTaskDiagnosticOperations` implement runtime
  result/progress/active-work read views through read-only runtime access.
- Public SDK result and diagnostic method signatures remain unchanged.
- Memory and Redis runtime backends both satisfy the same SDK read behavior.
- Engine-starter no longer owns SDK snapshot projection for final results,
  progress stats, or active lease diagnostics.
- No production caller uses engine-starter public methods for pure runtime
  result/progress/active-work reads.
- Guards prevent mutation-port exposure to SDK read code and prevent the old
  engine-starter read facade from returning.
- Owning docs are updated if implementation changes public SDK dependency rules
  or runtime-read assembly facts.

## Open Decisions

- Decide whether the narrow read access contract belongs directly on
  `TaskRuntimeHandle`, beside `TaskRuntimePortSet`, or as a package-private
  starter/SDK access object. The selected option must avoid exposing mutation
  ports to SDK query code.
- Decide whether active-work diagnostics should get a narrower read-only port
  than `TaskRuntimeRepairPort`. If not done in the first slice, it must remain
  inventoried as a diagnostic-only exception.
- Decide whether task shell read-only SDK views deserve a separate roadmap.
  They should not be hidden inside this runtime-read convergence.
