# Java External SDK Task-Scoped Invocation Roadmap

Status: implemented mainline. TSI-0, TSI-1, and TSI-2 are complete; TSI-3 is
deferred until append receipts can preserve item/message identity.

This roadmap adds task-scoped invocation conveniences to
`sdk/xa-mass-java-sdk`.

Related roadmap:
[`INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md`](../doc/archive/integrations/2026-06-02_INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md).

Relationship to EWH:

- EWH is complete and archived. It owns SDK public-contract correctness,
  including auth behavior and stale README result-read method names.
- This roadmap owns task-scoped invocation ergonomics: typed task routing
  helpers, task-bound SDK calls, and the decision record for future
  scenario-proven bulk append ergonomics.
- TSI examples must consume EWH's corrected method names and may update only
  snippets directly touched by task-scoped invocation.

It does not add a new server RPC route. The server already exposes the correct
mainline primitive:

```text
POST /api/v1/tasks/{taskId}/items:sync
```

The SDK problem is ergonomic: callers should be able to create one task shell
with a WorkerGroup selector, append many items, and optionally do synchronous
single-item calls against that same task without scattering one-off task shells.

There are two distinct recommended workflows:

```text
Pattern A: task-scoped interactive invocation
  contract = SESSION
  workloadClass = INTERACTIVE
  intake remains OPEN
  task is approved to READY before sync append
  repeated appendItemSync against the same task

Pattern B: high-volume producer
  contract = BATCH
  workloadClass = BULK
  appendItems in chunks while intake is OPEN
  seal explicitly when no more items are expected
  read results window/archive after convergence
```

Do not blur these patterns. A sealed BATCH task is not a valid target for
`items:sync`, because the public sync append route requires an active task with
an open intake window.

## Current Code Observations

- `TaskClient` already exposes:
  - `create(TaskCreateRequest)`
  - `forTask(String taskId)`
  - `appendItems(String taskId, TaskItemBatch)`
  - `appendItemSync(String taskId, TaskItemSyncRequest)`
  - `seal(String taskId)`
  - `results(String taskId, TaskResultReadRequest)`
- `TaskHandle` provides task-scoped delegation for append, sync append,
  command, approve, seal, result window, and archive operations. It stores only
  `taskId` plus the `TaskClient`.
- `TaskCreateRequest` and server `TaskShellCreateApiRequest` carry
  `sharedConfig`; the Java SDK now provides typed create helpers for
  `workerGroupId`, `workerGroupIds`, `targetWorkerAttributes`, `routingCode`,
  and route attributes.
- Engine matching already consumes task-level worker selectors through
  `Task.sharedConfig.workerGroupId` / `workerGroupIds`.
- The current public sync wait remains message-scoped: `taskId + messageId`.
  Timeout ends the HTTP wait only; the appended item keeps running.
- `POST /api/v1/tasks/{taskId}/items:sync` currently requires the task status
  to be `READY` or `RUNNING` and the task intake status to be `OPEN`.
  Create-then-sync examples must therefore either start from an existing active
  task or explicitly approve a SESSION task before sync append.
- EWH corrected broad Java SDK README result-read examples. TSI examples must
  keep using `results(...)`, and this roadmap may update only snippets directly
  touched by task-scoped invocation.
- `integrations/xa-mass-scenario-launcher` already proves the bulk producer
  pattern and currently carries local chunking code:
  `TaskScenarioSeeder.DEFAULT_ITEM_BATCH_SIZE = 500` and a manual
  `chunks(...)` loop before repeated `appendItems(...)` calls.
- `TaskAppendResult` currently reports task-level append status
  (`taskId`, `added`, `status`, `intakeStatus`, `message`) and does not expose
  per-item message ids. A generic SDK bulk helper must not hide identity it
  cannot return.

## Owner Decision

Task-scoped invocation ergonomics belong to `sdk/xa-mass-java-sdk`.

The SDK may own:

- typed request builders that map external Java names to public task API JSON;
- task routing helper methods for `workerGroupId`, `workerGroupIds`,
  `targetWorkerAttributes`, `routingCode`, and route attributes;
- advanced/manual narrowing helper methods only in a future roadmap where they
  preserve the current kernel meaning; `targetWorkerId` is not part of this
  roadmap and is not a normal business-routing selector;
