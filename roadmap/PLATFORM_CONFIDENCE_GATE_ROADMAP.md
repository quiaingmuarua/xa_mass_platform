# Platform Confidence Gate Roadmap

Status: implemented mainline confidence gate; keep as a completion record until
post-CI residue review decides whether to archive it.

Current implementation baseline:

- `tools/xa-mass-admin-cli` exists and owns module-local operator/admin HTTP.
- `xa-mass-admin env verify/init --config <file>` exists and is the preferred
  environment preparation path.
- `ScenarioCredentialBootstrapMain` is transitional residue only.
- The real-process confidence lane exists under
  `xa-mass-testing/scripts/run-platform-confidence-smoke.sh`.

## Summary

The project currently has many focused tests and several roadmap lines, but it
still allows an unacceptable failure mode: CI can pass while a packaged server,
operator auth, scenario environment initialization, Java SDK task producer, or
Java SDK worker launcher fails in a real process.

This roadmap builds a product-level confidence gate:

```text
package server jar
  -> start real server process
  -> wait for health
  -> run server-owned admin CLI over real HTTP
  -> prepare catalog/rules/API keys
  -> run Java SDK worker launcher
  -> run Java SDK task launcher
  -> run operator task APPROVE through admin CLI
  -> observe at least one visible result
  -> collect categorized logs on failure
```

The next implementation slice intentionally does not create a shared HTTP
client core. `xa-mass-admin-cli` is an internal/operator tooling surface, not a
public SDK promise. Its HTTP helper stays module-local and narrow until real
duplication justifies extraction.

## Current Code Observations

- `sdk/xa-mass-java-sdk` currently owns `MassPlatform`, task clients, worker
  clients, worker sessions, handler runtime, and its own `MassHttpClient`.
- `sdk/xa-mass-java-sdk` already depends on `sdk/xa-mass-public-contract`.
- `sdk/README.md` explicitly forbids operator login, CSRF, API-key lifecycle,
  seed/import, approval, rotation, or lifecycle-management APIs in
  `xa-mass-java-sdk`.
- `sdk/xa-mass-public-contract` currently owns narrow public task HTTP wire
  DTOs/constants. It does not yet own operator/admin/catalog/rule DTOs unless
  they are explicitly added with Controller ownership.
- `integrations/xa-mass-scenario-launcher` currently provides:
  - `ScenarioCredentialBootstrapMain`
  - `ScenarioTaskLauncherMain`
  - `ScenarioWorkerLauncherMain`
- `ScenarioCredentialBootstrapMain` already calls real server HTTP routes for
  `/api/v1/auth/login`, `/api/v1/api-keys:current`, and API-key creation, but
  it is no longer the preferred environment initializer.
- `ScenarioTaskLauncherMain` and `ScenarioWorkerLauncherMain` use
  `MassPlatform` / `xa-mass-java-sdk` for external task and worker behavior.
- `tools/xa-mass-admin-cli` now provides:
  - `health`
  - `auth config`
  - `auth login`
  - `api-key current`
  - `task command`
  - `env verify`
  - `env init`
- `tools/xa-mass-admin-cli/examples/admin-env.local.json` is the first small
  confidence config. It uses a one-worker fixture and writes secrets/marker
  under gitignored example-local paths.
- `xa-mass-server` has memory-local and durable-local profile context tests,
  but the current CI shape does not guarantee a packaged server process plus
  initializer plus task/worker launchers all work together.
- `application-memory-local.yml` currently uses `operator.mode=dev-header`.
  That is not acceptable for the confidence lane. The memory-local confidence
  command must run with explicit session auth and fixture-header disabled, or a
  dedicated no-bypass memory profile must be added before PCG-3.
- `application-durable-local.yml` already uses session auth and seeds only the
  minimal operator credential by default.
- Existing direction docs already separate:
  - scenario environment initialization,
  - server API contract health,
  - SDK/integrations boundary guard.
