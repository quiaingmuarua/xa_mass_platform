# Server API Contract Health Lane Roadmap

Status: proposed direction document.

## Summary

The project is moving toward product-ready server + SDK first operation. That
requires a repeatable API contract health lane: a way to verify that server
routes, auth behavior, public DTOs, Java SDK typed clients, scenario-launcher
external flows, frontend adapters, and generated API docs still describe the
same product surface.

This roadmap is separate from
`LOCAL_ENV_SCHEMA_RESET_AND_SCENARIO_CREDENTIAL_ROADMAP.md`. Local environment
readiness prepares a clean server, catalog/rules, operator credentials, and
task/worker API keys. This roadmap uses that stable environment to prove API
contract health.

## Current Code Observations

- `xa-mass-server` owns live OpenAPI/Knife4j docs at `/v3/api-docs` and
  `/doc.html`.
- `doc/FRONTEND_BACKEND_CONTRACT.md` records that backend route/DTO/auth
  changes must update server tests, public-contract/SDK surfaces when relevant,
  frontend adapters/types, and frontend tests.
- `sdk/xa-mass-public-contract` is the narrow shared HTTP wire DTO/constants
  owner for Controller-exposed public structures.
- `sdk/xa-mass-java-sdk` owns external Java task and worker typed clients,
  sessions, and handler runtime.
- `integrations/xa-mass-scenario-launcher` already proves SDK-backed task
  producer and worker registration/session flows against a running server.
- `SERVER_FRONTEND_STATIC_API_DOCS_ROADMAP.md` owns static API docs snapshot
  mechanics and frontend API reference presentation.
- `SERVER_API_OBSERVABILITY_ROADMAP.md` owns API failure logging and endpoint
  metrics that can diagnose contract-health failures.
- `LOCAL_ENV_SCHEMA_RESET_AND_SCENARIO_CREDENTIAL_ROADMAP.md` is the intended
  local readiness prerequisite for clean DB, seed/import, and real task/worker
  API-key preparation.

## Owner Review

- `xa-mass-server` owns HTTP route behavior, auth/session/CSRF, permissions,
  API-key lifecycle, OpenAPI generation, and backend product API docs.
- `sdk/xa-mass-public-contract` owns shared public HTTP DTO/constants only when
  the owning Controller exposes them and an external SDK/consumer needs the
  same shape.
- `sdk/xa-mass-java-sdk` owns typed external task/worker callers. It must not
  own operator login, local environment reset, or server credential lifecycle.
- `integrations/xa-mass-scenario-launcher` owns proof harness behavior for real
  external task and worker flows through the SDK.
- `frontend` owns adapter consumption and presentation; it must not define API
  truth or route dictionaries.
- Observability owns diagnostics and metrics, not the contract itself.

## Boundary Decision

API contract health is a verification lane, not a new product surface.

```text
local readiness
  -> clean server + seed/import + operator credentials + task/worker API keys

contract health lane
  -> generated OpenAPI/static snapshot
  -> server route/auth smoke
  -> Java SDK typed task + worker route proof
  -> scenario-launcher task + worker external proof
  -> frontend adapter/type drift check for consumed routes
  -> API failure/metrics diagnostics report
```

## Hard Rules

1. Do not hand-write API route dictionaries as contract truth.
2. Do not add frontend-only route aliases, permission names, or DTO truth to
   make contract tests pass.
3. Do not add operator login or API-key lifecycle API to `xa-mass-java-sdk`.
4. Do not treat local seed/import or schema reset as API contract proof.
5. Do not treat generated static docs as current truth unless the generation
   command or health lane has run.
6. Do not let worker registration DB observation rows become runtime truth or
   contract input.
7. API-key raw secrets must not be logged in contract health output.
8. Contract health failures should surface exact route/auth/DTO/SDK/frontend
   category, not only generic E2E failure.
9. If a backend response shape changes, the same slice must update server tests,
   public-contract/SDK surfaces when relevant, and frontend real adapters/types
   when consumed.
10. Keep API health lane separate from scheduling/runtime correctness proof;
    use existing lifecycle/trace proof lanes for kernel behavior.

## Non-Goals

- No local DB reset implementation; that belongs to
  `LOCAL_ENV_SCHEMA_RESET_AND_SCENARIO_CREDENTIAL_ROADMAP.md`.
- No full OpenAPI client generation for Java SDK or frontend.
- No production API portal publication.
- No broad frontend productionization.
- No new server API routes purely for health checks.
- No worker config support in scenario launcher unless the local readiness
  roadmap decides it.
- No Redis/runtime/SSE implementation.
- No commercial migration compatibility.

## Do Not Start With

Do not start by adding a broad E2E that only says "scenario passed". First
inventory route families, auth modes, DTO owners, SDK typed methods, frontend
adapters, and docs surfaces so each contract-health failure points to the owner
that must fix it.

## ACH-0 Inventory And Lane Definition

Goal: define the first API contract health lane without changing behavior.

Scope:

