# Task Shell Storage CRUD De-Scoping Inventory

Status: read-path isolation milestone closed on 2026-07-02 for
[TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md](TASK_SHELL_STORAGE_CRUD_DE_SCOPING_ROADMAP.md).
The roadmap remains active until engine's internal storage-task dependency is
removed and storage-shaped task CRUD/runtime SPI surfaces are deleted or
narrowed. It does not require deleting `TaskManager`.

This inventory is the current-code ledger for removing `TaskShellStore` from
maintained task ownership. Read-path rows are closed when current serving code
uses read projection plus task-runtime facts. Engine command/lifecycle rows are
the current storage-dependency blockers. This roadmap's next mainline is to
close the engine storage-task chain while keeping `TaskManager` if needed, not
to treat the storage dependency as harmless residue.

## Symbols

| Symbol / file | Current code fact | Classification | Target | Status |
| --- | --- | --- | --- | --- |
| `xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineTaskReadOperations.java` | Implements `TaskReadOperations`; detail/list/status/state/access use `TaskReadViewProjectionStore`; result/window/final/archive/work-stats/active-lease reads use task-runtime serving-lane helpers. | server/public read-view provider | Keep source on read-view metadata projection plus task-runtime facts; no direct `TaskShellStore` read owner. | closed |
| `xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/TaskReadViewProjectionStore.java` | Lean in-memory read projection over task id/name/tenant/project/user/contract/execution/source/shared-config/status/intake/counters; does not expose `Task` CRUD. | read-view metadata projection | Remain read-only projection for this roadmap; no storage CRUD, persistence, or lifecycle ownership. | closed |
| `xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/TaskReadViewPublishingTaskCommandPort.java` | Wraps the current `TaskCommandPort` and updates read projection only after accepted/applied command outcomes. | read-view publication side effect | Publish read metadata without changing command/lifecycle execution semantics. | closed |
| `xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java` | Wraps `TaskManager` command port with read-view publisher, installs created/ready/dispatch/assigned/terminal event listeners into the projection, and still holds `TaskShellStore` for command/lifecycle assembly. | mixed: read-provider cutover + engine storage-task blocker | Read provider no longer uses `TaskShellStore`; next mainline must remove `taskShellStore` command/lifecycle assembly by removing `TaskManager.taskStorage`, not by adding another task CRUD dependency. | read-path closed; engine blocker |
| `sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/TaskReadOperations.java` | Current server/embedded task read facade and DTO contract; signatures unchanged. | migration facade to `TaskReadViewPort` | Keep server-compatible migration facade in this roadmap; do not protect as final read contract. | closed for read path |
| `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java` | Delegates create and command post-reads through `TaskReadOperations`; read methods are facade pass-throughs. | server/public read-view consumer | Post-command snapshots now read through read-view provider, not storage. | closed |
| `xa-mass-server/src/main/java/com/xa/mass/api/internal/TaskApiController.java` | List/get/result endpoints consume `TaskReadOperations`; list now applies status predicate over bounded candidates before response assembly. | server/public read-view consumer | Preserve route/request/response DTOs; bounded status responses exclude non-matching statuses. | closed |
| `xa-mass-server/src/main/java/com/xa/mass/api/internal/InternalTaskReviewController.java` | Review reads consume `TaskReadOperations`. | server/public read-view consumer | Continue through read facade; source runtime facts and read-view metadata, not `TaskShellStore`. | closed by provider cutover |
| `xa-mass-task-runtime/src/main/java/com/xa/mass/task/runtime/TaskRuntimeReadPort.java` | Runtime read port exposes final-result, active-work, result-correlation, and progress reads only. | runtime read/query need | Use for runtime facts; do not add presentation metadata or external read-view DTOs to runtime core. | closed for this roadmap |
| `sdk/xa-mass-task-runtime-starter-sdk/src/main/java/com/xa/mass/task/runtime/starter/TaskRuntimePortSet.java` | Starter handle exposes runtime ports for assembly. | runtime read/query need | Back runtime-fact reads through serving-lane helpers; do not expose raw physical internals as SDK read API. | closed for this roadmap |
| `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskShellStore.java` | Fat whole-`Task` CRUD/query API still exists for current command/lifecycle assembly. | delete / engine compile blocker | Removed from maintained read/query ownership now; physical deletion or narrowing remains required after engine command/lifecycle callers close. | engine blocker |
| `platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/TaskShellLifecycleQuery.java` | Storage-side max-runtime deadline query still exists for current lifecycle maintenance. | lifecycle query blocker | Remove storage deadline polling from engine lifecycle maintenance before deleting this. | engine blocker |
| `xa-mass-kernel-spi/src/main/java/com/xa/mass/kernel/spi/task/TaskShellRuntimeStore.java` | Runtime-facing storage SPI accepting whole mutable `Task`. | engine command/lifecycle mainline blocker | Remove `TaskManager.taskStorage`; split each current Task-row fact to its owner; then delete this SPI. | engine blocker |
| `xa-mass-kernel-spi/src/main/java/com/xa/mass/kernel/spi/task/TaskShellRuntimeLifecycleQuery.java` | Runtime-facing storage max-runtime query SPI. | engine lifecycle query blocker | Remove storage deadline polling through runtime/lifecycle evidence; then delete this SPI. | engine blocker |
| `platform_infra/mass-storage-memory/src/main/java/com/xa/mass/storage/memory/InMemoryTaskShellStore.java` | Implements storage API plus kernel runtime SPI and helper indexes. | storage adapter blocker / temporary compile support | No new behavior; delete or narrow after runtime SPI/storage API callers close. | deletion blocker |
| `platform_infra/mass-storage-jdbc/src/main/java/com/xa/mass/storage/jdbc/JdbcTaskShellStore.java` | Implements storage API plus kernel runtime SPI and helper indexes. | storage adapter blocker / temporary compile support | No new behavior or persistence design; delete or narrow after runtime SPI/storage API callers close. | deletion blocker |
| `xa-mass-engine-starter/src/main/java/com/xa/mass/starter/builder/MassEngineBuilder.java#taskShellStore` | Public-ish builder injection of `TaskShellStore`. | engine assembly/config blocker | Not a maintained extension API; delete/narrow after engine command/lifecycle assembly stops requiring it. | deletion blocker |
| `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java#taskShellStore` | Embedded builder injection of `TaskShellStore`. | engine assembly/config blocker | Not a maintained extension API; delete/narrow after engine command/lifecycle assembly stops requiring it. | deletion blocker |
| `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java#taskShellStore` | User-facing options delegate exposing `TaskShellStore`. | engine assembly/config blocker | Not a maintained extension API; deletion/narrowing target after engine no longer needs storage task assembly. | deletion blocker |
| `xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java#taskShellStore` | Spring bean supplies `TaskShellStore` to current engine assembly. | engine assembly/config blocker | Keep startup behavior until engine command/lifecycle path no longer needs it; do not use as read owner. | deletion blocker |
| `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java` | Uses `TaskShellRuntimeStore` for create/lifecycle storage and `TaskShellRuntimeLifecycleQuery` for deadline polling. | engine storage-task mainline blocker | Next mainline cut: keep `TaskManager` if needed, but remove `taskStorage`, whole-Task CRUD calls, and storage-backed deadline polling from this path. | engine blocker |
| `xa-mass-engine/src/main/java/com/xa/mass/engine/watchdog/LeaseExpireWatchdog.java` | Calls `pollTasksPastMaxRuntimeDeadline`. | engine lifecycle query blocker | Next mainline cut: move max-runtime candidate source to non-storage runtime/lifecycle evidence or stop for target approval. | engine blocker |
| `platform_infra/mass-storage-api/src/test/java/com/xa/mass/storage/contract/TaskShellStoreContractTest.java` | Deleted; no active abstract whole-`Task` CRUD/query contract remains. | obsolete storage CRUD product test | Do not restore as maintained product behavior. | closed |
| `platform_infra/mass-storage-api/src/test/java/com/xa/mass/storage/contract/TaskShellLifecycleQueryContractTest.java` | Deleted; no active storage deadline-query contract remains. | lifecycle query test residue | Do not restore as maintained product behavior; TRLC owns future lifecycle proof. | closed |
| `platform_infra/mass-storage-memory/src/test/java/com/xa/mass/storage/memory/InMemoryTaskShellStoreIndexTest.java` | Deleted; memory status/project/deadline indexes are not protected as read owner behavior. | obsolete storage query/index test | Do not maintain indexes for read owner. | closed |
| `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/*TaskShell*ContractTest.java` | Deleted for H2/Postgres/SQLite; JDBC storage tests no longer extend task-shell CRUD/lifecycle contracts. | obsolete storage CRUD/lifecycle product tests | Do not keep as product behavior. | closed |
| `platform_infra/mass-storage-jdbc/src/test/java/com/xa/mass/storage/jdbc/JdbcStorageH2Test.java` / `JdbcStoragePostgresTest.java` / `JdbcStorageSQLiteTest.java` | Task-shell save/get/list/delete assertions removed; rule/SQLite infrastructure proof remains. | storage CRUD proof deletion | Keep storage infra smoke independent of task-shell CRUD/query product behavior. | closed |
| `platform_infra/mass-storage-api/src/test/java/com/xa/mass/storage/contract/StorageBoundaryGuardTest.java` | Guards storage boundary and now rejects reintroduced task-shell CRUD/lifecycle/index product contract tests. | ownership guard | Prevent old task-shell storage contract/index tests from returning. | closed |
| `sdk/xa-mass-embedded-sdk/src/test/java/com/xa/mass/sdk/architecture/EngineStarterBackdoorGuardTest.java` | Rewritten to guard provider ownership and forbidden storage reads; no longer pins `EngineTaskReadOperations` as final implementation/location. | migration guard rewritten | Protect forbidden read-provider storage calls, not class placement. | closed |
| `sdk/xa-mass-embedded-sdk/src/test/java/com/xa/mass/sdk/MassSdkApplicationTaskReadBoundaryTest.java` | New embedded SDK proof creates a task and verifies detail/access/state/status-list reads through real assembly. | behavior + ownership support proof | Prove representative SDK read semantics survive provider cutover. | closed |
| `xa-mass-server/src/test/java/com/xa/mass/api/internal/TaskApiListControllerTest.java` | Mixed-status bounded candidates now prove status-filtered responses exclude non-matching statuses. | behavior proof | Preserve server route contract while accepting bounded v0 candidate semantics. | closed |

