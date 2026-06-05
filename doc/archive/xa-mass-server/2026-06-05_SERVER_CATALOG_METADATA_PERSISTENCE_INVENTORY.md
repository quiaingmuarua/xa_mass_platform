# Server Catalog Metadata Persistence Inventory

Archived: 2026-06-05.

Status: implemented and archived historical inventory for
`2026-06-05_SERVER_CATALOG_METADATA_PERSISTENCE_ROADMAP.md`.

Current truth owners:

- `platform_infra/mass-storage-api/README.md`
- `platform_infra/mass-storage-memory/README.md`
- `platform_infra/mass-storage-jdbc/README.md`
- `xa-mass-server/README.md`
- `doc/INFRA_TRUTH_LAYERS.md`

Do not use this archived inventory as proof of current behavior. Verify against
current code, tests, and owner README files.

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
| `CatalogMetadataStore` | `platform_infra/mass-storage-api` | storage contract | durable catalog metadata port | Owns restart-readable project/event catalog metadata. |
| `InMemoryCatalogMetadataStore` | `platform_infra/mass-storage-memory` | in-memory maps | process-local implementation | Embedded/dev fallback and contract proof implementation, not durable server proof. |
| `JdbcCatalogMetadataStore` | `platform_infra/mass-storage-jdbc` | JDBC tables from control-plane Flyway migration | durable implementation | SQLite/H2 contract proof and server JDBC-mode restart source. |
| `CatalogMetadataProjection` | `xa-mass-server` | projection helper over store + embedded SDK | server assembly/projection | Restores durable store rows into `MassSdkApplication` and validates seed/import writes. |

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
- `CatalogMetadataStore` now has memory and JDBC implementations; JDBC schema
  is owned by the platform-infra control-plane migration path, not by
  server-owned API/IAM migrations.
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
6. Seed/import idempotency semantics for existing rows: replace-by-code.
   Re-importing a project or event with the same code replaces that catalog
   record and its project-event bindings; it does not create duplicates.
   This decision is CAT-1/CAT-3 contract input and must not remain implicit in
   implementation code.
7. Seed/import reference-integrity behavior for unknown event codes in project
   seeds: reject before writing project metadata. A failed import must not
   leave partially persisted project catalog rows.
   This decision is CAT-1/CAT-3 contract input and must not remain implicit in
   implementation code.
8. Catalog migration SQL belongs under
   `platform_infra/mass-storage-jdbc/src/main/resources/db/migration/control-plane/`.
   `xa-mass-server` consumes it through classpath assembly and must not add a
   parallel catalog migration under `db/migration/server-control-plane`.
9. SQLite is the restart/durable product proof. In-memory H2 remains a CI
   schema/contract proof for the same migration shape, not a separate server
   profile file or product durability target.

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
| `CatalogMetadataStoreContractTest` | landed | Required for memory/JDBC parity if a store contract is introduced. |
| `CatalogMetadataSQLiteRestartIntegrationTest` | landed | Proves project/event catalog metadata survives SQLite restart without repeated import. |
| New default-fallback runtime proof | landed through restart proof | CAT-2 integration proof: server dev/prod durable catalog mode does not satisfy route reads through `DefaultProjectEventCatalogFactory` when durable rows exist. |
| New default-fallback source guard | landed in `ServerMainSourceArchitectureGuardTest` | CAT-4 static/source guard: durable server assembly cannot regress to route-visible `DefaultProjectEventCatalogFactory` fallback truth. |
| `CatalogRestoreWorkerCapabilityViewIntegrationTest` | landed | Proves restored catalog enriches worker capability/read views without catalog tables storing worker topology/runtime state. |
| New catalog restore ordering proof | partially covered by restart/route proof | Restore is verified before route reads in server startup; a stricter failure-injection proof is optional follow-up if startup ordering becomes indirect. |

## Resolved Mainline Gaps

1. Durable catalog store contract and schema now exist under `platform_infra`.
2. Server dev/prod assembly now restores catalog rows from `CatalogMetadataStore`
   into the embedded SDK projection.
3. Route-visible default catalog fallback is excluded from dev/prod profile
   assembly.
4. Task route constructors require an injected `ControlPlaneCatalog` and no
   longer create constructor-level default catalog fallbacks.
5. Seed/import validates project-event references before durable project
   metadata write.
6. SQLite restart proof covers catalog restore without seed/import replay.
7. Source guard prevents default/demo catalog fallback from masking durable
   dev/prod catalog truth.
