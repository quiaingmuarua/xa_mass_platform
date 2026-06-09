# Scenario Environment External Initialization Inventory

Archived on 2026-06-09.

Current truth owner: `tools/xa-mass-admin-cli/README.md` for environment
initialization and `integrations/xa-mass-scenario-launcher/README.md` for task
and worker process launchers.

This superseded inventory is historical context only. Do not use its
`ScenarioEnvironmentInitializerMain` target as current direction.

Status: current code inventory for
`SCENARIO_ENVIRONMENT_EXTERNAL_INITIALIZATION_ROADMAP.md`.

## Summary

The current local scenario path has three separate pieces, but only two of them
are truly external at runtime:

- task producer and worker launchers already call public task/worker APIs
- API-key credentials can be prepared through operator login plus
  `/api/v1/api-keys`
- project/event catalog and default rules still require server startup
  seed/import or test-only embedded application calls

This inventory records that gap so the successor roadmap does not keep treating
startup seed as an external initialization API.

## Symbols

| Symbol | Current Owner | Current Role | Classification | Target |
| --- | --- | --- | --- | --- |
| `application-durable-local.yml` | `xa-mass-server` | Starts SQLite/Redis local server and seeds operator credentials | local profile assembly | Clean startup remains allowed; no scenario catalog/rules/task/worker should be default |
| `ControlPlaneSeedImportConfiguration` / `ControlPlaneSeedImporter` | `xa-mass-server` | Startup-time seed/import for catalog, rules, API keys, operator credentials | startup bootstrap residue for scenario data | Keep only explicit startup fixture support; scenario-ready env should use runtime operator APIs |
| `ControlPlaneSeedCatalog` | `xa-mass-server` | Seed DTO for events, projects, API-key raw secrets | startup fixture DTO | Do not make this the public operator API contract without review; no raw API-key secret seed in normal scenario path |
| `control-plane-seed/operator-credentials.json` | `xa-mass-server` | Minimal operator login seed | durable-local readiness input | Keep as the minimal clean-start credential until another operator provisioning path exists |
| `control-plane-seed/control-console-scenario.json` | `xa-mass-server` | Historical scenario catalog/API-key fixture | sample/bootstrap residue | Remove or move out of server main resources when external scenario initialization lands |
| `CatalogController` | `xa-mass-server` | Read-only `/api/v1/catalog/**` routes | current read API | Add operator-only write/import route owner beside read catalog routes or through a named control-plane controller |
| `ProjectApiController` | `xa-mass-server` | Read-only `/api/v1/projects/**` routes | current read API | Project writes/bindings need operator-only API before scenario initializer can prepare a clean server |
| `RuleApiController` | `xa-mass-server` | Read-only `/api/v1/admin/rules` and `/meta` | current read API | Add operator-only sync/upsert route protected by `rule:edit`; destructive replace must be explicit if retained |
| `CatalogMetadataStore` | `platform_infra/mass-storage-api` | Durable catalog store for events/projects | control-plane storage contract | Reuse for external catalog writes; do not move worker runtime truth into it |
| `CatalogMetadataProjection` | `xa-mass-server` | Converts durable catalog records and restores them into `MassSdkApplication` | server projection/wiring helper | External catalog writes must update both durable store and live application catalog in one owner path |
| `MassSdkApplication.replaceDefaultRules(...)` | `sdk/xa-mass-embedded-sdk` | Clears all current rules and adds the supplied collection | embedded runtime rule operation | Do not use as initializer default unless SEI-0 explicitly chooses destructive replace; prefer rule-id sync/upsert |
| `RuleStorage` / `JdbcRuleStorage` | `platform_infra` | Stores rules by id; JDBC implementation persists `xa_rule` | rule storage contract/adapter | External scenario rule sync should upsert by id and preserve non-manifest rules unless explicit replace mode is requested |
| `ApiKeyController` | `xa-mass-server` | `POST /api/v1/api-keys` returns one-time raw secret | real credential lifecycle API | Scenario environment initializer can keep using it after operator login and CSRF |
| `ScenarioCredentialBootstrapMain` | `integrations/xa-mass-scenario-launcher` | Verifies catalog exists and creates task/worker API-key cache files | integration-local tooling, currently misnamed for env init | Replace with `ScenarioEnvironmentInitializerMain` that initializes catalog/rules through operator APIs before credentials |
| `ScenarioWorkerLauncherMain` | `integrations/xa-mass-scenario-launcher` | Registers worker topology and starts SDK sessions | external worker process role | Keep separate; assumes environment initializer has run |
| `ScenarioTaskLauncherMain` | `integrations/xa-mass-scenario-launcher` | Creates task shell and appends items via Java SDK | external task producer role | Keep separate; assumes environment initializer has run |
| `examples/scenario.catalog.seed.json` | `integrations/xa-mass-scenario-launcher` | Scenario catalog JSON without API-key raw secrets; current filename still says seed | integration-local manifest input | Convert to an initializer manifest consumed through operator APIs; rename away from `seed` wording when callers move |
| `integrations/samples/dev/scenario/rules.json` | `integrations/samples` | Scenario rules JSON | legacy scenario fixture | Move or copy into scenario-launcher examples as initializer manifest input before removing startup-seed dependency |
| memory-profile integration tests | `xa-mass-server` / `integrations/xa-mass-scenario-launcher` test harness | Need scenario data on every in-memory server boot; current `memory-local` auth mode is `dev-header` | test harness setup | Run `ScenarioEnvironmentInitializerMain` or its shared initializer component after server startup and before worker/task launch; use explicit dev-header/test auth or override to session; do not make server startup auto-seed scenario data |
| `/api/v1/auth/config` | `xa-mass-server` | Reports operator auth mode and CSRF header name | auth discovery API | Initializer must call this before choosing session login or local/test dev-header auth |
| `ScenarioLauncherConfig` | `integrations/xa-mass-scenario-launcher` | Current task config with `server`, `credentials`, `runtime`, `actions`, `tasks` | task launcher config | Extend or create initializer config contract for environment manifests and worker key cache path; preserve config-relative path resolution |

