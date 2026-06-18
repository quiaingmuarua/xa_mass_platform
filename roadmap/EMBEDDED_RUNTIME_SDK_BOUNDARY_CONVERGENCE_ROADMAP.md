# Embedded Runtime And SDK Boundary Convergence Roadmap

Status: proposed direction document.

Related records:

- `README.md`
- `sdk/README.md`
- `sdk/xa-mass-embedded-sdk/README.md`
- `sdk/xa-mass-embedded-sdk-api/README.md`
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`
- `xa-mass-server/src/main/java/com/xa/mass/server/XaMassServerApplication.java`

## Summary

The current module graph keeps many owner boundaries clear, but the embedded SDK
artifact now carries two different jobs:

```text
stable SDK contracts and facade
  + embedded runtime assembly for engine/runtime/transport
```

That is acceptable for an in-process library product, but `xa-mass-server`
depends on `sdk/xa-mass-embedded-sdk` as a production dependency. This makes the
server consume the SDK product artifact to obtain runtime assembly, pulling the
SDK facade, runtime implementation, transport adapters, and contract interfaces
through one dependency.

Target outcome:

```text
sdk/xa-mass-embedded-sdk-api
  -> stable embedded/server contract interfaces and models

sdk/xa-mass-embedded-runtime
  -> JVM runtime assembly and implementation over engine/runtime/transport

sdk/xa-mass-embedded-sdk
  -> external in-process SDK facade built on the runtime assembly

xa-mass-server
  -> server host assembly over embedded-runtime + api contracts
  -> no production dependency on xa-mass-embedded-sdk
```

This is a boundary convergence roadmap, not a behavior redesign. It should not
change task lifecycle, scheduling, worker reachability, transport delivery,
result convergence, auth policy, or public HTTP contracts.

## Current Code Observations

- Root `pom.xml` currently declares `sdk/xa-mass-embedded-sdk-api`,
  `sdk/xa-mass-embedded-sdk`, and `sdk/xa-mass-java-sdk` as sibling modules.
- `xa-mass-server/pom.xml` has a production dependency on
  `xa-mass-embedded-sdk`.
- `sdk/xa-mass-embedded-sdk/pom.xml` has production dependencies on
  `xa-mass-engine`, `xa-mass-worker-runtime`, `mass-runtime-memory`,
  `mass-storage-memory`, `xa-mass-transport-api`,
  `xa-mass-transport-runtime`, `xa-mass-transport-polling`,
  `xa-mass-transport-socket`, and `xa-mass-transport-websocket`.
- `XaMassServerApplication#fullStackRuntimeApplication(...)` builds a
  `MassSdkApplication` through `MassSdk.builder()` and registers it as the
  production runtime bean for `memory-local` and `durable-local`.
- Server main code injects SDK-facing operation interfaces such as
  `TaskQueryOperations`, `TaskAdminOperations`, `TaskResultQueryOperations`,
  `RuntimeDiagnosticsOperations`, `ControlPlaneCatalog`,
  `PrincipalDirectory`, and SDK model/auth types.
- Many pure contracts already live in `xa-mass-embedded-sdk-api`, including
  catalog/auth/model contracts, `WorkerControlOperations`, and
  `TaskStageEvidenceOperations`.
- Many pure operation interfaces still live in `xa-mass-embedded-sdk`, including
  task, worker, resource, diagnostics, runtime-control, bootstrap, and listener
  contracts. This forces server production code to depend on the heavy SDK
  artifact even when it only needs contracts.
- `com.xa.mass.starter.*` and starter config classes are runtime assembly, not
  SDK contract. They currently live in `xa-mass-embedded-sdk`.
- `WorkerClientOperations` is not a pure API extraction candidate today. It
  exposes `EmbeddedPullWorkerSession`, `PulledTaskDispatch`, `TaskPullResult`, and
  transport-owned `TaskResultReport`; `EmbeddedPullWorkerSession` imports transport
  channel, lease, route, result, and runtime delivery types.
