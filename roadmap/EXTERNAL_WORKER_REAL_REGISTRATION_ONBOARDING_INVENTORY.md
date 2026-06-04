# External Worker Real Registration Onboarding Inventory

Status: current code inventory for
`EXTERNAL_WORKER_REAL_REGISTRATION_ONBOARDING_ROADMAP.md`.

This inventory separates implemented SDK registration paths from remaining
fixture-only bootstrap surfaces so follow-up work does not add parallel
launcher or worker-pack paths.

## Symbols

| Symbol / path | Current owner | Current role | Classification | Target |
| --- | --- | --- | --- | --- |
| `SampleBootstrapController` | `xa-mass-server` | Optional `/sample-api/bootstrap/**` catalog/rule/submitter fixture endpoint | dev fixture / server-owned MVC surface | Keep property-gated, prod default off, not worker onboarding |
| `sample.bootstrap.enabled` in `application-dev.yml` | `xa-mass-server` | Enables sample bootstrap for dev scenario launchers | dev fixture | Keep explicit dev fixture support |
| `sample.bootstrap.enabled` in `application-prod.yml` | `xa-mass-server` | Set to `false` | implemented prod guard | Keep prod default off |
| `ApiKeyCredentialService` | `xa-mass-server` | API key create/application/approval/revoke/expiry and submitter-auth projection | production host API | Reuse for worker onboarding; do not add new credential storage unless proven necessary |
| `ExternalWorkerApiController` | `xa-mass-server` | `/worker-api/v1/**` external worker registration and polling surface | production host API | Keeps shared `workerId` credential binding check |
| `requireBoundWorkerId(...)` | `xa-mass-server` | Normalizes requested worker id and enforces optional principal `workerId` binding | implemented enforcement | Keep binding centralized for worker register/presence/poll/result/report/ack paths |
| `WorkerClient` | `sdk/xa-mass-java-sdk` | Typed owner of `/worker-api/v1/**` route literals | SDK typed route owner | Allowed to keep route literals |
| `WorkerScenarioRegistrar` | `integrations/xa-mass-scenario-launcher` | Registers WorkerGroup, AdapterNode, NodeGroupBinding, and Worker through `MassPlatform` | implemented integration adopter path | Reuse; do not duplicate in a new launcher mode |
| `ScenarioLauncher` | `integrations/xa-mass-scenario-launcher` | Calls dev bootstrap only when enabled; real-proof runs can use `--skip-dev-bootstrap` | implemented decoupling | Keep one registration mainline through `WorkerScenarioRegistrar` |
| `DevBootstrapClient` | `integrations/xa-mass-scenario-launcher` | Calls `/sample-api/bootstrap/catalog` and `/rules` | dev fixture client | Keep for explicit dev preparation only |
| `ScenarioWorkerRuntime` | `integrations/xa-mass-scenario-launcher` | Starts SDK polling/WebSocket worker sessions | implemented integration adopter path | Reuse |
| `WorkerPackGeoLookupExternalSdkIntegrationTest` | `xa-mass-server` E2E over worker-pack | Proves worker-pack `tool.geo.lookup` as Java SDK polling worker | implemented representative proof | Harden docs/guards only unless a gap is found |
| `GeoLookupWorkerPack` | `integrations/xa-mass-worker-pack` | Builds Java SDK polling worker session for geo lookup | worker capability pack | Keep in worker-pack; do not move to SDK |
| `launch-workers.mjs` and sample launchers | `integrations/samples` | Dev/sample fixture orchestration | dev fixture | Do not treat as Java SDK product surface |

## Dependencies

| Module | Dependency / route | Scope | Reason | Target |
| --- | --- | --- | --- | --- |
| `sdk/xa-mass-java-sdk` | `/worker-api/v1/**` literals in `WorkerClient` | production SDK | Typed route owner | Allowed |
| `integrations/xa-mass-scenario-launcher` | `sdk/xa-mass-java-sdk` | production integration adopter | Real Java SDK proof | Keep |
| `integrations/xa-mass-scenario-launcher` | `/sample-api/bootstrap/**` through `DevBootstrapClient` | dev fixture | Catalog/rule/submitter prep | Remove from real-proof path |
| `integrations/xa-mass-worker-pack` | `sdk/xa-mass-java-sdk` | production capability proof | Worker-pack external worker session | Keep |
| `xa-mass-server` | `SampleBootstrapController` | dev fixture MVC endpoint | Local scenario bootstrap | Keep server-owned and prod default off |

## Decisions

- EWR-1 closed the prod bootstrap contradiction: prod now defaults sample
  bootstrap off and the server architecture guard protects that default.
- EWR-2 implemented worker credential binding through
  `PrincipalContext.attributes["workerId"]`.
- EWR-4 reuses `WorkerScenarioRegistrar`; real-proof runs skip mandatory
  `DevBootstrapClient` preparation instead of adding a second launcher
  registration path.
- EWR-5 starts from an existing worker-pack proof. No duplicate geo lookup
  happy path was added.
- EWR-6 route-literal guards must exclude SDK typed route owners such as
  `WorkerClient` and focus on adopter modules.
