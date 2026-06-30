# Engine Starter Boundary Pre-TROM Convergence Roadmap

Status: implemented prerequisite boundary; retained as the TROM handoff record
for `TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md`.

This roadmap creates a concrete, testable pre-TROM boundary: introduce
`xa-mass-engine-starter` as the only production module that exposes engine
runtime assembly and operation handles to SDK/starter callers.

It is a prerequisite to
[TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md).

This is not an API-protection roadmap for the current
`embedded-sdk <-> engine` object graph. The goal is to stop cross-module
corruption first: engine-facing assembly may remain internally imperfect inside
`xa-mass-engine-starter`, but `sdk/xa-mass-embedded-sdk` should no longer
directly depend on or import engine implementation/service/config owner types.
Engine-owned value contracts currently imported by SDK code are separate
temporary exceptions. They must stay inventoried with owner/removal targets and
should be removed only as part of a TROM slice that also explains how the old
engine path will close.

It does not redesign core public HTTP routes, core SDK request/response
contracts, or the future task-runtime protocol. It also does not preserve
frontend-only or non-core server view surfaces as compatibility constraints.
If a server view exists only to expose a meaningless engine projection or keep
the current frontend compiling, it may be deferred or recorded for a separate
route cleanup follow-up while the core API stays stable. Deleting or reshaping a
server route inside ECSP is allowed only when that route prevents removal of an
engine exposure leak or prevents the slice from compiling. ECSP prepares TROM
by making the current engine exposure verifiable through module dependencies
and forbidden imports before task-runtime ownership moves.

Read with:

- [TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md](TASK_RUNTIME_OWNER_MODULE_AND_STARTER_SDK_CONVERGENCE_ROADMAP.md)
- [xa-mass-engine/README.md](../xa-mass-engine/README.md)
- [sdk/xa-mass-embedded-sdk/README.md](../sdk/xa-mass-embedded-sdk/README.md)
- [sdk/README.md](../sdk/README.md)
- [TASK_LIFECYCLE_BASELINE.md](../doc/TASK_LIFECYCLE_BASELINE.md)
- [SDK_INTEGRATIONS_BOUNDARY_GUARD.md](../doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md)

## Current Code Observations

- Root `pom.xml` includes `xa-mass-engine-starter` between `xa-mass-engine`
  and `sdk/xa-mass-embedded-sdk`.
- `sdk/xa-mass-embedded-sdk/pom.xml` depends on
  `xa-mass-engine-starter` and bans a direct `xa-mass-engine` dependency.
- `MassEngine`, `EngineConfig`, `EngineRuntimeBridge`,
  `RuntimeEventBusEngineBridge`, `MassEngineBuilder`, and
  `StorageBackedMatchingRuleSetProvider` now live under
  `xa-mass-engine-starter`. The Java package remains
  `com.xa.mass.starter.*` for this slice; module ownership, not package rename,
  is the completed boundary.
- `MassApplication` remains in embedded SDK because it owns embedded
  application and transport assembly. It consumes engine-starter behavior
  methods and no longer exposes public `getEngine()`.
- `MassEngine` no longer exposes public `getConfig()`. Engine config/service
  access remains internal to `xa-mass-engine-starter`.
- `MassSdkApplication` no longer imports engine services such as
  `TaskCommandService`, `TaskQueryService`, or `TaskEventService`, and no longer
  reaches ordinary operations through `requireStartedEngine().getConfig()`.
- Remaining SDK main-source imports from `com.xa.mass.engine.*` are value or
  configuration exceptions recorded in
  [ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md](ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md):
  task command/result value records, task stage value records, and
  `PollingIdleBackoffPolicy`.
- Source-level removal of those current task operation / diagnostic / stage DTO
  imports is not owned by this completed ECSP prerequisite slice. It should be
  handled inside TROM only when it helps close a named old engine path or guard a
  migrated task-runtime lane.
- No server route was deleted, reshaped, re-owned, or given new
  auth/permission behavior in this ECSP implementation slice.
- Current task event/listener hooks are classified as SDK notification residue.
  They are not target runtime coordination APIs for TROM.

## Owner Review

`xa-mass-engine` owns task shell lifecycle, scheduling orchestration,
assignment, result orchestration, progress/terminal convergence, and any
engine-internal reaction semantics until TROM migrates item/result runtime
ownership.

`xa-mass-engine-starter` should own engine process assembly, engine config
construction, lifecycle handle creation, and the small operation handle exposed
to SDK/starter callers. It may depend on `xa-mass-engine` and tolerate
engine-specific internal details while this boundary converges.

`sdk/xa-mass-embedded-sdk` owns public/in-process SDK facade behavior and
embedded application assembly. It may consume `xa-mass-engine-starter`, but it
must not depend on `xa-mass-engine` or import engine implementation, service,
config-owner, or runtime-owner packages in main sources after this roadmap
completes. Engine-owned public value contracts must be extracted to a public
contract module or carried only as named temporary exceptions.

`xa-mass-server` may assemble the embedded runtime and expose backend product
surfaces. It should reach engine behavior through SDK/starter-facing handles or
server-owned application services, not engine internals.

Server core APIs remain stability constraints. Non-core view/control-console
surfaces are not kernel boundaries; when they depend on engine internals or
same-shape runtime projections, ECSP may delete, defer, or re-own them instead
of preserving frontend compatibility.

