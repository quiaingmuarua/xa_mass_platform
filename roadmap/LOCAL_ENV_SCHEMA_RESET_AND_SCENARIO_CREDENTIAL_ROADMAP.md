# Local Env Schema Reset And Scenario Credential Roadmap

Status: proposed direction document.

## Summary

Local scenario runs currently fail in confusing ways when a durable-local DB
contains old control-plane rows or when the scenario task API key exists only in
legacy seed/projection state. This roadmap converges two related local
readiness problems:

1. local destructive schema reset for pre-release SQLite environments
2. scenario credential preparation through real server operator login and
   API-key lifecycle APIs

The goal is not to add public SDK credential management. The goal is to make
local and integration-test scenario setup real enough to prove the product path:
an operator prepares a task producer credential, the launcher consumes that
credential from a local file, and task creation uses the same public task API as
external callers.

## Current Code Observations

- `doc/INFRA_TRUTH_LAYERS.md` already states that current pre-release schema
  changes may require deleting and recreating local DBs, and that a future
  schema-version check should fail fast with a recreate/reseed message.
- `JdbcStorageRuntime` runs platform storage Flyway migrations in
  `platform_infra/mass-storage-jdbc`.
- `ServerControlPlaneMigrationRunner` runs server-owned Flyway migrations in
  `xa-mass-server`.
- `application-durable-local.yml` uses SQLite by default through
  `mass.storage.mode=jdbc-sqlite`.
- `ControlPlaneSeedImporter` can import catalog/project/rule/API-key/operator
  seed data, but `integrations/samples/dev/scenario/bootstrap.json` includes
  devOnly API-key raw secrets.
- `AuthController` exposes `POST /api/v1/auth/login` for session-mode operator
  login and returns a CSRF token.
- `ApiKeyController` exposes `POST /api/v1/api-keys`; it creates server-owned
  API-key lifecycle rows and returns the one-time raw secret.
- `integrations/xa-mass-scenario-launcher` task config already supports
  `credentials.taskApiKeyFile`; the task launcher consumes existing credentials
  and does not create them.

## Owner Review

- Local schema reset belongs to `xa-mass-server` startup/profile assembly with
  storage support from `platform_infra/mass-storage-jdbc`. It is not runtime
  truth, trace truth, or migration compatibility.
- API-key lifecycle belongs to `xa-mass-server`. Operator login, session cookie,
  CSRF handling, and API-key creation remain server APIs.
- `integrations/xa-mass-scenario-launcher` may provide a local/integration-test
  credential preparation helper that calls those server APIs and writes a local
  secret cache file. It must not redefine credential models or create a stable
  SDK promise.
- `sdk/xa-mass-java-sdk` consumes an API key through `MassPlatform`; it must not
  gain operator login, credential application, or API-key lifecycle helpers in
  this roadmap.

## Boundary Decision

This roadmap owns a local environment preparation lane, not a product migration
lane.

```text
server local profile startup
  -> verify schema fingerprint
  -> fail fast or explicit local reset
  -> apply current SQL
  -> optional explicit seed/import for catalog/operator credentials

scenario credential helper
  -> use existing cache file when present
  -> otherwise operator login or local operator auth mode
  -> POST /api/v1/api-keys with cookie + CSRF + api-key:approve permission
  -> write returned one-time rawSecret to local ignored file

scenario task launcher
  -> read credentials.taskApiKeyFile
  -> create task and append items through Java SDK task APIs
```

## Hard Rules

1. Destructive reset is deny-by-default. It is allowed only when both are true:
   a named local/test profile is allowlisted, and the JDBC URL is a supported
   local file target for that slice.
2. Any non-allowlisted profile, unnamed environment, remote JDBC target,
   PostgreSQL target, or unsupported URL must fail before deleting anything,
   even if the reset property is set.
3. Default schema mismatch behavior is fail-fast with a concrete DB path and
   property/action hint.
4. Schema reset is not migration. Do not add historical upgrade compatibility
   or migration repair logic in this roadmap.
5. The schema fingerprint/reset hook must run before
   `JdbcStorageRuntime.create(...)` and before server-control-plane Flyway
   migrations. A first implementation that stores metadata in the DB must
   explicitly prove how it detects mismatch without migrating first.