- `MassRuntimeControl` and `RuleOperations` expose `RuleDefinition` from
  `xa-mass-kernel-spi`. Moving them to `xa-mass-embedded-sdk-api` requires an
  explicit API dependency decision or a neutral rule contract split.
- `sdk/xa-mass-embedded-sdk/pom.xml` owns the current japicmp profile for
  `com.xa.mass.sdk.*`. Moving public SDK interfaces to
  `xa-mass-embedded-sdk-api` changes artifact-level compatibility proof unless
  the guard moves or becomes aggregate/classpath-aware in the same slice.
- A zero direct dependency in `xa-mass-server/pom.xml` is not enough by itself.
  Today the server can still import runtime implementation packages when the
  SDK facade brings those modules onto the compile classpath transitively. This
  roadmap must guard both direct POM dependencies and server source imports.

## Owner Review

SDK API contracts belong to `sdk/xa-mass-embedded-sdk-api`.

Embedded JVM runtime assembly belongs to a runtime/assembly module. It may
depend on engine, worker-runtime, runtime infra, storage adapters, transport
runtime, and transport adapters because its owner is process-local runtime
composition.

The external embedded SDK facade belongs to `sdk/xa-mass-embedded-sdk`. It may
depend on the runtime assembly to provide the `MassSdk.builder()` library
experience, but server production code should not depend on that facade.

Server owns Spring Boot host assembly, HTTP/auth/session/API/product shell
behavior, server-local control-plane stores, and startup wiring. Server may
consume embedded-runtime implementation and SDK API contracts, but it must not
use the external SDK facade as the production owner of server runtime assembly.

Engine continues to own task lifecycle, scheduling orchestration, dispatch
binding, result convergence, and terminal policy. Worker runtime continues to
own worker lifecycle, reachability, scheduling evidence, admission, dispatch
gates, and worker report projection. Transport continues to own protocol
sessions, endpoint/route evidence, delivery queues, and final-hop mechanics.

Worker pull/session API is a data-plane contract decision, not a pure operation
interface move. The roadmap must not copy transport session objects into
`xa-mass-embedded-sdk-api` unchanged just to make server imports compile.

## Boundary Decision

Create `sdk/xa-mass-embedded-runtime` as the owner of current embedded runtime
composition.

Expand `sdk/xa-mass-embedded-sdk-api` so server and SDK can share stable
operation contracts without pulling runtime implementation or the SDK facade.

Retarget `xa-mass-server` production dependencies away from
`xa-mass-embedded-sdk` and toward:

```text
sdk/xa-mass-embedded-sdk-api
sdk/xa-mass-embedded-runtime
```

Keep `sdk/xa-mass-embedded-sdk` as the external in-process SDK product artifact.
It can remain a convenient one-dependency facade for library users, but it is no
longer the server production assembly dependency.

Do not move `WorkerClientOperations` or `ExternalWorkerOperations` into
`xa-mass-embedded-sdk-api` until the worker pull/session contract is owned and
transport/session implementation types are either hidden behind runtime/facade
code or replaced by neutral API DTOs.

Do not move `MassRuntimeControl`, `MassBootstrapDataProvider`, or
`RuleOperations` into `xa-mass-embedded-sdk-api` until the roadmap records
whether `xa-mass-kernel-spi` is an allowed API dependency or rule definitions
must be represented by an API-owned contract.

Public SDK compatibility is an execution prerequisite for any slice that moves
`com.xa.mass.sdk.*` classes or interfaces between artifacts. Artifact-level
japicmp compatibility and Maven transitive classpath compatibility are separate
proof surfaces.

Direct dependency isolation and compile-classpath hard isolation are different
targets. This roadmap makes server ownership enforceable through direct
dependency removal plus source/import guards. If the goal becomes "server Java
compilation cannot even see engine/worker-runtime/transport-runtime classes",
that requires a separate packaging or launcher decision, such as optional/
provided runtime implementation dependencies plus a distribution module,
shading/relocation, or moving runtime hosting out of the server process.

## Target Module Shape