## Boundary Decision

Create a module boundary that is directly verifiable:

```text
sdk/xa-mass-embedded-sdk
  -> xa-mass-engine-starter
    -> xa-mass-engine
```

`xa-mass-engine-starter` is an anti-corruption boundary, not a same-shape
facade over every `EngineConfig` getter and not a mechanical split of one large
class into many smaller sub-interfaces. Its first job is containment:

- cross-module engine imports are allowed in `xa-mass-engine-starter`;
- `sdk/xa-mass-embedded-sdk` main sources should not import
  engine implementation/service/config owner types;
- `sdk/xa-mass-embedded-sdk` should not directly depend on `xa-mass-engine`;
- engine-specific config, lifecycle, and runtime operation access may remain
  internally imperfect inside `xa-mass-engine-starter` while TROM proceeds;
- only approved starter-facing surfaces exposed from `xa-mass-engine-starter`
  are allowed to cross into embedded SDK.

Compatibility posture:

- `com.xa.mass.starter.*` advanced assembly classes are breakable internal
  surface. Keep a class only when ECSP-0 proves it is a true public SDK facade;
  otherwise move it, rename it, or delete it without compatibility wrappers.
- `MassApplication.getEngine()` and `MassEngine.getConfig()` are target-delete
  public backdoors. They may become engine-starter internal implementation
  details only when no SDK/server production caller can reach them.
- Non-core server view endpoints and frontend expectations are not preservation
  constraints for this roadmap. Core API behavior must stay stable; optional
  route deletion/re-own and frontend repair should happen after kernel
  boundaries are clean unless the current ECSP slice cannot remove an engine
  leak without touching the route.

## Execution Boundary

ECSP is a kernel-boundary roadmap, not a module-internal cleanup roadmap. A
slice should pay refactor cost only when the current shape lets another module
reach, preserve, or redefine engine runtime truth.

Kernel-breaking problems must be fixed inside ECSP:

- `embedded-sdk`, server, starter, transport, or worker-runtime production code
  can reach engine runtime truth through `EngineConfig`, `TaskManager`, broad
  runtime ports, or `getEngine().getConfig()`;
- a public or starter-facing handle exposes engine services, config-owner
  objects, runtime-owner objects, or same-shape owner getter groups;
- server view/frontend projection needs force engine to preserve an internal
  DTO, lifecycle state, query shape, or runtime projection;
- transport or worker-runtime evidence is reinterpreted by engine-starter as
  scheduling, lifecycle, result finality, or worker-state truth;
- two runtime owners can mutate or decide the same task/result/worker truth.

Module-internal residue is tolerated and explicitly out of scope unless it
crosses one of those boundaries:

- `TaskManager` may remain large and engine-internal;
- `xa-mass-engine-starter` may contain imperfect assembly code or internal
  service-locator style wiring while it contains the cross-module leak;
- engine-internal listener/event residue may remain when it is not a
  cross-module correctness path;
- SDK-visible value/view DTO extraction may be phased through the inventory
  when it is not exposing engine services or runtime owners;
- non-core server view and frontend repair may be deferred while core API
  behavior remains stable; deletion/re-own is a separate follow-up unless a
  current ECSP slice cannot remove an engine leak or stay compiling without it.

Slice stop rule: once a slice has removed the named cross-module leak, preserved
core runtime behavior, and added or identified the proof/guard that would catch
the leak returning, stop the slice. Do not continue into same-module
beautification, broad class splitting, DTO cleanup, or frontend repair.

## Engine Import Classification

The import guard must not be a blind `com.xa.mass.engine.*` ban until public
value contracts have an owner decision. ECSP separates current engine imports
into these lanes:

- `forbidden implementation/service/config-owner import`: `TaskManager`,
  `EngineRuntimeKernel`, `EngineConfig`, `MassEngine`, `TaskCommandService`,
  `TaskQueryService`, `TaskEventService`, `TaskManagerResultIngestFacade`,
  task runtime ports, `WorkerControlRuntime`, `TaskEventListenerRegistrar`,
  engine listener/watchdog/service/control implementations, and any owner
  object that lets SDK/starter reach engine runtime truth;
- `candidate public contract extraction`: SDK-visible engine value or
  diagnostic records such as `com.xa.mass.engine.model.*`,
  `com.xa.mass.engine.stage.*` projections/results, and engine-owned config
  contracts such as `PollingIdleBackoffPolicy`;
- `proof harness exception`: `xa-mass-testing/src/main` may import engine
  internals as a runtime proof harness until a separate testing cleanup roadmap
  says otherwise;
- `test-only exception`: unit or integration tests may import engine internals
  when the test is proving engine/starter behavior and the production source
  path remains clean.

Temporary public-contract exceptions must name the package/class, current SDK
caller, owner, and removal target. They must not include engine services,
runtime owners, config service locators, or lifecycle objects.

## Inventory Artifact

ECSP caller and exception inventory is recorded in the sibling file
[ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md](ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md).

Required symbol fields:

| Symbol | Current Caller | Import Lane | Owner | Why Temporary | Target Owner/Module | Removal Target | Slice |
| --- | --- | --- | --- | --- | --- | --- | --- |

Required dependency fields:

| Module | Dependency | Scope | Reason | Target | Slice |
| --- | --- | --- | --- | --- | --- |

