# Admin Env Init State Model Roadmap

Archived on 2026-06-09.

Current truth owner: `tools/xa-mass-admin-cli/README.md` for `xa-mass-admin
env init/verify`, typed env config, marker semantics, and admin HTTP
automation boundaries.

This document is historical roadmap context only. Do not use it as proof of
current behavior; verify against current code, tests, owner README files, and
`xa-mass-testing` confidence-smoke docs.

Status: mainline implemented. AEI-1, AEI-2, AEI-3, and AEI-5 are implemented
through `tools/xa-mass-admin-cli`; AEI-4 remains a deferred optional mode.
`ScenarioCredentialBootstrapMain` remains only as transitional legacy residue
for existing scenario-launcher commands/tests.

## Summary

`PLATFORM_CONFIDENCE_GATE_ROADMAP.md` depends on a reliable environment
initialization path, but the initialization semantics should be decided before
the confidence gate starts using it as proof.

Execution order with PCG:

```text
PCG-1
  -> create tools/xa-mass-admin-cli module skeleton
AEI-1/2/3
  -> define typed config, verify, apply, and marker semantics in that module
PCG-2B+
  -> consume xa-mass-admin env init/verify as the confidence setup owner
```

This roadmap is a prerequisite for PCG env-init behavior. It still depends on
PCG-1 for the admin CLI module skeleton; do not start AEI implementation by
creating a second initializer elsewhere.

This roadmap defines `xa-mass-admin env init` as an operator/admin HTTP flow
driven by a typed JSON config model plus an optional local marker file. The
marker file is a local checkpoint and acceleration hint, not server truth.

Target flow:

```text
xa-mass-admin env init --config scenario.local.json
  -> parse typed AdminEnvConfig
  -> auth config/login
  -> marker check according to state.mode
  -> verify required facts
  -> apply/repair missing desired state if needed
  -> verify again
  -> write env-init.json marker when state.mode=file
```

## Current Code Observations

- `ScenarioCredentialBootstrapMain --kind env` currently performs:
  - `GET /api/v1/auth/config`
  - operator login or dev-header auth
  - unconditional `POST /api/v1/control-plane/catalog:sync`
  - unconditional `POST /api/v1/control-plane/rules:sync`
  - hardcoded scenario catalog verification
  - task API-key validation/creation
  - workerId-bound worker API-key validation/creation from `workers.json`
- `ControlPlaneInitializationController` already exposes:
  - `POST /api/v1/control-plane/catalog:sync`
  - `POST /api/v1/control-plane/rules:sync`
- `ApiRouteAuthorizationCatalog` protects these routes with operator
  permissions:
  - catalog sync uses `config:edit`
  - rules sync uses `rule:edit`
- `application-durable-local.yml` already enables local SQLite schema
  fingerprint reset on mismatch. Schema reset is server startup behavior, not
  env-init behavior.
- `PLATFORM_CONFIDENCE_GATE_ROADMAP.md` now chooses
  `tools/xa-mass-admin-cli env init` as the environment initializer owner.

## Owner Review

- `xa-mass-server` owns operator auth, CSRF, route authorization, catalog/rule
  write APIs, API-key lifecycle, and local schema reset.
- `tools/xa-mass-admin-cli` owns the env init command and its typed config
  model. It calls server HTTP APIs; it does not write DBs directly.
- `integrations/xa-mass-scenario-launcher` owns task/worker process launchers
  and scenario manifests. It consumes prepared credentials.
- `sdk/xa-mass-java-sdk` remains ordinary API-key external actor SDK and must
  not gain env init or operator/admin credential lifecycle APIs.
- `env-init.json` is local checkpoint metadata. It is not server truth, not
  audit truth, and not a substitute for verification.

## Boundary Decision

`env init` is not a seed importer and not a schema reset owner.

```text
server startup
  -> minimal operator credential
  -> local schema reset if configured
  -> no scenario task/worker/runtime truth

xa-mass-admin env init
  -> typed JSON config
  -> operator HTTP
  -> catalog/rule desired state ensure
  -> API-key create/verify
  -> env-init.json marker after verification

scenario task/worker launchers
  -> read prepared API keys
  -> use Java SDK task/worker APIs
```

## Config Model

The admin CLI must parse JSON into a typed model instead of letting callers or
future agents interpret raw JSON ad hoc.

First-slice shape:

