# Local Readiness And API Health Gate Roadmap

Status: archived on 2026-06-09 after mainline readiness and worker-read performance proof were implemented for memory-local and durable-local.

## Summary

The platform is moving toward product-ready server + SDK first operation. The
next local proof must be repeatable without browser inspection:

1. Start a clean local server.
2. Initialize scenario-ready control-plane state through real server APIs.
3. Prove operator login, task API-key, and worker API-key paths work.
4. Run external task and worker actors through SDK/integrations.
5. Measure selected repeatable local read routes with explicit worker fixture
   scale. A 1-worker platform confidence run remains capability evidence only;
   worker-read performance evidence must come from bounded fanout proof and
   packaged worker-read health.

This roadmap is the local readiness prerequisite for
`SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md`. API contract health should assume
the environment can be initialized and measured by command, not by manual
browser inspection.

## Current Code Observations

- `tools/xa-mass-admin-cli` performs operator auth config discovery, session
  login, CSRF handling, `/api/v1/api-keys:current`, API-key create,
  catalog/rule sync, `env init`, `env verify`, and `api health`. It is the
  canonical local environment initializer and route-health runner for readiness
  and confidence gates.
- `tools/xa-mass-admin-cli/examples/admin-env.local.json` already describes
  local desired state for catalog/rules/task key/worker keys.
- `integrations/xa-mass-scenario-launcher` already separates task producer and
  worker process roles.
- The old `ScenarioCredentialBootstrapMain` operator/admin initializer entry
  has been removed from the scenario launcher. Scenario launcher now consumes
  the environment initialized by the admin CLI.
- `xa-mass-testing/scripts/run-platform-confidence-smoke.sh` starts a local
  server, runs admin env init, starts worker launcher, creates task work, sends
  operator approval, verifies result readback, and embeds
  `apiHealth.routeTimings` from admin CLI in `summary.json`.
- The current platform confidence smoke initializes one worker. It proves the
  external Product/API capability path, but it must not be counted as worker
  read performance proof.
- `write-proof-summary.mjs` surfaces `initializerElapsedMs` and structured
  `apiHealth.routeTimings` from platform confidence artifacts, and accepts
  job-scoped platform-confidence run directories so proof summaries can avoid
  stale local target artifacts.
- `xa-mass-testing/scripts/run-worker-read-health-smoke.sh` starts a packaged
  server, initializes 100 API-created workers across 5 groups, marks them
  API-online through the worker launcher, runs `api health`, and emits
  `workerFixture` metadata.
- Server API observability records failure logs and endpoint metrics. The
  readiness lane now additionally measures selected route health directly from
  the outer HTTP caller path.
- Local worker read routes now use `WorkerInspectionOperations.listOnlineWorkerIds()`
  and existing bulk diagnostics for presence/lock/session facts instead of
  per-row transport/admission queries in the server controllers.

## Implementation Snapshot

- Admin CLI is the canonical initializer and route-health runner.
- Scenario launcher no longer packages a credential-bootstrap jar or owns
  operator/admin initialization APIs.
- `run-platform-confidence-smoke.sh` emits `initializerElapsedMs` and
  `apiHealth.routeTimings`.
- `write-proof-summary.mjs --platform-confidence-dir` accepts either the
  platform-confidence parent directory or a direct run directory containing
  `summary.json`.
- `api health` is a hard local gate: selected repeatable read routes fail the
  command when they return an error envelope, missing expected data, or elapsed
  time at/above the 1000 ms local budget for the declared fixture scale.
- Negative raw `curl` checks remain named `authorization-no-bypass-safety`
  exceptions.
- Worker list/capability read models use a bounded online-worker snapshot.
- Worker-read performance evidence is split from the 1-worker platform
  confidence smoke. Bounded fanout tests cover 100+ synthetic workers, and
  packaged worker-read health emits `workerFixture.workerCount >= 100` before
  proof summary counts it as `scale-contention-evidence`.

