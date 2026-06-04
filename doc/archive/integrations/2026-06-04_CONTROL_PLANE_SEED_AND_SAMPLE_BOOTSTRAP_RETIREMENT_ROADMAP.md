> Archived: 2026-06-04
>
> Current truth owner: `xa-mass-server/README.md`, `integrations/README.md`,
> `sdk/xa-mass-java-sdk/README.md`,
> `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`, and current code/tests.
>
> Do not use this archived roadmap as current behavior proof.

# Control-Plane Seed And Sample Bootstrap Retirement Roadmap

Status: implemented mainline on 2026-06-04; archive after residue scan and
handoff link cleanup.

This roadmap retires the remaining dev-only sample bootstrap API and replaces
it with explicit new-environment control-plane seed/import. It is the next
server + integrations + SDK boundary step after real external worker
registration landed.

The core rule is:

```text
dev/prod may differ by infra, seed source, logging, and operational defaults;
dev/prod must not differ by public API contract.
```

## Purpose

Remove this residue path:

```text
ScenarioLauncher -> DevBootstrapClient -> /sample-api/bootstrap/**
```

Replace it with:

```text
explicit environment seed/import
  -> control-plane storage
  -> normal public SDK/API registration and task paths
```

This lets server, integrations, SDK, and worker-pack stop acting like dev
samples and start producing production-like pressure on the kernel path:

```text
API key -> WorkerGroup -> AdapterNode -> NodeGroupBinding -> Worker
  -> worker session -> task item dispatch -> result convergence
```

## Related Current Truth