Required approved starter surface fields:

| Surface | External Caller | Behavior Preserved | Input Fields | Return Fields | Internal Owner | Why Crosses Boundary | Replaces Old Getter | Proof |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

Required server route classification fields:

| Route | Controller | Current Status | Caller | Auth/Permission | Classification | Action | API Reference Update | Frontend Adapter Action | Proof |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

The inventory is not optional bookkeeping. ECSP-0A must populate the starter
assembly rows before ECSP-1 implementation starts, and ECSP-0B must populate
SDK operation/value-contract rows before ECSP-2 implementation starts.
ECSP-0C must populate server route rows before any ECSP slice deletes or
changes a server route, response shape, auth behavior, or permission. ECSP-0C
is a route gate, not a default route cleanup slice.

ECSP-4 guards may allow a remaining `com.xa.mass.engine.*` SDK import only when
this inventory contains a matching `temporary public-contract exception` row.
Forbidden implementation/service/config-owner imports are never allowlisted by
the inventory.

## Exposure Decision Gate

Do not expose an owner surface just because the current `EngineConfig` exposes
it. Every current embedded-sdk to engine access must pass this decision gate:

```text
Does an external SDK/starter caller still need this operation?
Does the operation protect a production invariant or only preserve old access?
Can the caller own the command/read fields without seeing engine internals?
Can the operation be deleted, moved internal, or deferred to TROM instead?
```

Allowed classifications:

- `no external exposure`: keep inside `xa-mass-engine-starter` or delete the
  old caller path;
- `starter lifecycle surface`: start, stop, running state, and shutdown proof;
- `starter operation surface`: a narrow command/read operation needed by
  embedded SDK behavior, not a getter for an owner object;
- `assembly-only configuration`: bootstrap inputs consumed while building the
  engine runtime, not readable runtime service locator state;
- `temporary exception`: retained only with a removal or TROM handoff note;
- `defer to TROM`: do not introduce a new surface in this prerequisite roadmap.

This decision is not "split by owner family". Some owner interfaces should not
exist outside `xa-mass-engine-starter` at all. A narrow surface is justified only
when there is a named external caller, a current behavior to preserve, and a
field shape that the caller can construct or validate without engine internals.

`TaskEventService`, task event listeners, and listener registration are not
target cross-module runtime coordination APIs. Existing listener use may remain
only when classified as engine-internal wiring, SDK notification residue, or a
temporary starter exception with a removal or TROM handoff note; the exception
must not expose `TaskEventService` itself as an SDK/server-reachable service.

The first cleanup target is best-effort explicit handoff/ingress/control, not a
generic event bus. Transport remains assigned-delivery/result-ingress only.

## First-Slice Selection Rule

`minimal` does not mean smallest file move or fewest lines changed. A first
slice is minimal only when all of these are true:

- it touches one production caller family, not the whole embedded SDK facade;
- it removes or contains at least one real `embedded-sdk -> engine` runtime
  service-locator path;
- it keeps public SDK request/response behavior unchanged;
- it does not introduce task-runtime semantics, public DTO redesign, or server
  route changes;
- it does not require module-internal cleanup beyond what is needed to remove
  the named cross-module leak and keep the slice compiling;
- it leaves the repo compiling after the slice, with a focused proof that would
  fail if the old broad engine access path returned;
- it does not replace `EngineConfig` with same-shape sub-facades.

`critical` means the slice protects a prerequisite invariant for TROM. A slice
is critical only when it satisfies at least one of these conditions:

- it establishes the production dependency direction
  `sdk/xa-mass-embedded-sdk -> xa-mass-engine-starter -> xa-mass-engine`;
- it prevents task-runtime work from rediscovering `EngineConfig` /
  `TaskManager` through SDK/starter callers;
- it controls a startup or runtime handoff path that crosses engine, worker, or
  transport ownership;
- it creates the first guardable contract shape for later SDK operation cleanup.

Internal tidiness is not a criticality signal. A messy class, heavy DTO, or
awkward internal assembly path is not enough for ECSP scope unless it is also a
cross-module kernel leak.

The selected first convergence chain is therefore the embedded application
assembly chain, not the broad SDK operation facade:

```text
current:
  MassApplication
    -> MassEngine + EngineConfig
    -> TaskResultIngestFacade / WorkerResourceQueryRuntime /
       WorkerDispatchBlockRuntime / WorkerHeartbeatRuntime /
       worker delivery target resolver
    -> transport assembly

target for the first implementation slice:
  MassApplication
    -> engine-starter runtime handle
    -> narrow assembly operations and ports needed by transport/starter
```

The first slice is not allowed to expose `TaskCommandService`,
`TaskQueryService`, `TaskEventService`, `TaskWorkRuntime`, `TaskResultRuntime`,
`WorkerControlRuntime`, broad `WorkerResourceQueryRuntime`, `RuleStorage`, or
`TaskManager` as starter-facing handles. Those belong to later SDK operation
inventory or TROM, not the first assembly convergence.

The first slice may expose only the operations needed by the current
`MassApplication` startup and transport bridge:

- lifecycle: start, stop, running state, enabled state;
- assigned-dispatch handoff binding, not a task-event listener registration
  surface;
- result ingress port, without exposing `TaskManagerResultIngestFacade`;
- selected-worker delivery target resolution as a caller-owned port backed by
  worker-runtime/transport evidence owners;