## Owner Review

- `tools/xa-mass-admin-cli env init` / `env verify` owns local environment
  readiness for a running server. Scenario launcher must not reintroduce
  operator login, CSRF, catalog/rule sync, or API-key lifecycle HTTP logic. The
  initializer must call server APIs and must not write DB files directly.
- `xa-mass-server` owns auth, API-key lifecycle, catalog/rule APIs, endpoint
  metrics, and read-model performance.
- `integrations/xa-mass-scenario-launcher` owns external actor proof through
  `xa-mass-java-sdk`; it consumes initialized credentials and catalog state.
- `xa-mass-testing` owns packaged smoke/confidence orchestration and report
  artifacts.
- `frontend` is a validation surface, not the health gate runner.
- Backup DB / snapshot restore is explicitly out of scope for this roadmap.

## Boundary Decision

Use API-driven local initialization as the mainline path.

```text
clean local server
  -> tools/xa-mass-admin-cli env init through operator APIs
  -> env verify through server APIs
  -> scenario worker launcher
  -> scenario task launcher
  -> operator task approve
  -> SDK result verifier
  -> API health route timing report
  -> API contract health lane
```

The admin CLI is the local initializer owner. Scenario launchers consume the
prepared environment and credentials.

## Hard Rules

1. Do not require browser automation to prove local API health.
2. Do not add operator login or API-key lifecycle behavior to
   `sdk/xa-mass-java-sdk`.
3. Do not reintroduce `ScenarioCredentialBootstrapMain` or another
   scenario-side operator/admin initializer. Admin CLI is canonical.
4. Do not use backup DB, SQLite snapshot restore, or direct DB writes as the
   local readiness mechanism.
5. Do not treat API-key cache files as truth. Validate them through
   `/api/v1/api-keys:current`.
6. Do not accept selected local API routes taking 1s or more as product-ready
   behavior after their owner read models have a bulk/snapshot proof. Before
   that proof lands, timing reports may be warning-only to avoid
   break-now-fix-later slices.
7. Read-only console routes use snapshot/eventual semantics. They must not
   acquire runtime locks to stabilize UI output.
8. Worker list/capability read models must avoid per-row runtime/Redis calls
   when a bulk or snapshot read exists.
9. API health reports must not print raw API-key secrets.
10. If a local route latency gate fails, report route, status, elapsed time,
    response size, route auth policy, and credential used by the health runner.
11. `memory-local` and `durable-local` may differ in infra and default local
    convenience settings, not in the readiness-gate API contract. The LRAH gate
    runs both with session auth and local fixture headers disabled.
12. Route-health timing starts after server startup and environment
    initialization. It does not include JVM boot, Flyway, local build time, or
    initializer work.
13. The first route-health gate checks reachability, success envelope, and
    normal data presence only. Exact DTO field correctness belongs to API
    contract, public-contract, SDK, and frontend adapter tests.
14. Write-route checks must be bounded and scenario-owned. They may create the
    minimal task/worker resources needed for proof, but they must not become a
    repeated polling health check that keeps creating product data.
15. Tests that prove real external HTTP behavior must not scatter bare HTTP
    platform calls across unrelated test classes or scripts. Consolidate
    operator/admin HTTP calls through tools-owned clients or scenario CLI
    commands, with named exceptions only for raw protocol-boundary tests.
16. Negative fail-closed checks may keep raw HTTP/curl only when the purpose is
    the protocol boundary itself, such as missing operator session, invalid
    task API-key, or invalid worker API-key. These checks must be named,
    centralized, and reported as authorization-no-bypass safety proof, not as
    generic route-health implementation.
17. Do not count the 1-worker platform confidence smoke as worker read
    performance proof. It is Product/API capability evidence.