```json
{
  "server": {
    "baseUrl": "http://127.0.0.1:8088",
    "profile": "memory-local"
  },
  "operator": {
    "user": "ops-admin",
    "passwordEnv": "MASS_OPERATOR_PASSWORD"
  },
  "environment": {
    "mode": "apply",
    "catalogManifest": "scenario.catalog.seed.json",
    "rulesManifest": "../../samples/dev/scenario/rules.json"
  },
  "credentials": {
    "taskCredential": {
      "apiKeyFile": "secrets/task-api-key.txt",
      "principalId": "scenario-task-producer",
      "createdForUserId": "ops-admin",
      "permissions": [
        "task:create",
        "task:edit",
        "task:view"
      ],
      "projectScopes": [
        "crawlerApp"
      ],
      "eventScopes": [
        "crawler.fetch-page"
      ],
      "rawSecretFile": "secrets/task-api-key.txt"
    },
    "workerCredentials": {
      "workerSpecFile": "workers.confidence.json",
      "principalIdTemplate": "scenario-worker-${workerId}",
      "createdForUserId": "ops-admin",
      "permissions": [
        "worker:poll"
      ],
      "projectScopesFromWorkerBindings": true,
      "eventScopesFromWorkerBindings": true,
      "rawSecretSource": "workerSpec.workerKey",
      "workerIdAttribute": "workerId"
    }
  },
  "state": {
    "mode": "file",
    "markerFile": ".state/env-init.json"
  },
  "verify": {
    "requiredProjects": [
      "crawlerApp",
      "deviceProbe"
    ],
    "requiredEvents": [
      "crawler.fetch-page",
      "stock.quote.fetch",
      "probe.phone.metadata"
    ]
  }
}
```

Suggested Java model:

```java
record AdminEnvConfig(
        ServerConfig server,
        OperatorConfig operator,
        EnvironmentConfig environment,
        CredentialConfig credentials,
        StateConfig state,
        VerifyConfig verify
) {}

enum EnvInitMode {
    VERIFY,
    APPLY,
    APPLY_IF_EMPTY,
    RESET_AND_APPLY
}

enum EnvStateMode {
    MEMORY,
    FILE
}
```

Credential config is desired credential state, not just cache file location.
For every task or worker API key that env init creates or verifies, the typed
model must provide either explicit fields or an explicit derivation rule for:

- `principalId`
- `createdForUserId`
- `permissions`
- `projectScopes`
- `eventScopes`
- raw secret source or generated-secret output file
- credential attributes that affect auth, especially worker `workerId`
  binding

Verification must compare the current API-key principal against this desired
state. A key that exists but has the wrong user, scope, permission, or
workerId binding is not current.

First confidence config must use a small worker spec. Do not point the first
lane at `integrations/samples/dev/scenario/workers.json`, because that fixture
expands to the broad sample topology. Use a dedicated one-worker fixture or an
explicit bounded worker selection in config.

Manifest naming transition:

- First implementation may point at existing files:
  - `integrations/xa-mass-scenario-launcher/examples/scenario.catalog.seed.json`
  - `integrations/samples/dev/scenario/rules.json`
- AEI-0 must decide whether to copy/rename them to manifest names before
  AEI-1 tests reference those names.
- Do not make example config point to non-existent manifest files.

## State Semantics

`state.mode=memory`:

- ignore marker file for skip decisions
- always run verification
- apply missing desired state when `environment.mode=apply`
- may write a run report, but not a reusable skip marker

`state.mode=file`:

- read `markerFile` if present
- compare marker against current config and manifest fingerprints
- if marker matches, run cheap verify
- if verify passes, no-op
- if marker missing, mismatched, or verify fails, apply/repair and write marker

Do not infer state mode from marker file presence. The config must say whether
state is `memory` or `file`.

## Marker File

Default marker file:

```text
.state/env-init.json
```

Marker rules:

- marker is written only after successful verify
- marker never contains raw API-key secrets
- marker records hashes and evidence used to decide safe no-op
- marker existence alone never proves environment readiness
- CI should prefer fresh state or always run verify even if marker exists

Suggested marker shape:

```json
{
  "version": 1,
  "baseUrl": "http://127.0.0.1:8088",
  "profile": "memory-local",
  "mode": "apply",
  "catalogManifestSha256": "...",
  "rulesManifestSha256": "...",
  "workerSpecSha256": "...",
  "taskCredentialSha256": "...",
  "workerCredentialPolicySha256": "...",
  "requiredProjects": [
    "crawlerApp",
    "deviceProbe"
  ],
  "requiredEvents": [
    "crawler.fetch-page",
    "stock.quote.fetch",
    "probe.phone.metadata"
  ],
  "initializedAt": "2026-06-09T00:00:00Z",
  "verifiedAt": "2026-06-09T00:00:00Z"
}
```

