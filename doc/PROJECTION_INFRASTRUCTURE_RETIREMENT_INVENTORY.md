# Projection Infrastructure Retirement Inventory

Status: PIR-0 inventory complete as of 2026-05-30. PIR-2 server review
storage localization, PIR-3 SDK `taskDetailStore(...)` removal, PIR-4
shared-infra deletion, and PIR-5 guard/baseline updates have since been
implemented in the current worktree. This file keeps the original PIR-0
classifications and records post-slice deltas below.

Authoritative scan:

```powershell
rg "TaskDetailStore|TaskMessageProjection|TaskMessageAttemptProjection|TaskMessageStats|TaskMessageAttemptStats|com\.xa\.mass\.storage\.api\.projection|taskDetailStore\(" -l -g "*.java" platform_infra xa-mass-server xa-mass-sdk xa-mass-engine xa-mass-testing integrations transport
```

## Summary Decision

`TaskDetailStore` was shared-infra projection residue, not a legitimate storage
owner. Server review no longer depends on it after PIR-2, SDK public wiring no
longer exposes it after PIR-3, and shared storage/test-framework residue has
been migrated or retired by PIR-4.

Deletion is complete in the current worktree: `mass-storage-api` no longer
defines `TaskDetailStore`, projection row records, or projection enums.

The JDBC path is weaker than the previous roadmap text implied:
`JdbcTaskCompatibilityProjection` is process-local in-memory state inside
`mass-storage-jdbc`; it does not own durable JDBC projection tables or DDL.
Therefore PIR-2 must create a real server-owned review store and, for JDBC
mode, server-owned schema/migration support if durable review rows are required.

## Post-PIR-2/PIR-3 Delta

Implemented replacement:

- `TaskReviewStore`
- `InMemoryTaskReviewStore`
- `JdbcTaskReviewStore`
- `TaskReviewStoreMaterializer`
- `TaskReviewStoreTaskReviewReadModel`

Server production no longer imports `TaskDetailStore`,
`com.xa.mass.storage.api.projection.*`, `TaskDetailStoreReviewMaterializer`, or
`TaskDetailStoreTaskReviewReadModel`.

`XaMassServerApplication` now creates server-local review stores directly:

- memory mode -> `InMemoryTaskReviewStore`
- JDBC mode -> `JdbcTaskReviewStore`

`JdbcTaskReviewStore` initializes server-owned review tables:

- `xa_task_review_item`
- `xa_task_review_attempt`

PIR-3 removed SDK public/config wiring:

- `MassEngineBuilder.taskDetailStore(TaskDetailStore)`
- `MassSdk.EngineOptions.taskDetailStore(TaskDetailStore)`
- `MassApplicationBuilder.EngineBuilder.taskDetailStore(TaskDetailStore)`
- `EngineConfig.taskDetailStore` field, getter, setter, and copy/default wiring

Production residues after PIR-3, retired by PIR-4:

- `TaskDetailStore` and projection enums in `mass-storage-api`
- `InMemoryTaskShellStore implements TaskDetailStore`
- `JdbcTaskShellStore implements TaskDetailStore`
- `JdbcTaskCompatibilityProjection`
- testing framework main-source callers under `xa-mass-testing`

## Post-PIR-4/PIR-5 Delta

Deleted shared-infra contracts and helpers:

- `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskDetailStore.java`
- `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/projection/*`
- `platform_infra/mass-storage-jdbc/src/main/java/com/xa/mass/storage/jdbc/JdbcTaskCompatibilityProjection.java`
- `platform_infra/mass-storage-api/src/test/java/com/xa/mass/storage/contract/TaskDetailStoreContractTest.java`
- `platform_infra/mass-storage-memory/src/test/java/com/xa/mass/storage/memory/InMemoryTaskDetailStoreContractTest.java`
- `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcH2TaskDetailStoreContractTest.java`
- `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcPostgresTaskDetailStoreContractTest.java`

Updated storage implementations:

- `InMemoryTaskShellStore` implements only task-shell storage/query contracts.
- `JdbcTaskShellStore` implements only task-shell storage/query contracts.
- `JdbcStorageRuntime` no longer exposes `taskDetailStore()`.

Retired engine/test-framework residue:

- `ProjectionAwareTaskManager`
- `ProjectionTestSupport`
- `CompatibilityProjectionAwait`
- `TaskCompatibilityProjectionAccess`
- `EngineProjectionResidueSuite`
- `ProjectionTestViews`
- `CompatibilityMessageView`
- `CompatibilityMessageSnapshot`
- `CompatibilityAttemptView`

Migrated proof surfaces:

- engine tests now assert runtime/result behavior directly
- transport result-ingest tests no longer read projection rows
- Redis runtime/trace integration tests use runtime and trace proof surfaces
- chaos/testing snapshots expose `reviewMessages` instead of
  `compatibilityProjection`
- active baseline docs describe server-local review materialization instead of
  shared projection infrastructure

Post-PIR-5 Java residue scan only returns forbidden-token strings in
architecture guard tests. Active-doc residue scan returns no output outside
roadmap/inventory documents.

## Historical PIR-0 Production Consumers

The rows below are the original PIR-0 starting-point inventory. They are kept
only as migration evidence; post-PIR current truth is recorded in the delta
sections above.

| Consumer | Classification | Current Use | Replacement Decision |
|---|---|---|---|
| `xa-mass-server/src/main/java/com/xa/mass/api/review/TaskDetailStoreTaskReviewReadModel.java` | server review read-model implementation | Reads review preview, attempts, and stats from `TaskDetailStore`; also still implements the legacy direct writer | Replace in PIR-2 with a server-local `TaskReviewStore` backed by server-owned records; delete this class or reduce it to a temporary adapter only during migration |
| `xa-mass-server/src/main/java/com/xa/mass/api/review/TaskDetailStoreReviewMaterializer.java` | server review materializer | Applies queued RMP events into `TaskDetailStore` rows | Replace in PIR-2 with a materializer targeting the server-local review store |
| `xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java` | server wiring/bean creation | Creates `TaskDetailStore` from `JdbcStorageRuntime.taskDetailStore()` or `TaskShellStore instanceof TaskDetailStore`; wires read model, materializer, and SDK engine builder with `taskDetailStore` | Replace in PIR-2/PIR-3 with server-local review store creation and remove SDK/engine config wiring |
| `xa-mass-sdk/src/main/java/com/xa/mass/starter/config/EngineConfig.java` | SDK builder/config wiring | Holds optional `TaskDetailStore`, copies it, exposes getter/setter | Remove in PIR-3; engine runtime must not require review materialization store config |
| `xa-mass-sdk/src/main/java/com/xa/mass/starter/builder/MassEngineBuilder.java` | SDK builder/config wiring | Public `.taskDetailStore(TaskDetailStore)` API and forwards to `EngineConfig` | Remove in PIR-3 |
| `xa-mass-sdk/src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java` | SDK builder/config wiring | Public engine builder `.taskDetailStore(TaskDetailStore)` API | Remove in PIR-3 |
| `xa-mass-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java` | SDK public facade | Public `EngineOptions.taskDetailStore(TaskDetailStore)` API | Remove in PIR-3 |
| `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskDetailStore.java` | shared infra projection contract | Defines projection CRUD methods, stats, inner row records | Delete in PIR-4 after PIR-2 and PIR-3 migrate production callers |
| `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/projection/*.java` | shared infra projection enums | Projection statuses and final reasons | Move semantics into server-local review records if still needed; delete package in PIR-4 |
| `platform_infra/mass-storage-memory/src/main/java/com/xa/mass/storage/memory/InMemoryTaskShellStore.java` | storage implementation | Implements `TaskShellStore`, `TaskShellLifecycleQuery`, and `TaskDetailStore` in one class | Remove projection methods and `TaskDetailStore` implementation in PIR-4; server creates its own in-memory review store in PIR-2 |
| `platform_infra/mass-storage-jdbc/src/main/java/com/xa/mass/storage/jdbc/JdbcTaskShellStore.java` | storage implementation | Implements `TaskDetailStore` by delegating projection methods to `JdbcTaskCompatibilityProjection` | Remove projection methods and `TaskDetailStore` implementation in PIR-4 |
| `platform_infra/mass-storage-jdbc/src/main/java/com/xa/mass/storage/jdbc/JdbcTaskCompatibilityProjection.java` | storage implementation residue | Process-local in-memory projection helper used by JDBC task shell store; not durable DB storage | Delete in PIR-4 after server-local review storage exists |
| `platform_infra/mass-storage-jdbc/src/main/java/com/xa/mass/storage/jdbc/JdbcStorageRuntime.java` | storage runtime wiring | Exposes `taskDetailStore()` by casting the task shell store | Remove in PIR-4; server must not ask JDBC storage runtime for review rows |

