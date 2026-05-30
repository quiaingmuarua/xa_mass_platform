# Projection Infrastructure Retirement Roadmap

Status: ready for PIR-0 inventory. Do not execute deletion slices until PIR-0
classifies every remaining projection consumer. The prerequisite
`REVIEW_MATERIALIZATION_PIPELINE_ROADMAP.md` has landed: production
review/export writes now flow through the queued materializer, while
`TaskDetailStore` remains the temporary persistence backing.

This roadmap retires `TaskDetailStore`, `TaskMessageProjection`,
`TaskMessageAttemptProjection`, and `com.xa.mass.storage.api.projection.*`
from `mass-storage-api`. The target state is that no shared infrastructure
module carries projection row types or projection query/write methods.

This is a deletion roadmap, not the replacement pipeline roadmap. Build and
verify the server-owned review report queue and `TaskDetailStore`-backed
materializer in
[`REVIEW_MATERIALIZATION_PIPELINE_ROADMAP.md`](./REVIEW_MATERIALIZATION_PIPELINE_ROADMAP.md)
first.

This roadmap uses the task split below:

- **DB task**: every task has a persisted task shell and control-plane record.
  DB task state may lag runtime state and is not the scheduling, lease, retry,
  finality, or progress truth.
- **runtime task**: engine-owned current state for scheduling and lifecycle
  convergence. Runtime task state owns ready/delayed/lease/retry/result-apply
  counters and is not CRUD-shaped. Do not name this surface
  `RuntimeTaskShell`; runtime is not a second task-shell store.
- **review materialization**: server-local rows used for review/export field
  coverage. Review materialization is allowed to lag runtime and must never
  drive runtime decisions.

## RMP Handoff

RMP completed on 2026-05-29 with these proofs:

- server production review writes use `QueueBackedTaskReviewReadModelWriter`
- accepted and terminal report events are applied through
  `TaskDetailStoreReviewMaterializer`
- memory backing is proven through `InMemoryTaskShellStore`
- JDBC backing is proven through H2 `JdbcStorageRuntime.taskDetailStore()`
- architecture guards keep review queue/materializer types out of shared infra
  and engine production

Remaining PIR residue:

- `TaskDetailStore`
- `TaskDetailStore.TaskMessageProjection`
- `TaskDetailStore.TaskMessageAttemptProjection`
- `com.xa.mass.storage.api.projection.*`
- `InMemoryTaskShellStore implements TaskDetailStore`
- `JdbcTaskShellStore implements TaskDetailStore`
- `JdbcTaskCompatibilityProjection`
- SDK `taskDetailStore(...)` wiring
- projection-oriented tests and helpers listed in PIR-0 inventory scope

## Problem Statement

PBC removed engine production code's imports of projection types, but the
projection infrastructure itself remains intact in `mass-storage-api`:

- `TaskDetailStore` (10+ methods including real-time-style stats queries)
- `TaskMessageProjection` / `TaskMessageAttemptProjection` (inner records)
- `com.xa.mass.storage.api.projection.*` (4 status/reason enums)
- `InMemoryTaskShellStore implements TaskDetailStore` (mixed implementation)
- `JdbcTaskShellStore implements TaskDetailStore` (mixed implementation)
- `JdbcTaskCompatibilityProjection` (JDBC projection delegation)
- SDK `MassEngineBuilder.taskDetailStore()` / `EngineConfig.taskDetailStore`

The projection was hidden from engine, not removed from the platform. Server
writes the same rows through `TaskDetailStoreTaskReviewReadModel`, and stats
queries like `getTaskMessageStats` aggregate projection rows as if they were
real-time data. On high-volume production workloads this is both an ownership
problem and a truth-boundary problem: DB/review materialization rows can lag
runtime and must not become scheduling, finality, retry, lease, or progress
truth.

## Current Code Observations

- Engine production already uses `TaskWorkRuntime.stats(taskId)` ->
  `TaskWorkStats` for terminal policy, state resolution, task progress
  snapshots, and dispatch gate decisions. Engine does not call
  `TaskDetailStore.getTaskMessageStats` in production.
