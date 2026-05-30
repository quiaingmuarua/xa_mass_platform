# Review Materialization Pipeline Inventory

Status: RMP-0 current-code inventory for
`REVIEW_MATERIALIZATION_PIPELINE_ROADMAP.md`.

This inventory classifies the current review/export read and write paths before
the queued materializer is introduced. It records current code only; target
implementation belongs to RMP-1 and later slices.

## Summary

Current review/export materialization is synchronous and `TaskDetailStore`
backed:

```text
accepted items
  -> TaskApiController.recordReviewItemsAccepted(...)
  -> TaskReviewReadModelWriter.recordItemsAccepted(...)
  -> TaskDetailStoreTaskReviewReadModel
  -> TaskDetailStore.upsertTaskMessageProjection(...)

terminal work
  -> MassSdkApplication task-work-final listener
  -> XaMassServerApplication.taskReviewReadModelFinalityListener(...)
  -> TaskReviewReadModelWriter.recordWorkFinal(...)
  -> TaskDetailStoreTaskReviewReadModel
  -> TaskDetailStore.upsertTaskMessageProjection(...)
  -> TaskDetailStore.upsertTaskMessageAttemptProjection(...)

review/export reads
  -> InternalTaskReviewController
  -> TaskReviewReadModel
  -> TaskDetailStoreTaskReviewReadModel
  -> TaskDetailStore projection reads/stats
```

RMP should change the write side first:

```text
TaskReviewReadModelWriter
  -> queue-backed writer
  -> review report queue
  -> TaskDetailStore-backed materializer
```

The read side can remain `TaskDetailStoreTaskReviewReadModel` during RMP.

## Review Write Producers

| Caller | Method | Classification | RMP target |
| --- | --- | --- | --- |
| `xa-mass-server/src/main/java/com/xa/mass/api/internal/TaskApiController.java` | `recordReviewItemsAccepted(...)` calls `TaskReviewReadModelWriter.recordItemsAccepted(...)` after append acceptance | production accepted-item review write producer | keep caller; writer becomes queue-backed in RMP-3 |
| `xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java` | `taskReviewReadModelFinalityListener(...)` registers `app.addTaskWorkFinalListener(...)` and calls `TaskReviewReadModelWriter.recordWorkFinal(...)` | production terminal-work review write producer | keep listener; writer becomes queue-backed in RMP-3 |
| `xa-mass-server/src/main/java/com/xa/mass/api/review/TaskDetailStoreTaskReviewReadModel.java` | implements `recordItemsAccepted(...)` and `recordWorkFinal(...)` with direct `TaskDetailStore` upserts | current direct writer and read model | split responsibility: direct write logic becomes materializer behavior in RMP-2; read side may remain |

## Review Read Consumers

| Caller | Method | Classification | RMP target |
| --- | --- | --- | --- |
| `xa-mass-server/src/main/java/com/xa/mass/api/internal/InternalTaskReviewController.java` | `loadReview(...)`, `loadItems(...)`, `loadStats(...)` through `TaskReviewReadModel` | production review/export read consumer | keep unchanged in RMP; reads existing materialized rows after queue drain |
| `xa-mass-server/src/test/java/com/xa/mass/server/e2e/support/ReviewReadModelSampleE2eTest.java` | `loadItems(...)`, `loadAttempts(...)` | test review/export proof | add queue drain before assertions once RMP-3 switches writer |
| `xa-mass-server/src/test/java/com/xa/mass/api/internal/TaskApiControllerTest.java` | constructs `TaskDetailStoreTaskReviewReadModel` and stubs `TaskDetailStore` reads | server API read/write test fixture | migrate selected tests to queue-backed writer and drain; keep direct read-model tests where scoped |
| `xa-mass-server/src/test/java/com/xa/mass/api/review/TaskDetailStoreTaskReviewReadModelTest.java` | direct writer behavior proof | review materialization behavior proof | reuse expected field coverage for `TaskDetailStoreReviewMaterializer` tests |

## Current TaskDetailStore Backing Points

| Module/file | Current behavior | Classification | RMP target |
| --- | --- | --- | --- |
| `platform_infra/mass-storage-memory/.../InMemoryTaskShellStore.java` | implements `TaskShellStore`, `TaskShellLifecycleQuery`, and `TaskDetailStore` | memory backing for current review materialization | keep as backing for RMP-2/RMP-4 |
| `platform_infra/mass-storage-jdbc/.../JdbcTaskShellStore.java` | implements `TaskShellStore`, `TaskShellLifecycleQuery`, and `TaskDetailStore`; delegates projection work | JDBC backing for current review materialization | keep as backing for RMP-2/RMP-4 |
| `platform_infra/mass-storage-jdbc/.../JdbcTaskCompatibilityProjection.java` | stores JDBC projection rows and stats | JDBC implementation detail for current `TaskDetailStore` | keep; PIR may revisit after RMP |
| `platform_infra/mass-storage-jdbc/.../JdbcStorageRuntime.java` | exposes `taskDetailStore()` by casting configured task shell store | JDBC assembly backing lookup | keep in RMP; do not move DDL/schema ownership |
| `xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java` | creates `TaskDetailStore` bean from JDBC runtime or task shell store | server wiring for current backing | keep backing; writer bean changes in RMP-3 |
| `xa-mass-sdk/src/main/java/com/xa/mass/starter/config/EngineConfig.java` | carries optional `TaskDetailStore` for current assembly | SDK/engine config residue required by current server wiring | keep in RMP; PIR may remove later |
| `xa-mass-sdk/src/main/java/com/xa/mass/starter/builder/MassEngineBuilder.java` | exposes `taskDetailStore(TaskDetailStore)` | SDK/starter builder residue | keep in RMP |
| `xa-mass-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java` | exposes `EngineOptions.taskDetailStore(...)` | SDK facade residue | keep in RMP |