- a task-scoped handle or facade that keeps the caller anchored to one task id;
- documentation and snippet tests proving the intended usage pattern.

The SDK must not own:

- server route semantics;
- engine matching decisions;
- task lifecycle or result convergence;
- WorkerGroup capability truth;
- a global one-off RPC API that creates a new task shell per call by default.

Server remains the HTTP host. Engine remains the scheduling/result kernel.
Worker runtime remains the worker lifecycle and candidate-source owner.

## Boundary Decision

Prefer task-scoped SDK ergonomics over a global invoke facade.

Good shape:

```java
TaskCreateResult task = mass.tasks().create(TaskCreateRequest.builder()
        .project("deviceProbe")
        .userId("agent")
        .contract(TaskContract.SESSION)
        .workerGroupId("phone-device-probe")
        .targetWorkerAttribute("fingerprintProfile", "fp-sg-alpha")
        .executionSpec(TaskExecutionSpec.builder()
                .workloadClass("INTERACTIVE")
                .batchSize(1)
                .build())
        .build());

TaskHandle handle = mass.tasks().forTask(task.taskId());
handle.approve();

TaskSyncAppendResult result = handle.appendItemSync(TaskItemSyncRequest.builder()
                .eventCode("probe.phone.metadata")
                .item(Map.of("phone", "+14155550100"))
                .timeoutMs(5000L)
                .build());
```

Also acceptable:

```java
TaskHandle handle = mass.tasks().forTask(existingReadyOrRunningOpenTaskId);
handle.appendItemSync(TaskItemSyncRequest.builder()
        .eventCode("probe.phone.metadata")
        .item(Map.of("phone", "+14155550100"))
        .timeoutMs(5000L)
        .build());
```

Not acceptable as the default product shape:

```java
mass.invoke("phone-device-probe", "probe.phone.metadata", payload);
```

That global shape hides task lifecycle and encourages scattered one-item tasks.
If a one-shot helper is ever added, it must be explicitly named as one-shot and
must still translate through task create, item append, seal/approve if needed,
and runtime result wait.

`invokeSync(...)` and a new `TaskItemInvocation` type are intentionally not the
preferred first-slice shape. They pull callers toward RPC vocabulary and create
`payload` vs `item` wording drift. If a future roadmap promotes
`invokeSync(...)`, the method documentation must state that it appends one item
to an existing task and waits for that item's stable-final result.

## TSI-0 Decisions

These decisions are part of this roadmap baseline and should be treated as the
starting contract for implementation:

- Task-scoped facade name: `TaskHandle`.
- Task-scoped entry point: `TaskClient.forTask(String taskId)`.
- Sync request type: reuse `TaskItemSyncRequest`; do not introduce
  `TaskItemInvocation` in this roadmap.
- First sync method name: `appendItemSync(...)`. Do not add
  `appendAndWait(...)` in the first slice; avoid creating two new names for
  the same route.
- Typed routing helpers live on `TaskCreateRequest.Builder` in this roadmap.
  Do not add matching `TaskUpdateRequest.Builder` helpers until task routing
  mutation semantics are explicitly reviewed.
- Include first-slice helpers for `workerGroupId`, `workerGroupIds`,
  `targetWorkerAttribute(s)`, `routingCode`, and `routeAttribute(s)`.
- Do not add `targetWorkerId(...)` in this roadmap. It remains an
  advanced/manual debug narrowing concept and should get a separate SDK review
  if it is promoted later.
- Do not add `adapterNodeId(...)`; AdapterNode remains placement/runtime
  registration truth, not task capability truth.
- SDK helper constants are SDK-owned literals and must not import
  `xa-mass-base`.
- TSI-3 bulk helper is deferred because current append receipts do not expose
  per-item message ids. Scenario-launcher may keep local chunking until the
  public append receipt contract can preserve identity.

## Slice State

| Slice | State | Execution Meaning |
| --- | --- | --- |
| TSI-0 | complete | Naming and API shape are fixed by this roadmap revision. |
| TSI-1 | complete | Typed `TaskCreateRequest.Builder` helpers and focused SDK tests are implemented. |
| TSI-2 | complete | `TaskHandle`, README snippets, SDK tests, and one server E2E proof are implemented. |
| TSI-3 | deferred | Do not add a bulk helper until append receipts expose item/message identity. |
| Conditional integrated proof | conditional | Use only when scenario-launcher or worker-pack adopts new task-scoped APIs. |

