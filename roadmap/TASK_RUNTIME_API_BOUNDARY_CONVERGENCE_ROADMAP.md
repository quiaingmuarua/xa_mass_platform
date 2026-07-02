# Task Runtime API Boundary Convergence Roadmap

Status: active. This roadmap is a boundary-convergence roadmap for task
runtime interfaces after the score-band Redis runtime keyspace has been
established. It does not redesign the Redis keyspace and does not add product
features. It exists because the current runtime mechanism is improving while
the engine-facing API surface and service wiring are getting thicker.

## Problem

The task-runtime storage mechanism now has a clearer owner direction:
accepted backlog, task score visibility, claim, active lease, retry, result
finality, progress, and discard are task-runtime truth. The code still exposes
and wires those capabilities through broad engine objects:

- `TaskManager` remains the shell/lifecycle facade, lock host, event holder,
  runtime lane installer, and compatibility caller surface.
- `TaskLifecycleService` no longer depends on the whole `TaskManager` object,
  but this remains a guarded boundary because lifecycle code must not regain a
  broad manager reach-through.
- `TaskRuntimeServingLane` is the new engine-facing runtime path. It does not
  store broad `TaskCommandPort`, `TaskQueryPort`, or `TaskEventService`
  objects, and the same-shape `TaskCommandService` / `TaskQueryService`
  wrappers have been deleted. This remains a guarded boundary because broad
  shell command/query reach-through must not leak back into the serving lane
  through future assembly changes.
- `xa-mass-task-runtime` exposes the right core port families, but it also
  contains public value/read-model/residue classes that make the module look
  like a DTO bucket instead of a small runtime owner API.

The immediate risk is not missing functionality. The risk is that old engine
objects and new task-runtime objects remain live as a circular mainline:

```text
TaskManager
    -> TaskLifecycleService through narrow function hooks
    -> TaskRuntimeServingLane
        -> task shell read/write and event projection hooks
```

This roadmap converges the API and wiring so task-runtime is a real item
runtime owner and engine remains the task shell, policy, and aggregate
lifecycle owner.

## Owner Boundary Decision

Engine owns:

- task shell lifecycle: create, approve, reject, pause, resume, block, cancel,
  delete, seal, and task aggregate state;
- intake admission before append;
- task-level policy resolution and task aggregate terminal policy;
- worker selection, assignment orchestration, and transport handoff;
- external server/SDK command contracts, read-view contracts, and trace/event
  projection.

Task-runtime owns:

- accepted item backlog;
- task runtime score visibility and runtime metadata;
- backlog claim and active lease truth;
- retry promotion and lease expiry discovery;
- result apply, duplicate/stale classification, retry scheduling, and message
  finality;
- runtime progress snapshots, active task-local reads, and task runtime discard.

Engine may translate task shell status into runtime gate/score commands.
Task-runtime may return runtime outcomes. Neither side should import the
other's full owner object to make that translation happen.

## Target Interface Families

### 1. External Task Command

Owner: engine shell/API mutation surface.

Caller: server, SDK, admin/test harnesses that need product-level task writes.

Allowed capabilities:

- create task shell;
- patch task definition;
- semantic lifecycle commands: approve, reject, pause, resume, block, cancel,
  delete, and seal;
- append task items after engine intake validation.

Forbidden:

- score, lease, retry, result-finality, Redis key, runtime epoch, and active
  lease internals;
- diagnostic snapshots, pageable final-result windows, trace views, assignment
  diagnostics, and other read-only projections;
- caller-supplied state-machine rules such as `allowedCurrentStatuses`,
  `currentStatus -> targetStatus`, or raw status mutation commands;
- broad mutable shell writes such as `updateTask(Task)`;
- returning the full mutable `Task` aggregate from create/update commands when
  the caller only needs command outcome and task id;
- use by `TaskRuntimeServingLane` as an internal runtime hook.

Write methods may return command receipts, created shell identifiers, or
write-result summaries, but those return values are command responses, not the
general read/view surface. Lifecycle commands express business intent only.
The engine shell owner decides whether the current task state allows that
intent, and the task-runtime score/gate is synchronized after the shell owner
applies the transition. Duplicate commands should converge through owner
outcomes such as `APPLIED`, `ALREADY_APPLIED`, `REJECTED`, `CONFLICT`, or
`NOT_FOUND`; callers must not provide the state-machine allowlist.

Target command outcomes should stay small:

```java
record TaskCommandOutcome(String taskId,
                          boolean accepted,
                          boolean applied,
                          String reasonCode,
                          String message) {
}

record TaskAppendOutcome(String taskId,
                         boolean accepted,
                         int acceptedCount,
                         List<String> messageIds,
                         String reasonCode,
                         String message) {
}
```

`accepted` is the stable coarse protocol result. `applied` distinguishes a new
state mutation from an idempotent already-applied command. `reasonCode` is the
stable fine-grained cause for API responses, SDK handling, logs, and tests.
Do not create a separate outcome class for every lifecycle command unless a
command genuinely needs additional command-owned fields. Append keeps
`messageIds` because the append owner generates accepted item identity and
SDK/server callers need those ids to correlate later results.

### 2. External Task Read View

Owner: read-model projection owner under the engine/API boundary, not the core
runtime mutation owner.

Caller: server/API handlers, SDK read-only operations, operator diagnostics,
control-console views, and tests that need snapshots without participating in
runtime mutation.

Target surface name: `TaskReadViewPort` or equivalent. In the current SDK/server
boundary this accepted equivalent is SDK-owned `TaskReadOperations`. It may
internally compose smaller readers, but externally it is the single task
read-only surface.