| Module | Role | Production dependencies allowed | Must not own |
| --- | --- | --- | --- |
| `sdk/xa-mass-embedded-sdk-api` | stable embedded/server contracts and SDK models | narrow neutral dependencies only; `xa-mass-kernel-spi` only after an explicit rule-contract decision | engine, server, transport runtime, storage/runtime implementation |
| `sdk/xa-mass-embedded-runtime` | process-local runtime assembly and implementation | engine, worker-runtime, runtime infra, storage adapters, transport api/runtime/adapters, trace sink | public HTTP DTOs, server auth/session/product shell, external Java SDK client |
| `sdk/xa-mass-embedded-sdk` | external in-process SDK facade | embedded-sdk-api, embedded-runtime | server production wiring, server control-plane stores |
| `xa-mass-server` | Spring Boot host/API/product shell | embedded-sdk-api, embedded-runtime, server-owned stores | SDK facade ownership, direct engine/worker-runtime/transport-runtime dependencies, external Java SDK client behavior |

## Do Not Start With

Do not start by deleting the server dependency on `xa-mass-embedded-sdk`.

First classify server callers, move contracts out of the heavy SDK artifact,
create the runtime assembly owner, then retarget server dependencies. Deleting
the dependency first will create a broad compile break and encourage
compatibility wrappers.

Do not move worker pull/session types as part of the first pure contract slice.
That would move transport/session ownership into API by accident.

Do not rename `MassSdkApplication` or move public `com.xa.mass.sdk.*` classes
between artifacts without updating the public SDK compatibility guard in the
same slice. Public SDK naming may still need a later decision, but the
artifact-level compatibility proof cannot be deferred once classes move.

## Non-Goals

1. No task lifecycle, scheduling, worker selection, worker reachability, result,
   transport delivery, or trace behavior change.
2. No change to public HTTP route shape or external Java SDK HTTP client
   behavior.
3. No compatibility alias for superseded internal server assembly paths.
4. No move of worker capability packs into SDK modules.
5. No server dependency on `xa-mass-java-sdk`.
6. No expansion of `xa-mass-public-contract`; this roadmap is about embedded
   JVM contracts, not public Controller wire DTO ownership.
7. No new server bootstrap API or seed taxonomy change.
8. No broad rename-only churn before dependency ownership is converged.
9. No unchanged migration of transport/session implementation types into
   `xa-mass-embedded-sdk-api`.

## ERB-0 Inventory And Classification

Goal: create the exact caller and dependency inventory before moving code.

Scope:

- Inventory main-source imports from `xa-mass-server` to `com.xa.mass.sdk.*`
  and classify each as API contract, SDK facade, runtime assembly, diagnostic,
  auth/catalog/model, test fixture, or residue.
- Inventory `xa-mass-embedded-sdk` main packages and classify each file as API
  contract, facade, runtime assembly, runtime implementation helper, transport
  worker public DTO/session, diagnostics implementation, auth/catalog support,
  or residue.
- Separate production and test dependencies for `xa-mass-server`,
  `xa-mass-embedded-sdk`, and the new target module.
- Decide whether worker pull DTO/session contracts stay in `embedded-sdk-api`
  or remain in the SDK facade until a public worker-session contract slice.
- Decide public SDK compatibility guard ownership for moved
  `com.xa.mass.sdk.*` types: API artifact check, aggregate/classpath check, or
  explicit deferral of the move.
- Classify `RuleOperations`, `MassRuntimeControl`, and
  `MassBootstrapDataProvider`; decide whether `xa-mass-kernel-spi` is allowed
  from `xa-mass-embedded-sdk-api` or whether an API-owned rule contract is
  required first.

Out of scope:

- Moving files.
- Changing Maven dependencies.
- Renaming public classes.

Acceptance:

- A sibling inventory file exists beside this roadmap, with at least these
  tables: server imports, SDK package classification, Maven dependencies,
  first target module for each symbol family.
- Production and test usage are separated.
- The inventory names the first executable move set for ERB-1 and explicitly
  excludes worker pull/session contracts from that move set unless a neutral
  API contract has already been chosen.