- embedded worker delivery profile with only the fields consumed by transport
  assembly, currently `workerId`, `workerGroupId`, and `transportHint`;
- current-session disconnect reporting as an action into the worker-runtime
  owner, not as `WorkerDispatchBlockRuntime` or session lifecycle truth;
- worker heartbeat as a narrow externally supplied port for pull-worker session
  assembly, or an explicit temporary exception with a removal note. It must not
  define worker lifecycle or registry heartbeat semantics inside
  `xa-mass-engine-starter`.

## Non-Goals

- No public HTTP route redesign.
- No public SDK request/response redesign.
- No frontend repair or frontend compatibility guarantee.
- No guarantee to preserve non-core server view/control-console endpoints that
  only expose engine internals or frontend convenience projections.
- No new `xa-mass-task-runtime` semantic contract.
- No Redis keyspace or runtime storage rewrite.
- No task-runtime starter loop migration.
- No generic event bus or listener-first runtime integration.
- No removal of `TaskManager` as engine-internal composition root.
- No broad module-internal cleanup inside `xa-mass-engine`,
  `xa-mass-engine-starter`, `sdk/xa-mass-embedded-sdk`, or server modules unless
  it is required to remove a cross-module kernel leak, preserve core runtime
  behavior, or keep compilation/tests passing.
- No moving task lifecycle, scheduling, result policy, or terminal policy out
  of engine.
- No compatibility aliases or duplicate old/new live runtime paths.
- No promise to keep the current embedded-sdk to engine method, getter, or
  object graph shape stable.
- No broad `EngineFacade` that mirrors every current `EngineConfig` getter.
- No mechanical split of `EngineConfig` into multiple same-shape sub-facades.
- No new starter-facing owner interface unless ECSP-0 proves an external caller
  and a production reason for exposing it.
- No blanket repo-wide ban on engine imports; proof harnesses and temporary
  public-contract exceptions must be scoped explicitly.

## Do Not Start With

Do not start by deleting `TaskManager`. `TaskManager` remains the current
engine-internal composition root until TROM replaces the relevant owner slices.
The public `MassEngine.getConfig()` path is target-delete, but delete it as part
of ECSP-1/ECSP-2 after its assembly and SDK callers have moved.

Do not start by splitting `TaskManager`, normalizing engine-starter internals,
renaming DTOs, or repairing server/frontend views. Those are valid only when
they are required to remove the current cross-module leak or keep the slice
green.

Do not start by changing public HTTP or SDK payloads. The pre-TROM cleanup is
about internal module boundaries.

Do not start by wiring TROM task-runtime ports. This roadmap prepares the
engine exposure boundary; the task-runtime owner module remains TROM work.

Do not add listeners to make the boundary look decoupled. Listener fanout is
not the target API for this prerequisite roadmap.

Do not treat every current embedded-sdk to engine call as something to
preserve. Keep only what is needed to keep the current runtime operable during
the migration.

Do not create `TaskOperations`, `WorkerOperations`, `RuntimeOperations`, or
similar surfaces just to make current getters compile. First decide whether the
operation should cross the module boundary at all.

Do not preserve `com.xa.mass.starter.*` packages for compatibility by default.
First decide whether each class is a real public SDK facade, advanced embedded
assembly surface, engine-starter internal implementation, or migration residue.

## ECSP-0A Assembly Caller Inventory And First Slice Decision

Goal: make the first implementation slice concrete before moving code.

Scope:

- Inventory production and test imports/calls for the embedded application
  assembly chain under `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/**`:
  - `xa-mass-engine` Maven dependency from embedded SDK and related modules
  - `com.xa.mass.engine.*` imports in starter assembly classes
  - `MassEngine`
  - `EngineConfig`
  - `MassEngine.getConfig()`
  - `MassApplication.getEngine()`
  - `MassApplication`
  - `MassApplicationBuilder`
  - `MassEngineBuilder`
  - `EngineRuntimeBridge`
  - `RuntimeEventBusEngineBridge`
  - `StorageBackedMatchingRuleSetProvider`
- Decide the exact new module path and artifact. Default:
  `xa-mass-engine-starter` at repo root with artifact
  `com.xa.mass:xa-mass-engine-starter`.
- Decide the starter-facing package prefix. Default:
  `com.xa.mass.engine.starter`.
- Apply the compatibility decision for current `com.xa.mass.starter.*` classes:
  they are allowed to break unless ECSP-0A proves a class is a true public SDK
  facade. Advanced/internal starter classes may move to
  `com.xa.mass.engine.starter.*`, become engine-starter internals, or be
  deleted. Do not leave both old and new packages as live wrappers.
- Classify each current `MassApplication` and builder engine call as:
  `no external exposure`, `move internal to engine-starter`,
  `replace with starter lifecycle surface`, `replace with starter operation
  surface`, `assembly-only configuration`, `delete`,
  `SDK notification residue`, `test-only`, `temporary exception`, or
  `defer to TROM`.
- Classify whether each finding is a cross-module kernel leak or tolerated
  module-internal residue. Only kernel leaks may force implementation in ECSP.
- For every proposed starter-facing operation, record the external caller, the
  production behavior it preserves, the stable command/read fields, and why the
  operation cannot remain internal to `xa-mass-engine-starter`.