## Env Init Modes

`verify`:

- no writes
- fails when required catalog/rules/API-key facts are missing or invalid

`apply`:

- default mode for confidence gate
- verify first when cheap
- upsert/repair missing desired catalog/rules/API-key facts
- verify after apply
- write marker when state mode is file

`apply-if-empty`:

- only applies when the selected catalog/rules state is empty
- if non-empty but incomplete, fails with a drift error
- useful for conservative local setup, not the confidence gate default
- deferred optional mode; do not implement in the first required confidence
  slice

`reset-and-apply`:

- destructive
- requires explicit `--confirm` or config `confirm=true`
- local/test only
- deferred optional mode; do not implement in the first required confidence
  slice
- schema mismatch remains server local-schema-reset responsibility

## Hard Rules

1. Do not use marker existence as the only readiness check.
2. Do not make `env init` clear DB tables on schema mismatch. Schema mismatch
   belongs to server startup local schema reset.
3. Do not let `env init` create task shells, task items, workers, WorkerGroups,
   AdapterNodes, NodeGroupBindings, leases, queues, results, sessions, CSRF
   tokens, trace rows, or audit rows as seed data.
4. Do not add env init or operator login to `xa-mass-java-sdk`.
5. Do not make confidence scripts implement catalog/rule/API-key HTTP calls.
6. Relative paths in config resolve relative to the config file directory.
7. `operator.passwordEnv` or `operator.passwordFile` is required for process
   confidence and real server verification. Dev-header or fixture auth may
   exist only in isolated unit/stub tests and must not be the env-init proof
   path.
8. `state.mode=file` requires `state.markerFile`.
9. `state.mode=memory` must not use marker file for skip/no-op decisions.
10. API-key raw secrets may be written only to configured gitignored cache
    files and must not be printed.
11. Operator login/readiness is the first auth precondition. If a clean server
    has no active login-capable operator credential, fail as
    `operator-auth/readiness`; do not continue and report catalog/API-key
    failures.
12. Task and worker API-key verification must validate principal, user, scopes,
    permissions, and required attributes, not only whether a raw secret
    authenticates.

## Do Not Start With

- Do not copy `ScenarioCredentialBootstrapMain` into admin CLI as a bulk
  implementation. Inventory it, then rebuild typed config and verify/apply
  slices explicitly.
- Do not start by writing marker skip behavior. Marker is useful only after
  typed config parsing and server verification exist.
- Do not start with the full sample worker fixture. The first confidence lane
  needs one worker / one task scale before broader scenario coverage.
- Do not preserve both `ScenarioCredentialBootstrapMain --kind env` and
  `xa-mass-admin env init` as independent long-term owners.

## Non-Goals

- No frontend UI for env init.
- No production secret manager integration.
- No historical DB migration compatibility.
- No schema reset implementation inside admin CLI.
- No OpenAPI generated admin client.
- No public Java SDK env-init API.
- No worker config redesign beyond reading existing worker specs for API-key
  preparation.
- No new mainline behavior added to `ScenarioCredentialBootstrapMain`.

## AEI-0 Inventory And Current Behavior Capture

Goal: capture current env init behavior and decide the first config fields
without changing behavior.

Scope:

- Inventory current `ScenarioCredentialBootstrapMain --kind env` behavior.
- Inventory existing `/api/v1/control-plane/catalog:sync` and
  `/api/v1/control-plane/rules:sync` route semantics and tests.
- Inventory current scenario manifest files and task/worker key files.
- Decide manifest naming that does not imply startup seed. Target names are:
  - `scenario.catalog.manifest.json`
  - `scenario.rules.manifest.json`
- Decide whether first-slice examples use existing file names or create/copy
  the manifest-named files immediately. Examples and tests must reference
  files that exist in the same slice.
- Inventory current task and worker API-key creation fields:
  - principal ID
  - created-for user
  - permissions
  - project scopes
  - event scopes
  - raw secret source
  - workerId-bound attributes
- Decide the small first confidence worker fixture or explicit worker
  selection mechanism. Do not use the broad expanded sample fixture as the
  first proof lane.
- Inventory minimal operator credential startup/readiness requirements.
- Inventory current password/env defaults and failure messages.
- Decide first config file path and example name.
- Decide gitignore location for marker and API-key cache files.