- `ADMIN_ENV_INIT_STATE_MODEL_ROADMAP.md` owns the detailed `xa-mass-admin
  env init` config model, memory/file marker semantics, and verify/apply mode
  decisions. This confidence roadmap consumes that result instead of redefining
  env-init state behavior.
- `SCENARIO_ENVIRONMENT_EXTERNAL_INITIALIZATION_ROADMAP.md` names a
  `ScenarioEnvironmentInitializerMain` under scenario launcher. This roadmap
  supersedes that initializer owner decision: environment initialization moves
  to `tools/xa-mass-admin-cli env init`; scenario launcher stays task/worker
  process tooling.

## Owner Review

- `xa-mass-server` owns HTTP behavior, operator auth/session/CSRF,
  permissions, API-key lifecycle, catalog/rule APIs, startup profiles, and
  product API documentation.
- `sdk/xa-mass-public-contract` owns shared Controller-exposed wire DTOs and
  constants when the owning route and consumer need are recorded.
- `sdk/xa-mass-java-sdk` owns ordinary API-key-authenticated external actor
  flows: task producer, worker registration, worker sessions, result/report
  submission, and typed read APIs.
- `tools/xa-mass-admin-cli` should own operator/admin automation over real
  server HTTP: login, CSRF, catalog/rule import, API-key lifecycle, and
  environment verification. Its first HTTP helper is module-local.
- `integrations/xa-mass-scenario-launcher` owns scenario task/worker process
  launchers. It should consume prepared credentials and manifests, not own the
  long-term operator/admin client or environment initializer.
- CI/smoke scripts own process orchestration and log collection only. They must
  not become handwritten HTTP business clients.

## Boundary Decision

There are two product client roles plus one module-local technical helper:

```text
MassPlatform Java SDK
  -> API-key-authenticated external task/worker actor
  -> no operator login or API-key lifecycle

Admin CLI
  -> operator/admin environment preparation over real HTTP
  -> login/session/CSRF, catalog/rules import, API-key lifecycle, env verify

Admin CLI local HTTP helper
  -> base URL, JSON, response envelope, timeout, cookie/CSRF handling
  -> private to tools/xa-mass-admin-cli in the first slice
```

Confidence proof composes these roles:

```text
script
  -> start packaged server
  -> wait health
  -> admin CLI env init
  -> scenario worker launcher
  -> scenario task launcher
  -> admin CLI operator task command
  -> SDK result verify
  -> stop server and collect logs
```

## Hard Rules

1. Do not add operator login, CSRF, API-key lifecycle, seed/import, or
   environment initialization to `xa-mass-java-sdk`.
2. Do not make `tools/xa-mass-admin-cli` depend on `xa-mass-java-sdk`.
3. Do not introduce `sdk/xa-mass-http-client-core` in this roadmap unless a
   later implementation checkpoint proves duplicated code and gets a new owner
   decision. First-slice admin HTTP code stays private to the admin CLI module.
4. Do not make confidence scripts issue business HTTP requests directly except
   health checks and log collection endpoints.
5. Do not use server internals, embedded SDK, direct service calls, or DB writes
   for admin CLI behavior. Admin CLI must use real HTTP.
6. Do not restore startup scenario seed as the mainline confidence path.
   Startup may provide minimal operator credential only.
7. Do not treat `memory-local` as a toy profile. It is a valid process/runtime
   shape and must pass the same external task/worker confidence flow.
8. The confidence lane must not use `dev-header` operator auth. If the existing
   `memory-local` profile remains dev-header for console convenience, the
   confidence smoke must override it to session auth or use a dedicated
   no-bypass memory profile.
9. Do not treat `durable-local` as proven by context load alone. It must pass a
   real packaged-process confidence flow with Redis/SQLite.