## Implementation Evidence

- `TaskCreateRequest.Builder` owns SDK literals for WorkerGroup/routing helper
  keys and does not import `xa-mass-base`.
- `TaskClient.forTask(...)` returns `TaskHandle`; the handle delegates to
  existing public task routes and does not create task shells implicitly.
- `TaskCommandRequest.approve()` backs `TaskHandle.approve()`.
- Java SDK README examples use typed routing helpers and task-scoped sync
  invocation.
- `JavaExternalSdkTaskScopedInvocationIntegrationTest` proves create
  `SESSION` task -> approve -> `TaskHandle.appendItemSync(...)` -> results
  against a real server and external polling worker.
- `TaskAppendResult` still lacks per-item message ids, so TSI-3 remains
  deferred by design.

## Target Shape

```text
sdk/xa-mass-java-sdk
  TaskCreateRequest.Builder
    workerGroupId(...)
    workerGroupIds(...)
    targetWorkerAttribute(...)
    targetWorkerAttributes(...)
    routingCode(...)
    routeAttribute(...)

  TaskClient
    forTask(taskId) -> TaskHandle

  TaskHandle
    appendItems(...)
    appendItemSync(...)
    command(...)
    approve()
    seal()
    results(...)

  TaskItemSyncRequest
    eventCode
    item
    timeoutMs
    clientRequestId
```

Do not use `TaskSession`: this is not a runtime session and should not collide
with `TaskContract.SESSION`, `PollingWorkerSession`, or
`WebSocketWorkerSession`.

## Hard Rules

- Do not add a server invocation route or global SDK `mass.invoke(...)`.
- Do not hide task lifecycle. Create, approve, append, seal, and result reads
  remain visible operations.
- Do not import `TaskSharedConfig` or any `xa-mass-base` type into
  `xa-mass-java-sdk` for these helpers.
- WorkerGroup selection belongs on task creation through `sharedConfig`;
  `eventCode` stays item-level dispatch evidence.
- Do not add task-level `adapterNodeId(...)` or `targetWorkerId(...)` helpers
  in this roadmap.
- Do not add a bulk append helper until append receipts expose enough identity
  for the SDK to return what it submits.

## Non-Goals

- No new `/rpc/invoke/**` server route in this roadmap.
- No new `/api/v1/invocations/**` server route in this roadmap.
- No global SDK `mass.invoke(group, action, payload)` default API.
- No server-side task auto-creation hidden behind sync append.
- No worker matching changes.
- No lifecycle or terminal-policy changes.
- No compatibility alias for stale SDK example method names.
- No dependency from `xa-mass-java-sdk` to `xa-mass-base` just to reuse
  `TaskSharedConfig` constants.
- No task-level `adapterNodeId(...)` helper in this roadmap. AdapterNode is
  placement/runtime registration truth, not task capability truth. Add such a
  helper only after a future server/kernel contract explicitly defines
  task-level node targeting.

## Do Not Start With

Do not start implementation by adding `mass.invoke(...)`, a new server
invocation route, task-level AdapterNode targeting, or hidden one-shot task
auto-creation. Start with SDK task-create routing helpers and the task-scoped
handle so task lifecycle remains visible.

## TSI-0: API Inventory And Naming Decision

Goal: document the SDK public shape before adding convenience methods.

Scope:

- inventory current `TaskClient`, task request builders, and README examples;
- use `TaskHandle` as the task-scoped facade name;
- explicitly reject `TaskSession` unless a later design gives it real runtime
  session ownership, which is not part of this roadmap;
- reuse `TaskItemSyncRequest`; do not add `TaskItemInvocation`;
- use `appendItemSync(...)` as the first task-scoped sync method name;
- put typed routing helpers on `TaskCreateRequest.Builder` only;
- explicitly keep `adapterNodeId(...)` out of task-create helpers for this
  roadmap because AdapterNode is not task capability truth;
- keep `targetWorkerId(...)` out of this roadmap. If promoted later, it must
  be documented as group-scoped advanced/manual narrowing and must not be
  presented beside `workerGroupId(s)` as a normal business-routing selector;
- document that SDK routing helper constants are SDK-owned literals and must
  not import `xa-mass-base`.
