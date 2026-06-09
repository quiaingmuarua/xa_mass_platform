# Scenario Environment External Initialization Roadmap

Status: superseded by `PLATFORM_CONFIDENCE_GATE_ROADMAP.md` and
`ADMIN_ENV_INIT_STATE_MODEL_ROADMAP.md`.

Supersession note:

- Do not implement `ScenarioEnvironmentInitializerMain` from this roadmap.
- Environment initialization owner is now `tools/xa-mass-admin-cli env init`.
- Scenario launcher keeps only task-producer and worker-process roles.
- Keep this document only as historical background until it is archived during
  PCG residue cleanup.

## Summary

`durable-local` may start as a clean platform shell. That is acceptable only if
there is a real external initialization path that can make a new environment
scenario-ready without restarting the server with sample seed flags.

This roadmap converges the local/product-readiness scenario lane into three
separate process roles:

1. environment initialization:
   `ScenarioEnvironmentInitializerMain`
2. external worker process:
   `ScenarioWorkerLauncherMain`
3. external task producer:
   `ScenarioTaskLauncherMain`

The first role reads local scenario JSON manifests and prepares control-plane
metadata plus credentials through server operator APIs. The second and third
roles run through the Java SDK task/worker external APIs. Startup seed remains
a minimal operator bootstrap, not the scenario data path.

## Current Code Observations

- `application-durable-local.yml` can now start from a clean SQLite-backed
  control-plane DB and seed an operator credential.
- `AuthController` exposes operator login and returns CSRF state for subsequent
  unsafe requests.
- `ApiKeyController` exposes `/api/v1/api-keys`; an operator with
  `api-key:approve` can create task/worker API keys and receive the one-time
  raw secret.
- `CatalogController` and `ProjectApiController` expose read-only catalog and
  project routes.
- `RuleApiController` exposes read-only `/api/v1/admin/rules` routes.
- `ControlPlaneSeedImporter` can import catalog metadata, rules, API keys, and
  operator credentials at startup, but this is not a runtime external API.
- Scenario catalog/rules JSON files can remain useful as local manifests, but
  the current preferred proof must submit them through HTTP APIs instead of
  importing them during server startup.
- `CatalogMetadataStore` and `CatalogMetadataProjection` already provide a
  durable catalog store/projection path for event/project metadata.
- `ScenarioCredentialBootstrapMain` currently creates task/worker key caches,
  but environment mode still requires the scenario catalog to already exist and
  fails with startup seed instructions when it does not.
- `ScenarioWorkerLauncherMain` already registers worker topology and starts
  SDK worker sessions.
- `ScenarioTaskLauncherMain` already creates task shells and appends items via
  `sdk/xa-mass-java-sdk`.

## Owner Review

- `xa-mass-server` owns operator login, CSRF, API route authorization,
  API-key lifecycle, catalog write APIs, rule write APIs, and startup/profile
  assembly.
- `platform_infra/mass-storage-jdbc` and `platform_infra/mass-storage-api`
  own generic durable catalog storage. They do not own server API routes,
  operator auth, API-key lifecycle, or worker runtime truth.
- `sdk/xa-mass-java-sdk` owns external task and worker typed clients. It must
  not gain operator login, CSRF, credential lifecycle, or local environment
  initialization helpers.
- `integrations/xa-mass-scenario-launcher` owns scenario orchestration tools
  that compose server operator APIs plus Java SDK task/worker APIs. It may call
  server control-plane APIs as integration-local tooling, not as public SDK
  surface.
- Worker and task launchers are separate external roles. The initializer must
  not start worker sessions or create tasks.

## Boundary Decision

Environment initialization is a real operator control-plane flow, not a server
startup seed flow.

```text
durable-local startup
  -> clean server shell
  -> minimal operator credential only
  -> no scenario project/event/rules/task/worker

ScenarioEnvironmentInitializerMain
  -> GET /api/v1/auth/config
  -> operator login
  -> read scenario catalog/rules JSON manifests
  -> catalog import/upsert through operator API
  -> rules sync through operator API
  -> create or refresh task API-key cache through /api/v1/api-keys
  -> create or refresh worker API-key cache through /api/v1/api-keys

ScenarioWorkerLauncherMain
  -> read worker API-key cache
  -> declare worker group / adapter node / bindings / workers
  -> start polling or WebSocket SDK worker sessions

ScenarioTaskLauncherMain
  -> read task API-key cache
  -> create task shell
  -> append task items
```