- The inventory records the public SDK compatibility guard strategy before any
  public `com.xa.mass.sdk.*` class or interface moves across artifacts.
- The inventory records the `xa-mass-kernel-spi` / rule-contract decision for
  `RuleOperations`, `MassRuntimeControl`, and `MassBootstrapDataProvider`.

Verification candidates:

```powershell
rg -n "import com\.xa\.mass\.sdk\.|import com\.xa\.mass\.starter\." xa-mass-server\src\main\java xa-mass-server\src\test\java
rg -n "<artifactId>xa-mass-embedded-sdk|<artifactId>xa-mass-embedded-sdk-api|<artifactId>xa-mass-java-sdk" xa-mass-server\pom.xml sdk\*\pom.xml
rg --files sdk\xa-mass-embedded-sdk\src\main\java
```

## ERB-1 Expand Embedded SDK API Pure Contracts

Goal: make `xa-mass-embedded-sdk-api` the real shared contract artifact for
server and embedded SDK without importing transport/session/runtime shapes.

Scope:

- Move only operation, listener, and model contracts proven by ERB-0 to be free
  of engine, server, transport runtime/channel/lease/delivery, runtime
  implementation, storage implementation, and undecided kernel-spi dependencies.
- Initial candidate move set:
  - `TaskQueryOperations`
  - `TaskResultQueryOperations`
  - `TaskAdminOperations`
  - `TaskDiagnosticOperations`
  - `RuntimeDiagnosticsOperations`
  - `ResourceOperations`
  - `ProjectOperations`
  - `EventOperations`
  - `CredentialPrincipalOperations`
  - `WorkerInspectionOperations`
  - `WorkerQueryOperations`
  - `WorkerRegistryOperations`
  - `WorkerTopologyOperations`
  - `WorkerAdminOperations`
  - `TaskWorkFinalListener`
  - `TaskWorkAttemptClosedListener`
- Keep these out of ERB-1 until ERB-2 settles their owner boundary:
  - `WorkerClientOperations`
  - `ExternalWorkerOperations`
  - `EmbeddedPullWorkerSession`
  - `PulledTaskDispatch`
  - `TaskPullResult`
  - `TaskResultReport`
- Keep `RuleOperations`, `MassRuntimeControl`, and
  `MassBootstrapDataProvider` out of the first move unless ERB-0 has chosen and
  documented the `xa-mass-kernel-spi` / rule-contract owner and the required API
  POM change is included in this slice.
- Keep implementations, builders, runtime config, and default diagnostics out of
  `xa-mass-embedded-sdk-api`.
- Apply the chosen public SDK compatibility guard strategy in this same slice
  for any moved `com.xa.mass.sdk.*` symbols.
- Update package imports in current producers and consumers after each move.

Out of scope:

- Moving `MassSdk`, `MassSdkApplication`, `MassApplication`, `MassEngine`, or
  starter config.
- Creating server runtime assembly.
- Changing public method semantics.
- Moving worker pull/session contracts unchanged into API.
- Deferring public SDK compatibility proof for moved public symbols.

Acceptance:

- Server main sources that only use moved pure operation interfaces,
  auth/catalog/model contracts, or listeners compile with
  `xa-mass-embedded-sdk-api` available for those symbols.
- `xa-mass-embedded-sdk-api` still has no dependency on engine, server,
  transport runtime/channel/lease/delivery, runtime implementation, or storage
  implementation modules.
- `WorkerClientOperations`, `ExternalWorkerOperations`, `EmbeddedPullWorkerSession`,
  `TaskResultReport`, and transport session types are not introduced into
  `xa-mass-embedded-sdk-api` by this slice.
- Public SDK compatibility checking is updated in the same slice so moved
  public contracts are checked at their new owner or through an explicitly
  accepted aggregate/classpath proof. ERB-6 must not be the first place this is
  decided.
- `xa-mass-embedded-sdk` compiles after importing the moved interfaces from the
  API artifact.

Verification candidates:

```powershell
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests compile
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk -am test
if (rg -n "xa-mass-engine|xa-mass-server|xa-mass-transport-runtime|mass-runtime-memory|mass-storage-memory" sdk\xa-mass-embedded-sdk-api\pom.xml) { throw "embedded-sdk-api has a forbidden runtime/server dependency" }
if (rg -n "WorkerClientOperations|ExternalWorkerOperations|EmbeddedPullWorkerSession|PulledTaskDispatch|TaskResultReport|TransportEndpointLease|DeliveryPullChannel" sdk\xa-mass-embedded-sdk-api\src\main\java) { throw "worker pull/session implementation types entered embedded-sdk-api before ERB-2" }
rg -n "japicmp|mass.sdk.api.baselineVersion|com\.xa\.mass\.sdk" sdk\*\pom.xml
```

## ERB-2 Settle Worker Session And Rule/Bootstrap Contract Boundaries

Goal: resolve contract families that block server retargeting but are not pure
API moves.

Scope:

- Choose and document one worker data-plane target:
  - introduce transport-neutral API request/result/session DTOs in
    `xa-mass-embedded-sdk-api`, with transport/session implementations hidden in
    runtime or SDK facade code; or
  - keep `WorkerClientOperations` as an SDK facade surface and introduce a
    runtime-owned server/internal worker pull/result port for server assembly.
- Do not move `WorkerClientOperations` unchanged while it exposes
  `EmbeddedPullWorkerSession`, `PulledTaskDispatch`, or transport-owned
  `TaskResultReport`.
- Decide whether `PulledTaskDispatch` and `TaskPullResult` are API DTOs,
  runtime DTOs, or SDK facade DTOs; remove transport helper dependencies before
  moving them to API.
- Decide whether `TaskResultReport` remains transport-owned or gets an
  API-owned result submission DTO with runtime/transport mapping.
- Decide the rule/bootstrap contract target:
  - explicitly allow `xa-mass-kernel-spi` from `xa-mass-embedded-sdk-api`; or
  - introduce API-owned rule contract types and keep kernel-spi internal to
    engine/runtime assembly.
- Move `RuleOperations`, `MassRuntimeControl`, and
  `MassBootstrapDataProvider` only after that rule/bootstrap decision is
  reflected in the API POM and imports.

Out of scope:

- Creating the runtime assembly module.
- Server dependency retargeting.
- Changing worker pull/result behavior, lease semantics, route ownership, or
  task result convergence.

Acceptance:

- Worker pull/session API ownership is explicit, and no transport channel,
  lease, delivery, or runtime implementation type enters
  `xa-mass-embedded-sdk-api` without a named owner decision.
- `WorkerClientOperations` is either still in the SDK facade or has been
  narrowed into a transport-neutral API contract; it is not moved unchanged.
- Rule/bootstrap contracts either remain in the SDK facade/runtime until a
  neutral contract exists, or `xa-mass-embedded-sdk-api` explicitly records the
  approved `xa-mass-kernel-spi` dependency.
- Server and SDK still compile after any contract moves in this slice.

Verification candidates:

```powershell
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests compile
if (rg -n "import com\.xa\.mass\.transport\.(channel|lease|runtime)|import com\.xa\.mass\.transport\.model\.TaskResultReport" sdk\xa-mass-embedded-sdk-api\src\main\java) { throw "transport session/result implementation imports entered embedded-sdk-api" }
rg -n "WorkerClientOperations|ExternalWorkerOperations|EmbeddedPullWorkerSession|PulledTaskDispatch|TaskPullResult|TaskResultReport|RuleOperations|MassRuntimeControl|MassBootstrapDataProvider" sdk\xa-mass-embedded-sdk-api\src\main\java sdk\xa-mass-embedded-sdk\src\main\java xa-mass-server\src\main\java
rg -n "<artifactId>xa-mass-kernel-spi</artifactId>|<artifactId>xa-mass-transport" sdk\xa-mass-embedded-sdk-api\pom.xml
```