10. Failure output must categorize owner: server-startup, health, operator-auth,
   admin-env-init, API-key, catalog-rule, task-launcher, worker-launcher,
   scheduling-result, or cleanup.
11. Raw API-key secrets must not be printed in logs. Cache file paths and
    principal IDs are acceptable.
12. Any profile/startup/auth/config changes must include startup or real
    process proof, not only constructor tests.
13. `xa-mass-public-contract` may be used by both SDK and admin CLI only for
    recorded Controller-exposed wire DTOs/constants.
14. Do not implement two environment initializers. `ScenarioCredentialBootstrapMain`
    and any planned `ScenarioEnvironmentInitializerMain` are residue once
    `tools/xa-mass-admin-cli env init` exists.

## Non-Goals

- No full OpenAPI generated client.
- No broad CLI framework or interactive terminal UI in the first pass.
- No production secret-manager integration.
- No frontend confidence gate in the first pass.
- No Redis cluster/distributed soak in this roadmap.
- No historical DB migration compatibility. Current pre-release schema-reset
  rules remain.
- No scenario worker config redesign unless required to run the confidence
  gate.
- No replacement of existing focused engine/server tests.

## Do Not Start With

Do not start by adding another large E2E that embeds HTTP requests directly in a
test class or shell script. That would make the test pass once while increasing
future route-change cost.

Do not start by recreating the admin CLI skeleton or copying
`ScenarioCredentialBootstrapMain`. The admin CLI and env-init model already
exist; the next slice must consume them through a real packaged-process smoke.

## PCG-0 Current Baseline Refresh And Gate Contract

Goal: refresh the first executable confidence lane against current code and
close open owner questions without changing behavior.

Scope:

- Inventory current process entries and confirm which are mainline versus
  residue:
  - server jar startup command
  - `tools/xa-mass-admin-cli env init`
  - `ScenarioCredentialBootstrapMain`
  - any planned or existing `ScenarioEnvironmentInitializerMain`
  - `ScenarioTaskLauncherMain`
  - `ScenarioWorkerLauncherMain`
- Inventory current HTTP client helpers in `xa-mass-java-sdk`, scenario
  launcher, and the existing admin CLI local helper boundary.
- Inventory current scenario manifests and credential cache files.
- Inventory current catalog/rule operator write API status:
  - route shape
  - permission
  - durable store/projection behavior
  - whether rule sync is upsert-only or destructive replace
- Decide first confidence scenario size:
  - one project
  - one event
  - one worker group
  - one polling worker
  - one task
  - small item count
- Decide the memory-local no-bypass startup shape:
  - either explicit command-line overrides for session auth, or
  - a dedicated memory-local confidence profile
  - no `dev-header` in the confidence lane
  - operator credential seed/import source is explicit
  - fixture-header auth is disabled
  - admin CLI `auth login` succeeds before `env init`
- Decide worker launcher orchestration:
  - background process start
  - PID capture
  - ready signal or bounded log/probe wait
  - timeout
  - cleanup on success and failure
- Decide active roadmap ownership:
  - mark `SCENARIO_ENVIRONMENT_EXTERNAL_INITIALIZATION_ROADMAP.md` as
    superseded by PCG/admin CLI, or archive it before implementation starts
- Decide exact output files:
  - server log
  - admin CLI log
  - task launcher log
  - worker launcher log
  - machine-readable summary
- Decide first CI placement and whether it is required on every PR or scheduled
  until stabilized.

Acceptance:

- Inventory names the exact commands and artifacts for the first confidence
  lane.
- Inventory names which existing scenario manifests are used as input.
- Inventory records the owner category taxonomy for failures.
- Inventory records whether catalog/rule write APIs already exist or must be
  implemented before PCG-2 env init.
- Inventory records whether PCG-1 and PCG-2B are already satisfied by
  `tools/xa-mass-admin-cli`.
- Inventory records the exact session-auth startup command for memory-local
  confidence proof.