- Create or update `SERVER_API_CONTRACT_HEALTH_LANE_INVENTORY.md`.
- Classify first route families:
  - operator auth and API-key lifecycle
  - task producer create/append/read
  - worker registration/presence/poll/result
  - catalog/project read routes needed by SDK/frontend
  - docs endpoints `/v3/api-docs` and `/doc.html`
- Map each route family to:
  - server Controller owner
  - auth mode and required permission
  - public-contract DTO owner, if any
  - Java SDK typed method, if any
  - frontend adapter/type consumer, if any
  - OpenAPI/static docs exposure category
- Decide whether the first lane requires local readiness LSR-3 complete, or
  whether ACH-1 can start with a mocked/controller contract subset.
- Decide first health output shape:
  - markdown report
  - JUnit XML/test classes only
  - generated JSON summary

Acceptance:

- Inventory names the first route families and their owners.
- Inventory explicitly records whether worker SDK registration proof is in the
  first lane or deferred behind local readiness.
- Inventory records which frontend adapters are in scope for the first lane.
- Inventory records how OpenAPI/static docs snapshots participate.
- No code behavior changes are required in this slice.

Verification:

```powershell
rg -n "RequestMapping|PostMapping|GetMapping|PatchMapping|DeleteMapping" xa-mass-server/src/main/java/com/xa/mass/api -g "*.java"
rg -n "MassPlatform|TaskClient|WorkerClient|/api/v1|/worker-api/v1" sdk/xa-mass-java-sdk integrations/xa-mass-scenario-launcher -g "*.java" -g "*.md"
rg -n "api-reference|v3/api-docs|doc.html|frontend/src/api|FRONTEND_BACKEND_CONTRACT" frontend xa-mass-server doc roadmap -g "*.ts" -g "*.vue" -g "*.md" -g "*.java"
```

## ACH-1 Generated API Docs Snapshot Proof

Goal: make generated API docs part of contract health without moving API truth
into frontend.

Scope:

- Depend on the exposure decisions from
  `SERVER_FRONTEND_STATIC_API_DOCS_ROADMAP.md`.
- Generate or capture `/v3/api-docs` for the selected local profile.
- Compare against a stored or generated snapshot according to ACH-0 decision.
- Confirm route families in ACH-0 are present with expected methods and tags.
- Classify excluded/internal/debug routes explicitly.

Acceptance:

- OpenAPI snapshot contains the selected public/operator/worker route families.
- Missing or extra route families fail with a category-specific message.
- Static docs snapshot, if generated, is marked as review artifact, not source
  truth.
- Live `/doc.html` exposure remains governed by server docs/static docs
  roadmap decisions.

Verification:

```powershell
./mvnw.cmd -pl xa-mass-server -am "-Dtest=*ApiDocs*Test,*OpenApi*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "v3/api-docs|doc.html|api-reference|OpenAPI" xa-mass-server frontend roadmap -g "*.java" -g "*.ts" -g "*.vue" -g "*.md"
```

## ACH-2 Server Route/Auth Contract Smoke

Goal: prove selected server route families reject/accept the correct credential
type before SDK/frontend adapters are blamed.

Scope:

- Add focused server contract tests for:
  - operator session login and CSRF-protected `POST /api/v1/api-keys`
  - API-key current route for cache validation
  - task create and append with task API-key
  - worker registration route with worker API-key and worker binding
  - docs endpoint exposure according to ACH-1
- Use route/auth labels in failure messages.
- Do not add new routes.

Acceptance:

- Missing credential, wrong credential audience, missing CSRF, missing
  permission, project scope denial, event scope denial, and worker binding
  denial are covered for the selected route families.
- Positive path proves the same route family used by SDK/integration callers.
- Test names and failure messages identify route family and auth contract.
- Server docs are updated when route/auth behavior changes.

Verification:

```powershell
./mvnw.cmd -pl xa-mass-server -am "-Dtest=*ApiContract*Test,*ApiAuth*Test,*ExternalWorkerApiControllerTest,*TaskApiControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## ACH-3 Java SDK Typed Route Parity

Goal: prove Java SDK typed clients still map to server public route families
and do not grow raw route or operator-login behavior.

Scope:

- Map `TaskClient`, `TaskHandle`, `WorkerClient`, worker sessions, and any
  public-contract DTO usage to selected server route families.
- Add or update SDK tests for:
  - task create/append/read typed calls
  - worker group/adapter/binding/worker registration typed calls
  - worker poll/submit-result typed calls or session paths
- Add a guard that SDK does not expose operator login or API-key lifecycle
  methods.
- Keep route literals allowed only inside SDK typed route owner classes.

Acceptance:

- SDK typed methods cover the selected task and worker route families.
- SDK tests fail if route path/method changes without updating typed clients.
- No SDK public method exposes operator login, API-key creation, seed/import, or
  schema reset.
- Public-contract DTOs are used only where the owner decision says they are
  shared HTTP wire shapes.

Verification:

```powershell
./mvnw.cmd -pl sdk/xa-mass-public-contract,sdk/xa-mass-java-sdk -am test
rg -n "auth/login|api-keys|seed|schema reset|reset-on-mismatch" sdk/xa-mass-java-sdk/src/main/java
rg -n '"/api/v1|"/worker-api/v1' sdk/xa-mass-java-sdk/src/main/java integrations/xa-mass-scenario-launcher/src/main/java
```

## ACH-4 Scenario Launcher External Contract Proof

Goal: run a representative external flow through SDK task and worker paths
against a real initialized server.

Scope:

- Depend on local readiness for:
  - clean local DB or verified schema fingerprint
  - catalog/rules/operator seed
  - task API-key cache file
  - worker API-key cache/spec preparation for selected worker specs
- Run scenario task launcher with `--config`.
- Run scenario worker launcher or an equivalent SDK-backed worker registration
  proof.
- Capture route/auth failures through API failure lane when available.

Acceptance:

- Task producer creates a task and appends items through Java SDK.
- Worker path declares WorkerGroup, AdapterNode, NodeGroupBinding, and Worker
  through Java SDK.
- At least one worker session poll/dispatch/result path succeeds, or worker
  session proof is explicitly deferred with a named reason and successor.
- Contract-health output separates setup failure from server route/auth/DTO/SDK
  failure.

Verification:

```powershell
./mvnw.cmd -pl xa-mass-server,integrations/xa-mass-scenario-launcher,sdk/xa-mass-java-sdk -am "-Dtest=*Scenario*Contract*Test,JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## ACH-5 Frontend Adapter And Public-Contract Drift

