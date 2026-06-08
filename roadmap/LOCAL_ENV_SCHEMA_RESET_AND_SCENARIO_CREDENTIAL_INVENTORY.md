# Local Env Schema Reset And Scenario Credential Inventory

Status: current code inventory for
`LOCAL_ENV_SCHEMA_RESET_AND_SCENARIO_CREDENTIAL_ROADMAP.md`.

## Scope

This inventory covers the local/integration-test readiness path for running
`integrations/xa-mass-scenario-launcher` against a real `xa-mass-server`.

It does not define Java SDK public surface. It records how a local server
environment is prepared so that a scenario task producer can consume an
existing API key through `credentials.taskApiKeyFile`.

## Current Symbols

| Symbol | Current Owner | Current Role | Classification | Target |
| --- | --- | --- | --- | --- |
| `JdbcStorageRuntime` | `platform_infra/mass-storage-jdbc` | Creates datasource and immediately runs platform storage Flyway migrations. | JDBC control-plane storage adapter | Stay generic storage adapter. Local schema reset must hook before `JdbcStorageRuntime.create(...)`, not after this object exists. |
| `ServerControlPlaneMigrationRunner` | `xa-mass-server` | Runs server-owned Flyway migrations under `server-control-plane` when the bean is created. | server control-plane migration owner | Participate in local schema fingerprint source list; reset must run before this migration path. |
| `mass.storage.mode=jdbc-sqlite` | `xa-mass-server` profile assembly | Durable-local SQLite control-plane storage. | local durable control-plane storage | First target for destructive local schema reset on mismatch. |
| `mass.storage.jdbc.url` | `xa-mass-server` profile assembly | Points to SQLite/H2/Postgres JDBC database. | storage location config | Reset allowlist must require a supported local file JDBC URL; Postgres and remote URLs are never auto-reset. |
| `data/xa-mass-sqlite/xa_mass.db` | local environment | Current durable-local SQLite DB file. | local control-plane data | May be deleted only by explicit local reset config. |
| `ControlPlaneSeedImporter` | `xa-mass-server` | Imports catalog/projects/rules/API keys/operator credentials from explicit seed files. | environment bootstrap | Remain explicit; do not become automatic migration. |
| `integrations/samples/dev/scenario/bootstrap.json` | integration fixture | Contains events/projects and devOnly API-key `rawSecret` entries. | legacy local fixture | Keep as fallback; not the preferred real credential preparation proof. |
| `control-plane-seed/operator-credentials.json` | `xa-mass-server` | Password-hash operator credential seed. | server-owned operator credential bootstrap | Used to create login-capable local operator before scenario credential helper runs. |
| `AuthController` `/api/v1/auth/login` | `xa-mass-server` | Operator session login; returns CSRF token and writes session cookie in session mode. | server public operator auth route | Credential helper may call it for local/integration setup only; helper must preserve cookie and CSRF. |
| `ApiKeyController` `/api/v1/api-keys` | `xa-mass-server` | Creates API-key lifecycle record and returns one-time raw secret. | server API-key lifecycle route | Credential helper may call it after operator auth; SDK must not wrap it. Existing DB credentials cannot expose raw secret again. |
| `ApiKeyCredentialService` | `xa-mass-server` | Owns API-key lifecycle truth and projects active credentials to auth projection. | server credential truth | Scenario key must exist here, not only in `xa_principal`. |
| `scenario.local.example.json` | `integrations/xa-mass-scenario-launcher` | Human task config example with `credentials.taskApiKeyFile`. | launcher local config | Keep file-based secret reference; do not check in real secret. |
| `examples/secrets/task-api-key.txt` | local filesystem | User-created local API-key cache. | volatile local secret cache | May be written by helper; must stay gitignored. |
| `ScenarioTaskLauncherMain` | `integrations/xa-mass-scenario-launcher` | Consumes existing task API key to create task and append items. | SDK adopter task producer | Must not login, create credentials, or seed server state. |

## Current Gaps

1. There is no schema fingerprint or reset guard. A stale local SQLite DB can
   survive schema/seed shape changes and produce confusing API-key/catalog
   mismatches.
2. The current seed importer is explicit and off by default, but the checked-in
   scenario fixture combines catalog metadata with devOnly raw API-key secrets.
   That makes "catalog prepared" and "credential prepared" easy to confuse.
3. `scenario.local.example.json` uses a task API-key file, but there is no
   helper that logs in as an operator, creates a task producer API key, and
   writes the returned one-time secret to that file.
4. API-key lifecycle truth lives in `xa_api_key_credential`; old or derived
   rows in `xa_principal` are not enough for task API authentication.
5. `ApiKeyController` can create API keys but does not accept caller-specified
   raw secrets. That is correct for the real lifecycle, but it means local
   launcher configs need a cache file populated from the one-time response.
6. Reusing an existing DB credential is not enough for launcher setup unless
   the local cache file already contains the raw secret. Otherwise the helper
   must create a new credential or follow an explicit revoke/recreate/rotate
   decision.
7. The scenario launcher README still presents raw-secret seed as the primary
   local setup path. That is useful as a fallback but not the desired real
   integration-test setup.

## Decisions To Close In Slice 0

1. Exact local profile and JDBC URL allowlist where destructive schema reset is
   allowed. Current preference: named local/test profile plus file-backed
   SQLite target only for the first implementation.
2. Whether the first reset implementation targets SQLite only, or also local
   file-backed H2.
3. Exact schema hash inputs:
   - server-owned SQL resources
   - platform JDBC migration resources
   - optionally profile/storage mode identifiers
4. Hash storage location:
   - first-pass preference: sidecar file beside SQLite DB, because it can be
     checked before opening/migrating the DB
   - DB metadata table is allowed only if the design proves mismatch detection
     without first running platform or server Flyway
5. Startup behavior on mismatch:
   - fail with recreate/reseed message by default
   - delete/recreate only when explicit local reset flag is enabled
6. Credential helper auth path:
   - session login first
   - dev-header fallback only for local profiles, if still needed
   - session helper must retain cookie and send `X-Mass-Csrf-Token`
   - proof operator must have `api-key:approve`
7. Credential cache semantics:
   - cache file exists: use it or optionally validate it
   - cache file missing: create a new API key and write returned one-time
     secret
   - existing DB credential without cache is not reusable unless a separate
     rotate/recreate decision is made
8. Local catalog/rule preparation path that does not require raw API-key seed
   secrets.

## Hard Boundary

- Schema reset is local destructive environment management, not migration.
- Schema reset must run before `JdbcStorageRuntime.create(...)` and before
  server-control-plane Flyway migration.
- Operator login and API-key creation are server API usage by a scenario helper,
  not Java SDK public contract.
- Task launcher config consumes `taskApiKeyFile`; it must not create the key.
