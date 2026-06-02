# SDK Module Layout And Public Contract Roadmap

Status: implemented mainline.

This roadmap converges the SDK module layout, the shared public contract owner,
and the embedded SDK artifact names. It is not a package rename roadmap and it
does not change runtime behavior by itself.

SCL-0 inventory is tracked in
`SDK_MODULE_LAYOUT_AND_PUBLIC_CONTRACT_INVENTORY.md`.

## Current Code Observations

- `sdk/xa-mass-java-sdk` is the external Java HTTP SDK used by external task
  producers and workers. It owns typed clients, worker sessions, and
  transport-neutral handler runtime types under `com.xa.mass.client.*`.
- `sdk/xa-mass-public-contract` owns the first narrow Controller-exposed wire
  contract slice for task create, item append, sync append, task command, and
  shared task routing constants under `com.xa.mass.contract.task`.
- `xa-mass-server` now uses `xa-mass-public-contract` request DTOs at the public
  `TaskApiController` boundary and converts to embedded SDK/runtime DTOs inside
  the server adapter.
- `sdk/xa-mass-embedded-sdk-api` remains the embedded/runtime-facing SDK API
  contract module. It may contain auth/catalog/event/model contracts that are
  not public HTTP wire contracts.
- `sdk/xa-mass-embedded-sdk` is the embedded JVM runtime composition SDK. It
  assembles engine, worker runtime, trace, storage, and transport modules while
  keeping Java package names under `com.xa.mass.sdk.*`.
- `integrations/xa-mass-scenario-launcher` is the primary external Java SDK
  adopter. `integrations/xa-mass-worker-pack` remains a worker capability pack
  and may keep an embedded SDK dependency only for active server E2E harness
  support recorded in the worker-pack inventory.
- The root project now keeps SDK product modules under `sdk/`.

## Owner Decision

SDK ownership should be split by caller boundary, not by historical artifact
names:

| Owner | Target Module | Role |
| --- | --- | --- |
| public HTTP contract | `sdk/xa-mass-public-contract` | Controller-exposed wire DTOs/constants used by server and external SDKs |
| external Java SDK | `sdk/xa-mass-java-sdk` | typed Java client/session/handler surface for external task producers and workers |
| embedded SDK API | `sdk/xa-mass-embedded-sdk-api` | embedded/runtime-facing API contracts that may still depend on platform internals |
| embedded SDK runtime | `sdk/xa-mass-embedded-sdk` | in-process JVM runtime composition and starter APIs |
| integrations | `integrations/xa-mass-scenario-launcher`, `integrations/xa-mass-worker-pack` | real external adopters and capability packs built on SDKs |

`xa-mass-public-contract` must sit below both server and external SDKs. It must
not depend on server, engine, transport, `xa-mass-base`, embedded SDK modules,
or `integrations/*`.

## Target Layout

```text
sdk/
  xa-mass-public-contract/
  xa-mass-java-sdk/
  xa-mass-embedded-sdk-api/
  xa-mass-embedded-sdk/

integrations/
  xa-mass-scenario-launcher/
  xa-mass-worker-pack/
```

Target Maven artifact IDs:

| Previous Artifact | Current Artifact | Package Rename |
| --- | --- | --- |
| none | `xa-mass-public-contract` | new package only |
| `xa-mass-java-sdk` | `xa-mass-java-sdk` | no |
| previous `xa-mass-sdk-api` | `xa-mass-embedded-sdk-api` | no |
| previous `xa-mass-sdk` | `xa-mass-embedded-sdk` | no |

The package names `com.xa.mass.client.*` and `com.xa.mass.sdk.*` remain stable
through this roadmap. A package rename is a separate high-cost decision and is
not justified for this convergence.

Execution order matters:

1. Complete the `xa-mass-public-contract` owner first.
2. Rename embedded SDK modules/artifacts while they still live at the current
   root-level paths.
3. Move SDK product modules under `sdk/` after the contract and artifact names
   are already verified.

## Hard Rules