## Historical PIR-0 Main-Source Test Framework Consumers

These are under `src/main`, but they are testing/support modules, not platform
production runtime.

| Consumer | Classification | Current Use | Replacement Decision |
|---|---|---|---|
| `xa-mass-testing/src/main/java/com/xa/mass/testing/chaos/support/ChaosRuntimeHarness.java` | testing framework helper | Carries `TaskDetailStore` and reads projection snapshots for chaos assertions; SDK `.taskDetailStore(...)` wiring has been removed | Migrate to runtime stats/results plus server-local review test support, or retire projection-specific assertions in PIR-4 |
| `xa-mass-testing/src/main/java/com/xa/mass/testing/chaos/support/ProjectionTestViews.java` | testing framework helper | Converts `TaskDetailStore` projections into compatibility views | Retire or replace with server-local review test views in PIR-4 |
| `xa-mass-testing/src/main/java/com/xa/mass/testing/concurrency/SdkTransportLoadRunner.java` | testing framework runner | Previously wired `.taskDetailStore(taskStorage)`; wiring removed in PIR-3 | No remaining projection call after PIR-3 |
| `xa-mass-testing/src/main/java/com/xa/mass/testing/perf/TaskWorkloadMixSmokeRunner.java` | testing framework runner | Previously called `EngineConfig.setTaskDetailStore(taskStorage)`; call removed in PIR-3 | No remaining projection call after PIR-3 |
| `xa-mass-testing/src/main/java/com/xa/mass/testing/perf/TaskInteractiveRetryWakeupSmokeRunner.java` | testing framework runner | Previously called `EngineConfig.setTaskDetailStore(taskStorage)`; call removed in PIR-3 | No remaining projection call after PIR-3 |
| `xa-mass-testing/src/main/java/com/xa/mass/testing/perf/TaskFlowLoadModelRunner.java` | testing framework runner | Previously called `EngineConfig.setTaskDetailStore(taskStorage)`; call removed in PIR-3 | No remaining projection call after PIR-3 |
| `xa-mass-testing/src/main/java/com/xa/mass/testing/soak/SdkPollingSchedulingSoakRunner.java` | testing framework runner | Previously wired `.taskDetailStore(taskStorage)`; wiring removed in PIR-3 | No remaining projection call after PIR-3 |

## Historical PIR-0 Test Consumers