## ERB-3 Create Embedded Runtime Assembly Module

Goal: move runtime assembly ownership out of the SDK product artifact.

Scope:

- Add Maven module `sdk/xa-mass-embedded-runtime`.
- Move `com.xa.mass.starter.*` and starter config/builder runtime assembly into
  the new module.
- Move runtime implementation helpers that depend on engine/runtime/transport
  internals into the new module.
- Decide the runtime application type exposed by this module. The preferred
  target is a runtime-owned type such as `EmbeddedMassRuntimeApplication` or
  `MassEmbeddedRuntime` that implements the contracts moved to
  `xa-mass-embedded-sdk-api`.
- Keep `sdk/xa-mass-embedded-sdk` as the public library facade; it should build
  or wrap the runtime-owned application rather than own the runtime assembly.
- Keep server out of the new module.

Out of scope:

- Server retargeting.
- Public SDK compatibility cleanup beyond imports and delegation required to
  compile.
- Removing `MassSdkApplication` before a separate public facade naming decision.

Acceptance:

- `xa-mass-embedded-runtime` owns runtime assembly dependencies currently
  concentrated in `xa-mass-embedded-sdk`.
- `xa-mass-embedded-sdk` no longer directly owns `com.xa.mass.starter.*`
  classes.
- `MassSdk.builder()` still provides the in-process SDK facade by delegating to
  the runtime assembly.
- Existing focused embedded-sdk behavior tests are either moved to the runtime
  module or remain as facade tests, with ownership clear from test package and
  module placement.

Verification candidates:

```powershell
.\mvnw.cmd -pl sdk/xa-mass-embedded-runtime,sdk/xa-mass-embedded-sdk -am test
if (rg -n "package com\.xa\.mass\.starter" sdk\xa-mass-embedded-sdk\src\main\java) { throw "starter runtime assembly still lives in embedded-sdk" }
rg -n "<artifactId>xa-mass-engine|<artifactId>xa-mass-worker-runtime|<artifactId>xa-mass-transport-runtime" sdk\xa-mass-embedded-runtime\pom.xml sdk\xa-mass-embedded-sdk\pom.xml
```

## ERB-4 Retarget Server Production Assembly

Goal: make server consume the runtime owner and API contracts, not the SDK
product facade.

Scope:

- Replace the server production dependency on `xa-mass-embedded-sdk` with
  `xa-mass-embedded-sdk-api` and `xa-mass-embedded-runtime`.
- Remove any direct server production dependency on `xa-mass-engine`,
  `xa-mass-worker-runtime`, and `xa-mass-transport-runtime`; those are runtime
  implementation dependencies owned by `xa-mass-embedded-runtime`.
- Remove server source imports of `com.xa.mass.engine.*`,
  `com.xa.mass.worker.runtime.*`, and `com.xa.mass.transport.runtime.*`. This is
  required even when those packages are still visible through transitive compile
  classpath dependencies.
- Update `XaMassServerApplication` so the Spring runtime bean is built through
  the embedded runtime assembly, not `MassSdk.builder()`.
- Type server beans and controller constructor dependencies to API contracts
  wherever possible.
- Retarget server worker pull/result controller dependencies to the ERB-2
  worker data-plane decision, not to the external SDK facade by default.
- Keep external Java SDK usage test-scoped where tests intentionally exercise
  real external SDK behavior.
- Preserve server profile behavior and startup wiring for `memory-local` and
  `durable-local`.

Out of scope:

- Changing route authorization.
- Changing seed/import taxonomy.
- Changing runtime infra defaults.
- Removing all test-scope SDK usage if a test intentionally proves external SDK
  behavior.

Acceptance:

- `xa-mass-server/pom.xml` has no production dependency on
  `xa-mass-embedded-sdk`.
- `xa-mass-server/pom.xml` has no production direct dependency on
  `xa-mass-engine`, `xa-mass-worker-runtime`, or `xa-mass-transport-runtime`.
- `xa-mass-server/src/main/java` has no imports from engine, worker-runtime, or
  transport-runtime implementation packages.