1. Do not make `xa-mass-java-sdk` depend on `xa-mass-base`, engine, server,
   transport implementations, or embedded SDK modules.
2. Do not make `xa-mass-public-contract` depend on platform runtime modules,
   Spring, embedded SDK modules, or external SDK modules.
3. Only DTOs and constants that are directly exposed by server Controller
   request/response contracts may move into `xa-mass-public-contract`.
4. Do not use `xa-mass-public-contract` to broaden the control plane, create new
   routes, or publish internal control/review/diagnostic models.
5. Do not rename Java packages as part of the module layout move.
6. Do not keep old module directories or artifact aliases as compatibility
   tracks after the rename lands.
7. Do not move embedded runtime assembly types into the external Java SDK.
8. Do not move worker-pack capability code into any SDK module.
9. Do not document the target layout as current until the corresponding slice
   has been implemented and verified.

## Non-Goals

- No external publication process or Maven Central readiness.
- No Node SDK track.
- No server behavior changes, route changes, or new task invocation shortcuts.
- No broad DTO migration from every embedded SDK API model in one slice.
- No migration of models that are not directly exposed by a server Controller
  route, even if they are currently convenient to share.
- No archive-doc rewrite except index/path updates required by active docs.
- No attempt to make `xa-mass-public-contract` the owner of embedded runtime
  controls, trace internals, engine diagnostics, or storage/review internals.

## Do Not Start With

Do not start by moving directories or renaming artifacts before the public
contract inventory is complete. The risky shortcut would have been to rename
the embedded SDK first and then discover that server/external SDK DTO ownership
was still mixed.

## Public Contract Boundary

`xa-mass-public-contract` is a server Controller wire-contract module. It should
start narrow and grow only when a type is proven to be directly exposed as a
request or response body by a concrete server Controller route and shared by an
external SDK caller. It is not a general control-plane model module.

The Controller-exposed wire contract includes the minimal nested type closure
needed for the migrated request/response DTO to compile without depending on
server, `xa-mass-base`, embedded SDK, engine, or transport modules. Nested types
must be listed in the inventory with the Controller method that exposes the
outer DTO. They do not become eligible merely because they are convenient shared
models elsewhere.

`ApiResponse<T>` envelope ownership is explicit: keep the envelope decoding
SDK-local/manual until the SCL-0 inventory proves the same envelope should be a
public shared contract. SCL-1 must not move `ApiResponse<T>` by default.

The required test for moving a type is:

1. identify the owning `*Controller` method;
2. identify whether the type appears in the route request body, response body,
   or public route constant used by that method;
3. list any nested wire DTOs needed by that request/response body;
4. prove the external Java SDK needs the same wire contract;
5. preserve the route JSON shape with focused controller and SDK tests.

Initial candidate set:

- public task shell create/update/list/read request and response DTOs;
- task item append and sync append request/response DTOs;
- task command request/result DTOs exposed through public routes;
- task result window/archive/item DTOs exposed through public routes;
- public worker, worker group, adapter node, and binding registration/snapshot
  DTOs used by external registration routes;
- public constants for `Task.sharedConfig` keys such as worker group routing and
  route attributes.

Excluded first-cut set:

- embedded SDK starter/config/builder APIs;
- engine/runtime diagnostics that are not public HTTP contracts;
- review materialization internals, queue events, and read-model writer events
  unless the exact DTO is the request or response body of a concrete Controller
  route;
- authorization/authentication internals that are not part of a Controller
  request/response DTO;
- server bootstrap/mock data loader DTOs that are not production Controller
  wire contracts;
- transport implementation messages;
- worker-pack capability configuration and provider internals.

## Roadmap Slices

### SCL-0: Contract And Layout Inventory

Inventory all SDK-related module paths, artifact IDs, package imports, and DTO
owners before editing Maven module paths.

Scope:

- classify embedded SDK API DTOs into Controller-exposed public contract,
  embedded SDK contract, server/review internal, and unclear;