Allowed capabilities:

- stable business reads: task shell, task aggregate status, task progress, and
  supported final-result reads;
- diagnostic/operator reads: runtime snapshot, assignment snapshot, trace
  snapshot, listener/loop diagnostics, and bounded full diagnostic snapshots;
- bounded final-result windows when a product/read contract still needs them;
- direct projection reads that bypass `TaskManager`, `TaskRuntimeServingLane`,
  and layered delegates when the projection owner can read the underlying
  owner truth safely;
- read-only aggregation across task shell, task-runtime, trace, assignment, and
  server projection sources.

Forbidden:

- append, claim, result apply, retry promotion, lease repair mutation, task
  shell lifecycle mutation, dispatch wakeup, or any owner-truth write;
- becoming the policy or lifecycle owner for the fields it displays;
- forcing core runtime ports to expose pageable view/window concepts;
- being used by `TaskRuntimeServingLane`, assignment, result ingest, repair, or
  other mutation hot paths;
- exposing Redis keys or storage adapter details as a view contract.

Server/API authorization can distinguish ordinary business reads from operator
diagnostic reads. The Java boundary should still converge them under the same
read-only surface so read/view concerns do not leak back into command or
runtime mutation code.

Target direction: the first `TaskReadViewPort` implementation stays in existing
engine/server boundaries. A separate task-view or read-model module is only a
future cleanup option after the read surface stabilizes, and it must remain
read-only and projection-owned.

### 3. Engine Shell/Lifecycle Internal Hooks

Owner: engine shell/lifecycle.

Caller: `TaskLifecycleService` and closely related package-private engine
lifecycle code.

Allowed capabilities:

- load and update task shell;
- delete task shell record;
- publish task ready and task terminal events;
- request task dispatch;
- append already-admitted runtime ingress items;
- sync runtime scheduler eligibility after a shell lifecycle transition;
- discard task-runtime state when shell deletion or terminal cleanup requires
  it;
- read runtime progress for task terminal policy evaluation.

Forbidden:

- depending on the concrete `TaskManager` type;
- exposing these hooks as server/SDK public contracts;
- reaching into task-runtime Redis/storage implementation details.

Internal shell hooks may still persist a full `Task` object while the engine
shell store uses that aggregate model. That write hook is not the external
command API and must not leak to server/SDK callers.

### 4. Task-Runtime Core Ports

Owner: `xa-mass-task-runtime`.

Caller: engine serving lane, task-runtime starter loops, memory/Redis contract
tests, and infra implementations through the same public ports.

The core runtime API should remain four small port families plus an optional
aggregate `TaskRuntimePortSet` in the starter SDK.

#### Work Port

Owns backlog append and claim.

Target capabilities:

```java
AppendBatchOutcome appendBacklog(String taskId, List<AppendItemInput> items, int maxBatchSize);

ClaimReadyOutcome claimBacklog(ScoreCandidate candidate,
                               List<WorkerReservationEvidence> reservations,
                               int maxItems,
                               long leaseMillis,
                               long nowMillis);
```

`AppendItemInput` must contain only caller-owned item identity and payload
reference or payload JSON. It is the only append input shape allowed across the
engine/task-runtime boundary. Physical runtimes may encode implementation-local
backlog frame fields, but those fields are not public API. Engine/starter
callers must not construct any value that contains runtime-owned retry count,
frame type, enqueue timestamp, or lease fields. `AppendAdmissionPolicy` is not
a runtime append item shape; admission limits belong to engine/server intake or
a primitive `maxBatchSize` argument.

#### Score Port

Owns task runtime visibility and score-band discovery.

Target capabilities:

```java
void putRuntimeMeta(TaskRuntimeMeta meta);

void setTaskScore(String taskId, String laneKey, RuntimeEpoch epoch, TaskScore score);

void removeTaskScore(String taskId, String laneKey, RuntimeEpoch epoch);

Optional<ScoreCandidate> scoreCandidate(String taskId, String laneKey);

ScoreCandidateBatch discoverSchedulable(String laneKey, long nowMillis, int limit);
```

Engine writes the runtime gate/score after shell lifecycle transitions.
Append does not update score. Claim trusts score visibility but must fence on
candidate lane, epoch, fence token, and observed score at the atomic mutation
boundary.

#### Convergence Port

Owns retry, lease repair discovery, result apply, finality, close, and discard.

Target capabilities:

```java
List<String> promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit);

List<ActiveLeaseRepairCandidate> scanExpiredLeases(String laneKey, long nowMillis, int taskLimit, int itemLimit);

MessageFinalityOutcome applyResult(RuntimeResultFact fact);

boolean closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch);

void discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason);

void discardWork(String taskId, RuntimeEpoch epoch, String reason);
```

Task-runtime decides accepted, duplicate, stale, retry scheduled, and logical
final status. Engine consumes the outcome for task aggregate, trace, and event
projection. Return types are receiver-driven: keep a dedicated outcome only
when production callers branch on multiple fields or pass those fields to the
next owner. Mutation methods whose details are not consumed by production
callers should return `void` or a primitive result. Diagnostic counts belong
behind `TaskReadViewPort`, logs, or tests, not the core mutation API.

#### Read Port

Owns point reads needed by runtime convergence and bounded diagnostics.

Target capabilities:

```java
TaskRuntimeProgressSnapshot progressSnapshot(String taskId);

ResultCorrelationSnapshot resultCorrelation(String taskId, String messageId);

Optional<FinalResultRow> finalResultByMessageId(String taskId, String messageId);

ActiveTaskWorkSnapshot activeWorkForTask(String taskId, int limit);
```

