# Server Catalog Metadata Persistence Roadmap

Status: proposed roadmap.

Related inventory:
`roadmap/SERVER_CATALOG_METADATA_PERSISTENCE_INVENTORY.md`.

## Current Code Observations

- `xa-mass-server` builds the embedded `MassSdkApplication` with
  `new ProjectEventCatalogRegistry()` as the catalog bootstrap registry.
- `ProjectEventCatalogRegistry` is explicitly an in-memory bootstrap registry
  and stores projects/events in `LinkedHashMap` fields.
- `ControlPlaneSeedImporter` can load project/event catalog data into
  `MassSdkApplication`, but without a durable catalog store the data must be
  imported again for a new process/clean in-memory catalog.
- Existing JDBC-backed control-plane storage covers task shell, rules,
  principal/auth projection, API-key lifecycle, operator IAM, API usage ledger,
  and opt-in task review materialization. It does not yet make project/event
  catalog metadata restart-readable by itself.
- `RuleStorage` already has JDBC backing. The project/event catalog gap should
  not absorb rule storage ownership.
- `ProjectRegistry` and SDK event definition registries are runtime/catalog
  projections. They should be rehydrated from durable catalog metadata, not
  treated as the database owner.

## Owner Review

Project/event catalog metadata is stable control-plane truth: it defines which
projects exist and which event capabilities are legal for those projects. It is
not runtime queue state, worker presence, dispatch history, or trace/audit.

Durable catalog storage is a platform control-plane storage concern:

- the narrow catalog store contract belongs under
  `platform_infra/mass-storage-api`;
- memory/JDBC implementations belong beside the existing storage adapters under
  `platform_infra/mass-storage-memory` and
  `platform_infra/mass-storage-jdbc`;
- `xa-mass-server` owns wiring, explicit seed/import orchestration, startup
  restore, route exposure, and Flyway assembly for the selected control-plane
  database.

The current `ProjectEventCatalogRegistry` lives in the SDK API because it is a
bootstrap registry and read facade, not because SDK API should own durable
server DB schema. Server-owned API-key/IAM/usage schema remains server-owned;
catalog metadata is not another server-private product table family.

## Boundary Decision

Single durable catalog write owner for this roadmap:
`platform_infra/mass-storage-*`.

The runtime shape should be:

1. Durable project/event catalog store owns restart-readable metadata.
2. Startup restores catalog metadata from the durable store.
3. Restored metadata is projected into `MassSdkApplication`, `ProjectRegistry`,
   and runtime event-definition views before external registration/task paths
   depend on it.
4. Explicit seed/import populates or updates the durable store; it is not a
   hidden dev/prod bootstrap requirement.

## Target Shape

- New environment initialization can load project/event catalog metadata once
  into SQLite-backed control-plane storage.
- Subsequent server restarts restore project/event catalog metadata without
  rerunning seed/import.
- `ControlPlaneCatalog` continues to be the read surface for controllers and
  SDK callers.
- `ProjectEventCatalogRegistry` remains a memory/bootstrap implementation for
  tests, embedded local usage, and SDK-only scenarios.
- `RuleStorage` remains the rule owner; catalog persistence coordinates with
  rule seed/import ordering but does not move matching rules into catalog
  tables.
- Existing server-owned API-key/IAM/usage tables remain untouched.

## Hard Rules

- Do not persist runtime queues, leases, worker online/presence, dispatch
  streams, result convergence, or trace/audit rows as catalog metadata.
- Do not make `ProjectRegistry` the persistence owner. It is a runtime
  projection and must be restored from durable catalog data.
- Do not require seed/import to run on every dev/prod startup.
- Do not add a hidden sample/bootstrap path. Initialization must stay explicit.
- Do not allow server dev/prod startup or route constructors to silently fall
  back to `DefaultProjectEventCatalogFactory` when durable catalog mode is
  active. Default catalog fallback is allowed only for explicitly local
  embedded/test/bootstrap paths.
- `eventCode` remains the globally unique capability/handler identity.
  Project-event binding is separate scope metadata. Do not persist event
  definitions as per-project duplicates, and do not mix project-event catalog
  metadata with WorkerGroup topology, worker selection, or runtime presence.
- Do not duplicate event/project DTO shapes unnecessarily. Reuse existing SDK
  public-contract definitions where they are the actual controller/API shape;
  introduce persistence records only where schema shape requires it.
