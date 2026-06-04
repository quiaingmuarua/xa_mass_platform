> Archived: 2026-06-04
>
> Current truth owner: `xa-mass-server/README.md`, `integrations/README.md`,
> `sdk/xa-mass-java-sdk/README.md`,
> `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`, and current code/tests.
>
> Do not use this archived inventory as current behavior proof.

# Control-Plane Seed And Sample Bootstrap Retirement Inventory

Status: implemented mainline inventory for
`CONTROL_PLANE_SEED_AND_SAMPLE_BOOTSTRAP_RETIREMENT_ROADMAP.md`.

This inventory records the implemented seed/import boundary and the retired
sample-bootstrap residue.

## Symbols

| Symbol / path | Current owner | Current role | Classification | Target |
| --- | --- | --- | --- | --- |
| `SampleBootstrapController` | `xa-mass-server` | Retired `/sample-api/bootstrap/catalog` and `/rules` HTTP writes | retired dev-only API divergence | Deleted; guarded by `ServerMainSourceArchitectureGuardTest` |
| `SampleCatalogBootstrapRequest` | `xa-mass-server` | Retired HTTP request DTO for event/project/submitter sample bootstrap | retired residue | Deleted |
| `SampleRuleBootstrapRequest` | `xa-mass-server` | Retired HTTP request DTO for rule sample bootstrap | retired residue | Deleted |
| `sample.bootstrap.enabled` | `xa-mass-server` config | Retired sample bootstrap route toggle | retired residue | Removed from active config |
| `sample.bootstrap.api-key` | `xa-mass-server` config | Retired header secret for dev-only sample bootstrap API | retired residue | Removed; seed/import has no public HTTP header auth |
| `DevBootstrapClient` | `integrations/xa-mass-scenario-launcher` | Retired sample bootstrap HTTP client | retired residue | Deleted |
| `ScenarioLauncherOptions.devBootstrapEnabled` and related flags/env | `integrations/xa-mass-scenario-launcher` | Retired sample bootstrap preparation toggles | retired residue | Deleted; scenario launcher assumes initialized metadata |
| `WorkerScenarioRegistrar` | `integrations/xa-mass-scenario-launcher` | Registers WorkerGroup, AdapterNode, NodeGroupBinding, Worker through Java SDK | production-like integration adopter | Keep as scenario launcher registration owner |
| `ControlConsoleScenarioBootstrapConfiguration` | `xa-mass-server` | Retired dev-profile `CommandLineRunner` registering probe events/projects/submitters | retired hidden startup seed residue | Deleted; replaced by explicit `mass.control-plane.seed.*` importer |
| `ControlPlaneSeedImportConfiguration` | `xa-mass-server` | Explicit default-off `CommandLineRunner` seed/import entry | control-plane seed/import owner | Keep server-owned; no HTTP route |
| `ControlPlaneSeedImporter` | `xa-mass-server` | Reads catalog/rules resources and applies events/projects/submitters/rules | control-plane seed/import owner | Keep explicit, default-off, fail-fast |
| `ControlPlaneSeedCatalog` | `xa-mass-server` | First catalog seed payload for events/projects/submitters with counted fixture expansion | seed/import payload | Keep server-owned payload, not public HTTP DTO |
| `ControlPlaneSeedRules` | `xa-mass-server` | First rules seed payload | seed/import payload | Keep server-owned payload, not public HTTP DTO |
| `control-plane-seed/control-console-scenario.json` | `xa-mass-server` resources | First built-in explicit local scenario seed | local seed fixture | Keep default-off; operators/tests opt in explicitly |
| `TestDevBootstrapConfiguration` | `xa-mass-server` tests | Test-only project/submitter/bootstrap preparation | test fixture | May stay test-owned; do not treat as public API |
| `MassSdkApplication.registerProject(...)` | embedded SDK/server assembly | Registers project catalog truth into the embedded runtime/control plane | current write operation | Candidate implementation dependency for first seed importer |
| `MassSdkApplication.registerSubmitter(...)` | embedded SDK/server assembly | Registers submitter credential truth | current write operation | Candidate implementation dependency for first seed importer |
| `MassSdkApplication.replaceDefaultRules(...)` | embedded SDK/server assembly | Replaces rule definitions | current write operation | Candidate implementation dependency for first seed importer |
| `platform_infra/mass-storage-jdbc` SQLite support | platform infra | SQLite dialect/runtime tests for task shell and rule truth | partial control-plane storage proof | Verify per seed target before claiming full persistence |
| `integrations/xa-mass-worker-pack` | integrations | Capability pack and SDK-backed worker runtime adopter | production-like adopter | Keep SDK-backed, no raw platform API ownership |