- `TaskWorkStats` already has `totalCount`, `successCount`, `failedCount`,
  `expiredCount`, `readyCount`, `inflightCount`, `delayedCount`,
  `processingCount()`, `pendingCount()`, `successRate()`, `failureRate()`.
  This covers runtime progress and terminal-policy needs. It is not a semantic
  replacement for review/export materialization coverage stats.
- Server review (`TaskReviewReadModel.loadStats`) reads from
  `TaskDetailStore.getTaskMessageStats` -- projection row aggregation. This
  must be split by meaning: runtime/progress stats should use runtime truth,
  while review/export materialization stats, if needed, must stay server-local
  and explicitly non-runtime.
- Server review items (`TaskReviewReadModel.loadItems`) reads from
  `TaskDetailStore.getTaskMessageProjections` -- these carry input, output,
  timing, error, retry fields. This is the only consumer that needs
  row-level projection data for review/export.
- `TaskDetailStoreTaskReviewReadModel` implements both read model and writer.
  The writer receives `recordItemsAccepted` and `recordWorkFinal` from
  server-side listeners. These events already carry all the fields needed
  for review row assembly.
- `InMemoryTaskShellStore` and `JdbcTaskShellStore` both
  `implements TaskDetailStore` alongside `TaskShellStore` and
  `TaskShellLifecycleQuery`. This forces task shell storage implementations
  to carry projection write/read/stats logic even though no engine
  production path uses it.
- SDK `MassEngineBuilder.taskDetailStore()` and
  `MassSdk.EngineOptions.taskDetailStore()` still accept a `TaskDetailStore`
  and wire it into `EngineConfig`. Engine does not consume it in production.
- `JdbcTaskCompatibilityProjection` is a JDBC helper class inside
  `mass-storage-jdbc` that `JdbcTaskShellStore` delegates projection
  operations to. It owns the SQL for projection CRUD and stats.
- Engine test residue (`TaskCompatibilityProjectionAccess`,
  `ProjectionAwareTaskManager`, `ProjectionTestSupport`,
  `CompatibilityProjectionAwait`, `TaskManagerLifecycleTest`,
  `SimpleTaskDispatchBinderTest`) still uses `TaskDetailStore` for
  secondary proof. These need a test-local replacement or retirement.

## Boundary Decision

Runtime progress stats belong to runtime queue state. `TaskWorkRuntime.stats()`
is the production truth surface for scheduling, terminal policy, progress, and
runtime console status.

Review/export item data is a server read-model concern. The server owns the
write path (via `TaskReviewReadModelWriter`) and the read path (via
`TaskReviewReadModel`). The storage backing for review rows should be
server-local, not shared infrastructure.

Review materialization stats may exist only as server-local read-model coverage
or export diagnostics. They must be named and documented as materialized-review
stats, not runtime progress stats, and they must not feed scheduling, lease,
retry, finality, or task progress decisions.

Message-level real-time projection is not a target. A live "query by
messageId" projection API has low runtime value and high boundary risk. Future
message-level operator needs should come from trace/archive read models or
queue/runtime monitoring counters, not from a shared storage projection row.

Task shell naming must stay DB/control-plane scoped. `TaskShellStore` means the
persisted DB task shell, not runtime current state. Runtime task state should be
named through `TaskWorkRuntime`, `TaskResultRuntime`, `TaskRuntimeView`,
`RuntimeTaskState`, or similar runtime-state/query language. Introducing
`RuntimeTaskShell` or `RuntimeTaskShellStore` would imply a second shell truth
and is out of bounds.

The target DB task-shell surface should eventually separate command/by-id
storage from broad query views:

```java
interface TaskShellStore {
    void saveTask(Task task);
    Optional<Task> getTask(String taskId);
    boolean updateTask(Task task);
    boolean deleteTask(String taskId);
}

interface TaskShellQueryStore {
    List<Task> listTasksPaged(int offset, int limit);
    List<Task> getTasksByStatus(TaskStatus status);
    List<Task> getTasksByProject(String project);
}

interface TaskShellLifecycleQuery {
    List<Task> pollTasksPastMaxRuntimeDeadline(LocalDateTime now, int limit);
}
```

