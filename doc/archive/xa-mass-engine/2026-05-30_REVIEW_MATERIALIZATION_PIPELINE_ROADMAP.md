# Review Materialization Pipeline Roadmap

Status: complete. RMP-0 inventory is captured in
[`REVIEW_MATERIALIZATION_PIPELINE_INVENTORY.md`](./2026-05-30_REVIEW_MATERIALIZATION_PIPELINE_INVENTORY.md).
RMP-1 through RMP-5 have landed: production server review writes now flow
through the server-local bounded report queue, `TaskDetailStore` remains the
temporary persistence backing, memory/JDBC backing paths are proven, and
architecture guards cover the new boundary.

Historical scope note: this roadmap proves the queued review materialization
pipeline that PIR later used as a prerequisite. It is not current
implementation truth after projection retirement. Current production review
materialization uses server-local `TaskReviewStore*` components, including
`TaskReviewStoreMaterializer` and `TaskReviewStoreTaskReviewReadModel`, not the
temporary `TaskDetailStore*` backing described in the RMP-0 observations below.

This roadmap creates the server-owned report queue and materializer for
task review/export rows. The first implementation intentionally reuses the
existing `TaskDetailStore` as the persistence backing so the new reporting
chain can be proven before any projection infrastructure is retired.

Current first-stage target:

```text
task item accepted
  -> server review report queue
  -> review materializer
  -> existing TaskDetailStore backing
  -> existing review/export read model

task work terminal
  -> server review report queue
  -> review materializer
  -> existing TaskDetailStore backing
  -> existing review/export read model
```

The first version reports only two materialization facts:

- accepted items, to preserve seed/input/export row coverage
- terminal work, to preserve final result/attempt/export row coverage

Intermediate runtime transitions such as ready, assigned, inflight,
retry-delayed, and lease-expired retry scheduling are not materialized in this
roadmap. Runtime progress remains runtime truth and should be queried from
runtime state, not inferred from review rows.

## Relationship To PIR

`PROJECTION_INFRASTRUCTURE_RETIREMENT_ROADMAP.md` remains separate. Its RMP
precondition is now satisfied, but PIR deletion work has not started in this
roadmap.

This roadmap is the prerequisite pipeline proof for PIR, not PIR itself. Do
not delete `TaskDetailStore`, storage projection enums, SDK
`taskDetailStore(...)` wiring, `JdbcTaskCompatibilityProjection`, or
projection-based tests in this roadmap.

The handoff to PIR is now:

- production review/export writes go through `QueueBackedTaskReviewReadModelWriter`
- accepted and terminal report events are materialized by
  `TaskDetailStoreReviewMaterializer`
- memory backing is proven through `InMemoryTaskShellStore`
- JDBC backing is proven through H2 `JdbcStorageRuntime.taskDetailStore()`
- remaining projection infrastructure is PIR residue, not RMP scope

Whether PIR then replaces `TaskDetailStore` with a server-local store is a
later owner decision.

## Historical RMP-0 Code Observations

The observations in this section are the code state captured before RMP landed.
They intentionally preserve the migration context and should not be read as the
current review/materialization implementation.

- At RMP-0, server review used `TaskReviewReadModel` and
  `TaskReviewReadModelWriter` as the review/export contract.
- `TaskDetailStoreTaskReviewReadModel` implemented both the read model
  and writer by delegating to shared `TaskDetailStore`.
- The writer already receives the two facts needed for first-version
  materialization:
  - `recordItemsAccepted(...)`
  - `recordWorkFinal(...)`
- `XaMassServerApplication` wired `TaskDetailStore` and
  `TaskDetailStoreTaskReviewReadModel` in the dev profile.
- JDBC mode got review persistence by delegating to
  `JdbcStorageRuntime.taskDetailStore()` and `JdbcTaskCompatibilityProjection`.
- In-memory mode got review persistence because
  `InMemoryTaskShellStore` also implements `TaskDetailStore`.
- `InternalTaskReviewController` reads `TaskReviewReadModel` and does not need
  to know whether writes are direct or queued.
- `TaskDetailStore` remains heavily used by tests and support utilities. This
  roadmap should not remove it; it should first introduce replacement write
  proof through a queue-backed materializer.