- Record the first implementation slice as the embedded application assembly
  convergence unless the inventory proves a smaller production caller family
  satisfies the first-slice selection rule better.
- Decide which current `com.xa.mass.starter.*` classes move to
  `xa-mass-engine-starter` and which remain embedded-sdk application assembly.
  Classify each as public SDK surface, internal assembly, test fixture, or
  migration residue before moving it.
- Mark the public `MassApplication.getEngine()` and `MassEngine.getConfig()`
  backdoor path as target-delete, inventory every production caller, assign each
  caller to the removal slice, and record the proof that will fail if the
  backdoor remains reachable after ECSP-1/ECSP-2. ECSP-0A does not delete code.

Acceptance:

- Inventory names every production assembly caller that imports engine packages
  or reaches engine through `EngineConfig` / `MassEngine.getConfig()`.
- The new module path, artifact, and package prefix are explicit.
- The `com.xa.mass.starter.*` package decision is explicit for each class:
  retain only true public SDK facades, move/rename/delete advanced or internal
  assembly classes, and do not create compatibility wrappers.
- Each starter-facing surface passes the exposure decision gate; a surface being
  narrower than `EngineConfig` is not enough.
- Every current `MassApplication` and builder engine call has a target
  classification.
- Inventory distinguishes cross-module kernel leaks from tolerated
  module-internal residue.
- Public vs internal status for current `MassEngine`, `EngineConfig`, and
  builder classes is recorded before movement.
- `MassApplication.getEngine()` and `MassEngine.getConfig()` are recorded as
  target-delete backdoors, not temporary-exception candidates. Their current
  callers, removal slices, and focused deletion/internalization proof are named.
- The accepted first slice states exactly which old call path disappears and
  which focused proof will fail if it returns.
- No code behavior changes are required in this slice.

## ECSP-0B SDK Operation Caller Inventory

Goal: classify broad SDK facade access without forcing it into the first
assembly slice.

Scope:

- Inventory `MassSdkApplication`, `DefaultRuntimeDiagnosticsOperations`, and
  related SDK operation classes that call `requireStartedEngine().getConfig()`
  or import engine services.
- Classify each operation as `public SDK behavior to preserve`, `internal
  starter operation`, `delete`, `notification residue`, `test-only`,
  `temporary exception`, or `defer to TROM`.
- Classify SDK-visible engine-owned value/config imports separately from
  services and runtime owners. For each value/config import, decide `move to
  public contract`, `move to SDK contract`, `temporary public-contract
  exception`, or `delete`.
- Do not introduce `TaskOperations`, `WorkerOperations`, `RuntimeOperations`,
  or owner-family sub-facades during this inventory.

Acceptance:

- Every broad SDK operation access to `EngineConfig` has an owner and target
  classification.
- Operations required only for current public SDK behavior are separated from
  internal runtime assembly needs.
- Every SDK-visible engine-owned value/config import has an extraction or
  temporary-exception decision.
- No SDK operation surface is approved merely because a current getter exists.
- No code behavior changes are required in this slice.

## ECSP-0C Server Route Surface Classification

Goal: prevent ECSP cleanup from deleting or reshaping server routes without a
route-level owner decision.

This slice is required only before an ECSP implementation slice deletes a
server route, changes response shape, changes auth/permission behavior, or
re-owns a server view. It is not a prerequisite for ECSP-1 when ECSP-1 only
moves embedded starter assembly.

Default outcome: route deletion, route re-owning, and frontend adapter repair
are separate follow-ups. They may enter an ECSP implementation slice only when
the route is the thing preserving an engine exposure leak, or when leaving it in
place prevents compilation or core API preservation.

Scope:

- Inventory every server route touched by a planned ECSP implementation slice
  using current controllers, `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`, and
  frontend real adapters when a frontend caller exists.
- Classify each route as one of:
  `core API`, `operator diagnostic`, `console diagnostic`, `internal debug`, or
  `obsolete-remove`.
- For `core API`, record the preserving owner surface and proof. ECSP must not
  delete core API routes.
- For `operator diagnostic`, record whether the route is preserved, re-owned,
  or deferred, plus the controller/service test or Boot-shell proof.
- For `console diagnostic`, `internal debug`, and `obsolete-remove`, route
  deletion is allowed only with a named API reference update and either a
  frontend adapter update or explicit proof that no frontend real adapter uses
  the route.
- For any route not required to remove the current engine leak or keep the slice
  compiling, record a follow-up instead of deleting/re-owning it inside ECSP.
- Record any auth/permission change with the owning guard or controller test.
- Do not use the generic "non-core view may be deleted" rule as a substitute
  for this route-level classification.

Acceptance:

- Every route touched by the planned ECSP slice has a route classification row
  in `ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md`.
- Delete/re-own/defer decisions name the API reference update, frontend adapter
  action if applicable, and proof.
- Core API routes are not deleted by ECSP.
- Non-core route cleanup is recorded as a separate follow-up unless the
  classification proves it is required by the current ECSP slice.
- No server route behavior changes are required in this classification slice.

## ECSP-1 Create Engine-Starter Module And Converge Assembly Chain

Goal: introduce the containment module and prove one real caller chain no
longer uses embedded-sdk engine internals.

Scope:

- Add `xa-mass-engine-starter` to the root Maven reactor.
- Add a module POM that depends on `xa-mass-engine` and the existing runtime,
  worker-runtime, storage, trace, and transport contracts needed for current
  engine assembly.