## Current Gaps

1. There is no runtime operator API that can register scenario project/event
   catalog metadata after the server has already started.
2. There is no runtime operator API that can sync/upsert the scenario rule set
   after the server has already started without clearing unrelated rules.
3. The current scenario credential bootstrapper still fails when catalog is
   missing and tells the operator to restart the server with seed arguments.
4. `durable-local` can be clean but not scenario-ready; this is acceptable only
   if an external initializer can make it scenario-ready without restart.
5. The current scenario setup docs still describe startup seed as the preferred
   local preparation path.
6. Existing Java SDK guard allowlists
   `ScenarioCredentialBootstrapMain` as an integration tool route owner; the
   allowlist must move with the renamed initializer.
7. Memory-profile integration tests need a repeatable post-start initializer
   hook because memory stores intentionally lose scenario catalog/rules on each
   server boot.
8. A session-login-only initializer would fail under the current
   `memory-local` auth mode because that profile uses `dev-header`.
9. Current `replaceDefaultRules(...)` is destructive, so it is not safe as the
   default scenario initializer rule operation.

## Decisions To Close In SEI-0

1. Exact catalog write route shape:
   - candidate A: `POST /api/v1/catalog:import`
   - candidate B: `POST /api/v1/control-plane/catalog:import`
   - target constraint: operator-only route, not Java SDK public surface
2. Exact rule write route shape:
   - candidate A: `POST /api/v1/admin/rules:replace`
   - candidate B: `POST /api/v1/control-plane/rules:replace`
   - target constraint: protected by `rule:edit`
3. Catalog edit permission:
   - candidate A: reuse `config:edit` for first slice
   - candidate B: add explicit catalog permissions
   - target constraint: do not overload task/worker permissions for catalog
     writes
4. Rule restart durability:
   - current code has `RuleStorage` and `JdbcRuleStorage`; SEI-0 must record
     whether the selected rule sync route proves restart durability through that
     storage.
   - default initializer behavior should be rule-id sync/upsert, not full
     replacement.
5. Scenario fixture location:
   - scenario catalog/rules used by initializer should live under
     `integrations/xa-mass-scenario-launcher/examples/`, not server main
     resources.
6. Scenario manifest names:
   - choose names that do not imply server startup seed, for example
     `scenario.catalog.manifest.json` and `scenario.rules.manifest.json`.
   - current `*.seed.json` files may remain only as migration residue until
     SEI-3/SEI-5.
7. Memory test automation:
   - integration tests may automatically run the initializer after the server
     context is listening.
   - server startup must not auto-run scenario initialization as a hidden
     replacement for seed/import.
   - auth mode must be explicit: either use local/test dev-header headers or
     override the memory test server to session mode.
8. Initializer config contract:
   - record fields for `server.baseUrl`, auth source,
     `environment.catalogManifest`, `environment.rulesManifest`,
     task API-key cache file, worker API-key cache file, relative path
     resolution, and CLI/env/config precedence.
9. Catalog import failure semantics:
   - repeated import should be idempotent by event/project code.
   - partial store/live divergence must be prevented or assigned a documented
     recovery proof.

## Explicit Non-Truths

- Startup seed is not proof that external initialization works.
- JSON manifest input is allowed; direct startup import is not the preferred
  scenario initialization path.
- Schema reset is not environment initialization.
- Worker registration is runtime/external truth and must not be restored from
  the control-plane DB at server startup.
- Task creation is not part of environment initialization.
- Java SDK is not the owner of operator login, CSRF, or API-key lifecycle.