- Inventory records the exact operator credential seed/import source used by
  the memory-local confidence command.
- Inventory records that worker launcher orchestration includes PID capture,
  readiness wait, timeout, log capture, and cleanup.
- Inventory records that PCG supersedes the SEI initializer owner; scenario
  launcher keeps only task/worker process roles.
- Active roadmap index or SEI roadmap status no longer presents
  `ScenarioEnvironmentInitializerMain` as a valid future owner.
- No implementation relies on script-level business HTTP calls.
- No behavior changes are required in this slice.

Verification:

```bash
rg -n "MassHttpClient|HttpClient|ScenarioCredentialBootstrapMain|ScenarioEnvironmentInitializerMain|ScenarioTaskLauncherMain|ScenarioWorkerLauncherMain" sdk integrations xa-mass-server roadmap -g "*.java" -g "*.md"
rg -n "memory-local|durable-local|api-keys:current|auth/login|catalog|rules|scenario" xa-mass-server integrations roadmap doc -g "*.java" -g "*.yml" -g "*.md"
```

## PCG-1 Admin CLI Baseline Confirmation

Goal: confirm the existing server-owned admin CLI baseline over real HTTP
without adding a shared SDK HTTP core.

Target module:

```text
tools/xa-mass-admin-cli
```

Allowed responsibilities for the module-local HTTP helper:

- base URL normalization
- request builder helpers
- default headers
- JSON encode/decode
- response envelope parsing
- HTTP status and API error mapping
- timeout configuration
- cookie jar handling for operator session
- CSRF header handling for unsafe operator requests

Forbidden responsibilities:

- public SDK API
- reusable cross-module HTTP client core
- task/worker actor client
- retry/business policy
- worker session behavior
- scenario launcher behavior

Scope:

- Do not create a second admin CLI module.
- Confirm `tools/xa-mass-admin-cli` is in the root reactor.
- Confirm it does not depend on:
  - `xa-mass-java-sdk`
  - `xa-mass-server`
  - `xa-mass-engine`
  - embedded SDK
  - platform runtime/storage implementations
- Confirm first commands exist:
  - `health`
  - `auth config`
  - `auth login`
  - `api-key current`
  - `env verify`
  - `env init`
- Confirm session cookie and CSRF capture from real `/api/v1/auth/login`.
- Confirm command output redacts secrets.
- Add only missing guards/tests discovered by PCG-0.

Acceptance:

- Admin CLI can log in against a real server in session auth mode.
- Admin CLI discovers auth mode through `/api/v1/auth/config` before login.
- Admin CLI can validate an API key through `/api/v1/api-keys:current`.
- Admin CLI exposes `env verify/init` as the preferred environment
  preparation path.
- Admin CLI does not expose task/worker actor behavior.
- Source guard proves admin CLI does not depend on `xa-mass-java-sdk`.
- Source guard proves `xa-mass-java-sdk` still has no operator/admin methods.
- No new `sdk/xa-mass-http-client-core` module exists after this slice.

Verification:

```bash
./mvnw -q -pl tools/xa-mass-admin-cli -am test
rg -n "xa-mass-java-sdk|MassPlatform|TaskClient|WorkerClient" tools/xa-mass-admin-cli pom.xml
rg -n "auth/login|api-keys" sdk/xa-mass-java-sdk/src/main/java
```

The absence of `sdk/xa-mass-http-client-core` should be guarded by a
cross-platform source test, not a shell-only assertion.

## PCG-2A Catalog/Rule Operator Write API Confirmation

Goal: confirm or complete the server APIs that make the server externally
initializable without startup scenario seed.

Scope:

- Implement this slice only if PCG-0 finds the required operator write APIs are
  missing or incomplete.
- Add or complete minimal operator HTTP routes for:
  - catalog/project/event upsert or import
  - rule sync/upsert