- classify external Java SDK DTOs into client-only API
  convenience and shared public HTTP contract candidates;
- identify all active embedded SDK, external Java SDK, and
  `com.xa.mass.sdk.model` references;
- record active docs and verification commands that must be updated when module
  paths move;
- classify archived docs as historical unless they are index files.

Acceptance:

- a sibling inventory document records DTO/module classifications and target
  owner for each main group;
- every `xa-mass-public-contract` candidate references the exact Controller
  method that exposes it;
- the inventory explicitly names the first 5-10 DTOs/constants targeted for
  SCL-1, each with its owning Controller method, route role, and nested
  wire-type closure;
- no code behavior changes are made in this slice;
- unresolved DTO ownership is explicitly marked as a stop point before SCL-1.

Verification:

```powershell
rg -n "xa-mass-embedded-sdk-api|xa-mass-embedded-sdk|sdk/xa-mass-java-sdk|com\.xa\.mass\.sdk\.model|com\.xa\.mass\.client\.task" pom.xml README.md doc xa-mass-server integrations sdk -g "*.java" -g "*.md" -g "pom.xml"
```

### SCL-1: Create `sdk/xa-mass-public-contract`

Add the new pure contract module without migrating all callers at once.

Scope:

- add `sdk/xa-mass-public-contract` to the Maven reactor;
- set artifact ID `xa-mass-public-contract`;
- introduce a small first public-contract package under `com.xa.mass.contract`;
- start with stable shared constants and the narrowest Controller-exposed DTO
  group needed to eliminate current external SDK/server double-write risk;
- add a module guard that bans dependencies on Spring, server, engine, transport,
  `xa-mass-base`, embedded SDK modules, and external SDK modules.

Acceptance:

- `xa-mass-public-contract` compiles as an independent module;
- `mvn dependency:tree` or an architecture test proves the module has no runtime
  dependency on forbidden platform modules;
- the first shared constants or DTO group is tied to the SCL-0 inventory entry
  for a concrete Controller request/response contract and is ready for
  server/external SDK adoption without changing route JSON.

Verification:

```powershell
mvn -pl sdk/xa-mass-public-contract -am test
mvn -pl sdk/xa-mass-public-contract dependency:tree
```

### SCL-2: Adopt Public Contract In External SDK And Server

Move only verified shared public HTTP contract DTOs/constants onto
`xa-mass-public-contract`.

Scope:

- make `sdk/xa-mass-java-sdk` depend on `xa-mass-public-contract`;
- make only the owning Controller route assembly depend on
  `xa-mass-public-contract` for the same public DTOs;
- keep external SDK client convenience builders in `com.xa.mass.client.*` when
  they are ergonomic API wrappers rather than wire contract records;
- preserve JSON route shape with focused server and SDK tests;
- add or update a guard so future duplicated public wire DTOs need an explicit
  owner decision.

Acceptance:

- no server route JSON changes are introduced unless a test fixture is updated
  with an explicit contract reason;
- `xa-mass-java-sdk` no longer owns the migrated shared contract type or
  constant;
- server no longer imports the migrated type or constant from embedded SDK API;
- every type migrated to `xa-mass-public-contract` has a matching SCL-0
  inventory entry with owning Controller method, route role, and nested
  wire-type closure; no type without that entry is migrated;
- external SDK public builders remain source-friendly for callers.

Verification:

```powershell
mvn -pl sdk/xa-mass-java-sdk,xa-mass-server -am "-Dtest=TaskClientTest,*ExternalSdk*,*TaskApi*" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### SCL-3: Rename Embedded SDK Modules And Artifacts

Rename the embedded SDK artifacts before moving directories under `sdk/`.
This keeps the semantic rename independently reviewable from the later physical
layout move.

Scope:

- rename the previous root module directory `xa-mass-sdk-api` to
  `xa-mass-embedded-sdk-api`;
- rename the previous root module directory `xa-mass-sdk` to
  `xa-mass-embedded-sdk`;
- change artifact IDs and module names to `xa-mass-embedded-sdk-api` and
  `xa-mass-embedded-sdk`;
- update Maven dependencies and active docs that refer to the embedded SDK
  artifact IDs;
- keep the external Java SDK in place for this slice;
- keep Java packages unchanged.

Acceptance:

- root reactor no longer lists the previous embedded SDK artifact IDs;
- no dependency uses the previous embedded SDK artifact IDs;
- root reactor still compiles with the renamed embedded SDK modules;
- package names under `com.xa.mass.sdk.*` are unchanged.

Verification:

```powershell
mvn -pl sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk -am test
rg -n "xa-mass-sdk-api|xa-mass-sdk" . -g "pom.xml"
rg -n "xa-mass-sdk-api|xa-mass-sdk" scripts .github
rg -n "xa-mass-sdk-api|xa-mass-sdk" README.md doc integrations sdk xa-mass-server -g "!doc/archive/**"
# Remaining active-doc hits must be this roadmap, the inventory, or explicit transition notes.
```

### SCL-4: Move SDK Modules Under `sdk/`

Converge physical module layout after the public contract owner and embedded
artifact names are already verified.

Scope:

- move the external Java SDK to `sdk/xa-mass-java-sdk` while keeping
  artifact ID `xa-mass-java-sdk`;
- move root module `xa-mass-embedded-sdk-api` to
  `sdk/xa-mass-embedded-sdk-api`;
- move root module `xa-mass-embedded-sdk` to `sdk/xa-mass-embedded-sdk`;
- update root `pom.xml`, module dependencies, README links, active roadmaps,
  runbooks, scripts, and CI references;
- keep Java packages unchanged.

Acceptance:

- root reactor lists all SDK product modules under `sdk/`;
- root reactor no longer lists root-level embedded SDK modules or
  the previous external Java SDK path;
- no old module directories remain as live modules;
- `integrations/` contains integration adopters and worker packs, not SDK
  product modules;
- active docs use the new paths.

Verification:

```powershell
mvn -pl sdk/xa-mass-public-contract,sdk/xa-mass-java-sdk,sdk/xa-mass-embedded-sdk-api,sdk/xa-mass-embedded-sdk -am test
rg -n "xa-mass-sdk-api|xa-mass-sdk|integrations/xa-mass-java-sdk" . -g "pom.xml"
rg -n "xa-mass-sdk-api|xa-mass-sdk|integrations/xa-mass-java-sdk" scripts .github
rg -n "xa-mass-sdk-api|xa-mass-sdk|integrations/xa-mass-java-sdk" README.md doc integrations sdk xa-mass-server -g "!doc/archive/**"
# Remaining active-doc hits must be this roadmap, the inventory, or explicit transition notes.
```

### SCL-5: Retarget Worker-Pack And Scenario-Launcher

Keep integrations as real adopters of SDK surfaces after the layout move.

Scope:

- make scenario-launcher depend on `sdk/xa-mass-java-sdk` via artifact
  `xa-mass-java-sdk`;
- make worker-pack depend on `xa-mass-java-sdk` for external worker behavior;
- keep or remove embedded SDK dependency from worker-pack based on whether a
  real embedded dev-shell capability remains after inventory;
- do not preserve worker-pack raw transport demos as SDK proof.

Acceptance:

- scenario-launcher remains the primary external SDK adopter;
- worker-pack capability tests prove real external SDK registration/execution;
- worker-pack has no embedded SDK dependency unless the inventory records a
  non-demo runtime reason.

Verification:

```powershell
mvn -pl integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack,xa-mass-server -am "-Dtest=JavaExternalSdkTaskScopedInvocationIntegrationTest,JavaExternalSdkPollingSessionIntegrationTest,WorkerPackGeoLookupExternalSdkIntegrationTest,PhoneDeviceWorkerPackExternalSdkIntegrationTest,JavaScenarioLauncherBlackBoxIntegrationTest,*WorkerPack*" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### SCL-6: Residue Scan And Documentation Convergence

