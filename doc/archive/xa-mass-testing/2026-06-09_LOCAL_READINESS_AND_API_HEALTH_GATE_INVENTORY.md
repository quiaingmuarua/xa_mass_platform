# Local Readiness And API Health Gate Inventory

Status: archived on 2026-06-09 with implemented readiness decisions and memory-local/durable-local worker-read performance proof for the archived local readiness roadmap.

## Scope

This inventory records the current local readiness surfaces that must become a
repeatable prerequisite for the API contract health lane.

The inventory is not API truth. Server controllers, generated OpenAPI, SDK
typed clients, and frontend adapters remain their current owners.

## Current Surfaces

| Surface | Owner | Current Role | Gap |
| --- | --- | --- | --- |
| `tools/xa-mass-admin-cli` | `tools` | Operator/admin automation for a running server. It can login, verify auth config, create/validate API keys, sync catalog/rules, write local cache/marker files, and run `api health`. | Canonical local environment initializer and route-health runner for readiness and confidence gates. |
| `tools/xa-mass-admin-cli/examples/admin-env.local.json` | `tools` | Typed local env desired-state file for catalog, rules, task key, worker keys, and marker file. | Needs to be the canonical checked-in local scenario config. |
| `integrations/xa-mass-scenario-launcher` | `integrations` | SDK adopter and external actor proof harness for task producer and worker process roles. | It consumes prepared env state and no longer owns admin/operator initialization. |
| `ScenarioCredentialBootstrapMain` | `integrations` | Removed legacy local scenario bootstrap that logged in and created task/worker API keys. | No active artifact or source owner remains under scenario launcher. |
| `xa-mass-testing/scripts/run-platform-confidence-smoke.sh` | `xa-mass-testing` | Packaged confidence script that starts server, runs admin env init, worker launcher, task launcher, operator approve, result verifier, and admin CLI API health. | Emits `summary.json` with positive proof checks, negative fail-closed checks, initializer elapsed time, and `apiHealth.routeTimings`; proof summary can consume the direct run directory. |
| `SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md` | `roadmap` | Contract-health lane for route/auth/DTO/SDK/frontend consistency. | Depends on stable local readiness; should not own env init implementation. |
| `ServerApiFailureLoggingFilter` / endpoint metrics | `xa-mass-server` | Logs API failures and exposes endpoint timing metrics. | Need a local health gate/report that reads these signals or measures routes directly. |

## Test Caller Boundary

Tests that prove real external HTTP behavior should not hand-roll raw request
construction for platform routes in every test class. Repeated bare HTTP calls
make route/auth/header/CSRF/API-key changes expensive and cause proof drift.

Target caller ownership:

- `tools/xa-mass-admin-cli` or a shared tools-owned HTTP client owns operator
  login, CSRF, API-key lifecycle, catalog/rule sync, and local route-health
  calls.
- `integrations/xa-mass-scenario-launcher` owns SDK-backed scenario task and
  worker actor calls.
- `xa-mass-testing` orchestrates processes and reads reports; it should prefer
  invoking tools/scenario commands over rebuilding HTTP clients in shell/tests.
- Server controller tests may still use `MockMvc` or direct controller/service
  tests for owner-local behavior.
- Negative auth/route tests may use focused low-level HTTP only when the point
  of the test is the raw protocol boundary; those exceptions must be named.

Analogy:

- Trace scenario proof validates behavior from emitted logs and trace
  artifacts.
- HTTP CLI scenario proof validates behavior from the outermost real HTTP
  caller path.

Both are proof lanes. Neither should scatter duplicate protocol code across
unrelated tests.

## Local Initialization Facts

- `durable-local` can start with only operator credentials seeded; scenario
  catalog, rules, task API-key, and worker API-keys are expected to be prepared
  after startup through operator/server APIs.
- `memory-local` defaults to local fixture auth behavior and volatile state for
  convenience, but the LRAH readiness gate runs `memory-local` with session
  auth and fixture headers disabled. `durable-local` and gated `memory-local`
  share the same public API/auth contract for readiness proof.
