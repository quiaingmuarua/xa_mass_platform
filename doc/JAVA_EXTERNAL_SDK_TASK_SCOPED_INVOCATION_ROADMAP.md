# Java External SDK Task-Scoped Invocation Roadmap

Status: proposed SDK ergonomics roadmap.

This roadmap adds task-scoped invocation conveniences to
`integrations/xa-mass-java-sdk`.

Related roadmap:
[`INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md`](./archive/integrations/2026-06-02_INTEGRATIONS_EXTERNAL_SDK_WORKER_PACK_HARDENING_ROADMAP.md).

Relationship to EWH:

- EWH is complete and archived. It owns SDK public-contract correctness,
  including auth behavior and stale README result-read method names.
- This roadmap owns task-scoped invocation ergonomics: typed task routing
  helpers, task-bound SDK calls, and bulk append helpers proven by current
  scenario-launcher usage.
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
  repeated appendItemSync / appendAndWait against the same task

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
  - `appendItems(String taskId, TaskItemBatch)`
  - `appendItemSync(String taskId, TaskItemSyncRequest)`
  - `seal(String taskId)`
  - `results(String taskId, TaskResultReadRequest)`
- `TaskCreateRequest` and server `TaskShellCreateApiRequest` carry
  `sharedConfig`, but the Java SDK does not provide typed helpers for task
  routing selectors such as `workerGroupId`, `workerGroupIds`,
  `targetWorkerAttributes`, or route attributes.
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

## Owner Decision

Task-scoped invocation ergonomics belong to `integrations/xa-mass-java-sdk`.

The SDK may own:

- typed request builders that map external Java names to public task API JSON;
- task routing helper methods for `workerGroupId`, `workerGroupIds`,
  `targetWorkerAttributes`, `routingCode`, and route attributes;
- advanced/manual narrowing helper methods only when they preserve the current
  kernel meaning; `targetWorkerId` is not a normal business-routing selector;
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
`payload` vs `item` wording drift. If the final API uses `invokeSync(...)`, the
method documentation must state that it appends one item to an existing task and
waits for that item's stable-final result. First implementation should prefer
`TaskItemSyncRequest`.

## Target Shape