18. Worker read performance evidence must declare fixture scale:
    `workerCount`, `workerGroupCount`, `onlineWorkerCount`, `lockedWorkerCount`,
    `sessionCount`, and whether the workers are synthetic controller fixtures
    or packaged server/API-created workers.
19. Worker read performance proof has two accepted shapes only:
    deterministic bounded fanout proof and packaged worker-read health. Route
    timing without fixture scale is metadata, not proof.

## Non-Goals

- No commercial production SLO definition.
- No frontend productionization.
- No SSE/realtime route implementation.
- No broad pagination redesign in this roadmap.
- No historical DB migration compatibility.
- No direct DB write initializer in the first slice.
- No backup DB / SQLite snapshot restore path.
- No worker config redesign for scenario launcher.
- No exact DTO field-contract assertions in this health gate; those belong to
  contract/SDK/frontend tests.
- No new hand-rolled raw HTTP route clients in generic tests when a
  tools/scenario CLI caller can own the request.

## Do Not Start With

Do not start by optimizing the frontend page or adding pagination. First prove
whether the backend route itself is slow and which read model performs repeated
runtime calls. Frontend changes are only valid after the server route contract
and timing are understood.

## LRAH-0 Inventory And Gate Definition

Goal: define the local readiness and API health gate without changing behavior.

Scope:

- Maintain `LOCAL_READINESS_AND_API_HEALTH_GATE_INVENTORY.md`.
- Classify initialization actors:
  - admin CLI
  - removed legacy `ScenarioCredentialBootstrapMain`
  - scenario task launcher
  - scenario worker launcher
  - confidence smoke script
- Classify local credential artifacts:
  - operator login credential
  - task API-key cache
  - worker API-key cache / workerId-bound key
  - env marker file
- Define first route latency set and local budget.
- Define a route-health manifest with these required fields:
  `method`, `path`, `routeAuthPolicy`, `credentialUsedByHealthRunner`,
  `readOrWrite`, `sourceCommand`, `budgetMs`, `normalDataPresence`, and
  `repeatable`.
- Decide how the health report is emitted:
  - JSON summary under `xa-mass-testing/target/...`
  - console table
  - both

Acceptance:

- Inventory records the initializer owner decision: `tools/xa-mass-admin-cli
  env init` / `env verify` is canonical; scenario-side initializer code is
  removed and must not be reintroduced.
- First route set includes worker read routes that previously showed
  seconds-level latency.
- Route-health manifest includes route auth policy, health-runner credential,
  and repeatability for each selected route, so public/bypass,
  operator-session, task API-key, and worker API-key routes are not collapsed
  into one generic client or misread as the same route auth policy.
- Local route budgets are set to `< 1000 ms` and explicitly marked as local
  readiness gates, not production SLOs.
- Route-health checks are limited to reachability, success status, and normal
  data presence; exact field correctness is out of scope.
- Inventory defines what "normal data presence" means for the first route set.
- Inventory records that the 1s budget applies after server startup and
  environment initialization, not to boot/init time.
- Inventory records the test caller boundary: tools/scenario CLI callers own
  real external HTTP proof; owner-local server tests may still use `MockMvc`.

Verification:

```powershell
rg -n "env init|env verify|api-keys:current|ScenarioCredentialBootstrapMain|run-platform-confidence-smoke" tools integrations xa-mass-testing -g "*.java" -g "*.sh" -g "*.md" -g "*.json"
rg -n "runtime/workers|worker-capabilities|worker-group-capabilities|http.server.requests|SERVER_API_FAILURE" xa-mass-server doc roadmap -g "*.java" -g "*.md" -g "*.yml"
```

## LRAH-1 Initializer Owner Convergence

Goal: make the existing admin CLI initializer the one path humans and tests use
for local scenario readiness.

Scope:

- Treat `tools/xa-mass-admin-cli env init` / `env verify` as the canonical
  initializer. Do not reimplement its auth, CSRF, catalog/rules, API-key, or
  marker logic elsewhere.