- Move or introduce only the approved engine-starter lifecycle/config/operation
  types under `com.xa.mass.engine.starter`.
- Keep behavior equivalent to the current embedded runtime while moving the
  engine-facing implementation surface into the new module.
- Keep internal cleanup bounded to the cross-module leak being moved. ECSP-1 is
  allowed to leave imperfect engine-starter internals when they are not
  SDK/server/starter reachable.
- ECSP-1 moves only engine-facing access and handles for the current starter
  handoffs. It must not move worker-runtime evidence ownership, transport
  assigned-delivery ownership, transport result-ingress ownership, adapter
  lifecycle, or session/heartbeat lifecycle into `xa-mass-engine-starter`.
- Retarget `MassApplication` and `MassApplicationBuilder` to the approved
  engine-starter runtime handle or assembly object instead of directly reading
  `EngineConfig` runtime service getters.
- ECSP-1 owns the implementation move for current `MassApplication ->
  EngineConfig` access to handoff handles, including result ingress,
  assigned-dispatch handoff, dispatch wakeup/recovery, worker reachability
  lookup, selected-worker delivery target resolution, current-session
  disconnect reporting, and pull-worker heartbeat access. The owner semantics of
  those handoffs remain with engine, worker-runtime, or transport as applicable.
- `xa-mass-engine-starter` may accept, compose, and pass caller-owned ports for
  those handoffs, but it must not define worker reachability truth, selected
  worker delivery truth, session lifecycle truth, worker heartbeat semantics, or
  result-ingress reliability semantics.
- Do not expose `TaskManager` or raw `EngineConfig` as the starter-facing
  contract.
- Do not expose one-for-one sub-facades for current `EngineConfig` getters.
  Unapproved owner objects stay internal to `xa-mass-engine-starter` or are
  deleted from the cross-module path.

Acceptance:

- `.\mvnw.cmd -q -pl xa-mass-engine-starter -am test` compiles.
- Within the embedded SDK starter assembly chain, only code moved into
  `xa-mass-engine-starter` may import engine implementation/service/config-owner
  types during this slice. Broad `MassSdkApplication` imports are inventoried in
  ECSP-0B and removed in ECSP-2, not forced into ECSP-1.
- The `MassApplication` startup/transport bridge path no longer calls
  `EngineConfig` runtime service getters such as `getTaskResultIngestFacade()`,
  `getWorkerResourceQueryRuntime()`, `getWorkerDispatchBlockRuntime()`, or
  `getWorkerHeartbeatRuntime()` from embedded-sdk code.
- `MassApplication` no longer relies on `getEngine().getConfig()` or raw
  `MassEngine.getConfig()` to assemble transport/starter runtime paths.
- Starter-facing surfaces do not expose raw engine services, stores, runtime
  implementations, or same-shape getter groups.
- Starter-facing surfaces do not make `xa-mass-engine-starter` a worker-runtime
  evidence owner, transport delivery owner, transport result-ingress owner, or
  session/heartbeat lifecycle owner.
- Core SDK facade behavior is unchanged. Advanced starter assembly surfaces may
  break according to the ECSP-0A classification.
- Engine and engine-starter internal residue is not treated as a slice failure
  when no production caller outside the owner module can reach it.
- No TROM task-runtime contract is introduced.

## ECSP-2 Embedded SDK Depends On Engine-Starter Only

Goal: make cross-module corruption visible and contain it in engine-starter.

Scope:

- Replace `sdk/xa-mass-embedded-sdk` direct dependency on `xa-mass-engine` with
  `xa-mass-engine-starter`.
- Move ordinary SDK facade calls from direct engine services/config lookups to
  approved starter-facing surfaces. Calls that fail the exposure decision gate
  must be deleted, moved internal to engine-starter, or deferred to TROM.
- If ECSP-2 deletes or changes any server route, response shape, auth behavior,
  or permission while moving SDK/server callers, ECSP-0C must classify that
  route first.
- Resolve SDK-visible engine-owned value/config imports by moving them to a
  public contract module / SDK contract or recording explicit temporary
  public-contract exceptions with a removal target.
- Keep SDK request/snapshot/public contracts stable.
- Preserve only core server/API semantics. Non-core server view endpoints that
  exist only to expose engine internals or frontend convenience projections are
  deferred to follow-up cleanup by default; delete or re-own them inside ECSP
  only when required to remove an engine leak or keep the slice compiling.
- Do not clean engine, server, or SDK module internals merely because broad SDK
  operation callers were inventoried. Move, delete, or contain only the
  cross-module path needed to remove the engine dependency/import leak.
- Keep existing SDK task event callbacks only as notification residue if ECSP-0
  confirms they are still needed. They must not become runtime correctness
  paths.

Acceptance:

- `sdk/xa-mass-embedded-sdk/pom.xml` no longer has a direct
  `xa-mass-engine` dependency.
- `sdk/xa-mass-embedded-sdk/src/main` has no engine
  implementation/service/config-owner imports.
- Any remaining `com.xa.mass.engine.*` SDK import is a named temporary
  public-contract exception with owner, caller, and removal target.
- `MassSdkApplication` no longer reaches through
  `requireStartedEngine().getConfig()` for ordinary operation access.