6. SQLite control-plane storage must not absorb runtime queue, lease, heartbeat,
   dispatch, result convergence, or trace/audit truth.
7. Scenario credential preparation must call server APIs; do not hand-write
   `xa_api_key_credential` or `xa_principal` rows.
8. The credential helper must not claim it can recover or reuse an existing DB
   credential's raw secret. Raw secret is available only from the create
   response or an existing local cache file.
9. The credential helper must not print raw API-key secrets to normal logs after
   writing the cache file.
10. `scenario.local.example.json` should keep `credentials.taskApiKeyFile`; do
   not check a real API key into the example config.
11. Do not add operator login or API-key lifecycle methods to
   `sdk/xa-mass-java-sdk`.
12. Do not make catalog/rule/API-key seed run automatically on every startup.
    Seed/import remains explicit environment preparation.

## Non-Goals

- No production schema migration compatibility.
- No PostgreSQL destructive reset.
- No SDK public credential-management API.
- No frontend work.
- No worker config support for scenario launcher.
- No automatic worker registration or task creation during server startup.
- No secret manager integration.
- No Redis/runtime reset behavior.

## Do Not Start With

Do not start by putting `taskApiKey` directly into
`scenario.local.example.json` or by inserting rows into SQLite by hand. That
would hide the actual owner boundary: the server owns API-key lifecycle, and the
launcher consumes a credential obtained through the real API path.

## LSR-0 Inventory And Decisions

Goal: close local reset and credential-prep decisions before implementation.

Scope:

- Update `LOCAL_ENV_SCHEMA_RESET_AND_SCENARIO_CREDENTIAL_INVENTORY.md`.
- Decide local reset profile and JDBC URL allowlist:
  - `durable-local` SQLite is the first required target.
  - file-backed H2 is either supported or explicitly deferred.
- Decide hash source list and hash storage location. First-pass preference is
  a SQLite sidecar metadata file so mismatch can be detected before opening the
  DB and before any Flyway migration.
- Decide exact property names.
- Decide the pre-migration execution point. LSR-1 must hook before
  `JdbcStorageRuntime.create(...)`; a later DB-metadata design is allowed only
  if it proves mismatch detection without requiring migration first.
- Decide credential helper auth path:
  - session login required for durable-local proof
  - dev-header may be local-only fallback if still useful
- Decide credential cache semantics:
  - cache file exists: use it, optionally validate through current-credential
    API
  - cache file missing: create a new API key and write the returned one-time
    raw secret
  - existing DB credential without cache: do not promise reuse; either fail
    clearly or define an explicit revoke/recreate or rotate path in a later
    roadmap
- Decide whether to add a catalog/rule seed fixture without API-key raw secrets
  for the new proof.

Acceptance:

- Inventory records all current seed/auth/API-key/storage paths used by this
  roadmap.
- Inventory records the final first-slice decisions by name.
- Property names, allowlisted profiles, supported JDBC URL patterns, and the
  pre-`JdbcStorageRuntime.create(...)` hook point are fixed before LSR-1 starts.
- The credential helper is explicitly classified as integration/local tooling,
  not SDK.

Verification:

```powershell
rg -n "JdbcStorageRuntime|ServerControlPlaneMigrationRunner|AuthController|ApiKeyController|ScenarioTaskLauncherMain|taskApiKeyFile" xa-mass-server integrations/xa-mass-scenario-launcher platform_infra -g "*.java" -g "*.md"
```

## LSR-1 Local Schema Fingerprint And Reset

Goal: prevent stale local DBs from silently producing invalid control-plane
state after schema/resource changes.

Scope:

- Add a server-local schema fingerprint check before current JDBC migrations
  can hide stale local state.
- The hook runs before `JdbcStorageRuntime.create(...)` creates the datasource
  and triggers platform Flyway, and before `ServerControlPlaneMigrationRunner`
  triggers server-control-plane Flyway.
