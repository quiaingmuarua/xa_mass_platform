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
| `DefaultProjectEventCatalogFactory` | `sdk/xa-mass-embedded-sdk-api` | hard-coded baseline project identities | local/default fallback catalog | Keep for explicit embedded/test/bootstrap use only. It must not satisfy server dev/prod durable catalog proof. |
| `CatalogConfiguration.catalog()` | `xa-mass-server` | `@ConditionalOnMissingBean(ControlPlaneCatalog.class)` default catalog bean | hidden route-visible fallback | Inventory residue. Server dev/prod durable catalog mode must not rely on this fallback as current catalog truth. |
| `TaskApiController` constructor fallback | `xa-mass-server` | `catalog == null ? DefaultProjectEventCatalogFactory...` | constructor-level fallback | Inventory residue. Keep only if explicitly test/local, or remove once server catalog bean is required. |
| `InternalDebugTaskInvocationController` constructor fallback | `xa-mass-server` | `catalog == null ? DefaultProjectEventCatalogFactory...` | constructor-level fallback | Inventory residue. Keep only if explicitly test/local, or remove once server catalog bean is required. |
| `RuleStorage` | `platform_infra/mass-storage-api` | JDBC-backed in JDBC modes | durable matching/default rule storage | Keep existing owner. Catalog persistence must coordinate seed/import ordering with rules but not absorb rule storage. |
| `ControlPlaneSeedImporter` | `xa-mass-server` | explicit seed/import into `MassSdkApplication` | new-environment initialization | Keep explicit and idempotent. It should become one way to populate durable catalog store, not a required repeated startup step. |
| `ControlPlaneSeedImportConfiguration` | `xa-mass-server` | optional startup import | explicit operator/new-env action | Keep default-off; no hidden dev/prod seed path. |
| `CatalogController` / `ProjectApiController` | `xa-mass-server` | reads `ControlPlaneCatalog` | HTTP read surface | Continue reading projected catalog truth; route shape is out of scope unless existing routes cannot expose persisted state. |
| `WorkerCapabilityViewSupport` | `xa-mass-server` | reads `ControlPlaneCatalog` to enrich event binding views | catalog-backed read projection | Add proof that restored durable catalog drives worker capability/read views without storing worker topology in catalog tables. |

## Current Code Observations

- `XaMassServerApplication` builds the embedded SDK with
  `new ProjectEventCatalogRegistry()` as the project catalog bootstrap registry.
- `ProjectEventCatalogRegistry` explicitly documents itself as an in-memory
  bootstrap registry and stores `projects` and `events` in `LinkedHashMap`
  fields.
- Server exposes a `ControlPlaneCatalog` bean through
  `serverControlPlaneCatalog(MassSdkApplication app)`, so controllers read the
  live SDK catalog view rather than a server-owned store directly.
- If no `ControlPlaneCatalog` bean exists, `CatalogConfiguration` currently
  creates a route-visible default catalog from
  `DefaultProjectEventCatalogFactory`.
- `TaskApiController` and `InternalDebugTaskInvocationController` also create
  a default catalog in their constructors if a null catalog is passed.
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
- Current seed import builds events and projects independently. The durable
  import path must validate project-event references before writing project
  metadata so a project cannot persist authorization for an unknown event code.
- Worker capability/read surfaces can use catalog metadata to derive project
  bindings for supported event codes, but that read enrichment is not worker
  topology persistence.
- Server-owned API-key lifecycle, operator IAM, and usage ledger persistence
  already landed under server-owned migrations. This roadmap must not reopen
  those store boundaries.

## Placement Decisions

1. Durable project/event catalog storage belongs to
   `platform_infra/mass-storage-*`.
2. The write/read store contract should be a narrow platform-infra contract,
   not a server-private route adapter and not an SDK bootstrap registry
   extension.
3. The durable event-definition shape must preserve fields
   currently used by routing, validation, project binding, task mode, response
   mode, convergence mode, target scope, and handler/capability display.
4. Event definitions are keyed by globally unique `eventCode`. Project-event
   binding is stored separately and must not create per-project duplicate event
   definitions.
5. Startup recovery ordering:
   - load project catalog;
   - load event definitions;
   - project projects into `ProjectRegistry`;
   - project events into runtime event definition descriptors;
   - then allow route handlers, seed/import, and worker registration proof to
     run.
6. Decide seed/import idempotency semantics for existing rows:
   insert-only, replace-by-code, or explicit `--replace` style behavior.
7. Decide seed/import reference-integrity behavior for unknown event codes in
   project seeds. Target: reject or report explicit failure before writing
   partial project metadata.

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
| New default-fallback absence proof | missing | Required to prove server dev/prod durable catalog mode does not satisfy route reads through `DefaultProjectEventCatalogFactory`. |
| Worker capability catalog restore proof | missing | Required to prove restored catalog enriches worker capability/read views without catalog tables storing worker topology/runtime state. |

## Immediate Gaps

1. Durable catalog store contract and schema do not exist.
2. Server dev/prod assembly still uses `new ProjectEventCatalogRegistry()` as
   the only catalog bootstrap registry.
3. Route-visible default catalog fallback can still appear through
   `CatalogConfiguration` when no `ControlPlaneCatalog` bean exists.
4. Task route constructors still create default catalog fallbacks on null
   catalog input.
5. Seed/import does not yet prove project-event reference integrity before
   project metadata is written.
6. No SQLite restart proof proves catalog metadata is restored without
   seed/import replay.
7. No guard currently prevents default/demo catalog fallback from masking a
   missing durable catalog restore in server dev/prod mode.