This is not a server view API. It must not become a dumping ground for
dashboards, full scans, worker-centric reverse indexes, or compatibility
windows.

### 5. Engine Runtime Serving Ports

Owner: engine internal hot path.

Caller: assignment, dispatch, result ingest, recovery, and maintenance loops.

Current port family may remain engine-internal:

- `TaskAssignmentRuntimePort`;
- `TaskLeaseMaintenancePort`;
- `TaskDispatchWakeupPort`;
- `TaskRuntimeRecoveryPort`;
- `TaskResultIngestPort`;
- `TaskStateRuntimePort`.

These ports should route through `TaskRuntimeServingLane`, but they are not
task-runtime core contracts and should not be exported as a generic platform
API. They adapt engine policy/orchestration to task-runtime outcomes.

### 6. Starter / Bootstrap Surface

Owner: task-runtime starter SDK and engine starter assembly.

Allowed capabilities:

- start/stop memory or Redis task-runtime backend;
- expose the core runtime port set and task-runtime loop host;
- own task-runtime thread/loop construction for migrated runtime loops.

Forbidden:

- task shell command or read-view operations;
- server view contracts;
- broad `EngineConfig` service-locator access to task-runtime internals;
- ownership of engine shell lifecycle or worker/transport semantics.

## Non-Goals

- Do not redesign the score-band Redis keyspace in this roadmap.
- Do not add server/frontend routes.
- Do not add new task runtime functionality beyond interface convergence.
- Do not split classes for aesthetics only.
- Do not create a new read-view/task-view module in this roadmap. Keep the
  first `TaskReadViewPort` implementation inside existing engine/server
  boundaries; a module split is a later cleanup decision after the read surface
  stabilizes.
- Do not add a task command status enum during this roadmap. Use
  `accepted`/`applied` for the coarse protocol and add command-specific detail
  through `reasonCode`.
- Do not intentionally change server HTTP route shapes or frontend contracts in
  this roadmap. Controllers may adapt to the internal port shape, but public
  route/schema changes require a separate API contract decision.
- Do not preserve old paths with compatibility wrappers, aliases, or fallback
  adapters.
- Do not move worker selection, transport dispatch, or task shell lifecycle
  truth into `xa-mass-task-runtime`.

## Execution Rules

- No new public `xa-mass-task-runtime` DTO unless the slice deletes or
  re-owns an existing public DTO and proves why the new value is caller-owned.
- No external command method may accept `Task` as a mutable aggregate write.
- No external command method may accept caller-supplied status transition rules
  such as allowed current statuses. Status-machine rules stay inside the engine
  shell owner and runtime gate/score synchronization.
- No read-only view/snapshot method may be added to `TaskCommandPort`,
  `TaskRuntimeServingLane`, or core task-runtime ports when it can live behind
  `TaskReadViewPort`.
- No new `TaskRuntimeServingLane` method unless the same slice removes an old
  serving entry or narrows a broad dependency.
- No new `TaskManager` pass-through method for runtime behavior.
- A slice that only moves code without reducing broad object reachability is
  not accepted.
- Each implementation slice should prefer net deletion, narrower dependency
  direction, or fewer public task-runtime/engine API shapes. New code is
  acceptable only when it closes a broader old path in the same slice.
- Production behavior should remain stable unless a slice explicitly names a
  serving cutover. This roadmap is primarily pre-convergence and shrink work.

## TRAPI-0: Current Surface Inventory

Goal: classify current task-related public and package-private surfaces before
editing behavior.

Scope:

- inventory `xa-mass-task-runtime` public classes into:
  `core port`, `runtime value`, `runtime-internal frame`, `read model`,
  `compatibility residue`, `test support`, or `delete candidate`;
- inventory current runtime return shapes into:
  `receiver-consumed outcome`, `primitive result`, `list result`,
  `diagnostic-only detail`, or `delete candidate`;
- inventory engine task surfaces into:
  `external command`, `external read-view`, `shell lifecycle internal`,
  `serving hot path`, `event/trace projection`, `compatibility residue`;
- inventory business query, diagnostic, snapshot, and result-window callers
  that should converge behind `TaskReadViewPort`;
- inventory `TaskRuntimeServingLane` constructor dependencies and public
  methods by caller family;
- inventory `TaskLifecycleService` calls back into `TaskManager`.

Acceptance:

- every public class under `xa-mass-task-runtime/src/main/java` has a target
  classification;
- `AppendItemInput` is explicitly classified as the only caller-owned append
  input that may remain on the public boundary; `BacklogFrameV1` and
  `AppendAdmissionPolicy` are delete candidates;
- every runtime outcome/batch wrapper has a named production receiver or a
  target primitive/list/void replacement;
- every current external task read/query/view method is classified as
  `TaskReadViewPort`, internal runtime point read, or residue;
- every `TaskRuntimeServingLane` constructor dependency is classified as
  allowed target hook, broad temporary hook, or delete candidate;
- every `TaskLifecycleService -> TaskManager` call has a target hook family;
- no code behavior changes.

Verification:

```powershell
rg -n "public (interface|record|class)" xa-mass-task-runtime/src/main/java xa-mass-engine/src/main/java/com/xa/mass/engine
rg -n "taskManager\\." xa-mass-engine/src/main/java/com/xa/mass/engine/TaskLifecycleService.java
```

Stop condition:

- if inventory shows a server/SDK route directly depends on a runtime core
  DTO, record it as a route/read-model residue before editing.