- record the execution relationship with completed EWH so README/example
  cleanup is not double-owned.

Acceptance:

- the selected API shape is recorded in this roadmap;
- no code behavior changes are required in this slice;
- the selected naming keeps task scope visible.
- EWH overlap is resolved: EWH owns broad stale README cleanup, and this
  roadmap updates only examples directly touched by task-scoped invocation.

Verification:

```powershell
rg -n "appendItemSync|TaskCreateRequest|workerGroupId|workerGroupIds|targetWorkerAttributes" sdk/xa-mass-java-sdk doc
```

## TSI-1: Typed Task Routing Helpers

Goal: make WorkerGroup-based task creation easy without exposing raw
`sharedConfig` mechanics as the normal SDK path.

Scope:

- add `TaskCreateRequest.Builder.workerGroupId(String)`;
- add `TaskCreateRequest.Builder.workerGroupIds(Collection<String>)`;
- add `TaskCreateRequest.Builder.targetWorkerAttribute(String, String)` and
  `targetWorkerAttributes(Map<String, String>)`;
- add `TaskCreateRequest.Builder.routingCode(String)`,
  `routeAttribute(String, String)`, and
  `routeAttributes(Map<String, String>)` because current server/engine
  contracts already support them through `sharedConfig`;
- do not add `targetWorkerId(String)` in this roadmap;
- do not add `adapterNodeId(String)` in this roadmap;
- preserve existing `sharedConfig(...)` escape hatch for advanced callers;
- do not import `TaskSharedConfig` from `xa-mass-base`.
- define builder merge semantics explicitly:
  - builder call order wins;
  - `sharedConfig(Map)` replaces current shared-config builder state;
  - typed helper calls after `sharedConfig(Map)` overwrite their typed keys;
  - a later `sharedConfig(Map)` may replace earlier typed helper values;
  - `workerGroupId(...)` removes `workerGroupIds`;
  - `workerGroupIds(...)` removes `workerGroupId`;
  - `targetWorkerAttributes(...)` replaces current target-attribute map;
  - repeated `targetWorkerAttribute(k, v)` merges one key at a time;
  - route attributes follow the same replace-vs-merge rule as target
    attributes.
- define null/blank semantics explicitly:
  - blank or null `workerGroupId` removes `workerGroupId` and still clears
    `workerGroupIds`;
  - blank or null `workerGroupIds` entries are ignored, and an empty
    normalized collection removes `workerGroupIds` while clearing
    `workerGroupId`;
  - blank attribute keys are invalid;
  - blank or null attribute values remove that attribute key.

Acceptance:

- generated JSON still contains the public task API shape:
  `sharedConfig.workerGroupId`, `sharedConfig.workerGroupIds`,
  and `sharedConfig.targetWorkerAttributes`;
- helper merge/override behavior is fixed by call order and covered by tests;
- tests cover single WorkerGroup, multiple WorkerGroups, and fingerprint-like
  target attributes;
- tests cover `routingCode` and route attributes;
- tests cover `workerGroupId` / `workerGroupIds` mutual exclusion;
- tests cover typed-helper-before-raw-map and raw-map-before-typed-helper
  ordering;
- tests cover null/blank handling for selector helper values;
- README examples no longer require raw `sharedConfig("workerGroupId", ...)`
  for ordinary WorkerGroup routing.

Verification:

```powershell
mvn -pl sdk/xa-mass-java-sdk "-Dtest=TaskClientTest" test
```

## TSI-2: Task-Scoped Handle And Documentation

Goal: make repeated task operations read as one task workflow without
pretending the wrapper is a new runtime capability.

Scope:

- add `TaskClient.forTask(String taskId)` returning a task-scoped handle;
- expose task-scoped methods:
  - `appendItems(TaskItemBatch)`
  - `appendItemSync(TaskItemSyncRequest)`
  - `command(TaskCommandRequest)`
  - `approve()`
  - `seal()`
  - `results(TaskResultReadRequest)`
  - optionally `archive()` and `downloadArchive()`;
- keep the existing flat `TaskClient` methods as the low-level API;
- ensure the handle stores only `taskId` plus a reference to `TaskClient`, not
  lifecycle or result state.
