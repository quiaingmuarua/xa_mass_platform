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

## Implemented Outcomes

1. `LocalSchemaResetGuard` now runs from server startup assembly before
   `JdbcStorageRuntime.create(...)`. It hashes platform and server
   control-plane migration SQL and stores a sidecar fingerprint beside the
   durable-local SQLite DB.
2. Existing `durable-local` SQLite DBs without a sidecar, or with a mismatched
   sidecar, reset by default because this repo is pre-release and does not
   preserve historical local DB compatibility. Destructive reset is still
   allowlisted to durable-local file-backed SQLite; non-allowlisted profiles,
   PostgreSQL, remote JDBC URLs, and unsupported targets fail before delete.
3. `scenario.local.example.json` still references
   `credentials.taskApiKeyFile`, but the preferred setup path now populates
   that file through `ScenarioCredentialBootstrapMain`.
4. `ScenarioCredentialBootstrapMain` validates an existing cache through
   `GET /api/v1/api-keys:current`; stale cache is refreshed through operator
   login plus `POST /api/v1/api-keys`, or rejected when refresh is disabled.
5. API-key lifecycle truth remains in `xa_api_key_credential`; the helper does
   not recover raw secrets from DB rows and does not add credential lifecycle
   APIs to `sdk/xa-mass-java-sdk`.
6. `integrations/xa-mass-scenario-launcher/examples/scenario.catalog.seed.json`
   prepares local catalog metadata without API-key raw secrets. The old
   raw-secret sample seed remains an explicit local fixture fallback only.
7. Scenario launcher, integrations, SDK, server, and infra docs now describe
   credential bootstrap and local schema reset as local/integration tooling,
   not SDK or migration compatibility.

## Slice 0 Decisions

1. Exact local profile and JDBC URL allowlist where destructive schema reset is
   allowed: `durable-local` plus file-backed SQLite only.
2. File-backed H2 reset is deferred. H2 may remain a test schema target, but it
   is not a destructive reset target in this roadmap.
3. Exact schema hash inputs:
   - platform JDBC migration resources under
     `classpath*:db/migration/control-plane/**/*.sql`
   - server-owned SQL resources under
     `classpath*:db/migration/server-control-plane/**/*.sql`
4. Hash storage location:
   - sidecar file beside SQLite DB:
     `<db-file-name>.schema.sha256`
   - DB metadata table storage remains deferred because it cannot prove
     mismatch detection before opening/migrating the DB in this slice.
5. Startup behavior on mismatch:
   - fail with recreate/reseed message by default
   - delete/recreate only when explicit local reset flag is enabled
6. Credential helper auth path:
   - session login first
   - dev-header fallback is deferred; the implemented helper uses session mode
   - session helper must retain cookie and send `X-Mass-Csrf-Token`
   - proof operator must have `api-key:approve`
7. Credential cache semantics:
   - cache file exists: validate it through `GET /api/v1/api-keys:current`
     before using it
   - stale or invalid cache after local DB reset must be detected; refresh
     through operator login + API-key creation and overwrite the cache only
     when the selected local policy enables refresh, otherwise fail with an
     explicit delete/refresh-cache hint
   - cache file missing: create a new API key and write returned one-time
     secret
   - existing DB credential without cache is not reusable unless a separate
     rotate/recreate decision is made
8. Local catalog/rule preparation path that does not require raw API-key seed
   secrets: `integrations/xa-mass-scenario-launcher/examples/scenario.catalog.seed.json`
   plus the existing rules seed, with operator credentials loaded from the
   server-owned operator credential seed.

## Hard Boundary

- Schema reset is local destructive environment management, not migration.
- Schema reset must run before `JdbcStorageRuntime.create(...)` and before
  server-control-plane Flyway migration.
- Operator login and API-key creation are server API usage by a scenario helper,
  not Java SDK public contract.
- Task launcher config consumes `taskApiKeyFile`; it must not create the key.