## Hard Rules

1. `durable-local` may be clean. Clean startup must not imply scenario-ready
   startup.
2. Scenario catalog/rules must be created through external operator APIs before
   worker/task launchers are considered runnable against a clean server.
3. Startup seed may provide the minimal operator credential. It must not be the
   preferred scenario catalog/rules/API-key path after this roadmap lands.
4. Scenario JSON files may remain, but their target role is external
   initializer manifest input. They must not be described as the normal server
   startup seed path.
5. Do not add operator login, CSRF, catalog import, rule import, or API-key
   lifecycle helpers to `sdk/xa-mass-java-sdk`.
6. Do not expose scenario-specific sample routes such as
   `/sample-api/bootstrap/**`.
7. Catalog writes are control-plane storage writes. They must update durable
   catalog storage and the live application catalog consistently.
8. Rule writes are operator control-plane writes. The first external scenario
   initializer route must be rule-id sync/upsert by default, not global full
   replace. A destructive full default-rule-set replace requires an explicit
   mode/route, `rule:edit`, and tests proving non-manifest rules are deleted
   only when requested.
9. Worker registration remains external runtime registration. Do not restore
   workers, AdapterNodes, NodeGroupBindings, worker sessions, heartbeats, or
   leases from the control-plane DB during server startup.
10. Task creation remains task-producer behavior. The environment initializer
   must not create tasks or append task items.
11. The three launcher roles must remain separate process entries. Do not
    reintroduce a single main that initializes environment, starts workers, and
    submits tasks in one flow.
12. API-key raw secrets may be written only to ignored local cache files and
    returned only by the existing one-time API-key creation response. Do not
    log raw secrets.
13. Initializer operator auth must be auth-mode aware. It must call
    `/api/v1/auth/config` first: session mode uses login + cookie + CSRF;
    dev-header mode is allowed only for local/test harnesses through explicit
    operator headers or an explicit test override to session mode.
14. Memory-profile integration tests may automate environment initialization
    only as a post-start test harness step that calls the same external
    operator APIs. Do not make the server auto-seed scenario data at startup to
    make memory tests pass.
15. Any server profile/startup/auth/control-plane API changes must include a
    startup or Spring context proof for the relevant profile.

## Non-Goals

- No public Java SDK credential-management API.
- No frontend UI for scenario environment initialization in the first pass.
- No historical DB migration compatibility; pre-release DB reset rules still
  apply.
- No worker config schema in `ScenarioEnvironmentInitializerMain`.
- No runtime worker history table in this roadmap.
- No SSE task/worker realtime stream implementation.
- No production secret manager integration.
- No task creation or worker-session start inside the initializer.

## Do Not Start With

Do not start by renaming `ScenarioCredentialBootstrapMain` and keeping its
current behavior. That would only rename the failure. The first real slice must
provide or choose the operator API that can write catalog/rules after the server
has already started.

## SEI-0 Inventory And API Shape Decision

Goal:

Close the owner and route decisions needed before implementation.

Scope:

- Review `SCENARIO_ENVIRONMENT_EXTERNAL_INITIALIZATION_INVENTORY.md`.
- Choose exact route shapes for catalog and rule writes.
- Choose catalog edit permission strategy.
- Choose rule write semantics. Default target is rule-id sync/upsert that does
  not delete non-manifest rules; destructive replace is a separate explicit
  mode if retained at all.
- Decide and record whether rule sync is restart durable through current
  `RuleStorage`.
- Decide and record initializer auth behavior for `session` and `dev-header`
  modes.
- Move or copy scenario catalog/rules manifests into
  `integrations/xa-mass-scenario-launcher/examples/` before SEI-2 starts.
- Decide scenario manifest names that do not imply server startup seed, for
  example `scenario.catalog.manifest.json` and
  `scenario.rules.manifest.json`.
- Record whether the first catalog write API uses a bulk import/upsert shape or
  narrower event/project endpoints.