This split is an adjacent task-shell boundary cleanup, not the core PIR
implementation. PIR should not broaden into task shell storage refactoring
unless a slice explicitly opts into that work. The rule still applies while PIR
runs: runtime dispatch recovery starts from `TaskWorkRuntime.readyTaskIds(...)`
and then performs bounded `TaskShellStore.getTask(...)` lookup; it must not use
DB task list/status/project queries as scheduling truth.

`TaskDetailStore` is not a legitimate storage abstraction. It is projection
residue that survived PBC because PBC only cut the engine->projection
dependency, not the projection->infrastructure dependency. It must be fully
removed from `mass-storage-api`.

## Non-Goals

1. No change to `TaskShellStore` or `TaskShellLifecycleQuery`. Those are
   legitimate control-plane contracts. This roadmap records the target
   command/query/lifecycle split, but it does not implement that split unless a
   later slice explicitly expands scope.
2. No change to `TaskWorkRuntime` or `TaskResultRuntime`. Those are already
   the runtime truth surface.
3. No trace/archive system design. If trace-derived review replaces
   server-local review storage later, that is a separate decision.
4. No console product redesign. The `TaskReviewReadModel` contract stays;
   only its backing implementation changes.
5. No engine scheduling or terminal policy changes. Engine already uses
   `TaskWorkStats` exclusively.
6. No rule-domain work. `RuleStorage` is not affected.

## Hard Rules

1. `mass-storage-api` must not define, carry, or expose projection row types,
   projection query methods, or projection stats methods after this roadmap
   completes.
2. `TaskShellStore` implementations (`InMemoryTaskShellStore`,
   `JdbcTaskShellStore`) must not `implements TaskDetailStore` after this
   roadmap completes.
3. SDK public API must not accept or expose `TaskDetailStore` after this
   roadmap completes.
4. Stats must be named by truth source:
   - runtime/progress stats come from runtime queue state
   - review/export materialization stats come from server-local read-model
     storage and are explicitly non-runtime
   - projection row aggregation must not be presented as real-time progress
     truth
5. This roadmap must not introduce a message-level real-time projection API.
   Future message-level inspection belongs to trace/archive read models or
   queue/runtime monitoring, not shared storage projection reads.
6. This roadmap must not introduce `RuntimeTaskShell`,
   `RuntimeTaskShellStore`, or any equivalent second task-shell store. Runtime
   task state must use runtime-state vocabulary and owners.

## Slice PIR-0: Inventory Remaining Consumers

Goal: identify every remaining production and test consumer of
`TaskDetailStore`, its inner types, and `com.xa.mass.storage.api.projection.*`.

Scope:

1. Classify every `TaskDetailStore` caller as:
   - server review read-model implementation (production)
   - server wiring/bean creation (production)
   - SDK builder/config wiring (production)
   - storage implementation (production)
   - engine test residue (test-only)
   - storage contract test (test-only)
   - testing framework helper (test-only)
2. For each production caller, identify the replacement path:
   - runtime/progress stats callers -> `TaskWorkRuntime.stats()` through a
     narrow query port
   - review/export materialization stats callers -> server-local review store,
     clearly marked non-runtime
   - review item callers -> server-local review store
   - wiring callers -> removal or server-local creation
3. For each test caller, classify whether it should:
   - migrate to runtime-based assertions
   - migrate to server-local review store test support
   - be retired as PBC residue
4. Identify the JDBC projection tables and their current DDL owner.
5. Identify any production path outside server that reads or writes
   `TaskDetailStore` projection rows.
6. Record current `TaskShellStore` query-method consumers separately so PIR
   does not accidentally treat DB task shell list/status/project queries as
   runtime task APIs. If a follow-up is needed, it belongs to a task-shell
   command/query split, not to projection retirement.

Acceptance:

1. Every consumer of `TaskDetailStore` has one classification and one
   replacement decision.
2. No unclassified production dependency exists.
3. JDBC projection table ownership is documented.
4. The inventory explicitly states that `TaskShellStore` is DB/control-plane
   shell storage and that runtime task state is not named or modeled as a
   runtime task shell.

