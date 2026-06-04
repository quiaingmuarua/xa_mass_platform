# External Worker Real Registration Onboarding Roadmap

Status: implemented mainline on 2026-06-04; archive after the next roadmap
residue pass.

Follow-up decision: this roadmap proved real SDK-backed registration while
leaving sample bootstrap as an explicit dev fixture. The current owner decision
is stricter: dev/prod may differ by infrastructure and seed source, not by API
contract. `DevBootstrapClient` and `SampleBootstrapController` are therefore
retirement targets for
`CONTROL_PLANE_SEED_AND_SAMPLE_BOOTSTRAP_RETIREMENT_ROADMAP.md`.

This roadmap converges external worker onboarding from dev/sample bootstrap
toward real SDK-driven registration against a running server. It keeps the
session focus on `sdk/` and `integrations/`, while treating server prod/dev
auth and bootstrap behavior as required host boundary work.

Related current truth:

- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`
- `sdk/README.md`
- `sdk/xa-mass-java-sdk/README.md`
- `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`
- `integrations/README.md`
- `integrations/xa-mass-scenario-launcher/README.md`
- `integrations/xa-mass-worker-pack/README.md`
- `xa-mass-server/README.md`
- `xa-mass-server/doc/INTERNAL_API_REFERENCE.md`
- `doc/PROOF_REGISTRY.md`

## Purpose

The platform should prove external worker value through real external
registration:

```text
API key credential
  -> WorkerGroup declaration
  -> AdapterNode registration
  -> NodeGroupBinding
  -> Worker registration
  -> worker session online/poll/result
  -> task submission/result convergence