```text
integrations/xa-mass-java-sdk
  TaskCreateRequest.Builder
    workerGroupId(...)
    workerGroupIds(...)
    targetWorkerAttribute(...)
    targetWorkerAttributes(...)
    routingCode(...)
    routeAttribute(...)
    targetWorkerId(...) as an advanced/manual narrowing helper only if TSI-0
      records the guardrails

  TaskClient
    forTask(taskId) -> TaskHandle / BoundTaskClient

  Task-scoped handle
    appendItems(...)
    appendAndWait(...) or appendItemSync(...)
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

Naming is a slice decision. The important contract is that the facade is scoped
to an existing task id. Do not use `TaskSession`: this is not a runtime session
and should not collide with `TaskContract.SESSION`, `PollingWorkerSession`, or
`WebSocketWorkerSession`.

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

Goal: decide the SDK public shape before adding convenience methods.

Scope:

- inventory current `TaskClient`, task request builders, and README examples;
- decide task-scoped facade name:
  `TaskHandle`, `BoundTaskClient`, or equivalent;
- explicitly reject `TaskSession` unless a later design gives it real runtime
  session ownership, which is not part of this roadmap;
- decide whether invocation payload type reuses `TaskItemSyncRequest` or adds a
  smaller `TaskItemInvocation` wrapper; first-slice default is to reuse
  `TaskItemSyncRequest`;
- decide final sync method naming: prefer `appendAndWait(...)`,
  `appendItemSync(...)`, or equivalent task/item vocabulary over RPC-like
  `invokeSync(...)`;
- decide whether typed routing helpers live on `TaskCreateRequest.Builder`
  only, or also on `TaskUpdateRequest.Builder`;
- explicitly keep `adapterNodeId(...)` out of task-create helpers for this
  roadmap because AdapterNode is not task capability truth;
- decide whether `targetWorkerId(...)` is included as an advanced/manual
  narrowing helper. It must be documented as group-scoped and must not be
  presented beside `workerGroupId(s)` as a normal business-routing selector;
- document that SDK routing helper constants are SDK-owned literals and must
  not import `xa-mass-base`.
- record the execution relationship with completed EWH so README/example cleanup is not
  double-owned.

Acceptance:

- the selected API shape is recorded in this roadmap or an inventory;
- no code behavior changes are required in this slice;
- the selected naming keeps task scope visible.
- EWH overlap is resolved: EWH owns broad stale README cleanup, and this
  roadmap updates only examples directly touched by task-scoped invocation.

Verification:

```powershell
rg -n "appendItemSync|TaskCreateRequest|workerGroupId|workerGroupIds|targetWorkerAttributes" integrations/xa-mass-java-sdk doc
```

## TSI-1: Typed Task Routing Helpers

Goal: make WorkerGroup-based task creation easy without exposing raw
`sharedConfig` mechanics as the normal SDK path.

Scope:

- add `TaskCreateRequest.Builder.workerGroupId(String)`;
- add `TaskCreateRequest.Builder.workerGroupIds(Collection<String>)`;
- add `TaskCreateRequest.Builder.targetWorkerAttribute(String, String)` and
  `targetWorkerAttributes(Map<String, String>)`;
- add `TaskCreateRequest.Builder.targetWorkerId(String)` only if TSI-0 records
  it as an advanced/manual narrowing helper with explicit WorkerGroup selector
  guardrails;
- add route/routing helpers only if current server/engine contracts already
  support them through `sharedConfig`;
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
- validate or document that `targetWorkerId(...)` requires an explicit
  `workerGroupId(...)` or `workerGroupIds(...)` selector.

Acceptance:

- generated JSON still contains the public task API shape:
  `sharedConfig.workerGroupId`, `sharedConfig.workerGroupIds`,
  and `sharedConfig.targetWorkerAttributes`;
- if `targetWorkerId(...)` is added, generated JSON contains
  `sharedConfig.targetWorkerId` and docs mark it as advanced/manual narrowing;
- helper merge/override behavior is fixed by call order and covered by tests;
- tests cover single WorkerGroup, multiple WorkerGroups, and fingerprint-like
  target attributes;
- tests cover target worker id only if the advanced/manual helper is added;
- tests cover `workerGroupId` / `workerGroupIds` mutual exclusion;
- tests cover typed-helper-before-raw-map and raw-map-before-typed-helper
  ordering;
- tests cover null/blank handling for selector helper values;
- if `targetWorkerId(...)` is added, tests cover its explicit WorkerGroup
  selector requirement or document why validation is intentionally deferred to
  server/engine;
- README examples no longer require raw `sharedConfig("workerGroupId", ...)`
  for ordinary WorkerGroup routing.

Verification:

```powershell
mvn -pl integrations/xa-mass-java-sdk "-Dtest=TaskClientTest" test
```

## TSI-2: Task-Scoped Handle And Documentation

Goal: make repeated task operations read as one task workflow without
pretending the wrapper is a new runtime capability.

Scope:

- add `TaskClient.forTask(String taskId)` returning a task-scoped handle;
- expose task-scoped methods:
  - `appendItems(TaskItemBatch)`
  - `appendAndWait(TaskItemSyncRequest)` / `appendItemSync(...)` or equivalent
    task/item vocabulary
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
- no server code changes are required.
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
  task-scoped `appendItemSync(...)` / `appendAndWait(...)`, and read the
  result through `results(...)`.

Verification:

```powershell
rg -n "mass\\.invoke|workerGroupId|appendItemSync|appendAndWait|forTask" integrations/xa-mass-java-sdk doc
mvn -pl integrations/xa-mass-java-sdk "-Dtest=TaskClientTest" test
mvn -pl xa-mass-server -am "-Dtest=JavaExternalSdkTaskScopedInvocationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## TSI-3: Scenario-Proven Bulk Append Ergonomics

Goal: support the preferred high-volume pattern only where current code already
proves repeated boilerplate: one task shell, many items.

Scope:

- inventory `TaskScenarioSeeder` chunking and confirm whether an SDK helper can
  remove real duplicated append/chunk logic without hiding message ids;
- add an SDK helper for chunking item appends against one task only if it can
  replace that current scenario-launcher boilerplate;
- make chunk size caller-configurable and default to the known public ingest
  limit only if the SDK can keep the default aligned with server docs;
- return append receipts without hiding message ids;
- do not auto-seal unless the method name explicitly says it seals.

Acceptance:

- helper never creates one task per item;
- helper preserves item/message identity returned by server receipts;
- tests cover chunking boundaries and empty input;
- docs explain bulk append as the default high-volume pattern.
- if the helper is implemented, scenario-launcher consumes it in the same
  slice. If scenario-launcher cannot consume it, do not add the helper yet;
  record the reason and leave the roadmap at TSI-2.

Verification:

```powershell
mvn -pl integrations/xa-mass-java-sdk "-Dtest=TaskClientTest" test
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
4. TSI-3 scenario-proven bulk append ergonomics, only if current launcher
   boilerplate justifies it.
5. Conditional integrated proof only if wrapper adoption changes a real
   integration path.

Keep this roadmap SDK-first. If implementation discovers a server contract gap,
stop and record it as a separate server/API roadmap instead of hiding a server
semantic change inside SDK ergonomics.