## Required Field Coverage

Accepted item materialization must preserve:

| Field | Source |
| --- | --- |
| `taskId` | append task id |
| `messageId` | `TaskItemBatchAppendReceipt.messageIds()` |
| `input` | normalized accepted item map |
| `eventCode` | derived from `input.eventCode` by review read model |
| `payloadRef` | currently null on accepted rows |
| `status` | `INIT` |
| `createTime` / `updateTime` | materialization time |
| `retryCount` | `0` |
| `maxRetryCount` | resolved task default retry count |

Terminal item materialization must preserve:

| Field | Source |
| --- | --- |
| `taskId` / `messageId` | final snapshot |
| `payloadRef` | final snapshot or previous accepted row |
| `status` | final snapshot status parsed to projection status |
| `assignedTime` / `startTime` / `completeTime` / `updateTime` | final snapshot with current fallback where existing writer does so |
| `retryCount` / `maxRetryCount` | final snapshot and previous row fallback |
| `errorMessage` / `errorCode` | final snapshot |
| `finalReason` | final snapshot parsed to projection final reason |
| `output` | final snapshot output |
| `latestAttemptId` / `latestAttemptWorkerId` / `latestAttemptBatchId` | final snapshot or previous accepted row |

Terminal attempt materialization must preserve:

| Field | Source |
| --- | --- |
| `attemptId` | final snapshot attempt id |
| `taskId` / `messageId` | final snapshot |
| `attemptNo` | current writer uses `retryCount + 1` |
| `workerId` / `batchId` | final snapshot |
| `status` | final snapshot status parsed to attempt status |
| `finalReason` | final snapshot final reason parsed to attempt final reason |
| `errorMessage` / `errorCode` | final snapshot |
| `output` | final snapshot output |

## Synchronous Assumptions To Migrate

| Area | Current assumption | RMP migration |
| --- | --- | --- |
| `TaskApiControllerTest` append/review assertions | direct writer call updates mock or store immediately | after RMP-3, drain queue before asserting materialized rows |
| sample E2E review assertions | review rows are available after flow completion | after RMP-3, drain queue or wait for queue idle |
| terminal finality listener tests, if added | final listener can synchronously write detail rows | assert queued event submission and materializer result after drain |
| chaos/support projection helpers | direct `TaskDetailStore` reads are diagnostic/report support | keep for RMP; later PIR decides migration/removal |
| storage contract tests | `TaskDetailStore` contract remains live | keep for RMP; later PIR owns deletion |

## Test Residue And Support Usage

| File | Classification | RMP target |
| --- | --- | --- |
| `platform_infra/mass-storage-api/src/test/java/com/xa/mass/storage/contract/TaskDetailStoreContractTest.java` | storage contract test for current backing | keep |
| `platform_infra/mass-storage-memory/src/test/java/com/xa/mass/storage/memory/InMemoryTaskDetailStoreContractTest.java` | memory backing proof | keep |
| `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcH2TaskDetailStoreContractTest.java` | JDBC H2 backing proof | keep |
| `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcPostgresTaskDetailStoreContractTest.java` | JDBC Postgres backing proof | keep |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/TaskCompatibilityProjectionAccess.java` | engine test-only projection residue access | keep; PIR later |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/ProjectionAwareTaskManager.java` | engine test fixture residue | keep; PIR later |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/ProjectionTestSupport.java` | engine test support residue | keep; PIR later |
| `xa-mass-engine/src/test/java/com/xa/mass/engine/CompatibilityProjectionAwait.java` | test await helper for projection residue | keep; RMP queue tests need separate queue idle helper |
| `xa-mass-testing/src/main/java/com/xa/mass/testing/chaos/support/ProjectionTestViews.java` | chaos/report diagnostic support | keep; PIR later |
| `xa-mass-testing/src/main/java/com/xa/mass/testing/chaos/support/ChaosRuntimeHarness.java` | harness exposes `TaskDetailStore` | keep; PIR later |
| `transport/transport_runtime/src/test/java/com/xa/mass/transport/runtime/RuntimeTaskResultIngestChannelTest.java` | transport test reads/writes projection residue | keep; PIR later |

## RMP-0 Decisions

1. RMP should not introduce `TaskReviewStore` as a required first-stage
   contract.
2. RMP should keep `TaskDetailStore` as memory/JDBC backing.
3. RMP should split the current direct writer behavior into:
   - queue-backed `TaskReviewReadModelWriter`
   - `TaskDetailStore`-backed materializer
4. RMP should keep the current `TaskReviewReadModel` read behavior during the
   pipeline proof.
5. RMP tests must use deterministic queue drain/await-idle after RMP-3.
6. PIR remains responsible for deleting `TaskDetailStore`, storage projection
   enums, SDK builder wiring, and projection-heavy tests.

## RMP-0 Acceptance Evidence

This inventory satisfies RMP-0 acceptance:

1. Every current review read/write caller is classified.
2. Required accepted, terminal item, and terminal attempt fields are recorded.
3. Memory and JDBC `TaskDetailStore` backing points are documented.
4. Test migration needs are classified without deleting old projection tests.