- SDK production code no longer reaches engine operations through
  `getEngine().getConfig()` or any equivalent raw `MassEngine.getConfig()`
  backdoor.
- Raw `MassEngine.getConfig()` is not exposed outside `xa-mass-engine-starter`.
- Public `MassApplication.getEngine()` / `MassEngine.getConfig()` backdoors are
  deleted or made engine-starter internal after all ECSP-1 and ECSP-2 production
  callers have moved.
- `MassSdkApplication` does not replace `getConfig()` with same-shape
  owner-family sub-facades.
- Focused embedded SDK tests still pass.
- Any server route deletion or shape/auth/permission change has a completed
  ECSP-0C classification row and the named doc/adapter/test updates.
- Any non-core route cleanup not required by the current engine leak is recorded
  as a follow-up and not implemented inside ECSP.
- Remaining module-internal cleanup is recorded only when it can re-open a
  kernel boundary leak; otherwise it is left out of ECSP.

## ECSP-3 Starter Assembly And Transport Boundary

Goal: prove the starter/transport boundary remains semantically correct after
ECSP-1 moves the current handoffs behind engine-starter.

Scope:

- Verify the ECSP-1 starter handoff still treats result ingress,
  assigned-delivery handoff, dispatch wakeup/recovery, worker reachability, and
  selected-worker delivery target resolution as transport/worker-runtime
  interactions, not engine object exposure.
- `xa-mass-engine-starter` may compose or inject worker/transport ports, but it
  must not become the semantic owner of worker-runtime evidence, transport
  assigned delivery, or transport result ingress.
- Classify current dispatch listener registration and task event listener use
  as engine-internal wiring or transitional notification residue.
- Keep transport as best-effort assigned delivery and result ingress only.
- Keep server and embedded application assembly from reading engine internals.
- Do not repair frontend consumers or optional server view endpoints as part of
  ECSP-3; prove the starter/transport semantics first.

Acceptance:

- Embedded runtime still starts/stops current behavior.
- Transport bridge code treats transport as assigned-delivery/result-ingress
  only.
- Worker evidence remains worker-runtime-owned, and delivery/result channels
  remain transport-owned.
- ECSP-3 does not retarget or re-own the same `MassApplication -> EngineConfig`
  getter paths already assigned to ECSP-1.
- No starter/SDK/runtime path depends on listener fanout for dispatch or result
  correctness.
- Existing embedded distributed transport tests still pass or have focused
  replacements named in the slice.

## ECSP-4 Guards And TROM Handoff

Goal: freeze the module boundary and give TROM a clean starting point.

Scope:

- Add or update architecture guards for:
  - `sdk/xa-mass-embedded-sdk` direct dependency on `xa-mass-engine`
  - `sdk/xa-mass-embedded-sdk/src/main` importing forbidden engine
    implementation/service/config-owner packages
  - any remaining SDK import of engine-owned public value/config contracts that
    lacks a temporary public-contract exception record in
    `ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md`
  - SDK facade using broad engine config lookup, including
    `requireStartedEngine().getConfig()`, `getEngine().getConfig()`, or raw
    `MassEngine.getConfig()` exposure outside `xa-mass-engine-starter`
  - SDK facade using same-shape sub-facades that merely mirror old
    `EngineConfig` owner getters
  - SDK/starter code using `TaskEventService` or listener fanout as runtime
    correctness path
  - transport importing engine implementation classes outside retained result
    ingest / assigned-delivery seams
  - server controllers using engine/runtime implementation contracts for core
    APIs, and non-core server view endpoints that preserve engine internals
    instead of deleting, deferring, or re-owning them
  - server route deletion or shape/auth/permission change without an ECSP-0C
    route classification row and named API reference/frontend/proof action
- Scope production guards to embedded SDK, transport production main, and server
  product/controller main paths. `xa-mass-testing/src/main` is a proof-harness
  exception unless a separate testing cleanup roadmap removes it.
- Do not add guards that freeze engine-starter internal class names, temporary
  internal assembly shape, or same-module cleanup choices.
- Add or extend these named guard targets:
  - `EmbeddedSdkEngineDependencyGuardTest`: `sdk/xa-mass-embedded-sdk` main must
    not directly depend on or import forbidden `xa-mass-engine` lanes.
  - `EngineStarterBackdoorGuardTest`: production code must not call
    `getEngine().getConfig()`, and public `MassEngine.getConfig()` must not be
    SDK/server reachable.
  - `EngineStarterSurfaceInventoryGuardTest`: every starter-facing surface must
    have a complete `Approved Starter Surfaces` row and must not be a same-shape
    `EngineConfig` getter group.
  - `EngineStarterWorkerTransportOwnershipGuardTest`: engine-starter must not
    own worker-runtime evidence, transport delivery/result-ingress, adapter
    lifecycle, or session/heartbeat lifecycle truth.
  - `EngineCallerSurfaceInventoryCompletenessGuardTest`: inventory sections
    required by the current slice must not contain `_TBD` placeholder rows.
- Update TROM if final engine-starter names or handoff contracts differ from
  this prerequisite roadmap.
- Record remaining intentional exceptions inside `xa-mass-engine-starter`.

Acceptance:

- Guards fail on the old broad embedded-sdk to engine dependency/import
  patterns.
- Guards or inventory checks fail when a new starter-facing surface lacks an
  exposure decision record.