- Update docs and examples so scenario users run admin CLI initialization before
  task/worker launchers.
- Update confidence smoke and local runbooks to call the canonical initializer
  directly, not a duplicated operator HTTP client.
- Remove `ScenarioCredentialBootstrapMain` and its package entry so scenario
  launcher cannot become a second operator/admin initializer.
- Ensure `env verify` remains the named failure surface when any
  catalog/rule/key is missing.
- Update owner docs so scenario launcher docs point to the chosen initializer.
- Expose the admin CLI initializer as the preferred test/human command so smoke
  tests do not hand-roll operator login, CSRF, catalog/rules sync, or API-key
  lifecycle HTTP calls.

Acceptance:

- A clean local server with operator credential can become scenario-ready by
  running `tools/xa-mass-admin-cli env init`.
- Re-running the initializer with a matching marker validates state rather than
  blindly trusting the marker.
- A stale task or worker key cache is detected through
  `/api/v1/api-keys:current`.
- Worker credentials are workerId-bound when worker specs require it.
- Scenario task/worker launchers do not call operator initialization APIs.
- No initializer writes the DB directly or restores a backup DB.
- `ScenarioCredentialBootstrapMain` and the
  `xa-mass-scenario-credential-bootstrap` package entry are removed; humans and
  test scripts have one preferred initializer path.
- Existing confidence scripts/tests invoke the initializer command or its
  tools-owned client instead of duplicating raw HTTP setup code.

Verification:

```powershell
./mvnw.cmd -pl tools/xa-mass-admin-cli -am "-Dtest=AdminEnvServiceTest,AdminCliMainTest" test
./mvnw.cmd -pl integrations/xa-mass-scenario-launcher -am "-Dtest=ScenarioLauncherOptionsTest,WorkerScenarioRegistrarTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "ScenarioCredentialBootstrapMain|xa-mass-scenario-credential-bootstrap" integrations/xa-mass-scenario-launcher -g "*.md" -g "*.java" -g "*.xml"
```

## LRAH-2 Confidence Smoke Emits API Health Report

Implementation status: complete.

Goal: make the packaged confidence smoke produce a machine-readable local
readiness + API health report.

Scope:

- Treat existing confidence positive checks as input facts: operator login/env
  init/task approve, task create/read, worker register/submit, and result
  verifier already exist.
- Add `xa-mass-admin-cli api health --config <admin-env.json>` as the first
  route-health runner. It emits JSON and owns route timing HTTP calls.
- Extend `run-platform-confidence-smoke.sh` or its helper scripts to consume
  the admin CLI `api health` output and add:
  - selected route timings
  - route category/auth policy
  - credential used by the health runner
  - route repeatability classification
  - route-health summary status for the API contract health lane
- Record route timings without browser through the admin CLI, not through new
  shell-local platform HTTP request construction.
- Keep existing negative fail-closed raw HTTP checks as named
  authorization-no-bypass safety proof when their purpose is to prove the
  protocol boundary rejects unauthenticated or invalid credentials.
- Include route status, elapsed milliseconds, response bytes, route auth
  policy, credential used by the health runner, and gate mode.
- Treat `< 1000 ms` as the first-pass local route budget for every selected
  repeatable read route, but keep this warning/non-gating until LRAH-3 proves
  the worker read model routes use bulk/snapshot owner APIs.
- Only assert reachability, success status, and normal data presence. Do not
  assert exact DTO field correctness in this lane.
- Measure route timings after the initializer has completed. Exclude startup,
  Flyway, build, and initializer elapsed time from per-route latency.
- Keep write-route checks bounded:
  - task route proof may create one scenario task in the confidence flow
  - worker route proof may register the configured scenario worker topology
  - health timing must not repeatedly create new tasks/workers outside the
    scenario proof flow
  - write-route elapsed/status may be recorded from the scenario flow, but is
    not part of the repeatable read-route latency loop

Acceptance:

- Confidence summary fails if environment initialization fails.
- Confidence summary records warning/non-gating status if any selected
  repeatable read route takes 1000 ms or more before LRAH-3 is complete.
- Confidence summary fails on latency only after LRAH-3 promotes the selected
  repeatable read routes to hard gates.
- Confidence summary fails if a selected route returns non-2xx, empty data when
  data is expected, or an API error envelope.
- Confidence summary records initializer elapsed time separately from route
  latency.
- `xa-mass-admin-cli api health --config <admin-env.json>` exists and emits the
  route timing JSON consumed by confidence smoke.
- Confidence smoke does not duplicate operator/session/API-key HTTP mechanics
  in the shell script when a CLI command owns them.
- Report output points to exact route category instead of only "scenario
  failed".
- Existing authorized positive checks remain the functional proof source;
  LRAH-2 adds `apiHealth` / `routeTimings` instead of reimplementing those
  checks.
- For worker read routes, LRAH-2 route timings are capability/readiness
  metadata unless the artifact also declares a worker fixture scale accepted by
  LRAH-3A/LRAH-3B.
- Proof summary emits `apiHealth.routeTimings` as structured evidence metadata
  instead of leaving route timings only in the raw runner summary.
- `memory-local` is the required fast gate. `durable-local` is required when
  local Redis is available; if Redis is unavailable, the script must skip or
  fail with a named environment category rather than silently passing the
  durable gate.
- Raw secrets are never printed.

Verification:

```powershell
$env:MASS_OPERATOR_PASSWORD='ops-admin'
& 'C:\Program Files\Git\bin\bash.exe' xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile memory-local
# When local Redis is available, durable-local is the mainline persistence/runtime proof.
& 'C:\Program Files\Git\bin\bash.exe' xa-mass-testing/scripts/run-platform-confidence-smoke.sh --profile durable-local
rg -n "api health|apiHealth|routeTimings|elapsedMs|routeAuthPolicy|credentialUsedByHealthRunner|worker-capabilities|worker-group-capabilities|runtime/workers" xa-mass-testing tools -g "*.sh" -g "*.mjs" -g "*.java" -g "*.json"
```

On Windows, this proof uses Git Bash. Do not use the WindowsApps `bash.exe`
WSL launcher for the packaged local smoke because it can mix Linux Java with
Windows-built jars.

## LRAH-3 Local Read API Performance Guard

Implementation status: complete. Bounded snapshot code, bounded fanout tests,
and memory-local packaged worker-read health have landed.

Goal: stop console-read routes from regressing into seconds-level local
latency.

Scope:

- Add focused server tests or support checks that detect repeated per-row
  runtime reads for:
  - `/api/v1/runtime/workers`
  - `/api/v1/catalog/worker-capabilities`
  - `/api/v1/catalog/worker-group-capabilities`
- Inventory the available bulk/snapshot owner APIs before changing
  controllers. If a bulk/snapshot owner does not exist, add that owner API
  first, then retarget the controller.
- Prefer bulk/snapshot runtime reads for:
  - transport online worker ids
  - admission lease-held worker ids
  - connection/session facts
- Rename or clarify ambiguous fields where needed:
  - `locked` means `exclusiveLeaseHeld` / admission lease state, not query lock.
- Update owner docs with read model semantics:
  - eventual snapshot
  - no read locks
  - no strong consistency promise for console rows
- Separate worker-read performance proof from Product/API capability smoke.
  The current 1-worker platform confidence run is not accepted as performance
  proof.

Acceptance:

- The slice records which bulk/snapshot owner APIs exist for online workers,
  admission lease-held workers, and connection/session facts.
- Worker list and worker capability routes do not perform per-row admission
  lock checks after a bulk/snapshot owner API exists.
- Tests prove bulk/snapshot calls are used once per request or bounded by
  route family, not by unbounded row joins.