- `xa-mass-server/src/main/java` does not import `MassSdk` or the external SDK
  facade as production runtime assembly.
- Server worker pull/result APIs compile against the API or runtime-owned
  contract chosen in ERB-2, not because `xa-mass-embedded-sdk` remains on the
  production classpath.
- Server runtime startup still wires task shell store, task work runtime, task
  result runtime, worker registry, rule storage, credential principal store,
  transport adapters, delivery store, route-owner store, and trace sink through
  the same owner boundaries.
- Spring startup/context proof runs for the affected profiles because this
  slice touches `XaMassServerApplication` assembly.

Verification candidates:

```powershell
if (rg -n "<artifactId>xa-mass-embedded-sdk</artifactId>" xa-mass-server\pom.xml) { throw "server still has a production embedded-sdk dependency" }
if (rg -n "<artifactId>xa-mass-engine</artifactId>|<artifactId>xa-mass-worker-runtime</artifactId>|<artifactId>xa-mass-transport-runtime</artifactId>" xa-mass-server\pom.xml) { throw "server still has a direct runtime implementation dependency" }
if (rg -n "import com\.xa\.mass\.engine\.|import com\.xa\.mass\.worker\.runtime\.|import com\.xa\.mass\.transport\.runtime\." xa-mass-server\src\main\java) { throw "server main still imports runtime implementation packages" }
if (rg -n "import com\.xa\.mass\.sdk\.MassSdk|import com\.xa\.mass\.sdk\.MassSdkApplication|import com\.xa\.mass\.sdk\.WorkerClientOperations|import com\.xa\.mass\.sdk\.ExternalWorkerOperations" xa-mass-server\src\main\java) { throw "server main still imports external SDK facade as production assembly" }
.\mvnw.cmd -pl xa-mass-server -am "-Dtest=ServerMainSourceArchitectureGuardTest,CleanServerStartupIntegrationTest,ControlPlaneSeedImportIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## ERB-5 Add Dependency And Import Guards

Goal: prevent the same dependency collapse from returning.

Scope:

- Add a Maven enforcer or architecture guard that bans
  `xa-mass-server` production dependency on `xa-mass-embedded-sdk`.
- Add a Maven enforcer or architecture guard that bans direct
  `xa-mass-server` production dependencies on `xa-mass-engine`,
  `xa-mass-worker-runtime`, and `xa-mass-transport-runtime`.
- Add guards that keep `xa-mass-embedded-sdk-api` free of engine, server,
  runtime implementation, storage implementation, and transport runtime
  dependencies.
- Add guards that keep transport channel, lease, delivery, and runtime session
  implementation types out of `xa-mass-embedded-sdk-api` unless a later
  explicit owner decision changes the API boundary.
- Add a guard that keeps `xa-mass-embedded-runtime` free of server and external
  Java SDK dependencies.
- Add or update server source guards so production code cannot import
  `MassSdk` facade classes as assembly owners.
- Add server source guards so production code cannot import engine,
  worker-runtime, or transport-runtime implementation packages even if Maven
  transitive dependencies make those packages compile-visible.
- Add or move the public SDK compatibility guard to the owner chosen in ERB-1
  and ERB-2.
- Update `sdk/README.md`, `sdk/xa-mass-embedded-sdk-api/README.md`,
  `sdk/xa-mass-embedded-sdk/README.md`, and `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`
  to describe the new split.

Out of scope:

- Renaming all public SDK classes.
- Archiving this roadmap.

Acceptance:

- Guards fail on the old `server -> xa-mass-embedded-sdk` production dependency.
- Guards fail on direct server production dependencies to engine,
  worker-runtime, or transport-runtime implementation modules.
- Guards fail on server production source imports from engine, worker-runtime,
  or transport-runtime implementation packages.
- Guards fail if `xa-mass-embedded-sdk-api` gains runtime implementation
  dependencies.
- Guards fail if transport session implementation types enter
  `xa-mass-embedded-sdk-api` outside the documented worker data-plane contract.
- The public SDK compatibility guard covers moved public contracts at the API
  artifact or aggregate/classpath surface chosen earlier.
- Docs no longer describe `xa-mass-embedded-sdk` as the server runtime assembly
  dependency.

Verification candidates:

```powershell
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-runtime,sdk/xa-mass-embedded-sdk,xa-mass-server -am test
.\mvnw.cmd -pl xa-mass-server -am "-Dtest=ServerMainSourceArchitectureGuardTest" test
```

## ERB-6 Residue Cleanup And Compatibility Review

Goal: remove stale dependency vocabulary and decide whether public SDK facade
compatibility has remaining facade naming or artifact-shape residue after the
guard owner has already been chosen.

Scope:

- Run residue scans for:
  - stale docs saying server starts from `xa-mass-embedded-sdk`
  - stale `starter` placement references
  - test-only imports that accidentally mask production dependency removal
  - duplicate runtime builder paths
  - compatibility aliases that keep old and new assembly paths alive
- Verify the ERB-1/ERB-2 public SDK compatibility guard placement is still
  correct after runtime and server retargeting.
- Decide whether `MassSdkApplication` remains the public facade class, becomes a
  wrapper over runtime-owned implementation, or is renamed in a later public SDK
  compatibility roadmap.
- Update proof registry/testing index only if the proof ownership changes.
- Archive this roadmap only after dependency, guard, doc, and residue criteria
  are satisfied.

Out of scope:

- Public SDK API breaking changes without an explicit compatibility decision.
- Transport internal id cleanup.
- Worker eligibility vocabulary cleanup.

Acceptance:

- No production server dependency or import path points at the SDK facade.
- No active docs describe target state as already implemented unless the
  corresponding code and guards are in place.
- Any remaining public SDK facade compatibility debt is either resolved or
  recorded in a separate follow-up roadmap.
- Completed facts are moved to owning READMEs/baselines before archive.

Verification candidates:

```powershell
rg -n "xa-mass-embedded-sdk|MassSdkApplication|MassSdk\.builder|com\.xa\.mass\.starter" README.md doc sdk xa-mass-server roadmap
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-runtime,sdk/xa-mass-embedded-sdk,xa-mass-server -am test
```

## Suggested Implementation Order

1. Land ERB-0 as inventory only.
2. Land ERB-1 as pure contract extraction.
3. Land ERB-2 as worker session plus rule/bootstrap contract decision.
4. Land ERB-3 as runtime module creation and runtime assembly move.
5. Land ERB-4 as server retarget plus startup proof.
6. Land ERB-5 guards and owner docs.
7. Land ERB-6 residue cleanup and public facade compatibility review.

No slice should leave the repository compiling only after a later slice.

## Roadmap Completion Criteria

This roadmap is complete only when all are true:

- `xa-mass-server` has no production dependency on `xa-mass-embedded-sdk`.
- `xa-mass-server/pom.xml` has zero direct production dependencies on
  `xa-mass-engine`, `xa-mass-worker-runtime`, and `xa-mass-transport-runtime`.
- `xa-mass-server` production runtime assembly is owned by
  `xa-mass-embedded-runtime` or server-local configuration, not the SDK facade.
- `xa-mass-embedded-sdk-api` owns the shared embedded/server operation
  contracts needed by server and SDK.
- Worker pull/session contracts have an explicit owner, and transport
  session/runtime implementation types do not enter API by accident.
- Public SDK compatibility checking covers moved public contracts at the chosen
  artifact or aggregate/classpath proof surface.
- `xa-mass-embedded-runtime` owns runtime assembly and is explicitly allowed to
  depend on engine/runtime/transport implementation modules.
- `xa-mass-embedded-sdk` remains an external in-process SDK facade, not a server
  production dependency.
- Architecture or Maven guards prevent regression.
- Startup/context proof passes for server profile wiring touched by the change.
- Owner docs and SDK/integration boundary docs match current code.
- Residue scan is complete and any public SDK compatibility leftovers are
  resolved or moved to a separate active roadmap.