- Record the initializer config contract:
  `server.baseUrl`, auth source, `environment.catalogManifest`,
  `environment.rulesManifest`, task key cache file, worker key cache file,
  relative path resolution, and CLI/env/config precedence.
- Record how memory-profile integration tests run environment initialization
  automatically after server startup without making server startup seed
  scenario data.

Acceptance:

- Inventory records the selected route shapes and permissions.
- Inventory states the selected rule sync/replace semantics and restart
  durability.
- Inventory states the selected initializer auth-mode behavior.
- Inventory states the selected initializer config shape and precedence.
- Inventory states whether scenario JSON files are manifests, fixtures, or
  startup seed inputs for each active path.
- Inventory records the memory-profile test automation rule.
- No implementation slice is allowed to treat startup seed as the external API.
- Current local schema reset and scenario credential readiness are documented by
  `xa-mass-server/README.md`, `tools/xa-mass-admin-cli/README.md`, and the
  platform confidence smoke; they are prerequisites, not runtime
  initialization owners.

Verification:

```bash
rg -n "CatalogController|ProjectApiController|RuleApiController|ControlPlaneSeedImporter|ScenarioCredentialBootstrapMain|ScenarioEnvironmentInitializerMain" xa-mass-server integrations/xa-mass-scenario-launcher roadmap -g "*.java" -g "*.md"
```

## SEI-1 Operator Catalog And Rule Write APIs

Goal:

Make a clean running server externally initializable without startup seed flags.

Scope:

- Add operator-only catalog import/upsert route selected in SEI-0.
- Add operator-only rules sync/upsert route selected in SEI-0.
- Register both routes in `ApiRouteAuthorizationCatalog`.
- Require session auth and CSRF for unsafe operator writes.
- Reuse current catalog DTOs only if they are Controller-exposed and do not
  leak seed-only raw secret concepts.
- Catalog writes must call `CatalogMetadataStore` and update the live
  `MassSdkApplication` catalog in one server-owned path.
- Rule sync must call the current `RuleOperations`/`RuleStorage` owner and must
  not clear rules that are absent from the manifest.
- Destructive rule replace, if implemented, must be a separate explicit mode or
  route and must not be the initializer default.

Acceptance:

- A clean `durable-local` server can accept operator catalog/rule initialization
  without restart.
- Catalog write API rejects project-event bindings that are missing after
  resolving existing events plus events supplied by the same import request.
- Repeated catalog import is idempotent by event/project code.
- Catalog import failures do not leave durable store and live app catalog
  partially divergent, or the implementation records a precise recovery rule
  backed by a restart/restore proof.
- Catalog write API does not accept API-key raw secrets.
- Rule write API is protected by `rule:edit`.
- Rule sync upserts rules by id and preserves existing non-manifest rules.
- If destructive replace exists, it deletes non-manifest rules only when an
  explicit replace mode/route is used.
- Catalog write API is protected by the permission selected in SEI-0.
- Read routes under `/api/v1/catalog/**`, `/api/v1/projects/**`, and
  `/api/v1/admin/rules` reflect the initialized state.
- Startup seed remains optional and is not required for the proof.

Verification:

```bash
./mvnw.cmd -pl xa-mass-server -am "-Dtest=CatalogControllerTest,ProjectApiControllerTest,RuleApiControllerTest,ApiAuthInterceptorTest,ServerDurableLocalProfileContextTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

If the exact test classes change during implementation, update this roadmap
with the actual proof names rather than relying on `failIfNoSpecifiedTests`.

## SEI-2 ScenarioEnvironmentInitializerMain

Goal:

Replace credential-only bootstrap behavior with a real integration-local
environment initializer.

Scope:

- Introduce `ScenarioEnvironmentInitializerMain` as the active initializer
  entry. `ScenarioCredentialBootstrapMain` may remain only as explicit residue
  until SEI-5 if deleting it in this slice would obscure caller movement.
- The initializer reads scenario catalog/rules manifests from
  `integrations/xa-mass-scenario-launcher/examples/`; manifest location and
  names are SEI-0/SEI-1 prerequisites, not SEI-3 documentation work.
- The initializer config contract includes `server.baseUrl`,
  `environment.catalogManifest`, `environment.rulesManifest`,
  `credentials.taskApiKeyFile`, `credentials.workerApiKeyFile`, operator auth
  source, relative path resolution, and CLI/env/config precedence.
- The initializer calls `/api/v1/auth/config` before authenticating.
- In session mode, the initializer logs in as an operator, carries cookie and
  CSRF state, calls the catalog/rule write APIs from SEI-1, then prepares task
  and worker API-key cache files through `/api/v1/api-keys`.
- In dev-header mode, the initializer may proceed only when explicitly running
  local/test harness mode; it sends the required operator headers instead of
  calling `/api/v1/auth/login`.
- Cache files must be validated through `/api/v1/api-keys:current`; stale cache
  files are refreshed when configured.
- The initializer must fail clearly when the operator lacks catalog/rule/API-key
  permissions.
- The initializer must not start workers or create tasks.
- Provide a test-harness callable entry point or shared initializer component so
  memory-profile integration tests can run the same external API initialization
  automatically after the server is listening.

Acceptance:

- Running the initializer against a clean `durable-local` server makes
  `crawler.fetch-page`, `stock.quote.fetch`, `crawlerApp`, scenario rules, task
  API key, and worker API key available without server restart.
- Running the initializer against a memory-local test server uses the explicit
  dev-header/test auth path or a session override and does not fail because
  `/api/v1/auth/login` is unavailable.
- The old startup-seed instruction is removed from initializer failure messages.
- Memory-profile integration tests can initialize scenario catalog/rules/keys
  by invoking the initializer after server startup, without enabling server
  startup scenario seed.
- Java SDK architecture guard allowlists the renamed integration initializer as
  the route-literal owner and no longer allowlists the old class.
- The initializer does not print raw API-key secrets after writing cache files.

Verification:

```bash
./mvnw.cmd -pl integrations/xa-mass-scenario-launcher -am "-Dtest=ScenarioEnvironmentInitializerMainTest,ScenarioLauncherOptionsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
./mvnw.cmd -pl sdk/xa-mass-java-sdk -am "-Dtest=JavaExternalSdkArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## SEI-3 Three-Process Local Run Contract

Goal:

Make the intended human/local run sequence explicit and runnable.

Scope:

- Update `integrations/xa-mass-scenario-launcher/README.md` to show exactly
  three process roles:
  1. `ScenarioEnvironmentInitializerMain`
  2. `ScenarioWorkerLauncherMain`
  3. `ScenarioTaskLauncherMain`
- Update `integrations/README.md`, `sdk/README.md`, and
  `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` if route ownership or integration
  boundary text changes.
- Keep `durable-local` startup docs clean: start server, then initialize env;
  do not require scenario catalog/rules startup seed flags.
- Document the manifest names and config fields selected in SEI-0.

Acceptance:

- A new reader can tell that clean `durable-local` is not scenario-ready until
  the initializer runs.
- The worker launcher reads the worker API-key cache prepared by the
  initializer.
- The task launcher reads the task API-key cache prepared by the initializer.
- No active README presents startup seed as the preferred scenario preparation
  path.
- Any remaining seed path is labeled explicit fixture/support only.
- Scenario JSON manifests are documented as initializer input, not startup seed.
- Config precedence and relative path resolution are documented for initializer
  catalog/rules manifests and task/worker key cache files.

Verification:

```bash
rg -n "ScenarioCredentialBootstrapMain|credential-bootstrap|seed.catalog-location|seed.rules-location|ScenarioEnvironmentInitializerMain|ScenarioWorkerLauncherMain|ScenarioTaskLauncherMain" integrations sdk doc xa-mass-server roadmap -g "*.md" -g "*.java"
```

## SEI-4 End-To-End Scenario Proof

Goal:

Prove the clean-server to initialized-env to worker/task path.

Scope:

- Add or update an integration proof that starts from clean durable catalog
  state, applies external environment initialization, starts/registers worker
  topology, and submits a task.
- The proof must not call `ControlPlaneSeedImporter` for scenario catalog/rules.
- The proof may use test harness support to run server context and initializer,
  but the initializer calls must go through HTTP APIs.