## Data Groups

| Data group | Current write path | Target layer | Target setup path | Notes |
| --- | --- | --- | --- | --- |
| event definitions | `ControlPlaneSeedImporter`, tests | control-plane catalog | explicit seed/import | Current proof is H2/dev readback; SQLite restart persistence is not claimed in this roadmap |
| projects | `ControlPlaneSeedImporter`, tests | control-plane catalog | explicit seed/import | Current proof is H2/dev readback; project catalog persistence remains outside this slice |
| submitters / credentials | `ControlPlaneSeedImporter`, tests, API key service | control-plane storage | explicit seed/import or real API key flow | JDBC submitter credential storage exists; non-local secrets must not be hard-coded |
| rule definitions | `ControlPlaneSeedImporter`, E2E tests | control-plane storage | explicit seed/import | JDBC/SQLite rule storage has owner proof; engine built-in defaults are not sample metadata |
| WorkerGroup / AdapterNode / NodeGroupBinding / Worker | Java SDK worker APIs | runtime registration plus control-plane declaration as implemented | public SDK/API registration | Must not move into seed/import |
| worker presence / heartbeat / poll / result | Java SDK sessions and `/worker-api/v1/**` | runtime truth | public SDK/API runtime path | Must not move into SQLite |

## Current Decisions

- Dev/prod API parity is now a hard rule. API divergence should be removed,
  not made more configurable.
- Hidden startup metadata seeding is subject to the same rule as HTTP
  bootstrap. Removing `/sample-api/bootstrap/**` is not enough if dev startup
  still creates sample/control-console metadata by default.
- SQLite is the lightweight control-plane DB direction; Redis remains runtime
  truth; trace DB materialization is deferred and trace-owned.
- Seed/import is default-off and explicit. It is not migration and not a
  public runtime API.
- Scenario-launcher should prove external SDK behavior against an initialized
  server.
- Worker-pack should expose capabilities and SDK-backed worker runtimes only.
- Dev H2 remains allowed in this roadmap. SQLite-for-dev is not selected as a
  CPSR-2 scope extension.
- CPSR only proves explicit seed/import behavior and H2/dev readback for the
  current local scenario fixture. It does not claim complete SQLite persistence
  for event/project catalog truth.
- Clean startup does not assert zero engine default rules. Engine-owned built-in
  rules may exist without seed/import and are not sample/control-console
  metadata.

## Implemented Seed Payload

- Catalog resource: `ControlPlaneSeedCatalog`
  - `events`
  - `projects`
  - `submitters`
  - optional `count` for fixture expansion
- Rules resource: `ControlPlaneSeedRules`
  - `rules`
- Implemented local seed resource:
  `xa-mass-server/src/main/resources/control-plane-seed/control-console-scenario.json`.
- Existing sample fixture resources under `integrations/samples/dev/scenario/`
  are still usable by explicit file-based seed/import in tests and local runs.

## Implemented Proofs

- `CleanServerStartupIntegrationTest` proves dev startup with seed/import
  disabled creates no sample/control-console event, project, submitter, task,
  worker, or worker group state.
- `ControlPlaneSeedImportIntegrationTest` proves explicit seed/import applies
  events, projects, submitters, and rules.
- `DevSampleWorkerLauncherIntegrationTest` now gets catalog/rule/submitter
  metadata from explicit seed/import rather than `/sample-api/bootstrap/**`.
- `JavaScenarioLauncherBlackBoxIntegrationTest` uses initialized server
  metadata and no scenario-launcher dev bootstrap client.
- `ServerMainSourceArchitectureGuardTest` proves sample bootstrap HTTP is not
  an active server API and worker-pack does not own platform/bootstrap routes.

## Verification Seed

```powershell
rg -n "SampleBootstrapController|DevBootstrapClient|sample\\.bootstrap|/sample-api/bootstrap|X-Sample-Bootstrap-Key|ControlConsoleScenarioBootstrapConfiguration|registerProject|registerSubmitter|replaceDefaultRules" xa-mass-server integrations sdk roadmap doc -g "*.java" -g "*.md" -g "*.yml"
```