## Task Row Fact Split

`TaskManager.taskStorage` removal is blocked only by facts currently co-located
in the whole mutable `Task` row. These facts must be moved to their owner; they
must not be reassembled behind another CRUD interface.

| Current fact in `Task` row | Current use | Target owner/source | Closure slice | Status |
| --- | --- | --- | --- | --- |
| task existence after create | command lookup, post-command readbacks, delete | read projection point lookup plus runtime owner existence where needed; no `getTask()` returning base `Task` | TSDC-4 | open |
| descriptor metadata: name/project/tenant/user/contract/source/shared config | access checks, read snapshots, append selector input | read-view metadata projection/lenses and command input | TSDC-4 | open |
| execution spec/default retry/max runtime | append item construction, deadline candidate calculation, view output | descriptor metadata lens for append defaults; non-storage runtime/lifecycle evidence for max-runtime candidates | TSDC-4 | open |
| intake status open/sealed | append admission and state/read snapshots | task-runtime owner fact plus read projection publication | TSDC-4 | open |
| lifecycle/status/terminal reason | command transition checks, dispatch eligibility, read state | task-runtime owner fact/score-band plus read projection publication | TSDC-4 | open |
| counters: target/eligible/progress-like fields | read display and legacy command side effects | runtime work/result facts for real progress; read projection for display counters where still needed | TSDC-4 / read-index residue | open |
| max-runtime deadline scan | `LeaseExpireWatchdog` terminal maintenance | same-behavior non-storage runtime/lifecycle candidate evidence | TSDC-4 | open |
| delete/discard bookkeeping | delete command, terminal discard | runtime discard/work discard plus read projection tombstone/removal | TSDC-4 | open |