## Slice PIR-1: Separate Runtime Progress From Review Materialization Stats

Goal: stop projection row aggregation from pretending to be runtime progress
truth while preserving explicitly named, server-local review materialization
coverage if review/export still needs it.

Stats query port decision: the server review read-model must not directly
import or call `TaskWorkRuntime`. Instead, expose a narrow SDK-level query
port (e.g. `TaskQueryOperations.getTaskWorkStats(taskId)` or a dedicated
`TaskReviewStatsSource` interface) that returns `TaskWorkStats`. The server
review implementation calls this port; the SDK/engine wires the port to
`TaskWorkRuntime.stats()` internally. This keeps the server -> engine
dependency bounded to a query contract, not an internal runtime type.

Do not use a listener/counter approach for runtime progress stats. Runtime
progress must come from queue/runtime truth, not an eventually-consistent
counter that drifts from queue state.

Do not create a message-level real-time projection query as part of this slice.
The only live runtime stats surface should be aggregate runtime state needed for
progress/monitoring. Message-level detail should stay in server-local review
materialization for review/export or move later to trace/archive.

Scope:

1. Define or extend a narrow stats query port in the SDK query surface
   (e.g. on `TaskQueryOperations`) that returns `TaskWorkStats` for a
   given `taskId`. Engine wires this to `TaskWorkRuntime.stats()`. This port is
   for runtime progress/monitoring only.
2. `TaskReviewReadModel.loadStats()` is split or redefined so runtime progress
   fields come from the stats query port, while any review/export coverage
   fields come from the server-local review store and are labeled as
   materialization stats.
3. Remove `TaskMessageStats` and `TaskMessageAttemptStats` from
   `TaskDetailStore` after all consumers are migrated.
4. If any consumer needs stats that `TaskWorkStats` does not cover (e.g.
   attempt-level active/running/failed counts), first classify whether that is
   runtime progress, review materialization coverage, or trace/archive
   analytics. Only runtime-progress gaps may extend `TaskWorkStats` or runtime
   queries. Do not keep projection row aggregation as a runtime fallback.
5. Update review/console tests so runtime progress assertions use
   runtime-sourced stats, while review/export materialization assertions stay
   explicitly read-model scoped.

Acceptance:

1. No production code calls `TaskDetailStore.getTaskMessageStats` or
   `TaskDetailStore.getTaskMessageAttemptStats`.
2. `TaskMessageStats` and `TaskMessageAttemptStats` classes are deleted from
   `TaskDetailStore`.
3. Stats query port exists in SDK query surface; server review calls it.
4. Server review does not import `TaskWorkRuntime` directly.
5. Review stats tests distinguish runtime progress assertions from
   materialization coverage assertions.
6. No message-level real-time projection query is introduced.

## Slice PIR-2: Server Review Storage Localization

Goal: move review item storage from shared `TaskDetailStore` to a
server-local review store.

Module boundary rule: all review store record types, interfaces, in-memory
implementations, and JDBC implementations must live in `xa-mass-server` (or
a future server-owned module). They must not live in any `platform_infra/`
module (`mass-storage-api`, `mass-storage-memory`, `mass-storage-jdbc`,
`mass-runtime-*`). Moving projection code from one shared infra package to
another shared infra package does not satisfy this slice.

Scope:

1. Create a server-local review store interface and implementation that owns
   the review projection row lifecycle (write on ingress/finality, read for
   preview/export). This replaces `TaskDetailStoreTaskReviewReadModel`'s
   delegation to `TaskDetailStore`.
2. For in-memory mode: server creates its own review store instance, not
   shared with `InMemoryTaskShellStore`.
3. For JDBC mode: server owns its own review projection JDBC helper inside
   `xa-mass-server`. The underlying tables may remain the same initially,
   but the JDBC helper, SQL, and DDL/schema initialization are server-owned.
   The server-local JDBC review store must own its own schema initialization
   and migration so that removing `JdbcTaskCompatibilityProjection` from
   `mass-storage-jdbc` in PIR-4 does not break review persistence or server
   startup.