```

This roadmap left `SampleBootstrapController` and dev fixture launchers as
local validation helpers, but they must not be the path that proves
production-style worker registration. The follow-up target is to remove that
sample HTTP bootstrap path and replace it with explicit environment
seed/import into control-plane storage.

## Current Facts

- The server already exposes real external worker registration APIs under
  `/worker-api/v1/**`:
  - `POST /worker-api/v1/worker-groups`
  - `POST /worker-api/v1/adapter-nodes`
  - `POST /worker-api/v1/node-group-bindings`
  - `POST /worker-api/v1/workers`
  - polling presence, poll, result submit, capability, state, and command ack
- `sdk/xa-mass-java-sdk` already wraps the worker topology and worker-session
  path through `MassPlatform.workerSessions()`.
- API key support already has create, application, approval, revoke, expiry,
  permission, project scope, event scope, and submitter-auth projection.
- `integrations/xa-mass-scenario-launcher` registers WorkerGroup,
  AdapterNode, NodeGroupBinding, and Worker through `MassPlatform`.
  Dev fixture runs can still prepare catalog/rules through `DevBootstrapClient`,
  while real-proof black-box runs use pre-created host metadata and
  `--skip-dev-bootstrap`. This is implemented residue, not the target external
  SDK shape.
- `WorkerPackGeoLookupExternalSdkIntegrationTest` already proves one
  worker-pack capability through Java SDK worker-session registration and task
  result convergence; remaining worker-pack work is proof hardening, doc sync,
  and guard closure unless inventory finds a gap.
- `xa-mass-server/README.md` says sample bootstrap is dev/sample wiring and
  should be disabled by default in prod. `application-prod.yml` now keeps
  `sample.bootstrap.enabled=false`.
- `SampleBootstrapController` is protected only by `X-Sample-Bootstrap-Key`.
  It should remain a dev fixture endpoint, not become worker onboarding.
- `SampleBootstrapController` is a server-owned Spring MVC surface.
  Worker-pack owns capabilities and harness clients, not server MVC bootstrap
  endpoints.
- `integrations/xa-mass-scenario-launcher` is the primary Java SDK adopter.
- `integrations/xa-mass-worker-pack` owns real worker capabilities and should
  prove them through SDK/API registration, not server startup seeding.

## Hard Rules

- Do not add server startup seeding for WorkerGroup, AdapterNode,
  NodeGroupBinding, Worker, task shell, or task item truth.
- Do not expand `SampleBootstrapController` into a production onboarding API.
- Do not preserve dev/prod API divergence as a product pattern. Dev/prod may
  differ by infra backend, seed source, logging, and operational defaults, not
  by public task/worker/control-plane API surface.
- Do not move server MVC bootstrap endpoints into worker-pack, and do not move
  worker-pack capability code into server bootstrap endpoints.
- Prod must not enable sample bootstrap by default.
- Real worker registration must use public `/worker-api/v1/**` routes through
  the Java SDK or equivalent external HTTP calls.
- WorkerGroup remains capability truth. Worker rows must not own project/event
  capability.
- API key credential scope must be enforced through project scopes, event
  scopes, worker permission, and worker binding attributes; do not bypass it in
  SDK/integration code.
- `sdk/xa-mass-java-sdk` must not depend on server, engine, base, transport
  implementation, embedded SDK, or worker-pack modules.
- Worker-pack capability code must stay in `integrations/xa-mass-worker-pack`;
  SDK modules may expose registration/session primitives but must not absorb
  capability implementations.

## Do Not Start With

Do not start by adding another sample endpoint or server-owned bootstrap route.
The first production boundary fix is to make prod reject sample bootstrap by
default, then prove the existing external registration chain with API-keyed SDK
callers.

## Target Shape

- Dev fixture bootstrap is implemented residue to retire after explicit
  control-plane seed/import exists.
- Prod-like startup boots a clean platform shell with no sample bootstrap
  surface unless explicitly enabled.
- Operator/API-key flow can mint or approve credentials that are sufficient for
  external worker registration.
- Scenario-launcher can run a real external worker registration flow from an
  API key and base URL.
- Worker-pack capabilities can be exercised through Java SDK worker sessions
  as real external workers; the first geo lookup proof already exists and
  should be hardened rather than duplicated.
- Proof uses SDK/integration paths and server E2E, not server startup seeding.

## EWR-0: Inventory Current External Worker Onboarding

Goal: make the current caller and fixture split explicit before changing code.

Scope:

- Create a sibling inventory:
  `roadmap/EXTERNAL_WORKER_REAL_REGISTRATION_ONBOARDING_INVENTORY.md`.
- Inventory current production and test paths for:
  - `SampleBootstrapController`,
  - `sample.bootstrap.*` properties,
  - API key create/application/approval/revoke flows,
  - `/worker-api/v1/**` registration routes,
  - scenario-launcher worker registration usage,
  - scenario-launcher `DevBootstrapClient` catalog/rule preparation usage,
  - worker-pack external SDK integration usage,
  - dev sample launchers.
- Classify each path as:
  - production host API,
  - SDK public caller path,
  - integration adopter,
  - worker capability pack,
  - dev fixture,
  - test fixture,
  - residue.

Acceptance:

- Inventory records the current prod/dev sample bootstrap contradiction.
- Inventory separates already implemented SDK registration paths from missing
  sample-bootstrap residue.
- Inventory classifies worker-pack geo lookup proof as already implemented or
  names the exact gap that remains.
- Inventory names the first implementation slice targets.
- No code behavior changes in this slice.

Verification:

```powershell
rg -n "SampleBootstrapController|sample\\.bootstrap|/worker-api/v1|ApiKeyCredentialService|MassPlatform\\.workerSessions|WorkerPackGeoLookupExternalSdkIntegrationTest|JavaScenarioLauncherBlackBoxIntegrationTest" xa-mass-server sdk integrations doc roadmap -g "*.java" -g "*.md" -g "*.yml"
```

## EWR-1: Close Prod Sample Bootstrap By Default

Goal: align prod config with the server README and SDK/integrations boundary
guard.

Scope:

- Change `application-prod.yml` so `sample.bootstrap.enabled=false` by default.
- Keep dev behavior explicit: dev may enable sample bootstrap for local fixture
  paths.
- Add or update server startup/config tests proving:
  - prod-like profile does not expose `/sample-api/bootstrap/**` by default,
  - dev fixture profile may expose it when explicitly enabled,
  - clean startup still creates no WorkerGroup, AdapterNode, Worker, task, or
    task item truth.
- Update `xa-mass-server/README.md` only if wording needs to match the final
  config.

Acceptance:

- Prod default does not register `SampleBootstrapController`.
- Dev fixture behavior remains intentionally available.
- The server README and actual configuration agree.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=CleanServerStartupIntegrationTest,*SampleBootstrap*,*ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "sample\\.bootstrap:\\n\\s+enabled:\\s+true|SampleBootstrapController" xa-mass-server/src/main/resources xa-mass-server/src/test/java xa-mass-server/README.md
```

## EWR-2: Define API-Keyed External Worker Credential Shape

Goal: make the minimum real worker credential semantics explicit and testable.

Scope:

- Keep using the existing API key service and submitter auth projection.
- Define the required credential shape for external workers:
  - worker permission,
  - project scopes,
  - event scopes,
  - optional current binding attribute `workerId` in
    `PrincipalContext.attributes` when a credential is bound to one worker.
- Implement the current binding rule in one shared controller path:
  - if credential attribute `workerId` is blank or absent, preserve existing
    scoped-worker behavior,
  - if credential attribute `workerId` is present, every worker-scoped route
    must require it to match the requested/path worker id.
- Apply the shared binding check to worker register, online, heartbeat,
  offline, poll, submit result, command poll, capability report, state report,
  and command ack paths.
- Add tests that create an API key with worker permissions and prove it can:
  - declare a WorkerGroup only within allowed event/project scope,
  - register AdapterNode and NodeGroupBinding,
  - register the bound worker,
  - fail when event scope or worker id does not match.
- Do not add a new credential type unless the existing API key model cannot
  express the proof.

Acceptance:

- A real API key can authorize the external worker registration chain.
- Scope mismatch fails before worker truth is registered.
- Bound worker-id mismatch fails before worker truth, presence, poll, result,
  report, or command acknowledgement side effects.
- Raw sample bootstrap key is not used in this proof.

Verification:

```powershell
./mvnw -pl xa-mass-server -am "-Dtest=ApiKeyControllerTest,ApiKeyApplicationControllerTest,ExternalWorkerApiControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## EWR-3: SDK Onboarding Helper Or Documented Composition

Goal: make the Java SDK path ergonomic enough that integrations do not need raw
route orchestration.

Scope:

- Review whether existing `mass.workers()` and `mass.workerSessions()` builders
  already provide a clean onboarding composition.
- If existing calls are sufficient, document the exact sequence in
  `EXTERNAL_SDK_QUICKSTART.md` and do not add a helper.
- If repeated integration code proves the sequence is too error-prone, add a
  narrow SDK helper that only composes existing public worker topology calls.
- Any helper must not hide WorkerGroup declaration as an implicit side effect
  of `PollingWorkerSession.start()` or `WebSocketWorkerSession.start()`.

Acceptance:

- The SDK quickstart shows a complete API-keyed external worker registration
  path.
- Scenario-launcher can follow the documented path without hard-coded platform
  route literals.
- No server/engine/base dependency enters `xa-mass-java-sdk`.

Verification:

```powershell
./mvnw -pl sdk/xa-mass-java-sdk -am test
rg -n "/worker-api/v1|new HttpClient|SampleBootstrap" sdk/xa-mass-java-sdk integrations/xa-mass-scenario-launcher -g "*.java" -g "*.md"
```

## EWR-4: Scenario-Launcher Real-Proof Bootstrap Decoupling

Goal: make scenario-launcher the primary executable adopter without adding a
parallel registration mode.

Scope:

- Keep the existing `WorkerScenarioRegistrar` SDK registration chain as the
  registration owner path.
- Split scenario-launcher catalog/rule/credential preparation from worker/task
  registration so real-proof runs can skip `DevBootstrapClient`. This roadmap
  stopped at decoupling; the successor roadmap owns deleting
  `DevBootstrapClient`.
- Make the real-proof path accept already prepared server metadata and API
  keys, then execute the existing worker registration and task path.
- The implemented dev fixture path can still call `DevBootstrapClient`
  explicitly when a local scenario needs sample catalog/rule setup, but that is
  implemented residue rather than target shape.
- Do not introduce a second "real mode" that duplicates WorkerGroup,
  AdapterNode, NodeGroupBinding, Worker, session, and task orchestration.
- The real-proof path must not call `/sample-api/bootstrap/**`.

Acceptance:

- Scenario-launcher proves external worker registration and task execution
  against a clean running server.
- The proof works without server startup seeding.
- The proof uses the Java SDK typed client/session surface.
- The launcher has one registration mainline; dev/sample bootstrap is only an
  optional preparation step in this completed slice and is scheduled for
  retirement by the control-plane seed/import roadmap.

Verification:

```powershell
./mvnw -pl integrations/xa-mass-scenario-launcher,xa-mass-server -am "-Dtest=JavaScenarioLauncherBlackBoxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n -g "*.java" -g "*.md" -- "--skip-dev-bootstrap|devBootstrapEnabled" integrations/xa-mass-scenario-launcher xa-mass-server/src/test/java/com/xa/mass/server/e2e
rg -n "sample-api/bootstrap|X-Sample-Bootstrap-Key" integrations/xa-mass-scenario-launcher/src/main/java/com/xa/mass/scenario/DevBootstrapClient.java integrations/xa-mass-scenario-launcher/README.md
```

## EWR-5: Worker-Pack Capability Proof Hardening

Goal: harden existing worker-pack external SDK proof without duplicating it.

Scope:

- Treat `WorkerPackGeoLookupExternalSdkIntegrationTest` as the current first
  proof that worker-pack capability registration can use Java SDK worker
  sessions.
- Use EWR-0 inventory to decide whether this slice needs code, guard, or docs.
  If the proof is already complete, do not add another geo lookup happy path.
- Retain worker-pack capability code under `integrations/xa-mass-worker-pack`.
- Keep or strengthen the SDK worker handler/session runtime use for real
  capability paths, starting from the existing `tool.geo.lookup` proof.
- Keep dev/E2E harness packages separated from production capability packages.
- Do not promote worker-pack command/fault harness behavior into the Java SDK.

Acceptance:

- Worker-pack capability registration uses the same SDK/API-keyed worker
  registration path as external callers, or the inventory records the exact
  remaining gap.
- A task can target the WorkerGroup and receive a real capability result.
- Existing worker-pack architecture guards still separate capability code from
  harness code.

Verification:

```powershell
./mvnw -pl integrations/xa-mass-worker-pack,xa-mass-server -am "-Dtest=WorkerPackGeoLookupExternalSdkIntegrationTest,PhoneDeviceWorkerPackExternalSdkIntegrationTest,WorkerPackArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## EWR-6: Guards, Docs, And Residue Scan

Goal: prevent drift back to sample bootstrap or server-owned registration
proof.

Scope:

- Add or update guards so:
  - prod config cannot enable sample bootstrap by default,
  - server startup does not seed WorkerGroup/worker/task truth,
  - scenario-launcher real-proof runs do not call sample bootstrap,
  - adopter modules such as scenario-launcher and worker-pack mainline do not
    hard-code platform route literals when typed SDK calls exist.
- Keep `sdk/xa-mass-java-sdk` typed clients as allowed route owners; for
  example `WorkerClient` may own `/worker-api/v1/**` literals.
- Update owner docs:
  - `sdk/README.md`,
  - `sdk/xa-mass-java-sdk/README.md`,
  - `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`,
  - `integrations/README.md`,
  - `integrations/xa-mass-scenario-launcher/README.md`,
  - `integrations/xa-mass-worker-pack/README.md`,
  - `xa-mass-server/README.md`,
  - `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` only if guard rules change.
- Update `doc/PROOF_REGISTRY.md` only if proof ownership or representative
  class names change.

Acceptance:

- Active docs describe sample bootstrap as fixture-only.
- Real external worker registration proof is named in the owner docs.
- Residue scan has no accidental sample-bootstrap dependency in SDK/adopter
  mainline.

Verification:

```powershell
rg -n "sample-api/bootstrap|X-Sample-Bootstrap-Key|sample\\.bootstrap|server startup.*worker|startup.*WorkerGroup|/worker-api/v1" integrations xa-mass-server doc roadmap -g "*.java" -g "*.md" -g "*.yml"
rg -n -g "*.java" -g "*.md" -g "*.yml" -- "--skip-dev-bootstrap|devBootstrapEnabled|application-prod\\.yml|sample\\.bootstrap\\.enabled=false|Worker credential binding denied" integrations xa-mass-server sdk doc roadmap
rg -n "/worker-api/v1" sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker -g "*.java"
./mvnw -pl sdk/xa-mass-java-sdk,integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack,xa-mass-server -am test
git diff --check
```

## Non-Goals

- No public policy product, SchedulingPolicyCatalog, or ProjectSchedulingBinding
  implementation.
- No new server startup seed path.
- No production expansion of `/sample-api/bootstrap/**`.
- No Node SDK track.
- No Java socket worker session.
- No new worker credential storage model unless EWR-2 proves the existing API
  key model cannot express worker onboarding.
- No worker-pack capability code migration into SDK modules.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Prod still exposes sample bootstrap | Local fixture path becomes production-like backdoor | EWR-1 config and guard |
| API key scopes are too broad | Worker can register unexpected groups/events | EWR-2 scope mismatch tests |
| SDK helper hides topology side effects | WorkerGroup capability truth becomes implicit | EWR-3 helper rule: no implicit group declaration in session start |
| Scenario proof uses sample bootstrap | External value proof stays fake | EWR-4 no sample bootstrap acceptance |
| Worker-pack harness leaks into SDK | SDK becomes worker-pack product surface | EWR-5 package guard |
| Docs claim production readiness too early | Agents treat staging paths as current fact | EWR-6 owner-doc residue scan |