## TRAPI-1: Freeze Core Runtime API Shape

Goal: make the four core task-runtime port families the only core runtime API
shape and prevent more DTO sprawl.

Scope:

- define an allowlist for core task-runtime public ports and values;
- mark or move non-core read-window contracts out of the core port set and
  toward `TaskReadViewPort` or an explicit read-model projection owner;
- ensure `TaskRuntimeResultWindowReadModel` is classified as non-core
  compatibility/read-model surface;
- mark receiverless runtime outcome wrappers as residue unless production code
  consumes their fields for a branch or handoff;
- add or update architecture guard coverage so new public runtime DTOs require
  explicit allowlist changes.

Acceptance:

- `xa-mass-task-runtime` public classes are either allowlisted or classified as
  residue with a removal/re-own target;
- public runtime outcomes are receiver-driven; diagnostic-only mutation details
  are not part of the core port allowlist;
- receiverless wrappers `ActiveLeaseRepairBatch`, `RetryPromotionBatch`,
  `LeaseRepairBatch`, `TaskCloseAttemptOutcome`,
  `DiscardTaskRuntimeOutcome`, and `DiscardTaskWorkOutcome` are deleted and
  guarded from re-entry;
- old command-bucket DTOs and old port names remain forbidden;
- final-result window reads are not part of the core runtime port set and have
  a `TaskReadViewPort`/read-model target;
- no new behavior is added.

Verification candidates:

```powershell
mvn -pl xa-mass-task-runtime "-Dtest=TaskRuntimeContractShapeTest,TaskRuntimeArchitectureGuardTest" test
mvn -pl platform_infra/mass-task-runtime-redis "-Dtest=RedisTaskRuntimeArchitectureGuardTest" test
```

## TRAPI-1A: Converge External Command Outcome Surface

Goal: replace early CRUD-style task command shapes with semantic commands and
bounded outcomes before more runtime serving work depends on them.

Scope:

- replace `createTaskShell` external return shape with `TaskCommandOutcome`
  carrying `taskId`, coarse status, reason code, and message instead of
  returning the full mutable `Task` aggregate;
- remove `updateTask(Task)` from the external command surface. If full shell
  persistence is still needed internally, keep it behind package-private engine
  shell write hooks only;
- keep external lifecycle methods as semantic commands such as approve, reject,
  pause, resume, block, cancel, delete, and seal;
- use one generic `TaskCommandOutcome` for create and lifecycle commands, plus
  one `TaskAppendOutcome` for append because append needs `acceptedCount`;
- do not create per-command outcome classes unless a command has additional
  command-owned fields that cannot fit the generic outcome;
- do not introduce a generic `transitionTaskStatus(current, target)` command
  or caller-supplied `allowedCurrentStatuses`;
- keep duplicate/retry-safe calls on the same primitive outcome protocol:
  `accepted` says whether the command is accepted, `applied` says whether a new
  mutation happened, and `reasonCode` carries detailed reasons such as already
  paused, terminal conflict, intake closed, or batch too large;
- ensure create/read separation: callers that need full task details after a
  successful create must use `TaskReadViewPort`.

Acceptance:

- external task command callers cannot update arbitrary shell fields by
  passing a mutable `Task`;
- external task command callers cannot define state-machine transition rules;
- external task command surface does not introduce more than the generic
  command outcome and append-specific outcome unless explicitly justified;
- command outcomes do not introduce a status enum; `accepted`/`applied` are the
  coarse protocol and `reasonCode` is used for command-specific causes;
- repeated lifecycle commands are handled by owner outcomes, not by caller
  pre-check allowlists;
- tests prove at least one idempotent lifecycle command returns an already
  applied or rejected outcome without mutating runtime truth;
- server/SDK read paths for created task details point to the read-view surface,
  not the create command response.

Current evidence:

- `TaskCommandPort#createTaskShell` returns `TaskCommandOutcome`; callers that
  need the mutable task aggregate read it through `TaskQueryPort` after a
  successful create.
- `TaskCommandPort` no longer exposes `updateTask(Task)`,
  `resumeTaskDetailed`, or `appendTaskItemsWithReceipt`.
- `TaskManager` no longer exposes public `updateTask(Task)`; engine-internal
  shell persistence is package-private `persistTaskShell(Task)`.
- lifecycle command methods return `TaskCommandOutcome`; append returns
  `TaskAppendOutcome` with accepted count and generated message ids.
- `TaskAppendReceipt` and `TaskResumeResult` have been deleted from
  `xa-mass-engine/src/main/java`.
- `TaskAssignmentRuntimePort` no longer exposes generic `updateTask(Task)`;
  assignment persistence is named `persistAssignmentState(Task)` and guarded
  as an assignment-path hook.
- SDK public convenience methods keep their SDK-owned external shapes while
  adapting through the bounded engine command outcomes.

