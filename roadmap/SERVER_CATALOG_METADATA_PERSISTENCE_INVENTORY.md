# Server Catalog Metadata Persistence Inventory

Status: proposed inventory for
`SERVER_CATALOG_METADATA_PERSISTENCE_ROADMAP.md`.

## Scope

This inventory covers project and event catalog metadata used by
`xa-mass-server` through the embedded SDK. It classifies current in-memory
catalog registries, seed/import paths, runtime projections, and existing rule
storage.

It does not cover worker topology, WorkerGroup declarations, adapter nodes,
runtime queues, worker presence, task work/result runtime, trace/audit streams,
or high-volume task/item history.

## Current Symbols

| Symbol | Current Owner | Current Backing | Classification | Target |
| --- | --- | --- | --- | --- |
| `ProjectEventCatalogRegistry` | `sdk/xa-mass-embedded-sdk-api` | `LinkedHashMap` projects/events | in-memory bootstrap registry | Keep as SDK-local memory registry for embedded/test bootstrap; do not make it the server durable store. |
| `ControlPlaneCatalog` | `sdk/xa-mass-embedded-sdk-api` | interface over project/event reads | read facade | Keep as public/embedded read view; back it from current runtime/catalog projection after restore. |
| `DefinitionBackedControlPlaneCatalog` | `sdk/xa-mass-embedded-sdk` | suppliers over SDK project/event views | derived read view | Keep as runtime projection view; do not treat as durable storage. |
| `MassSdkApplication.registerProject(...)` | `sdk/xa-mass-embedded-sdk` | writes bootstrap registry and core `ProjectRegistry` | write path plus runtime projection | Retarget server durable writes through a catalog store, then project into SDK/runtime registries. |
| `MassSdkApplication.registerEventDefinition(...)` | `sdk/xa-mass-embedded-sdk` | writes event definition registry/runtime descriptor | write path plus runtime projection | Retarget server durable writes through a catalog store, then project into runtime event definitions. |
| `ProjectRegistry` | engine/core static registry | process-local static registry | runtime/project validation projection | Rehydrate from durable project catalog on startup; do not make it the persistence owner. |
| `EventDefinitionRegistry` | `sdk/xa-mass-embedded-sdk-api` | in-memory event definitions | runtime/catalog projection | Rehydrate from durable event catalog on startup; do not make it the persistence owner. |
| `RuleStorage` | `platform_infra/mass-storage-api` | JDBC-backed in JDBC modes | durable matching/default rule storage | Keep existing owner. Catalog persistence must coordinate seed/import ordering with rules but not absorb rule storage. |
| `ControlPlaneSeedImporter` | `xa-mass-server` | explicit seed/import into `MassSdkApplication` | new-environment initialization | Keep explicit and idempotent. It should become one way to populate durable catalog store, not a required repeated startup step. |
| `ControlPlaneSeedImportConfiguration` | `xa-mass-server` | optional startup import | explicit operator/new-env action | Keep default-off; no hidden dev/prod seed path. |
| `CatalogController` / `ProjectApiController` | `xa-mass-server` | reads `ControlPlaneCatalog` | HTTP read surface | Continue reading projected catalog truth; route shape is out of scope unless existing routes cannot expose persisted state. |

## Current Code Observations

- `XaMassServerApplication` builds the embedded SDK with
  `new ProjectEventCatalogRegistry()` as the project catalog bootstrap registry.
- `ProjectEventCatalogRegistry` explicitly documents itself as an in-memory
  bootstrap registry and stores `projects` and `events` in `LinkedHashMap`
  fields.
- Server exposes a `ControlPlaneCatalog` bean through
  `serverControlPlaneCatalog(MassSdkApplication app)`, so controllers read the
  live SDK catalog view rather than a server-owned store directly.
- `MassSdkApplication.registerProject(...)` writes the bootstrap project
  registry and then registers the project into the core static `ProjectRegistry`
  when the SDK/application is running.
- `MassSdkApplication.registerEventDefinition(...)` writes SDK event definition
  state and projects event descriptors into runtime-facing event metadata.
- Existing `RuleStorage` already has JDBC backing through
  `JdbcStorageRuntime.ruleStorage()` and is not the primary gap in this
  roadmap.
- `ControlPlaneSeedImporter` currently calls
  `registerEventDefinition(...)`, `registerProject(...)`,
  `registerSubmitter(...)`, and `replaceDefaultRules(...)`. This proves the
  import path but does not make project/event catalog restart-readable unless
  the import is repeated.
- Server-owned API-key lifecycle, operator IAM, and usage ledger persistence
  already landed under server-owned migrations. This roadmap must not reopen
  those store boundaries.

## Placement Decisions To Make

1. Decide whether durable project/event catalog tables belong to
   `xa-mass-server` as product/control-plane metadata or to
   `platform_infra/mass-storage-api` as generic catalog storage.
2. Decide whether the write contract should be server-owned
   `ProjectEventCatalogStore` or SDK-owned `CatalogStore` / `CatalogRegistry`
   extension.
3. Decide the durable event-definition shape. The schema must preserve fields
   currently used by routing, validation, project binding, task mode, response
   mode, convergence mode, target scope, and handler/capability display.
4. Decide startup recovery ordering:
   - load project catalog;
   - load event definitions;
   - project projects into `ProjectRegistry`;
   - project events into runtime event definition descriptors;
   - then allow route handlers, seed/import, and worker registration proof to
     run.
5. Decide seed/import idempotency semantics for existing rows:
   insert-only, replace-by-code, or explicit `--replace` style behavior.

## Non-Store / Out-of-Scope Memory

- `SubmitterViewerSessionStore` remains memory-only by current server store
  decision.
- `SyncTaskResultBridge.pendingByMessage` remains request-local wait state.
- `TaskSyncRequestSupervisor` counters remain in-process admission metrics.
- Worker online/presence, worker registry churn, leases, dispatch queues, and
  result convergence remain runtime/Redis or engine runtime concerns.
- Trace/audit DB writing remains trace-owned future work.

## Proof Surfaces

| Proof | Current Role | Target Use |
| --- | --- | --- |
| `ProjectEventCatalogRegistryTest` | verifies in-memory SDK registry behavior | Keep as SDK memory/bootstrap behavior proof. |
| `ControlPlaneSeedImportIntegrationTest` | proves explicit seed/import can populate server state | Extend or pair with restart proof after durable catalog store lands. |
| `CleanServerStartupIntegrationTest` | proves clean startup has no hidden sample/task/worker seed | Keep: durable catalog restore must not become hidden sample bootstrap. |
| `ServerControlPlaneStoreConfigurationTest` | proves server-owned store assembly for API-key/IAM/usage | Use as pattern if catalog store becomes server-owned. |
| `ServerMainSourceArchitectureGuardTest` | source guard for server boundaries | Add guard for catalog schema placement and no hidden in-memory catalog fallback if needed. |
| New catalog store contract test | missing | Required for memory/JDBC parity if a store contract is introduced. |
| New SQLite restart proof | missing | Required to prove project/event catalog metadata survives process/store restart without repeated import. |