Remove stale naming and old layout references from active docs and guards.

Scope:

- update root README and `doc/README.md` owner docs to the new `sdk/` paths;
- update `doc/VERIFIED_RUNBOOK.md`, proof registry, testing index, and active
  roadmaps that contain executable commands;
- preserve archive docs as historical unless an archive index link breaks;
- add a short transition note explaining that `com.xa.mass.sdk.*` packages were
  intentionally not renamed.

Acceptance:

- active docs no longer direct new work to old SDK module paths or artifact IDs;
- any remaining old-name hit outside archive is an explicit historical note or
  superseded-roadmap pointer;
- users can find all SDK modules under `sdk/` from the documentation index.

Verification:

```powershell
rg -n "xa-mass-sdk-api|xa-mass-sdk|integrations/xa-mass-java-sdk" . -g "pom.xml"
rg -n "xa-mass-sdk-api|xa-mass-sdk|integrations/xa-mass-java-sdk" scripts .github
rg -n "xa-mass-sdk-api|xa-mass-sdk|integrations/xa-mass-java-sdk" README.md doc integrations sdk xa-mass-server -g "!doc/archive/**"
# Remaining active-doc hits must be this roadmap, the inventory, or explicit transition notes.
git diff --check
```

## Cross-Roadmap Notes

- `JAVA_EXTERNAL_SDK_PUBLIC_READINESS_ROADMAP.md` says not to replace or rename
  the previous embedded SDK artifact. This roadmap does not merge external SDK
  behavior into embedded SDK. It narrows that older wording:
  artifact/module-only rename is allowed when the embedded SDK is renamed to
  clarify ownership.
- `JAVA_EXTERNAL_SDK_TASK_SCOPED_INVOCATION_ROADMAP.md` remains the proof for
  task-scoped external SDK invocation. Public-contract extraction must preserve
  that behavior and its tests.
- `INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_ROADMAP.md` and the archived EWH
  hardening roadmap define worker-pack as a real external SDK adopter, not an
  SDK owner.
- `TASK_WORKER_RUNTIME_HISTORY_BOUNDARY_ROADMAP.md` and engine convergence docs
  must use `sdk/xa-mass-embedded-sdk` in active verification commands after
  SCL-6.

## Risks

| Risk | Mitigation |
| --- | --- |
| Public-contract scope grows into another base module | Start with a narrow DTO/constant group and ban runtime dependencies. |
| Public-contract becomes a wider control-plane API | Require every migrated type to reference a concrete Controller request/response method. |
| Nested DTOs pull embedded SDK or base dependencies into public-contract | Require nested wire-type closure in the inventory and keep it dependency-clean. |
| `ApiResponse<T>` moves by accident and widens every API route | Keep the envelope SDK-local/manual unless SCL-0 explicitly chooses it as a shared contract. |
| Artifact rename breaks active verification commands | Inventory commands first, update active docs in the same slice as path moves. |
| Package/artifact mismatch confuses readers | Document that package rename is intentionally out of scope and explain the reason. |
| External SDK builders become too thin or awkward | Keep ergonomic client builders in `com.xa.mass.client.*`; only move wire contract records/constants. |
| Worker-pack keeps embedded SDK only for old demo bootstrap | SCL-5 requires a real non-demo reason or removes the dependency. |
| Archive churn obscures history | Do not rewrite archived roadmaps except broken indexes. |
| Server imports both public-contract and embedded DTOs for the same route | Add guard or inventory check for duplicated public wire DTO ownership. |

## Stop Points

Stop and re-review before implementation if:

- SCL-0 finds a public DTO whose route JSON cannot be preserved without a server
  route contract change;
- `xa-mass-public-contract` appears to need `xa-mass-base`, Spring, engine, or
  transport to compile;
- worker-pack still needs embedded SDK for a behavior that should actually be
  external SDK-owned;
- any external publication or downstream consumer already relies on the current
  artifact IDs outside the repo;
- a package rename becomes necessary to complete the module move.