- Guards or inventory checks fail when a remaining SDK engine import is not
  represented in
  `roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md` as a valid temporary
  public-contract exception.
- Guards fail on production `getEngine().getConfig()` or raw
  `MassEngine.getConfig()` usage outside `xa-mass-engine-starter`.
- `xa-mass-engine-starter` is the explicit containment module for remaining
  engine-facing assembly residue.
- Guards freeze cross-module boundaries, not same-module implementation style.
- Required inventory sections for ECSP-1/ECSP-2 contain completed rows or an
  explicit `N/A for this slice` row, never `_TBD` placeholders.
- Named guard targets exist as new tests or explicit extensions of existing
  guard tests before roadmap completion is claimed.
- Route-facing guards fail when ECSP changes or deletes a server route without
  the ECSP-0C route classification and named follow-up proof.
- TROM-0 can start from known engine-starter handles instead of rediscovering
  `EngineConfig` and `TaskManager` usage across SDK/server/starter code.

## Suggested Implementation Order

1. ECSP-0A: inventory the assembly caller chain and choose the first slice by
   the first-slice selection rule.
2. ECSP-1: create `xa-mass-engine-starter` and converge the
   `MassApplication` assembly chain.
3. ECSP-0B: inventory broad SDK operation callers before approving any
   operation surfaces.
4. Conditional ECSP-0C: classify server routes before any ECSP slice deletes or
   changes a server route, response shape, auth behavior, or permission.
5. ECSP-2: make embedded SDK depend on engine-starter only for approved
   assembly and operation surfaces.
6. ECSP-3: prove starter/transport semantics, listener/event-bus residue, and
   cross-module guards without re-owning ECSP-1 handoff moves.
7. ECSP-4: guards and TROM handoff.
8. Start TROM-0 from the completed engine-starter boundary and guard set.

## Verification Candidates

Focused ECSP verification commands:

```powershell
.\mvnw.cmd -q -pl xa-mass-engine-starter -am test
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am "-Dtest=*GuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am -Dtest=MassSdkTest "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am test
.\mvnw.cmd -q -pl xa-mass-engine -Dtest=EngineSchedulingCoreArchitectureGuardTest test
.\mvnw.cmd -q -pl transport/transport_runtime -Dtest=TransportConvergenceArchitectureGuardTest test
```

Named guards added for ECSP:

- `EmbeddedSdkEngineDependencyGuardTest`
- `EngineStarterBackdoorGuardTest`
- `EngineStarterSurfaceInventoryGuardTest`
- `EngineStarterWorkerTransportOwnershipGuardTest`
- `EngineCallerSurfaceInventoryCompletenessGuardTest`

ECSP did not change Spring/server wiring or server route behavior. If a future
slice changes Spring/server wiring, add a focused Spring context or Boot-shell
proof for the touched profile.

If ECSP-0C approves a server route deletion or shape/auth/permission change,
include the owning server controller/service test, the
`xa-mass-server/doc/INTERNAL_API_REFERENCE.md` update, and the matching frontend
real-adapter update/test or an explicit no-frontend-caller proof.

## Roadmap Completion Criteria

- `xa-mass-engine-starter` exists in the reactor and owns engine-facing
  assembly/config/operation handles.
- `sdk/xa-mass-embedded-sdk` no longer directly depends on `xa-mass-engine`.
- `sdk/xa-mass-embedded-sdk/src/main` no longer imports engine implementation
  service/config/runtime-owner packages.
- Remaining SDK imports of engine-owned value/config contracts are either moved
  to public contract modules or recorded as temporary public-contract
  exceptions with removal targets.
- `MassSdkApplication` no longer uses `EngineConfig` as a broad runtime service
  locator for ordinary SDK operations.
- Starter assembly consumes only approved engine-starter surfaces and retained
  worker/transport operation ports.
- Exposed starter surfaces are justified by external caller need; old owner
  getters are not preserved as same-shape sub-facades.
- `xa-mass-engine-starter` does not own worker-runtime evidence, transport
  assigned delivery, transport result ingress, adapter lifecycle, or
  session/heartbeat lifecycle truth.
- `com.xa.mass.starter.*` advanced assembly surfaces are moved, renamed, or
  deleted without compatibility wrappers unless ECSP-0A proved a true public
  SDK facade.
- Public `MassApplication.getEngine()` and `MassEngine.getConfig()` backdoors
  are deleted or made engine-starter internal only.
- Core server/API behavior remains stable; non-core view/control-console
  surfaces are not preserved when they depend on engine internals or frontend
  convenience projections, but any route deletion or shape/auth/permission
  change passed ECSP-0C route classification.
- Listener and task-event surfaces are classified as internal, notification
  residue, or explicit temporary exceptions; they are not target TROM boundary
  APIs.
- Server controllers/product services do not depend on engine/runtime
  implementation internals.
- Guards block direct embedded-sdk to engine dependency/import patterns from
  returning.
- Required inventory sections have no `_TBD` placeholder rows, and named guard
  targets exist as tests or explicit extensions of existing guard tests.
- Roadmap completion does not require `TaskManager` decomposition,
  engine-starter internal cleanup, non-core server view repair, frontend repair,
  or broad DTO cleanup when those residues no longer cross a kernel boundary.
- TROM-0 is updated or cross-linked to consume the engine-starter boundary,
  inventory, and guard set.