- Do not move existing API-key/IAM/usage schema or behavior as part of this
  roadmap.
- No historical DB compatibility is required during this pre-release stage.
  Clean DB creation and current-schema restart proof are enough.

## Non-Goals

- No worker topology persistence in this roadmap. WorkerGroup, AdapterNode,
  node-group bindings, worker registrations, worker presence, and worker state
  reports remain separate worker/runtime concerns.
- No task lifecycle, scheduling, matching, or result convergence change.
- No trace DB writer.
- No PostgreSQL production-hardening requirement. Prefer portable SQL shapes so
  SQLite proof does not block later PostgreSQL work.
- No external API route expansion unless current route shape cannot expose the
  restored catalog state.

## Do Not Start With

Do not start by replacing `ProjectEventCatalogRegistry` with a JDBC class. First
decide the durable owner and restore/project ordering. The in-memory registry is
a useful SDK bootstrap implementation; the gap is that server durable catalog
truth has no store and no startup restore path.

## CAT-0 - Inventory And Owner Decision

Goal:

Freeze the current facts and close fallback/restore decisions before writing
schema or adapters.

Scope:

- Keep `SERVER_CATALOG_METADATA_PERSISTENCE_INVENTORY.md` current.
- Inventory all project/event catalog writers, readers, and startup projection
  points.
- Record `platform_infra/mass-storage-*` as the durable catalog storage owner
  and `xa-mass-server` as the wiring/seed/import/route owner.
- Inventory all current default catalog fallback paths, including Spring
  fallback beans and controller constructor fallbacks.
- Record which existing DTOs/definitions are reused for persisted shape and
  where persistence-only records are allowed.
- Record seed/import conflict semantics: insert-only, replace-by-code, or
  explicit replace mode.
- Record seed/import reference integrity semantics for project-event bindings.

Acceptance:

- The roadmap records `platform_infra/mass-storage-*` as the single durable
  catalog store owner.
- The inventory lists all current catalog write paths and read paths.
- The inventory lists all current default catalog fallback paths and their
  target disposition.
- The first implementation slice has a predetermined module owner and target
  package.
- The first implementation slice can prove restored catalog metadata comes from
  durable storage, not from seed/import replay or default catalog fallback.
- No code behavior changes are made in this slice unless they are guard/docs
  only.

Verification:

```bash
rg -n "ProjectEventCatalogRegistry|ControlPlaneCatalog|registerProject\\(|registerEventDefinition\\(|ProjectRegistry\\.register|ControlPlaneSeedImporter|RuleStorage" xa-mass-server sdk platform_infra -g "*.java"
```

## CAT-1 - Catalog Store Contract And Schema

Goal:

Introduce the minimal durable store contract and schema for project/event
catalog metadata.

Scope:

- Add memory and JDBC implementations for the platform-infra catalog store
  owner.
- Persist projects by project code.
- Persist event definitions by global event code.
- Persist project-event bindings separately from event definitions and from
  worker topology.
- Persist all fields required by runtime validation/display.
- Keep rule definitions in `RuleStorage`; only reference or coordinate with
  rules if seed/import ordering requires it.
- Add schema notes in the owner module's DB/schema directory.
- Add Flyway SQL in the owner module's migration directory.

Acceptance:

- Memory and JDBC implementations pass the same store contract test.
- SQLite and H2 can create the schema from a clean DB.
- The schema can represent disabled projects/events, project-event binding, and
  event capability metadata required by current controllers.
- No worker topology, runtime state, trace, or task item history is added to
  catalog tables.
- No per-project duplicate event definition rows are required to express
  project membership.

Verification:

```bash
./mvnw -pl xa-mass-server -am -Dtest=*Catalog*StoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The contract and storage tests should run in the owning `platform_infra`
modules; the server test command remains the route/restore proof.

## CAT-2 - Startup Restore And Runtime Projection

Goal:

Restore durable project/event catalog metadata into the current SDK/runtime
projection on startup.

Scope:

- Load catalog metadata before task routes, worker registration proof, or
  seed/import-dependent flows require project/event validation.
- Project projects into `ProjectRegistry`.
- Project events into SDK/runtime event-definition views.
- Keep `ControlPlaneCatalog` as the controller read surface.
- Preserve the SDK in-memory bootstrap path for embedded/test scenarios.

Acceptance:

- Server restart with SQLite restores previously imported project/event catalog
  metadata without rerunning seed/import.
- `CatalogController`, `ProjectApiController`, `TaskApiController`, and worker
  capability views read restored catalog state.
- Startup does not silently fall back to default demo catalog when durable
  catalog rows exist.
- Server dev/prod durable catalog mode does not use
  `DefaultProjectEventCatalogFactory` as route-visible catalog truth.
- Clean startup with no seed/import remains clean; no sample projects/events are
  hidden-bootstrap-created.
- Restored catalog metadata drives worker capability/read views without
  persisting WorkerGroup, adapter-node, worker, heartbeat, or topology data in
  catalog tables.

Verification:

```bash
./mvnw -pl xa-mass-server -am -Dtest=CleanServerStartupIntegrationTest,*Catalog*Restart*Test,*SeedImport*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

## CAT-3 - Seed/Import Idempotency

Goal:

Make explicit new-environment seed/import populate durable catalog metadata in a
repeatable way.

Scope:

- Route project/event seed import through the durable catalog store or through a
  store-backed SDK write path.
- Preserve submitter/API-key and rule import behavior under their existing
  owners.
- Implement the conflict behavior chosen in CAT-0.
- Validate project-event references before writing durable project metadata.
- Ensure imports are explicit and default-off.

Acceptance:

- Running seed/import once creates durable project/event catalog metadata.
- Restart reads the catalog without rerunning seed/import.
- Re-running seed/import follows the recorded conflict semantics.
- A project seed that references an unknown event code is rejected or reported
  as an explicit import failure; it must not leave partially persisted project
  catalog metadata.
- Seed/import does not create task/worker/runtime truth by default.

Verification:

```bash
./mvnw -pl xa-mass-server -am -Dtest=ControlPlaneSeedImportIntegrationTest,*Catalog*Restart*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

## CAT-4 - Guards And Owner Docs

Goal:

Prevent catalog metadata from drifting back into hidden memory-only bootstrap or
the wrong schema owner.

Scope:

- Add source guards for schema placement.
- Add guard or focused test proving server JDBC mode does not assemble only
  `new ProjectEventCatalogRegistry()` as durable catalog truth.
- Add guard or focused test proving server dev/prod durable catalog mode does
  not expose `DefaultProjectEventCatalogFactory` fallback catalog as current
  route truth.
- Update owner docs:
  - `xa-mass-server/README.md`
  - `platform_infra/mass-storage-jdbc/README.md`
  - owner schema README under the selected storage module
  - `doc/INFRA_TRUTH_LAYERS.md` if global storage truth changes
  - `sdk/README.md` only if SDK public boundary changes
- Archive this roadmap only after residue scan and current facts are moved to
  owner docs.

Acceptance:

- Active docs state project/event catalog restart behavior precisely.
- Guards prevent catalog DB schema from being placed in the wrong module.
- Guards prevent default/demo catalog fallback from satisfying durable
  dev/prod catalog proof.
- No active README treats archived roadmap prose as current truth.
- Residue scan finds no stale "must rerun import every startup" language for
  persisted catalog metadata.

Verification:

```bash
rg -n "ProjectEventCatalogRegistry\\(\\)|must rerun|every startup|catalog metadata.*memory|project/event.*memory" xa-mass-server sdk roadmap doc -g "*.java" -g "*.md"
./mvnw -pl xa-mass-server -am -Dtest=ServerMainSourceArchitectureGuardTest,*Catalog*Test,*SeedImport*Test -Dsurefire.failIfNoSpecifiedTests=false test
git diff --check
```

## Completion Criteria

This roadmap is complete when:

- project/event catalog metadata has a platform-infra durable owner and store
  contract;
- default/demo catalog fallback cannot masquerade as durable dev/prod catalog
  truth;
- SQLite restart proof shows catalog metadata survives process restart without
  repeated seed/import;
- runtime/SDK projection is restored from durable catalog metadata before
  routes depend on it;
- seed/import is explicit, idempotent, and no longer required every startup for
  already persisted catalog metadata;
- rules remain under `RuleStorage`;
- worker topology/runtime/trace truth remains out of catalog tables;
- owner docs and guards encode the boundary.