- Use real operator authorization and CSRF.
- Keep semantics narrow:
  - default rule sync is upsert-only
  - destructive replace, if retained, requires an explicit route/mode and proof
  - no task or worker runtime truth is written
- Update catalog/rule stores and live projections consistently.
- Add route/auth tests for missing credential, missing CSRF, missing
  permission, positive import/upsert, and durable readback where applicable.

Acceptance:

- A clean running server can accept catalog/rule initialization through
  operator HTTP APIs after startup.
- Startup scenario seed is not required for scenario catalog/rules.
- Route/auth failures are explicit and do not fall back to fixture headers
  unless the profile explicitly allows fixture-header auth.
- The API writes only control-plane catalog/rule truth, not task/worker runtime
  truth.
- If these APIs already exist, this slice records the existing routes and
  tests instead of duplicating them.

Verification:

```bash
./mvnw -q -pl xa-mass-server -am "-Dtest=CatalogControllerTest,RuleApiControllerTest,ControlPlaneInitializationControllerTest,ApiAuthInterceptorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "PostMapping|PutMapping|PatchMapping|catalog|rules|rule:edit|catalog:edit" xa-mass-server/src/main/java xa-mass-server/src/test/java -g "*.java"
```

## PCG-2B Admin Env Init Baseline Confirmation

Goal: confirm scenario environment preparation is already owned by admin CLI.

Dependencies:

- `ADMIN_ENV_INIT_STATE_MODEL_ROADMAP.md` has defined the typed config model,
  marker semantics, and verify/apply mode behavior.
- PCG-2A catalog/rule operator write APIs are implemented or confirmed
  available.
- PCG-1 admin CLI can perform auth config, session login, CSRF handling, and
  API-key current validation.

Scope:

- Consume the existing `env init --config <file>` implementation according to
  `ADMIN_ENV_INIT_STATE_MODEL_ROADMAP.md`.
- Do not reimplement env init in scenario launcher or scripts.
- Confirm the typed config model is used rather than ad hoc JSON
  interpretation.
- Confirm `env init` uses real HTTP:
  - auth config
  - operator login
  - catalog import/upsert
  - rule sync/upsert
  - API-key create/verify
- Keep raw secret output limited to gitignored cache files.
- Keep scenario launchers consuming the prepared API keys.
- Do not start workers or create tasks in `env init`.
- Add only missing diagnostics/guards discovered by PCG-0.

Acceptance:

- Clean memory-local server can be made scenario-ready by admin CLI after
  startup under session auth, not dev-header auth.
- Clean durable-local server can be made scenario-ready by admin CLI after
  startup.
- `env init` writes or verifies its marker according to the separate env-init
  state model roadmap.
- Scenario launchers no longer need `ScenarioCredentialBootstrapMain` for the
  preferred path.
- `ScenarioCredentialBootstrapMain` and any planned
  `ScenarioEnvironmentInitializerMain` do not remain as independent long-term
  initializer owners. They are removed, archived, or temporarily delegated to
  admin CLI with a bounded residue note.
- Failure output identifies which init step failed.

Verification:

```bash
./mvnw -q -pl tools/xa-mass-admin-cli,integrations/xa-mass-scenario-launcher -am test
rg -n "ScenarioCredentialBootstrapMain|ScenarioEnvironmentInitializerMain|credential-bootstrap|sample-api/bootstrap|seed.catalog-location|seed.rules-location" integrations sdk xa-mass-server roadmap doc -g "*.java" -g "*.md" -g "*.xml" -g "*.yml"
```

## PCG-3 Memory-Local No-Bypass Process Confidence Smoke

Goal: prove the packaged memory-local product path works through real
processes.

Scope:

- Add a script under `xa-mass-testing/scripts/` or another agreed test tooling
  location, for example:

```text
xa-mass-testing/scripts/run-platform-confidence-smoke.sh
```