4. The server-local review store uses server-owned record types (which can
   start as copies of `TaskMessageProjection` / `TaskMessageAttemptProjection`
   without the storage-api dependency). These become the
   `TaskReviewReadModel` implementation detail.
5. `TaskDetailStoreTaskReviewReadModel` is deleted or replaced by the new
   server-local implementation.
6. Server wiring (`XaMassServerApplication`) creates the review store
   directly without requesting `TaskDetailStore` from engine config.
7. The server-local review store is documented as review/export
   materialization only. It must not provide scheduling, lease, retry,
   finality, or runtime progress truth, and it must not expose a live
   message-level real-time query API.

Acceptance:

1. Server review read/write does not import `TaskDetailStore` or
   `com.xa.mass.storage.api.projection.*`.
2. `TaskReviewReadModel` and `TaskReviewReadModelWriter` still work with the
   same field coverage.
3. In-memory and JDBC review store paths both work.
4. Review/export e2e tests pass through the new owner.
5. All review store types (records, interfaces, JDBC helpers) are inside
   `xa-mass-server`, not in any `platform_infra/` module.
6. Server-local JDBC review store owns its own schema/table initialization;
   it does not depend on `mass-storage-jdbc` projection helpers for DDL.
7. Review store APIs are review/export materialization APIs only; no caller
   can use them as runtime task truth.

## Slice PIR-3: SDK `taskDetailStore()` Removal

Goal: remove `TaskDetailStore` from public SDK API.

Scope:

1. Remove `MassEngineBuilder.taskDetailStore(TaskDetailStore)`.
2. Remove `MassSdk.EngineOptions.taskDetailStore(TaskDetailStore)`.
3. Remove `MassApplicationBuilder.EngineBuilder.taskDetailStore(TaskDetailStore)`.
4. Remove `EngineConfig.taskDetailStore` field, getter, and setter.
5. If any SDK test or server wiring still needs a `TaskDetailStore` reference
   for non-engine purposes, that reference must come from a server-local
   factory, not from SDK/engine config.

Acceptance:

1. SDK public API has no `TaskDetailStore` parameter or method.
2. `EngineConfig` has no `taskDetailStore` field.
3. SDK compiles and engine starts without `TaskDetailStore` wiring.

## Slice PIR-4: `TaskDetailStore` Deletion From Shared Infra

Goal: remove `TaskDetailStore` and all projection types from
`mass-storage-api`.

Scope:

1. Delete `TaskDetailStore` interface from `mass-storage-api`.
2. Delete `com.xa.mass.storage.api.projection.*` (4 enum files).
3. Remove `implements TaskDetailStore` from `InMemoryTaskShellStore`.
   Delete all projection CRUD/stats methods from that class.
4. Remove `implements TaskDetailStore` from `JdbcTaskShellStore`.
   Delete all projection delegation methods from that class.
5. Delete `JdbcTaskCompatibilityProjection` from `mass-storage-jdbc`. PIR-2
   must have already created a server-local JDBC review helper that owns its
   own SQL and DDL. If any SQL is still shared, that is a PIR-2 acceptance
   gap, not a reason to keep the shared infra helper.
6. Delete `TaskDetailStoreContractTest` from `mass-storage-api` tests.
7. Migrate or retire engine test residue:
   - `TaskCompatibilityProjectionAccess` -> retire or convert to
     server-local test support
   - `ProjectionAwareTaskManager` -> retire
   - `ProjectionTestSupport` -> retire
   - `CompatibilityProjectionAwait` -> retire
   - Update `TaskManagerLifecycleTest` / `SimpleTaskDispatchBinderTest` to
     use runtime-based assertions if they still need projection-like checks
8. Migrate or retire testing framework helpers:
   - `ProjectionTestViews` in `xa-mass-testing` -> retire or convert
   - `ChaosRuntimeHarness` projection usage -> runtime stats

Acceptance:

1. `mass-storage-api` has no `TaskDetailStore` interface, no inner
   projection records, no projection enums.
2. `InMemoryTaskShellStore` implements only `TaskShellStore` and
   `TaskShellLifecycleQuery`.
3. `JdbcTaskShellStore` implements only `TaskShellStore` and
   `TaskShellLifecycleQuery`.