- Docs clarify that console read models are eventual snapshots.
- Local timing reports for worker read routes do not count as performance proof
  unless the artifact names fixture scale.
- Worker-read performance proof requires both LRAH-3A and LRAH-3B.

## LRAH-3A Worker Read Bounded Fanout Proof

Implementation status: complete.

Goal: prove worker read routes are not doing per-row runtime/admission/session
queries.

Scope:

- Add focused server/controller tests with a synthetic fixture of at least 100
  workers.
- The fixture must include at least 5 worker groups, mixed online/offline
  workers, at least one locked worker, and session/connection facts for a
  subset of workers.
- Cover:
  - `/api/v1/runtime/workers`
  - `/api/v1/catalog/worker-capabilities`
  - `/api/v1/catalog/worker-group-capabilities`
- Assert bulk/snapshot calls are constant for the request:
  - online worker ids: once per request or once per route family
  - locked worker ids: once per request or once per route family
  - sessions/connections: once per request or once per route family
- Do not use wall-clock latency as the only proof. This proof is about query
  shape and bounded fanout.

Acceptance:

- Fixture metadata records `workerCount >= 100`, `workerGroupCount >= 5`,
  `onlineWorkerCount`, `lockedWorkerCount`, and `sessionCount`.
- Tests fail if worker read controllers call runtime/admission/session
  lookups once per worker row.
- Tests verify the response still includes representative rows/counts from the
  large fixture, not only empty or truncated success envelopes.
- The proof is PR-gatable and does not require Redis or a packaged server.

## LRAH-3B Packaged Worker Read Health

Implementation status: complete for memory-local and durable-local; durable-local requires Redis.

Goal: prove the real packaged server path keeps worker read routes healthy
with a non-trivial initialized worker fixture.

Scope:

- Add a packaged worker-read health mode or script that starts the real server,
  runs admin CLI initialization, creates/registers at least 100 worker records
  through supported server/API paths, and then runs `xa-mass-admin-cli api
  health`.
- The fixture may mark workers API-online without starting 100 long-running
  worker polling sessions. If the claim includes live session performance, the
  fixture must also start and report the session count.
- Emit `workerFixture` metadata into the health/report artifact:
  `workerCount`, `workerGroupCount`, `onlineWorkerCount`, `lockedWorkerCount`,
  `sessionCount`, `creationPath`, and `startedWorkerSessionCount`.
- `write-proof-summary.mjs` must preserve the worker fixture metadata for
  route timings.

Acceptance:

- The packaged worker-read health artifact is rejected as performance evidence
  when `workerFixture.workerCount < 100`.
- Selected worker read routes fail the local gate at `elapsedMs >= 1000` only
  when fixture metadata is present and valid.
- The artifact states whether it is `memory-local`, `durable-local`, or both.
  `memory-local` should be PR-gatable; `durable-local` may remain conditional
  on local Redis availability if the reason is explicit.
- The Product/API platform confidence smoke may continue to run with one
  worker, but proof summary must not count that run as worker-read performance
  evidence.

Verification:

```powershell
./mvnw.cmd -pl xa-mass-worker-runtime,sdk/xa-mass-embedded-sdk,transport/transport_api,xa-mass-server -am "-Dtest=WorkerAdmissionOwnerTest,WorkerManagerTest,MassSdkTest,WorkerPresenceStoreTest,WorkerApiControllerTest,CatalogControllerTest" test
rg -n "isWorkerLocked\\(|hasWorkerExclusiveLease\\(|isWorkerOnline\\(" xa-mass-server/src/main/java/com/xa/mass/api/internal -g "*.java"
rg -n "workerFixture|workerCount|apiHealth.routeTimings|worker-read" xa-mass-testing tools roadmap -g "*.sh" -g "*.mjs" -g "*.java" -g "*.md"
$env:MASS_OPERATOR_PASSWORD='ops-admin'
& 'C:\Program Files\Git\bin\bash.exe' xa-mass-testing/scripts/run-worker-read-health-smoke.sh --profile memory-local
node xa-mass-testing/scripts/write-proof-summary.mjs --job local-worker-read-health --worker-read-health-dir xa-mass-testing/target/worker-read-health/<run-dir> --output xa-mass-testing/target/proof-summary/worker-read-health-summary.json
```