- Hash current SQL resources used by the selected local storage assembly.
- Store the hash in the chosen local metadata location. First-pass expected
  location is a sidecar file beside the SQLite DB; DB metadata table storage is
  deferred unless LSR-0 proves pre-migration mismatch detection.
- On mismatch:
  - default: fail with DB path, old/new hash, and recreate/reseed hint
  - explicit local reset: delete the local DB and metadata, then allow clean DB
    creation
- Allow reset only for LSR-0 allowlisted local profile and local file JDBC URL.
  Every other profile/URL fails before delete.

Acceptance:

- allowlisted `durable-local + jdbc-sqlite local file + matching schema hash`
  starts normally.
- allowlisted `durable-local + jdbc-sqlite local file + mismatched schema hash`
  fails by default with a clear message.
- allowlisted `durable-local + jdbc-sqlite local file + mismatched schema hash
  + explicit reset=true` deletes/recreates the local DB and starts with the
  current schema.
- reset requested for any non-allowlisted profile, unnamed environment, remote
  JDBC URL, PostgreSQL URL, or unsupported URL fails before deleting anything.
- A focused proof shows the reset check executes before platform and server
  Flyway migrations.
- Startup-level proof exists; direct unit tests alone are not enough.
- Owner docs explain this is local destructive reset, not migration.

Verification:

```powershell
./mvnw.cmd -pl xa-mass-server -am "-Dtest=*Schema*Reset*Test,*Profile*Startup*Test,ServerMainSourceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check
```

## LSR-2 Scenario Credential Preparation Helper

Goal: prepare a local task producer API key through the real server API path and
write it to the launcher cache file.

Scope:

- Add a scenario-launcher helper main, for example
  `ScenarioCredentialBootstrapMain`.
- The helper first checks the configured cache file:
  - if present, use it as the task API key
  - optionally validate it through a current-credential route if LSR-0 selects
    that behavior
  - do not attempt to recover raw secret from an existing DB credential
- If the cache file is missing, the helper authenticates as an operator:
  - session login path first
  - optional dev-header fallback only if LSR-0 explicitly keeps it
- For session mode, the helper preserves the login cookie and sends
  `X-Mass-Csrf-Token` on `POST /api/v1/api-keys`.
- The operator proof user must have `api-key:approve`; the helper should fail
  clearly when the permission is missing.
- The helper calls `POST /api/v1/api-keys` to create a new task producer
  credential for the configured project/event scopes. It must not describe this
  as "reuse" unless the local cache file already exists.
- The helper writes the returned one-time `rawSecret` to a local file such as
  `integrations/xa-mass-scenario-launcher/examples/secrets/task-api-key.txt`.
- The helper must not print the raw secret after writing it.
- The helper must be documented as local/integration-test tooling, not SDK.

Acceptance:

- Helper uses an existing cache file without creating a duplicate credential,
  or creates a new task producer credential for `crawlerApp` /
  `crawler.fetch-page` when the cache file is missing.
- Helper preserves session cookies and sends `X-Mass-Csrf-Token` when using
  session auth.
- Helper proof operator has `api-key:approve`; missing permission is covered.
- Helper fails clearly when operator login fails, CSRF is missing, the API-key
  route is unauthorized, duplicate principal/create conflict occurs, cache file
  is absent and creation is disabled, or the target catalog scope is not
  prepared.
- Existing `ScenarioTaskLauncherMain` continues to consume only
  `credentials.taskApiKeyFile`; it does not login or create credentials.
- `sdk/xa-mass-java-sdk` has no new operator-login or API-key lifecycle API.

Verification:

```powershell
./mvnw.cmd -pl integrations/xa-mass-scenario-launcher,sdk/xa-mass-java-sdk -am test
rg -n "auth/login|api-keys|ScenarioCredential|taskApiKeyFile" integrations/xa-mass-scenario-launcher sdk/xa-mass-java-sdk -g "*.java" -g "*.md"
rg -n "login\\(|api-keys" sdk/xa-mass-java-sdk/src/main/java
```

The final `rg` command should not find SDK public credential-management code.

## LSR-3 Real Local Scenario Proof

Goal: prove the intended local flow end to end without raw API-key seed secrets.

Scope:

- Prepare a local catalog/rule/operator seed path that does not require raw
  task API-key secrets.
