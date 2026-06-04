# Control-Plane Seed And Sample Bootstrap Retirement Inventory

Status: current code inventory for
`CONTROL_PLANE_SEED_AND_SAMPLE_BOOTSTRAP_RETIREMENT_ROADMAP.md`.

This inventory records the starting point only. Implementation slices may
update classifications as seed/import ownership lands.

## Symbols

| Symbol / path | Current owner | Current role | Classification | Target |
| --- | --- | --- | --- | --- |
| `SampleBootstrapController` | `xa-mass-server` | Optional `/sample-api/bootstrap/catalog` and `/rules` HTTP writes | implemented residue / dev-only API divergence | Delete after seed/import replaces local setup |
| `SampleCatalogBootstrapRequest` | `xa-mass-server` | HTTP request DTO for event/project/submitter sample bootstrap | implemented residue | Replace with seed/import payload or delete |
| `SampleRuleBootstrapRequest` | `xa-mass-server` | HTTP request DTO for rule sample bootstrap | implemented residue | Replace with seed/import payload or delete |
| `sample.bootstrap.enabled` | `xa-mass-server` config | Enables sample bootstrap route in dev, disables in prod | implemented residue | Remove after route retirement |
| `sample.bootstrap.api-key` | `xa-mass-server` config | Header secret for dev-only sample bootstrap API | implemented residue | Remove; seed/import must not use public HTTP header auth |
| `DevBootstrapClient` | `integrations/xa-mass-scenario-launcher` | Calls sample bootstrap HTTP routes | implemented residue | Keep off the default proof path in CPSR-3; delete in CPSR-4 |
| `ScenarioLauncherOptions.devBootstrapEnabled` and related flags/env | `integrations/xa-mass-scenario-launcher` | Toggles sample bootstrap preparation | implemented residue | Keep explicit opt-in only during CPSR-3; delete in CPSR-4 |
| `WorkerScenarioRegistrar` | `integrations/xa-mass-scenario-launcher` | Registers WorkerGroup, AdapterNode, NodeGroupBinding, Worker through Java SDK | production-like integration adopter | Keep as scenario launcher registration owner |
| `ControlConsoleScenarioBootstrapConfiguration` | `xa-mass-server` | Dev-profile `CommandLineRunner` registering probe events/projects/submitters | startup data injection / hidden seed residue | Convert to seed/import or move to test-only/explicit local command; dev default must not run it |
| `TestDevBootstrapConfiguration` | `xa-mass-server` tests | Test-only project/submitter/bootstrap preparation | test fixture | May stay test-owned; do not treat as public API |
| `MassSdkApplication.registerProject(...)` | embedded SDK/server assembly | Registers project catalog truth into the embedded runtime/control plane | current write operation | Candidate implementation dependency for first seed importer |
| `MassSdkApplication.registerSubmitter(...)` | embedded SDK/server assembly | Registers submitter credential truth | current write operation | Candidate implementation dependency for first seed importer |
| `MassSdkApplication.replaceDefaultRules(...)` | embedded SDK/server assembly | Replaces rule definitions | current write operation | Candidate implementation dependency for first seed importer |
| `platform_infra/mass-storage-jdbc` SQLite support | platform infra | SQLite dialect/runtime tests for task shell and rule truth | partial control-plane storage proof | Verify per seed target before claiming full persistence |
| `integrations/xa-mass-worker-pack` | integrations | Capability pack and SDK-backed worker runtime adopter | production-like adopter | Keep SDK-backed, no raw platform API ownership |

## Data Groups

| Data group | Current write path | Target layer | Target setup path | Notes |
| --- | --- | --- | --- | --- |
| event definitions | `SampleBootstrapController`, `ControlConsoleScenarioBootstrapConfiguration`, tests | control-plane storage | explicit seed/import | Must remain catalog truth, not worker registration |
| projects | `SampleBootstrapController`, `ControlConsoleScenarioBootstrapConfiguration`, tests | control-plane storage | explicit seed/import | Project/event binding constrains allowed work |
| submitters / credentials | `SampleBootstrapController`, `ControlConsoleScenarioBootstrapConfiguration`, tests, API key service | control-plane storage | explicit seed/import or real API key flow | Non-local secrets must not be hard-coded |
| rule definitions | `SampleBootstrapController`, E2E tests | control-plane storage | explicit seed/import | Do not hide engine defaults inside storage modules |
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

## First Implementation Questions

- Should dev profile move from H2 control-plane storage to SQLite in this
  roadmap, or remain an infra difference while seed/import proves SQLite in
  prod-like tests?
- Should the first seed/import implementation reuse `MassSdkApplication`
  operations directly, or write through lower-level storage/control-plane
  services where available?
- Should `ControlConsoleScenarioBootstrapConfiguration` become a seed file or
  move to a test-only/explicit local command? It must not remain dev-default
  startup seeding.
- What is the first accepted non-local credential secret policy:
  operator-provided secret, generated one-time secret, or seed-disabled until
  real API key creation is used?

## Verification Seed

```powershell
rg -n "SampleBootstrapController|DevBootstrapClient|sample\\.bootstrap|/sample-api/bootstrap|X-Sample-Bootstrap-Key|ControlConsoleScenarioBootstrapConfiguration|registerProject|registerSubmitter|replaceDefaultRules" xa-mass-server integrations sdk roadmap doc -g "*.java" -g "*.md" -g "*.yml"
```