| Consumer | Classification | Current Use | Replacement Decision |
|---|---|---|---|
| `platform_infra/mass-storage-api/src/test/java/com/xa/mass/storage/contract/TaskDetailStoreContractTest.java` | storage contract test | Contract coverage for projection CRUD/stats | Delete in PIR-4 |
| `platform_infra/mass-storage-memory/src/test/java/com/xa/mass/storage/memory/InMemoryTaskDetailStoreContractTest.java` | storage contract test | Runs `TaskDetailStoreContractTest` against memory store | Delete in PIR-4 |
| `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcH2TaskDetailStoreContractTest.java` | storage contract test | Runs `TaskDetailStoreContractTest` against JDBC runtime | Replace with server-local JDBC review store tests in PIR-2 or delete in PIR-4 |
| `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcPostgresTaskDetailStoreContractTest.java` | storage contract test | Same as H2 for Postgres | Replace with server-local JDBC review store tests in PIR-2 or delete in PIR-4 |
| `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcStorageH2Test.java` | storage test | Asserts task shell plus projection helper behavior | Split task shell assertions from review storage assertions; projection assertions move to PIR-2 server tests or delete |
| `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcStoragePostgresTest.java` | storage test | Same as H2 for Postgres | Split task shell assertions from review storage assertions; projection assertions move to PIR-2 server tests or delete |
| `xa-mass-server/src/test/java/com/xa/mass/api/review/*TaskDetailStore*Test.java` | server review tests | Verifies current queue/materializer/read-model using `TaskDetailStore` | Rename/retarget to server-local review store tests in PIR-2 |
| `xa-mass-server/src/test/java/com/xa/mass/api/review/QueueBackedTaskReviewBackingStoreTest.java` | server review test | Proves queued writer drains into current backing | Retarget to server-local review store in PIR-2 |
| `xa-mass-server/src/test/java/com/xa/mass/api/review/QueueBackedTaskReviewMaterializationIntegrationTest.java` | server review integration test | Proves RMP queue -> materializer -> `TaskDetailStore` | Retarget to server-local review store in PIR-2 |
| `xa-mass-server/src/test/java/com/xa/mass/api/internal/TaskApiControllerTest.java` | server API test | Mocks `TaskDetailStoreTaskReviewReadModel` backing data | Retarget to server-local review read model fixtures in PIR-2 |
| `xa-mass-server/src/test/java/com/xa/mass/server/support/ServerMainSourceArchitectureGuardTest.java` | server architecture guard | Currently requires `TaskDetailStoreReviewMaterializer` in server wiring | Update in PIR-2 to require queue + server-local materializer and forbid shared projection imports |
| `xa-mass-server/src/test/java/com/xa/mass/server/e2e/support/ServerMainlineE2eArchitectureGuardTest.java` | server architecture guard | Contains projection/review source rules | Update in PIR-2/PIR-5 |
| `xa-mass-sdk/src/test/java/com/xa/mass/sdk/MassSdkTest.java` | SDK test | Previously covered `taskDetailStore` optional wiring and missing getter behavior | Updated in PIR-3 to assert engine runtime does not require review storage wiring |
| `xa-mass-sdk/src/test/java/com/xa/mass/starter/MassEngineStartRecoveryTest.java` | SDK/start recovery test | Previously read `config.getTaskDetailStore()` projections | Projection assertion removed in PIR-3; dispatch/runtime recovery assertions remain |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskManagerLifecycleTest.java` | engine test residue | Large compatibility projection assertions and helpers | Migrate to runtime-based assertions or server-local review test support in PIR-4 |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskCompatibilityProjectionAccess.java` | engine test residue | Test helper over `TaskDetailStore` projection views | Retire in PIR-4 |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/ProjectionAwareTaskManager.java` | engine test residue | Test manager wrapper for projection access | Retire in PIR-4 |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/ProjectionTestSupport.java` | engine test residue | Builds projection rows for tests | Retire in PIR-4 |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/CompatibilityProjectionAwait.java` | engine test residue | Awaits projection visibility | Retire in PIR-4 |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/listener/SimpleTaskDispatchBinderTest.java` | engine test residue | Projection-based binder assertions | Migrate to runtime/result assertions in PIR-4 |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/storage/InMemoryTaskShellStoreTest.java` | misplaced storage test | Tests projection behavior on `InMemoryTaskShellStore` from engine module | Delete or move remaining task-shell-only coverage to storage module in PIR-4 |
| `transport/transport_runtime/src/test/java/com/xa/mass/transport/runtime/RuntimeTaskResultIngestChannelTest.java` | transport test residue | Reads compatibility projections after result ingest | Replace with result ingest/runtime finality assertions in PIR-4 |
| `platform_infra/mass-runtime-redis/src/test/java/com/xa/mass/runtime/redis/RedisRuntimeTraceIntegrationTest.java` | runtime integration test residue | Uses projections as compatibility view during trace/runtime proof | Replace with runtime/trace assertions in PIR-4 |
| `xa-mass-testing/src/test/java/com/xa/mass/testing/soak/SoakSourceArchitectureGuardTest.java` | testing architecture guard | Forbids projection residue in soak source | Keep and update after PIR-4 |

## JDBC Projection Table Ownership

There are currently no JDBC projection tables.

Evidence:

- `platform_infra/mass-storage-jdbc/src/main/resources/db/migration/control-plane/V1__create_control_plane_tables.sql`
  only creates `xa_task` and task shell indexes.
- `JdbcTaskCompatibilityProjection` stores message and attempt rows in
  `ConcurrentHashMap` / `ConcurrentLinkedDeque` process-local state.
- `JdbcTaskShellStore` delegates projection methods to that process-local
  helper.

Decision:

- PIR-2 must not rely on moving existing JDBC projection DDL because it does
  not exist.
- If server review materialization must be durable under JDBC mode, PIR-2 must
  create server-owned review tables and migrations in `xa-mass-server` or a
  future server-owned module.
- `JdbcTaskCompatibilityProjection` was deleted in PIR-4 after server review
  stopped using `JdbcStorageRuntime.taskDetailStore()`.

## Engine Production Dependency Inventory

`xa-mass-engine` production has no current main-source import of
`TaskDetailStore`, `TaskDetailStore` inner projection types, or
`com.xa.mass.storage.api.projection.*`.

Current production `xa-mass-engine -> mass-storage-api` dependency reasons:

- `TaskManager` imports `TaskShellStore` and `TaskShellLifecycleQuery`.
- `StorageBackedMatchingRuleSetProvider` imports `RuleStorage`.

Decision:

- These are non-projection control-plane/rule residues and are out of PIR
  scope unless the owner explicitly expands this roadmap.
- PIR must not add any engine production dependency on `mass-storage-memory`,
  `mass-storage-jdbc`, server review stores, review materializers, or
  projection row types.
- Engine and testing-framework projection dependencies were retired in PIR-4.
  Remaining engine `mass-storage-api` production use is non-projection
  task-shell/rule residue and out of PIR scope.

## Task Shell Query Consumers

`TaskShellStore` list/status/project query methods are DB/control-plane shell
queries. They are not runtime task state APIs.

Current relevant production consumers:

- `TaskManager` uses `TaskShellStore` for task shell by-id persistence and
  bounded shell lookup.
- `TaskManager.ScanningTaskShellLifecycleQuery` can fall back to DB shell
  scans when a store does not implement `TaskShellLifecycleQuery`.
- `JdbcTaskShellStore` implements list/status/project shell queries and
  max-runtime lifecycle polling.

Decision:

- Do not rename or model any replacement as `RuntimeTaskShell` or
  `RuntimeTaskShellStore`.
- Any future command/query split for `TaskShellStore` belongs to a separate
  task-shell boundary roadmap.
- Runtime dispatch/recovery truth must continue to start from runtime queues,
  then perform bounded shell lookup where needed.

## PIR-0 Acceptance Check

1. Every `TaskDetailStore` consumer has one classification and replacement
   decision above.
2. No production projection dependency remains after PIR-4; server and SDK
   production paths have been migrated.
3. JDBC projection table ownership is documented as absent; current JDBC
   projection is process-local memory.
4. Task shell storage is explicitly DB/control-plane scoped, not runtime shell
   truth.
5. Production engine storage dependencies remain non-projection residues:
   task shell and rule storage only.