- Start server with the local profile and explicit seed/import.
- Run credential helper to create the task API key and write the cache file.
- Run `ScenarioTaskLauncherMain --config
  integrations/xa-mass-scenario-launcher/examples/scenario.local.example.json`.
- Confirm task creation and item append succeed.

Acceptance:

- The proof uses API-key lifecycle creation, not checked-in raw task secrets.
- Catalog/rules are explicit setup and are not created by the launcher.
- The launcher does not receive operator credentials.
- Re-running after local schema reset produces a clean environment and a new
  valid cached task API key.
- The proof can be run from documented commands or a focused integration test.

Verification:

```powershell
./mvnw.cmd -pl xa-mass-server,integrations/xa-mass-scenario-launcher -am "-Dtest=*Scenario*Credential*IntegrationTest,*Schema*Reset*IntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## LSR-4 Docs, Guards, And Residue

Goal: make the boundary durable for future agents.

Scope:

- Update:
  - `doc/INFRA_TRUTH_LAYERS.md`
  - `xa-mass-server/README.md`
  - `integrations/README.md`
  - `integrations/xa-mass-scenario-launcher/README.md`
  - `sdk/README.md`
- Add or update guards so SDK does not gain operator login/API-key lifecycle
  surface.
- Add startup/profile guard for prod reset prohibition.
- Add startup/profile guard for destructive reset allowlist enforcement.
- Residue scan old raw-secret-primary demo instructions.

Acceptance:

- Active docs say local schema reset is destructive and local-only.
- Active docs say API-key preparation through operator login is an integration
  helper, not SDK.
- Raw-secret seed remains documented only as explicit local fixture fallback.
- No active README presents checked-in `crawler-task-api-key` raw-secret seed as
  the preferred scenario credential path.
- Roadmap status is updated honestly:
  - `active` if later phases remain
  - `implemented mainline` only after LSR-3/LSR-4 acceptance is satisfied

Verification:

```powershell
rg -n "crawler-task-api-key|allow-local-fixture-raw-secrets|taskApiKeyFile|ScenarioCredential|schema reset|reset-on-mismatch|schema fingerprint" doc xa-mass-server integrations sdk roadmap -g "*.md"
rg -n "auth/login|api-keys" sdk/xa-mass-java-sdk/src/main/java
git diff --check
```

## Completion Criteria

The roadmap can be marked complete only when:

1. durable-local stale SQLite DBs cannot silently run with mismatched schema
   resources
2. non-allowlisted profiles/URLs cannot auto-reset or delete DB data
3. scenario credential preparation can create a real task producer API key
   through operator auth and API-key lifecycle APIs
4. `scenario.local.example.json` consumes a file-based API key cache
5. task launcher can create and append through Java SDK after helper
   preparation
6. Java SDK has no operator-login or API-key lifecycle public methods
7. owner docs describe the local-only nature of reset and credential helper
8. raw-secret seed is retained only as explicit local fixture fallback

## Suggested Implementation Order

1. LSR-0 inventory decisions.
2. LSR-1 schema fingerprint fail-fast before enabling auto-reset.
3. LSR-2 credential helper using current server APIs.
4. LSR-3 local proof without raw task-secret seed.
5. LSR-4 docs/guards/residue.

## Risks

| Risk | Mitigation |
| --- | --- |
| Local reset accidentally deletes non-local data | Allowlist only named local/test profiles plus supported local file JDBC URLs; startup tests cover non-allowlisted profile/URL failure before delete. |
| Helper becomes de facto SDK API | Keep helper under scenario-launcher, update SDK guard and docs. |
| Raw secrets leak to logs | Write to file only; redact command output and tests. |
| Catalog seed and credential prep remain coupled | Add catalog/rule seed proof without API-key raw secrets. |
| Hash misses a migration resource | LSR-0 records exact resource source list; tests mutate/override hash input. |
| Reset hides real migration bugs | Default mismatch behavior fails; auto-reset requires explicit local flag. |
| Existing DB credential has no raw secret | Use existing cache file or create a new credential; do not promise raw-secret recovery from DB rows. |