## Boundary Decision

Review report ingestion and materialization orchestration are server-owned.

The first implementation may persist materialized review rows through the
existing `TaskDetailStore` backing store. That is an implementation choice for
risk reduction, not a decision that `TaskDetailStore` is runtime truth or the
final long-term review-store contract.

Review materialization is best-effort DB-backed detail materialization. It is
used to populate durable review/export/read-model rows, not to commit runtime
output results or decide task lifecycle truth. Failed review materialization
must not roll back item append, runtime acceptance, result convergence, or
finality.

This is intentionally different from trace. Trace is the lifecycle
observability/audit stream; review materialization is the server query/export
read model persisted through the current DB backing. It may later consume trace
or share evidence vocabulary with trace, but it is not replaced by trace in
this roadmap.

Best-effort does not mean silent loss. Queue submit rejection, materializer
failure, bounded drain timeout, and shutdown drops must be observable through
logs and small bounded counters so tests and operators can see that detail
materialization lagged or failed.

Server owns:

- review report queue
- accepted/final review report event records
- materializer worker and retry/idempotency rules
- queue-backed review writer
- deterministic drain/await-idle test support
- materializer tests for accepted and terminal facts

Existing shared storage infra temporarily provides:

- `TaskDetailStore`
- `TaskMessageProjection`
- `TaskMessageAttemptProjection`
- memory/JDBC backing implementations

Engine/runtime owns:

- scheduling, leases, retries, finality, terminal policy, and runtime progress
- runtime result convergence and result read truth

Storage infra owns task shell persistence and the current `TaskDetailStore`
backing until a later PIR/store-extraction decision removes it.

## Target Shape

Suggested first-stage server-local contracts:

```java
interface TaskReviewReportQueue {
    boolean submit(TaskReviewReportEvent event);
    boolean awaitIdle(Duration timeout);
}

sealed interface TaskReviewReportEvent permits
        TaskReviewItemsAcceptedEvent,
        TaskReviewWorkTerminalEvent {
    String taskId();
}

interface TaskReviewMaterializer {
    void apply(TaskReviewReportEvent event);
}

final class QueueBackedTaskReviewReadModelWriter
        implements TaskReviewReadModelWriter {
    // converts current server write callbacks into queue events
}

final class TaskDetailStoreReviewMaterializer
        implements TaskReviewMaterializer {
    // maps accepted/final queue events to TaskDetailStore upserts
}
```

Exact names may change during RMP-0 inventory. The important shape is that the
queue, event records, queue-backed writer, materializer, and drain support live
under `xa-mass-server`; the persistence backing can remain `TaskDetailStore`
for this roadmap.

`submit(...)` is intentionally best-effort. A `false` result means the detail
event was rejected or dropped and must be logged/countable by the server write
adapter, but it must not fail the runtime path. A first version may expose
only simple counters such as submitted, rejected, applied, failed, pending,
and last-error; it does not need durable replay or dead-letter semantics.

Do not introduce `TaskReviewStore` as a required first-stage contract. A
server-local store may be a later PIR prerequisite if the owner decision still
requires retiring `TaskDetailStore`.

## Materialized Row Semantics

Accepted item rows are keyed by:

```text
taskId + messageId
```

Terminal attempt rows are keyed by:

```text
taskId + messageId + attemptId
```

Terminal item updates are keyed by:

```text
taskId + messageId
```

Materialization must be idempotent:

- repeated accepted events do not duplicate rows
- repeated terminal events update the same item and attempt rows
- terminal events may arrive without a prior accepted row; the materializer
  creates a minimal row from terminal evidence
- accepted events arriving after terminal events must not erase final fields

Review rows may lag runtime. They are review/export materialization, not
runtime progress truth.

## Non-Goals

1. No `TaskDetailStore` deletion.
2. No `com.xa.mass.storage.api.projection.*` deletion.
3. No SDK `taskDetailStore(...)` deletion.
4. No `JdbcTaskCompatibilityProjection` deletion.
5. No new mandatory server-local review store.
6. No mass-storage-api or mass-storage-jdbc projection cleanup.
7. No engine scheduling, terminal-policy, retry, lease, or dispatch changes.
8. No message-level real-time projection API.
9. No materialization of every runtime transition.
10. No trace/archive replacement. Trace-derived review is a later direction.
11. No console redesign beyond preserving existing review/export behavior.
12. No durable exactly-once report queue, dead-letter system, or crash-replay
    guarantee in the first-stage implementation.