- Script responsibilities only:
  - package server/admin/scenario artifacts
  - start server jar with `memory-local` plus explicit no-bypass operator auth
    overrides, or with a dedicated no-bypass memory confidence profile
  - ensure minimal operator credential seed/import is enabled and points to the
    intended credential source
  - ensure fixture-header auth is disabled
  - wait for `/actuator/health`
  - run admin CLI `auth config` and `auth login` before `env init`
  - invoke admin CLI `env init`
  - start scenario worker launcher as a background process
  - capture worker launcher PID
  - wait for worker registration/session readiness through a bounded ready
    signal, log marker, or SDK/admin verification command
  - invoke scenario task launcher
  - invoke admin CLI `task command --command APPROVE` for the created task
  - wait for visible task result through SDK/admin verification command
  - stop processes
  - collect logs
- Keep business HTTP inside admin CLI or Java SDK launchers.
- Use `tools/xa-mass-admin-cli/examples/admin-env.local.json` or an equivalent
  small confidence config. Do not use the broad sample worker fixture as the
  first confidence lane.
- Ensure the operator password is provided through environment, not logs.

Acceptance:

- A real packaged server process starts in `memory-local`.
- `/api/v1/auth/config` reports session auth, not dev-header.
- Operator credential readiness is explicit: admin CLI `auth login` succeeds
  before `env init`.
- The memory-local confidence command disables fixture-header auth.
- Admin CLI prepares catalog/rules/API keys through real HTTP.
- Scenario worker launcher registers and runs one worker.
- Worker launcher is backgrounded with PID capture, bounded readiness wait,
  timeout, log capture, and guaranteed cleanup.
- Scenario task launcher creates and appends work.
- At least one task reaches visible success result.
- Failure artifacts include categorized logs.

Verification:

```bash
xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile memory-local
```

## PCG-4 Durable-Local Process Confidence Smoke

Goal: prove the same confidence path works with durable-local runtime
infrastructure.

Scope:

- Start required Redis service in local/CI environment.
- Use a temporary SQLite path.
- Start packaged server jar with `durable-local`.
- Reuse the same admin CLI env init and scenario task/worker launchers.
- Assert no silent fallback to memory runtime/storage.
- Capture Redis/SQLite/server logs or diagnostics sufficient for startup
  failures.

Acceptance:

- Durable-local packaged server starts from a clean SQLite path.
- Runtime mode is Redis and storage mode is SQLite/JDBC according to profile
  validation.
- Admin CLI env init works after startup.
- Same minimal task/worker scenario reaches visible result.
- Failure category distinguishes Redis startup, SQLite/schema, server startup,
  admin init, task launcher, worker launcher, and scheduling/result.

Verification:

```bash
xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile durable-local
```

## PCG-5 CI Gate Integration

Goal: make the confidence lane visible in CI without hiding failures behind a
generic E2E status.

Scope:

- Add a GitHub Actions job for the memory-local process smoke.
- Add durable-local process smoke either:
  - in the same required job after stabilization, or
  - as scheduled/manual first, then promote to required.
- Upload artifacts:
  - server log
  - admin CLI log
  - task launcher log
  - worker launcher log
  - summary JSON/markdown
- Ensure the job fails when no test/scenario actually ran.

Acceptance:

- CI failure names the failed owner category.
- Logs are available without rerunning locally.
- The smoke uses packaged artifacts, not test-only Spring context.
- The smoke does not duplicate HTTP route logic in shell.
- The CI matrix clearly distinguishes memory-local and durable-local.

Verification:

```bash
rg -n "platform-confidence|run-platform-confidence-smoke|xa-mass-admin-cli" .github xa-mass-testing roadmap -g "*"
```

## PCG-6 Residue Removal

Goal: remove old confidence-bypassing paths after the real gate exists.

Scope:

- Remove or archive old docs that present startup scenario seed as the
  preferred path.