4. `com.xa.mass.storage.api.projection` package does not exist.
5. `JdbcTaskCompatibilityProjection` does not exist in `mass-storage-jdbc`.
6. All engine tests compile and pass.
7. All server tests compile and pass.
8. Server JDBC boot E2E (review write + read + stats) passes without any
   shared infra projection helper.

## Slice PIR-5: Architecture Guard And Baseline Update

Goal: prevent projection infrastructure from returning to shared infra, and
update owning baseline docs so the next reader does not re-derive the
deleted contracts.

Scope:

1. Add or extend architecture guards:
   - `mass-storage-api` must not contain classes or interfaces with
     `Projection` in the name or in the `projection` package
   - `TaskShellStore` implementations must not carry message/attempt
     CRUD or stats methods
   - runtime code must not introduce `RuntimeTaskShell` or
     `RuntimeTaskShellStore` naming
   - DB task list/status/project queries must not be used as scheduling,
     lease, retry, finality, or runtime progress truth
   - server-local review stores must not expose scheduling, lease, retry,
     finality, dispatch, or runtime-progress method names
   - no shared module exposes a live message-level projection lookup
   - engine production must not import any server-local review store type
   - SDK public API must not accept review/projection store parameters
2. Update `EngineProofOwnershipGuardTest` allowlist to remove
   `TaskDetailStore` from the known storage imports (it should now fail
   if anyone adds it back).
3. Add a `StorageBoundaryGuardTest` check that `mass-storage-api` public
   types do not include projection row types or projection stats.
4. Update global baseline docs that still reference `TaskDetailStore`,
   projection residue, or the old projection boundary:
   - `doc/INFRA_TRUTH_LAYERS.md`: remove or update sections describing
     `TaskDetailStore` as a storage layer or projection boundary
   - `doc/DB_STORAGE_PRINCIPLES.md`: remove or update sections describing
     projection table ownership in shared infra
   - `doc/STATE_MACHINE_BASELINE.md`: remove or update sections describing
     projection-based state visibility
   - `platform_infra/README.md`: update if it describes `TaskDetailStore`
     as a provided interface
   - `xa-mass-engine/README.md`: update if PBC archive references still
     describe `TaskDetailStore` as living in shared infra
5. Verify that no active baseline doc describes `TaskDetailStore` or
   `com.xa.mass.storage.api.projection.*` as a current contract.

Acceptance:

1. Guard fails if `TaskDetailStore` or any projection type reappears in
   `mass-storage-api`.
2. Guard fails if `TaskShellStore` implementations grow projection methods.
3. Guard fails if SDK public API exposes review/projection store wiring.
4. `doc/INFRA_TRUTH_LAYERS.md`, `doc/DB_STORAGE_PRINCIPLES.md`,
   `doc/STATE_MACHINE_BASELINE.md` do not describe `TaskDetailStore` or
   projection residue as current contracts.
5. No active baseline doc references `TaskDetailStore` as a living
   shared infra interface.
6. Guard or source scan fails if a shared module reintroduces message-level
   real-time projection lookup APIs.
7. Guard or source scan fails if runtime code introduces runtime task-shell
   naming or routes dispatch recovery through DB task list/status/project
   queries.

## Implementation Order

```text
PIR-0 -> PIR-1 -> PIR-2 -> PIR-3 -> PIR-4 -> PIR-5
```

PIR-1 (stats) and PIR-2 (review storage) can run in parallel after PIR-0
lands, because they address independent consumer categories. PIR-3 and PIR-4
depend on PIR-2 completing first -- SDK config removal and interface deletion
require all production callers to have migrated.

PIR-1 is the highest-value quick win: it stops projection row aggregation
from pretending to be real-time stats on production workloads.

## Verification Candidates

```powershell
mvn -pl platform_infra/mass-storage-api -am test
```

```powershell
mvn -pl xa-mass-server -am '-Dtest=TaskApiControllerTest,ControlConsoleRoutingIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-engine -am '-Dtest=EngineProofOwnershipGuardTest,EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The exact test list should be corrected in PIR-0 after the full consumer
inventory is complete.