Verification candidates:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskManagerLifecycleTest,TaskKernelLifecycleTest" test
mvn -pl xa-mass-engine-starter "-Dtest=EngineConfigTaskRuntimeServingLaneTest" test
rg -n "updateTask\\(Task|Task createTaskShell|allowedCurrentStatuses|transitionTaskStatus" xa-mass-engine/src/main/java sdk xa-mass-server/src/main/java
```

## TRAPI-2: Narrow Append/Claim Core Contracts

Goal: stop engine/starter callers from constructing runtime-owned backlog
frames or policy carrier DTOs.

Scope:

- replace external `appendBacklog` caller input with caller-owned append item
  identity and payload fields;
- remove public `BacklogFrameV1`; runtime implementations may keep only
  implementation-local encoded backlog frame data;
- converge `AppendItemInput`, `BacklogFrameV1`, and `AppendAdmissionPolicy`:
  one caller-owned append item shape remains public, `BacklogFrameV1` is
  deleted from public source, and `AppendAdmissionPolicy` is deleted unless a
  later engine/server intake contract proves it needs a non-runtime owner;
- keep claim candidate fencing on `ScoreCandidate`, including lane, epoch,
  fence token, and observed score;
- do not add messageId de-duplication or caller idempotency guarantees in this
  slice.

Acceptance:

- engine/starter code does not construct `BacklogFrameV1`;
- only one public append item DTO remains for the engine/task-runtime append
  boundary;
- `BacklogFrameV1` and `AppendAdmissionPolicy` do not exist under
  `xa-mass-task-runtime/src/main/java`;
- append admission limits are passed as primitives or owned by engine/server
  intake, not by a duplicated runtime append DTO;
- append contract does not accept retry count, frame type, enqueue timestamp,
  or other runtime-owned frame fields from non-runtime callers;
- claim still rejects stale candidates through focused memory/Redis contract
  tests;
- append remains all-accepted-or-rejected for the current batch path.

Current evidence:

- `TaskRuntimeWorkPort#appendBacklog` accepts `List<AppendItemInput>`.
- `BacklogFrameV1` and `AppendAdmissionPolicy` have been deleted from
  `xa-mass-task-runtime/src/main/java`.
- Engine, starter, memory, and Redis callers pass the caller-owned append item
  shape directly.

Verification candidates:

```powershell
mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis -am "-Dtest=TaskRuntimeContractShapeTest,InMemoryTaskRuntimeContractTest,RedisScoreBandTaskRuntimeTest" test
mvn -pl xa-mass-engine "-Dtest=TaskRuntimeServingLaneTest,TaskRuntimeServingLaneOldPathClosureGuardTest" test
```

## TRAPI-3: Cut TaskLifecycleService Off From TaskManager

Goal: make lifecycle code depend on shell/runtime hooks instead of the whole
`TaskManager` object.

Scope:

- change `TaskLifecycleService` construction so it receives explicit
  package-private dependencies for shell reads/writes, lifecycle events,
  runtime append/sync/discard, dispatch request, progress read, and terminal
  policy evaluation;
- keep task locking in `TaskManager` for this slice;
- delete `TaskManager` methods that only exist as broad pass-throughs after
  the new hooks are installed;
- do not move task shell storage or lifecycle truth into task-runtime.

Acceptance:

- `TaskLifecycleService` no longer imports, stores, or calls `TaskManager`;
- `TaskLifecycleService` receives narrow shell/runtime/event hooks from
  `TaskManager` assembly instead of the concrete manager object;
- append admission that reads runtime ready backlog is owned by
  `TaskRuntimeServingLane#validateRuntimeAppendAdmission`;
- lifecycle tests still execute through `TaskManager` public command methods;
- task shell lifecycle behavior is unchanged;
- `TaskManager` has fewer runtime/lifecycle pass-through methods than before
  the slice, or the slice records why a specific pass-through remains.

Current evidence:

- `TaskLifecycleService` constructor receives narrow function hooks for shell
  load/write/delete, runtime append/admission/sync/progress/discard, terminal
  policy, event publication, and dispatch request.
- `TaskRuntimeServingLaneOldPathClosureGuardTest` rejects concrete
  `TaskManager` fields and `taskManager.` calls inside `TaskLifecycleService`.
- `TaskRuntimeServingLane#validateRuntimeAppendAdmission` owns runtime
  ready-backlog admission using resolved backpressure policy plus runtime
  progress.

Verification candidates:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskManagerLifecycleTest,TaskKernelLifecycleTest,TaskRuntimeServingLaneOldPathClosureGuardTest" test
rg -n "TaskManager taskManager|private final TaskManager|taskManager\\." xa-mass-engine/src/main/java/com/xa/mass/engine/TaskLifecycleService.java
```

## TRAPI-4: Cut TaskRuntimeServingLane Off From Broad Engine Services

Goal: keep the serving lane as an engine/runtime adapter without letting it call
the full task command, read-view, or event surfaces.

Scope:

- delete same-shape `TaskCommandService` / `TaskQueryService` wrappers and make
  `TaskCommandPort` / `TaskQueryPort` the only engine shell command/query
  contracts;
- replace `TaskRuntimeServingLane` constructor dependencies on broad
  `TaskCommandPort`, `TaskQueryPort`, `TaskReadViewPort`, and
  `TaskEventService` with
  minimal shell read/write and event projection hooks;
- ensure the serving lane cannot call shell commands such as approve, pause,
  resume, append, delete, or seal through a broad service object;
- keep engine aggregate policy and trace/event projection outside the
  task-runtime core module;
- keep serving lane package-local or engine-internal; do not export it as
  task-runtime API.

Acceptance:

- `TaskRuntimeServingLane` does not import or store `TaskCommandPort`,
  `TaskQueryPort`, `TaskReadViewPort`, `TaskCommandService`,
  `TaskQueryService`, or `TaskEventService`;
- `TaskCommandService` and `TaskQueryService` source files are absent;
- `EngineConfig` assembly still wires the same serving path without exposing
  task-runtime internals to embedded SDK callers;
- result ingest, lease repair, recovery, and dispatch tests still use the new
  task-runtime owner path;
- no new broad service wrapper is introduced.

Current evidence:

- `TaskRuntimeServingLane` no longer exposes a `forTaskManager(...)` factory
  or accepts a `TaskManager` parameter. Its main assembly entry is
  `forShellHooks(...)`, which receives explicit shell read/write and event
  projection hooks.
- `TaskManager#createTaskRuntimeServingLane(...)` is the only main-code
  conversion point from manager-owned shell hooks to the serving lane hook
  surface; the standalone same-module assembly wrapper has been deleted.