## LRAH-4 External HTTP Proof Caller Consolidation

Goal: keep test and smoke HTTP callers maintainable as API routes evolve.

Scope:

- Inventory current raw HTTP platform callers in:
  - `xa-mass-testing`
  - `integrations/xa-mass-scenario-launcher`
  - shell scripts
  - server E2E tests
- Classify callers:
  - owner-local server/controller proof
  - external admin/operator proof
  - external SDK/scenario proof
  - negative raw protocol-boundary proof
- Move reusable external admin/operator calls behind:
  - `tools/xa-mass-admin-cli`
  - or a tools-owned HTTP client used by the CLI
- Move scenario task/worker proof behind scenario launcher commands or
  SDK-backed helpers.
- Keep raw HTTP only for tests whose explicit purpose is auth/header/protocol
  rejection at the route boundary.

Acceptance:

- New external HTTP health/smoke proof uses a CLI/scenario command, not
  duplicated request construction.
- Existing bare HTTP callers are inventoried and either retained with a named
  raw-boundary reason or queued for migration.
- Route/auth/header changes have one main tools/scenario caller to update for
  the local readiness lane.
- Docs compare this lane to trace proof: trace validates from logs/artifacts;
  HTTP CLI validates from the real external caller boundary.

Verification:

```powershell
rg -n "HttpClient|Invoke-WebRequest|curl|/api/v1|/worker-api/v1|X-Mass-Csrf-Token|X-mass-api-key" xa-mass-testing integrations tools xa-mass-server/src/test -g "*.java" -g "*.sh" -g "*.ps1" -g "*.md"
rg -n "api health|env init|ScenarioCredentialBootstrapMain" tools integrations xa-mass-testing roadmap -g "*.java" -g "*.sh" -g "*.md"
```

## LRAH-5 Contract Health Handoff

Goal: hand off a stable local environment and route health report to the API
contract health lane.

Scope:

- Update `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` and inventory to point to
  this roadmap as local readiness prerequisite.
- Ensure contract health runs after:
  - admin CLI environment initialization
  - env verify
  - local API health timing report
- Remove or archive any superseded local readiness roadmap residue.

Acceptance:

- API contract health lane no longer needs to rediscover how to initialize
  local scenario state.
- Browser/manual console inspection is not part of the required proof path.
- Current facts move into owner docs before this roadmap is archived.

Verification:

```powershell
rg -n "LOCAL_READINESS_AND_API_HEALTH_GATE|SERVER_API_CONTRACT_HEALTH_LANE|env init|api health" roadmap tools integrations xa-mass-testing doc -g "*.md" -g "*.sh" -g "*.java"
git diff --check
```

## Completion Criteria

- `tools/xa-mass-admin-cli env init` / `env verify` is the canonical local
  initializer.
- Scenario task and worker launchers consume prepared credentials and no longer
  own environment setup.
- Packaged confidence smoke emits route-level local API health timing.
- Worker read routes are protected from unbounded per-row runtime/admission
  queries.
- Worker read performance proof is complete only when:
  - LRAH-3A proves bounded fanout with at least 100 synthetic workers.
  - LRAH-3B proves packaged worker-read health with at least 100 API-created
    workers and valid `workerFixture` metadata.
  - Any selected worker read route taking 1s or more fails only in artifacts
    that declare valid fixture scale.
- Backup DB / snapshot restore is not part of the local readiness path.
- External HTTP proof callers are consolidated through tools/scenario commands
  except named raw-boundary tests.
- API contract health lane consumes this local readiness gate as prerequisite.