Goal: keep frontend and public-contract consumers aligned with server contract
changes without making frontend a source of truth.

Scope:

- For route families consumed by frontend, verify matching
  `frontend/src/api/*.real.ts` adapters and type files.
- Verify `sdk/xa-mass-public-contract` only contains Controller-exposed shared
  DTOs/constants selected by owner decision.
- Update frontend tests only for consumed route/shape/auth changes.
- Do not require frontend to consume every contract-health route family.

Acceptance:

- Backend response shape changes in selected route families update frontend
  adapters/types when frontend consumes them.
- Public-contract DTO movement has owner evidence from Controller routes.
- No frontend inline `fetch`, route alias, permission alias, or mock-only
  production shape is added.
- Frontend docs/API reference snapshot remains generated/review artifact.

Verification:

```powershell
rg -n "fetch\\(|/api/v1|/worker-api/v1" frontend/src -g "*.ts" -g "*.vue"
./mvnw.cmd -pl sdk/xa-mass-public-contract -am test
```

Frontend package verification should use the owning frontend commands decided
by the frontend owner docs or CI workflow.

## ACH-6 Observability Health Report

Goal: make contract failures easy to diagnose without adding high-cardinality
custom metrics.

Scope:

- Use existing `SERVER_API_FAILURE` lane and `http.server.requests` metrics as
  diagnostics during contract-health runs.
- Record endpoint success/failure and latency summary for selected route
  families.
- Keep metrics bounded by existing HTTP server metrics; do not create a custom
  per-user/per-key/per-task metric set.

Acceptance:

- Contract-health run can report whether failures were auth, route, DTO,
  SDK/client, frontend adapter, setup, or runtime dispatch.
- `SERVER_API_FAILURE` events for selected negative cases are visible when the
  observability roadmap has landed.
- Endpoint metrics are diagnostic support only and do not redefine contract
  truth.

Verification:

```powershell
./mvnw.cmd -pl xa-mass-server -am "-Dtest=*ApiObservability*Test,*ServerApiFailure*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## Completion Criteria

This roadmap can be marked complete only when:

1. first route family inventory is current and owner-classified
2. generated OpenAPI/static docs snapshot participates in the health lane
3. server route/auth contract smoke covers selected task, worker, operator, and
   API-key routes
4. Java SDK typed clients are parity-checked against selected server route
   families
5. scenario-launcher proves external task and worker registration/session
   contract, or any deferred worker session proof has a named successor
6. frontend adapters/types are checked for consumed route families
7. public-contract DTO sharing is checked against Controller ownership
8. API failure/metrics diagnostics are available for contract-health runs
9. local readiness prerequisites are linked but not duplicated
10. active owner docs describe this lane as verification, not a new API surface

## Suggested Implementation Order

1. ACH-0 inventory and lane definition.
2. ACH-1 generated API docs snapshot proof.
3. ACH-2 server route/auth contract smoke.
4. ACH-3 Java SDK typed route parity.
5. ACH-4 scenario-launcher external contract proof.
6. ACH-5 frontend/public-contract drift.
7. ACH-6 observability health report.

## Risks

| Risk | Mitigation |
| --- | --- |
| Health lane becomes a broad E2E bucket | Keep route families owner-classified and report failure category. |
| Local setup failures hide contract failures | Depend on local readiness roadmap and separate setup failure category. |
| Frontend becomes API truth | Only verify consumed adapters/types; generated docs remain server-owned. |
| SDK gains operator/credential lifecycle APIs | Guard `xa-mass-java-sdk` against login/API-key creation/seed/reset methods. |
| OpenAPI snapshot drifts from live server | Snapshot generation command or test must run in the health lane. |
| Worker proof is skipped because task proof passes | ACH-0 must explicitly decide worker registration/session proof status. |