13. No guarantee that every detail row survives process crash before the queue
    drains. Runtime output/result truth must be recovered from runtime/result
    owners, not review materialization rows.

## Hard Rules

1. The review materialization queue and materializer must not drive runtime
   decisions.
2. Server review/export may read materialized rows; engine production must not.
3. First-version report events are accepted and terminal only.
4. Runtime progress stats must come from runtime state, not review row counts.
5. Review materialization stats, where used, must be named as materialization
   coverage, not runtime progress.
6. Tests must have a deterministic drain/await-idle mechanism before they
   depend on asynchronous materialization.
7. The first-stage materializer writes through `TaskDetailStore`; replacing the
   store contract belongs to PIR or a later store-extraction roadmap.
8. Queue-backed review writes are best-effort and must not throw through
   append, finality, result convergence, or runtime scheduling paths.
9. Best-effort failures must be observable through logs and bounded counters;
   silent loss is not acceptable even though reliable replay is out of scope.
10. Queue events must be immutable snapshots. The queue must not retain
    caller-owned mutable `List`, `Map`, notification, or receipt objects.
11. Shutdown drain is bounded and best-effort. Any unprocessed or failed count
    must be observable; shutdown must not pretend all detail rows were applied.

## Do Not Start With

Do not start by deleting `TaskDetailStore`, replacing JDBC schema ownership,
changing SDK builder APIs, introducing a new review-store abstraction, or
removing projection tests. Build and verify the queued report materializer
against the existing backing store first.

## Slice RMP-0: Inventory Current Review Write And Read Paths

Goal: identify every current producer and consumer of review/export rows before
introducing the queue-backed writer.

Scope:

1. Inventory callers of `TaskReviewReadModelWriter.recordItemsAccepted(...)`
   and `recordWorkFinal(...)`.
2. Inventory callers of `TaskReviewReadModel.loadReview(...)`,
   `loadItems(...)`, `loadAttempts(...)`, and `loadStats(...)`.
3. Classify current `TaskDetailStore` usages that are review/export related
   separately from storage-infra or test residue.
4. Identify current field coverage required by review UI and exports:
   - accepted item fields
   - terminal item fields
   - attempt fields
   - stats/summary fields
5. Identify where server wiring currently creates review read model and writer
   beans for memory and JDBC modes.
6. Identify tests that need queue drain/await-idle support after write
   materialization becomes asynchronous.
7. Identify any code path that assumes `TaskReviewReadModelWriter` writes rows
   synchronously and must be updated to drain the queue before assertions.

Acceptance:

1. Every review read/write caller has one classification.
2. Required row fields are documented before queue event records are created.
3. Memory and JDBC `TaskDetailStore` backing points are documented.
4. Test migration needs are classified without deleting old projection tests.

## Slice RMP-1: Define Report Events, Queue, And Drain Support

Goal: introduce the review report queue and event contracts without switching
production writes yet.

Scope:

1. Add server-local accepted and terminal report event records.
2. Add `TaskReviewReportQueue` with bounded submit and deterministic
   `awaitIdle` / drain support.
3. Add `TaskReviewMaterializer` contract.
4. Add a first in-process queue implementation suitable for dev/test.
5. Define best-effort submit semantics:
   - accepted submission
   - rejected or dropped submission
   - materializer failure after accepted submission
   - bounded drain timeout
6. Snapshot accepted item maps, append receipts, final notifications, and final
   snapshot fields into immutable event records before enqueue.
7. Expose small bounded observability for the queue, for example submitted,
   rejected, applied, failed, pending, and last-error.
8. Keep current `TaskDetailStoreTaskReviewReadModel` active as the production
   read/write path.
9. Add unit tests for queue ordering, bounded behavior, idle detection,
   failure reporting, submit rejection, and snapshot immutability.

Acceptance:

1. Queue/event contracts live under `xa-mass-server`.
2. No `platform_infra/` module imports server review queue contracts.
3. Queue supports deterministic drain/await-idle in tests.
4. Submit failure is observable but does not fail the caller's runtime path.
5. Mutating caller-owned accepted item maps after submit cannot change the
   queued event.
6. Existing review/export behavior is unchanged.

RMP-1 evidence:

```powershell
mvn -pl xa-mass-server -am "-Dtest=InProcessTaskReviewReportQueueTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-05-29: build success, 5 tests passed.

## Slice RMP-2: Implement TaskDetailStore-Backed Materializer

Goal: prove that queued accepted/final events can materialize the same review
rows currently written directly by `TaskDetailStoreTaskReviewReadModel`.

Scope:

1. Add `TaskDetailStoreReviewMaterializer`.
2. Map accepted item events to `TaskDetailStore.TaskMessageProjection` upserts.
3. Map terminal work events to:
   - final `TaskMessageProjection` updates
   - terminal `TaskMessageAttemptProjection` upserts
4. Preserve existing field coverage from `TaskDetailStoreTaskReviewReadModel`.
5. Preserve terminal-before-accepted behavior by creating a minimal message row
   when prior accepted materialization is missing.
6. Preserve accepted-after-terminal behavior by preventing accepted events from
   clearing final fields.
7. Treat materializer exceptions as detail-materialization failures: count and
   log them through the queue path without changing runtime/result truth.
8. Add materializer tests for idempotency, duplicate accepted, duplicate
   terminal, terminal-before-accepted, accepted-after-terminal, and field
   preservation.
9. Add materializer failure tests proving failure is observable and isolated
   from append/finality/result paths.

Acceptance:

1. Materializer writes through existing `TaskDetailStore`.
2. Accepted and terminal events produce equivalent review rows to the current
   direct writer.
3. Upserts are idempotent by task/message/attempt keys.
4. Materializer failure does not become runtime failure.
5. Tests prove materializer behavior without replacing `TaskDetailStore`.

RMP-2 evidence:

```powershell
mvn -pl xa-mass-server -am "-Dtest=TaskDetailStoreReviewMaterializerTest,TaskDetailStoreTaskReviewReadModelTest,InProcessTaskReviewReportQueueTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-05-29: build success, 10 tests passed.

## Slice RMP-3: Switch Server Writer To Queue-Backed Writer

Goal: route production review writes through the queue while keeping the
existing review/export read path backed by `TaskDetailStore`.

Scope:

1. Add `QueueBackedTaskReviewReadModelWriter`.
2. Replace the server `TaskReviewReadModelWriter` bean with the queue-backed
   writer.
3. Wire the queue to `TaskDetailStoreReviewMaterializer`.
4. Keep `TaskReviewReadModel` backed by the existing
   `TaskDetailStoreTaskReviewReadModel` read side.
5. Ensure final-notification enrichment still happens before terminal report
   event snapshot creation and submission.
6. Ensure queue submit rejection or queue failure is caught, logged, and
   counted by the writer without rolling back append/finality/result handling.
7. Add server tests that create items, report terminal work, drain the queue,
   and verify rows through the existing `TaskReviewReadModel`.

Acceptance:

1. Production server write hooks submit queue events instead of directly
   upserting review rows.
2. The materializer writes current `TaskDetailStore` rows.
3. Review/export reads remain compatible through the existing read model.
4. Tests use queue drain/await-idle before asserting materialized rows.
5. Existing console review/export endpoints keep equivalent field coverage.
6. Queue submit failure is visible as best-effort materialization failure and
   does not fail append, finality, or result convergence.

RMP-3 evidence:

```powershell
mvn -pl xa-mass-server -am "-Dtest=QueueBackedTaskReviewMaterializationIntegrationTest,QueueBackedTaskReviewReadModelWriterTest,TaskDetailStoreReviewMaterializerTest,TaskDetailStoreTaskReviewReadModelTest,InProcessTaskReviewReportQueueTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-05-29: build success, 14 tests passed.

## Slice RMP-4: Prove Memory And JDBC Backing Paths

Goal: verify that the queued materializer works with both current backing
implementations before any future projection retirement work starts.

Scope:

1. Prove memory mode through `InMemoryTaskShellStore` as the `TaskDetailStore`
   backing.
2. Prove JDBC mode through `JdbcStorageRuntime.taskDetailStore()` as the
   `TaskDetailStore` backing.
3. Prove accepted, terminal, idempotency, and export reads after queue drain.
4. Add or update H2/Postgres-oriented tests where existing infra allows it.
5. Do not move JDBC DDL or delete `JdbcTaskCompatibilityProjection`.
6. Prove bounded drain and shutdown behavior as best-effort, not exactly-once
   or crash-replay behavior.

Acceptance:

1. Memory review/export writes and reads pass through the queued materializer.
2. JDBC review/export writes and reads pass through the queued materializer.
3. No server-local JDBC review store is required for this roadmap.
4. Tests prove drain-before-read behavior for both backing modes.
5. Tests or documented evidence show unprocessed/failed detail events are
   observable when drain or shutdown cannot apply them.

RMP-4 evidence:

```powershell
mvn -pl xa-mass-server -am "-Dtest=QueueBackedTaskReviewBackingStoreTest,QueueBackedTaskReviewMaterializationIntegrationTest,QueueBackedTaskReviewReadModelWriterTest,TaskDetailStoreReviewMaterializerTest,TaskDetailStoreTaskReviewReadModelTest,InProcessTaskReviewReportQueueTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-05-29: build success, 16 tests passed.

## Slice RMP-5: Proof, Guards, And PIR Handoff

Goal: close this roadmap with the queued write path active and the old storage
contract still present.

Scope:

1. Add or update guards:
   - review queue/materializer types stay out of `platform_infra/`
   - engine production does not import server review materialization types
   - queue/materializer APIs do not expose scheduling, lease, retry, dispatch,
     or terminal-policy method names
2. Add source scan or architecture guard documenting that production review
   writes go through the queue-backed writer.
3. Update `PROJECTION_INFRASTRUCTURE_RETIREMENT_ROADMAP.md` so PIR depends on
   queued materialization proof, not immediate server-local store extraction.
4. Update docs only to describe the new current write path:
   - review write path is queued and server-owned
   - persistence backing remains `TaskDetailStore`
   - review rows remain non-runtime materialization
   - detail materialization remains best-effort and non-result truth
5. Create PIR handoff notes listing remaining old projection symbols and tests,
   but do not delete them.

Acceptance:

1. Guards prevent queue/materializer contracts from moving into shared infra.
2. Docs describe review materialization writes as server-owned and queued.
3. PIR has a clear precondition: RMP complete and production review writes go
   through the queue-backed materializer.
4. Remaining projection infrastructure is classified as later PIR work, not
   deleted in RMP.
5. Guards or tests make silent detail-materialization loss and mutable event
   retention visible.

RMP-5 evidence:

```powershell
mvn -pl xa-mass-server -am "-Dtest=ServerMainSourceArchitectureGuardTest,QueueBackedTaskReviewBackingStoreTest,QueueBackedTaskReviewMaterializationIntegrationTest,QueueBackedTaskReviewReadModelWriterTest,TaskDetailStoreReviewMaterializerTest,TaskDetailStoreTaskReviewReadModelTest,InProcessTaskReviewReportQueueTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-05-29: build success, 25 tests passed.

Additional review regression evidence:

```powershell
mvn -pl xa-mass-server -am "-Dtest=*Review*" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Result on 2026-05-29: build success, 23 tests passed.

## Implementation Order

```text
RMP-0 -> RMP-1 -> RMP-2 -> RMP-3 -> RMP-4 -> RMP-5
```

RMP-1 and RMP-2 may be implemented in one phase if small enough. RMP-3 must
not land before deterministic queue drain support exists. RMP-4 must land
before any PIR deletion slice starts.

## Verification Candidates

```powershell
mvn -pl xa-mass-server -am '-Dtest=TaskDetailStoreTaskReviewReadModelTest,TaskApiControllerTest,ControlConsoleRoutingIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-server -am '-Dtest=*Review*' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

```powershell
mvn -pl xa-mass-engine,xa-mass-server -am '-Dtest=EngineProofOwnershipGuardTest,EngineSchedulingCoreArchitectureGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The exact test list should be corrected in RMP-0 after the current review
read/write inventory is complete.