- The serving lane stores `Function<String, Task>`, `Predicate<Task>`,
  `Consumer<Task>`, and event `BiConsumer` hooks instead of broad task command,
  query, read-view, or event service objects.
- `EngineConfig` and `EngineRuntimeKernel` expose `TaskCommandPort` directly;
  the deleted same-shape `TaskCommandService` wrapper is no longer a second
  command surface.
- `TaskRuntimeServingLaneOldPathClosureGuardTest` rejects reintroduced broad
  service fields, `forTaskManager(...)`, and broad `TaskManager` parameters
  inside the serving lane.
- `SimpleTaskDispatchBinderTest` now drives dispatch through READY task score/
  gate state before claiming runtime backlog, so binder proof follows the new
  score-owned schedulability boundary instead of assuming NEW tasks are
  claimable.

Verification candidates:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskRuntimeServingLaneTest,TaskRuntimeRecoveryPortTest,TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest,TaskRuntimeServingLaneOldPathClosureGuardTest,TaskManagerLifecycleTest,TaskKernelLifecycleTest,SimpleTaskDispatchBinderTest" test
mvn -pl xa-mass-engine-starter "-Dtest=EngineConfigTaskRuntimeServingLaneTest" test
rg -n "TaskCommandPort|TaskQueryPort|TaskCommandService|TaskQueryService|TaskReadViewPort|TaskEventService" xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java
rg -n "forTaskManager\\(|TaskManager taskManager|TaskManager manager" xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java
```

## TRAPI-5: Converge External Read-Only TaskReadViewPort

Goal: converge business query, diagnostic snapshots, result windows, and trace
projection behind one external read-only task surface, while keeping core
runtime mutation and engine command paths clean.

Scope:

- keep `TaskRuntimeReadPort` limited to point reads needed by convergence and
  owner-local bounded diagnostics;
- define `TaskReadViewPort` as the single external read-only surface for task
  shell reads, status/progress reads, final-result windows, runtime snapshots,
  assignment snapshots, trace snapshots, and bounded full diagnostic snapshots;
- move or re-own final result windows as `TaskReadViewPort`/read-model
  projection, not core runtime mechanism;
- allow the read-view implementation to bypass layered engine delegates for
  projection reads when the source owner can be read safely;
- keep the first implementation in existing engine/server read-model code.
  Do not introduce a new module while this roadmap is focused on shrinking
  command/runtime interfaces;
- keep trace emission side-channel and non-authoritative;
- remove worker-centric active reverse-index assumptions from task-runtime core
  reads unless a later roadmap proves a high-ROI diagnostic owner.

Acceptance:

- core runtime ports do not expose pageable server view concepts;
- `TaskReadViewPort` or accepted equivalent `TaskReadOperations` is read-only
  and separated from `TaskCommandPort`, `TaskRuntimeServingLane`, and core
  runtime mutation ports;
- business read/query callers and diagnostic/view/snapshot callers have one
  target external read surface rather than separate Java API families;
- any direct projection read path names its source owner and cannot write task,
  runtime, assignment, transport, or trace truth;
- result-window API is either behind `TaskReadViewPort` or has a recorded
  removal/re-owner target outside the core runtime port set;
- serving tests still prove finality and late duplicate behavior through
  point reads and outcomes, not by depending on ordered result windows as
  runtime truth.

Current evidence:

- SDK-owned `TaskReadOperations` is the accepted external read-view equivalent
  for this slice. It contains task shell/detail/status/access reads, result
  windows, archive reads, final-result point lookup, state validation/
  resolution, work stats, and active lease snapshots.
- `TaskReadOperations` lives in `sdk/xa-mass-embedded-sdk-api`, so server and
  SDK callers share one read-only contract instead of an embedded-sdk-local
  implementation interface.
- `EngineTaskReadOperations` is the package-private engine-starter
  implementation of that contract. It translates engine/task-runtime read
  facts into SDK-owned snapshots and owns archive streaming composition.
- `MassApplication` exposes the unified read entry through
  `public TaskReadOperations taskReads()` and does not expose raw task read
  helpers.
- `MassEngine` exposes package-private `taskReads()` for embedded-sdk assembly
  and no longer exposes raw task read helpers.
- `EngineConfig` exposes only `public TaskReadOperations
  getTaskReadOperations()` for starter-facing reads. Its raw read helper
  methods are package-private implementation details consumed by
  `EngineTaskReadOperations`.
- `EngineConfig#getTaskShellStore()` is no longer public. Engine-starter read
  composition may still use the shell store internally, but external SDK,
  server, testing, and perf callers must use `TaskReadOperations` or their own
  explicitly injected test store reference.
- `MassSdkApplication` implements `TaskReadOperations` and routes task reads
  through `delegate.taskReads()`. It no longer calls `delegate.getTask(...)`,
  `delegate.readTaskResults(...)`, or other raw `MassApplication` read helpers.
- `TaskApiController` and `InternalTaskReviewController` inject
  `TaskReadOperations` directly, so server read/result endpoints do not depend
  on a separate task query/result/diagnostic Java API family.
- `TaskDiagnosticOperations`, `TaskQueryOperations`,
  `TaskResultQueryOperations`, and `DefaultTaskDiagnosticOperations` are absent
  from the embedded SDK main source.