- API-key raw secrets are only returned on create. A stale local key cache must
  be validated with `/api/v1/api-keys:current`; if validation fails, env init
  must refresh or fail with a clear category.
- Worker API-key credentials may be workerId-bound. A worker launcher key that
  is not bound to the registered `workerId` correctly fails.
- Scenario task launcher and worker launcher are separate external actor roles.

## API Latency Facts

- The operator console is a local validation surface. Browser navigation should
  not be required to prove basic route health.
- Local control-console reads should normally be millisecond-level on local
  infra. Seconds-level latency is a product-readiness failure even if it does
  not break functional tests.
- Recent observed slow surfaces:
  - `GET /api/v1/runtime/workers`
  - `GET /api/v1/catalog/worker-capabilities`
  - `GET /api/v1/catalog/worker-group-capabilities`
- Root cause class: read models can accidentally perform per-row runtime reads
  such as online checks, admission lease checks, or transport diagnostics.
- Current worker list/capability controllers use a bulk online-worker snapshot
  through `WorkerInspectionOperations.listOnlineWorkerIds()` and existing
  bulk diagnostics for lock/session facts.
- The current packaged platform confidence fixture initializes one worker.
  That is Product/API capability evidence only; it is not worker read
  performance evidence.
- Worker read performance evidence needs explicit fixture scale. The first
  accepted scale is at least 100 workers, which is still a small local
  readiness fixture rather than a high-volume perf claim.
- `run-worker-read-health-smoke.sh` provides the packaged worker-read health
  fixture for both `memory-local` and `durable-local`: 100
  API-created/API-online workers across 5 groups, no started worker sessions,
  and no locked workers. It is worker read-model scale proof, not task
  execution or live-session scale proof.
- Read-only route consistency is snapshot/eventual. Read models must not
  acquire runtime locks to make console output stable.

## Initialization Decision

The roadmap explicitly rejects backup DB / snapshot restore as a mainline local
readiness mechanism. Local readiness must be prepared by an initializer command
that calls real server APIs after the server is running.

Target implementation shape:

- canonical local entry: `tools/xa-mass-admin-cli env init` and `env verify`
- scenario launcher consumes prepared credentials and environment state
- no scenario-side initializer command remains under scenario launcher; it must
  not reintroduce operator login, CSRF, catalog/rules sync, or API-key
  lifecycle HTTP logic

The target behavior is: login, sync catalog/rules,
validate/create task and worker API keys, then run route health checks through
HTTP. No initializer writes DB files directly.

The initializer scope is environment readiness: catalog, rules, operator auth,
task API-key, worker API-key, cache validation, marker verification, and route
reachability. Credential bootstrap is only one part of that workflow.

## Implementation Notes

1. Existing packaged confidence smoke proves startup, session auth, env init,
   task/worker launch, operator approval, result readback, and selected route
   timing through `apiHealth.routeTimings`. Its current one-worker fixture is
   not worker read performance proof.
2. Admin CLI is the only active environment initializer path. The old
   scenario-side credential bootstrap artifact/source has been removed.
3. The confidence smoke records a hard 1000 ms route latency budget for the
   selected repeatable read routes in `api health`, but worker-read
   performance evidence requires separate fixture scale metadata.
4. Proof summary records structured `apiHealth.routeTimings` from packaged
   confidence artifacts, including direct run-directory inputs for job-scoped
   summaries.
5. Browser inspection is useful for UX diagnosis, but it should not be required
   for server API health proof.
6. Worker runtime read models use snapshot/bulk-read rules for the current
   worker list/capability routes. Bounded fanout is covered by >=100-worker
   synthetic controller tests, and packaged worker-read health is covered by a
   >=100-worker API-created fixture.

## Candidate First Route Set

The route-health manifest must be mechanical. Every route entry records:
`method`, `path`, `routeAuthPolicy`, `credentialUsedByHealthRunner`,
`readOrWrite`, `sourceCommand`, `budgetMs`, `normalDataPresence`, and
`repeatable`.

`routeAuthPolicy` is the server route contract. `credentialUsedByHealthRunner`
is only how the local health runner chooses to call the route. Do not collapse
these two fields: some catalog routes are current bypass/read routes even when
the runner is operating after admin CLI initialization.