- Memory-profile tests may automatically call the initializer as setup after
  the server is listening; this is test harness automation, not server startup
  behavior.
- Worker registration remains runtime/external truth. Do not assert that worker
  registration is restored from DB after server restart.

Acceptance:

- Clean durable-local startup exposes no scenario project/event/rules until
  initializer runs.
- Clean memory-profile test startup exposes no scenario project/event/rules
  until the test harness invokes the initializer.
- Initializer creates catalog/rules and both API-key cache files.
- Worker launcher registers the scenario worker group and starts a session.
- Task launcher creates a task and appends scenario items with the task API key.
- Task execution can complete through the externally registered worker.
- The proof reports exact failure class when catalog/rule/API-key/worker/task
  steps fail.

Verification:

```bash
./mvnw.cmd -pl xa-mass-server,integrations/xa-mass-scenario-launcher -am "-Dtest=ScenarioExternalEnvironmentInitializationIntegrationTest,JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

If `ScenarioExternalEnvironmentInitializationIntegrationTest` does not exist at
implementation time, it must be added in this slice or the verification command
must be updated to the real proof class.

## SEI-5 Residue Removal And Guards

Goal:

Remove the old seed-first scenario path from active truth.

Scope:

- Remove or relocate `control-plane-seed/control-console-scenario.json` if it is
  no longer used by current tests.
- Remove the old `ScenarioCredentialBootstrapMain` class name and jar assembly
  after all callers move.
- Add/extend guards that prevent active scenario docs from requiring startup
  seed for catalog/rules.
- Ensure server sample/bootstrap routes are not reintroduced.
- Update archive or roadmap status only after residue scan passes.

Acceptance:

- Active docs and launchers use `ScenarioEnvironmentInitializerMain`.
- Server main resources do not carry scenario catalog/rules as the normal local
  path.
- Scenario JSON manifests may remain under scenario-launcher examples; active
  docs must not call them server startup seed.
- `durable-local` remains clean-start plus operator credential.
- Java SDK still has no operator login or credential lifecycle helpers.
- Any remaining startup seed fixture is test/support scoped and named as such.

Verification:

```bash
rg -n "ScenarioCredentialBootstrapMain|xa-mass-scenario-credential-bootstrap|control-console-scenario|sample-api/bootstrap|seed.catalog-location|seed.rules-location" . -g "*.java" -g "*.md" -g "*.xml" -g "*.yml" -g "*.json" --glob "!doc/archive/**" --glob "!**/target/**"
./mvnw.cmd -pl xa-mass-server,integrations/xa-mass-scenario-launcher,sdk/xa-mass-java-sdk -am test
git diff --check
```

## Completion Criteria

The roadmap can be marked complete only when all of these are true:

- A clean `durable-local` server starts without scenario catalog/rules/task/
  worker state.
- A real operator API path can initialize scenario catalog and rules after
  server startup.
- Scenario JSON files are retained only as initializer manifests or explicit
  fixtures; the normal path submits them through operator APIs.
- `ScenarioEnvironmentInitializerMain` initializes catalog/rules plus task and
  worker API-key cache files without server restart.
- Memory-profile integration tests initialize scenario state by invoking the
  same external initializer after server startup.
- `ScenarioWorkerLauncherMain` and `ScenarioTaskLauncherMain` run as separate
  external process roles using initializer output.
- Scenario worker registration and task submission are proven through external
  HTTP/SDK calls, not embedded app calls or startup seed.
- Active docs no longer present startup scenario seed as the preferred local
  path.
- Guards prevent Java SDK credential-management creep and seed-first scenario
  residue from returning.

## Related Roadmaps

- Current local schema reset and API-key cache readiness are documented by
  `xa-mass-server/README.md`, `tools/xa-mass-admin-cli/README.md`, and the
  platform confidence smoke. This superseded roadmap must not revive
  seed-first scenario readiness residue.
- `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` should use this roadmap's final
  three-process setup as the environment prerequisite for contract health.
- `integrations/xa-mass-scenario-launcher/README.md` documents current human
  task config shape. Worker config and environment initialization remain
  separate ownership decisions.