- Remove or demote `ScenarioCredentialBootstrapMain` if admin CLI owns env init.
- Remove or demote any `ScenarioEnvironmentInitializerMain` residue if present.
- Remove shell/test naked HTTP initialization that duplicates admin CLI.
- Update:
  - `sdk/README.md`
  - `integrations/README.md`
  - `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`
  - `xa-mass-testing/VERIFIED_RUNBOOK.md`
  - scenario launcher README
- Add guards against reintroducing:
  - admin methods in `MassPlatform`
  - premature shared `xa-mass-http-client-core` without an owner decision
  - direct server/internal dependencies in admin CLI
  - confidence scripts that embed catalog/rule/API-key HTTP calls

Acceptance:

- There is one preferred environment initialization path for confidence runs:
  admin CLI over real HTTP.
- Scenario task/worker launchers remain external actor processes.
- Java SDK remains API-key external actor SDK.
- Old seed-first or bootstrap-first docs are archived or explicitly labeled
  support/fixture only.
- Active docs explain how to run the confidence gate locally.

Verification:

```bash
rg -n "ScenarioCredentialBootstrapMain|ScenarioEnvironmentInitializerMain|credential-bootstrap|sample-api/bootstrap|startup seed|seed-first|MassPlatform.*login|api-key lifecycle" sdk integrations xa-mass-testing doc roadmap -g "*.java" -g "*.md" -g "*.sh" -g "*.xml" -g "*.yml"
git diff --check
```

## Test Strategy

Contract/support tests:

- Admin CLI module-local request/response/error/cookie/CSRF helper.
- Java SDK typed client behavior unchanged.
- Admin CLI command parsing and redaction.
- Admin CLI route/auth negative cases where cheap.

Process proof:

- Memory-local packaged server confidence smoke with session auth/no dev-header
  bypass.
- Durable-local packaged server confidence smoke.

Guards:

- No shared `xa-mass-http-client-core` module is introduced by this roadmap.
- `xa-mass-java-sdk` has no operator login/API-key lifecycle/admin init.
- `tools/xa-mass-admin-cli` has no dependency on `xa-mass-java-sdk`.
- Confidence scripts do not carry catalog/rule/API-key HTTP request bodies.

Failure classification:

- server-startup
- health
- operator-auth
- admin-env-init
- catalog-rule
- api-key
- task-launcher
- worker-launcher
- scheduling-result
- cleanup

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Admin CLI local HTTP helper grows into hidden SDK | Route/business semantics become reusable by accident | Keep helper package-private/module-local and add no exported dependency |
| Premature HTTP core extraction adds complexity | Shared core becomes a fake abstraction before duplication is proven | Guard against `sdk/xa-mass-http-client-core` in this roadmap |
| Admin CLI duplicates Java SDK task/worker behavior | Two external actor paths diverge | Admin CLI owns env/admin only; scenario launchers use Java SDK |
| Confidence script becomes a hidden HTTP client | Route changes become expensive and invisible | Script may only orchestrate processes and call CLI/launchers |
| Durable smoke is flaky before infrastructure stabilizes | CI noise | Start as scheduled/manual if needed, but memory smoke should become required first |
| Startup seed returns through convenience pressure | Real auth/control-plane path remains unproven | Guard active docs and scripts against seed-first scenario readiness |
| Raw API-key secrets leak in logs | Credential exposure | Redaction in admin CLI and artifact checks |
| Memory-local keeps using dev-header in confidence runs | Green smoke still misses real operator auth failures | First smoke must assert `/api/v1/auth/config` reports session auth |

## Final Target

The platform has a credible confidence gate:

```text
Java SDK external actor path
  -> admin CLI environment path with module-local HTTP helper
  -> packaged server process smoke
  -> memory-local and durable-local mainline proof
```

After this roadmap, a green CI should mean more than "units passed": it should
prove a clean server can become scenario-ready through real operator/admin
HTTP, then execute a task/worker flow through the same Java SDK path external
users will run.