Acceptance:

- Inventory states current unconditional sync behavior.
- Inventory states selected first-slice config model fields.
- Inventory states selected marker path and cache file paths.
- Inventory records how old `*.seed.json` or sample fixture names migrate to
  manifest names without preserving startup seed semantics.
- Inventory records credential desired-state fields and current-key equality
  rules.
- Inventory records the operator-readiness diagnostic that must run before
  catalog/rule/API-key checks.
- Inventory records first confidence gate worker scale as one worker or an
  explicitly bounded selection.
- Inventory states first confidence gate should use `environment.mode=apply`.
- No code behavior changes.

Verification:

```bash
rg -n "ScenarioCredentialBootstrapMain|control-plane/catalog:sync|control-plane/rules:sync|task-api-key|worker-api-key|env-init|scenario.catalog.seed|scenario.catalog.manifest|scenario.rules.manifest" integrations xa-mass-server roadmap -g "*.java" -g "*.md" -g "*.json" -g "*.yml"
```

## AEI-1 Typed Config Model

Goal: introduce `AdminEnvConfig` and validation before moving behavior.

Dependencies:

- `PLATFORM_CONFIDENCE_GATE_ROADMAP.md` PCG-1 has created the
  `tools/xa-mass-admin-cli` module skeleton.

Scope:

- Add typed config records under `tools/xa-mass-admin-cli`.
- Do not add new config/marker behavior to `ScenarioCredentialBootstrapMain`.
- Implement:
  - JSON parsing
  - relative path resolution from config file directory
  - enum parsing for `environment.mode` and `state.mode`
  - typed task credential desired state
  - typed worker credential desired state or explicit derivation rules from
    worker specs
  - validation errors with field paths
- Add example config file.

Acceptance:

- Config parser rejects unknown/missing critical fields with actionable
  messages.
- Config parser rejects task credential config that lacks principal, user,
  permissions, scopes, and raw secret source/output.
- Config parser rejects worker credential config that lacks worker spec input,
  principal derivation, permissions, scope derivation, raw secret source, and
  workerId binding attribute.
- Example config references existing catalog/rule files or files created in
  this slice.
- Example config uses the small first confidence worker fixture or an explicit
  bounded worker selection.
- `state.mode=FILE` without marker file fails.
- `state.mode=MEMORY` with marker file does not use it as skip input.
- `operator.passwordEnv` resolves from environment at runtime, not at parse
  documentation time.
- No env init behavior changes yet.
- `ScenarioCredentialBootstrapMain` remains untouched except for later residue
  removal or bounded delegation.

Verification:

```bash
./mvnw -q -pl tools/xa-mass-admin-cli -am "-Dtest=*AdminEnvConfig*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "AdminEnvConfig|env-init.json|state.mode|markerFile" integrations/xa-mass-scenario-launcher/src/main/java
```

## AEI-2 Verify Command

Goal: make verification explicit before applying state.

Scope:

- Add `xa-mass-admin env verify --config <file>`.
- Verify:
  - server health
  - auth config
  - operator credential readiness and operator login when required
  - required projects/events
  - task API-key current against configured desired credential state
  - worker API-key current against configured desired credential state for
    every configured worker in the first confidence config; larger configs may
    later add a sampled verification mode with an explicit count summary
- Do not write catalog/rules/API keys.
- Do not write marker.

Acceptance:

- A clean server without an active login-capable operator credential fails as
  `operator-auth/readiness`, before catalog/rule/API-key diagnostics.
- Clean uninitialized server fails verify with missing-fact diagnostics.
- Initialized server passes verify.
- API key that authenticates but has wrong principal, user, scope, permission,
  or workerId binding fails as stale/mismatched credential.
- Invalid task API key and worker API key failures are categorized separately.
- Marker-matched no-op still verifies server health, required projects/events,
  task API key, and configured worker API keys.
- Verify output can be consumed by confidence gate.

Verification:

```bash
./mvnw -q -pl tools/xa-mass-admin-cli -am "-Dtest=*AdminEnvVerify*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## AEI-3 Apply Mode And Marker

Goal: implement default `env init` semantics.

Scope:

- Add `xa-mass-admin env init --config <file>`.
- Implement `environment.mode=apply`.
- Implement `state.mode=memory` and `state.mode=file`.
- In file mode:
  - read marker
  - compare base URL, profile, mode, manifest hashes, worker spec hash, and
    required facts
  - cheap verify when marker matches
  - apply when marker is missing/mismatched/verify fails
  - write marker only after successful verify
- Apply path:
  - catalog sync/upsert
  - rules sync/upsert
  - task API-key validate/create from configured credential desired state
  - worker API-key validate/create from configured worker credential desired
    state and worker specs

Acceptance:

- First run applies desired state and writes marker in file mode.
- Second run with unchanged marker performs verify/no-op.
- Marker mismatch triggers apply/verify/rewrite.
- Memory mode never uses marker to skip.
- Marker never contains raw API-key secrets.
- Created task and worker API keys match configured principal, user, scopes,
  permissions, and workerId binding attributes.
- Failure output distinguishes auth, catalog, rule, task-key, worker-key, and
  verify failures.

Verification:

```bash
./mvnw -q -pl tools/xa-mass-admin-cli -am "-Dtest=*AdminEnvInit*Test,*AdminEnvMarker*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## AEI-4 Optional Apply-If-Empty Mode

Goal: support conservative local initialization without making it the confidence
gate default.

Status: deferred optional slice. Do not implement before `verify` and `apply`
are stable in the confidence gate.

Scope:

- Add `environment.mode=apply-if-empty`.
- Define "empty" using required fact categories:
  - no relevant projects/events
  - no relevant rules
  - no matching API-key cache/current credential
- If state is non-empty but incomplete, fail with drift diagnostics instead of
  silently applying.

Acceptance:

- Empty server applies desired state.
- Partially initialized server fails with drift diagnostics.
- Fully initialized server verifies and no-ops.
- Confidence gate docs keep `apply` as the default.

Verification:

```bash
./mvnw -q -pl tools/xa-mass-admin-cli -am "-Dtest=*ApplyIfEmpty*Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## AEI-5 Residue And PCG Handoff

Goal: make PCG consume the new env init semantics.

Scope:

- Update `PLATFORM_CONFIDENCE_GATE_ROADMAP.md` to depend on this roadmap for
  env init semantics.
- Update scenario launcher README to point to admin env init.
- Remove/demote old `ScenarioCredentialBootstrapMain` env owner language.
- Rename or relocate active scenario catalog/rules inputs to manifest names if
  they are currently named as startup seed files.
- Ensure `ScenarioTaskLauncherMain` and `ScenarioWorkerLauncherMain` consume
  prepared credential files and do not own environment initialization.

Acceptance:

- One active env init owner remains: `xa-mass-admin env init`.
- PCG can call `env init --config` and `env verify --config`.
- Old bootstrap wording is archived or labeled transitional.
- Active docs explain marker semantics and memory/file state mode.
- Active docs use manifest wording for env init inputs, not startup seed
  wording.

Verification:

```bash
rg -n "ScenarioCredentialBootstrapMain|credential-bootstrap|env-init.json|apply-if-empty|state.mode|env verify|env init|scenario.catalog.seed|startup seed" integrations tools doc roadmap sdk -g "*.java" -g "*.md" -g "*.json" -g "*.xml"
git diff --check
```

## Test Strategy

- Config parser tests for defaults, path resolution, and validation.
- Marker tests for hash match/mismatch and no raw secret output.
- Verify tests for missing project, missing event, invalid task key, invalid
  worker key, and auth failure.
- Apply tests against stub server first, then PCG process smoke against real
  server.
- Guard tests to keep env init out of `xa-mass-java-sdk`.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Marker becomes fake truth | Stale local file hides server drift | Always run cheap verify before no-op |
| Apply-if-empty hides partial drift | Launcher fails later with unclear error | Non-empty incomplete state fails with drift diagnostics |
| Env init becomes schema reset | Control-plane lifecycle and schema lifecycle mix | Keep schema reset in server startup; destructive reset needs separate explicit command |
| Config JSON becomes ad hoc | Future agents reinterpret fields differently | Typed config model with validation and tests |
| Admin CLI duplicates SDK actor behavior | Boundary confusion | Env init creates credentials/config only; task/worker launchers use Java SDK |

## Final Target

`xa-mass-admin env init` is predictable, typed, and cheap to rerun:

```text
typed config
  -> explicit state mode
  -> marker as checkpoint only
  -> verify before no-op
  -> apply desired control-plane/auth state
  -> scenario launchers consume prepared environment
```

This gives the confidence gate a stable prerequisite without making server
startup seed or script-level HTTP calls the mainline setup path.