- `doc/INFRA_TRUTH_LAYERS.md`
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`
- `platform_infra/README.md`
- `doc/archive/integrations/2026-06-04_EXTERNAL_WORKER_REAL_REGISTRATION_ONBOARDING_ROADMAP.md`
- `doc/archive/integrations/2026-06-04_EXTERNAL_WORKER_REAL_REGISTRATION_ONBOARDING_INVENTORY.md`
- `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`
- `integrations/README.md`
- `integrations/xa-mass-scenario-launcher/README.md`
- `integrations/xa-mass-worker-pack/README.md`
- `xa-mass-server/README.md`

## Current Code Facts

- `SampleBootstrapController`, `SampleCatalogBootstrapRequest`, and
  `SampleRuleBootstrapRequest` have been deleted from server main source.
- `DevBootstrapClient`, `--skip-dev-bootstrap`, `--dev-bootstrap`,
  `MASS_SCENARIO_DEV_BOOTSTRAP`, `SAMPLE_BOOTSTRAP_KEY`, and bootstrap-key
  scenario-launcher options have been deleted.
- `sample.bootstrap.*`, `/sample-api/bootstrap/**`, and
  `X-Sample-Bootstrap-Key` are removed from active server and integration
  mainline.
- `ControlConsoleScenarioBootstrapConfiguration` has been deleted. Dev startup
  no longer seeds control-console/sample metadata by default.
- `ControlPlaneSeedImportConfiguration` provides an explicit default-off
  `mass.control-plane.seed.*` runner for dev/prod profiles.
- `ControlPlaneSeedImporter` reads catalog/rules resources and applies events,
  projects, submitters, and rules through `MassSdkApplication`.
- `application-prod.yml` uses `mass.storage.mode=jdbc-sqlite` plus Redis
  runtime/delivery/presence. `application-dev.yml` keeps H2/memory runtime as
  an allowed infra difference.
- Dev/prod API parity is protected by
  `ServerMainSourceArchitectureGuardTest.sampleBootstrapHttpIsNotActiveServerApi`.
- `CleanServerStartupIntegrationTest` proves dev startup with seed/import
  disabled creates no sample/control-console metadata and no task/worker
  runtime truth.
- `ControlPlaneSeedImportIntegrationTest` proves explicit seed/import applies
  event, project, submitter, and rule metadata.
- SQLite remains the lightweight control-plane storage direction, but this
  roadmap does not claim complete SQLite persistence for every seed target.
  The inventory records which targets remain current readback proof only.
- Worker-pack remains an SDK-backed capability/runtime adopter and owns no raw
  platform or bootstrap HTTP surface in production source.

## Hard Rules

- Dev/prod may change infra backend, seed source, logging, ports, thread
  counts, and operational defaults; they must not expose different public API
  contracts.
- SQLite is only control-plane storage: project, rule, catalog, submitter,
  credential, seed/import metadata, and other stable restart-required facts.
- Redis remains runtime truth for ready/delayed queues, leases, counters,
  worker presence, dispatch handoff, result ingress, and cross-process runtime
  state.
- Trace/audit DB materialization is trace-owned and deferred. It must not
  become control-plane storage or runtime truth.
- Seed/import is default-off and explicit. It may run for a new environment or
  test/local fixture setup, but not as hidden production startup seeding.
- Startup `CommandLineRunner` metadata seeding is subject to the same rule.
  A dev-profile runner that silently creates projects/events/submitters by
  default is another bootstrap path, even if it is not exposed through HTTP.
- Do not introduce migrations for commercial-history compatibility. Current
  stage may fail fast on stale schema or stale local data with a clear
  recreate/reseed instruction.
- Do not replace `/sample-api/bootstrap/**` with another dev-only HTTP
  bootstrap API.
- Do not make scenario-launcher prepare server internals. It proves SDK-backed
  external registration against an initialized server.
- Do not make worker-pack own raw platform APIs, raw worker routes, or Spring
  MVC bootstrap endpoints.
- Do not move worker registration, worker presence, task queue, lease, or
  result convergence into seed/import.

## Non-Goals

- No PostgreSQL mainline dependency.
- No commercial migration framework.
- No trace DB materialization implementation.
- No new public project/rule/admin write API unless a separate control-plane
  API roadmap owns IAM, caller model, DTOs, and persistence semantics.
- No worker-pack capability move into SDK or server.
- No compatibility alias that keeps sample bootstrap and seed/import as two
  long-lived setup tracks.

## Do Not Start With

Do not start by deleting `SampleBootstrapController`, `DevBootstrapClient`, or
`sample.bootstrap.*`. First replace the catalog/rule/submitter preparation role
for local verification, black-box tests, and scenario-launcher runs. No slice
should break the existing real external worker registration proof and restore
it later.

## Target Shape

- Clean server startup exposes no sample bootstrap HTTP surface.
- Clean server startup with seed/import disabled creates no sample
  catalog/project/submitter/rule metadata.
- Environment setup is explicit seed/import into control-plane storage.
- Seed/import defaults off and can be enabled only by explicit config or a
  local/test command path.
- Scenario-launcher has one mainline: SDK-backed external worker registration,
  worker session start, task submission, and result convergence.
- Worker-pack is a reusable capability pack plus SDK-backed worker runtime
  adopter.
- Dev/prod public API contracts are identical.

## CPSR-0: Inventory Seed Owners And Residue

Goal: make the data owner and caller split explicit before changing behavior.

Scope:

- Create or update
  `roadmap/CONTROL_PLANE_SEED_AND_SAMPLE_BOOTSTRAP_RETIREMENT_INVENTORY.md`.
- Inventory all current seed-like sources:
  - `SampleBootstrapController`,
  - `DevBootstrapClient`,
  - `sample.bootstrap.*`,
  - `ControlConsoleScenarioBootstrapConfiguration`,
  - test-only `TestDevBootstrapConfiguration`,
  - E2E tests that call `registerProject`, `registerSubmitter`, or
    `replaceDefaultRules`,
  - worker-pack and scenario-launcher callers.
- Classify each value as:
  - control-plane seed data,
  - runtime registration data,
  - test fixture,
  - local scenario fixture,
  - production API,
  - implemented residue.
- For each control-plane seed data group, record the current storage owner and
  whether SQLite-backed persistence is already proven.

Acceptance:

- Inventory names the first seed/import payload shape.
- Inventory names which current source replaces `SampleBootstrapController`.
- Inventory states whether dev H2 remains allowed or whether SQLite becomes
  the dev/prod control-plane default in this roadmap.
- If the inventory selects SQLite as the dev control-plane default, record that
  as a named CPSR-2 scope extension with explicit persistence proof
  expectations. Do not let an inventory decision silently expand CPSR-2.
- Inventory names the first tests that must move off sample HTTP.

Verification:

```powershell
rg -n "SampleBootstrapController|DevBootstrapClient|sample\\.bootstrap|/sample-api/bootstrap|X-Sample-Bootstrap-Key|ControlConsoleScenarioBootstrapConfiguration|registerProject|registerSubmitter|replaceDefaultRules" xa-mass-server integrations sdk roadmap doc -g "*.java" -g "*.md" -g "*.yml"
```

## CPSR-1: Define Explicit Seed/Import Contract

Goal: replace implicit dev HTTP bootstrap with a concrete local/new-environment
setup contract.

Scope:

- Define config and naming for the importer, for example:
  - `mass.control-plane.seed.enabled=false`,
  - `mass.control-plane.seed.location=...`,
  - `mass.control-plane.seed.mode=validate|apply`.
- Keep seed/import off by default in both dev and prod.
- Define the first payload format for:
  - events,
  - projects,
  - submitters/credentials,
  - rules.
- Define idempotency expectations:
  - re-run with same ids is replace/update or fail-fast, but must be explicit,
  - no silent partial import.
- Define credential secret handling:
  - local/dev fixture secret may be deterministic,
  - non-local seed should accept operator-provided secret or generate once,
  - only hashed/stored credential truth should persist.
- Define stale schema behavior as fail-fast/recreate/reseed, not migration.

Acceptance:

- No public HTTP route is introduced.
- Seed/import owner and config are documented in `xa-mass-server/README.md`.
- `doc/INFRA_TRUTH_LAYERS.md` remains the placement authority for SQLite,
  Redis, and trace decisions.

Verification:

```powershell
rg -n "mass\\.control-plane\\.seed|seed/import|sample\\.bootstrap|/sample-api/bootstrap" xa-mass-server README.md doc roadmap -g "*.md" -g "*.yml" -g "*.java"
```

## CPSR-2: Implement Control-Plane Seed Importer

Goal: add the replacement for sample bootstrap without adding API divergence.

Scope:

- Add a server-owned seed importer that runs only when explicitly enabled.
- Reuse existing control-plane operations where possible:
  `registerEventDefinition`, `registerProject`, `registerSubmitter`, and
  `replaceDefaultRules`.
- Keep importer code under server/bootstrap or another server-owned package,
  not under worker-pack, SDK, or integrations.
- Keep seed/import separate from worker registration. WorkerGroup,
  AdapterNode, NodeGroupBinding, Worker, presence, and sessions still enter
  through public SDK/API paths.
- Add focused tests for:
  - default off,
  - enabled import applies events/projects/submitters/rules,
  - invalid payload fails visibly,
  - stale schema or unsupported version fails fast if versioning is present.
- Update clean startup proof so seed/import disabled startup creates no sample
  catalog, project, submitter, or rule metadata. This is separate from the
  existing no task/worker/runtime truth assertion.
- The clean startup proof must cover the dev profile path that currently loads
  `ControlConsoleScenarioBootstrapConfiguration`; dev startup with seed/import
  disabled must not create control-console/sample metadata by default.
- Add enabled-import proof showing the same metadata appears only after
  explicit seed/import.
- For every seed target, either prove SQLite restart readability or record in
  the inventory that the target remains in-memory/currently out of this
  roadmap's persistence scope. Do not claim complete SQLite control-plane
  persistence from `MassSdkApplication` calls alone.

Acceptance:

- A clean startup without seed/import creates no task/worker/runtime truth.
- A clean startup without seed/import creates no sample catalog, project,
  submitter, or rule metadata.
- The no-metadata clean startup proof covers dev profile startup, not only
  prod-like startup, because the current hidden seed residue is dev-profile
  `CommandLineRunner` metadata injection.
- Explicit seed/import prepares only control-plane metadata.
- Explicit seed/import has per-target persistence evidence:
  events/projects/submitters/rules are readable after SQLite restart, or the
  inventory names the target as not yet SQLite-persistent and out of this
  slice's persistence claim.
- The importer has no HTTP controller.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=*Seed*,*Bootstrap*,*ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## CPSR-3: Default Scenario Proof To Initialized Server

Goal: make scenario and black-box proof default to initialized server metadata
instead of sample bootstrap HTTP, without deleting the old client before the
replacement setup path is proven.

Scope:

- Update scenario-launcher local docs and test wiring to assume seed/import or
  test-owned fixture preparation.
- First change scenario-launcher default behavior to require an already
  initialized server and perform no bootstrap preparation.
- Do not delete `--skip-dev-bootstrap` / default-toggle residue before
  seed/import is implemented, documented, and proven as the local setup path.
- Convert tests that exercise scenario launcher through sample bootstrap to
  seed/import or direct test fixture setup.
- Keep `WorkerScenarioRegistrar` as the one registration owner path.
- If legacy sample bootstrap options still exist at the end of this slice, they
  must be explicit opt-in residue and must not be the default path.

Acceptance:

- Default scenario-launcher execution does not call `/sample-api/bootstrap/**`.
- Real-proof tests use initialized metadata and do not depend on sample HTTP.
- Any remaining `DevBootstrapClient` / dev-bootstrap option is explicit opt-in
  residue scheduled for CPSR-4, not the default proof path.
- Scenario-launcher still proves external worker registration and task result
  convergence.

Verification:

```powershell
./mvnw -pl integrations/xa-mass-scenario-launcher,xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "sample-api/bootstrap|X-Sample-Bootstrap-Key" integrations/xa-mass-scenario-launcher/src/main xa-mass-server/src/test/java -g "*.java" -g "*.md"
rg -n "DevBootstrapClient|devBootstrapEnabled|skip-dev-bootstrap|MASS_SCENARIO_DEV_BOOTSTRAP" integrations/xa-mass-scenario-launcher -g "*.java" -g "*.md"
```

## CPSR-4: Retire Scenario And Server Bootstrap Residue

Goal: remove dev-only API divergence from scenario-launcher and server
mainline after seed/import is proven.

Scope:

- Remove `DevBootstrapClient` from scenario-launcher mainline.
- Remove `devBootstrapEnabled`, `--skip-dev-bootstrap`,
  `MASS_SCENARIO_DEV_BOOTSTRAP`, bootstrap-key options, and docs that exist
  only for sample HTTP.
- Delete `SampleBootstrapController` and request DTO residue after callers are
  converted.
- Remove `sample.bootstrap.*` active config and README entries.
- Update `ServerMainSourceArchitectureGuardTest` from "sample bootstrap is
  prod-selectable but off" to "sample bootstrap HTTP is not an active server
  API".
- Convert `ControlConsoleScenarioBootstrapConfiguration` to the same
  seed/import contract, or move it out of server mainline into a test-only or
  explicit local command path.
- Dev profile must not run control-console scenario metadata seeding by
  default. If a local command path remains, it must be explicitly invoked and
  documented as seed/import, not as a second bootstrap mechanism.

Acceptance:

- Dev/prod profiles expose the same public API surface.
- Scenario-launcher main source contains no `/sample-api/bootstrap`,
  `X-Sample-Bootstrap-Key`, or `DevBootstrapClient`.
- Source scan shows no active `/sample-api/bootstrap/**` server route.
- Source/config scan shows no default dev startup path that creates
  control-console/sample events, projects, submitters, or rules outside the
  seed/import contract.
- Local verification still has an explicit seed/import setup path.

Verification:

```powershell
./mvnw -pl integrations/xa-mass-scenario-launcher,xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest,*ServerMainSourceArchitectureGuardTest,*Seed*" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "DevBootstrapClient|devBootstrapEnabled|skip-dev-bootstrap|MASS_SCENARIO_DEV_BOOTSTRAP|X-Sample-Bootstrap-Key" integrations/xa-mass-scenario-launcher -g "*.java" -g "*.md"
rg -n "SampleBootstrapController|sample\\.bootstrap|/sample-api/bootstrap|X-Sample-Bootstrap-Key" xa-mass-server/src/main xa-mass-server/src/main/resources xa-mass-server/README.md -g "*.java" -g "*.yml" -g "*.md"
```

## CPSR-5: Worker-Pack Raw API Boundary Guard

Goal: keep worker-pack as capability/runtime adopter, not platform API owner.

Scope:

- Scan worker-pack production source for raw `/worker-api/v1`,
  `/sample-api/bootstrap`, `HttpClient` platform callers, and platform route
  ownership such as `@RequestMapping("/worker-api/v1")` or
  `@RequestMapping("/sample-api/bootstrap")`. Do not treat a generic
  `@RestController` as a violation unless it exposes platform/bootstrap routes.
- Move any protocol fixture that must remain into test/harness ownership, or
  delete it if it only demonstrates an obsolete path.
- Add or extend an architecture guard proving worker-pack production code:
  - uses Java SDK sessions for worker runtime,
  - does not expose raw platform API clients,
  - does not own bootstrap controllers.

Acceptance:

- Worker-pack public surface is capability definitions plus SDK-backed worker
  runtime.
- Worker-pack does not become a second SDK or raw API facade.

Verification:

```powershell
./mvnw -pl integrations/xa-mass-worker-pack,xa-mass-server -am "-Dtest=*WorkerPack*,*ArchitectureGuard*" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "/worker-api/v1|/sample-api/bootstrap|@RequestMapping\\(\\\"/worker-api/v1|@RequestMapping\\(\\\"/sample-api/bootstrap|HttpClient|SampleBootstrap" integrations/xa-mass-worker-pack/src/main -g "*.java" -g "*.md"
```

## CPSR-6: Owner Docs, Guards, And Residue Scan

Goal: make the retired path stay retired.

Scope:

- Update:
  - `xa-mass-server/README.md`,
  - `integrations/README.md`,
  - `integrations/xa-mass-scenario-launcher/README.md`,
  - `integrations/xa-mass-worker-pack/README.md`,
  - `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md` if external setup
    wording changes.
- Keep `doc/INFRA_TRUTH_LAYERS.md` and
  `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` aligned with the final code.
- Add or update guards for:
  - no active sample bootstrap HTTP route,
  - no scenario-launcher sample bootstrap client,
  - no worker-pack raw API surface,
  - dev/prod API contract parity.
- Archived `EXTERNAL_WORKER_REAL_REGISTRATION_ONBOARDING_ROADMAP.md` and its
  inventory after this successor roadmap owned the remaining residue.

Verification:

```powershell
# Active main-source residue must be empty.
rg -n "DevBootstrapClient|SampleBootstrapController|sample\\.bootstrap|/sample-api/bootstrap|X-Sample-Bootstrap-Key" xa-mass-server/src/main integrations/xa-mass-scenario-launcher/src/main integrations/xa-mass-worker-pack/src/main sdk -g "*.java" -g "*.md" -g "*.yml"

# Test residue is allowed only in absence guards or test-owned fixture setup.
rg -n "DevBootstrapClient|SampleBootstrapController|sample\\.bootstrap|/sample-api/bootstrap|X-Sample-Bootstrap-Key" xa-mass-server/src/test integrations/xa-mass-scenario-launcher/src/test integrations/xa-mass-worker-pack/src/test -g "*.java" -g "*.md"

# Active docs may mention retired terms only as residue-scan expectations until
# this roadmap is archived; owner READMEs must not route users through them.
rg -n "DevBootstrapClient|SampleBootstrapController|sample\\.bootstrap|/sample-api/bootstrap|X-Sample-Bootstrap-Key" doc roadmap README.md xa-mass-server/README.md integrations/README.md sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md -g "*.md"

rg -n "/worker-api/v1" integrations/xa-mass-worker-pack/src/main -g "*.java" -g "*.md"
./mvnw -pl sdk/xa-mass-java-sdk,integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack,xa-mass-server -am test
git diff --check
```

Acceptance:

- Old bootstrap vocabulary appears only in archive, this roadmap before it is
  archived, or tests intentionally proving absence.
- The aggregate SDK/integrations/server proof still passes.
- New agents can understand the production-like flow from owner READMEs
  without following dev sample bootstrap docs.