## TaskReadOperations Target Sources

| Method | Current source | Target source | Status |
| --- | --- | --- | --- |
| `getTaskDetail` | `TaskReadViewProjectionStore` plus runtime work stats | read-view metadata projection plus runtime progress/finality where available | closed |
| `listTaskSummaries` | bounded `TaskReadViewProjectionStore` scan plus runtime work stats | bounded read-view metadata scan plus runtime status/progress projection | closed |
| `getTaskSummariesByStatus` | bounded `TaskReadViewProjectionStore` status scan plus server-side predicate guard | bounded read-view metadata scan plus bounded status filtering | closed |
| `taskExists` | read-view metadata point lookup | read-view metadata point lookup | closed |
| `getTaskState` | read-view status/intake/terminal projection | runtime/meta status projection | closed |
| `getTaskAccess` | read-view project/sharedConfig/intake projection | read-view metadata projection for project/sharedConfig/intake | closed |
| `readTaskResults` | task-runtime result window through `EngineConfig` | task-runtime result/finality facts | closed |
| `getTaskWorkFinal` | task-runtime final row through `EngineConfig` | task-runtime final row | closed |
| `getTaskResultArchiveManifest` | read-view terminal state plus task-runtime result count | read-view state plus runtime result count | closed |
| `writeTaskResultArchiveContent` | task-runtime result windows | task-runtime result windows | closed |
| `validateTaskState` | read-view status/terminal/intake projection plus task-runtime stats | diagnostic/runtime projection; does not mutate lifecycle | closed |
| `resolveTaskState` | read-view status/terminal projection plus task-runtime stats | diagnostic/runtime projection; does not mutate lifecycle | closed |
| `getTaskWorkStats` | runtime progress stats through `EngineConfig` | task-runtime progress facts | closed |
| `getActiveLeases` | runtime active leases through `EngineConfig` | task-runtime active-work facts | closed |

## Closure Notes

- Read-path isolation is closed by provider cutover, guard proof, SDK behavior
  proof, server bounded-status proof, and storage CRUD product-test deletion.
- Remaining `TaskShellStore`/`TaskShellRuntimeStore` command/lifecycle callers
  are the next engine-internal storage-task blockers. They do not block the read
  milestone, but they are the mainline for final roadmap closure.
- Known non-core read-view residue: no durable read projection, no true global
  pagination/status/project indexes, and display-only counters/timestamps may be
  incomplete in v0.
- No row may introduce `TaskDescriptorStore`, `TaskRuntimeTaskStore`, whole
  mutable `Task` view DTOs, storage sync loops, or storage-driven runtime truth.