- update Java SDK README examples directly related to task-scoped invocation:
  create task shell with WorkerGroup selector, append items, wait for one item,
  and read results through `results(...)`.
- do not turn README cleanup unrelated to task-scoped invocation into this
  roadmap's responsibility; that remains completed EWH.

Acceptance:

- the handle does not create task shells implicitly;
- every handle method delegates to existing public server routes;
- tests prove path construction and JSON shape;
- no server runtime code changes are required. A server E2E test may be added
  to prove the SDK flow against a real host.
- task-scoped sync examples show that the task is already `READY`/`RUNNING`
  with intake `OPEN`;
- examples must not call `seal()` before `appendItemSync(...)`;
- no active task-scoped README example uses stale result-read method names;
- no active task-scoped README example recommends global one-off invocation as
  the default;
- examples show `workerGroupId` at task creation and `eventCode` at item
  append/wait.
- server E2E proves the promoted create -> approve -> sync append flow through
  Java SDK calls against a real host:
  create a `SESSION` task, approve it to `READY` while intake is `OPEN`, call
  task-scoped `appendItemSync(...)`, and read the result through
  `results(...)`.

Verification:

```powershell
rg -n "mass\\.invoke|workerGroupId|appendItemSync|forTask" sdk/xa-mass-java-sdk doc
mvn -pl sdk/xa-mass-java-sdk "-Dtest=TaskClientTest" test
mvn -pl xa-mass-server -am "-Dtest=JavaExternalSdkTaskScopedInvocationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## TSI-3: Scenario-Proven Bulk Append Ergonomics

Goal: support the preferred high-volume pattern only after the SDK can preserve
append identity. Current state is deferred.

Scope:

- inventory `TaskScenarioSeeder` chunking before any future bulk helper work;
- do not add an SDK helper while `TaskAppendResult` lacks per-item message id
  or equivalent receipt identity;
- if the public append receipt contract later exposes identity, add a helper
  for chunking item appends against one task only if it can replace current
  scenario-launcher boilerplate in the same slice;
- make chunk size caller-configurable and default to a documented public ingest
  limit only if the SDK can keep that default aligned with server docs;
- return append receipts without hiding message ids;
- do not auto-seal unless the method name explicitly says it seals.

Acceptance:

- this roadmap records that TSI-3 is deferred under the current
  `TaskAppendResult` contract;
- no SDK bulk helper is added until it can preserve item/message identity;
- if the helper is implemented in a future slice, it never creates one task
  per item;
- if the helper is implemented in a future slice, tests cover chunking
  boundaries and empty input;
- if the helper is implemented in a future slice, scenario-launcher consumes it
  in the same slice. If scenario-launcher cannot consume it, do not add the
  helper yet.

Verification:

```powershell
rg -n "record TaskAppendResult|chunks\\(|appendItems\\(" sdk/xa-mass-java-sdk integrations/xa-mass-scenario-launcher
```

## Conditional Integrated Proof

This is not a default slice for TSI-3-only adoption churn. TSI-2 already has a
required server E2E for the promoted task-scoped sync flow. Use this section
only if implementation changes an existing integration path such as
scenario-launcher or worker-pack beyond that required proof.

Scope:

- update an existing Java SDK black-box or worker-pack integration proof to use
  the task-scoped wrapper only if the wrapper is adopted by that path;
- do not add a duplicate server happy-path test if current proof already covers
  the invariant;
- preserve `PROOF_REGISTRY.md` ownership if the proof is updated.

Acceptance:

- integrated proof still goes through public task and worker APIs;
- WorkerGroup selector is visible on task create;
- item dispatch/result convergence is unchanged;
- no server startup seeding is introduced.

Verification:

```powershell
mvn -pl xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest,WorkerPackGeoLookupExternalSdkIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## Suggested Implementation Order

1. TSI-0 naming decision.
2. TSI-1 typed task routing helpers.
3. TSI-2 task-scoped handle and documentation.
4. Record TSI-3 as deferred under the current append receipt contract. Reopen
   it only after append receipts expose item/message identity and
   scenario-launcher can consume the helper in the same slice.
5. Conditional integrated proof only if wrapper adoption changes a real
   integration path.

Keep this roadmap SDK-first. If implementation discovers a server contract gap,
stop and record it as a separate server/API roadmap instead of hiding a server
semantic change inside SDK ergonomics.