| Method | Route | Route Auth Policy | Credential Used By Health Runner | Read/Write | Repeatable | Source Command | Purpose | Suggested Local Budget |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/v1/auth/config` | public | none | read | yes | admin CLI `api health` | auth readiness | elapsed < 1000 ms |
| `POST` | `/api/v1/auth/login` | public | operator credentials | write | no | `xa-mass-admin-cli env init` | operator credential proof | record elapsed/status from env init |
| `GET` | `/api/v1/projects` | operator or SDK read | operator-session | read | yes | admin CLI `api health` | catalog project read | elapsed < 1000 ms |
| `GET` | `/api/v1/catalog/events` | `SDK_CREDENTIAL_BYPASS` read | none | read | yes | admin CLI `api health` | event catalog read | elapsed < 1000 ms |
| `GET` | `/api/v1/admin/rules` | operator-session | operator-session | read | yes | admin CLI `api health` | rule read | elapsed < 1000 ms |
| `GET` | `/api/v1/runtime/workers` | operator-session | operator-session | read | yes | admin CLI `api health` | worker read model | elapsed < 1000 ms only counts as worker-read performance evidence with `workerFixture.workerCount >= 100` |
| `GET` | `/api/v1/catalog/worker-capabilities` | `SDK_CREDENTIAL_BYPASS` read | none | read | yes | admin CLI `api health` | worker capability detail | elapsed < 1000 ms only counts as worker-read performance evidence with `workerFixture.workerCount >= 100` |
| `GET` | `/api/v1/catalog/worker-group-capabilities` | `SDK_CREDENTIAL_BYPASS` read | none | read | yes | admin CLI `api health` | worker capability summary | elapsed < 1000 ms only counts as worker-read performance evidence with `workerFixture.workerCount >= 100` |
| `POST` | `/api/v1/tasks` | task API-key | task-api-key | write | no | scenario task launcher | task API-key positive proof | record elapsed/status from scenario flow only |
| `POST` | `/worker-api/v1/worker-groups` and `/worker-api/v1/workers` | worker API-key | worker-api-key | write | no | scenario worker launcher | worker API-key positive proof | record elapsed/status from scenario flow only |

Budgets are local readiness gates, not production SLOs.

## Worker Read Performance Evidence Boundary

Do not use the default platform confidence smoke as worker read performance
proof. Its current worker fixture is intentionally small and proves that the
external product path works.

Accepted worker read performance evidence:

1. Bounded fanout proof:
   - synthetic server/controller fixture
   - `workerCount >= 100`
   - `workerGroupCount >= 5`
   - mixed online/offline, locked, and session/connection facts
   - asserts constant bulk/snapshot calls instead of per-worker runtime calls
2. Packaged worker-read health:
   - real packaged server and supported API creation/registration path
   - `workerFixture.workerCount >= 100`
   - records `workerGroupCount`, `onlineWorkerCount`, `lockedWorkerCount`,
     `sessionCount`, `creationPath`, and `startedWorkerSessionCount`
   - selected worker read routes fail at `elapsedMs >= 1000`

Anything without fixture scale is route-health metadata, not worker-read
performance proof.

The first route-health gate checks reachability, HTTP success, and normal data
presence only. Exact response field correctness belongs to API contract,
public-contract, SDK, and frontend adapter tests.

Budget scope:

- measured after the server has started and the initializer has completed
- excludes JVM boot, Flyway, local build/compile time, and first server startup
  readiness
- may allow one warm-up read before timing if the health runner records that
  choice consistently

Normal data presence means the route returned a success envelope and the
minimum useful data for local readiness exists:

- auth config returns an auth mode
- login returns or establishes an operator session/CSRF context
- projects/events/rules reads return non-empty configured scenario facts
- worker read routes return at least the initialized worker rows after worker
  registration proof has run
- task create returns a task id when the task producer key is used
- worker registration routes accept the initialized worker credential and
  return a success envelope

Exact field names, DTO shape drift, optional diagnostics, display aliases, and
frontend adapter mapping are out of scope for this gate.