- `EngineStarterBackdoorGuardTest` rejects public raw task read methods on
  `MassApplication`, `MassEngine`, and `EngineConfig`; rejects SDK read paths
  that call `MassApplication` raw read helpers directly; and proves
  `TaskReadOperations` lives in the SDK API module while
  `StarterTaskReadOperations` stays deleted. It also rejects public
  `EngineConfig#getTaskShellStore()` as a raw storage read backdoor.

Verification candidates:

```powershell
mvn -pl xa-mass-engine "-Dtest=TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest,TaskRuntimeServingLaneOldPathClosureGuardTest" test
mvn -pl xa-mass-server "-Dtest=RedisRuntimeLateReplayE2eScenario,TaskApiAllMessagesFailedTraceObservedIntegrationTest,TaskApiCallbackReplayTraceObservedIntegrationTest,TaskApiMixedResultsTraceObservedIntegrationTest" test
mvn -pl sdk/xa-mass-embedded-sdk "-Dtest=MassSdkTest,EngineStarterBackdoorGuardTest,EmbeddedSdkEngineDependencyGuardTest,EngineStarterSurfaceInventoryGuardTest" test
rg -n "TaskDiagnosticOperations|TaskQueryOperations|TaskResultQueryOperations|DefaultTaskDiagnosticOperations" sdk/xa-mass-embedded-sdk/src/main/java xa-mass-server/src/main/java
rg -n "public (Task getTask|List<Task> listTasksPaged|List<Task> getTasksByStatus|TaskResultWindowSnapshot readTaskResults|TaskWorkStatsSnapshot getTaskWorkStats|List<TaskActiveLeaseSnapshot> getActiveLeases|Optional<TaskWorkFinalSnapshot> getVisibleTaskResultByMessageId|long countVisibleTaskResults|TaskStateValidationResult validateTaskState|TaskStateResolutionResult resolveTaskState|TaskStateValidationSnapshot validateTaskState|TaskStateResolutionSnapshot resolveTaskState)" sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java xa-mass-engine-starter/src/main/java/com/xa/mass/starter/MassEngine.java xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java
rg -n "class StarterTaskReadOperations|interface TaskReadOperations" sdk/xa-mass-embedded-sdk/src/main/java xa-mass-engine-starter/src/main/java
rg -n "public TaskShellStore getTaskShellStore" xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java
```

## TRAPI-6: Starter Surface Closure

Goal: keep task-runtime bootstrap as runtime assembly and prevent starter
surfaces from becoming another broad engine service locator.

Scope:

- verify task-runtime starter exposes runtime handle, port set, and loop host
  lifecycle only;
- verify engine starter assembly does not expose task-runtime internals through
  broad getters beyond approved starter surfaces;
- keep memory/Redis backend selection in starter/bootstrap, not in engine
  lifecycle logic.

Acceptance:

- `sdk/xa-mass-task-runtime-starter-sdk` starts/stops memory and Redis runtime
  implementations only through public task-runtime ports;
- no starter-facing API exposes task shell command, task read-view, or server
  view behavior as task-runtime starter responsibility;
- ECSP guard regression remains green for embedded SDK boundary when this
  slice touches engine starter.

Current evidence:

- `TaskRuntimeHandle` exposes `runtime()`, loop lifecycle, backend kind, and
  status only. It no longer exposes `TaskRuntimeResultWindowReadModel`.
- `TaskRuntimeStarter` no longer carries the ordered result-window read-model
  through handle construction.
- `TaskRuntimePortSet` remains the grouped runtime port surface and does not
  inherit `TaskRuntimeResultWindowReadModel`.
- `EngineConfig` consumes the ordered final-result window read-model only
  inside engine assembly by checking the runtime port set capability before
  creating `TaskRuntimeServingLane`.
- `TaskRuntimeStarterArchitectureGuardTest` rejects
  `TaskRuntimeResultWindowReadModel` and `resultWindowReadModel()` on
  `TaskRuntimeHandle`.

Verification candidates:

```powershell
mvn -pl sdk/xa-mass-task-runtime-starter-sdk "-Dtest=TaskRuntimeStarterBootstrapTest,TaskRuntimeStarterLifecycleTest,TaskRuntimeStarterArchitectureGuardTest" test
mvn -pl xa-mass-engine-starter "-Dtest=EngineConfigTaskRuntimeServingLaneTest" test
mvn -pl sdk/xa-mass-embedded-sdk "-Dtest=EmbeddedSdkEngineDependencyGuardTest,EngineStarterSurfaceInventoryGuardTest" test
```

## TRAPI-7: Residue Deletion And Guard Freeze

Goal: make the interface convergence measurable by deletion and negative
guards.

Scope:

- delete or re-own public task-runtime residue from TRAPI-0/1;
- delete stale tests that preserve old runtime vocabulary after replacement
  tests exist;
- update module README files with current interface truth;
- add stable guards for forbidden broad object dependencies and forbidden core
  runtime DTO expansion.

Acceptance:

- no live old and new task-runtime API paths remain for the same owner
  responsibility;
- `TaskLifecycleService` and `TaskRuntimeServingLane` no longer form a
  `TaskManager` service feedback loop through broad objects;
- public task-runtime production class count is lower than TRAPI-0 baseline or
  each retained public class is allowlisted with owner classification;
- documentation describes current behavior, not just target direction.

Current evidence:

- Public runtime residue `BacklogFrameV1`, `AppendAdmissionPolicy`,
  `ActiveWorkSnapshot`, `SchedulerDiscoveryOutcome`,
  `SchedulerTaskCandidate`, `ActiveLeaseRepairBatch`, `LeaseRepairBatch`,
  `RetryPromotionBatch`, `DiscardTaskRuntimeOutcome`,
  `DiscardTaskWorkOutcome`, and `TaskCloseAttemptOutcome` is deleted from
  `xa-mass-task-runtime/src/main/java`.
- `ScoreCandidate` no longer carries a conversion helper from the deleted
  scheduler-candidate DTO; score discovery uses `ScoreCandidateBatch`
  directly.
- `TaskRuntimeReadPort` exposes one final-result point-read method,
  `getFinalResultByMessageId(...)`; the duplicate `finalResult(...)` alias is
  deleted so the core read port does not grow same-semantic convenience
  entries.
- `TaskRuntimeArchitectureGuardTest` keeps the deleted residue list as a
  negative guard and rejects reintroduced duplicate final-result read aliases.
- Memory and Redis runtime tests prove the reduced public API still drives
  append, score discovery, claim, result convergence, active lease repair,
  final reads, and discard.

Verification candidates:

```powershell
mvn -pl xa-mass-task-runtime,platform_infra/mass-task-runtime-memory,platform_infra/mass-task-runtime-redis,sdk/xa-mass-task-runtime-starter-sdk,xa-mass-engine,xa-mass-engine-starter,xa-mass-server -am -DskipTests test-compile
mvn -pl xa-mass-task-runtime "-Dtest=TaskRuntimeContractShapeTest,TaskRuntimeArchitectureGuardTest" test
mvn -pl platform_infra/mass-task-runtime-memory "-Dtest=InMemoryTaskRuntimeContractTest" test
mvn -pl platform_infra/mass-task-runtime-redis "-Dtest=RedisScoreBandTaskRuntimeTest,RedisTaskRuntimeArchitectureGuardTest,RedisTaskRuntimeScoreBandKeyspaceProofTest,RedisTaskRuntimeScoreBandAdvanceCandidateTest" test
mvn -pl xa-mass-engine "-Dtest=TaskRuntimeServingLaneOldPathClosureGuardTest,TaskManagerLifecycleTest,TaskKernelLifecycleTest,TaskRuntimeServingLaneTest,TaskRuntimeRecoveryPortTest,TaskResultRuntimeConvergenceTest,TaskResultConcurrencyConvergenceTest" test
mvn -pl xa-mass-engine-starter "-Dtest=EngineConfigTaskRuntimeServingLaneTest" test
git diff --check
```

## Completion Criteria

This roadmap is complete only when all of the following are true:

- `xa-mass-task-runtime` exposes a small, allowlisted core runtime API for
  score, work, convergence, and point reads.
- core runtime outcomes are kept only when production receivers consume their
  fields; receiverless mutation details are `void`, primitive/list return
  values, or read-view diagnostics.
- the append boundary has one public caller-owned item shape; `BacklogFrameV1`
  and `AppendAdmissionPolicy` are absent from the public runtime source unless
  a future non-runtime owner explicitly reintroduces a different contract.
- Engine external task command and read-view surfaces do not expose
  task-runtime internals.
- `TaskCommandPort` is the external mutation surface and does not carry
  diagnostic/view/snapshot reads.
- `TaskCommandPort` exposes semantic command intent and bounded outcomes; it
  does not expose full mutable task-shell updates or caller-defined status
  transition rules.
- external command outcomes are intentionally small: one generic command
  outcome, one append-specific outcome when accepted count is needed, and no
  per-command outcome class sprawl without a named command-owned field.
- `TaskReadViewPort` or its accepted equivalent is the single external
  read-only task surface for business reads and diagnostic/view/snapshot
  projections.
- `TaskLifecycleService` no longer depends on the whole `TaskManager` object.
- `TaskRuntimeServingLane` no longer depends on broad task command, read-view,
  or event services and cannot route back through full shell command or
  external query surfaces.
- Non-core result windows and diagnostics are classified outside the core
  runtime API and either live behind the external read-view surface or are
  deleted.
- Focused guards prevent reintroducing old command-bucket ports, broad service
  feedback loops, runtime DTO sprawl, and Redis/keyspace internals in engine/server/SDK
  APIs.
- Current owner docs are updated and this roadmap can be archived after a
  residue scan.

## Do Not Start With

- Do not start by splitting `TaskRuntimeServingLane` into multiple classes
  unless the split deletes broad dependencies or moves a non-core read model
  out of the mainline.
- Do not start by adding another bridge/facade around `TaskManager`.
- Do not start by changing Redis keys.
- Do not start by adding server view APIs.
- Do not start by moving `TaskManager` wholesale into a new module.
- Do not start by making task-runtime understand the full `Task` model.
- Do not start by replacing lifecycle commands with generic status transition
  APIs. State-machine rules belong to the engine shell owner and runtime
  gate/score synchronization.

## Relationship To Existing Roadmaps

- `TASK_RUNTIME_SCORE_BAND_REDIS_KEYSPACE_REWRITE_ROADMAP.md` owns the Redis
  score-band keyspace and runtime mechanism proof. This roadmap consumes that
  direction but does not reopen key design.
- `TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md` owns the
  broader task-runtime owner/starter migration. This roadmap is a narrower
  pre-convergence track to reduce API and engine feedback loops before more TROM serving
  work.
- `ENGINE_CALLER_SURFACE_PRE_TROM_CONVERGENCE_ROADMAP.md` owns embedded SDK and
  engine-starter caller exposure. This roadmap reuses its boundary guard
  expectations when starter or embedded SDK surfaces are touched.
